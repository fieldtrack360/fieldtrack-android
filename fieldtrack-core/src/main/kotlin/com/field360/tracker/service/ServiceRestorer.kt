package com.field360.tracker.service

import android.content.Context
import com.field360.tracker.di.TrackerGraph
import com.field360.tracker.domain.model.TrackerEvent
import com.field360.tracker.work.RestoreWorker

/**
 * The one door every "the service needs to come back" request goes through, and the
 * thing that stops that request from becoming a loop.
 *
 * ### The loop this ends
 *
 * [TrackingService.promoteToForeground] answers a refused `startForeground` by enqueuing
 * [RestoreWorker]. [RestoreWorker] answers by calling [TrackingService.start]. On a device
 * where the app is not eligible to start a foreground service from the background — which
 * after an OEM kill on API 31+ is the *normal* state, not an edge case — that start is
 * refused again, and the two call each other forever:
 *
 * ```
 * promoteToForeground() refused → RestoreWorker → TrackingService.start() → refused → …
 * ```
 *
 * Nothing capped it and nothing delayed it. Each turn spent an expedited-work quota slot;
 * once the quota was gone WorkManager silently demoted the request to ordinary work
 * ([androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST]), and ordinary work
 * in the `RESTRICTED` standby bucket — where MIUI/HyperOS puts a "Battery saver"-restricted
 * app — does not run at all. So the loop burned the one budget that could have brought the
 * service back later, and then went quiet with the session still open.
 *
 * ### The shape that replaces it
 *
 * Attempts are counted and delayed. The first is expedited and immediate, because a single
 * refusal is often transient — an allowlist window that closed between the decision to
 * start and the start itself. Every attempt after that is ordinary work with a doubling
 * delay, because repeated refusals mean the app is simply not eligible, and hammering an
 * ineligible app is what spent the quota. After [MAX_ATTEMPTS] the fast path gives up and
 * says so.
 *
 * **Giving up is not giving up on the session.** Two slower paths keep running and neither
 * is counted here: [ServiceHeartbeat], whose alarm is independent of WorkManager entirely,
 * and `BackstopWorker` on its own 15-minute periodic tick. The cap governs the *tight
 * retry*, not recovery. Each heartbeat tick calls [reset], so every alarm grants a fresh
 * budget of fast attempts.
 *
 * ### Why in-memory state is the right state
 *
 * The counter is a property of one process's losing streak. A process that dies takes it
 * with it, and that is correct: a fresh process is a fresh chance, and the very next thing
 * a revived process does is try to start the service. Persisting it would carry a dead
 * process's bad luck into a live one's first attempt.
 */
internal object ServiceRestorer {

    /**
     * Fast attempts before the tight retry stands down. Five, spanning ~7.5 minutes of
     * backoff, so the give-up lands before the 15-minute backstop tick rather than after —
     * a cap that outlives the slow path it defers to would never actually be reached.
     */
    private const val MAX_ATTEMPTS = 5

    /** First backed-off delay; doubles per attempt up to [MAX_DELAY_SECONDS]. */
    private const val BASE_DELAY_SECONDS = 30L

    /** Ceiling on the doubling — the backstop's own cadence, and no point waiting longer. */
    private const val MAX_DELAY_SECONDS = 900L

    @Volatile
    private var attempts: Int = 0

    /**
     * Asks for the foreground service to be restored, if the budget allows it.
     *
     * Safe to call from anywhere, including a broadcast receiver's main-thread window:
     * it touches no disk and enqueues work, which is what [RestoreWorker] is for.
     */
    fun request(context: Context) {
        val appContext = context.applicationContext
        val attempt = synchronized(this) {
            if (attempts >= MAX_ATTEMPTS) return@synchronized null
            ++attempts
        }

        if (attempt == null) {
            // Said once per exhausted budget, not once per request: the guard above returns
            // early on every call after the last increment, so this branch is only reached
            // by callers that keep asking — which is exactly who needs telling.
            TrackerGraph.get(appContext).events.tryEmit(
                TrackerEvent.Diagnostic(
                    "foreground service could not be restored in $MAX_ATTEMPTS attempts; " +
                        "falling back to the heartbeat alarm and the 15-minute backstop",
                ),
            )
            return
        }

        if (attempt == 1) {
            // Immediate and expedited. A first refusal is usually a closed allowlist
            // window, and the window may well be open again by the time the job lands.
            RestoreWorker.enqueueExpedited(appContext)
            return
        }

        RestoreWorker.enqueueDelayed(appContext, delaySecondsFor(attempt))
    }

    /**
     * Clears the losing streak.
     *
     * Called when the service is actually up ([TrackingService.promoteToForeground]), when
     * a session ends (`SessionTeardown`), and on every [ServiceHeartbeat] tick — the last
     * being what makes the cap a rate limit rather than a permanent surrender.
     */
    fun reset() {
        synchronized(this) { attempts = 0 }
    }

    /** True while the fast retry path still has budget. Exposed for logging and tests. */
    internal val hasBudget: Boolean get() = synchronized(this) { attempts < MAX_ATTEMPTS }

    /** 30 s, 60 s, 120 s, 240 s … capped. Attempt 1 never reaches here. */
    private fun delaySecondsFor(attempt: Int): Long {
        val doublings = (attempt - 2).coerceAtLeast(0)
        // Shifted rather than exponentiated, and clamped before the shift: `1L shl 62` is
        // still a long, `1L shl 64` is 1, and a wrapped delay would schedule the retry in
        // the past.
        val multiplier = 1L shl doublings.coerceAtMost(MAX_DOUBLINGS)
        return (BASE_DELAY_SECONDS * multiplier).coerceAtMost(MAX_DELAY_SECONDS)
    }

    /** Enough to pass [MAX_DELAY_SECONDS] with [BASE_DELAY_SECONDS]; the clamp does the rest. */
    private const val MAX_DOUBLINGS = 10

    /** Test seam: the attempt count is process state, and a test that asserts on it needs it. */
    internal val attemptCount: Int get() = synchronized(this) { attempts }
}
