package com.field360.tracker.motion

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.field360.traker.geo.port.Clock
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Did this device physically move, or did its coordinates?
 *
 * Every stationary defence in `AcceptancePipeline` reasons about position, because a GNSS
 * fix carries nothing else. That makes them all statistical, and a statistical gate needs
 * an escape hatch wide enough for a real journey — which is exactly the hatch indoor
 * multipath finds. A phone on a desk, served by fused location, hops between Wi-Fi and
 * cell centroids; each hop is a plausible-looking displacement with a respectable accuracy
 * circle, and enough of them look like someone walking away.
 *
 * The accelerometer settles it. It measures the device, not its estimate of itself, and no
 * amount of multipath can fabricate a reading on it. A phone lying on a desk holds
 * |a| ≈ g with tens of milli-g of noise; a phone in a pocket, a bag or a hand does not,
 * and neither does one in a vehicle with its engine running.
 *
 * ### What this class is allowed to claim
 *
 * Exactly one thing: *"no sample in the recent window deviated from gravity"*. It does not
 * decide whether a point is stored — [com.field360.traker.geo.filter.AcceptancePipeline]
 * does, and it requires its own verdict, the GNSS chip's, and the pedometer's to agree
 * before this one counts for anything (EC-142). This is a fourth witness, never a judge.
 *
 * That division is deliberate and it is the lesson of EC-53: the incumbent treated the
 * motion API as authoritative and lost 17-minute drives whenever a OnePlus or Xiaomi
 * reported `STILL` under battery saver. A witness that can only ever *withhold* a drift
 * point cannot lose a trip; one that gates capture can.
 *
 * ### Failing safe
 *
 * Three ways this returns `false` — "no claim" — rather than risk a wrong one:
 *
 *  - **No accelerometer, or not registered.** Nothing to say.
 *  - **Too few samples, or a hole in the window.** A doze, a batch that never flushed, a
 *    session that just started. Absence of evidence is not evidence of stillness, and the
 *    device was not being watched across a gap.
 *  - **[escapeMillis] elapsed since the last time it said "moving".** The valve. An
 *    accelerometer that wedges, or a threshold wrong for some OEM's part, would otherwise
 *    silence the track for a whole shift; instead the claim lapses, one fix is judged the
 *    way it was before this class existed, and the window restarts.
 */
internal class StillnessMonitor(
    context: Context,
    private val clock: Clock,
) : SensorEventListener {

    private val sensorManager: SensorManager? = context.getSystemService(SensorManager::class.java)
    private val accelerometer: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    /** Guards every field below; touched from the sensor thread and the ingest coroutine. */
    private val lock = Any()

    private var registered = false
    private var escapeMillis = DEFAULT_ESCAPE_MIN * MILLIS_PER_MINUTE

    /** Monotonic ms of the newest sample folded in. `0` = nothing seen yet. */
    private var lastSampleMs = 0L

    /** Monotonic ms of the last sample that deviated from gravity, or of the last reset. */
    private var lastMotionMs = 0L

    /** Samples seen since [lastMotionMs]; the "enough evidence" half of the test. */
    private var quietSamples = 0

    val isAvailable: Boolean get() = accelerometer != null

    /**
     * Registered only while a session is active, at the slowest rate the platform offers
     * and with a batch latency, so the application processor is not woken for a stream of
     * samples nobody reads between fixes (EC-138).
     *
     * `SENSOR_DELAY_NORMAL` is ~5 Hz, which is far more than this needs — the question is
     * "did anything at all happen in the last half minute", not "what happened". Batching
     * means the hub answers it for free.
     */
    fun start(escapeMin: Int) {
        val sensor = accelerometer ?: return
        val manager = sensorManager ?: return

        synchronized(lock) {
            escapeMillis = escapeMin.coerceAtLeast(1) * MILLIS_PER_MINUTE
            // A session opens knowing nothing. Start from "moving" so the first window has
            // to be earned rather than inherited from whatever the last one left behind.
            reset(clock.elapsedRealtimeNanos() / NANOS_PER_MILLI)
            if (registered) return
        }

        val ok = manager.registerListener(
            this,
            sensor,
            SensorManager.SENSOR_DELAY_NORMAL,
            (BATCH_LATENCY_MS * MICROS_PER_MILLI).toInt(),
        )
        synchronized(lock) { registered = ok }
    }

    fun stop() {
        synchronized(lock) {
            if (!registered) return
            registered = false
        }
        sensorManager?.unregisterListener(this)
        synchronized(lock) { reset(0L) }
    }

    /**
     * Called on every accepted point: the window this class reports on is "since the last
     * stored point", the same interval `IngestContext.stepsSinceLastPoint` covers, so the
     * two witnesses are answering the same question about the same stretch of time.
     */
    fun onPointStored() {
        synchronized(lock) { quietSamples = 0 }
    }

    /**
     * @return true only when this class is prepared to assert the device has not moved.
     *
     * Read once per fix from the ingest coroutine, as `IngestContext.stillnessVeto`.
     */
    fun isStill(): Boolean = synchronized(lock) {
        if (!registered) return false
        if (quietSamples < MIN_QUIET_SAMPLES) return false

        val nowMs = clock.elapsedRealtimeNanos() / NANOS_PER_MILLI

        // A hole in the stream is a hole in the evidence. Doze, a batch that never
        // flushed, a hub that stopped: whatever the cause, nothing was watching, and a
        // window with a gap in it cannot say the device stayed put across the gap.
        if (nowMs - lastSampleMs > SAMPLE_STALE_MS) return false

        // The valve. Deliberately measured from the last observed *motion* rather than
        // from the last veto, so a device that has genuinely been parked for an hour still
        // lets a fix through every `escapeMillis` — and a wedged sensor, which looks
        // identical from here, does too.
        if (nowMs - lastMotionMs >= escapeMillis) {
            reset(nowMs)
            return false
        }
        return true
    }

    override fun onSensorChanged(event: SensorEvent?) {
        val values = event?.values ?: return
        if (event.sensor?.type != Sensor.TYPE_ACCELEROMETER) return
        if (values.size < AXES) return

        // Magnitude, not per-axis: a device at rest reads |a| = g whatever its orientation,
        // so this needs no gravity estimate, no filter and no calibration — and, unlike a
        // per-axis test, it does not read a slow tilt as motion. Rotation about the
        // gravity vector is invisible to it, which is correct here: a phone spun on a desk
        // has not gone anywhere.
        val magnitude = sqrt(
            (values[0] * values[0] + values[1] * values[1] + values[2] * values[2]).toDouble(),
        ).toFloat()

        // Sensor timestamps are `elapsedRealtimeNanos` on every device that batches, which
        // is the same clock `TrackFix` and the filter use. Falling back to a read of the
        // clock covers the OEMs that ship zero here.
        val sampleMs = if (event.timestamp > 0L) {
            event.timestamp / NANOS_PER_MILLI
        } else {
            clock.elapsedRealtimeNanos() / NANOS_PER_MILLI
        }

        synchronized(lock) {
            lastSampleMs = sampleMs
            if (abs(magnitude - SensorManager.STANDARD_GRAVITY) > QUIET_DEVIATION_MPS2) {
                reset(sampleMs)
            } else {
                quietSamples++
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int): Unit = Unit

    /** Caller holds [lock]. */
    private fun reset(nowMs: Long) {
        lastMotionMs = nowMs
        quietSamples = 0
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
        const val MICROS_PER_MILLI = 1_000L
        const val MILLIS_PER_MINUTE = 60_000L
        const val AXES = 3

        /** Matches `MotionConfig.stillnessEscapeMin`. */
        const val DEFAULT_ESCAPE_MIN = 30

        /**
         * How far |a| may stray from gravity and still count as quiet, m/s².
         *
         * A phone flat on a desk sits inside ~0.03; one held still in a hand runs 0.2–0.5;
         * a parked car with the engine idling runs 0.2–0.4 through the chassis. 0.15
         * therefore keeps "on a surface" and rejects everything a person is touching or a
         * running engine is shaking — which is the conservative side to be wrong on, since
         * a missed veto costs one drift point and a wrong one costs a real point.
         */
        const val QUIET_DEVIATION_MPS2 = 0.15f

        /** Matches `StepCorroborator`: the hub buffers, the AP sleeps (EC-138). */
        const val BATCH_LATENCY_MS = 60_000L

        /**
         * Samples required before the window means anything, at ~5 Hz.
         *
         * Fifty is ten seconds of continuous quiet. Below that the window is shorter than
         * the pause between two strides and would veto a walker mid-step.
         */
        const val MIN_QUIET_SAMPLES = 50

        /**
         * Newest sample older than this and the window has a hole in it, ms.
         *
         * Twice [BATCH_LATENCY_MS], so an ordinary late flush is not mistaken for a stall.
         */
        const val SAMPLE_STALE_MS = 2 * BATCH_LATENCY_MS
    }
}
