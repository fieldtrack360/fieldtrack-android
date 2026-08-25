package com.field360.traker.geo.motion

import com.field360.traker.geo.filter.TrackerConstants
import com.field360.traker.geo.math.Bearing
import com.field360.traker.geo.math.Haversine
import com.field360.traker.geo.model.TrackFix
import kotlin.math.max

private const val NANOS_PER_SECOND = 1_000_000_000f
private const val NANOS_PER_MILLI = 1_000_000L

/**
 * Detects *sustained* turning and asks for a faster sampling tier while it lasts.
 *
 * This is the capture-side companion to bearing-change capture. That stage decides
 * whether a fix already taken is worth storing; nothing there can recover a corner that
 * was never sampled. At the 12 s vehicular cadence a car at 40 km/h covers ~130 m
 * between fixes, so a junction happens entirely between two samples and the polyline can
 * only draw the chord (EC-45, spec §8.2).
 *
 * **It is inherently reactive, and this KDoc says so rather than letting the field find
 * out.** A turn is detected from fixes taken during it, so the burst arms partway
 * through. What it buys is everything *after* that instant: the second half of a long
 * bend, corners 2..n of a roundabout or an interchange, the next junction in a grid. A
 * single isolated 90° turn taken at speed is still under-sampled at its apex by this
 * detector alone.
 *
 * Two things address that, and neither replaces this one. [GyroTurnGate] arms the same
 * burst from yaw rate, which rises as the wheel turns rather than after the heading has
 * changed — so on a device with a gyroscope the burst is already running when the corner
 * arrives, and this detector's job becomes holding it through the rest of the bend. Where
 * the road's real shape matters more than the sampling, the answer is still a routing API,
 * which is why `RoadSnapProvider` exists.
 *
 * Pure, in the same discipline as [MotionStateMachine] and `FilterState`: takes a state
 * and a fix, returns a new state. No timers, no coroutines, no clock of its own — the
 * fix's monotonic timestamp is the only clock, so a recorded drive replays identically.
 */
public class TurnDetector(
    private val c: TrackerConstants = TrackerConstants.Default,
) {

    /**
     * @property lastLat previous fix position, for the displacement heading fallback.
     *   Kept here rather than read from `FilterState` because the detector runs on
     *   **raw** fixes, including ones the acceptance pipeline rejects: a turn is a fact
     *   about the vehicle, not about which samples were worth storing.
     * @property lastElapsedNanos nullable rather than a `0` sentinel, for the same reason
     *   [MotionStateMachine.State.pendingMoveSinceNanos] is: `elapsedRealtimeNanos` is 0
     *   at boot, so a first fix arriving at monotonic zero would be indistinguishable
     *   from "no previous fix" and the second fix of every post-reboot session would be
     *   measured against a Δt of zero and silently discarded.
     * @property burstUntilNanos monotonic deadline; `0` means not bursting — safe as a
     *   sentinel here, unlike the field above, because it is only ever compared with
     *   `<` and a fix at monotonic zero is correctly *not* inside a window ending at
     *   zero. Storing a deadline rather than a boolean gives the burst its linger for
     *   free and keeps the whole thing a pure function of the fix clock.
     */
    public data class State(
        val lastLat: Double = 0.0,
        val lastLng: Double = 0.0,
        val lastHeadingDeg: Float = HEADING_UNSET,
        val lastElapsedNanos: Long? = null,
        val burstUntilNanos: Long = 0L,
    ) {
        public val hasHeading: Boolean get() = lastHeadingDeg >= 0f
        public val hasPrevious: Boolean get() = lastElapsedNanos != null
    }

    public data class Result(
        val state: State,
        /** True while the faster tier should be requested. */
        val isBursting: Boolean,
        /** Observed rate of heading change, deg/s. Zero when nothing was measurable. */
        val turnRateDegPerSec: Float,
        /**
         * How fast the device was travelling by this fix's reckoning, m/s — the greater of
         * the chip's Doppler speed and the displacement since the previous fix.
         *
         * Exposed because the detector already has to compute it to decide whether a
         * heading change is a turn or a phone rotating on a desk, and because it is the
         * best speed available on **every raw fix**, accepted or rejected. Consumers that
         * need to know the device is in a vehicle — [GyroTurnGate]'s caller, which opens
         * and closes a gyroscope on the answer — would otherwise have to recompute it or
         * settle for the accepted-point stream, which goes quiet through exactly the
         * junction they care about.
         */
        val speedMps: Float,
    )

    public fun onFix(state: State, fix: TrackFix): Result {
        val dtSec = state.lastElapsedNanos
            ?.let { (fix.elapsedRealtimeNanos - it).toFloat() / NANOS_PER_SECOND }
            ?: 0f
        val heading = headingOf(state, fix)
        val speed = observedSpeed(state, fix, dtSec)
        val rate = turnRate(state, fix, heading, dtSec, speed)

        // Each qualifying fix re-arms the hold rather than extending a running total, so
        // a twisty road stays fast for as long as it stays twisty and a straight road
        // falls back one hold window after the last corner. There is deliberately no cap
        // on consecutive bursts: the only way to keep re-arming it is to keep turning,
        // and a vehicle turning for ten minutes is precisely the case this exists for.
        val burstUntil = if (rate >= c.turnBurstEnterDegPerSec) {
            fix.elapsedRealtimeNanos + c.turnBurstHoldMs * NANOS_PER_MILLI
        } else {
            state.burstUntilNanos
        }

        val next = State(
            lastLat = fix.latitude,
            lastLng = fix.longitude,
            // A fix we could not derive a heading from leaves the previous one standing.
            // Clearing it would blind the next comparison and drop the burst mid-bend.
            lastHeadingDeg = heading ?: state.lastHeadingDeg,
            lastElapsedNanos = fix.elapsedRealtimeNanos,
            burstUntilNanos = burstUntil,
        )

        return Result(
            state = next,
            isBursting = fix.elapsedRealtimeNanos < burstUntil,
            turnRateDegPerSec = rate,
            speedMps = speed,
        )
    }

    /** Forget everything. Called at session start and across a reboot boundary. */
    public fun reset(): State = State()

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Hardware heading first — the chip derives it from Doppler, so it is where the
     * vehicle is pointing rather than the chord across a sampling interval, and a chord
     * across a corner reports roughly half the angle actually turned.
     */
    private fun headingOf(state: State, fix: TrackFix): Float? = when {
        fix.hasBearing -> fix.bearingDeg
        !state.hasPrevious -> null
        Haversine.metres(state.lastLat, state.lastLng, fix.latitude, fix.longitude) >= c.bearingCaptureMinDist ->
            Bearing.degrees(state.lastLat, state.lastLng, fix.latitude, fix.longitude).toFloat()
        else -> null
    }

    /**
     * @return degrees of heading change per second, or `0` when the comparison would be
     *   meaningless.
     *
     * Every rejection here guards against bursting for something that is not a turn:
     *  - **no previous heading**, or none derivable from this fix — nothing to compare;
     *  - **too slow** — a phone rotating on a desk or in a pocket sweeps the full circle
     *    while the user goes nowhere, and burning the fast tier on that is the exact
     *    battery complaint an aggressive SDK earns. Speed is `max(hardware, computed)`
     *    so a fix carrying no Doppler speed can still qualify on displacement;
     *  - **Δt outside the window** — under a second the rate divides by ~0 and explodes;
     *    across a signal gap the two headings describe unrelated legs and the "rate" is
     *    an artefact of the gap length, not of any turn;
     *  - **poor accuracy** — a displacement heading from a 60 m error circle is noise,
     *    and noise sampled every 12 s looks exactly like a turn.
     */
    private fun turnRate(state: State, fix: TrackFix, heading: Float?, dtSec: Float, speed: Float): Float {
        if (heading == null || !state.hasHeading) return 0f
        if (dtSec < c.turnBurstMinDtSec || dtSec > c.turnBurstMaxDtSec) return 0f
        if (fix.accuracy >= c.bearingCaptureMaxAccuracy) return 0f
        if (speed < c.turnBurstMinSpeed) return 0f

        val turned = Bearing.difference(state.lastHeadingDeg.toDouble(), heading.toDouble())
        return (turned / dtSec).toFloat()
    }

    /**
     * The greater of the chip's Doppler speed and the speed implied by displacement.
     *
     * `max`, not a preference, and not an average: a fix carrying no Doppler speed can
     * still qualify on displacement, and a chip reporting a stale zero while the device
     * moves must not be able to veto what two positions plainly show.
     *
     * Outside the Δt window this falls back to hardware speed alone. A displacement speed
     * across a signal gap divides a real distance by a gap length and means nothing, and
     * under a second it divides by ~0 — the same two reasons [turnRate] refuses to measure
     * a heading change there.
     */
    private fun observedSpeed(state: State, fix: TrackFix, dtSec: Float): Float {
        val hwSpeed = if (fix.hasSpeed) fix.speedMps else 0f
        if (!state.hasPrevious) return hwSpeed
        if (dtSec < c.turnBurstMinDtSec || dtSec > c.turnBurstMaxDtSec) return hwSpeed

        val moved = Haversine.metres(state.lastLat, state.lastLng, fix.latitude, fix.longitude)
        return max(hwSpeed, (moved / dtSec).toFloat())
    }

    public companion object {
        /** No heading observed yet. Negative, so it cannot collide with `[0, 360)`. */
        public const val HEADING_UNSET: Float = -1f
    }
}
