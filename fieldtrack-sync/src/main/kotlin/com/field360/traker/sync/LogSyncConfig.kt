package com.field360.traker.sync

import com.field360.traker.sync.internal.SyncService
import com.field360.traker.sync.internal.jsonParamOrNull
import java.net.URI

/**
 * Where diagnostics go, and how much of them (`docs/APP-LOG-API.md`).
 *
 * A **separate** configuration from [SyncConfig], not a flag on it, and the separation is
 * load-bearing rather than tidy:
 *
 *  - **A 401 on the points URL is destructive.** It stops tracking, clears the upload queue
 *    and forgets the credential. A log-shipping mistake must not be able to reach that, so
 *    the two endpoints are configured apart and are best given apart credentials.
 *  - **Logs are lossy and points are not.** This queue drops a permanently-rejected batch
 *    and moves on; the point queue retries one forever, because there dropping is data
 *    loss. Opposite policies cannot share a config object without one of them being wrong.
 *
 * ### It follows the points endpoint
 *
 * Everything here has a default derived from the [SyncConfig] already in force, so the
 * ordinary call is:
 *
 * ```kotlin
 * sync.configure(SyncConfig.builder().url(".../v1/location/batch").header(…).extraParam("device_id", id).build())
 * sync.configureLogs()          // same host, same credential, /v1/logs/batch
 * ```
 *
 * The [url] resolves against the points URL's own scheme and host, [deviceId] is inherited
 * from `SyncConfig.extraParams["device_id"]`, and [headers] fall back to the points
 * headers. That inheritance is not a convenience: sending a *different* `device_id` on this
 * channel produces two unrelated datasets, and the join between a hole in a track and the
 * reason for it is the entire point of the endpoint.
 *
 * @property url the whole endpoint. Blank means "derive it" — see [resolvedAgainst].
 * @property deviceId the id this device is known by. Blank means "inherit it".
 * @property level the minimum severity recorded. Filtering happens at **record** time, not
 *   at upload time, which is what keeps the buffer a strict FIFO the uploader settles with
 *   one cursor — and what stops a device storing what it will never send.
 * @property types which kinds are recorded and shipped. [LogType.DECISION] is absent from
 *   the default for a reason worth reading its KDoc for: ~29 000 entries per device per
 *   shift.
 * @property bufferCapacity rows kept on the device. The oldest are evicted first, shipped
 *   or not — a bounded buffer that refused to drop unsent rows would be unbounded on
 *   exactly the device that cannot reach a server. The resulting gap in `seq` is reported
 *   to the backend rather than hidden.
 * @property retentionHours how long a **shipped** entry is kept before pruning. Queued
 *   entries are never pruned by age; only the ring evicts those.
 * @property batchSize entries per request, 1..[MAX_BATCH_SIZE]. The server answers `413`
 *   above its own ceiling, and a `413` batch is dropped rather than retried.
 * @property gzipRequestBody **on by default here**, unlike [SyncConfig]. Log bodies are
 *   repetitive prose and compress around 8:1.
 * @property nudgeLevel the severity that earns a **prompt** drain instead of waiting for
 *   the heartbeat, or `null` to wait for it always. `WARN` by default, which is what makes a
 *   GPS toggle, a permission revocation or a capture suspension reach the server in seconds
 *   rather than in up to [uploadIntervalMinutes]. Ordinary `INFO` chatter still rides the
 *   heartbeat, because a radio wake per log line is exactly the battery cost this channel is
 *   otherwise careful to avoid.
 * @property nudgeCooldownMs the shortest gap between two prompt drains. A burst inside the
 *   window does not fire again immediately — but it is **deferred, not dropped**: one drain
 *   is scheduled for the end of the window, so the entries written after the first drain
 *   still go out without waiting for the heartbeat.
 * @property uploadIntervalMinutes cadence of the periodic drain. Diagnostics are read after
 *   the fact, so shipping them every fifteen minutes rather than on every entry is what
 *   keeps this channel off the radio budget the points queue is already spending.
 * @property extraParams merged into the top level of the body, beside `logs`.
 */
public data class LogSyncConfig(
    val url: String = "",
    val deviceId: String = "",
    val method: String = "POST",
    val headers: Map<String, String> = emptyMap(),
    val autoSync: Boolean = true,
    val level: LogLevel = LogLevel.INFO,
    val types: Set<LogType> = setOf(LogType.EVENT, LogType.LIFECYCLE, LogType.MESSAGE),
    val bufferCapacity: Int = DEFAULT_BUFFER_CAPACITY,
    val retentionHours: Int = DEFAULT_RETENTION_HOURS,
    val batchSize: Int = DEFAULT_BATCH_SIZE,
    val requiresUnmeteredNetwork: Boolean = false,
    val gzipRequestBody: Boolean = true,
    val allowCleartext: Boolean = false,
    val timeouts: SyncTimeouts = SyncTimeouts(),
    val uploadIntervalMinutes: Long = DEFAULT_INTERVAL_MINUTES,
    val nudgeLevel: LogLevel? = LogLevel.WARN,
    val nudgeCooldownMs: Long = DEFAULT_NUDGE_COOLDOWN_MS,
    val extraParams: Map<String, Any> = emptyMap(),
) {

    /** True when this device is shipping the decision log — see [LogType.DECISION]. */
    public val shipsDecisions: Boolean get() = LogType.DECISION in types

    /**
     * Everything wrong with this config, or an empty list.
     *
     * Run by `TrackerSync.configureLogs` **after** [resolvedAgainst] has filled in what the
     * points endpoint can supply, so a blank [url] or [deviceId] is reported only when
     * nothing could complete it — which is the point at which the answer is actually known.
     */
    public fun validate(): List<String> = buildList {
        if (url.isBlank()) {
            add(
                "url could not be resolved. Configure the points endpoint first — the log " +
                    "URL is derived from it — or set a full url, or a baseUrl on either " +
                    "LogSyncConfig.builder() or TrackerConfig.builder().",
            )
        } else {
            val uri = runCatching { URI(url) }.getOrNull()
            when (val scheme = uri?.scheme?.lowercase()) {
                null -> add("url is not a valid absolute URL: $url")
                "https" -> Unit
                "http" -> if (!allowCleartext && uri.host !in LOOPBACK_HOSTS) {
                    add(
                        "url must be https://. Cleartext is blocked at runtime by Android's " +
                            "default network security policy, so an http:// endpoint fails " +
                            "as an ordinary network error and retries. Set allowCleartext = " +
                            "true if this is deliberate; loopback is already exempt.",
                    )
                }
                else -> add("url scheme must be https (or http for a local server), not $scheme")
            }
        }

        if (deviceId.isBlank()) {
            add(
                "deviceId is blank and could not be inherited. It is what joins a log entry " +
                    "to the device whose points it explains, so set it here or put " +
                    "\"device_id\" in SyncConfig.extraParams.",
            )
        }

        if (method.isBlank()) {
            add("method must not be blank")
        } else if (method.uppercase() !in SyncService.SUPPORTED_METHODS) {
            add("method must be one of ${SyncService.SUPPORTED_METHODS.joinToString()} (was \"$method\")")
        }

        if (batchSize !in 1..MAX_BATCH_SIZE) add("batchSize must be in 1..$MAX_BATCH_SIZE")
        if (types.isEmpty()) add("types must name at least one LogType, or do not configure logs")
        if (bufferCapacity < 1) add("bufferCapacity must be >= 1 (was $bufferCapacity)")
        if (retentionHours < 1) add("retentionHours must be >= 1 (was $retentionHours)")
        // WorkManager's own floor. A shorter interval is silently raised to 15 minutes,
        // which is worse than being told.
        if (uploadIntervalMinutes < MIN_INTERVAL_MINUTES) {
            add("uploadIntervalMinutes must be >= $MIN_INTERVAL_MINUTES (WorkManager's floor)")
        }
        if (nudgeCooldownMs < 0) add("nudgeCooldownMs must be >= 0 (was $nudgeCooldownMs)")
        if (timeouts.connectMs <= 0) add("timeouts.connectMs must be > 0")
        if (timeouts.readMs <= 0) add("timeouts.readMs must be > 0")
        if (timeouts.writeMs <= 0) add("timeouts.writeMs must be > 0")

        for ((key, value) in extraParams) {
            when {
                key.isBlank() -> add("extraParams keys must not be blank")
                key in RESERVED_KEYS -> add(
                    "extraParams may not use the key \"$key\" — the log envelope owns it. " +
                        "Reserved: ${RESERVED_KEYS.joinToString()}",
                )
                jsonParamOrNull(value) == null -> add(
                    "extraParams[\"$key\"] cannot be sent as JSON. Use a String, Boolean, " +
                        "number, or a Map/List of those; omit the key rather than passing null.",
                )
            }
        }
    }

    /**
     * Fills in whatever the host left out, from the points endpoint and then from
     * `TrackerConfig.baseUrl`.
     *
     * The URL is resolved in the order a host would expect its own instruction to win:
     *
     *  1. an absolute [url] set here;
     *  2. a bare path set here, against `TrackerConfig.baseUrl`;
     *  3. **the points URL's own origin**, plus a bare path here or [DEFAULT_PATH].
     *
     * Step 3 is what "follows the sync base URL" means. A host that has already told the
     * SDK where its backend lives should not have to say it twice, and two spellings of one
     * host is how a staging build ends up posting logs to production.
     *
     * @param points the configuration in force on the points channel, or `null` if the host
     *   has not called `configure()` — in which case only steps 1 and 2 can apply.
     */
    internal fun resolvedAgainst(points: SyncConfig?, baseUrl: String?): LogSyncConfig {
        val resolvedUrl = when {
            url.isNotBlank() && runCatching { URI(url).scheme }.getOrNull() != null -> url
            url.isNotBlank() && !baseUrl.isNullOrBlank() -> join(baseUrl, url)
            else -> originOf(points?.url)?.let { join(it, url.ifBlank { DEFAULT_PATH }) }
                ?: baseUrl?.let { join(it, url.ifBlank { DEFAULT_PATH }) }
                ?: url
        }

        return copy(
            url = resolvedUrl,
            // Inherited rather than defaulted: a different id here would produce a second,
            // unrelated dataset for the same phone.
            deviceId = deviceId.ifBlank {
                (points?.extraParams?.get(DEVICE_ID_KEY) as? String).orEmpty()
            },
            // The points credential, unless the host supplied one. Recommended: give this
            // endpoint its own token with its own scope, so a revocation on one channel is
            // not a revocation on the other.
            headers = headers.ifEmpty { points?.headers.orEmpty() },
        )
    }

    /** Scheme, host and port of [url], or `null` when there is nothing to take. */
    private fun originOf(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val scheme = uri.scheme ?: return null
        val host = uri.host ?: return null
        return buildString {
            append(scheme).append("://").append(host)
            if (uri.port != -1) append(':').append(uri.port)
        }
    }

    private fun join(base: String, path: String): String =
        "${base.trim().trimEnd('/')}/${path.trim().trimStart('/')}"

    /** Fluent, Java-callable construction. Mirrors [SyncConfig.Builder]. */
    public class Builder {
        private var url: String = ""
        private var deviceId: String = ""
        private var method: String = "POST"
        private val headers = LinkedHashMap<String, String>()
        private var autoSync: Boolean = true
        private var level: LogLevel = LogLevel.INFO
        private var types: Set<LogType> = setOf(LogType.EVENT, LogType.LIFECYCLE, LogType.MESSAGE)
        private var bufferCapacity: Int = DEFAULT_BUFFER_CAPACITY
        private var retentionHours: Int = DEFAULT_RETENTION_HOURS
        private var batchSize: Int = DEFAULT_BATCH_SIZE
        private var requiresUnmeteredNetwork: Boolean = false
        private var gzipRequestBody: Boolean = true
        private var allowCleartext: Boolean = false
        private var timeouts: SyncTimeouts = SyncTimeouts()
        private var uploadIntervalMinutes: Long = DEFAULT_INTERVAL_MINUTES
        private var nudgeLevel: LogLevel? = LogLevel.WARN
        private var nudgeCooldownMs: Long = DEFAULT_NUDGE_COOLDOWN_MS
        private val extraParams = LinkedHashMap<String, Any>()

        /** The whole endpoint. Omit it to derive one from the points URL. */
        public fun url(url: String): Builder = apply { this.url = url }

        /** A path against the points URL's origin, or against `TrackerConfig.baseUrl`. */
        public fun path(path: String): Builder = apply { this.url = path }

        /** Omit it to inherit `SyncConfig.extraParams["device_id"]`, which is the point. */
        public fun deviceId(deviceId: String): Builder = apply { this.deviceId = deviceId }

        public fun method(method: String): Builder = apply { this.method = method }

        /** Adds one header. Leave the map empty to inherit the points credential. */
        public fun header(name: String, value: String): Builder = apply { headers[name] = value }

        public fun headers(headers: Map<String, String>): Builder =
            apply { this.headers.putAll(headers) }

        public fun autoSync(enabled: Boolean): Builder = apply { autoSync = enabled }

        /** Minimum severity recorded. Applied when the entry is written, not when it is sent. */
        public fun level(level: LogLevel): Builder = apply { this.level = level }

        public fun types(types: Set<LogType>): Builder = apply { this.types = types }

        /**
         * Record and ship the decision log from this device.
         *
         * ~29 000 entries per device per 8-hour shift, the same order as `track_point` and
         * wider. Turn it on for a named device with a ticket open, not for a fleet — and
         * note it is only recorded at [LogLevel.DEBUG].
         */
        public fun shipDecisions(enabled: Boolean): Builder = apply {
            types = if (enabled) types + LogType.DECISION else types - LogType.DECISION
        }

        public fun bufferCapacity(rows: Int): Builder = apply { bufferCapacity = rows }

        public fun retentionHours(hours: Int): Builder = apply { retentionHours = hours }

        public fun batchSize(entries: Int): Builder = apply { batchSize = entries }

        public fun requiresUnmeteredNetwork(required: Boolean): Builder =
            apply { requiresUnmeteredNetwork = required }

        public fun gzipRequestBody(enabled: Boolean): Builder = apply { gzipRequestBody = enabled }

        /** Only for a local development server. See [LogSyncConfig.validate]. */
        public fun allowCleartext(allowed: Boolean): Builder = apply { allowCleartext = allowed }

        public fun timeouts(timeouts: SyncTimeouts): Builder = apply { this.timeouts = timeouts }

        public fun uploadIntervalMinutes(minutes: Long): Builder =
            apply { uploadIntervalMinutes = minutes }

        /**
         * The severity that earns a prompt drain rather than waiting for the heartbeat.
         *
         * `null` turns it off entirely, and everything then waits for
         * [uploadIntervalMinutes]. Lowering it to [LogLevel.INFO] is a battery decision, not
         * a diagnostics one: on a busy device that is a radio wake every cooldown window.
         */
        public fun nudgeLevel(level: LogLevel?): Builder = apply { nudgeLevel = level }

        /** The shortest gap between two prompt drains. A burst inside it is deferred. */
        public fun nudgeCooldownMs(ms: Long): Builder = apply { nudgeCooldownMs = ms }

        public fun extraParam(name: String, value: Any): Builder =
            apply { extraParams[name] = value }

        public fun extraParams(params: Map<String, Any>): Builder =
            apply { extraParams.putAll(params) }

        /**
         * Unvalidated on purpose, unlike [SyncConfig.Builder.build].
         *
         * Half of this object is legitimately blank until it has been resolved against the
         * points endpoint, so validation belongs to `configureLogs` — which is where the
         * missing halves are finally known, and where it throws.
         */
        public fun build(): LogSyncConfig = LogSyncConfig(
            url = url,
            deviceId = deviceId,
            method = method,
            headers = headers.toMap(),
            autoSync = autoSync,
            level = level,
            types = types,
            bufferCapacity = bufferCapacity,
            retentionHours = retentionHours,
            batchSize = batchSize,
            requiresUnmeteredNetwork = requiresUnmeteredNetwork,
            gzipRequestBody = gzipRequestBody,
            allowCleartext = allowCleartext,
            timeouts = timeouts,
            uploadIntervalMinutes = uploadIntervalMinutes,
            nudgeLevel = nudgeLevel,
            nudgeCooldownMs = nudgeCooldownMs,
            extraParams = extraParams.toMap(),
        )
    }

    public companion object {
        @JvmStatic
        public fun builder(): Builder = Builder()

        /** The endpoint's path, as the backend implements it. */
        public const val DEFAULT_PATH: String = "v1/logs/batch"

        public const val DEFAULT_BATCH_SIZE: Int = 200

        /** The server answers `413` above this, and a `413` batch is dropped, not retried. */
        public const val MAX_BATCH_SIZE: Int = 500
        public const val DEFAULT_BUFFER_CAPACITY: Int = 5_000
        public const val DEFAULT_RETENTION_HOURS: Int = 72
        public const val DEFAULT_INTERVAL_MINUTES: Long = 15

        /**
         * 30 s between prompt drains.
         *
         * Long enough that a cascade — GPS off, then a provider change, then a capture
         * suspension, all inside two seconds — costs one upload rather than three, and short
         * enough that somebody watching a dashboard while a driver toggles a setting sees it
         * before they give up.
         */
        public const val DEFAULT_NUDGE_COOLDOWN_MS: Long = 30_000
        public const val MIN_INTERVAL_MINUTES: Long = 15

        /** Keys the envelope owns. Reserved against [extraParams]. */
        internal val RESERVED_KEYS: Set<String> =
            setOf("logs", "device_id", "session_id", "app", "device", "uploaded_at")

        internal const val DEVICE_ID_KEY: String = "device_id"

        private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "::1", "[::1]", "10.0.2.2")
    }
}
