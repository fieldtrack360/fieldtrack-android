package com.field360.tracker.di

import android.annotation.SuppressLint
import androidx.annotation.VisibleForTesting
import android.content.Context
import com.field360.tracker.Tracker
import com.field360.tracker.TrackerConfig
import com.field360.tracker.capture.CaptureGate
import com.field360.tracker.capture.FixIngestor
import com.field360.tracker.capture.LiveTrackFeed
import com.field360.tracker.capture.LocationStreamController
import com.field360.tracker.capture.OneShotProvider
import com.field360.tracker.data.db.ActivitySegmentDao
import com.field360.tracker.data.db.FilterStateDao
import com.field360.tracker.data.db.FixDecisionDao
import com.field360.tracker.data.db.RawFixDao
import com.field360.tracker.data.db.RawPointDao
import com.field360.tracker.data.db.TrackerDatabase
import com.field360.tracker.data.db.TrackPointDao
import com.field360.tracker.data.db.TrackSessionDao
import com.field360.tracker.data.location.AccuracyTuning
import com.field360.tracker.data.location.FixMapper
import com.field360.tracker.data.location.FusedLocationSource
import com.field360.tracker.data.location.LocationSource
import com.field360.tracker.data.location.PlatformLocationSource
import com.field360.tracker.data.location.RoutingLocationSource
import com.field360.tracker.data.platform.AndroidBatteryProbe
import com.field360.tracker.data.platform.AndroidClock
import com.field360.tracker.data.platform.BatteryMonitor
import com.field360.tracker.data.platform.AndroidLogger
import com.field360.tracker.data.repository.ConfigRepositoryImpl
import com.field360.tracker.data.repository.ConfigStore
import com.field360.tracker.data.repository.DecisionRepositoryImpl
import com.field360.tracker.data.repository.PendingUploadStoreImpl
import com.field360.tracker.data.repository.RoomPointStore
import com.field360.tracker.data.repository.SessionRepositoryImpl
import com.field360.tracker.data.repository.TrackPointRepositoryImpl
import com.field360.tracker.domain.model.TrackerEvent
import com.field360.tracker.domain.repository.ConfigRepository
import com.field360.tracker.domain.repository.DecisionRepository
import com.field360.tracker.domain.repository.PendingUploadStore
import com.field360.tracker.domain.repository.SessionRepository
import com.field360.tracker.domain.repository.TrackPointRepository
import com.field360.tracker.domain.usecase.ResolveConfigUseCase
import com.field360.tracker.domain.usecase.SessionTeardown
import com.field360.tracker.domain.usecase.StartTrackingUseCase
import com.field360.tracker.domain.usecase.StopTrackingUseCase
import com.field360.traker.geo.filter.AcceptancePipeline
import com.field360.traker.geo.filter.TrackerConstants
import com.field360.traker.geo.motion.MotionStateMachine
import com.field360.traker.geo.motion.TurnDetector
import com.field360.traker.geo.port.Clock
import com.field360.traker.geo.port.PointStore
import com.field360.traker.geo.port.TrackLogger
import com.field360.tracker.BuildConfig
import com.field360.tracker.data.remote.GsonVerdictAuthenticator
import com.field360.tracker.data.remote.RetrofitLicenseApi
import com.field360.tracker.data.repository.LicenseVerdictStoreImpl
import com.field360.tracker.domain.repository.LicenseApi
import com.field360.tracker.domain.repository.LicenseVerdictStore
import com.field360.tracker.domain.repository.VerdictAuthenticator
import com.field360.tracker.domain.usecase.CheckLicenseRevocationUseCase
import com.field360.tracker.domain.usecase.GetCachedLicenseActionUseCase
import com.field360.tracker.domain.usecase.GetLicenseInfoUseCase
import com.field360.tracker.license.LicenseConfig
import com.field360.tracker.license.LicenseGate
import com.field360.tracker.integrity.internal.IntegrityEnvironment
import com.field360.tracker.integrity.internal.IntegrityEvaluator
import com.field360.tracker.integrity.internal.IntegrityFeed
import com.field360.tracker.integrity.internal.IntegrityMonitor
import com.field360.tracker.integrity.probes.AccessibilityProbe
import com.field360.tracker.integrity.probes.ClockIntegrityProbe
import com.field360.tracker.integrity.probes.DeveloperModeProbe
import com.field360.tracker.integrity.probes.HookingProbe
import com.field360.tracker.integrity.probes.MockLocationProbe
import com.field360.tracker.motion.ActivityRecognizer
import com.field360.tracker.motion.CaptureStream
import com.field360.tracker.motion.GeofenceRegistrar
import com.field360.tracker.motion.GyroTurnMonitor
import com.field360.tracker.motion.GyroscopeYawSource
import com.field360.tracker.motion.MotionController
import com.field360.tracker.motion.MotionWakeSource
import com.field360.tracker.motion.SensorProbe
import com.field360.tracker.motion.SignificantMotionWake
import com.field360.tracker.motion.StationaryFence
import com.field360.tracker.motion.StepCorroborator
import com.field360.tracker.motion.YawRateSource
import com.field360.tracker.permission.PermissionManager
import com.field360.tracker.permission.ProviderStateMonitor
import com.field360.tracker.service.HealthLoop
import com.field360.tracker.work.DaoUploadQueueStats
import com.field360.tracker.work.UploadQueueStats
import com.field360.tracker.work.SyncScheduler
import com.field360.tracker.work.Watchdog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * The SDK's object graph, wired by hand.
 *
 * This replaces Hilt, and the reversal is deliberate. Hilt inside `fieldtrack-core` forced
 * every consuming app to apply the Hilt Gradle plugin and annotate its `Application`
 * with `@HiltAndroidApp` — an integration tax on every host, and a hard blocker for any
 * host whose `Application` class is not its own to annotate (a React Native template's
 * `MainApplication`, a Unity or Flutter shell, a modular app whose `Application` lives in
 * another team's module). An SDK should absorb its own wiring; see CROSS-PLATFORM.md B-1.
 *
 * What is lost is compile-time graph verification. What replaces it is that the whole
 * graph is 60 readable lines in one file — a missing edge is a Kotlin compile error here
 * rather than a KSP error somewhere else, and a cycle is a `StackOverflowError` on first
 * touch rather than a build failure. Both are caught by simply constructing the graph,
 * which [TrackerGraphTest] does.
 *
 * Every member is `by lazy`, so nothing is built until something asks for it: touching
 * [permissions] does not open the database, and `Tracker.getInstance()` does not do disk
 * I/O on the caller's thread.
 *
 * Scoping matches the Hilt graph it replaces exactly: everything here was `@Singleton`,
 * and the DAO providers were unscoped only because they delegate to a `@Singleton`
 * database. One process, one graph, one [FixIngestor] — two would mean two filter states
 * writing one table.
 */
internal class TrackerGraph private constructor(
    @JvmField val context: Context,
    /**
     * Test-only replacements for the two licence pieces that cannot be reached any other
     * way: the transport, so a test can count calls without a socket, and the trust
     * anchor, which is otherwise a compile-time constant.
     *
     * Null in every shipped path — [installForTest] is the only caller that passes
     * anything, `TrackerGraph` is `internal`, and neither is reachable from a host. This
     * is not a runtime-configurable trust anchor: making the response key settable from
     * outside the module would let anyone who could call it sign their own verdicts, which
     * is exactly what compiling the key in prevents.
     */
    private val licenseApiOverride: LicenseApi? = null,
    private val responseKeyOverride: ByteArray? = null,
) {

    // ── ports and primitives ────────────────────────────────────────────────

    /**
     * Replay 0, unlimited subscribers, and a buffer so a slow collector cannot stall the
     * ingestor. Never a `var callback` — the second registrant would silently replace
     * the first, and the host UI plus a background collector is the normal case (EC-112).
     */
    val events: MutableSharedFlow<TrackerEvent> by lazy {
        MutableSharedFlow(
            replay = 0,
            extraBufferCapacity = EVENT_BUFFER,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    }

    /** The SDK's own application-scoped coroutine scope; outlives any Activity. */
    val scope: CoroutineScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Default) }

    val clock: Clock by lazy { AndroidClock() }

    /**
     * Battery state: cached for the ingest path, broadcast-driven for the transitions.
     * Also what [Tracker.batteryInfo] and [Tracker.batteryState] read from, so a host and a
     * stored point can never disagree about what the charge was.
     */
    val batteryMonitor: BatteryMonitor by lazy {
        BatteryMonitor(context, AndroidBatteryProbe(context), clock, events)
    }
    val logger: TrackLogger by lazy { AndroidLogger() }

    /**
     * Every decision constant lives in this one object, which is what makes PLAN.md §3
     * invariant 1 ("no algorithm above fieldtrack-geo") mechanically checkable.
     */
    val constants: TrackerConstants by lazy { TrackerConstants.Default }
    val pipeline: AcceptancePipeline by lazy { AcceptancePipeline(constants) }
    val motionStateMachine: MotionStateMachine by lazy { MotionStateMachine() }
    val turnDetector: TurnDetector by lazy { TurnDetector(constants) }

    // ── storage ─────────────────────────────────────────────────────────────

    val database: TrackerDatabase by lazy { TrackerDatabase.build(context) }

    val pointDao: TrackPointDao by lazy { database.points() }
    val sessionDao: TrackSessionDao by lazy { database.sessions() }
    val decisionDao: FixDecisionDao by lazy { database.decisions() }
    val filterStateDao: FilterStateDao by lazy { database.filterState() }
    val activitySegmentDao: ActivitySegmentDao by lazy { database.activity() }
    val rawFixDao: RawFixDao by lazy { database.rawFixes() }
    val rawPointDao: RawPointDao by lazy { database.rawPoints() }

    val configStore: ConfigStore by lazy { ConfigStore(context) }

    val pointStore: PointStore by lazy { roomPointStore }
    val roomPointStore: RoomPointStore by lazy {
        RoomPointStore(pointDao, filterStateDao, decisionDao, rawFixDao, rawPointDao, events)
    }

    // ── repositories: domain declares the interface, data supplies the impl ──

    val trackPoints: TrackPointRepository by lazy { TrackPointRepositoryImpl(pointDao) }
    val sessions: SessionRepository by lazy { SessionRepositoryImpl(sessionDao, clock) }
    val decisions: DecisionRepository by lazy { DecisionRepositoryImpl(decisionDao) }
    val config: ConfigRepository by lazy { ConfigRepositoryImpl(configStore) }

    /** The one public door fieldtrack-sync uploads through. */
    val pendingUploads: PendingUploadStore by lazy { PendingUploadStoreImpl(pointDao) }

    /**
     * How deep the upload queue is and when it last drained.
     *
     * Hoisted out of [syncScheduler] so the two readers share one instance rather than
     * each wrapping the DAO themselves. `TrackingService` is the second reader — it
     * renders these numbers into the ongoing notification when
     * `ServiceConfig.showSyncStatusInNotification` is on.
     *
     * Note this is a **core** type reading core's own table, not a call into
     * `fieldtrack-sync`: the queue is rows in `TrackPointDao`, so counting them needs no
     * dependency on the sync artifact and works identically when it is absent.
     */
    val uploadQueueStats: UploadQueueStats by lazy { DaoUploadQueueStats(pointDao) }

    /** The door in the other direction: core asking for a drain (G-4). */
    val syncScheduler: SyncScheduler by lazy {
        SyncScheduler(uploadQueueStats, clock, logger)
    }

    // ── platform seams ──────────────────────────────────────────────────────

    val fusedLocationSource: LocationSource by lazy { FusedLocationSource(context) }

    /**
     * What every capture path talks to. Routes to fused or to the platform
     * `LocationManager` per `GeolocationConfig.providerType`.
     */
    val locationSource: RoutingLocationSource by lazy {
        RoutingLocationSource(fusedLocationSource) { type -> PlatformLocationSource(context, type) }
    }

    val fixMapper: FixMapper by lazy { FixMapper(clock) }
    val permissions: PermissionManager by lazy { PermissionManager(context) }
    val sensorProbe: SensorProbe by lazy { SensorProbe(context, permissions) }

    /**
     * Deliberately the **fused** source, not the router. `ProviderState.fusedAvailable`
     * answers "is Play Services here", which is a fact about the device that a host uses to
     * decide whether to switch providers — routing it would make the field self-fulfilling
     * and report `true` for a host that had already given up on fused.
     */
    val providerStateMonitor: ProviderStateMonitor by lazy {
        ProviderStateMonitor(context, permissions, fusedLocationSource, events)
    }

    // ── motion seams — see MotionPorts.kt ───────────────────────────────────

    val significantMotion: SignificantMotionWake by lazy { SignificantMotionWake(context) }
    val motionWakeSource: MotionWakeSource by lazy { significantMotion }
    val stationaryFence: StationaryFence by lazy { StationaryFence(context, events, logger) }
    val geofenceRegistrar: GeofenceRegistrar by lazy { stationaryFence }
    val stepCorroborator: StepCorroborator by lazy { StepCorroborator(context) }
    val yawRateSource: YawRateSource by lazy { GyroscopeYawSource(context) }
    val gyroTurnMonitor: GyroTurnMonitor by lazy {
        GyroTurnMonitor(yawRateSource, clock, logger, constants)
    }
    val activityRecognizer: ActivityRecognizer by lazy {
        ActivityRecognizer(context, permissions, events, logger, scope)
    }

    // ── device integrity ────────────────────────────────────────────────────

    /**
     * Per-fix evidence for the integrity layer: mock fixes seen, GNSS-vs-system clock skew.
     * Written by the ingest path, read by the probes.
     */
    val integrityFeed: IntegrityFeed by lazy { IntegrityFeed(clock) }

    /**
     * The probe list, in signal order. Constructed eagerly inside the lazy so the platform
     * lookups happen once, not per evaluation.
     */
    val integrityEvaluator: IntegrityEvaluator by lazy {
        IntegrityEvaluator(
            probes = listOf(
                AccessibilityProbe(context),
                DeveloperModeProbe(context),
                HookingProbe(),
                ClockIntegrityProbe(context, integrityFeed),
                MockLocationProbe(context, integrityFeed),
            ),
            clock = clock,
            isWaived = { IntegrityEnvironment.isWaived(context) },
        )
    }

    val integrityMonitor: IntegrityMonitor by lazy {
        IntegrityMonitor(integrityEvaluator, events, integrityFeed)
    }

    // ── licensing ───────────────────────────────────────────────────────────

    /** The offline gate: token, signature, package. No network, and what licenses the app. */
    val licenseGate: LicenseGate by lazy { LicenseGate(context) }

    /**
     * The online half, wired the way every other feature here is: domain interfaces
     * (`domain/repository/LicenseRepositories.kt`), concrete implementations from
     * `data/`, and a use case that has never heard of either. Declared as the interface
     * type on purpose — the graph is the only file that knows Retrofit and Gson are
     * the answer, so swapping the transport touches one line.
     */
    val verdictAuthenticator: VerdictAuthenticator by lazy {
        GsonVerdictAuthenticator(responseKeyOverride ?: LicenseConfig.responsePublicKey())
    }

    val licenseApi: LicenseApi by lazy {
        licenseApiOverride ?: RetrofitLicenseApi(LicenseConfig.baseUrl(context), logger = logger)
    }

    val licenseVerdictStore: LicenseVerdictStore by lazy {
        LicenseVerdictStoreImpl(context, verdictAuthenticator)
    }

    /**
     * Revocation and expiry, which the offline gate structurally cannot know about.
     * Built lazily like everything here, so a build with no compiled-in response key
     * never constructs an HTTP client at all.
     */
    val checkLicenseRevocation: CheckLicenseRevocationUseCase by lazy {
        CheckLicenseRevocationUseCase(
            api = licenseApi,
            authenticator = verdictAuthenticator,
            store = licenseVerdictStore,
            packageName = context.packageName,
            sdkVersion = BuildConfig.SDK_VERSION,
            logger = logger,
        )
    }

    /** The no-network read `ready()` consults. Separate class, opposite obligation. */
    val getCachedLicenseAction: GetCachedLicenseActionUseCase by lazy {
        GetCachedLicenseActionUseCase(licenseVerdictStore)
    }

    /** The no-network read `Tracker.licenseInfo()` answers from. */
    val getLicenseInfo: GetLicenseInfoUseCase by lazy {
        GetLicenseInfoUseCase(licenseVerdictStore)
    }

    // ── capture ─────────────────────────────────────────────────────────────

    val watchdog: Watchdog by lazy { Watchdog(clock, events) }
    val liveTrackFeed: LiveTrackFeed by lazy { LiveTrackFeed(trackPoints) }

    val ingestor: FixIngestor by lazy {
        FixIngestor(
            store = roomPointStore,
            pipeline = pipeline,
            turnDetector = turnDetector,
            constants = constants,
            clock = clock,
            watchdog = watchdog,
            events = events,
            liveTrack = liveTrackFeed,
            battery = batteryMonitor,
            integrityFeed = integrityFeed,
            integrityFlags = { integrityMonitor.flags },
            providerFlags = { providerStateMonitor.snapshotFlags },
        )
    }

    val streamController: LocationStreamController by lazy {
        LocationStreamController(locationSource, fixMapper, ingestor, logger, scope)
    }
    val captureStream: CaptureStream by lazy { streamController }

    val oneShotProvider: OneShotProvider by lazy {
        OneShotProvider(locationSource, fixMapper, ingestor, events, logger, providerStateMonitor)
    }

    /**
     * The consumer `ProviderStateMonitor` never had: suspends and re-arms capture as the
     * permission grant and the location providers move under an open session.
     */
    val captureGate: CaptureGate by lazy {
        CaptureGate(
            providerState = providerStateMonitor.state,
            captureSwitch = streamController,
            events = events,
            logger = logger,
            scope = scope,
            onResumed = { config ->
                // The retry cap is per-outage by contract, and the outage just ended —
                // without this, three timeouts logged while the GPS was off would suppress
                // the very one-shot that proves it came back (EC-17).
                oneShotProvider.resetFailures()
                oneShotProvider.capture(config)
            },
        )
    }

    val motionController: MotionController by lazy {
        MotionController(
            machine = motionStateMachine,
            streamController = captureStream,
            significantMotion = motionWakeSource,
            stationaryFence = geofenceRegistrar,
            clock = clock,
            events = events,
            logger = logger,
            scope = scope,
        )
    }

    val healthLoop: HealthLoop by lazy {
        HealthLoop(
            context, sessions, clock, events, watchdog, motionController, providerStateMonitor,
            syncScheduler, logger, integrityMonitor, stopTracking,
        )
    }

    // ── use cases ───────────────────────────────────────────────────────────

    /**
     * The one teardown, shared by `stop()` and by the start path's "only one session at a
     * time" rule.
     */
    val sessionTeardown: SessionTeardown by lazy {
        SessionTeardown(
            sessions = sessions,
            ingestor = ingestor,
            streamController = streamController,
            captureGate = captureGate,
            motionController = motionController,
            stepCorroborator = stepCorroborator,
            activityRecognizer = activityRecognizer,
            significantMotion = significantMotion,
            gyroTurnMonitor = gyroTurnMonitor,
            watchdog = watchdog,
            context = context,
        )
    }

    val startTracking: StartTrackingUseCase by lazy {
        StartTrackingUseCase(
            sessions = sessions,
            ingestor = ingestor,
            locationSource = locationSource,
            streamController = streamController,
            captureGate = captureGate,
            teardown = sessionTeardown,
            providerStateMonitor = providerStateMonitor,
            motionController = motionController,
            oneShotProvider = oneShotProvider,
            configStore = configStore,
            permissions = permissions,
            stepCorroborator = stepCorroborator,
            activityRecognizer = activityRecognizer,
            watchdog = watchdog,
            syncScheduler = syncScheduler,
            context = context,
            events = events,
            scope = scope,
            gyroTurnMonitor = gyroTurnMonitor,
            applyConfig = ::applyConfig,
        )
    }

    /**
     * The two config values that are wired rather than passed: the provider the router
     * sends to, and the engine constants the accuracy meter moves.
     *
     * Applied per `start()` rather than at `ready()` because `ready()` only resolves the
     * config — a host may call it, read the resolved value, and start with something else.
     * Everything downstream is session-scoped anyway.
     */
    private fun applyConfig(config: TrackerConfig) {
        locationSource.select(config.geolocation.providerType)
        ingestor.retune(AccuracyTuning.apply(constants, config.geolocation))
    }

    val stopTracking: StopTrackingUseCase by lazy {
        StopTrackingUseCase(
            teardown = sessionTeardown,
            syncScheduler = syncScheduler,
            events = events,
        )
    }

    val resolveConfig: ResolveConfigUseCase by lazy {
        ResolveConfigUseCase(
            config,
            sensorProbe,
            events,
            isDebuggable = { IntegrityEnvironment.isWaived(context) },
        )
    }

    // ── the public surface ──────────────────────────────────────────────────

    val trackIt: Tracker by lazy {
        Tracker(
            startTracking = startTracking,
            stopTracking = stopTracking,
            resolveConfig = resolveConfig,
            sessions = sessions,
            points = trackPoints,
            decisions = decisions,
            rawFixes = rawFixDao,
            rawPoints = rawPointDao,
            ingestor = ingestor,
            oneShotProvider = oneShotProvider,
            liveTrackFeed = liveTrackFeed,
            captureGate = captureGate,
            clock = clock,
            providerStateMonitor = providerStateMonitor,
            batteryMonitor = batteryMonitor,
            sensorProbe = sensorProbe,
            integrityMonitor = integrityMonitor,
            stationaryFence = stationaryFence,
            permissions = permissions,
            licenseGate = licenseGate,
            getCachedLicenseAction = getCachedLicenseAction,
            getLicenseInfo = getLicenseInfo,
            checkLicenseRevocation = checkLicenseRevocation,
            context = context,
            scope = scope,
            eventSink = events,
        )
    }

    internal companion object {
        private const val EVENT_BUFFER = 64

        @SuppressLint("StaticFieldLeak") // get() stores only context.applicationContext.
        @Volatile
        private var instance: TrackerGraph? = null

        /**
         * The one graph for this process.
         *
         * Double-checked rather than `by lazy` on the object because it takes a
         * [Context]: the first caller supplies it, everyone after gets the same graph
         * whatever they pass. Always stored against the **application** context — a
         * graph holding an Activity would leak it for the process lifetime.
         */
        fun get(context: Context): TrackerGraph {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: TrackerGraph(context.applicationContext).also { instance = it }
            }
        }

        /**
         * Installs a graph whose licence transport and trust anchor come from the caller,
         * and makes it the one [get] returns.
         *
         * The only way to exercise `Tracker.ready()`'s licence behaviour: the transport is
         * built from a compiled-in URL and the trust anchor from a compiled-in key, so
         * without this a test can only ever observe an unconfigured build deciding to do
         * nothing. Pair with [resetForTest] in an `@After`, or the graph leaks into every
         * test that runs afterwards in the same JVM.
         */
        @VisibleForTesting
        fun installForTest(
            context: Context,
            licenseApi: LicenseApi,
            responseKey: ByteArray,
        ): TrackerGraph = synchronized(this) {
            TrackerGraph(context.applicationContext, licenseApi, responseKey)
                .also { instance = it }
        }

        /** Test seam only. Drops the graph so the next [get] rebuilds it. */
        fun resetForTest() {
            synchronized(this) { instance = null }
        }
    }
}
