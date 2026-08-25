package com.field360.traker.geo.plot

import com.field360.traker.geo.math.Bearing
import com.field360.traker.geo.math.Geodesy
import com.field360.traker.geo.math.Haversine
import com.field360.traker.geo.model.GeoPoint
import com.field360.traker.geo.plot.model.PlotPoint
import com.field360.traker.geo.plot.model.RenderTag
import kotlin.math.cos
import kotlin.math.pow

/**
 * Douglas-Peucker line simplification (SMOOTH-NAV-PLAN Phase 4).
 *
 * The stage the plotting plane never had. Without it every stored vertex reaches the
 * spline, which resamples at 5 m and hands the encoder ~1 vertex per 5 m of track —
 * a 50 km drive approaches ten thousand points in a string a renderer decodes on every
 * frame. Simplifying *before* smoothing is the standard order: it removes vertices the
 * curve would otherwise be obliged to pass through, so the same track draws smoother
 * with fewer points, not rougher.
 *
 * **Douglas-Peucker, not Visvalingam-Whyatt.** DP's max-perpendicular-distance test
 * keeps whatever deviates — which on a road track means corners. Visvalingam removes
 * the smallest-area triangles, which are exactly the sharp short-legged turns a
 * junction produces; it generalises coastlines beautifully and rounds off the geometry
 * this SDK exists to record.
 *
 * **Anchors are never dropped.** Segment boundaries and stop nodes address the drawn
 * path by [PlotPoint.sourceIndex] ([Snapper.spanFor]); a simplifier free to delete
 * those vertices would silently empty a segment's polyline. Simplification runs
 * *between* anchors, so every anchor survives by construction rather than by luck.
 */
public object Simplify {

    /**
     * @param anchors `sourceIndex` values that must survive — cluster boundaries and
     *   significant nodes. Protected points ([PlotPoint.isProtected]) are anchors too,
     *   without being listed.
     * @param epsilonM `0` or less disables simplification and returns [path] unchanged.
     */
    public fun simplify(
        path: List<PlotPoint>,
        epsilonM: Double,
        anchors: Set<Int> = emptySet(),
    ): List<PlotPoint> = simplify(path, epsilonM) { it.isProtected || it.sourceIndex in anchors }

    /**
     * Simplification between the vertices [isAnchor] protects. The first and last
     * points of [path] are anchors whether or not the predicate says so.
     */
    public fun simplify(
        path: List<PlotPoint>,
        epsilonM: Double,
        isAnchor: (PlotPoint) -> Boolean,
    ): List<PlotPoint> {
        if (epsilonM <= 0.0 || path.size <= 2) return path

        val out = mutableListOf<PlotPoint>()
        var runStart = 0
        for (i in 1..path.lastIndex) {
            if (i != path.lastIndex && !isAnchor(path[i])) continue

            val run = douglasPeucker(path.subList(runStart, i + 1), epsilonM)
            // The run's first point is the previous run's last: emit it once.
            out += if (out.isEmpty()) run else run.drop(1)
            runStart = i
        }
        return out
    }

    /**
     * Thins a **smoothed** path by dropping resampled points the curve does not need,
     * keeping every vertex that came from a fix or from a road.
     *
     * This is where the polyline size actually comes down, and the reason the
     * pre-smoothing pass cannot do it alone: [Spline] resamples at a fixed spacing, so
     * a 140 m leg becomes ~28 vertices whether its endpoints were simplified or not.
     * Running Douglas-Peucker over the *result* makes that density adaptive — a
     * straight leg collapses back to its endpoints while a bend keeps every sample it
     * needs to stay within [epsilonM] of the curve.
     *
     * Anchoring on provenance is what keeps it safe: only [RenderTag.ROUNDED_CURVE]
     * points are candidates, so every source index survives and [Snapper.spanFor] still
     * finds the segment boundaries it slices on (EC-102a).
     */
    public fun simplifyRendered(path: List<PlotPoint>, epsilonM: Double): List<PlotPoint> =
        simplify(path, epsilonM) { it.tag != RenderTag.ROUNDED_CURVE }

    /**
     * The `sourceIndex` values where the path turns hard enough that deleting the vertex
     * would change the shape a reader can see — corners, to be passed to [simplify] as
     * anchors.
     *
     * Douglas-Peucker keeps whatever deviates *from a chord*, and that is not the same
     * test as "keeps corners", however often the two coincide. Deviation is measured in
     * metres, so a 90° turn between two 6 m legs deviates about 4 m and dies under any
     * tolerance above that, while a 3° kink in the middle of a 400 m straight deviates
     * 10 m and survives. At the default 2 m tolerance the difference rarely shows; at the
     * tolerance [epsilonForZoom] returns for a zoomed-out redraw — tens of metres — the
     * simplifier reaches the point of deleting junctions and leaving the noise, and the
     * track flattens into the straight-legs-and-jumps shape that corners disappearing
     * always produces.
     *
     * Angle is the right test for a corner, so this measures the angle and hands the
     * result to the simplifier as something it may not touch. Cost is a handful of
     * retained vertices; the tolerance elsewhere stays as loose as the caller set it,
     * which is the point of anchoring rather than lowering epsilon globally.
     *
     * @param minTurnDeg the turn, in degrees, at which a vertex becomes a corner. Legs
     *   shorter than [minLegM] are ignored on both sides: the angle between two 2 m legs
     *   is GPS scatter, and anchoring on it would pin the noise this stage exists to
     *   remove.
     */
    public fun cornerAnchors(
        path: List<PlotPoint>,
        minTurnDeg: Double = DEFAULT_CORNER_TURN_DEG,
        minLegM: Double = DEFAULT_CORNER_MIN_LEG_M,
    ): Set<Int> {
        if (path.size < 3) return emptySet()

        val anchors = mutableSetOf<Int>()
        for (i in 1 until path.lastIndex) {
            val previous = path[i - 1]
            val vertex = path[i]
            val next = path[i + 1]

            val legIn = Haversine.metres(previous.latitude, previous.longitude, vertex.latitude, vertex.longitude)
            val legOut = Haversine.metres(vertex.latitude, vertex.longitude, next.latitude, next.longitude)
            if (legIn < minLegM || legOut < minLegM) continue

            val inbound = Bearing.degrees(previous.latitude, previous.longitude, vertex.latitude, vertex.longitude)
            val outbound = Bearing.degrees(vertex.latitude, vertex.longitude, next.latitude, next.longitude)
            if (Bearing.difference(inbound, outbound) >= minTurnDeg) anchors += vertex.sourceIndex
        }
        return anchors
    }

    /**
     * Classic Douglas-Peucker over one run. Endpoints always survive.
     *
     * Iterative, not recursive: a day of dense walking is tens of thousands of points,
     * and the recursive formulation is one pathological zigzag away from a stack
     * overflow inside a rendering call.
     */
    public fun douglasPeucker(path: List<PlotPoint>, epsilonM: Double): List<PlotPoint> {
        if (path.size <= 2 || epsilonM <= 0.0) return path

        val keep = BooleanArray(path.size)
        keep[0] = true
        keep[path.lastIndex] = true

        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(0 to path.lastIndex)

        while (stack.isNotEmpty()) {
            val (first, last) = stack.removeLast()
            if (last <= first + 1) continue

            val a = GeoPoint(path[first].latitude, path[first].longitude)
            val b = GeoPoint(path[last].latitude, path[last].longitude)

            var worstIndex = -1
            var worstDistance = epsilonM
            for (i in first + 1 until last) {
                val distance = Geodesy.crossTrackMeters(GeoPoint(path[i].latitude, path[i].longitude), a, b)
                if (distance > worstDistance) {
                    worstDistance = distance
                    worstIndex = i
                }
            }

            // Nothing in this span deviates by more than epsilon: the chord represents
            // all of it, and every interior point goes.
            if (worstIndex < 0) continue

            keep[worstIndex] = true
            stack.addLast(first to worstIndex)
            stack.addLast(worstIndex to last)
        }

        return path.filterIndexed { i, _ -> keep[i] }
    }

    /**
     * Simplification tolerance for a rendering at [zoom], in metres — the tolerance
     * that maps to [tolerancePx] screen pixels at that zoom and [latitude].
     *
     * Web-Mercator ground resolution: `156543.03392 · cos(lat) / 2^zoom` metres per
     * pixel. Offered for hosts that redraw a *finished* track as the camera moves;
     * deliberately not applied to the live tail, whose contract is append-only —
     * re-simplifying it would rewrite geometry a renderer has already drawn.
     */
    public fun epsilonForZoom(zoom: Float, latitude: Double, tolerancePx: Double = DEFAULT_TOLERANCE_PX): Double {
        val metersPerPixel = EQUATOR_METERS_PER_PIXEL * cos(Math.toRadians(latitude)) / 2.0.pow(zoom.toDouble())
        return metersPerPixel * tolerancePx
    }

    /** Total length of a path, metres — for reporting what simplification cost. */
    public fun lengthMeters(path: List<PlotPoint>): Double =
        path.zipWithNext { a, b -> Haversine.metres(a.latitude, a.longitude, b.latitude, b.longitude) }.sum()

    /**
     * Ground resolution at zoom 0, metres per pixel, for 256 px tiles —
     * `2πR / 256` on the Web-Mercator sphere.
     */
    public const val EQUATOR_METERS_PER_PIXEL: Double = 156_543.033_92

    /** Sub-pixel deviation is invisible; 1.5 px keeps a comfortable margin. */
    public const val DEFAULT_TOLERANCE_PX: Double = 1.5

    /**
     * 20° — below a slip road, above the heading spread two consecutive fixes produce on
     * a straight road once [DEFAULT_CORNER_MIN_LEG_M] has been cleared.
     */
    public const val DEFAULT_CORNER_TURN_DEG: Double = 20.0

    /**
     * Matches `TrackerConstants.bearingCaptureMinDist`, and for the same reason: the
     * bearing between two points 15 m apart is a direction, and below that it is a
     * function of the accuracy circle.
     */
    public const val DEFAULT_CORNER_MIN_LEG_M: Double = 15.0
}
