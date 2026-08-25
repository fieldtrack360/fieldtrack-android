package com.field360.tracker.work

import com.field360.tracker.data.db.TrackPointDao
import com.field360.tracker.domain.repository.SyncTrigger
import com.field360.traker.geo.port.Clock
import com.field360.traker.geo.port.TrackLogger
import com.field360.tracker.sdkLog

/**
 * The two facts the scheduler needs about the queue.
 *
 * A two-method interface rather than the DAO itself, so the decision — throttle, staleness,
 * whether to ask at all — is testable without standing up Room, and so this class cannot
 * quietly grow a dependency on the rest of the table.
 */
internal interface UploadQueueStats {
    suspend fun pendingCount(): Int

    /** Last confirmed upload, or null if there has never been one. */
    suspend fun lastSyncTimeMs(): Long?
}

internal class DaoUploadQueueStats(private val dao: TrackPointDao) : UploadQueueStats {
    override suspend fun pendingCount(): Int = dao.pendingUploadCount()
    override suspend fun lastSyncTimeMs(): Long? = dao.lastSyncTimeMs()
}

/**
 * Pulls the upload trigger.
 *
 * The queue, batching, backoff and 401 teardown in `fieldtrack-sync` all worked; nothing ever
 * started them. `SyncConfig.autoSync` was documented as "upload as points arrive" and read
 * nowhere, so a host that configured sync and never called `syncNow()` itself accumulated
 * rows forever (GAPS.md G-4, spec §3.4 step 3 and §12.2 check 3).
 *
 * Two callers, deliberately:
 *
 * - [onAcceptedPoint], from the ingest path, which is what makes `autoSync` mean anything.
 * - [onSupervisionTick], from the health loop and the periodic backstop, which is the
 *   safety net for everything the point path cannot see: a drain that failed while the app
 *   was dead, a queue left over from a previous session, a stationary user who is storing
 *   nothing to trigger on.
 *
 * Does nothing at all when no [SyncTrigger] is registered — a host without the sync
 * artifact, or with `autoSync` off, pays for none of this.
 */
internal class SyncScheduler(
    private val queue: UploadQueueStats,
    private val clock: Clock,
    private val logger: TrackLogger,
) {

    @Volatile
    private var trigger: SyncTrigger? = null

    @Volatile
    private var lastRequestMs = 0L

    /** Null clears it — `autoSync = false`, or a 401/403 that tore the config down. */
    fun register(trigger: SyncTrigger?) {
        this.trigger = trigger
        lastRequestMs = 0L
        sdkLog {
            logger.d(TAG, if (trigger == null) "Sync trigger cleared" else "Sync trigger registered")
        }
    }

    /**
     * A point was accepted and stored.
     *
     * Throttled rather than fired per point: in navigation mode this runs once a second,
     * and while WorkManager coalesces the work itself, the enqueue is still a binder call
     * on the ingest path. One request a minute is far below the batch size, so nothing
     * waits meaningfully longer for it.
     *
     * Store-then-sync holds: the row is already durable when this is called, so a request
     * that never arrives costs nothing.
     */
    fun onAcceptedPoint() {
        val active = trigger ?: return
        val now = clock.wallTimeMs()
        if (now - lastRequestMs < MIN_REQUEST_INTERVAL_MS) return

        lastRequestMs = now
        active.requestSync()
    }

    /**
     * The supervision path — health loop every two minutes, backstop every fifteen.
     *
     * Asks the queue rather than assuming: with nothing pending there is nothing to send,
     * and waking a worker to discover that is the "absence of uploads is not evidence of a
     * dead tracker" mistake in reverse.
     *
     * The staleness clause is what makes this a net rather than an echo of
     * [onAcceptedPoint]. Rows can sit queued with the point path silent — a parked user
     * stores nothing, and a drain that failed while the process was dead left the backlog
     * behind — so once the last confirmed upload is [STALE_SYNC_MS] old the throttle is
     * bypassed and the request goes out regardless.
     */
    suspend fun onSupervisionTick() {
        val active = trigger ?: return

        val pending = runCatching { queue.pendingCount() }.getOrDefault(0)
        if (pending == 0) return

        val now = clock.wallTimeMs()
        val lastSyncMs = runCatching { queue.lastSyncTimeMs() }.getOrNull() ?: 0L
        val stale = now - lastSyncMs >= STALE_SYNC_MS
        if (!stale && now - lastRequestMs < MIN_REQUEST_INTERVAL_MS) return

        lastRequestMs = now
        sdkLog { logger.d(TAG, "$pending row(s) queued${if (stale) " and sync is stale" else ""}; requesting a drain") }
        active.requestSync()
    }

    /**
     * The session just closed — the last moment anything in core is still watching.
     *
     * **Bypasses the throttle, deliberately.** [onAcceptedPoint] fires at most once a
     * minute, so a session that ends within a minute of its last stored point leaves
     * nothing scheduled. That would be survivable if some later tick noticed, and none
     * does: `StopTrackingUseCase` cancels the backstop and stops the service the health
     * loop runs in, so both supervision paths die with the session.
     *
     * What this actually buys is not the request but its residue. `requestSync()`
     * enqueues network-constrained work that WorkManager persists in its own database, so
     * a queue recorded offline drains when connectivity returns *even if the process is
     * killed first* — which is the case `NetworkMonitor` in `fieldtrack-sync` cannot
     * cover, because a dead process has no callbacks.
     *
     * Still asks the queue first: a session that ended with everything uploaded should
     * leave no work behind at all.
     */
    suspend fun onSessionClosed() {
        val active = trigger ?: return

        val pending = runCatching { queue.pendingCount() }.getOrDefault(0)
        if (pending == 0) return

        lastRequestMs = clock.wallTimeMs()
        sdkLog { logger.d(TAG, "Session closed with $pending row(s) queued; leaving a drain scheduled") }
        active.requestSync()
    }

    private companion object {
        const val TAG = "SyncScheduler"
        const val MIN_REQUEST_INTERVAL_MS = 60_000L

        /** Spec §3.4: "last sync ≥ 16 min old → run the sync queue". */
        const val STALE_SYNC_MS = 16 * 60 * 1_000L
    }
}
