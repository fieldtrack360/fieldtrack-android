package com.field360.fieldtrack.sample.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.field360.fieldtrack.sample.TrackerViewModel
import com.field360.tracker.domain.model.PermissionTier
import com.field360.tracker.motion.MotionQuality
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What the SDK is doing right now, in the panels a field run reads when something looks
 * wrong.
 *
 * This is the console Home used to be. It moved when Home became the configuration
 * editor, and the split is along a real line rather than a convenient one: everything
 * here *reports*, nothing here *sets*. A screen where a value might be a live reading or
 * might be a control you have not pressed yet is a screen that gets misread in a moving
 * vehicle.
 *
 * Ordered by the question each panel answers, hardest first. The live event stream is last
 * because it is the evidence, not the verdict.
 */
@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod") // A console: one screen, many panels.
fun StatusScreen(
    state: TrackerViewModel.UiState,
    onRequestPermissions: () -> Unit,
    onAllowBackground: () -> Unit,
    /** Raises the system location dialog. See MainActivity.ensureLocationEnabled. */
    onEnableLocation: () -> Unit = {},
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
        modifier = Modifier.fillMaxSize().background(Hack.Bg).padding(horizontal = 12.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // ── the driving-position card ───────────────────────────────────────
        item {
            val statusColor = statusColorOf(state)
            TerminalCard(
                title = "session state",
                accent = statusColor,
                trailing = state.sessionId?.take(SHORT_ID) ?: "no session",
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Badge(statusLabelOf(state), statusColor)
                    FlagBadge("capture", state.isCapturing)
                    FlagBadge("gps", state.providerState.gpsEnabled)
                    if (state.providerState.powerSaveMode) Badge("power save", Hack.Amber)
                }
                state.captureSuspendedReason?.let { Alert(it, Hack.Amber) }

                KeyValue("points", state.pointCount.toString(), Hack.Green)
                KeyValue("motion", state.motionState.name)
                KeyValue("permission", state.permissionTier.name, tierColor(state))
                KeyValue("licence", state.licenseStatus.ifBlank { "unknown" })
                KeyValue("heartbeat", state.lastHeartbeatAtMs?.let(::clock) ?: "none", Hack.Dim)
                KeyValue("last event", state.lastEvent.ifBlank { "—" }, Hack.Dim)

                state.error?.let { Alert(it, Hack.Red) }
            }
        }

        // ── the ladder, and where on it this device is ──────────────────────
        item {
            TerminalCard(
                title = "permissions",
                accent = if (backgroundActionable) Hack.Amber else Hack.Green,
            ) {
                KeyValue("tier", state.permissionTier.name, tierColor(state))
                KeyValue(
                    "background",
                    state.backgroundStep.name,
                    if (backgroundActionable) Hack.Amber else Hack.Green,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GhostButton(
                        text = "grant location",
                        onClick = onRequestPermissions,
                        modifier = Modifier.weight(1f),
                    )
                    // Opens the rationale dialog, which routes to the runtime prompt on
                    // API 29 or to Settings on API 30+, where the prompt does not exist
                    // (EC-05).
                    GhostButton(
                        text = "allow always",
                        onClick = onAllowBackground,
                        enabled = backgroundActionable,
                        accent = Hack.Amber,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        // ── the motion hardware, and whether it changed the mode ────────────
        item { MotionCard(state) }

        // ── the device, as the SDK sees it ──────────────────────────────────
        item {
            TerminalCard(title = "provider") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FlagBadge("services", state.providerState.locationServicesEnabled)
                    FlagBadge("gps", state.providerState.gpsEnabled)
                    FlagBadge("network", state.providerState.networkEnabled)
                    FlagBadge("fused", state.providerState.fusedAvailable)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FlagBadge("power save", !state.providerState.powerSaveMode)
                    FlagBadge("airplane", !state.providerState.airplaneMode)
                    Badge(state.providerState.accuracyAuthorization.name, Hack.Cyan)
                }
                // The one device-side fault a host can actually fix from inside the app.
                // A granted permission on a phone with the location switch off records
                // nothing at all, and the switch is not something start() can turn on.
                if (!state.providerState.locationServicesEnabled) {
                    Alert("location services are off — nothing can be recorded", Hack.Red)
                    HackButton(
                        text = "enable location",
                        onClick = onEnableLocation,
                        accent = Hack.Amber,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        // ── the upload half ─────────────────────────────────────────────────
        item {
            // "Not configured" is a working state, not an error: the SDK is offline-first,
            // and a host that sets no SYNC_URL simply keeps everything in Room. Dressing
            // that up as a fault sends the reader after a problem they do not have.
            val syncAccent = when {
                state.syncFailing -> Hack.Red
                state.syncEndpoint == null -> Hack.Dim
                else -> Hack.Green
            }
            TerminalCard(
                title = "uplink",
                accent = syncAccent,
                trailing = if (state.syncEndpoint == null) "offline-first" else null,
            ) {
                if (state.syncEndpoint == null) {
                    KeyValue("endpoint", "not configured", Hack.Dim)
                    Text(
                        "set SYNC_URL in local.properties to enable uploads",
                        style = MonoBody.copy(color = Hack.Dim, fontSize = 11.sp),
                    )
                } else {
                    KeyValue("endpoint", state.syncEndpoint, syncAccent)
                    // A generated per-install UUID: without it on screen there is no way
                    // to correlate a row on the server with the install that sent it.
                    KeyValue("device_id", state.syncDeviceId.take(DEVICE_ID_CHARS), Hack.Cyan)
                    // The session's start time, not the SDK's session id — see
                    // `sessionEnvelopeId`. Shown whole rather than truncated: the whole
                    // point of the format is that it is readable.
                    KeyValue(
                        "session_id",
                        state.syncSessionId ?: "omitted · no session",
                        if (state.syncSessionId == null) Hack.Dim else Hack.Cyan,
                    )
                }
                // Outside the branch on purpose. Rows accumulate whether or not anything
                // can upload them, and a queue that is merely growing emits no events at
                // all — this is the only line separating "offline" from "broken".
                KeyValue(
                    "queued",
                    "${state.syncQueued} row(s)",
                    if (state.syncQueued > 0) Hack.Amber else Hack.Green,
                )
                if (state.syncHealth.isNotBlank()) KeyValue("status", state.syncHealth, syncAccent)
                if (state.syncLastEvent.isNotBlank()) KeyValue("last tx", state.syncLastEvent, syncAccent)

                // Drains inline and reports the exact result, including the reason string
                // a background drain can only report as a status code.
                GhostButton(
                    text = if (state.syncRunning) "draining…" else "▲ sync now",
                    onClick = onSyncNow,
                    enabled = !state.syncRunning,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // ── the API surface, exercised by hand ──────────────────────────────
        item {
            TerminalCard(
                title = "api probes",
                trailing = "fences ${state.registeredGeofenceCount} · hits ${state.geofenceEventCount}",
            ) {
                // The output of the last probe, verbatim. Never summarised: the exact
                // string is the whole value of running one.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(Hack.SurfaceHi)
                        .padding(8.dp),
                ) {
                    Text(
                        text = "$ ${state.apiCheckResult}",
                        style = MonoBody.copy(
                            color = when {
                                state.apiCheckResult.contains("FAILED") -> Hack.Red
                                state.apiCheckRunning -> Hack.Amber
                                else -> Hack.Green
                            },
                            fontSize = 11.sp,
                        ),
                    )
                }
                HackButton(
                    text = "test current location",
                    onClick = onTestCurrentLocation,
                    enabled = !state.apiCheckRunning,
                    modifier = Modifier.fillMaxWidth(),
                )
                ProbeRow(
                    left = "add fence here" to onAddTestGeofence,
                    right = "list fences" to onListGeofences,
                    enabled = !state.apiCheckRunning,
                )
                ProbeRow(
                    left = "get test fence" to onGetTestGeofence,
                    right = "remove test" to onRemoveTestGeofence,
                    enabled = !state.apiCheckRunning,
                )
                ProbeRow(
                    left = "read history" to onReadGeofenceHistory,
                    right = "clear history" to onClearGeofenceHistory,
                    enabled = !state.apiCheckRunning,
                )
                GhostButton(
                    text = "add 10 fences",
                    onClick = onAddTenTestGeofences,
                    enabled = !state.apiCheckRunning,
                    modifier = Modifier.fillMaxWidth(),
                )
                // Refused mid-session on purpose: removing the stationary fence under a
                // live session is a way to break motion detection and blame the SDK.
                GhostButton(
                    text = "remove all fences",
                    onClick = onRemoveAllGeofences,
                    enabled = !state.apiCheckRunning && !state.isTracking,
                    accent = Hack.Red,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // ── what has been recorded so far ───────────────────────────────────
        item {
            TerminalCard(
                title = "recorded sessions",
                trailing = "${state.sessions.size} total",
            ) {
                if (state.sessions.isEmpty()) {
                    Text(
                        "no sessions recorded on this install",
                        style = MonoBody.copy(color = Hack.Dim, fontSize = 11.sp),
                    )
                } else {
                    // Newest first, capped: this is a jump list, not the archive. Every
                    // other tab reads whichever one is selected here.
                    state.sessions.take(SESSION_ROWS).forEach { session ->
                        SessionRow(
                            id = session.id,
                            startedAtMs = session.startedAtMs,
                            open = session.isOpen,
                            selected = session.id == state.selectedSessionId,
                            onClick = { onOpenSession(session.id) },
                        )
                    }
                }
            }
        }

        // ── the file a tester walks away with ───────────────────────────────
        item {
            TerminalCard(title = "capture log", trailing = "${state.logSizeBytes / BYTES_PER_KB} KB") {
                KeyValue("file", state.logPath.substringAfterLast('/').ifBlank { "—" }, Hack.Dim)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HackButton(
                        text = "share",
                        onClick = onShareLog,
                        enabled = state.logSizeBytes > 0,
                        modifier = Modifier.weight(1f),
                    )
                    GhostButton(
                        text = "clear",
                        onClick = onClearLog,
                        accent = Hack.Red,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        // ── the evidence ────────────────────────────────────────────────────
        item {
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "[ LIVE EVENT STREAM ]",
                    style = MonoBody.copy(
                        color = Hack.Green,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        letterSpacing = 1.sp,
                    ),
                )
                Text(
                    "${state.log.size} line(s)",
                    style = MonoBody.copy(color = Hack.Dim, fontSize = 11.sp),
                )
            }
        }

        if (state.log.isEmpty()) {
            item {
                Text(
                    "> waiting for the first event…",
                    style = MonoBody.copy(color = Hack.Dim, fontSize = 11.sp),
                )
            }
        }

        items(state.log) { line ->
            Text(
                text = "> $line",
                style = MonoBody.copy(color = logColor(line), fontSize = 11.sp),
                modifier = Modifier.fillMaxWidth().background(Hack.Surface).padding(6.dp),
            )
        }
    }
}

/**
 * What motion hardware this device has, and whether it cost the configured tracking mode.
 *
 * Worth a card of its own because this is the one SDK decision that is otherwise
 * unobservable from a host app. On `POOR` hardware the SDK rewrites `trackingMode` to
 * `CONTINUOUS` inside `ready()` and announces it with a `MOTION_DETECTION_DEGRADED`
 * event — to a `replay = 0` flow, before this app has a collector. The event never
 * arrives here. Without this card a degraded device looks exactly like a healthy one, and
 * the only symptom is a battery complaint nobody can explain.
 *
 * Read from two sources on purpose: the verdict and the effective mode come off
 * `TrackerState`, so they are what the SDK actually acted on, and the per-sensor flags
 * come off `getSensors()`, which is what explains the verdict.
 */
@Composable
private fun MotionCard(state: TrackerViewModel.UiState) {
    val sensors = state.deviceSensors
    val accent = when (state.motionQuality) {
        MotionQuality.FULL -> Hack.Green
        MotionQuality.DEGRADED -> Hack.Amber
        MotionQuality.POOR -> Hack.Red
    }
    // The mode the config console last sent, against the one in force. A difference here
    // is always the SDK overriding the host, because nothing else can change it.
    val requested = state.configApplied.geolocation.trackingMode
    val overridden = requested != state.effectiveTrackingMode

    TerminalCard(title = "motion", accent = accent, trailing = state.motionQuality.name) {
        if (sensors == null) {
            Text(
                "not probed yet",
                style = MonoBody.copy(color = Hack.Dim, fontSize = 11.sp),
            )
            return@TerminalCard
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FlagBadge("accel", sensors.accelerometer)
            FlagBadge("gyro", sensors.gyroscope)
            FlagBadge("sig motion", sensors.significantMotion)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            // Both step sensors already fold in the ACTIVITY_RECOGNITION grant — see
            // SensorProbe. A device with the hardware and no permission reports false
            // here, which is the honest answer: the SDK cannot use it either way.
            FlagBadge("step det", sensors.stepDetector)
            FlagBadge("step count", sensors.stepCounter)
            FlagBadge("rotation", sensors.rotationVector)
        }
        KeyValue("mode requested", requested.name, Hack.Dim)
        KeyValue(
            "mode in force",
            state.effectiveTrackingMode.name,
            if (overridden) Hack.Red else Hack.Green,
        )

        when {
            // The whole reason the card exists. Named consequences, not just the verdict:
            // a tester who set MOTION_ONLY for battery needs to know they are now paying
            // for a stream that runs while parked.
            overridden -> Alert(
                "motionQuality=${state.motionQuality.name} — the SDK overrode $requested " +
                    "with ${state.effectiveTrackingMode.name}. the location stream now runs " +
                    "while stationary, which costs materially more battery",
                Hack.Red,
            )

            // POOR without an override means CONTINUOUS was asked for anyway. Still worth
            // saying: motion gating is untrustworthy here, so stop detection and the
            // stationary geofence are both unreliable whatever the mode.
            state.motionQuality == MotionQuality.POOR -> Alert(
                "motion gating is not trustworthy on this device — stop detection is unreliable",
                Hack.Amber,
            )

            // The SDK doubles stopTimeoutMin on this tier. Stated here because nothing
            // reads the resolved config back, so the value in the config console is the
            // one that was sent — not the one in force.
            state.motionQuality == MotionQuality.DEGRADED -> Alert(
                "no gyroscope or no hardware trigger — stops are detected later and less " +
                    "certainly. the SDK has doubled motion.stopTimeoutMin " +
                    "(sent ${state.configApplied.motion.stopTimeoutMin} min)",
                Hack.Amber,
            )

            else -> Unit
        }

        // Said explicitly rather than left to be inferred from `step det` being false. A
        // denied ACTIVITY_RECOGNITION can reach POOR on hardware that is otherwise fine,
        // and that is the case most likely to be misread as a broken device.
        if (state.permissionTier != PermissionTier.NONE && !sensors.stepDetector && !sensors.significantMotion) {
            Text(
                "no usable motion trigger: check ACTIVITY_RECOGNITION is granted",
                style = MonoBody.copy(color = Hack.Dim, fontSize = 10.sp),
            )
        }
    }
}

/** Two probes on one line — the pairing is by topic, so the row is a unit. */
@Composable
private fun ProbeRow(
    left: Pair<String, () -> Unit>,
    right: Pair<String, () -> Unit>,
    enabled: Boolean,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GhostButton(left.first, left.second, Modifier.weight(1f), enabled)
        GhostButton(right.first, right.second, Modifier.weight(1f), enabled)
    }
}

/** One recorded session, and whether it is the one every other tab is pinned to. */
@Composable
private fun SessionRow(
    id: String,
    startedAtMs: Long,
    open: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val accent = if (open) Hack.Green else Hack.Dim
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) Hack.SurfaceHi else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = (if (selected) "> " else "  ") + id.take(SHORT_ID),
            style = MonoBody.copy(
                color = if (selected) Hack.Green else Hack.Text,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                fontSize = 11.sp,
            ),
        )
        Text(clock(startedAtMs), style = MonoBody.copy(color = Hack.Dim, fontSize = 11.sp))
        Badge(if (open) "open" else "closed", accent)
    }
}

/** A line that is not a value — a suspension reason, an error. Boxed so it is not scanned past. */
@Composable
fun Alert(text: String, color: Color) {
    Text(
        text = "! $text",
        style = MonoBody.copy(color = color, fontSize = 11.sp),
        modifier = Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = ALERT_FILL_ALPHA))
            .padding(6.dp),
    )
}

/**
 * Three states, not two.
 *
 * An open session that is not capturing — revoked permission, GPS switched off — used to
 * read exactly like a healthy one, which is how a stalled drive reached the end of the day
 * before anyone noticed. Shared with the Home header, so the two screens cannot disagree
 * about whether anything is being recorded.
 */
fun statusLabelOf(state: TrackerViewModel.UiState): String = when {
    state.isTracking && !state.isCapturing -> "suspended"
    state.isTracking -> "recording"
    else -> "idle"
}

fun statusColorOf(state: TrackerViewModel.UiState): Color = when {
    state.isTracking && !state.isCapturing -> Hack.Amber
    state.isTracking -> Hack.Green
    else -> Hack.Dim
}

/**
 * Colours a stream line by what it says.
 *
 * Prefix matching on the strings the view model already writes, rather than a second
 * severity field carried alongside them: the line is the record, and a colour derived from
 * anything else can disagree with the text next to it.
 */
private fun logColor(line: String): Color = when {
    line.startsWith("ERROR") || line.startsWith("SUSPEND") -> Hack.Red
    line.startsWith("DROP") || line.startsWith("REJECT") || line.startsWith("SKIP") -> Hack.Amber
    line.startsWith("ACCEPT") || line.startsWith("RESUME") -> Hack.Green
    line.startsWith("sync ·") || line.startsWith("HTTP") -> Hack.Cyan
    else -> Hack.Text
}

fun tierColor(state: TrackerViewModel.UiState): Color = when (state.permissionTier.name) {
    "FULL" -> Hack.Green
    "NONE" -> Hack.Red
    else -> Hack.Amber
}

/** Wall clock only: the date is on the session, and a full timestamp costs a whole row. */
private fun clock(atMs: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(atMs))

private const val SESSION_ROWS = 6
private const val DEVICE_ID_CHARS = 18
private const val BYTES_PER_KB = 1024
private const val ALERT_FILL_ALPHA = 0.12f

@Composable
@Preview(showBackground = true, backgroundColor = 0xFF060A06)
fun StatusScreenPreview() {
    StatusScreen(
        state = TrackerViewModel.UiState(
            isTracking = true,
            isCapturing = true,
            pointCount = 128,
            sessionId = "8f2c41ab-77de-4f01-9c11-0d2a55b6e900",
            licenseStatus = "debug installs waived",
            lastHeartbeatAtMs = 1_724_582_400_000L,
            syncQueued = 12,
            syncEndpoint = "https://api.example.com/v1/location/batch",
            syncDeviceId = "0f9a1c7e-3b44-4b0a-9f21-1c9d6e2a77bb",
            apiCheckResult = "OK lat=23.02231 lng=72.57136 acc=8m",
            log = listOf(
                "ACCEPT  bearing_change  acc=6m",
                "MOTION  MOVING",
                "sync · HTTP 200 · 40 row(s)",
                "DROP    accuracy 41m > 20m",
                "ERROR   FGS_START_REFUSED: started from background",
            ),
        ),
        onRequestPermissions = {},
        onAllowBackground = {},
    )
}
