package com.field360.traker.geo.plot.model

import com.field360.traker.geo.model.ActivityType
import com.field360.traker.geo.model.Bounds
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A ready-to-draw track.
 *
 * The headline deliverable: any map library on any platform renders this without doing
 * geometry. Produced entirely on-device — no backend, no routing key, no quota.
 *
 * @property precision encoded-polyline precision, stated explicitly rather than
 *   assumed. A consumer that hardcodes 5 against a 6 track puts the user in the wrong
 *   hemisphere (EC-110).
 * @property bounds `null` — never NaN-filled — when there are no points (EC-93).
 * @property warnings open string set: `snap_unavailable`, `coarse_accuracy`,
 *   `mock_locations_present`, `truncated`, `session_interrupted`. **Nothing is ever
 *   silently dropped**; anything omitted is named here.
 */
@Serializable
public data class Track(
    val version: Int = FORMAT_VERSION,
    val sessionId: String? = null,
    val generatedAtMs: Long = 0,
    val from: Long = 0,
    val to: Long = 0,
    val timezone: String = "UTC",
    val precision: Int = DEFAULT_PRECISION,
    val bounds: Bounds? = null,
    val stats: TrackStats = TrackStats(),
    val encodedPolyline: String = "",
    val points: List<TrackJsonPoint> = emptyList(),
    val segments: List<TrackSegment> = emptyList(),
    val stops: List<StopNode> = emptyList(),
    val arrows: List<ArrowAnchor> = emptyList(),
    val warnings: List<String> = emptyList(),
) {
    public companion object {
        public const val FORMAT_VERSION: Int = 1
        public const val DEFAULT_PRECISION: Int = 6
    }
}

/** `i` is the index every other array references — never an index into the polyline. */
@Serializable
public data class TrackJsonPoint(
    val i: Int,
    val t: Long,
    val lat: Double,
    val lng: Double,
    val acc: Float = 0f,
    val spd: Float = 0f,
    val brg: Float = 0f,
    val act: String? = null,
    val src: String = "gps",
    val mock: Boolean = false,
)

@Serializable
public data class TrackStats(
    val distanceMeters: Double = 0.0,
    val durationSec: Long = 0,
    val movingSec: Long = 0,
    val stoppedSec: Long = 0,
    val maxSpeedMps: Float = 0f,
    val avgMovingSpeedMps: Float = 0f,
    val pointCount: Int = 0,
    val stopCount: Int = 0,
    val activityBreakdownSec: Map<String, Long> = emptyMap(),
)

public enum class SegmentType {
    @SerialName("travel")
    TRAVEL,

    @SerialName("stop")
    STOP,
}

/**
 * @property from index into [Track.points], inclusive.
 * @property to index into [Track.points], inclusive.
 * @property encodedPolyline lets a renderer colour each span without decoding the
 *   whole track.
 */
@Serializable
public data class TrackSegment(
    val from: Int,
    val to: Int,
    val type: SegmentType,
    val startMs: Long,
    val endMs: Long,
    val distanceMeters: Double = 0.0,
    val durationSec: Long = 0,
    val avgSpeedMps: Float = 0f,
    val maxSpeedMps: Float = 0f,
    val p75SpeedMps: Float = 0f,
    val activity: String? = null,
    val activityIcon: String? = null,
    val speedBand: String? = null,
    val encodedPolyline: String = "",
    val stopIndex: Int? = null,
)

/**
 * @property isOngoing the session is still open and dwell was computed against the
 *   wall clock at build time — renderers should pulse this marker (EC-111).
 */
@Serializable
public data class StopNode(
    val index: Int,
    val lat: Double,
    val lng: Double,
    val arrivalMs: Long,
    val departureMs: Long?,
    val dwellSec: Long,
    val radiusM: Double = 0.0,
    val pointCount: Int = 0,
    val address: String? = null,
    val isOngoing: Boolean = false,
)

/**
 * The feature that makes "plot with an arrow" trivial for a host.
 *
 * Placement needs bearings, geodesic offsets, zoom-adaptive spacing and jump handling —
 * everyone wants it, nobody wants to compute it, and if the renderer and the export
 * compute it separately they drift. That is exactly what happened in the reference,
 * which had two divergent spacing ladders and visibly different arrow density before
 * and after the first pinch (SOURCE-AUDIT A9, EC-108).
 */
@Serializable
public data class ArrowAnchor(
    val lat: Double,
    val lng: Double,
    val bearing: Double,
    val segment: Int,
)

/**
 * Build-time knobs. [zoom] selects the arrow spacing tier.
 *
 * `@Serializable` in place rather than mirrored by a bridge DTO, unlike every other type
 * that crosses a language boundary. The rule the mirrors exist for is that a wire format
 * must not be hostage to an internal field rename — but this is a pure input value
 * object with no internals to protect, so there is nothing a mirror would decouple
 * (CROSS-PLATFORM.md B-5).
 */
@Serializable
public data class TrackOptions @JvmOverloads constructor(
    val zoom: Float = 14f,
    val includeRawPoints: Boolean = true,
    val consolidateStops: Boolean = true,
    val stopRadiusM: Double = 60.0,
    val stopMinDwellSec: Long = 600,
    /**
     * How the drawn line is smoothed. Defaults to [Smoothing.SPLINE].
     *
     * [Smoothing.BEZIER] is the previous behaviour and remains available, but it only
     * rounds vertices that turn more than `bezierMinAngleDeg` — it cannot do anything
     * about a 120 m leg drawn as a chord, which is the usual complaint (EC-45b).
     */
    val smoothing: Smoothing = Smoothing.SPLINE,
    /** Resample spacing for [Smoothing.SPLINE]. */
    val splineSpacingM: Double = Smoothing.DEFAULT_SPACING_M,
    val bezierMinAngleDeg: Double = 30.0,
    val bezierCutbackM: Double = 25.0,
    /**
     * Use road geometry when a `RoadSnapProvider` is installed.
     *
     * Costs nothing on its own: with no provider the build never asks for geometry and
     * never emits a `snap_unavailable` warning. Set it `false` to keep raw geometry even
     * when a provider *is* installed — useful when a track is being audited against the
     * fixes that were actually captured.
     */
    val snapToRoad: Boolean = true,
    /** Beyond this from the returned road, a fix keeps its captured position (EC-101). */
    val snapMaxOffRoadM: Double = 80.0,
    val polylinePrecision: Int = Track.DEFAULT_PRECISION,
    val speedBandsKmph: List<Float> = listOf(10f, 20f),
    val arrowMinSegmentM: Double = 60.0,
    /**
     * Douglas-Peucker tolerance applied before smoothing; `0` disables the stage
     * (SMOOTH-NAV-PLAN Phase 4).
     *
     * A vertex whose perpendicular distance from the chord across its neighbours is
     * under this is redundant — the line already goes there. Removing it before the
     * spline both shrinks the encoded polyline and improves the curve, which is
     * obliged to pass through every vertex it is given.
     */
    val simplifyEpsilonM: Double = DEFAULT_SIMPLIFY_EPSILON_M,
) {
    public companion object {
        /**
         * 2 m — the walking value, deliberately the conservative end of the 2 m (walk)
         * to 5 m (drive) range.
         *
         * It has to be safe at both cadences from one number. At a 12 s vehicular
         * cadence, vertices are ~120 m apart and a real corner deviates far more than
         * 2 m, so nothing that carries shape is touched and only genuinely collinear
         * highway vertices go. On dense walking data the same 2 m is squarely inside
         * GPS noise and clears the jitter the curve would otherwise trace faithfully.
         *
         * Declared here rather than on `Simplify` for the reason
         * [Smoothing.DEFAULT_SPACING_M] is: `plot.model` must not depend on `plot`.
         */
        public const val DEFAULT_SIMPLIFY_EPSILON_M: Double = 2.0
    }
}

/** What the plotting plane does to make a sparse track drawable. */
public enum class Smoothing {
    /** Chords between stored vertices, exactly as captured. */
    NONE,

    /** Round vertices sharper than `bezierMinAngleDeg`; leave every leg a chord. */
    BEZIER,

    /** Centripetal Catmull-Rom through every vertex, resampled (EC-45b). */
    SPLINE,

    /**
     * As [SPLINE], but each vertex's *recorded heading* is the curve's tangent there
     * rather than a direction inferred from its neighbours.
     *
     * The difference only shows at corners, and there it is the whole thing. Catmull-Rom
     * builds the tangent at a vertex from the vertices either side of it; through a turn
     * those two sit on opposite legs, so the inferred tangent is the chord across the
     * corner and the curve leaves the vertex pointing somewhere the vehicle never
     * pointed. A GNSS bearing is the Doppler heading at that instant, so using it puts
     * the curve on the vehicle's actual heading in and out of every fix — and the corner
     * appears *between* two fixes without either of them having recorded its apex.
     *
     * Falls back to the Catmull-Rom tangent *direction* wherever no bearing was recorded,
     * so a track from a chipset that reports no heading draws the shape [SPLINE] draws —
     * close to it, not identical, since the tangent magnitudes are scaled per span rather
     * than by a centripetal parameterisation.
     */
    HEADING_SPLINE,
    ;

    public companion object {
        /**
         * Resample spacing for [SPLINE], roughly a car length — dense enough that no leg
         * reads as a straight line.
         *
         * Declared here rather than on `Spline` because `TrackOptions` needs it too, and
         * `plot.model` must not depend on `plot`. Restating the number in both places is
         * the alternative, and two constants that must agree eventually do not.
         */
        public const val DEFAULT_SPACING_M: Double = 5.0
    }
}

/** Render tag, kept on the *output* type — never written back onto a stored point (A10). */
public enum class RenderTag { RAW, SNAPPED_TO_ROAD, ROUNDED_CURVE, PROTECTED }

/** A point plus its render provenance, used between plotting stages. */
public data class PlotPoint(
    val latitude: Double,
    val longitude: Double,
    val timeMs: Long,
    val sourceIndex: Int = -1,
    val activity: ActivityType? = null,
    val tag: RenderTag = RenderTag.RAW,
    /** Session bookends and host markers are never moved by rounding (EC-103). */
    val isProtected: Boolean = false,
    /**
     * Recorded heading at this vertex, degrees clockwise from north, or [BEARING_UNSET].
     *
     * Carried through the plotting stages for [Smoothing.HEADING_SPLINE], which uses it
     * as the curve's tangent. Set only on vertices that came from a fix whose chipset
     * reported a bearing: a heading derived from the displacement between two plotted
     * vertices is the chord direction, which is what the smoother is trying to improve
     * on, so inventing one here would quietly turn the stage back into Catmull-Rom while
     * claiming otherwise. Interpolated vertices leave it unset for the same reason.
     */
    val bearingDeg: Float = BEARING_UNSET,
) {
    public val hasBearing: Boolean get() = bearingDeg >= 0f

    public companion object {
        /** No heading recorded. Negative, so it cannot collide with `[0, 360)`. */
        public const val BEARING_UNSET: Float = -1f
    }
}
