package com.field360.tracker.service

import android.content.Context
import android.os.Looper
import com.field360.tracker.di.TrackerGraph
import com.field360.tracker.domain.model.TrackerEvent
import com.field360.tracker.domain.model.WakeResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The body of `Tracker.wake` — a wake from outside the device, normally a high-priority
 * FCM data message the server sends when an open shift has gone quiet.
 *
 * Exists because the two system wakes — geofence and activity transition — both depend on
 * the user moving, and the gaps they leave are exactly the ones a server can see and a
 * device cannot: a process frozen by an OEM battery manager, or parked in Doze, uploads
 * nothing and notices nothing. A high-priority FCM message pierces Doze and, on API 31+,
 * is an exemption for starting a foreground service from the background, so it can do
 * what `reviveServiceIfNeeded` does for the receivers.
 *
 * What it cannot do: reach an app the OEM has **force-stopped**. Android does not deliver
 * FCM to a stopped package until the user opens it again. That case is still covered only
 * by the device being exempt from battery optimisation.
 */
internal object RemoteWake {

    /**
     * Upper bound on the blocking call. FCM gives `onMessageReceived` about 10 seconds
     * (and the FGS-start exemption window is of the same order); a wake that has not
     * finished its one database read by then will not make it anyway.
     */
    private const val TIMEOUT_MS = 8_000L

    fun wake(context: Context): WakeResult {
        val appContext = context.applicationContext
        val graph = TrackerGraph.get(appContext)

        // Blocking the main thread on a database read is an ANR, so a main-thread caller
        // gets the work fire-and-forget instead. FCM never calls on main; this is for the
        // host that wires the wake somewhere else.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
                report(graph, run(appContext, graph))
            }
            return WakeResult.DISPATCHED
        }

        val result = runBlocking {
            withTimeoutOrNull(TIMEOUT_MS) { run(appContext, graph) }
        } ?: WakeResult.TIMED_OUT
        report(graph, result)
        return result
    }

    private suspend fun run(appContext: Context, graph: TrackerGraph): WakeResult {
        // Advisory, as everywhere it is read: between an OS kill and `onDestroy` it is a
        // stale `true`, the capture below reaches no collector, and `BackstopWorker` is
        // what recovers. Accepted rather than probed — there is no cheap probe that is
        // any less stale.
        val result = if (TrackingService.running) {
            CaptureBus.request()
            WakeResult.ALIVE
        } else {
            reviveTrackingService(appContext, graph)
        }
        // Whatever happened above, a queued backlog is what the server is missing.
        graph.syncScheduler.onRemoteWake()
        return result
    }

    private fun report(graph: TrackerGraph, result: WakeResult) {
        graph.events.tryEmit(TrackerEvent.Diagnostic("remote wake: ${result.name}"))
    }
}
