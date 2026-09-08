package com.field360.tracker.data.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import com.field360.tracker.DesiredAccuracy
import com.field360.tracker.GeolocationConfig
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.Granularity
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

/**
 * Abstracts fused vs. platform location so "no Play Services" (Huawei, AOSP) is a
 * swap at one seam rather than a branch scattered everywhere (EC-19).
 */
internal interface LocationSource {
    fun stream(config: GeolocationConfig, vehicular: Boolean, turning: Boolean = false): Flow<List<Location>>
    suspend fun oneShot(config: GeolocationConfig): Location?
    fun isAvailable(): Boolean

    /**
     * Delivers whatever the OS is still holding for the current registration, and returns
     * once it has been delivered.
     *
     * Called by [com.field360.tracker.capture.LocationStreamController] immediately before
     * it tears a stream down, which it does on **every** cadence flip. Without it the
     * buffer is simply discarded: `removeLocationUpdates` makes no promise about batched
     * fixes it has not handed over yet, and this SDK asks for a batch window of one to two
     * sampling intervals — so a flip could cost up to 30 s of fixes that the chip had
     * already taken.
     *
     * That cost is paid where it hurts most. Cadence flips are not rare and they are not
     * random: the vehicular tier is dropped and restored at every traffic stop, and the
     * turn burst arms and expires around every corner. All of them happen while driving,
     * none of them happen to a parked phone — so the loss reads in the field as "it
     * captures fine until I start moving".
     *
     * Default no-op: only a batching provider has anything to flush.
     */
    suspend fun flush() {
        // No batching by default; nothing is being held.
    }
}

internal object LocationRequests {

    /**
     * Three cadence tiers, fastest wins: turning → vehicular → normal (EC-45).
     *
     * Navigation sits above all three: the tiers are a bet about where the geometry is,
     * and navigation is the host declaring the bet is off — every fix is wanted
     * (SMOOTH-NAV-PLAN Phase 1).
     */
    fun intervalFor(config: GeolocationConfig, vehicular: Boolean, turning: Boolean): Long = when {
        config.navigationMode -> config.navigationIntervalMs
        turning && config.turnBurst -> config.turnBurstIntervalMs
        vehicular && config.adaptiveCadence -> config.vehicularIntervalMs
        else -> config.intervalMs
    }

    fun stream(config: GeolocationConfig, vehicular: Boolean, turning: Boolean = false): LocationRequest {
        val interval = intervalFor(config, vehicular, turning)
        return LocationRequest.Builder(interval)
            // Navigation is GPS-dominant by definition: honouring a BALANCED preference
            // there would feed the position animation Wi-Fi centroids.
            .setPriority(
                if (config.navigationMode) Priority.PRIORITY_HIGH_ACCURACY else config.desiredAccuracy.toPriority(),
            )
            .setGranularity(Granularity.GRANULARITY_PERMISSION_LEVEL)
            // Was hardcoded `true`, which is a good default and a bad constant. Every
            // cadence flip in `LocationStreamController` tears the request down and
            // rebuilds it, and each rebuild re-arms the wait — so on hardware with a slow
            // GNSS lock the first fix after every vehicular/turn-burst transition is
            // delayed by however long the chip takes to reach its accuracy target. On a
            // budget chipset in the background that is the difference between a sampled
            // corner and a missing one. See `GeolocationConfig.waitForAccurateLocation`.
            .setWaitForAccurateLocation(config.waitForAccurateLocation)
            // EC-119 — 0 by contract. Validated in TrackerConfig, restated here.
            .setMinUpdateDistanceMeters(config.distanceFilterM)
            // Clamped to the tier: a floor slower than the interval it governs would
            // throttle the tier back to the value it was meant to accelerate past.
            .setMinUpdateIntervalMillis(minOf(fastestFloorFor(config), interval))
            // Asks the OS to batch. The callback MUST then iterate the whole batch —
            // see FusedLocationSource (SOURCE-AUDIT A4).
            .setMaxUpdateDelayMillis(batchWindowFor(config, interval))
            .setMaxUpdateAgeMillis(config.maxFixAgeMs)
            .build()
    }

    /**
     * The floor under [LocationRequest.Builder.setMinUpdateIntervalMillis]. The steady
     * 30 s default would cap navigation at one fix per 30 s — the profile's own floor
     * has to replace it, not merely compete with it.
     */
    fun fastestFloorFor(config: GeolocationConfig): Long =
        if (config.navigationMode) config.navigationFastestIntervalMs else config.fastestIntervalMs

    /**
     * Batching is a real battery win, but a window many times the interval defeats every
     * feedback loop built on the fixes.
     *
     * The turn burst is the concrete casualty: with a fixed 60 s window and a 12 s
     * vehicular interval, five fixes are held and delivered a minute late, so the burst
     * arms after the turn is over and the fast samples it asked for are never taken. The
     * window therefore scales with the tier rather than being absolute.
     *
     * At the 60 s default interval this changes nothing — `maxUpdateDelayMs` defaults to
     * 60 s, which is already one interval.
     *
     * Navigation disables batching outright: the fixes feed an animation, and a batch
     * window of even one interval delivers every frame late (SMOOTH-NAV-PLAN Phase 1).
     */
    fun batchWindowFor(config: GeolocationConfig, intervalMs: Long): Long {
        if (config.navigationMode) return 0
        // `0` means off, and until now it could not: the `coerceIn` below has a lower bound
        // of one interval, so every value a host wrote — `0` included — came back as *at
        // least* `intervalMs` of batching. `maxUpdateDelayMs = 0` is the documented way to
        // ask for unbatched delivery and it silently did the opposite, which is exactly the
        // knob to reach for on a device whose background fixes arrive a minute late.
        if (config.maxUpdateDelayMs <= 0L) return 0
        return config.maxUpdateDelayMs.coerceIn(intervalMs, intervalMs * MAX_BATCH_MULTIPLE)
    }

    fun oneShot(config: GeolocationConfig): CurrentLocationRequest =
        CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setGranularity(Granularity.GRANULARITY_PERMISSION_LEVEL)
            .setMaxUpdateAgeMillis(ONE_SHOT_MAX_AGE_MS)
            .setDurationMillis(config.oneShotTimeoutMs)
            .build()

    private fun DesiredAccuracy.toPriority(): Int = when (this) {
        DesiredAccuracy.HIGH -> Priority.PRIORITY_HIGH_ACCURACY
        DesiredAccuracy.BALANCED -> Priority.PRIORITY_BALANCED_POWER_ACCURACY
        DesiredAccuracy.LOW -> Priority.PRIORITY_LOW_POWER
    }

    private const val ONE_SHOT_MAX_AGE_MS = 5_000L

    /** Two intervals of batching: still a battery win, still a live feedback loop. */
    private const val MAX_BATCH_MULTIPLE = 2L
}

internal class FusedLocationSource(
    private val context: Context,
) : LocationSource {

    private val client by lazy { LocationServices.getFusedLocationProviderClient(context) }

    override fun isAvailable(): Boolean =
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS

    @SuppressLint("MissingPermission") // Callers gate on PermissionManager; EC-01 covers the denial path.
    override fun stream(
        config: GeolocationConfig,
        vehicular: Boolean,
        turning: Boolean,
    ): Flow<List<Location>> = callbackFlow {
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                // THE batch fix (A4). `lastLocation` collapses a batch of 4-6 fixes to
                // one — precisely the samples turn geometry needs. Sorted ascending so
                // the burst gate, which keys on fix time, sees them in order.
                val batch = result.locations.sortedBy { it.elapsedRealtimeNanos }
                if (batch.isNotEmpty()) trySend(batch)
            }
        }

        client.requestLocationUpdates(
            LocationRequests.stream(config, vehicular, turning),
            callback,
            context.mainLooper,
        )

        // Teardown only. The batched backlog is drained by [flush] *before* this runs —
        // it has to be, because by the time `awaitClose` fires the channel is already
        // closing and a `trySend` from a late callback would be dropped on the floor.
        awaitClose { client.removeLocationUpdates(callback) }
    }

    /**
     * `flushLocations()` delivers the backlog through the callback that is still
     * registered, and its `Task` completes only once that delivery has happened — which is
     * what makes awaiting it, rather than firing and forgetting, the whole point.
     *
     * Failures are swallowed: this runs on the teardown path of a cadence flip, and a
     * provider that cannot flush is not a reason to refuse to restart the stream. The cost
     * of a failure is the batch that would have been lost anyway.
     */
    override suspend fun flush() {
        runCatching { client.flushLocations().await() }
    }

    @SuppressLint("MissingPermission")
    override suspend fun oneShot(config: GeolocationConfig): Location? =
        runCatching { client.getCurrentLocation(LocationRequests.oneShot(config), null).await() }
            .getOrNull()
}
