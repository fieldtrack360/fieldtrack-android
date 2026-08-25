package com.field360.tracker.motion

import com.field360.tracker.sdkLog
import com.field360.traker.geo.filter.TrackerConstants
import com.field360.traker.geo.motion.GyroTurnGate
import com.field360.traker.geo.port.Clock
import com.field360.traker.geo.port.TrackLogger

private const val NANOS_PER_MILLI = 1_000_000L

/**
 * Decides when the gyroscope is worth listening to, and turns what it hears into a turn
 * burst (EC-45d).
 *
 * [GyroTurnGate] answers "is this yaw rate a turn". This answers the two questions the
 * gate deliberately does not: **is this device even in a vehicle**, and **is the sensor
 * registered right now**. Both have the same answer, and it comes from GNSS rather than
 * from any sensor: while fixes report vehicular speed the gyroscope is listening, and
 * within [TrackerConstants.gyroTurnVehicularWindowMs] of them stopping it is not.
 *
 * That single rule carries the two objections a gyroscope-driven feature has to answer.
 *
 *  - **A walker swinging a phone.** Yaw rates from a hand or a pocket dwarf any junction,
 *    and no threshold separates them reliably. Nothing separates them here either — the
 *    sensor is simply never registered, because a walker never clears
 *    [TrackerConstants.turnBurstMinSpeed].
 *  - **Battery.** A gyroscope held open for a session is a real, measurable cost and the
 *    complaint an aggressive SDK earns. Held open only while the vehicle is moving, it is
 *    running for exactly the period whose corners it is there to sample.
 *
 * The staleness check runs on the yaw callback as well as on the fix path, and that
 * redundancy is load-bearing: a vehicle that parks in an underground car park stops
 * producing fixes at the same moment it stops moving, so the fix path would never run
 * again to notice the latch expiring, and the sensor would stay registered until the
 * session ended.
 *
 * Thread-safe by synchronisation rather than by confinement, unlike [MotionController]:
 * yaw samples arrive on the main looper and fix speeds on the ingest coroutine, and a
 * channel between them would add a hop to a path whose entire value is being early.
 */
internal class GyroTurnMonitor(
    private val source: YawRateSource,
    private val clock: Clock,
    private val logger: TrackLogger,
    private val constants: TrackerConstants = TrackerConstants.Default,
    private val gate: GyroTurnGate = GyroTurnGate(constants),
) {

    /** Called on a real change only, with the same contract as `FixIngestor.onTurnBurst`. */
    var onTurning: ((Boolean) -> Unit)? = null

    private var state = GyroTurnGate.State()
    private var listening = false
    private var turning = false

    /** When GNSS last measured vehicular speed. `null` means never, this session. */
    private var lastVehicularNanos: Long? = null

    val isAvailable: Boolean get() = source.isAvailable

    /**
     * Session start. Deliberately does **not** register the sensor: a session usually
     * begins with a parked device, and the first vehicular fix is what opens the sensor.
     */
    @Synchronized
    fun start() {
        state = gate.reset()
        lastVehicularNanos = null
        stopListening()
    }

    /**
     * Every fix's speed, accepted or rejected.
     *
     * Rejected fixes count for the same reason `TurnDetector` runs on raw fixes: whether a
     * vehicle is moving is a fact about the vehicle, not about which samples were worth
     * storing, and gating the sensor on accepted points would close it during exactly the
     * stretch — a burst of rejects through a junction — that it exists for.
     */
    @Synchronized
    fun onSpeed(speedMps: Float) {
        if (!source.isAvailable) return

        val now = clock.elapsedRealtimeNanos()
        if (speedMps >= constants.turnBurstMinSpeed) {
            lastVehicularNanos = now
            startListening()
        } else if (isStale(now)) {
            stopListening()
        }
    }

    /** Session stop, and any point at which the stream is torn down. */
    @Synchronized
    fun stop() {
        lastVehicularNanos = null
        stopListening()
    }

    // ─────────────────────────────────────────────────────────────────────────

    @Synchronized
    private fun onYawRate(yawRateDegPerSec: Float) {
        val now = clock.elapsedRealtimeNanos()
        // A vehicle that parks underground stops producing fixes and stops moving at the
        // same instant, so `onSpeed` will not run again to close the sensor. This is the
        // path that does.
        if (isStale(now)) {
            stopListening()
            return
        }

        val result = gate.onSample(state, yawRateDegPerSec, now)
        state = result.state
        if (result.isTurning == turning) return

        turning = result.isTurning
        sdkLog {
            logger.d(
                TAG,
                if (turning) {
                    "Gyro turn detected at ${result.yawRateDegPerSec.toInt()} deg/s"
                } else {
                    "Gyro turn burst expired"
                },
            )
        }
        onTurning?.invoke(turning)
    }

    private fun isStale(nowNanos: Long): Boolean {
        val last = lastVehicularNanos ?: return true
        return nowNanos - last > constants.gyroTurnVehicularWindowMs * NANOS_PER_MILLI
    }

    private fun startListening() {
        if (listening) return
        listening = true
        state = gate.reset()
        source.start(::onYawRate)
        sdkLog { logger.d(TAG, "Gyroscope armed for turn prediction") }
    }

    /**
     * Clears the burst as well as the registration.
     *
     * Leaving [turning] set on the way out would strand the fast cadence tier: nothing
     * else clears it, because the only thing that ever does is a gyro sample, and the
     * sensor supplying those has just been unregistered.
     */
    private fun stopListening() {
        if (!listening) {
            // Still worth clearing a burst raised before the sensor came down — `start()`
            // calls this on a session that may have been mid-corner.
            releaseBurst()
            return
        }
        listening = false
        source.stop()
        state = gate.reset()
        releaseBurst()
        sdkLog { logger.d(TAG, "Gyroscope released") }
    }

    private fun releaseBurst() {
        if (!turning) return
        turning = false
        onTurning?.invoke(false)
    }

    private companion object {
        const val TAG = "GyroTurn"
    }
}
