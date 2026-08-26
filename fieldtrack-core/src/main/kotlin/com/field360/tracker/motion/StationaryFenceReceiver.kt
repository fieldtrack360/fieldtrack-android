package com.field360.tracker.motion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.field360.tracker.di.TrackerGraph
import com.field360.tracker.domain.model.GeofenceTransition
import com.field360.tracker.domain.model.TrackerEvent
import com.google.android.gms.location.Geofence
import com.field360.tracker.service.CaptureBus
import com.field360.tracker.service.reviveServiceIfNeeded
import com.google.android.gms.location.GeofencingEvent
import kotlinx.coroutines.flow.MutableSharedFlow

/** Fence exit means the user left the stop. Wake and capture. */
public class StationaryFenceReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return

        // Manifest-declared receiver: constructed by the system, so the graph is looked
        // up rather than injected. Lazy, so this builds nothing that is not already up.
        val graph = TrackerGraph.get(context)
        val events: MutableSharedFlow<TrackerEvent> = graph.events
        val motionController = graph.motionController

        if (event.hasError()) {
            events.tryEmit(TrackerEvent.Diagnostic("geofence_error: ${event.errorCode}"))
            return
        }

        // On any real transition, not only the stationary-exit case handled below. A
        // system geofence is the one wake path that survives an OEM killing the process,
        // so being woken by one is the moment to put the service back — whatever the
        // transition turns out to mean. Below this line, `CaptureBus.request()` assumes a
        // collector that only exists while the service is alive.
        reviveServiceIfNeeded(context)

        val transition = when (event.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> GeofenceTransition.ENTER
            Geofence.GEOFENCE_TRANSITION_EXIT -> GeofenceTransition.EXIT
            else -> {
                events.tryEmit(
                    TrackerEvent.Diagnostic("stationary_fence_transition:${event.geofenceTransition}"),
                )
                return
            }
        }

        var stationaryFenceExited = false
        event.triggeringGeofences.orEmpty().forEach { triggered ->
            val registration = graph.stationaryFence.store.registration(triggered.requestId)
            if (registration == null) {
                events.tryEmit(TrackerEvent.Diagnostic("unknown_geofence:${triggered.requestId}"))
                return@forEach
            }
            graph.stationaryFence.store.record(registration, transition, System.currentTimeMillis())
            when (transition) {
                GeofenceTransition.ENTER ->
                    events.tryEmit(TrackerEvent.GeofenceEntered(registration.geofence))
                GeofenceTransition.EXIT -> {
                    events.tryEmit(TrackerEvent.GeofenceExited(registration.geofence))
                    stationaryFenceExited = stationaryFenceExited || registration.isStationaryWakeFence
                }
            }
        }

        if (stationaryFenceExited) {
            motionController.onStationaryFenceExit()
            // In-process request; never startForegroundService from a receiver (A13).
            CaptureBus.request()
        }
    }
}
