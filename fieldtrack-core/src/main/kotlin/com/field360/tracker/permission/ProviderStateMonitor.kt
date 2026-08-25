package com.field360.tracker.permission

import android.app.AppOpsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.field360.tracker.data.location.LocationSource
import com.field360.tracker.domain.model.ErrorCode
import com.field360.tracker.domain.model.LocationAccuracy
import com.field360.tracker.domain.model.PermissionTier
import com.field360.tracker.domain.model.ProviderState
import com.field360.tracker.domain.model.TrackerEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One [StateFlow] fed by three independent sources: permission changes, the GPS master
 * switch, and battery saver.
 *
 * Polling is not acceptable here — a permission can be revoked while the foreground
 * service is running, and the SDK must react immediately and stop cleanly rather than
 * keep a dead provider registered and silently lose data (EC-06).
 */
internal class ProviderStateMonitor(
    private val context: Context,
    private val permissions: PermissionManager,
    private val locationSource: LocationSource,
    private val events: MutableSharedFlow<TrackerEvent>,
) {

    private val _state = MutableStateFlow(ProviderState())
    val state: StateFlow<ProviderState> = _state.asStateFlow()

    /**
     * The current state packed for storage on a point — see `ProviderSnapshot`.
     *
     * A plain field read, which is the whole reason it exists: the ingest path stamps this
     * on every fix and must not be able to trigger permission or Settings queries from
     * inside the capture loop. [refresh] owns the sampling; this only reads what it left.
     */
    val snapshotFlags: Int get() = _state.value.toSnapshot().toFlags()

    private val appOps = context.getSystemService(AppOpsManager::class.java)
    private val locationManager = context.getSystemService(LocationManager::class.java)
    private val powerManager = context.getSystemService(PowerManager::class.java)

    private var opsListener: AppOpsManager.OnOpChangedListener? = null

    /**
     * Every op actually registered, so [stop] can unwind exactly what [start] armed.
     *
     * `startWatchingMode` is per-op: the listener that used to be registered for
     * `OPSTR_FINE_LOCATION` alone tested `OPSTR_COARSE_LOCATION` in its body for a callback
     * it could never receive. An app granted coarse only — the common shape after an
     * Android 12 "Approximate" choice — therefore had no revocation signal at all.
     */
    private val watchedOps = mutableListOf<String>()
    private var registered = false

    /**
     * Whether [refresh] has produced a real observation yet.
     *
     * The initial [ProviderState] is a placeholder — every flag false, tier `NONE` — not
     * something the device ever reported. Without this, the first refresh on a perfectly
     * healthy phone would announce a `NONE → FULL` permission change and a location-services
     * recovery that never happened, and a host that re-prompts on `PermissionChange` would
     * do it on every launch.
     */
    private var observed = false

    private val systemReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = refresh()
    }

    fun start() {
        if (registered) return
        registered = true

        // Pattern lifted from the reference (AttendanceLoggerService.kt:877-892): the
        // only way to learn about a mid-session revocation without polling (EC-06).
        opsListener = AppOpsManager.OnOpChangedListener { op, packageName ->
            if (packageName == context.packageName && op in WATCHED_OPS) refresh()
        }.also { listener ->
            WATCHED_OPS.forEach { op ->
                // One OEM refusing one op must not cost the others. A device that will not
                // let us watch coarse should still report a fine-location revocation.
                runCatching { appOps.startWatchingMode(op, context.packageName, listener) }
                    .onSuccess { watchedOps += op }
            }
        }

        context.registerReceiver(
            systemReceiver,
            IntentFilter().apply {
                addAction(LocationManager.PROVIDERS_CHANGED_ACTION) // EC-16
                addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED) // EC-21
                // Protected system broadcast, so a context-registered receiver needs no
                // exported flag even on API 34+. Airplane mode gates network positioning
                // without touching the GPS provider, which is a degradation that otherwise
                // reads as "the device stopped reporting" with nothing to explain it.
                addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            },
        )

        refresh()
    }

    fun stop() {
        if (!registered) return
        registered = false
        // `stopWatchingMode` unregisters the listener wholesale rather than per-op, so one
        // call is enough — the list exists to record what was armed, and is cleared with it.
        opsListener?.let { runCatching { appOps.stopWatchingMode(it) } }
        opsListener = null
        watchedOps.clear()
        runCatching { context.unregisterReceiver(systemReceiver) }
    }

    /**
     * Recomputes and emits.
     *
     * This is the reporting half only. Acting on a downgrade — tearing the stream down,
     * and rebuilding it when the grant or the provider comes back — belongs to
     * `CaptureGate`, which collects [state]. Either way the session stays **open**:
     * whether to end it is the host's decision, never a side effect of a permission
     * toggle (EC-07).
     */
    fun refresh() {
        val previous = _state.value
        val next = ProviderState(
            gpsEnabled = runCatching {
                locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            }.getOrDefault(false),
            networkEnabled = runCatching {
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            }.getOrDefault(false),
            locationServicesEnabled = locationServicesEnabled(),
            permission = permissions.tier(),
            accuracyAuthorization = permissions.accuracy(),
            fusedAvailable = locationSource.isAvailable(),
            powerSaveMode = powerManager.isPowerSaveMode,
            airplaneMode = airplaneMode(),
        )
        val first = !observed
        observed = true
        if (next == previous && !first) return

        _state.value = next
        events.tryEmit(TrackerEvent.ProviderChange(next))

        // Nothing transitioned — this is the first look at the device, and the "previous"
        // value it would be compared against is a constructor default. `CaptureGate` acts
        // on the state itself and so is unaffected; only the edge reports are skipped.
        if (first) return

        if (next.powerSaveMode != previous.powerSaveMode) {
            events.tryEmit(TrackerEvent.PowerSaveChange(next.powerSaveMode))
        }

        // Every tier movement, in both directions. The old code reported one edge —
        // FULL to anything else — which meant a FOREGROUND_ONLY app losing location
        // outright emitted nothing at all, and a re-grant was equally silent. A host that
        // wants to re-prompt needs the revoke; a host that wants to stop nagging needs
        // the recovery.
        if (previous.permission != next.permission ||
            previous.accuracyAuthorization != next.accuracyAuthorization
        ) {
            events.tryEmit(
                TrackerEvent.PermissionChange(
                    previous = previous.permission,
                    current = next.permission,
                    accuracy = next.accuracyAuthorization,
                ),
            )
        }

        // The code has to match what was actually lost. BACKGROUND_PERMISSION_MISSING for
        // a total revocation sent hosts to the "Allow all the time" screen for a grant the
        // user no longer held at any tier.
        when {
            previous.permission != PermissionTier.NONE && next.permission == PermissionTier.NONE ->
                events.tryEmit(
                    TrackerEvent.Error(
                        ErrorCode.PERMISSION_DENIED,
                        "Location permission revoked while tracking",
                    ),
                )

            previous.permission == PermissionTier.FULL && next.permission != PermissionTier.FULL ->
                events.tryEmit(
                    TrackerEvent.Error(
                        ErrorCode.BACKGROUND_PERMISSION_MISSING,
                        "Background location revoked; degraded to ${next.permission}",
                    ),
                )
        }

        // Precise to approximate is orthogonal to the tier and was previously unreported
        // mid-session, even though `start()` refuses to begin a session on it (EC-02).
        if (previous.accuracyAuthorization == LocationAccuracy.PRECISE &&
            next.accuracyAuthorization == LocationAccuracy.APPROXIMATE
        ) {
            events.tryEmit(
                TrackerEvent.Error(
                    ErrorCode.COARSE_ONLY,
                    "Precise location downgraded to approximate; accuracy gating will reject most fixes",
                ),
            )
        }

        // Keyed on "can any provider answer", not on GPS alone: a device with GPS off and
        // network positioning on is not in an outage, and the old check called it one.
        val couldLocate = previous.canLocate()
        val canLocate = next.canLocate()
        if (couldLocate != canLocate) {
            events.tryEmit(TrackerEvent.LocationServicesChange(canLocate, next))
            if (!canLocate) {
                events.tryEmit(
                    TrackerEvent.Error(
                        ErrorCode.LOCATION_DISABLED,
                        "Location services turned off (gps=${next.gpsEnabled}, " +
                            "network=${next.networkEnabled}, master=${next.locationServicesEnabled})",
                    ),
                )
            }
        }
    }

    /** Whether any provider the SDK can use is switched on. Mirrors `CaptureGate`. */
    private fun ProviderState.canLocate(): Boolean =
        locationServicesEnabled && (gpsEnabled || networkEnabled)

    /**
     * The Settings master switch, which is **not** the union of the two providers: a device
     * can report location enabled with GPS switched off, and `isLocationEnabled` is the only
     * thing that answers the switch itself. Below API 28 there is no such call, so the union
     * is the honest approximation and is documented as one.
     */
    private fun locationServicesEnabled(): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            locationManager.isLocationEnabled
        } else {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
    }.getOrDefault(false)

    /**
     * `Settings.Global.AIRPLANE_MODE_ON`, which needs no permission. A read that throws
     * answers `false` rather than propagating — this is a diagnostic field, and an SDK that
     * fails to start because a Settings read was denied by an OEM is worse than one that
     * misses a flag.
     */
    private fun airplaneMode(): Boolean = runCatching {
        Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
    }.getOrDefault(false)

    private companion object {
        /**
         * Both location ops, because either can move on its own.
         *
         * Fine covers the "Precise" toggle and the all-the-time/while-using downgrade;
         * coarse is the only signal an approximate-only app ever gets. Watching one and
         * testing for the other — which is what this class did — is a listener that can
         * never fire for half the grants Android issues.
         */
        val WATCHED_OPS = listOf(
            AppOpsManager.OPSTR_FINE_LOCATION,
            AppOpsManager.OPSTR_COARSE_LOCATION,
        )
    }
}
