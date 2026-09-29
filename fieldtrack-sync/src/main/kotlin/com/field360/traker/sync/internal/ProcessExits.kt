package com.field360.traker.sync.internal

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import com.field360.tracker.TrackerArtifacts
import com.field360.tracker.domain.model.TrackerEvent

/** An event the process emitted before the log channel attached, and when. */
internal class EarlyEvent(
    val event: TrackerEvent,
    val wallTimeMs: Long,
    val elapsedRealtimeNanos: Long,
)

/**
 * The events core held for this process — once; later calls return nothing.
 *
 * Here rather than inline in `TrackerSync` for the same packaging reason as the lambdas in
 * `LogWiring`.
 */
internal fun drainEarlyEvents(access: TrackerArtifacts): List<EarlyEvent> = buildList {
    access.drainEarlyEvents { event, wallTimeMs, elapsedRealtimeNanos ->
        add(EarlyEvent(event, wallTimeMs, elapsedRealtimeNanos))
    }
}

/**
 * How the previous process of this app ended, as Android recorded it.
 *
 * @property reason `ApplicationExitInfo.REASON_*` as a name — `OTHER` and `SIGNALED` are
 *   what OEM battery managers usually leave behind, `USER_REQUESTED` a force stop or a
 *   swipe the ROM treats as one, `LOW_MEMORY` the kernel.
 */
internal data class ProcessExit(
    val reason: String,
    val timestampMs: Long,
    val description: String?,
    val importance: Int,
    val status: Int,
    val processName: String?,
)

/**
 * The most recent recorded exit of this package, or `null` below Android 11 or when the
 * platform will not say.
 */
internal fun lastProcessExit(context: Context): ProcessExit? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
    return runCatching {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return null
        manager.getHistoricalProcessExitReasons(context.packageName, 0, 1)
            .firstOrNull()
            ?.let(::toProcessExit)
    }.getOrNull()
}

@RequiresApi(Build.VERSION_CODES.R)
private fun toProcessExit(info: ApplicationExitInfo) = ProcessExit(
    reason = exitReasonName(info.reason),
    timestampMs = info.timestamp,
    description = info.description,
    importance = info.importance,
    status = info.status,
    processName = info.processName,
)

/** Literal values, so the names added after API 30 resolve on every device. */
internal fun exitReasonName(reason: Int): String = when (reason) {
    0 -> "UNKNOWN"
    1 -> "EXIT_SELF"
    2 -> "SIGNALED"
    3 -> "LOW_MEMORY"
    4 -> "CRASH"
    5 -> "CRASH_NATIVE"
    6 -> "ANR"
    7 -> "INITIALIZATION_FAILURE"
    8 -> "PERMISSION_CHANGE"
    9 -> "EXCESSIVE_RESOURCE_USAGE"
    10 -> "USER_REQUESTED"
    11 -> "USER_STOPPED"
    12 -> "DEPENDENCY_DIED"
    13 -> "OTHER"
    14 -> "FREEZER"
    15 -> "PACKAGE_STATE_CHANGE"
    16 -> "PACKAGE_UPDATED"
    else -> "REASON_$reason"
}
