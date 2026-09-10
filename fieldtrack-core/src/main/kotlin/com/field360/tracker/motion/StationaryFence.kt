package com.field360.tracker.motion

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.field360.tracker.domain.model.TrackerEvent
import com.field360.tracker.domain.model.TrackerGeofence
import com.field360.traker.geo.port.TrackLogger
import com.field360.tracker.sdkLog
import com.field360.tracker.sdkWarn
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

/** Persistent multi-geofence registry backed by Android's system geofencing service. */
internal class StationaryFence(
    private val context: Context,
    private val events: MutableSharedFlow<TrackerEvent>,
    private val logger: TrackLogger,
) : GeofenceRegistrar {
    internal val store: GeofenceStore = GeofenceStore(context)
    private val mutationLock = Mutex()

    private val client: GeofencingClient by lazy {
        LocationServices.getGeofencingClient(context)
    }

    private val pendingIntent: PendingIntent by lazy {
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, StationaryFenceReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }

    fun all(): List<TrackerGeofence> = store.registrations().map { it.geofence }

    fun get(id: String): TrackerGeofence? = store.registration(id)?.geofence

    @SuppressLint("MissingPermission") // Public caller receives a typed failure.
    suspend fun add(geofence: TrackerGeofence): AddResult = mutationLock.withLock {
        if (!store.canAdd(geofence.id, isStationaryWakeFence = false)) {
            return@withLock AddResult.LIMIT_REACHED
        }

        runCatching { client.addGeofences(requestFor(geofence), pendingIntent).await() }
            .onSuccess {
                store.put(RegisteredGeofence(geofence, isStationaryWakeFence = false))
                events.tryEmit(TrackerEvent.GeofenceAdded(geofence))
            }
            .onFailure(::reportRegistrationFailure)
            .fold(onSuccess = { AddResult.ADDED }, onFailure = { AddResult.FAILED })
    }

    suspend fun remove(id: String): Boolean? = mutationLock.withLock {
        val registration = store.registration(id) ?: return@withLock false
        runCatching { client.removeGeofences(listOf(id)).await() }
            .onSuccess {
                store.remove(id)
                events.tryEmit(TrackerEvent.GeofenceRemoved(registration.geofence.id))
            }
            .fold(onSuccess = { true }, onFailure = { null })
    }

    suspend fun removeAll(): Int? = mutationLock.withLock {
        val registrations = store.registrations()
        if (registrations.isEmpty()) return@withLock 0
        runCatching { client.removeGeofences(registrations.map { it.geofence.id }).await() }
            .onSuccess {
                store.clearRegistrations().forEach {
                    events.tryEmit(TrackerEvent.GeofenceRemoved(it.geofence.id))
                }
            }
            .fold(onSuccess = { registrations.size }, onFailure = { null })
    }

    /** Fire-and-observe path used by the motion controller, which is not suspend-based. */
    @SuppressLint("MissingPermission") // StartTrackingUseCase already gates permission.
    override fun register(geofence: TrackerGeofence): Boolean {
        return runCatching { client.addGeofences(requestFor(geofence), pendingIntent) }
            .onSuccess { task ->
                task.addOnSuccessListener {
                    store.put(RegisteredGeofence(geofence, isStationaryWakeFence = true))
                    events.tryEmit(TrackerEvent.GeofenceAdded(geofence))
                }.addOnFailureListener(::reportRegistrationFailure)
            }
            .onFailure(::reportRegistrationFailure)
            .isSuccess
    }

    override fun unregister(id: String): Boolean {
        val registration = store.registration(id) ?: return true
        return runCatching { client.removeGeofences(listOf(id)) }
            .onSuccess { task ->
                task.addOnSuccessListener {
                    store.remove(id)
                    events.tryEmit(TrackerEvent.GeofenceRemoved(registration.geofence.id))
                }
            }
            .isSuccess
    }

    private fun requestFor(geofence: TrackerGeofence): GeofencingRequest {
        val fence = Geofence.Builder()
            .setRequestId(geofence.id)
            .setCircularRegion(geofence.latitude, geofence.longitude, geofence.radiusM)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(
                Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT,
            )
            .build()
        return GeofencingRequest.Builder()
            .setInitialTrigger(0)
            .addGeofence(fence)
            .build()
    }

    private fun reportRegistrationFailure(error: Throwable) {
        sdkWarn { logger.w(TAG, "Geofence registration failed: ${error.message}") }
        events.tryEmit(TrackerEvent.Diagnostic("geofence_unavailable: ${error.message}"))
    }

    internal enum class AddResult { ADDED, LIMIT_REACHED, FAILED }

    private companion object {
        const val TAG = "StationaryFence"
        const val REQUEST_CODE = 8_302
    }
}
