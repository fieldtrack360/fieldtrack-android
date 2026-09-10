package com.field360.tracker.capture

import android.os.Build
import com.field360.tracker.TrackerConfig
import com.field360.tracker.sdkLog
import com.field360.tracker.sdkWarn
import com.field360.tracker.data.location.FixMapper
import com.field360.tracker.data.location.LocationSource
import com.field360.tracker.domain.model.ErrorCode
import com.field360.tracker.domain.model.TrackerEvent
import com.field360.traker.geo.model.TrackFix
import com.field360.traker.geo.port.TrackLogger
import com.field360.tracker.permission.ProviderStateMonitor
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A single high-accuracy fix on demand — used by force-capture at an activity
 * transition, by the backstop worker, and by `getCurrentLocation()`.
 *
 * Two guards matter here. The retry cap stops a device that simply cannot get a fix
 * (indoors, cold start) from becoming a retry storm; the reference caps at 2–3 for the
 * same reason (EC-17, EC-20). The mutex stops overlapping requests — an AR transition
 * and the backstop can easily coincide, and two in-flight one-shots would feed the
 * ingestor duplicate work for no benefit.
 *
 * Results go through the same [FixIngestor] as the stream, so a one-shot fix is judged
 * by exactly the same gates against the same anchor (SOURCE-AUDIT A3).
 */
internal class OneShotProvider(
    private val locationSource: LocationSource,
    private val fixMapper: FixMapper,
    private val ingestor: FixIngestor,
    private val events: MutableSharedFlow<TrackerEvent>,
    private val logger: TrackLogger,
    private val providerState: ProviderStateMonitor,
) {

    private val mutex = Mutex()
    private var consecutiveFailures = 0

    /** @return the mapped fix, or `null` if none could be obtained. */
    suspend fun capture(
        config: TrackerConfig,
        feedIngestor: Boolean = true,
        suppressAfterRepeatedFailures: Boolean = true,
    ): TrackFix? {
        if (suppressAfterRepeatedFailures && consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            sdkWarn { logger.w(TAG, "One-shot suppressed after $consecutiveFailures consecutive failures") }
            return null
        }

        // Never queue behind an in-flight request; a coincident AR transition and
        // backstop tick should produce one fix, not two.
        if (!mutex.tryLock()) return null
        try {
            val location = withTimeoutOrNull(config.geolocation.oneShotTimeoutMs) {
                locationSource.oneShot(config.geolocation)
            }

            if (location == null) {
                if (suppressAfterRepeatedFailures) consecutiveFailures++
                val state = providerState.state.value
                // GPS off, approximate-only grant, or power save all shrink the odds of a
                // HIGH_ACCURACY fix landing inside the deadline — surface them at the
                // timeout site since they're the usual explanation on a given device/OS
                // combination (e.g. reports isolated to API 33).
                sdkWarn {
                    logger.w(
                        TAG,
                        "One-shot timed out after ${config.geolocation.oneShotTimeoutMs} ms " +
                            "(gpsEnabled=${state.gpsEnabled}, networkEnabled=${state.networkEnabled}, " +
                            "accuracy=${state.accuracyAuthorization}, powerSaveMode=${state.powerSaveMode}, " +
                            "device=${Build.MANUFACTURER} ${Build.MODEL}, api=${Build.VERSION.SDK_INT})",
                    )
                }
                events.tryEmit(
                    TrackerEvent.Error(
                        ErrorCode.FIX_TIMEOUT,
                        "One-shot timed out after ${config.geolocation.oneShotTimeoutMs} ms " +
                            "(gps=${state.gpsEnabled}, accuracy=${state.accuracyAuthorization}, " +
                            "api=${Build.VERSION.SDK_INT})",
                    ),
                )
                return null
            }

            consecutiveFailures = 0
            val fix = fixMapper.map(location, config.geolocation.mockLocationPolicy) ?: return null
            if (feedIngestor) ingestor.offer(fix)
            return fix
        } finally {
            mutex.unlock()
        }
    }

    /** Cleared on a successful stream fix or a new session, so the cap is per-outage. */
    fun resetFailures() {
        consecutiveFailures = 0
    }

    private companion object {
        const val TAG = "OneShotProvider"
        const val MAX_CONSECUTIVE_FAILURES = 3
    }
}
