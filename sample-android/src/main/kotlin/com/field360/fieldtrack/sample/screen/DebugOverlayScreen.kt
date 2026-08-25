package com.field360.fieldtrack.sample.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.field360.fieldtrack.sample.BuildConfig
import com.field360.fieldtrack.sample.TrackerViewModel
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.rememberCameraPositionState

/**
 * The three-layer debug overlay (spec §8.4).
 *
 * This is the single most useful diagnostic in the whole system. Nearly every accuracy
 * complaint is resolved in seconds by seeing **which layer the artefact first appears
 * in**:
 *
 *  - it's already in the **raw** layer → an OS or configuration problem (a non-zero
 *    distance filter, lost speed/bearing flags, a wrong sampling interval);
 *  - raw is clean but **filter** output is wrong → a pipeline stage or a mis-tuned
 *    constant;
 *  - filter is right but **stored** disagrees → a persistence or dedup bug.
 *
 * Raw fixes only exist when `persistence.persistRawFixes` is enabled; the screen says so
 * rather than silently showing two layers.
 */
@Composable
fun DebugOverlayScreen(
    state: TrackerViewModel.UiState,
    onOpenSession: (String) -> Unit = {},
) {
    var showRaw by remember { mutableStateOf(true) }
    var showFilter by remember { mutableStateOf(true) }
    var showStored by remember { mutableStateOf(true) }

    if (BuildConfig.MAPS_API_KEY.isBlank()) {
        MissingApiKey()
        return
    }

    // Above the empty-state guard, never below it. A session that captured nothing is
    // exactly when you most need to switch to one that did, and an early return would
    // take the only control for doing that off the screen.
    Column(Modifier.fillMaxSize()) {
        SessionPicker(
            sessions = state.sessions,
            selectedId = state.selectedSessionId,
            onOpenSession = onOpenSession,
        )

        if (state.points.isEmpty() && state.rawFixes.isEmpty()) {
            Centered(
                "Nothing captured in this session.\n\n" +
                    if (state.rawFixesEnabled) {
                        "persistRawFixes is on, so a session recorded now will have a raw layer."
                    } else {
                        "Raw fixes need persistence.persistRawFixes = true."
                    },
            )
            return@Column
        }

        DebugMap(state, showRaw, showFilter, showStored)

        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = showRaw,
                    onClick = { showRaw = !showRaw },
                    label = { Text("Raw ${state.rawFixes.size}") },
                )
                FilterChip(
                    selected = showFilter,
                    onClick = { showFilter = !showFilter },
                    label = { Text("Filter ${state.decisions.size}") },
                )
                FilterChip(
                    selected = showStored,
                    onClick = { showStored = !showStored },
                    label = { Text("Stored ${state.points.size}") },
                )
            }
            if (state.rawFixes.isEmpty()) {
                // The reason matters more than the fact. Naming a flag that is already
                // set sends the reader to re-read config instead of at this session.
                Text(
                    when {
                        state.rawFixesError != null ->
                            "Raw layer could not be read: ${state.rawFixesError}"
                        !state.rawFixesEnabled ->
                            "Raw layer off — enable persistence.persistRawFixes to record it."
                        else ->
                            "Raw layer empty for this session. persistRawFixes IS on, so " +
                                "this session predates it — raw fixes are written during " +
                                "capture and cannot be backfilled."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.DebugMap(
    state: TrackerViewModel.UiState,
    showRaw: Boolean,
    showFilter: Boolean,
    showStored: Boolean,
) {
    val camera = rememberCameraPositionState {
        val first = state.points.firstOrNull()
        position = CameraPosition.fromLatLngZoom(
            first?.let { LatLng(it.latitude, it.longitude) } ?: LatLng(0.0, 0.0),
            if (first != null) DEBUG_ZOOM else 2f,
        )
    }

    GoogleMap(
        modifier = Modifier.weight(1f).fillMaxWidth(),
        cameraPositionState = camera,
    ) {
        // Layer 1 — what the OS actually delivered, before any gate.
        if (showRaw) {
            state.rawFixes.forEach { fix ->
                Circle(
                    center = LatLng(fix.latitude, fix.longitude),
                    radius = RAW_RADIUS_M,
                    fillColor = RAW_COLOUR,
                    strokeColor = RAW_COLOUR,
                    strokeWidth = 1f,
                )
            }
        }

        // Layer 2 — the Kalman output for every fix, accepted or not. This is where
        // an over-inflated R or a wedged filter becomes visible.
        if (showFilter) {
            state.decisions.forEach { decision ->
                Circle(
                    center = LatLng(decision.filterLat, decision.filterLng),
                    radius = FILTER_RADIUS_M,
                    fillColor = FILTER_COLOUR,
                    strokeColor = FILTER_COLOUR,
                    strokeWidth = 1f,
                )
            }
        }

        // Layer 3 — what actually reached storage.
        if (showStored) {
            state.points.forEach { point ->
                Circle(
                    center = LatLng(point.latitude, point.longitude),
                    radius = STORED_RADIUS_M,
                    fillColor = STORED_COLOUR,
                    strokeColor = STORED_COLOUR,
                    strokeWidth = 2f,
                )
            }
        }
    }
}

private val RAW_COLOUR = Color(0x999E9E9E) // grey
private val FILTER_COLOUR = Color(0x992196F3) // blue
private val STORED_COLOUR = Color(0xCC4CAF50) // green

private const val RAW_RADIUS_M = 3.0
private const val FILTER_RADIUS_M = 4.0
private const val STORED_RADIUS_M = 6.0
private const val DEBUG_ZOOM = 17f
