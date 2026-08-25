package com.field360.tracker

import com.field360.traker.geo.model.MockPolicy
import com.field360.tracker.integrity.IntegrityPolicy
import java.net.URI
import com.field360.tracker.domain.model.TrackerGeofence
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * @property reset `true` (default) — `ready()` applies this config on top of factory
 *   defaults. `false` — the persisted config wins and **this object is ignored after
 *   the first launch**; only `setConfig()` changes anything thereafter.
 *
 *   Leave it `true` during development. A `false` here with edited constants is the
 *   classic "my config changes do nothing" bug, and the single most common support
 *   question in this category (SDK-COMPARISON §5).
 */
@Serializable
public data class TrackerConfig(
    val geolocation: GeolocationConfig = GeolocationConfig(),
    val motion: MotionConfig = MotionConfig(),
    val service: ServiceConfig = ServiceConfig(),
    val persistence: PersistenceConfig = PersistenceConfig(),
    val sensors: SensorConfig = SensorConfig(),
    val security: SecurityConfig = SecurityConfig(),
    @Transient
    val license: String? = null,
    /**
     * Scheme and host your backend lives at — e.g. `https://api.example.com`.
     *
     * **Core never reads it.** It opens no socket and has no endpoint of its own; this is
     * carried here so a host with one base URL for its whole app can set it once, in the
     * config it is already building, and have `fieldtrack-sync` resolve a path against it:
     *
     * ```kotlin
     * trackIt.ready(TrackerConfig.builder().baseUrl("https://api.example.com").build())
     * sync.configure(SyncConfig.builder().path("v1/location/batch").build())
     * ```
     *
     * A `SyncConfig` that already carries an absolute URL wins — this is a fallback, never
     * an override, so a host that sets both gets the one it wrote closest to the upload.
     *
     * Persisted with the rest of the config, so it survives a process death like every other
     * field. Do not put a credential in it: it is stored in plain text.
     */
    val baseUrl: String? = null,
    val reset: Boolean = true,
) {
    /** Fails `ready()` fast and by name, rather than deep inside a provider. */
    public fun validate(): List<String> = buildList {
        if (geolocation.intervalMs < geolocation.fastestIntervalMs) {
            add("geolocation.intervalMs (${geolocation.intervalMs}) must be >= fastestIntervalMs (${geolocation.fastestIntervalMs}) — EC-120")
        }
        if (geolocation.distanceFilterM > 0f) {
            add("geolocation.distanceFilterM must be 0; a non-zero OS distance filter generates stationary drift — EC-119")
        }
        // EC-121: a heartbeat close to the sampling interval fires every fix and
        // defeats stationary suppression entirely.
        val minHeartbeatSec = geolocation.intervalMs / 1000 * HEARTBEAT_INTERVAL_MULTIPLE
        if (motion.heartbeatIntervalSec < minHeartbeatSec) {
            add("motion.heartbeatIntervalSec must be >= $HEARTBEAT_INTERVAL_MULTIPLE x the sampling interval ($minHeartbeatSec s) — EC-121")
        }
        if (motion.stationaryRadiusM <= 0f || motion.stationaryRadiusM.isNaN()) {
            add("motion.stationaryRadiusM must be > 0")
        }
        if (motion.stationaryGeofenceId.isBlank()) {
            add("motion.stationaryGeofenceId must not be blank")
        }
        if (motion.stationaryGeofenceOnEnterEvent.isBlank()) {
            add("motion.stationaryGeofenceOnEnterEvent must not be blank")
        }
        if (motion.stationaryGeofenceOnExitEvent.isBlank()) {
            add("motion.stationaryGeofenceOnExitEvent must not be blank")
        }
        // Checked here rather than where it is consumed, because a typo'd base URL should
        // fail while the host is assembling config — not one `configure()` call later in a
        // different module, reported against a path the host did not write.
        baseUrl?.let { url ->
            val uri = runCatching { URI(url) }.getOrNull()
            if (url.isBlank() || uri?.scheme == null || uri.host.isNullOrBlank()) {
                add("baseUrl must be an absolute URL with a scheme and host, e.g. https://api.example.com")
            }
        }
        // EC-45: the tiers must actually be ordered, or the "faster" one is slower than
        // what it replaces and the burst quietly makes turn geometry worse.
        if (geolocation.turnBurst) {
            if (geolocation.turnBurstIntervalMs <= 0) {
                add("geolocation.turnBurstIntervalMs must be > 0 — EC-45")
            }
            val slowest = if (geolocation.adaptiveCadence) {
                geolocation.vehicularIntervalMs
            } else {
                geolocation.intervalMs
            }
            if (geolocation.turnBurstIntervalMs > slowest) {
                add(
                    "geolocation.turnBurstIntervalMs (${geolocation.turnBurstIntervalMs}) must be <= the tier " +
                        "it accelerates ($slowest) — EC-45",
                )
            }
        }
        if (geolocation.navigationMode) {
            if (geolocation.navigationIntervalMs <= 0) {
                add("geolocation.navigationIntervalMs must be > 0")
            }
            if (geolocation.navigationIntervalMs < geolocation.navigationFastestIntervalMs) {
                add(
                    "geolocation.navigationIntervalMs (${geolocation.navigationIntervalMs}) must be >= " +
                        "navigationFastestIntervalMs (${geolocation.navigationFastestIntervalMs}) — EC-120",
                )
            }
            // A 1 Hz stream without a foreground service is throttled or killed at the
            // first backgrounding — a failure that presents as "navigation randomly
            // stops" and is invisible in any log the host will think to read.
            if (!service.foregroundService) {
                add("geolocation.navigationMode requires service.foregroundService")
            }
        }
        if (persistence.maxDaysToPersist < 0) add("persistence.maxDaysToPersist must be >= 0")

        addAll(geolocation.accuracy.validate())

        // A network-only stream cannot beat a GNSS-calibrated ceiling. Fused fixes run at
        // 4-20 m; Wi-Fi/cell centroids run at 20-2000 m, so a 20-30 m ceiling rejects every
        // fix the provider is capable of producing and the session stores nothing at all.
        // Fail here rather than let the host debug an empty track (EC-32).
        if (geolocation.providerType == LocationProviderType.NETWORK_ONLY &&
            geolocation.accuracy.maxAccuracyM < MIN_NETWORK_ACCURACY_M
        ) {
            add(
                "geolocation.providerType=NETWORK_ONLY needs an accuracy ceiling of at least " +
                    "$MIN_NETWORK_ACCURACY_M m (currently ${geolocation.accuracy.maxAccuracyM} m) — " +
                    "use AccuracyProfile.RELAXED or CUSTOM",
            )
        }

        // PASSIVE delivers whatever other apps happened to request, at whatever cadence
        // they requested it. There is no stream to accelerate and no fix to force.
        if (geolocation.providerType == LocationProviderType.PASSIVE && geolocation.navigationMode) {
            add("geolocation.navigationMode cannot be used with providerType=PASSIVE")
        }

        addAll(security.validate())
    }

    public companion object {
        internal const val HEARTBEAT_INTERVAL_MULTIPLE = 5

        /** Below this a network centroid can never pass, so the ceiling is a mute button. */
        internal const val MIN_NETWORK_ACCURACY_M = 50f

        /** `TrackerConfig.builder()` — the Java-friendly entry point. See [Builder]. */
        @JvmStatic
        public fun builder(): Builder = Builder()
    }

    /**
     * Fluent, Java-callable construction of the whole config.
     *
     * The `data class` constructor is unchanged and remains the idiomatic Kotlin route —
     * named arguments and `copy()` already do this job well. This exists because they do
     * it only from Kotlin: from Java, `TrackerConfig(GeolocationConfig(...), ...)` means
     * positionally constructing five nested classes with ~60 parameters between them, and
     * every added field is a source break for every host that did.
     *
     * ```kotlin
     * val config = TrackerConfig.builder()
     *     .provider(LocationProviderType.GPS_ONLY)
     *     .accuracyProfile(AccuracyProfile.STRICT)
     *     .useStepCorroboration(true)
     *     .build()
     * ```
     *
     * [build] runs [validate] and throws `IllegalArgumentException` on failure. That is a
     * deliberate exception to the SDK's no-throw contract: everything reachable from
     * [Tracker] returns a typed `TrackerResult` because it runs inside a host's coroutine
     * where a throw is an unpreventable crash. This runs on the host's own thread while it
     * is assembling a value, which is exactly where fail-fast belongs — the alternative is
     * an invalid config that reports itself an entire `ready()` call later. Use
     * [buildUnchecked] when the config is being assembled from untrusted input and the
     * host would rather read [validate] itself.
     */
    public class Builder {

        private var geolocation: GeolocationConfig = GeolocationConfig()
        private var motion: MotionConfig = MotionConfig()
        private var service: ServiceConfig = ServiceConfig()
        private var persistence: PersistenceConfig = PersistenceConfig()
        private var sensors: SensorConfig = SensorConfig()
        private var security: SecurityConfig = SecurityConfig()
        private var license: String? = null
        private var baseUrl: String? = null
        private var reset: Boolean = true

        // ── whole blocks, for a host that already has one ────────────────────

        public fun geolocation(value: GeolocationConfig): Builder = apply { geolocation = value }
        public fun motion(value: MotionConfig): Builder = apply { motion = value }
        public fun service(value: ServiceConfig): Builder = apply { service = value }
        public fun persistence(value: PersistenceConfig): Builder = apply { persistence = value }
        public fun sensors(value: SensorConfig): Builder = apply { sensors = value }
        /** Device-integrity policy — see [SecurityConfig]. Waived entirely in debuggable builds. */
        public fun security(value: SecurityConfig): Builder = apply { security = value }
        /** Optional release-build license token. Ignored by persistence. */
        public fun license(value: String?): Builder = apply { license = value }

        /**
         * Base URL for the upload endpoint — see [TrackerConfig.baseUrl].
         *
         * Read only by `fieldtrack-sync`, and only when a `SyncConfig` does not carry an
         * absolute URL of its own. Setting it with that module absent is harmless and does
         * nothing.
         */
        public fun baseUrl(value: String?): Builder = apply { baseUrl = value }

        /** See [TrackerConfig.reset] — leave `true` during development. */
        public fun reset(value: Boolean): Builder = apply { reset = value }

        // ── geolocation ──────────────────────────────────────────────────────

        public fun trackingMode(value: TrackingMode): Builder =
            apply { geolocation = geolocation.copy(trackingMode = value) }

        /**
         * Which hardware actually produces the fixes — see [LocationProviderType].
         *
         * This is a different question from [desiredAccuracy], which only biases the fused
         * provider's own choice among the sources it has.
         */
        public fun provider(value: LocationProviderType): Builder =
            apply { geolocation = geolocation.copy(providerType = value) }

        public fun desiredAccuracy(value: DesiredAccuracy): Builder =
            apply { geolocation = geolocation.copy(desiredAccuracy = value) }

        // ── the accuracy meter ───────────────────────────────────────────────

        /**
         * The named ceilings. [AccuracyProfile.CUSTOM] needs [maxAccuracyMeters] as well —
         * setting one without the other fails [build].
         */
        public fun accuracyProfile(value: AccuracyProfile): Builder = apply {
            geolocation = geolocation.copy(accuracy = geolocation.accuracy.copy(profile = value))
        }

        /**
         * The ceiling in metres, and an implicit switch to [AccuracyProfile.CUSTOM].
         *
         * Switching the profile here rather than making the host set both is the whole
         * point: `maxAccuracyMeters(15f)` silently doing nothing because the profile was
         * still `BALANCED` is precisely the class of bug this API is meant to remove.
         */
        public fun maxAccuracyMeters(value: Float): Builder = apply {
            geolocation = geolocation.copy(
                accuracy = geolocation.accuracy.copy(
                    profile = AccuracyProfile.CUSTOM,
                    maxAccuracyMeters = value,
                ),
            )
        }

        /** Post-gap re-anchor bar; `null` restores the profile's own value (EC-140). */
        public fun recoveryTrustMeters(value: Float?): Builder = apply {
            geolocation = geolocation.copy(
                accuracy = geolocation.accuracy.copy(recoveryTrustMeters = value),
            )
        }

        public fun accuracy(value: AccuracyConfig): Builder =
            apply { geolocation = geolocation.copy(accuracy = value) }

        // ── cadence ──────────────────────────────────────────────────────────

        public fun intervalMs(value: Long): Builder =
            apply { geolocation = geolocation.copy(intervalMs = value) }

        public fun fastestIntervalMs(value: Long): Builder =
            apply { geolocation = geolocation.copy(fastestIntervalMs = value) }

        public fun maxUpdateDelayMs(value: Long): Builder =
            apply { geolocation = geolocation.copy(maxUpdateDelayMs = value) }

        public fun maxFixAgeMs(value: Long): Builder =
            apply { geolocation = geolocation.copy(maxFixAgeMs = value) }

        public fun adaptiveCadence(value: Boolean): Builder =
            apply { geolocation = geolocation.copy(adaptiveCadence = value) }

        public fun vehicularIntervalMs(value: Long): Builder =
            apply { geolocation = geolocation.copy(vehicularIntervalMs = value) }

        public fun turnBurst(value: Boolean): Builder =
            apply { geolocation = geolocation.copy(turnBurst = value) }

        public fun turnBurstIntervalMs(value: Long): Builder =
            apply { geolocation = geolocation.copy(turnBurstIntervalMs = value) }

        public fun navigationMode(value: Boolean): Builder =
            apply { geolocation = geolocation.copy(navigationMode = value) }

        public fun navigationIntervalMs(value: Long): Builder =
            apply { geolocation = geolocation.copy(navigationIntervalMs = value) }

        public fun navigationFastestIntervalMs(value: Long): Builder =
            apply { geolocation = geolocation.copy(navigationFastestIntervalMs = value) }

        public fun oneShotTimeoutMs(value: Long): Builder =
            apply { geolocation = geolocation.copy(oneShotTimeoutMs = value) }

        public fun mockLocationPolicy(value: MockPolicy): Builder =
            apply { geolocation = geolocation.copy(mockLocationPolicy = value) }

        // ── motion ───────────────────────────────────────────────────────────

        public fun activityRecognition(value: Boolean): Builder =
            apply { motion = motion.copy(activityRecognition = value) }

        public fun activityRecognitionIntervalMs(value: Long): Builder =
            apply { motion = motion.copy(activityRecognitionIntervalMs = value) }

        public fun activityConfidenceMin(value: Int): Builder =
            apply { motion = motion.copy(activityConfidenceMin = value) }

        public fun snapshotConfidenceMin(value: Int): Builder =
            apply { motion = motion.copy(snapshotConfidenceMin = value) }

        public fun disableStopDetection(value: Boolean): Builder =
            apply { motion = motion.copy(disableStopDetection = value) }

        public fun stopOnStationary(value: Boolean): Builder =
            apply { motion = motion.copy(stopOnStationary = value) }

        public fun stopTimeoutMin(value: Int): Builder =
            apply { motion = motion.copy(stopTimeoutMin = value) }

        public fun stationaryRadiusM(value: Float): Builder =
            apply { motion = motion.copy(stationaryRadiusM = value) }

        public fun stationaryGeofenceId(value: String): Builder =
            apply { motion = motion.copy(stationaryGeofenceId = value) }

        public fun stationaryGeofenceOnEnterEvent(value: String): Builder =
            apply { motion = motion.copy(stationaryGeofenceOnEnterEvent = value) }

        public fun stationaryGeofenceOnExitEvent(value: String): Builder =
            apply { motion = motion.copy(stationaryGeofenceOnExitEvent = value) }

        public fun motionTriggerDelayMs(value: Long): Builder =
            apply { motion = motion.copy(motionTriggerDelayMs = value) }

        public fun heartbeatIntervalSec(value: Int): Builder =
            apply { motion = motion.copy(heartbeatIntervalSec = value) }

        public fun persistHeartbeat(value: Boolean): Builder =
            apply { motion = motion.copy(persistHeartbeat = value) }

        public fun bearingChangeCaptureDeg(value: Int): Builder =
            apply { motion = motion.copy(bearingChangeCaptureDeg = value) }

        public fun cornerAnchorCapture(value: Boolean): Builder =
            apply { motion = motion.copy(cornerAnchorCapture = value) }

        // ── sensors ──────────────────────────────────────────────────────────

        public fun useSignificantMotion(value: Boolean): Builder =
            apply { sensors = sensors.copy(useSignificantMotion = value) }

        public fun useStepCorroboration(value: Boolean): Builder =
            apply { sensors = sensors.copy(useStepCorroboration = value) }

        public fun useAccelerometerVeto(value: Boolean): Builder =
            apply { sensors = sensors.copy(useAccelerometerVeto = value) }

        public fun useBarometer(value: Boolean): Builder =
            apply { sensors = sensors.copy(useBarometer = value) }

        public fun stepBatchLatencyMs(value: Long): Builder =
            apply { sensors = sensors.copy(stepBatchLatencyMs = value) }

        public fun useGyroTurnPrediction(value: Boolean): Builder =
            apply { sensors = sensors.copy(useGyroTurnPrediction = value) }

        // ── service ──────────────────────────────────────────────────────────

        public fun foregroundService(value: Boolean): Builder =
            apply { service = service.copy(foregroundService = value) }

        public fun stopOnTerminate(value: Boolean): Builder =
            apply { service = service.copy(stopOnTerminate = value) }

        public fun startOnBoot(value: Boolean): Builder =
            apply { service = service.copy(startOnBoot = value) }

        public fun healthLoopMs(value: Long): Builder =
            apply { service = service.copy(healthLoopMs = value) }

        public fun watchdogIntervalMs(value: Long): Builder =
            apply { service = service.copy(watchdogIntervalMs = value) }

        public fun watchdogThrottleMs(value: Long): Builder =
            apply { service = service.copy(watchdogThrottleMs = value) }

        public fun backstopIntervalMin(value: Int): Builder =
            apply { service = service.copy(backstopIntervalMin = value) }

        public fun deadTrackerMovingMin(value: Int): Builder =
            apply { service = service.copy(deadTrackerMovingMin = value) }

        public fun deadTrackerStationaryMin(value: Int): Builder =
            apply { service = service.copy(deadTrackerStationaryMin = value) }

        public fun wakeLockMs(value: Long): Builder =
            apply { service = service.copy(wakeLockMs = value) }

        public fun notification(title: String, text: String): Builder =
            apply { service = service.copy(notificationTitle = title, notificationText = text) }

        public fun notificationChannel(id: String, name: String): Builder =
            apply { service = service.copy(notificationChannelId = id, notificationChannelName = name) }

        public fun notificationSmallIconResName(value: String?): Builder =
            apply { service = service.copy(notificationSmallIconResName = value) }

        // ── persistence ──────────────────────────────────────────────────────

        public fun maxDaysToPersist(value: Int): Builder =
            apply { persistence = persistence.copy(maxDaysToPersist = value) }

        public fun maxRecords(value: Int): Builder =
            apply { persistence = persistence.copy(maxRecords = value) }

        public fun persistRawFixes(value: Boolean): Builder =
            apply { persistence = persistence.copy(persistRawFixes = value) }

        public fun rawRingCapacity(value: Int): Builder =
            apply { persistence = persistence.copy(rawRingCapacity = value) }

        public fun persistRawPoints(value: Boolean): Builder =
            apply { persistence = persistence.copy(persistRawPoints = value) }

        public fun rawPointRingCapacity(value: Int): Builder =
            apply { persistence = persistence.copy(rawPointRingCapacity = value) }

        public fun persistDecisions(value: Boolean): Builder =
            apply { persistence = persistence.copy(persistDecisions = value) }

        public fun decisionRetentionDays(value: Int): Builder =
            apply { persistence = persistence.copy(decisionRetentionDays = value) }

        public fun decisionMaxRows(value: Int): Builder =
            apply { persistence = persistence.copy(decisionMaxRows = value) }

        // ── device integrity ─────────────────────────────────────────────────

        /**
         * Master switch for the whole integrity layer.
         *
         * `false` in a release source set is what `FieldTrackSecurityDisabled` lint flags —
         * see docs/DEVICE-INTEGRITY-PLAN.md §7.
         */
        public fun securityEnabled(value: Boolean): Builder =
            apply { security = security.copy(enabled = value) }

        public fun accessibilityPolicy(value: IntegrityPolicy): Builder =
            apply { security = security.copy(accessibility = value) }

        public fun developerModePolicy(value: IntegrityPolicy): Builder =
            apply { security = security.copy(developerMode = value) }

        public fun hookingPolicy(value: IntegrityPolicy): Builder =
            apply { security = security.copy(hooking = value) }

        public fun clockPolicy(value: IntegrityPolicy): Builder =
            apply { security = security.copy(clock = value) }

        public fun mockLocationIntegrityPolicy(value: IntegrityPolicy): Builder =
            apply { security = security.copy(mockLocation = value) }

        /** Accessibility packages that never raise a finding — see [SecurityConfig.accessibilityAllowlist]. */
        public fun accessibilityAllowlist(value: Set<String>): Builder =
            apply { security = security.copy(accessibilityAllowlist = value) }

        public fun maxClockSkewMs(value: Long): Builder =
            apply { security = security.copy(maxClockSkewMs = value) }

        public fun integrityRecheckIntervalMs(value: Long): Builder =
            apply { security = security.copy(recheckIntervalMs = value) }

        // ── terminal ─────────────────────────────────────────────────────────

        /** @throws IllegalArgumentException if [TrackerConfig.validate] reports anything. */
        public fun build(): TrackerConfig {
            val config = buildUnchecked()
            val errors = config.validate()
            require(errors.isEmpty()) { "Invalid TrackerConfig: ${errors.joinToString("; ")}" }
            return config
        }

        /** The same value, unvalidated. `ready()` still validates and returns a typed error. */
        public fun buildUnchecked(): TrackerConfig = TrackerConfig(
            geolocation = geolocation,
            motion = motion,
            service = service,
            persistence = persistence,
            sensors = sensors,
            security = security,
            license = license,
            baseUrl = baseUrl,
            reset = reset,
        )
    }
}

@Serializable
public data class GeolocationConfig(
    val trackingMode: TrackingMode = TrackingMode.ADAPTIVE,
    /**
     * Which provider produces the fixes. Defaults to [LocationProviderType.FUSED], which
     * is the behaviour that shipped before this option existed.
     *
     * Orthogonal to [desiredAccuracy]: that biases the *fused* provider's own choice among
     * the sources it has, and cannot exclude any of them. This picks the hardware.
     */
    val providerType: LocationProviderType = LocationProviderType.FUSED,
    val desiredAccuracy: DesiredAccuracy = DesiredAccuracy.HIGH,
    /** The accuracy meter — how good a fix must be before it is stored. See [AccuracyConfig]. */
    val accuracy: AccuracyConfig = AccuracyConfig(),
    /**
     * **Must stay 0.** A non-zero OS distance filter is a stationary-drift *generator*:
     * the OS only wakes you when noise exceeds the filter, so every update looks like
     * movement. All thinning is done in software, deliberately (EC-119).
     */
    val distanceFilterM: Float = 0f,
    val intervalMs: Long = 60_000,
    val fastestIntervalMs: Long = 30_000,
    val maxUpdateDelayMs: Long = 60_000,
    val maxFixAgeMs: Long = 10_000,
    val deliveryStalenessMs: Long = 60_000,
    /** 12 s while vehicular — the biggest turn-fidelity win short of a routing API (EC-45). */
    val adaptiveCadence: Boolean = true,
    val vehicularIntervalMs: Long = 12_000,
    /**
     * Third cadence tier: sampling while the vehicle is measurably *turning*.
     *
     * Adaptive cadence is a guess about the whole drive — fast everywhere, because the
     * turns could be anywhere. This spends the battery only where the geometry is, and
     * `fieldtrack-geo`'s `TurnDetector` decides when that is. Off leaves exactly the
     * two-tier behaviour that shipped before it.
     */
    val turnBurst: Boolean = true,
    val turnBurstIntervalMs: Long = 4_000,
    val oneShotTimeoutMs: Long = 30_000,
    val mockLocationPolicy: MockPolicy = MockPolicy.FLAG,
    /**
     * Navigation cadence profile: ~1 Hz, high accuracy, OS batching off, overriding
     * every adaptive tier while on (SMOOTH-NAV-PLAN Phase 1).
     *
     * The adaptive tiers exist to spend battery only where the geometry is; navigation
     * inverts that trade — a screen-on, foreground use where a fix a second *is* the
     * product. While on: the request interval is [navigationIntervalMs], priority is
     * forced to high accuracy whatever [desiredAccuracy] says, and [maxUpdateDelayMs]
     * is ignored — a single batching window would hold more fixes than the animation
     * they feed.
     *
     * Requires `service.foregroundService`; a 1 Hz stream without one is throttled or
     * killed at the first backgrounding, which reads as "navigation randomly stops".
     */
    val navigationMode: Boolean = false,
    val navigationIntervalMs: Long = 1_000,
    val navigationFastestIntervalMs: Long = 500,
)

@Serializable
public data class MotionConfig(
    val activityRecognition: Boolean = true,
    val activityRecognitionIntervalMs: Long = 10_000,
    val activityConfidenceMin: Int = 75,
    val snapshotConfidenceMin: Int = 50,
    val disableStopDetection: Boolean = false,
    val stopOnStationary: Boolean = false,
    val stopTimeoutMin: Int = 5,
    val stationaryRadiusM: Float = 150f,
    val stationaryGeofenceId: String = TrackerGeofence.DEFAULT_ID,
    val stationaryGeofenceOnEnterEvent: String = TrackerGeofence.DEFAULT_ENTER_EVENT,
    val stationaryGeofenceOnExitEvent: String = TrackerGeofence.DEFAULT_EXIT_EVENT,
    val motionTriggerDelayMs: Long = 0,
    /**
     * DATA-plane heartbeat: warms the filter but is **not stored**. This is what makes
     * a two-hour steady user produce exactly one point. Distinct from the control-plane
     * [com.field360.tracker.domain.model.TrackerEvent.Heartbeat] (EC-48).
     */
    val heartbeatIntervalSec: Int = 900,
    val persistHeartbeat: Boolean = false,
    /**
     * Store a point whenever the heading has turned this far since the last stored one,
     * regardless of what the speed and distance gates decided. `0` disables it.
     *
     * This is the capture-side half of turn fidelity, and it is the half that adaptive
     * cadence cannot cover: at 12 s a corner taken at 25 km/h falls between two samples
     * that are each individually unremarkable, so both survive the gates and the polyline
     * draws the chord across the corner. Comparing headings is what makes the *angle*
     * itself a reason to keep a point (EC-45).
     *
     * 30° is below a motorway interchange and above lane-change and GPS heading noise.
     * It was 40°, which is a junction threshold rather than a bend threshold: because the
     * comparison runs against the last *stored* heading, a long curve turning 35° between
     * stored points never crossed it and the drawn line kept only the straight legs
     * either side. The engine's own floors — 15 m moved, 1.5 m/s, accuracy under 50 m —
     * are what keep the lower value from firing on noise.
     */
    val bearingChangeCaptureDeg: Int = 30,
    /**
     * Restore a rejected fix once the *next* fix shows a corner turned across it (EC-45e).
     *
     * The half [bearingChangeCaptureDeg] structurally cannot reach. That gate compares a
     * fix against the last **stored** point, so it looks backwards — and at a corner's
     * apex only half the turn is behind you. A 90° junction sampled on approach, at the
     * apex and on the exit offers the apex fix as a 45° change, under any threshold set to
     * recognise a junction; it is dropped, the exit fix is stored on the full 90°, and the
     * line runs straight from approach to exit. The vertex that would have described the
     * corner was discarded one fix earlier.
     *
     * So the rejection is held for one fix. If the path bent across it — by geometry, or
     * because the gyroscope or `TurnDetector` says the vehicle was turning — the fix is
     * stored after all, as `Reasons.CORNER_ANCHOR`.
     *
     * **Only the heuristic gate's rejections are ever reconsidered.** Every harder gate —
     * impossible speed, poor accuracy, the three-sigma outlier test — is untouched: a
     * corner is a reason to keep an unremarkable fix, never a reason to admit a bad one.
     *
     * The latency is one fix, and only for fixes that were being discarded. Accepted
     * points still reach `TrackerEvent.Location`, the live tail and the puck the instant
     * the pipeline accepts them.
     *
     * Set `false` for the pre-EC-45e behaviour exactly.
     */
    val cornerAnchorCapture: Boolean = true,
)

@Serializable
public data class SensorConfig(
    /** Permission-free, ~zero-power hardware wake for STATIONARY → MOVING (EC-132). */
    val useSignificantMotion: Boolean = true,
    /** Step-count veto on stationary drift, and confirmation of indoor walks (EC-133). */
    val useStepCorroboration: Boolean = true,
    val useAccelerometerVeto: Boolean = true,
    val useBarometer: Boolean = false,
    val stepBatchLatencyMs: Long = 60_000,
    /**
     * Arm the turn burst from the **gyroscope**, ahead of GNSS heading (EC-45d).
     *
     * `TurnDetector` can only see a turn in the difference between two fixes, so at a 12 s
     * cadence it learns about a corner at best 12 s after it began — by which time the
     * corner is behind the vehicle and the extra samples land on the straight after it.
     * Yaw rate rises as the wheel turns, a second or more before heading has moved enough
     * for GNSS to resolve it, so the burst runs *into* the corner instead of out of it.
     *
     * On by default, and cheaper than it sounds: the gyroscope is registered only while
     * fixes report vehicular speed and released within a minute of them stopping, so a
     * session spent walking or parked never opens it. Set `false` for GNSS-only turn
     * detection — the behaviour of every release before this one — on a fleet where the
     * power budget is tight enough to prefer the corners over the milliamps.
     *
     * A no-op when `geolocation.turnBurst` is off, and on a device with no gyroscope or no
     * gravity source.
     */
    val useGyroTurnPrediction: Boolean = true,
)

/**
 * @property stopOnTerminate defaults **false** and [startOnBoot] defaults **true** —
 *   both inverted from the incumbent. An SDK whose purpose is surviving termination
 *   should not ship defaults under which a swipe-away silently ends tracking; that
 *   failure is invisible until the data is missing (SDK-COMPARISON §4, EC-125).
 */
@Serializable
public data class ServiceConfig(
    val foregroundService: Boolean = true,
    val stopOnTerminate: Boolean = false,
    val startOnBoot: Boolean = true,
    val healthLoopMs: Long = 120_000,
    val watchdogIntervalMs: Long = 60_000,
    val watchdogThrottleMs: Long = 900_000,
    val backstopIntervalMin: Int = 15,
    val deadTrackerMovingMin: Int = 30,
    val deadTrackerStationaryMin: Int = 60,
    val wakeLockMs: Long = 20_000,
    val notificationTitle: String = "Tracking active",
    val notificationText: String = "Recording your location",
    val notificationChannelId: String = "trackit_tracking",
    val notificationChannelName: String = "Location tracking",
    val notificationSmallIconResName: String? = null,
)

@Serializable
public data class PersistenceConfig(
    val maxDaysToPersist: Int = 7,
    val maxRecords: Int = 0,
    val persistRawFixes: Boolean = false,
    val rawRingCapacity: Int = 5_000,
    /**
     * Store every judged fix in point form in `raw_points`, accepted or not.
     *
     * Off by default: it is one wide row per fix, which on a 12 s cadence is real write
     * amplification for a host that never queries it. On when you need to ask why a
     * point is missing rather than why a point is wrong.
     */
    val persistRawPoints: Boolean = false,
    /**
     * Rows of `raw_points` kept **per session**, not across the database.
     *
     * A global cap would let one long session evict every earlier session's rows, and
     * the comparison that explains a bad session is usually the good one before it.
     */
    val rawPointRingCapacity: Int = 20_000,
    val persistDecisions: Boolean = true,
    val decisionRetentionDays: Int = 3,
    val decisionMaxRows: Int = 50_000,
)

public enum class TrackingMode {
    /** Stream at 60 s always; the filter does all thinning. Highest fidelity and battery. */
    CONTINUOUS,

    /** Stream while moving with adaptive cadence, heartbeat-only while stationary. */
    ADAPTIVE,

    /** Location fully off while stationary. Lowest battery, coarsest stop timing. */
    MOTION_ONLY,
}

public enum class DesiredAccuracy { HIGH, BALANCED, LOW }

/**
 * Which hardware the fixes come from.
 *
 * [FUSED] is the default and the right answer for almost every host: Play Services blends
 * GNSS, Wi-Fi, cell and the device's own sensors, and gets a first fix indoors where a
 * bare GNSS request gets nothing for minutes. The other three exist because "fused" is
 * a black box, and some hosts have a reason to open it — a survey app that must never
 * record a Wi-Fi centroid, a low-power app that wants only what other apps already paid
 * for, or a device with no Play Services at all.
 *
 * The three non-fused values go through `android.location.LocationManager` and therefore
 * **work with no Play Services installed** — which is the Huawei/AOSP case that used to
 * return `PLAY_SERVICES_UNAVAILABLE` from `start()` with nothing the host could do (EC-19).
 */
public enum class LocationProviderType {
    /** Play Services fused provider. Blends every source; best time-to-first-fix. */
    FUSED,

    /**
     * `LocationManager.GPS_PROVIDER` — GNSS only.
     *
     * Every fix is satellite-derived, so nothing that reaches the pipeline is a Wi-Fi
     * teleport by construction. The cost is real and should be expected: no fix at all
     * indoors, in a car park or in a tunnel, cold starts of 30-60 s, and materially more
     * battery than fused at the same interval, because there is no cached fix to hand back.
     */
    GPS_ONLY,

    /**
     * `LocationManager.NETWORK_PROVIDER` — Wi-Fi and cell centroids only.
     *
     * Coarse (20-2000 m) and cheap. Needs an accuracy ceiling to match; a GNSS-calibrated
     * ceiling rejects every fix this provider can produce, and `validate()` refuses that
     * combination rather than let it present as an empty track.
     */
    NETWORK_ONLY,

    /**
     * `LocationManager.PASSIVE_PROVIDER` — fixes other apps requested, for free.
     *
     * Costs no additional power and requests nothing of its own, which is also its
     * limitation: cadence, provider and accuracy are all whatever some other app asked
     * for, and on a device where nothing else is tracking it delivers nothing at all.
     * Every cadence tier is inert here, so [GeolocationConfig.navigationMode] is refused.
     */
    PASSIVE,
}

/**
 * The accuracy meter: how good a fix must be before it earns a stored point.
 *
 * This is a ceiling on the reported error radius, applied to fixes claimed to be **moving**
 * — the one unconditional bound in the acceptance pipeline. Every other accuracy limit in
 * the engine is conditional on a motion class that the fix's own displacement helps decide,
 * which is circular: a 66 m positioning error computes as ~14 m/s, ~14 m/s reads as
 * vehicular, and vehicular carries the loosest ceiling there is. That circle is how a field
 * capture stored a 153 m spike and a 173° reversal inside an ordinary city drive (EC-139).
 *
 * Stationary fixes are deliberately **not** governed by this. They are handled by the anchor
 * and wobble defences, which treat a poor fix as something to freeze against rather than
 * something to plot — tightening this to fix stationary drift would be tuning down the wrong
 * stage (spec §8.1, §8.3).
 *
 * ```kotlin
 * TrackerConfig.builder().accuracyProfile(AccuracyProfile.STRICT).build()
 * TrackerConfig.builder().maxAccuracyMeters(35f).build()   // implies CUSTOM
 * ```
 */
@Serializable
public data class AccuracyConfig(
    val profile: AccuracyProfile = AccuracyProfile.BALANCED,
    /** Required by [AccuracyProfile.CUSTOM], ignored — and rejected — by every other profile. */
    val maxAccuracyMeters: Float? = null,
    /** Overrides the profile's post-gap re-anchor bar. `null` keeps it (EC-140). */
    val recoveryTrustMeters: Float? = null,
) {

    /** The effective moving ceiling, metres. */
    public val maxAccuracyM: Float
        get() = when (profile) {
            AccuracyProfile.STRICT -> STRICT_MAX_M
            AccuracyProfile.BALANCED -> BALANCED_MAX_M
            AccuracyProfile.RELAXED -> RELAXED_MAX_M
            AccuracyProfile.CUSTOM -> maxAccuracyMeters ?: BALANCED_MAX_M
        }

    /**
     * The effective post-gap re-anchor bar, metres — always at or below [maxAccuracyM].
     *
     * A separate and stricter number because it answers a different question. The first fix
     * after a signal blackout is the least corroborated fix of the session and the most
     * consequential, since every later fix is judged from wherever it lands. This bar decides
     * whether one uncorroborated fix may move the anchor; [maxAccuracyM] decides whether a
     * fix inside an already-tracked run is usable at all.
     */
    public val recoveryTrustM: Float
        get() = (
            recoveryTrustMeters ?: when (profile) {
                AccuracyProfile.STRICT -> STRICT_RECOVERY_M
                AccuracyProfile.BALANCED -> BALANCED_RECOVERY_M
                AccuracyProfile.RELAXED -> RELAXED_RECOVERY_M
                AccuracyProfile.CUSTOM -> BALANCED_RECOVERY_M
            }
            ).coerceAtMost(maxAccuracyM)

    internal fun validate(): List<String> = buildList {
        if (profile == AccuracyProfile.CUSTOM) {
            val custom = maxAccuracyMeters
            if (custom == null) {
                add("geolocation.accuracy.maxAccuracyMeters is required with AccuracyProfile.CUSTOM")
            } else if (custom.isNaN() || custom !in MIN_CUSTOM_M..MAX_CUSTOM_M) {
                add(
                    "geolocation.accuracy.maxAccuracyMeters ($custom) must be in " +
                        "$MIN_CUSTOM_M..$MAX_CUSTOM_M m",
                )
            }
        } else if (maxAccuracyMeters != null) {
            // Silently ignoring it is the failure this whole API exists to remove: the host
            // sets a number, nothing changes, and nothing says why.
            add(
                "geolocation.accuracy.maxAccuracyMeters only applies with AccuracyProfile.CUSTOM " +
                    "(profile is $profile)",
            )
        }

        recoveryTrustMeters?.let {
            if (it.isNaN() || it <= 0f) add("geolocation.accuracy.recoveryTrustMeters must be > 0")
        }
    }

    public companion object {
        /**
         * Urban-canyon strict. Below the ~25 m at which a fused fix stops being GNSS-led,
         * so what survives is satellite-quality or nothing.
         */
        public const val STRICT_MAX_M: Float = 20f

        /**
         * The engine default, and set from field data rather than taste: on the reference
         * drive the clean sections ran at 4-8 m and never exceeded 19.4 m at p90, while
         * every direction reversal in it sat above 20 m.
         */
        public const val BALANCED_MAX_M: Float = 30f

        /** For indoor, network-assisted or coverage-first work where a coarse point beats none. */
        public const val RELAXED_MAX_M: Float = 60f

        public const val STRICT_RECOVERY_M: Float = 15f
        public const val BALANCED_RECOVERY_M: Float = 25f
        public const val RELAXED_RECOVERY_M: Float = 40f

        /** Under 5 m nothing consumer-grade qualifies; over 500 m the ceiling is not a filter. */
        internal const val MIN_CUSTOM_M = 5f
        internal const val MAX_CUSTOM_M = 500f
    }
}

/**
 * Device-integrity policy — the second security layer, beside the license gate.
 *
 * **Release-only by construction.** Every probe is skipped and every policy ignored when
 * the host app is debuggable, exactly as the license gate is waived there. Development,
 * emulators and instrumentation runs are therefore unaffected without anyone having to
 * remember to turn something off — and, more importantly, without a "turn it off" switch
 * that could survive into a release build.
 *
 * Each policy is [IntegrityPolicy.WARN] or [IntegrityPolicy.BLOCK]; `WARN` still reports
 * the finding, stamps it on every point and uploads it. Blocking is a product decision
 * with a real cost — a false positive stops a field worker's tracking — so only the two
 * signals that have no innocent explanation block by default.
 *
 * @property accessibility Default [IntegrityPolicy.WARN], deliberately. Accessibility
 *   services are also how blind and motor-impaired users operate a phone; blocking on
 *   them by default would lock those users out of the host app entirely. System services
 *   never raise a finding, and [accessibilityAllowlist] covers the rest.
 * @property clock Default [IntegrityPolicy.WARN]. A traveller crossing time zones with a
 *   briefly stale clock is not an attacker.
 * @property accessibilityAllowlist Package names that never raise
 *   [com.field360.tracker.integrity.IntegritySignal.ACCESSIBILITY_SERVICE_ACTIVE].
 *   Packages installed as part of the
 *   system image are always allowed and need no entry here.
 * @property maxClockSkewMs Tolerated `|GNSS UTC − system clock|`. GNSS time comes from
 *   the satellite signal and cannot be set from the Settings app, which makes it the one
 *   trusted reference the SDK has offline.
 * @property recheckIntervalMs Re-evaluation cadence inside the health loop while a session
 *   is open. `0` disables periodic re-checks; `ready()` and `start()` still evaluate.
 */
@Serializable
public data class SecurityConfig(
    val enabled: Boolean = true,
    val accessibility: IntegrityPolicy = IntegrityPolicy.WARN,
    val developerMode: IntegrityPolicy = IntegrityPolicy.WARN,
    val hooking: IntegrityPolicy = IntegrityPolicy.BLOCK,
    val clock: IntegrityPolicy = IntegrityPolicy.WARN,
    val mockLocation: IntegrityPolicy = IntegrityPolicy.BLOCK,
    val accessibilityAllowlist: Set<String> = emptySet(),
    val maxClockSkewMs: Long = DEFAULT_MAX_CLOCK_SKEW_MS,
    val recheckIntervalMs: Long = DEFAULT_RECHECK_INTERVAL_MS,
) {
    internal fun validate(): List<String> = buildList {
        if (maxClockSkewMs < 0) add("security.maxClockSkewMs must be >= 0")
        if (recheckIntervalMs != 0L && recheckIntervalMs < MIN_RECHECK_INTERVAL_MS) {
            add(
                "security.recheckIntervalMs must be 0 (disabled) or >= $MIN_RECHECK_INTERVAL_MS ms — " +
                    "a tighter loop probes /proc on the service thread for no added signal",
            )
        }
    }

    public companion object {
        /** Two minutes. Wide enough for NTP drift and a slow GNSS lock, narrow enough to catch backdating. */
        public const val DEFAULT_MAX_CLOCK_SKEW_MS: Long = 120_000

        /** Fifteen minutes, matching the cadence at which a health loop can act without cost. */
        public const val DEFAULT_RECHECK_INTERVAL_MS: Long = 15 * 60_000

        internal const val MIN_RECHECK_INTERVAL_MS = 60_000L
    }
}

/** Named points on the accuracy meter. See [AccuracyConfig] for what each one governs. */
public enum class AccuracyProfile {
    /** 20 m moving ceiling, 15 m re-anchor bar. Sparser track, no zigzag. */
    STRICT,

    /** 30 m / 25 m — the engine's shipped defaults. */
    BALANCED,

    /** 60 m / 40 m. More points, visible wander in poor conditions. */
    RELAXED,

    /** Whatever [AccuracyConfig.maxAccuracyMeters] says. */
    CUSTOM,
}
