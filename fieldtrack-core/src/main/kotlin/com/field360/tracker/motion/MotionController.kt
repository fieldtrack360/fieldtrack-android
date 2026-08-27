package com.field360.tracker.motion

import com.field360.tracker.TrackerConfig
import com.field360.tracker.sdkLog
import com.field360.tracker.domain.model.TrackerEvent
import com.field360.tracker.domain.model.TrackerGeofence
import com.field360.traker.geo.model.ActivityType
import com.field360.traker.geo.model.MotionState
import com.field360.traker.geo.model.MovementStatus
import com.field360.traker.geo.model.TrackPoint
import com.field360.traker.geo.motion.MotionEvent
import com.field360.traker.geo.motion.MotionStateMachine
import com.field360.traker.geo.port.Clock
import com.field360.traker.geo.port.TrackLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

/**
 * The Android side of motion detection: owns the pure [MotionStateMachine]'s state and
 * turns its transitions into hardware actions.
 *
 * The division is deliberate. Every *decision* lives in `fieldtrack-geo` where it is
 * JVM-testable; this class only arms sensors, registers fences and changes cadence
 * (PLAN.md §3 invariant 1).
 *
 * Events arrive from four independent places — the ingest coroutine, an activity-
 * recognition broadcast, a sensor-hub interrupt on a hardware thread, and a geofence
 * broadcast. They are funnelled through one [Channel] with a single consumer, the same
 * shape as [com.field360.tracker.capture.FixIngestor], so there is no interleaving and
 * no lock. That is precisely what the reference's static, non-atomically-updated motion
 * state got wrong (SOURCE-AUDIT A6).
 */
internal class MotionController(
    private val machine: MotionStateMachine,
    private val streamController: CaptureStream,
    private val significantMotion: MotionWakeSource,
    private val stationaryFence: GeofenceRegistrar,
    private val clock: Clock,
    private val events: MutableSharedFlow<TrackerEvent>,
    private val logger: TrackLogger,
    private val scope: CoroutineScope,
) {

    private val inbox = Channel<MotionEvent>(
        capacity = INBOX_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private var consumerJob: Job? = null
    private var state = MotionStateMachine.State()
    private var config: TrackerConfig? = null
    private var lastPoint: TrackPoint? = null

    /**
     * Whether the session-opening anchor fence has been written yet — see
     * [armAnchorFenceOnce]. Per session, so it is cleared in both [start] and [stop].
     */
    private var anchorFenceArmed = false

    val motionState: MotionState get() = state.motion

    fun start(config: TrackerConfig) {
        this.config = config
        state = MotionStateMachine.State()
        lastPoint = null
        anchorFenceArmed = false

        consumerJob?.cancel()
        consumerJob = scope.launch {
            for (event in inbox) handle(event)
        }

        // Armed immediately: a session that starts with the user already parked still
        // needs a wake path, and significant motion is the one needing no permission
        // and no Play Services (EC-132).
        if (config.sensors.useSignificantMotion && significantMotion.isAvailable) {
            armSignificantMotion()
        }
    }

    fun stop() {
        consumerJob?.cancel()
        consumerJob = null
        significantMotion.disarm()
        config?.let { stationaryFence.unregister(it.motion.stationaryGeofenceId) }
        config = null
        state = MotionStateMachine.State()
        lastPoint = null
        anchorFenceArmed = false
    }

    /** Fed for every point the pipeline accepted. */
    fun onAcceptedPoint(point: TrackPoint) {
        lastPoint = point
        armAnchorFenceOnce(point)
        offer(
            MotionEvent.AcceptedFix(
                latitude = point.latitude,
                longitude = point.longitude,
                isMoving = point.movementStatus == MovementStatus.MOVING,
            ),
        )
    }

    /**
     * Registers the wake fence on the FIRST accepted fix of the session, rather than
     * waiting for [onEnterStationary].
     *
     * The window this closes is the opening minutes of a session, and it was total. A
     * process killed there had **nothing** registered with the operating system: the
     * fence is written on the STATIONARY transition, which needs an accepted fix *and*
     * `stopTimeoutMin` on top of it — five minutes by default — and significant motion is
     * a `TriggerEventListener` living in this process, so it dies with it. Activity
     * recognition is the only other system registration, and this SDK's own notes record
     * whole drives reported `STILL` on OnePlus and Xiaomi under battery saver. So for the
     * first five minutes of every session there was no reliable way back in.
     *
     * A system geofence has neither problem: Play Services holds it, it outlives the
     * process, and being woken by one puts the app in the API 31+ allowlist for starting
     * a foreground service from the background — which is what makes
     * `reviveServiceIfNeeded` legal on that path.
     *
     * Deliberately not re-armed after [onEnterMoving] unregisters it. Through a drive the
     * service is alive and activity transitions are frequent; holding a fence the user is
     * actively leaving is the battery cost EC-138 is about. This covers the gap before
     * the first transition, and hands back to the existing behaviour after it.
     */
    private fun armAnchorFenceOnce(point: TrackPoint) {
        val active = config ?: return
        if (anchorFenceArmed) return
        // Set before the call, not after: `register()` is fire-and-observe and a failure
        // is reported through events, so retrying it on every accepted fix would turn a
        // device with geofencing unavailable into a registration attempt per fix.
        anchorFenceArmed = true

        stationaryFence.register(
            TrackerGeofence(
                id = active.motion.stationaryGeofenceId,
                latitude = point.latitude,
                longitude = point.longitude,
                radiusM = active.motion.stationaryRadiusM,
                onEnterEvent = active.motion.stationaryGeofenceOnEnterEvent,
                onExitEvent = active.motion.stationaryGeofenceOnExitEvent,
            ),
        )
    }

    /** AR is enrichment: it may accelerate a transition, never veto one (EC-53). */
    fun onActivityTransition(activity: ActivityType) = offer(MotionEvent.ActivityEnter(activity))

    fun onStationaryFenceExit() = offer(MotionEvent.StationaryFenceExit)

    fun onChangePace(moving: Boolean) = offer(MotionEvent.ChangePace(moving))

    /** Drives the stop timeout and any deferred move; called from the health loop. */
    fun tick() = offer(MotionEvent.Tick)

    private fun offer(event: MotionEvent) {
        inbox.trySend(event)
    }

    private fun armSignificantMotion() {
        // Fires on a hardware callback thread — do nothing there but enqueue.
        significantMotion.arm { offer(MotionEvent.SignificantMotion) }
    }

    private fun handle(event: MotionEvent) {
        val active = config ?: return

        val transition = machine.onEvent(state, event, clock.elapsedRealtimeNanos())
        state = transition.state

        // Emitted only on a real transition, so changePace(true) while already moving
        // is a genuine no-op (EC-59).
        val changedTo = transition.changedTo ?: return

        sdkLog { logger.d(TAG, "Motion -> $changedTo") }
        events.tryEmit(TrackerEvent.MotionChange(changedTo, lastPoint))

        when (changedTo) {
            MotionState.MOVING -> onEnterMoving(active)
            MotionState.STATIONARY -> onEnterStationary(active)
            // Cadence only — no wake path is armed or disarmed here, because the machine
            // has not decided this is a stop. `STATIONARY` is where that is committed to.
            MotionState.STOP_PENDING -> streamController.onStopPending()
            MotionState.STOPPED -> Unit
        }
    }

    private fun onEnterMoving(config: TrackerConfig) {
        // Wake paths that only make sense while parked come down; leaving a geofence and
        // a trigger sensor registered through a drive is pure battery cost (EC-138).
        significantMotion.disarm()
        stationaryFence.unregister(config.motion.stationaryGeofenceId)
        // Note what is NOT here: `setVehicular(true)`.
        //
        // Moving is not the same fact as vehicular, and treating them as one gave every
        // pedestrian session `vehicularIntervalMs` — 12 s against a 60 s base, five times
        // the fix rate, for a walker whose track needs nothing of the kind.
        // `GeolocationConfig.vehicularIntervalMs` has always documented itself as the tier
        // used "once fixes report vehicle speed"; the tier is now raised from that speed,
        // in `LocationStreamController.onObservedSpeed`.
        //
        // `onMoving` still restores a tier parked by a `STOP_PENDING`, so pulling away
        // from a junction does not wait for the next fix to measure speed again.
        streamController.onMoving()
    }

    private fun onEnterStationary(config: TrackerConfig) {
        streamController.onStationary()

        if (config.sensors.useSignificantMotion && significantMotion.isAvailable) {
            armSignificantMotion()
        }

        // A system-registered fence survives process death — the one wake path that
        // still works after an OEM battery manager kills us.
        lastPoint?.let { point ->
            stationaryFence.register(
                TrackerGeofence(
                    id = config.motion.stationaryGeofenceId,
                    latitude = point.latitude,
                    longitude = point.longitude,
                    radiusM = config.motion.stationaryRadiusM,
                    onEnterEvent = config.motion.stationaryGeofenceOnEnterEvent,
                    onExitEvent = config.motion.stationaryGeofenceOnExitEvent,
                ),
            )
        }
    }

    private companion object {
        const val TAG = "MotionController"
        const val INBOX_CAPACITY = 64
    }
}
