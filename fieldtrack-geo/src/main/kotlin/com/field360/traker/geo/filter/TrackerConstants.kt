package com.field360.traker.geo.filter

/**
 * Every tuning constant in the acceptance pipeline, in one place.
 *
 * Two reasons this is a `data class` rather than a file of `const val`s: the fixture
 * harness can sweep a constant and re-run a recorded day, and PLAN.md §3 invariant 1
 * ("no algorithm above `fieldtrack-geo`") is checkable — there is exactly one place a
 * decision number can live.
 *
 * Values are the field-verified production set from the reference implementation
 * (spec §27). Each has a documented physical rationale and a named symptom it
 * prevents; **do not soften one to fix a symptom another stage owns** (spec §8.3).
 *
 * The Gen-1 leftovers at `LocationUtil.kt:51-61` are deliberately absent — porting
 * them would imply they were live, and they are not (SOURCE-AUDIT A18).
 */
public data class TrackerConstants(
    // ── timing ────────────────────────────────────────────────────────────────
    /** Duplicate callbacks closer than this are one event, not two (EC-30). */
    val burstMs: Long = 500,
    /**
     * How far the monotonic clock must run **backwards** before it counts as a reboot
     * rather than as reordered delivery (EC-92a).
     *
     * The two look alike and are wildly different in scale. A reboot restarts
     * `elapsedRealtimeNanos` at zero, so it rewinds by the device's entire prior uptime —
     * minutes at the very least, usually hours. Fixes arriving out of order across two
     * batched deliveries rewind by at most the batching window, which this SDK now caps
     * at twice the sampling interval.
     *
     * Five minutes sits far above any batching window and far below a plausible uptime.
     * Treating *any* backward step as a reboot — which is what shipped — wiped the entire
     * filter mid-drive on a 5.2 second reordering.
     */
    val rebootBackwardStepMs: Long = 5 * 60 * 1000L,
    /**
     * Consecutive out-of-order fixes tolerated before the held timestamp is assumed
     * wrong and a reboot is declared anyway.
     *
     * Without this a reboot early in a session — where the rewind is smaller than
     * [rebootBackwardStepMs] — would classify every subsequent fix as out-of-order and
     * drop it, forever, because the held timestamp could never advance. Reordering is
     * sporadic by nature; three in a row with no forward fix between them is not
     * reordering.
     */
    val maxOutOfOrderRun: Int = 3,
    /** Above this Δt the fix is "post-gap" and recovery logic applies (EC-40). */
    val signalGapSec: Float = 110f,
    val recoveryTimeoutSec: Float = 900f,
    /** Stationary fixes are folded into the filter at most this often (EC-48). */
    val heartbeatSec: Float = 900f,

    // ── speeds, m/s ───────────────────────────────────────────────────────────
    /** Below this the GNSS chip says "not moving", and it is trusted over position. */
    val speedStationaryMax: Float = 0.3f,
    val speedWalkingMin: Float = 0.6f,
    val speedVehicularMin: Float = 3.0f,
    /** Point-to-point speed above this overrides a stationary hardware reading. */
    val speedGpsTrust: Float = 1.5f,
    val speedVirtuallyStopped: Float = 2.0f,
    val speedMaxPhysicalKmph: Float = 140f,
    val speedHighwayKmph: Float = 45f,

    // ── distances, metres ─────────────────────────────────────────────────────
    val distMinMove: Double = 10.0,
    val distJitter: Double = 15.0,
    /** Indoor GPS wobble guard; doubled when the fix has no hardware speed (EC-38). */
    val distStationaryWobble: Double = 40.0,
    val distStationaryWobbleNoHwSpeed: Double = 80.0,
    val distRecoveryWakeup: Double = 100.0,
    val distRecoveryVehicular: Double = 200.0,
    val distGpsRecoveryLarge: Double = 400.0,

    // ── net-displacement persistence (EC-39) ──────────────────────────────────
    /**
     * **Unused since EC-39a; kept so a stored config snapshot still deserialises.**
     *
     * It was the per-fix step a departure had to advance by. A fixed 20 m step is a
     * statement about *sampling cadence*, not about movement: it is one second of driving
     * and fifteen of walking, and at the 4 s turn-burst tier nothing on foot can ever
     * clear it. Replaced by [persistAdvanceM] against the net high-water mark, which
     * means the same thing at every cadence.
     */
    @Deprecated("Cadence-dependent; superseded by persistAdvanceM (EC-39a)")
    val persistGrowMargin: Double = 20.0,
    /**
     * Minimum advance on the net-displacement high-water mark for a fix to count as
     * *still departing* rather than drifting (EC-39a).
     *
     * Separate from [persistGrowMargin], and much smaller, because the two answer
     * different questions. `persistGrowMargin` asks "did this fix advance enough to
     * count as a departure step?" — a 20 m answer calibrated for 60 s sampling. This one
     * asks "is the user still going somewhere?", and the answer has to hold at *every*
     * cadence: at 12 s a walker advances ~16 m per fix and at the 4 s turn-burst tier
     * ~5 m. Judging that with the 20 m step made every sub-vehicular departure
     * permanently unconfirmable — the origin re-anchored onto the walker each time, so
     * net displacement could never accumulate past one fix's worth.
     */
    val persistAdvanceM: Double = 5.0,
    /**
     * Net displacement that confirms a departure on a single fix, metres.
     *
     * Conditional since EC-142 on the GNSS chip reporting *some* motion. Where it reports
     * none, displacement is the only evidence in play and its size says nothing — drift is
     * always able to be a long way from the anchor — so the departure has to be earned on
     * the [persistDepartCount] ladder instead, which measures persistence rather than size.
     */
    val persistConfirmNet: Double = 100.0,
    val persistDepartCount: Int = 2,
    val persistMinNet: Double = 40.0,
    val settleFixesToExit: Int = 2,

    // ── tiered recovery (EC-40, EC-41) ────────────────────────────────────────
    val recoveryImmediateDist: Double = 150.0,
    val recoveryConfirmNear: Double = 60.0,
    /**
     * Accuracy a post-gap fix must beat before it is allowed to re-anchor the filter and
     * plot at once, metres (EC-140).
     *
     * The first fix after a blackout is the least trustworthy fix of the whole session
     * and the most consequential: nothing corroborates it, and every later fix is judged
     * from wherever it lands. In the field capture the 116 s gap was followed by a 45 m
     * fix 104 m from the last stored point, which re-anchored the track and dragged the
     * next fifteen fixes into a zigzag around the wrong street.
     *
     * Deliberately stricter than [accuracyHigh]: this bar decides whether one uncorroborated
     * fix may move the anchor, not whether a fix in an already-tracked run is usable.
     * Anything above it is *held* — warmed into the filter, stored nowhere — until a second
     * fix lands within [recoveryConfirmNear] and agrees, which is the mechanism that
     * already existed for weak-evidence recoveries and is simply no longer skippable on
     * accuracy grounds.
     */
    val accuracyRecoveryTrust: Float = 25f,
    /**
     * How long a held recovery candidate may wait for confirmation before it is abandoned,
     * seconds (EC-140).
     *
     * Bounded so a run of poor fixes near the candidate cannot hold the track silent
     * indefinitely. On expiry the candidate is dropped and the current fix is judged by
     * the ordinary gates — it is *not* promoted, because "nothing better arrived" is not
     * evidence that the candidate was right.
     */
    val recoveryHoldMaxSec: Float = 120f,

    // ── accuracy ceilings, metres ─────────────────────────────────────────────
    val accuracyHigh: Float = 40f,
    val accuracyMedium: Float = 70f,
    val accuracyStationaryLimit: Float = 40f,
    val accuracyMaxVehicular: Float = 85f,
    /**
     * Hard ceiling on a fix claimed to be **moving**, metres. Above this it is rejected
     * outright, whatever its motion class and whatever any later stage would have made of
     * it (EC-139).
     *
     * Every other accuracy bound in this class is conditional on a motion class that the
     * fix's own displacement helps decide, which is circular: a 66 m positioning error
     * computes as 13.9 m/s, 13.9 m/s reads as vehicular, and vehicular carries the loosest
     * ceiling in the file ([accuracyMaxVehicular]). That is how the field capture stored a
     * 153 m spike and a 173° reversal inside a normal city drive. This bound is
     * unconditional precisely so displacement cannot argue its way past it.
     *
     * 30 m is set from the field data rather than from taste: the clean parts of that drive
     * ran at 4–8 m and never exceeded 19.4 m at the 90th percentile, while every direction
     * reversal in it sat above 20 m. It is also above the ~25 m at which a fused fix stops
     * being GNSS-led, so a genuinely degraded but still GNSS-quality fix survives.
     *
     * Stationary fixes are exempt — they are governed by the anchor and wobble defences
     * (spec §8.1), which already treat a poor fix as something to freeze against rather
     * than something to plot. The tunnel case is exempt too, via the NLP bypass: a network
     * fix with corroborating *prior* hardware speed is evidence of a vehicle that has lost
     * GNSS, not of GNSS lying (EC-32).
     */
    val accuracyMovingMax: Float = 30f,
    /** Network fixes worse than this are rejected outright (EC-32). */
    val accuracyNlpReject: Float = 25f,

    // ── bounded hard-reject run (EC-139a) ─────────────────────────────────────
    /**
     * Consecutive fixes the unconditional accuracy gates may drop before the next one is
     * admitted as a bridge instead.
     *
     * [accuracyMovingMax] and [accuracyNlpReject] are the only gates in this file with no
     * escape valve — the sigma gate has its forced reset, recovery has its hold, and both
     * of these simply return. That is correct on hardware that can meet the ceiling, and
     * on hardware that cannot it means the ceiling has no exit: every fix is dropped for
     * as long as the conditions last, and the polyline draws one straight chord from the
     * last fix that met the bar to the next one that does. The chord is not a *better*
     * answer than coarse points — it is a route the device never took, drawn with no
     * uncertainty at all, and it is what a field capture on a Redmi A5 and a vivo V2315
     * produced for kilometres at a time.
     *
     * Four is the smallest number that cannot be reached by ordinary noise. Real fix
     * accuracy is autocorrelated over a few samples — a bus shelter, an underpass, a turn
     * into an urban canyon — so one, two, even three consecutive poor fixes are a normal
     * transient the ceiling is right to swallow. A fourth says the device is not having a
     * bad moment, it is producing this accuracy as a matter of course, and no amount of
     * further waiting will improve it.
     *
     * `0` restores the pre-EC-139a behaviour exactly: unbounded runs, no bridge. It is the
     * kill switch, and it is the value a fixture harness sets to replay a recording made
     * before this stage existed.
     */
    val maxHardRejectRun: Int = 4,
    /**
     * How far a bridged fix may sit from the last stored point, as a multiple of what the
     * vehicle's **prior** speed could have covered, plus a flat allowance.
     *
     * Deliberately the same shape and the same 1.3 as [nlpBypassSpeedFactor], and for the
     * identical reason: every input is earned from fixes that predate the one being
     * judged, so a bigger positioning error cannot argue itself into a higher speed and
     * then use that speed as its own permission (EC-32a). A bridge is an admission that
     * the fix is imprecise; it must never become an admission that it is unbounded.
     *
     * The flat term is larger than the NLP bypass's 60 m because the fix's own error
     * circle is the thing being tolerated here — a 40 m fix is allowed to be 40 m wrong
     * about where it is without that counting as travel it could not have made.
     */
    val bridgeSpeedFactor: Double = 1.3,
    val bridgeFlatM: Double = 100.0,
    /**
     * The same allowance for the sigma gate's forced reset, and deliberately four times
     * wider (EC-43a).
     *
     * The forced reset is a rescue, not a routine acceptance: the filter has been rejecting
     * everything and this branch exists so it can never stay wedged. Re-seeding is
     * therefore unconditional and stays that way. The only question this number answers is
     * whether the rescue fix also becomes a *vertex*, and the bar for that has to sit well
     * clear of anything a real vehicle produces — a departure from a stop, a motorway
     * resume, a long leg after a hold — because a false negative here costs one point and a
     * false positive draws a kilometre-long spur onto the track.
     *
     * 400 m matches [distGpsRecoveryLarge], which is the existing answer elsewhere in this
     * file to "how far can a thing legitimately have moved when we have no speed to go on".
     *
     * Note it must be tighter than stage 3's [speedMaxPhysicalKmph] to mean anything at
     * all: an envelope wider than the physical-sanity cap is unreachable by construction
     * and would be a no-op dressed as a guard.
     */
    val forcedResetFlatM: Double = 400.0,
    val nlpBypassWindowMs: Long = 10 * 60 * 1000L,
    /**
     * How far a network fix may be from the last one and still be believed, as a
     * multiple of what the vehicle's **prior** speed could have covered, plus a flat
     * allowance (EC-32a).
     *
     * 1.3 matches [driftToleranceSpeedFactor] — the same "how much more than expected is
     * still plausible" judgement, made about the same thing. The flat term covers the
     * error circle of the fix itself, which is by definition tens of metres wide here.
     *
     * A tunnel at 15 m/s covers ~180 m in twelve seconds against a 294 m allowance and
     * passes. The 287 m spike that motivated this, on a ~10 m/s prior estimate, gets a
     * 216 m allowance and does not.
     */
    val nlpBypassSpeedFactor: Double = 1.3,
    val nlpBypassFlatM: Double = 60.0,

    // ── 3-sigma gate (EC-35, EC-43, EC-44) ────────────────────────────────────
    val sigmaMultiplier: Float = 3f,
    val gateAccuracyFactor: Float = 1.5f,
    val gateFlatTermM: Float = 200f,
    val gateSpeedExpansionFactor: Float = 1.2f,
    val gateMaxVehicular: Float = 2500f,
    val gateMaxMoving: Float = 800f,
    val gateMaxStationaryBase: Float = 400f,
    val gateMaxStationaryPerReject: Float = 200f,
    val gateMinM: Float = 50f,
    val maxRejectsSignalGap: Int = 2,
    val maxRejectsStationary: Int = 4,
    val maxRejectsVehicular: Int = 2,
    val maxRejectsWalking: Int = 3,

    // ── Kalman Q/R tuning (spec §7 stage 7) ───────────────────────────────────
    /**
     * Process noise, as an **acceleration** spectral density in m/s² (EC-44a).
     *
     * Renamed from the old `q*` set, and every value re-derived, because the unit changed
     * with the motion model: the scalar filter's `q` was a position-drift rate, so
     * carrying the numbers over unchanged would have quietly inflated the 3-sigma gate by
     * ~70 % and let through the multipath spikes the gate exists to catch. The rename is
     * deliberate — it forces every call site to be looked at rather than silently
     * compiling against the wrong meaning.
     *
     * Values are what a vehicle's acceleration actually varies by between fixes: a
     * motorway cruise barely changes speed, city driving brakes and accelerates hard.
     */
    val qAccelHighway: Float = 0.25f,
    val qAccelVehicular: Float = 0.6f,
    /** Near-zero while hardware-stationary: keeps the gate tight so drift is rejected
     *  outright rather than averaged in. One of the six stationary defences (EC-38). */
    val qAccelStationary: Float = 0.02f,
    /** A poor fix gets the *smaller* noise, so the prediction stays tight and the gate
     *  narrow — the same split the scalar filter made, preserved through the rename. */
    val qAccelMovingPoorAccuracy: Float = 0.2f,
    val qAccelMovingGoodAccuracy: Float = 0.45f,
    val qAccelDefault: Float = 0.3f,
    /**
     * Cornering, and the one value here that is a *lateral* acceleration (EC-45a).
     *
     * A constant-velocity model predicts a straight line by definition, so at a corner it
     * is wrong in a way no amount of tuning on the straight fixes. The prediction carries
     * on past the turn, the correction spends the next few fixes catching up, and the
     * stored track cuts the corner and then overshoots it — visible on a field capture as
     * a spike running past a right-hand turn before snapping back onto the road.
     *
     * Raising the process noise while a turn is in progress tells the filter its own
     * prediction is the unreliable party and to weight the measurement instead. 2.0 m/s²
     * because that is roughly what a normal cornering manoeuvre pulls laterally — this is
     * a physical quantity, not a tuning knob, and it should be argued about in those terms.
     *
     * **Applied to the correction only, never to the gate.** The gate keeps its
     * straight-line `q`: a turn is a reason to track harder, not a reason to admit fixes
     * that would otherwise have been rejected, and one value driving both would have
     * quietly made every corner a noise amnesty.
     */
    val qAccelTurning: Float = 2.0f,
    /**
     * How far the measured heading must diverge from the filter's own before the
     * constant-velocity model counts as wrong.
     *
     * Not expressed as a rate, unlike [turnBurstEnterDegPerSec], because the two ask
     * different questions: the burst tier watches for a *sustained* swing worth sampling
     * faster, while this is an instantaneous disagreement between prediction and
     * measurement. A gentle curve can trip the first without ever tripping this one.
     */
    val qTurnMinDeltaDeg: Float = 25f,
    val rBadAccuracyThreshold: Float = 30f,
    val rBadAccuracyMultiplierHighway: Float = 3.0f,
    val rBadAccuracyMultiplier: Float = 2.5f,
    val driftToleranceHighwayMin: Double = 25.0,
    val driftToleranceVehicularMin: Double = 60.0,
    val driftToleranceDefault: Double = 50.0,
    val driftToleranceSpeedFactor: Double = 1.3,
    val rDriftDivisorHighway: Double = 15.0,
    val rDriftDivisor: Double = 30.0,
    val rDriftMaxHighway: Double = 15.0,
    val rDriftMax: Double = 8.0,
    /** The single most important stationary fix: inflating R freezes the filter output
     *  at the anchor, so a 40 m drift fix moves it ~1–2 m instead of ~20 m (EC-39). */
    val rAnchorDivisor: Double = 5.0,
    val rAnchorMax: Double = 100.0,
    val anchorMinDistHwStationary: Double = 10.0,
    val anchorMinDist: Double = 15.0,

    // ── vehicular leg sanity ──────────────────────────────────────────────────
    val vehicularLegSpeedCap: Double = 45.0,
    val vehicularLegFlatM: Double = 200.0,

    // ── bearing-change capture (EC-45) ────────────────────────────────────────
    /**
     * Floors on the heading comparison, **not** the turn threshold itself — that is host
     * config and arrives on [com.field360.traker.geo.model.IngestContext].
     *
     * Both floors exist because a heading is only as trustworthy as the displacement it
     * was derived from. Under 15 m the angle between two fixes is dominated by position
     * noise, so a parked phone would "turn" through 180° every few minutes and store a
     * point each time — bearing-change capture would become a stationary-drift
     * generator, which is precisely what the six stationary defences exist to prevent.
     */
    val bearingCaptureMinDist: Double = 15.0,
    val bearingCaptureMinSpeed: Float = 1.5f,
    /** A turn plotted from a 100 m error circle is not a corner, it is a guess. */
    val bearingCaptureMaxAccuracy: Float = 50f,

    // ── turn-burst sampling (EC-45) ───────────────────────────────────────────
    /**
     * Heading change per second that arms the faster sampling tier
     * ([com.field360.traker.geo.motion.TurnDetector]).
     *
     * **Derived from the geometry error the burst exists to remove, not guessed.** A
     * chord of length `L` across a curve of radius `R` departs from the road by about
     * `L² / 8R`. At the 12 s vehicular cadence and 9 m/s that chord is ~108 m, so:
     *
     * | curve radius | deviation at 12 s | turn rate `v/R` |
     * |---|---|---|
     * | 100 m (urban corner) | 15 m | 5.2 °/s |
     * | 300 m (sweeping bend) | 4.9 m | 1.7 °/s |
     * | 500 m (motorway) | 2.9 m | 1.0 °/s |
     *
     * 1.5 °/s is where the deviation stops being tolerable. Everything that would draw
     * more than ~5 m off the road arms the 4 s tier and lands under a metre; the gentler
     * curves that stay within a few metres are left alone.
     *
     * The floor is set by noise, and a field capture pins it: on a real drive the
     * straight sections ran at 0.18–0.92 °/s and the turns at 2.5–6.0 °/s. 1.5 sits in
     * that gap. **This was 3.0 and missed a 2.5 °/s turn along with every gentle curve** —
     * calibrated against roundabouts, which never needed the help.
     */
    val turnBurstEnterDegPerSec: Float = 1.5f,
    /** How long the fast tier lingers after the last qualifying fix. */
    val turnBurstHoldMs: Long = 30_000,
    val turnBurstMinSpeed: Float = 2.5f,
    /** Under a second the rate divides by ~0; over 30 s the two headings are unrelated. */
    val turnBurstMinDtSec: Float = 1.0f,
    val turnBurstMaxDtSec: Float = 30.0f,

    // ── predictive turn burst from the gyroscope (EC-45d) ─────────────────────
    /**
     * Yaw rate about the world vertical that arms the fast tier
     * ([com.field360.traker.geo.motion.GyroTurnGate]).
     *
     * Deliberately the *same* number as the 100 m urban corner in
     * [turnBurstEnterDegPerSec]'s table, and it means something different here.
     * `TurnDetector` measures the rate GNSS heading changed **across a whole 12 s
     * interval** — an average, which a corner entered halfway through the interval halves.
     * A gyroscope reports the instantaneous rate, so the same corner reads far higher:
     * `v / R` at 7 m/s round a 15 m junction is 26 °/s, and even a 300 m motorway sweep at
     * 25 m/s is 4.8 °/s.
     *
     * 5 °/s therefore sits just under the gentlest curve worth sampling faster and far
     * above what a vehicle on a straight road produces. It is not a noise floor — gyro
     * noise is a fraction of a degree per second — it is the curvature below which the
     * chord error stays under a few metres and the extra samples are not worth the
     * battery.
     */
    val gyroTurnEnterDegPerSec: Float = 5.0f,
    /**
     * How long the rate must stay above [gyroTurnEnterDegPerSec] before the burst arms.
     *
     * This is the whole difference between predicting a turn and reacting to a pothole.
     * A vehicle takes the better part of a second to develop steering angle and holds it
     * for the length of the corner; a phone knocked in its cradle, a speed bump, or a
     * lane correction is a spike measured in tens of milliseconds. 600 ms discards those
     * and still arms the burst well before the next scheduled fix at any cadence tier.
     */
    val gyroTurnSustainMs: Long = 600,
    /**
     * Above this the sample is a phone being handled, not a vehicle turning, and it is
     * **rejected** rather than clamped — so it also breaks the sustain run and cannot
     * accumulate into a burst.
     *
     * 120 °/s is a three-second full circle. No road vehicle does that; a phone lifted out
     * of a cradle exceeds it in a tenth of a second.
     */
    val gyroTurnMaxDegPerSec: Float = 120.0f,
    /**
     * How long a GNSS-measured vehicular speed keeps the gyroscope registered.
     *
     * The gate's real defence against a walker swinging a phone is that the gyroscope is
     * not listening at all unless GNSS has recently seen the device travelling at
     * [turnBurstMinSpeed]. This window is how long "recently" lasts, and it is set at one
     * minute for a specific reason: it must comfortably outlive the 12 s vehicular
     * cadence, so a normal drive keeps re-arming it, while still shutting the sensor down
     * within a minute of the vehicle stopping.
     */
    val gyroTurnVehicularWindowMs: Long = 60_000,

    // ── deferred corner anchors (EC-45e) ──────────────────────────────────────
    /**
     * How far a restored corner anchor must sit from the last stored point
     * ([com.field360.traker.geo.motion.CornerWindow]).
     *
     * An anti-duplicate floor and nothing more — two vertices 3 m apart draw the same line
     * as one and cost an extra row. It is far below [anchorMinDist] and [distMinMove] on
     * purpose: a fix reaches this stage precisely *because* it moved less than
     * `distMinMove`, so a floor at the usual 15 m would reject every candidate the stage
     * can ever be handed. Keeping stationary drift out is a job for the Doppler check and
     * the turn itself, not for a distance that would also exclude the real case.
     */
    val cornerAnchorMinDist: Double = 5.0,
) {
    public companion object {
        public val Default: TrackerConstants = TrackerConstants()
    }
}
