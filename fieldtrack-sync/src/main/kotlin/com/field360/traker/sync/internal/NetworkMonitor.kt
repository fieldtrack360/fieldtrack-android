package com.field360.traker.sync.internal

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import com.field360.traker.geo.port.TrackLogger
import com.field360.traker.sync.SyncQueue
import com.field360.traker.sync.sdkLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Drains the queue the moment the device is back on a usable network.
 *
 * **Why this exists when `SyncWorker` already carries a `NetworkType.CONNECTED`
 * constraint.** That constraint is the *durable* half: WorkManager persists the request
 * and runs it across process death and reboot, which is the only thing that recovers a
 * backlog left by an app that was killed. What it is not is *prompt* — the job has to
 * already be enqueued for the constraint to release it, and a drain that has already
 * finished leaves nothing waiting for connectivity to come back to. This is the prompt
 * half: while the process is alive, a network transition asks for a drain directly, and
 * the queued work it enqueues is itself network-constrained, so the two halves reinforce
 * rather than duplicate.
 *
 * **Not a `CONNECTIVITY_ACTION` receiver.** That broadcast is deprecated since API 28 and
 * has been unavailable to manifest-declared receivers since API 24 — a background app
 * simply never sees it. `registerDefaultNetworkCallback` is the supported route and is a
 * better fit anyway: it reports only the network the process would actually use, so a
 * Wi-Fi/cellular handover is one transition rather than an interleaved pair of
 * `onAvailable`/`onLost` across two networks.
 *
 * **Validated, not merely connected.** A captive portal answers `onAvailable` with
 * `NET_CAPABILITY_INTERNET` and then intercepts the upload; requiring
 * `NET_CAPABILITY_VALIDATED` is what distinguishes "attached to a Wi-Fi network" from
 * "packets reach the internet". Registration cannot ask for `VALIDATED` — the platform
 * strips it from a `NetworkRequest` — so it is read off the capabilities instead.
 *
 * Rising edges only, and throttled: see [RisingEdge].
 *
 * Never throws. A device with no `ConnectivityManager`, a vendor that throws from
 * `registerDefaultNetworkCallback`, or the platform's own limit on concurrent callbacks
 * all degrade to "no prompt half", which is exactly the behaviour that shipped before
 * this class existed.
 */
internal class NetworkMonitor(
    private val context: Context,
    private val logger: TrackLogger,
    private val queue: SyncQueue,
    /** False once a 401/403 has torn the configuration down; nothing should be drained then. */
    private val isUploadable: () -> Boolean,
    /** Rows are waiting and the network is usable — ask for a drain. */
    private val onQueued: (Int) -> Unit,
) {

    private val edge = RisingEdge()

    /**
     * Where the queue lookup runs.
     *
     * **The coroutine lives here rather than in `TrackerSync`, and that is load-bearing
     * rather than tidiness.** A suspend lambda compiles to a real class named after its
     * enclosing declaration, and one written inside `TrackerSync` would read that class's
     * private fields — which makes Kotlin emit package-private `access$` bridges on it.
     * `TrackerSync` is pinned to the published API package by a `-keep` rule, and without
     * `-allowaccessmodification` R8 cannot widen those bridges, so it cannot repackage the
     * lambda either: the shrunken class stays behind as `com.field360.traker.sync.a`, and
     * `verifyReleaseObfuscation` fails the release for leaking an implementation class into
     * an API package. Born in this package, the same lambda repackages with everything else.
     *
     * `SupervisorJob` so a failed lookup cannot cancel the scope and silently retire the
     * connectivity path for the rest of the process.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var manager: ConnectivityManager? = null
    private var callback: ConnectivityManager.NetworkCallback? = null

    /** The timer owing a drain for a rise [RisingEdge] held back. Null when none is due. */
    @Volatile
    private var deferredDrain: Job? = null

    /**
     * Whether the *current* config wants an unmetered network. Read on the callback
     * thread, written from [start] on the host's thread.
     */
    @Volatile
    private var requiresUnmetered: Boolean = false

    /**
     * @return true when the platform accepted the registration. False means there is no
     *   prompt half on this device and the caller should fall back to checking the queue
     *   once itself — the durable `SyncWorker` constraint is unaffected either way.
     */
    fun start(requiresUnmeteredNetwork: Boolean): Boolean {
        stop()
        requiresUnmetered = requiresUnmeteredNetwork

        val service = runCatching {
            context.getSystemService(ConnectivityManager::class.java)
        }.getOrNull() ?: return false

        val watcher = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                update(isUsable(caps))
            }

            // The default network went away with nothing to replace it. Recorded rather
            // than ignored: without it the gate stays latched high and the *next* genuine
            // reconnection is not an edge at all.
            override fun onLost(network: Network) = update(usable = false)
        }

        // Deliberately not `registerNetworkCallback(request, cb)`: that reports every
        // network matching a filter, so a phone holding Wi-Fi and cellular at once
        // produces two independent streams and the gate below would be tracking neither.
        return runCatching {
            service.registerDefaultNetworkCallback(watcher)
            manager = service
            callback = watcher
            sdkLog { logger.d(TAG, "Watching connectivity (unmetered required: $requiresUnmeteredNetwork)") }
            true
        }.getOrElse { failure ->
            // TooManyRequestsException at 100 concurrent callbacks per process, and OEM
            // variants throw their own. Neither is worth a crash for an optimisation.
            sdkLog { logger.w(TAG, "Could not watch connectivity: ${failure.message}") }
            false
        }
    }

    fun stop() {
        val service = manager
        val watcher = callback
        manager = null
        callback = null
        cancelDeferred()
        synchronized(edge) { edge.reset() }
        if (service != null && watcher != null) {
            runCatching { service.unregisterNetworkCallback(watcher) }
        }
    }

    /**
     * Usable means all three: it claims internet, the platform has *proved* it reaches
     * the internet, and it satisfies whatever `requiresUnmeteredNetwork` asked for. The
     * metered clause is what makes losing Wi-Fi a falling edge under an unmetered-only
     * config, so re-joining Wi-Fi later is a rising one.
     */
    private fun isUsable(caps: NetworkCapabilities): Boolean =
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
            (!requiresUnmetered || caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))

    private fun update(usable: Boolean) {
        // elapsedRealtime, not wall time: a network transition and an NTP correction are
        // entirely capable of arriving together, and a throttle measured against a clock
        // that just jumped backwards would latch for as long as the jump.
        //
        // Synchronised because [edge] is now touched from two threads: the platform's
        // callback thread here, and the deferral timer below.
        val verdict = synchronized(edge) { edge.rose(usable, SystemClock.elapsedRealtime()) }

        when (verdict) {
            RisingEdge.Verdict.Drain -> {
                // A rise that fires now supersedes one waiting on the clock.
                cancelDeferred()
                drainIfQueued()
            }
            is RisingEdge.Verdict.Defer -> scheduleDeferredDrain(verdict.afterMs)
            RisingEdge.Verdict.Ignore -> Unit
        }
    }

    /**
     * The drain owed for a rise the cooldown held back.
     *
     * Fires unconditionally rather than re-testing the network first, and that is
     * deliberate: the work it enqueues carries its own connectivity constraint, so a
     * network that fell again in the meantime costs a blocked request rather than a wasted
     * one — while re-testing would reintroduce exactly the dropped-edge the deferral
     * exists to close.
     */
    private fun scheduleDeferredDrain(afterMs: Long) = synchronized(edge) {
        deferredDrain?.cancel()
        deferredDrain = scope.launch {
            delay(afterMs)
            synchronized(edge) { edge.deferredFired(SystemClock.elapsedRealtime()) }
            sdkLog { logger.d(TAG, "Reconnect held by the cooldown; draining now") }
            drainIfQueued()
        }
    }

    /**
     * On the same monitor as [scheduleDeferredDrain], so a [stop] racing the callback
     * thread cannot leave a timer behind that outlives the registration it belongs to.
     */
    private fun cancelDeferred() = synchronized(edge) {
        deferredDrain?.cancel()
        deferredDrain = null
    }

    /**
     * Ask for a drain, but only if there is anything to drain.
     *
     * The queue lookup is what keeps this from being a wake-up call: a reconnection with an
     * empty queue is the overwhelmingly common case on a device that is mostly online, and
     * enqueueing a worker to discover that costs a process start for nothing.
     *
     * Also called directly by the host when the platform refused to report connectivity at
     * all — that one check still has to happen, so it is made by hand.
     */
    fun drainIfQueued() {
        scope.launch {
            if (!isUploadable()) return@launch

            val queued = runCatching { queue.pendingCount() }.getOrDefault(0)
            if (queued == 0) return@launch

            sdkLog { logger.d(TAG, "Network back with $queued row(s) queued; requesting a drain") }
            onQueued(queued)
        }
    }

    private companion object {
        const val TAG = "NetworkMonitor"
    }
}

/**
 * The decision half of [NetworkMonitor], with no Android in it so the awkward cases are
 * testable: a handover, a flapping validation, a callback that repeats itself.
 *
 * Two rules:
 *
 * - **Edges, not levels.** `onCapabilitiesChanged` fires for signal strength, for a
 *   changing link speed, for anything at all about the default network — many times a
 *   minute on a moving vehicle. Only the unusable → usable transition is news.
 * - **A cooldown on top, that defers rather than drops.** Validation can genuinely
 *   oscillate on a weak network, and each oscillation is a real edge; one drain covers all
 *   of them. What the cooldown must not do is *lose* the last one — a flap that settles
 *   inside the window used to return false with nothing left to re-check it. So a
 *   suppressed rise comes back as [Verdict.Defer] carrying the remaining cooldown, and the
 *   caller owes a drain when it expires.
 */
internal class RisingEdge(private val cooldownMs: Long = DEFAULT_COOLDOWN_MS) {

    /** What [rose] decided. */
    sealed interface Verdict {
        /** A rise, past the cooldown. Drain now. */
        data object Drain : Verdict

        /**
         * A **real** rise, held back by the cooldown rather than discarded.
         *
         * @property afterMs what is left of the cooldown. The caller owes a drain then.
         */
        data class Defer(val afterMs: Long) : Verdict

        /** Not a rise, or a rise already covered by an outstanding [Defer]. */
        data object Ignore : Verdict
    }

    private var usable = false
    private var lastRiseMs = Long.MIN_VALUE

    /** Whether a [Verdict.Defer] is outstanding, so a flap cannot queue one per oscillation. */
    private var deferred = false

    /**
     * @return what to do about [usable] at [nowMs].
     *
     * **A suppressed rise is deferred, not dropped**, and that is the whole reason this
     * returns a verdict rather than a `Boolean`. A real reconnection is not one callback:
     * `onLost`, then `onCapabilitiesChanged` without `VALIDATED`, then the validated one,
     * all inside a couple of seconds. When the settle landed inside the cooldown the old
     * code returned false and *nothing re-checked* — the network was up, rows were queued,
     * and the only things left were the health tick (two minutes, and only while the
     * service is alive) and the backstop worker (fifteen). Returning [Verdict.Defer] hands
     * the caller the remaining cooldown so the drain happens late instead of never.
     */
    fun rose(usable: Boolean, nowMs: Long): Verdict {
        val previous = this.usable
        this.usable = usable
        // The level is always recorded, even when the rise is swallowed by the cooldown:
        // dropping it would leave the gate low and turn the next fall/rise pair into a
        // second edge for the same reconnection.
        if (!usable || previous) return Verdict.Ignore

        val elapsed = nowMs - lastRiseMs
        if (lastRiseMs != Long.MIN_VALUE && elapsed < cooldownMs) {
            // One deferral covers the whole flap. Every further oscillation inside the
            // window is the same reconnection, and a wake-up each would be the burst the
            // cooldown exists to prevent.
            if (deferred) return Verdict.Ignore
            deferred = true
            return Verdict.Defer(cooldownMs - elapsed)
        }

        deferred = false
        lastRiseMs = nowMs
        return Verdict.Drain
    }

    /**
     * The deferred drain is happening now: it counts as the rise the cooldown is measured
     * from, and it clears the way for the next one.
     */
    fun deferredFired(nowMs: Long) {
        deferred = false
        lastRiseMs = nowMs
    }

    /**
     * Forgets the level but **keeps the cooldown**. Re-registering — which
     * [NetworkMonitor.start] does on every `configure()` — replays the current network as
     * a fresh edge, and a host that re-configures in a loop would otherwise get an
     * unthrottled drain request each time.
     *
     * The deferral is dropped rather than kept, because [NetworkMonitor.stop] cancels the
     * timer that would have fired it. Re-registration replays the current network anyway,
     * so a still-usable one produces its own verdict immediately.
     */
    fun reset() {
        usable = false
        deferred = false
    }

    companion object {
        /**
         * 15 s. Long enough to swallow the validation flapping of a weak network and the
         * repeat callbacks of a handover; far shorter than the 30 s `SyncWorker` backoff,
         * so a genuine second reconnection is never the thing that waits.
         */
        const val DEFAULT_COOLDOWN_MS: Long = 15_000
    }
}
