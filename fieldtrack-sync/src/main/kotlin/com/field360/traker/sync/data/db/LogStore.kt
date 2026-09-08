/*
 * `data.db`, not `internal.db`, and the difference is load-bearing.
 *
 * Room resolves `LogDatabase_Impl` through `Class.forName(name + "_Impl")`, so both ends of
 * that lookup are pinned by name and R8 can neither rename nor repackage them — they ship in
 * whatever package this file declares. `sync.internal.**` is on `verifyReleaseObfuscation`'s
 * forbidden list (a leaked implementation package is what that check exists to catch), so
 * declaring them there fails the release build. `fieldtrack-core` puts its own Room classes
 * in `data.db` for exactly this reason; this mirrors it.
 *
 * Everything else in `sync.internal` is free to move because nothing pins it: those are
 * top-level functions R8 renames and repackages into `tr.dev.sync` like any other internal.
 */
package com.field360.traker.sync.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert

/**
 * One diagnostic entry, waiting to be shipped.
 *
 * A bounded, append-only ring: new entries push out the oldest once the configured
 * capacity is passed, and the gap that leaves in [seq] is what tells the server entries
 * were lost rather than nothing having happened.
 *
 * @property uid the wire id — `SHA-1("<sessionId>:<seq>:<type>:<elapsedRealtimeNanos>")`.
 *   Unique-indexed so a double-record is dropped here rather than sent twice.
 * @property sessionId `null` for an entry emitted outside any session. Legal, and
 *   sometimes the only correct answer — a boot belongs to the device, not to whichever
 *   session was current at upload time.
 * @property data JSON as text, or `null`. Never inspected beyond "is it valid JSON".
 * @property syncState `0` queued, `1` shipped or dropped.
 */
@Entity(
    tableName = "log_entry",
    indices = [
        Index(value = ["uid"], unique = true),
        Index("syncState"),
        Index("timeMs"),
    ],
)
internal data class LogEntryRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uid: String,
    val sessionId: String?,
    val seq: Long,
    val timeMs: Long,
    val elapsedRealtimeNanos: Long,
    val level: String,
    val type: String,
    val tag: String,
    val code: String?,
    val message: String,
    val data: String?,
    val syncState: Int = 0,
)

/**
 * The two counters that have to outlive the process.
 *
 * A sequence number restarting at 0 after a process death would replay ids the server has
 * already stored, and a decision watermark that forgot itself would re-ship the whole
 * decision log on every launch. Both are one integer keyed by a string, so they share a
 * table rather than earning two.
 */
@Entity(tableName = "log_counter")
internal data class LogCounterRow(
    @PrimaryKey val key: String,
    val value: Long,
)

@Dao
internal interface LogDao {

    /**
     * IGNORE, on the same reasoning as `track_point`: `uid` is derived, so a double-record
     * of the same entry is a no-op rather than a row that gets uploaded twice.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entry: LogEntryRow): Long

    @Query("SELECT * FROM log_entry WHERE syncState = 0 ORDER BY id LIMIT :limit")
    suspend fun pending(limit: Int): List<LogEntryRow>

    @Query("SELECT COUNT(*) FROM log_entry WHERE syncState = 0")
    suspend fun pendingCount(): Int

    /**
     * Settles a contiguous prefix.
     *
     * Exact because the queue filters on nothing but `syncState` — every level and type
     * decision is made before a row exists — so a batch is always a prefix and there is no
     * row below the cursor still owed a send.
     */
    @Query("UPDATE log_entry SET syncState = 1 WHERE syncState = 0 AND id <= :cursor")
    suspend fun markSettled(cursor: Long): Int

    @Query("SELECT COUNT(*) FROM log_entry")
    suspend fun count(): Int

    /**
     * The ring: keep the newest [keep] rows, whatever their sync state.
     *
     * Shipped and unshipped alike, deliberately. A bounded buffer that refused to evict
     * unsent rows is an unbounded buffer on exactly the device that cannot reach a server
     * — which is the device most likely to be recording something worth reading.
     */
    @Query("DELETE FROM log_entry WHERE id NOT IN (SELECT id FROM log_entry ORDER BY id DESC LIMIT :keep)")
    suspend fun trimTo(keep: Int): Int

    /** Age-based prune of settled rows only. Anything still queued is left alone. */
    @Query("DELETE FROM log_entry WHERE syncState = 1 AND timeMs < :cutoffMs")
    suspend fun pruneSettledOlderThan(cutoffMs: Long): Int

    @Query("DELETE FROM log_entry")
    suspend fun clear(): Int

    @Query(
        """
        SELECT * FROM log_entry
        WHERE (:sessionId IS NULL OR sessionId = :sessionId)
        ORDER BY id DESC
        LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun query(sessionId: String?, limit: Int, offset: Int): List<LogEntryRow>

    @Query("SELECT value FROM log_counter WHERE key = :key")
    suspend fun counter(key: String): Long?

    @Upsert
    suspend fun putCounter(row: LogCounterRow)
}

/**
 * The diagnostic buffer's own database.
 *
 * Separate from `fieldtrack-core`'s on purpose. The log channel lives entirely in this
 * module, so a host that never depends on `fieldtrack-sync` carries no table it can never
 * write — and this schema can move without touching a migration path that guards a
 * customer's positions.
 */
@Database(
    entities = [LogEntryRow::class, LogCounterRow::class],
    version = 1,
    // Same rule as core: schemas are committed, migrations are explicit, and
    // fallbackToDestructiveMigration() is never called.
    exportSchema = true,
)
internal abstract class LogDatabase : RoomDatabase() {
    abstract fun logs(): LogDao

    companion object {
        /** Package-scoped, so two apps embedding the SDK cannot collide. */
        fun nameFor(context: Context): String = "fieldtrack-logs-${context.packageName}.db"

        fun build(context: Context): LogDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                LogDatabase::class.java,
                nameFor(context),
            )
                // WAL so a read for a debug screen never blocks the recorder's writes.
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
    }
}

/** `seq` is per session **and** per type — see `LogRecord.seq`. */
internal fun seqKey(sessionId: String?, type: String): String = "seq:$sessionId:$type"

/**
 * How far the decision log has been shipped for one session, as an
 * `elapsedRealtimeNanos`.
 *
 * A watermark rather than a row flag, because the decision log belongs to core and this
 * module reads it through the public query API — there is no column here to mark. Re-ship
 * after a reboot resets the clock is harmless: the wire id is derived from the same
 * timestamp, so the server dedupes it.
 */
internal fun decisionWatermarkKey(sessionId: String): String = "decision:$sessionId"
