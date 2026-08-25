package com.field360.traker.geo.plot

import com.field360.traker.geo.math.Haversine
import com.field360.traker.geo.model.Bounds
import com.field360.traker.geo.model.GeoPoint
import com.field360.traker.geo.model.TrackPoint
import com.field360.traker.geo.plot.model.ArrowAnchor
import com.field360.traker.geo.plot.model.PlotPoint
import com.field360.traker.geo.plot.model.SegmentType
import com.field360.traker.geo.plot.model.Smoothing
import com.field360.traker.geo.plot.model.StopNode
import com.field360.traker.geo.plot.model.Track
import com.field360.traker.geo.plot.model.TrackJsonPoint
import com.field360.traker.geo.plot.model.TrackOptions
import com.field360.traker.geo.plot.model.TrackSegment
import com.field360.traker.geo.plot.model.TrackStats

/**
 * The plotting plane, end to end:
 *
 * ```
 * points → consolidateStops → significantNodes → clusters → speed stats
 *        → labels → snap? → bezierRound → arrows → encodePolyline → stats
 * ```
 *
 * Entirely on-device and entirely pure — **including the snap stage**. [roadGeometry] is
 * geometry the caller already fetched, not a provider this function may call: the
 * network round-trip belongs to `fieldtrack-core`, and keeping it there is what lets a
 * track render with no backend, no routing key and no quota, and what keeps every rule
 * in [Snapper] testable against a hand-written road with no server (PLAN.md §5).
 */
public object TrackBuilder {

    /**
     * @param roadGeometry road vertices from a `RoadSnapProvider`, or
     *   [Snapper.RoadGeometry.None] when snapping was not requested. Anything the
     *   provider could not answer degrades to raw geometry plus a
     *   [WARNING_SNAP_UNAVAILABLE] — the track is never lost because a routing service
     *   was (EC-100).
     */
    public fun build(
        points: List<TrackPoint>,
        options: TrackOptions = TrackOptions(),
        sessionId: String? = null,
        nowMs: Long = 0,
        timezone: String = "UTC",
        warnings: List<String> = emptyList(),
        roadGeometry: Snapper.RoadGeometry = Snapper.RoadGeometry.None,
    ): Track {
        // Empty and single-point tracks are WELL-FORMED, never NaN-filled. A renderer
        // should be able to draw the result of any query without special-casing
        // (EC-93, EC-94).
        if (points.isEmpty()) {
            return Track(
                sessionId = sessionId,
                generatedAtMs = nowMs,
                timezone = timezone,
                precision = options.polylinePrecision,
                warnings = warnings,
            )
        }

        val collected = mutableListOf<String>().apply { addAll(warnings) }
        if (points.any { it.isMock }) collected += WARNING_MOCK_PRESENT

        val consolidated = if (options.consolidateStops) {
            Consolidation.consolidate(points, options.stopRadiusM, options.stopMinDwellSec)
        } else {
            points
        }

        if (consolidated.size == 1) {
            return singlePointTrack(consolidated.first(), options, sessionId, nowMs, timezone, collected)
        }

        val nodeIndices = SignificantNodes.detect(consolidated)
        val clusters = Clusters.build(consolidated, nodeIndices)

        val plotPath = consolidated.mapIndexed { index, point ->
            PlotPoint(
                latitude = point.latitude,
                longitude = point.longitude,
                timeMs = point.timeMs,
                sourceIndex = index,
                activity = point.detectedActivity,
                // First and last are session bookends and must never be moved (EC-103).
                isProtected = index == 0 || index == consolidated.lastIndex,
                bearingDeg = plotBearingOf(point),
            )
        }

        // Simplify before snapping and smoothing — the standard order (SMOOTH-NAV-PLAN
        // Phase 4). A redundant vertex fed to the spline is a vertex the curve is
        // obliged to pass through, so removing it first makes the line both smaller and
        // smoother. Cluster boundaries and timeline nodes are anchors: segments and
        // arrows address the drawn path by source index, and a simplifier free to drop
        // those vertices would quietly empty a segment's polyline (EC-102a).
        //
        // Corners are anchors too, and for a reason distinct from the other two: they are
        // shape rather than addressing. Douglas-Peucker's test is perpendicular distance
        // in metres, which under-weights exactly the geometry a junction produces — a
        // sharp turn between two short legs — so at any tolerance looser than the 2 m
        // default the simplifier will delete the corner and keep a gentle kink halfway
        // down a straight. Anchoring by angle is what stops that, without loosening the
        // tolerance for the straights ([Simplify.cornerAnchors]).
        val anchors = buildSet {
            addAll(nodeIndices)
            clusters.forEach { cluster ->
                add(cluster.fromIndex)
                add(cluster.toIndex)
            }
            addAll(Simplify.cornerAnchors(plotPath))
        }
        val simplified = Simplify.simplify(plotPath, options.simplifyEpsilonM, anchors)

        // Snap before rounding: rounding exists to make sparse sampling look less like a
        // sawtooth, and where the road's own geometry has just been injected there is
        // nothing sparse left to hide (API.md §12).
        val snapped = if (options.snapToRoad && roadGeometry is Snapper.RoadGeometry.Snapped) {
            Snapper.snap(simplified, roadGeometry.path, options.snapMaxOffRoadM)
        } else {
            simplified
        }
        if (options.snapToRoad && roadGeometry.isUnavailable) collected += WARNING_SNAP_UNAVAILABLE

        val smoothed = when (options.smoothing) {
            Smoothing.SPLINE -> Spline.smooth(snapped, options.splineSpacingM)
            Smoothing.HEADING_SPLINE -> HeadingSpline.smooth(snapped, options.splineSpacingM)
            Smoothing.BEZIER ->
                BezierRounding.round(snapped, options.bezierMinAngleDeg, options.bezierCutbackM)
            Smoothing.NONE -> snapped
        }

        // The second simplification pass, and the one that actually bounds the polyline.
        // Smoothing resamples at a fixed spacing, so a straight 140 m leg arrives here as
        // ~28 vertices that a two-point chord would draw identically. Only resampled
        // points are candidates — every vertex that came from a fix or a road survives,
        // so `spanFor` still finds its boundaries (EC-102a).
        val rendered = Simplify.simplifyRendered(smoothed, options.simplifyEpsilonM)

        val geometry = rendered.map { GeoPoint(it.latitude, it.longitude) }
        val encoded = PolylineCodec.encode(geometry, options.polylinePrecision)

        // Segments slice out of `rendered`, arrows out of `snapped`, and the difference is
        // deliberate.
        //
        // Segments were the bug: they sliced the pre-smoothing path, so the track-level
        // polyline was the *only* geometry smoothing ever reached, and a host drawing
        // per-segment speed bands — the reason segments carry their own polyline at all —
        // saw none of it.
        //
        // Arrows stay on the sparse path because their spacing ladder thins by distance
        // (EC-106a): run over a path resampled every 5 m it finds legs everywhere and the
        // count roughly doubles, which is vertex density deciding arrow density rather
        // than zoom. Anchoring to the original vertices costs nothing in accuracy here,
        // because a Catmull-Rom spline *interpolates* — every one of those vertices lies
        // exactly on the drawn curve, so the arrows sit on the line rather than beside it
        // (EC-102a). That would not hold under Bézier, which only approaches its control
        // points.
        val segments = buildSegments(consolidated, clusters, options, rendered)
        val stops = buildStops(consolidated, clusters, nodeIndices, nowMs)
        val arrows = buildArrows(consolidated, clusters, options, snapped)

        return Track(
            sessionId = sessionId,
            generatedAtMs = nowMs,
            from = consolidated.first().timeMs,
            to = consolidated.last().timeMs,
            timezone = timezone,
            precision = options.polylinePrecision,
            bounds = boundsOf(consolidated),
            stats = statsOf(consolidated, clusters, stops),
            encodedPolyline = encoded,
            points = if (options.includeRawPoints) jsonPoints(consolidated) else emptyList(),
            segments = segments,
            stops = stops,
            arrows = arrows,
            warnings = collected,
        )
    }

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * @param snapped the path after the snap stage. A segment's polyline is sliced out of
     *   it by source index rather than rebuilt from `cluster.points`, so a renderer
     *   colouring spans draws the same road as the track-level polyline. Slicing by
     *   position instead would break the moment injection changed the list's length.
     */
    private fun buildSegments(
        points: List<TrackPoint>,
        clusters: List<Clusters.Segment>,
        options: TrackOptions,
        snapped: List<PlotPoint>,
    ): List<TrackSegment> = clusters.map { cluster ->
        val label = ActivityLabels.label(
            detected = dominantActivity(cluster),
            maxSpeedMps = cluster.stats.maxSpeedMps,
            p75SpeedMps = cluster.stats.p75SpeedMps,
        )
        val span = Snapper.spanFor(snapped, cluster.fromIndex, cluster.toIndex)
        val geometry = if (span.isEmpty()) {
            cluster.points.map { GeoPoint(it.latitude, it.longitude) }
        } else {
            span.map { GeoPoint(it.latitude, it.longitude) }
        }

        if (cluster.isRealMovement) {
            TrackSegment(
                from = cluster.fromIndex,
                to = cluster.toIndex,
                type = SegmentType.TRAVEL,
                startMs = cluster.startMs,
                endMs = cluster.endMs,
                distanceMeters = cluster.stats.distanceMeters,
                durationSec = cluster.stats.durationSec,
                avgSpeedMps = cluster.stats.avgSpeedMps,
                maxSpeedMps = cluster.stats.maxSpeedMps,
                p75SpeedMps = cluster.stats.p75SpeedMps,
                activity = label.name,
                activityIcon = label.icon,
                speedBand = ActivityLabels.speedBand(cluster.stats.avgSpeedMps, options.speedBandsKmph),
                encodedPolyline = PolylineCodec.encode(geometry, options.polylinePrecision),
            )
        } else {
            TrackSegment(
                from = cluster.fromIndex,
                to = cluster.toIndex,
                type = SegmentType.STOP,
                startMs = cluster.startMs,
                endMs = cluster.endMs,
                durationSec = cluster.stats.durationSec,
                encodedPolyline = "",
            )
        }
    }

    private fun buildStops(
        points: List<TrackPoint>,
        clusters: List<Clusters.Segment>,
        nodeIndices: List<Int>,
        nowMs: Long,
    ): List<StopNode> {
        val stops = mutableListOf<StopNode>()
        var stopIndex = 1

        clusters.filter { !it.isRealMovement }.forEach { cluster ->
            val arrival = cluster.points.first()
            val isLast = cluster.toIndex >= points.lastIndex
            // An open session's final dwell is measured against NOW and marked ongoing,
            // so the renderer can pulse it rather than showing a frozen duration (EC-111).
            val departureMs = if (isLast) null else cluster.points.last().timeMs

            stops += StopNode(
                index = stopIndex++,
                lat = arrival.latitude,
                lng = arrival.longitude,
                arrivalMs = arrival.timeMs,
                departureMs = departureMs,
                dwellSec = Clusters.dwellSec(arrival.timeMs, departureMs, nowMs),
                radiusM = radiusOf(cluster.points),
                pointCount = cluster.points.size,
                isOngoing = isLast && nowMs > 0,
            )
        }
        return stops
    }

    /**
     * Placed on the same geometry the renderer will draw. An arrow computed from raw
     * points while the polyline follows a snapped road floats off the line and points
     * along a leg that is not there — the class of divergence SOURCE-AUDIT A9 is about.
     */
    private fun buildArrows(
        points: List<TrackPoint>,
        clusters: List<Clusters.Segment>,
        options: TrackOptions,
        snapped: List<PlotPoint>,
    ): List<ArrowAnchor> = clusters
        .filter { it.isRealMovement }
        .flatMapIndexed { index, cluster ->
            val span = Snapper.spanFor(snapped, cluster.fromIndex, cluster.toIndex)
            val path = if (span.isEmpty()) {
                cluster.points.map { GeoPoint(it.latitude, it.longitude) }
            } else {
                span.map { GeoPoint(it.latitude, it.longitude) }
            }
            Arrows.place(
                path = path,
                zoom = options.zoom,
                segmentIndex = index,
                minSegmentM = options.arrowMinSegmentM,
            )
        }

    private fun statsOf(
        points: List<TrackPoint>,
        clusters: List<Clusters.Segment>,
        stops: List<StopNode>,
    ): TrackStats {
        val travel = clusters.filter { it.isRealMovement }
        val movingSec = travel.sumOf { it.stats.durationSec }
        val distance = travel.sumOf { it.stats.distanceMeters }
        val totalSec = ((points.last().timeMs - points.first().timeMs) / 1000).coerceAtLeast(0)

        val breakdown = travel.groupBy { cluster ->
            ActivityLabels.label(
                dominantActivity(cluster),
                cluster.stats.maxSpeedMps,
                cluster.stats.p75SpeedMps,
            ).commuteCategory
        }.mapValues { (_, group) -> group.sumOf { it.stats.durationSec } }

        return TrackStats(
            distanceMeters = distance,
            durationSec = totalSec,
            movingSec = movingSec,
            stoppedSec = (totalSec - movingSec).coerceAtLeast(0),
            maxSpeedMps = travel.maxOfOrNull { it.stats.maxSpeedMps } ?: 0f,
            avgMovingSpeedMps = if (movingSec > 0) (distance / movingSec).toFloat() else 0f,
            pointCount = points.size,
            stopCount = stops.size,
            activityBreakdownSec = breakdown,
        )
    }

    private fun singlePointTrack(
        point: TrackPoint,
        options: TrackOptions,
        sessionId: String?,
        nowMs: Long,
        timezone: String,
        warnings: List<String>,
    ): Track = Track(
        sessionId = sessionId,
        generatedAtMs = nowMs,
        from = point.timeMs,
        to = point.timeMs,
        timezone = timezone,
        precision = options.polylinePrecision,
        // Degenerate but valid: bounds collapse onto the point, no segments, no arrows,
        // exactly one stop node (EC-94).
        bounds = Bounds(point.latitude, point.latitude, point.longitude, point.longitude),
        stats = TrackStats(pointCount = 1, stopCount = 1),
        encodedPolyline = PolylineCodec.encode(
            listOf(GeoPoint(point.latitude, point.longitude)),
            options.polylinePrecision,
        ),
        points = jsonPoints(listOf(point)),
        stops = listOf(
            StopNode(
                index = 1,
                lat = point.latitude,
                lng = point.longitude,
                arrivalMs = point.timeMs,
                departureMs = null,
                dwellSec = Clusters.dwellSec(point.timeMs, null, nowMs),
                pointCount = 1,
                isOngoing = nowMs > 0,
            ),
        ),
        warnings = warnings,
    )

    /**
     * The heading [HeadingSpline] may use as a tangent at this vertex, or
     * [PlotPoint.BEARING_UNSET].
     *
     * Two conditions, and the second is the one that is easy to miss. A chipset reports
     * `hasBearing = false` when it has no heading, which is the obvious case. It also
     * reports a heading for a phone sitting on a table, derived from a Doppler shift that
     * is entirely multipath, and that heading swings through the full circle while the
     * device goes nowhere. Handing those to a smoother as tangents would bend the line
     * into a knot at every stop — which is precisely where [Consolidation] has already
     * placed two vertices on the same centroid.
     *
     * So a heading counts only while the point was travelling, at the same walking-pace
     * floor [Consolidation] uses to decide a point cannot belong to a dwell.
     */
    private fun plotBearingOf(point: TrackPoint): Float =
        if (point.hasBearing && point.speedMps >= Consolidation.DEFAULT_MOVING_MPS) {
            point.bearingDeg
        } else {
            PlotPoint.BEARING_UNSET
        }

    private fun jsonPoints(points: List<TrackPoint>): List<TrackJsonPoint> =
        points.mapIndexed { index, point ->
            TrackJsonPoint(
                i = index,
                t = point.timeMs,
                lat = point.latitude,
                lng = point.longitude,
                acc = point.accuracy,
                spd = point.speedMps,
                brg = point.bearingDeg,
                act = point.detectedActivity?.name,
                src = point.provider,
                mock = point.isMock,
            )
        }

    private fun dominantActivity(cluster: Clusters.Segment) =
        cluster.points.mapNotNull { it.detectedActivity }
            .groupingBy { it }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key

    private fun radiusOf(points: List<TrackPoint>): Double {
        if (points.size < 2) return 0.0
        val lat = points.sumOf { it.latitude } / points.size
        val lng = points.sumOf { it.longitude } / points.size
        return points.maxOf { Haversine.metres(lat, lng, it.latitude, it.longitude) }
    }

    private fun boundsOf(points: List<TrackPoint>) = Bounds(
        north = points.maxOf { it.latitude },
        south = points.minOf { it.latitude },
        east = points.maxOf { it.longitude },
        west = points.minOf { it.longitude },
    )

    public const val WARNING_MOCK_PRESENT: String = "mock_locations_present"
    public const val WARNING_SNAP_UNAVAILABLE: String = "snap_unavailable"
    public const val WARNING_COARSE_ACCURACY: String = "coarse_accuracy"
    public const val WARNING_TRUNCATED: String = "truncated"
}
