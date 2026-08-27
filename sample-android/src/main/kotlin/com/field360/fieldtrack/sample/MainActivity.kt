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
import androidx.activity.result.IntentSenderRequest
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
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority
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
import com.field360.fieldtrack.sample.screen.StatusScreen
import com.field360.fieldtrack.sample.screen.SyncAlertDialog
import com.field360.fieldtrack.sample.screen.TrackScreen
import java.io.File

class MainActivity : ComponentActivity() {

    private var onPermissionResult: ((Map<String, Boolean>) -> Unit)? = null

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted -> onPermissionResult?.invoke(granted) }

    private var onLocationSettingsResult: (() -> Unit)? = null

    /**
     * The result of the system "turn on location" dialog.
     *
     * The continuation runs either way. A user who declines still gets the session: the
     * SDK opens it, `CaptureGate` suspends capture, and it resumes on its own when the
     * switch comes back (EC-06, EC-07). Refusing to open the session instead would lose
     * the record of a drive that was attempted, which is the opposite of what this SDK is
     * for — and the screen says SUSPENDED throughout, so nothing is being hidden.
     */
    private val resolveLocationSettings = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) {
        onLocationSettingsResult?.invoke()
        onLocationSettingsResult = null
    }

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
                    onEnsureLocation = ::ensureLocationEnabled,
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
     * Asks the OS to switch location on, then runs [then].
     *
     * This is the half of "can this device track" that runtime permissions do not cover,
     * and the half that was missing: a granted `ACCESS_FINE_LOCATION` on a phone with the
     * location master switch off produces a session that records nothing, reports
     * `CaptureSuspended`, and looks — to anyone who did not read the reason line — like the
     * SDK silently failing.
     *
     * `SettingsClient` rather than an intent to the settings screen, because it is the one
     * route that keeps the user in the app: Play Services shows the "For a better
     * experience, turn on device location" sheet, and one tap flips the switch. The
     * settings-screen intent is the fallback for a device that has no Play Services, where
     * the sheet does not exist at all.
     *
     * Deliberately NOT called from inside the SDK. `Tracker` shows no UI by design
     * (PERMISSIONS.md §5) — it reports `ProviderState.locationServicesEnabled` and leaves
     * the asking to the host, because only the host knows when an interruption is welcome.
     * This method is that host half.
     */
    private fun ensureLocationEnabled(then: () -> Unit) {
        val request = LocationSettingsRequest.Builder()
            // The same priority the SDK will actually request with. Asking about a weaker
            // one would get a "yes" for a configuration that is not the one being started.
            .addLocationRequest(
                LocationRequest.Builder(
                    Priority.PRIORITY_HIGH_ACCURACY,
                    LOCATION_CHECK_INTERVAL_MS,
                ).build(),
            )
            // Shows the sheet even when the user previously ticked "never again" for it.
            .setAlwaysShow(true)
            .build()

        LocationServices.getSettingsClient(this)
            .checkLocationSettings(request)
            // Already satisfied: no dialog, no interruption, straight through.
            .addOnSuccessListener { then() }
            .addOnFailureListener { failure ->
                val resolution = (failure as? ResolvableApiException)?.resolution
                if (resolution == null) {
                    // No Play Services resolution available. The settings screen is the
                    // only route left, and it leaves the app — so `then()` does NOT run:
                    // the user comes back and presses Start, rather than finding a session
                    // already open behind the settings screen they were sent to.
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                    return@addOnFailureListener
                }
                onLocationSettingsResult = then
                runCatching {
                    resolveLocationSettings.launch(IntentSenderRequest.Builder(resolution).build())
                }.onFailure {
                    // The IntentSender can be dead by the time it is launched. Fall back
                    // rather than swallowing it — a Start that does nothing at all is the
                    // worst of the outcomes here.
                    onLocationSettingsResult = null
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
            }
    }

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

    private companion object {
        /** Mirrors `geolocation.intervalMs` in `SampleApplication`, so the check asks about
         *  the stream that is actually about to start. */
        const val LOCATION_CHECK_INTERVAL_MS = 15_000L
    }

    private fun trackerSettingsIntent() =
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null),
        )
}

/**
 * The five screens, numbered.
 *
 * @property code shown above the label in the bar. A number per slot is what a terminal
 *   menu offers, and it keeps the items the same width whatever they are called.
 */
private enum class Tab(val code: String, val label: String) {
    /** The configuration console. See [HomeScreen]. */
    HOME("01", "Config"),

    /**
     * What Home used to be — every diagnostic panel, unchanged.
     *
     * Second rather than first because a tester who has just changed a setting looks at
     * the result of it, and the result is here. It carries the sync-queue badge for the
     * same reason it used to sit on Home: a queue growing while nothing uploads is the
     * fault this app exists to make visible.
     */
    STATUS("02", "Status"),
    TRACK("03", "Track"),
    DEBUG("04", "Debug"),
    DECISIONS("05", "Decisions"),
}

@Composable
private fun SampleApp(
    onRequestForeground: ((Map<String, Boolean>) -> Unit) -> Unit,
    onRequestBackground: (() -> Unit) -> Unit,
    onOpenSettings: () -> Unit,
    /** Raises the system location dialog if needed, then runs the block. */
    onEnsureLocation: (() -> Unit) -> Unit,
    onShareLog: (String) -> Unit,
) {
    val viewModel: TrackerViewModel = viewModel(factory = TrackerViewModel.Factory)
    val state by viewModel.state.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(Tab.HOME) }
    val context = LocalContext.current

    // Both gates, in the order the OS enforces them: the permission dialog first — raised
    // by `start()`'s own preflight — and the location switch second, since asking to turn
    // location on before this app may read it is a question about someone else's problem.
    fun startWithLocationCheck() {
        // start() raises the permission dialog itself and returns without touching the
        // SDK, so this hands off and stops. The Grant path comes back through here once
        // the OS has answered, and the location sheet is then the next question.
        if (viewModel.hasPermissionGap()) {
            viewModel.start()
            return
        }
        onEnsureLocation {
            viewModel.refreshProviderState()
            viewModel.start()
        }
    }

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
                        //
                        // Through the location check on the way, because a grant is only
                        // half of it: permission says this app may read location, the
                        // switch says the device produces any.
                        onRequestForeground { startWithLocationCheck() }

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
                // Config edited and not yet applied. Visible from every tab for the same
                // reason the queue depth is: it is silently true and it changes what the
                // next Start will do. A whole-config comparison rather than a field count
                // — the count belongs on the screen that can show which fields.
                Tab.HOME -> "*".takeIf { state.configDraft != state.configApplied }
                // A queue that is growing while nothing uploads is the fault this app
                // exists to make visible, and it is visible from every tab.
                Tab.STATUS -> state.syncQueued.takeIf { it > 0 }?.let { "Q$it" }
                Tab.TRACK -> state.points.size.takeIf { it > 0 }?.toString()
                Tab.DEBUG -> state.rawFixes.size.takeIf { it > 0 }?.toString()
                Tab.DECISIONS -> state.decisions.size.takeIf { it > 0 }?.toString()
            },
            badgeColor = if (entry == Tab.STATUS && state.syncFailing) Hack.Red else Hack.Amber,
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
                // The configuration console. Start and Stop live here as well as being
                // the thing Apply drives: a config edit and the session it applies to are
                // one decision, and splitting them across two tabs is how a value gets
                // typed and never applied.
                Tab.HOME -> HomeScreen(
                    state = state,
                    onStart = ::startWithLocationCheck,
                    onStop = viewModel::stop,
                    onEditConfig = viewModel::editConfig,
                    onEditConfigText = viewModel::editConfigText,
                    onApplyConfig = viewModel::applyConfig,
                    onResetConfig = viewModel::resetConfig,
                    onResetToSdkDefaults = viewModel::resetConfigToSdkDefaults,
                )
                Tab.STATUS -> StatusScreen(
                    state = state,
                    // Offered on the provider card as well as on Start: a switch flipped
                    // off mid-session suspends capture, and the fix should be one tap from
                    // the line that reports it.
                    onEnableLocation = {
                        onEnsureLocation { viewModel.refreshProviderState() }
                    },
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
