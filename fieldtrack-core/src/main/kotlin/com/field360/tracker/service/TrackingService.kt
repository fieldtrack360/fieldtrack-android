package com.field360.tracker.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.field360.tracker.ServiceConfig
import com.field360.tracker.TrackerConfig
import com.field360.tracker.sdkLog
import com.field360.tracker.sdkWarn
import com.field360.tracker.capture.OneShotProvider
import com.field360.tracker.data.platform.WakeLockController
import com.field360.tracker.di.TrackerGraph
import com.field360.tracker.domain.model.ErrorCode
import com.field360.tracker.domain.model.TrackerEvent
import com.field360.tracker.domain.repository.ConfigRepository
import com.field360.traker.geo.port.TrackLogger
import com.field360.tracker.motion.MotionController
import com.field360.tracker.work.UploadQueueStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus

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
     *
     * `@Volatile` since [startSupervision] moved off the main thread. It is written from
     * two threads now — the main one in [postForegroundNotification] and [teardown], the
     * supervision dispatcher in [refreshNotification] — and without this the supervision
     * side could compare against a value the main thread wrote and it never saw, and
     * re-post a notification that is already on screen.
     *
     * A lock would buy nothing beyond the visibility. The only interleaving these two
     * writers have is "both decide to post"; the loser's cost is one redundant `notify`
     * of the same content, which the user cannot see.
     */
    @Volatile
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
     *
     * `@Volatile` for the same reason as [postedNotification]: the sync-status loop that
     * owns it runs on the supervision dispatcher, and [refreshNotification] reads it from
     * whichever thread posts.
     */
    @Volatile
    private var postedStatusLine: String? = null

    /**
     * The `PARTIAL_WAKE_LOCK` and the policy governing it, owned by the graph rather than
     * by this service.
     *
     * It has to be reachable from the ingest path — [com.field360.tracker.WakeLockPolicy.PER_FIX]
     * takes the lock when a fix is delivered, and that happens in `FixIngestor`, not here.
     * The service still owns its *lifecycle*: it configures the policy per start command
     * and releases on teardown.
     */
    private val wakeLocks: WakeLockController get() = graph.wakeLocks

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        // EC-64: the OS restarts a sticky service with a NULL Intent. Reconstruct from
        // persisted state rather than trusting redelivery.
        val action = intent?.action ?: ACTION_RESUME

        if (action == ACTION_STOP) {
            // Promoted before it is torn down, and the order is not cosmetic. If *this*
            // instance was created by `startForegroundService`, the platform is holding a
            // ~10-second timer that only `startForeground` clears, and returning without
            // one is `ForegroundServiceDidNotStartInTimeException` — a fatal crash on a
            // command whose next line stops the service anyway.
            //
            // The SDK's own [stop] uses `stopService` and never lands here, but
            // [ACTION_STOP] is public: a host that builds the intent itself and sends it
            // through `startForegroundService` takes exactly that path.
            //
            // Deliberately not [promoteToForeground]: its refusal branch schedules a
            // restore, and asking for the service back is the opposite of what a stop
            // command means. A refusal here needs no handling at all — being refused
            // means no timer was ever armed.
            runCatching { postForegroundNotification() }
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

        // Siblings of the supervision job, on the same dispatcher as it.
        //
        // `lifecycleScope + Dispatchers.Default` keeps the *Job* — so these stay children
        // of the lifecycle and are cancelled by exactly what cancelled them before, which
        // [teardown] relies on — and changes only the thread they run on. Passing the
        // supervision coroutine's own scope instead would have reparented them under
        // [supervision] and quietly changed what `supervision?.cancel()` brings down.
        val offMain = lifecycleScope + Dispatchers.Default

        // `Dispatchers.Default`, where this used to inherit `lifecycleScope`'s
        // `Dispatchers.Main.immediate`.
        //
        // Two things were wrong with that. `Main.immediate` runs a coroutine *inline* on
        // the calling thread until its first real suspension, so this block began
        // executing inside `onStartCommand` itself and the start command could not return
        // until it suspended — and a service cannot be destroyed until `onStartCommand`
        // returns, which is time spent inside every window the platform is measuring.
        // Then every resumption after that landed back on the main thread: the graph
        // construction cascade behind `resumeCapture`, the Play Services and
        // `SensorManager` registrations it performs, the `PowerManager` acquire in
        // `wakeLocks.configure`, and a `NotificationManager` round trip per refresh.
        //
        // None of that needs the main thread. Room's DAOs here are all `suspend` and
        // dispatch themselves; the fused client is handed `context.mainLooper` explicitly
        // and the platform one an `Executor` (see `LocationSource`), so neither depends on
        // the caller having a `Looper`; and `NotificationManager` and `SensorManager` are
        // both safe to call from any thread. The one thing deliberately left on main is
        // `stopSelf` below.
        supervision = lifecycleScope.launch(Dispatchers.Default) {
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

            // Per start command, so a reconfigure between sessions takes effect without a
            // process restart. The policy decides how much CPU time this actually buys —
            // see `WakeLockController`, which is where the hold lives now that PER_FIX
            // needs it reachable from the ingest path rather than only from here.
            wakeLocks.configure(config.service, offMain)

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
            // The loop itself runs off main; the stop it may decide on does not.
            // `stopSelf` is a `Service` lifecycle call and the teardown it triggers ends in
            // `ServiceCompat.stopForeground` from `onDestroy`, so it is kept on the thread
            // the rest of the service's lifecycle runs on rather than saving a hop.
            healthLoop.start(offMain, config) {
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

    private fun teardown() {
        running = false
        supervision?.cancel()
        supervision = null
        wakeLocks.release()
        healthLoop.stop()
        // Explicit, and with REMOVE. The platform drops a foreground notification when the
        // service is destroyed, but `teardown` also runs on the ACTION_STOP path *before*
        // `stopSelf`, and on an OEM that defers the destroy the "Tracking active"
        // notification is what the user is left looking at after tapping Stop.
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        postedNotification = null
    }

    /**
     * The `startForeground` call itself, with nothing around it.
     *
     * Extracted so the [ACTION_STOP] path can satisfy the start-foreground contract
     * without also inheriting [promoteToForeground]'s refusal handling, which schedules a
     * restore. Everything it touches is in memory — see [cachedServiceConfig].
     */
    @SuppressLint("InlinedApi") // ServiceCompat ignores this inlined type below API 29.
    private fun postForegroundNotification() {
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
    }

    /** @return false if the OS refused; the service has already stopped itself. */
    private fun promoteToForeground(): Boolean = try {
        postForegroundNotification()
        reportPromotionLatency()
        // The service is genuinely up, so whatever losing streak got us here is over.
        // Without this the backoff would carry a previous failure's attempt count into the
        // next real outage and give it a 4-minute delay it never earned.
        ServiceRestorer.reset()
        true
    } catch (e: Exception) {
        // Two distinct failures, both real:
        //   API 31+ ForegroundServiceStartNotAllowedException — started from background.
        //   API 34+ SecurityException — location is foreground-only, so a location-typed
        //           FGS may only START from an eligible state even when granted.
        // Stop cleanly to honour the start-foreground contract; otherwise the platform
        // piles a "did not call startForeground" ANR on top of the original failure.
        //
        // FIRST, before the diagnostics and before the restore is enqueued. The platform's
        // start-foreground timer runs until this service is destroyed, and the destroy
        // cannot happen until `onStartCommand` returns — so every millisecond spent here
        // is spent inside the window that produces
        // `ForegroundServiceDidNotStartInTimeException`. `ServiceRestorer.request` below
        // reaches `WorkManager.getInstance()`, and the first call to that in a process
        // opens WorkManager's own database on this thread; on a cold start that is exactly
        // the kind of delay the timer is measuring.
        stopSelf()

        sdkWarn { logger.w(TAG, "startForeground(location) refused: ${e.message}") }
        events.tryEmit(TrackerEvent.Error(ErrorCode.FGS_START_REFUSED, e.message.orEmpty()))

        // The retry this comment has always claimed happens (EC-62), and until now did
        // not: nothing else in the SDK enqueues a restore from here. `HealthLoop` was the
        // only caller, and the health loop lives *inside* this service — so a refusal on
        // the very first start command, or a process killed before the first tick at
        // `healthLoopMs`, left the session open with nothing scheduled to bring it back.
        //
        // Through [ServiceRestorer] rather than straight at `RestoreWorker`, because the
        // direct call was a loop: the worker's only action is to start this service, and
        // on an app that is not eligible to start a foreground service from the background
        // — the normal state after an OEM kill on API 31+ — it lands right back here. The
        // restorer counts the attempts, backs them off, and stands down in favour of the
        // heartbeat alarm and the backstop rather than spending the expedited quota that
        // both of those still need.
        //
        // Requested unconditionally rather than behind a session check. `RestoreWorker`
        // reads `sessions.current()` at the moment it runs, so a refusal on the way out
        // of a session that has already closed resolves to a no-op there, and
        // `SessionTeardown` cancels the unique work by name regardless.
        ServiceRestorer.request(applicationContext)
        false
    }

    /**
     * Reports how long the platform's start-foreground window was actually used.
     *
     * The crash this measures for — `ForegroundServiceDidNotStartInTimeException` — fires
     * when the gap between `startForegroundService` and `startForeground` exceeds roughly
     * ten seconds, and the part of that gap the SDK does not control is usually the
     * larger one: on a start from `BootReceiver`, `ServiceHeartbeat` or `RestoreWorker`
     * the process is dead, so the host's `Application.onCreate` runs inside the window
     * before this service is even constructed. A crash report says only that the deadline
     * was missed; this says how close an ordinary start came to it.
     *
     * Two channels on purpose. The ordinary reading goes to logcat, which release builds
     * compile out — it is only interesting while looking at it. A reading past
     * [PROMOTION_WARN_MS] goes out as a [TrackerEvent.Diagnostic] as well, because that
     * one is a crash the host has not had yet, on a device the developer does not have,
     * and it has to survive into a release build to be worth anything.
     *
     * Silent when there is no stamp to measure against: a sticky restart or an OEM revival
     * constructs this service without anything in this process having called [start], and
     * a zero there would otherwise read as an instant promotion.
     */
    private fun reportPromotionLatency() {
        val requestedAt = startRequestedAtMs
        startRequestedAtMs = 0L
        if (requestedAt == 0L) return

        val elapsedMs = SystemClock.elapsedRealtime() - requestedAt
        sdkLog { logger.d(TAG, "startForeground reached ${elapsedMs}ms after start request") }
        if (elapsedMs >= PROMOTION_WARN_MS) {
            sdkWarn { logger.w(TAG, "foreground promotion took ${elapsedMs}ms") }
            events.tryEmit(
                TrackerEvent.Diagnostic(
                    "foreground promotion took ${elapsedMs}ms of the platform's ~10s " +
                        "start-foreground window; a slower start would crash with " +
                        "ForegroundServiceDidNotStartInTimeException. The window opens at " +
                        "startForegroundService(), so host Application.onCreate work on a " +
                        "cold start counts against it.",
                ),
            )
        }
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
            sdkWarn { logger.w(TAG, "notificationSmallIconResName '$resName' not found; using default") }
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
         * How much of the platform's start-foreground window may be used before the
         * promotion is reported as a near miss.
         *
         * Half of the ~10 second deadline. Set at a fraction rather than just under it
         * because the reading is a warning about the devices that did *not* report — the
         * same start on a colder cache, a slower disk or a busier CPU is the one that
         * crashes, and a threshold that only fires at 9 seconds would say nothing until
         * the crash was already happening in the field.
         */
        private const val PROMOTION_WARN_MS = 5_000L

        /**
         * `elapsedRealtime` at the last [start] call, or 0 when this process has not made
         * one that is still unaccounted for.
         *
         * Cleared by [reportPromotionLatency] as it is read, so a sticky restart — which
         * constructs the service with no [start] behind it — measures nothing rather than
         * measuring against a stamp left by the previous session.
         *
         * `elapsedRealtime` rather than the wall clock: it counts through sleep, which is
         * where a cold start on a dozing device spends its time, and it cannot be moved
         * backwards by an NTP correction mid-measurement.
         */
        @Volatile
        private var startRequestedAtMs: Long = 0L

        /**
         * Serialises [start] against [stop].
         *
         * Without it the two interleave freely, and there are six independent start
         * callers — `StartTrackingUseCase`, `BootReceiver`, `ServiceHeartbeat`,
         * `reviveServiceIfNeeded`, `RestoreWorker`, `BackstopWorker` — every one of them
         * on a background dispatcher, against one stop caller (`SessionTeardown`). Nothing
         * in that arrangement could express "a start command is in the air, do not tear
         * this down underneath it", which is the state [stop] now has to read.
         *
         * Held across the platform call on purpose. `startForegroundService` and
         * `stopService` are both asynchronous — they queue a command and return, and
         * neither re-enters this class — so the section is a binder round trip long and
         * cannot deadlock against `onStartCommand`, which runs later on the main thread.
         */
        private val commandLock = Any()

        /**
         * Used only when the host named an icon that does not resolve, or named none.
         * The title, text, channel and icon a host DID configure live in [ServiceConfig]
         * and are read from there — duplicating their defaults here is how the two drift.
         */
        internal val DEFAULT_SMALL_ICON = android.R.drawable.ic_menu_mylocation

        public const val ACTION_RESUME: String = "com.field360.tracker.RESUME"
        public const val ACTION_STOP: String = "com.field360.tracker.STOP"

        /**
         * Issues the start command, and reports whether the platform accepted it.
         *
         * **The throw is caught here, not left to each caller.** `startForegroundService`
         * itself raises `ForegroundServiceStartNotAllowedException` on API 31+ when the app
         * is not eligible to start one from the background — the refusal does not wait for
         * [promoteToForeground], because the service is never constructed. Two callers did
         * not handle that: `RestoreWorker`, where the throw failed the worker outright and
         * `WorkManager` recorded a `FAILED` job with nothing said about why, and
         * `BootReceiver`, where it propagated out of a `goAsync` block. Both are exactly
         * the paths that exist to survive a kill.
         *
         * @return false when the start command was refused. Callers that can do something
         *   about it — retry through [ServiceRestorer], fall through to another layer —
         *   should; callers on a best-effort path may ignore it.
         */
        public fun start(context: Context, config: ServiceConfig): Boolean {
            if (!config.foregroundService) return false
            val intent = Intent(context, TrackingService::class.java).setAction(ACTION_RESUME)
            return synchronized(commandLock) {
                try {
                    // Stamped immediately before the call, because this is the instant the
                    // platform's start-foreground deadline begins — see
                    // [reportPromotionLatency]. Set before rather than after so a process
                    // that is forked to answer this start command cannot promote before the
                    // stamp it is measured against exists.
                    //
                    // It is also what [stop] reads to decide how to stop, which is the
                    // second reason it may not be set after the call: a stop arriving in
                    // that gap would see a zero and take the abrupt path against a service
                    // that is, by then, already spoken for.
                    startRequestedAtMs = SystemClock.elapsedRealtime()
                    context.startForegroundService(intent)
                    true
                } catch (e: Exception) {
                    // Nothing was armed, so nothing is pending: leaving the stamp set would
                    // charge the next successful start with the time since this refusal,
                    // and would send the next [stop] down the wrong branch.
                    startRequestedAtMs = 0L
                    val graph = TrackerGraph.get(context.applicationContext)
                    sdkWarn {
                        graph.logger.w(TAG, "startForegroundService refused: ${e.message}")
                    }
                    graph.events.tryEmit(
                        TrackerEvent.Error(ErrorCode.FGS_START_REFUSED, e.message.orEmpty()),
                    )
                    false
                }
            }
        }

        /**
         * Stops the service, by whichever of the two routes is safe right now.
         *
         * ### The ordinary route
         *
         * `stopService`, and for the reason this has always given: on API 26+ a
         * `startService(ACTION_STOP)` round trip throws `IllegalStateException` when the
         * service is not already running, and "stop something that may already be dead" is
         * exactly the case that hits. `onDestroy` runs the same teardown either way.
         *
         * ### The route taken while a start is in the air
         *
         * A non-zero [startRequestedAtMs] means someone called [start] and the service has
         * not promoted yet — the platform is holding a ~10 second start-foreground timer
         * against a command that has not been delivered. Tearing the service down in that
         * window asks the platform to resolve two contradictory orders, and what it does
         * with them is a matter of AOSP version: the service may come down having never
         * called `startForeground`, or the queued start may be handed to an instance whose
         * `onDestroy` is already scheduled — which sets `running = true`, re-arms
         * supervision, and then cancels all of it a moment later, leaving an open session
         * with no service and nothing that noticed (the half of `TrackingUseCases`'s
         * superseded-instance note that re-arming supervision could not cover).
         *
         * So the stop is delivered as a **start command** instead. [ACTION_STOP] is queued
         * behind the start already in flight, both are delivered in order, and
         * `onStartCommand` answers the platform properly on each: the first promotes, the
         * second promotes and immediately tears down (see the [ACTION_STOP] branch, which
         * exists for precisely this). No interleaving to reason about and no dependence on
         * which AOSP release is underneath — the contract is simply satisfied both times.
         *
         * `startService` can still refuse — the app may have gone background between the
         * two calls — so a failure falls through to `stopService`, which is no worse than
         * what this did unconditionally before.
         *
         * A stale read in either direction is safe, which is why [startRequestedAtMs] is
         * not re-synchronised against the instance that clears it: a stale non-zero sends a
         * live service down the [ACTION_STOP] path, which stops it correctly, and a stale
         * zero is exactly today's behaviour.
         */
        public fun stop(context: Context): Unit = stop(context, restartFollows = false)

        /**
         * @param restartFollows true when the caller will issue a [start] as its very next
         *   act — `StartTrackingUseCase`, which tears the old session down before opening
         *   the new one. Such a caller **must** pass true: the [ACTION_STOP] route below
         *   queues the stop as a start command, its `stopSelf` clears the record's
         *   `startRequested`, and the caller's own start — issued microseconds later and
         *   sitting in the same undelivered batch — can go down with it. The abrupt route
         *   has no such ambiguity, and the start that follows is what puts the service
         *   back, so nothing is lost by taking it here.
         */
        internal fun stop(context: Context, restartFollows: Boolean) {
            synchronized(commandLock) {
                val startPending = startRequestedAtMs != 0L
                // Cleared whichever way this goes: the start it measured is being
                // cancelled, and leaving it set would route every later stop through
                // ACTION_STOP and charge the next promotion with the time since.
                startRequestedAtMs = 0L

                if (startPending && !restartFollows && deliverStopCommand(context)) return

                runCatching {
                    context.stopService(Intent(context, TrackingService::class.java))
                }
            }
        }

        /**
         * @return true when the platform accepted an [ACTION_STOP] start command, so the
         *   service is guaranteed an `onStartCommand` in which to satisfy the platform.
         *   False when it refused or found no service, leaving the caller to fall back.
         */
        private fun deliverStopCommand(context: Context): Boolean {
            val intent = Intent(context, TrackingService::class.java).setAction(ACTION_STOP)
            // `startService`, never `startForegroundService`: this must not arm a *second*
            // start-foreground timer on the way to stopping the thing.
            return runCatching { context.startService(intent) }.getOrNull() != null
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
