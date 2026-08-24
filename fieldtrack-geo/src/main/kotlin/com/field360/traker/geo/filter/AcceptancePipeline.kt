package com.field360.traker.geo.filter

import com.field360.traker.geo.math.Bearing
import com.field360.traker.geo.math.Haversine
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
        // ── Stage 0 — structural validity (EC-23 … EC-28) ────────────────────
        Validation.check(fix, context.mockPolicy)?.let { reason ->
            return reject(fix, state, reason, Eval.EMPTY)
        }

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
            return reject(fix, state, Reasons.BURST, Eval.EMPTY)
        }

        // ── Stage 1 (cont.) — cold start / resume ────────────────────────────
        var s = state
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

        // ── Stage 1.5 — network-fix (NLP) authenticity ───────────────────────
        val looksLikeNlp = fix.looksLikeNetworkFix
        val nlpBypassed = looksLikeNlp &&
            fix.accuracy > c.accuracyNlpReject &&
            nlpBypass(fix, past, s, dtSec, distanceMoved)
        if (looksLikeNlp && fix.accuracy > c.accuracyNlpReject && !nlpBypassed) {
            return reject(fix, s, Reasons.NLP_FALLBACK, Eval.EMPTY)
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
        if ((eval.isVehicular || eval.isMoving) && fix.accuracy > c.accuracyMovingMax && !nlpBypassed) {
            return reject(fix, s, Reasons.POOR_ACCURACY, eval)
        }

        // ── Stage 4 — tiered recovery (runs BEFORE the sigma gate) ───────────
        recoveryOutcome(fix, s, eval, distanceMoved, context, past)?.let { return it }
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
                return if (distanceMoved > c.distMinMove) {
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
        val reason = heuristicReason(fix, past, s, eval, distanceMoved, dtSec, looksLikeNlp)
            ?: bearingChangeReason(fix, s, eval, heading, context)
            ?: return reject(fix, s, Reasons.HEURISTIC_GATE, eval, sigma, threshold)

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
    private fun recoveryOutcome(
        fix: TrackFix,
        state: FilterState,
        eval: Eval,
        distanceMoved: Double,
        context: IngestContext,
        past: TrackPoint,
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

        val recoveryNeeded = fix.accuracy < c.accuracyMedium && (
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
                if (net > c.persistConfirmNet || departCount >= c.persistDepartCount) {
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
    }
}
