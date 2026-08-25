package com.field360.tracker.domain.model

import com.field360.traker.geo.model.ActivityType
import com.field360.traker.geo.model.FixDecision
import com.field360.traker.geo.model.MotionState
import com.field360.traker.geo.model.ProviderSnapshot
import com.field360.traker.geo.model.TrackPoint
import com.field360.tracker.integrity.IntegrityReport
import kotlinx.serialization.Serializable

/**
 * A tracking run, first-class rather than implicit.
 *
 * Every point belongs to a session, and the session carries the config snapshot that
 * was in effect. That is what lets a track recorded six weeks ago be interpreted
 * correctly after the config changed, and it is what makes fixture replay honest —
 * a fixture replays under the config it was recorded with (SDK-COMPARISON §5).
 */
public data class TrackSession(
    val id: String,
    val startedAtMs: Long,
    val startedAtElapsedNanos: Long,
    val endedAtMs: Long? = null,
    val tag: String? = null,
    val configSnapshot: String? = null,
) {
    val isOpen: Boolean get() = endedAtMs == null
}

/** Result type for every fallible entry point. The SDK returns errors; it never throws. */
public sealed interface TrackerResult<out T> {
    public data class Ok<T>(val value: T) : TrackerResult<T>
    public data class Error(val code: ErrorCode, val message: String) : TrackerResult<Nothing>
}

public enum class ErrorCode {
    NOT_READY,
    PERMISSION_DENIED,
    BACKGROUND_PERMISSION_MISSING,
    COARSE_ONLY,
    LOCATION_DISABLED,
    PLAY_SERVICES_UNAVAILABLE,
    FGS_START_REFUSED,
    NOTIFICATION_HIDDEN,
    FIX_TIMEOUT,
    STORAGE_FULL,
    STORAGE_RESET,
    TRACKER_DEAD,
    INVALID_CONFIG,
    LICENSE_MISSING,
    LICENSE_INVALID,
    LICENSE_BUNDLE_MISMATCH,

    /**
     * The backend says this licence was revoked after it was issued.
     *
     * Only ever raised from a `/verify` response that passed all three checks — signature,
     * `key_id` against our own token, and the nonce echo. A response that failed any of
     * them is discarded rather than acted on, so a proxy the device owner controls cannot
     * manufacture this code.
     */
    LICENSE_REVOKED,

    /** The backend says this licence has expired — typically an ended trial. */
    LICENSE_EXPIRED,

    /**
     * The token verified offline but the backend has no record of it.
     *
     * **Never stops tracking.** A token that satisfies a key we compiled in ourselves and
     * is then unknown to the ledger is our data problem, not a customer who did anything
     * wrong.
     */
    LICENSE_UNKNOWN,

    /** The backend has this licence under a different package name. Diagnostic only. */
    LICENSE_PACKAGE_MISMATCH,

    /** The backend has this licence under a different SDK type. Diagnostic only. */
    LICENSE_SDK_MISMATCH,

    NO_ACTIVITY,

    /**
     * The device or process failed a `BLOCK`-policy integrity check — a hooking framework,
     * a selected mock-location app, or whatever else `SecurityConfig` was told to refuse.
     *
     * The message names the blocking signals; `Tracker.integrity()` carries the full report.
     * Never returned by a debuggable build, where the whole layer is waived.
     */
    DEVICE_INTEGRITY_BLOCKED,

    /** motionQuality = POOR — motion gating is not trustworthy on this hardware (EC-137). */
    MOTION_DETECTION_DEGRADED,

    /** Geofence registration failed. The internal stationary wake path degrades. */
    GEOFENCE_REGISTRATION_FAILED,

    /** Geofence removal failed. The active fence may still be armed in Play Services. */
    GEOFENCE_REMOVAL_FAILED,

    /** The SDK-managed geofence registry already contains [TrackerGeofence.MAX_GEOFENCES]. */
    GEOFENCE_LIMIT_REACHED,

    /**
     * A `RoadSnapProvider` was installed but could not answer. **Never fatal**: the track
     * is built from raw geometry and carries a `snap_unavailable` warning (EC-100).
     */
    SNAP_UNAVAILABLE,

    /**
     * Something threw where the contract says nothing throws.
     *
     * Exists for the bridge surfaces, which have to turn *any* failure into a value: a
     * Java callback and a JS Promise both need a code, and inventing a plausible-looking
     * one — `STORAGE_FULL` for an arbitrary exception — would send a host debugging the
     * wrong subsystem. The message carries what actually happened.
     *
     * A host seeing this has found a bug in the SDK, not a condition to handle.
     */
    INTERNAL,
}

public enum class PermissionTier { NONE, FOREGROUND_ONLY, FULL }

public enum class LocationAccuracy { APPROXIMATE, PRECISE }

/**
 * @property locationServicesEnabled the Settings master switch. Not the union of
 *   [gpsEnabled] and [networkEnabled] — from API 28 the platform answers this directly, and
 *   the two can disagree: a device with every provider off still reports location "enabled"
 *   until the switch itself is turned off.
 * @property airplaneMode airplane mode is on. Not a gate — GPS keeps working in airplane
 *   mode on most devices, while network positioning does not — but it is the first thing to
 *   check when a track degrades to GPS-only or stops in a building.
 */
public data class ProviderState(
    val gpsEnabled: Boolean = false,
    val networkEnabled: Boolean = false,
    val locationServicesEnabled: Boolean = false,
    val permission: PermissionTier = PermissionTier.NONE,
    val accuracyAuthorization: LocationAccuracy = LocationAccuracy.APPROXIMATE,
    val fusedAvailable: Boolean = false,
    val powerSaveMode: Boolean = false,
    val airplaneMode: Boolean = false,
) {
    /**
     * This state as the per-point wire snapshot uploaded with every coordinate.
     *
     * The mapping from the SDK's own vocabulary to the wire codes lives here, next to the
     * state it translates, so the two cannot drift.
     */
    internal fun toSnapshot(): ProviderSnapshot = ProviderSnapshot(
        recorded = true,
        gpsEnabled = gpsEnabled,
        networkEnabled = networkEnabled,
        locationServicesEnabled = locationServicesEnabled,
        airplaneMode = airplaneMode,
        authorizationStatus = when (permission) {
            // Android cannot tell "never asked" from "asked and refused", so both land on
            // DENIED rather than one of them claiming NOT_DETERMINED it cannot prove.
            PermissionTier.NONE -> ProviderSnapshot.STATUS_DENIED
            PermissionTier.FOREGROUND_ONLY -> ProviderSnapshot.STATUS_WHEN_IN_USE
            PermissionTier.FULL -> ProviderSnapshot.STATUS_ALWAYS
        },
        accuracyAuthorization = when (accuracyAuthorization) {
            LocationAccuracy.PRECISE -> ProviderSnapshot.ACCURACY_FULL
            LocationAccuracy.APPROXIMATE -> ProviderSnapshot.ACCURACY_REDUCED
        },
    )
}

/**
 * Events the host may observe.
 *
 * Exposed as a `SharedFlow` with replay 0 and unlimited subscribers — never a
 * `var callback`, which silently lets the second registrant replace the first
 * (EC-112).
 */
public sealed interface TrackerEvent {
    public data class Location(val point: TrackPoint) : TrackerEvent
    public data class LocationRejected(val decision: FixDecision) : TrackerEvent
    public data class MotionChange(val state: MotionState, val point: TrackPoint?) : TrackerEvent
    public data class ActivityChange(val activity: ActivityType, val confidence: Int) : TrackerEvent
    public data class EnabledChange(val enabled: Boolean) : TrackerEvent
    public data class ProviderChange(val state: ProviderState) : TrackerEvent
    public data class Heartbeat(val atMs: Long) : TrackerEvent
    public data class PowerSaveChange(val enabled: Boolean) : TrackerEvent

    /**
     * The location permission grant changed while the SDK was running — a revoke, a
     * re-grant, a downgrade from "Allow all the time" to "While using the app", or a
     * precise/approximate flip.
     *
     * Emitted for **every** transition in either direction, including the recoveries.
     * [ProviderChange] carries the same facts inside a whole-state snapshot, which makes
     * a host wanting to react to *the permission specifically* diff two snapshots to find
     * out what moved. This says it directly, which is what a re-prompt decision needs.
     *
     * A revoke does not close the session. It suspends capture and arrives here plus as
     * an [Error]; the session stays open and capture resumes on its own once the grant
     * comes back — see [CaptureSuspended] (EC-06, EC-07).
     *
     * @property previous the tier before this change.
     * @property current the tier now in force.
     * @property accuracy the granularity now in force. Orthogonal to the tier: a grant can
     *   stay `FULL` and still drop to [LocationAccuracy.APPROXIMATE].
     */
    public data class PermissionChange(
        val previous: PermissionTier,
        val current: PermissionTier,
        val accuracy: LocationAccuracy,
    ) : TrackerEvent

    /**
     * The device location master switch, or the selected provider behind it, was toggled.
     *
     * Both directions, unlike the one-way `LOCATION_DISABLED` error that preceded it: "GPS
     * came back" is the transition a host waiting to re-enable a UI actually needs, and it
     * was previously unobservable except by diffing [ProviderChange] snapshots.
     */
    public data class LocationServicesChange(
        val enabled: Boolean,
        val state: ProviderState,
    ) : TrackerEvent

    /**
     * Capture stopped feeding the pipeline, but the session is still open.
     *
     * Raised when the SDK can no longer produce fixes through no fault of the host's:
     * location permission revoked mid-session ([ErrorCode.PERMISSION_DENIED]) or every
     * provider switched off ([ErrorCode.LOCATION_DISABLED]). The location request is torn
     * down rather than left registered against a dead provider, and re-armed by
     * [CaptureResumed] the moment the condition clears.
     *
     * Deliberately **not** a stop: whether a permission toggle should end a session is the
     * host's decision, never a side effect inside the SDK (EC-07).
     */
    public data class CaptureSuspended(
        val reason: ErrorCode,
        val message: String,
    ) : TrackerEvent

    /**
     * Capture re-armed after a [CaptureSuspended], in the same session and against the
     * same session id. A one-shot fix is requested immediately so the gap has a boundary
     * on both sides rather than waiting out a full interval.
     */
    public data object CaptureResumed : TrackerEvent

    /**
     * Charge level or power source changed.
     *
     * Emitted on transitions, not on a timer: plug, unplug, low, okay, and whatever drift
     * the capture path notices between them. With no session running there is nothing
     * polling, so only the four broadcasts fire.
     */
    public data class BatteryChange(val battery: BatteryInfo) : TrackerEvent
    public data class GeofenceAdded(val geofence: TrackerGeofence) : TrackerEvent
    public data class GeofenceRemoved(val geofenceId: String) : TrackerEvent
    public data class GeofenceEntered(val geofence: TrackerGeofence) : TrackerEvent
    public data class GeofenceExited(val geofence: TrackerGeofence) : TrackerEvent

    /** A session was found still open at launch — the host decides what to do (EC-66). */
    public data class SessionInterrupted(val session: TrackSession) : TrackerEvent
    /**
     * The device-integrity flag set changed — a hooking framework appeared, a mock fix
     * arrived, developer options were switched on mid-session.
     *
     * Emitted on transitions only, not on every re-evaluation. A `BLOCK`-policy finding
     * arrives here *and* as an [Error] with `ErrorCode.DEVICE_INTEGRITY_BLOCKED`.
     */
    public data class IntegrityChange(val report: IntegrityReport) : TrackerEvent

    /**
     * A licence check completed and its answer was authenticated.
     *
     * Emitted for **every** verified answer, `active` included — this is how a host sees
     * a successful check at all. Fires shortly after every `Tracker.ready()`, on the
     * background worker's 12-hour tick, and on `Tracker.checkLicense()`.
     *
     * The one after `ready()` arrives *after* `ready()` has already returned. The startup
     * path decides from cache and never waits on a network call, so a host must not treat
     * a successful `ready()` as this event having happened yet.
     *
     * Silence is not success. Nothing is emitted when the network failed, the build has
     * no licence configured, or a response failed verification: all three mean nothing
     * was learned, and all three carry on tracking.
     *
     * `REVOKED` and `EXPIRED` arrive here *and* as an [Error] with the matching
     * `ErrorCode`, in the same way a blocking integrity finding does. The rest arrive
     * here only.
     */
    public data class LicenseChecked(val info: LicenseInfo) : TrackerEvent
    public data class Diagnostic(val message: String) : TrackerEvent
    public data class Error(val code: ErrorCode, val message: String) : TrackerEvent
}

/**
 * Public state for a persistent circular system geofence.
 *
 * Hosts may register 19 fences. The internal stationary wake fence uses a reserved slot
 * and the same model so all additions and crossings share one event contract.
 */
@Serializable
public data class TrackerGeofence(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val radiusM: Float,
    val onEnterEvent: String = DEFAULT_ENTER_EVENT,
    val onExitEvent: String = DEFAULT_EXIT_EVENT,
) {
    public companion object {
        /** Public parity limit; the SDK's internal stationary wake fence does not count. */
        public const val MAX_GEOFENCES: Int = 19
        public const val DEFAULT_ID: String = "fieldtrack-stationary"
        public const val DEFAULT_ENTER_EVENT: String = "stationary_fence_enter"
        public const val DEFAULT_EXIT_EVENT: String = "stationary_fence_exit"
    }
}

/** A persisted crossing of a registered geofence. */
@Serializable
public data class TrackerGeofenceEvent(
    val geofence: TrackerGeofence,
    val transition: GeofenceTransition,
    val timestampMs: Long,
    val eventName: String,
)

@Serializable
public enum class GeofenceTransition { ENTER, EXIT }

/** Coarse lifecycle state of the SDK itself. */
public data class TrackerState(
    val isReady: Boolean = false,
    val isTracking: Boolean = false,
    /**
     * Whether the location stream is actually registered right now.
     *
     * Not the same question as [isTracking], and the difference is the whole point: a
     * session with a revoked permission or a switched-off GPS stays open and keeps
     * `isTracking = true`, because ending it is the host's call — but nothing is being
     * captured. Before this field the two states were indistinguishable from outside, so
     * a host UI reported "tracking" through an outage it could have prompted the user to
     * fix. `false` while `isTracking` is `true` means suspended; see
     * [TrackerEvent.CaptureSuspended].
     */
    val isCapturing: Boolean = false,
    val motionState: MotionState = MotionState.STOPPED,
    val providerState: ProviderState = ProviderState(),
    val currentSessionId: String? = null,
)

/**
 * Query surface for stored points. All reads are paged (EC-80).
 *
 * `@Serializable` in place for the same reason as `TrackOptions`: a pure input value
 * object has no internals a mirror DTO would decouple (CROSS-PLATFORM.md B-5).
 */
@Serializable
public data class PointQuery @JvmOverloads constructor(
    val sessionId: String? = null,
    val fromMs: Long? = null,
    val toMs: Long? = null,
    val limit: Int = 500,
    val offset: Int = 0,
)
