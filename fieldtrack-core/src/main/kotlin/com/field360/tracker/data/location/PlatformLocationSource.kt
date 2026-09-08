package com.field360.tracker.data.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import com.field360.tracker.DesiredAccuracy
import com.field360.tracker.GeolocationConfig
import com.field360.tracker.LocationProviderType
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * The `android.location.LocationManager` half of the provider choice: GPS-only,
 * network-only and passive.
 *
 * This is the fallback EC-19 said belonged here and that `start()` used to substitute a
 * `PLAY_SERVICES_UNAVAILABLE` error for. It is now also the mechanism behind
 * [LocationProviderType], because "GPS only" is not something the fused provider can be
 * asked for — `PRIORITY_HIGH_ACCURACY` biases it towards GNSS and still returns Wi-Fi
 * centroids whenever they are what it has.
 *
 * Two behavioural differences from [FusedLocationSource] the pipeline sees:
 *  - **No batching.** `LocationManager` has no `maxUpdateDelay`, so fixes arrive one per
 *    callback and every emitted list has exactly one member. Nothing downstream cares —
 *    the collector iterates the batch either way (SOURCE-AUDIT A4) — but the Doze-window
 *    battery win that batching buys is not available here.
 *  - **No `waitForAccurateLocation`.** The first fix after registration is delivered as-is,
 *    which is precisely why the accuracy meter is applied to it rather than assumed.
 */
internal class PlatformLocationSource(
    private val context: Context,
    private val providerType: () -> LocationProviderType,
) : LocationSource {

    private val manager: LocationManager? by lazy {
        context.getSystemService(LocationManager::class.java)
    }

    private val executor by lazy { ContextCompat.getMainExecutor(context) }

    /**
     * True when the selected provider physically exists on this device.
     *
     * Deliberately `allProviders` rather than `isProviderEnabled`: existence and the user's
     * master switch are different failures with different remedies, and the second is
     * already reported continuously by `ProviderStateMonitor` as `ProviderState.gpsEnabled`.
     * Conflating them here would turn "GPS is switched off" into "this device has no GPS",
     * which sends the host to fix the wrong thing.
     */
    override fun isAvailable(): Boolean {
        val provider = providerName() ?: return false
        return runCatching { manager?.allProviders?.contains(provider) == true }.getOrDefault(false)
    }

    @SuppressLint("MissingPermission") // Callers gate on PermissionManager; EC-01 covers denial.
    override fun stream(
        config: GeolocationConfig,
        vehicular: Boolean,
        turning: Boolean,
    ): Flow<List<Location>> = callbackFlow {
        val lm = manager
        val provider = providerName()
        if (lm == null || provider == null) {
            close()
            return@callbackFlow
        }

        val interval = LocationRequests.intervalFor(config, vehicular, turning)
        val request = LocationRequestCompat.Builder(interval)
            // Same clamp as the fused path: a floor slower than the tier it governs would
            // throttle that tier back to the value it was meant to accelerate past.
            .setMinUpdateIntervalMillis(minOf(LocationRequests.fastestFloorFor(config), interval))
            // EC-119 — 0 by contract. A non-zero OS distance filter is a drift generator.
            .setMinUpdateDistanceMeters(config.distanceFilterM)
            .setQuality(qualityFor(config))
            .build()

        val listener = LocationListenerCompat { location -> trySend(listOf(location)) }

        val registered = runCatching {
            LocationManagerCompat.requestLocationUpdates(lm, provider, request, executor, listener)
        }.isSuccess
        if (!registered) {
            close()
            return@callbackFlow
        }

        awaitClose { runCatching { LocationManagerCompat.removeUpdates(lm, listener) } }
    }

    @SuppressLint("MissingPermission")
    override suspend fun oneShot(config: GeolocationConfig): Location? {
        val lm = manager ?: return null
        // PASSIVE has nothing to ask: it only ever sees what another app's request produced,
        // so the honest answer is the last such fix, or none.
        val provider = providerName() ?: return null
        if (providerType() == LocationProviderType.PASSIVE) {
            return runCatching { lm.getLastKnownLocation(provider) }.getOrNull()
        }

        val signal = CancellationSignal()
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { runCatching { signal.cancel() } }
            val started = runCatching {
                LocationManagerCompat.getCurrentLocation(lm, provider, signal, executor) { location ->
                    if (continuation.isActive) continuation.resume(location)
                }
            }.isSuccess
            // A provider that refuses registration never calls the consumer, and the caller's
            // `withTimeoutOrNull` would sit on it for the full one-shot budget for nothing.
            if (!started && continuation.isActive) continuation.resume(null)
        }
    }

    private fun providerName(): String? = when (providerType()) {
        LocationProviderType.GPS_ONLY -> LocationManager.GPS_PROVIDER
        LocationProviderType.NETWORK_ONLY -> LocationManager.NETWORK_PROVIDER
        LocationProviderType.PASSIVE -> LocationManager.PASSIVE_PROVIDER
        // Routed to FusedLocationSource; reaching here would be a wiring bug, not a state.
        LocationProviderType.FUSED -> null
    }

    /**
     * `quality` is a hint about power, not a provider selector — the provider is already
     * pinned by name. Navigation forces the high setting for the same reason the fused path
     * does: honouring a BALANCED preference there would feed the position animation the
     * coarsest fixes the chip is willing to produce.
     */
    private fun qualityFor(config: GeolocationConfig): Int = when {
        config.navigationMode -> LocationRequestCompat.QUALITY_HIGH_ACCURACY
        config.desiredAccuracy == DesiredAccuracy.HIGH -> LocationRequestCompat.QUALITY_HIGH_ACCURACY
        config.desiredAccuracy == DesiredAccuracy.BALANCED -> LocationRequestCompat.QUALITY_BALANCED_POWER_ACCURACY
        else -> LocationRequestCompat.QUALITY_LOW_POWER
    }
}

/**
 * Sends each call to the source the host's [LocationProviderType] selected.
 *
 * A router rather than a `when` at each call site because [LocationSource.isAvailable] has
 * no config parameter and is read from three places, one of them a broadcast receiver. The
 * selection is therefore held here, set once per session by `StartTrackingUseCase` before
 * anything reads it, and applied uniformly.
 */
internal class RoutingLocationSource(
    private val fused: LocationSource,
    platformFactory: (() -> LocationProviderType) -> LocationSource,
) : LocationSource {

    @Volatile
    private var selected: LocationProviderType = LocationProviderType.FUSED

    private val platform: LocationSource = platformFactory { selected }

    /** Called before [isAvailable] is consulted for a new session. */
    fun select(type: LocationProviderType) {
        selected = type
    }

    override fun stream(
        config: GeolocationConfig,
        vehicular: Boolean,
        turning: Boolean,
    ): Flow<List<Location>> = sourceFor(config.providerType).stream(config, vehicular, turning)

    override suspend fun oneShot(config: GeolocationConfig): Location? =
        sourceFor(config.providerType).oneShot(config)

    override fun isAvailable(): Boolean = sourceFor(selected).isAvailable()

    /**
     * Routed on [selected], not on a config, because the caller tearing a stream down has
     * no config in hand — and the registration being flushed belongs to whichever source
     * opened it, which is the one [selected] names.
     */
    override suspend fun flush() {
        sourceFor(selected).flush()
    }

    /**
     * Config wins where a config is in hand; [selected] is the fallback for the one call
     * that has none. The two can only disagree between a host editing its config and the
     * next `start()`, and preferring the config there is what makes a provider change take
     * effect on the stream it was meant for.
     */
    private fun sourceFor(type: LocationProviderType): LocationSource =
        if (type == LocationProviderType.FUSED) fused else platform
}
