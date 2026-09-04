package com.field360.traker.geo.filter

import com.field360.traker.geo.math.Bearing
import com.field360.traker.geo.math.Haversine
import com.field360.traker.geo.model.Deferred
import com.field360.traker.geo.model.FilterState
import com.field360.traker.geo.model.FixDecision
import com.field360.traker.geo.model.GeoPoint
import com.field360.traker.geo.model.IngestContext
import com.field360.traker.geo.model.MovementStatus
import com.field360.traker.geo.model.PipelineResult
import com.field360.traker.geo.model.Reasons
import com.field360.traker.geo.model.TrackFix
import com.field360.traker.geo.model.TrackPoint
import com.field360.traker.geo.model.Verdict
import com.field360.traker.geo.util.Uuids
import kotlin.math.max
import kotlin.math.min

/**
 * The seven-stage acceptance pipeline.
 *
 * Ported stage-for-stage from `LocationUtil.isKalmanFilteredLocation`
 * (`LocationUtil.kt:172-669`), field-hardened over three generations. Nine named noise
 * classes, nine dedicated gates (spec §8.3).
 *
 * **The stage order is load-bearing and must not change.**
 *  - burst runs first, before anything mutates the last-fix clock;
 *  - the network-fix check runs before motion-state determination, because a network
 *    fix has no hardware speed and would masquerade as stationary;
 *  - recovery runs before the sigma gate, or a post-gap fix burns the reject counter.
 *
 * **Do not "tune down" a stage to fix a symptom another stage owns.** Widening the
 * sigma gate because turns get rejected also lets multipath spikes through; the
 * correct fix is the drift-tolerance scaling in stage 7 (spec §8.3).
 *
 * Stage 6 also carries **bearing-change capture**: a fix whose heading has turned past
 * `IngestContext.bearingChangeCaptureDeg` since the last stored point is stored even
 * when no speed or distance gate asked for it. Without it a corner taken at 25 km/h
 * between two 12 s samples plots as a chord, because both samples are individually
 * unremarkable and the geometry lives entirely in the angle between them (EC-45).
 *
 * Pure: `(fix, past, state, context) -> PipelineResult`. No shared mutable filter, so
 * the concurrency hazard the reference documented and accepted simply cannot occur
 * (SOURCE-AUDIT A3, A6). Replaying the same fixture twice yields a byte-identical
 * decision sequence.
 */
public class AcceptancePipeline(
    private val c: TrackerConstants = TrackerConstants.Default,
) {

    public fun accept(
        fix: TrackFix,
        past: TrackPoint?,
        state: FilterState,
        context: IngestContext,
    ): PipelineResult {
        // The motion layer's verdict, stamped once so every decision row this call
        // produces carries it. `FilterState.motionState` had no writer at all — it sat at
        // its `STOPPED` default and was logged as that constant on a motorway and on a
        // desk alike, which made the decision table's motion column worse than useless.
        // It is a log field and stays one: nothing below reads it (EC-142).
        val stamped = state.copy(motionState = context.motionState)

        // ── Stage 0 — structural validity (EC-23 … EC-28) ────────────────────
        Validation.check(fix, context.mockPolicy)?.let { reason ->
            return reject(fix, stamped, reason, Eval.EMPTY)
        }

        // The run of unconditional accuracy rejections, read from the state as it
        // arrived and cleared on `seen` below. Clearing it here rather than on each of the
        // dozen other exit paths is what makes "consecutive" mean consecutive: only the
        // two gates that own the run write it back, so any other verdict — an accept, a
        // sigma rejection, a departure hold — ends the run by simply not carrying it
        // (EC-139a).
        val priorHardRejectRun = state.hardRejectRun

        // A structurally valid fix was delivered. Recorded here, ahead of every gate that
        // can drop it, because the question this answers is "is the provider still
        // producing fixes" and the answer does not depend on what we go on to decide about
        // this one. `maxOf` keeps it monotonic against a reordered delivery that reached
        // the pipeline directly (EC-140a).
        val seen = stamped.copy(
            lastSeenElapsedNanos = maxOf(stamped.lastSeenElapsedNanos, fix.elapsedRealtimeNanos),
            hardRejectRun = 0,
        )

        // ── Stage 1 — burst ──────────────────────────────────────────────────
        // Keyed on FIX time, not delivery time. Keying it on delivery is what made
        // the reference collapse an entire batch into one fix (A4/A5, EC-30).
        //
        // The window is half-open on BOTH sides, and the lower bound is load-bearing
        // (EC-92b). A negative delta is not "too soon after the last point", it is a
        // clock that restarted underneath a state restored from before a reboot — and
        // treating it as a burst is a trap with no exit, because only `acceptFix`
        // advances this anchor. `ClockGuard` owns that case and resets the filter;
        // this bound is what stops the gate swallowing the evidence first.
        val sinceLastFixNanos = fix.elapsedRealtimeNanos - state.lastFixElapsedNanos
        if (state.lastFixElapsedNanos != 0L &&
            sinceLastFixNanos in 0 until c.burstMs * NANOS_PER_MILLI
        ) {
            return reject(fix, seen, Reasons.BURST, Eval.EMPTY)
        }

        // ── Stage 1 (cont.) — cold start / resume ────────────────────────────
        var s = seen
        if (!s.isInitialised || past == null) {
            if (past == null) {
                // Genuinely nothing to compare against. Accept unconditionally —
                // and ONLY here (EC-50).
                s = KalmanFilter
                    .seed(s, fix.latitude, fix.longitude, fix.accuracy, fix.elapsedRealtimeNanos)
                    .withOrigin(fix.latitude, fix.longitude)
                    .clearMovement()
                return acceptFix(fix, s, Reasons.INIT, Eval.EMPTY, context, past, heading = hardwareHeading(fix))
            }
            // Resume after process death. Re-seed from the STORED anchor using its
            // STORED timestamp, then fall through so THIS fix is judged by every
            // gate. The reference re-seeded with `now`, which routed the first
            // post-restart fix into an unconditional accept (A2, EC-51).
            s = KalmanFilter
                .seed(
                    s,
                    past.latitude,
                    past.longitude,
                    past.accuracy.takeIf { it > 0f } ?: RESUME_FALLBACK_ACCURACY,
                    past.elapsedRealtimeNanos,
                )
                .withOrigin(past.latitude, past.longitude)
                .clearMovement()
        }

        // ── Derived inputs ───────────────────────────────────────────────────
        // Δt comes from the monotonic clock only. It therefore cannot be negative,
        // which is why the reference's negative-Δt branch (an unconditional accept)
        // is absent here — it is unreachable by construction (A1, EC-42).
        val dtSec = (fix.elapsedRealtimeNanos - past.elapsedRealtimeNanos).toFloat() / NANOS_PER_SECOND
        val distanceMoved = Haversine.metres(past.latitude, past.longitude, fix.latitude, fix.longitude)
        val calcSpeed = if (dtSec >= 1f) (distanceMoved / dtSec).toFloat() else 0f
        val heading = headingOf(fix, past, distanceMoved)
        // How long the provider was actually silent, as opposed to how long the filter has
        // been stale. Read from `state`, never from `s`: `seen` has already stamped this
        // fix, so measuring against `s` would answer zero every time. A zero stamp means
        // nothing has been seen yet — a fresh session, or the far side of a reboot — and
        // that is honestly an unbounded gap rather than a short one (EC-140a).
        val sinceSeenNanos = fix.elapsedRealtimeNanos - state.lastSeenElapsedNanos
        val deliveryGapSec = if (state.lastSeenElapsedNanos == 0L || sinceSeenNanos < 0L) {
            // Nothing seen yet, or a stamp belonging to a timeline that no longer exists.
            // Both are an *absence* of evidence that fixes have been arriving, and the
            // honest reading of that is an unbounded gap — which is exactly the behaviour
            // that shipped before this term existed. Reading a negative step as "no gap"
            // would be the opposite: it would silence recovery for the rest of the session
            // on a device whose clock rewound, which is the one case recovery is for.
            // `ClockGuard` normally resets the whole state at a reboot boundary before the
            // pipeline ever sees this, so the guard is a floor under that, not a duplicate
            // of it (EC-92a, EC-140a).
            Float.MAX_VALUE
        } else {
            sinceSeenNanos.toFloat() / NANOS_PER_SECOND
        }

        // ── Stage 1.5 — network-fix (NLP) authenticity ───────────────────────
        val looksLikeNlp = fix.looksLikeNetworkFix
        val nlpBypassed = looksLikeNlp &&
            fix.accuracy > c.accuracyNlpReject &&
            nlpBypass(fix, past, s, dtSec, distanceMoved)
        if (looksLikeNlp && fix.accuracy > c.accuracyNlpReject && !nlpBypassed) {
            // Counted into the same run as stage 3.5, so a device alternating between the
            // two still reaches the bound — but deliberately never *bridged* here. A fix
            // stage 3.5 drops has a position the chip computed and an error circle around
            // it; a network centroid has neither, and admitting one because several
            // preceded it would hand the polyline exactly the Wi-Fi teleports this gate
            // exists to catch (EC-32). What rescues the honest case is `looksLikeNetworkFix`
            // no longer mistaking a GNSS fix for a centroid, not an amnesty here.
            return reject(
                fix,
                s.copy(hardRejectRun = priorHardRejectRun + 1),
                Reasons.NLP_FALLBACK,
                Eval.EMPTY,
            )
        }

        // ── Stage 2 — motion-state determination ─────────────────────────────
        val eval = evaluateMotion(fix, past, s, dtSec, distanceMoved, calcSpeed, context)

        // ── Stage 3 — physical sanity ────────────────────────────────────────
        if (dtSec > 0f) {
            val instantKmph = (distanceMoved / dtSec) * MPS_TO_KMPH
            if (instantKmph > c.speedMaxPhysicalKmph) {
                return reject(fix, s, Reasons.IMPOSSIBLE_SPEED, eval)
            }
        }

        // ── Stage 3.5 — unconditional accuracy ceiling while moving ──────────
        // Deliberately ahead of recovery and the sigma gate, because both of them can
        // *accept* a fix: recovery re-anchors onto one, and the forced reset re-seeds onto
        // anything up to `accuracyMaxVehicular`. A ceiling those stages can route around is
        // not a ceiling, and the field capture stored a 66 m fix with a 173° reversal on
        // exactly that route.
        //
        // Every other accuracy bound in this pipeline is chosen by a motion class that the
        // fix's own displacement helped decide (see `evaluateMotion`: `effectiveSpeed` folds
        // in `calcSpeed`), so a bigger positioning error buys a looser ceiling. This one is
        // unconditional so that loop cannot close (EC-139).
        //
        // Unconditional, but no longer unbounded. The ceiling has always been right about
        // the individual fix and silent about the run: on hardware whose whole accuracy
        // distribution sits above it, every fix is dropped and the polyline draws one
        // chord from the last fix that met the bar to the next one that does. That chord
        // is not a more accurate answer than a run of coarse points — it is a road the
        // device never travelled, drawn with no uncertainty at all. So after
        // `maxHardRejectRun` consecutive drops the next *reachable* fix is admitted and
        // labelled (EC-139a).
        var bridged = false
        if ((eval.isVehicular || eval.isMoving) && fix.accuracy > c.accuracyMovingMax && !nlpBypassed) {
            val run = priorHardRejectRun + 1
            bridged = c.maxHardRejectRun > 0 &&
                run >= c.maxHardRejectRun &&
                reachable(fix, past, s, dtSec, distanceMoved, c.bridgeFlatM)
            if (!bridged) {
                return reject(fix, s.copy(hardRejectRun = run), Reasons.POOR_ACCURACY, eval)
            }
        }

        // ── Stage 4 — tiered recovery (runs BEFORE the sigma gate) ───────────
        recoveryOutcome(fix, s, eval, distanceMoved, context, past, deliveryGapSec)?.let { return it }
        s = s.copy(recoveryPending = null)

        // ── Stage 5 — 3-sigma gate + forced reset ────────────────────────────
        // q for THIS fix, computed before the gate. The reference read whichever q
        // the last *accepted* fix left behind, so a stationary q of 0.0001 could
        // tighten the gate for the first fix of a drive to ~0 (A7).
        val q = processNoise(eval)
        val sigma = KalmanFilter.predictSigma(s, fix.elapsedRealtimeNanos, q)
        val predictedDelta = gateDistance(s, fix, q)
        val threshold = gateThreshold(fix, s, eval, sigma, dtSec)

        // Two process noises, deliberately. The gate above uses the straight-line q, so a
        // corner cannot widen it into an amnesty for fixes that would otherwise be
        // rejected; the correction below uses the cornering q, because that is where the
        // constant-velocity model is provably wrong and should stop being trusted.
        // `maxOf` so a highway turn does not inherit the highway q's tighter value (EC-45a).
        val qTrack = if (isTurning(s, eval, heading)) maxOf(q, c.qAccelTurning) else q

        if (predictedDelta > threshold) {
            val rejectCount = s.consecutiveRejectCount + 1
            val gated = s.copy(consecutiveRejectCount = rejectCount)
            if (rejectCount >= maxRejects(eval)) {
                // Junk beyond rescue — resetting onto it would teleport the user.
                if (fix.accuracy > c.accuracyMaxVehicular) {
                    return reject(fix, gated, Reasons.SIGMA_JUNK_FAIL, eval, sigma, threshold)
                }
                // Forced reset: THE mechanism that guarantees the filter can never
                // wedge permanently. Do not soften its constants (EC-43).
                val reseeded = KalmanFilter
                    .seed(gated, fix.latitude, fix.longitude, fix.accuracy, fix.elapsedRealtimeNanos)
                    .copy(consecutiveRejectCount = 0)
                // The re-seed above is unconditional and stays that way: EC-43's promise
                // is that the filter can never wedge, and the seed is what keeps it — not
                // the store. Whether this fix also becomes a *vertex* is the separate
                // question, and a leg no vehicle could have driven is not one. Two
                // rejections is all it takes to reach here, so on a device that jumps this
                // branch was the pipeline's own teleport generator: it re-anchored onto the
                // outlier and then stored it, which is the "point far away" in the field
                // reports. The envelope is the vehicular leg test stage 6 already applies,
                // 45 m/s plus 200 m, so nothing a real drive produces is affected.
                val legPlausible =
                    reachable(fix, past, s, dtSec, distanceMoved, c.forcedResetFlatM)
                return if (distanceMoved > c.distMinMove && legPlausible) {
                    acceptFix(
                        fix, reseeded, Reasons.SIGMA_FORCED_RESET, eval, context, past,
                        sigma, threshold, heading = heading,
                    )
                } else {
                    reject(fix, reseeded, Reasons.SIGMA_FORCED_RESET, eval, sigma, threshold)
                }
            }
            return reject(fix, gated, Reasons.SIGMA_GATE_OUTLIER, eval, sigma, threshold)
        }

        // ── Stage 6 — heuristic acceptance branches ──────────────────────────
        // The turn branch is a fallback, never an override: when a speed or distance gate
        // already wants this fix, its reason is the more informative one to log. It only
        // decides fixes the other branches would have dropped — the corner taken slowly
        // enough to read as neither vehicular nor a walk (EC-45).
        // Whether non-GNSS hardware is entitled to overrule stage 6 on this fix. Computed
        // once, because it governs both the branches below and the corner-anchor
        // counterfactual — a fix the sensors say never happened is not a corner either.
        val stillnessSuppresses = stillnessSuppresses(eval, context)

        // A bridged fix has already been judged twice — by stage 3.5's ceiling, which
        // said no, and by the run bound and reachability envelope, which together
        // overruled it. Stage 6 asks a different question ("is this fix significant?") and
        // its answer cannot reinstate a rejection that has already been overruled, so the
        // label that belongs in the decision log is the one explaining why this fix
        // survived at all. It is deliberately not `Vehicular`: a stretch plotted from
        // coarse fixes must never read back as a stretch the device measured well
        // (EC-139a).
        val reason = if (bridged) {
            Reasons.ACCURACY_BRIDGE
        } else {
            heuristicReason(fix, past, s, eval, distanceMoved, dtSec, looksLikeNlp)
                ?: bearingChangeReason(fix, s, eval, heading, context)
                ?: return heuristicRejection(
                    fix, past, s, eval, context, qTrack, sigma, threshold, predictedDelta, distanceMoved, heading,
                    stillnessSuppresses = stillnessSuppresses,
                )
        }

        // ── Stage 6.5 — stillness veto (EC-142) ──────────────────────────────
        if (stillnessSuppresses && reason in VETOABLE_REASONS) {
            // Through the same departure-collapse check the heuristic gate's rejections
            // take, so the two rejection paths cannot disagree about an unlatched tally.
            val settled = collapseDeparture(fix, s)
            return reject(fix, settled, Reasons.STILLNESS_VETO, eval, sigma, threshold)
        }

        // ── Stage 7 — Q/R tuning, persistence, routing ───────────────────────
        return finalise(
            fix = fix,
            past = past,
            state = s,
            eval = eval,
            context = context,
            reason = reason,
            qTrack = qTrack,
            sigma = sigma,
            threshold = threshold,
            predictedDelta = predictedDelta,
            distanceMoved = distanceMoved,
            heading = heading,
        )
    }

    /**
     * The heuristic gate's rejection, plus — when the host asked for it — the accept this
     * fix would have had (EC-45e).
     *
     * **This is the only soft rejection in the pipeline, and that is what makes the seam
     * safe.** Every gate above it says the fix is wrong: invalid, a duplicate, a network
     * fallback, physically impossible, too imprecise to place, or outside the filter's
     * three-sigma gate. This one says only that the fix is *unremarkable* — it moved too
     * little, too slowly, and turned too gently to be worth a vertex on its own. Offering
     * a second opinion on any of the others would be an amnesty for junk, which is exactly
     * what this file's header forbids. Offering one here is a second opinion on
     * significance, and significance is the one thing a later fix can genuinely change.
     *
     * The counterfactual is computed by running the real stage 7, not by approximating it.
     * Anything less would hand the caller a point and a filter state the pipeline would
     * never itself have produced, and the whole value of the seam is that adopting the
     * deferred branch is indistinguishable from the gate having accepted the fix outright.
     *
     * A [finalise] that ends in a *skip* — the departure ladder holding a fix back, or
     * drift suppression re-anchoring on it — yields no point and therefore nothing to
     * defer. That is the correct answer: those stages exist to stop stationary drift
     * becoming geometry, and a corner is not a reason to overrule them.
     */
    @Suppress("LongParameterList")
    private fun heuristicRejection(
        fix: TrackFix,
        past: TrackPoint,
        state: FilterState,
        eval: Eval,
        context: IngestContext,
        qTrack: Float,
        sigma: Float,
        threshold: Float,
        predictedDelta: Double,
        distanceMoved: Double,
        heading: Float?,
        stillnessSuppresses: Boolean,
    ): PipelineResult {
        val settled = collapseDeparture(fix, state)
        val rejected = reject(fix, settled, Reasons.HEURISTIC_GATE, eval, sigma, threshold)
        if (!context.cornerAnchorCapture) return rejected
        // Offering the counterfactual would be offering a vertex on a corner the device
        // never turned. The gate's own rejection stands, with its own reason (EC-142).
        if (stillnessSuppresses) return rejected

        val kept = finalise(
            fix = fix,
            past = past,
            state = settled,
            eval = eval,
            context = context,
            reason = Reasons.CORNER_ANCHOR,
            qTrack = qTrack,
            sigma = sigma,
            threshold = threshold,
            predictedDelta = predictedDelta,
            distanceMoved = distanceMoved,
            heading = heading,
        )
        val point = kept.point ?: return rejected

        return rejected.copy(
            deferred = Deferred(decision = kept.decision, state = kept.state, point = point),
        )
    }

    /**
     * Unwinds a departure tally that never became a departure (EC-142).
     *
     * Settle detection in [reject] does this for a departure that already *latched*;
     * nothing did it for one still climbing the ladder, and the gap mattered because of
     * where the ladder runs. Stage 7-A is reached only by a fix that measured as moving,
     * so an excursion's outward leg is counted and the drift back to the anchor — which is
     * unremarkable, and rejected here — is not seen at all. `departCount` and the net
     * high-water mark therefore survived arbitrarily long stretches of stillness, and two
     * centroid hops minutes apart satisfied a test written to require two *consecutive*
     * advancing fixes. That is what stored points from a phone that never left a desk even
     * after the single-fix confirmation was closed.
     *
     * Back inside [TrackerConstants.persistMinNet] of the origin is the unambiguous half
     * of "wandered out and came back": the excursion ended where it began. Someone paused
     * at a crossing mid-departure is still out at their high-water mark and keeps their
     * tally — only a return to the anchor clears it.
     *
     * The origin itself is left where it is. This is a rejected fix; it is trustworthy
     * enough to say the excursion collapsed, which is a fact about the *previous* fixes,
     * and not trustworthy enough to become the new anchor.
     */
    private fun collapseDeparture(fix: TrackFix, state: FilterState): FilterState {
        if (state.movingMode || state.departCount == 0) return state
        val origin = state.origin ?: return state
        val net = Haversine.metres(origin.latitude, origin.longitude, fix.latitude, fix.longitude)
        if (net >= c.persistMinNet) return state
        return state.copy(departCount = 0, prevNetMeters = 0f)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Stage 1.5
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * @return true when a poor network fix should be kept anyway — the tunnel and
     *   parking-garage case, where GNSS is gone but the vehicle is genuinely still
     *   moving (EC-32).
     *
     * **Every input here predates the fix being judged.** That is the whole rule, and
     * getting it wrong is what produced a 287 m backward spike on a real drive: the
     * bypass used to ask whether *this fix's own displacement* implied vehicular speed,
     * which made it self-justifying — a bigger positioning error computed as a higher
     * speed, and a higher speed was exactly the evidence the bypass wanted. A 287 m
     * network-fix error read as 24 m/s of driving and sailed through, taking the
     * polyline backwards and then forwards again (EC-32a).
     *
     * The replacement asks two independent questions:
     *
     *  - **Was the vehicle moving?** From the filter's own velocity estimate and the
     *    last stored point — both earned from earlier fixes, neither touched by this one.
     *  - **Could it have got here?** The displacement has to be within reach of that
     *    prior speed. A tunnel at 15 m/s covers ~180 m in twelve seconds and passes; a
     *    287 m jump on the back of a 10 m/s estimate does not.
     *
     * Recent *hardware* vehicular evidence is still required on top, and the `!= 0L`
     * guard on it is load-bearing under a monotonic clock: 0 means "boot", not "long
     * ago", so without it the window would be open for the first ten minutes of every
     * session and the network-fix rejection would silently do nothing. (The reference
     * could omit it only because its 0 meant 1970 on a wall clock.)
     */
    private fun nlpBypass(
        fix: TrackFix,
        past: TrackPoint,
        state: FilterState,
        dtSec: Float,
        distanceMoved: Double,
    ): Boolean {
        if (state.lastHwVehicularNanos == 0L) return false
        val sinceHardware = fix.elapsedRealtimeNanos - state.lastHwVehicularNanos
        if (sinceHardware >= c.nlpBypassWindowMs * NANOS_PER_MILLI) return false

        // Best estimate of how fast we were going *before* this fix arrived. The filter's
        // velocity is smoothed over many corrections and cannot be spiked by one bad fix;
        // the stored point covers the case where the filter was recently re-seeded and
        // has not re-learned a velocity yet.
        val priorSpeed = max(KalmanFilter.speedOf(state), past.speedMps)
        if (priorSpeed < c.speedVehicularMin) return false

        val reachable = priorSpeed * dtSec * c.nlpBypassSpeedFactor + c.nlpBypassFlatM
        return distanceMoved <= reachable
    }

    /**
     * @return true when a fix some gate has already judged badly is at least *reachable*
     *   from where the device was and how fast it was going (EC-139a, EC-43a).
     *
     * The bound that keeps a rescue from becoming a teleport. Two stages call it, and both
     * are about to overrule a rejection: the accuracy bridge, which admits that the fix is
     * imprecise, and the sigma gate's forced reset, which admits that the filter has to be
     * re-seeded onto something. Neither is entitled to say the device could be *anywhere*.
     * Without this, the very run that earns a bridge would let the worst fix of that run
     * through — a 300 m multipath excursion arriving fourth in a row is exactly as eligible
     * as a 35 m one, and plotting it is the spike this whole change exists to remove.
     *
     * **Every input is independent of this fix's own displacement**, which is the rule
     * [nlpBypass] follows and for the same reason (EC-32a). The filter's velocity is
     * smoothed across many corrections and cannot be spiked by one bad measurement;
     * `past.speedMps` belongs to a point already stored. Deriving the speed from the
     * displacement being judged would make the test self-justifying — a larger error
     * computes as a higher speed, and a higher speed is precisely the permission being
     * asked for.
     *
     * The GNSS chip's own Doppler is included, and that is *not* a hole in the rule. It is
     * measured from the carrier frequency shift, not by differencing two positions, so a
     * wider error circle does not produce a larger speed and the self-justifying loop
     * cannot close through it. It is also what makes this test work at all on the hardware
     * that needs it: the first bridge of a session is judged against a filter that has
     * learned no velocity yet and a `past` seeded by `Init` with none, so without the
     * Doppler term the flat allowance would be the entire envelope and a drive that has
     * been rejecting fixes for a minute could never clear it. ([nlpBypass] cannot use the
     * same term for the plain reason that a network centroid has no Doppler to offer.)
     *
     * @param flatAllowanceM the floor under the envelope, which carries a standing start
     *   where the prior speed is legitimately zero and the device has still moved by the
     *   time of the next fix. The two callers pass deliberately different values — see
     *   [TrackerConstants.bridgeFlatM] and [TrackerConstants.forcedResetFlatM].
     */
    private fun reachable(
        fix: TrackFix,
        past: TrackPoint,
        state: FilterState,
        dtSec: Float,
        distanceMoved: Double,
        flatAllowanceM: Double,
    ): Boolean {
        val priorSpeed = maxOf(
            KalmanFilter.speedOf(state),
            past.speedMps,
            if (fix.hasSpeed) fix.speedMps else 0f,
        )
        return distanceMoved <= priorSpeed * dtSec * c.bridgeSpeedFactor + flatAllowanceM
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Stage 2
    // ─────────────────────────────────────────────────────────────────────────

    private fun evaluateMotion(
        fix: TrackFix,
        past: TrackPoint,
        state: FilterState,
        dtSec: Float,
        distanceMoved: Double,
        calcSpeed: Float,
        context: IngestContext,
    ): Eval {
        val hwSpeed = if (fix.hasSpeed) fix.speedMps else 0f
        val isHardwareStationary = hwSpeed < c.speedStationaryMax
        val isSignalGap = dtSec > c.signalGapSec

        // Trust the GNSS chip's Doppler over position deltas to decide "is the user
        // moving" — position deltas while stationary are noise by definition. The
        // three-way choice below is the whole of defence #1 (spec §8.1).
        val effectiveSpeed = when {
            // Chip says still but we genuinely covered ground: believe the ground.
            isHardwareStationary && calcSpeed > c.speedGpsTrust ->
                max(hwSpeed, calcSpeed * GPS_TRUST_DERATE)
            // Phantom Doppler: 3–8 m/s reported with ~zero displacement, observed on
            // the Moto G34 family. Believe the displacement (EC-36).
            //
            // "~zero displacement" is the actual test, and it has to be, because the
            // obvious proxy — a low *computed* speed — is a lie after any long gap.
            // `calcSpeed` divides by the time since the last STORED point, so a car
            // pulling away from a five-minute stop computes 43 m / 328 s = 0.13 m/s
            // while the chip correctly reports 9.15. Using the proxy zeroed a genuine
            // departure and dropped the first fix of the drive (EC-36a).
            hwSpeed > c.speedVehicularMin &&
                calcSpeed < c.speedWalkingMin &&
                distanceMoved < c.distStationaryWobble -> calcSpeed
            else -> max(hwSpeed, calcSpeed)
        }

        val isVehicular = effectiveSpeed > c.speedVehicularMin

        // Indoor GPS wobble is 30–70 m at a 60 s cadence. Widen the guard when the
        // fix carries no hardware speed at all, which is the indoor case (EC-38).
        val wobbleGuard = if (isHardwareStationary && !fix.hasSpeed) {
            c.distStationaryWobbleNoHwSpeed
        } else {
            c.distStationaryWobble
        }

        // A walking pace the chip reports AND the ground confirms. Both halves are
        // load-bearing: the Doppler alone is the phantom-speed failure (EC-36), and
        // displacement alone is indoor wobble (EC-38). Together they are a walk.
        //
        // Without this a steady walker is classified stationary at any cadence faster
        // than ~30 s, because 1.3 m/s clears neither the 2 m/s `speedVirtuallyStopped`
        // bar nor the 40 m wobble guard — a 12 s fix covers 16 m. The bars were set for
        // 60 s sampling, where a walker delivers 78 m and clears the guard easily; every
        // faster cadence this SDK now uses falls through the gap (EC-39b).
        val corroboratedWalk = fix.hasSpeed &&
            hwSpeed >= c.speedWalkingMin &&
            calcSpeed >= c.speedWalkingMin

        var isMoving = (
            effectiveSpeed > c.speedWalkingMin &&
                (effectiveSpeed >= c.speedVirtuallyStopped || distanceMoved > wobbleGuard)
            ) || (
            distanceMoved > c.distStationaryWobble &&
                !isHardwareStationary &&
                calcSpeed > c.speedStationaryMax
            ) || corroboratedWalk

        // Stage 2a — pedestrian corroboration. Skipped entirely when no pedometer.
        // Steps are physical evidence that multipath cannot fabricate (EC-133).
        var stepVeto = false
        val steps = context.stepsSinceLastPoint
        if (steps != null && isHardwareStationary &&
            distanceMoved >= c.distMinMove && distanceMoved <= STEP_CORROBORATION_MAX_M
        ) {
            if (steps == 0) {
                // Displacement without a single step cannot be walking.
                isMoving = false
                stepVeto = true
            } else if (steps >= STEP_CORROBORATION_MIN_STEPS && distanceMoved > c.distJitter) {
                // Indoor walks often yield no hardware speed at all; the pedometer
                // is the only witness.
                isMoving = true
            }
        }

        // Walked away during a blackout and is now stationary somewhere new (EC-47).
        val impliedBlackoutSpeed = if (dtSec > 0f) (distanceMoved / dtSec).toFloat() else 0f
        val isArrivalTransition = isSignalGap &&
            isHardwareStationary &&
            past.speedMps >= BLACKOUT_PAST_SPEED_MIN &&
            impliedBlackoutSpeed >= c.speedStationaryMax &&
            impliedBlackoutSpeed <= c.speedVehicularMin

        return Eval(
            hwSpeed = hwSpeed,
            calcSpeed = calcSpeed,
            effectiveSpeed = effectiveSpeed,
            isHardwareStationary = isHardwareStationary,
            isSignalGap = isSignalGap,
            isVehicular = isVehicular,
            isMoving = isMoving,
            isArrivalTransition = isArrivalTransition,
            distanceMoved = distanceMoved,
            dtSec = dtSec,
            stepVeto = stepVeto,
            // Splits the moving-mode Q: a poor fix gets a small q so the prediction
            // stays tight, a good one gets a large q so the gate can follow real
            // movement (spec §7 stage 7).
            accuracyPoor = fix.accuracy > Q_MOVING_ACCURACY_SPLIT,
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Stage 4
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * @return a terminal result when recovery handled the fix, else `null`.
     *
     * Every accept here is a hard re-anchor across a gap, so the captured heading is
     * seeded from hardware only: a heading derived from displacement would span the gap
     * and describe a leg that was never travelled as drawn.
     */
    @Suppress("LongParameterList")
    private fun recoveryOutcome(
        fix: TrackFix,
        state: FilterState,
        eval: Eval,
        distanceMoved: Double,
        context: IngestContext,
        past: TrackPoint,
        /** Seconds since the provider last delivered a fix, whatever was decided about it. */
        deliveryGapSec: Float,
    ): PipelineResult? {
        // A held candidate from a previous fix: confirm it, keep holding, or drop it and
        // fall through.
        state.recoveryPending?.let { pending ->
            // Time since the candidate was held. The hold seeds the filter clock to the
            // candidate's own timestamp and nothing advances it while the hold stands, so
            // this needs no separate stored field.
            val heldForSec = (fix.elapsedRealtimeNanos - state.elapsedNanos).toFloat() / NANOS_PER_SECOND
            val nearPending = Haversine.metres(pending.latitude, pending.longitude, fix.latitude, fix.longitude)
            if (nearPending <= c.recoveryConfirmNear && heldForSec <= c.recoveryHoldMaxSec) {
                // Confirmation has to be worth more than the thing it confirms. Without an
                // accuracy bar here, two consecutive multipath fixes 60 m apart corroborate
                // each other and the pipeline re-anchors onto the pair — the hold was doing
                // nothing but delaying the same bad anchor by one fix (EC-140).
                if (fix.accuracy >= c.accuracyRecoveryTrust) {
                    // Keep holding the ORIGINAL candidate: re-seeding onto this fix would
                    // walk the anchor along a chain of poor fixes, one hold at a time.
                    return skip(fix, state, Reasons.RECOVERY_HELD, eval)
                }
                val confirmed = KalmanFilter
                    .seed(state, fix.latitude, fix.longitude, fix.accuracy, fix.elapsedRealtimeNanos)
                    .withOrigin(fix.latitude, fix.longitude)
                    .clearMovement()
                    .copy(recoveryPending = null, consecutiveRejectCount = 0)
                return acceptFix(
                    fix, confirmed, Reasons.RECOVERY_CONFIRMED, eval, context, past,
                    heading = hardwareHeading(fix),
                )
            }
            // Snapped back somewhere else, or nothing corroborated it in time — the held fix
            // was noise. Clear and continue. An expired candidate is never promoted: "no
            // better fix arrived" is not evidence that this one was right.
        }

        // Measured against the FILTER clock, not the last stored point: the filter is
        // what has gone stale during a gap.
        val processingGapSec =
            (fix.elapsedRealtimeNanos - state.elapsedNanos).toFloat() / NANOS_PER_SECOND
        val isProcessingGap = processingGapSec > c.signalGapSec

        // Recovery is for a blackout, and a blackout means fixes stopped *arriving*. The
        // filter clock alone cannot tell that apart from fixes arriving and being
        // rejected, because only an accept advances it — so a run of accuracy rejections
        // aged the filter past `signalGapSec` and manufactured a gap that never happened.
        // The cost was not a wasted branch: `RECOVERY_RESET` hard re-anchors and calls
        // `clearMovement()`, which drops the captured heading (so bearing-change capture
        // goes blind and the next corner plots as a chord) and restarts the departure
        // ladder (so the next ~100 m stores nothing). Each manufactured gap therefore made
        // the next one more likely — the loop behind the field reports of a track that
        // "jumps and then draws a straight line" (EC-140a).
        //
        // On a genuine blackout no fixes arrive, so this term is true whenever the old
        // condition was, and recovery behaves exactly as it always has.
        val isDeliveryGap = deliveryGapSec > c.signalGapSec

        val recoveryNeeded = isDeliveryGap && fix.accuracy < c.accuracyMedium && (
            (processingGapSec > c.recoveryTimeoutSec && distanceMoved > c.distRecoveryWakeup) ||
                (isProcessingGap && eval.isVehicular && distanceMoved > c.distRecoveryVehicular) ||
                (isProcessingGap && distanceMoved > c.distRecoveryWakeup && fix.accuracy < c.accuracyHigh)
            )
        if (!recoveryNeeded) return null

        // Distance is evidence that *something* moved, never evidence of where it moved to,
        // and after a blackout there is nothing else in the session to check this fix
        // against. So the re-anchor now needs both: a leg long enough to mean travel, and a
        // fix precise enough to be worth anchoring on. Anything else is held for
        // confirmation instead of plotted (EC-140).
        val trustworthy = fix.accuracy < c.accuracyRecoveryTrust
        val immediate = trustworthy && (
            distanceMoved >= c.recoveryImmediateDist ||
                (isProcessingGap && eval.isVehicular && distanceMoved > c.distRecoveryVehicular)
            )

        return if (immediate) {
            // Strong evidence of real travel — re-seed outright.
            val reset = KalmanFilter
                .seed(state, fix.latitude, fix.longitude, fix.accuracy, fix.elapsedRealtimeNanos)
                .withOrigin(fix.latitude, fix.longitude)
                .clearMovement()
                .copy(recoveryPending = null, consecutiveRejectCount = 0)
            acceptFix(fix, reset, Reasons.RECOVERY_RESET, eval, context, past, heading = hardwareHeading(fix))
        } else {
            // Weak evidence — warm the filter but store nothing, and require a second
            // fix within 60 m to confirm. A lone spurious post-gap fix never plots.
            val held = KalmanFilter
                .seed(state, fix.latitude, fix.longitude, fix.accuracy, fix.elapsedRealtimeNanos)
                .copy(recoveryPending = GeoPoint(fix.latitude, fix.longitude))
            skip(fix, held, Reasons.RECOVERY_HELD, eval)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Stage 5 helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * How far this fix is from where the filter expected it — the quantity the 3-sigma
     * gate judges.
     *
     * **Whichever prediction is closer wins**, and that choice is the whole reason a
     * constant-velocity model is safe here.
     *
     *  - On a straight road the extrapolated position is right and the last corrected one
     *    lags by a full leg. Taking the extrapolation is what removes the lag that
     *    rejected one fix in four on a steady 40 km/h drive (EC-44a).
     *  - Through a real corner the extrapolation overshoots down the old heading. Taking
     *    the last corrected position instead reproduces the scalar filter's behaviour
     *    exactly — which is the answer to the post-turn rejection cascade the CTRV design
     *    was rejected for (EKF-DESIGN-REVIEW §C1).
     *
     * So the gate is never wider than the old one *and* never lags on a straight. The
     * filter cannot be worse than what it replaced at any single fix.
     */
    private fun gateDistance(state: FilterState, fix: TrackFix, q: Float): Double {
        val fromCorrected = Haversine.metres(state.lat, state.lng, fix.latitude, fix.longitude)
        if (!state.isInitialised) return fromCorrected

        val predicted = KalmanFilter.predict(state, fix.elapsedRealtimeNanos, q)
        val fromPredicted = Haversine.metres(
            predicted.latitude, predicted.longitude, fix.latitude, fix.longitude,
        )
        return min(fromCorrected, fromPredicted)
    }

    private fun gateThreshold(
        fix: TrackFix,
        state: FilterState,
        eval: Eval,
        sigma: Float,
        dtSec: Float,
    ): Float {
        val baseGate = c.sigmaMultiplier * sigma + fix.accuracy * c.gateAccuracyFactor + c.gateFlatTermM
        // Widen in proportion to how far the user could legitimately have gone. A turn
        // never exceeds this, which is what stops the post-turn rejection cascade (EC-44).
        val speedExpansion = eval.effectiveSpeed * dtSec * c.gateSpeedExpansionFactor
        val maxGate = when {
            eval.isVehicular -> c.gateMaxVehicular
            eval.isMoving -> c.gateMaxMoving
            else -> c.gateMaxStationaryBase + state.consecutiveRejectCount * c.gateMaxStationaryPerReject
        }
        return (baseGate + speedExpansion).coerceIn(c.gateMinM, maxGate)
    }

    private fun maxRejects(eval: Eval): Int = when {
        eval.isSignalGap -> c.maxRejectsSignalGap
        eval.isHardwareStationary && eval.effectiveSpeed < c.speedVirtuallyStopped -> c.maxRejectsStationary
        eval.isVehicular -> c.maxRejectsVehicular
        else -> c.maxRejectsWalking
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Stage 6.5 — stillness veto (EC-142)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * @return true when non-GNSS hardware may overrule stage 6 and drop this fix.
     *
     * Every stationary defence above this one is statistical, because position is the only
     * evidence they have: wobble guards, the R-penalty, the departure ladder. Each
     * therefore has an escape hatch sized for a real journey, and indoor multipath is
     * capable of finding them — which is how a phone on a desk keeps producing points.
     * This is the one gate with evidence of a different kind, so it is the one allowed to
     * close them.
     *
     * The four conditions are a conjunction, and each is here to stop this stage becoming
     * the incumbent's worst failure — a motion API saying `STILL` through a 17-minute
     * drive on exactly this SDK's target hardware (EC-53). No single witness can veto:
     *
     *  - **The host asked for it.** Off by default; `MotionConfig.suppressWhileStationary`.
     *  - **The engine's own verdict agrees.** A fix that measured as moving or vehicular is
     *    never offered to the sensors for a second opinion. Displacement that stage 2 read
     *    as travel outranks any accelerometer window, always.
     *  - **The GNSS chip agrees.** Doppler under [TrackerConstants.speedStationaryMax] is a
     *    hardware measurement multipath cannot fabricate; a chip reporting speed is a chip
     *    disagreeing, and it wins.
     *  - **The pedometer agrees, or is absent.** Steps are physical evidence of a walk the
     *    Doppler often misses indoors (EC-133). One counted step withdraws the veto.
     *
     * The producer supplies the fifth: `context.stillnessVeto` is required to be `false`
     * whenever its own evidence is missing, stale or gapped, and to expire on a bound so a
     * wedged sensor degrades to the old behaviour rather than silencing the track.
     */
    private fun stillnessSuppresses(eval: Eval, context: IngestContext): Boolean {
        if (!context.stillnessVeto) return false
        if (eval.isMoving || eval.isVehicular) return false
        if (!eval.isHardwareStationary) return false
        if ((context.stepsSinceLastPoint ?: 0) > 0) return false
        return true
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Stage 6
    // ─────────────────────────────────────────────────────────────────────────

    private fun heuristicReason(
        fix: TrackFix,
        past: TrackPoint,
        state: FilterState,
        eval: Eval,
        distanceMoved: Double,
        dtSec: Float,
        looksLikeNlp: Boolean,
    ): String? = when {
        eval.isVehicular -> {
            val maxAcc = when {
                eval.isSignalGap -> c.accuracyMaxVehicular
                looksLikeNlp -> c.accuracyMedium
                eval.hwSpeed < c.speedVirtuallyStopped -> VEHICULAR_LOW_HW_ACCURACY
                else -> c.accuracyMaxVehicular
            }
            val legPlausible = distanceMoved < c.vehicularLegSpeedCap * dtSec + c.vehicularLegFlatM
            if (fix.accuracy < maxAcc && legPlausible) Reasons.VEHICULAR else null
        }

        eval.isMoving -> {
            val accLimit = when {
                eval.isArrivalTransition -> c.accuracyMedium
                eval.isHardwareStationary -> c.accuracyStationaryLimit
                else -> c.accuracyMedium
            }
            val moved = distanceMoved > c.distMinMove || dtSec > MOVING_MIN_DT_SEC
            if (fix.accuracy < accLimit && moved) Reasons.MOVING_WALKING else null
        }

        // Stationary. Accept only for one of these reasons, in precedence order.
        else -> {
            val netFromOrigin = state.origin?.let {
                Haversine.metres(it.latitude, it.longitude, fix.latitude, fix.longitude)
            } ?: 0.0

            val isArrival = past.speedMps > ARRIVAL_PAST_SPEED_MIN &&
                eval.hwSpeed < ARRIVAL_PAST_SPEED_MIN &&
                fix.accuracy < c.accuracyHigh &&
                distanceMoved < c.distStationaryWobble

            val isGpsRecovery =
                (distanceMoved > c.recoveryImmediateDist && fix.accuracy < c.accuracyHigh) ||
                    (distanceMoved > c.distGpsRecoveryLarge && fix.accuracy < GPS_RECOVERY_LARGE_ACCURACY) ||
                    (eval.isSignalGap && distanceMoved > c.distRecoveryWakeup && fix.accuracy < c.accuracyNlpReject)

            val isBlackoutArrival = eval.isArrivalTransition &&
                fix.accuracy < c.accuracyMedium &&
                distanceMoved > c.distStationaryWobble

            // ARRIVAL, not progress. The speed floor is the whole meaning of the word and
            // it was missing: without it this fired on every fix of a walk that had
            // already departed, and its routing in stage 7-B re-anchors the origin and
            // clears `movingMode` — so a walk restarted the departure ladder every few
            // fixes and plotted as a handful of scattered points instead of a path
            // (EC-39c).
            val isWalkArrival = state.departCount >= 1 &&
                netFromOrigin > c.persistMinNet &&
                fix.accuracy < c.accuracyHigh &&
                eval.effectiveSpeed < c.speedWalkingMin

            val isHeartbeat = dtSec > c.heartbeatSec &&
                fix.accuracy < c.accuracyMedium &&
                distanceMoved < c.distRecoveryWakeup

            when {
                isArrival -> Reasons.ARRIVAL
                isGpsRecovery -> Reasons.STATIONARY_RECOVERY
                isBlackoutArrival -> Reasons.BLACKOUT_ARRIVAL
                isWalkArrival -> Reasons.WALK_ARRIVAL
                isHeartbeat -> Reasons.HEARTBEAT
                else -> null
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Stage 6 — bearing-change capture (EC-45)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * This fix's direction of travel, or `null` when nothing trustworthy is available.
     *
     * Hardware first: the GNSS chip derives heading from Doppler, so it is a true
     * instantaneous heading rather than the chord across a sampling interval. That
     * distinction is the whole point at a corner — the chord from before the turn to
     * after it bisects the turn and reports half the angle, while the chip reports the
     * heading the vehicle is actually pointing.
     *
     * The displacement fallback is deliberately floored at [TrackerConstants.bearingCaptureMinDist]:
     * `atan2` over two fixes 3 m apart with 8 m accuracy is a random number.
     */
    private fun headingOf(fix: TrackFix, past: TrackPoint, distanceMoved: Double): Float? = when {
        fix.hasBearing -> fix.bearingDeg
        distanceMoved >= c.bearingCaptureMinDist ->
            Bearing.degrees(past.latitude, past.longitude, fix.latitude, fix.longitude).toFloat()
        else -> null
    }

    /** Hardware heading only — used where a displacement-derived one would span a gap. */
    private fun hardwareHeading(fix: TrackFix): Float? = fix.bearingDeg.takeIf { fix.hasBearing }

    /**
     * @return [Reasons.BEARING_CHANGE] when this fix turned far enough from the last
     *   stored point to be worth a vertex, else `null`.
     *
     * Every guard here is load-bearing against the same failure: bearing-change capture
     * becoming a stationary-drift generator. A parked phone produces fixes whose
     * displacement-derived heading swings through the full circle, and storing a point on
     * each would undo the six stationary defences at a stroke. Hence the speed floor, the
     * distance floor and the accuracy ceiling — a corner is taken *while moving*, with a
     * fix good enough to place it.
     */
    private fun bearingChangeReason(
        fix: TrackFix,
        state: FilterState,
        eval: Eval,
        heading: Float?,
        context: IngestContext,
    ): String? {
        val thresholdDeg = context.bearingChangeCaptureDeg
        if (thresholdDeg <= 0) return null
        if (heading == null || !state.hasCapturedBearing) return null
        if (eval.effectiveSpeed < c.bearingCaptureMinSpeed) return null
        if (eval.distanceMoved < c.bearingCaptureMinDist) return null
        if (fix.accuracy >= c.bearingCaptureMaxAccuracy) return null
        // A step veto means the pedometer already proved this displacement was not travel.
        if (eval.stepVeto) return null

        val turn = Bearing.difference(state.lastCapturedBearingDeg.toDouble(), heading.toDouble())
        return if (turn >= thresholdDeg) Reasons.BEARING_CHANGE else null
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Stage 7
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * The GNSS chip itself saying "this thing is driving", from Doppler rather than from
     * arithmetic on two positions.
     *
     * `hasSpeed` is the load-bearing half. Without it `speedMps` is a default-zero field
     * and the check would read whatever happened to be there; with it, this is a hardware
     * measurement no amount of multipath around a parked phone can fabricate. That is why
     * it is allowed to override displacement-based heuristics rather than merely inform
     * them (EC-36a, EC-39d).
     */
    private fun hardwareVehicular(fix: TrackFix, eval: Eval): Boolean =
        fix.hasSpeed && eval.hwSpeed >= c.speedVehicularMin

    /**
     * Whether the constant-velocity model is currently wrong (EC-45a).
     *
     * The filter carries its own heading in `velocityNorth/EastMps`, so the disagreement
     * between that and the measured heading *is* the model error — no turn state has to
     * be plumbed in from [com.field360.traker.geo.motion.TurnDetector], and this stays
     * true for a turn that detector never armed.
     *
     * Both speeds must clear the bar, not either. A heading derived from a near-stationary
     * phone swings through the full circle on multipath alone, and letting that raise the
     * process noise would hand the drift a wider correction on exactly the fixes the six
     * stationary defences exist to suppress.
     */
    private fun isTurning(state: FilterState, eval: Eval, heading: Float?): Boolean {
        if (heading == null || !state.isInitialised) return false
        if (eval.effectiveSpeed < c.turnBurstMinSpeed) return false
        if (KalmanFilter.speedOf(state) < c.turnBurstMinSpeed) return false

        val filterHeading = Bearing.ofVelocity(state.velocityNorthMps, state.velocityEastMps)
            ?: return false
        return Bearing.difference(filterHeading, heading.toDouble()) >= c.qTurnMinDeltaDeg
    }

    /** Acceleration spectral density, m/s², for the constant-velocity model (EC-44a). */
    private fun processNoise(eval: Eval): Float {
        val speedKmph = eval.effectiveSpeed * MPS_TO_KMPH
        val isHighway = eval.isVehicular && speedKmph > c.speedHighwayKmph
        return when {
            isHighway -> c.qAccelHighway
            eval.isVehicular -> c.qAccelVehicular
            // Prediction variance barely grows between fixes, so the gate stays tight
            // around the anchor and drift excursions are rejected outright (EC-38).
            eval.isHardwareStationary -> c.qAccelStationary
            eval.isMoving ->
                if (eval.accuracyPoor) c.qAccelMovingPoorAccuracy else c.qAccelMovingGoodAccuracy
            else -> c.qAccelDefault
        }
    }

    private fun measurementNoise(fix: TrackFix, eval: Eval, predictedDelta: Double): Float {
        val speedKmph = eval.effectiveSpeed * MPS_TO_KMPH
        val isHighway = eval.isVehicular && speedKmph > c.speedHighwayKmph

        var r = fix.accuracy.toDouble()
        if (fix.accuracy > c.rBadAccuracyThreshold) {
            r *= if (isHighway) c.rBadAccuracyMultiplierHighway else c.rBadAccuracyMultiplier
        }

        // Do NOT drift-penalise displacement that the measured speed already explains.
        // Skipping this scaling is what causes R over-inflation -> Kalman state lag ->
        // sigma gate cascade: the track goes straight for a kilometre, then teleports
        // (EC-44, spec §8.2).
        val expectedTravel = eval.effectiveSpeed * eval.dtSec
        val driftTolerance = when {
            isHighway -> max(c.driftToleranceHighwayMin, expectedTravel * c.driftToleranceSpeedFactor)
            eval.isVehicular -> max(c.driftToleranceVehicularMin, expectedTravel * c.driftToleranceSpeedFactor)
            else -> c.driftToleranceDefault
        }
        if (predictedDelta > driftTolerance && !eval.isSignalGap) {
            val divisor = if (isHighway) c.rDriftDivisorHighway else c.rDriftDivisor
            val cap = if (isHighway) c.rDriftMaxHighway else c.rDriftMax
            r *= (predictedDelta / divisor).coerceIn(1.0, cap)
        }

        // Anchor penalty — the single most important stationary fix. Inflating R
        // freezes the filter output at the anchor: a 40 m drift fix moves it ~1-2 m
        // instead of ~20 m (spec §8.1 defence #3).
        val isVirtuallyStopped = eval.effectiveSpeed < c.speedVirtuallyStopped
        val shouldAnchor = isVirtuallyStopped && (
            (eval.isHardwareStationary && eval.distanceMoved > c.anchorMinDistHwStationary) ||
                (!eval.isSignalGap && eval.distanceMoved > c.anchorMinDist)
            )
        if (shouldAnchor) {
            r *= (eval.distanceMoved / c.rAnchorDivisor).coerceIn(1.0, c.rAnchorMax)
        }

        return r.toFloat()
    }

    @Suppress("LongParameterList")
    private fun finalise(
        fix: TrackFix,
        past: TrackPoint,
        state: FilterState,
        eval: Eval,
        context: IngestContext,
        reason: String,
        /** Cornering-aware. The gate has already run on the straight-line `q` (EC-45a). */
        qTrack: Float,
        sigma: Float,
        threshold: Float,
        predictedDelta: Double,
        distanceMoved: Double,
        heading: Float?,
    ): PipelineResult {
        val r = measurementNoise(fix, eval, predictedDelta)
        var s = state

        // ── 7-A — net-displacement persistence ───────────────────────────────
        // Nothing publishes as "movement" until net displacement from the origin
        // anchor passes 100 m, or grows monotonically on two consecutive fixes.
        // Drift wanders out and back; a real departure grows. This is what kills the
        // "user walked 60 m at 2 am" artifact (EC-39).
        if ((eval.isVehicular || eval.isMoving) && !eval.isArrivalTransition && !s.movingMode &&
            !hardwareVehicular(fix, eval)
        ) {
            val origin = s.origin
            if (origin == null) {
                s = KalmanFilter.process(s, fix.latitude, fix.longitude, r, fix.elapsedRealtimeNanos, qTrack)
                    .withOrigin(fix.latitude, fix.longitude)
                return skip(fix, s, Reasons.ORIGIN_SET, eval, sigma, threshold)
            }

            val net = Haversine.metres(origin.latitude, origin.longitude, fix.latitude, fix.longitude)
            // Advancing against the high-water mark. Drift wanders out and back, so its
            // net stalls against its own maximum; a journey's net only ever climbs. THAT
            // is the signal this stage is really looking for, and unlike a fixed per-fix
            // step it means the same thing at 60 s, 12 s and 4 s sampling (EC-39, EC-39a).
            val stillAdvancing = net > s.prevNetMeters + c.persistAdvanceM

            if (net > c.persistMinNet && stillAdvancing) {
                val departCount = s.departCount + 1
                s = s.copy(departCount = departCount, prevNetMeters = net.toFloat())
                // Size alone may confirm a departure outright, but only while the GNSS chip
                // agrees the device is moving at all. When it reports zero, displacement is
                // the only evidence in play and size is not a quality of it — a larger
                // positioning error is simply a larger number, so "it is a long way from
                // the anchor" is the one thing drift is guaranteed to be able to say.
                //
                // Nor can this be rescued with a speed bar. `dtSec` runs from the last
                // *stored* point, so a run of rejected fixes stretches it, and a 160 m
                // centroid hop across a 60 s stretch computes as 2.6 m/s — an unremarkable
                // jog. Displacement over time genuinely cannot tell the two apart; what
                // can is that a journey keeps going and a hop comes straight back, which is
                // precisely what the count ladder below measures.
                //
                // Without this, one hop latched `movingMode`, `movingMode` switches this
                // whole ladder off until settle detection unwinds it, and every hop after
                // it stored as `Vehicular`. That is the "phone on my desk, still collecting
                // points" report, and it is why the ladder looked like it did nothing.
                //
                // Nothing real is lost: a drive reports Doppler and never reaches this
                // branch at all (EC-39d), a walk the chip *can* see still confirms on one
                // fix, and a walk indoors that it cannot confirms one fix later on the
                // count ladder (EC-142).
                val confirmedBySize = net > c.persistConfirmNet && !eval.isHardwareStationary
                if (confirmedBySize || departCount >= c.persistDepartCount) {
                    // Latched: a real departure. Fall through and publish.
                    s = s.copy(movingMode = true, settleCount = 0)
                } else {
                    s = KalmanFilter.process(s, fix.latitude, fix.longitude, r, fix.elapsedRealtimeNanos, qTrack)
                    return skip(fix, s, Reasons.DEPARTURE_HELD, eval, sigma, threshold)
                }
            } else if (stillAdvancing) {
                // Going somewhere, just not far enough yet to call it. **The origin stays
                // put.** Re-anchoring it here — which is what this branch used to fold
                // into the drift case — moved the anchor onto the user on every fix, so
                // net displacement could never exceed one fix's travel and anyone slower
                // than `persistMinNet / cadence` became permanently invisible. At the 12 s
                // vehicular cadence that silently swallowed every walk, every cycle and
                // all slow city driving (EC-39a).
                s = KalmanFilter.process(s, fix.latitude, fix.longitude, r, fix.elapsedRealtimeNanos, qTrack)
                    .copy(prevNetMeters = net.toFloat())
                return skip(fix, s, Reasons.DEPARTURE_HELD, eval, sigma, threshold)
            } else {
                // Net stalled against its own high-water mark: wandered out and came back.
                // Re-anchor so the loop cannot accumulate (EC-39).
                s = KalmanFilter.process(s, fix.latitude, fix.longitude, r, fix.elapsedRealtimeNanos, qTrack)
                    .withOrigin(fix.latitude, fix.longitude)
                return skip(fix, s, Reasons.DRIFT_SUPPRESSED, eval, sigma, threshold)
            }
        }

        // A departure the GNSS chip is independently confirming skips the ladder above
        // and latches at once. The ladder exists to suppress *drift* (EC-39), and drift
        // never reports vehicular Doppler — it is metres of multipath around a parked
        // phone, with the chip saying zero. Making a car wait 100 m of net displacement
        // for permission to be moving is what cut the first 120-160 m off every departure
        // in the field capture (EC-39d).
        if (hardwareVehicular(fix, eval) && !s.movingMode) {
            s = s.copy(movingMode = true, settleCount = 0)
        }

        // ── 7-B — reason routing ─────────────────────────────────────────────
        if (eval.isArrivalTransition) {
            s = s.withOrigin(fix.latitude, fix.longitude).clearMovement()
        }

        s = when (reason) {
            // Hard reseed: the filter's position is stale by definition here.
            Reasons.STATIONARY_RECOVERY ->
                KalmanFilter.seed(s, fix.latitude, fix.longitude, fix.accuracy, fix.elapsedRealtimeNanos)

            // Warm the filter, store nothing. This is what makes a 2-hour steady user
            // produce exactly ONE stored point (EC-48).
            Reasons.HEARTBEAT -> {
                val warmed = KalmanFilter.process(s, fix.latitude, fix.longitude, r, fix.elapsedRealtimeNanos, qTrack)
                return skip(fix, warmed, Reasons.HEARTBEAT_SKIPPED, eval, sigma, threshold)
            }

            Reasons.WALK_ARRIVAL ->
                KalmanFilter.process(s, fix.latitude, fix.longitude, r, fix.elapsedRealtimeNanos, qTrack)
                    .withOrigin(fix.latitude, fix.longitude)
                    .clearMovement()

            else -> KalmanFilter.process(s, fix.latitude, fix.longitude, r, fix.elapsedRealtimeNanos, qTrack)
        }

        // Remember genuine hardware-vehicular evidence for the NLP bypass window, so a
        // tunnel or parking garage keeps tracking on network fixes (EC-32).
        if ((fix.hasSpeed || fix.hasBearing) && eval.effectiveSpeed >= c.speedVehicularMin) {
            s = s.copy(lastHwVehicularNanos = fix.elapsedRealtimeNanos)
        }

        s = s.copy(consecutiveRejectCount = 0)
        return acceptFix(fix, s, reason, eval, context, past, sigma, threshold, distanceMoved, heading)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Terminal helpers
    // ─────────────────────────────────────────────────────────────────────────

    @Suppress("LongParameterList")
    private fun acceptFix(
        fix: TrackFix,
        state: FilterState,
        reason: String,
        eval: Eval,
        context: IngestContext,
        past: TrackPoint?,
        sigma: Float = 0f,
        threshold: Float = 0f,
        distanceMoved: Double = eval.distanceMoved,
        heading: Float? = null,
    ): PipelineResult {
        // Burst clock advances on ACCEPT only (spec §7 stage 7: `lastProcessingTime = now`).
        //
        // The captured heading advances here too, and ONLY here: it is the heading at the
        // last *stored* point by definition, so updating it on a skip or a reject would
        // measure the next turn from a vertex the polyline does not contain (EC-45). A
        // null heading leaves the previous one standing rather than clearing it — one
        // unusable fix must not blind the comparison for the rest of the drive.
        val committed = state.copy(
            lastFixElapsedNanos = fix.elapsedRealtimeNanos,
            lastCapturedBearingDeg = heading ?: state.lastCapturedBearingDeg,
            // Stated here as well as implied by `seen`, because this is the invariant that
            // matters: a stored point ends the run of unconditional rejections, so the
            // next bridge has to be earned from scratch (EC-139a).
            hardRejectRun = 0,
        )

        val point = TrackPoint(
            uuid = Uuids.forFix(context.sessionId, fix.elapsedRealtimeNanos),
            sessionId = context.sessionId,
            timeMs = fix.timeMs,
            elapsedRealtimeNanos = fix.elapsedRealtimeNanos,
            localDate = context.localDate,
            timezone = context.timezone,
            latitude = fix.latitude,
            longitude = fix.longitude,
            accuracy = fix.accuracy,
            altitude = fix.altitude,
            speedMps = eval.effectiveSpeed,
            bearingDeg = fix.bearingDeg,
            hasSpeed = fix.hasSpeed,
            hasBearing = fix.hasBearing,
            provider = fix.provider,
            isMock = fix.isMock,
            movementStatus = movementStatus(fix, eval),
            detectedActivity = context.detectedActivity,
            activityStartTimeMs = context.activityStartTimeMs,
            odometerMeters = context.odometerMeters + if (past != null) distanceMoved else 0.0,
            batteryPct = context.batteryPct,
            isCharging = context.isCharging,
            extras = context.extras,
            integrityFlags = context.integrityFlags,
            providerFlags = context.providerFlags,
            acceptReason = reason,
        )

        return PipelineResult(
            decision = decision(fix, committed, Verdict.Accept(reason), eval, sigma, threshold, distanceMoved),
            state = committed,
            point = point,
        )
    }

    private fun skip(
        fix: TrackFix,
        state: FilterState,
        reason: String,
        eval: Eval,
        sigma: Float = 0f,
        threshold: Float = 0f,
    ): PipelineResult = PipelineResult(
        decision = decision(fix, state, Verdict.Skip(reason), eval, sigma, threshold, eval.distanceMoved),
        state = state,
        point = null,
    )

    private fun reject(
        fix: TrackFix,
        state: FilterState,
        reason: String,
        eval: Eval,
        sigma: Float = 0f,
        threshold: Float = 0f,
    ): PipelineResult {
        // 7-C — settle detection. While latched in movingMode, consecutive near-zero
        // fixes mean the user has actually stopped; re-anchor so the next departure is
        // judged fresh. Prevents state-machine thrash for delivery riders (EC-60).
        var s = state
        if (s.movingMode && eval.distanceMoved < c.distJitter && eval.hwSpeed < c.speedWalkingMin) {
            val settle = s.settleCount + 1
            s = if (settle >= c.settleFixesToExit) {
                s.copy(movingMode = false, settleCount = 0).withOrigin(fix.latitude, fix.longitude)
            } else {
                s.copy(settleCount = settle)
            }
        }
        return PipelineResult(
            decision = decision(fix, s, Verdict.Reject(reason), eval, sigma, threshold, eval.distanceMoved),
            state = s,
            point = null,
        )
    }

    @Suppress("LongParameterList")
    private fun decision(
        fix: TrackFix,
        state: FilterState,
        verdict: Verdict,
        eval: Eval,
        sigma: Float,
        threshold: Float,
        distanceMoved: Double,
    ) = FixDecision(
        fix = fix,
        verdict = verdict,
        filterLat = state.lat,
        filterLng = state.lng,
        sigma = sigma,
        threshold = threshold,
        distanceMovedM = distanceMoved,
        effectiveSpeedMps = eval.effectiveSpeed,
        motionState = state.motionState,
    )

    /** Per-point stamp (spec §9): trust hardware speed when it is plausible. */
    private fun movementStatus(fix: TrackFix, eval: Eval): MovementStatus = when {
        fix.hasSpeed && fix.speedMps < MOVEMENT_STATUS_HW_TRUST_MAX ->
            if (fix.speedMps < MOVEMENT_STATUS_STEADY_MAX) MovementStatus.STEADY else MovementStatus.MOVING
        eval.isMoving || eval.isVehicular -> MovementStatus.MOVING
        else -> MovementStatus.STEADY
    }

    /** Everything stage 2 derived, carried forward so later stages agree with it. */
    private data class Eval(
        val hwSpeed: Float,
        val calcSpeed: Float,
        val effectiveSpeed: Float,
        val isHardwareStationary: Boolean,
        val isSignalGap: Boolean,
        val isVehicular: Boolean,
        val isMoving: Boolean,
        val isArrivalTransition: Boolean,
        val distanceMoved: Double,
        val dtSec: Float,
        val stepVeto: Boolean,
        val accuracyPoor: Boolean = false,
    ) {
        companion object {
            val EMPTY = Eval(
                hwSpeed = 0f,
                calcSpeed = 0f,
                effectiveSpeed = 0f,
                isHardwareStationary = true,
                isSignalGap = false,
                isVehicular = false,
                isMoving = false,
                isArrivalTransition = false,
                distanceMoved = 0.0,
                dtSec = 0f,
                stepVeto = false,
            )
        }
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
        const val NANOS_PER_SECOND = 1_000_000_000f
        const val MPS_TO_KMPH = 3.6f
        const val GPS_TRUST_DERATE = 0.85f
        const val RESUME_FALLBACK_ACCURACY = 25f
        const val MOVING_MIN_DT_SEC = 50f
        const val ARRIVAL_PAST_SPEED_MIN = 0.5f
        const val BLACKOUT_PAST_SPEED_MIN = 1.0f
        const val GPS_RECOVERY_LARGE_ACCURACY = 80f
        const val VEHICULAR_LOW_HW_ACCURACY = 50f
        const val STEP_CORROBORATION_MAX_M = 80.0
        const val STEP_CORROBORATION_MIN_STEPS = 20
        const val Q_MOVING_ACCURACY_SPLIT = 35f
        const val MOVEMENT_STATUS_HW_TRUST_MAX = 5f
        const val MOVEMENT_STATUS_STEADY_MAX = 0.5f

        /**
         * The stage-6 reasons a stillness veto may overrule — every one that can *store* a
         * point out of the stationary branch (EC-142).
         *
         * [Reasons.HEARTBEAT] is deliberately absent, and its absence is load-bearing. It
         * stores nothing anyway: stage 7-B warms the filter on it and returns
         * [Reasons.HEARTBEAT_SKIPPED]. That warming is the SDK's only self-correcting path
         * on a device whose wake paths have all failed — a heartbeat fix landing beyond the
         * stationary radius is what re-declares MOVING within one interval instead of never
         * (EC-57). Vetoing it would let a stuck accelerometer freeze the filter clock, and
         * that is the failure mode this whole stage is supposed to be immune to.
         */
        val VETOABLE_REASONS: Set<String> = setOf(
            Reasons.ARRIVAL,
            Reasons.STATIONARY_RECOVERY,
            Reasons.BLACKOUT_ARRIVAL,
            Reasons.WALK_ARRIVAL,
            Reasons.BEARING_CHANGE,
        )
    }
}
