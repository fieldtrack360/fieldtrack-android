package com.field360.traker.geo.motion

import com.field360.traker.geo.filter.TrackerConstants
import com.field360.traker.geo.math.Bearing
import com.field360.traker.geo.math.Haversine
import com.field360.traker.geo.model.TrackFix
import com.field360.traker.geo.model.TrackPoint

/**
 * Decides, one fix late, whether a rejected fix was standing at a corner (EC-45e).
 *
 * The gap this closes is a matter of arithmetic, not tuning. Bearing-change capture stores
 * a fix whose heading has turned far enough *since the last stored point* — it looks
 * backwards, and at a corner's apex only half the turn is behind you. A 90° junction
 * sampled once on approach, once at the apex and once on the exit presents the apex fix as
 * a 45° change, under any threshold set to recognise a junction. So the apex is dropped,
 * the exit fix is stored on the full 90°, and the drawn line runs straight from approach
 * to exit — the chord across the corner, with the vertex that would have described it
 * discarded a fix earlier.
 *
 * Seeing the other half means waiting for the fix after the apex, and that is all this
 * does: hold one rejected fix, and when the next arrives, ask whether the path bent across
 * the one being held.
 *
 * **One fix of latency, and only for fixes that were being thrown away.** An accepted fix
 * is committed the moment the pipeline accepts it, so nothing a host can see — the live
 * tail, the puck, `TrackerEvent.Location` — is delayed by this. What is delayed is a
 * rejection, by exactly one fix.
 *
 * Pure, and deliberately not a ring buffer despite the name. Only the immediately
 * preceding fix can be a corner's apex: any older one has a stored point or another
 * rejection between it and the current fix, and a vertex two fixes back is a different
 * corner or none.
 */
public object CornerWindow {

    /**
     * @param past the last stored point — where the drawn line currently comes from.
     * @param held the rejected fix's would-be point, sitting between [past] and [current].
     * @param current the fix that has just arrived, and the only new evidence there is.
     * @param turnActive a detector already says the vehicle is turning across this pair —
     *   `TurnDetector`'s burst, or the gyroscope's. Sufficient on its own, because both
     *   measure the turn directly rather than inferring it from three positions.
     * @param minTurnDeg the angle the host calls a corner
     *   (`IngestContext.bearingChangeCaptureDeg`). `0` disables geometric detection,
     *   leaving only [turnActive].
     */
    @Suppress("LongParameterList")
    public fun isCornerAnchor(
        past: TrackPoint,
        held: TrackPoint,
        current: TrackFix,
        turnActive: Boolean,
        minTurnDeg: Int,
        c: TrackerConstants = TrackerConstants.Default,
    ): Boolean {
        // A vertex on top of the one before it draws nothing and costs a row.
        //
        // Deliberately a much smaller floor than [TrackerConstants.anchorMinDist], and the
        // reason is worth stating because the obvious value is wrong here. A fix reaches
        // the heuristic gate's rejection *because* it moved less than
        // `distMinMove` — 10 m — so any floor at the usual 15 m would reject every fix
        // this stage could ever be handed, and the whole mechanism would be dead code that
        // passed its own unit tests. Drift is kept out by [isTravelling] and by the turn
        // itself, which is where that job belongs.
        if (Haversine.metres(
                past.latitude, past.longitude, held.latitude, held.longitude,
            ) < c.cornerAnchorMinDist
        ) {
            return false
        }

        // The stationary-drift guard, and the one doing real work. A parked phone's
        // rejected fixes wander far enough to fake a corner and their displacement-derived
        // headings swing through the full circle; what they never have is Doppler. So the
        // question is not "did this move far enough" but "was the device travelling", and
        // the chip is the witness (EC-36a).
        if (!isTravelling(held, current, c)) return false

        if (turnActive) return true
        if (minTurnDeg <= 0) return false

        val inbound = headingInto(past, held, c) ?: return false
        val outbound = headingOutOf(held, current, c) ?: return false
        return Bearing.difference(inbound, outbound) >= minTurnDeg
    }

    /**
     * Whether the device was actually going somewhere across the held fix.
     *
     * Hardware speed first, from either end of the pair: a chip reporting Doppler over
     * walking pace is a measurement multipath around a parked phone cannot fabricate,
     * which is exactly why the acceptance pipeline lets the same signal override its own
     * displacement heuristics (EC-36a, EC-39d).
     *
     * Where no chip reported speed at all — a network fix, or an OEM that clears the flag —
     * there is nothing to appeal to but displacement, held to the same floor a heading is:
     * below it, both the distance and the direction are functions of the accuracy circle.
     */
    private fun isTravelling(held: TrackPoint, current: TrackFix, c: TrackerConstants): Boolean = when {
        current.hasSpeed -> current.speedMps >= c.speedWalkingMin
        held.hasSpeed -> held.speedMps >= c.speedWalkingMin
        else -> Haversine.metres(
            held.latitude, held.longitude, current.latitude, current.longitude,
        ) >= c.bearingCaptureMinDist
    }

    /**
     * Heading on arrival at [held].
     *
     * Hardware first, for the reason it is first everywhere else in this SDK: the chip
     * derives heading from Doppler, so it is where the device was pointing at that
     * instant, while a heading derived from two positions is the chord between them. At a
     * corner that distinction is the whole measurement — the chord bisects the turn and
     * reports half of it, which is the very error this class exists to work around.
     */
    private fun headingInto(past: TrackPoint, held: TrackPoint, c: TrackerConstants): Double? = when {
        held.hasBearing -> held.bearingDeg.toDouble()
        Haversine.metres(past.latitude, past.longitude, held.latitude, held.longitude) >= c.bearingCaptureMinDist ->
            Bearing.degrees(past.latitude, past.longitude, held.latitude, held.longitude)

        else -> null
    }

    /** Heading on departure from [held], by the same rule. */
    private fun headingOutOf(held: TrackPoint, current: TrackFix, c: TrackerConstants): Double? = when {
        current.hasBearing -> current.bearingDeg.toDouble()
        Haversine.metres(
            held.latitude, held.longitude, current.latitude, current.longitude,
        ) >= c.bearingCaptureMinDist ->
            Bearing.degrees(held.latitude, held.longitude, current.latitude, current.longitude)

        else -> null
    }
}
