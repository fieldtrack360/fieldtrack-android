package com.field360.traker.sync

import com.field360.tracker.domain.repository.PendingUploadStore
import com.field360.traker.geo.model.MovementStatus
import com.field360.traker.geo.model.ProviderSnapshot
import com.field360.traker.geo.model.TrackPoint
import com.field360.tracker.integrity.IntegritySignal
import com.field360.traker.geo.port.Clock
import com.field360.traker.geo.port.TrackLogger
import com.field360.traker.sync.internal.jsonParamOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement

/**
 * Drains the upload queue.
 *
 * Store-then-sync, never sync-then-store: a point is durable in Room before anything is
 * attempted, so a failed upload costs nothing and a dead network costs nothing. Rows are
 * marked synced **only** on a confirmed success — the default state is "still queued",
 * which is the safe direction.
 *
 * A parked user uploads nothing by design, because the filter stores nothing. **Absence
 * of uploads is not evidence of a dead tracker** — that is what the raw-fix watchdog
 * clock is for (EC-70).
 */
public class SyncQueue internal constructor(
    private val store: PendingUploadStore,
    private val clock: Clock,
    private val logger: TrackLogger,
    /**
     * Where per-exchange events go. A plain lambda rather than a flow because the queue
     * should not own the buffering policy — [TrackerSync] does, and it is the thing hosts
     * collect from.
     */
    private val onEvent: (SyncEvent) -> Unit = {},
) {

    private val mutex = Mutex()
    private val json = Json { encodeDefaults = true; explicitNulls = false }

    public sealed interface Result {
        public data class Uploaded(val count: Int) : Result
        public data object Empty : Result

        /**
         * @property retryAfterMs the server's own `Retry-After`, when it sent one. Null means
         *   it had no opinion and the SDK's backoff applies.
         */
        public data class Retry(val reason: String, val retryAfterMs: Long? = null) : Result

        /** Terminal — the caller must tear the session down, not retry (spec §3.3). */
        public data object AuthExpired : Result

        /**
         * Terminal, and **not** [AuthExpired].
         *
         * A 401 says the credential is gone, so the queued rows belong to a user who can no
         * longer own them and clearing them is right. A 403 says this credential may not
         * write this resource — a scope, a rotated key, a server-side permission bug. Those
         * rows are still the host's data and are still valid; throwing them away to fix a
         * permissions mistake would be the more expensive of the two errors.
         *
         * So this stops the retry loop — which is the battery burn worth stopping — and
         * leaves every row queued for a re-`configure()` with a working credential.
         */
        public data object Forbidden : Result
    }

    public suspend fun drain(config: SyncConfig, transport: SyncTransport): Result {
        // One drain at a time. A scheduled retry and a manual syncNow() colliding would
        // upload the same rows twice; the server would dedupe on uuid, but the second
        // request is pure waste.
        if (!mutex.tryLock()) return Result.Retry(REASON_ALREADY_DRAINING)
        try {
            var uploaded = 0
            // Bounded so one call cannot hold the lock through an enormous backlog.
            repeat(MAX_BATCHES_PER_DRAIN) {
                val batch = store.pending(config.batchSize)
                if (batch.isEmpty()) {
                    return if (uploaded > 0) Result.Uploaded(uploaded) else Result.Empty
                }

                val response = transport.upload(
                    SyncRequest(
                        url = config.url,
                        method = config.method,
                        headers = config.headers,
                        jsonBody = encodeBody(
                            points = batch.map { toSyncPoint(it, config.includePointSessionId) },
                            extraParams = config.extraParams,
                        ),
                        gzip = config.gzipRequestBody,
                        timeouts = config.timeouts,
                    ),
                )

                // Emitted before the branch, so every exchange reports exactly once — the
                // terminal paths return, and an event written after the return is an event
                // the host never sees for precisely the failures it most needs to see.
                onEvent(SyncEvent.HttpResponse(response.statusCode(), batch.size))

                when (response) {
                    is SyncResponse.Success -> {
                        store.markSynced(batch.map { it.uuid }, clock.wallTimeMs())
                        uploaded += batch.size
                    }
                    SyncResponse.Unauthorized -> {
                        sdkLog { logger.w(TAG, "401 on upload; auth expired") }
                        return Result.AuthExpired
                    }
                    SyncResponse.Forbidden -> {
                        sdkLog { logger.w(TAG, "403 on upload; credential rejected — rows stay queued") }
                        return Result.Forbidden
                    }
                    is SyncResponse.Failure -> {
                        // Rows stay queued deliberately. Retried by SyncWorker's backoff, or
                        // by the server's own schedule when it sent one.
                        sdkLog { logger.w(TAG, "Upload failed (${response.code}): ${response.message}") }
                        return Result.Retry(response.message, response.retryAfterMs)
                    }
                }
            }
            return Result.Uploaded(uploaded)
        } finally {
            mutex.unlock()
        }
    }

    /**
     * The request body: the host's `extraParams`, then the batch under `location`.
     *
     * That order is the reference contract's — an envelope of identity and auth wrapping the
     * points, not the other way round — and it is stable, because both halves are written in
     * insertion order.
     *
     * With no extra params this takes the original path and produces a byte-identical body,
     * which is the point: the overwhelmingly common case must not start depending on tree
     * encoding to stay the same shape it has always been.
     *
     * `SyncConfig.validate` has already rejected anything unserializable, so the `?: return`
     * below is unreachable by construction. It degrades to omitting the key rather than
     * throwing anyway — a drain is not the place to discover a config problem, and dropping
     * one parameter beats dropping the batch.
     */
    private fun encodeBody(points: List<SyncPoint>, extraParams: Map<String, Any>): String {
        if (extraParams.isEmpty()) return json.encodeToString(SyncPayload(points))

        val body = buildJsonObject {
            for ((key, value) in extraParams) {
                put(key, jsonParamOrNull(value) ?: continue)
            }
            put(
                SyncConfig.LOCATION_KEY,
                json.encodeToJsonElement(ListSerializer(SyncPoint.serializer()), points),
            )
        }
        return json.encodeToString(body)
    }

    public suspend fun pendingCount(): Int = store.pendingCount()

    /**
     * Called after a 401. The queued rows belong to a session that can no longer be
     * uploaded, and carrying them forward would leak one user's positions into the next
     * login (spec §3.3).
     */
    public suspend fun clearOnAuthExpiry() {
        store.clearQueue()
    }

    /**
     * @param includeSessionId stamp the row with the session that recorded it. Off gives a
     *   byte-identical body to every previous release — see [SyncConfig.includePointSessionId].
     */
    private fun toSyncPoint(point: TrackPoint, includeSessionId: Boolean) = SyncPoint(
        uuid = point.uuid,
        time = point.timeMs,
        local_date = point.localDate,
        latitude = point.latitude,
        longitude = point.longitude,
        accuracy = point.accuracy,
        movementSpeed = point.speedMps,
        provider = point.providerSnapshot(),
        hasSpeed = point.hasSpeed,
        hasBearing = point.hasBearing,
        time_zone = point.timezone,
        // "<locationType>@<movementStatus>" — the server stores this verbatim and the
        // plotting side parses it back (spec §9).
        activity_status = "${point.provider}@${point.movementStatus.wireName()}",
        detected_activity_type = point.detectedActivity?.name,
        detected_activity_start_time = point.activityStartTimeMs,
        battery_percentage = point.batteryPct?.toString(),
        is_charging = point.isCharging,
        is_mock = point.isMock,
        integrity_flags = point.integrityFlags,
        integrity_signals = IntegritySignal.entries
            .filter { point.integrityFlags and it.mask != 0 }
            .map { it.name },
        // From the row, never from the config: a backlog drained after a process death can
        // hold rows from more than one session, and the envelope cannot describe them all.
        session_id = point.sessionId.takeIf { includeSessionId },
    )

    /**
     * `null` — and so an omitted `provider` key — for a point stored before the SDK began
     * recording the snapshot. Sending an object of `false`s for those would be a claim about
     * a device nobody looked at.
     */
    private fun TrackPoint.providerSnapshot(): SyncProvider? {
        val snapshot = ProviderSnapshot.fromFlags(providerFlags)
        if (!snapshot.recorded) return null
        return SyncProvider(
            network = snapshot.networkEnabled,
            gps = snapshot.gpsEnabled,
            enabled = snapshot.locationServicesEnabled,
            status = snapshot.authorizationStatus,
            accuracyAuthorization = snapshot.accuracyAuthorization,
            airplane = snapshot.airplaneMode,
        )
    }

    private fun MovementStatus.wireName() = name.lowercase()

    /**
     * `null` means no HTTP exchange completed — a dead network, a DNS failure, a timeout.
     * A device problem and a server problem are different things and a diagnostics screen
     * should not draw them the same way.
     */
    private fun SyncResponse.statusCode(): Int? = when (this) {
        is SyncResponse.Success -> code
        SyncResponse.Unauthorized -> HTTP_UNAUTHORIZED
        SyncResponse.Forbidden -> HTTP_FORBIDDEN
        is SyncResponse.Failure -> code
    }

    /**
     * `internal`, so the reason strings stay out of the published API while still being
     * one definition rather than a literal repeated at the site that has to recognise it.
     *
     * [Result.Retry.reason] is documentation for a host, not a protocol — nothing outside
     * this module should be branching on it, and `SyncWorker` only does so because these
     * three reasons are the ones that are *not* a failed exchange.
     */
    internal companion object {
        /** Another drain holds the lock. It is doing this caller's work. */
        internal const val REASON_ALREADY_DRAINING = "already draining"

        /** No `configure()` yet, or a 401/403 tore it down. */
        internal const val REASON_NOT_CONFIGURED = "sync not configured"

        /** Configured, but nothing to upload through. Unreachable in practice. */
        internal const val REASON_NO_TRANSPORT = "no transport"

        const val TAG = "SyncQueue"
        const val MAX_BATCHES_PER_DRAIN = 20
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
    }
}
