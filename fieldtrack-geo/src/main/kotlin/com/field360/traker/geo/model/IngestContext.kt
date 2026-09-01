package com.field360.traker.geo.model

/**
 * The per-fix facts the engine cannot derive on its own.
 *
 * Everything here is supplied by `fieldtrack-core` (session identity, calendar fields,
 * battery, the current activity label). Keeping them in one parameter object is what
 * lets [com.field360.traker.geo.filter.AcceptancePipeline] stay a pure function of
 * `(fix, past, state, context)` — which is in turn what makes fixture replay
 * byte-deterministic (PLAN.md §6 criterion 7).
 */
public data class IngestContext(
    val sessionId: String,
    /** IANA zone id, resolved at fix time — a session can cross zones (EC-89). */
    val timezone: String,
    /** `yyyy-MM-dd` in [timezone]. */
    val localDate: String,
    val mockPolicy: MockPolicy = MockPolicy.FLAG,
    val odometerMeters: Double = 0.0,
    val detectedActivity: ActivityType? = null,
    val activityStartTimeMs: Long = 0,
    val batteryPct: Int? = null,
    val isCharging: Boolean? = null,
    val extras: String? = null,
    /**
     * Steps observed since the previous point, when a pedometer is available.
     *
     * Independent physical evidence of motion, immune to multipath: zero steps across
     * a 60 m indoor excursion proves drift, and 30 steps proves a walk the Doppler
     * never saw. `null` means "no pedometer" and the corroboration stage is skipped
     * entirely rather than partially (EC-133, SDK-COMPARISON §6.2).
     */
    val stepsSinceLastPoint: Int? = null,
    /**
     * Heading change, in degrees, that forces a point to be stored regardless of what the
     * speed and distance gates decided. `0` disables it.
     *
     * Host config (`MotionConfig.bearingChangeCaptureDeg`) rather than a
     * [com.field360.traker.geo.filter.TrackerConstants] value, for the same reason
     * [mockPolicy] is: it is a policy the host owns, not a number the engine tuned. The
     * engine's own floors on the comparison stay in `TrackerConstants` (EC-45).
     */
    val bearingChangeCaptureDeg: Int = DEFAULT_BEARING_CHANGE_CAPTURE_DEG,
    /**
     * Offer a [PipelineResult.deferred] alongside a heuristic-gate rejection, so the
     * caller can restore the fix once the next one shows a corner turned across it
     * (EC-45e).
     *
     * Host config (`MotionConfig.cornerAnchorCapture`) for the same reason
     * [bearingChangeCaptureDeg] is: it changes how many points a drive stores, which is
     * the host's budget to spend. It costs one extra pass through the filter's final
     * stage per rejected fix — arithmetic, no I/O — and nothing at all when off, because
     * the pipeline does not compute the branch it was not asked for.
     */
    val cornerAnchorCapture: Boolean = true,
    /**
     * The request interval of the stream that captured this fix, in ms — `null` when
     * the fix did not come from the stream (one-shot, backstop, host insert). Stamped
     * at capture time by the stream's own collector, never sampled later: a fix keeps
     * the tier it was actually taken at, even across a cadence flip.
     *
     * Several gates count fixes, and a count is a duration in disguise: two departure
     * confirmations are ~24 s at the 12 s tier but ~8 s at the turn-burst tier. This
     * hint is what lets cadence-sensitive constants scale instead of silently meaning
     * different things at different tiers (SMOOTH-NAV-PLAN Phase 1).
     *
     * Not consumed by any gate yet — plumbed ahead of need. The contract for future
     * consumers: `null` MUST mean "behave exactly as before", so every recorded fixture
     * without a tier replays byte-identically.
     */
    val cadenceTierMs: Long? = null,
    /**
     * Device-integrity bitmask at the time this fix was ingested, as
     * `IntegrityReport.flags` in `fieldtrack-core`. `0` means "nothing observed", which is
     * also what a debuggable build and a host with the layer switched off produce.
     *
     * The engine never interprets it — it is carried onto the accepted point so the row,
     * and the upload built from that row, say what the device looked like when the point
     * was taken. Defaulting to `0` keeps every recorded fixture replaying byte-identically.
     */
    val integrityFlags: Int = 0,
    /**
     * [ProviderSnapshot.toFlags] for the location subsystem at the time this fix was
     * ingested. [ProviderSnapshot.NOT_RECORDED] means no snapshot was taken.
     *
     * The engine never interprets it — carried onto the accepted point so the row, and the
     * upload built from that row, say what the device's location stack looked like when the
     * point was taken. Defaulting to `NOT_RECORDED` keeps every recorded fixture replaying
     * byte-identically.
     */
    val providerFlags: Int = ProviderSnapshot.NOT_RECORDED,
    /**
     * The motion layer's current verdict, for the decision log only.
     *
     * `FilterState.motionState` was never assigned by anything — it defaulted to
     * [MotionState.STOPPED] and was stamped onto every `FixDecision` and every raw-point
     * row as that constant, so the one column an investigation reaches for first said the
     * same word on a motorway as on a desk. The state lives in `MotionController`, which
     * the engine cannot see, so it arrives here like every other fact the engine cannot
     * derive.
     *
     * **Read by no gate, and it must stay that way.** Motion detection may set the
     * sampling rate and it may annotate a decision; it may never decide one. A device
     * reporting `STILL` through a 17-minute drive is a documented failure on this SDK's
     * own target hardware (EC-53), and a gate reading this field is how that becomes a
     * lost trip. What *is* allowed to suppress a point is [stillnessVeto], which is a
     * different claim on different evidence.
     */
    val motionState: MotionState = MotionState.STOPPED,
    /**
     * Non-GNSS hardware asserts the device has not moved since the last stored point
     * (EC-142). `false` means "no such assertion", which is what every device without the
     * sensors, and every host with the stage switched off, produces.
     *
     * Supplied by `StillnessMonitor` in `fieldtrack-core` and consumed in exactly one
     * place: the stationary branch of the acceptance pipeline's stage 6. The engine's own
     * stationary defences are all statistical — wobble guards, R-penalties, the departure
     * ladder — because position is the only evidence they have, and each of them therefore
     * has an escape hatch that indoor multipath can find. This is evidence of a different
     * kind, and it is allowed to close those hatches.
     *
     * **It is a veto, never a trigger.** It can only ever suppress a point the pipeline
     * had already classified as stationary; it cannot cause one to be stored, and it is
     * not consulted at all once `isMoving` or `isVehicular` is true. The producer is
     * required to keep it conservative in the same direction — absent or ambiguous
     * evidence means `false` — and to bound how long it may stay `true`, so a wedged
     * sensor degrades to the previous behaviour instead of silencing the track.
     */
    val stillnessVeto: Boolean = false,
) {
    public companion object {
        /**
         * Matches `MotionConfig.bearingChangeCaptureDeg`.
         *
         * A road junction turns through ~90°, and for a long time this sat at 40° on the
         * reasoning that anything smaller was noise. That reasoning was measuring the
         * wrong thing. The comparison is against the heading at the last *stored* point,
         * not the last fix, so on a motorway slip road or a long bend the vehicle can
         * turn through 35° between stored points, over and over, and never once cross the
         * threshold — the track keeps the straight legs and drops every curve between
         * them. Bearing-change capture exists to put a vertex where the path bends, and
         * 40° declined to do that for the whole class of bends that are not junctions.
         *
         * 30° instead, which is below a motorway interchange and above what the engine's
         * own floors can produce as noise: the comparison already requires 15 m of
         * displacement, 1.5 m/s of speed and an accuracy circle under 50 m
         * ([com.field360.traker.geo.filter.TrackerConstants.bearingCaptureMinDist] and
         * neighbours), and a heading derived under those conditions does not wander 30°
         * while travelling in a straight line.
         *
         * The cost is vertices, and it is bounded: a fix can only be force-stored once it
         * has moved 15 m, so the worst case is a tighter spiral storing more of a corner
         * that a host asked to see. Set `0` to disable the stage entirely.
         */
        public const val DEFAULT_BEARING_CHANGE_CAPTURE_DEG: Int = 30
    }
}
