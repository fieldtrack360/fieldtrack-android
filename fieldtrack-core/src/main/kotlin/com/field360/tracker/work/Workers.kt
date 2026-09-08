package com.field360.tracker.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.field360.tracker.TrackerConfig
import com.field360.tracker.di.TrackerGraph
import com.field360.tracker.domain.model.TrackerEvent
import com.field360.tracker.domain.model.LicenseAction
import com.field360.tracker.license.LicenseState
import com.field360.tracker.service.ServiceHeartbeat
import com.field360.tracker.service.ServiceRestorer
import com.field360.tracker.service.TrackingService
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/**
 * Dependency access for workers.
 *
 * Workers are constructed by WorkManager, so there is no constructor to wire — the graph
 * is looked up from the application context instead. The property that matters is that
 * **the host does nothing**: WorkManager's default initialisation is enough, with no
 * `Configuration.Provider` and no custom `WorkerFactory` to install. That was the
 * argument for `EntryPointAccessors` over `@HiltWorker` when this used Hilt, and it is
 * the same argument now that there is no Hilt at all.
 */
private fun Context.trackItGraph(): TrackerGraph = TrackerGraph.get(applicationContext)

/**
 * The 15-minute safety net.
 *
 * Captures a fix even when the continuous stream has died — an OEM kill, a refused FGS
 * promotion, a provider that stopped delivering. It feeds the **shared** ingestor
 * rather than deriving its own `past`, which is the whole point: the reference had the
 * worker and the service judging fixes against different anchors while mutating one
 * static filter (SOURCE-AUDIT A3).
 *
 * Best-effort by nature — in the `RESTRICTED` app standby bucket it may not run at all
 * (EC-22).
 */
internal class BackstopWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val deps = applicationContext.trackItGraph()

        // Runs whether or not a fix arrives, and — the part that was wrong — whether or
        // not a session is open. This is the only supervision that survives a dead
        // service, so it is the one thing that can notice a backlog left behind by a
        // drain that failed while the process was gone (spec §12.2 check 3). Below the
        // session check it could never do that: a closed session is exactly the state a
        // stranded queue is left in.
        deps.syncScheduler.onSupervisionTick()

        val session = deps.sessions.current() ?: return Result.success()
        val config = deps.config.load() ?: TrackerConfig()

        // A session is open and there is no service serving it — the process was killed.
        // Nothing else notices that from outside the process: `HealthLoop` runs *inside*
        // the service and cannot report its own absence, and the wake receivers only fire
        // when the user moves. This tick is the one piece of supervision that survives.
        //
        // Best effort, and honestly so. On API 31+ a plain job is not on the allowlist for
        // starting a foreground service from the background, so this can be refused; it
        // succeeds below API 31, on OEMs that do not enforce it, and whenever the app was
        // recently in the foreground. `TrackingService` reports its own refusal and
        // enqueues a restore, so a refused attempt is not a silent one.
        if (!TrackingService.running && config.service.foregroundService) {
            // Best-effort and uncounted, deliberately. This tick is one of the two slow
            // paths `ServiceRestorer` stands down *in favour of*, so routing its failure
            // back into that budget would let a periodic job exhaust the fast retries the
            // next real opportunity needs. It simply tries again in fifteen minutes.
            TrackingService.start(applicationContext, config.service)
        }

        // The heartbeat chain breaks in exactly one way — a tick delivered to a process
        // that was killed before it re-armed. This is the second thing that can notice,
        // and unlike `HealthLoop.ensureHeartbeatArmed` it still runs with the service dead.
        ServiceHeartbeat.schedule(applicationContext, config.service)

        // Through OneShotProvider, not the raw source: it carries the timeout, the
        // retry cap and the mutex that stops a coincident activity-transition capture
        // from firing a second concurrent request (EC-17, EC-20).
        deps.oneShotProvider.capture(config) ?: return noFix()

        return Result.success()
    }

    /**
     * What to do when the safety net caught nothing.
     *
     * `Result.retry()` unconditionally, which is what this was, is a retry storm on exactly
     * the devices the backstop exists for. A phone indoors, in a pocket, or with GNSS
     * duty-cycled by the OEM returns nothing for minutes at a time, and each retry is a
     * fresh `PRIORITY_HIGH_ACCURACY` request with a 30 s budget: at the 30 s linear backoff
     * that is a near-continuous high-accuracy fix attempt for as long as the device cannot
     * produce one. On a MIUI/HyperOS device that drain is not merely wasted — it is one of
     * the things that gets the app killed, so the storm actively causes the failure the
     * worker is meant to recover from.
     *
     * Two retries, then wait for the next period. The 15-minute tick is already the
     * contract; giving up inside a period costs at most one skipped backstop fix, and a
     * device that could not produce a fix in three attempts across a minute is not going to
     * produce one on the fourth.
     *
     * `success`, not `failure`: a periodic worker that fails is still rescheduled, but
     * `FAILED` is also what `HealthLoop.ensureBackstopAlive` treats as a dead worker worth
     * re-enqueuing, and "the device is indoors" must not read as "the backstop broke".
     */
    private fun noFix(): Result =
        if (runAttemptCount < MAX_FIX_ATTEMPTS - 1) Result.retry() else Result.success()

    companion object {
        const val NAME = "fieldtrack-backstop"

        fun enqueue(context: Context, intervalMinutes: Int) {
            val request = PeriodicWorkRequestBuilder<BackstopWorker>(
                intervalMinutes.toLong(),
                TimeUnit.MINUTES,
            )
                .setBackoffCriteria(BackoffPolicy.LINEAR, MIN_BACKOFF_SECONDS, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                NAME,
                // KEEP: re-enqueuing on every start would reset the 15-minute clock and
                // the backstop would never actually fire on a frequently-restarted app.
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
        }

        private const val MIN_BACKOFF_SECONDS = 30L

        /**
         * One-shot attempts per 15-minute period, retries included. Three: the tick itself
         * and two retries, spanning about a minute and a half of backoff.
         */
        private const val MAX_FIX_ATTEMPTS = 3
    }
}

/**
 * Re-promotes the foreground service after it was killed or refused.
 *
 * Expedited, because the window in which the app is eligible to start an FGS may be
 * short (EC-62, EC-69).
 */
internal class RestoreWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val deps = applicationContext.trackItGraph()
        deps.sessions.current() ?: return Result.success()
        val config = deps.config.load() ?: TrackerConfig()
        // A host running without a foreground service has nothing here to restore, and
        // `start` returns false for that reason rather than a refusal — checked up front so
        // the retry path below cannot be fed a configuration choice.
        if (!config.service.foregroundService) return Result.success()

        // The boolean, not a bare call. `startForegroundService` throws on API 31+ when the
        // app is not eligible to start one from the background, and an uncaught throw here
        // failed the worker outright — `WorkManager` recorded FAILED, nothing was emitted,
        // and the one job whose entire purpose is surviving a kill died silently on the
        // devices where kills happen. `TrackingService.start` now reports it instead.
        if (!TrackingService.start(applicationContext, config.service)) {
            // Back to the counted path, which decides whether another attempt is worth
            // making at all. Never a bare re-enqueue from here: this worker's only action
            // is that start, so answering its own failure by scheduling itself is the loop.
            ServiceRestorer.request(applicationContext)
        }
        return Result.success()
    }

    companion object {
        const val NAME = "fieldtrack-restore"

        /**
         * The immediate attempt. Reach for it through
         * [com.field360.tracker.service.ServiceRestorer], never directly — the counting and
         * the backoff are what stop this from becoming a loop with `promoteToForeground`.
         */
        fun enqueueExpedited(context: Context) {
            val request = OneTimeWorkRequestBuilder<RestoreWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setConstraints(Constraints.NONE)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, request)
        }

        /**
         * A backed-off attempt, for a device that has already refused at least once.
         *
         * **Not expedited, and it cannot be**: `WorkManager` rejects a request that combines
         * `setExpedited` with an initial delay, which is the right rule — expedited means
         * "run this now" and a delay means the opposite. The distinction carries the whole
         * intent here. An app that refused a foreground-service start a moment ago is not
         * eligible *now*; spending an expedited quota slot to be told so again is how the
         * quota ran out, and the quota is what the next genuine opportunity needs.
         */
        fun enqueueDelayed(context: Context, delaySeconds: Long) {
            val request = OneTimeWorkRequestBuilder<RestoreWorker>()
                .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
                .setConstraints(Constraints.NONE)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, request)
        }

        /**
         * Cancels a pending re-promotion.
         *
         * Part of every session teardown, and not optional. This worker exists to restart
         * a service that died with a session still open, and it decides that from
         * `sessions.current()` at the moment it runs. An expedited request enqueued moments
         * before a stop would therefore find the session still open — the close and the
         * service stop cannot be one atomic act — and put the foreground notification back
         * up for a session that had just ended, where it sat until the next health tick
         * noticed, up to two minutes later.
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
        }
    }
}

/**
 * TTL enforcement for points and the decision log.
 *
 * Never touches rows belonging to an **open** session — pruning mid-session would put a
 * hole in the track being recorded (EC-81). The decision log is capped by both age and
 * row count (EC-87).
 */
internal class PruneWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val deps = applicationContext.trackItGraph()
        val config = deps.config.load() ?: TrackerConfig()
        val now = deps.clock.wallTimeMs()

        val persistence = config.persistence
        if (persistence.maxDaysToPersist > 0) {
            deps.trackPoints.prune(now - persistence.maxDaysToPersist * MILLIS_PER_DAY)
        }
        if (persistence.persistDecisions) {
            deps.decisions.prune(
                cutoffMs = now - persistence.decisionRetentionDays * MILLIS_PER_DAY,
                maxRows = persistence.decisionMaxRows,
            )
        }
        return Result.success()
    }

    companion object {
        const val NAME = "fieldtrack-prune"
        private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000

        fun enqueue(context: Context) {
            val request = PeriodicWorkRequestBuilder<PruneWorker>(1, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder().setRequiresBatteryNotLow(true).build(),
                )
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}

/**
 * The revocation check, on a slow tick.
 *
 * Twice a day is not a heartbeat — it is roughly the cache TTL a paid licence returns, and
 * anything faster is polling. The server allows 120 requests per minute per IP; being
 * anywhere near that from a fleet of installs is a bug, not a configuration.
 *
 * Everything about this worker is built to be ignorable. It requires connectivity, so
 * WorkManager simply does not run it offline — no connectivity is not a failure state.
 * It returns `success` whatever happened, because a licence server outage is not a failed
 * job and retrying it aggressively is the opposite of what should happen. And the check
 * it calls fails open, so the worst case for every failure path is that nothing changes.
 */
internal class LicenseCheckWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val deps = applicationContext.trackItGraph()
        val token = deps.licenseGate.token(deps.configStore.cached?.license)
            ?: return Result.success()

        val result = deps.checkLicenseRevocation(token)
        val (action, info) = result
        LicenseState.apply(action)

        // A failed check is not a verdict, so it never becomes a LicenseChecked. It is
        // still worth saying out loud: without this the host cannot distinguish "the
        // licence server is unreachable" from "nothing has run yet", and both look like
        // silence. Diagnostic is the channel that means "information, not a decision".
        result.error?.let { error ->
            deps.events.tryEmit(TrackerEvent.Diagnostic("licence check failed: ${error.describe()}"))
        }


        // Every authenticated answer reaches the host, `active` included — a host that
        // only ever heard about failures would have no way to tell a healthy licence
        // from a check that never ran. Null means nothing was learned, and says nothing.
        info?.let { deps.events.tryEmit(TrackerEvent.LicenseChecked(it)) }

        when (action) {
            is LicenseAction.Stop -> {
                deps.events.tryEmit(
                    TrackerEvent.Error(action.code, action.reason ?: action.code.name),
                )
                // Ends the session cleanly rather than letting capture run on into a
                // licence that no longer exists. The host hears about it through the
                // error above, which is the same channel every other refusal uses.
                deps.stopTracking()
            }
            is LicenseAction.Diagnose -> {
                deps.events.tryEmit(
                    TrackerEvent.Diagnostic(
                        "licence ${action.code.name}: ${action.reason ?: "no detail"}",
                    ),
                )
            }
            LicenseAction.CarryOn -> Unit
        }

        return Result.success()
    }

    companion object {
        const val NAME = "fieldtrack-licence-check"

        fun enqueue(context: Context) {
            val request = PeriodicWorkRequestBuilder<LicenseCheckWorker>(
                INTERVAL_HOURS,
                TimeUnit.HOURS,
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                NAME,
                // KEEP, like the backstop: re-enqueuing on every ready() would reset the
                // 12-hour clock, and an app that is restarted often would never check.
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        private const val INTERVAL_HOURS = 12L
        private const val BACKOFF_MINUTES = 30L
    }
}
