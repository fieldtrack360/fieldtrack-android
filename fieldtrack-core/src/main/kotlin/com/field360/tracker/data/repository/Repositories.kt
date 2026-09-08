package com.field360.tracker.data.repository

import com.field360.tracker.TrackerConfig
import com.field360.tracker.data.db.FixDecisionDao
import com.field360.tracker.data.db.TrackPointDao
import com.field360.tracker.data.db.TrackSessionDao
import com.field360.tracker.data.db.toDomain
import com.field360.tracker.data.db.toEntity
import com.field360.tracker.domain.model.PointQuery
import com.field360.tracker.domain.model.TrackSession
import com.field360.tracker.domain.repository.ConfigRepository
import com.field360.tracker.domain.repository.DecisionRepository
import com.field360.tracker.domain.repository.PendingUploadStore
import com.field360.tracker.domain.repository.SessionRepository
import com.field360.tracker.domain.repository.TrackPointRepository
import com.field360.traker.geo.model.FixDecision
import com.field360.traker.geo.model.MotionState
import com.field360.traker.geo.model.TrackFix
import com.field360.traker.geo.model.TrackPoint
import com.field360.traker.geo.model.Verdict
import com.field360.traker.geo.port.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

internal class TrackPointRepositoryImpl(
    private val dao: TrackPointDao,
) : TrackPointRepository {

    override suspend fun query(query: PointQuery): List<TrackPoint> =
        dao.query(query.sessionId, query.fromMs, query.toMs, query.limit, query.offset)
            .map { it.toDomain() }

    override fun observe(sessionId: String): Flow<List<TrackPoint>> =
        dao.observe(sessionId).map { rows -> rows.map { it.toDomain() } }

    override suspend fun count(query: PointQuery): Int =
        dao.count(query.sessionId, query.fromMs, query.toMs)

    override suspend fun insert(point: TrackPoint): Long = dao.insert(point.toEntity())

    override suspend fun delete(query: PointQuery): Int =
        query.sessionId?.let { dao.deleteSession(it) } ?: 0

    override suspend fun odometerMeters(): Double = dao.odometer()

    override suspend fun prune(cutoffMs: Long): Int = dao.prune(cutoffMs)
}

/**
 * Drains through the public [PendingUploadStore] seam, so `fieldtrack-sync` never sees a
 * Room type and core never sees a socket.
 */
internal class PendingUploadStoreImpl(
    private val dao: TrackPointDao,
) : PendingUploadStore {

    override suspend fun pending(limit: Int): List<TrackPoint> =
        dao.pendingUpload(limit).map { it.toDomain() }

    override suspend fun pendingCount(): Int = dao.pendingUploadCount()

    override suspend fun markSynced(uuids: List<String>, syncedAtMs: Long) {
        if (uuids.isEmpty()) return
        dao.markSynced(uuids, syncedAtMs)
    }

    override suspend fun clearQueue() {
        dao.clearQueue()
    }
}

internal class SessionRepositoryImpl(
    private val dao: TrackSessionDao,
    private val clock: Clock,
) : SessionRepository {

    override suspend fun open(tag: String?, configSnapshot: String?): TrackSession {
        // start() is idempotent: an already-open session is returned rather than a
        // second one being created alongside it (EC-72).
        dao.openSession()?.let { return it.toDomain() }

        val startedAtMs = clock.wallTimeMs()
        val session = TrackSession(
            id = newSessionId(startedAtMs),
            startedAtMs = startedAtMs,
            startedAtElapsedNanos = clock.elapsedRealtimeNanos(),
            tag = tag,
            configSnapshot = configSnapshot,
        )
        dao.upsert(session.toEntity())
        return session
    }

    override suspend fun close(sessionId: String): TrackSession? {
        val existing = dao.byId(sessionId) ?: return null
        val closed = existing.copy(endedAtMs = clock.wallTimeMs())
        dao.upsert(closed)
        return closed.toDomain()
    }

    override suspend fun current(): TrackSession? = dao.openSession()?.toDomain()

    override fun observeCurrent(): Flow<TrackSession?> =
        dao.observeOpenSession().map { it?.toDomain() }

    override suspend fun range(fromMs: Long?, toMs: Long?): List<TrackSession> =
        dao.range(fromMs, toMs).map { it.toDomain() }

    /**
     * `20260907-143512-1f0c8a2e` — the start time a human can read, then eight random hex
     * characters.
     *
     * A bare UUID told a support engineer reading a log or an exported filename nothing
     * about *when* the run happened, which is the first thing anyone asks. The timestamp
     * prefix also makes ids sort chronologically as plain strings.
     *
     * The random suffix is not decoration: two sessions can open inside the same second
     * (a stop/start, a crash recovery), and the id is a primary key that points reference.
     *
     * Rendered in the **device's** zone at the moment of the call, so it is readable to
     * whoever is holding the phone rather than to a server in UTC. That makes it a label,
     * not a timestamp: it is not unique across a zone change, it never round-trips back to
     * an instant, and nothing should parse it. [TrackSession.startedAtMs] stays the
     * authoritative start, and each point still carries its own `timezone` (EC-89).
     */
    private fun newSessionId(startedAtMs: Long): String {
        val stamp = SESSION_ID_FORMAT
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(startedAtMs))
        return "$stamp-" + UUID.randomUUID().toString().take(SESSION_ID_SUFFIX_LENGTH)
    }

    private companion object {
        /** No colons or spaces: session ids land in filenames, log lines and URLs. */
        val SESSION_ID_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        const val SESSION_ID_SUFFIX_LENGTH = 8
    }
}

internal class DecisionRepositoryImpl(
    private val dao: FixDecisionDao,
) : DecisionRepository {

    override suspend fun query(sessionId: String?, limit: Int, offset: Int): List<FixDecision> =
        dao.query(sessionId, limit, offset).map { row ->
            FixDecision(
                fix = TrackFix(
                    timeMs = row.timeMs,
                    elapsedRealtimeNanos = row.elapsedRealtimeNanos,
                    receivedAtElapsedNanos = row.elapsedRealtimeNanos,
                    latitude = row.latitude,
                    longitude = row.longitude,
                    accuracy = row.accuracy,
                    bearingDeg = row.bearingDeg,
                    hasSpeed = row.hasSpeed,
                    hasBearing = row.hasBearing,
                ),
                verdict = when (row.verdict) {
                    "ACCEPT" -> Verdict.Accept(row.reason)
                    "SKIP" -> Verdict.Skip(row.reason)
                    else -> Verdict.Reject(row.reason)
                },
                filterLat = row.filterLat,
                filterLng = row.filterLng,
                sigma = row.sigma,
                threshold = row.threshold,
                distanceMovedM = row.distanceMovedM,
                effectiveSpeedMps = row.effectiveSpeedMps,
                motionState = runCatching { MotionState.valueOf(row.motionState) }
                    .getOrDefault(MotionState.STOPPED),
            )
        }

    /** Capped by age AND count, so a long trip cannot bloat the database (EC-87). */
    override suspend fun prune(cutoffMs: Long, maxRows: Int) {
        dao.pruneOlderThan(cutoffMs)
        if (maxRows > 0) dao.pruneToCount(maxRows)
    }
}

/** Config persistence. Unknown keys are dropped so a downgrade cannot brick startup (EC-124). */
internal class ConfigRepositoryImpl(
    private val store: ConfigStore,
) : ConfigRepository {
    override suspend fun load(): TrackerConfig? = store.load()
    override suspend fun save(config: TrackerConfig) = store.save(config)
    override suspend fun clear() = store.clear()
}
