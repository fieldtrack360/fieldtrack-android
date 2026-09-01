package com.field360.tracker.domain.usecase

import android.content.Context
import com.field360.tracker.TrackerConfig
import com.field360.tracker.capture.CaptureGate
import com.field360.tracker.capture.FixIngestor
import com.field360.tracker.capture.LocationStreamController
import com.field360.tracker.capture.OneShotProvider
import com.field360.tracker.capture.TurnSource
import com.field360.tracker.domain.model.ErrorCode
import com.field360.tracker.domain.model.PermissionTier
import com.field360.tracker.domain.model.TrackSession
import com.field360.tracker.domain.model.TrackerEvent
import com.field360.tracker.domain.repository.ConfigRepository
import com.field360.tracker.domain.repository.SessionRepository
import com.field360.tracker.motion.ActivityRecognizer
import com.field360.tracker.motion.GyroTurnMonitor
import com.field360.tracker.motion.MotionController
import com.field360.tracker.motion.StepCorroborator
import com.field360.tracker.motion.StillnessMonitor
import com.field360.tracker.permission.PermissionManager
import com.field360.tracker.permission.ProviderStateMonitor
import com.field360.tracker.sdkLog
import com.field360.tracker.work.BackstopWorker
import com.field360.tracker.work.SyncScheduler
import com.field360.tracker.work.Watchdog
import com.field360.traker.geo.port.TrackLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Everything that has to come **up** for a session to actually record, in the order it has
 * to come up in.
 *
 * The mirror of [SessionTeardown], and extracted for the same reason: **two** callers need
 * it. `start()` is the obvious one. [ResumeCaptureUseCase] is the other, and it is the one
 * this class exists for.
 *
 * ### The hole this closes
 *
 * Every resurrection path in the SDK restored the *service* and nothing else —
 * `BootReceiver`, `RestoreWorker`, `BackstopWorker`, and `reviveServiceIfNeeded` all end in
 * `TrackingService.start`. But the capture pipeline is not in the service: it is
 * [FixIngestor] and [LocationStreamController] living in the process, started from
 * `StartTrackingUseCase` and nowhere else. So after an OEM killed the process — which on
 * OnePlus and Xiaomi is a matter of minutes with the screen off — a geofence or
 * activity-transition wake put the foreground service back with:
 *
 *  - the session still open on disk,
 *  - "Recording your location" back on screen,
 *  - `TrackingService.running` true, so `Watchdog` judged the service healthy,
 *  - `HealthLoop` ticking and reporting nothing wrong,
 *  - **no location request registered, and no ingest consumer running.**
 *
 * Zero points, for the rest of the drive, with every supervision layer saying healthy. The
 * 15-minute [BackstopWorker] fix did not help either: it calls `ingestor.offer`, and with
 * no consumer draining the channel that fix is dropped silently.
 *
 * Nothing here starts a service, and that is deliberate — the caller that needs one has
 * already started it, and the caller that runs *inside* it must not restart it.
 */
internal class CaptureLauncher(
    private val ingestor: FixIngestor,
    private val streamController: LocationStreamController,
    private val captureGate: CaptureGate,
    private val motionController: MotionController,
    private val stepCorroborator: StepCorroborator,
    private val stillnessMonitor: StillnessMonitor,
    private val activityRecognizer: ActivityRecognizer,
    private val gyroTurnMonitor: GyroTurnMonitor,
    private val oneShotProvider: OneShotProvider,
    private val watchdog: Watchdog,
    private val syncScheduler: SyncScheduler,
    private val context: Context,
    private val scope: CoroutineScope,
) {

    /**
     * Wires the ingest path, starts the consumer, registers the stream and arms the gate.
     *
     * Idempotent in the only sense that matters: [FixIngestor.start] and
     * [LocationStreamController.start] both cancel whatever they were running first, so a
     * second call re-arms rather than stacking. It is still the caller's job not to make
     * one — `start()` tears the previous session down first, and [ResumeCaptureUseCase]
     * checks [FixIngestor.isRunning].
     */
    suspend fun launch(session: TrackSession, config: TrackerConfig) {
        ingestor.mockPolicy = config.geolocation.mockLocationPolicy
        ingestor.persistRawFixes = config.persistence.persistRawFixes
        ingestor.rawRingCapacity = config.persistence.rawRingCapacity
        ingestor.persistRawPoints = config.persistence.persistRawPoints
        ingestor.rawPointCapacity = config.persistence.rawPointRingCapacity
        ingestor.bearingChangeCaptureDeg = config.motion.bearingChangeCaptureDeg
        ingestor.cornerAnchorCapture = config.motion.cornerAnchorCapture

        // Session-scoped sensor registration. Started here, torn down in stop() — a
        // pedometer left registered after a session is battery drain with nothing to
        // show for it, and that is the failure users blame the SDK for (EC-138).
        if (config.sensors.useStepCorroboration) {
            stepCorroborator.start(config.sensors)
            ingestor.stepsSinceLastPoint = stepCorroborator::consumeSteps
        }

        // The fourth witness against stationary drift, and the only one that measures the
        // device rather than its own estimate of where it is (EC-142). Off unless the host
        // asked for it; `ResolveConfigUseCase` has already turned the flag off on a device
        // with no accelerometer, so the availability check here is belt and braces rather
        // than the decision.
        val stillnessArmed = config.motion.suppressWhileStationary && stillnessMonitor.isAvailable
        if (stillnessArmed) {
            stillnessMonitor.start(config.motion.stillnessEscapeMin)
            ingestor.stillnessVeto = stillnessMonitor::isStill
        }

        // Motion transitions drive cadence and the wake paths, but never gate capture.
        // The upload nudge rides along here because this is the one place that knows a
        // point was both accepted and stored — which is exactly what `autoSync` means.
        // Throttled inside the scheduler, and a no-op when no host registered a trigger.
        ingestor.onAcceptedPoint = { point ->
            motionController.onAcceptedPoint(point)
            syncScheduler.onAcceptedPoint()
            // Restarts the stillness window on the same boundary the pedometer's tally
            // restarts on, so both witnesses always describe the same interval.
            if (stillnessArmed) stillnessMonitor.onPointStored()
        }

        // A label on the decision log, not an input to it. See `FixIngestor.motionState`.
        ingestor.motionState = motionController.motionState
        motionController.onMotionChange = { ingestor.motionState = it }
        // The third cadence tier: raw fixes feed turn detection, turn detection feeds the
        // sampling rate. A callback rather than an injected dependency because the stream
        // controller already depends on the ingestor (EC-45).
        ingestor.onTurnBurst = { streamController.setTurning(TurnSource.GNSS_BEARING, it) }
        wireObservedSpeed(gyroArmed = armGyroTurnPrediction(config))
        ingestor.start(session, scope)
        watchdog.reset()
        oneShotProvider.resetFailures()

        motionController.start(config)
        streamController.start(config, vehicular = false)

        // Armed **after** the stream, so its first evaluation judges a stream that exists.
        // A session opened while location was switched off is therefore suspended
        // immediately and resumes on its own when the switch comes back, rather than
        // holding a registration against a dead provider for the rest of the drive
        // (EC-06, EC-07).
        captureGate.arm(config)

        // Enrichment only: a label on the point and one extra fix at a motion change.
        // Denial degrades to speed + displacement rather than failing start() (EC-09).
        if (config.motion.activityRecognition) activityRecognizer.register()

        // The 15-minute safety net feeds the SAME ingestor, so it can never disagree
        // with the stream about where the user was last seen (SOURCE-AUDIT A3). `KEEP`
        // inside, so re-arming after a revival does not reset its clock.
        BackstopWorker.enqueue(context, config.service.backstopIntervalMin)
    }

    /**
     * Wires the predictive half of turn-burst sampling, when the device and the config
     * both allow it (EC-45d).
     *
     * Three conditions, and the order they are checked in says what each means.
     * `turnBurst` off means the host does not want the fast tier at all, so a predictor
     * for it is moot. `useGyroTurnPrediction` off means the host wants the tier but only
     * from GNSS — the conservative setting, and the one to reach for if a fleet's battery
     * budget is tight. A missing gyroscope or gravity source means the device cannot do
     * it, and that is not an error: `TurnDetector` alone is the behaviour every release
     * before this one shipped.
     *
     * @return whether the monitor was armed, so [wireObservedSpeed] knows whether the
     *   speed feed has a second consumer. It must not simply call `onSpeed` regardless:
     *   that method opens the gyroscope, and calling it on a host that turned gyro
     *   prediction off would register the sensor it declined.
     */
    private fun armGyroTurnPrediction(config: TrackerConfig): Boolean {
        if (!config.geolocation.turnBurst) return false
        if (!config.sensors.useGyroTurnPrediction) return false
        if (!gyroTurnMonitor.isAvailable) return false

        gyroTurnMonitor.onTurning = { turning ->
            streamController.setTurning(TurnSource.GYROSCOPE, turning)
            // The same signal, for a different decision: the stream uses it to sample
            // faster, the corner window uses it as direct evidence that a turn spans the
            // fix it is holding (EC-45d, EC-45e).
            ingestor.gyroTurning = turning
        }
        gyroTurnMonitor.start()
        return true
    }

    /**
     * Fans every fix's speed out to the two things that need it.
     *
     * Wired unconditionally: the cadence tier needs the same signal on every device — a
     * host with `turnBurst` off, or a phone with no gyroscope, still has a vehicular tier
     * to raise — and without this it would have had none, leaving the tier permanently off
     * instead of permanently on.
     */
    private fun wireObservedSpeed(gyroArmed: Boolean) {
        ingestor.onObservedSpeed = { speedMps ->
            streamController.onObservedSpeed(speedMps)
            if (gyroArmed) gyroTurnMonitor.onSpeed(speedMps)
        }
    }
}

/**
 * Restarts capture for a session that is still open on disk but has no pipeline behind it.
 *
 * Called from `TrackingService.startSupervision`, which is the one place every revival path
 * already converges on — `BootReceiver`, `RestoreWorker`, `BackstopWorker` and
 * `reviveServiceIfNeeded` all end in a start command on that service. Putting the resume
 * there rather than in each of them means a new wake path gets it for free, and none of
 * them can be the one that forgets.
 *
 * **A no-op in the healthy case**, which is most calls: `onStartCommand` re-arms
 * supervision on every start command, and the session's own pipeline is normally already
 * running. [FixIngestor.isRunning] is the test, and it is the same field that separates
 * "start() called twice" from "a session survived the process that was recording it".
 *
 * **Never opens or closes a session.** A session it cannot resume — permission revoked
 * while the process was dead, no persisted config — is left open and reported, exactly as
 * a mid-session revocation is (EC-07). Deciding a drive is over is the host's call.
 */
internal class ResumeCaptureUseCase(
    private val sessions: SessionRepository,
    private val configRepository: ConfigRepository,
    private val ingestor: FixIngestor,
    private val launcher: CaptureLauncher,
    private val permissions: PermissionManager,
    private val providerStateMonitor: ProviderStateMonitor,
    private val events: MutableSharedFlow<TrackerEvent>,
    private val logger: TrackLogger,
    /** The graph's `applyConfig` — see `StartTrackingUseCase`. */
    private val applyConfig: (TrackerConfig) -> Unit,
) {

    /** @return true if this call actually restarted a dead pipeline. */
    suspend operator fun invoke(): Boolean {
        if (ingestor.isRunning) return false
        val session = sessions.current() ?: return false

        // From disk, not from memory: in a process the OS built to deliver one broadcast,
        // `ready()` may not have run yet and `configStore.cached` is null. The persisted
        // config is what the session was opened with, which is the one that should
        // continue recording it.
        val config = configRepository.load()
        if (config == null) {
            events.tryEmit(
                TrackerEvent.Diagnostic(
                    "session ${session.id} is open but no persisted config exists; capture not resumed",
                ),
            )
            return false
        }

        applyConfig(config)

        // Both, and in this order. `start` is idempotent and is what registers the AppOps
        // watcher and the PROVIDERS_CHANGED receiver in a process that has none; `refresh`
        // is what stops `CaptureGate` judging the device from a constructor default and
        // suspending a stream that was never in an outage.
        providerStateMonitor.start()
        providerStateMonitor.refresh()

        if (permissions.tier() == PermissionTier.NONE) {
            // The session stays open and the host is told. `CaptureGate` cannot report
            // this one — it is armed by the launch below, which is exactly what is being
            // skipped.
            events.tryEmit(
                TrackerEvent.Error(
                    ErrorCode.PERMISSION_DENIED,
                    "Session ${session.id} is open but location permission is not granted; " +
                        "capture cannot resume until it is",
                ),
            )
            return false
        }

        launcher.launch(session, config)

        sdkLog { logger.w(TAG, "Capture resumed for session ${session.id} after process death") }
        events.tryEmit(
            TrackerEvent.Diagnostic(
                "capture resumed for session ${session.id} after the process was killed",
            ),
        )
        // The host's `TrackerState` is rebuilt from scratch in a revived process, so
        // without this `isTracking` reads false while the SDK is actively recording.
        events.tryEmit(TrackerEvent.EnabledChange(enabled = true))
        return true
    }

    private companion object {
        const val TAG = "ResumeCapture"
    }
}
