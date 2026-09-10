package com.field360.traker.sync

import com.field360.tracker.domain.model.ErrorCode
import com.field360.tracker.domain.model.LocationAccuracy
import com.field360.tracker.domain.model.PermissionTier
import com.field360.tracker.domain.model.ProviderState
import com.field360.tracker.domain.model.TrackerEvent
import com.field360.tracker.motion.DeviceSensors
import com.field360.tracker.motion.MotionQuality
import com.field360.traker.geo.port.Clock
import com.field360.traker.geo.port.TrackLogger
import com.field360.traker.sync.data.db.LogCounterRow
import com.field360.traker.sync.data.db.LogDao
import com.field360.traker.sync.data.db.LogEntryRow
import com.field360.traker.sync.data.db.seqKey
import com.field360.traker.sync.internal.isJsonStructure
import com.field360.traker.sync.internal.jsonObjectOrNull
import com.field360.traker.sync.internal.logEntryId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Writes the diagnostic buffer.
 *
 * Everything the SDK tells a host through `Tracker.events` is a live notification that
 * exists only while somebody is collecting it. A process the OEM killed mid-drive is
 * exactly the case those notifications would have explained, and exactly the case where
 * nobody was listening. This class is what makes them durable.
 *
 * ### What it does not record
 *
 * Accepted points (`TrackerEvent.Location`) and rejections
 * (`TrackerEvent.LocationRejected`) are deliberately dropped. The first is what the points
 * endpoint is for; the second already lives in the SDK's decision log and is read from
 * there at send time, because mirroring ~29 000 rows a shift into a second table is write
 * amplification, not diagnostics. `Heartbeat` is dropped for volume with nothing to say.
 *
 * ### Ordering, and why a channel
 *
 * [record] is called from a host's thread and from the event collector. It must never
 * block either, and must never lose the ordering that makes `seq` mean something, so
 * drafts go through a single-consumer channel: one writer, one sequence space per session
 * and type, no lock on the calling thread.
 *
 * The channel is bounded and drops its **oldest** on overflow, matching the ring below it.
 * A diagnostic buffer that applied backpressure to the tracker it is diagnosing would be a
 * worse bug than the ones it was added to find.
 */
internal class LogRecorder(
    private val dao: LogDao,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val logger: TrackLogger,
    /**
     * The open session, or `null`.
     *
     * A lambda rather than a value because the recorder outlives every session, and `null`
     * is a real answer here rather than a gap: a boot or a service start belongs to the
     * device, not to whichever session happened to be current when it was uploaded.
     */
    private val sessionId: () -> String?,
    /** `Tracker.events`. A flow rather than the tracker, so this class is testable. */
    private val events: SharedFlow<TrackerEvent>,
    /**
     * The device's motion hardware, or `null` where it cannot be probed.
     *
     * A lambda for the same reason [sessionId] is one: this class is exercised without an
     * Android graph behind it, and there is no `SensorManager` there to answer.
     */
    private val sensors: () -> DeviceSensors? = { null },
    /**
     * The device's location permissions and providers, or `null` when it cannot be read.
     *
     * A lambda for the same reason [sensors] is one, and read at write time rather than
     * cached: the whole value of this entry is that it says what was true at the moment the
     * start was attempted, and permission and the GPS switch both move while a process
     * lives.
     */
    private val providerState: () -> ProviderState? = { null },
    /**
     * Whether `ACTIVITY_RECOGNITION` is granted.
     *
     * Reported beside [sensors] because `DeviceSensors` folds the grant into its step
     * fields: `stepDetector` is false both on a device that has no step sensor and on one
     * that has it behind a denied permission, and those are two different tickets with two
     * different remedies.
     */
    private val activityRecognitionGranted: () -> Boolean = { false },
    /**
     * The open session, read from the store rather than from a cached state.
     *
     * [sessionId] is a `StateFlow` read, and that state is published by a **different**
     * collector of the same event flow this class attaches to — so at the instant
     * `EnabledChange(true)` arrives here, the id there can still name the previous session
     * or none at all. A motion line is only worth writing if it lands on the session it
     * describes, so it resolves the session itself.
     */
    private val openSessionId: suspend () -> String? = { sessionId() },
) {

    @Volatile
    private var config: LogSyncConfig? = null

    private val inbox = Channel<Draft>(
        capacity = INBOX_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private var pump: Job? = null
    private var collector: Job? = null

    /** Asks the uploader for a drain now. Set by `configureLogs`. */
    private var onNudge: (() -> Unit)? = null

    /** Monotonic, so a wall-clock change cannot make the cooldown look elapsed. */
    private var lastNudgeNanos: Long = UNSET_NANOS

    /** A drain already scheduled for the end of the current cooldown window. */
    private var pendingNudge: Job? = null

    /** Entries written since the last ring trim. */
    private var writtenSinceTrim = 0

    /**
     * The session whose motion hardware has already been recorded.
     *
     * One line per session, not per start signal: `EnabledChange(true)` also arrives when
     * capture is resumed inside a revived process, and the hardware has not changed since
     * the line already sitting at the head of that session.
     */
    private var motionRecordedFor: String? = null

    /**
     * The session whose location state has already been recorded.
     *
     * Only the *session* write is deduplicated. A failed start has no session to key on and
     * is always written: two refusals are two facts, and collapsing them would hide a user
     * tapping Start repeatedly against a permission that never got granted — which is the
     * exact shape of the reports this entry exists to answer.
     */
    private var locationRecordedFor: String? = null

    /** Serialises the check-then-write above; two start signals can race on it. */
    private val motionLock = Mutex()

    val isRecording: Boolean get() = config != null

    /**
     * Applies the endpoint's logging settings, starting the writer if it is not running.
     *
     * Idempotent, and safe to call on every `configureLogs`.
     */
    fun configure(active: LogSyncConfig, onNudge: () -> Unit) {
        config = active
        this.onNudge = onNudge
        start()
        attach()
        // For the host that calls `configureLogs()` *after* `start()`. The session-start
        // signal that normally carries these has already been and gone, and a session
        // missing its motion line is the one case this whole entry exists to cover.
        recordSessionMotion()
        recordSessionLocation()
    }

    /**
     * Stops recording. The buffer is **not** cleared — entries written before a host turned
     * the channel off still describe the period they were written in, and are still shipped
     * if the channel comes back.
     */
    fun stop() {
        config = null
        onNudge = null
        pump?.cancel()
        pump = null
        collector?.cancel()
        collector = null
        pendingNudge?.cancel()
        pendingNudge = null
    }

    /**
     * Queues one entry. Non-blocking, and a no-op when the level or type is not wanted.
     *
     * @param data JSON — an object or an array — as text, or `null`. Anything else is
     *   dropped rather than sent: the server's column is structured, and one malformed
     *   value would spoil a batch that also carried thirty useful entries.
     */
    fun record(
        level: LogLevel,
        type: LogType,
        tag: String,
        message: String,
        code: String? = null,
        data: String? = null,
    ) {
        submit(
            Draft(
                sessionId = sessionId(),
                timeMs = clock.wallTimeMs(),
                elapsedRealtimeNanos = clock.elapsedRealtimeNanos(),
                level = level,
                type = type,
                tag = tag,
                code = code,
                message = message,
                data = data,
            ),
        )
    }

    /**
     * The one gate every entry passes, whoever built the draft.
     *
     * The filters, the truncation and the hand-off live here rather than in [record] so
     * that a draft which pinned its own session and instant — the motion line below, and
     * every draft converted from a `TrackerEvent` — keeps them, instead of having the clock
     * and the current session re-read at queue time.
     */
    private fun submit(draft: Draft) {
        val active = config ?: return
        if (!draft.level.admits(active.level)) return
        if (draft.type !in active.types) return

        val payload = draft.data?.takeIf(::isJsonStructure)
        if (draft.data != null && payload == null) {
            sdkWarn {
                logger.w(TAG, "Dropped log data for \"${draft.tag}\": not a JSON object or array")
            }
        }

        // `seq` and the wire id are assigned by the pump, where the sequence space is
        // single-threaded. What travels here is everything else.
        inbox.trySend(
            draft.copy(
                tag = draft.tag.take(MAX_TAG_CHARS),
                code = draft.code?.take(MAX_CODE_CHARS),
                message = draft.message.take(MAX_MESSAGE_CHARS),
                data = payload,
            ),
        )
    }

    /** Records a session or service boundary. */
    fun lifecycle(phase: String, tag: String, message: String = phase) {
        record(
            level = LogLevel.INFO,
            type = LogType.LIFECYCLE,
            tag = tag,
            message = message,
            code = phase,
            data = jsonObjectOrNull(mapOf("phase" to phase)),
        )
    }

    /**
     * Writes the device's motion hardware at the head of the open session, once.
     *
     * ### Why the session and not the envelope
     *
     * The batch envelope names the phone — manufacturer, model, OS — and says nothing
     * about whether that phone can *detect a stop*. Motion gating is what decides the
     * capture cadence, and a device with no significant-motion sensor and no step detector
     * is rated [MotionQuality.POOR]: `ResolveConfigUseCase` forces `CONTINUOUS` on it, a
     * `DEGRADED` one has its stop timeout doubled, and both draw tracks that look wrong in
     * ways the points cannot explain. "The gaps are the hardware" is a one-line answer to a
     * ticket, and until this entry existed the server had no way to reach it.
     *
     * ### Once per session
     *
     * Sensors cannot appear or disappear while the process lives, so this is a per-session
     * constant rather than something to re-send per drain. The one field under it that
     * *can* move — the `ACTIVITY_RECOGNITION` grant, which gates the step sensors — has a
     * `PermissionChange` entry of its own already.
     *
     * A no-op when no session is open: an entry claiming to describe a session there would
     * be filed against the device, which is the one thing this is not.
     */
    fun recordSessionMotion() {
        if (config == null) return
        scope.launch {
            runCatching { writeSessionMotion() }
                .onFailure { sdkWarn { logger.w(TAG, "Motion probe failed: ${it.message}") } }
        }
    }

    private suspend fun writeSessionMotion() {
        motionLock.withLock {
            val session = openSessionId() ?: return
            if (session == motionRecordedFor) return
            val probe = sensors() ?: return
            // Set before the submit, not after: the filters below are a host's standing
            // decision about what it records, and a retry on the next start signal would
            // reach exactly the same one.
            motionRecordedFor = session
            submit(motionDraft(session, probe))
        }
    }

    /**
     * Writes the device's location permissions and providers.
     *
     * ### Why it is not conditional on the permission being granted
     *
     * The denied case is the one worth recording. A `start()` refused at the permission
     * gate returns before a session row exists, so `EnabledChange` never fires, the motion
     * line never runs, and the whole channel says nothing about the attempt — leaving "this
     * device never starts tracking" with no evidence behind it but the user's word.
     * [openSessionId] is allowed to be null here, and the entry is then filed against the
     * device, which the log API documents as legal and sometimes correct.
     *
     * ### Once per session, always per failure
     *
     * A granted start writes one line and the guard stops the repeats. A refused start has
     * no session to key on and writes every time, deliberately: three refusals in ten
     * seconds is a user tapping Start against a permission that was never granted, and that
     * pattern is the answer to the ticket.
     *
     * @param force `true` from a failed start, where there may be no session and the
     *   per-session guard must not apply.
     */
    fun recordSessionLocation(force: Boolean = false) {
        if (config == null) return
        scope.launch {
            runCatching { writeSessionLocation(force) }
                .onFailure { sdkWarn { logger.w(TAG, "Location probe failed: ${it.message}") } }
        }
    }

    private suspend fun writeSessionLocation(force: Boolean) {
        motionLock.withLock {
            val session = openSessionId()
            if (!force) {
                if (session == null) return
                if (session == locationRecordedFor) return
            }
            val state = providerState() ?: return
            if (session != null) locationRecordedFor = session
            submit(locationDraft(session, state))
        }
    }

    private fun locationDraft(session: String?, state: ProviderState): Draft {
        // Anything that would stop or degrade recording. WARN rather than INFO so it
        // survives the default `level = INFO` filter on a fleet — an entry about a device
        // that cannot track is worthless if the device that cannot track drops it.
        val blocking = state.permission != PermissionTier.FULL ||
            !state.locationServicesEnabled ||
            state.accuracyAuthorization != LocationAccuracy.PRECISE ||
            !state.gpsEnabled

        return Draft(
            sessionId = session,
            timeMs = clock.wallTimeMs(),
            elapsedRealtimeNanos = clock.elapsedRealtimeNanos(),
            level = if (blocking) LogLevel.WARN else LogLevel.INFO,
            type = LogType.LIFECYCLE,
            tag = TAG_PROVIDER,
            code = CODE_DEVICE_LOCATION,
            message = locationMessage(state),
            data = jsonObjectOrNull(
                mapOf(
                    "phase" to LifecyclePhase.DEVICE_LOCATION,
                    "permission" to state.permission.name,
                    "accuracy" to state.accuracyAuthorization.name,
                    "gps_enabled" to state.gpsEnabled,
                    "network_enabled" to state.networkEnabled,
                    "location_services_enabled" to state.locationServicesEnabled,
                    "fused_available" to state.fusedAvailable,
                    "power_save_mode" to state.powerSaveMode,
                    "airplane_mode" to state.airplaneMode,
                    // Says whether this describes a session that opened or an attempt that
                    // did not. Without it the two are indistinguishable on the server, and
                    // they are the two different tickets.
                    "session_opened" to (session != null),
                ),
            ),
        )
    }

    /** One human-readable line, so the reason is legible without opening `data`. */
    private fun locationMessage(state: ProviderState): String {
        val faults = buildList {
            when (state.permission) {
                PermissionTier.NONE -> add("no location permission")
                PermissionTier.FOREGROUND_ONLY -> add("foreground-only permission")
                PermissionTier.FULL -> Unit
            }
            if (state.accuracyAuthorization != LocationAccuracy.PRECISE) add("approximate only")
            if (!state.locationServicesEnabled) add("location services off")
            if (!state.gpsEnabled) add("GPS provider off")
            if (state.airplaneMode) add("airplane mode")
            if (state.powerSaveMode) add("power save on")
            if (!state.fusedAvailable) add("no fused provider")
        }

        return if (faults.isEmpty()) {
            "Location ready: ${state.permission.name}/${state.accuracyAuthorization.name}"
        } else {
            "Location degraded: ${faults.joinToString(", ")}"
        }
    }

    private fun motionDraft(session: String, sensors: DeviceSensors): Draft {
        val activityRecognition = activityRecognitionGranted()
        return Draft(
            sessionId = session,
            timeMs = clock.wallTimeMs(),
            elapsedRealtimeNanos = clock.elapsedRealtimeNanos(),
            // POOR only. DEGRADED is the ordinary state of a great deal of cheap hardware
            // and the SDK already compensates for it by doubling the stop timeout, so
            // warning on it would warn on a large part of a fleet and therefore on none of
            // it — the same reasoning `BackgroundRestrictions.degraded` is built on.
            level = if (sensors.motionQuality == MotionQuality.POOR) LogLevel.WARN else LogLevel.INFO,
            type = LogType.LIFECYCLE,
            tag = TAG_MOTION,
            code = CODE_DEVICE_MOTION,
            message = motionMessage(sensors, activityRecognition),
            data = jsonObjectOrNull(
                mapOf(
                    "phase" to LifecyclePhase.DEVICE_MOTION,
                    "motion_quality" to sensors.motionQuality.name,
                    "accelerometer" to sensors.accelerometer,
                    "gyroscope" to sensors.gyroscope,
                    "magnetometer" to sensors.magnetometer,
                    "significant_motion" to sensors.significantMotion,
                    "step_detector" to sensors.stepDetector,
                    "step_counter" to sensors.stepCounter,
                    "barometer" to sensors.barometer,
                    "rotation_vector" to sensors.rotationVector,
                    // Not derivable from the two step fields above: `SensorProbe` already
                    // folded this grant into them, so a false there could mean either no
                    // sensor or no permission.
                    "activity_recognition" to activityRecognition,
                ),
            ),
        )
    }

    /** The whole answer on one line, so the entry reads without opening `data`. */
    private fun motionMessage(sensors: DeviceSensors, activityRecognition: Boolean): String {
        val present = buildList {
            if (sensors.accelerometer) add("accelerometer")
            if (sensors.gyroscope) add("gyroscope")
            if (sensors.magnetometer) add("magnetometer")
            if (sensors.significantMotion) add("significant-motion")
            if (sensors.stepDetector) add("step-detector")
            if (sensors.stepCounter) add("step-counter")
            if (sensors.barometer) add("barometer")
            if (sensors.rotationVector) add("rotation-vector")
        }
        return "Motion hardware ${sensors.motionQuality}: " +
            (if (present.isEmpty()) "no motion sensors" else present.joinToString(", ")) +
            "; activity recognition ${if (activityRecognition) "granted" else "denied"}"
    }

    /**
     * The next sequence number for a session and type, allocated and persisted together.
     *
     * Persisted rather than derived from `MAX(seq)`, because the ring evicts rows: a
     * session whose entries had all been dropped would otherwise restart at 0 and replay
     * ids the server already holds.
     */
    suspend fun nextSeq(sessionId: String?, type: LogType): Long {
        val key = seqKey(sessionId, type.wireName)
        val next = (dao.counter(key) ?: 0L)
        dao.putCounter(LogCounterRow(key, next + 1))
        return next
    }

    private fun start() {
        if (pump != null) return
        pump = scope.launch {
            for (draft in inbox) {
                runCatching { write(draft) }
                    .onFailure { sdkWarn { logger.w(TAG, "Log write failed: ${it.message}") } }
            }
        }
    }

    private fun attach() {
        if (collector != null) return
        collector = scope.launch {
            events.collect { event ->
                event.toDraft()?.let(::submit)
                // Launched rather than awaited: resolving the session is a store read, and
                // a collector that blocks on one delays every entry queued behind it.
                if (event is TrackerEvent.EnabledChange && event.enabled) {
                    recordSessionMotion()
                    recordSessionLocation()
                }

                // The other half, and the half that has no session behind it. A start
                // refused at a gate emits one of these and returns — no session row, no
                // EnabledChange, and until now nothing in the channel describing the
                // device that refused. `force` because there is nothing to deduplicate
                // against and every refusal is worth having.
                if (event is TrackerEvent.Error && event.code in START_BLOCKING) {
                    recordSessionLocation(force = true)
                }
            }
        }
    }

    private suspend fun write(draft: Draft) {
        val seq = nextSeq(draft.sessionId, draft.type)
        val rowId = dao.insert(
            LogEntryRow(
                uid = logEntryId(
                    sessionId = draft.sessionId,
                    seq = seq,
                    type = draft.type.wireName,
                    elapsedRealtimeNanos = draft.elapsedRealtimeNanos,
                ),
                sessionId = draft.sessionId,
                seq = seq,
                timeMs = draft.timeMs,
                elapsedRealtimeNanos = draft.elapsedRealtimeNanos,
                level = draft.level.name,
                type = draft.type.name,
                tag = draft.tag,
                code = draft.code,
                message = draft.message,
                data = draft.data,
            ),
        )

        // Trimmed in blocks rather than per insert: the cap is a budget, and one DELETE
        // per recorded line is the write amplification this whole channel is careful about.
        if (++writtenSinceTrim >= RING_HEADROOM) {
            writtenSinceTrim = 0
            dao.trimTo(config?.bufferCapacity?.coerceAtLeast(1) ?: DEFAULT_CAPACITY)
        }

        // Only for a row that actually landed: a duplicate uid is ignored by the insert,
        // and nudging on one would let a repeated entry drive the radio.
        if (rowId > 0) considerNudge(draft.level)
    }

    /**
     * Asks for a prompt drain when an entry is severe enough to be worth one.
     *
     * The whole channel is otherwise a 15-minute heartbeat, which is right for the volume
     * and wrong for the entries somebody is actually waiting on: a GPS toggle, a permission
     * revocation, a capture suspension. Those are `WARN` or above, and they are the ones
     * this releases early.
     *
     * **A suppressed nudge is deferred, not dropped.** A real incident is rarely one entry —
     * location services off, then a provider change, then a capture suspension, all inside
     * two seconds. Firing on the first and discarding the rest would ship the first drain
     * and leave everything written after it waiting out the heartbeat, which is the failure
     * this method exists to remove. One deferral is outstanding at a time, so a cascade
     * costs two uploads rather than one per entry.
     *
     * Runs on the pump coroutine, which is single-threaded, so the two fields it touches
     * need no lock.
     */
    private fun considerNudge(level: LogLevel) {
        val active = config ?: return
        val threshold = active.nudgeLevel ?: return
        if (!level.admits(threshold)) return

        val now = clock.elapsedRealtimeNanos()
        if (lastNudgeNanos == UNSET_NANOS) {
            fireNudge(now)
            return
        }

        val sinceMs = (now - lastNudgeNanos) / NANOS_PER_MILLI
        if (sinceMs >= active.nudgeCooldownMs) {
            fireNudge(now)
            return
        }

        if (pendingNudge?.isActive == true) return
        val remainingMs = active.nudgeCooldownMs - sinceMs
        pendingNudge = scope.launch {
            delay(remainingMs)
            fireNudge(clock.elapsedRealtimeNanos())
        }
    }

    private fun fireNudge(atNanos: Long) {
        lastNudgeNanos = atNanos
        onNudge?.invoke()
    }

    /**
     * `null` for the events this channel deliberately does not carry — see the class KDoc.
     *
     * Levels are assigned by what a reader would do about the entry, not by how the SDK
     * feels about it: a permission revocation and a capture suspension are things somebody
     * has to act on, a provider toggle is the context for them.
     */
    private fun TrackerEvent.toDraft(): Draft? = when (this) {
        is TrackerEvent.Error -> draft(
            level = LogLevel.ERROR,
            tag = TAG_TRACKER,
            code = code.name,
            message = message,
            data = jsonObjectOrNull(mapOf("error_code" to code.name)),
        )

        is TrackerEvent.CaptureSuspended -> draft(
            level = LogLevel.WARN,
            tag = TAG_CAPTURE,
            code = reason.name,
            message = message,
            data = jsonObjectOrNull(mapOf("error_code" to reason.name)),
        )

        TrackerEvent.CaptureResumed -> draft(
            level = LogLevel.INFO,
            tag = TAG_CAPTURE,
            code = null,
            message = "Capture resumed",
            data = null,
        )

        is TrackerEvent.PermissionChange -> draft(
            level = LogLevel.WARN,
            tag = TAG_PERMISSION,
            code = current.name,
            message = "Permission $previous -> $current (accuracy $accuracy)",
            data = jsonObjectOrNull(
                mapOf(
                    "previous" to previous.name,
                    "current" to current.name,
                    "accuracy" to accuracy.name,
                ),
            ),
        )

        is TrackerEvent.LocationServicesChange -> draft(
            level = if (enabled) LogLevel.INFO else LogLevel.WARN,
            tag = TAG_PROVIDER,
            code = null,
            message = "Location services ${if (enabled) "available" else "unavailable"}",
            data = jsonObjectOrNull(
                mapOf(
                    "available" to enabled,
                    "gps" to state.gpsEnabled,
                    "network" to state.networkEnabled,
                    "enabled" to state.locationServicesEnabled,
                ),
            ),
        )

        is TrackerEvent.ProviderChange -> draft(
            // WARN when a provider or the master switch actually moved, INFO otherwise.
            //
            // `nudgeLevel` is WARN, so an INFO entry waits out the heartbeat — up to
            // `uploadIntervalMinutes`. That is right for a power-save or airplane flip and
            // wrong for a GPS toggle, which is one of the three things this channel exists
            // to deliver promptly. `LocationServicesChange` cannot carry it: that fires
            // only when the device stops being able to locate *at all*, so GPS switched off
            // while network positioning survives arrives here and nowhere else.
            level = if (providersMoved(previous, state)) LogLevel.WARN else LogLevel.INFO,
            tag = TAG_PROVIDER,
            code = providerTransition(previous, state),
            message = providerMessage(previous, state),
            data = jsonObjectOrNull(
                buildMap {
                    put("gps", state.gpsEnabled)
                    put("network", state.networkEnabled)
                    put("enabled", state.locationServicesEnabled)
                    put("permission", state.permission.name)
                    put("accuracy_authorization", state.accuracyAuthorization.name)
                    put("power_save", state.powerSaveMode)
                    put("airplane", state.airplaneMode)
                    put("fused_available", state.fusedAvailable)
                    // Only when there is a real transition to describe. Reading the entry
                    // otherwise means finding the previous row, which the ring may have
                    // evicted and a level filter may never have stored.
                    previous?.let {
                        put("previous_gps", it.gpsEnabled)
                        put("previous_network", it.networkEnabled)
                        put("previous_enabled", it.locationServicesEnabled)
                    }
                },
            ),
        )

        is TrackerEvent.PowerSaveChange -> draft(
            level = LogLevel.INFO,
            tag = TAG_PROVIDER,
            code = null,
            message = "Power save ${if (enabled) "on" else "off"}",
            data = jsonObjectOrNull(mapOf("power_save" to enabled)),
        )

        is TrackerEvent.IntegrityChange -> draft(
            level = if (report.blockingSignals.isEmpty()) LogLevel.INFO else LogLevel.WARN,
            tag = TAG_INTEGRITY,
            code = report.blockingSignals.firstOrNull()?.name,
            message = "Integrity flags ${report.flags}",
            data = jsonObjectOrNull(
                mapOf(
                    "flags" to report.flags,
                    "signals" to report.findings.joinToString(",") { it.signal.name },
                    "blocking" to report.blockingSignals.joinToString(",") { it.name },
                ),
            ),
        )

        is TrackerEvent.MotionChange -> draft(
            level = LogLevel.DEBUG,
            tag = TAG_MOTION,
            code = state.name,
            message = "Motion state $state",
            data = jsonObjectOrNull(mapOf("motion_state" to state.name)),
        )

        is TrackerEvent.ActivityChange -> draft(
            level = LogLevel.DEBUG,
            tag = TAG_MOTION,
            code = activity.name,
            message = "Activity $activity ($confidence%)",
            data = jsonObjectOrNull(mapOf("activity" to activity.name, "confidence" to confidence)),
        )

        is TrackerEvent.Diagnostic -> draft(
            level = LogLevel.INFO,
            tag = TAG_TRACKER,
            code = null,
            message = message,
            data = null,
        )

        // The two that are lifecycle boundaries rather than notifications. These are the
        // only place a session start or stop ever reaches the server.
        is TrackerEvent.EnabledChange -> draft(
            level = LogLevel.INFO,
            type = LogType.LIFECYCLE,
            tag = TAG_SESSION,
            code = if (enabled) LifecyclePhase.SESSION_START else LifecyclePhase.SESSION_STOP,
            message = if (enabled) "Session started" else "Session stopped",
            data = jsonObjectOrNull(
                mapOf(
                    "phase" to
                        if (enabled) LifecyclePhase.SESSION_START else LifecyclePhase.SESSION_STOP,
                ),
            ),
        )

        is TrackerEvent.SessionInterrupted -> draft(
            level = LogLevel.WARN,
            type = LogType.LIFECYCLE,
            tag = TAG_SESSION,
            code = LifecyclePhase.SESSION_INTERRUPTED,
            message = "Session ${session.id} interrupted",
            data = jsonObjectOrNull(
                mapOf(
                    "phase" to LifecyclePhase.SESSION_INTERRUPTED,
                    "session_id" to session.id,
                ),
            ),
        )

        is TrackerEvent.GeofenceEntered -> draft(
            level = LogLevel.INFO,
            tag = TAG_GEOFENCE,
            code = "ENTER",
            message = "Entered ${geofence.id}",
            data = jsonObjectOrNull(mapOf("geofence_id" to geofence.id, "transition" to "enter")),
        )

        is TrackerEvent.GeofenceExited -> draft(
            level = LogLevel.INFO,
            tag = TAG_GEOFENCE,
            code = "EXIT",
            message = "Exited ${geofence.id}",
            data = jsonObjectOrNull(mapOf("geofence_id" to geofence.id, "transition" to "exit")),
        )

        // Points, rejections, heartbeats, battery and licence answers — see the class KDoc.
        else -> null
    }

    /**
     * Whether a provider or the location master switch moved, as opposed to a power-save,
     * airplane, permission or accuracy field that has an event of its own already.
     *
     * `false` when [previous] is null — the first observation is a starting position, not a
     * transition, and reporting it as one would raise a WARN on every launch.
     */
    private fun providersMoved(previous: ProviderState?, state: ProviderState): Boolean {
        val was = previous ?: return false
        return was.gpsEnabled != state.gpsEnabled ||
            was.networkEnabled != state.networkEnabled ||
            was.locationServicesEnabled != state.locationServicesEnabled
    }

    /**
     * The transition as a filterable `code`, most significant first.
     *
     * The master switch outranks the providers because turning it off takes both of them
     * with it, and `LOCATION_OFF` is the honest name for that entry.
     */
    private fun providerTransition(previous: ProviderState?, state: ProviderState): String? {
        val was = previous ?: return null
        return when {
            was.locationServicesEnabled != state.locationServicesEnabled ->
                if (state.locationServicesEnabled) CODE_LOCATION_ON else CODE_LOCATION_OFF

            was.gpsEnabled != state.gpsEnabled ->
                if (state.gpsEnabled) CODE_GPS_ON else CODE_GPS_OFF

            was.networkEnabled != state.networkEnabled ->
                if (state.networkEnabled) CODE_NETWORK_ON else CODE_NETWORK_OFF

            else -> null
        }
    }

    /** Every provider that moved, so the line reads without opening `data`. */
    private fun providerMessage(previous: ProviderState?, state: ProviderState): String {
        val was = previous ?: return "Provider state observed"
        val moved = buildList {
            if (was.gpsEnabled != state.gpsEnabled) add("GPS ${onOff(state.gpsEnabled)}")
            if (was.networkEnabled != state.networkEnabled) {
                add("network positioning ${onOff(state.networkEnabled)}")
            }
            if (was.locationServicesEnabled != state.locationServicesEnabled) {
                add("location services ${onOff(state.locationServicesEnabled)}")
            }
        }
        return if (moved.isEmpty()) "Provider state changed" else moved.joinToString(", ")
    }

    private fun onOff(enabled: Boolean): String = if (enabled) "on" else "off"

    /** The clock is read here so every draft from one event carries one instant. */
    private fun draft(
        level: LogLevel,
        tag: String,
        code: String?,
        message: String,
        data: String?,
        type: LogType = LogType.EVENT,
    ) = Draft(
        sessionId = sessionId(),
        timeMs = clock.wallTimeMs(),
        elapsedRealtimeNanos = clock.elapsedRealtimeNanos(),
        level = level,
        type = type,
        tag = tag,
        code = code,
        message = message,
        data = data,
    )

    private data class Draft(
        val sessionId: String?,
        val timeMs: Long,
        val elapsedRealtimeNanos: Long,
        val level: LogLevel,
        val type: LogType,
        val tag: String,
        val code: String?,
        val message: String,
        val data: String?,
    )

    private companion object {
        const val TAG = "LogRecorder"
        const val TAG_TRACKER = "Tracker"
        const val TAG_CAPTURE = "CaptureGate"
        const val TAG_PERMISSION = "Permissions"
        const val TAG_PROVIDER = "ProviderState"
        const val TAG_INTEGRITY = "Integrity"
        const val TAG_MOTION = "Motion"
        const val TAG_SESSION = "Session"
        const val TAG_GEOFENCE = "Geofence"

        /** The session's motion-hardware line, as `code` — what a dashboard filters on. */
        const val CODE_DEVICE_MOTION = "DEVICE_MOTION"
        const val CODE_DEVICE_LOCATION = "DEVICE_LOCATION"

        /**
         * The errors that end a start attempt before a session exists.
         *
         * Each one gets a location snapshot filed against the device, because each one is a
         * report that reads "it never starts tracking on this phone" and has, until now,
         * arrived with nothing attached. `FGS_START_REFUSED` is in the list even though it
         * happens *after* the session opens: on the OEMs where it fires, what the reader
         * needs is the same permission-and-provider picture.
         */
        val START_BLOCKING = setOf(
            ErrorCode.PERMISSION_DENIED,
            ErrorCode.BACKGROUND_PERMISSION_MISSING,
            ErrorCode.COARSE_ONLY,
            ErrorCode.LOCATION_DISABLED,
            ErrorCode.PLAY_SERVICES_UNAVAILABLE,
            ErrorCode.FGS_START_REFUSED,
            ErrorCode.DEVICE_INTEGRITY_BLOCKED,
            ErrorCode.NOT_READY,
        )

        /** Provider transitions, as `code` — what a dashboard filters a toggle by. */
        const val CODE_GPS_ON = "GPS_ON"
        const val CODE_GPS_OFF = "GPS_OFF"
        const val CODE_NETWORK_ON = "NETWORK_ON"
        const val CODE_NETWORK_OFF = "NETWORK_OFF"
        const val CODE_LOCATION_ON = "LOCATION_ON"
        const val CODE_LOCATION_OFF = "LOCATION_OFF"

        const val INBOX_CAPACITY = 256
        const val UNSET_NANOS = Long.MIN_VALUE
        const val NANOS_PER_MILLI = 1_000_000L
        const val DEFAULT_CAPACITY = 5_000

        /**
         * How far past the configured capacity the ring may grow before it is trimmed.
         * The cap is a budget, not a fence.
         */
        const val RING_HEADROOM = 256

        /** The server truncates at these; truncating here saves shipping what it will cut. */
        const val MAX_MESSAGE_CHARS = 4_096
        const val MAX_TAG_CHARS = 64
        const val MAX_CODE_CHARS = 64
    }
}
