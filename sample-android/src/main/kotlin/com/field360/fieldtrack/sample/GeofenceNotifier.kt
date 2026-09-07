package com.field360.fieldtrack.sample

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.field360.tracker.domain.model.GeofenceTransition
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Posts one notification per geofence crossing.
 *
 * Separate from the SDK's own ongoing foreground notification and on its own channel, so
 * a user can silence "you crossed a fence" without silencing "tracking is running" — they
 * are different kinds of message and Android's own guidance is that they get different
 * channels.
 *
 * **Every crossing gets its own notification id.** Reusing one id would make each arrival
 * replace the last, which for the case this exists to demonstrate — a drive through
 * several fences — hides all but the final one. The id is derived from the crossing's own
 * identity rather than from a counter, so the same crossing re-posted after a process
 * restart lands on the notification it already had instead of stacking a duplicate.
 *
 * This is host code, deliberately. The SDK emits `TrackerEvent.GeofenceEntered` and
 * persists the crossing; what a user should be *told* about it is a product decision, and
 * a location SDK that posted notifications of its own would be making it on the host's
 * behalf.
 */
class GeofenceNotifier(context: Context) {

    private val context: Context = context.applicationContext

    private val manager = NotificationManagerCompat.from(this.context)

    /**
     * Created once, and safe to call again: `createNotificationChannel` is idempotent for
     * an existing id, and a channel the user has since reconfigured is left as they set it.
     */
    fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = CHANNEL_DESCRIPTION
            enableLights(true)
        }
        manager.createNotificationChannel(channel)
    }

    /**
     * @return true when the notification was handed to the system.
     *
     * `false` covers the one case a sample must not crash on: `POST_NOTIFICATIONS` is a
     * runtime permission from API 33 and the user can refuse it, or revoke it later. The
     * crossing is still recorded in [GeofenceAlertLog] either way — a notification the
     * user declined to receive is not a reason to lose the record of the event.
     */
    fun post(alert: GeofenceAlert): Boolean {
        if (!manager.areNotificationsEnabled()) {
            Log.w(SampleApplication.TRACKER_TAG, "geofence alert not posted: notifications disabled")
            return false
        }

        val verb = if (alert.isEnter) "Entered" else "Left"
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_tracking)
            .setContentTitle("$verb ${alert.geofenceId}")
            .setContentText(detailOf(alert))
            .setStyle(NotificationCompat.BigTextStyle().bigText(detailOf(alert)))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            // The crossing's own time, not the post time. On a cold start the process is
            // built minutes after the fence was crossed, and a notification timestamped
            // "now" would misreport when the user actually left.
            .setWhen(alert.crossedAtMs)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()

        // Belt and braces around the permission check above: the grant can be revoked
        // between the two calls, and `notify` throws rather than returning.
        return runCatching { manager.notify(idOf(alert), notification) }
            .onFailure { Log.w(SampleApplication.TRACKER_TAG, "geofence alert not posted", it) }
            .isSuccess
    }

    /** Clears every crossing notification this app has posted and not had dismissed. */
    fun cancelAll() {
        runCatching { manager.cancelAll() }
    }

    // ─────────────────────────────────────────────────────────────────────────

    private fun detailOf(alert: GeofenceAlert): String {
        val direction = when (alert.transition) {
            GeofenceTransition.ENTER -> "enter"
            GeofenceTransition.EXIT -> "exit"
        }
        val at = TIME_FORMAT.format(Instant.ofEpochMilli(alert.crossedAtMs).atZone(ZoneId.systemDefault()))
        return String.format(
            Locale.US,
            "%s · %s · %.5f, %.5f · r=%.0fm · %s",
            direction,
            alert.eventName,
            alert.latitude,
            alert.longitude,
            alert.radiusM,
            at,
        )
    }

    private fun openApp(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            OPEN_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * Stable per crossing, so re-posting one updates rather than duplicates.
     *
     * `hashCode` can collide, and the consequence here is one notification replacing an
     * unrelated one — acceptable for a demonstration, and far better than the counter it
     * replaces, which duplicated on every process restart.
     */
    private fun idOf(alert: GeofenceAlert): Int = alert.key.hashCode()

    private companion object {
        const val CHANNEL_ID = "geofence_crossings"
        const val CHANNEL_NAME = "Geofence crossings"
        const val CHANNEL_DESCRIPTION = "Fires when the device enters or leaves a registered fence"
        const val OPEN_REQUEST_CODE = 4001

        val TIME_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US)
    }
}
