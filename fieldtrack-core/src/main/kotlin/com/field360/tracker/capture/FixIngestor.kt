package com.field360.tracker.capture

import com.field360.tracker.data.platform.BatteryReader
import com.field360.tracker.data.repository.RoomPointStore
import com.field360.tracker.domain.model.TrackerEvent
import com.field360.tracker.domain.model.TrackSession
import com.field360.traker.geo.filter.AcceptancePipeline
import com.field360.traker.geo.filter.ClockGuard
import com.field360.traker.geo.filter.TrackerConstants
import com.field360.traker.geo.model.Deferred
import com.field360.traker.geo.model.FilterState
import com.field360.traker.geo.model.IngestContext
import com.field360.traker.geo.model.MockPolicy
import com.field360.traker.geo.model.MotionState
import com.field360.traker.geo.model.PipelineResult
import com.field360.traker.geo.model.ProviderSnapshot
import com.field360.traker.geo.model.Reasons
import com.field360.traker.geo.model.TrackFix
import com.field360.traker.geo.model.TrackPoint
import com.field360.traker.geo.motion.CornerWindow
import com.field360.traker.geo.motion.TurnDetector
import com.field360.traker.geo.port.Clock
import com.field360.tracker.integrity.internal.IntegrityFeed
import com.field360.tracker.work.Watchdog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * One consumer, one filter state, no statics.
 *
 * This class is the structural answer to three audit findings at once. The reference
 * had two capture entry points — the tracking service and the 15-minute worker — that
 * derived `past` differently while mutating the *same* static filter through twelve
 * `@Volatile` fields (A3, A6). Its own comment conceded the compound updates were
 * non-atomic and rested on a "service & worker rarely overlap" assumption that fails
 * about once an hour by construction.
 *
 * Here every source — stream, one-shot, backstop, host insert — calls [offer], and a
 * single coroutine drains the channel. `past` and [FilterState] are fields of that one
 * consumer, so there is nothing to interleave (EC-52).
 */
internal class FixIngestor(
    private val store: RoomPointStore,
    private var pipeline: AcceptancePipeline,
    private var turnDetector: TurnDetector,
    private var constants: TrackerConstants,
    private val clock: Clock,
    private val watchdog: Watchdog,
    private val events: MutableSharedFlow<TrackerEvent>,
    private val liveTrack: LiveTrackFeed,
    private val battery: BatteryReader,
    private val integrityFeed: IntegrityFeed,
    /**
     * The current integrity bitmask, read once per fix. A lambda over
     * `IntegrityMonitor.flags` rather than the monitor itself: this class must not be able
     * to trigger a probe from the ingest path, and a field read is all it is allowed.
     */
    private val integrityFlags: () -> Int,
    /**
     * The packed location-subsystem snapshot, read once per fix. A lambda over
     * `ProviderStateMonitor.snapshotFlags` for the same reason [integrityFlags] is one: this
     * class must not be able to trigger a permission or Settings query from the ingest path,
     * and a field read is all it is allowed.
     */
    private val providerFlags: () -> Int = { ProviderSnapshot.NOT_RECORDED },
) {

    /**
     * The fix plus the facts only its *source* knows. The cadence tier is stamped here,
     * at offer time, and never sampled at consume time: a consume-time lookup would
     * mislabel every queued fix across a cadence flip and stamp one-shot/backstop/host
     * fixes with a tier they never had (SMOOTH-NAV-PLAN Phase 1).
     */
    private data class Queued(val fix: TrackFix, val cadenceTierMs: Long?)

    private val channel = Channel<Queued>(
        capacity = CHANNEL_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private var consumerJob: Job? = null
    private var state: FilterState = FilterState()
    private var past: TrackPoint? = null

    /**
     * Cumulative distance to carry when [past] has to be dropped at a reboot boundary.
     *
     * Normally the odometer rides on the last stored point, which is where the pipeline
     * reads it from. A reboot invalidates that point's clock but not its distance, so the
     * total is parked here for the one fix that has no predecessor (EC-92c).
     */
    private var odometerBaseMeters = 0.0
    private var session: TrackSession? = null
    private var lastElapsedNanos = 0L
    private var outOfOrderRun = 0
    private var turnState = TurnDetector.State()
    private var bursting = false

    /**
     * A heuristic-gate rejection whose fate the *next* fix decides (EC-45e).
     *
     * At most one, and only ever the immediately preceding fix — see [CornerWindow] for
     * why an older one can never be a corner's apex. While one is held, `state`, `past`,
     * the decision log and the live track all still describe the world as of the fix
     * before it: nothing is written for a fix under consideration, because the two
     * outcomes disagree about the filter state and writing either would commit to it.
     */
    private var heldAnchor: HeldAnchor? = null

    /**
     * @property rejected what the pipeline actually returned. Committed verbatim when the
     *   next fix shows no corner, so a drive with no turns is byte-identical to one
     *   recorded before this stage existed.
     * @property context the context the fix was *judged under* — kept because
     *   [RoomPointStore.recordRawPoint] writes the odometer and calendar from it, and both stop
     *   being true the moment `past` advances.
     */
    private data class HeldAnchor(
        val rejected: PipelineResult,
        val deferred: Deferred,
        val context: IngestContext,
    )

    var mockPolicy: MockPolicy = MockPolicy.FLAG
    var persistRawFixes: Boolean = false
    var rawRingCapacity: Int = 5_000
    var persistRawPoints: Boolean = false
    var rawPointCapacity: Int = 20_000
    var stepsSinceLastPoint: (() -> Int?)? = null

    /**
     * `MotionConfig.suppressWhileStationary`, wired to `StillnessMonitor.isStill` — or
     * `null` when the host left the stage off or the device has no accelerometer, which is
     * also what "the sensors have no opinion" means to the pipeline (EC-142).
     *
     * A lambda, like [stepsSinceLastPoint], and for the same reason: this class reads a
     * field the motion layer maintains, and must not be able to reach into that layer or
     * start anything there from the ingest path.
     */
    var stillnessVeto: (() -> Boolean)? = null

    /**
     * The motion layer's current state, for the decision log only.
     *
     * Pushed rather than pulled, and a plain field rather than a callback, because the one
     * thing it must not become is a dependency on `MotionController` — capture is never
     * gated on motion detection (EC-53). `FilterState.motionState` had no writer before
     * this, so every decision row in the database recorded the `STOPPED` default whatever
     * the device was doing.
     *
     * `@Volatile` for the same reason [gyroTurning] is: written from the motion layer's
     * consumer coroutine, read on the ingest one, and a stale read costs one row's label.
     */
    @Volatile
    var motionState: MotionState = MotionState.STOPPED

    /** `MotionConfig.bearingChangeCaptureDeg`; `0` disables turn capture (EC-45). */
    var bearingChangeCaptureDeg: Int = IngestContext.DEFAULT_BEARING_CHANGE_CAPTURE_DEG

    /** `MotionConfig.cornerAnchorCapture`; `false` restores pre-EC-45e behaviour exactly. */
    var cornerAnchorCapture: Boolean = true

    /**
     * Whether the gyroscope currently says the vehicle is turning, pushed by
     * [com.field360.tracker.motion.GyroTurnMonitor] (EC-45d).
     *
     * A pushed flag rather than a read of the monitor, so this class keeps depending on
     * nothing in the motion layer, and `false` on a device with no gyroscope leaves the
     * corner window on its geometric test alone.
     *
     * `@Volatile` because it is written from the sensor's thread and read on the ingest
     * coroutine. A stale read costs one fix's worth of hint on a decision that has a
     * geometric fallback, so the two do not need to be ordered against each other.
     */
    @Volatile
    var gyroTurning: Boolean = false

    /**
     * Set by the motion layer. A callback rather than a direct dependency so the
     * ingestor stays unaware of motion detection — capture must never be gated on it
     * (EC-53).
     */
    var onAcceptedPoint: ((TrackPoint) -> Unit)? = null

    /**
     * Set by the capture layer. Fires only on a change, so the stream controller sees one
     * call per cadence flip rather than one per fix (EC-45).
     *
     * A callback rather than a direct dependency for a structural reason, not a stylistic
     * one: `LocationStreamController` already depends on this class, so injecting it back
     * would be a cycle.
     */
    var onTurnBurst: ((Boolean) -> Unit)? = null

    /**
     * Every raw fix's observed speed, m/s — `TurnDetector`'s reconciliation of Doppler
     * against displacement, before the pipeline has judged anything.
     *
     * Fires on **every** fix, unlike [onTurnBurst], because its consumer
     * ([com.field360.tracker.motion.GyroTurnMonitor]) is deciding whether a gyroscope
     * should be registered at all, and a device that stops producing qualifying speeds is
     * exactly the case it must notice. Raw rather than accepted for the same reason turn
     * detection is: a junction can produce a run of rejects, and closing the sensor there
     * would close it in the one place it earns its power budget.
     */
    var onObservedSpeed: ((Float) -> Unit)? = null

    /**
     * True while a consumer is live, i.e. points are actively being written.
     *
     * This is what separates "start() called twice" from "a session was left open by a
     * crash". The first must stay idempotent (EC-72); the second must not silently
     * adopt a stale session — see [com.field360.tracker.domain.usecase.StartTrackingUseCase].
     */
    val isRunning: Boolean get() = consumerJob?.isActive == true

    /**
     * Non-blocking. Safe to call from a callback, a worker or the host.
     *
     * @param cadenceTierMs the request interval of the stream that produced this fix.
     *   Only the stream collector passes it — one-shot, backstop and host inserts leave
     *   it `null`, which is the `IngestContext.cadenceTierMs` contract: `null` means
     *   "not a stream fix", never "unknown tier" (SMOOTH-NAV-PLAN Phase 1).
     */
    fun offer(fix: TrackFix, cadenceTierMs: Long? = null) {
        channel.trySend(Queued(fix, cadenceTierMs))
    }

    /**
     * Swaps in engine constants derived from the host's config — the accuracy meter
     * (`GeolocationConfig.accuracy`) is the only thing that moves them today.
     *
     * The pipeline and the turn detector are replaced rather than mutated because both are
     * pure by contract: a fixture replayed twice must produce a byte-identical decision
     * sequence, and a constant that can change underneath a running `accept()` would make
     * that false. New instances make the swap atomic from the consumer's point of view.
     *
     * **Call before [start], never during a session.** `StartTrackingUseCase` is the only
     * caller and does exactly that; retuning mid-session would judge the second half of a
     * track by a different bar than the first, with nothing in the decision log to say so.
     */
    fun retune(constants: TrackerConstants) {
        if (this.constants == constants) return
        this.constants = constants
        pipeline = AcceptancePipeline(constants)
        turnDetector = TurnDetector(constants)
    }

    suspend fun start(session: TrackSession, scope: CoroutineScope) {
        this.session = session
        store.currentSessionId = session.id
        // Restored BEFORE the first fix is processed. Not optional — this is what stops
        // a post-process-death fix being blind-accepted wherever it lands (A2, EC-51).
        state = store.loadFilterState() ?: FilterState()
        past = store.lastPoint(session.id)
        // Seeded from the restored state, NOT zeroed (EC-92b). `ClockGuard.classify`
        // reads `0` as "nothing seen yet" and answers FORWARD unconditionally, so
        // starting at zero hides the one rewind that matters most: the state on disk
        // was written before a reboot and its clock anchor belongs to a timeline that
        // no longer exists. The burst gate then measures every fix against an anchor
        // hours in its own future, rejects on the negative delta, and — because only
        // an ACCEPT advances that anchor — never recovers. A field capture lost an
        // entire 25-minute session that way: 290 fixes, 290 `Burst` rejects, 0 points.
        lastElapsedNanos = state.lastFixElapsedNanos
        odometerBaseMeters = 0.0
        outOfOrderRun = 0
        // Deliberately NOT restored from storage. The heading held here belongs to a leg
        // that ended whenever the last session did; a new drive out of the same car park
        // would otherwise inherit it and read the first straight as a turn.
        turnState = turnDetector.reset()
        bursting = false
        // A hold belongs to the session it was taken in. Carrying one across would judge
        // it against a `past` from a different drive (EC-45e).
        heldAnchor = null
        // Seeded before the consumer launches, so the engine exists before the first
        // fix and is only ever touched from the single consumer afterwards.
        liveTrack.start(session.id)

        consumerJob?.cancel()
        consumerJob = scope.launch {
            for (queued in channel) consume(queued)
        }
    }

    /** Channel is closed and drained *before* teardown, so no point outlives stop() (EC-73). */
    fun stop() {
        consumerJob?.cancel()
        consumerJob = null
        session = null
        store.currentSessionId = null
        liveTrack.stop()
    }

    private suspend fun consume(queued: Queued) {
        val fix = queued.fix
        val active = session ?: return // EC-73: a late fix after stop() is simply dropped.

        // Liveness and the raw-fix layer are stamped BEFORE anything can drop this fix.
        // The OS delivered it, so it is evidence the provider is alive whatever we go on
        // to decide; judging liveness on accepted points instead would make the watchdog
        // fire constantly on a parked user, who by design stores nothing (EC-70). Layer 1
        // of the debug overlay (spec §8.4) is the *unfiltered* truth, and a reordered
        // delivery is part of that truth — it is exactly what you go looking for when
        // diagnosing this.
        watchdog.onRawFix(fix)
        // Before any gate: a mock fix that MockPolicy.REJECT is about to drop is exactly
        // the fix the integrity layer needs to have seen, and a session that stored nothing
        // because every fix was fake must still be able to say so.
        integrityFeed.onFix(fix)
        val integrity = integrityFlags()
        if (persistRawFixes) store.recordRawFix(fix, active.id, rawRingCapacity, integrity)

        when (ClockGuard.classify(lastElapsedNanos, fix.elapsedRealtimeNanos, outOfOrderRun, constants)) {
            ClockGuard.Step.OUT_OF_ORDER -> {
                // Two batched deliveries interleaved. The pipeline has already moved past
                // this instant, so feeding it would mean a negative Δt everywhere — the
                // case AcceptancePipeline documents as unreachable by construction (A1,
                // EC-42), and it is this guard that makes that true. Drop it and keep the
                // held timestamp, which is the newer of the two.
                outOfOrderRun++
                events.emit(TrackerEvent.Diagnostic(Reasons.OUT_OF_ORDER))
                return
            }

            // EC-29 / EC-92: elapsedRealtimeNanos restarts at reboot, so everything the
            // filter holds — position, velocity, origin, captured heading — describes a
            // timeline that no longer exists. Start over rather than feed it a huge
            // negative delta.
            ClockGuard.Step.REBOOT -> {
                // Dropped, not resolved. The held fix and this one sit on two different
                // monotonic timelines, so the geometry between them is meaningless and the
                // filter state it carries describes a timeline that no longer exists. It
                // reverts to the rejection the gate already gave it.
                heldAnchor = null
                state = FilterState()
                // The stored anchor is DROPPED, not reloaded (EC-92c). Its
                // `elapsedRealtimeNanos` was written on a clock that no longer exists, so
                // judging this fix against it yields a negative Δt everywhere — the case
                // AcceptancePipeline documents as unreachable by construction (A1, EC-42),
                // and this branch is that construction. Reloading it instead put the
                // filter's resume path on a timeline it could not measure.
                //
                // The odometer survives the boundary: distance already travelled is still
                // distance travelled, and it is the one fact on the stored point that does
                // not depend on the clock. Only the gap across the reboot is unmeasurable,
                // and that gap is not counted — which is honest, since the device was off.
                odometerBaseMeters = past?.odometerMeters ?: odometerBaseMeters
                past = null
                // Keyed on the monotonic clock that just restarted, so its deadline is
                // now in the far future and the burst would never expire.
                turnState = turnDetector.reset()
                outOfOrderRun = 0
                lastElapsedNanos = fix.elapsedRealtimeNanos
                events.emit(TrackerEvent.Diagnostic(Reasons.REBOOT_BOUNDARY))
            }

            ClockGuard.Step.FORWARD -> {
                outOfOrderRun = 0
                lastElapsedNanos = fix.elapsedRealtimeNanos
            }
        }

        // Turn detection runs on the RAW fix, before the pipeline and regardless of its
        // verdict. A vehicle rounding a bend is turning whether or not each sample was
        // worth storing, and gating cadence on accepted points would mean the samples the
        // burst exists to take are the ones that decide whether to take them (EC-45).
        updateTurnBurst(fix)

        // Settle the previous fix before judging this one. Order is the whole safety
        // argument: the held fix's two possible filter states diverge from the moment it
        // was judged, so the choice between them has to be made before anything is
        // measured against either (EC-45e).
        resolveHeldAnchor(fix)

        val context = contextFor(active, queued.cadenceTierMs, integrity)
        val result = pipeline.accept(fix, past, state, context)

        val deferred = result.deferred
        if (deferred != null) {
            heldAnchor = HeldAnchor(rejected = result, deferred = deferred, context = context)
            return
        }
        commit(result, context)
    }

    /**
     * Commits one pipeline outcome: filter state, decision log, raw-point row, live track,
     * and — on an accept — the stored point and the events that announce it.
     *
     * Extracted so the deferred branch commits through exactly the same path as an
     * ordinary accept. A second, parallel write path is how the two would drift apart, and
     * the contract this stage rests on is that adopting the deferred branch is
     * indistinguishable from the gate having accepted the fix outright.
     */
    private suspend fun commit(result: PipelineResult, context: IngestContext) {
        state = result.state

        store.saveFilterState(state)
        store.recordDecision(result.decision)
        // Written for every verdict, and written from `context` — the odometer and
        // calendar it carries are the ones this fix was judged under, which stops being
        // true the moment `past` advances below.
        if (persistRawPoints) {
            store.recordRawPoint(result.decision, context, result.point, rawPointCapacity)
        }
        // In-memory only — the live surface adds no DB writes to the per-fix path.
        liveTrack.onFix(state, result.point)

        val point = result.point
        if (point != null) {
            store.insert(point)
            past = point
            onAcceptedPoint?.invoke(point)
            events.emit(TrackerEvent.Location(point))
        } else {
            events.emit(TrackerEvent.LocationRejected(result.decision))
        }
    }

    /**
     * Decides the held fix's fate now that [next] exists, and commits one outcome or the
     * other (EC-45e).
     *
     * [next] is used as evidence only — it is not judged here, and it is judged normally
     * by the caller immediately afterwards against whatever `past` this leaves behind.
     *
     * A session that stops with a fix still held simply drops it, which is the rejection
     * the heuristic gate had already given it. The only thing lost is that fix's row in
     * the decision log, at most once per session, for a fix that stored nothing.
     */
    private suspend fun resolveHeldAnchor(next: TrackFix) {
        val held = heldAnchor ?: return
        heldAnchor = null

        val anchor = past?.let { previous ->
            CornerWindow.isCornerAnchor(
                past = previous,
                held = held.deferred.point,
                current = next,
                // Either detector saying so is enough: both measure the turn itself —
                // one from heading between fixes, one from yaw rate — rather than
                // inferring it from three positions the way the geometric test must.
                turnActive = bursting || gyroTurning,
                minTurnDeg = bearingChangeCaptureDeg,
                c = constants,
            )
        } ?: false

        if (anchor) {
            commit(
                PipelineResult(
                    decision = held.deferred.decision,
                    state = held.deferred.state,
                    point = held.deferred.point,
                ),
                held.context,
            )
        } else {
            commit(held.rejected, held.context)
        }
    }

    private fun updateTurnBurst(fix: TrackFix) {
        val result = turnDetector.onFix(turnState, fix)
        turnState = result.state

        // Unconditional, and ahead of the change guard below: the gyroscope's registration
        // window is refreshed by speed, not by a turn, and a drive down a straight road
        // produces no turn events at all.
        onObservedSpeed?.invoke(result.speedMps)

        if (result.isBursting == bursting) return

        bursting = result.isBursting
        onTurnBurst?.invoke(result.isBursting)
    }

    private fun contextFor(
        session: TrackSession,
        cadenceTierMs: Long?,
        integrityFlags: Int,
    ): IngestContext {
        val zone = ZoneId.systemDefault()
        // Cached behind a one-minute TTL, so this is a field read on all but one fix a
        // minute. Read for every verdict, not only accepted ones: a rejected fix's raw-point
        // row is exactly where "the phone was at 4 %" belongs (§11.1, G-2).
        val power = battery.read()
        return IngestContext(
            sessionId = session.id,
            // Resolved per fix: a session can cross time zones on a flight (EC-89).
            timezone = zone.id,
            localDate = DATE_FORMAT.withZone(zone).format(Instant.ofEpochMilli(clock.wallTimeMs())),
            mockPolicy = mockPolicy,
            odometerMeters = past?.odometerMeters ?: odometerBaseMeters,
            stepsSinceLastPoint = stepsSinceLastPoint?.invoke(),
            // Read once per fix, like the steps above, and for the same interval: both
            // witnesses answer "since the last stored point", so the pipeline can hold them
            // against each other (EC-142). Null when the stage is off — the pipeline's
            // default, and byte-identical to every fixture recorded before it existed.
            stillnessVeto = stillnessVeto?.invoke() ?: false,
            motionState = motionState,
            bearingChangeCaptureDeg = bearingChangeCaptureDeg,
            cornerAnchorCapture = cornerAnchorCapture,
            cadenceTierMs = cadenceTierMs,
            batteryPct = power.percent,
            isCharging = power.isCharging,
            integrityFlags = integrityFlags,
            // Read for every verdict, like the battery above: a rejected fix's raw-point row
            // is exactly where "location was on but permission had dropped to coarse"
            // belongs, and that is the row a gap investigation starts from.
            providerFlags = providerFlags(),
        )
    }

    private companion object {
        const val CHANNEL_CAPACITY = 256
        val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    }
}
