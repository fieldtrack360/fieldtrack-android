package com.field360.fieldtrack.sample

import android.app.Application
import android.os.Build
import com.field360.tracker.AccuracyProfile
import com.field360.tracker.LocationProviderType
import com.field360.tracker.Tracker
import com.field360.tracker.TrackerConfig
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
            // reset = true (the default) during development, so edited config actually
            // takes effect. Flipping it to false is the classic "my config changes do
            // nothing" bug (SDK-COMPARISON §5).
            tracker.ready(
                // The builder rather than the constructor here on purpose: it is the
                // surface a Java host has, so the sample exercises it.
                TrackerConfig.builder()
                    .license(BuildConfig.TRACKER_LICENSE.takeIf { it.isNotBlank() })
                    // Fused by default. Switch to GPS_ONLY on a device with no Play
                    // Services, or when a Wi-Fi centroid must never reach the record.
                    .provider(LocationProviderType.FUSED)
                    // The accuracy meter. BALANCED is the engine's own 30 m moving ceiling;
                    // STRICT (20 m) trades points for a line that never zigzags.
                    .accuracyProfile(AccuracyProfile.STRICT)
                    // Raw fixes are layer 1 of the debug overlay. Off by default in the
                    // SDK because it is a diagnostic, not production behavior — but the
                    // sample exists precisely to diagnose (spec §8.4).
                    .persistRawFixes(PERSIST_RAW_FIXES)
                    // Layer 3: every judged fix in point form, so a missing point can
                    // be compared against the ones that made it (v6).
                    .persistRawPoints(PERSIST_RAW_POINTS)
                    .build(),
            )
        }
    }

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
