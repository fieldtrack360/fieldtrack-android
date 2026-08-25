package com.field360.fieldtrack.sample

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.field360.tracker.RawFix
import com.field360.tracker.Tracker
import com.field360.tracker.domain.model.ErrorCode
import com.field360.tracker.domain.model.LicenseStatus
import com.field360.tracker.domain.model.PermissionTier
import com.field360.tracker.domain.model.PointQuery
import com.field360.tracker.domain.model.ProviderState
import com.field360.tracker.domain.model.TrackSession
import com.field360.tracker.domain.model.TrackerEvent
import com.field360.tracker.domain.model.TrackerGeofence
import com.field360.tracker.domain.model.TrackerResult
import com.field360.tracker.permission.PermissionManager
import com.field360.traker.geo.math.Geodesy
import com.field360.traker.geo.model.FixDecision
import com.field360.traker.geo.model.GeoPoint
import com.field360.traker.geo.model.MotionState
import com.field360.traker.geo.model.TrackPoint
import com.field360.traker.geo.plot.model.Track
import com.field360.traker.geo.plot.model.TrackOptions
import com.field360.traker.sync.SyncEvent
import com.field360.traker.sync.SyncQueue
import com.field360.traker.sync.TrackerSync
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A licence or licence-API problem, shaped for a dialog.
 *
 * Three SDK channels feed this, and they mean different things — flattening them into one
 * type is the sample's choice, not the SDK's:
 *
 * | Channel | Means |
 * |---|---|
 * | `TrackerEvent.Error` with a `LICENSE_*` code | the SDK refused, or stopped |
 * | `TrackerEvent.LicenseChecked` with a non-`ACTIVE` status | the server answered, and it was not "fine" |
 * | `TrackerEvent.Diagnostic` starting `licence check failed` | the check could not run at all |
 *
 * **Only the first can stop tracking, and only for two codes.** Everything else is
 * information: a `LICENSE_UNKNOWN` means our backend has no record of a token that
 * verified offline, and an unreachable server means nothing was learned. Both leave
 * tracking running, which is why [stopsTracking] exists — a dialog that implied otherwise
 * would send an integrator hunting a fault that is not there.
 */
data class LicenseAlert(
    val headline: String,
    val detail: String,
    val stopsTracking: Boolean,
)

/**
 * Why uploads are not happening, in the sample's own words.
 *
 * A developer's alert, like [LicenseAlert] — it names the HTTP status and the SDK's own
 * behaviour rather than translating either into something a customer would read.
 *
 * @property terminal nothing will upload until something *changes* — a credential, a URL,
 *   a dependency. Distinguished from a transient failure because the two deserve opposite
 *   reactions: a 500 or a dead network is the retry loop working as designed and needs no
 *   one's attention, while a 403 means the loop has stopped and will not restart on its
 *   own. Collapsing them trains you to ignore the ones that matter.
 * @property queued rows waiting when the alert was raised.
 */
data class SyncAlert(
    val headline: String,
    val detail: String,
    val terminal: Boolean,
    val queued: Int,
)

/**
 * One view model over the whole SDK surface.
 *
 * Deliberately thin: the sample exists to exercise `Tracker`, not to demonstrate app
 * architecture. Everything interesting lives behind the SDK boundary.
 */
class TrackerViewModel(
    private val tracker: Tracker,
    private val sync: TrackerSync,
    private val captureLog: CaptureLog,
    /** Why `configure()` was rejected at startup, or null. See `SampleApplication`. */
    private val syncConfigError: String? = null,
    /** Whether Retrofit and OkHttp are actually linked. See `SampleApplication`. */
    private val transportAvailable: Boolean = true,
    /** The `device_id` going out in every request envelope, for display. */
    private val deviceId: String = "",
    /** What the host passed to `persistRawFixes()`. The SDK exposes no way to read it back. */
    private val persistRawFixes: Boolean = false,
    /**
     * Re-runs `configure()` with the given session id in `extraParams`.
     *
     * A lambda rather than the `Application` itself: a view model holding a Context is how
     * one outlives the process it came from, and this needs exactly one capability from it.
     */
    private val onSessionChanged: (String?) -> Unit = {},
) : ViewModel() {

    /** The session id currently baked into `SyncConfig.extraParams`. */
    private var configuredSessionId: String? = null

    /** Wall clock of the last confirmed upload, for the stalled-queue check. */
    private var lastSyncOkAtMs: Long = System.currentTimeMillis()

    /** Reset by any 2xx. A run of these is what promotes a transient failure to an alert. */
    private var consecutiveSyncFailures: Int = 0

    /**
     * A fault that will not clear on its own is standing. Survives dismissing the dialog,
     * because the dialog is the notification and this is the condition.
     */
    private var syncTerminal: Boolean = false

    /** One stall, one dialog. Cleared by an empty queue or any successful upload. */
    private var stallAlerted: Boolean = false

    /**
     * [PermissionManager.BackgroundRequest] flattened for the UI. The SDK's version
     * carries a Settings `Intent`, which has no business in a state holder — the host
     * already knows how to open its own settings page.
     */
    enum class BackgroundStep {
        GRANTED,
        NOT_APPLICABLE,
        NEEDS_FOREGROUND_FIRST,

        /** API 29 only — a runtime prompt still works. */
        PROMPT,

        /** API 30+ — Settings is the only route (EC-05). */
        SETTINGS,
    }

    data class UiState(
        val isTracking: Boolean = false,
        val sessionId: String? = null,
        val motionState: MotionState = MotionState.STOPPED,
        val providerState: ProviderState = ProviderState(),
        val pointCount: Int = 0,
        val lastEvent: String = "",
        val lastHeartbeatAtMs: Long? = null,
        val error: String? = null,
        val points: List<TrackPoint> = emptyList(),
        val rawFixes: List<RawFix> = emptyList(),
        /**
         * Whether `persistence.persistRawFixes` was actually configured, and why the last
         * read of the layer came back empty.
         *
         * Both exist because "no raw fixes" has three causes that look identical on
         * screen: the flag is off, the flag is on but this session was recorded before it
         * was, or the query threw. Telling a developer to enable a setting that is already
         * enabled is the failure mode worth designing out.
         */
        val rawFixesEnabled: Boolean = false,
        val rawFixesError: String? = null,
        val decisions: List<FixDecision> = emptyList(),
        val track: Track? = null,
        val log: List<String> = emptyList(),
        val permissionTier: PermissionTier = PermissionTier.NONE,
        val backgroundStep: BackgroundStep = BackgroundStep.NOT_APPLICABLE,
        val backgroundAttempts: Int = 0,
        val showBackgroundDialog: Boolean = false,
        val licenseStatus: String = "",
        /** Non-null while a licence problem is worth interrupting the user for. */
        val licenseAlert: LicenseAlert? = null,
        val logPath: String = "",
        val logSizeBytes: Long = 0,
        /** Newest first. Every session ever recorded, for the Home list. */
        val sessions: List<TrackSession> = emptyList(),
        /** Which session the Track/Debug/Decisions tabs are showing. */
        val selectedSessionId: String? = null,
        /**
         * Whether to ask the installed `RoadSnapProvider` for road geometry.
         *
         * Inert with no provider installed — `buildTrack` never leaves the device and the
         * flag changes nothing. With one installed it is the comparison that matters:
         * the same fixes drawn against the road network and drawn against themselves.
         */
        val snapToRoad: Boolean = true,
        /** `snap_unavailable` and friends, straight off the built track (EC-100). */
        val trackWarnings: List<String> = emptyList(),
        /** Result of the latest manually triggered SDK API check. */
        val apiCheckResult: String = "No API check run yet",
        val apiCheckRunning: Boolean = false,
        /**
         * The upload half, as a line the Home screen can print.
         *
         * `queued` is the diagnostic that matters offline: it should climb while the
         * device has no network and fall to zero shortly after one returns. Watching it
         * is how you tell "sync is working" from "sync has been silently failing", which
         * an event log alone cannot — a queue that never drains produces no events at all.
         */
        val syncEndpoint: String? = null,
        /** Sent as `device_id` in the request envelope. A generated per-install UUID. */
        val syncDeviceId: String = "",
        val syncQueued: Int = 0,
        val syncLastEvent: String = "",
        /**
         * One line naming the current upload state, healthy or not. Always populated once
         * sync is configured, so the Home card never has to guess between "fine" and
         * "nothing has happened yet".
         */
        val syncHealth: String = "",
        /** Paints the sync block red. True for transient failures as well as terminal ones. */
        val syncFailing: Boolean = false,
        /** Non-null while an upload problem is worth interrupting the user for. */
        val syncAlert: SyncAlert? = null,
        val syncRunning: Boolean = false,
        val registeredGeofenceCount: Int = 0,
        val geofenceEventCount: Int = 0,
        val geofences: List<TrackerGeofence> = emptyList(),
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /**
     * One-shot toasts, for watching capture happen without staring at the log.
     *
     * A [SharedFlow] rather than a state field because a toast is an event: replaying it
     * on the next recomposition — which is what state would do — would show the same
     * point again every time the screen rotated.
     */
    private val _toasts = MutableSharedFlow<String>(
        extraBufferCapacity = TOAST_BUFFER,
        // A dense stretch of capture outruns any consumer. Dropping the backlog is right:
        // the newest point is the interesting one, and a queue of stale toasts would
        // still be draining long after the drive ended.
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val toasts: SharedFlow<String> = _toasts.asSharedFlow()

    private var lastToastAtMs = 0L

    init {
        refreshPermissions()
        refreshGeofenceCounts()
        writeRunHeader()
        loadSessions()
        viewModelScope.launch {
            // Application-scoped in the SDK, lifecycle-scoped here: the collector dies
            // with the view model and native state stays the truth (EC-113).
            tracker.events.collect { event -> onEvent(event) }
        }
        viewModelScope.launch {
            tracker.state.collect { sdk ->
                _state.update {
                    it.copy(
                        isTracking = sdk.isTracking,
                        sessionId = sdk.currentSessionId,
                        motionState = sdk.motionState,
                        providerState = sdk.providerState,
                    )
                }
                // `extraParams` is frozen when configure() runs, so a session_id that is
                // meant to track the live session has to re-run it. Guarded on an actual
                // change: configure() re-registers the trigger and restarts the
                // connectivity watcher, and doing that on every state emission would
                // thrash both.
                if (sdk.currentSessionId != configuredSessionId) {
                    configuredSessionId = sdk.currentSessionId
                    onSessionChanged(sdk.currentSessionId)
                }
            }
        }
        observeSync()
    }

    /**
     * The upload half, for the Home screen.
     *
     * Two sources, because neither is enough alone. `TrackerSync.events` reports the
     * exchanges — including the ones WorkManager ran in the background — but says nothing
     * while the device is offline and nothing is being attempted. The poll covers exactly
     * that gap: a queue that is growing produces no events at all, and "240 queued" is the
     * only thing on screen that distinguishes working-offline from broken.
     *
     * Five seconds because `pendingCount()` is one indexed `COUNT(*)` and this screen is
     * a diagnostic. A production host would show the count on a refresh, not a timer.
     */
    private fun observeSync() {
        _state.update { it.copy(syncEndpoint = sync.endpoint, syncDeviceId = deviceId) }

        // Two states that are already wrong before a single request is made, so they are
        // raised at startup rather than inferred from a failure minutes away. Neither
        // returns early: the queue keeps growing in Room whether or not anything can
        // upload it, and the backlog is exactly the number worth showing while it does.
        when {
            syncConfigError != null -> raiseSyncAlert(
                headline = "Upload endpoint rejected",
                detail = "SyncConfig.validate() failed: $syncConfigError\n\nFix SYNC_URL " +
                    "in local.properties and reinstall. Nothing will ever upload until " +
                    "it validates — configure() throws rather than accepting a config " +
                    "that would fail on every request.",
                terminal = true,
            )
            // Checked only once sync is meant to be running: with no endpoint there is
            // no transport to be missing, and saying otherwise sends the reader after a
            // dependency they do not need.
            sync.isConfigured && !transportAvailable -> raiseSyncAlert(
                headline = "No HTTP transport on the classpath",
                detail = "OkHttpSyncTransport needs Retrofit and OkHttp, and both are " +
                    "compileOnly inside fieldtrack-sync. TrackerSync fell back to " +
                    "NoOpTransport, so every upload fails with no HTTP status — which on " +
                    "screen is indistinguishable from a dead network.\n\nAdd " +
                    "implementation(libs.retrofit) and implementation(libs.okhttp), or " +
                    "pass your own SyncTransport to configure().",
                terminal = true,
            )
        }

        // No endpoint and no config error is the offline-first default, not a fault.
        // Poll anyway so the card can still report what is accumulating.
        if (sync.isConfigured) {
            viewModelScope.launch { sync.events.collect(::onSyncEvent) }
        }
        viewModelScope.launch {
            while (true) {
                pollSyncQueue()
                delay(SYNC_POLL_MS)
            }
        }
    }

    private fun onSyncEvent(event: SyncEvent) {
        val line = when (event) {
            is SyncEvent.HttpResponse ->
                "HTTP ${event.statusCode ?: "no response"} · ${event.count} row(s)"
            is SyncEvent.NetworkAvailable ->
                "network back · ${event.queued} row(s) queued"
        }
        _state.update { current ->
            current.copy(
                syncLastEvent = line,
                log = (listOf("sync · $line") + current.log).take(LOG_LIMIT),
            )
        }

        val response = event as? SyncEvent.HttpResponse ?: return
        val code = response.statusCode
        if (code != null && code in HTTP_OK_RANGE) {
            onSyncSucceeded("uploading · last batch $code")
            return
        }

        consecutiveSyncFailures++
        classifySyncFailure(code, consecutiveSyncFailures)
    }

    /**
     * One failed exchange, turned into something a developer can act on.
     *
     * The split that matters is terminal vs transient, and it is not the same split as
     * 4xx vs 5xx. A 401 and a 403 stop the retry loop inside the SDK; a 404 does not, but
     * it will fail identically forever, so treating it as transient means watching a
     * queue grow against a URL that does not exist. A 429 or a 500 is the loop working.
     */
    private fun classifySyncFailure(code: Int?, failures: Int) {
        val queued = _state.value.syncQueued
        val (headline, detail, terminal) = when {
            code == HTTP_UNAUTHORIZED -> Triple(
                "401 — auth expired, tracking stopped",
                "The SDK tore the configuration down and CLEARED the upload queue: a 401 " +
                    "means the credential these rows were recorded under is gone, and " +
                    "keeping them would leak one user's positions into the next login.\n\n" +
                    "Tracking has stopped. Call configure() again with a fresh credential.",
                true,
            )
            code == HTTP_FORBIDDEN -> Triple(
                "403 — credential rejected, uploads halted",
                "Uploads have stopped and every row is KEPT — unlike a 401, a 403 says " +
                    "this credential may not write this resource, and the data is still " +
                    "valid. requestSync() is a no-op until configure() is called again.\n\n" +
                    "Usually a scope, a rotated key, or a server-side permission rule.",
                true,
            )
            code == null -> Triple(
                "No response from the server",
                "No HTTP exchange completed at all — a dead network, DNS failure, or " +
                    "timeout. This is a device-side problem, not a server one.\n\n" +
                    "Rows stay queued and WorkManager retries on a 30 s linear backoff, " +
                    "and again as soon as connectivity returns. If SYNC_URL is a dev " +
                    "tunnel, check the tunnel is still up.",
                false,
            )
            code == HTTP_NOT_FOUND -> Triple(
                "404 — endpoint does not exist",
                "The server answered, so the network is fine and the URL is wrong. The " +
                    "SDK will retry this forever with the same result.\n\n" +
                    "Check SYNC_URL is the full endpoint the batch is POSTed to, not a " +
                    "base path.",
                true,
            )
            code in HTTP_CLIENT_ERRORS && code != HTTP_TOO_MANY_REQUESTS -> Triple(
                "HTTP $code — the server rejected the request",
                "A 4xx that is not 401, 403 or 429 usually means the body is not what " +
                    "the server expects. Rows stay queued and are retried unchanged, so " +
                    "this will not clear on its own.\n\n" +
                    "Compare the payload against SYNC-MODULE.md §5.",
                true,
            )
            else -> Triple(
                "HTTP ${code ?: "?"} — upload failed",
                "Rows stay queued and are retried: linear 30 s backoff, or the server's " +
                    "own Retry-After when it sent one.\n\n" +
                    "$failures consecutive failure(s). A handful is the retry loop " +
                    "working; a rising queue that never falls is not.",
                false,
            )
        }

        _state.update {
            it.copy(syncFailing = true, syncHealth = headline)
        }

        // A transient failure alerts only once it has stopped looking transient. The
        // first 500 is the retry loop doing its job and interrupting for it would train
        // the reader to dismiss this dialog without reading it — which is exactly what
        // must not happen when the 403 arrives.
        if (terminal || failures >= TRANSIENT_FAILURES_BEFORE_ALERT) {
            raiseSyncAlert(headline, detail, terminal, queued)
        }
    }

    /**
     * The case no event can report: rows queued, nothing being attempted, silence.
     *
     * An upload that is never triggered emits nothing at all, so a queue that only grows
     * looks identical to a queue that is healthy and empty unless something counts it.
     * This is the check that catches a trigger path that stopped firing.
     */
    private suspend fun pollSyncQueue() {
        val queued = runCatching { sync.pendingCount() }.getOrDefault(0)
        // Endpoint re-read every tick, not cached: a 401 clears the configuration with no
        // host involvement, so a remembered value goes stale at exactly the moment it
        // matters.
        val endpoint = sync.endpoint
        _state.update { it.copy(syncQueued = queued, syncEndpoint = endpoint) }

        // A standing terminal fault already explains everything below it, and overwriting
        // its headline with a queue depth would replace the cause with a symptom.
        if (syncTerminal) return

        if (queued == 0) {
            stallAlerted = false
            if (!_state.value.syncFailing) {
                _state.update { it.copy(syncHealth = "idle · queue empty") }
            }
            return
        }
        // Rows with nowhere to go. Not a fault — this is the offline-first default — so
        // it is reported without turning the card red.
        if (endpoint == null) {
            _state.update { it.copy(syncHealth = "$queued row(s) held locally · no endpoint set") }
            return
        }

        val sinceOk = System.currentTimeMillis() - lastSyncOkAtMs
        if (sinceOk < STALE_SYNC_MS) return

        val stalledMin = sinceOk / 60_000
        _state.update {
            it.copy(
                syncFailing = true,
                syncHealth = "$queued row(s) queued, nothing uploaded in $stalledMin min",
            )
        }

        // Latched, not re-raised every tick: a dismissed dialog that reappears five
        // seconds later is a dialog nobody reads. Cleared by the queue reaching zero or
        // by any successful upload, so a genuine second stall alerts again.
        if (stallAlerted) return
        stallAlerted = true
        raiseSyncAlert(
            headline = "Queue is not draining",
            detail = "$queued row(s) have been waiting and no batch has succeeded for " +
                "$stalledMin minutes.\n\nIf the device is offline this is expected — " +
                "capture is offline-first and the queue is doing its job. If it is " +
                "online, a trigger path has stopped firing: check logcat for SyncWorker, " +
                "and use Sync now on the Home card to get the exact drain result.",
            terminal = false,
            queued = queued,
        )
    }

    private fun raiseSyncAlert(
        headline: String,
        detail: String,
        terminal: Boolean,
        queued: Int = _state.value.syncQueued,
    ) {
        if (terminal) syncTerminal = true
        _state.update {
            it.copy(
                syncFailing = true,
                syncHealth = headline,
                syncAlert = SyncAlert(headline, detail, terminal, queued),
                log = (listOf("sync · $headline") + it.log).take(LOG_LIMIT),
            )
        }
    }

    /**
     * A batch got through, so every latch above is stale by definition.
     *
     * Including [syncTerminal]: a 404 that starts working because the server was deployed,
     * or a 403 cleared by a re-`configure()`, is exactly the case where the standing fault
     * must stop suppressing everything else.
     */
    private fun onSyncSucceeded(health: String) {
        lastSyncOkAtMs = System.currentTimeMillis()
        consecutiveSyncFailures = 0
        syncTerminal = false
        stallAlerted = false
        _state.update { it.copy(syncFailing = false, syncHealth = health) }
    }

    fun dismissSyncAlert() {
        _state.update { it.copy(syncAlert = null) }
    }

    /**
     * Drains inline and reports exactly what happened.
     *
     * The most useful button on the screen while integrating: `syncNow()` returns the
     * drain result directly, including the `Retry` reason string, where the background
     * path can only report an HTTP status through an event. A dead transport says so here
     * and is merely "no response" there.
     */
    fun syncNow() = viewModelScope.launch {
        if (!sync.isConfigured) {
            raiseSyncAlert(
                headline = "Sync is not configured",
                detail = "No endpoint is set, so there is nothing to drain. Set SYNC_URL " +
                    "in local.properties and reinstall.\n\nThis is a valid state, not a " +
                    "fault: with no endpoint the SDK keeps every point in Room and opens " +
                    "no socket.",
                terminal = true,
            )
            return@launch
        }

        _state.update { it.copy(syncRunning = true) }
        val result = runCatching { sync.syncNow() }
        _state.update { it.copy(syncRunning = false) }

        result.onFailure { failure ->
            raiseSyncAlert(
                headline = "syncNow() threw",
                detail = "${failure::class.simpleName}: ${failure.message}",
                terminal = true,
            )
            return@launch
        }

        when (val outcome = result.getOrThrow()) {
            is SyncQueue.Result.Uploaded -> {
                onSyncSucceeded("uploaded ${outcome.count} row(s)")
                _toasts.tryEmit("Uploaded ${outcome.count} row(s)")
            }
            SyncQueue.Result.Empty -> {
                onSyncSucceeded("idle · queue empty")
                _toasts.tryEmit("Nothing queued")
            }
            // The two the events cannot describe: a reason string rather than a status.
            is SyncQueue.Result.Retry -> raiseSyncAlert(
                headline = "Upload failed — will retry",
                detail = "The drain reported: ${outcome.reason}\n\n" +
                    (outcome.retryAfterMs?.let { "Server asked to wait ${it / 1_000} s.\n\n" } ?: "") +
                    "Rows stay queued. \"No transport configured\" here means Retrofit or " +
                    "OkHttp is missing from the host; anything else is the server or the " +
                    "network.",
                terminal = false,
            )
            SyncQueue.Result.AuthExpired -> classifySyncFailure(HTTP_UNAUTHORIZED, ++consecutiveSyncFailures)
            SyncQueue.Result.Forbidden -> classifySyncFailure(HTTP_FORBIDDEN, ++consecutiveSyncFailures)
        }
        pollSyncQueue()
    }

    /**
     * Written once per view-model creation. Without it a file spanning several days of
     * field testing is unreadable — you cannot tell which device, build or permission
     * tier produced a given run.
     */
    private fun writeRunHeader() {
        captureLog.runHeader(
            sensors = runCatching { tracker.getSensors() }.getOrNull(),
            tier = tracker.permissionTier(),
            accuracy = tracker.permissions().accuracy(),
            provider = runCatching { tracker.providerState().value }.getOrNull(),
            mapsKeyPresent = BuildConfig.MAPS_API_KEY.isNotEmpty(),
            licensePresent = BuildConfig.TRACKER_LICENSE.isNotEmpty(),
        )
        _state.update {
            it.copy(
                licenseStatus = when {
                    BuildConfig.TRACKER_LICENSE.isNotEmpty() -> "configured from local.properties"
                    else -> "debug installs waived; add TRACKIT_LICENSE for release builds"
                },
            )
        }
        refreshLogStats()
    }

    private fun refreshLogStats() {
        _state.update { it.copy(logPath = captureLog.path, logSizeBytes = captureLog.sizeBytes()) }
    }

    /** Wipes the capture file. Testing runs otherwise accumulate across days. */
    fun clearLog() {
        captureLog.clear()
        captureLog.note("CLEARED", "log truncated by user")
        refreshLogStats()
    }

    /**
     * Turns a licence-related event into something worth interrupting the user for, or
     * null for everything else.
     *
     * `ACTIVE` deliberately produces nothing. A dialog on every successful check would be
     * unusable — the SDK checks on every app open — and "the licence is fine" is not news.
     * It still reaches the log and the Home screen's status line.
     */
    private fun licenseAlertFor(event: TrackerEvent): LicenseAlert? = when {
        event is TrackerEvent.Error && event.code.name.startsWith("LICENSE_") -> LicenseAlert(
            headline = when (event.code) {
                ErrorCode.LICENSE_REVOKED -> "This licence has been revoked."
                ErrorCode.LICENSE_EXPIRED -> "This licence has expired."
                ErrorCode.LICENSE_MISSING -> "No licence token was supplied."
                ErrorCode.LICENSE_INVALID -> "The licence token failed verification."
                ErrorCode.LICENSE_BUNDLE_MISMATCH ->
                    "The licence is for a different application id."
                else -> "The SDK reported a licence problem."
            },
            detail = "${event.code}: ${event.message}",
            // The only two that stop anything. Everything else is a refusal to start or
            // a diagnostic, and saying "tracking stopped" for those would be a lie.
            stopsTracking = event.code == ErrorCode.LICENSE_REVOKED ||
                event.code == ErrorCode.LICENSE_EXPIRED,
        )

        event is TrackerEvent.LicenseChecked && event.info.status != LicenseStatus.ACTIVE ->
            LicenseAlert(
                headline = when (event.info.status) {
                    LicenseStatus.UNKNOWN_KEY, LicenseStatus.INVALID_KEY ->
                        "The server has no record of this licence. That is a gap in our " +
                            "ledger, not a problem with your key — tracking continues."
                    LicenseStatus.PACKAGE_MISMATCH ->
                        "The server disagrees about the application id."
                    LicenseStatus.SDK_MISMATCH -> "The server does not recognise this SDK type."
                    LicenseStatus.UNRECOGNISED ->
                        "The server sent a status this build has never been taught. " +
                            "Ignored on purpose, so a new status cannot stop old installs."
                    else -> "The licence server reported: ${event.info.status}"
                },
                detail = buildString {
                    append("status=").append(event.info.status)
                    append(" valid=").append(event.info.valid)
                    append(" cached=").append(event.info.fromCache)
                    event.info.reason?.let { append("\nreason=").append(it) }
                },
                // A revoked or expired verdict arrives here *and* as an Error, and the
                // Error branch above is the one that owns the "stopped" wording.
                stopsTracking = false,
            )

        event is TrackerEvent.Diagnostic && event.message.startsWith(LICENCE_FAILED) ->
            LicenseAlert(
                headline = "The licence check could not run. Nothing was learned, and " +
                    "tracking is unaffected.",
                detail = event.message,
                stopsTracking = false,
            )

        else -> null
    }

    fun dismissLicenseAlert() {
        _state.update { it.copy(licenseAlert = null) }
    }

    private fun onEvent(event: TrackerEvent) {
        // Every capture goes to the file first, before any UI-shaped summarising. The
        // in-memory `log` below is capped at LOG_LIMIT lines for the screen; the file is
        // the complete record.
        captureLog.event(event)

        val line = when (event) {
            is TrackerEvent.Location ->
                "ACCEPT  ${event.point.acceptReason}  acc=${"%.0f".format(event.point.accuracy)}m"
            is TrackerEvent.LocationRejected ->
                "${verdictOf(event.decision)}  ${event.decision.reason}"
            is TrackerEvent.MotionChange -> "MOTION  ${event.state}"
            is TrackerEvent.ActivityChange -> "ACTIVITY ${event.activity}"
            is TrackerEvent.ProviderChange -> "PROVIDER gps=${event.state.gpsEnabled} tier=${event.state.permission}"
            is TrackerEvent.Error -> "ERROR   ${event.code}: ${event.message}"
            is TrackerEvent.Diagnostic -> "DIAG    ${event.message}"
            is TrackerEvent.SessionInterrupted -> "SESSION interrupted ${event.session.id.take(8)}"
            is TrackerEvent.EnabledChange -> "ENABLED ${event.enabled}"
            is TrackerEvent.PowerSaveChange -> "POWER   saver=${event.enabled}"
            is TrackerEvent.GeofenceAdded -> "GEOFENCE added ${event.geofence.id}"
            is TrackerEvent.GeofenceRemoved -> "GEOFENCE removed ${event.geofenceId}"
            is TrackerEvent.GeofenceEntered -> "GEOFENCE enter ${event.geofence.id}"
            is TrackerEvent.GeofenceExited -> "GEOFENCE exit ${event.geofence.id}"
            is TrackerEvent.Heartbeat -> "HEARTBEAT ${event.atMs}"
            is TrackerEvent.BatteryChange ->
                "BATTERY ${event.battery.percent}% charging=${event.battery.isCharging}"
            // Waived is the normal reading here: the sample is installed debuggable, so the
            // integrity layer probes nothing. Seeing that stated is the point — an empty
            // findings list in a debug build is not a claim that the device is clean.
            is TrackerEvent.IntegrityChange ->
                if (event.report.waived) {
                    "INTEGRITY waived (debuggable build)"
                } else {
                    "INTEGRITY ${event.report.findings.joinToString { "${it.signal}@${it.policy}" }}"
                }
            // Silent in this sample unless a licence URL and response key are configured:
            // with neither set the SDK makes no call, so no event arrives. That silence
            // means "not checked", never "licence is fine".
            is TrackerEvent.LicenseChecked ->
                "LICENCE ${event.info.status}" +
                    if (event.info.fromCache) " (cached)" else ""
        }

        licenseAlertFor(event)?.let { alert -> _state.update { it.copy(licenseAlert = alert) } }

        if (event is TrackerEvent.Location) onPointCollected(event.point)
        if (event is TrackerEvent.GeofenceAdded ||
            event is TrackerEvent.GeofenceRemoved ||
            event is TrackerEvent.GeofenceEntered ||
            event is TrackerEvent.GeofenceExited
        ) {
            refreshGeofenceCounts()
        }

        _state.update { current ->
            current.copy(
                lastEvent = line,
                lastHeartbeatAtMs = (event as? TrackerEvent.Heartbeat)?.atMs ?: current.lastHeartbeatAtMs,
                error = (event as? TrackerEvent.Error)?.let { "${it.code}: ${it.message}" } ?: current.error,
                pointCount = current.pointCount + if (event is TrackerEvent.Location) 1 else 0,
                log = (listOf(line) + current.log).take(LOG_LIMIT),
            )
        }

        // The SDK degrades to foreground-only rather than refusing (A16, EC-03), so this
        // arrives as a non-fatal event on start() and again if the grant is revoked
        // mid-session. An error string alone is useless here — from Android 11 there is
        // no prompt the user could have missed, so the steps have to be shown.
        if (event is TrackerEvent.Error && event.code == ErrorCode.BACKGROUND_PERMISSION_MISSING) {
            showBackgroundRationale()
        }
    }

    /**
     * A point made it to storage: logcat line always, toast at a rate a human can read.
     *
     * Logcat is unthrottled on purpose — it is the record you grep afterwards, and a
     * dropped line there is a hole in the evidence. The toast is the opposite: it exists
     * to answer "is it capturing right now" at a glance, so one every couple of seconds
     * says as much as sixty would.
     */
    private fun onPointCollected(point: TrackPoint) {
        val collected = _state.value.pointCount + 1
        Log.i(
            TAG,
            "point #$collected reason=${point.acceptReason} " +
                "lat=${point.latitude} lng=${point.longitude} " +
                "acc=${"%.1f".format(point.accuracy)}m spd=${"%.1f".format(point.speedMps)}m/s " +
                "odo=${"%.0f".format(point.odometerMeters)}m session=${point.sessionId.take(SHORT_ID)}",
        )

        val now = System.currentTimeMillis()
        // The first point of a session always shows: it is the one that answers whether
        // capture started at all, which is exactly the question the reboot defect raised.
        if (collected > 1 && now - lastToastAtMs < TOAST_MIN_INTERVAL_MS) return
        lastToastAtMs = now
        _toasts.tryEmit(
            "Point $collected · ${point.acceptReason} · ${"%.0f".format(point.accuracy)}m",
        )
    }

    fun start() = viewModelScope.launch {
        captureLog.note("START", "requested tag=sample tier=${tracker.permissionTier()}")
        when (val result = tracker.start(tag = "sample")) {
            is TrackerResult.Ok -> {
                captureLog.note("START", "ok session=${result.value.id}")
                // A new session means new counters. Carrying the previous run's totals
                // over is how a "why does it say 400 points" question starts.
                loadSessions()
                _state.update {
                    it.copy(
                        error = null,
                        sessionId = result.value.id,
                        // Follow the live session, not whatever was last browsed.
                        selectedSessionId = result.value.id,
                        pointCount = 0,
                        points = emptyList(),
                        rawFixes = emptyList(),
                        decisions = emptyList(),
                        track = null,
                    )
                }
            }
            is TrackerResult.Error -> {
                captureLog.note("START", "failed code=${result.code} message=${result.message}")
                _state.update { it.copy(error = "${result.code}: ${result.message}") }
            }
        }
        refreshLogStats()
    }

    fun stop() = viewModelScope.launch {
        // Capture the id before stopping — afterwards there is no current session to ask.
        val sessionId = tracker.currentSession()?.id ?: _state.value.sessionId
        tracker.stop()
        captureLog.note("STOP", "session=${sessionId ?: "-"}")
        dumpSession(sessionId)
        loadSessions()
        refresh()
    }

    /** Requests a snapshot without starting or modifying a tracking session. */
    fun testCurrentLocation() = runApiCheck("CURRENT") {
        when (val result = tracker.getCurrentLocation()) {
            is TrackerResult.Ok -> {
                val fix = result.value
                "OK lat=${"%.6f".format(fix.latitude)} lng=${"%.6f".format(fix.longitude)} " +
                    "acc=${"%.1f".format(fix.accuracy)}m provider=${fix.provider}"
            }
            is TrackerResult.Error -> "FAILED ${result.code}: ${result.message}"
        }
    }

    /** Requests a snapshot and registers/replaces a stable test fence at that position. */
    fun addTestGeofence() = runApiCheck("GEOFENCE_ADD") {
        when (val location = tracker.getCurrentLocation()) {
            is TrackerResult.Error ->
                "FAILED location ${location.code}: ${location.message}"
            is TrackerResult.Ok -> {
                val fix = location.value
                val fence = TrackerGeofence(
                    id = TEST_GEOFENCE_ID,
                    latitude = fix.latitude,
                    longitude = fix.longitude,
                    radiusM = TEST_GEOFENCE_RADIUS_M,
                    onEnterEvent = "sample_test_enter",
                    onExitEvent = "sample_test_exit",
                )
                when (val added = tracker.addGeofence(fence)) {
                    is TrackerResult.Ok -> {
                        refreshGeofenceCounts()
                        "OK id=${added.value.id} radius=${added.value.radiusM}m"
                    }
                    is TrackerResult.Error -> "FAILED ${added.code}: ${added.message}"
                }
            }
        }
    }

    /** Registers ten stable test fences in a ring around one current-location fix. */
    fun addTenTestGeofences() = runApiCheck("GEOFENCE_ADD_10") {
        when (val location = tracker.getCurrentLocation()) {
            is TrackerResult.Error ->
                "FAILED location ${location.code}: ${location.message}"
            is TrackerResult.Ok -> {
                val origin = GeoPoint(location.value.latitude, location.value.longitude)
                var addedCount = 0
                var firstFailure: String? = null
                repeat(TEST_GEOFENCE_BATCH_SIZE) { index ->
                    val angle = 2.0 * PI * index / TEST_GEOFENCE_BATCH_SIZE
                    val center = Geodesy.offsetMeters(
                        origin = origin,
                        northM = cos(angle) * TEST_GEOFENCE_RING_RADIUS_M,
                        eastM = sin(angle) * TEST_GEOFENCE_RING_RADIUS_M,
                    )
                    val id = "$TEST_GEOFENCE_BATCH_PREFIX${index + 1}"
                    when (
                        val result = tracker.addGeofence(
                            TrackerGeofence(
                                id = id,
                                latitude = center.latitude,
                                longitude = center.longitude,
                                radiusM = TEST_GEOFENCE_RADIUS_M,
                                onEnterEvent = "${id}_enter",
                                onExitEvent = "${id}_exit",
                            ),
                        )
                    ) {
                        is TrackerResult.Ok -> addedCount++
                        is TrackerResult.Error -> if (firstFailure == null) {
                            firstFailure = "${result.code}: ${result.message}"
                        }
                    }
                }
                refreshGeofenceCounts()
                if (addedCount == TEST_GEOFENCE_BATCH_SIZE) {
                    "OK added=$addedCount/$TEST_GEOFENCE_BATCH_SIZE"
                } else {
                    "FAILED added=$addedCount/$TEST_GEOFENCE_BATCH_SIZE first=$firstFailure"
                }
            }
        }
    }

    fun listTestGeofences() = runApiCheck("GEOFENCE_LIST") {
        val fences = tracker.getGeofences()
        refreshGeofenceCounts()
        if (fences.isEmpty()) "OK no registered geofences" else {
            "OK count=${fences.size} ids=${fences.joinToString { it.id }}"
        }
    }

    fun getTestGeofence() = runApiCheck("GEOFENCE_GET") {
        val fence = tracker.getGeofence(TEST_GEOFENCE_ID)
        if (fence == null) "OK test fence not registered" else {
            "OK id=${fence.id} lat=${"%.6f".format(fence.latitude)} " +
                "lng=${"%.6f".format(fence.longitude)} radius=${fence.radiusM}m"
        }
    }

    fun removeTestGeofence() = runApiCheck("GEOFENCE_REMOVE") {
        when (val result = tracker.removeGeofence(TEST_GEOFENCE_ID)) {
            is TrackerResult.Ok -> {
                refreshGeofenceCounts()
                "OK removed=${result.value}"
            }
            is TrackerResult.Error -> "FAILED ${result.code}: ${result.message}"
        }
    }

    fun removeAllTestGeofences() = runApiCheck("GEOFENCE_REMOVE_ALL") {
        when (val result = tracker.removeAllGeofences()) {
            is TrackerResult.Ok -> {
                refreshGeofenceCounts()
                "OK removed=${result.value}"
            }
            is TrackerResult.Error -> "FAILED ${result.code}: ${result.message}"
        }
    }

    fun readTestGeofenceHistory() = runApiCheck("GEOFENCE_HISTORY") {
        val events = tracker.getGeofenceEvents(limit = MAX_GEOFENCE_EVENTS)
        refreshGeofenceCounts()
        val latest = events.firstOrNull()
        if (latest == null) "OK no crossing events" else {
            "OK count=${events.size} latest=${latest.transition}:${latest.geofence.id}"
        }
    }

    fun clearTestGeofenceHistory() = runApiCheck("GEOFENCE_HISTORY_CLEAR") {
        val deleted = tracker.deleteGeofenceEvents()
        refreshGeofenceCounts()
        "OK deleted=$deleted"
    }

    private fun runApiCheck(kind: String, block: suspend () -> String) = viewModelScope.launch {
        if (_state.value.apiCheckRunning) return@launch
        _state.update { it.copy(apiCheckRunning = true, apiCheckResult = "$kind running...") }
        val result = runCatching { block() }
            .getOrElse { error -> "FAILED INTERNAL: ${error.message ?: error::class.simpleName}" }
        captureLog.note(kind, result)
        Log.i(TAG, "$kind $result")
        _state.update {
            it.copy(
                apiCheckRunning = false,
                apiCheckResult = "$kind $result",
                error = if (result.startsWith("FAILED")) result else null,
            )
        }
        _toasts.tryEmit("$kind ${if (result.startsWith("OK")) "passed" else "failed"}")
        refreshLogStats()
    }

    private fun refreshGeofenceCounts() {
        val geofences = tracker.getGeofences()
        _state.update {
            it.copy(
                registeredGeofenceCount = geofences.size,
                geofenceEventCount = tracker.getGeofenceEvents(limit = MAX_GEOFENCE_EVENTS).size,
                geofences = geofences,
            )
        }
    }

    /**
     * Everything the database holds for one session, appended on stop.
     *
     * The event stream only carries what happened while a collector was alive. This is
     * what actually got persisted — including fixes rejected before any event fired.
     */
    private suspend fun dumpSession(sessionId: String?) {
        val query = PointQuery(sessionId = sessionId, limit = MAX_POINTS)
        val raw = loadRawFixes(sessionId)
        captureLog.sessionDump(
            sessionId = sessionId,
            rawFixes = raw,
            decisions = tracker.getDecisions(sessionId, limit = MAX_DECISIONS),
            points = tracker.getPoints(query),
        )
        refreshLogStats()
    }

    /**
     * Layer 1, with the reason it is empty kept rather than thrown away.
     *
     * `runCatching { … }.getOrDefault(emptyList())` was silently turning a failed query
     * into "no raw fixes recorded", which is the one answer that sends you to check a
     * setting instead of the exception. Empty and broken are now different states.
     */
    private suspend fun loadRawFixes(sessionId: String?): List<RawFix> {
        if (sessionId == null) {
            _state.update { it.copy(rawFixesEnabled = persistRawFixes, rawFixesError = null) }
            return emptyList()
        }
        return runCatching { tracker.getRawFixes(sessionId) }
            .onSuccess {
                _state.update { s -> s.copy(rawFixesEnabled = persistRawFixes, rawFixesError = null) }
            }
            .getOrElse { failure ->
                _state.update { s ->
                    s.copy(
                        rawFixesEnabled = persistRawFixes,
                        rawFixesError = "${failure::class.simpleName}: ${failure.message}",
                        log = (listOf("getRawFixes failed: ${failure.message}") + s.log).take(LOG_LIMIT),
                    )
                }
                emptyList()
            }
    }

    /** Every session ever recorded, newest first. */
    fun loadSessions() = viewModelScope.launch {
        val all = tracker.getSessions().sortedByDescending { it.startedAtMs }
        _state.update { it.copy(sessions = all) }
    }

    /**
     * Show a past session on the Track/Debug/Decisions tabs.
     *
     * Loads the same four layers `refresh()` does, but pinned to the chosen id rather
     * than to whatever session is currently open — otherwise tapping a session from
     * yesterday would draw today's track.
     */
    fun openSession(sessionId: String) = viewModelScope.launch {
        val query = PointQuery(sessionId = sessionId, limit = MAX_POINTS)
        val points = tracker.getPoints(query)
        val track = tracker.buildTrack(query, trackOptions())
        val decisions = tracker.getDecisions(sessionId, limit = MAX_DECISIONS)
        val raw = loadRawFixes(sessionId)

        captureLog.note("OPEN", "session=$sessionId points=${points.size} segments=${track.segments.size}")
        // Dump on open, not only on stop. Diagnosing a drive means looking at it *after*
        // the drive, often days later, and without this the only session you could ever
        // export was the one you had just finished. `Clear` on the Home tab is the
        // pressure valve for the file growth this costs.
        captureLog.sessionDump(sessionId, raw, decisions, points)
        captureLog.trackSummary(track)

        _state.update {
            it.copy(
                selectedSessionId = sessionId,
                points = points,
                rawFixes = raw,
                decisions = decisions,
                track = track,
                trackWarnings = track.warnings,
                pointCount = points.size,
            )
        }
    }

    private fun trackOptions() = TrackOptions(zoom = 15f, snapToRoad = _state.value.snapToRoad)

    /**
     * Toggle map-matching and rebuild.
     *
     * Worth having in the sample rather than as a config constant, because the honest
     * comparison is the two lines side by side: everything the SDK does offline is an
     * approximation of a road it cannot see, and this is the only way to look at how close
     * that approximation got.
     */
    fun setSnapToRoad(enabled: Boolean) {
        _state.update { it.copy(snapToRoad = enabled) }
        refresh()
    }

    /** Pulls all three overlay layers plus the built track for the selected session. */
    fun refresh() = viewModelScope.launch {
        // A session picked from the Home list wins: without this, switching tabs while
        // viewing an old session would silently snap back to the live one.
        val sessionId = _state.value.selectedSessionId
            ?: tracker.currentSession()?.id
            ?: _state.value.sessionId
        val query = PointQuery(sessionId = sessionId, limit = MAX_POINTS)

        val points = tracker.getPoints(query)
        val decisions = tracker.getDecisions(sessionId, limit = MAX_DECISIONS)
        val raw = loadRawFixes(sessionId)
        val track = tracker.buildTrack(query, trackOptions())
        val geofences = tracker.getGeofences()

        _state.update {
            it.copy(
                points = points,
                decisions = decisions,
                rawFixes = raw,
                track = track,
                trackWarnings = track.warnings,
                pointCount = points.size,
                logPath = captureLog.path,
                logSizeBytes = captureLog.sizeBytes(),
                geofences = geofences,
                registeredGeofenceCount = geofences.size,
                geofenceEventCount = tracker.getGeofenceEvents(limit = MAX_GEOFENCE_EVENTS).size,
            )
        }
    }

    /**
     * Re-read the ladder. Cheap, and the only correct thing to do on resume: a grant can
     * change in Settings while this process is alive, and the Settings route is the whole
     * point of the background step (EC-05).
     */
    fun refreshPermissions() {
        val step = when (tracker.permissions().backgroundRequest()) {
            PermissionManager.BackgroundRequest.AlreadyGranted -> BackgroundStep.GRANTED
            PermissionManager.BackgroundRequest.NotApplicable -> BackgroundStep.NOT_APPLICABLE
            PermissionManager.BackgroundRequest.NeedsForegroundFirst -> BackgroundStep.NEEDS_FOREGROUND_FIRST
            is PermissionManager.BackgroundRequest.Prompt -> BackgroundStep.PROMPT
            is PermissionManager.BackgroundRequest.NeedsSettings -> BackgroundStep.SETTINGS
        }
        _state.update {
            it.copy(
                permissionTier = tracker.permissionTier(),
                backgroundStep = step,
                // Granted while we were away — close the dialog instead of asking again.
                showBackgroundDialog = it.showBackgroundDialog && step.isActionable(),
            )
        }
    }

    /**
     * Open the rationale. Never the request itself: Play policy wants the user to
     * understand *why* before the background step, and on Android 11+ the OS shows no
     * prompt at all, so the dialog is the only place the Settings detour can be
     * explained.
     */
    fun showBackgroundRationale() {
        refreshPermissions()
        val current = _state.value
        if (!current.backgroundStep.isActionable()) return

        // Attempt cap: a "Don't ask again" user must never be prompt-looped, so once the
        // runtime prompt is spent the Settings route is all that is offered (EC-14).
        val step = if (current.backgroundStep == BackgroundStep.PROMPT &&
            tracker.permissions().shouldStopAsking(current.backgroundAttempts)
        ) {
            BackgroundStep.SETTINGS
        } else {
            current.backgroundStep
        }

        _state.update { it.copy(backgroundStep = step, showBackgroundDialog = true) }
    }

    fun dismissBackgroundRationale() {
        _state.update { it.copy(showBackgroundDialog = false) }
    }

    /** The user agreed; the host performs the actual request or Settings jump. */
    fun onBackgroundRationaleConfirmed() {
        _state.update {
            it.copy(showBackgroundDialog = false, backgroundAttempts = it.backgroundAttempts + 1)
        }
    }

    private fun BackgroundStep.isActionable(): Boolean =
        this != BackgroundStep.GRANTED && this != BackgroundStep.NOT_APPLICABLE

    private fun verdictOf(decision: FixDecision): String =
        decision.verdict::class.simpleName.orEmpty().uppercase().padEnd(VERDICT_WIDTH)

    companion object {
        /** The prefix the SDK uses for a check that could not run. */
        private const val LICENCE_FAILED = "licence check failed"

        /**
         * Constructed from the `Application` rather than by a DI framework.
         *
         * `AndroidViewModelFactory.APPLICATION_KEY` is how a `ViewModel` reaches the
         * `Application` without being handed a `Context` — and this is the whole of the
         * sample's wiring. That is the part worth copying: the SDK asks the host for
         * nothing, so the host's DI story stays entirely the host's business.
         */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as SampleApplication
                TrackerViewModel(
                    tracker = app.tracker,
                    sync = app.sync,
                    captureLog = app.captureLog,
                    syncConfigError = app.syncConfigError,
                    transportAvailable = app.syncTransportAvailable,
                    deviceId = app.deviceId,
                    persistRawFixes = SampleApplication.PERSIST_RAW_FIXES,
                    onSessionChanged = app::installSync,
                )
            }
        }

        private const val LOG_LIMIT = 300

        /** How often the Home screen re-reads the upload queue depth. */
        private const val SYNC_POLL_MS = 5_000L

        /**
         * How long a non-empty queue may sit with no successful upload before it is
         * called a fault rather than a device that happens to be offline.
         *
         * Above the SDK's own 16-minute staleness bar would hide the very case that bar
         * exists to fix; far below it would fire on every tunnel. Five minutes is long
         * enough that ordinary loss of signal passes unremarked.
         */
        private const val STALE_SYNC_MS = 5 * 60 * 1_000L

        /**
         * Consecutive failures before a *transient* failure earns a dialog.
         *
         * One 500 is the retry loop working. Interrupting for it teaches the reader to
         * dismiss this dialog unread, which is precisely what must not happen when the
         * terminal one arrives.
         */
        private const val TRANSIENT_FAILURES_BEFORE_ALERT = 3

        private val HTTP_OK_RANGE = 200..299
        private val HTTP_CLIENT_ERRORS = 400..499
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val MAX_POINTS = 5_000
        private const val MAX_DECISIONS = 500
        private const val MAX_GEOFENCE_EVENTS = 5_000
        private const val TEST_GEOFENCE_ID = "sample-api-test"
        private const val TEST_GEOFENCE_BATCH_PREFIX = "sample-batch-"
        private const val TEST_GEOFENCE_BATCH_SIZE = 10
        private const val TEST_GEOFENCE_RADIUS_M = 100f
        private const val TEST_GEOFENCE_RING_RADIUS_M = 400.0
        private const val VERDICT_WIDTH = 7

        /** Matches the SDK's own `Tracker/<tag>` logcat convention, so one grep finds both. */
        private const val TAG = "Tracker/Sample"
        private const val SHORT_ID = 8
        private const val TOAST_BUFFER = 4

        /** Slightly longer than `Toast.LENGTH_SHORT`, so toasts never queue up behind each other. */
        private const val TOAST_MIN_INTERVAL_MS = 2_500L
    }
}
