package com.field360.traker.geo.plot

import com.field360.traker.geo.math.Bearing
import com.field360.traker.geo.math.Haversine
import com.field360.traker.geo.plot.model.PlotPoint
import com.field360.traker.geo.plot.model.RenderTag
import kotlin.math.min

/**
 * Rounds sharp corners so a sparse track stops looking like a sawtooth (spec §22.2).
 *
 * This is cosmetic and honest about it: it **cannot invent the true corner**, only stop
 * the polyline from turning through a hard vertex. Where real turn geometry matters the
 * answer is more samples at capture time (adaptive cadence) or map-matching at render
 * time — not a prettier curve through the same two points (spec §8.2). Where [Snapper]
 * has supplied that real geometry, rounding stands down rather than smoothing it.
 *
 * Three guards:
 *  - **Protected points are never moved.** Session bookends and host-inserted markers
 *    must stay exactly where they were recorded (EC-103).
 *  - **Cutback is bounded by its neighbours**: `min(25 m, 0.4 × distToPrev, 0.4 × distToNext)`,
 *    so a short leg between two sharp turns cannot overshoot past the adjacent vertex
 *    (EC-104).
 *  - **Snapped points are never rounded.** They are the road, not an approximation of it.
 *
 * Pure: returns a new list, and tags the inserted points [RenderTag.ROUNDED_CURVE] on
 * the *output* type rather than writing a render flag back onto a stored point (A10).
 */
public object BezierRounding {

    public fun round(
        path: List<PlotPoint>,
        minAngleDeg: Double = MIN_ANGLE_DEG,
        maxCutbackM: Double = MAX_CUTBACK_M,
        pointsPerCurve: Int = POINTS_PER_CURVE,
    ): List<PlotPoint> {
        if (path.size < 3) return path

        val out = mutableListOf(path.first())

        for (i in 1 until path.lastIndex) {
            val previous = path[i - 1]
            val vertex = path[i]
            val next = path[i + 1]

            val turn = turnAngle(previous, vertex, next)
            // A snapped vertex is the road's own geometry, not a sparse sample of it.
            // Rounding it would smooth away a corner that is genuinely square and push
            // the line off the carriageway the snap just put it on (EC-101).
            if (vertex.isProtected || vertex.tag == RenderTag.SNAPPED_TO_ROAD || turn < minAngleDeg) {
                out += vertex
                continue
            }

            val distPrev = Haversine.metres(
                previous.latitude, previous.longitude, vertex.latitude, vertex.longitude,
            )
            val distNext = Haversine.metres(
                vertex.latitude, vertex.longitude, next.latitude, next.longitude,
            )
            val cutback = min(maxCutbackM, min(NEIGHBOUR_FRACTION * distPrev, NEIGHBOUR_FRACTION * distNext))
            if (cutback <= 0.0) {
                out += vertex
                continue
            }

            val start = pointTowards(vertex, previous, cutback / distPrev.coerceAtLeast(1e-9))
            val end = pointTowards(vertex, next, cutback / distNext.coerceAtLeast(1e-9))

            // Quadratic Bézier with the original vertex as the control point: the curve
            // passes near it without ever touching it.
            for (step in 0..pointsPerCurve) {
                val t = step.toDouble() / pointsPerCurve
                val inverse = 1 - t
                val lat = inverse * inverse * start.first +
                    2 * inverse * t * vertex.latitude +
                    t * t * end.first
                val lng = inverse * inverse * start.second +
                    2 * inverse * t * vertex.longitude +
                    t * t * end.second
                out += vertex.copy(
                    latitude = lat,
                    longitude = lng,
                    tag = RenderTag.ROUNDED_CURVE,
                    // The corner's own recorded heading belongs to the vertex, not to the
                    // curve drawn around it: these points sit metres away from where that
                    // measurement was taken.
                    bearingDeg = PlotPoint.BEARING_UNSET,
                )
            }
        }

        out += path.last()
        return out
    }

    /** Interior turn magnitude in degrees; 0 means dead straight. */
    internal fun turnAngle(a: PlotPoint, b: PlotPoint, c: PlotPoint): Double {
        val incoming = Bearing.degrees(a.latitude, a.longitude, b.latitude, b.longitude)
        val outgoing = Bearing.degrees(b.latitude, b.longitude, c.latitude, c.longitude)
        return Bearing.difference(incoming, outgoing)
    }

    /** @return (lat, lng) a [fraction] of the way from [from] toward [toward]. */
    private fun pointTowards(from: PlotPoint, toward: PlotPoint, fraction: Double): Pair<Double, Double> {
        val clamped = fraction.coerceIn(0.0, 1.0)
        val lngDelta = Haversine.normaliseLongitudeDelta(toward.longitude - from.longitude)
        return Pair(
            from.latitude + (toward.latitude - from.latitude) * clamped,
            from.longitude + lngDelta * clamped,
        )
    }

    public const val MIN_ANGLE_DEG: Double = 30.0
    public const val MAX_CUTBACK_M: Double = 25.0
    public const val POINTS_PER_CURVE: Int = 5
    private const val NEIGHBOUR_FRACTION = 0.4
}
