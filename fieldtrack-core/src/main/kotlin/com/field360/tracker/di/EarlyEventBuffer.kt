package com.field360.tracker.di

import com.field360.tracker.domain.model.TrackerEvent
import com.field360.traker.geo.port.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Holds the events a process emits before anybody is listening, for the one consumer that
 * needs them afterwards: the diagnostic log in `fieldtrack-sync`.
 *
 * ### The gap this closes
 *
 * `TrackerGraph.events` is `replay = 0`, deliberately — a host collecting from a screen
 * must not be handed a stale `Error` from an hour ago. The cost is that an event emitted
 * with no subscriber is gone. In a process the OS rebuilt to restart the service, the
 * service's supervision runs `ResumeCaptureUseCase` before the host's `Application` has
 * configured the log channel, so "capture resumed … after the process was killed", the
 * restrictions line and `EnabledChange(true)` were all emitted into nothing. The field
 * logs then showed capture coming back with no entry saying so, and the kill it recovered
 * from was invisible.
 *
 * ### Why a buffer beside the flow, not replay on it
 *
 * Replay would change what every host collector receives. This subscribes once, when the
 * flow is created, keeps a small window of the process's opening events, and hands them
 * over exactly once through [claim]. Host collectors see nothing different.
 *
 * Bounded twice: [capacity] entries (oldest dropped) and [windowMs] after creation, after
 * which it stops collecting. An app that never configures the log channel pays for one
 * short-lived collector and a few dozen references.
 */
internal class EarlyEventBuffer(
    private val clock: Clock,
    private val capacity: Int = DEFAULT_CAPACITY,
    private val windowMs: Long = DEFAULT_WINDOW_MS,
) {

    /** One held event and the instant it was emitted, not the instant it was claimed. */
    class Entry(
        val event: TrackerEvent,
        val wallTimeMs: Long,
        val elapsedRealtimeNanos: Long,
    )

    private val lock = Any()
    private val entries = ArrayDeque<Entry>()
    private var claimed = false
    private var collector: Job? = null

    /**
     * Subscribes to [events]. Must be called as the flow is created, before anything can
     * emit into it — `UNDISPATCHED` so the subscription exists by the time this returns.
     */
    fun attach(events: SharedFlow<TrackerEvent>, scope: CoroutineScope) {
        collector = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            withTimeoutOrNull(windowMs) {
                events.collect { hold(it) }
            }
        }
    }

    /**
     * Everything held so far, oldest first — once. Later calls return an empty list, and
     * the buffer stops collecting.
     */
    fun claim(): List<Entry> {
        val out = synchronized(lock) {
            if (claimed) return emptyList()
            claimed = true
            entries.toList().also { entries.clear() }
        }
        collector?.cancel()
        collector = null
        return out
    }

    private fun hold(event: TrackerEvent) {
        if (!isWorthHolding(event)) return
        val entry = Entry(event, clock.wallTimeMs(), clock.elapsedRealtimeNanos())
        synchronized(lock) {
            if (claimed) return
            if (entries.size >= capacity) entries.removeFirst()
            entries.addLast(entry)
        }
    }

    internal companion object {
        const val DEFAULT_CAPACITY = 64
        const val DEFAULT_WINDOW_MS = 10 * 60_000L

        /**
         * The per-fix and periodic events are left out: the log channel does not record
         * them either, and at one fix every few seconds they would push the lifecycle
         * events this exists for out of [capacity] within minutes.
         */
        fun isWorthHolding(event: TrackerEvent): Boolean = when (event) {
            is TrackerEvent.Location,
            is TrackerEvent.LocationRejected,
            is TrackerEvent.Heartbeat,
            is TrackerEvent.BatteryChange,
            -> false
            else -> true
        }
    }
}
