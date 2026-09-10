package com.field360.tracker.motion

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.field360.tracker.domain.model.TrackerEvent
import com.field360.tracker.sdkLog
import com.field360.tracker.sdkWarn
import com.field360.traker.geo.port.TrackLogger
import com.field360.tracker.permission.PermissionManager
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.DetectedActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

/**
 * Registers activity-transition updates.
 *
 * Everything here is best-effort by design. Activity recognition is **enrichment**: it
 * stamps a label on stored points and buys one extra fix at a motion change. It is
 * never a capture gate, because it is wrong often enough to lose whole trips — the
 * reference documents 17-minute drives reported as `STILL` on OnePlus and Xiaomi under
 * battery saver (EC-53). So every failure path here degrades silently to speed +
 * displacement motion detection rather than propagating (EC-09).
 */
internal class ActivityRecognizer(
    private val context: Context,
    private val permissions: PermissionManager,
    private val events: MutableSharedFlow<TrackerEvent>,
    private val logger: TrackLogger,
    private val scope: CoroutineScope,
) {

    private val client by lazy { ActivityRecognition.getClient(context) }

    private val pendingIntent: PendingIntent by lazy {
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            // setPackage keeps the implicit broadcast deliverable on API 26+; without
            // it the transition intent is silently dropped.
            Intent(context, ActivityTransitionReceiver::class.java)
                .setPackage(context.packageName),
            // MUTABLE because Play Services writes the transition result into the intent.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }

    private var snapshotWatchdog: Job? = null
    private var registered = false

    @SuppressLint("MissingPermission") // Guarded by the permission check immediately below.
    fun register() {
        // EC-09 — guard every AR call. Denied permission must degrade, not throw.
        if (!permissions.hasActivityRecognition()) {
            sdkWarn {
                logger.w(TAG, "ACTIVITY_RECOGNITION not granted; motion falls back to speed + displacement")
            }
            return
        }
        if (registered) return

        val request = ActivityTransitionRequest(TRANSITIONS)

        client.requestActivityTransitionUpdates(request, pendingIntent)
            .addOnSuccessListener {
                registered = true
                sdkLog { logger.d(TAG, "Activity transitions registered") }
            }
            .addOnFailureListener { error ->
                sdkWarn { logger.w(TAG, "Activity transition registration failed: ${error.message}") }
                events.tryEmit(TrackerEvent.Diagnostic("activity_recognition_unavailable"))
            }

        armSnapshotWatchdog()
    }

    @SuppressLint("MissingPermission")
    fun unregister() {
        snapshotWatchdog?.cancel()
        snapshotWatchdog = null
        if (!registered) return
        registered = false

        if (!permissions.hasActivityRecognition()) return
        runCatching { client.removeActivityTransitionUpdates(pendingIntent) }
        runCatching { client.removeActivityUpdates(pendingIntent) }
    }

    /**
     * The reference requests a one-shot snapshot at registration with a detection
     * interval of **0 ms**, and relies on the receiver to cancel it after the first
     * result. If that result never arrives — permission revoked between register and
     * delivery, Play Services unavailable, process killed first — nothing cancels it and
     * GMS keeps firing at maximum rate for the life of the app. Its own KDoc names the
     * consequence: *"draining battery and flooding the receiver"* (SOURCE-AUDIT A12).
     *
     * So the cancel is unconditional and on a timer, independent of whether the snapshot
     * ever lands.
     */
    @SuppressLint("MissingPermission")
    private fun armSnapshotWatchdog() {
        snapshotWatchdog?.cancel()
        snapshotWatchdog = scope.launch {
            delay(SNAPSHOT_WATCHDOG_MS)
            runCatching { client.removeActivityUpdates(pendingIntent) }
            sdkLog { logger.d(TAG, "Snapshot subscription cancelled by watchdog") }
        }
    }

    private companion object {
        const val TAG = "ActivityRecognizer"
        const val REQUEST_CODE = 8_303
        const val SNAPSHOT_WATCHDOG_MS = 30_000L

        /** ENTER and EXIT for every activity class the plotting side can label. */
        val TRANSITIONS: List<ActivityTransition> = listOf(
            DetectedActivity.IN_VEHICLE,
            DetectedActivity.ON_BICYCLE,
            DetectedActivity.ON_FOOT,
            DetectedActivity.WALKING,
            DetectedActivity.RUNNING,
            DetectedActivity.STILL,
        ).flatMap { activity ->
            listOf(
                ActivityTransition.Builder()
                    .setActivityType(activity)
                    .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                    .build(),
                ActivityTransition.Builder()
                    .setActivityType(activity)
                    .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_EXIT)
                    .build(),
            )
        }
    }
}
