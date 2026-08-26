package com.field360.tracker.service

import android.content.BroadcastReceiver
import android.content.Context
import com.field360.tracker.TrackerConfig
import com.field360.tracker.di.TrackerGraph
import com.field360.tracker.domain.model.TrackerEvent
import com.field360.tracker.work.RestoreWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Brings the foreground service back when the OS woke this process into existence and
 * there is no service in it, with a session still open.
 *
 * This is the half of the wake path that was missing. A geofence transition and an
 * activity-recognition transition are both registered with the *system* — they outlive
 * the process, and after an OEM kill they are the only thing that still reaches us. But
 * both receivers did nothing with that wake except call [CaptureBus.request], and
 * `CaptureBus` is an in-process `SharedFlow` whose only collector is created in
 * [TrackingService.startSupervision]. Woken into a fresh process, the emit went to no
 * collector at all: the broadcast arrived, the graph was built, and nothing happened.
 *
 * Called only from those two receivers, and that restriction is load-bearing. Receiving
 * a geofencing or activity-recognition transition is on the API 31+ allowlist for
 * starting a foreground service from the background, so `startForegroundService` is legal
 * on this path and is very nearly the only path where it is. [RestoreWorker] is the
 * fallback for the case where the platform disagrees anyway — an OEM with its own rules,
 * or an allowlist window that closed while the disk read below was in flight.
 *
 * Note what this does NOT do: it does not resurrect a session, and it does not decide
 * whether one should exist. It re-promotes a service for a session that is already open
 * on disk, and returns immediately when there is none.
 */
internal fun BroadcastReceiver.reviveServiceIfNeeded(context: Context) {
    // Read before anything else, so the healthy case — a wake delivered to a process that
    // still has its service — costs one volatile read and no disk touch. Advisory only:
    // between an OS kill and `onDestroy` this is a stale `true` and the revival is
    // skipped, which is the window `BackstopWorker` covers on its own 15-minute tick.
    if (TrackingService.running) return

    val appContext = context.applicationContext
    val graph = TrackerGraph.get(appContext)

    // `goAsync` because the work below opens the database. A receiver's main-thread
    // window is short and this is exactly the kind of I/O that must not run in it.
    val pendingResult = goAsync()
    CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
        try {
            graph.sessions.current() ?: return@launch
            val config = graph.config.load() ?: TrackerConfig()
            if (!config.service.foregroundService) return@launch

            runCatching { TrackingService.start(appContext, config.service) }
                .onFailure { failure ->
                    graph.events.tryEmit(
                        TrackerEvent.Diagnostic(
                            "wake revival refused (${failure.message}); falling back to RestoreWorker",
                        ),
                    )
                    RestoreWorker.enqueueExpedited(appContext)
                }
        } finally {
            pendingResult.finish()
        }
    }
}
