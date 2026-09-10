package com.field360.tracker.data.platform

import android.content.Context
import android.os.PowerManager
import com.field360.tracker.ServiceConfig
import com.field360.tracker.WakeLockPolicy
import com.field360.tracker.sdkLog
import com.field360.tracker.sdkWarn
import com.field360.traker.geo.port.TrackLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The SDK's `PARTIAL_WAKE_LOCK`, and the policy that decides how long it is held.
 *
 * ### Why this stopped being a loop inside the service
 *
 * `foregroundServiceType="location"` keeps the *process* alive; it does not keep the *CPU*
 * awake. In Doze — and far more aggressively on MIUI/HyperOS and OxygenOS — the process is
 * frozen between location callbacks, so the ingest consumer, the motion tick that drives
 * the stop timeout, and the health loop all stall. A partial wake lock is the answer to
 * that, and the SDK held one for the entire life of every session, re-armed every ten
 * seconds forever.
 *
 * That works, and it is also self-defeating on the hardware it was written for. A
 * continuously held partial wake lock is one of the loudest signals an OEM power manager
 * has: MIUI's Power Keeper, Samsung's DEP and OxygenOS all weight sustained wakelock time
 * heavily when deciding what to kill, and Play Console reports it as excessive wakeup
 * behaviour against the *host* app. The mitigation for being killed was itself a reason to
 * be killed.
 *
 * [WakeLockPolicy.PER_FIX] — the default — holds the lock only where the work is. Each
 * delivered fix takes the lock for `wakeLockMs` and the timeout releases it, so the CPU
 * stays up long enough for the ingest coroutine to run the pipeline and write the row, and
 * then the device is allowed to sleep. At the 60 s default cadence with a 20 s hold that is
 * a third of the wall clock rather than all of it, and the duty cycle falls further the
 * slower the tier.
 *
 * ### What PER_FIX gives up, said plainly
 *
 * The timed loops — `HealthLoop`, the motion tick — run on `delay`, which is a coroutine
 * timer and not an alarm. Between fixes the CPU may now sleep, so those ticks drift late by
 * however long the device stays down. That is the trade: supervision cadence for not being
 * killed. It is survivable because the things that must *not* drift no longer depend on
 * this — `ServiceHeartbeat` is an `AlarmManager` chain, and the backstop is a periodic job.
 * A host that would rather have the old behaviour sets [WakeLockPolicy.CONTINUOUS] and
 * accepts the OEM's attention.
 *
 * ### Never throws
 *
 * An OEM that refuses `PowerManager`, or a `WAKE_LOCK` permission stripped by a host's own
 * manifest merge, degrades to holding nothing rather than taking the service down.
 */
internal class WakeLockController(
    private val context: Context,
    private val logger: TrackLogger,
) {

    /**
     * Not reference counted, so a re-arm is a re-arm rather than a second hold that
     * `release()` would then have to be called twice to undo. One lock for the process,
     * created on first use.
     */
    private var lock: PowerManager.WakeLock? = null

    @Volatile
    private var policy: WakeLockPolicy = WakeLockPolicy.PER_FIX

    @Volatile
    private var holdMs: Long = 0L

    /** Re-arms the lock under [WakeLockPolicy.CONTINUOUS]; null under every other policy. */
    private var renewalJob: Job? = null

    /**
     * Applies the session's policy. Called from `TrackingService` on every start command,
     * so a reconfigure between sessions takes effect without a process restart.
     *
     * @param scope the service's lifecycle scope — used only by [WakeLockPolicy.CONTINUOUS],
     *   whose renewal loop must die with the service that owns it.
     */
    fun configure(config: ServiceConfig, scope: CoroutineScope) {
        renewalJob?.cancel()
        renewalJob = null

        policy = config.wakeLockPolicy
        holdMs = config.wakeLockMs

        // `wakeLockMs = 0` disables the lock whatever the policy says — that contract
        // predates the policy and hosts rely on it.
        if (holdMs <= 0L || policy == WakeLockPolicy.NONE) {
            releaseNow()
            return
        }

        if (policy != WakeLockPolicy.CONTINUOUS) return

        renewalJob = scope.launch {
            while (isActive) {
                acquire(holdMs)
                // Half the timeout, so the renewal always lands while the lock it depends
                // on is still holding the CPU up to run it.
                delay(holdMs / 2)
            }
        }
    }

    /**
     * A fix was delivered — keep the CPU up long enough to process it.
     *
     * Called from the ingest path on every raw fix, before any gate: a fix that the
     * pipeline is about to reject still has to be *judged*, and the judging is the work
     * that needs the CPU. A no-op under every policy but [WakeLockPolicy.PER_FIX], and
     * cheap enough to sit on the capture path — one volatile read in the common case.
     */
    fun onFixDelivered() {
        if (policy != WakeLockPolicy.PER_FIX) return
        val hold = holdMs
        if (hold <= 0L) return
        acquire(hold)
    }

    /** Drops the lock and any renewal loop. Part of the service's teardown. */
    fun release() {
        renewalJob?.cancel()
        renewalJob = null
        releaseNow()
    }

    /**
     * Acquires or re-arms with [timeoutMs] as the timeout, so a lock leaked by a process
     * that dies between `onStartCommand` and `onDestroy` releases itself rather than
     * draining the battery until reboot.
     */
    private fun acquire(timeoutMs: Long) {
        val held = lock ?: runCatching {
            context.getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
                .apply { setReferenceCounted(false) }
        }.onFailure {
            sdkWarn { logger.w(TAG, "PowerManager refused a wake lock: ${it.message}") }
        }.getOrNull()?.also { lock = it } ?: return

        runCatching { held.acquire(timeoutMs) }
    }

    private fun releaseNow() {
        val held = lock ?: return
        lock = null
        runCatching { if (held.isHeld) held.release() }
    }

    private companion object {
        const val TAG = "WakeLock"

        /**
         * Namespaced with the SDK's package, because a wake-lock tag is what `dumpsys power`
         * and Play Console's excessive-wakelock report attribute the hold to. A generic tag
         * makes a battery complaint impossible to trace to whoever caused it.
         */
        const val WAKE_LOCK_TAG = "fieldtrack:tracking"
    }
}
