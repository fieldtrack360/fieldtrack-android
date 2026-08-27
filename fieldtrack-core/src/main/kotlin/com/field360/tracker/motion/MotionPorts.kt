package com.field360.tracker.motion

import com.field360.tracker.domain.model.TrackerGeofence

/**
 * The seams between motion *decisions* and the Android hardware that serves them.
 *
 * `MotionController` decides; these three interfaces do. Depending on abstractions
 * rather than on `SensorManager` and `GeofencingClient` directly is what makes the
 * controller's wiring testable at all — and the wiring is where the interesting bugs
 * live, since the state machine underneath is already covered in `fieldtrack-geo`.
 *
 * All three are genuinely optional at runtime: the sensor may not exist, geofence
 * registration fails for real reasons, and `MOTION_ONLY` deliberately stops the stream.
 * Each implementation degrades rather than throwing.
 */

/**
 * A hardware wake path out of STATIONARY.
 *
 * Implemented by [SignificantMotionWake]. Trigger sensors are one-shot by contract, so
 * an implementation must re-arm itself after firing (EC-132).
 */
internal interface MotionWakeSource {
    val isAvailable: Boolean
    fun arm(onMotion: () -> Unit)
    fun disarm()
}

/**
 * A system-registered fence around the stop anchor.
 *
 * Implemented by [StationaryFence]. Registration genuinely fails — GMS errors, the
 * 100-geofence per-app cap — and an implementation must degrade to the heartbeat path
 * rather than propagate (EC-58).
 */
internal interface GeofenceRegistrar {
    fun register(geofence: TrackerGeofence): Boolean
    fun unregister(id: String): Boolean
}

/**
 * Yaw rate about the world vertical, in degrees per second.
 *
 * Implemented by [GyroscopeYawSource]. A port rather than a direct `SensorManager` call
 * because the interesting logic — when to listen at all, and what counts as a turn — is
 * what needs testing, and none of it needs a gyroscope to test
 * ([com.field360.traker.geo.motion.GyroTurnGate], [GyroTurnMonitor]).
 *
 * Sign is preserved (positive and negative are the two directions of turn) and callers are
 * free to ignore it. Devices without a gyroscope report `isAvailable = false` and every
 * other method is a no-op, so the caller needs no capability branch of its own.
 */
internal interface YawRateSource {
    val isAvailable: Boolean

    /**
     * @param onYawRate called on whatever thread the sensor stack delivers on — the main
     *   looper, in the Android implementation. Implementations must not stamp a time onto
     *   the sample: the caller owns the clock, so that a replayed fixture and a live drive
     *   run through identical arithmetic.
     */
    fun start(onYawRate: (Float) -> Unit)

    fun stop()
}

/**
 * The location stream, as the motion layer sees it.
 *
 * Implemented by [com.field360.tracker.capture.LocationStreamController]. Narrow on
 * purpose: motion may change *cadence* and, in `MOTION_ONLY`, whether the stream runs
 * at all — but it can never gate capture itself, because activity recognition is wrong
 * often enough to lose whole trips (EC-53).
 */
internal interface CaptureStream {
    fun onMoving()

    /**
     * A stop the machine is not yet committed to — a traffic light or a real parking,
     * indistinguishable at this point.
     *
     * The cadence answer is the same either way: nothing needs vehicular sampling from a
     * vehicle that has stopped moving. The tier is parked rather than dropped, so pulling
     * away restores it on the [onMoving] transition instead of waiting for a fix at the
     * base interval to measure speed again (EC-56).
     */
    fun onStopPending()

    fun onStationary()
    fun setVehicular(vehicular: Boolean)
}
