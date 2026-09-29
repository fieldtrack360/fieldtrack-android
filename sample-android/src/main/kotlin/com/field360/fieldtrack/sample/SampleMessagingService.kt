package com.field360.fieldtrack.sample

import android.util.Log
import com.field360.tracker.Tracker
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Remote wake: the server's way back into a device that has gone quiet.
 *
 * A frozen or dozing process uploads nothing and notices nothing, so only the server can
 * see the gap. When an open shift has not uploaded for a while, the server sends a
 * **data-only, high-priority** message:
 *
 * ```json
 * { "message": { "token": "<device token>",
 *     "android": { "priority": "high", "ttl": "300s" },
 *     "data": { "type": "fieldtrack_wake" } } }
 * ```
 *
 * - Data-only: a `notification` block makes Android show it itself while the app is in
 *   the background, and [onMessageReceived] is never called.
 * - High priority: normal priority waits out Doze, and only high priority grants the
 *   exemption `Tracker.wake` needs to start the tracking service from the background.
 *
 * Everything else is `Tracker.wake` — it decides whether there is anything to do.
 */
class SampleMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        if (message.data[KEY_TYPE] != TYPE_WAKE) return

        // Already on a background thread, which is what `wake` requires.
        val result = Tracker.wake(applicationContext)
        Log.i(TAG, "remote wake (priority=${message.priority}): $result")
    }

    /**
     * A real host sends this to its backend, against the signed-in user and device, so the
     * server has somewhere to send the wake. The sample has no such backend, so it logs it
     * — paste it into the Firebase console's test-message dialog to try the path by hand.
     */
    override fun onNewToken(token: String) {
        Log.i(TAG, "FCM token: $token")
    }

    private companion object {
        const val TAG = "SampleMessaging"
        const val KEY_TYPE = "type"
        const val TYPE_WAKE = "fieldtrack_wake"
    }
}
