package com.field360.tracker.domain.usecase

import android.content.Context
import com.field360.tracker.LocationProviderType
import com.field360.tracker.TrackerConfig
import com.field360.tracker.TrackingMode
import com.field360.tracker.capture.CaptureGate
import com.field360.tracker.capture.FixIngestor
import com.field360.tracker.capture.LocationStreamController
import com.field360.tracker.data.location.LocationSource
import com.field360.tracker.data.repository.ConfigStore
import com.field360.tracker.domain.model.ErrorCode
import com.field360.tracker.domain.model.LocationAccuracy
import com.field360.tracker.domain.model.PermissionTier
import com.field360.tracker.domain.model.TrackerEvent
import com.field360.tracker.domain.model.TrackerResult
import com.field360.tracker.domain.model.TrackSession
import com.field360.tracker.domain.repository.ConfigRepository
import com.field360.tracker.domain.repository.SessionRepository
import com.field360.traker.geo.model.MockPolicy
import com.field360.traker.geo.model.MotionState
import com.field360.tracker.integrity.IntegrityPolicy
import com.field360.tracker.motion.DeviceSensors
import com.field360.tracker.motion.MotionQuality
import com.field360.tracker.motion.SensorProbe
import com.field360.tracker.motion.ActivityRecognizer
import com.field360.tracker.motion.GyroTurnMonitor
import com.field360.tracker.motion.MotionController
import com.field360.tracker.motion.SignificantMotionWake
import com.field360.tracker.motion.StepCorroborator
import com.field360.tracker.motion.StillnessMonitor
import com.field360.tracker.permission.PermissionManager
import com.field360.tracker.permission.ProviderStateMonitor
import com.field360.tracker.service.TrackingService
import com.field360.tracker.work.BackstopWorker
import com.field360.tracker.work.RestoreWorker
import com.field360.tracker.work.SyncScheduler
import com.field360.tracker.work.Watchdog
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Opens a session and starts the capture pipeline.
 *
 * **Every start is a new session, and only one session is ever active.** Whatever was
 * running before this call — a live pipeline, a session left open by process death, a
 * foreground service the host never stopped, the previous run's workers and registered
 * sensors — is torn down through [SessionTeardown] before the new session row is written.
 * Two runs therefore never share an id, and there is never a second service.
 *
 * That replaces the earlier idempotent behaviour, where a `start()` with the pipeline live
 * returned the session already being written to (EC-72). The trade is deliberate and was
 * chosen explicitly: a double tap on Start now produces two session ids. Hosts that care
 * should gate the control on `TrackerState.isTracking`, as `sample-android` does — the
 * previous design bought double-tap safety at the price of a new session silently
 * inheriting the old one's service, supervision loop and config.
 *
 * The teardown runs **after** every gate has passed, never before: a `start()` that is
 * about to fail on a missing permission must not take a healthy running session down
 * with it.
 */
public class StartTrackingUseCase internal constructor(
    private val sessions: SessionRepository,
    private val locationSource: LocationSource,
    private val captureGate: CaptureGate,
    private val teardown: SessionTeardown,
    private val providerStateMonitor: ProviderStateMonitor,
    private val configStore: ConfigStore,
    private val permissions: PermissionManager,
    private val context: Context,
    private val events: MutableSharedFlow<TrackerEvent>,
    /**
     * Everything that has to come up for a session to record, shared with
     * [ResumeCaptureUseCase] — see [CaptureLauncher] for why that sharing is the point.
     */
    private val launcher: CaptureLauncher,
    /**
     * Applies the parts of the config that are wired rather than passed: the provider
     * selection [LocationSource] routes on, and the engine constants the accuracy meter
     * moves. A lambda supplied by the graph, because both targets sit in layers this use
     * case must not import.
     *
     * Runs first, before the availability check — "is a provider available" is a question
     * about the provider the host chose, and answering it about the previous one is how a
     * GPS-only host on a Play-Services-free device gets told to install Play Services.
     */
    private val applyConfig: (TrackerConfig) -> Unit,
) {

    public suspend operator fun invoke(
        config: TrackerConfig,
        tag: String? = null,
    ): TrackerResult<TrackSession> {
        applyConfig(config)

        // Re-read the device before any gate consults it. `ProviderStateMonitor` is
        // broadcast-driven, and a broadcast can be missed: a context-registered receiver
        // is unregistered when the process dies, and `PROVIDERS_CHANGED` fired in that
        // window is simply never seen. The cached state then still says the GPS is off
        // long after the user switched it back on, and every gate reading it — plus the
        // `providerFlags` stamped on each stored point — inherits the stale answer. Cheap:
        // three binder reads, once per session. `getCurrentLocation()` already did this;
        // `start()` did not, which is the asymmetry that made "it works for a one-shot but
        // not for a session" reproducible.
        providerStateMonitor.refresh()

        // EC-01: no permission means a typed error, never a SecurityException and never
        // a service that starts and silently produces nothing.
        when (permissions.tier()) {
            PermissionTier.NONE ->
                return TrackerResult.Error(ErrorCode.PERMISSION_DENIED, "Location permission not granted")

            PermissionTier.FOREGROUND_ONLY -> {
                // Degrade rather than refuse (A16, EC-03). The session opens and tracks
                // while the app is visible; the host is told why background is missing.
                events.tryEmit(
                    TrackerEvent.Error(
                        ErrorCode.BACKGROUND_PERMISSION_MISSING,
                        "Tracking foreground-only; grant \"Allow all the time\" for background",
                    ),
                )
            }

            PermissionTier.FULL -> Unit
        }

        // EC-02: a 1-3 km error circle defeats every gate in the pipeline.
        if (permissions.accuracy() == LocationAccuracy.APPROXIMATE &&
            config.geolocation.trackingMode != TrackingMode.MOTION_ONLY
        ) {
            return TrackerResult.Error(
                ErrorCode.COARSE_ONLY,
                "Approximate location cannot support ${config.geolocation.trackingMode}",
            )
        }

        if (!locationSource.isAvailable()) {
            // EC-19: no Play Services (Huawei, AOSP). A host on such a device now has a
            // real remedy — `LocationProviderType.GPS_ONLY` and friends run on the platform
            // LocationManager and need no Play Services at all — so the message names it
            // rather than reporting a dead end.
            val message = when (config.geolocation.providerType) {
                LocationProviderType.FUSED ->
                    "Fused provider unavailable; set geolocation.providerType to GPS_ONLY " +
                        "or NETWORK_ONLY to track without Play Services"

                else ->
                    "${config.geolocation.providerType} provider is not present on this device"
            }
            events.tryEmit(TrackerEvent.Error(ErrorCode.PLAY_SERVICES_UNAVAILABLE, message))
            return TrackerResult.Error(ErrorCode.PLAY_SERVICES_UNAVAILABLE, message)
        }

        // One session at a time, enforced here rather than assumed.
        //
        // This is the only place that can guarantee it: the previous run may have been a
        // live pipeline, a crash leftover the host was already told about via
        // `SessionInterrupted` (EC-66), or a service that outlived its session entirely
        // after a sticky restart. All three end the same way, and all three end *now* —
        // before the new row exists, so no window has two open sessions in it.
        //
        // Tearing down the service matters as much as closing the row. A surviving
        // instance answers the `startForegroundService` below on its existing
        // `onStartCommand`, and every value it supervises with — the health-loop cadence,
        // the force-capture config, the watchdog thresholds — was read when *that* session
        // started. A new session would silently run on the old session's configuration.
        val superseded = teardown()
        if (superseded != null) {
            events.tryEmit(
                TrackerEvent.Diagnostic(
                    "session ${superseded.id} superseded by a new start(); its service and " +
                        "workers were stopped",
                ),
            )
        }

        val session = sessions.open(tag, configStore.encode(config))

        // The whole pipeline, in one call, shared with the resume path. Arming the gate is
        // the last thing it does, which is why `isCapturing` is readable immediately after
        // this returns: a session opened while location was switched off is already
        // reported as suspended rather than claiming a capture that is not running.
        //
        // Deliberately not a start() gate: refusing to open a session because the GPS is
        // off would strand a host that starts tracking from a background trigger — the
        // session is the record, and the record should exist with a documented gap in it
        // rather than not exist at all.
        launcher.launch(session, config)

        TrackingService.start(context, config.service)

        events.tryEmit(TrackerEvent.EnabledChange(enabled = true))
        return TrackerResult.Ok(session)
    }
}

/**
 * Everything that has to come down when a session ends, in the order it has to come down in.
 *
 * Extracted because **two** callers need it, not one. `stop()` is the obvious caller;
 * `start()` is the other, because only one session may be active at a time and a new one
 * must not inherit the previous run's service instance, its supervision loop, its workers
 * or its registered sensors. Before this existed, `start()` closed a leftover session row
 * and left everything attached to it running.
 *
 * A single definition rather than two similar ones on purpose: a teardown that is nearly
 * the same in two places is a teardown that will differ in one of them after the next edit,
 * and the thing that leaks is a foreground service the user can see.
 */
internal class SessionTeardown(
    private val sessions: SessionRepository,
    private val ingestor: FixIngestor,
    private val streamController: LocationStreamController,
    private val captureGate: CaptureGate,
    private val motionController: MotionController,
    private val stepCorroborator: StepCorroborator,
    private val stillnessMonitor: StillnessMonitor,
    private val activityRecognizer: ActivityRecognizer,
    private val significantMotion: SignificantMotionWake,
    private val gyroTurnMonitor: GyroTurnMonitor,
    private val watchdog: Watchdog,
    private val context: Context,
) {

    /**
     * Brings down whatever is running and closes any open session.
     *
     * Safe with nothing running, and that case is not a no-op: a service left alive with no
     * session — a sticky restart, an FGS the host never stopped — is exactly what this has
     * to be able to kill.
     *
     * @return the session that was closed, or `null` if none was open.
     */
    suspend operator fun invoke(): TrackSession? {
        val current = sessions.current()

        // Order matters: stop feeding the channel, then close the session, so no point
        // is written after the session it belongs to has ended (EC-73).
        //
        // The gate comes down first of all: it is the one component that can *restart* the
        // stream, and a provider recovery landing between `release()` and `ingestor.stop()`
        // would re-register a request for a session about to close.
        captureGate.disarm()
        streamController.release()
        motionController.stop()
        ingestor.onAcceptedPoint = null
        ingestor.onTurnBurst = null
        ingestor.onObservedSpeed = null
        ingestor.stop()

        // Sensors and system-registered wakes come down in the SAME teardown as the
        // location stream. Anything left armed here is silent battery drain (EC-138).
        stepCorroborator.stop()
        // Unconditional, like the gyroscope below: a config that left the stage off *this*
        // session says nothing about whether the previous one left an accelerometer
        // registered. The veto is cleared too, so a session started later with the stage
        // off cannot inherit a lambda reading a stopped monitor (EC-142).
        stillnessMonitor.stop()
        ingestor.stillnessVeto = null
        motionController.onMotionChange = null
        ingestor.motionState = MotionState.STOPPED
        // Unconditional, unlike the arming side: a config that disabled prediction *this*
        // session says nothing about whether the previous one left a gyroscope open.
        gyroTurnMonitor.stop()
        gyroTurnMonitor.onTurning = null
        ingestor.gyroTurning = false
        activityRecognizer.unregister()
        significantMotion.disarm()
        watchdog.reset()

        // Both workers, and both before the session row is closed.
        //
        // `RestoreWorker` is the one that used to bite: it re-promotes the service whenever
        // it finds an open session, so an expedited request already in flight would restart
        // the service on its way out and leave the notification up until the next health
        // tick — up to `healthLoopMs` later — noticed there was nothing to supervise.
        BackstopWorker.cancel(context)
        RestoreWorker.cancel(context)

        // Closed BEFORE the service is told to stop, which is the reverse of the original
        // order and the reason a stopped session could come back. Every resurrection path
        // in the SDK — `RestoreWorker`, `BootReceiver`, the health loop — gates on
        // `sessions.current()`, so closing first means none of them can act on the window
        // between the two calls. The ingest path is already stopped above, so EC-73 still
        // holds.
        val closed = current?.let { sessions.close(it.id) }

        TrackingService.stop(context)
        return closed
    }
}

/**
 * Closes the session and tears the pipeline down.
 *
 * `Ok(null)` when no session was open — but the teardown still runs, because "no session"
 * and "nothing running" are different states and the second is not implied by the first
 * (EC-74).
 */
public class StopTrackingUseCase internal constructor(
    private val teardown: SessionTeardown,
    private val syncScheduler: SyncScheduler,
    private val events: MutableSharedFlow<TrackerEvent>,
) {
    public suspend operator fun invoke(): TrackerResult<TrackSession?> {
        val closed = teardown()

        // After the close, and after the backstop is already cancelled: every supervision
        // path in core has now stopped, so this is the last chance to leave a
        // network-constrained drain enqueued for anything the session recorded offline.
        // WorkManager persists it, so it survives the process being killed and fires when
        // connectivity returns (G-4).
        syncScheduler.onSessionClosed()

        // Only when something actually ended. A stop() called against an already-stopped
        // SDK still sweeps up a stale service, but announcing a transition that did not
        // happen would have hosts mirroring `isTracking` react to nothing.
        if (closed != null) events.tryEmit(TrackerEvent.EnabledChange(enabled = false))
        return TrackerResult.Ok(closed)
    }
}

/**
 * Resolves the effective config at startup and reports where each value came from.
 *
 * The `reset` flag is the classic footgun: with `reset = false` the supplied config is
 * ignored after the first launch, so a developer edits constants, rebuilds, and nothing
 * changes. Logging the effective source makes that answerable from logcat instead of a
 * support thread (SDK-COMPARISON §5).
 */
public class ResolveConfigUseCase internal constructor(
    private val repository: ConfigRepository,
    private val sensorProbe: SensorProbe,
    private val events: MutableSharedFlow<TrackerEvent>,
    /**
     * `IntegrityEnvironment.isWaived` — a lambda rather than a `Context` so this class stays
     * testable on the JVM, and so there is still exactly one definition of "debug build" in
     * the SDK. See the mock-location guard in [invoke].
     */
    private val isDebuggable: () -> Boolean = { false },
) {
    public suspend operator fun invoke(supplied: TrackerConfig): Result {
        val persisted = repository.load()
        val resolved = when {
            supplied.reset -> supplied
            persisted != null -> persisted
            else -> supplied
        }

        val errors = resolved.validate()
        if (errors.isNotEmpty()) return Result(resolved, errors, ConfigSource.SUPPLIED, null)

        // Act on the hardware, don't merely report it. Running a motion-gated design on
        // a device that cannot support motion detection produces gaps the user blames on
        // the SDK, so degrade the mode and say which sensors are missing (EC-137).
        //
        // Both tiers below are acted on, which is the difference between this and the
        // incumbent's diagnostic-only `getSensors()` — see `SensorProbe`. `POOR` changes
        // the mode; `DEGRADED` leaves the mode alone and widens the stop timeout instead.
        val sensors = sensorProbe.probe()
        val effective = when {
            sensors.motionQuality == MotionQuality.POOR &&
                resolved.geolocation.trackingMode != TrackingMode.CONTINUOUS -> {
                events.tryEmit(
                    TrackerEvent.Error(
                        ErrorCode.MOTION_DETECTION_DEGRADED,
                        "motionQuality=POOR (accelerometer=${sensors.accelerometer}, " +
                            "gyroscope=${sensors.gyroscope}, significantMotion=${sensors.significantMotion}, " +
                            "stepDetector=${sensors.stepDetector}); " +
                            "forcing CONTINUOUS in place of ${resolved.geolocation.trackingMode} — " +
                            // The consequence, not just the decision. A host that chose
                            // MOTION_ONLY chose it for battery, and this override hands it
                            // the most expensive mode short of navigation. Saying only
                            // "forcing CONTINUOUS" turns that into a battery complaint
                            // nobody can trace back to here.
                            "the location stream will now run while stationary, which costs " +
                            "materially more battery than the requested mode",
                    ),
                )
                resolved.copy(
                    geolocation = resolved.geolocation.copy(trackingMode = TrackingMode.CONTINUOUS),
                )
            }

            // A device with an accelerometer but no gyroscope and no hardware trigger
            // detects a stop later and less certainly than one with both. Widening the
            // timeout trades stop *precision* for not declaring a stop that did not
            // happen — the cheaper error of the two, because a late stop costs a few extra
            // fixes and a false stop costs the rest of the trip.
            //
            // Applied in every mode, not only the motion-gated ones: the timeout governs
            // how long the SDK waits before believing a stop, and that belief drives the
            // cadence tiers and `stopOnStationary` regardless of which mode is running.
            sensors.motionQuality == MotionQuality.DEGRADED -> {
                val widened = resolved.motion.stopTimeoutMin * DEGRADED_STOP_TIMEOUT_FACTOR
                // Announced for the same reason the mode override is: a config the SDK
                // rewrote behind the host's back and never mentioned is the failure this
                // whole path exists to avoid. Diagnostic rather than Error — nothing is
                // wrong, and nothing the host did needs correcting.
                events.tryEmit(
                    TrackerEvent.Diagnostic(
                        "motionQuality=DEGRADED (gyroscope=${sensors.gyroscope}, " +
                            "significantMotion=${sensors.significantMotion}, " +
                            "stepDetector=${sensors.stepDetector}); " +
                            "motion.stopTimeoutMin widened " +
                            "${resolved.motion.stopTimeoutMin} -> $widened min",
                    ),
                )
                resolved.copy(motion = resolved.motion.copy(stopTimeoutMin = widened))
            }

            else -> resolved
        }

        // A stage the host asked for that this device cannot serve. Turned off here rather
        // than left to fail open in `StillnessMonitor`, so `Tracker.config` reports what is
        // actually running — a flag reading `true` while nothing implements it is the
        // defect this whole feature was written next to (EC-142).
        val gated = if (effective.motion.suppressWhileStationary && !sensors.accelerometer) {
            events.tryEmit(
                TrackerEvent.Diagnostic(
                    "motion.suppressWhileStationary requires an accelerometer and this device " +
                        "reports none; the stage is off and stationary points will be filtered " +
                        "by the acceptance pipeline alone",
                ),
            )
            effective.copy(motion = effective.motion.copy(suppressWhileStationary = false))
        } else {
            effective
        }

        // Two settings that must not be able to contradict each other: `security.mockLocation
        // = BLOCK` means the SDK refuses to run on a device with mock locations, so it cannot
        // also be storing mock fixes because `mockLocationPolicy` was left at FLAG. The
        // stricter of the two wins, and it wins silently — a validation error here would fail
        // `ready()` over a combination the SDK can resolve correctly on its own.
        //
        // A debuggable host is exempt, and this is the same waiver the integrity layer itself
        // takes (`IntegrityEnvironment.isWaived`), applied to the one place that was still
        // enforcing mock policy behind its back. Both defaults are strict — `mockLocation` is
        // BLOCK — so before this, a developer driving a fake route through the emulator got a
        // silent, total data loss: `FixMapper` dropped every fix, nothing reached the
        // database, and no event said why. Release builds are untouched; a repackaged APK
        // claiming `debuggable` is the case already covered in `IntegrityProbe`'s KDoc.
        val waiveMock = isDebuggable()
        val guarded = if (!waiveMock &&
            gated.security.mockLocation == IntegrityPolicy.BLOCK &&
            gated.geolocation.mockLocationPolicy != MockPolicy.REJECT
        ) {
            gated.copy(
                geolocation = gated.geolocation.copy(mockLocationPolicy = MockPolicy.REJECT),
            )
        } else {
            gated
        }

        repository.save(guarded)
        val source = when {
            supplied.reset -> ConfigSource.SUPPLIED
            persisted != null -> ConfigSource.PERSISTED
            else -> ConfigSource.DEFAULT
        }
        return Result(guarded, emptyList(), source, sensors)
    }

    public data class Result(
        val config: TrackerConfig,
        val validationErrors: List<String>,
        val source: ConfigSource,
        val sensors: DeviceSensors?,
    )

    public enum class ConfigSource { DEFAULT, PERSISTED, SUPPLIED }

    private companion object {
        /**
         * How much `motion.stopTimeoutMin` is widened on `MotionQuality.DEGRADED`.
         *
         * Doubling rather than a fixed number of minutes, because the host's value is a
         * statement about its own use case — a courier stopping for 90 seconds and a
         * survey rig parked for an hour want different timeouts, and a flat `+10` would
         * be most of the first and none of the second. The factor keeps the host's
         * intent and only makes the SDK slower to believe a stop it cannot corroborate.
         */
        const val DEGRADED_STOP_TIMEOUT_FACTOR = 2
    }
}
