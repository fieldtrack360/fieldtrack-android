package com.field360.traker.sync.internal

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import com.field360.traker.geo.port.TrackLogger
import com.field360.traker.sync.sdkLog

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
    private val onUsable: () -> Unit,
) {

    private val edge = RisingEdge()

    private var manager: ConnectivityManager? = null
    private var callback: ConnectivityManager.NetworkCallback? = null

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
        edge.reset()
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
        if (!edge.rose(usable, SystemClock.elapsedRealtime())) return
        sdkLog { logger.d(TAG, "Network usable again; asking for a drain") }
        onUsable()
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
 * - **A cooldown on top.** Validation can genuinely oscillate on a weak network, and each
 *   oscillation is a real edge. `SyncWorker` coalesces the resulting enqueues via
 *   `ExistingWorkPolicy.KEEP`, so the cost of a burst is binder calls rather than
 *   requests — but they land on whatever thread the platform picked, and a drain that is
 *   already running gains nothing from being asked again.
 */
internal class RisingEdge(private val cooldownMs: Long = DEFAULT_COOLDOWN_MS) {

    private var usable = false
    private var lastRiseMs = Long.MIN_VALUE

    /** @return true when [usable] is a rise worth acting on. */
    fun rose(usable: Boolean, nowMs: Long): Boolean {
        val previous = this.usable
        this.usable = usable
        // The level is always recorded, even when the rise is swallowed by the cooldown:
        // dropping it would leave the gate low and turn the next fall/rise pair into a
        // second edge for the same reconnection.
        if (!usable || previous) return false

        if (lastRiseMs != Long.MIN_VALUE && nowMs - lastRiseMs < cooldownMs) return false
        lastRiseMs = nowMs
        return true
    }

    /**
     * Forgets the level but **keeps the cooldown**. Re-registering — which
     * [NetworkMonitor.start] does on every `configure()` — replays the current network as
     * a fresh edge, and a host that re-configures in a loop would otherwise get an
     * unthrottled drain request each time.
     */
    fun reset() {
        usable = false
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
