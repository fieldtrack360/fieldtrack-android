package com.field360.tracker.work

import android.content.Context
import androidx.annotation.RestrictTo
import androidx.work.Configuration
import androidx.work.WorkManager

/**
 * The one place the SDK takes a `WorkManager` handle.
 *
 * ### Why this is not just `WorkManager.getInstance`
 *
 * `WorkManager` ships a `ContentProvider` — `androidx.startup.InitializationProvider`
 * hosting `WorkManagerInitializer` — which the platform runs during `Application` attach,
 * *before* `Application.onCreate`, on **every cold start of the process**. It constructs
 * the executors, the schedulers, the constraint trackers and the `WorkDatabase` object.
 * None of that is free, and all of it is loaded and run on the main thread.
 *
 * That cost lands in the worst possible window. `TrackingService` is a
 * `startForegroundService()` target, and the platform's ~10 second start-foreground
 * deadline opens at the *call*, not at `onStartCommand` — so on a sticky restart, a boot
 * start or an OEM revival, every ContentProvider in the merged manifest runs inside the
 * window before the service is even constructed. Overrun is
 * `ForegroundServiceDidNotStartInTimeException`, a fatal crash the SDK cannot catch; see
 * `TrackingService.reportPromotionLatency`.
 *
 * A host can delete that provider from its manifest (INTEGRATION-GUIDE.md §1.7, and
 * `sample-android`'s manifest for the exact block), which moves WorkManager's
 * initialisation off the cold start and onto first use. Doing so normally forces the host's `Application` to implement
 * `Configuration.Provider`, because `WorkManager.getInstance` throws `IllegalStateException`
 * when nothing has initialised it. **This object removes that requirement**: it initialises
 * WorkManager itself, with exactly the configuration `WorkManagerInitializer` would have
 * used, the first time the SDK asks for a handle.
 *
 * Which keeps the SDK's standing promise intact — the host adds no Kotlin, implements no
 * interface and installs no `WorkerFactory` (`Workers.kt`); removing the provider is a
 * manifest-only opt-in, and a host that leaves the provider in place is unaffected because
 * the first branch below simply succeeds.
 *
 * ### What this does NOT cover
 *
 * A host that removes the provider **and calls `WorkManager.getInstance` itself** before
 * the SDK has taken a handle still crashes, exactly as it would without the SDK present.
 * That is why the removal is documented as opt-in rather than shipped in
 * `fieldtrack-core`'s own manifest: an SDK that deletes a dependency's initialiser out
 * from under its hosts breaks the ones that were relying on it.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public object WorkManagerAccess {

    /**
     * Set once this object has driven a successful [WorkManager.initialize].
     *
     * Guards against the second caller re-entering [initialiseOnce] and paying for a
     * `runCatching` over a call that is now guaranteed to throw. It is *not* the
     * correctness barrier — `WorkManager.initialize` is itself synchronized and idempotent
     * in the only way that matters (the second call throws rather than replacing the
     * instance) — which is why the read outside the lock is safe.
     */
    @Volatile
    private var selfInitialised: Boolean = false

    /**
     * @return the process's `WorkManager`, initialising it first if nothing else has.
     *
     * Throws exactly what `WorkManager.getInstance` throws today when initialisation is
     * genuinely impossible, rather than swallowing it into a null: every call site in the
     * SDK already decides for itself whether a failed enqueue is fatal (`HealthLoop`
     * wraps its read, `ServiceRestorer`'s callers let it propagate to the worker), and
     * changing that contract here would silently disarm those decisions.
     */
    public fun get(context: Context): WorkManager {
        val app = context.applicationContext
        return try {
            // The ordinary path in a host that kept the provider, and the path taken after
            // the first SDK call in a host that did not. `getInstance` also handles an
            // `Application` that implements `Configuration.Provider` on its own, so a host
            // that took the documented AndroidX route never reaches the catch either.
            WorkManager.getInstance(app)
        } catch (e: IllegalStateException) {
            initialiseOnce(app)
            WorkManager.getInstance(app)
        }
    }

    /**
     * Installs the default configuration — byte for byte what `WorkManagerInitializer`
     * would have installed at process attach, just later and off the critical path.
     *
     * The `runCatching` is for the race, not for failure: two SDK threads can reach the
     * catch above at the same moment (a worker enqueueing while the health loop reads),
     * and the loser gets `IllegalStateException("WorkManager is already initialized")`.
     * That loss is a success — the handle it wanted now exists — so it is dropped here and
     * the caller re-reads it through `getInstance`.
     */
    private fun initialiseOnce(app: Context) {
        if (selfInitialised) return
        synchronized(this) {
            if (selfInitialised) return
            runCatching { WorkManager.initialize(app, Configuration.Builder().build()) }
            selfInitialised = true
        }
    }
}
