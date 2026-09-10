package com.field360.tracker.data.platform

import android.os.SystemClock
import android.util.Log
import com.field360.tracker.BuildConfig
import com.field360.traker.geo.port.Clock
import com.field360.traker.geo.port.SdkLogRelay
import com.field360.traker.geo.port.TrackLogger

/**
 * The two clocks, kept apart on purpose.
 *
 * [wallTimeMs] is for storage and display and may jump backwards; only
 * [elapsedRealtimeNanos] is allowed into filter arithmetic (SOURCE-AUDIT A1).
 */
internal class AndroidClock() : Clock {
    override fun wallTimeMs(): Long = System.currentTimeMillis()
    override fun elapsedRealtimeNanos(): Long = SystemClock.elapsedRealtimeNanos()
}

/**
 * Logcat, plus [SdkLogRelay] when a host has turned diagnostics on.
 *
 * The two destinations are gated separately on purpose. Logcat is compiled off in release
 * because any app on a rooted device can read it and a shipped app should not narrate
 * itself there. The relay is a private buffer the host asked for and ships to its own
 * backend, so it stays on in release — that is the only way a phone in the field can
 * explain what it did.
 */
internal class AndroidLogger() : TrackLogger {
    override fun d(tag: String, message: String) {
        if (BuildConfig.SDK_LOGGING_ENABLED) Log.d(prefixed(tag), message)
        SdkLogRelay.d(tag, message)
    }

    override fun w(tag: String, message: String) {
        if (BuildConfig.SDK_LOGGING_ENABLED) Log.w(prefixed(tag), message)
        SdkLogRelay.w(tag, message)
    }

    // The relay is given the bare tag, not this. `Tracker/` exists to make one `adb
    // logcat -s` filter catch every SDK line; the buffer stores the tag in its own column
    // and a prefix there would be noise on every row.
    private fun prefixed(tag: String) = "Tracker - $tag"
}
