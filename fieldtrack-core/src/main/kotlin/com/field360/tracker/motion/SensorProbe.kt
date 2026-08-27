package com.field360.tracker.motion

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import com.field360.tracker.permission.PermissionManager

/**
 * What motion hardware this device actually has, and what that means for us.
 *
 * The incumbent's `getSensors()` is a pure diagnostic: it tells you motion detection
 * *will be* inaccurate and then changes nothing. Here the answer feeds
 * [MotionQuality], and `POOR` forces `CONTINUOUS` mode — running a motion-gated design
 * on hardware that cannot support it produces random gaps the user blames on the SDK
 * (SDK-COMPARISON §6.5, EC-137).
 */
public class SensorProbe internal constructor(
    private val context: Context,
    private val permissions: PermissionManager,
) {

    private val sensorManager: SensorManager? =
        context.getSystemService(SensorManager::class.java)

    public fun probe(): DeviceSensors {
        val accelerometer = has(Sensor.TYPE_ACCELEROMETER)
        val gyroscope = has(Sensor.TYPE_GYROSCOPE)
        val significantMotion = has(Sensor.TYPE_SIGNIFICANT_MOTION)
        val stepDetector = has(Sensor.TYPE_STEP_DETECTOR)

        // Step sensors need ACTIVITY_RECOGNITION from API 29. Probe permission AND
        // availability, so stage 2a is skipped entirely rather than half-registered
        // and silently eventless (EC-133).
        val stepUsable = stepDetector && permissions.hasActivityRecognition()

        return DeviceSensors(
            accelerometer = accelerometer,
            gyroscope = gyroscope,
            magnetometer = has(Sensor.TYPE_MAGNETIC_FIELD),
            significantMotion = significantMotion,
            stepDetector = stepUsable,
            stepCounter = has(Sensor.TYPE_STEP_COUNTER) && permissions.hasActivityRecognition(),
            barometer = has(Sensor.TYPE_PRESSURE),
            rotationVector = has(Sensor.TYPE_ROTATION_VECTOR),
            motionQuality = quality(accelerometer, gyroscope, significantMotion, stepUsable),
        )
    }

    private fun quality(
        accelerometer: Boolean,
        gyroscope: Boolean,
        significantMotion: Boolean,
        stepDetector: Boolean,
    ): MotionQuality {
        val hasTrigger = significantMotion || stepDetector
        return when {
            // No accelerometer, or AR denied with no hardware trigger to fall back on:
            // motion gating cannot be trusted at all.
            !accelerometer || (!permissions.hasActivityRecognition() && !hasTrigger) -> MotionQuality.POOR
            accelerometer && gyroscope && hasTrigger -> MotionQuality.FULL
            else -> MotionQuality.DEGRADED
        }
    }

    private fun has(type: Int): Boolean = sensorManager?.getDefaultSensor(type) != null
}

public data class DeviceSensors(
    val accelerometer: Boolean,
    val gyroscope: Boolean,
    val magnetometer: Boolean,
    val significantMotion: Boolean,
    val stepDetector: Boolean,
    val stepCounter: Boolean,
    val barometer: Boolean,
    val rotationVector: Boolean,
    val motionQuality: MotionQuality,
)

public enum class MotionQuality {
    /** Accelerometer + gyroscope + a trigger sensor. Default behaviour. */
    FULL,

    /**
     * Accelerometer present, gyroscope or trigger sensors missing.
     *
     * The mode is left alone; `ResolveConfigUseCase` doubles `motion.stopTimeoutMin`
     * instead and emits a `Diagnostic` saying so. Stops on such a device are detected
     * later and less certainly, and waiting longer before believing one is the cheaper
     * error — a late stop costs a few extra fixes, a false stop costs the rest of the trip.
     */
    DEGRADED,

    /** Motion gating is not trustworthy — force CONTINUOUS and tell the host. */
    POOR,
}
