package com.field360.traker.geo.plot

import com.field360.traker.geo.math.Bearing
import com.field360.traker.geo.math.Haversine
import com.field360.traker.geo.plot.model.PlotPoint
import com.field360.traker.geo.plot.model.RenderTag
import com.field360.traker.geo.plot.model.Smoothing
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/**
 * Resamples a sparse track into a curve whose tangent at every vertex is the heading the
 * device actually recorded there (EC-45c).
 *
 * [Spline] answers the complaint that a 120 m leg draws as a visible straight line, and
 * it answers it well. What it cannot answer is the corner, because of where it gets its
 * tangents: centripetal Catmull-Rom derives the direction at a vertex from the vertices
 * *either side* of it, and through a turn those two sit on opposite legs. The derived
 * tangent is therefore the chord across the corner — the curve leaves the vertex pointing
 * along a direction the vehicle was never pointing, cuts the inside of the turn, and at
 * the sampling cadences this SDK runs at that is most of what "the polyline does not take
 * the turn properly" means.
 *
 * Every stored point already carries the answer. `TrackPoint.bearingDeg` is the chipset's
 * Doppler heading at that instant — the true tangent, measured, not inferred — and using
 * it as the Hermite tangent puts the curve on the vehicle's real heading entering and
 * leaving each fix. The corner then appears **between** two fixes, from two headings,
 * without either fix having sampled its apex. That is the one thing no amount of
 * post-processing on positions alone can do.
 *
 * Three things this is honest about:
 *
 *  - **It is still an assumption.** Two headings and two positions do not determine the
 *    road between them; they determine a plausible curve that agrees with both ends. Where
 *    the real shape matters the answer remains map-matching ([Snapper]), and snapped
 *    geometry passes through untouched.
 *  - **It degrades, it does not fail.** A vertex with no recorded bearing takes the
 *    Catmull-Rom tangent *direction* — the chord across its two neighbours — so a track
 *    from a chipset that reports no heading draws the same shape [Spline] draws, to
 *    within the difference between chord-scaled and centripetal tangent magnitudes. It is
 *    close, not identical, and no test here asserts that it is identical.
 *  - **G1, not C1.** Tangent *directions* are continuous across vertices; their
 *    magnitudes are scaled per span by that span's own chord, so curvature can jump at a
 *    knot. Visually that is invisible, and the alternative — one global parameterisation —
 *    reintroduces the overshoot on unevenly spaced controls that centripetal
 *    parameterisation exists to avoid.
 */
public object HeadingSpline {

    /**
     * @param path in plot order. Interpolated points inherit the `sourceIndex` of the
     *   vertex that starts their span, so [Snapper.spanFor] still slices segments and
     *   arrows correctly out of the result (EC-102a).
     * @param maxTangentDeviationDeg how far a recorded heading may disagree with the
     *   chord before it is trimmed back towards it. Past 90° the Hermite curve reverses
     *   and draws a loop, so this is a hard requirement rather than a taste setting — see
     *   [DEFAULT_MAX_TANGENT_DEVIATION_DEG].
     */
    public fun smooth(
        path: List<PlotPoint>,
        spacingM: Double = Smoothing.DEFAULT_SPACING_M,
        maxPerSpan: Int = Spline.DEFAULT_MAX_PER_SPAN,
        maxTangentDeviationDeg: Double = DEFAULT_MAX_TANGENT_DEVIATION_DEG,
    ): List<PlotPoint> {
        if (path.size < 2) return path

        // Run-splitting on road geometry, for the reason `Spline.smooth` does it: a
        // smoother has no business improving on a road's own shape, and standing the whole
        // stage down because *some* vertex was snapped leaves every off-road leg drawing
        // the raw chord this exists to remove (EC-101).
        if (path.none { it.tag == RenderTag.SNAPPED_TO_ROAD }) {
            return smoothRun(path, spacingM, maxPerSpan, maxTangentDeviationDeg)
        }

        val out = mutableListOf<PlotPoint>()
        var i = 0
        while (i <= path.lastIndex) {
            if (path[i].tag == RenderTag.SNAPPED_TO_ROAD) {
                out += path[i]
                i++
                continue
            }
            val start = i
            while (i <= path.lastIndex && path[i].tag != RenderTag.SNAPPED_TO_ROAD) i++
            val run = path.subList(start, i)
            out += if (run.size >= 2) smoothRun(run, spacingM, maxPerSpan, maxTangentDeviationDeg) else run
        }
        return out
    }

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * One run of non-snapped vertices.
     *
     * Two vertices are enough here, unlike [Spline], which needs three before a
     * Catmull-Rom span has neighbours to take its tangents from. With headings the
     * tangents come from the endpoints themselves, so a two-vertex run — a leg either
     * side of a snapped section, or a track with one span in it — still curves.
     */
    private fun smoothRun(
        path: List<PlotPoint>,
        spacingM: Double,
        maxPerSpan: Int,
        maxTangentDeviationDeg: Double,
    ): List<PlotPoint> {
        if (path.size < 2) return path

        // Coincident controls make the chord length zero and every direction below
        // undefined. Dropping them changes no geometry — they plot on top of each other.
        val knots = path.filterIndexed { i, p ->
            i == 0 || i == path.lastIndex || Haversine.metres(
                path[i - 1].latitude, path[i - 1].longitude, p.latitude, p.longitude,
            ) > Spline.COINCIDENT_M
        }
        if (knots.size < 2) return path

        // Same plane as `Spline.smoothRun`: latitude in degrees for y, longitude as a
        // delta from a reference knot scaled by cos(lat) for x, so a degree east and a
        // degree north are the same distance and the antimeridian cannot produce a
        // 40 000 km step between 179.99 and -179.99 (EC-26).
        val lngScale = cos(Math.toRadians(knots[knots.size / 2].latitude))
        val lngOrigin = knots.first().longitude
        val xs = DoubleArray(knots.size) {
            Haversine.normaliseLongitudeDelta(knots[it].longitude - lngOrigin) * lngScale
        }
        val ys = DoubleArray(knots.size) { knots[it].latitude }

        val tangents = DoubleArray(knots.size) { tangentAngleAt(knots, xs, ys, it) }

        val out = mutableListOf(knots.first())

        for (i in 0 until knots.lastIndex) {
            val start = knots[i]
            val end = knots[i + 1]

            val dx = xs[i + 1] - xs[i]
            val dy = ys[i + 1] - ys[i]
            val chordLength = Math.hypot(dx, dy)
            val chordAngle = angleOf(dx, dy)

            // A Hermite span whose tangents equal its chord *is* the chord, so both the
            // degenerate case and the no-curvature case fall out of the same arithmetic
            // rather than needing a branch.
            val a0 = trimTowards(chordAngle, tangents[i], maxTangentDeviationDeg)
            val a1 = trimTowards(chordAngle, tangents[i + 1], maxTangentDeviationDeg)

            // Tangent magnitude equal to the chord: with both tangents on the chord's own
            // direction this reproduces the straight line exactly, so a straight road is
            // still drawn straight however dense the resampling.
            val m0x = sin(Math.toRadians(a0)) * chordLength
            val m0y = cos(Math.toRadians(a0)) * chordLength
            val m1x = sin(Math.toRadians(a1)) * chordLength
            val m1y = cos(Math.toRadians(a1)) * chordLength

            val spanM = Haversine.metres(start.latitude, start.longitude, end.latitude, end.longitude)
            val steps = ceil(spanM / spacingM).toInt().coerceIn(1, maxPerSpan)

            for (step in 1 until steps) {
                val u = step.toDouble() / steps
                val x = hermite(xs[i], m0x, xs[i + 1], m1x, u)
                val y = hermite(ys[i], m0y, ys[i + 1], m1y, u)
                out += end.copy(
                    latitude = y,
                    longitude = Haversine.normaliseLongitudeDelta(lngOrigin + x / lngScale),
                    sourceIndex = start.sourceIndex,
                    tag = RenderTag.ROUNDED_CURVE,
                    isProtected = false,
                    // An interpolated vertex has no recorded heading, and the curve's own
                    // tangent there is not one. Claiming it as a bearing would let a later
                    // pass over this output treat a computed direction as a measurement.
                    bearingDeg = PlotPoint.BEARING_UNSET,
                )
            }
            // The span's own endpoint verbatim, never a sample at u = 1: rounding there
            // would nudge a protected vertex off where it was recorded (EC-103).
            out += end
        }
        return out
    }

    /**
     * The curve's direction at knot [i], degrees clockwise from north.
     *
     * Recorded heading where there is one. Otherwise the Catmull-Rom direction — the
     * chord across the knot's two neighbours — which is what [Spline] would have used for
     * the whole path, so a bearing-less track keeps its previous shape rather than
     * acquiring some new approximation of it.
     */
    private fun tangentAngleAt(knots: List<PlotPoint>, xs: DoubleArray, ys: DoubleArray, i: Int): Double {
        val recorded = knots[i]
        if (recorded.hasBearing) return recorded.bearingDeg.toDouble()

        val before = (i - 1).coerceAtLeast(0)
        val after = (i + 1).coerceAtMost(knots.lastIndex)
        return angleOf(xs[after] - xs[before], ys[after] - ys[before])
    }

    /**
     * [angle] pulled back towards [reference] until it is no further than [limitDeg] from
     * it, preserving which side of the reference it was on.
     *
     * Not cosmetic. A cubic Hermite whose tangent points more than 90° away from its chord
     * travels backwards before it turns round, which draws a visible loop; at exactly 90°
     * it forms a cusp. Recorded headings do land there — a fix taken mid-U-turn, a
     * stationary-drift heading that survived the capture gates, a hairpin sampled once —
     * and the drawn line must degrade to something flatter than the truth rather than to
     * a knot in the road.
     */
    private fun trimTowards(reference: Double, angle: Double, limitDeg: Double): Double {
        val offset = Bearing.signedDifference(reference, angle)
        if (abs(offset) <= limitDeg) return angle
        return reference + limitDeg * if (offset < 0) -1.0 else 1.0
    }

    /** Direction of a vector in the plot plane, degrees clockwise from north (`+y`). */
    private fun angleOf(dx: Double, dy: Double): Double =
        (Math.toDegrees(atan2(dx, dy)) + 360.0) % 360.0

    /** Cubic Hermite basis on one axis, `u` in `(0, 1)`. */
    private fun hermite(p0: Double, m0: Double, p1: Double, m1: Double, u: Double): Double {
        val u2 = u * u
        val u3 = u2 * u
        return (2 * u3 - 3 * u2 + 1) * p0 +
            (u3 - 2 * u2 + u) * m0 +
            (-2 * u3 + 3 * u2) * p1 +
            (u3 - u2) * m1
    }

    /**
     * 75°, comfortably inside the 90° at which a Hermite span cusps.
     *
     * A genuine junction reaches roughly half of it — the heading at a fix taken on the
     * approach differs from the chord to the fix after the corner by about a quarter of
     * the turn — so this trims pathological headings without touching the corners the
     * stage exists to draw.
     */
    public const val DEFAULT_MAX_TANGENT_DEVIATION_DEG: Double = 75.0
}
