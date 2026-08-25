package com.field360.tracker.capture

import com.field360.tracker.TrackerConfig
import com.field360.tracker.TrackingMode
import com.field360.tracker.sdkLog
import com.field360.tracker.data.location.FixMapper
import com.field360.tracker.data.location.LocationRequests
import com.field360.tracker.data.location.LocationSource
import com.field360.traker.geo.port.TrackLogger
import com.field360.tracker.motion.CaptureStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Owns the location stream's lifetime and its cadence.
 *
 * Split out from the start/stop use cases because cadence has to change *during* a
 * session: at 60 s a car at 40 km/h covers ~660 m between fixes, so a 90° turn happens
 * entirely between samples and no filter can recover data that was never captured.
 * Dropping to 12 s while vehicular is the largest turn-fidelity win available without a
 * routing API (EC-45, spec §8.2).
 *
 * A third tier sits above vehicular: while `fieldtrack-geo`'s `TurnDetector` says the
 * vehicle is measurably turning, cadence drops again to `turnBurstIntervalMs`. Adaptive
 * cadence is a guess about the whole drive; this spends the battery only where the
 * geometry actually is (EC-45).
 *
 * Restarts only on an actual cadence *change* — tearing the request down and rebuilding
 * it on every motion event would cost more than the extra samples buy. That guard is why
 * the turn burst holds for 30 s rather than following each fix: the detector's hold
 * window is what keeps this from thrashing the location request through a bend.
 */
/**
 * Who is asking for the turn-burst cadence tier.
 *
 * The two see the same corner at different moments and hold it for different spans, which
 * is the point of having both — see [LocationStreamController.setTurning].
 */
internal enum class TurnSource {
    /** `fieldtrack-geo`'s `TurnDetector`, from the change in GNSS heading between fixes. */
    GNSS_BEARING,

    /** [com.field360.tracker.motion.GyroTurnMonitor], from yaw rate about the vertical. */
    GYROSCOPE,
}

/**
 * The half of [LocationStreamController] that [CaptureGate] drives.
 *
 * A seam rather than the class itself, so the outage state machine can be exercised
 * without a `LocationSource`, a `FixIngestor` and a database behind it — the matrix of
 * permission tiers against provider states is the part worth testing exhaustively, and it
 * is pure given this interface.
 */
internal interface CaptureSwitch {
    /** True while capture is blocked by a provider or permission outage. */
    val isSuspended: Boolean

    /** Tears the request down and refuses to rebuild it until [resumeCapture]. */
    fun suspendCapture()

    /** @return true if this call actually re-armed a suspended stream. */
    fun resumeCapture(): Boolean
}

internal class LocationStreamController(
    private val locationSource: LocationSource,
    private val fixMapper: FixMapper,
    private val ingestor: FixIngestor,
    private val logger: TrackLogger,
    private val scope: CoroutineScope,
) : CaptureStream, CaptureSwitch {

    private var job: Job? = null
    private var config: TrackerConfig? = null

    @Volatile
    private var vehicular: Boolean = false

    @Volatile
    private var turning: Boolean = false

    /** Which detectors currently want the burst. The tier follows their union. */
    private val turnRequests = java.util.EnumSet.noneOf(TurnSource::class.java)

    /**
     * A hard block on registering the request at all, owned by [CaptureGate].
     *
     * Not merely "stopped": every cadence path here ends in [restart], and two of them —
     * [onMoving] and [setVehicular] — are driven by `MotionController`, which knows
     * nothing about permissions or the GPS switch. Without a latch, a motion transition
     * arriving seconds after a revocation would re-register the request against a provider
     * the app is no longer allowed to read, and the SDK would sit there holding a callback
     * that can never fire (EC-06).
     */
    @Volatile
    private var suspended: Boolean = false

    val isRunning: Boolean get() = job?.isActive == true

    override val isSuspended: Boolean get() = suspended

    fun start(config: TrackerConfig, vehicular: Boolean = false) {
        this.config = config
        this.vehicular = vehicular
        this.turnRequests.clear()
        this.turning = false
        // A new session starts unblocked; `CaptureGate.arm` re-evaluates immediately after
        // and re-latches if the device is still in an outage.
        this.suspended = false
        restart()
    }

    /**
     * Tears the request down and refuses to rebuild it until [resumeCapture].
     *
     * Idempotent, and safe to call with no session open — the [config] guard in [restart]
     * already covers that case.
     */
    override fun suspendCapture() {
        if (suspended) return
        suspended = true
        sdkLog { logger.d(TAG, "Capture suspended") }
        stop()
    }

    override fun resumeCapture(): Boolean {
        if (!suspended) return false
        suspended = false
        if (config == null) return false
        sdkLog { logger.d(TAG, "Capture resumed") }
        restart()
        return true
    }

    /**
     * Records what [source] currently thinks and applies the union.
     *
     * Two independent detectors ask for the burst — `TurnDetector` from GNSS heading and
     * [com.field360.tracker.motion.GyroTurnMonitor] from yaw rate — and they are *meant*
     * to disagree, because they see a corner at different moments. The gyroscope arms as
     * the wheel turns; GNSS confirms it a fix or two later and holds it through the rest
     * of the bend. Feeding both into one boolean would have each clear the other's burst:
     * the gyroscope's hold expires mid-corner, writes `false`, and the fast tier drops
     * while `TurnDetector` still says the vehicle is turning.
     *
     * So the effective state is the union, and a source can only ever speak for itself.
     */
    @Synchronized
    fun setTurning(source: TurnSource, turning: Boolean) {
        if (turning) turnRequests += source else turnRequests -= source
        applyTurning(turnRequests.isNotEmpty())
    }

    /**
     * Drops every source's request at once — used where the vehicle demonstrably is not
     * turning, whatever a detector last said.
     */
    @Synchronized
    fun clearTurning() {
        turnRequests.clear()
        applyTurning(false)
    }

    /** A no-op unless the cadence tier actually flips, same as [setVehicular] (EC-45). */
    private fun applyTurning(turning: Boolean) {
        val active = config ?: return
        if (this.turning == turning) return

        val before = LocationRequests.intervalFor(active.geolocation, vehicular, this.turning)
        // The field is recorded before the guards, never after. A guarded early return
        // that left it stale would hand the next restart a tier the detector no longer
        // asks for — a burst that outlives the bend that armed it.
        this.turning = turning
        if (!active.geolocation.turnBurst) return
        // Nothing to accelerate. Restarting a stopped stream here would resume capture in
        // MOTION_ONLY on the strength of a reading from before it was stopped.
        if (!isRunning) return
        // A flip that does not change the request must not restart it. Navigation is the
        // case that made this real: it outranks every tier, so with it on the rebuilt
        // request is byte-identical — and each needless teardown re-arms
        // waitForAccurateLocation, holding back fixes exactly where the 1 Hz feed
        // matters most (SMOOTH-NAV-PLAN Phase 1).
        if (LocationRequests.intervalFor(active.geolocation, vehicular, turning) == before) return

        sdkLog { logger.d(TAG, "Cadence -> ${if (turning) "turn burst" else "steady"}") }
        restart()
    }

    /**
     * Called by [com.field360.tracker.motion.MotionController] as motion state changes.
     * A no-op unless the cadence tier actually flips.
     */
    override fun setVehicular(vehicular: Boolean) {
        val active = config ?: return
        if (this.vehicular == vehicular) return
        if (!active.geolocation.adaptiveCadence) return

        val before = LocationRequests.intervalFor(active.geolocation, this.vehicular, turning)
        this.vehicular = vehicular
        // Same guard as setTurning: an identical request is never restarted (navigation
        // outranks the tiers, and a turn burst already outranks vehicular).
        if (LocationRequests.intervalFor(active.geolocation, vehicular, turning) == before) return

        sdkLog { logger.d(TAG, "Cadence -> ${if (vehicular) "vehicular" else "normal"}") }
        restart()
    }

    /**
     * In `MOTION_ONLY` the stream is genuinely switched off while stationary — that is
     * the mode's entire point. `ADAPTIVE` keeps it running and lets the filter thin,
     * because the heartbeat is what self-corrects a device whose wake paths all failed
     * (EC-57).
     */
    override fun onStationary() {
        val active = config ?: return
        if (active.geolocation.trackingMode == TrackingMode.MOTION_ONLY) {
            turnRequests.clear()
            turning = false
            sdkLog { logger.d(TAG, "MOTION_ONLY: stopping stream while stationary") }
            stop()
            return
        }

        // A parked vehicle is not turning, whatever the last fix said. Dropped first and
        // on its own path, because `setVehicular` returns early when the vehicular tier
        // is already off — and a burst left running against a parked phone is the
        // battery complaint this feature would otherwise earn.
        clearTurning()
        setVehicular(false)
    }

    override fun onMoving() {
        val active = config ?: return
        // Checked before `start`, because `start` clears the latch — it is the entry point
        // for a *new* session, and motion is not a new session. A move detected during an
        // outage must not be able to unblock capture; only `CaptureGate` may.
        if (suspended) return
        if (!isRunning) start(active, vehicular = false)
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    fun release() {
        stop()
        config = null
        vehicular = false
        turnRequests.clear()
        turning = false
        // Cleared with the rest of the session state: a suspension belongs to the session
        // that was interrupted, and carrying it into the next `start()` would leave the
        // new one dead on arrival.
        suspended = false
    }

    private fun restart() {
        val active = config ?: return
        // The single choke point every cadence path funnels through — see [suspended].
        if (suspended) return
        job?.cancel()
        job = scope.launch {
            // The tier is stamped per fix, at capture: this collector knows exactly
            // which request produced its fixes. Sampling the controller at consume time
            // instead would mislabel queued fixes across a flip and stamp one-shot and
            // backstop fixes with a tier they never had (SMOOTH-NAV-PLAN Phase 1).
            val intervalMs = LocationRequests.intervalFor(active.geolocation, vehicular, turning)
            locationSource.stream(active.geolocation, vehicular, turning).collect { batch ->
                // Whole batch, ascending. Reading only the last member is the defect
                // that silently discards 4-6 fixes per Doze window (SOURCE-AUDIT A4).
                batch.forEach { location ->
                    fixMapper.map(location, active.geolocation.mockLocationPolicy)
                        ?.let { ingestor.offer(it, cadenceTierMs = intervalMs) }
                }
            }
        }
    }

    private companion object {
        const val TAG = "StreamController"
    }
}
