package com.field360.traker.geo.model

/**
 * An accepted, stored point.
 *
 * Carries both clocks: [timeMs] for display and day-bucketing, [elapsedRealtimeNanos]
 * so a point restored from the database can re-seed the filter with its **real**
 * observation time. The reference lost that on the database round-trip — `toLatLng()`
 * never mapped `time`, so a restored anchor claimed to have been observed *now*, which
 * routed the first post-restart fix into an unconditional accept (SOURCE-AUDIT A2,
 * EC-51).
 *
 * @property timezone IANA id, stored **per point** — a session can cross zones on a
 *   flight and the day summary must group by the point's own zone (EC-89).
 * @property acceptReason the [Reasons] string this point was accepted under. Kept on
 *   the row because it is the field-debugging language.
 */
public data class TrackPoint(
    val id: Long = 0,
    val uuid: String,
    val sessionId: String,
    val timeMs: Long,
    val elapsedRealtimeNanos: Long,
    val localDate: String,
    val timezone: String,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val altitude: Double? = null,
    val speedMps: Float = 0f,
    val bearingDeg: Float = 0f,
    val hasSpeed: Boolean = false,
    val hasBearing: Boolean = false,
    val provider: String = TrackFix.UNKNOWN_PROVIDER,
    val isMock: Boolean = false,
    val movementStatus: MovementStatus = MovementStatus.STEADY,
    val detectedActivity: ActivityType? = null,
    val activityStartTimeMs: Long = 0,
    val odometerMeters: Double = 0.0,
    val batteryPct: Int? = null,
    val isCharging: Boolean? = null,
    val extras: String? = null,
    /**
     * Device-integrity bitmask observed when this point was captured — see
     * `IntegrityReport.flags` in `fieldtrack-core`. `0` on every point captured by a
     * debuggable build, by a host that switched the layer off, and by any build predating
     * it.
     */
    val integrityFlags: Int = 0,
    /**
     * [ProviderSnapshot.toFlags] for the location subsystem when this point was captured —
     * providers, the master switch, permission tier, accuracy authorization and airplane
     * mode. [ProviderSnapshot.NOT_RECORDED] on every point captured before it existed.
     */
    val providerFlags: Int = ProviderSnapshot.NOT_RECORDED,
    val acceptReason: String,
)

/** Immutable geographic point. Used for anchors and plotting geometry. */
public data class GeoPoint(val latitude: Double, val longitude: Double)

/** Bounding box. Null rather than NaN-filled when a track has no points (EC-93). */
@kotlinx.serialization.Serializable
public data class Bounds(
    val north: Double,
    val south: Double,
    val east: Double,
    val west: Double,
)
