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
    private var registered = false

    private val systemReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = refresh()
    }

    fun start() {
        if (registered) return
        registered = true

        // Pattern lifted from the reference (AttendanceLoggerService.kt:877-892): the
        // only way to learn about a mid-session revocation without polling (EC-06).
        opsListener = AppOpsManager.OnOpChangedListener { op, packageName ->
            if (packageName == context.packageName &&
                (op == AppOpsManager.OPSTR_FINE_LOCATION || op == AppOpsManager.OPSTR_COARSE_LOCATION)
            ) {
                refresh()
            }
        }.also {
            appOps.startWatchingMode(AppOpsManager.OPSTR_FINE_LOCATION, context.packageName, it)
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
        opsListener?.let(appOps::stopWatchingMode)
        opsListener = null
        runCatching { context.unregisterReceiver(systemReceiver) }
    }

    /**
     * Recomputes and emits. A downgrade stops the stream but deliberately leaves the
     * session **open** — whether to end it is the host's decision, never a side effect
     * of a permission toggle. An upgrade restarts automatically (EC-07).
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
        if (next == previous) return

        _state.value = next
        events.tryEmit(TrackerEvent.ProviderChange(next))

        if (next.powerSaveMode != previous.powerSaveMode) {
            events.tryEmit(TrackerEvent.PowerSaveChange(next.powerSaveMode))
        }

        if (previous.permission == PermissionTier.FULL && next.permission != PermissionTier.FULL) {
            events.tryEmit(
                TrackerEvent.Error(
                    ErrorCode.BACKGROUND_PERMISSION_MISSING,
                    "Background location revoked; degraded to ${next.permission}",
                ),
            )
        }

        if (previous.gpsEnabled && !next.gpsEnabled) {
            events.tryEmit(
                TrackerEvent.Error(ErrorCode.LOCATION_DISABLED, "Location services turned off"),
            )
        }
    }

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
}
