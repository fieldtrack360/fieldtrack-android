package com.field360.fieldtrack.sample

import android.app.Application
import android.os.Build
import android.util.Log
import com.field360.tracker.AccuracyProfile
import com.field360.tracker.DesiredAccuracy
import com.field360.tracker.LocationProviderType
import com.field360.tracker.Tracker
import com.field360.tracker.TrackerConfig
import com.field360.tracker.TrackingMode
import com.field360.tracker.domain.model.TrackerGeofence
import com.field360.tracker.domain.model.TrackerResult
import com.field360.tracker.integrity.IntegrityPolicy
import com.field360.traker.geo.model.MockPolicy
import com.field360.traker.snap.OsrmSnapProvider
import com.field360.traker.sync.SyncConfig
import com.field360.traker.sync.TrackerSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.time.Duration

/**
 * A plain `Application` — no DI framework, no annotations, nothing the SDK requires.
 *
 * That is the point of the sample: `Tracker.getInstance(this)` is the entire integration.
 * An earlier revision had to be `@HiltAndroidApp` because Hilt shipped inside the SDK;
 * it no longer does (see `Tracker`'s KDoc).
 *
 * `ready()` is called once here rather than from an Activity: it restores persisted
 * filter state and reports an interrupted session, and both should happen before any
 * UI exists to observe them.
 */
class SampleApplication : Application() {

    val tracker: Tracker by lazy { Tracker.getInstance(this) }

    val captureLog: CaptureLog by lazy { CaptureLog(this) }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val sync: TrackerSync by lazy { TrackerSync.getInstance(this) }

    /** The `device_id` sent with every upload. Per-install, not per-device — see the class. */
    private val identity: SyncIdentity by lazy { SyncIdentity(this) }

    /** Exposed so the Home card can show what is going out in the request envelope. */
    val deviceId: String get() = identity.deviceId

    /**
     * Why [installSync] gave up, or `null` if it did not.
     *
     * Held on the Application rather than reported through `TrackerSync`, because the
     * failure happens *before* there is a configuration to report it against — a config
     * that does not validate is never installed, so `sync.endpoint` stays null and looks
     * identical to "no SYNC_URL set". The view model reads this to tell those two apart.
     */
    @Volatile
    var syncConfigError: String? = null
        private set

    /**
     * Whether the transport `OkHttpSyncTransport` needs is actually on the classpath.
     *
     * Retrofit and OkHttp are `compileOnly` inside `fieldtrack-sync`, so a host that
     * forgets to add them still compiles and still calls `configure()` successfully — and
     * then every upload fails against `NoOpTransport`, reporting no HTTP status at all.
     * On the wire that is indistinguishable from a dead network, which is the one failure
     * a developer is most likely to dismiss as "I'll be online later".
     *
     * A classpath fact, so it is checked once here rather than inferred from events.
     */
    val syncTransportAvailable: Boolean by lazy {
        runCatching { Class.forName("retrofit2.Retrofit") }.isSuccess &&
            runCatching { Class.forName("okhttp3.OkHttpClient") }.isSuccess
    }

    override fun onCreate() {
        super.onCreate()
        installRoadSnapping()
        installSync()
        scope.launch {
            val config = buildTrackerConfig()
            Log.i(TRACKER_TAG, "ready() config=$config")
            when (val result = tracker.ready(config)) {
                is TrackerResult.Ok ->
                    Log.i(TRACKER_TAG, "ready() ok state=${result.value}")

                is TrackerResult.Error ->
                    Log.e(TRACKER_TAG, "ready() failed code=${result.code} message=${result.message}")
            }
        }
    }

    /**
     * Every option the SDK exposes, set explicitly.
     *
     * A real host sets four or five of these and takes the defaults for the rest — that is
     * the intended way to use the builder, and every value below that carries no comment
     * *is* the default. It is written out in full for one reason: a configuration surface
     * nobody exercises is a surface nobody knows is broken. Setting each one here means a
     * renamed setter, a removed one, or a value the validator now refuses fails at this
     * call site, in the sample, rather than in a host's app.
     *
     * Two deliberate omissions, both because they cannot coexist with what is set:
     *
     *  - `geolocation()`, `motion()`, `service()`, `persistence()`, `sensors()`,
     *    `security()` and `accuracy()` each take a whole sub-config object and **replace**
     *    it. Calling one after the granular setters below would silently discard them, so a
     *    host picks one style or the other. This uses the granular one.
     *  - `maxAccuracyMeters()` implies `AccuracyProfile.CUSTOM`, and `validate()` rejects
     *    it against any other profile rather than ignoring it. With `STRICT` set below it
     *    is not an option that exists — see `AccuracyConfig`.
     */
    @Suppress("LongMethod") // The length is the point: every setter, none hidden.
    private fun buildTrackerConfig(): TrackerConfig =
        // The builder rather than the constructor on purpose: it is the surface a Java
        // host has, so the sample exercises it.
        TrackerConfig.builder()
            // ── identity and lifecycle ──────────────────────────────────────
            .license(BuildConfig.TRACKER_LICENSE.takeIf { it.isNotBlank() })
            // Read only by fieldtrack-sync, and only when SyncConfig carries no absolute
            // URL of its own. Harmless when that module is absent.
            .baseUrl(BuildConfig.SYNC_URL.takeIf { it.isNotBlank() })
            // reset = true (the default) during development, so edited config actually
            // takes effect. Flipping it to false is the classic "my config changes do
            // nothing" bug (SDK-COMPARISON §5).
            .reset(true)

            // ── geolocation ─────────────────────────────────────────────────
            .trackingMode(TrackingMode.ADAPTIVE)
            // Fused by default. Switch to GPS_ONLY on a device with no Play Services, or
            // when a Wi-Fi centroid must never reach the record.
            .provider(LocationProviderType.FUSED)
            .desiredAccuracy(DesiredAccuracy.HIGH)
            // The accuracy meter. BALANCED is the engine's own 30 m moving ceiling;
            // STRICT (20 m) trades points for a line that never zigzags.
            .accuracyProfile(AccuracyProfile.STRICT)
            // The post-gap re-anchor bar. Left to the profile by default; set here because
            // the first fix after a blackout decides where every later fix is judged from.
            .recoveryTrustMeters(15f)
            // 15 s / 5 s rather than the SDK's 60 s / 30 s: the sample is a diagnostic and
            // a sparse stream makes every other layer harder to read. intervalMs must stay
            // >= fastestIntervalMs (EC-120).
            .intervalMs(15_000)
            .fastestIntervalMs(5_000)
            .maxUpdateDelayMs(60_000)
            .maxFixAgeMs(10_000)
            // The three cadence tiers: base above, vehicular once fixes report vehicle
            // speed, turn burst across a corner. They must stay ordered — a burst slower
            // than the tier it accelerates makes turn geometry worse (EC-45).
            .adaptiveCadence(true)
            .vehicularIntervalMs(12_000)
            .turnBurst(true)
            .turnBurstIntervalMs(4_000)
            // 1 Hz navigation. Off: it costs battery no diagnostic session needs. Flip it
            // on to watch the fast path — it requires the foreground service, which is on.
            .navigationMode(false)
            .navigationIntervalMs(1_000)
            .navigationFastestIntervalMs(500)
            .oneShotTimeoutMs(30_000)
            // FLAG stores mock fixes with a flag rather than dropping them, so an emulated
            // route is still visible in the overlay. REJECT is the production choice.
            .mockLocationPolicy(MockPolicy.FLAG)

            // ── motion ──────────────────────────────────────────────────────
            .activityRecognition(true)
            .activityRecognitionIntervalMs(10_000)
            .activityConfidenceMin(75)
            .snapshotConfidenceMin(50)
            .disableStopDetection(false)
            // false: a stationary stretch suppresses capture but never ends the session.
            // true hands that decision to the SDK, and the host stops being told why.
            .stopOnStationary(false)
            .stopTimeoutMin(5)
            .stationaryRadiusM(150f)
            .stationaryGeofenceId(TrackerGeofence.DEFAULT_ID)
            .stationaryGeofenceOnEnterEvent(TrackerGeofence.DEFAULT_ENTER_EVENT)
            .stationaryGeofenceOnExitEvent(TrackerGeofence.DEFAULT_EXIT_EVENT)
            .motionTriggerDelayMs(0)
            // Must stay >= 5x the sampling interval, or the heartbeat fires on every fix
            // and defeats stationary suppression entirely (EC-121).
            .heartbeatIntervalSec(900)
            .persistHeartbeat(false)
            // Store a point once the heading has turned this far since the last one, and
            // let CornerWindow reconsider a rejected fix one fix later (EC-45e).
            .bearingChangeCaptureDeg(30)
            .cornerAnchorCapture(true)

            // ── sensors ─────────────────────────────────────────────────────
            .useSignificantMotion(true)
            .useStepCorroboration(true)
            .useAccelerometerVeto(true)
            // Off by default and left off: pressure adds nothing to a 2-D track and the
            // barometer is missing on most mid-range hardware.
            .useBarometer(false)
            .stepBatchLatencyMs(60_000)
            .useGyroTurnPrediction(true)

            // ── service ─────────────────────────────────────────────────────
            .foregroundService(true)
            // false — a swipe-away must not silently end tracking. See ServiceConfig.
            .stopOnTerminate(false)
            .startOnBoot(true)
            .healthLoopMs(120_000)
            .watchdogIntervalMs(60_000)
            .watchdogThrottleMs(900_000)
            .backstopIntervalMin(15)
            .deadTrackerMovingMin(30)
            .deadTrackerStationaryMin(60)
            .wakeLockMs(20_000)
            // What the user actually sees for the whole session. All of it is the host's,
            // the channel included: an SDK-named channel in an app's notification settings
            // is a support ticket.
            .notification("FieldTrack sample", "Recording your location")
            .notificationChannel("fieldtrack_sample_tracking", "Location tracking")
            // By NAME, not by @DrawableRes id: the config is persisted, and an id does not
            // survive the next R regeneration. Resolved against this app's resources.
            .notificationSmallIconResName("ic_stat_tracking")

            // ── persistence ─────────────────────────────────────────────────
            .maxDaysToPersist(7)
            // 0 = no row cap; the TTL above is then the only limit.
            .maxRecords(0)
            // Raw fixes are layer 1 of the debug overlay. Off by default in the SDK
            // because it is a diagnostic, not production behavior — but the sample exists
            // precisely to diagnose (spec §8.4).
            .persistRawFixes(PERSIST_RAW_FIXES)
            .rawRingCapacity(5_000)
            // Layer 3: every judged fix in point form, so a missing point can be compared
            // against the ones that made it (v6).
            .persistRawPoints(PERSIST_RAW_POINTS)
            .rawPointRingCapacity(20_000)
            // Layer 2: the verdict and reason for every fix — what the Decision Log reads.
            .persistDecisions(true)
            .decisionRetentionDays(3)
            .decisionMaxRows(50_000)

            // ── security / device integrity ─────────────────────────────────
            //
            // Every policy below is inert in this build. The sample is installed
            // debuggable and the integrity layer waives itself there rather than reporting
            // findings no developer can act on — `IntegrityChange` then says "waived",
            // which is a different statement from "clean".
            .securityEnabled(true)
            .accessibilityPolicy(IntegrityPolicy.WARN)
            .developerModePolicy(IntegrityPolicy.WARN)
            .hookingPolicy(IntegrityPolicy.BLOCK)
            .clockPolicy(IntegrityPolicy.WARN)
            // BLOCK here and MockPolicy.FLAG above are reconciled by the SDK, not by the
            // host: outside a debuggable build the stricter of the two wins and the
            // pipeline rejects mock fixes regardless. See ResolveConfigUseCase.
            .mockLocationIntegrityPolicy(IntegrityPolicy.BLOCK)
            // Accessibility services the host vouches for — a screen reader is not an
            // attacker, and an empty allowlist flags every one of them.
            .accessibilityAllowlist(emptySet())
            .maxClockSkewMs(120_000)
            .integrityRecheckIntervalMs(15 * 60_000)
            .build()

    /**
     * The upload half, if `SYNC_URL` is set in `local.properties`.
     *
     * Blank is the default and a working configuration: no endpoint is configured, points
     * accumulate in Room, and the SDK opens no socket. Everything below is what a host
     * adds on top of an offline-first recorder — not what makes it work.
     *
     * **Configured before `ready()`**, like the snap provider and for a related reason: a
     * `configure()` after tracking has started leaves whatever was captured in between
     * with no trigger registered, so those points wait for the next supervision tick
     * rather than uploading as they arrive.
     *
     * What `autoSync = true` actually buys, in the order the SDK tries it:
     *
     *  1. a point is accepted → a drain is requested (throttled to once a minute);
     *  2. the device returns to a usable network → a drain is requested, which is what
     *     empties a queue recorded in a tunnel or a basement;
     *  3. the health loop and the 15-minute backstop notice rows that path 1 never saw;
     *  4. the session closes → a network-constrained drain is left enqueued, so a backlog
     *     survives the process being killed and uploads when connectivity returns.
     *
     * ## `extraParams` and the session id
     *
     * The body is `{ "device_id": …, "session_id": …, "location": [ … ] }` — an envelope of
     * identity wrapping the batch.
     *
     * `extraParams` is fixed when `configure()` runs, so a value that changes needs
     * `configure()` to run again. That is why this is public and why `TrackerViewModel`
     * calls it on every session change rather than only at startup.
     *
     * **Know what a top-level `session_id` claims.** It labels the whole envelope, and a
     * batch is `pending(batchSize)` — oldest rows first, across *every* unsent session. A
     * device that recorded three sessions offline uploads all three under whatever session
     * was current at the last `configure()`, and the server has no way to tell. `SyncPoint`
     * carries no session id of its own, so nothing downstream can correct it.
     *
     * It is right when the queue is drained before each session ends, which for an online
     * device it effectively is. It is wrong for exactly the offline backlog this SDK is
     * built to survive. If per-row attribution matters, the honest fix is a `session_id`
     * on each point rather than on the envelope — see the note in `SYNC-MODULE.md` §5.
     *
     * Re-`configure()` also clears a 403 halt, which is its documented recovery path. A
     * halted uploader therefore un-halts at the next session start and retries once.
     */
    fun installSync(sessionId: String? = null) {
        val url = BuildConfig.SYNC_URL
        if (url.isBlank()) return

        // `configure()` throws on a config that does not validate — a non-https URL, an
        // unsupported verb, a batch size out of range. That is the SDK's deliberate
        // fail-fast, and it is correct: this runs on the host's own thread while it
        // assembles a value. What is NOT correct is letting it take Application.onCreate
        // with it, because a typo in local.properties then presents as "the sample app
        // does not launch" with the real reason only in logcat.
        runCatching {
            sync.configure(
                SyncConfig.builder()
                    .url(url)
                    // Upload as points arrive. With it off the host owns the schedule and
                    // calls syncNow() itself — including the connectivity path above,
                    // which is deliberately part of "auto".
                    .autoSync(true)
                    // 100 is the default. Larger means fewer requests but a bigger retry
                    // unit: a failure re-sends the whole batch.
                    .batchSize(100)
                    // Left false on purpose. Field workers are on cellular most of the
                    // day, and requiring Wi-Fi parks the queue until they are back at an
                    // office — which reads as "sync is broken" rather than as the choice
                    // it is.
                    .requiresUnmeteredNetwork(false)
                    // Top-level envelope fields, alongside the batch's `location` array:
                    //
                    //   { "device_id": "…", "session_id": "…", "location": [ … ] }
                    //
                    // `device_id` is a per-install UUID and is genuinely constant, so it
                    // belongs here without qualification.
                    //
                    // `session_id` is NOT constant, which is why installSync() is called
                    // again on every session start. Read the KDoc above before relying on
                    // it — an envelope field describes the whole batch, and a batch is
                    // not guaranteed to belong to one session.
                    .extraParams(
                        buildMap {
                            put("device_id", "Google pixel 4A")
                            // Omitted rather than sent null while no session is open:
                            // `null` is not a supported extraParams value, and a literal
                            // "none" would be a session id the server could index on.
                            sessionId?.let { put("session_id", it) }
                        },
                    )
                    .build(),
            )
        }.onFailure { failure ->
            syncConfigError = failure.message ?: failure::class.simpleName ?: "unknown error"
        }
    }

    /**
     * Road snapping, if `OSRM_BASE_URL` is set in `local.properties`.
     *
     * Blank is the default and a perfectly good configuration: no provider is installed,
     * `buildTrack` never leaves the device, and the polyline is drawn from the fixes that
     * were captured. Everything up to this point — the acceptance pipeline, cornering
     * process noise, the spline — makes that line as good as it can be *without a road
     * network*. This is the step that gives it one.
     *
     * Installed before `ready()` because a provider set after the first `buildTrack` would
     * leave that track unsnapped and every later one snapped, which looks like a bug in
     * the SDK rather than a race in the host.
     */
    private fun installRoadSnapping() {
        val baseUrl = BuildConfig.OSRM_BASE_URL
        if (baseUrl.isBlank()) return

        tracker.setRoadSnapProvider(
            OsrmSnapProvider(
                baseUrl = baseUrl,
                client = OkHttpClient.Builder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .readTimeout(Duration.ofSeconds(10))
                    .build(),
            ),
        )
    }

    companion object {
        /**
         * The one logcat tag the whole sample writes under, SDK callbacks included.
         *
         * `adb logcat -s TRACKER_TAG` is then the complete picture: the config at startup,
         * every `TrackerEvent`, every `SyncEvent`, and each SDK state flow the sample
         * observes.
         */
        const val TRACKER_TAG: String = "TRACKER_TAG"

        /**
         * Layer 1 of the debug overlay: every fix as the OS delivered it, before any gate.
         *
         * A named constant rather than a literal in the builder because the UI has to
         * state the same fact and there is no way to read it back — `Tracker` exposes no
         * resolved config. Two places asserting "raw fixes are on" from two sources is how
         * a screen ends up telling you to enable something that is already enabled.
         */
        const val PERSIST_RAW_FIXES: Boolean = true

        /** Layer 3: every judged fix in point form, accepted or not. */
        const val PERSIST_RAW_POINTS: Boolean = true
    }
}
