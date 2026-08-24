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
) {
    public companion object {
        /** Matches `MotionConfig.bearingChangeCaptureDeg`; a normal road junction is ~90°. */
        public const val DEFAULT_BEARING_CHANGE_CAPTURE_DEG: Int = 40
    }
}
