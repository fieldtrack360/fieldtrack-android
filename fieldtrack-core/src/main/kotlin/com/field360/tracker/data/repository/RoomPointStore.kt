package com.field360.tracker.data.repository

import android.database.sqlite.SQLiteFullException
import com.field360.tracker.data.db.FilterStateDao
import com.field360.tracker.data.db.FixDecisionDao
import com.field360.tracker.data.db.RawFixDao
import com.field360.tracker.data.db.RawFixEntity
import com.field360.tracker.data.db.RawPointDao
import com.field360.tracker.data.db.TrackPointDao
import com.field360.tracker.data.db.rawPointEntity
import com.field360.tracker.data.db.toDomain
import com.field360.tracker.data.db.toEntity
import com.field360.tracker.domain.model.ErrorCode
import com.field360.tracker.domain.model.TrackerEvent
import com.field360.traker.geo.model.FilterState
import com.field360.traker.geo.model.FixDecision
import com.field360.traker.geo.model.IngestContext
import com.field360.traker.geo.model.TrackFix
import com.field360.traker.geo.model.TrackPoint
import com.field360.traker.geo.port.PointStore
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * The Room-backed implementation of the engine's [PointStore] port.
 *
 * This class is the *only* place `fieldtrack-geo` touches storage, which is what keeps
 * the engine free of Android and testable on the JVM (PLAN.md §3).
 *
 * @param sessionIdProvider decision rows need the session they belong to, but the
 *   engine has no concept of one — it is supplied here rather than widening the port.
 */
internal class RoomPointStore(
    private val points: TrackPointDao,
    private val filterState: FilterStateDao,
    private val decisions: FixDecisionDao,
    private val rawFixes: RawFixDao,
    private val rawPoints: RawPointDao,
    private val events: MutableSharedFlow<TrackerEvent>,
) : PointStore {

    @Volatile
    var currentSessionId: String? = null

    @Volatile
    var persistDecisions: Boolean = true

    override suspend fun lastPoint(sessionId: String): TrackPoint? =
        points.last(sessionId)?.toDomain()

    override suspend fun insert(point: TrackPoint): Long = try {
        points.insert(point.toEntity())
    } catch (e: SQLiteFullException) {
        // EC-78: a full disk must not crash the host or silently swallow the fix.
        events.tryEmit(TrackerEvent.Error(ErrorCode.STORAGE_FULL, e.message.orEmpty()))
        -1L
    }

    override suspend fun loadFilterState(): FilterState? = filterState.load()?.toDomain()

    override suspend fun saveFilterState(state: FilterState) {
        runCatching { filterState.save(state.toEntity()) }
            .onFailure { events.tryEmit(TrackerEvent.Error(ErrorCode.STORAGE_FULL, it.message.orEmpty())) }
    }

    /** Debug ring buffer; trimmed on every write so it cannot grow without bound. */
    suspend fun recordRawFix(fix: TrackFix, sessionId: String, capacity: Int, integrityFlags: Int = 0) {
        runCatching {
            rawFixes.insert(
                RawFixEntity(
                    sessionId = sessionId,
                    timeMs = fix.timeMs,
                    elapsedRealtimeNanos = fix.elapsedRealtimeNanos,
                    latitude = fix.latitude,
                    longitude = fix.longitude,
                    accuracy = fix.accuracy,
                    speedMps = fix.speedMps,
                    bearingDeg = fix.bearingDeg,
                    hasSpeed = fix.hasSpeed,
                    hasBearing = fix.hasBearing,
                    provider = fix.provider,
                    speedAccuracyMps = fix.speedAccuracyMps,
                    bearingAccuracyDeg = fix.bearingAccuracyDeg,
                    integrityFlags = integrityFlags,
                ),
            )
            rawFixes.trimTo(capacity)
            // Unlike `insert`/`saveFilterState`, this failed silently before — a debug-only
            // table with no event on failure meant `persistRawFixes = true` could look
            // correctly wired and still leave the table empty, with nothing in the event
            // stream to say why.
        }.onFailure { events.tryEmit(TrackerEvent.Diagnostic("recordRawFix failed: ${it.message}")) }
    }

    /**
     * One point-form row per judged fix, accepted or not (v6).
     *
     * Trimmed per session on every write, so a runaway session bounds itself without
     * touching the diagnostics of the sessions before it.
     */
    suspend fun recordRawPoint(
        decision: FixDecision,
        context: IngestContext,
        point: TrackPoint?,
        capacity: Int,
    ) {
        runCatching {
            rawPoints.insert(
                rawPointEntity(
                    decision = decision,
                    sessionId = context.sessionId,
                    localDate = context.localDate,
                    timezone = context.timezone,
                    detectedActivity = context.detectedActivity?.name,
                    activityStartTimeMs = context.activityStartTimeMs,
                    odometerMeters = context.odometerMeters,
                    batteryPct = context.batteryPct,
                    isCharging = context.isCharging,
                    extras = context.extras,
                    integrityFlags = context.integrityFlags,
                    providerFlags = context.providerFlags,
                    point = point,
                ),
            )
            rawPoints.trimSessionTo(context.sessionId, capacity)
        }.onFailure {
            // Same contract as `saveFilterState`: a full disk is reported, never thrown.
            // This layer is diagnostics — losing a row here must not cost a real point.
            events.tryEmit(TrackerEvent.Error(ErrorCode.STORAGE_FULL, it.message.orEmpty()))
        }
    }

    override suspend fun recordDecision(decision: FixDecision) {
        if (!persistDecisions) return
        val session = currentSessionId ?: return
        runCatching { decisions.insert(decision.toEntity(session)) }
    }
}
