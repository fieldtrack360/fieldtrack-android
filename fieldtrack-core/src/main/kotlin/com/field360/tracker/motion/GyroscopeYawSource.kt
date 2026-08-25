package com.field360.tracker.motion

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.field360.traker.geo.motion.GyroTurnGate

/**
 * [YawRateSource] over `TYPE_GYROSCOPE`, projected onto gravity.
 *
 * Two sensors, because one is not enough. The gyroscope reports rotation about the
 * *device's* axes, and a phone in a pocket, a windscreen cradle or a cupholder has no
 * fixed relationship to the vehicle — the same corner appears on a different axis for each.
 * Projecting onto the gravity direction extracts the component about the world vertical,
 * which is the vehicle's yaw however the phone happens to be lying
 * ([GyroTurnGate.yawRateAboutGravity]).
 *
 * `TYPE_GRAVITY` where the device has it — it is the fused, already-smoothed answer, and
 * on any device with a gyroscope it is virtually always present. The accelerometer
 * fallback is a low-pass filter over raw acceleration, which is the same estimate the
 * platform's own fusion starts from and is good enough for a direction: cornering
 * acceleration is a fraction of g and the filter's time constant is far longer than a
 * corner.
 *
 * **Registered only while driving.** [GyroTurnMonitor] owns that decision and calls
 * [start] and [stop] as GNSS speed says the device is or is not in a vehicle. A gyroscope
 * left running for a whole session is a real battery cost and the exact complaint an
 * aggressive SDK earns (EC-138).
 */
internal class GyroscopeYawSource(
    context: Context,
) : YawRateSource {

    private val sensorManager: SensorManager? = context.getSystemService(SensorManager::class.java)
    private val gyroscope: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val gravity: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val accelerometer: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    /** Whichever of the two can supply a gravity direction; `TYPE_GRAVITY` preferred. */
    private val gravitySensor: Sensor? = gravity ?: accelerometer

    private var listener: SensorEventListener? = null

    override val isAvailable: Boolean get() = gyroscope != null && gravitySensor != null

    override fun start(onYawRate: (Float) -> Unit) {
        val manager = sensorManager ?: return
        val gyro = gyroscope ?: return
        val gravityInput = gravitySensor ?: return
        if (listener != null) return

        val sensorListener = object : SensorEventListener {
            // Seeded to a phone lying flat rather than to zero. A zero seed makes the
            // first few gyro samples project onto a degenerate vector and report no
            // rotation, which is a hole exactly where the burst is most useful — the
            // sensor is registered because the vehicle just started moving.
            private var gravityX = 0f
            private var gravityY = 0f
            private var gravityZ = SensorManager.GRAVITY_EARTH

            override fun onSensorChanged(event: SensorEvent?) {
                val values = event?.values ?: return
                if (values.size < AXES) return

                when (event.sensor?.type) {
                    Sensor.TYPE_GRAVITY -> {
                        gravityX = values[0]
                        gravityY = values[1]
                        gravityZ = values[2]
                    }

                    // Raw acceleration is gravity plus whatever the vehicle is doing. The
                    // low pass is what separates them: cornering and braking are transient
                    // next to a time constant this long, so what survives is the vertical.
                    Sensor.TYPE_ACCELEROMETER -> {
                        gravityX = lowPass(gravityX, values[0])
                        gravityY = lowPass(gravityY, values[1])
                        gravityZ = lowPass(gravityZ, values[2])
                    }

                    Sensor.TYPE_GYROSCOPE -> onYawRate(
                        GyroTurnGate.yawRateAboutGravity(
                            gyroXRadPerSec = values[0],
                            gyroYRadPerSec = values[1],
                            gyroZRadPerSec = values[2],
                            gravityX = gravityX,
                            gravityY = gravityY,
                            gravityZ = gravityZ,
                        ),
                    )

                    else -> Unit
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        listener = sensorListener
        // 20 Hz. A corner lasts seconds and the sustain window is 600 ms, so nothing here
        // needs the game or fastest tiers — and those are where a gyroscope's power draw
        // stops being negligible. Zero max latency: batching would deliver a turn after it
        // was over, which is the entire defect this class exists to fix.
        manager.registerListener(sensorListener, gyro, SAMPLING_PERIOD_US, 0)
        manager.registerListener(sensorListener, gravityInput, SAMPLING_PERIOD_US, 0)
    }

    override fun stop() {
        listener?.let { sensorManager?.unregisterListener(it) }
        listener = null
    }

    private fun lowPass(current: Float, sample: Float): Float =
        current * LOW_PASS_ALPHA + sample * (1f - LOW_PASS_ALPHA)

    private companion object {
        const val AXES = 3

        /** 50 000 µs — 20 Hz. */
        const val SAMPLING_PERIOD_US = 50_000

        /** ~0.5 s time constant at 20 Hz: long against a corner, short against a hill. */
        const val LOW_PASS_ALPHA = 0.9f
    }
}
