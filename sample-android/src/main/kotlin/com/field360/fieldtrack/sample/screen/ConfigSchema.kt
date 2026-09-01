package com.field360.fieldtrack.sample.screen

import com.field360.tracker.AccuracyProfile
import com.field360.tracker.DesiredAccuracy
import com.field360.tracker.LocationProviderType
import com.field360.tracker.TrackerConfig
import com.field360.tracker.TrackingMode
import com.field360.tracker.integrity.IntegrityPolicy
import com.field360.traker.geo.model.MockPolicy

/**
 * Every option in [TrackerConfig], described rather than drawn.
 *
 * The Home screen is a config editor for ~80 fields. Written as composables one per field
 * that would be ~80 near-identical blocks, and the failure mode of that shape is not
 * length — it is drift: the twelfth `Long` row gets a keyboard the eleventh does not, one
 * of them writes to the wrong `copy()` target, and nothing on screen says so. So the
 * fields are *data* here, and [ConfigConsole] draws three widgets against the whole list.
 *
 * Each field carries its own read and its own write against the whole config, which is
 * what lets a nested value ([AccuracyConfig] under [GeolocationConfig]) sit in a flat list
 * beside a top-level one with no special case anywhere in the UI.
 *
 * **This list is the sample's coverage of the configuration surface.** A field added to
 * the SDK and not added here is a field the sample never sets — the same argument
 * `SampleApplication.buildTrackerConfig` makes for spelling out every setter.
 */
sealed interface ConfigField {
    val key: String
    val label: String

    /** One short line under the row. Empty for a field whose name is the whole story. */
    val hint: String
}

/** A switch. The only widget with no invalid state. */
data class BoolField(
    override val key: String,
    override val label: String,
    override val hint: String = "",
    val get: (TrackerConfig) -> Boolean,
    val set: (TrackerConfig, Boolean) -> TrackerConfig,
) : ConfigField

/**
 * A closed set of values — every enum in the config surface.
 *
 * Carried as `String` rather than as a generic type parameter: the alternative is a
 * variance dance in the UI for no gain, since a dropdown renders names either way. The
 * options come from `enumValues`, so a constant added to an SDK enum appears here without
 * this file being edited.
 */
data class ChoiceField(
    override val key: String,
    override val label: String,
    override val hint: String = "",
    val options: List<String>,
    val get: (TrackerConfig) -> String,
    val set: (TrackerConfig, String) -> TrackerConfig,
) : ConfigField

/**
 * Anything typed: numbers, strings, and the nullable variants of both.
 *
 * [parse] returns `null` for a string that is not a value of this field's type, and that
 * is the whole error model. The editor keeps the typed text either way — a field being
 * edited is allowed to be momentarily unparseable, because `""` and `"-"` are what every
 * number looks like halfway through being typed. Only a parseable string reaches the
 * config.
 */
data class TextField(
    override val key: String,
    override val label: String,
    override val hint: String = "",
    val keyboard: ConfigKeyboard,
    val get: (TrackerConfig) -> String,
    val parse: (TrackerConfig, String) -> TrackerConfig?,
) : ConfigField

/** Which soft keyboard a [TextField] asks for. Purely an input affordance. */
enum class ConfigKeyboard { NUMBER, DECIMAL, TEXT }

/** One collapsible section: an SDK sub-config, or the handful of top-level fields. */
data class ConfigGroup(val title: String, val fields: List<ConfigField>)

// ── builders ────────────────────────────────────────────────────────────────
//
// Thin on purpose. Every one of them exists so that a field below is one line and reads
// as its own name plus its own copy() target — the two things worth checking by eye.

private fun bool(
    key: String,
    hint: String = "",
    get: (TrackerConfig) -> Boolean,
    set: (TrackerConfig, Boolean) -> TrackerConfig,
) = BoolField(key = key, label = key, hint = hint, get = get, set = set)

private inline fun <reified E : Enum<E>> choice(
    key: String,
    hint: String = "",
    noinline get: (TrackerConfig) -> E,
    crossinline set: (TrackerConfig, E) -> TrackerConfig,
) = ChoiceField(
    key = key,
    label = key,
    hint = hint,
    options = enumValues<E>().map { it.name },
    get = { config -> get(config).name },
    set = { config, value -> set(config, enumValueOf<E>(value)) },
)

private fun longField(
    key: String,
    hint: String = "",
    get: (TrackerConfig) -> Long,
    set: (TrackerConfig, Long) -> TrackerConfig,
) = TextField(
    key = key,
    label = key,
    hint = hint,
    keyboard = ConfigKeyboard.NUMBER,
    get = { config -> get(config).toString() },
    parse = { config, raw -> raw.trim().toLongOrNull()?.let { set(config, it) } },
)

private fun intField(
    key: String,
    hint: String = "",
    get: (TrackerConfig) -> Int,
    set: (TrackerConfig, Int) -> TrackerConfig,
) = TextField(
    key = key,
    label = key,
    hint = hint,
    keyboard = ConfigKeyboard.NUMBER,
    get = { config -> get(config).toString() },
    parse = { config, raw -> raw.trim().toIntOrNull()?.let { set(config, it) } },
)

private fun floatField(
    key: String,
    hint: String = "",
    get: (TrackerConfig) -> Float,
    set: (TrackerConfig, Float) -> TrackerConfig,
) = TextField(
    key = key,
    label = key,
    hint = hint,
    keyboard = ConfigKeyboard.DECIMAL,
    get = { config -> get(config).toString() },
    parse = { config, raw -> raw.trim().toFloatOrNull()?.let { set(config, it) } },
)

/**
 * A `Float?`, where blank means `null` rather than "not yet typed".
 *
 * The distinction matters for exactly the fields that use this: `maxAccuracyMeters` null
 * means "take the profile's ceiling", and there is no other way to express it in a text
 * box.
 */
private fun nullableFloatField(
    key: String,
    hint: String = "",
    get: (TrackerConfig) -> Float?,
    set: (TrackerConfig, Float?) -> TrackerConfig,
) = TextField(
    key = key,
    label = key,
    hint = hint,
    keyboard = ConfigKeyboard.DECIMAL,
    get = { config -> get(config)?.toString().orEmpty() },
    parse = { config, raw ->
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) set(config, null) else trimmed.toFloatOrNull()?.let { set(config, it) }
    },
)

private fun stringField(
    key: String,
    hint: String = "",
    get: (TrackerConfig) -> String,
    set: (TrackerConfig, String) -> TrackerConfig,
) = TextField(
    key = key,
    label = key,
    hint = hint,
    keyboard = ConfigKeyboard.TEXT,
    get = get,
    parse = { config, raw -> set(config, raw) },
)

/** Blank means `null`. Never invalid — every string is a value of `String?`. */
private fun nullableStringField(
    key: String,
    hint: String = "",
    get: (TrackerConfig) -> String?,
    set: (TrackerConfig, String?) -> TrackerConfig,
) = TextField(
    key = key,
    label = key,
    hint = hint,
    keyboard = ConfigKeyboard.TEXT,
    get = { config -> get(config).orEmpty() },
    parse = { config, raw -> set(config, raw.trim().ifBlank { null }) },
)

// ── the surface ─────────────────────────────────────────────────────────────

/**
 * Grouped the way [TrackerConfig] is, in the order a tester reaches for them.
 *
 * `geolocation` first because cadence is what most runs are changing; `security` last
 * because every policy in it is inert in a debuggable build, which is the only build this
 * sample ships as.
 */
@Suppress("LongMethod") // A schema. Its length is the size of the SDK's config surface.
fun configGroups(): List<ConfigGroup> = listOf(
    ConfigGroup(
        title = "geolocation",
        fields = listOf(
            choice<TrackingMode>(
                "trackingMode",
                "MOTION_ONLY captures only while the device is moving",
                { it.geolocation.trackingMode },
                { c, v -> c.copy(geolocation = c.geolocation.copy(trackingMode = v)) },
            ),
            choice<LocationProviderType>(
                "providerType",
                "GPS_ONLY on a device with no Play Services",
                { it.geolocation.providerType },
                { c, v -> c.copy(geolocation = c.geolocation.copy(providerType = v)) },
            ),
            choice<DesiredAccuracy>(
                "desiredAccuracy",
                "",
                { it.geolocation.desiredAccuracy },
                { c, v -> c.copy(geolocation = c.geolocation.copy(desiredAccuracy = v)) },
            ),
            choice<MockPolicy>(
                "mockLocationPolicy",
                "FLAG stores mock fixes marked; REJECT is the production choice",
                { it.geolocation.mockLocationPolicy },
                { c, v -> c.copy(geolocation = c.geolocation.copy(mockLocationPolicy = v)) },
            ),
            floatField(
                "distanceFilterM",
                "validate() rejects anything above 0 — try it",
                { it.geolocation.distanceFilterM },
                { c, v -> c.copy(geolocation = c.geolocation.copy(distanceFilterM = v)) },
            ),
            longField(
                "intervalMs",
                "must stay >= fastestIntervalMs",
                { it.geolocation.intervalMs },
                { c, v -> c.copy(geolocation = c.geolocation.copy(intervalMs = v)) },
            ),
            longField(
                "fastestIntervalMs",
                "",
                { it.geolocation.fastestIntervalMs },
                { c, v -> c.copy(geolocation = c.geolocation.copy(fastestIntervalMs = v)) },
            ),
            longField(
                "maxUpdateDelayMs",
                "",
                { it.geolocation.maxUpdateDelayMs },
                { c, v -> c.copy(geolocation = c.geolocation.copy(maxUpdateDelayMs = v)) },
            ),
            longField(
                "maxFixAgeMs",
                "",
                { it.geolocation.maxFixAgeMs },
                { c, v -> c.copy(geolocation = c.geolocation.copy(maxFixAgeMs = v)) },
            ),
            longField(
                "deliveryStalenessMs",
                "",
                { it.geolocation.deliveryStalenessMs },
                { c, v -> c.copy(geolocation = c.geolocation.copy(deliveryStalenessMs = v)) },
            ),
            bool(
                "adaptiveCadence",
                "",
                { it.geolocation.adaptiveCadence },
                { c, v -> c.copy(geolocation = c.geolocation.copy(adaptiveCadence = v)) },
            ),
            longField(
                "vehicularIntervalMs",
                "the tier used once fixes report vehicle speed",
                { it.geolocation.vehicularIntervalMs },
                { c, v -> c.copy(geolocation = c.geolocation.copy(vehicularIntervalMs = v)) },
            ),
            bool(
                "turnBurst",
                "",
                { it.geolocation.turnBurst },
                { c, v -> c.copy(geolocation = c.geolocation.copy(turnBurst = v)) },
            ),
            longField(
                "turnBurstIntervalMs",
                "must be faster than the tier it accelerates",
                { it.geolocation.turnBurstIntervalMs },
                { c, v -> c.copy(geolocation = c.geolocation.copy(turnBurstIntervalMs = v)) },
            ),
            longField(
                "oneShotTimeoutMs",
                "",
                { it.geolocation.oneShotTimeoutMs },
                { c, v -> c.copy(geolocation = c.geolocation.copy(oneShotTimeoutMs = v)) },
            ),
            bool(
                "navigationMode",
                "1 Hz. Requires service.foregroundService, and refuses PASSIVE",
                { it.geolocation.navigationMode },
                { c, v -> c.copy(geolocation = c.geolocation.copy(navigationMode = v)) },
            ),
            longField(
                "navigationIntervalMs",
                "",
                { it.geolocation.navigationIntervalMs },
                { c, v -> c.copy(geolocation = c.geolocation.copy(navigationIntervalMs = v)) },
            ),
            longField(
                "navigationFastestIntervalMs",
                "",
                { it.geolocation.navigationFastestIntervalMs },
                { c, v -> c.copy(geolocation = c.geolocation.copy(navigationFastestIntervalMs = v)) },
            ),
        ),
    ),
    ConfigGroup(
        title = "accuracy",
        fields = listOf(
            choice<AccuracyProfile>(
                "profile",
                "the moving-fix error ceiling. CUSTOM requires maxAccuracyMeters",
                { it.geolocation.accuracy.profile },
                { c, v ->
                    c.copy(
                        geolocation = c.geolocation.copy(
                            accuracy = c.geolocation.accuracy.copy(profile = v),
                        ),
                    )
                },
            ),
            nullableFloatField(
                "maxAccuracyMeters",
                "blank = take the profile's ceiling. Set = CUSTOM only, 5–500 m",
                { it.geolocation.accuracy.maxAccuracyMeters },
                { c, v ->
                    c.copy(
                        geolocation = c.geolocation.copy(
                            accuracy = c.geolocation.accuracy.copy(maxAccuracyMeters = v),
                        ),
                    )
                },
            ),
            nullableFloatField(
                "recoveryTrustMeters",
                "the bar the first fix after a blackout must clear",
                { it.geolocation.accuracy.recoveryTrustMeters },
                { c, v ->
                    c.copy(
                        geolocation = c.geolocation.copy(
                            accuracy = c.geolocation.accuracy.copy(recoveryTrustMeters = v),
                        ),
                    )
                },
            ),
        ),
    ),
    ConfigGroup(
        title = "motion",
        fields = listOf(
            bool(
                "activityRecognition",
                "",
                { it.motion.activityRecognition },
                { c, v -> c.copy(motion = c.motion.copy(activityRecognition = v)) },
            ),
            longField(
                "activityRecognitionIntervalMs",
                "",
                { it.motion.activityRecognitionIntervalMs },
                { c, v -> c.copy(motion = c.motion.copy(activityRecognitionIntervalMs = v)) },
            ),
            intField(
                "activityConfidenceMin",
                "",
                { it.motion.activityConfidenceMin },
                { c, v -> c.copy(motion = c.motion.copy(activityConfidenceMin = v)) },
            ),
            intField(
                "snapshotConfidenceMin",
                "",
                { it.motion.snapshotConfidenceMin },
                { c, v -> c.copy(motion = c.motion.copy(snapshotConfidenceMin = v)) },
            ),
            bool(
                "disableStopDetection",
                "declared but unimplemented — nothing in the SDK reads it",
                { it.motion.disableStopDetection },
                { c, v -> c.copy(motion = c.motion.copy(disableStopDetection = v)) },
            ),
            bool(
                "stopOnStationary",
                "declared but unimplemented — see suppressWhileStationary",
                { it.motion.stopOnStationary },
                { c, v -> c.copy(motion = c.motion.copy(stopOnStationary = v)) },
            ),
            bool(
                "suppressWhileStationary",
                "on = drop drift points the accelerometer says never moved",
                { it.motion.suppressWhileStationary },
                { c, v -> c.copy(motion = c.motion.copy(suppressWhileStationary = v)) },
            ),
            intField(
                "stillnessEscapeMin",
                "how long suppression may last before one fix is let through",
                { it.motion.stillnessEscapeMin },
                { c, v -> c.copy(motion = c.motion.copy(stillnessEscapeMin = v)) },
            ),
            intField(
                "stopTimeoutMin",
                "",
                { it.motion.stopTimeoutMin },
                { c, v -> c.copy(motion = c.motion.copy(stopTimeoutMin = v)) },
            ),
            floatField(
                "stationaryRadiusM",
                "must be above 0",
                { it.motion.stationaryRadiusM },
                { c, v -> c.copy(motion = c.motion.copy(stationaryRadiusM = v)) },
            ),
            stringField(
                "stationaryGeofenceId",
                "must not be blank",
                { it.motion.stationaryGeofenceId },
                { c, v -> c.copy(motion = c.motion.copy(stationaryGeofenceId = v)) },
            ),
            stringField(
                "stationaryGeofenceOnEnterEvent",
                "",
                { it.motion.stationaryGeofenceOnEnterEvent },
                { c, v -> c.copy(motion = c.motion.copy(stationaryGeofenceOnEnterEvent = v)) },
            ),
            stringField(
                "stationaryGeofenceOnExitEvent",
                "",
                { it.motion.stationaryGeofenceOnExitEvent },
                { c, v -> c.copy(motion = c.motion.copy(stationaryGeofenceOnExitEvent = v)) },
            ),
            longField(
                "motionTriggerDelayMs",
                "",
                { it.motion.motionTriggerDelayMs },
                { c, v -> c.copy(motion = c.motion.copy(motionTriggerDelayMs = v)) },
            ),
            intField(
                "heartbeatIntervalSec",
                "must stay >= 5x the sampling interval",
                { it.motion.heartbeatIntervalSec },
                { c, v -> c.copy(motion = c.motion.copy(heartbeatIntervalSec = v)) },
            ),
            bool(
                "persistHeartbeat",
                "store the stationary heartbeat as points",
                { it.motion.persistHeartbeat },
                { c, v -> c.copy(motion = c.motion.copy(persistHeartbeat = v)) },
            ),
            intField(
                "bearingChangeCaptureDeg",
                "capture once the heading has turned this far",
                { it.motion.bearingChangeCaptureDeg },
                { c, v -> c.copy(motion = c.motion.copy(bearingChangeCaptureDeg = v)) },
            ),
            bool(
                "cornerAnchorCapture",
                "",
                { it.motion.cornerAnchorCapture },
                { c, v -> c.copy(motion = c.motion.copy(cornerAnchorCapture = v)) },
            ),
        ),
    ),
    ConfigGroup(
        title = "sensors",
        fields = listOf(
            bool(
                "useSignificantMotion",
                "",
                { it.sensors.useSignificantMotion },
                { c, v -> c.copy(sensors = c.sensors.copy(useSignificantMotion = v)) },
            ),
            bool(
                "useStepCorroboration",
                "",
                { it.sensors.useStepCorroboration },
                { c, v -> c.copy(sensors = c.sensors.copy(useStepCorroboration = v)) },
            ),
            bool(
                "useAccelerometerVeto",
                "",
                { it.sensors.useAccelerometerVeto },
                { c, v -> c.copy(sensors = c.sensors.copy(useAccelerometerVeto = v)) },
            ),
            bool(
                "useBarometer",
                "missing on most mid-range hardware",
                { it.sensors.useBarometer },
                { c, v -> c.copy(sensors = c.sensors.copy(useBarometer = v)) },
            ),
            bool(
                "useGyroTurnPrediction",
                "no-op with turnBurst off or no gyroscope",
                { it.sensors.useGyroTurnPrediction },
                { c, v -> c.copy(sensors = c.sensors.copy(useGyroTurnPrediction = v)) },
            ),
            longField(
                "stepBatchLatencyMs",
                "",
                { it.sensors.stepBatchLatencyMs },
                { c, v -> c.copy(sensors = c.sensors.copy(stepBatchLatencyMs = v)) },
            ),
        ),
    ),
    ConfigGroup(
        title = "service",
        fields = listOf(
            bool(
                "foregroundService",
                "",
                { it.service.foregroundService },
                { c, v -> c.copy(service = c.service.copy(foregroundService = v)) },
            ),
            bool(
                "stopOnTerminate",
                "on = a swipe-away silently ends tracking",
                { it.service.stopOnTerminate },
                { c, v -> c.copy(service = c.service.copy(stopOnTerminate = v)) },
            ),
            bool(
                "startOnBoot",
                "",
                { it.service.startOnBoot },
                { c, v -> c.copy(service = c.service.copy(startOnBoot = v)) },
            ),
            longField(
                "healthLoopMs",
                "",
                { it.service.healthLoopMs },
                { c, v -> c.copy(service = c.service.copy(healthLoopMs = v)) },
            ),
            longField(
                "watchdogIntervalMs",
                "also the refresh clock for the sync notification line",
                { it.service.watchdogIntervalMs },
                { c, v -> c.copy(service = c.service.copy(watchdogIntervalMs = v)) },
            ),
            longField(
                "watchdogThrottleMs",
                "",
                { it.service.watchdogThrottleMs },
                { c, v -> c.copy(service = c.service.copy(watchdogThrottleMs = v)) },
            ),
            intField(
                "backstopIntervalMin",
                "WorkManager's floor is 15",
                { it.service.backstopIntervalMin },
                { c, v -> c.copy(service = c.service.copy(backstopIntervalMin = v)) },
            ),
            intField(
                "deadTrackerMovingMin",
                "",
                { it.service.deadTrackerMovingMin },
                { c, v -> c.copy(service = c.service.copy(deadTrackerMovingMin = v)) },
            ),
            intField(
                "deadTrackerStationaryMin",
                "",
                { it.service.deadTrackerStationaryMin },
                { c, v -> c.copy(service = c.service.copy(deadTrackerStationaryMin = v)) },
            ),
            longField(
                "wakeLockMs",
                "",
                { it.service.wakeLockMs },
                { c, v -> c.copy(service = c.service.copy(wakeLockMs = v)) },
            ),
            stringField(
                "notificationTitle",
                "",
                { it.service.notificationTitle },
                { c, v -> c.copy(service = c.service.copy(notificationTitle = v)) },
            ),
            stringField(
                "notificationText",
                "",
                { it.service.notificationText },
                { c, v -> c.copy(service = c.service.copy(notificationText = v)) },
            ),
            stringField(
                "notificationChannelId",
                "changing this creates a second channel in system settings",
                { it.service.notificationChannelId },
                { c, v -> c.copy(service = c.service.copy(notificationChannelId = v)) },
            ),
            stringField(
                "notificationChannelName",
                "",
                { it.service.notificationChannelName },
                { c, v -> c.copy(service = c.service.copy(notificationChannelName = v)) },
            ),
            nullableStringField(
                "notificationSmallIconResName",
                "drawable NAME, not an id. Blank = the SDK's fallback",
                { it.service.notificationSmallIconResName },
                { c, v -> c.copy(service = c.service.copy(notificationSmallIconResName = v)) },
            ),
            bool(
                "showSyncStatusInNotification",
                "upload queue in the subtitle and body. Title untouched. Diagnostic",
                { it.service.showSyncStatusInNotification },
                { c, v -> c.copy(service = c.service.copy(showSyncStatusInNotification = v)) },
            ),
            nullableStringField(
                "syncNotificationSubText",
                "subtitle beside the title. Blank = no subtitle",
                { it.service.syncNotificationSubText },
                { c, v -> c.copy(service = c.service.copy(syncNotificationSubText = v)) },
            ),
            stringField(
                "syncNotificationText",
                "{pending} and {age} are substituted at post time",
                { it.service.syncNotificationText },
                { c, v -> c.copy(service = c.service.copy(syncNotificationText = v)) },
            ),
        ),
    ),
    ConfigGroup(
        title = "persistence",
        fields = listOf(
            intField(
                "maxDaysToPersist",
                "",
                { it.persistence.maxDaysToPersist },
                { c, v -> c.copy(persistence = c.persistence.copy(maxDaysToPersist = v)) },
            ),
            intField(
                "maxRecords",
                "0 = no row cap; the TTL above is then the only limit",
                { it.persistence.maxRecords },
                { c, v -> c.copy(persistence = c.persistence.copy(maxRecords = v)) },
            ),
            bool(
                "persistRawFixes",
                "debug overlay layer 1. Off = that tab reads empty",
                { it.persistence.persistRawFixes },
                { c, v -> c.copy(persistence = c.persistence.copy(persistRawFixes = v)) },
            ),
            intField(
                "rawRingCapacity",
                "",
                { it.persistence.rawRingCapacity },
                { c, v -> c.copy(persistence = c.persistence.copy(rawRingCapacity = v)) },
            ),
            bool(
                "persistRawPoints",
                "debug overlay layer 3",
                { it.persistence.persistRawPoints },
                { c, v -> c.copy(persistence = c.persistence.copy(persistRawPoints = v)) },
            ),
            intField(
                "rawPointRingCapacity",
                "",
                { it.persistence.rawPointRingCapacity },
                { c, v -> c.copy(persistence = c.persistence.copy(rawPointRingCapacity = v)) },
            ),
            bool(
                "persistDecisions",
                "what the Decisions tab reads",
                { it.persistence.persistDecisions },
                { c, v -> c.copy(persistence = c.persistence.copy(persistDecisions = v)) },
            ),
            intField(
                "decisionRetentionDays",
                "",
                { it.persistence.decisionRetentionDays },
                { c, v -> c.copy(persistence = c.persistence.copy(decisionRetentionDays = v)) },
            ),
            intField(
                "decisionMaxRows",
                "",
                { it.persistence.decisionMaxRows },
                { c, v -> c.copy(persistence = c.persistence.copy(decisionMaxRows = v)) },
            ),
        ),
    ),
    ConfigGroup(
        title = "security",
        fields = listOf(
            bool(
                "enabled",
                "every policy below is waived in a debuggable build — this one included",
                { it.security.enabled },
                { c, v -> c.copy(security = c.security.copy(enabled = v)) },
            ),
            choice<IntegrityPolicy>(
                "accessibility",
                "",
                { it.security.accessibility },
                { c, v -> c.copy(security = c.security.copy(accessibility = v)) },
            ),
            choice<IntegrityPolicy>(
                "developerMode",
                "",
                { it.security.developerMode },
                { c, v -> c.copy(security = c.security.copy(developerMode = v)) },
            ),
            choice<IntegrityPolicy>(
                "hooking",
                "",
                { it.security.hooking },
                { c, v -> c.copy(security = c.security.copy(hooking = v)) },
            ),
            choice<IntegrityPolicy>(
                "clock",
                "",
                { it.security.clock },
                { c, v -> c.copy(security = c.security.copy(clock = v)) },
            ),
            choice<IntegrityPolicy>(
                "mockLocation",
                "the stricter of this and geolocation.mockLocationPolicy wins",
                { it.security.mockLocation },
                { c, v -> c.copy(security = c.security.copy(mockLocation = v)) },
            ),
            stringField(
                "accessibilityAllowlist",
                "comma-separated package names the host vouches for",
                { it.security.accessibilityAllowlist.joinToString(", ") },
                { c, v ->
                    c.copy(
                        security = c.security.copy(
                            accessibilityAllowlist = v.split(',')
                                .map(String::trim)
                                .filter(String::isNotEmpty)
                                .toSet(),
                        ),
                    )
                },
            ),
            longField(
                "maxClockSkewMs",
                "",
                { it.security.maxClockSkewMs },
                { c, v -> c.copy(security = c.security.copy(maxClockSkewMs = v)) },
            ),
            longField(
                "recheckIntervalMs",
                "0 disables the periodic re-check; the floor is 60000 otherwise",
                { it.security.recheckIntervalMs },
                { c, v -> c.copy(security = c.security.copy(recheckIntervalMs = v)) },
            ),
        ),
    ),
    ConfigGroup(
        title = "top level",
        fields = listOf(
            nullableStringField(
                "baseUrl",
                "read only by fieldtrack-sync. Must be absolute, with scheme and host",
                { it.baseUrl },
                { c, v -> c.copy(baseUrl = v) },
            ),
            nullableStringField(
                "license",
                "blank is valid here — debuggable installs are waived",
                { it.license },
                { c, v -> c.copy(license = v) },
            ),
            bool(
                "reset",
                "off = the persisted config wins and edits here do nothing",
                { it.reset },
                { c, v -> c.copy(reset = v) },
            ),
        ),
    ),
)
