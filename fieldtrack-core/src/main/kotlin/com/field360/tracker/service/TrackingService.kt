package com.field360.tracker.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.field360.tracker.ServiceConfig
import com.field360.tracker.TrackerConfig
import com.field360.tracker.sdkLog
import com.field360.tracker.capture.OneShotProvider
import com.field360.tracker.di.TrackerGraph
import com.field360.tracker.domain.model.ErrorCode
import com.field360.tracker.domain.model.TrackerEvent
import com.field360.tracker.domain.repository.ConfigRepository
import com.field360.traker.geo.port.TrackLogger
import com.field360.tracker.motion.MotionController
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The foreground service that hosts the capture stream.
 *
 * Its most important property is that it **never crash-loops**. Foreground promotion is
 * the single most common way background-location SDKs die on modern Android, and it
 * fails in two different ways that must both be caught (PERMISSIONS.md §6).
 */
public class TrackingService : LifecycleService() {

    /**
     * The graph, resolved lazily on first use rather than in `onCreate`.
     *
     * A service is constructed by the system, so there is nothing to inject into. `by
     * lazy` over an `onCreate` assignment because `onCreate` runs on the main thread and
     * the graph opens the database on first touch — every member here is itself lazy, so
     * nothing is built until the field below it is actually read.
     */
    private val graph by lazy { TrackerGraph.get(applicationContext) }

    private val events: MutableSharedFlow<TrackerEvent> get() = graph.events

    private val logger: TrackLogger get() = graph.logger

    private val healthLoop: HealthLoop get() = graph.healthLoop

    private val oneShotProvider: OneShotProvider get() = graph.oneShotProvider

    private val motionController: MotionController get() = graph.motionController

    private val configRepository: ConfigRepository get() = graph.config

    /** The supervision coroutines for the session currently being served. */
    private var supervision: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        // EC-64: the OS restarts a sticky service with a NULL Intent. Reconstruct from
        // persisted state rather than trusting redelivery.
        val action = intent?.action ?: ACTION_RESUME

        if (action == ACTION_STOP) {
            teardown()
            stopSelf()
            return START_NOT_STICKY
        }

        if (!promoteToForeground()) return START_NOT_STICKY

        running = true
        // Unconditional, where this used to run only on the first start command.
        //
        // `StartTrackingUseCase` stops this service before opening a new session, but
        // `stopService` is a request, not a barrier: a start command issued immediately
        // after can still land on an instance whose `onDestroy` has not run. That instance
        // used to keep supervising with the config it read when the *previous* session
        // began — its health-loop cadence, its watchdog thresholds, its force-capture
        // config — and nothing ever corrected it.
        //
        // Re-arming instead is cheap and has no failure mode: [startSupervision] cancels
        // the previous coroutines before launching, so repeated start commands cannot
        // stack collectors either.
        startSupervision()

        // START_STICKY, never START_STICKY_COMPATIBILITY — the latter does not guarantee
        // onStartCommand is called again, so the service returns unconfigured (A14).
        return START_STICKY
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun startSupervision() {
        supervision?.cancel()
        supervision = lifecycleScope.launch {
            val config = configRepository.load() ?: TrackerConfig()

            // In-process force-capture. Never startForegroundService() from a receiver:
            // a `running` flag goes stale between an OS kill and onDestroy, and the call
            // then throws with nothing to catch it (SOURCE-AUDIT A13).
            launch {
                CaptureBus.forceCapture.collect {
                    oneShotProvider.capture(config)
                }
            }

            // 2-minute supervision: worker liveness, session still open, tracker alive.
            healthLoop.start(lifecycleScope, config) {
                lifecycleScope.launch { stopSelf() }
            }

            // The stop timeout and any deferred move need a clock tick to fire; the
            // health loop cadence is the cheapest one already running.
            launch {
                while (isActive) {
                    delay(config.service.watchdogIntervalMs)
                    motionController.tick()
                }
            }
        }
    }

    private fun teardown() {
        running = false
        supervision?.cancel()
        supervision = null
        healthLoop.stop()
        // Explicit, and with REMOVE. The platform drops a foreground notification when the
        // service is destroyed, but `teardown` also runs on the ACTION_STOP path *before*
        // `stopSelf`, and on an OEM that defers the destroy the "Tracking active"
        // notification is what the user is left looking at after tapping Stop.
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    /** @return false if the OS refused; the service has already stopped itself. */
    @SuppressLint("InlinedApi") // ServiceCompat ignores this inlined type below API 29.
    private fun promoteToForeground(): Boolean = try {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
        )
        true
    } catch (e: Exception) {
        // Two distinct failures, both real:
        //   API 31+ ForegroundServiceStartNotAllowedException — started from background.
        //   API 34+ SecurityException — location is foreground-only, so a location-typed
        //           FGS may only START from an eligible state even when granted.
        // Stop cleanly to honour the start-foreground contract; otherwise the platform
        // piles a "did not call startForeground" ANR on top of the original failure.
        // RestoreWorker re-promotes when the app is next eligible (EC-62).
        sdkLog { logger.w(TAG, "startForeground(location) refused: ${e.message}") }
        events.tryEmit(TrackerEvent.Error(ErrorCode.FGS_START_REFUSED, e.message.orEmpty()))
        stopSelf()
        false
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        // Re-created on every start: a user-deleted channel makes the notification
        // invisible and gets the service killed on some OEMs (EC-76).
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(DEFAULT_TITLE)
            .setContentText(DEFAULT_TEXT)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    public companion object {
        /**
         * Read by the watchdog to decide whether a restore is needed. Cleared in both
         * [teardown] paths, but treat it as advisory only: between an OS kill and
         * `onDestroy` it is stale `true`, which is exactly the window that made the
         * reference's force-capture throw (SOURCE-AUDIT A13).
         */
        @Volatile
        internal var running: Boolean = false
            private set

        internal const val TAG = "TrackingService"
        internal const val NOTIFICATION_ID = 8_301
        internal const val CHANNEL_ID = "trackit_tracking"
        internal const val CHANNEL_NAME = "Location tracking"
        internal const val DEFAULT_TITLE = "Tracking active"
        internal const val DEFAULT_TEXT = "Recording your location"

        public const val ACTION_RESUME: String = "com.field360.tracker.RESUME"
        public const val ACTION_STOP: String = "com.field360.tracker.STOP"

        public fun start(context: Context, config: ServiceConfig) {
            if (!config.foregroundService) return
            val intent = Intent(context, TrackingService::class.java).setAction(ACTION_RESUME)
            context.startForegroundService(intent)
        }

        /**
         * `stopService`, not a `startService(ACTION_STOP)` round trip: on API 26+ the
         * latter throws `IllegalStateException` when the service is not already running,
         * and "stop something that may already be dead" is exactly the case that hits.
         * `onDestroy` runs the same teardown either way.
         */
        public fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, TrackingService::class.java)) }
        }
    }
}

/**
 * In-process force-capture channel.
 *
 * Activity-recognition transitions want an immediate extra fix. Delivering that as an
 * `Intent` to `startForegroundService` from a broadcast receiver is what throws
 * `ForegroundServiceStartNotAllowedException` when the service died moments earlier
 * (A13). A `SharedFlow` has no such failure mode: if nothing is running, there is
 * simply no collector.
 */
internal object CaptureBus {
    val forceCapture: MutableSharedFlow<Unit> = MutableSharedFlow(extraBufferCapacity = 1)

    fun request() {
        forceCapture.tryEmit(Unit)
    }
}
