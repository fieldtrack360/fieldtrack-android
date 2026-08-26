package com.field360.tracker.motion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.field360.tracker.di.TrackerGraph
import com.field360.tracker.domain.model.TrackerEvent
import com.field360.traker.geo.model.ActivityType
import com.field360.tracker.service.CaptureBus
import com.field360.tracker.service.reviveServiceIfNeeded
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Activity-recognition transitions.
 *
 * **Enrichment, never a capture gate.** The reference documents entire 17-minute drives
 * during which AR reported `STILL` on OnePlus and Xiaomi devices under battery saver;
 * gating capture on this label would lose the whole trip. So a transition does two
 * things only: stamps a label, and asks for one extra fix (EC-53).
 */
public class ActivityTransitionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (!ActivityTransitionResult.hasResult(intent)) return
        val result = ActivityTransitionResult.extractResult(intent) ?: return

        // First, because the rest of this method is useless without it. The registration
        // that delivered this broadcast lives in Play Services and outlives our process,
        // so after an OEM kill this runs with no service and no collector on `CaptureBus`
        // — the label is emitted to a flow nobody reads and the extra fix never happens.
        // A no-op when the service is already up.
        reviveServiceIfNeeded(context)

        // Resolved here rather than field-injected: a manifest-declared receiver is
        // constructed by the system, so there is no constructor to wire and nothing to
        // inject into. The graph is lazy, so this costs nothing but a map lookup.
        val graph = TrackerGraph.get(context)
        val events: MutableSharedFlow<TrackerEvent> = graph.events
        val motionController = graph.motionController

        // Processed chronologically — delivery order is not guaranteed.
        result.transitionEvents.sortedBy { it.elapsedRealTimeNanos }.forEach { event ->
            val activity = event.activityType.toActivityType()
            events.tryEmit(TrackerEvent.ActivityChange(activity, confidence = 0))
            motionController.onActivityTransition(activity)

            // One extra fix at the transition — the cheapest turn-geometry win there is.
            // In-process request; no startForegroundService from a receiver (A13).
            CaptureBus.request()
        }
    }
}

internal fun Int.toActivityType(): ActivityType = when (this) {
    DetectedActivity.IN_VEHICLE -> ActivityType.IN_VEHICLE
    DetectedActivity.ON_BICYCLE -> ActivityType.ON_BICYCLE
    DetectedActivity.ON_FOOT -> ActivityType.ON_FOOT
    DetectedActivity.WALKING -> ActivityType.WALKING
    DetectedActivity.RUNNING -> ActivityType.RUNNING
    DetectedActivity.STILL -> ActivityType.STILL
    DetectedActivity.TILTING -> ActivityType.TILTING
    else -> ActivityType.UNKNOWN
}
