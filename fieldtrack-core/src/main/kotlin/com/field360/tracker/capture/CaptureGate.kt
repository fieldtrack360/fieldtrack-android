package com.field360.tracker.capture

import com.field360.tracker.TrackerConfig
import com.field360.tracker.domain.model.ErrorCode
import com.field360.tracker.domain.model.PermissionTier
import com.field360.tracker.domain.model.ProviderState
import com.field360.tracker.domain.model.TrackerEvent
import com.field360.tracker.sdkLog
import com.field360.traker.geo.port.TrackLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Keeps the location stream matched to what the device will actually allow, for as long
 * as a session is open.
 *
 * `ProviderStateMonitor` has always *observed* mid-session revocations and GPS toggles —
 * that is what the `AppOpsManager` watcher and the `PROVIDERS_CHANGED` receiver are for —
 * and its own KDoc claimed a downgrade stopped the stream and an upgrade restarted it
 * (EC-07). Nothing implemented that. The state flowed to `Tracker.state`, was stamped on
 * points, and was read by the one-shot path for its timeout message; no consumer ever
 * touched [LocationStreamController]. So a permission revoked at 10:00 left the request
 * registered against a provider the app could no longer read, and re-granting it at 10:05
 * changed nothing — the session stayed open, `isTracking` stayed true, and not one fix
 * arrived for the rest of the drive. This class is the missing consumer.
 *
 * **It never closes the session.** Ending a drive because a user tapped a permission
 * toggle is an application decision, and the SDK is not entitled to make it (EC-07). The
 * session stays open, the stream is torn down, the host is told, and capture re-arms by
 * itself the moment the device allows it again.
 *
 * ### Why two conditions and not three
 * Capture is possible when the app holds *some* location permission and *some* provider is
 * switched on. Granularity is deliberately excluded: an approximate-only grant still
 * produces fixes, and `AcceptancePipeline` is where a 1–3 km error circle gets judged.
 * Suspending on it would turn a degradation into an outage and drop the coarse fixes that
 * are the only evidence the user moved at all (EC-02 gates that at `start()`, where the
 * host can still act on it).
 */
internal class CaptureGate(
    private val providerState: StateFlow<ProviderState>,
    private val captureSwitch: CaptureSwitch,
    private val events: MutableSharedFlow<TrackerEvent>,
    private val logger: TrackLogger,
    private val scope: CoroutineScope,
    /**
     * Run once per recovery, after the stream is re-armed.
     *
     * A lambda rather than an injected `OneShotProvider` so this class stays constructible
     * without the whole capture stack behind it; the graph supplies the real thing.
     */
    private val onResumed: suspend (TrackerConfig) -> Unit,
) {

    private var job: Job? = null

    @Volatile
    private var config: TrackerConfig? = null

    /**
     * `null` until the first evaluation, which is what makes [arm] able to suspend a stream
     * that was started into an outage without also claiming it "changed".
     */
    private var usable: Boolean? = null

    /** True while a session is open and the device can actually produce fixes. */
    val isCapturing: Boolean get() = job != null && !captureSwitch.isSuspended

    /**
     * Starts watching, and evaluates once immediately.
     *
     * Called from `StartTrackingUseCase` **after** `streamController.start`, so the
     * immediate evaluation acts on a stream that already exists: a session opened while
     * location was switched off is suspended here rather than left registered and silent.
     */
    fun arm(config: TrackerConfig) {
        this.config = config
        this.usable = null
        job?.cancel()
        job = scope.launch {
            providerState.collect { state -> evaluate(state) }
        }
    }

    fun disarm() {
        job?.cancel()
        job = null
        config = null
        usable = null
    }

    private suspend fun evaluate(state: ProviderState) {
        val active = config ?: return
        val now = canCapture(state)
        if (usable == now) return

        val first = usable == null
        usable = now

        if (!now) {
            val (code, message) = reasonFor(state)
            sdkLog { logger.w(TAG, "Suspending capture: $message") }
            captureSwitch.suspendCapture()
            events.tryEmit(TrackerEvent.CaptureSuspended(code, message))
            events.tryEmit(TrackerEvent.Error(code, message))
            return
        }

        // Nothing was suspended, so there is nothing to announce: this is the ordinary
        // path where a session starts on a healthy device and the first evaluation simply
        // agrees with it. Without this guard every `start()` would emit a spurious
        // `CaptureResumed` before a single fix had arrived.
        if (first && !captureSwitch.isSuspended) return

        if (!captureSwitch.resumeCapture()) return

        sdkLog { logger.d(TAG, "Capture resumed (permission=${state.permission})") }
        events.tryEmit(TrackerEvent.CaptureResumed)

        // Bookends the gap. The stream's own first fix is a whole interval away at the
        // default cadence, so without this a recovery is invisible for a minute and looks
        // to a host exactly like the outage continuing.
        onResumed(active)
    }

    /**
     * A permission and a provider. `locationServicesEnabled` is read as well as the two
     * providers because below API 28 it *is* their union, and above it the master switch
     * can be off while a provider still reports enabled — either one being false is enough
     * to stop fixes arriving.
     */
    private fun canCapture(state: ProviderState): Boolean =
        state.permission != PermissionTier.NONE &&
            state.locationServicesEnabled &&
            (state.gpsEnabled || state.networkEnabled)

    private fun reasonFor(state: ProviderState): Pair<ErrorCode, String> = when {
        state.permission == PermissionTier.NONE ->
            ErrorCode.PERMISSION_DENIED to
                "Location permission revoked mid-session; capture suspended until it is granted again"

        else ->
            ErrorCode.LOCATION_DISABLED to
                "Location services unavailable (gps=${state.gpsEnabled}, network=${state.networkEnabled}, " +
                    "master=${state.locationServicesEnabled}); capture suspended until they return"
    }

    private companion object {
        const val TAG = "CaptureGate"
    }
}
