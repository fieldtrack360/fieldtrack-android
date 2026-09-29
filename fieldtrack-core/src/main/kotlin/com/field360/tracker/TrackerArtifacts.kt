package com.field360.tracker

import android.content.Context
import com.field360.tracker.di.TrackerGraph
import com.field360.tracker.domain.model.TrackerEvent
import com.field360.tracker.domain.repository.PendingUploadStore
import com.field360.tracker.domain.repository.SyncTrigger
import com.field360.traker.geo.port.Clock
import com.field360.traker.geo.port.TrackLogger

/**
 * The wiring seam Tracker's own optional artifacts build against.
 *
 * `fieldtrack-sync` needs the same clock, the same logger and the same upload queue the
 * capture side is using — not a second set. Under Hilt that was a `@EntryPoint`
 * interface; with the graph wired by hand it is this, and the reason it exists is
 * identical: `TrackerGraph` is `internal` to `fieldtrack-core`, so a sibling artifact in a
 * different Gradle module cannot see it.
 *
 * **Not a host-facing API.** Nothing here is needed to use the SDK — a host wants
 * [Tracker.getInstance] and nothing else on this page. It is `public` only because
 * Kotlin has no "visible to my other modules" visibility, and every member is a type the
 * public surface already exposes, so it widens nothing that was not already reachable.
 */
public class TrackerArtifacts private constructor(private val graph: TrackerGraph) {

    public val trackIt: Tracker get() = graph.trackIt

    public val clock: Clock get() = graph.clock

    public val logger: TrackLogger get() = graph.logger

    /** The one door `fieldtrack-sync` uploads through. */
    public val pendingUploads: PendingUploadStore get() = graph.pendingUploads

    /**
     * The door in the other direction.
     *
     * Core knows when a point was stored and when a queue has gone stale; it has no idea
     * what uploading one would mean. `fieldtrack-sync` registers a [SyncTrigger] here when
     * `SyncConfig.autoSync` is on and clears it when the configuration goes away, which is
     * what finally makes that flag mean something (GAPS.md G-4).
     *
     * Null clears it. Registering twice replaces — there is one uploader per process, and
     * a second registrant would otherwise double every request.
     */
    public fun registerSyncTrigger(trigger: SyncTrigger?) {
        graph.syncScheduler.register(trigger)
    }

    /**
     * [TrackerConfig.baseUrl] as `ready()` resolved it, or `null`.
     *
     * Core stores it and never reads it — this is the one door it leaves for the module that
     * does. Returns `null` before `ready()` has run, which is why `fieldtrack-sync` treats it as
     * a fallback and reports a missing endpoint rather than waiting for one.
     */
    public val baseUrl: String? get() = graph.configStore.cached?.baseUrl

    /**
     * Hands [consumer] the events this process emitted before anything collected
     * [Tracker.events], oldest first, each with the instant it was emitted — **once per
     * process**; later calls deliver nothing.
     *
     * For `fieldtrack-sync`'s diagnostic log. `Tracker.events` does not replay, so in a
     * process revived to restart capture, the entries that say so are emitted before the
     * host has configured the log channel. This is where they wait. Per-fix, heartbeat and
     * battery events are not held.
     */
    public fun drainEarlyEvents(
        consumer: (event: TrackerEvent, wallTimeMs: Long, elapsedRealtimeNanos: Long) -> Unit,
    ) {
        graph.earlyEvents.claim().forEach {
            consumer(it.event, it.wallTimeMs, it.elapsedRealtimeNanos)
        }
    }

    public companion object {
        /** Same process-wide graph [Tracker.getInstance] returns from. */
        @JvmStatic
        public fun of(context: Context): TrackerArtifacts =
            TrackerArtifacts(TrackerGraph.get(context))
    }
}
