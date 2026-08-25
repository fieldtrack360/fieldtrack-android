package com.field360.fieldtrack.sample.screen

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import com.field360.fieldtrack.sample.BuildConfig
import com.field360.tracker.RawFix
import com.field360.tracker.domain.model.TrackerGeofence
import com.field360.traker.geo.math.Bearing
import com.field360.traker.geo.math.Geodesy
import com.field360.traker.geo.math.Haversine
import com.field360.traker.geo.model.GeoPoint
import com.field360.traker.geo.plot.PolylineCodec
import com.field360.traker.geo.plot.TrackBuilder.WARNING_SNAP_UNAVAILABLE
import com.field360.traker.geo.plot.model.SegmentType
import com.field360.traker.geo.plot.model.Track
import com.field360.fieldtrack.sample.TrackerViewModel
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.GoogleMapComposable
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.cos
import android.graphics.Color as AndroidColor

/**
 * The plotting output, drawn exactly as a host would draw it.
 *
 * Everything coloured here comes from `Track` — the polyline is decoded from
 * `encodedPolyline`, the stops are its stop nodes. No geometry is recomputed, which is
 * the whole point of shipping the plotting plane.
 *
 * The direction arrow is the one presentational choice this screen makes for itself. The
 * SDK precomputes `track.arrows` — anchors and bearings that the JSON export carries, so
 * a host drawing them cannot disagree with what it exported (A9, EC-108) — but a row of
 * a dozen identical chevrons mostly reads as clutter. One arrow walking the line says
 * the same thing at a glance, and a host wanting the stippled look still has the anchors.
 *
 * The **black** line is the exception and the reason it earns its place: the raw fixes as
 * the OS delivered them, before any gate ran. Everything else on this screen is the SDK's
 * opinion; that line is the input it formed the opinion from. Where they diverge is where
 * the pipeline made a decision worth explaining, and having both on one map turns "the
 * track looks wrong" into "the track differs from the fixes *here*".
 */
@Composable
fun TrackScreen(
    state: TrackerViewModel.UiState,
    onOpenSession: (String) -> Unit = {},
    onSnapToRoad: (Boolean) -> Unit = {},
) {
    val track = state.track

    // Saveable, so the choice survives a rotation and a trip to another tab. Defaults on
    // because the layer is the point of comparison — flip to `false` to make the screen
    // show only what a host would actually draw.
    var showRaw by rememberSaveable { mutableStateOf(true) }
    var showGeofences by rememberSaveable { mutableStateOf(true) }

    if (BuildConfig.MAPS_API_KEY.isBlank()) {
        MissingApiKey()
        return
    }

    Column(Modifier.fillMaxSize()) {
        // Above the map, not below it: choosing what to look at comes before looking.
        SessionPicker(
            sessions = state.sessions,
            selectedId = state.selectedSessionId,
            onOpenSession = onOpenSession,
        )

        val hasTrack = track != null && track.points.isNotEmpty()
        if (!hasTrack && state.geofences.isEmpty()) {
            Centered(
                if (state.sessions.isEmpty()) {
                    "No track yet — start tracking, then return to this tab."
                } else {
                    "That session stored no points. Pick another, or check the Decisions tab " +
                        "for why its fixes were rejected."
                },
            )
            return
        }

        // Hidden is passed down as "no points to draw" rather than as a flag: the map has
        // no business knowing why a layer is absent, only that it is.
        TrackMap(
            track = track,
            rawFixes = if (showRaw) state.rawFixes else emptyList(),
            geofences = if (showGeofences) state.geofences else emptyList(),
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = showRaw,
                onClick = { showRaw = !showRaw },
                // Disabled rather than hidden when there is nothing to draw: a control
                // that vanishes reads as a missing feature, one that greys out reads as
                // a missing input — which is what an empty raw layer actually is.
                enabled = state.rawFixes.size >= 2,
                label = { Text("Raw ${state.rawFixes.size}") },
            )
            FilterChip(
                selected = state.snapToRoad,
                onClick = { onSnapToRoad(!state.snapToRoad) },
                enabled = hasTrack,
                label = { Text("Snap") },
            )
            FilterChip(
                selected = showGeofences,
                onClick = { showGeofences = !showGeofences },
                enabled = state.geofences.isNotEmpty(),
                label = { Text("Fences ${state.geofences.size}") },
            )
        }

        // Not an error state. A provider that could not answer costs geometry, never the
        // track, and the line below is drawn from raw fixes exactly as it would be with no
        // provider installed (EC-100).
        if (hasTrack && state.trackWarnings.contains(WARNING_SNAP_UNAVAILABLE)) {
            Text(
                text = "Road snapping unavailable — drawn from captured fixes.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        if (track != null && track.points.isNotEmpty()) {
            TrackSummary(track, state.rawFixes.size, state.rawFixesEnabled, state.rawFixesError)
        } else {
            Text(
                text = "Showing ${state.geofences.size} registered geofence(s); no track selected.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

@Composable
private fun TrackMap(
    track: Track?,
    rawFixes: List<RawFix>,
    geofences: List<TrackerGeofence>,
    modifier: Modifier,
) {
    val camera = rememberCameraPositionState {
        val bounds = track?.bounds
        position = if (bounds != null) {
            CameraPosition.fromLatLngZoom(
                LatLng((bounds.north + bounds.south) / 2, (bounds.east + bounds.west) / 2),
                DEFAULT_ZOOM,
            )
        } else if (geofences.isNotEmpty()) {
            CameraPosition.fromLatLngZoom(
                LatLng(geofences.first().latitude, geofences.first().longitude),
                GEOFENCE_ZOOM,
            )
        } else {
            CameraPosition.fromLatLngZoom(LatLng(0.0, 0.0), 2f)
        }
    }
    var mapLoaded by remember { mutableStateOf(false) }

    LaunchedEffect(mapLoaded, track?.encodedPolyline, geofences) {
        if (!mapLoaded) return@LaunchedEffect
        mapBounds(track, geofences)?.let { bounds ->
            runCatching {
                camera.move(CameraUpdateFactory.newLatLngBounds(bounds, CAMERA_PADDING_PX))
            }
        }
    }

    GoogleMap(
        modifier = modifier,
        cameraPositionState = camera,
        properties = MapProperties(isMyLocationEnabled = false),
        onMapLoaded = { mapLoaded = true },
    ) {
        geofences.forEach { fence ->
            val center = LatLng(fence.latitude, fence.longitude)
            Circle(
                center = center,
                radius = fence.radiusM.toDouble(),
                fillColor = GEOFENCE_FILL,
                strokeColor = GEOFENCE_STROKE,
                strokeWidth = GEOFENCE_STROKE_WIDTH,
                zIndex = GEOFENCE_Z,
            )
            Marker(
                state = remember(fence.id, fence.latitude, fence.longitude) { MarkerState(center) },
                icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ORANGE),
                anchor = Offset(0.5f, 1f),
                zIndex = GEOFENCE_MARKER_Z,
                title = fence.id,
                snippet = "Radius ${fence.radiusM.toInt()} m · ${fence.onEnterEvent} / ${fence.onExitEvent}",
            )
        }

        // One polyline per travel segment, coloured by the SDK's speed band.
        track?.segments.orEmpty().filter { it.type == SegmentType.TRAVEL }.forEach { segment ->
            val path = PolylineCodec.decode(segment.encodedPolyline, track?.precision ?: 6)
                .map { LatLng(it.latitude, it.longitude) }
            if (path.size >= 2) {
                Polyline(
                    points = path,
                    color = bandColour(segment.speedBand),
                    width = POLYLINE_WIDTH,
                    geodesic = true,
                )
            }
        }

        // Layer 1 — the fixes exactly as the OS delivered them, before any gate ran.
        //
        // Drawn ON TOP of the speed bands and much thinner: a black thread over a wide
        // coloured ribbon, so both stay readable where they coincide and the eye goes
        // straight to where they part. Under the bands it would simply be invisible.
        //
        // `geodesic = false` on purpose. The great-circle arcs used for the SDK's line
        // are indistinguishable at this scale, but this layer's whole claim is "no
        // processing" — so it joins consecutive fixes with literal straight segments and
        // interpolates nothing.
        //
        // Needs `persistence.persistRawFixes = true`; the summary below says so when the
        // layer is empty, rather than leaving a silently missing line.
        if (rawFixes.size >= 2) {
            Polyline(
                points = rawFixes.map { LatLng(it.latitude, it.longitude) },
                color = RAW_COLOUR,
                width = RAW_WIDTH,
                geodesic = false,
                zIndex = RAW_Z,
            )
        }

        // Built inside the map content: BitmapDescriptorFactory throws until the Maps
        // SDK has initialised, which maps-compose guarantees only for this lambda.
        val arrowIcon = remember { arrowDescriptor() }

        // One arrow travelling the whole line, rather than a row of static chevrons.
        //
        // The SDK still precomputes `track.arrows`, and a host wanting the classic
        // stippled-direction look should draw those — they are the anchors the JSON
        // export carries, so the map and the export cannot disagree (A9, EC-108). This
        // screen makes the opposite trade on purpose: a single moving arrow reads the
        // direction of a whole track at a glance, where a dozen identical chevrons
        // mostly read as clutter.
        val drawnPath = remember(track?.encodedPolyline, track?.precision) {
            if (track == null) emptyList() else PolylineCodec.decode(track.encodedPolyline, track.precision)
                .map { LatLng(it.latitude, it.longitude) }
        }
        TravellingArrow(path = drawnPath, icon = arrowIcon)

        track?.stops.orEmpty().forEach { stop ->
            Marker(
                state = rememberMarkerStateAt(stop.lat, stop.lng),
                icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE),
                title = "Stop ${stop.index}",
                snippet = "${stop.dwellSec / 60} min" + if (stop.isOngoing) " (ongoing)" else "",
            )
        }

        // Start and end last, so they sit above the arrow that passes through them.
        // Anchor (0.5, 1.0) is the pin's tip — the point of the teardrop lands exactly
        // on the coordinate, which is what makes a pin readable as "here".
        track?.points?.firstOrNull()?.let { first ->
            Marker(
                state = rememberMarkerStateAt(first.lat, first.lng),
                icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN),
                anchor = Offset(0.5f, 1.0f),
                zIndex = ENDPOINT_Z,
                title = "Start",
                snippet = clockTime(first.t),
            )
        }
        // Guarded: a one-point track would otherwise stack an End pin on the Start pin.
        track?.points?.lastOrNull()
            ?.takeIf { track.points.size > 1 }
            ?.let { last ->
                Marker(
                    state = rememberMarkerStateAt(last.lat, last.lng),
                    icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED),
                    anchor = Offset(0.5f, 1.0f),
                    zIndex = ENDPOINT_Z,
                    title = "End",
                    snippet = clockTime(last.t),
                )
            }
    }
}

/**
 * Keyed on the coordinate, so a marker keeps its state across recomposition but a track
 * that changes gets fresh ones. Constructing `MarkerState` inline is the
 * `UnrememberedMutableState` bug: it is rebuilt on every recomposition and any state the
 * map wrote into it — drag position, info-window visibility — is discarded.
 */
@Composable
private fun rememberMarkerStateAt(lat: Double, lng: Double): MarkerState =
    remember(lat, lng) { MarkerState(LatLng(lat, lng)) }

/**
 * A single arrow that walks the drawn line, pointing the way the track was travelled.
 *
 * Its own composable, and that is not tidiness: reading an animated value recomposes the
 * scope that reads it, and reading it inside the map's content lambda would rebuild every
 * polyline and marker on the screen sixty times a second. Here the per-frame work is one
 * marker node.
 *
 * The walk is arc-length parameterised, not index parameterised. Stepping vertex to
 * vertex would race through the dense samples of a bend and crawl along a straight leg,
 * because vertex spacing is a function of curvature, not of distance — so the position
 * comes from a cumulative-distance table and the arrow moves at a constant speed over
 * the ground.
 */
@Composable
@GoogleMapComposable
private fun TravellingArrow(path: List<LatLng>, icon: BitmapDescriptor) {
    if (path.size < 2) return

    val cumulative = remember(path) { cumulativeMetres(path) }
    val totalM = cumulative.last()
    if (totalM <= 0.0) return

    val progress by rememberInfiniteTransition(label = "arrow").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            // Linear: any easing here would read as the arrow slowing down mid-track,
            // which looks like data rather than animation.
            animation = tween(durationMillis = travelDurationMs(totalM), easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "progress",
    )

    // Mutated rather than re-created: a MarkerState rebuilt per frame drops the map's
    // own state and re-adds the marker, which flickers.
    val markerState = remember(path) { MarkerState(path.first()) }
    val pose = poseAt(path, cumulative, progress.toDouble() * totalM)
    markerState.position = pose.position

    Marker(
        state = markerState,
        icon = icon,
        rotation = pose.bearingDeg,
        // Pinned to the map plane so it turns with the compass instead of staying
        // screen-upright, and centre-anchored so it rotates about its own middle.
        flat = true,
        anchor = Offset(0.5f, 0.5f),
        zIndex = ARROW_Z,
    )
}

private data class ArrowPose(val position: LatLng, val bearingDeg: Float)

/** Distance from the first vertex to each vertex, metres; `[0]` is always `0`. */
private fun cumulativeMetres(path: List<LatLng>): DoubleArray {
    val out = DoubleArray(path.size)
    for (i in 1..path.lastIndex) {
        out[i] = out[i - 1] + Haversine.metres(
            path[i - 1].latitude,
            path[i - 1].longitude,
            path[i].latitude,
            path[i].longitude,
        )
    }
    return out
}

/**
 * Where the arrow is, and which way it faces, [distanceM] along the line.
 *
 * The leg is found by binary search rather than by scanning, so the cost per frame does
 * not grow with the length of the track.
 */
private fun poseAt(path: List<LatLng>, cumulative: DoubleArray, distanceM: Double): ArrowPose {
    val clamped = distanceM.coerceIn(0.0, cumulative.last())

    // Hand-rolled over the primitive array rather than `cumulative.toList().binarySearch`:
    // that boxes every element into a fresh list, and this runs on every animation frame.
    var low = 0
    var high = path.size - 2
    while (low < high) {
        val mid = (low + high + 1) / 2
        if (cumulative[mid] <= clamped) low = mid else high = mid - 1
    }
    val leg = low

    val legLength = cumulative[leg + 1] - cumulative[leg]
    val fraction = if (legLength <= 0.0) 0.0 else (clamped - cumulative[leg]) / legLength

    val from = path[leg]
    val to = path[leg + 1]
    val point = Geodesy.interpolate(
        GeoPoint(from.latitude, from.longitude),
        GeoPoint(to.latitude, to.longitude),
        fraction,
    )
    return ArrowPose(
        position = LatLng(point.latitude, point.longitude),
        bearingDeg = Bearing.degrees(from.latitude, from.longitude, to.latitude, to.longitude).toFloat(),
    )
}

/**
 * One lap of the track, in milliseconds.
 *
 * Proportional to distance so the arrow moves at the same apparent speed on a 1 km walk
 * and a 40 km drive, then clamped at both ends: below the floor the loop is a twitch,
 * above the ceiling the arrow appears not to be moving at all.
 */
private fun travelDurationMs(totalM: Double): Int =
    (totalM / ARROW_METRES_PER_SECOND * 1_000).toInt().coerceIn(MIN_TRAVEL_MS, MAX_TRAVEL_MS)

/**
 * A flat chevron pointing north, rotated per anchor by the marker's `rotation`.
 *
 * Drawn in code rather than shipped as a drawable so the arrow cannot drift out of sync
 * with the outline width or the marker size the map actually needs. The white stroke is
 * not decoration: without it the arrow disappears into the red speed band.
 */
private fun arrowDescriptor(): BitmapDescriptor {
    val size = ARROW_PX
    val bitmap = createBitmap(size, size)
    val canvas = Canvas(bitmap)
    val s = size.toFloat()

    val chevron = Path().apply {
        moveTo(s * 0.5f, s * 0.06f)
        lineTo(s * 0.92f, s * 0.94f)
        lineTo(s * 0.5f, s * 0.70f)
        lineTo(s * 0.08f, s * 0.94f)
        close()
    }

    canvas.drawPath(
        chevron,
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.WHITE
            style = Paint.Style.STROKE
            strokeWidth = s * 0.14f
            strokeJoin = Paint.Join.ROUND
        },
    )
    canvas.drawPath(
        chevron,
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.BLACK
            style = Paint.Style.FILL
        },
    )

    return BitmapDescriptorFactory.fromBitmap(bitmap)
}

private fun clockTime(atMs: Long): String =
    CLOCK.format(Instant.ofEpochMilli(atMs).atZone(ZoneId.systemDefault()))

/** Bounds include each fence's radius, not only its centre marker. */
private fun mapBounds(track: Track?, geofences: List<TrackerGeofence>): LatLngBounds? {
    val points = buildList {
        track?.bounds?.let { bounds ->
            add(LatLng(bounds.south, bounds.west))
            add(LatLng(bounds.north, bounds.east))
        }
        geofences.forEach { fence ->
            val latitudeDelta = fence.radiusM / METRES_PER_LATITUDE_DEGREE
            val longitudeScale = cos(Math.toRadians(fence.latitude)).coerceAtLeast(MIN_LONGITUDE_SCALE)
            val longitudeDelta = fence.radiusM / (METRES_PER_LATITUDE_DEGREE * longitudeScale)
            add(
                LatLng(
                    (fence.latitude - latitudeDelta).coerceAtLeast(-90.0),
                    (fence.longitude - longitudeDelta).coerceAtLeast(-180.0),
                ),
            )
            add(
                LatLng(
                    (fence.latitude + latitudeDelta).coerceAtMost(90.0),
                    (fence.longitude + longitudeDelta).coerceAtMost(180.0),
                ),
            )
        }
    }
    if (points.size < 2 || points.all { it == points.first() }) return null
    return LatLngBounds.Builder().apply { points.forEach(::include) }.build()
}

@Composable
private fun TrackSummary(track: Track, rawFixCount: Int, enabled: Boolean, error: String?) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(
            text = "Track · session ${track.sessionId?.take(8) ?: "—"}",
            style = MaterialTheme.typography.titleMedium,
        )
        Text("Distance: ${"%.2f".format(track.stats.distanceMeters / 1000)} km")
        Text("Moving: ${track.stats.movingSec / 60} min · Stopped: ${track.stats.stoppedSec / 60} min")
        Text("Points: ${track.stats.pointCount} · Stops: ${track.stats.stopCount}")

        // The ratio is the diagnostic: raw is what the OS offered, points are what
        // survived the pipeline. A wide gap is the thing to go and explain.
        if (rawFixCount >= 2) {
            Text(
                text = "Raw (black): $rawFixCount fixes → ${track.stats.pointCount} stored",
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            // Says why the chip above is greyed out. Without this the disabled control is
            // a dead end — but the wrong reason is worse than none, and this used to give
            // one: it told you to enable a flag that the sample already sets, which sends
            // you to re-read config instead of at the session you are looking at.
            Text(
                text = when {
                    error != null ->
                        "Raw layer could not be read: $error"
                    !enabled ->
                        "Raw layer off — set persistence.persistRawFixes = true to record it."
                    else ->
                        "Raw layer empty for this session. persistRawFixes IS on, so this " +
                            "session was recorded before it was enabled — raw fixes are " +
                            "written during capture and cannot be backfilled. Record a new " +
                            "session to populate it."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        if (track.warnings.isNotEmpty()) {
            Text("Warnings: ${track.warnings.joinToString()}", color = MaterialTheme.colorScheme.error)
        }
    }
}

private fun bandColour(band: String?): Color = when (band) {
    "green" -> Color(0xFF04D95C)
    "yellow" -> Color(0xFFF5BC00)
    else -> Color(0xFFF20202)
}

@Composable
internal fun MissingApiKey() {
    Centered(
        "No Maps API key.\n\nAdd MAPS_API_KEY=… to local.properties and rebuild.\n" +
            "Everything else in the sample works without it.",
    )
}

@Composable
internal fun Centered(message: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(message, style = MaterialTheme.typography.bodyMedium)
    }
}

private const val DEFAULT_ZOOM = 15f
private const val GEOFENCE_ZOOM = 16f
private const val CAMERA_PADDING_PX = 96
private const val POLYLINE_WIDTH = 16f
private const val ARROW_PX = 72

/** Apparent speed of the travelling arrow over the ground — brisk, not frantic. */
private const val ARROW_METRES_PER_SECOND = 400.0
private const val MIN_TRAVEL_MS = 4_000
private const val MAX_TRAVEL_MS = 20_000

/** Opaque black, and thin enough to read as a thread over the 16 px speed band. */
private val RAW_COLOUR = Color(0xFF000000)
private const val RAW_WIDTH = 5f

// Raw above the speed bands (implicit 0), everything interactive above raw.
private const val RAW_Z = 0.5f
private const val ARROW_Z = 1f
private const val ENDPOINT_Z = 2f
private const val GEOFENCE_Z = 2.5f
private const val GEOFENCE_MARKER_Z = 3f
private const val GEOFENCE_STROKE_WIDTH = 4f
private const val METRES_PER_LATITUDE_DEGREE = 111_320.0
private const val MIN_LONGITUDE_SCALE = 0.01
private val GEOFENCE_FILL = Color(0x332A9D8F)
private val GEOFENCE_STROKE = Color(0xFF147D72)
private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US)
