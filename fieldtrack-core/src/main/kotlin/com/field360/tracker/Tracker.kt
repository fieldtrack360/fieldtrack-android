package com.field360.tracker

import android.content.Context
import com.field360.tracker.capture.FixIngestor
import com.field360.tracker.capture.LiveTrackFeed
import com.field360.tracker.capture.OneShotProvider
import com.field360.tracker.data.platform.BatteryMonitor
import com.field360.tracker.domain.model.BatteryInfo
import com.field360.tracker.domain.model.ErrorCode
import com.field360.tracker.domain.model.PermissionTier
import com.field360.tracker.domain.model.PointQuery
import com.field360.tracker.domain.model.ProviderState
import com.field360.tracker.domain.model.TrackerGeofence
import com.field360.tracker.domain.model.TrackerGeofenceEvent
import com.field360.tracker.domain.model.TrackerEvent
import com.field360.tracker.domain.model.TrackerResult
import com.field360.tracker.domain.model.TrackerState
import com.field360.tracker.domain.model.TrackSession
import com.field360.tracker.data.db.RawFixDao
import com.field360.tracker.data.db.RawPointDao
import com.field360.tracker.data.db.toDomain
import com.field360.tracker.di.TrackerGraph
import com.field360.tracker.domain.model.LicenseAction
import com.field360.tracker.domain.model.LicenseInfo
import com.field360.tracker.domain.usecase.CheckLicenseRevocationUseCase
import com.field360.tracker.domain.usecase.GetCachedLicenseActionUseCase
import com.field360.tracker.domain.usecase.GetLicenseInfoUseCase
import com.field360.tracker.license.LicenseGate
import com.field360.tracker.license.LicenseState
import com.field360.tracker.work.LicenseCheckWorker
import com.field360.tracker.domain.repository.DecisionRepository
import com.field360.tracker.domain.repository.SessionRepository
import com.field360.tracker.domain.repository.TrackPointRepository
import com.field360.tracker.domain.usecase.ResolveConfigUseCase
import com.field360.tracker.domain.usecase.StartTrackingUseCase
import com.field360.tracker.domain.usecase.StopTrackingUseCase
import com.field360.traker.geo.export.GeoJson
import com.field360.traker.geo.filter.ClockGuard
import com.field360.traker.geo.export.TrackJson
import com.field360.traker.geo.model.ActivityType
import com.field360.traker.geo.model.FixDecision
import com.field360.traker.geo.model.GeoPoint
import com.field360.traker.geo.model.MovementStatus
import com.field360.traker.geo.model.TrackPoint
import com.field360.traker.geo.model.TrackFix
import com.field360.traker.geo.plot.Snapper
import com.field360.traker.geo.plot.TrackBuilder
import com.field360.traker.geo.plot.model.LiveTrackUpdate
import com.field360.traker.geo.plot.model.Track
import com.field360.traker.geo.plot.model.TrackOptions
import com.field360.traker.geo.port.Clock
import com.field360.traker.geo.port.RoadSnapProvider
import com.field360.traker.geo.port.SnapFix
import com.field360.traker.geo.port.SnapRequest
import com.field360.tracker.integrity.internal.IntegrityMonitor
import com.field360.tracker.integrity.IntegrityReport
import com.field360.tracker.motion.DeviceSensors
import com.field360.tracker.motion.SensorProbe
import com.field360.tracker.motion.StationaryFence
import com.field360.tracker.permission.PermissionManager
import com.field360.tracker.capture.CaptureGate
import com.field360.tracker.permission.ProviderStateMonitor
import com.field360.tracker.work.PruneWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import java.util.TimeZone
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The SDK's public surface.
 *
 * Obtained with [Tracker.getInstance]:
 *
 * ```kotlin
 * val trackIt = Tracker.getInstance(context)
 * ```
 *
 * One instance per process, wired by hand in `di/TrackerGraph.kt`. **The SDK carries no
 * DI framework and requires none from the host** — no Hilt plugin, no `@HiltAndroidApp`,
 * no KSP. An earlier revision shipped Hilt inside `fieldtrack-core` and pushed that
 * requirement onto every consumer; it was removed because an SDK whose install story
 * starts with "first, adopt a DI framework" is not installable by a host that cannot
 * annotate its own `Application` class (CROSS-PLATFORM.md B-1).
 *
 * A host that uses a DI framework is of course still free to bind this object into its
 * own graph: `@Provides fun trackIt(app: Application) = Tracker.getInstance(app)`.
 *
 * Nothing here throws. Every fallible call returns [TrackerResult] with a typed
 * [ErrorCode], because an SDK that throws into a host's coroutine is a crash the host
 * cannot reasonably prevent (EC-01, EC-75).
 */
public class Tracker internal constructor(
    private val startTracking: StartTrackingUseCase,
    private val stopTracking: StopTrackingUseCase,
    private val resolveConfig: ResolveConfigUseCase,
    private val sessions: SessionRepository,
    private val points: TrackPointRepository,
    private val decisions: DecisionRepository,
    private val rawFixes: RawFixDao,
    private val rawPoints: RawPointDao,
    private val ingestor: FixIngestor,
    private val oneShotProvider: OneShotProvider,
    private val liveTrackFeed: LiveTrackFeed,
    private val captureGate: CaptureGate,
    private val clock: Clock,
    private val providerStateMonitor: ProviderStateMonitor,
    private val batteryMonitor: BatteryMonitor,
    private val sensorProbe: SensorProbe,
    private val integrityMonitor: IntegrityMonitor,
    private val stationaryFence: StationaryFence,
    private val permissions: PermissionManager,
    private val licenseGate: LicenseGate,
    private val getCachedLicenseAction: GetCachedLicenseActionUseCase,
    private val getLicenseInfo: GetLicenseInfoUseCase,
    private val checkLicenseRevocation: CheckLicenseRevocationUseCase,
    private val context: Context,
    private val scope: CoroutineScope,
    private val eventSink: MutableSharedFlow<TrackerEvent>,
) {

    @Volatile
    private var sensors: DeviceSensors? = null

    private val _state = MutableStateFlow(TrackerState())
    public val state: StateFlow<TrackerState> = _state.asStateFlow()

    /**
     * Replay 0, unlimited subscribers. Collect from a lifecycle scope for UI, or from an
     * application-scoped one for work that must continue with no UI on screen (EC-114).
     */
    public val events: SharedFlow<TrackerEvent> = eventSink.asSharedFlow()

    @Volatile
    private var config: TrackerConfig? = null

    /** Keeps concurrent [checkLicense] calls from each making their own request. */
    private val licenseCheckLock = Mutex()

    @Volatile
    private var stateSyncJob: Job? = null

    @Volatile
    private var roadSnapProvider: RoadSnapProvider = RoadSnapProvider.Disabled

    /** Returns a registered fence by id, or null when it is not registered. */
    public fun getGeofence(id: String = TrackerGeofence.DEFAULT_ID): TrackerGeofence? =
        stationaryFence.get(id)

    /** Snapshot of every registered fence. Android and iOS both cap this at 19. */
    public fun getGeofences(): List<TrackerGeofence> = stationaryFence.all()

    /**
     * Registers or replaces a persistent system geofence.
     */
    public suspend fun addGeofence(geofence: TrackerGeofence): TrackerResult<TrackerGeofence> {
        if (geofence.id.isBlank() || geofence.latitude !in -90.0..90.0 ||
            geofence.longitude !in -180.0..180.0 || geofence.radiusM <= 0f
        ) {
            return TrackerResult.Error(ErrorCode.INVALID_CONFIG, "Invalid geofence")
        }
        return when (stationaryFence.add(geofence)) {
            StationaryFence.AddResult.ADDED -> TrackerResult.Ok(geofence)
            StationaryFence.AddResult.LIMIT_REACHED -> TrackerResult.Error(
                ErrorCode.GEOFENCE_LIMIT_REACHED,
                "A maximum of ${TrackerGeofence.MAX_GEOFENCES} geofences may be registered",
            )
            StationaryFence.AddResult.FAILED -> TrackerResult.Error(
                ErrorCode.GEOFENCE_REGISTRATION_FAILED,
                "Geofence registration failed",
            )
        }
    }

    /** Removes one registered geofence. `Ok(false)` means no matching id existed. */
    public suspend fun removeGeofence(id: String = TrackerGeofence.DEFAULT_ID): TrackerResult<Boolean> {
        val removed = stationaryFence.remove(id)
        return if (removed != null) TrackerResult.Ok(removed) else {
            TrackerResult.Error(
                ErrorCode.GEOFENCE_REMOVAL_FAILED,
                "Geofence removal failed",
            )
        }
    }

    /** Removes all SDK-managed geofences. */
    public suspend fun removeAllGeofences(): TrackerResult<Int> {
        val removedCount = stationaryFence.removeAll()
        return if (removedCount != null) {
            TrackerResult.Ok(removedCount)
        } else {
            TrackerResult.Error(ErrorCode.GEOFENCE_REMOVAL_FAILED, "Geofence removal failed")
        }
    }

    /** Newest-first persisted enter/exit history, optionally filtered and paged. */
    @JvmOverloads
    public fun getGeofenceEvents(
        geofenceId: String? = null,
        fromMs: Long? = null,
        toMs: Long? = null,
        limit: Int = 500,
        offset: Int = 0,
    ): List<TrackerGeofenceEvent> =
        stationaryFence.store.events(geofenceId, fromMs, toMs, limit, offset)

    /** Deletes matching crossing history and returns the number of deleted records. */
    @JvmOverloads
    public fun deleteGeofenceEvents(
        geofenceId: String? = null,
        fromMs: Long? = null,
        toMs: Long? = null,
    ): Int = stationaryFence.store.deleteEvents(geofenceId, fromMs, toMs)

    /**
     * Resolves configuration, restores persisted filter state, and reports an
     * interrupted session if one was left open by a crash or force-stop (EC-66).
     */
    public suspend fun ready(config: TrackerConfig = TrackerConfig()): TrackerResult<TrackerState> {
        revocationGate(config)?.let { return it }

        // Evaluated on the resolved-by-the-host config rather than the persisted one: the
        // integrity policy is the host's standing decision about its own release build, so
        // it must apply on the very first launch, before any config has been stored.
        integrityGate(config.security)?.let { return it }
        // Said out loud rather than left silent: "no findings" and "nothing was looked at"
        // are different states, and a developer reading a clean report in a debug build
        // should not conclude the layer is working.
        if (integrityMonitor.current.waived) {
            eventSink.tryEmit(
                TrackerEvent.Diagnostic(
                    "device integrity waived — debuggable build or security.enabled = false",
                ),
            )
        }

        val resolved = resolveConfig(config)
        if (resolved.validationErrors.isNotEmpty()) {
            val message = resolved.validationErrors.joinToString("; ")
            eventSink.tryEmit(TrackerEvent.Error(ErrorCode.INVALID_CONFIG, message))
            return TrackerResult.Error(ErrorCode.INVALID_CONFIG, message)
        }

        this.config = resolved.config
        this.sensors = resolved.sensors

        // Every app open gets a licence check — after `config` is set, because that is
        // where the token comes from, and off the return path, because it must not delay
        // one. See [launchStartupLicenseCheck].
        launchStartupLicenseCheck()

        if (stateSyncJob == null) {
            stateSyncJob = scope.launch {
                eventSink.collect { event ->
                    when (event) {
                        is TrackerEvent.ProviderChange ->
                            _state.update { it.copy(providerState = event.state) }

                        // A suspension is not a stop: `isTracking` and the session id are
                        // left alone on purpose, because the session is still open and the
                        // host has not been asked to end it. Only `isCapturing` moves.
                        is TrackerEvent.CaptureSuspended ->
                            _state.update { it.copy(isCapturing = false) }

                        TrackerEvent.CaptureResumed ->
                            _state.update { it.copy(isCapturing = true) }

                        is TrackerEvent.MotionChange ->
                            _state.update { it.copy(motionState = event.state) }

                        // The SDK can end a session without the host calling stop() — the
                        // health loop does exactly that on a BLOCK-policy integrity finding.
                        // Without this, `state.isTracking` would stay true for a session
                        // that no longer exists.
                        is TrackerEvent.EnabledChange ->
                            if (!event.enabled) {
                                _state.update {
                                    it.copy(
                                        isTracking = false,
                                        isCapturing = false,
                                        currentSessionId = null,
                                    )
                                }
                            }

                        else -> Unit
                    }
                }
            }
        }

        providerStateMonitor.start()
        // Four broadcasts a day, and it makes batteryState() live from ready() onward
        // rather than only while a session is open.
        batteryMonitor.start()

        // TTL enforcement is independent of a session; enqueue once and let it run daily.
        PruneWorker.enqueue(context)

        sessions.current()?.let { open ->
            eventSink.tryEmit(TrackerEvent.SessionInterrupted(open))
        }

        _state.update {
            it.copy(
                isReady = true,
                currentSessionId = sessions.current()?.id,
                providerState = providerStateMonitor.state.value,
                // Both read off the RESOLVED config, not the supplied one. That is the
                // point of publishing them: `resolveConfig` may have overridden the mode
                // on poor motion hardware, and the `MOTION_DETECTION_DEGRADED` event that
                // says so is emitted above, to a `replay = 0` flow, before most hosts have
                // a collector. See `TrackerState.motionQuality`.
                // `sensors` is null only on the validation-failure path, which returned
                // above — so this elvis is unreachable here. Kept rather than asserted
                // because the honest fallback is "what we already knew", and publishing a
                // default FULL would state a hardware verdict nothing produced.
                motionQuality = resolved.sensors?.motionQuality ?: it.motionQuality,
                effectiveTrackingMode = resolved.config.geolocation.trackingMode,
            )
        }
        return TrackerResult.Ok(_state.value)
    }

    /**
     * Runs the integrity layer and turns a `BLOCK` verdict into the error both `ready()`
     * and `start()` return. `null` means "carry on".
     *
     * Deliberately not a `fun isTampered(): Boolean` used from two call sites: each entry
     * point evaluates for itself, so patching one method out does not silently open both
     * doors. The report is published either way — a `WARN` finding still reaches the host
     * and still rides along on every point.
     */
    /**
     * The **gate** in `ready()` — cache and the in-process latch only. **No network
     * happens on this path, and nothing here is ever awaited on a network call.**
     *
     * A fresh check does fire on every `ready()`, but not from here: it is launched
     * unawaited by [launchStartupLicenseCheck] once config is resolved. The separation is
     * the whole design. Whether the host is allowed to proceed is decided from an answer
     * already on disk, so a licence server outage cannot delay a launch or refuse one; the
     * new answer arrives moments later and acts on its own. A host that has never been
     * online since being revoked therefore keeps working, which is the correct trade —
     * treating an unreachable server as evidence would hand anyone with a firewall rule
     * the ability to stop a paying customer.
     *
     * Staleness is ignored on purpose — see [GetCachedLicenseActionUseCase].
     *
     * The worker is enqueued after the check rather than before it, so a build with no
     * licence configured never schedules anything. It remains the backstop for an app
     * left open for days; the startup check is what makes the *first* one prompt.
     */
    private suspend fun revocationGate(config: TrackerConfig): TrackerResult.Error? {
        val token = licenseGate.token(config.license) ?: return null

        val action = LicenseState.current ?: getCachedLicenseAction(token)
        LicenseCheckWorker.enqueue(context)

        if (action !is LicenseAction.Stop) return null

        val message = action.reason ?: "Tracker license is no longer valid"
        eventSink.tryEmit(TrackerEvent.Error(action.code, message))
        return TrackerResult.Error(action.code, message)
    }

    private fun integrityGate(security: SecurityConfig): TrackerResult.Error? {
        val report = integrityMonitor.evaluate(security)
        if (!report.blocked) return null

        val message = "Device integrity check failed: ${report.describeBlocking()}"
        eventSink.tryEmit(TrackerEvent.Error(ErrorCode.DEVICE_INTEGRITY_BLOCKED, message))
        return TrackerResult.Error(ErrorCode.DEVICE_INTEGRITY_BLOCKED, message)
    }

    /**
     * The last device-integrity evaluation. No probing — this is a field read.
     *
     * `IntegrityReport.waived` is `true` in a debuggable build, where nothing is probed
     * and nothing blocks.
     */
    public fun integrity(): IntegrityReport = integrityMonitor.current

    /**
     * Probes now and returns the fresh report, publishing it to [integrityState] and to
     * [TrackerEvent.IntegrityChange] if anything moved.
     *
     * Reads `/proc`, the installed-package list and a loopback socket — tens of
     * milliseconds. Call it on a state change worth re-checking (an app install, a return
     * from the background), not on a timer: the SDK already re-checks inside the health
     * loop at `SecurityConfig.recheckIntervalMs`.
     */
    public suspend fun checkIntegrity(): IntegrityReport = withContext(Dispatchers.IO) {
        integrityMonitor.evaluate(config?.security ?: SecurityConfig())
    }

    /**
     * What the licence server last said about this app's licence, from the on-device
     * cache. **Never touches the network**, so it is free to call from a UI.
     *
     * Null when no verified answer has ever been stored — a build with no licence
     * configured, a first run that has not reached its first check, or a cached entry
     * that failed re-verification. Null is not "invalid": nothing that fails here stops
     * tracking, and a host should treat it as "not known yet" rather than as a refusal.
     *
     * [LicenseInfo.fromCache] is always `true` here. Use [checkLicense] to force a call.
     */
    public suspend fun licenseInfo(): LicenseInfo? {
        val token = licenseGate.token(config?.license) ?: return null
        return getLicenseInfo(token)
    }

    /**
     * The startup check: fired on every [ready], never awaited by it.
     *
     * `ready()` still returns from cache alone, so nothing here can add a millisecond to
     * a host's launch or refuse one because a licence server is having a bad day. The
     * answer lands moments later on its own: through [TrackerEvent.LicenseChecked], the
     * revocation latch, and — for a `REVOKED` or `EXPIRED` verdict — a stop.
     *
     * **This is what closes the first-run gap.** `LicenseCheckWorker` is periodic on a
     * 12-hour interval with the default flex, so its first tick can land anywhere in the
     * first 12 hours after install; before this, a fresh install could run most of a day
     * against a licence nobody had verified. The worker remains what keeps a
     * long-running install honest — this is what makes the first check prompt.
     *
     * Cheap to do on every open, because the cache short-circuits it: an app opened forty
     * times a day still makes one request per TTL, not forty. [licenseCheckLock] serialises
     * the calls so that two opens in quick succession — or a host calling [checkLicense]
     * itself at the same moment — cannot both slip past a cold cache and go out twice.
     *
     * Wrapped in `runCatching` for a reason worth stating: this runs unobserved on the SDK
     * scope, so anything thrown here has no caller to catch it and would surface as a
     * crash in the host's app. The layer underneath already fails open; this is the belt
     * to that pair of braces.
     */
    private fun launchStartupLicenseCheck() {
        scope.launch { runCatching { checkLicense() } }
    }

    /**
     * Serialised, not deduplicated: a second caller through still gets an answer, it just
     * gets it from the cache the first one filled rather than from a second request.
     */
    private suspend fun runCheck(token: String, forceRefresh: Boolean): LicenseInfo? =
        licenseCheckLock.withLock { check(token, forceRefresh) }

    /**
     * Checks with the licence server now and returns what it said, publishing the result
     * to [TrackerEvent.LicenseChecked].
     *
     * Returns null when nothing was learned: no network, no licence configured, or a
     * response that failed verification. **Null never means the licence is bad** — every
     * one of those paths carries on tracking, deliberately, because the device owner can
     * produce all three on demand and must not be able to disable the SDK that way.
     *
     * Forces a real request rather than returning a cached verdict — an explicit "check
     * now" from a host should mean now. Still bounded: a five-minute floor sits under it,
     * so calling this on every screen produces at most one request per five minutes rather
     * than one per screen.
     *
     * The SDK already calls this itself on every [ready] and every 12 hours after that, so
     * a host does not need to schedule anything. This is for a host that wants an answer
     * at a particular moment — a "check licence" button, or a retry after showing the user
     * a licence error. Use [licenseInfo] for a cheap read with no network at all.
     *
     * A `REVOKED` or `EXPIRED` answer stops tracking as a side effect, and arrives as an
     * [TrackerEvent.Error] as well as here.
     */
    public suspend fun checkLicense(): LicenseInfo? = withContext(Dispatchers.IO) {
        val token = licenseGate.token(config?.license) ?: return@withContext null

        runCheck(token, forceRefresh = true)
    }

    private suspend fun check(token: String, forceRefresh: Boolean): LicenseInfo? {
        val result = checkLicenseRevocation(token, forceRefresh)
        val (action, info) = result

        // A failed check is not a verdict, so it never becomes a LicenseChecked. It is
        // still worth saying out loud: without this the host cannot distinguish "the
        // licence server is unreachable" from "nothing has run yet", and both look like
        // silence. Diagnostic is the channel that means "information, not a decision".
        result.error?.let { error ->
            eventSink.tryEmit(TrackerEvent.Diagnostic("licence check failed: ${error.describe()}"))
        }

        LicenseState.apply(action)

        info?.let { eventSink.tryEmit(TrackerEvent.LicenseChecked(it)) }

        if (action is LicenseAction.Stop) {
            val message = action.reason ?: "Tracker license is no longer valid"
            eventSink.tryEmit(TrackerEvent.Error(action.code, message))
            // A no-op when nothing is running, which is the usual case on the startup
            // path — `StopTrackingUseCase` returns immediately with no open session.
            stopTracking()
        }

        return info
    }

    /** Live device-integrity state. Emits on a change of the flag set, not on every check. */
    public fun integrityState(): StateFlow<IntegrityReport> = integrityMonitor.state

    /**
     * Live provider and permission state: GPS toggle, permission tier, granularity,
     * fused availability, battery saver. Updated by broadcast and by `AppOpsManager`,
     * never polled (EC-06, EC-16, EC-21).
     */
    public fun providerState(): StateFlow<ProviderState> = providerStateMonitor.state

    /**
     * Charge level and power source, read from the platform now.
     *
     * Needs no session, no permission and no [ready] call — safe from anywhere, including
     * before tracking has ever started. It is a binder call, so it belongs in a refresh, not
     * in a per-frame render; collect [batteryState] for a live display instead.
     *
     * Returns [BatteryInfo.Unknown] rather than throwing or guessing when the platform will
     * not answer. A null percentage is "we do not know", never 0 %.
     *
     * This is the same reading stamped on every stored point, so a host's display and its
     * uploaded rows cannot disagree.
     */
    public fun batteryInfo(): BatteryInfo = batteryMonitor.refresh()

    /**
     * Live battery state, updated on plug, unplug, low and okay — and, while a session is
     * running, on the capture path's own refresh.
     *
     * Starts at [BatteryInfo.Unknown] until something reads; call [batteryInfo] once if you
     * need a value immediately. [TrackerEvent.BatteryChange] carries the same transitions
     * for hosts collecting the event flow.
     */
    public fun batteryState(): StateFlow<BatteryInfo> = batteryMonitor.state

    /** What motion hardware exists, and the [MotionQuality] the SDK derived from it. */
    public fun getSensors(): DeviceSensors = sensorProbe.probe()

    /** Current permission tier — `NONE`, `FOREGROUND_ONLY` or `FULL`. */
    public fun permissionTier(): PermissionTier = permissions.tier()

    /**
     * The permission ladder, as data. The host owns every prompt; the SDK shows no UI
     * (PERMISSIONS.md §5).
     */
    public fun permissions(): PermissionManager = permissions

    public suspend fun start(tag: String? = null): TrackerResult<TrackSession> {
        val active = config
            ?: return TrackerResult.Error(ErrorCode.NOT_READY, "Call ready() before start()")

        // Re-evaluated rather than reused from ready(): a device can be tampered with in
        // the seconds between the two calls, and start() is the one that opens a session
        // whose points a payroll run will later trust.
        integrityGate(active.security)?.let { return it }
        integrityMonitor.onSessionStart()

        return when (val result = startTracking(active, tag)) {
            is TrackerResult.Ok -> {
                _state.update {
                    it.copy(
                        isTracking = true,
                        // Read from the gate rather than assumed true: `startTracking`
                        // arms it before returning, so a session opened while location was
                        // switched off is already reported as suspended here instead of
                        // claiming a capture that is not running.
                        isCapturing = captureGate.isCapturing,
                        currentSessionId = result.value.id,
                        providerState = providerStateMonitor.state.value,
                    )
                }
                result
            }
            is TrackerResult.Error -> result
        }
    }

    public suspend fun stop(): TrackerResult<TrackSession?> {
        val result = stopTracking()
        _state.update { it.copy(isTracking = false, isCapturing = false, currentSessionId = null) }
        return result
    }

    /**
     * Re-reads the location subsystem now and publishes the result.
     *
     * [providerState] is broadcast-driven and normally needs no prompting, but a
     * context-registered receiver dies with the process, so a `PROVIDERS_CHANGED` or an
     * app-op change that lands while the app is not running is never delivered. Call this
     * from `onResume` if a screen's decisions depend on the GPS switch — the SDK already
     * does it on every [start] and [getCurrentLocation].
     *
     * Cheap and safe from anywhere; a real change emits [TrackerEvent.ProviderChange] and,
     * where one applies, [TrackerEvent.PermissionChange] or
     * [TrackerEvent.LocationServicesChange].
     */
    public fun refreshProviderState(): ProviderState {
        providerStateMonitor.refresh()
        return providerStateMonitor.state.value
    }

    /**
     * Requests one fresh location using the configuration supplied to [ready].
     *
     * The returned [TrackFix] is a snapshot only: it is not accepted, persisted, added
     * to odometer distance, or emitted as a tracking location. Call [start] when the
     * location should become part of a session.
     */
    public suspend fun getCurrentLocation(): TrackerResult<TrackFix> {
        val active = config
            ?: return TrackerResult.Error(ErrorCode.NOT_READY, "Call ready() before getCurrentLocation()")
        if (permissions.tier() == PermissionTier.NONE) {
            return TrackerResult.Error(
                ErrorCode.PERMISSION_DENIED,
                "Location permission not granted",
            )
        }

        providerStateMonitor.refresh()
        val provider = providerStateMonitor.state.value
        if (!provider.gpsEnabled && !provider.networkEnabled) {
            return TrackerResult.Error(ErrorCode.LOCATION_DISABLED, "Location services are disabled")
        }

        val fix = oneShotProvider.capture(
            active,
            feedIngestor = false,
            suppressAfterRepeatedFailures = false,
        )
            ?: return TrackerResult.Error(
                ErrorCode.FIX_TIMEOUT,
                "No usable location fix arrived within ${active.geolocation.oneShotTimeoutMs} ms",
            )
        return TrackerResult.Ok(fix)
    }

    public suspend fun getPoints(query: PointQuery = PointQuery()): List<TrackPoint> =
        points.query(query)

    public fun observePoints(sessionId: String): Flow<List<TrackPoint>> = points.observe(sessionId)

    /**
     * The live rendering surface (SMOOTH-NAV-PLAN Phase 2): one frame per processed
     * fix while a session is active — an append-only smoothed tail, the re-smoothed
     * last span, and the filter's own position estimate for an animated puck.
     *
     * Conflated: collectors always see the latest frame and can never slow capture
     * down. Use this for a map that follows the user; use [buildTrack] for the
     * consolidated, snapped, segmented historical product. Check
     * [LiveTrackUpdate.sequence] before drawing — flows across dispatchers can
     * deliver a stale frame after a newer one.
     */
    public fun liveTrack(): Flow<LiveTrackUpdate> = liveTrackFeed.updates

    /**
     * Snap the live puck to a route this app is navigating (SMOOTH-NAV-PLAN Phase 5).
     *
     * The single cheapest trick in navigation rendering, and most of why a well-known
     * blue dot never wobbles off the road during turn-by-turn: it is not being matched
     * against the road network, it is being projected onto the one polyline the app is
     * already following. Entirely offline — no provider, no key, no quota.
     *
     * Only [liveTrack]'s puck moves. Stored points and [buildTrack] are untouched: the
     * route is the host's claim about where the user intends to go, and writing it into
     * the record would fabricate evidence for a road nobody was measured on.
     *
     * Pass an empty list to clear it. Check [isOffRoute] to decide about rerouting.
     */
    public fun setActiveRoute(route: List<GeoPoint>) {
        liveTrackFeed.setActiveRoute(route)
    }

    /**
     * True once the position has missed the active route for enough consecutive fixes
     * to be a wrong turn rather than a multipath spike. Always false with no route set.
     */
    public fun isOffRoute(): Boolean = liveTrackFeed.isOffRoute

    public suspend fun getCount(query: PointQuery = PointQuery()): Int = points.count(query)

    public suspend fun getOdometerMeters(): Double = points.odometerMeters()

    public suspend fun getSessions(fromMs: Long? = null, toMs: Long? = null): List<TrackSession> =
        sessions.range(fromMs, toMs)

    public suspend fun currentSession(): TrackSession? = sessions.current()

    // ── plotting ─────────────────────────────────────────────────────────────

    /**
     * Optional road snapping. Defaults to [RoadSnapProvider.Disabled], under which
     * [buildTrack] never leaves the device and never emits a `snap_unavailable` warning.
     *
     * A settable property rather than a Hilt binding on purpose. A `@Binds` in core would
     * force every host that wants its own provider to fight a duplicate binding, and a
     * default binding a host *cannot* override is worse than no default at all. This also
     * keeps the HTTP client and the API key in the host's artifact, where they belong:
     * core carries neither (PLAN.md §5).
     *
     * ```kotlin
     * trackIt.setRoadSnapProvider(OsrmSnapProvider(baseUrl = "https://osrm.example.com"))
     * ```
     */
    public fun setRoadSnapProvider(provider: RoadSnapProvider) {
        roadSnapProvider = provider
    }

    /**
     * Builds a ready-to-draw track: consolidated stops, travel/dwell segments,
     * precomputed arrow anchors, an encoded polyline and per-session statistics.
     *
     * Runs entirely in `fieldtrack-geo` on-device unless a [RoadSnapProvider] has been
     * installed — no backend, no routing key, no quota by default.
     */
    public suspend fun buildTrack(
        query: PointQuery = PointQuery(),
        options: TrackOptions = TrackOptions(),
    ): Track {
        val stored = points.query(query)
        return TrackBuilder.build(
            points = stored,
            options = options,
            sessionId = query.sessionId,
            nowMs = clock.wallTimeMs(),
            timezone = TimeZone.getDefault().id,
            roadGeometry = fetchRoadGeometry(stored, options),
        )
    }

    /**
     * @return road geometry for [stored], or a value saying why there is none.
     *
     * **Every failure degrades to [Snapper.RoadGeometry.Unavailable]; none propagates.**
     * Losing a whole day's track because a routing service was rate-limited, offline or
     * slow is not a trade any host would choose, and `buildTrack` is on the path that
     * draws the map (EC-100). The provider contract already says implementations must
     * degrade rather than fail — the `catch` is here because "must" is not "will", and a
     * third-party provider throwing into a host's coroutine is a crash the host cannot
     * reasonably prevent (EC-75).
     */
    private suspend fun fetchRoadGeometry(
        stored: List<TrackPoint>,
        options: TrackOptions,
    ): Snapper.RoadGeometry {
        val provider = roadSnapProvider
        if (!options.snapToRoad || provider === RoadSnapProvider.Disabled) {
            return Snapper.RoadGeometry.None
        }
        if (stored.size < Snapper.MIN_ROAD_POINTS) return Snapper.RoadGeometry.None

        // The richer request (SMOOTH-NAV-PLAN Phase 5). A matcher decides between
        // candidate roads on how far a fix could plausibly have travelled and how much
        // it is worth trusting; both facts are already sitting on the stored points, and
        // sending only coordinates threw them away. Providers written against the
        // original contract ignore it by default.
        val request = SnapRequest(
            stored.map {
                SnapFix(
                    point = GeoPoint(it.latitude, it.longitude),
                    timeMs = it.timeMs,
                    accuracyM = it.accuracy,
                )
            },
        )
        return runCatching { provider.snap(request) }
            .map { Snapper.RoadGeometry.Snapped(it) }
            .getOrElse { failure ->
                eventSink.tryEmit(
                    TrackerEvent.Error(
                        ErrorCode.SNAP_UNAVAILABLE,
                        "Road snapping failed: ${failure.message ?: failure::class.simpleName}",
                    ),
                )
                Snapper.RoadGeometry.Unavailable
            }
    }

    /** The wire format in POLYLINE-JSON.md §1. */
    public suspend fun exportPolylineJson(
        query: PointQuery = PointQuery(),
        options: TrackOptions = TrackOptions(),
    ): String = TrackJson.encode(buildTrack(query, options))

    /** GeoJSON `FeatureCollection`; coordinates are `[lng, lat]` per RFC 7946. */
    public suspend fun exportGeoJson(
        query: PointQuery = PointQuery(),
        options: TrackOptions = TrackOptions(),
    ): String = GeoJson.encode(buildTrack(query, options))

    // ── diagnostics ──────────────────────────────────────────────────────────

    /**
     * Layer 1 of the three-layer debug overlay: the unfiltered fixes as the OS delivered
     * them. Only populated when `persistence.persistRawFixes` is on (spec §8.4).
     */
    public suspend fun getRawFixes(sessionId: String): List<RawFix> =
        // Delivery order out of the DAO, fix order out of here. Unlike stored points,
        // this layer keeps the stragglers `ClockGuard` would have dropped, so drawing it
        // in the order it arrived braids the line back on itself (EC-88b).
        ClockGuard.inFixOrder(rawFixes.bySession(sessionId)) { it.elapsedRealtimeNanos }
            .map {
                RawFix(
                    timeMs = it.timeMs,
                    latitude = it.latitude,
                    longitude = it.longitude,
                    accuracy = it.accuracy,
                    bearingDeg = it.bearingDeg,
                    provider = it.provider,
                    integrityFlags = it.integrityFlags,
                )
            }

    /**
     * Every judged fix in point form, accepted or not. Only populated when
     * `persistence.persistRawPoints` is on.
     *
     * The layer to reach for when the question is "why is there no point here" rather
     * than "why is this point wrong": the discarded candidates come back in the same
     * shape as [getPoints], so the two can be read side by side. [RawPoint.uuid] joins
     * back to the stored `TrackPoint` for the ones that were accepted.
     *
     * Delivery order out of the DAO, fix order out of here — same reasoning as
     * [getRawFixes]: this layer keeps the stragglers, so a reboot boundary has to be
     * resolved in Kotlin rather than by a SQL sort (EC-88b).
     */
    public suspend fun getRawPoints(sessionId: String): List<RawPoint> =
        ClockGuard.inFixOrder(rawPoints.bySession(sessionId)) { it.elapsedRealtimeNanos }
            .map { it.toDomain() }

    /** The decision log: why every fix was accepted, skipped or rejected. */
    public suspend fun getDecisions(
        sessionId: String? = null,
        limit: Int = DEFAULT_DECISION_LIMIT,
        offset: Int = 0,
    ): List<FixDecision> = decisions.query(sessionId, limit, offset)

    /**
     * Feed a fix in from a source the SDK does not own (a test, a replay, a custom
     * provider). It is judged by exactly the same gates as a live fix — a host cannot
     * inject an unvalidated point (EC-86).
     */
    public fun offerFix(fix: com.field360.traker.geo.model.TrackFix) {
        ingestor.offer(fix)
    }

    public companion object {
        private const val DEFAULT_DECISION_LIMIT = 200

        /**
         * The SDK, for this process.
         *
         * Idempotent and thread-safe: every call returns the same object, whatever
         * [context] is passed. The application context is what gets retained, so passing
         * an Activity is safe and leaks nothing.
         *
         * Cheap — the object graph is lazy, so this opens no database and touches no
         * disk. The first real work happens in [ready].
         */
        @JvmStatic
        public fun getInstance(context: Context): Tracker = TrackerGraph.get(context).trackIt
    }

}

/**
 * What a fix became, whatever the pipeline decided about it.
 *
 * Deliberately the same columns as `TrackPoint` plus [verdict] and [reason], so a
 * rejected candidate and the points either side of it can be compared without reshaping
 * anything. The numeric argument for the verdict — sigma, threshold, distance moved —
 * lives on `FixDecision`; this is the geometry and the enrichment.
 */
public data class RawPoint(
    val uuid: String,
    val sessionId: String,
    val timeMs: Long,
    val elapsedRealtimeNanos: Long,
    val localDate: String,
    val timezone: String,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val altitude: Double?,
    val speedMps: Float,
    val bearingDeg: Float,
    val hasSpeed: Boolean,
    val hasBearing: Boolean,
    val provider: String,
    val isMock: Boolean,
    val movementStatus: MovementStatus,
    val detectedActivity: ActivityType?,
    val activityStartTimeMs: Long,
    val odometerMeters: Double,
    val batteryPct: Int?,
    val isCharging: Boolean?,
    val extras: String?,
    /** Device-integrity bitmask when this fix was judged — see `IntegrityReport.flags`. */
    val integrityFlags: Int,
    /**
     * Location-subsystem snapshot when this fix was judged — see
     * `ProviderSnapshot.fromFlags`. `0` means no snapshot was recorded.
     */
    val providerFlags: Int = 0,
    /** `ACCEPT`, `SKIP` or `REJECT`. */
    val verdict: String,
    /** The `Reasons` vocabulary — the same strings the decision log uses. */
    val reason: String,
) {
    val isAccepted: Boolean get() = verdict == "ACCEPT"
}

/** A fix exactly as the OS delivered it, before any gate ran. */
public data class RawFix(
    val timeMs: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    /** 0f when the provider reported no bearing — check `hasBearing` on the source fix. */
    val bearingDeg: Float,
    val provider: String,
    /** Device-integrity bitmask when this fix was received — see `IntegrityReport.flags`. */
    val integrityFlags: Int = 0,
)
