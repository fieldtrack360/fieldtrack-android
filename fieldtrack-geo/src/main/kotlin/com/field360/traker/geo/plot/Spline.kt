package com.field360.traker.geo.plot

import com.field360.traker.geo.math.Haversine
import com.field360.traker.geo.model.GeoPoint
import com.field360.traker.geo.plot.model.PlotPoint
import com.field360.traker.geo.plot.model.RenderTag
import com.field360.traker.geo.plot.model.Smoothing
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.pow

/**
 * Resamples a sparse track into a continuous curve (EC-45b).
 *
 * [BezierRounding] rounds *corners* — a vertex turning more than 30° gets a curve cut
 * across it and everything else is left as a chord. That is the right treatment for a
 * sawtooth and no treatment at all for the complaint that actually arrives, which is that
 * a 12 s cadence at 10 m/s puts vertices 120 m apart and the line between them is
 * visibly, unarguably straight. Rounding the joins does not help when the problem is the
 * legs.
 *
 * So this fits a curve through **every** vertex and resamples it at
 * [Smoothing.DEFAULT_SPACING_M].
 *
 * **Centripetal Catmull-Rom** (α = 0.5), not uniform. Uniform is the obvious
 * implementation and it is wrong for this data: on unevenly spaced control points it
 * overshoots, and where two points are nearly coincident — which GPS produces constantly
 * — it forms cusps and self-intersecting loops. Centripetal parameterisation is *provably*
 * free of both (Yuksel et al., 2011), which is the only reason it is safe to run over
 * field data unattended.
 *
 * **Interpolating, not approximating.** The curve passes exactly through every input
 * vertex, so protected points need no special case the way they do under Bézier: session
 * bookends and host markers come out where they went in (EC-103).
 *
 * Honest about what it is: a *rendering* transform. It makes the line smooth; it does not
 * make it correct. Between two fixes 120 m apart the curve is an assumption about a road
 * nobody measured, and where that road's real shape matters the answer is still
 * map-matching ([Snapper]) — which is why a snapped path is returned untouched.
 */
public object Spline {

    /**
     * @param path in plot order. Interpolated points inherit the `sourceIndex` of the
     *   vertex that starts their span, so [Snapper.spanFor] still slices segments and
     *   arrows correctly out of the result (EC-102a).
     */
    public fun smooth(
        path: List<PlotPoint>,
        spacingM: Double = Smoothing.DEFAULT_SPACING_M,
        maxPerSpan: Int = DEFAULT_MAX_PER_SPAN,
    ): List<PlotPoint> {
        if (path.size < 3) return path
        // The common case, and the only one before per-run standdown existed: no road
        // geometry anywhere, so the whole path is one run.
        if (path.none { it.tag == RenderTag.SNAPPED_TO_ROAD }) return smoothRun(path, spacingM, maxPerSpan)

        // Otherwise smooth run by run (SMOOTH-NAV-PLAN Phase 4). Standing the whole
        // smoother down because *some* vertex came from a road left a partially snapped
        // track drawing raw 120 m chords on every off-road leg — the exact complaint the
        // spline exists to answer, reappearing whenever snapping half-worked. Road
        // geometry still passes through untouched: a smoother has no business improving
        // on the road's own shape (EC-101).
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
            // A run of one or two vertices has no interior to curve; it joins its
            // neighbours as the chord it already was.
            out += if (run.size >= 3) smoothRun(run, spacingM, maxPerSpan) else run
        }
        return out
    }

    /** One run of non-snapped vertices — the whole path, before snapping splits it. */
    private fun smoothRun(
        path: List<PlotPoint>,
        spacingM: Double,
        maxPerSpan: Int,
    ): List<PlotPoint> {
        if (path.size < 3) return path

        // Coincident controls make the centripetal knot spacing zero and the
        // parameterisation degenerate. Dropping them changes no geometry — they plot on
        // top of each other — but it is what keeps the divisions below defined.
        val knots = path.filterIndexed { i, p ->
            i == 0 || i == path.lastIndex || Haversine.metres(
                path[i - 1].latitude, path[i - 1].longitude, p.latitude, p.longitude,
            ) > COINCIDENT_M
        }
        if (knots.size < 3) return path

        // Longitude scaled by cos(lat) so a degree east and a degree north are the same
        // distance. Without it the knot spacing is anisotropic and the curve leans.
        val lngScale = cos(Math.toRadians(knots[knots.size / 2].latitude))
        // Longitudes are carried as deltas from a reference knot, wrapped into
        // ±180. Feeding raw longitudes in makes a track crossing the antimeridian
        // see a ~40 000 km step between 179.99 and -179.99 and draw a curve across the
        // planet (EC-26) — the guard `BezierRounding` and `Arrows` already had.
        val lngOrigin = knots.first().longitude
        val xs = DoubleArray(knots.size) {
            Haversine.normaliseLongitudeDelta(knots[it].longitude - lngOrigin) * lngScale
        }
        val ys = DoubleArray(knots.size) { knots[it].latitude }

        val out = mutableListOf(knots.first())

        for (i in 0 until knots.lastIndex) {
            // Endpoints duplicated so the first and last spans are curved too, rather than
            // left as the chords this exists to remove.
            val i0 = (i - 1).coerceAtLeast(0)
            val i3 = (i + 2).coerceAtMost(knots.lastIndex)

            val start = knots[i]
            val end = knots[i + 1]
            val spanM = Haversine.metres(start.latitude, start.longitude, end.latitude, end.longitude)
            val steps = ceil(spanM / spacingM).toInt().coerceIn(1, maxPerSpan)

            val t = knotVector(xs, ys, i0, i, i + 1, i3)

            for (step in 1 until steps) {
                val u = step.toDouble() / steps
                out += end.copy(
                    latitude = evaluate(ys, t, i0, i, i + 1, i3, u),
                    longitude = Haversine.normaliseLongitudeDelta(
                        lngOrigin + evaluate(xs, t, i0, i, i + 1, i3, u) / lngScale,
                    ),
                    sourceIndex = start.sourceIndex,
                    tag = RenderTag.ROUNDED_CURVE,
                    isProtected = false,
                    // `end.copy` would otherwise hand every interpolated vertex the
                    // heading recorded at the span's endpoint, which was measured tens of
                    // metres further along. Nothing downstream reads it today; leaving it
                    // set would be a measurement claimed for a point that is an
                    // interpolation.
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
     * The interior samples of ONE span — the curve between [p1] and [p2], with [p0] and
     * [p3] as the neighbouring knots that shape its tangents. Excludes both endpoints.
     *
     * This is [smooth]'s inner loop exposed for the live surface (SMOOTH-NAV-PLAN
     * Phase 2): a live track freezes each span the moment its last neighbour exists, and
     * freezing it with this function is what makes the frozen tail agree with what
     * [smooth] would later produce for the same knots. Clamp a missing neighbour by
     * duplication (`p0 = p1` at the start of a track, `p3 = p2` at its live end) —
     * the same handling [smooth] applies at its endpoints.
     *
     * One honest divergence: [smooth] scales longitude by `cos(lat)` of the *whole
     * path's* middle knot, which a growing track cannot know yet; this uses [p1]'s
     * latitude. Sub-centimetre for any track that fits in a city, and the reason the
     * agreement is "within tolerance", not byte-identical.
     */
    public fun sampleSpan(
        p0: GeoPoint,
        p1: GeoPoint,
        p2: GeoPoint,
        p3: GeoPoint,
        spacingM: Double = Smoothing.DEFAULT_SPACING_M,
        maxPerSpan: Int = DEFAULT_MAX_PER_SPAN,
    ): List<GeoPoint> {
        val lngScale = cos(Math.toRadians(p1.latitude))
        // Deltas from p1, wrapped — same antimeridian guard as `smooth` (EC-26).
        val lngOrigin = p1.longitude
        fun x(p: GeoPoint) = Haversine.normaliseLongitudeDelta(p.longitude - lngOrigin) * lngScale
        val xs = doubleArrayOf(x(p0), x(p1), x(p2), x(p3))
        val ys = doubleArrayOf(p0.latitude, p1.latitude, p2.latitude, p3.latitude)

        val spanM = Haversine.metres(p1.latitude, p1.longitude, p2.latitude, p2.longitude)
        val steps = ceil(spanM / spacingM).toInt().coerceIn(1, maxPerSpan)
        val t = knotVector(xs, ys, 0, 1, 2, 3)

        val out = mutableListOf<GeoPoint>()
        for (step in 1 until steps) {
            val u = step.toDouble() / steps
            out += GeoPoint(
                evaluate(ys, t, 0, 1, 2, 3, u),
                Haversine.normaliseLongitudeDelta(lngOrigin + evaluate(xs, t, 0, 1, 2, 3, u) / lngScale),
            )
        }
        return out
    }

    /**
     * Centripetal knot vector for one span: `t[n+1] = t[n] + |Δp|^0.5`.
     *
     * The square root is what makes this *centripetal* rather than uniform (`^0`) or
     * chordal (`^1`), and it is the exponent that buys the no-cusp guarantee.
     */
    private fun knotVector(xs: DoubleArray, ys: DoubleArray, i0: Int, i1: Int, i2: Int, i3: Int): DoubleArray {
        fun step(a: Int, b: Int): Double {
            val dx = xs[b] - xs[a]
            val dy = ys[b] - ys[a]
            return (dx * dx + dy * dy).pow(ALPHA / 2.0)
        }
        val t = DoubleArray(4)
        t[0] = 0.0
        t[1] = t[0] + step(i0, i1)
        t[2] = t[1] + step(i1, i2)
        t[3] = t[2] + step(i2, i3)
        return t
    }

    /** Barry-Goldman pyramid for one axis at [u] in `(0, 1)` across the middle span. */
    @Suppress("LongParameterList")
    private fun evaluate(
        v: DoubleArray,
        t: DoubleArray,
        i0: Int,
        i1: Int,
        i2: Int,
        i3: Int,
        u: Double,
    ): Double {
        // A duplicated endpoint gives a zero-length knot interval. Fall back to the
        // straight chord for that span rather than dividing by it.
        if (t[1] <= t[0] || t[2] <= t[1] || t[3] <= t[2]) return v[i1] + (v[i2] - v[i1]) * u

        val tt = t[1] + (t[2] - t[1]) * u
        val a1 = (t[1] - tt) / (t[1] - t[0]) * v[i0] + (tt - t[0]) / (t[1] - t[0]) * v[i1]
        val a2 = (t[2] - tt) / (t[2] - t[1]) * v[i1] + (tt - t[1]) / (t[2] - t[1]) * v[i2]
        val a3 = (t[3] - tt) / (t[3] - t[2]) * v[i2] + (tt - t[2]) / (t[3] - t[2]) * v[i3]
        val b1 = (t[2] - tt) / (t[2] - t[0]) * a1 + (tt - t[0]) / (t[2] - t[0]) * a2
        val b2 = (t[3] - tt) / (t[3] - t[1]) * a2 + (tt - t[1]) / (t[3] - t[1]) * a3
        return (t[2] - tt) / (t[2] - t[1]) * b1 + (tt - t[1]) / (t[2] - t[1]) * b2
    }

    /**
     * Ceiling on samples for one span, so a blackout leg does not emit thousands of
     * vertices for a curve nobody measured.
     */
    public const val DEFAULT_MAX_PER_SPAN: Int = 64

    /** Centripetal. 0 would be uniform, 1 chordal; only this value has the guarantee. */
    private const val ALPHA = 0.5

    /** Closer than this and two controls are the same point as far as the knots care. */
    public const val COINCIDENT_M: Double = 0.05
}
