package com.field360.tracker.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
internal interface TrackPointDao {

    /** IGNORE, not REPLACE: the uuid makes a duplicate insert a no-op (EC-82). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(point: TrackPointEntity): Long

    /**
     * Ordered by the monotonic clock, not wall time — a mid-session clock change must
     * not reorder history (EC-88).
     */
    @Query(
        """
        SELECT * FROM track_point
        WHERE sessionId = :sessionId
        ORDER BY elapsedRealtimeNanos DESC
        LIMIT 1
        """,
    )
    suspend fun last(sessionId: String): TrackPointEntity?

    /**
     * `timeMs` filters, `id` orders — the two clocks are not interchangeable (EC-88a).
     *
     * A range query is a question about wall time ("show me Tuesday"), so the filter has
     * to use it. Ordering must not: one NTP correction mid-session reorders the result,
     * and a reordered point list draws as a polyline that crosses itself. Field capture
     * `90a38095` shows exactly that braid.
     *
     * `id` rather than `elapsedRealtimeNanos`, which is what [last] uses and what EC-88
     * says. The monotonic clock restarts at zero on reboot, so a session that spans one
     * sorts its entire post-reboot tail to the *front* — a worse failure than the wall
     * clock's. Insertion order has neither problem, and it is exact here because
     * `ClockGuard` drops out-of-order deliveries before they are ever stored (EC-92a):
     * nothing reaches this table except in the order it happened.
     */
    @Query(
        """
        SELECT * FROM track_point
        WHERE (:sessionId IS NULL OR sessionId = :sessionId)
          AND (:fromMs IS NULL OR timeMs >= :fromMs)
          AND (:toMs IS NULL OR timeMs <= :toMs)
        ORDER BY id
        LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun query(
        sessionId: String?,
        fromMs: Long?,
        toMs: Long?,
        limit: Int,
        offset: Int,
    ): List<TrackPointEntity>

    /** Ordered by insertion for the same reason as [query]. */
    @Query("SELECT * FROM track_point WHERE sessionId = :sessionId ORDER BY id")
    fun observe(sessionId: String): Flow<List<TrackPointEntity>>

    @Query(
        """
        SELECT COUNT(*) FROM track_point
        WHERE (:sessionId IS NULL OR sessionId = :sessionId)
          AND (:fromMs IS NULL OR timeMs >= :fromMs)
          AND (:toMs IS NULL OR timeMs <= :toMs)
        """,
    )
    suspend fun count(sessionId: String?, fromMs: Long?, toMs: Long?): Int

    @Query("SELECT COALESCE(MAX(odometerMeters), 0) FROM track_point")
    suspend fun odometer(): Double

    /** Pruning never touches rows belonging to an **open** session (EC-81). */
    @Query(
        """
        DELETE FROM track_point
        WHERE timeMs < :cutoffMs
          AND sessionId IN (SELECT id FROM track_session WHERE endedAtMs IS NOT NULL)
        """,
    )
    suspend fun prune(cutoffMs: Long): Int

    // ── upload queue (used only by the optional fieldtrack-sync artifact) ──────

    /**
     * Insertion order, for the same reason as [query] — and this is the query where getting
     * it wrong hurts most.
     *
     * `elapsedRealtimeNanos` restarts at zero on reboot, so a backlog that straddles one
     * sorts its entire post-reboot tail to the *front* of the queue. Every other ordering
     * query in this file moved to `id` for that reason (EC-88a); this one was left behind,
     * and it is the only one with no `sessionId` filter to bound the damage — it sorts the
     * whole table at once, across every session the device has ever recorded.
     *
     * A multi-day offline backlog is exactly what this SDK is built to survive and exactly
     * what spans a reboot, so the failure lands on the case that matters: the batches go up
     * shuffled, and a server that plots them in receipt order draws a braid. Nothing is lost
     * — `MAX_BATCHES_PER_DRAIN` keeps going until the table is empty and the wire carries
     * each point's own `time` — but the queue stops being FIFO precisely when the queue
     * being FIFO is the whole point.
     *
     * Exact here for the same reason it is exact in [query]: `ClockGuard` drops out-of-order
     * deliveries before they are ever stored (EC-92a), so nothing reaches this table except
     * in the order it happened.
     */
    @Query("SELECT * FROM track_point WHERE syncState = 0 ORDER BY id LIMIT :limit")
    suspend fun pendingUpload(limit: Int): List<TrackPointEntity>

    @Query("SELECT COUNT(*) FROM track_point WHERE syncState = 0")
    suspend fun pendingUploadCount(): Int

    /**
     * When the last confirmed upload happened, or null if none ever has.
     *
     * `syncTimeMs` is 0 on an unsynced row and on a row cleared by a 401 teardown, so the
     * MAX is over confirmed uploads only — which is what "last sync ≥ 16 minutes old"
     * (spec §3.4) has to be measured against.
     */
    @Query("SELECT MAX(syncTimeMs) FROM track_point WHERE syncTimeMs > 0")
    suspend fun lastSyncTimeMs(): Long?

    @Query("UPDATE track_point SET syncState = 1, syncTimeMs = :syncedAtMs WHERE uuid IN (:uuids)")
    suspend fun markSynced(uuids: List<String>, syncedAtMs: Long): Int

    @Query("UPDATE track_point SET syncState = 1, syncTimeMs = 0 WHERE syncState = 0")
    suspend fun clearQueue(): Int

    @Query("DELETE FROM track_point WHERE sessionId = :sessionId")
    suspend fun deleteSession(sessionId: String): Int
}

@Dao
internal interface TrackSessionDao {

    @Upsert
    suspend fun upsert(session: TrackSessionEntity)

    @Query("SELECT * FROM track_session WHERE id = :id")
    suspend fun byId(id: String): TrackSessionEntity?

    @Query("SELECT * FROM track_session WHERE endedAtMs IS NULL ORDER BY startedAtMs DESC LIMIT 1")
    suspend fun openSession(): TrackSessionEntity?

    @Query("SELECT * FROM track_session WHERE endedAtMs IS NULL ORDER BY startedAtMs DESC LIMIT 1")
    fun observeOpenSession(): Flow<TrackSessionEntity?>

    @Query(
        """
        SELECT * FROM track_session
        WHERE (:fromMs IS NULL OR startedAtMs >= :fromMs)
          AND (:toMs IS NULL OR startedAtMs <= :toMs)
        ORDER BY startedAtMs DESC
        """,
    )
    suspend fun range(fromMs: Long?, toMs: Long?): List<TrackSessionEntity>
}

@Dao
internal interface FixDecisionDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(decision: FixDecisionEntity)

    @Query(
        """
        SELECT * FROM fix_decision
        WHERE (:sessionId IS NULL OR sessionId = :sessionId)
        ORDER BY elapsedRealtimeNanos DESC
        LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun query(sessionId: String?, limit: Int, offset: Int): List<FixDecisionEntity>

    /** Ring cap by age (EC-87). */
    @Query("DELETE FROM fix_decision WHERE timeMs < :cutoffMs")
    suspend fun pruneOlderThan(cutoffMs: Long): Int

    /** Ring cap by count — keep the newest [keep] rows. */
    @Query(
        """
        DELETE FROM fix_decision
        WHERE id NOT IN (SELECT id FROM fix_decision ORDER BY id DESC LIMIT :keep)
        """,
    )
    suspend fun pruneToCount(keep: Int): Int
}

@Dao
internal interface FilterStateDao {

    @Upsert
    suspend fun save(state: FilterStateEntity)

    @Query("SELECT * FROM filter_state WHERE id = ${FilterStateEntity.SINGLETON_ID}")
    suspend fun load(): FilterStateEntity?

    @Query("DELETE FROM filter_state")
    suspend fun clear()
}

@Dao
internal interface ActivitySegmentDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(segment: ActivitySegmentEntity): Long

    @Query("SELECT * FROM activity_segment WHERE isOngoing = 1 ORDER BY startTimeMs DESC LIMIT 1")
    suspend fun openSegment(): ActivitySegmentEntity?

    @Query("UPDATE activity_segment SET endTimeMs = :endTimeMs, isOngoing = 0 WHERE id = :id")
    suspend fun close(id: Long, endTimeMs: Long)

    /** Segments older than 24 h are auto-closed on restore rather than left dangling. */
    @Query("UPDATE activity_segment SET endTimeMs = :nowMs, isOngoing = 0 WHERE isOngoing = 1 AND startTimeMs < :cutoffMs")
    suspend fun autoCloseStale(cutoffMs: Long, nowMs: Long): Int

    @Query("SELECT * FROM activity_segment WHERE startTimeMs BETWEEN :fromMs AND :toMs ORDER BY startTimeMs")
    suspend fun range(fromMs: Long, toMs: Long): List<ActivitySegmentEntity>
}

@Dao
internal interface RawFixDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(fix: RawFixEntity)

    /**
     * Insertion order, which is *delivery* order and deliberately not fix order.
     *
     * This table is written before `ClockGuard` runs — that is the point of it, the raw
     * layer has to show what the OS actually handed over — so unlike `track_point` it
     * does contain out-of-order deliveries, and sorting by `elapsedRealtimeNanos` here
     * would be right within a boot and catastrophic across one. Neither column alone is
     * the answer: `ClockGuard.inFixOrder` finishes the job in Kotlin, where a reboot can
     * be told from a straggler.
     */
    @Query("SELECT * FROM raw_fix WHERE sessionId = :sessionId ORDER BY id")
    suspend fun bySession(sessionId: String): List<RawFixEntity>

    @Query("DELETE FROM raw_fix WHERE id NOT IN (SELECT id FROM raw_fix ORDER BY id DESC LIMIT :keep)")
    suspend fun trimTo(keep: Int): Int
}

@Dao
internal interface RawPointDao {

    /** IGNORE for the same reason `track_point` uses it: the uuid makes a retry a no-op. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(point: RawPointEntity): Long

    /**
     * Insertion order, which is delivery order.
     *
     * Same reasoning as `raw_fix.bySession`: this table is written per fix as it was
     * judged, so it contains the out-of-order deliveries `track_point` never sees.
     * Sorting on `elapsedRealtimeNanos` would be right within a boot and catastrophic
     * across one — `ClockGuard.inFixOrder` is what resolves that, in Kotlin.
     */
    @Query("SELECT * FROM raw_points WHERE sessionId = :sessionId ORDER BY id")
    suspend fun bySession(sessionId: String): List<RawPointEntity>

    /** Only the discarded ones — the question this table was added to answer. */
    @Query(
        """
        SELECT * FROM raw_points
        WHERE sessionId = :sessionId AND verdict != :accepted
        ORDER BY id
        """,
    )
    suspend fun rejectedBySession(sessionId: String, accepted: String = ACCEPT): List<RawPointEntity>

    @Query("SELECT COUNT(*) FROM raw_points WHERE (:sessionId IS NULL OR sessionId = :sessionId)")
    suspend fun count(sessionId: String?): Int

    /**
     * Per-session ring, deliberately not the global one `raw_fix` uses.
     *
     * A global cap means one long session evicts every earlier session's diagnostics,
     * which is precisely when you want them: the comparison that explains a bad session
     * is usually the good session before it.
     */
    @Query(
        """
        DELETE FROM raw_points
        WHERE sessionId = :sessionId
          AND id NOT IN (
            SELECT id FROM raw_points WHERE sessionId = :sessionId ORDER BY id DESC LIMIT :keep
          )
        """,
    )
    suspend fun trimSessionTo(sessionId: String, keep: Int): Int

    @Query("DELETE FROM raw_points WHERE sessionId = :sessionId")
    suspend fun deleteSession(sessionId: String): Int

    companion object {
        /** Matches `Verdict.Accept::class.simpleName.uppercase()`, which is what is stored. */
        const val ACCEPT: String = "ACCEPT"
    }
}
