package com.field360.fieldtrack.sample

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.field360.fieldtrack.sample.screen.BackgroundLocationDialog
import com.field360.fieldtrack.sample.screen.DebugOverlayScreen
import com.field360.fieldtrack.sample.screen.DecisionLogScreen
import com.field360.fieldtrack.sample.screen.Hack
import com.field360.fieldtrack.sample.screen.HackerBottomBar
import com.field360.fieldtrack.sample.screen.HackerTab
import com.field360.fieldtrack.sample.screen.HomeScreen
import com.field360.fieldtrack.sample.screen.hackerColorScheme
import com.field360.fieldtrack.sample.screen.hackerTypography
import com.field360.fieldtrack.sample.screen.LicenseAlertDialog
import com.field360.fieldtrack.sample.screen.PermissionAlertDialog
import com.field360.fieldtrack.sample.screen.SyncAlertDialog
import com.field360.fieldtrack.sample.screen.TrackScreen
import java.io.File

class MainActivity : ComponentActivity() {

    private var onPermissionResult: ((Map<String, Boolean>) -> Unit)? = null

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted -> onPermissionResult?.invoke(granted) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // The terminal theme is applied here rather than per screen so Material's own
            // surfaces — the four dialogs this app raises, menus, toasts — come out in the
            // same palette. A proportional white dialog over a green console is how a
            // theme announces that it is only skin deep.
            MaterialTheme(
                colorScheme = hackerColorScheme(),
                typography = hackerTypography(),
            ) {
                SampleApp(
                    onRequestForeground = { callback ->
                        onPermissionResult = callback
                        requestPermissions.launch(foregroundPermissions())
                    },
                    onRequestBackground = { callback ->
                        onPermissionResult = { callback() }
                        requestBackgroundLocation()
                    },
                    onOpenSettings = { startActivity(trackerSettingsIntent()) },
                    onShareLog = ::shareLog,
                )
            }
        }
    }

    /**
     * Notifications first on API 33+, then fine + coarse together. Background is a
     * SEPARATE, later step — bundling it here makes Android deny it silently (EC-04),
     * and from API 30 it cannot be prompted at all (EC-05).
     */
    private fun foregroundPermissions(): Array<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            add(Manifest.permission.ACTIVITY_RECOGNITION)
        }
    }.toTypedArray()

    /**
     * Hands the capture file out as a content:// URI. `adb pull` works too, but a field
     * tester with no laptop needs a way to get the file off the phone.
     */
    private fun shareLog(path: String) {
        val file = File(path)
        if (!file.exists()) return

        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Tracker capture log")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Share capture log"))
    }

    /**
     * Reached only when the view model reports [TrackerViewModel.BackgroundStep.PROMPT],
     * which it produces on API 29 alone — from API 30 a runtime request for background
     * location silently does nothing and the answer is SETTINGS instead (EC-05).
     *
     * The lint suppression is safe for the same reason plus one more: the permission name
     * is a compile-time String constant, so nothing resolves against the platform at
     * runtime on older devices.
     */
    @SuppressLint("InlinedApi")
    private fun requestBackgroundLocation() {
        requestPermissions.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
    }

    private fun trackerSettingsIntent() =
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null),
        )
}

/**
 * The four screens, numbered.
 *
 * @property code shown above the label in the bar. A number per slot is what a terminal
 *   menu offers, and it keeps the four items the same width whatever they are called.
 */
private enum class Tab(val code: String, val label: String) {
    HOME("01", "Home"),
    TRACK("02", "Track"),
    DEBUG("03", "Debug"),
    DECISIONS("04", "Decisions"),
}

@Composable
private fun SampleApp(
    onRequestForeground: ((Map<String, Boolean>) -> Unit) -> Unit,
    onRequestBackground: (() -> Unit) -> Unit,
    onOpenSettings: () -> Unit,
    onShareLog: (String) -> Unit,
) {
    val viewModel: TrackerViewModel = viewModel(factory = TrackerViewModel.Factory)
    val state by viewModel.state.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(Tab.HOME) }
    val context = LocalContext.current

    // Collected here rather than in the view model because a toast needs a Context, and
    // handing one to a view model is how it outlives the activity it came from.
    LaunchedEffect(Unit) {
        viewModel.toasts.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    // Grants change outside the app — the background route literally leaves for Settings
    // and comes back — so the ladder is re-read on every resume, not just at startup.
    LifecycleResumeEffect(Unit) {
        viewModel.refreshPermissions()
        onPauseOrDispose {}
    }

    // Licence problems interrupt regardless of which tab is showing: a revoked licence
    // stops tracking, and a check that could not run is worth knowing about wherever the
    // user happens to be.
    state.licenseAlert?.let { alert ->
        LicenseAlertDialog(alert = alert, onDismiss = viewModel::dismissLicenseAlert)
    }

    // Upload problems interrupt from any tab too. Deliberately below the licence dialog:
    // a licence that stopped tracking is why there is nothing to upload, and answering
    // the upload question first would send the reader after the wrong fault.
    state.syncAlert?.let { alert ->
        SyncAlertDialog(
            alert = alert,
            onRetry = {
                viewModel.dismissSyncAlert()
                viewModel.syncNow()
            },
            onDismiss = viewModel::dismissSyncAlert,
        )
    }

    // Above the background dialog on purpose: this is the one raised by Start, and it can
    // itself hand off to that ladder. Two dialogs stacked would leave the user answering
    // the second question first.
    state.permissionAlert?.let { alert ->
        PermissionAlertDialog(
            alert = alert,
            onGrant = {
                viewModel.dismissPermissionAlert()
                when (alert.action) {
                    // The runtime array covers notifications, fine, coarse and activity
                    // recognition in one pass. Background is never in it — bundling it is a
                    // silent denial (EC-04) — so the background rung is offered on the way
                    // back, exactly as the Grant location button does.
                    PermissionAction.REQUEST_RUNTIME ->
                        // Back into start() once the OS has answered, not into a dead end:
                        // the preflight runs again on the new grants and either starts or
                        // says what is still missing. A denial therefore re-states the cost
                        // rather than silently doing nothing.
                        onRequestForeground { viewModel.start() }

                    PermissionAction.BACKGROUND_LADDER -> viewModel.showBackgroundRationale()
                }
            },
            onStartAnyway = viewModel::startAnyway,
            onDismiss = viewModel::dismissPermissionAlert,
        )
    }

    if (state.showBackgroundDialog) {
        BackgroundLocationDialog(
            step = state.backgroundStep,
            onDismiss = viewModel::dismissBackgroundRationale,
            onConfirm = {
                viewModel.onBackgroundRationaleConfirmed()
                when (state.backgroundStep) {
                    TrackerViewModel.BackgroundStep.SETTINGS -> onOpenSettings()
                    TrackerViewModel.BackgroundStep.PROMPT ->
                        onRequestBackground(viewModel::refreshPermissions)
                    // Fine is not granted yet, so background is not grantable. Climb one
                    // rung, then re-open this dialog on the way back.
                    TrackerViewModel.BackgroundStep.NEEDS_FOREGROUND_FIRST ->
                        onRequestForeground { viewModel.showBackgroundRationale() }
                    else -> Unit
                }
            },
        )
    }

    // Counts that belong on the bar rather than on a screen: they are the reason to
    // switch tabs, and they are useless on the tab that is already showing.
    val tabs = Tab.entries.map { entry ->
        HackerTab(
            code = entry.code,
            label = entry.label,
            badge = when (entry) {
                // A queue that is growing while nothing uploads is the fault this app
                // exists to make visible, and it is visible from every tab.
                Tab.HOME -> state.syncQueued.takeIf { it > 0 }?.let { "Q$it" }
                Tab.TRACK -> state.points.size.takeIf { it > 0 }?.toString()
                Tab.DEBUG -> state.rawFixes.size.takeIf { it > 0 }?.toString()
                Tab.DECISIONS -> state.decisions.size.takeIf { it > 0 }?.toString()
            },
            badgeColor = if (entry == Tab.HOME && state.syncFailing) Hack.Red else Hack.Amber,
        )
    }

    Scaffold(
        containerColor = Hack.Bg,
        bottomBar = {
            HackerBottomBar(
                tabs = tabs,
                selected = tabs[tab.ordinal],
                onSelect = { selected ->
                    val entry = Tab.entries[tabs.indexOf(selected)]
                    tab = entry
                    // Every screen but Home reads stored data, so pull it fresh on entry
                    // rather than holding a permanent query open. The session list is
                    // loaded either way — Track has its own picker and an empty dropdown
                    // there is indistinguishable from having recorded nothing.
                    viewModel.loadSessions()
                    if (entry != Tab.HOME) viewModel.refresh()
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().background(Hack.Bg).padding(padding)) {
            when (tab) {
                Tab.HOME -> HomeScreen(
                    state = state,
                    onStart = viewModel::start,
                    onStop = viewModel::stop,
                    // Foreground first, then offer the background rung — never both in
                    // one request array, which Android denies silently (EC-04).
                    onRequestPermissions = {
                        onRequestForeground { viewModel.showBackgroundRationale() }
                    },
                    onAllowBackground = viewModel::showBackgroundRationale,
                    onSyncNow = { viewModel.syncNow() },
                    onShareLog = { onShareLog(state.logPath) },
                    onClearLog = viewModel::clearLog,
                    onTestCurrentLocation = viewModel::testCurrentLocation,
                    onAddTestGeofence = viewModel::addTestGeofence,
                    onAddTenTestGeofences = viewModel::addTenTestGeofences,
                    onListGeofences = viewModel::listTestGeofences,
                    onGetTestGeofence = viewModel::getTestGeofence,
                    onRemoveTestGeofence = viewModel::removeTestGeofence,
                    onRemoveAllGeofences = viewModel::removeAllTestGeofences,
                    onReadGeofenceHistory = viewModel::readTestGeofenceHistory,
                    onClearGeofenceHistory = viewModel::clearTestGeofenceHistory,
                    // Load the session, then jump to the map. openSession() is what
                    // pins every other tab to that id.
                    onOpenSession = { sessionId ->
                        viewModel.openSession(sessionId)
                        tab = Tab.TRACK
                    },
                )
                // All three diagnostic tabs share one selection: openSession() pins
                // `selectedSessionId`, and every tab reads it. Change the session on any
                // of them and the others follow.
                Tab.TRACK -> TrackScreen(
                    state,
                    onOpenSession = viewModel::openSession,
                    onSnapToRoad = viewModel::setSnapToRoad,
                )
                Tab.DEBUG ->  DebugOverlayScreen(state, onOpenSession = viewModel::openSession)
                Tab.DECISIONS -> DecisionLogScreen(state, onOpenSession = viewModel::openSession)
            }
        }
    }
}
