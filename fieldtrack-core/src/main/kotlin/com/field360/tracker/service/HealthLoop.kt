package com.field360.tracker.service

import android.content.Context
import androidx.work.WorkInfo
import com.field360.tracker.TrackerConfig
import com.field360.tracker.sdkLog
import com.field360.tracker.sdkWarn
import com.field360.tracker.domain.model.TrackerEvent
import com.field360.tracker.domain.repository.SessionRepository
import com.field360.traker.geo.port.Clock
import com.field360.traker.geo.model.MotionState
import com.field360.traker.geo.port.TrackLogger
import com.field360.tracker.domain.model.ErrorCode
import com.field360.tracker.domain.usecase.StopTrackingUseCase
import com.field360.tracker.integrity.internal.IntegrityMonitor
import com.field360.tracker.motion.MotionController
import com.field360.tracker.permission.BackgroundRestrictions
import com.field360.tracker.permission.ProviderStateMonitor
import com.field360.tracker.work.BackstopWorker
import com.field360.tracker.work.SyncScheduler
import com.field360.tracker.work.Watchdog
import com.field360.tracker.work.WorkManagerAccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlin.time.Duration.Companion.milliseconds

/**
 * The in-service supervision loops: liveness every `watchdogIntervalMs` (60 s by default),
 * everything more expensive every `healthLoopMs` (two minutes).
 *
 * Its job is to notice the failures nothing else reports: a periodic worker stuck in
 * `FAILED`/`CANCELLED`, a heartbeat alarm whose chain was broken by a kill, a session that
 * ended while the service kept running, a tracker that has gone quiet. The reference runs
 * the slow loop at the same cadence (`AttendanceLoggerService.kt:442-451`, `:1041`).
 *
 * Note what it structurally cannot do: report its own absence. It runs *inside*
 * [TrackingService], so a process the OEM killed takes this with it. That is what
 * [ServiceHeartbeat] and `BackstopWorker` are for — see [start] for the split, and
 * [ServiceRestorer] for what happens when the answer is "restore the service".
 */
internal class
HealthLoop(
    private val context: Context,
    private val sessions: SessionRepository,
    private val clock: Clock,
    private val events: MutableSharedFlow<TrackerEvent>,
    private val watchdog: Watchdog,
    private val motionController: MotionController,
    private val providerState: ProviderStateMonitor,
    private val syncScheduler: SyncScheduler,
    private val logger: TrackLogger,
    private val integrityMonitor: IntegrityMonitor,
    private val stopTracking: StopTrackingUseCase,
) {

    private var job: Job? = null

    /** Monotonic timestamp of the last integrity evaluation; `0` = not evaluated in this loop. */
    private var lastIntegrityCheckNanos: Long = 0

    /** Last reported background-restriction state, so only the edges are announced. */
    private var lastRestrictions: BackgroundRestrictions? = null

    /**
     * Two loops on two clocks, and the split is the point.
     *
     * `ServiceConfig.watchdogIntervalMs` documents itself as the watchdog's cadence and
     * `PERMISSIONS.md` §7 lists the watchdog at "60 s". Neither was true: the watchdog was
     * ticked from inside [runCheck], so it actually ran at `healthLoopMs` — 120 s by
     * default — and `watchdogIntervalMs` drove only the motion clock and the notification's
     * sync line. Every liveness decision in the SDK was therefore taken at half the rate it
     * was configured for, which on a device that kills the service is the difference
     * between one missed fix interval and two.
     *
     * They are separated rather than merged upward because they cost different amounts.
     * The watchdog is one indexed session read and some arithmetic on a monotonic clock;
     * [runCheck] queries `WorkManager`, may run the whole integrity probe list, and asks the
     * sync scheduler to consider a drain. Running the expensive one at the fast cadence to
     * fix the cheap one would be a battery regression dressed as a bug fix.
     */
    fun start(scope: CoroutineScope, config: TrackerConfig, onSessionClosed: () -> Unit) {
        job?.cancel()
        job = scope.launch {
            // Liveness, on the cadence the config actually names.
            launch {
                while (isActive) {
                    delay(config.service.watchdogIntervalMs.milliseconds)
                    withContext(NonCancellable) { runWatchdog(config) }
                }
            }

            // Everything that touches WorkManager, the integrity probes or the network.
            launch {
                while (isActive) {
                    delay(config.service.healthLoopMs.milliseconds)
                    withContext(NonCancellable) { runCheck(config, onSessionClosed) }
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        lastIntegrityCheckNanos = 0
        lastRestrictions = null
    }

    /**
     * The fast tick: is tracking actually alive, and if not, act.
     *
     * Deliberately *not* the place that stops a service with no session — that is a
     * teardown decision and it belongs with the rest of them in [runCheck]. All this does
     * with a closed session is decline to judge one.
     */
    private suspend fun runWatchdog(config: TrackerConfig) {
        if (sessions.current() == null) return

        val action = watchdog.tick(
            config = config.service,
            serviceRunning = TrackingService.running,
            hasOpenSession = true,
            // A moving user is judged on the tighter threshold; a parked one is expected
            // to be quiet, so the two cases cannot share a limit (EC-70).
            moving = motionController.motionState == MotionState.MOVING,
            powerSave = providerState.state.value.powerSaveMode,
        )
        if (action == Watchdog.Action.RestoreService) {
            // Counted and backed off — see `ServiceRestorer`. Straight at
            // `RestoreWorker.enqueueExpedited`, this was one half of a loop with
            // `TrackingService.promoteToForeground`.
            ServiceRestorer.request(context)
        }
    }

    private suspend fun runCheck(config: TrackerConfig, onSessionClosed: () -> Unit) {
        val session = sessions.current()
        if (session == null) {
            // No session but the service is alive — stop rather than burn battery.
            sdkLog { logger.d(TAG, "No open session; stopping service") }
            onSessionClosed()
            return
        }

        ensureBackstopAlive(config)
        ensureHeartbeatArmed(config)
        reportRestrictionChanges()

        // A device can be rooted, hooked or handed a fake-GPS app *during* a session, and
        // a check that only ran at start() would be one reboot behind the attacker. Costed
        // deliberately: at the default fifteen minutes this is ~120 evaluations a month.
        if (integrityBlocked(config)) {
            onSessionClosed()
            return
        }

        // Spec §3.4 step 3 and §12.2 check 3: rows queued, or the last sync gone stale,
        // means run the queue. A no-op unless the host configured sync with autoSync on.
        syncScheduler.onSupervisionTick()

        // Health is judged first; the heartbeat then records that the loop is alive.
        events.tryEmit(TrackerEvent.Heartbeat(clock.wallTimeMs()))
    }

    /**
     * Re-evaluates device integrity at `SecurityConfig.recheckIntervalMs` and tears the
     * session down on a `BLOCK` verdict.
     *
     * Stopping goes through [StopTrackingUseCase] rather than just killing the service:
     * the session has to be closed, the stream released and the sensors disarmed, or the
     * SDK leaves a half-live capture stack behind on exactly the device it just decided
     * not to trust.
     */
    private suspend fun integrityBlocked(config: TrackerConfig): Boolean {
        val interval = config.security.recheckIntervalMs
        if (interval <= 0L) return false

        val now = clock.elapsedRealtimeNanos()
        if (lastIntegrityCheckNanos != 0L &&
            (now - lastIntegrityCheckNanos) / NANOS_PER_MS < interval
        ) {
            return false
        }
        lastIntegrityCheckNanos = now

        val report = withContext(Dispatchers.IO) { integrityMonitor.evaluate(config.security) }
        if (!report.blocked) return false

        val message = "Device integrity check failed mid-session: ${report.describeBlocking()}"
        sdkWarn { logger.w(TAG, message) }
        events.tryEmit(TrackerEvent.Error(ErrorCode.DEVICE_INTEGRITY_BLOCKED, message))
        stopTracking()
        return true
    }

    /**
     * WorkManager periodic work can land in `FAILED` or `CANCELLED` and then simply
     * never run again — silent, and the backstop is precisely the thing you rely on
     * when the stream has already failed. Re-enqueue it (EC-71).
     */
    private suspend fun ensureBackstopAlive(config: TrackerConfig) {
        // Flow variant so no ListenableFuture/Guava bridge is pulled into the AAR.
        val infos = runCatching {
            WorkManagerAccess.get(context)
                .getWorkInfosForUniqueWorkFlow(BackstopWorker.NAME)
                .first()
        }.getOrNull() ?: return

        val healthy = infos.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }
        if (!healthy) {
            sdkWarn { logger.w(TAG, "Backstop work not alive (${infos.map { it.state }}); re-enqueuing") }
            BackstopWorker.enqueue(context, config.service.backstopIntervalMin)
        }
    }

    /**
     * Re-arms the heartbeat alarm on every slow tick.
     *
     * The same argument as [ensureBackstopAlive], for a mechanism that fails differently.
     * An alarm is one-shot and self-perpetuating, so the chain has exactly one way to break
     * — a tick that was delivered but whose re-arm did not happen, because the process was
     * killed in the window between the two — and after that it is gone for the rest of the
     * session with nothing to notice. This is what notices. Cheap, idempotent, and it
     * cannot stack: `schedule` replaces the pending alarm rather than adding one.
     */
    private fun ensureHeartbeatArmed(config: TrackerConfig) {
        ServiceHeartbeat.schedule(context, config.service)
    }

    /**
     * Says so when the OS's opinion of this app's background rights *changes* mid-session.
     *
     * On the edge only, never on the level. The standby bucket demotes as an app goes
     * unused and an OEM battery manager can move an app to "Restricted" hours into a shift,
     * so this genuinely moves during a session — and it is the moment it moves that
     * explains the gap that starts right afterwards. Reporting the level instead would put
     * the same line on the event stream every two minutes for the whole session, which is
     * how a real signal becomes something hosts filter out.
     *
     * `CaptureLauncher` reports the opening state once, so the first observation here is a
     * change from that rather than a repeat of it.
     */
    private fun reportRestrictionChanges() {
        val now = BackgroundRestrictions.read(context)
        if (now == lastRestrictions) return
        lastRestrictions = now
        sdkWarn { logger.w(TAG, now.describe()) }
        events.tryEmit(TrackerEvent.Diagnostic(now.describe()))
    }

    private companion object {
        const val TAG = "HealthLoop"
        const val NANOS_PER_MS = 1_000_000L
    }
}
