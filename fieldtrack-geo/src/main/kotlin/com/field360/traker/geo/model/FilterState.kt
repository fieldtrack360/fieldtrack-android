package com.field360.traker.geo.model

import kotlin.math.sqrt

/**
 * The entire mutable world of the acceptance pipeline, as one immutable value.
 *
 * The reference held this as twelve `@Volatile` fields on a static `KalmanLatLngFilter`
 * shared by the tracking service and the 15-minute worker. Its own class comment
 * conceded the compound updates were non-atomic and rested on a "service & worker
 * rarely overlap" assumption — which by construction fails about once an hour
 * (SOURCE-AUDIT A6). `setOrigin()` alone wrote five fields non-atomically, so a
 * concurrent reader could see a new origin with a stale `prevNetMeters` and flip the
 * departure decision.
 *
 * Here there is no shared mutable filter at all: a single [com.field360.traker.geo]
 * pipeline call takes a state and returns a new one, and `FixIngestor` owns the only
 * reference. That also makes the state trivially serialisable to the `filter_state`
 * table, which is what closes the cold-start teleport hole (A2, EC-51).
 *
 * @property variance P, in metres². Negative is the uninitialised sentinel.
 * @property elapsedNanos monotonic timestamp of the last fix folded into the filter.
 * @property lastHwVehicularNanos last time a *hardware* fix reported vehicular speed;
 *   drives the 10-minute NLP bypass so tunnels and parking garages still track (EC-32).
 * @property consecutiveRejectCount feeds the forced reset — the mechanism that
 *   guarantees the filter can never wedge permanently (EC-43).
 * @property lastSeenElapsedNanos monotonic stamp of the last structurally valid fix the
 *   pipeline was *handed*, whatever it decided about it. Deliberately distinct from
 *   [elapsedNanos], which advances only when a measurement is folded in: the difference
 *   between the two is precisely "fixes are arriving and being rejected", and that is not
 *   a signal blackout (EC-140a).
 * @property hardRejectRun consecutive fixes dropped by the two *unconditional* accuracy
 *   gates — stage 1.5's network-fix test and stage 3.5's moving ceiling. Neither touches
 *   [consecutiveRejectCount], which belongs to the sigma gate, so before this field the
 *   run had no bound and no escape at all (EC-139a).
 * @property origin net-displacement anchor. Movement is only published once net
 *   displacement from here passes the persistence test, which is what kills slow
 *   drift loops (EC-39).
 * @property movingMode latched once a real departure is confirmed; exited only after
 *   [settleCount] consecutive settled fixes (EC-60).
 * @property recoveryPending a held post-gap candidate awaiting confirmation by a
 *   second fix within [TrackerConstants.recoveryConfirmNear] metres (EC-40).
 * @property lastFixElapsedNanos burst gate anchor, keyed on **fix** time rather than
 *   delivery time. Keying it on delivery time is what made the reference reject whole
 *   batches, since batched fixes arrive milliseconds apart (SOURCE-AUDIT A5, EC-30).
 * @property lastCapturedBearingDeg heading at the last **stored** point, in `[0, 360)`,
 *   or [BEARING_UNSET]. Compared against the current heading to force a capture at a
 *   turn, which is what puts a vertex at the corner instead of a chord across it
 *   (EC-45). Deliberately keyed on the last *stored* point rather than the last fix:
 *   keying it on the last fix makes a long sweeping bend accumulate no difference at
 *   all, because each 12 s leg turns only a few degrees.
 */
public data class FilterState(
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val variance: Float = UNINITIALISED,
    val elapsedNanos: Long = 0L,
    val lastHwVehicularNanos: Long = 0L,
    val consecutiveRejectCount: Int = 0,
    val lastSeenElapsedNanos: Long = 0L,
    val hardRejectRun: Int = 0,
    val origin: GeoPoint? = null,
    val departCount: Int = 0,
    val prevNetMeters: Float = 0f,
    val movingMode: Boolean = false,
    val settleCount: Int = 0,
    val recoveryPending: GeoPoint? = null,
    val lastFixElapsedNanos: Long = 0L,
    val motionState: MotionState = MotionState.STOPPED,
    val stopPendingSinceNanos: Long = 0L,
    val lastCapturedBearingDeg: Float = BEARING_UNSET,
    /**
     * Velocity estimate, metres per second, north/east on the local tangent plane.
     *
     * Not the fix's hardware speed and not a copy of it: this is what the filter has
     * *inferred* from successive corrections. A position-only filter had no such term, so
     * against steady motion it lagged by a fixed amount every fix until the sigma gate
     * tripped — the "track jumps" defect (EC-44a).
     */
    val velocityNorthMps: Float = 0f,
    val velocityEastMps: Float = 0f,
    /**
     * Off-diagonal of the 2×2 covariance, m²/s. This is the term that lets a position
     * correction also inform the velocity — without it the velocity would never be
     * learned from position measurements at all.
     */
    val covPosVel: Float = 0f,
    /** Velocity variance, (m/s)². */
    val varianceVel: Float = VELOCITY_VARIANCE_SEED,
) {
    val isInitialised: Boolean get() = variance >= 0f

    val hasCapturedBearing: Boolean get() = lastCapturedBearingDeg >= 0f

    val isOriginSet: Boolean get() = origin != null

    /** Current filter accuracy, σ in metres. */
    public fun accuracy(): Float =
        if (variance < 0f) Float.MAX_VALUE else sqrt(variance)

    /** Anchor the net-displacement origin here and clear the departure tally. */
    public fun withOrigin(latitude: Double, longitude: Double): FilterState =
        copy(origin = GeoPoint(latitude, longitude), departCount = 0, prevNetMeters = 0f)

    /**
     * Clear movement latches. Called whenever a hard re-anchor happens (init, resume,
     * recovery reset, arrival) so the next departure is judged from scratch.
     *
     * The captured heading goes with them: after a re-anchor the stored heading belongs
     * to a leg that is no longer adjacent to the next one, so comparing against it would
     * report a turn that never happened.
     */
    public fun clearMovement(): FilterState = copy(
        movingMode = false,
        settleCount = 0,
        departCount = 0,
        prevNetMeters = 0f,
        lastCapturedBearingDeg = BEARING_UNSET,
    )

    public companion object {
        public const val UNINITIALISED: Float = -1f

        /** No heading captured yet. Negative, so it can never collide with `[0, 360)`. */
        public const val BEARING_UNSET: Float = -1f

        /**
         * Velocity variance a freshly seeded filter starts with, (m/s)².
         *
         * 25 ⇒ σ = 5 m/s: "could be walking, could be driving". Large enough that the
         * first corrected fix moves the velocity estimate decisively, small enough that
         * one noisy fix cannot fling it to an implausible speed.
         *
         * Declared here rather than on `KalmanFilter` so the dependency runs one way:
         * `filter` may know about `model`, never the reverse.
         */
        public const val VELOCITY_VARIANCE_SEED: Float = 25f
    }
}
