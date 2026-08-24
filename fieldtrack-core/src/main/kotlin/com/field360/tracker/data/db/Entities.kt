package com.field360.tracker.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * @property uuid `sha1(sessionId + elapsedRealtimeNanos)`. Two writers racing to store
 *   the same fix — the stream and the 15-minute backstop — derive the same id, so the
 *   duplicate insert is ignored rather than stored twice (EC-82).
 * @property timezone stored per point, because a session can cross zones (EC-89).
 * @property syncState only ever touched by the optional `fieldtrack-sync` artifact; core
 *   never opens a socket.
 */
@Entity(
    tableName = "track_point",
    indices = [
        Index("timeMs"),
        Index("sessionId", "timeMs"),
        Index("localDate"),
        Index(value = ["uuid"], unique = true),
    ],
    foreignKeys = [
        ForeignKey(
            entity = TrackSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
internal data class TrackPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,
    val sessionId: String,
    val timeMs: Long,
    val elapsedRealtimeNanos: Long,
    val localDate: String,
    val timezone: String,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val altitude: Double?,
    val speedMps: Float,
    val bearingDeg: Float,
    val hasSpeed: Boolean,
    val hasBearing: Boolean,
    val provider: String,
    val isMock: Boolean,
    val movementStatus: String,
    val detectedActivity: String?,
    val activityStartTimeMs: Long,
    val odometerMeters: Double,
    val batteryPct: Int?,
    val isCharging: Boolean?,
    val extras: String?,
    /** `IntegrityReport.flags` when this point was captured; `0` before v7 and in debug (v7). */
    val integrityFlags: Int = 0,
    /** `ProviderSnapshot.toFlags()` when this point was captured; `0` = not recorded (v8). */
    val providerFlags: Int = 0,
    val acceptReason: String,
    val syncState: Int = 0,
    val syncTimeMs: Long = 0,
)

@Entity(tableName = "track_session", indices = [Index("startedAtMs")])
internal data class TrackSessionEntity(
    @PrimaryKey val id: String,
    val startedAtMs: Long,
    val startedAtElapsedNanos: Long,
    val endedAtMs: Long? = null,
    val tag: String? = null,
    val configSnapshot: String? = null,
)

/**
 * The decision log: why every fix was accepted, skipped or rejected.
 *
 * A queryable table rather than a log file, which is what turns an accuracy complaint
 * into a replayable regression test. Ring-capped by both count and age so a long trip
 * cannot bloat the database (EC-87).
 */
@Entity(tableName = "fix_decision", indices = [Index("timeMs"), Index("sessionId")])
internal data class FixDecisionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val timeMs: Long,
    val elapsedRealtimeNanos: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val bearingDeg: Float,
    val hasSpeed: Boolean,
    val hasBearing: Boolean,
    val verdict: String,
    val reason: String,
    val filterLat: Double,
    val filterLng: Double,
    val sigma: Float,
    val threshold: Float,
    val distanceMovedM: Double,
    val effectiveSpeedMps: Float,
    val motionState: String,
)

/**
 * Single-row filter state.
 *
 * Persisting this is not optional: it is what stops the first fix after process death
 * from being blind-accepted wherever it happens to land (SOURCE-AUDIT A2, EC-51).
 */
@Entity(tableName = "filter_state")
internal data class FilterStateEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val lat: Double,
    val lng: Double,
    val variance: Float,
    val elapsedNanos: Long,
    val lastHwVehicularNanos: Long,
    val consecutiveRejectCount: Int,
    val originLat: Double?,
    val originLng: Double?,
    val departCount: Int,
    val prevNetMeters: Float,
    val movingMode: Boolean,
    val settleCount: Int,
    val recoveryLat: Double?,
    val recoveryLng: Double?,
    val lastFixElapsedNanos: Long,
    val motionState: String,
    val stopPendingSinceNanos: Long,
    /** `-1` = none captured yet, matching `FilterState.BEARING_UNSET` (EC-45). */
    val lastCapturedBearingDeg: Float = -1f,
    /** Constant-velocity state: m/s north/east, plus its 2×2 covariance (EC-44a). */
    val velocityNorthMps: Float = 0f,
    val velocityEastMps: Float = 0f,
    val covPosVel: Float = 0f,
    val varianceVel: Float = 25f,
) {
    companion object {
        const val SINGLETON_ID: Int = 1
    }
}

/** Debug ring buffer of raw fixes, off by default (`persistRawFixes`). */
@Entity(tableName = "raw_fix", indices = [Index("elapsedRealtimeNanos")])
internal data class RawFixEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val timeMs: Long,
    val elapsedRealtimeNanos: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val speedMps: Float,
    val bearingDeg: Float,
    val hasSpeed: Boolean,
    val hasBearing: Boolean,
    val provider: String,
    /** `null` = the platform reported no confidence — deliberately not 0 (v5). */
    val speedAccuracyMps: Float? = null,
    val bearingAccuracyDeg: Float? = null,
    /** `IntegrityReport.flags` when this fix was received; `0` before v7 and in debug (v7). */
    val integrityFlags: Int = 0,
)

/**
 * Every fix in **point form**, whatever the pipeline decided (v6).
 *
 * The three diagnostic layers answer three different questions and this is the third:
 *  - `raw_fix` is what the OS handed over, in fix shape, before any gate ran;
 *  - `fix_decision` is the numeric argument for one verdict — sigma, threshold, distance;
 *  - this is what the point *would have been*, in the same columns as `track_point`.
 *
 * That last one is the reason it exists. Comparing a rejected candidate against the
 * points around it previously meant joining three shapes by hand; here the accepted and
 * the discarded read out of one query with identical columns, and [uuid] is the join key
 * back to `track_point` for the ones that made it (`Uuids.forFix` derives the same id on
 * both sides).
 *
 * Off by default (`persistRawPoints`): a row per fix in a table this wide is real write
 * amplification, and a host that does not need it should not pay for it.
 *
 * @property verdict `ACCEPT`, `SKIP` or `REJECT`. Stored as the name rather than joined
 *   from `fix_decision`, so this table stands alone.
 * @property odometerMeters the running total *as of this fix*. On a reject it is
 *   unchanged from the last accepted point, which is the honest value — a discarded fix
 *   moves nobody.
 * @property movementStatus only the pipeline's accepted branch derives this, so a
 *   non-accepted row carries the status of the fix it was measured against.
 */
@Entity(
    tableName = "raw_points",
    indices = [
        Index("sessionId", "timeMs"),
        Index("timeMs"),
        Index(value = ["uuid"], unique = true),
    ],
    foreignKeys = [
        ForeignKey(
            entity = TrackSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
internal data class RawPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,
    val sessionId: String,
    val timeMs: Long,
    val elapsedRealtimeNanos: Long,
    val localDate: String,
    val timezone: String,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val altitude: Double?,
    val speedMps: Float,
    val bearingDeg: Float,
    val hasSpeed: Boolean,
    val hasBearing: Boolean,
    val provider: String,
    val isMock: Boolean,
    val movementStatus: String,
    val detectedActivity: String?,
    val activityStartTimeMs: Long,
    val odometerMeters: Double,
    val batteryPct: Int?,
    val isCharging: Boolean?,
    val extras: String?,
    /** `IntegrityReport.flags` when this fix was judged; `0` before v7 and in debug (v7). */
    val integrityFlags: Int = 0,
    /** `ProviderSnapshot.toFlags()` when this fix was judged; `0` = not recorded (v8). */
    val providerFlags: Int = 0,
    val verdict: String,
    val reason: String,
)

/** Open/close pairs from activity recognition; auto-closed after 24 h on restore. */
@Entity(tableName = "activity_segment", indices = [Index("startTimeMs")])
internal data class ActivitySegmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val activityType: String,
    val startTimeMs: Long,
    val endTimeMs: Long? = null,
    val isOngoing: Boolean = true,
)
