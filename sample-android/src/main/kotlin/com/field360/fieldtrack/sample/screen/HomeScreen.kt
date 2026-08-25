package com.field360.fieldtrack.sample.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.field360.fieldtrack.sample.TrackerViewModel

@Composable
fun HomeScreen(
    state: TrackerViewModel.UiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRequestPermissions: () -> Unit,
    onAllowBackground: () -> Unit,
    onSyncNow: () -> Unit = {},
    onShareLog: () -> Unit = {},
    onClearLog: () -> Unit = {},
    onOpenSession: (String) -> Unit = {},
    onTestCurrentLocation: () -> Unit = {},
    onAddTestGeofence: () -> Unit = {},
    onAddTenTestGeofences: () -> Unit = {},
    onListGeofences: () -> Unit = {},
    onGetTestGeofence: () -> Unit = {},
    onRemoveTestGeofence: () -> Unit = {},
    onRemoveAllGeofences: () -> Unit = {},
    onReadGeofenceHistory: () -> Unit = {},
    onClearGeofenceHistory: () -> Unit = {},
) {
    val backgroundActionable = state.backgroundStep != TrackerViewModel.BackgroundStep.GRANTED &&
        state.backgroundStep != TrackerViewModel.BackgroundStep.NOT_APPLICABLE

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = when {
                            state.isTracking && !state.isCapturing -> "Tracking (suspended)"
                            state.isTracking -> "Tracking"
                            else -> "Idle"
                        },
                        style = MaterialTheme.typography.titleLarge,
                        // The whole reason `isCapturing` exists: an open session with a
                        // revoked permission or a switched-off GPS used to read exactly
                        // like a healthy one, so a stalled drive looked fine on screen.
                        color = if (state.isTracking && !state.isCapturing) {
                            MaterialTheme.colorScheme.error
                        } else {
                            Color.Unspecified
                        },
                    )
                    state.captureSuspendedReason?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                    Text("Motion: ${state.motionState}")
                    Text(
                        "Provider: gps=${state.providerState.gpsEnabled} " +
                            "network=${state.providerState.networkEnabled} " +
                            "powerSave=${state.providerState.powerSaveMode}",
                    )
                    Text("Permission: ${state.permissionTier}")
                    Text("License: ${state.licenseStatus.ifBlank { "unknown" }}")
                    Text(
                        "Heartbeat: ${state.lastHeartbeatAtMs?.toString() ?: "none"}",
                    )
                    Text("Session: ${state.sessionId?.take(8) ?: "—"}")
                    Text("Points accepted: ${state.pointCount}")

                    // The upload half. "Not configured" is a working state, not an error —
                    // the SDK is offline-first and a host that sets no SYNC_URL simply
                    // keeps everything in Room, so this must not be dressed up as a fault.
                    val syncColour = if (state.syncFailing) {
                        MaterialTheme.colorScheme.error
                    } else {
                        Color.Unspecified
                    }
                    if (state.syncEndpoint == null) {
                        Text("Sync: not configured — set SYNC_URL in local.properties", color = syncColour)
                    } else {
                        Text("Sync: ${state.syncEndpoint}", color = syncColour)
                        // Sent as `device_id` in every request envelope. Shown because it
                        // is a generated UUID — otherwise there is no way to correlate a
                        // row on the server with the install that sent it.
                        Text("device_id: ${state.syncDeviceId}", color = syncColour)
                    }
                    // Outside the branch on purpose: rows accumulate in Room whether or
                    // not anything can upload them, and the backlog is the number worth
                    // showing most when nothing can. It climbs with no network and drops
                    // to zero shortly after one returns; a queue that is merely growing
                    // emits no events at all, so this is the only line that separates
                    // "offline" from "broken".
                    Text("Queued: ${state.syncQueued} row(s)", color = syncColour)
                    if (state.syncHealth.isNotBlank()) {
                        Text("Status: ${state.syncHealth}", color = syncColour)
                    }
                    if (state.syncLastEvent.isNotBlank()) {
                        Text("Last exchange: ${state.syncLastEvent}", color = syncColour)
                    }
                    // Drains inline and reports the exact result — including the reason
                    // string a background drain can only report as a status code. The
                    // fastest way to find out why nothing is arriving.
                    OutlinedButton(
                        onClick = onSyncNow,
                        enabled = !state.syncRunning,
                    ) { Text(if (state.syncRunning) "Syncing…" else "Sync now") }

                    state.error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onStart, enabled = !state.isTracking) { Text("Start") }
                OutlinedButton(onClick = onStop, enabled = state.isTracking) { Text("Stop") }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onRequestPermissions) { Text("Grant location") }
                // Opens the rationale dialog, which then routes to the runtime prompt (API 29)
                // or to Settings (API 30+, where the prompt does not exist — EC-05).
                OutlinedButton(onClick = onAllowBackground, enabled = backgroundActionable) {
                    Text("Allow all the time")
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("API checks", style = MaterialTheme.typography.titleMedium)
                    Text(
                        state.apiCheckResult,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Geofences: ${state.registeredGeofenceCount} · crossings: ${state.geofenceEventCount}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(
                        onClick = onTestCurrentLocation,
                        enabled = !state.apiCheckRunning,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Test current location") }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = onAddTestGeofence,
                            enabled = !state.apiCheckRunning,
                        ) { Text("Add fence here") }
                        OutlinedButton(
                            onClick = onListGeofences,
                            enabled = !state.apiCheckRunning,
                        ) { Text("List fences") }
                    }
                    OutlinedButton(
                        onClick = onAddTenTestGeofences,
                        enabled = !state.apiCheckRunning,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Add 10 fences") }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = onGetTestGeofence,
                            enabled = !state.apiCheckRunning,
                        ) { Text("Get test fence") }
                        OutlinedButton(
                            onClick = onRemoveTestGeofence,
                            enabled = !state.apiCheckRunning,
                        ) { Text("Remove test") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = onReadGeofenceHistory,
                            enabled = !state.apiCheckRunning,
                        ) { Text("Read history") }
                        OutlinedButton(
                            onClick = onClearGeofenceHistory,
                            enabled = !state.apiCheckRunning,
                        ) { Text("Clear history") }
                    }
                    OutlinedButton(
                        onClick = onRemoveAllGeofences,
                        enabled = !state.apiCheckRunning && !state.isTracking,
                    ) { Text("Remove all fences") }
                }
            }
        }

        // The capture log is the whole point of the sample on a field run, and until now
        // there was no way to get it off the phone without a laptop and `adb pull`.
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("Capture log", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "${state.logSizeBytes / 1024} KB · ${state.logPath.substringAfterLast('/')}",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onShareLog, enabled = state.logSizeBytes > 0) { Text("Share") }
                        OutlinedButton(onClick = onClearLog) { Text("Clear") }
                    }
                }
            }
        }
        item { Text("Live events", style = MaterialTheme.typography.titleMedium) }
        items(state.log) { line ->
            Text(
                text = line,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
@Preview(showBackground = true)
fun HomeScreenPreview() {
    HomeScreen(
        state = TrackerViewModel.UiState(
            isTracking = true,
            pointCount = 10,
            licenseStatus = "debug installs waived",
            lastHeartbeatAtMs = 1234567890L,
            log = listOf("Started", "Moving", "Point collected")
        ),
        onStart = {},
        onStop = {},
        onRequestPermissions = {},
        onAllowBackground = {}
    )
}
