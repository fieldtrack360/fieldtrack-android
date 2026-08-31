package com.field360.tracker.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.PowerManager
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
import com.field360.tracker.work.RestoreWorker
import com.field360.tracker.work.UploadQueueStats
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

    private val queueStats: UploadQueueStats get() = graph.uploadQueueStats

    /** The supervision coroutines for the session currently being served. */
    private var supervision: Job? = null

    /**
     * What [buildNotification] was last given, so [startSupervision] can tell whether the
     * persisted config it just read differs from what is already on screen.
     */
    private var postedNotification: ServiceConfig? = null

    /**
     * The upload-queue line currently on screen, or null when the feature is off or
     * nothing has been read yet.
     *
     * Tracked separately from [postedNotification] because it changes for a different
     * reason and on a different clock: the config moves when the host reconfigures, this
     * moves every time a row is queued or drained. Comparing it before re-posting is what
     * keeps a stationary device with a settled queue from re-notifying once a minute
     * forever.
     */
    private var postedStatusLine: String? = null

    /**
     * The partial wake lock, or null when [ServiceConfig.wakeLockMs] is 0 or the platform
     * refused to hand one out.
     */
    private var wakeLock: PowerManager.WakeLock? = null

    /** Re-arms [wakeLock] before its timeout expires — see [startWakeLockRenewal]. */
    private var wakeLockJob: Job? = null

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

            // BEFORE anything else in this scope, because everything below supervises a
            // pipeline this may be the thing that creates.
            //
            // Every revival path in the SDK ends in a start command on this service —
            // `BootReceiver`, `RestoreWorker`, `BackstopWorker`, `reviveServiceIfNeeded`.
            // None of them could restart capture, because capture is not in the service:
            // it is the ingestor and the stream controller living in the process, started
            // from `StartTrackingUseCase` and nowhere else. So an OEM kill — minutes, on
            // OnePlus and Xiaomi with the screen off — was answered by putting the
            // notification back over a pipeline that no longer existed, with the watchdog
            // and the health loop both reporting healthy because the service was up and
            // the session was open. A no-op whenever the pipeline is already running,
            // which is every ordinary start command.
            graph.resumeCapture()

            // Held for the life of the service, not per fix. A location-typed foreground
            // service does not keep the CPU awake, so on an OEM that sleeps aggressively
            // the ingest coroutine, the motion tick and the health loop all stall between
            // fixes — `wakeLockMs` existed to prevent exactly that and was read by
            // nothing. Zero disables it.
            startWakeLockRenewal(config.service)

            // The one case [promoteToForeground] cannot get right on its own: after the
            // process was killed and the sticky restart brought this service back, the
            // in-memory config was empty and the platform defaults went up. The host's
            // title, text and icon are on disk, and this is the first point at which
            // reading them is allowed to suspend.
            refreshNotification(config.service)

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

            // Its own loop rather than a line inside the tick above: this one touches the
            // database and the notification manager, and a slow query must not delay the
            // motion clock that the stop timeout depends on.
            if (config.service.showSyncStatusInNotification) {
                launch {
                    while (isActive) {
                        refreshSyncStatus(config.service)
                        delay(config.service.watchdogIntervalMs)
                    }
                }
            }
        }
    }

    /** Re-posts the ongoing notification when [config] differs from what is on screen. */
    private fun refreshNotification(config: ServiceConfig) {
        if (config == postedNotification) return
        postedNotification = config
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification(config, postedStatusLine))
        }
    }

    /**
     * Reads the upload queue and re-posts the notification when the answer has changed.
     *
     * The whole point of putting this on the notification is that it is readable with the
     * host app dead — see [ServiceConfig.showSyncStatusInNotification]. So every failure
     * here is swallowed into a line that says so rather than propagated: this runs in the
     * supervision scope, and an exception would take the force-capture collector and the
     * motion clock down with it over a diagnostic.
     */
    private suspend fun refreshSyncStatus(config: ServiceConfig) {
        // Nothing is listening for a drain, so the queue depth is not a backlog — see
        // `SyncScheduler.isConfigured`. Checked on every tick rather than once, because a
        // host may call `configure()` after tracking has started, and a 401/403 can clear
        // it mid-session; the line appears and disappears with it.
        if (!graph.syncScheduler.isConfigured) {
            // Put the host's own text back if a status line is currently on screen. Only
            // then: an unconditional re-post would re-notify every tick for the whole life
            // of a session that never had sync configured.
            if (postedStatusLine != null) {
                postedStatusLine = null
                runCatching {
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIFICATION_ID, buildNotification(postedNotification ?: config))
                }
            }
            return
        }

        val line = runCatching {
            syncStatusLine(
                template = config.syncNotificationText,
                pending = queueStats.pendingCount(),
                lastSyncMs = queueStats.lastSyncTimeMs(),
                nowMs = graph.clock.wallTimeMs(),
            )
        }.getOrElse { "upload status unavailable" }

        if (line == postedStatusLine) return
        postedStatusLine = line

        val current = postedNotification ?: config
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification(current, line))
        }
    }

    /**
     * Keeps a `PARTIAL_WAKE_LOCK` alive for as long as the service is serving a session.
     *
     * **Why a renewal loop rather than one long acquire.** The lock is taken with
     * [ServiceConfig.wakeLockMs] as its timeout, so a lock leaked by a process that dies
     * between `onStartCommand` and `onDestroy` releases itself rather than draining the
     * battery until reboot. A timeout that short then has to be re-armed, and the loop can
     * only be relied on to run because the lock it renews is what keeps the CPU running —
     * which is the same reason it is renewed at half the timeout rather than at it.
     *
     * **Why this is needed at all.** `foregroundServiceType="location"` keeps the *process*
     * alive; it does not keep the *CPU* awake. In Doze — and far more aggressively on
     * OxygenOS and MIUI — the process is frozen between location callbacks, so `delay()` in
     * the health loop, the motion tick that drives the stop timeout, and the ingest
     * consumer all stop running on their stated cadence. The host declares the cost by
     * setting the value; `0` opts out entirely and restores the previous behaviour.
     */
    private fun startWakeLockRenewal(config: ServiceConfig) {
        wakeLockJob?.cancel()
        if (config.wakeLockMs <= 0L) return

        wakeLockJob = lifecycleScope.launch {
            while (isActive) {
                renewWakeLock(config.wakeLockMs)
                delay(config.wakeLockMs / 2)
            }
        }
    }

    /**
     * Acquires or re-arms the lock. Never throws: an OEM that refuses `PowerManager`, or a
     * `WAKE_LOCK` permission stripped by a host's own manifest merge, degrades to the
     * behaviour that shipped before this existed rather than taking the service down.
     */
    private fun renewWakeLock(timeoutMs: Long) {
        val lock = wakeLock ?: runCatching {
            getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
                // Not reference counted, so a re-arm is a re-arm rather than a second
                // hold that `release()` would then have to be called twice to undo.
                .apply { setReferenceCounted(false) }
        }.getOrNull()?.also { wakeLock = it } ?: return

        runCatching { lock.acquire(timeoutMs) }
    }

    private fun releaseWakeLock() {
        wakeLockJob?.cancel()
        wakeLockJob = null
        val lock = wakeLock ?: return
        wakeLock = null
        runCatching { if (lock.isHeld) lock.release() }
    }

    private fun teardown() {
        running = false
        supervision?.cancel()
        supervision = null
        releaseWakeLock()
        healthLoop.stop()
        // Explicit, and with REMOVE. The platform drops a foreground notification when the
        // service is destroyed, but `teardown` also runs on the ACTION_STOP path *before*
        // `stopSelf`, and on an OEM that defers the destroy the "Tracking active"
        // notification is what the user is left looking at after tapping Stop.
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        postedNotification = null
    }

    /** @return false if the OS refused; the service has already stopped itself. */
    @SuppressLint("InlinedApi") // ServiceCompat ignores this inlined type below API 29.
    private fun promoteToForeground(): Boolean = try {
        // The in-memory config, never a disk read: `startForeground` has to happen inside
        // `onStartCommand` on the main thread, and `ConfigRepository.load()` suspends.
        // `ResolveConfigUseCase` saves on every `ready()`, so this is populated for the
        // whole life of the process that started the session. After a sticky restart
        // following process death it is null, the defaults are posted, and
        // [startSupervision] re-posts with the persisted config a moment later.
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(cachedServiceConfig().also { postedNotification = it }),
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
        sdkLog { logger.w(TAG, "startForeground(location) refused: ${e.message}") }
        events.tryEmit(TrackerEvent.Error(ErrorCode.FGS_START_REFUSED, e.message.orEmpty()))

        // The retry this comment has always claimed happens (EC-62), and until now did
        // not: nothing else in the SDK enqueues a restore from here. `HealthLoop` was the
        // only caller, and the health loop lives *inside* this service — so a refusal on
        // the very first start command, or a process killed before the first tick at
        // `healthLoopMs`, left the session open with nothing scheduled to bring it back.
        //
        // Enqueued unconditionally rather than behind a session check. `RestoreWorker`
        // reads `sessions.current()` at the moment it runs, so a refusal on the way out
        // of a session that has already closed resolves to a no-op there, and
        // `SessionTeardown` cancels the unique work by name regardless.
        RestoreWorker.enqueueExpedited(applicationContext)
        stopSelf()
        false
    }

    private fun cachedServiceConfig(): ServiceConfig =
        graph.configStore.cached?.service ?: ServiceConfig()

    private fun buildNotification(config: ServiceConfig, statusLine: String? = null): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        // Re-created on every start: a user-deleted channel makes the notification
        // invisible and gets the service killed on some OEMs (EC-76).
        val channel = NotificationChannel(
            config.notificationChannelId,
            config.notificationChannelName,
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)

        return NotificationCompat.Builder(this, config.notificationChannelId)
            // **The title is the host's, always.** The sync status is a diagnostic layered
            // onto the ongoing notification, not a notification of its own, and letting it
            // take the title took away the one line that says which app is holding the
            // foreground service. A user who sees "FieldTrack · upload" where their app's
            // name belongs has lost the notification's identity to a debug readout.
            .setContentTitle(config.notificationTitle)
            // The sync headline goes in the **subtitle**, beside the title rather than over
            // it, and only while a status line is actually on screen. Null — the default,
            // and the case when the host set no wording — leaves the notification with no
            // subtitle at all, exactly as it looks with the diagnostic off.
            .setSubText(statusLine?.let { config.syncNotificationSubText })
            // The status line REPLACES the host's text rather than appending to it. The
            // collapsed notification shows one line, and a concatenation would push the
            // number — the only part being read during the test — off the end of it.
            .setContentText(statusLine ?: config.notificationText)
            .setSmallIcon(resolveSmallIcon(config.notificationSmallIconResName))
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    /**
     * Resolves `ServiceConfig.notificationSmallIconResName` against the HOST's resources.
     *
     * A name rather than an `@DrawableRes Int` because [ServiceConfig] is `@Serializable`
     * and persisted: a resource id is not stable across builds, so a stored one points at
     * whatever moved into that slot after the next R regeneration.
     *
     * Falls back to the platform icon rather than throwing. An unresolvable name is a
     * cosmetic mistake in the host's config, and `setSmallIcon(0)` is not — it makes the
     * post fail, which fails `startForeground`, which stops the service and ends the
     * session. Said out loud in the log so the mistake is findable.
     */
    // getIdentifier is discouraged because a compile-time R constant is faster and
    // verifiable — and it is unavailable here by construction. The icon belongs to the
    // HOST's resource table, which this module cannot reference, and the config carrying
    // it is serialized to disk, where an id would not survive the next R regeneration.
    // Once per service start, off any hot path.
    @SuppressLint("DiscouragedApi")
    private fun resolveSmallIcon(resName: String?): Int {
        if (resName.isNullOrBlank()) return DEFAULT_SMALL_ICON

        // "ic_stat_track", "drawable/ic_stat_track" and "com.host.app:drawable/ic_stat_track"
        // are all things a host will reasonably write, so accept all three. Qualified forms
        // carry their own type, bare ones are looked up as a drawable and then a mipmap.
        val qualified = '/' in resName || ':' in resName
        val id = runCatching {
            if (qualified) {
                resources.getIdentifier(resName, null, packageName)
            } else {
                resources.getIdentifier(resName, "drawable", packageName)
                    .takeIf { it != 0 }
                    ?: resources.getIdentifier(resName, "mipmap", packageName)
            }
        }.getOrDefault(0)

        if (id == 0) {
            sdkLog { logger.w(TAG, "notificationSmallIconResName '$resName' not found; using default") }
            return DEFAULT_SMALL_ICON
        }
        return id
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

        /**
         * Namespaced with the SDK's package, because a wake-lock tag is what `dumpsys
         * power` and Play Console's excessive-wakelock report attribute the hold to. A
         * generic tag makes a battery complaint impossible to trace to whoever caused it.
         */
        internal const val WAKE_LOCK_TAG = "fieldtrack:tracking"
        /**
         * Used only when the host named an icon that does not resolve, or named none.
         * The title, text, channel and icon a host DID configure live in [ServiceConfig]
         * and are read from there — duplicating their defaults here is how the two drift.
         */
        internal val DEFAULT_SMALL_ICON = android.R.drawable.ic_menu_mylocation

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
/**
 * Renders [ServiceConfig.syncNotificationText] against the queue — by default
 * `unsynced 42 · last upload 21m ago`.
 *
 * Both default halves earn their place. The count alone cannot tell a queue that is
 * draining from one that is merely not growing — a parked device stores nothing, so a
 * still count is the *expected* reading, not a stalled one. The upload age is what
 * separates them: it resets the moment anything reaches the server. A host that overrides
 * the template and keeps only `{pending}` gives that up knowingly.
 *
 * Substitution is literal and order-independent, and an unrecognised `{token}` is left
 * exactly as written — a typo then shows up on the notification as itself rather than
 * silently becoming an empty string, which is the failure that would otherwise be
 * diagnosed as "the sync status is broken".
 *
 * Internal and top-level rather than a private method, so the formatting is reachable from
 * a JVM test without standing up a Service.
 */
internal fun syncStatusLine(
    template: String,
    pending: Int,
    lastSyncMs: Long?,
    nowMs: Long,
): String {
    val age = when {
        lastSyncMs == null || lastSyncMs <= 0L -> "never"
        // A clock that moved backwards — an NTP correction, a user editing the date —
        // would otherwise render a negative age. "just now" is wrong by at most the skew
        // and is never nonsense.
        nowMs <= lastSyncMs -> "just now"
        else -> formatAge(nowMs - lastSyncMs)
    }
    return template
        .replace(TOKEN_PENDING, pending.toString())
        .replace(TOKEN_AGE, age)
}

private const val TOKEN_PENDING = "{pending}"
private const val TOKEN_AGE = "{age}"

private fun formatAge(millis: Long): String {
    val seconds = millis / 1_000
    val minutes = seconds / 60
    val hours = minutes / 60
    return when {
        seconds < 60 -> "${seconds}s ago"
        minutes < 60 -> "${minutes}m ago"
        hours < 24 -> "${hours}h ago"
        else -> "${hours / 24}d ago"
    }
}

internal object CaptureBus {
    val forceCapture: MutableSharedFlow<Unit> = MutableSharedFlow(extraBufferCapacity = 1)

    fun request() {
        forceCapture.tryEmit(Unit)
    }
}
