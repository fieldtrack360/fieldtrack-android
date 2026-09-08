package com.field360.traker.sync

import com.field360.traker.geo.model.FixDecision
import com.field360.traker.geo.port.Clock
import com.field360.traker.geo.port.TrackLogger
import com.field360.traker.geo.util.Uuids
import com.field360.traker.sync.data.db.LogCounterRow
import com.field360.traker.sync.data.db.LogDao
import com.field360.traker.sync.data.db.LogEntryRow
import com.field360.traker.sync.data.db.decisionWatermarkKey
import com.field360.traker.sync.internal.jsonParamOrNull
import com.field360.traker.sync.internal.logEntryId
import com.field360.traker.sync.internal.parseJsonOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement

/**
 * Ships the diagnostic buffer — [SyncQueue]'s counterpart for the log endpoint
 * (`docs/APP-LOG-API.md`).
 *
 * Same store-then-ship discipline, one deliberate inversion, and a hard boundary.
 *
 * ### The inversion: a rejected batch is dropped, not retried
 *
 * [SyncQueue] retries a 400 forever, because for a position the alternative is data loss.
 * Here the trade runs the other way: a permanently-unacceptable log batch would block this
 * buffer for the life of the install, burning battery to be told the same thing, to
 * preserve entries whose whole purpose was to explain a problem somebody has by now stopped
 * having. So a non-retryable 4xx — a `413` over the server's 500-entry ceiling included —
 * settles the batch and the drain moves on.
 *
 * ### The boundary: this class cannot touch a position
 *
 * It holds its own [LogDao] and two read-only lambdas over the tracker. There is no path from a 401
 * here to the point queue, to `Tracker.stop()`, or to any stored location — which is the
 * entire reason the two channels are configured, credentialed and queued apart. A 401 stops
 * log shipping and does nothing else.
 */
public class LogSyncQueue internal constructor(
    private val dao: LogDao,
    private val recorder: LogRecorder,
    /**
     * The session decisions are read for: the open one, or the most recent if none is open.
     *
     * A lambda rather than the `Tracker` itself, so this class can be exercised without an
     * Android graph behind it. `null` means the device has never recorded a session, in
     * which case there is nothing to convert.
     */
    private val activeSession: suspend () -> String?,
    /** `Tracker.getDecisions(sessionId, limit, offset)`, newest first. */
    private val decisions: suspend (String, Int, Int) -> List<FixDecision>,
    private val clock: Clock,
    private val logger: TrackLogger,
    private val appInfo: LogAppInfo,
    private val deviceInfo: LogDeviceInfo,
    private val onEvent: (SyncEvent) -> Unit = {},
) {

    private val mutex = Mutex()
    private val json = Json { encodeDefaults = true; explicitNulls = false }

    public sealed interface Result {
        public data class Shipped(val count: Int) : Result
        public data object Empty : Result

        /** @property retryAfterMs the server's own `Retry-After`, when it sent one. */
        public data class Retry(val reason: String, val retryAfterMs: Long? = null) : Result

        /**
         * The **log** endpoint refused the channel itself, rather than one batch.
         *
         * Two shapes: the credential was rejected (`401`, `403`), or there is no endpoint
         * at this URL (`404`, `405`, `501` — see `isEndpointAbsent`). Both are terminal for
         * this channel and inert for every other one, and both keep the buffer: the entries
         * are still the host's data, and a diagnostics endpoint that is missing or that
         * refuses a token is not a reason to destroy the diagnostics.
         *
         * Recovery is the next `configureLogs()`, which is an `Application.onCreate` for
         * most hosts — so a backend that deploys the route later is picked up on the next
         * process start with the buffer intact.
         */
        public data class Rejected(val statusCode: Int) : Result
    }

    /**
     * One pass over both sources.
     *
     * Recorded entries first, converted decisions second, and the order matters on a device
     * shipping both: an entry is what explains a gap, a decision is what fills one in, and a
     * truncated drain should leave the more explanatory half already delivered.
     */
    public suspend fun drain(config: LogSyncConfig, transport: SyncTransport): Result {
        if (!mutex.tryLock()) return Result.Retry(REASON_ALREADY_DRAINING)
        try {
            var shipped = 0

            when (val entries = drainEntries(config, transport)) {
                is Result.Shipped -> shipped += entries.count
                Result.Empty -> Unit
                else -> return entries
            }

            if (config.shipsDecisions) {
                when (val decisions = drainDecisions(config, transport)) {
                    is Result.Shipped -> shipped += decisions.count
                    Result.Empty -> Unit
                    else -> return decisions
                }
            }

            return if (shipped > 0) Result.Shipped(shipped) else Result.Empty
        } finally {
            mutex.unlock()
        }
    }

    /** Entries waiting. Decisions are counted only as "some or none", so they are excluded. */
    public suspend fun pendingCount(): Int = dao.pendingCount()

    /** Removes settled entries older than [olderThanMs]. Queued rows are left alone. */
    public suspend fun prune(olderThanMs: Long) {
        dao.pruneSettledOlderThan(clock.wallTimeMs() - olderThanMs)
    }

    /**
     * Moves the decision watermark to now, without sending anything.
     *
     * Called when a host turns decision shipping on. The pipeline writes its decision log
     * whether or not anyone uploads it, so without this the first drain after an opt-in
     * would ship days of history — tens of thousands of entries about drives nobody asked
     * to diagnose.
     */
    public suspend fun skipExistingDecisions() {
        val session = activeSessionId() ?: return
        val newest = decisions(session, 1, 0).firstOrNull() ?: return
        dao.putCounter(
            LogCounterRow(decisionWatermarkKey(session), newest.fix.elapsedRealtimeNanos),
        )
    }

    // ── the recorded buffer ─────────────────────────────────────────────────

    private suspend fun drainEntries(config: LogSyncConfig, transport: SyncTransport): Result {
        var shipped = 0
        repeat(MAX_BATCHES_PER_DRAIN) {
            val rows = dao.pending(config.batchSize)
            if (rows.isEmpty()) {
                return if (shipped > 0) Result.Shipped(shipped) else Result.Empty
            }
            val cursor = rows.last().id

            // Filtered here as well as at record time, because the two sets can disagree
            // across a re-`configureLogs` that narrowed them. An entry filtered out is
            // settled rather than left behind — it would otherwise sit at the head of every
            // future batch forever.
            val sendable = rows.mapNotNull { it.toRecord() }
                .filter { it.type in config.types && it.level.admits(config.level) }
            if (sendable.isEmpty()) {
                dao.markSettled(cursor)
                return@repeat
            }

            when (val outcome = send(sendable, config, transport)) {
                is Sent.Ok -> {
                    dao.markSettled(cursor)
                    shipped += sendable.size
                }
                is Sent.Drop -> {
                    dao.markSettled(cursor)
                    return@repeat
                }
                is Sent.Stop -> return outcome.result
            }
        }
        return if (shipped > 0) Result.Shipped(shipped) else Result.Empty
    }

    // ── the decision log, read from core and converted at send time ─────────

    /**
     * Converted rather than mirrored: at a 1 Hz cadence the decision log takes ~29 000 rows
     * per device per shift, and writing every one of them into this module's buffer as well
     * would be write amplification for a channel that is off on most devices.
     *
     * Scoped to one session — the open one, or the most recent if none is open — because
     * `Tracker.getDecisions` can only attribute a row to a session when it is asked for one.
     * That is also the session anybody is diagnosing.
     */
    private suspend fun drainDecisions(config: LogSyncConfig, transport: SyncTransport): Result {
        val session = activeSessionId() ?: return Result.Empty
        val watermarkKey = decisionWatermarkKey(session)
        val watermark = dao.counter(watermarkKey) ?: 0L

        val fresh = collectDecisionsAbove(session, watermark)
        if (fresh.isEmpty()) return Result.Empty

        var shipped = 0
        for (chunk in fresh.chunked(config.batchSize)) {
            val records = chunk.map { it.toRecord(session, recorder.nextSeq(session, LogType.DECISION)) }
            when (val outcome = send(records, config, transport)) {
                is Sent.Ok -> {
                    // The watermark advances per batch, not per drain: a failure halfway
                    // through a backlog must not re-ship what already landed.
                    dao.putCounter(
                        LogCounterRow(watermarkKey, chunk.last().fix.elapsedRealtimeNanos),
                    )
                    shipped += records.size
                }
                is Sent.Drop -> dao.putCounter(
                    LogCounterRow(watermarkKey, chunk.last().fix.elapsedRealtimeNanos),
                )
                is Sent.Stop -> return outcome.result
            }
        }
        return if (shipped > 0) Result.Shipped(shipped) else Result.Empty
    }

    /**
     * Everything newer than [watermark], oldest first.
     *
     * `getDecisions` pages newest-first, so this walks backwards until it meets a row the
     * watermark already covers. Bounded by [MAX_DECISION_SCAN] pages: a device that has been
     * offline for a week should ship what it can and move on, not spend the drain window
     * walking a table.
     */
    private suspend fun collectDecisionsAbove(session: String, watermark: Long): List<FixDecision> {
        val fresh = mutableListOf<FixDecision>()
        var offset = 0
        repeat(MAX_DECISION_SCAN) {
            val page = decisions(session, DECISION_PAGE, offset)
            if (page.isEmpty()) return fresh.asReversed()
            for (decision in page) {
                if (decision.fix.elapsedRealtimeNanos <= watermark) return fresh.asReversed()
                fresh += decision
            }
            offset += page.size
        }
        return fresh.asReversed()
    }

    private suspend fun activeSessionId(): String? =
        runCatching { activeSession() }.getOrNull()

    // ── the wire ────────────────────────────────────────────────────────────

    private sealed interface Sent {
        data object Ok : Sent
        data object Drop : Sent
        data class Stop(val result: Result) : Sent
    }

    private suspend fun send(
        records: List<LogRecord>,
        config: LogSyncConfig,
        transport: SyncTransport,
    ): Sent {
        val response = transport.upload(
            SyncRequest(
                url = config.url,
                method = config.method,
                headers = config.headers,
                jsonBody = encodeBody(records, config),
                gzip = config.gzipRequestBody,
                timeouts = config.timeouts,
            ),
        )

        onEvent(SyncEvent.HttpResponse(response.statusCode(), records.size))

        return when (response) {
            is SyncResponse.Success -> Sent.Ok

            SyncResponse.Unauthorized -> {
                sdkLog { logger.w(TAG, "401 on log upload; shipping stopped, buffer kept") }
                Sent.Stop(Result.Rejected(HTTP_UNAUTHORIZED))
            }

            SyncResponse.Forbidden -> {
                sdkLog { logger.w(TAG, "403 on log upload; shipping stopped, buffer kept") }
                Sent.Stop(Result.Rejected(HTTP_FORBIDDEN))
            }

            is SyncResponse.Failure -> when {
                // **Logging is optional.** A host whose backend never implemented this
                // endpoint must not pay for it: without this, the heartbeat wakes the radio
                // every `uploadIntervalMinutes` for the life of the install to be told 404
                // again, and drops a batch of the host's diagnostics each time it asks.
                //
                // So it halts the way a 401 does — buffer kept, points untouched, nothing
                // reaching `Tracker` — and the next `configureLogs()` tries again. Nothing
                // else about the SDK changes: capture, storage and the point queue never
                // see this.
                response.isEndpointAbsent() -> {
                    sdkLog {
                        logger.w(
                            TAG,
                            "No log endpoint at this URL (${response.code}); shipping " +
                                "stopped, buffer kept, positions unaffected",
                        )
                    }
                    Sent.Stop(Result.Rejected(response.code ?: HTTP_NOT_FOUND))
                }

                response.isPermanent() -> {
                    // The inversion in the class KDoc. Logged at warn because a silently
                    // discarded batch is exactly what makes a later "the logs are
                    // incomplete" impossible to explain.
                    sdkLog {
                        logger.w(
                            TAG,
                            "Dropping ${records.size} log entries: server answered " +
                                "${response.code} (${response.message})",
                        )
                    }
                    Sent.Drop
                }

                else -> {
                    sdkLog {
                        logger.w(TAG, "Log upload failed (${response.code}): ${response.message}")
                    }
                    Sent.Stop(Result.Retry(response.message, response.retryAfterMs))
                }
            }
        }
    }

    /**
     * The envelope, then the batch under `logs` — the same order and the same reasoning as
     * the points body: identity and auth wrapping the payload, not the other way round.
     */
    private fun encodeBody(records: List<LogRecord>, config: LogSyncConfig): String {
        val entries = records.map { it.toDto() }
        if (config.extraParams.isEmpty()) {
            return json.encodeToString(
                LogPayload(
                    device_id = config.deviceId,
                    uploaded_at = clock.wallTimeMs(),
                    app = appInfo,
                    device = deviceInfo,
                    logs = entries,
                ),
            )
        }

        return json.encodeToString(
            buildJsonObject {
                for ((key, value) in config.extraParams) {
                    put(key, jsonParamOrNull(value) ?: continue)
                }
                put("device_id", json.encodeToJsonElement(config.deviceId))
                put("uploaded_at", json.encodeToJsonElement(clock.wallTimeMs()))
                put("app", json.encodeToJsonElement(appInfo))
                put("device", json.encodeToJsonElement(deviceInfo))
                put("logs", json.encodeToJsonElement(ListSerializer(LogEntryDto.serializer()), entries))
            },
        )
    }

    private fun LogRecord.toDto() = LogEntryDto(
        id = id,
        session_id = sessionId,
        seq = seq,
        time = timeMs,
        // A string, not a number: a boot-relative nanosecond count passes 2^53 after 104
        // days of uptime, and every JavaScript backend that will read this rounds it
        // silently. The endpoint accepts both forms.
        elapsed_nanos = elapsedRealtimeNanos.toString(),
        level = level.wireName,
        type = type.wireName,
        tag = tag,
        code = code,
        message = message,
        data = data?.let(::parseJsonOrNull),
    )

    private fun LogEntryRow.toRecord(): LogRecord? {
        // A row written by a newer build must not be able to fail an older drain, so an
        // unknown member reads as its nearest safe neighbour rather than throwing.
        val parsedLevel = LogLevel.entries.firstOrNull { it.name == level } ?: LogLevel.INFO
        val parsedType = LogType.entries.firstOrNull { it.name == type } ?: LogType.MESSAGE
        return LogRecord(
            id = uid,
            sessionId = sessionId,
            seq = seq,
            timeMs = timeMs,
            elapsedRealtimeNanos = elapsedRealtimeNanos,
            level = parsedLevel,
            type = parsedType,
            tag = tag,
            code = code,
            message = message,
            data = data,
        )
    }

    /**
     * One judged fix as a log entry.
     *
     * `point_uuid` is the join that makes this worth storing: an `ACCEPT` links to the point
     * that reached the dashboard, and a `REJECT` is a point that never did, at a place where
     * the polyline draws a straight line instead. Derived here exactly as it was derived on
     * the way in — same session, same monotonic stamp, same digest — so the two agree.
     */
    private fun FixDecision.toRecord(session: String, seq: Long): LogRecord {
        val payload = buildJsonObject {
            put("verdict", json.encodeToJsonElement(if (isAccept) "ACCEPT" else "REJECT"))
            put("reason", json.encodeToJsonElement(reason))
            put("latitude", json.encodeToJsonElement(fix.latitude))
            put("longitude", json.encodeToJsonElement(fix.longitude))
            put("accuracy", json.encodeToJsonElement(fix.accuracy))
            put("bearing_deg", json.encodeToJsonElement(fix.bearingDeg))
            put("has_speed", json.encodeToJsonElement(fix.hasSpeed))
            put("has_bearing", json.encodeToJsonElement(fix.hasBearing))
            // Where the filter thought the device was. The pair that makes a rejection
            // readable: a fix 300 m from the estimate is a different story from one 8 m away
            // that still failed the gate.
            put("filter_lat", json.encodeToJsonElement(filterLat))
            put("filter_lng", json.encodeToJsonElement(filterLng))
            put("sigma", json.encodeToJsonElement(sigma))
            put("threshold", json.encodeToJsonElement(threshold))
            put("distance_moved_m", json.encodeToJsonElement(distanceMovedM))
            put("effective_speed_mps", json.encodeToJsonElement(effectiveSpeedMps))
            put("motion_state", json.encodeToJsonElement(motionState.name))
            if (isAccept) {
                put(
                    "point_uuid",
                    json.encodeToJsonElement(Uuids.forFix(session, fix.elapsedRealtimeNanos)),
                )
            }
        }

        return LogRecord(
            id = logEntryId(session, seq, LogType.DECISION.wireName, fix.elapsedRealtimeNanos),
            sessionId = session,
            seq = seq,
            timeMs = fix.timeMs,
            elapsedRealtimeNanos = fix.elapsedRealtimeNanos,
            // Always DEBUG, whatever the verdict. A rejection is not a warning — the
            // pipeline dropping a 300 m multipath excursion is it working — and levelling
            // these higher would bury real problems under 29 000 rows a shift.
            level = LogLevel.DEBUG,
            type = LogType.DECISION,
            tag = TAG_PIPELINE,
            code = reason,
            message = "${if (isAccept) "ACCEPT" else "REJECT"} — $reason",
            data = payload.toString(),
        )
    }

    /**
     * A 4xx that is not 401/403, and not one of the two that mean "again, later".
     *
     * 408 and 429 are the server asking for a retry in 4xx clothing; everything else in the
     * range is a statement about these bytes, and these bytes will not change. `413` — over
     * the endpoint's 500-entry ceiling — lands here on purpose.
     */
    /**
     * The URL answered, but this endpoint is not there: no route (`404`), a route that
     * refuses the verb (`405`), or a server saying it does not implement it (`501`).
     *
     * Deliberately separate from the other permanent codes. Those are statements about
     * *these bytes* — too many entries, a body the server dislikes — and are answered by
     * settling the batch and sending the next one, which is a different batch. These are
     * statements about the *endpoint*, and every later batch collects the same answer, so
     * the honest response is to stop asking rather than to drop the host's diagnostics one
     * batch at a time.
     *
     * `503` is **not** here: `AUTH_MODE=external` with no provider answers 503, and
     * `docs/APP-LOG-API.md` documents that as retryable.
     */
    private fun SyncResponse.Failure.isEndpointAbsent(): Boolean =
        code == HTTP_NOT_FOUND || code == HTTP_METHOD_NOT_ALLOWED || code == HTTP_NOT_IMPLEMENTED

    private fun SyncResponse.Failure.isPermanent(): Boolean {
        val status = code ?: return false // no HTTP exchange at all — a device problem
        return status in 400..499 && status != HTTP_TIMEOUT && status != HTTP_TOO_MANY_REQUESTS
    }

    private fun SyncResponse.statusCode(): Int? = when (this) {
        is SyncResponse.Success -> code
        SyncResponse.Unauthorized -> HTTP_UNAUTHORIZED
        SyncResponse.Forbidden -> HTTP_FORBIDDEN
        is SyncResponse.Failure -> code
    }

    internal companion object {
        internal const val REASON_ALREADY_DRAINING = "already draining"
        internal const val REASON_NOT_CONFIGURED = "log sync not configured"
        internal const val REASON_NO_TRANSPORT = "no transport"

        const val TAG = "LogSyncQueue"
        const val TAG_PIPELINE = "AcceptancePipeline"
        const val MAX_BATCHES_PER_DRAIN = 20
        const val MAX_DECISION_SCAN = 20
        const val DECISION_PAGE = 500
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
        const val HTTP_NOT_FOUND = 404
        const val HTTP_METHOD_NOT_ALLOWED = 405
        const val HTTP_TIMEOUT = 408
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val HTTP_NOT_IMPLEMENTED = 501
    }
}
