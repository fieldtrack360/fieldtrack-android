package com.field360.tracker.data.db

import com.field360.tracker.RawPoint
import com.field360.tracker.domain.model.TrackSession
import com.field360.traker.geo.model.ActivityType
import com.field360.traker.geo.model.FilterState
import com.field360.traker.geo.model.FixDecision
import com.field360.traker.geo.model.GeoPoint
import com.field360.traker.geo.model.MotionState
import com.field360.traker.geo.model.MovementStatus
import com.field360.traker.geo.model.TrackPoint
import com.field360.traker.geo.model.Verdict
import com.field360.traker.geo.util.Uuids

/**
 * Entity ⇄ domain conversion, kept in one file so the storage shape and the engine's
 * types can evolve independently.
 *
 * Enums cross this boundary as strings and decode defensively: an unknown value
 * becomes `UNKNOWN` rather than throwing, so a database written by a newer SDK cannot
 * brick an older one (EC-117).
 */

internal fun TrackPointEntity.toDomain(): TrackPoint = TrackPoint(
    id = id,
    uuid = uuid,
    sessionId = sessionId,
    timeMs = timeMs,
    elapsedRealtimeNanos = elapsedRealtimeNanos,
    localDate = localDate,
    timezone = timezone,
    latitude = latitude,
    longitude = longitude,
    accuracy = accuracy,
    altitude = altitude,
    speedMps = speedMps,
    bearingDeg = bearingDeg,
    hasSpeed = hasSpeed,
    hasBearing = hasBearing,
    provider = provider,
    isMock = isMock,
    movementStatus = movementStatus.toMovementStatus(),
    detectedActivity = detectedActivity?.toActivityType(),
    activityStartTimeMs = activityStartTimeMs,
    odometerMeters = odometerMeters,
    batteryPct = batteryPct,
    isCharging = isCharging,
    extras = extras,
    integrityFlags = integrityFlags,
    providerFlags = providerFlags,
    acceptReason = acceptReason,
)

internal fun TrackPoint.toEntity(): TrackPointEntity = TrackPointEntity(
    id = id,
    uuid = uuid,
    sessionId = sessionId,
    timeMs = timeMs,
    elapsedRealtimeNanos = elapsedRealtimeNanos,
    localDate = localDate,
    timezone = timezone,
    latitude = latitude,
    longitude = longitude,
    accuracy = accuracy,
    altitude = altitude,
    speedMps = speedMps,
    bearingDeg = bearingDeg,
    hasSpeed = hasSpeed,
    hasBearing = hasBearing,
    provider = provider,
    isMock = isMock,
    movementStatus = movementStatus.name,
    detectedActivity = detectedActivity?.name,
    activityStartTimeMs = activityStartTimeMs,
    odometerMeters = odometerMeters,
    batteryPct = batteryPct,
    isCharging = isCharging,
    extras = extras,
    integrityFlags = integrityFlags,
    providerFlags = providerFlags,
    acceptReason = acceptReason,
)

internal fun TrackSessionEntity.toDomain(): TrackSession = TrackSession(
    id = id,
    startedAtMs = startedAtMs,
    startedAtElapsedNanos = startedAtElapsedNanos,
    endedAtMs = endedAtMs,
    tag = tag,
    configSnapshot = configSnapshot,
)

internal fun TrackSession.toEntity(): TrackSessionEntity = TrackSessionEntity(
    id = id,
    startedAtMs = startedAtMs,
    startedAtElapsedNanos = startedAtElapsedNanos,
    endedAtMs = endedAtMs,
    tag = tag,
    configSnapshot = configSnapshot,
)

internal fun FilterStateEntity.toDomain(): FilterState = FilterState(
    lat = lat,
    lng = lng,
    variance = variance,
    elapsedNanos = elapsedNanos,
    lastHwVehicularNanos = lastHwVehicularNanos,
    consecutiveRejectCount = consecutiveRejectCount,
    lastSeenElapsedNanos = lastSeenElapsedNanos,
    hardRejectRun = hardRejectRun,
    origin = originLat?.let { la -> originLng?.let { lo -> GeoPoint(la, lo) } },
    departCount = departCount,
    prevNetMeters = prevNetMeters,
    movingMode = movingMode,
    settleCount = settleCount,
    recoveryPending = recoveryLat?.let { la -> recoveryLng?.let { lo -> GeoPoint(la, lo) } },
    lastFixElapsedNanos = lastFixElapsedNanos,
    motionState = motionState.toMotionState(),
    stopPendingSinceNanos = stopPendingSinceNanos,
    lastCapturedBearingDeg = lastCapturedBearingDeg,
    velocityNorthMps = velocityNorthMps,
    velocityEastMps = velocityEastMps,
    covPosVel = covPosVel,
    varianceVel = varianceVel,
)

internal fun FilterState.toEntity(): FilterStateEntity = FilterStateEntity(
    lat = lat,
    lng = lng,
    variance = variance,
    elapsedNanos = elapsedNanos,
    lastHwVehicularNanos = lastHwVehicularNanos,
    consecutiveRejectCount = consecutiveRejectCount,
    lastSeenElapsedNanos = lastSeenElapsedNanos,
    hardRejectRun = hardRejectRun,
    originLat = origin?.latitude,
    originLng = origin?.longitude,
    departCount = departCount,
    prevNetMeters = prevNetMeters,
    movingMode = movingMode,
    settleCount = settleCount,
    recoveryLat = recoveryPending?.latitude,
    recoveryLng = recoveryPending?.longitude,
    lastFixElapsedNanos = lastFixElapsedNanos,
    motionState = motionState.name,
    stopPendingSinceNanos = stopPendingSinceNanos,
    lastCapturedBearingDeg = lastCapturedBearingDeg,
    velocityNorthMps = velocityNorthMps,
    velocityEastMps = velocityEastMps,
    covPosVel = covPosVel,
    varianceVel = varianceVel,
)

internal fun FixDecision.toEntity(sessionId: String): FixDecisionEntity = FixDecisionEntity(
    sessionId = sessionId,
    timeMs = fix.timeMs,
    elapsedRealtimeNanos = fix.elapsedRealtimeNanos,
    latitude = fix.latitude,
    longitude = fix.longitude,
    accuracy = fix.accuracy,
    bearingDeg = fix.bearingDeg,
    hasSpeed = fix.hasSpeed,
    hasBearing = fix.hasBearing,
    verdict = when (verdict) {
        is Verdict.Accept -> "ACCEPT"
        is Verdict.Skip -> "SKIP"
        is Verdict.Reject -> "REJECT"
    },
    reason = reason,
    filterLat = filterLat,
    filterLng = filterLng,
    sigma = sigma,
    threshold = threshold,
    distanceMovedM = distanceMovedM,
    effectiveSpeedMps = effectiveSpeedMps,
    motionState = motionState.name,
)

/**
 * The point-form row for one judged fix, accepted or not (v6).
 *
 * Built from four sources because no single one has the whole picture: the [point] when
 * the pipeline produced one, the [decision] for the verdict and the speed it settled on,
 * the [context] for everything the engine does not know (calendar, activity, battery),
 * and the fix itself for the hardware readings.
 *
 * The accepted row is deliberately the *stored* point rather than a re-derivation of it:
 * a diagnostic layer that computes its own version of the truth is a second
 * implementation to keep in step, and the first time they disagree the table is worthless.
 */
internal fun rawPointEntity(
    decision: FixDecision,
    sessionId: String,
    localDate: String,
    timezone: String,
    detectedActivity: String?,
    activityStartTimeMs: Long,
    odometerMeters: Double,
    batteryPct: Int?,
    isCharging: Boolean?,
    extras: String?,
    integrityFlags: Int,
    providerFlags: Int,
    point: TrackPoint?,
): RawPointEntity {
    val fix = decision.fix
    return RawPointEntity(
        uuid = point?.uuid ?: Uuids.forFix(sessionId, fix.elapsedRealtimeNanos),
        sessionId = sessionId,
        timeMs = fix.timeMs,
        elapsedRealtimeNanos = fix.elapsedRealtimeNanos,
        localDate = localDate,
        timezone = timezone,
        latitude = fix.latitude,
        longitude = fix.longitude,
        accuracy = fix.accuracy,
        altitude = fix.altitude,
        // The pipeline's effective speed, not the raw Doppler reading: it is what every
        // gate actually argued with, and `raw_fix` already holds the hardware value.
        speedMps = point?.speedMps ?: decision.effectiveSpeedMps,
        bearingDeg = fix.bearingDeg,
        hasSpeed = fix.hasSpeed,
        hasBearing = fix.hasBearing,
        provider = fix.provider,
        isMock = fix.isMock,
        movementStatus = (point?.movementStatus ?: MovementStatus.STEADY).name,
        detectedActivity = detectedActivity,
        activityStartTimeMs = activityStartTimeMs,
        // On a reject this is the running total carried in, unchanged. A discarded fix
        // moves nobody, and inventing distance for one is how an odometer starts lying.
        odometerMeters = point?.odometerMeters ?: odometerMeters,
        batteryPct = batteryPct,
        isCharging = isCharging,
        extras = extras,
        integrityFlags = integrityFlags,
        providerFlags = providerFlags,
        verdict = decision.verdict.storedName(),
        reason = decision.reason,
    )
}

internal fun RawPointEntity.toDomain(): RawPoint = RawPoint(
    uuid = uuid,
    sessionId = sessionId,
    timeMs = timeMs,
    elapsedRealtimeNanos = elapsedRealtimeNanos,
    localDate = localDate,
    timezone = timezone,
    latitude = latitude,
    longitude = longitude,
    accuracy = accuracy,
    altitude = altitude,
    speedMps = speedMps,
    bearingDeg = bearingDeg,
    hasSpeed = hasSpeed,
    hasBearing = hasBearing,
    provider = provider,
    isMock = isMock,
    movementStatus = movementStatus.toMovementStatus(),
    detectedActivity = detectedActivity?.toActivityType(),
    activityStartTimeMs = activityStartTimeMs,
    odometerMeters = odometerMeters,
    batteryPct = batteryPct,
    isCharging = isCharging,
    extras = extras,
    integrityFlags = integrityFlags,
    providerFlags = providerFlags,
    verdict = verdict,
    reason = reason,
)

private fun Verdict.storedName(): String = when (this) {
    is Verdict.Accept -> "ACCEPT"
    is Verdict.Skip -> "SKIP"
    is Verdict.Reject -> "REJECT"
}

private fun String.toMovementStatus(): MovementStatus =
    runCatching { MovementStatus.valueOf(this) }.getOrDefault(MovementStatus.STEADY)

private fun String.toActivityType(): ActivityType =
    runCatching { ActivityType.valueOf(this) }.getOrDefault(ActivityType.UNKNOWN)

private fun String.toMotionState(): MotionState =
    runCatching { MotionState.valueOf(this) }.getOrDefault(MotionState.STOPPED)
