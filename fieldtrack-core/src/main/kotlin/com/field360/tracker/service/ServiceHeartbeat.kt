package com.field360.tracker.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import com.field360.tracker.ServiceConfig
import com.field360.tracker.TrackerConfig
import com.field360.tracker.di.TrackerGraph
import com.field360.tracker.domain.model.TrackerEvent
import com.field360.tracker.sdkLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The revival path that does not go through `WorkManager`.
 *
 * ### Why a third path exists at all
 *
 * `PERMISSIONS.md` §7 lists seven survival layers, and on a HyperOS/MIUI device with
 * "Battery saver" left at its default *Restricted* setting, six of them fail for the same
 * single reason. `BackstopWorker` and [com.field360.tracker.work.RestoreWorker] are
 * `WorkManager` jobs, and `WorkManager` is `JobScheduler`: an app in the `RESTRICTED`
 * standby bucket does not get jobs run — the SDK's own `BackstopWorker` KDoc has always
 * said so (EC-22). `BootReceiver` needs the OEM's Autostart toggle, off by default on the
 * same ROMs (EC-126). The health loop and the watchdog both live *inside*
 * [TrackingService] and cannot report their own absence. So after an OEM kill every layer
 * that could have noticed was disabled by one setting, and the session stayed open with
 * nothing recording it.
 *
 * `AlarmManager` is a different subsystem from `JobScheduler`, with different throttling
 * and a different OEM allowlist. It is not a guarantee — nothing on Android is, against a
 * ROM that decides otherwise — but it is genuinely *independent*, which is the whole
 * argument for a layered stack. This is the layer that keeps working when the job
 * scheduler is the thing that was switched off.
 *
 * ### Inexact by default, exact when the host has earned it
 *
 * The alarm is scheduled with [AlarmManager.setAndAllowWhileIdle], which pierces Doze and
 * needs **no permission**. That matters: `SCHEDULE_EXACT_ALARM` is Play-policy sensitive
 * in exactly the way `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is, and this SDK deliberately
 * declares neither (see the manifest, EC-15). An SDK that quietly adds a policy-reviewed
 * permission to its host's merged manifest has made the host's Play submission its own
 * decision to make.
 *
 * A host that *has* declared `SCHEDULE_EXACT_ALARM` or `USE_EXACT_ALARM` itself, and had
 * it granted, gets [AlarmManager.setExactAndAllowWhileIdle] instead — checked at runtime
 * via [AlarmManager.canScheduleExactAlarms], never assumed. That upgrade is worth real
 * money on API 31+: an exact alarm is on the platform's allowlist for **starting a
 * foreground service from the background**, and an inexact one is not. So with the
 * permission the heartbeat can actually re-promote the service; without it, the promotion
 * is best-effort and succeeds below API 31, on ROMs that do not enforce the restriction,
 * and whenever the app was recently in the foreground.
 *
 * ### It re-arms itself, and that is the point
 *
 * An `AlarmManager` alarm is one-shot, so [ServiceHeartbeatReceiver] schedules the next
 * one before it does anything else. That ordering is load-bearing: a failure anywhere
 * below must not be able to end the chain, because the chain is what the whole layer is.
 * Alarms do not survive a reboot, so `BootReceiver` re-arms too.
 *
 * ### The second job it does, for free
 *
 * Under [com.field360.tracker.WakeLockPolicy.PER_FIX] — the default — the CPU is only held
 * up around a delivered fix, so when fixes *stop* arriving there is nothing keeping the
 * device awake and `HealthLoop`'s `delay` and the motion tick stall along with it. That is
 * precisely the situation in which supervision most needs to run. This alarm is a wakeup,
 * and a wakeup is what unblocks a stalled coroutine timer: even on the ticks where the
 * service is already up and this returns immediately, the wake itself puts the in-service
 * loops back on the CPU. It bounds how long they can be stalled to one heartbeat period.
 */
internal object ServiceHeartbeat {

    /**
     * Arms the next tick, replacing any alarm already pending.
     *
     * Idempotent by construction — `FLAG_UPDATE_CURRENT` means the same `PendingIntent` is
     * reused and the new trigger time simply replaces the old one, so calling this from
     * the receiver, from `CaptureLauncher` and from `BootReceiver` cannot stack alarms.
     *
     * Never throws. An OEM that refuses `AlarmManager`, or a device that has hit the
     * per-app alarm cap, degrades to the behaviour that shipped before this existed
     * rather than taking a broadcast receiver or a session start down with it.
     */
    fun schedule(context: Context, config: ServiceConfig) {
        if (config.serviceHeartbeatMin <= 0) return
        // Nothing to restore. Guarded here rather than at each caller so a host running
        // without a foreground service cannot end up with an alarm chain waking the device
        // every fifteen minutes to discover there is no service it is allowed to start.
        if (!config.foregroundService) return
        val appContext = context.applicationContext
        val manager = appContext.getSystemService(AlarmManager::class.java) ?: return
        val pending = pendingIntent(appContext, PendingIntent.FLAG_UPDATE_CURRENT) ?: return

        val triggerAt = SystemClock.elapsedRealtime() +
            config.serviceHeartbeatMin * MILLIS_PER_MINUTE

        // ELAPSED_REALTIME_WAKEUP, never RTC: the heartbeat measures an interval since the
        // last tick, and an RTC alarm would fire early or late by however far a user or an
        // NTP correction moved the wall clock.
        runCatching {
            if (canScheduleExact(manager)) {
                manager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    pending,
                )
            } else {
                manager.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    pending,
                )
            }
        }
    }

    /**
     * Drops the pending alarm and the `PendingIntent` behind it.
     *
     * Both, and in that order. Cancelling the alarm without cancelling the
     * `PendingIntent` leaves a live token the next `FLAG_NO_CREATE` lookup still finds,
     * which is how a "cancelled" heartbeat comes back after the next session start.
     */
    fun cancel(context: Context) {
        val appContext = context.applicationContext
        val manager = appContext.getSystemService(AlarmManager::class.java)
        val pending = pendingIntent(appContext, PendingIntent.FLAG_NO_CREATE) ?: return
        runCatching { manager?.cancel(pending) }
        pending.cancel()
    }

    /**
     * `FLAG_IMMUTABLE` because nothing fills anything in: the intent is explicit, addressed
     * to a receiver in this package, and carries no extras. Mutable would be a gratuitous
     * hole and is refused outright on API 31+ anyway.
     */
    private fun pendingIntent(context: Context, flag: Int): PendingIntent? {
        val intent = Intent(context, ServiceHeartbeatReceiver::class.java)
            .setAction(ACTION_HEARTBEAT)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            flag or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * Whether the **host** declared an exact-alarm permission and it is granted.
     *
     * Below API 31 exact alarms need no permission and the answer is always yes. Above it,
     * this is a runtime question with a runtime answer — `canScheduleExactAlarms` flips
     * when a user revokes "Alarms & reminders", so it is asked on every schedule rather
     * than cached.
     */
    private fun canScheduleExact(manager: AlarmManager): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { manager.canScheduleExactAlarms() }.getOrDefault(false)
        } else {
            true
        }

    internal const val ACTION_HEARTBEAT = "com.field360.tracker.HEARTBEAT"

    /** Namespaced by being a private constant on an SDK-owned receiver; collides with nothing. */
    private const val REQUEST_CODE = 8_302

    private const val MILLIS_PER_MINUTE = 60_000L
}

/**
 * One heartbeat tick: re-arm, then put the service back if a session is open without one.
 *
 * Manifest-declared, so it is delivered into a process the OS builds for the purpose —
 * which is the case this exists for. Everything it needs is read from disk, because in
 * that process `Tracker.ready()` has not run and nothing is cached.
 */
public class ServiceHeartbeatReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ServiceHeartbeat.ACTION_HEARTBEAT) return

        val appContext = context.applicationContext
        val graph = TrackerGraph.get(appContext)

        // FIRST, and synchronously, from whatever config is already in memory. The chain
        // is the layer: if the process dies during the disk read below, the next alarm has
        // to already be armed or this was the last tick there will ever be. `cached` is
        // null in a process built to deliver this broadcast, and the default cadence is
        // the right answer there — the persisted value replaces it a moment later.
        ServiceHeartbeat.schedule(
            appContext,
            graph.configStore.cached?.service ?: ServiceConfig(),
        )

        // `goAsync` because the work below opens the database. A receiver's main-thread
        // window is short and this is exactly the I/O that must not run in it.
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                val config = graph.config.load() ?: TrackerConfig()

                // Re-armed with the host's own cadence now that it is readable. Replaces
                // the alarm set above rather than adding to it — see `schedule`.
                ServiceHeartbeat.schedule(appContext, config.service)

                // No session means nothing to keep alive, and an alarm chain running
                // against a closed session is a battery cost with no product behind it.
                // `SessionTeardown` cancels this too; this is the belt for the case where
                // the session was closed by a process that died before it could.
                if (graph.sessions.current() == null) {
                    ServiceHeartbeat.cancel(appContext)
                    return@launch
                }

                if (!config.service.foregroundService) return@launch

                // Advisory only, exactly as in `reviveServiceIfNeeded`: between an OS kill
                // and `onDestroy` this reads a stale `true` and the tick is skipped, which
                // the next one covers. The cheap read comes first so the healthy case —
                // most ticks — costs nothing.
                if (TrackingService.running) return@launch

                // A fresh budget of fast retries per tick. This is what makes
                // `ServiceRestorer`'s cap a rate limit rather than a permanent surrender:
                // the process that exhausted it may have been ineligible for reasons that
                // no longer hold, and an alarm firing is the cheapest evidence that time
                // has passed.
                ServiceRestorer.reset()

                sdkLog {
                    graph.logger.w(TAG, "Heartbeat: session open with no service; restoring")
                }
                graph.events.tryEmit(
                    TrackerEvent.Diagnostic("heartbeat alarm found an open session with no service"),
                )

                if (!TrackingService.start(appContext, config.service)) {
                    // Refused at the call site — the app is not eligible to start a
                    // foreground service from here. Hand it to the counted path, which
                    // will retry with backoff and stand down rather than loop.
                    ServiceRestorer.request(appContext)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val TAG = "ServiceHeartbeat"
    }
}
