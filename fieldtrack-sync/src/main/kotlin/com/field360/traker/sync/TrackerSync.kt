package com.field360.traker.sync

import android.annotation.SuppressLint
import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.field360.tracker.Tracker
import com.field360.tracker.TrackerArtifacts
import com.field360.tracker.domain.repository.SyncTrigger
import com.field360.traker.geo.port.TrackLogger
import com.field360.traker.sync.internal.MAX_PARAM_DEPTH
import com.field360.traker.sync.internal.NetworkMonitor
import com.field360.traker.sync.internal.NoOpTransport
import com.field360.traker.sync.internal.SyncService
import com.field360.traker.sync.internal.jsonParamOrNull
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * @property autoSync upload as points arrive. With it off, the host calls [TrackerSync.syncNow].
 * @property batchSize rows per request. Larger means fewer requests but a bigger retry
 *   unit — a failure re-sends the whole batch.
 * @property gzipRequestBody compress the JSON body. Off by default and deliberately so:
 *   there is no negotiation for request-body encoding — a client sending
 *   `Content-Encoding: gzip` is asserting it, and a server that does not expect it answers
 *   400 or stores the compressed bytes as the payload. Turning this on by default would
 *   break working integrations on an upgrade, with a failure that reads as a server bug.
 * @property allowCleartext permit an `http://` URL. For a local development server only —
 *   see [validate].
 * @property timeouts applied by the built-in transport. Ignored by a custom [SyncTransport],
 *   which owns the client that would honour them.
 * @property extraParams merged into the **top level** of every request body, alongside the
 *   `location` array — the shape most backends want, where the batch travels inside an
 *   envelope carrying identity (`user_id`, `device_id`, a session token) rather than alone.
 *
 *   Values may be a `String`, `Boolean`, any boxed number, or a `Map`/`List`/array of those
 *   for nested structures — `null` is not a value, omit the key instead. Anything else is
 *   rejected by [validate], naming the key, rather than failing on the first upload.
 *
 *   The key `location` is reserved: it is the batch itself.
 */
public data class SyncConfig(
    val url: String,
    val method: String = "POST",
    val headers: Map<String, String> = emptyMap(),
    val autoSync: Boolean = true,
    val batchSize: Int = 100,
    val requiresUnmeteredNetwork: Boolean = false,
    val gzipRequestBody: Boolean = false,
    val allowCleartext: Boolean = false,
    val timeouts: SyncTimeouts = SyncTimeouts(),
    val extraParams: Map<String, Any> = emptyMap(),
    /**
     * Stamp every uploaded row with the id of the session that recorded it — see
     * [SyncPoint.session_id].
     *
     * **Off by default, and the default is not the safe-looking choice — it is the
     * compatible one.** With it off the request body is byte-identical to what every
     * previous release sent, so turning this on is a decision a backend has to be ready
     * for, not a surprise in a patch release.
     *
     * **Turn it on if a batch can ever span two sessions**, which on an offline-first
     * recorder means: turn it on. A queue that could not drain — no network, or a process
     * the OEM killed — is still there when the next drive starts, and the envelope's own
     * `session_id` (if the host sets one in [extraParams]) then describes only whichever
     * session was current at the last `configure()`.
     */
    val includePointSessionId: Boolean = false,
) {

    /**
     * Everything wrong with this config, or an empty list.
     *
     * Mirrors `TrackerConfig.validate()`: [TrackerSync.configure] runs this and throws, and
     * a host assembling a config from untrusted input can read it first instead.
     *
     * The scheme check is the one that earns its place. Android blocks cleartext by default
     * from API 28, so an `http://` URL is accepted here, uploaded to, and fails at runtime
     * as a generic network error — retried forever, on battery, with nothing in the logs
     * naming the real cause. Loopback is exempt because the platform's own default network
     * security config exempts it, so a local dev server needs no flag at all.
     */
    public fun validate(): List<String> = validate(requireAbsoluteUrl = true)

    /**
     * @param requireAbsoluteUrl `false` while a builder holds only a path and the base is
     *   still expected from `TrackerConfig.baseUrl`. [TrackerSync.configure] resolves first
     *   and then validates with `true`, so a config that never gets a base is still rejected
     *   — just at the point where the answer is finally known.
     */
    internal fun validate(requireAbsoluteUrl: Boolean): List<String> = buildList {
        if (url.isBlank()) add("url must not be blank")

        val uri = runCatching { URI(url) }.getOrNull()
        val scheme = uri?.scheme?.lowercase()
        when {
            !requireAbsoluteUrl && scheme == null -> Unit
            uri == null || scheme == null -> add(
                "url is not a valid absolute URL: $url. Set a full url, or a baseUrl on " +
                    "either SyncConfig.builder() or TrackerConfig.builder() for the path to " +
                    "resolve against.",
            )
            scheme == "https" -> Unit
            scheme == "http" && (allowCleartext || uri.isLoopback()) -> Unit
            scheme == "http" -> add(
                "url must be https://. Cleartext is blocked at runtime by Android's default " +
                    "network security policy, so an http:// endpoint fails as an ordinary " +
                    "network error and retries forever. Set allowCleartext = true if this is " +
                    "deliberate; loopback addresses are already exempt.",
            )
            else -> add("url scheme must be https (or http for a local server), not $scheme")
        }

        if (method.isBlank()) {
            add("method must not be blank")
        } else if (method.uppercase() !in SyncService.SUPPORTED_METHODS) {
            // Retrofit's verb annotations are compile-time constants, so the transport
            // dispatches over a fixed set rather than passing the string through. Caught
            // here so a bad verb fails at `configure()` rather than on the first upload,
            // hours later, as a generic failure.
            add(
                "method must be one of ${SyncService.SUPPORTED_METHODS.joinToString()} " +
                    "(was \"$method\")",
            )
        }
        if (batchSize !in 1..MAX_BATCH_SIZE) add("batchSize must be in 1..$MAX_BATCH_SIZE")
        if (timeouts.connectMs <= 0) add("timeouts.connectMs must be > 0")
        if (timeouts.readMs <= 0) add("timeouts.readMs must be > 0")
        if (timeouts.writeMs <= 0) add("timeouts.writeMs must be > 0")

        // Checked here rather than at upload time on purpose. An unserializable value found
        // mid-drain has no good answer — the batch cannot be sent and the rows cannot be
        // blamed — so it is caught while the host is still holding the config it wrote.
        for ((key, value) in extraParams) {
            when {
                key.isBlank() -> add("extraParams keys must not be blank")
                key == LOCATION_KEY -> add(
                    "extraParams may not use the key \"$LOCATION_KEY\" — that is the batch " +
                        "itself. Rename the parameter, or remap the whole body in a custom " +
                        "SyncTransport.",
                )
                jsonParamOrNull(value) == null -> add(
                    "extraParams[\"$key\"] is ${describe(value)}, which cannot be sent as " +
                        "JSON. Use a String, Boolean, number, or a Map/List of those; omit " +
                        "the key rather than passing null.",
                )
            }
        }
    }

    private fun describe(value: Any?): String = when (value) {
        null -> "null"
        // A deep or cyclic structure is the one failure the type name alone does not explain.
        is Map<*, *>, is Iterable<*>, is Array<*> ->
            "a ${value.javaClass.simpleName} holding an unsupported value, a non-String key, " +
                "or nesting deeper than $MAX_PARAM_DEPTH levels"
        else -> "a ${value.javaClass.name}"
    }

    private fun URI.isLoopback(): Boolean = host in LOOPBACK_HOSTS

    /**
     * This config, with [url] completed from `TrackerConfig.baseUrl` if it needs completing.
     *
     * A **fallback, never an override**: an absolute `url` here is returned untouched, so a
     * host that sets both gets the one it wrote closest to the upload. Only a relative value
     * — what `SyncConfig.builder().path("v1/points")` produces on its own — is joined to the
     * base, with the same single-slash rule the builder uses.
     *
     * A relative url with no base URL anywhere is left alone, so [validate] reports "not a
     * valid absolute URL" naming what the host actually wrote, rather than this silently
     * producing something that fails later.
     */
    internal fun resolvedAgainst(baseUrl: String?): SyncConfig {
        if (baseUrl.isNullOrBlank()) return this
        if (url.isBlank()) return this
        // An absolute url wins. `URI.scheme` is the test, not a `startsWith("http")` —
        // "https://x" and a host's own scheme both parse, "v1/points" does not.
        if (runCatching { URI(url).scheme }.getOrNull() != null) return this

        return copy(url = "${baseUrl.trim().trimEnd('/')}/${url.trim().trimStart('/')}")
    }

    /**
     * Fluent, Java-callable construction — and the place to set a **base URL** once.
     *
     * Most hosts already keep a base URL for their own API and want the SDK pointed at a
     * path under it, not handed a second full URL that drifts out of step when the
     * environment changes:
     *
     * ```kotlin
     * val config = SyncConfig.builder()
     *     .baseUrl(BuildConfig.API_BASE_URL)    // "https://api.example.com"
     *     .path("v1/location/batch")
     *     .header("Authorization", "Bearer $token")
     *     .batchSize(100)
     *     .build()
     * ```
     *
     * [baseUrl] and [path] are joined with exactly one `/` between them regardless of which
     * side carries it, so `"https://api.example.com/"` + `"/v1/points"` is the same
     * endpoint as `"https://api.example.com"` + `"v1/points"`. A double slash in a path is
     * a 404 on some servers and a redirect on others, which is a bad thing to discover in
     * the field.
     *
     * [url] sets the whole thing directly and wins over both, for a host that already has
     * one composed.
     *
     * [build] runs [validate] and throws `IllegalArgumentException`, matching
     * `TrackerConfig.Builder.build()` — same deliberate exception to the SDK's no-throw
     * contract, same reason: this runs on your own thread while you assemble a value.
     */
    public class Builder {
        private var url: String? = null
        private var baseUrl: String? = null
        private var path: String? = null
        private var method: String = "POST"
        private val headers = LinkedHashMap<String, String>()
        private var autoSync: Boolean = true
        private var batchSize: Int = 100
        private var requiresUnmeteredNetwork: Boolean = false
        private var gzipRequestBody: Boolean = false
        private var allowCleartext: Boolean = false
        private var timeouts: SyncTimeouts = SyncTimeouts()
        private val extraParams = LinkedHashMap<String, Any>()
        private var includePointSessionId: Boolean = false

        /** The whole endpoint. Overrides [baseUrl] and [path] when both are set. */
        public fun url(url: String): Builder = apply { this.url = url }

        /** Scheme, host and any common prefix — e.g. `https://api.example.com`. */
        public fun baseUrl(baseUrl: String): Builder = apply { this.baseUrl = baseUrl }

        /** Appended to [baseUrl]. Leading and trailing slashes are normalised. */
        public fun path(path: String): Builder = apply { this.path = path }

        public fun method(method: String): Builder = apply { this.method = method }

        /** Adds one header. Repeated names replace, matching the underlying map. */
        public fun header(name: String, value: String): Builder = apply { headers[name] = value }

        /** Adds all of them, keeping anything already set that these do not name. */
        public fun headers(headers: Map<String, String>): Builder =
            apply { this.headers.putAll(headers) }

        public fun autoSync(enabled: Boolean): Builder = apply { autoSync = enabled }

        public fun batchSize(rows: Int): Builder = apply { batchSize = rows }

        public fun requiresUnmeteredNetwork(required: Boolean): Builder =
            apply { requiresUnmeteredNetwork = required }

        public fun gzipRequestBody(enabled: Boolean): Builder =
            apply { gzipRequestBody = enabled }

        /** Only for a local development server. See [SyncConfig.validate]. */
        public fun allowCleartext(allowed: Boolean): Builder = apply { allowCleartext = allowed }

        public fun timeouts(timeouts: SyncTimeouts): Builder = apply { this.timeouts = timeouts }

        public fun timeouts(connectMs: Long, readMs: Long, writeMs: Long): Builder =
            apply { timeouts = SyncTimeouts(connectMs, readMs, writeMs) }

        /**
         * Adds one top-level body parameter, sent alongside the `location` array.
         *
         * Repeated names replace, matching [header] and the underlying map. See
         * [SyncConfig.extraParams] for the accepted value types.
         */
        public fun extraParam(name: String, value: Any): Builder =
            apply { extraParams[name] = value }

        /** Adds all of them, keeping anything already set that these do not name. */
        public fun extraParams(params: Map<String, Any>): Builder =
            apply { extraParams.putAll(params) }

        /**
         * Stamp each uploaded row with its own session id — see
         * [SyncConfig.includePointSessionId]. Set this on any host that records offline.
         */
        public fun includePointSessionId(include: Boolean): Builder =
            apply { includePointSessionId = include }

        /**
         * @throws IllegalArgumentException if [SyncConfig.validate] reports anything.
         *
         * A **path with no base URL is allowed here**, and only here: it means the base is
         * expected from `TrackerConfig.baseUrl`, which this builder cannot see.
         * [TrackerSync.configure] resolves the two and throws if neither supplied one, so
         * the config is still rejected — at the point where the answer is actually known,
         * with a message naming both places a base can come from.
         */
        public fun build(): SyncConfig {
            val config = buildUnchecked()
            val deferred = url == null && baseUrl.isNullOrBlank() && !path.isNullOrBlank()
            val errors = config.validate(requireAbsoluteUrl = !deferred)
            require(errors.isEmpty()) { "Invalid SyncConfig: ${errors.joinToString("; ")}" }
            return config
        }

        /**
         * The same value, unvalidated. For a host assembling config from untrusted input
         * that would rather read [SyncConfig.validate] itself than catch.
         */
        public fun buildUnchecked(): SyncConfig = SyncConfig(
            url = url ?: join(baseUrl, path),
            method = method,
            headers = headers.toMap(),
            autoSync = autoSync,
            batchSize = batchSize,
            requiresUnmeteredNetwork = requiresUnmeteredNetwork,
            gzipRequestBody = gzipRequestBody,
            allowCleartext = allowCleartext,
            timeouts = timeouts,
            extraParams = extraParams.toMap(),
            includePointSessionId = includePointSessionId,
        )

        private fun join(base: String?, path: String?): String {
            val head = base?.trim().orEmpty().trimEnd('/')
            val tail = path?.trim().orEmpty().trim('/')
            return when {
                head.isEmpty() -> tail          // validate() reports this as not absolute
                tail.isEmpty() -> head
                else -> "$head/$tail"
            }
        }
    }

    public companion object {
        /** Fluent construction; also the way to set a base URL and a path separately. */
        @JvmStatic
        public fun builder(): Builder = Builder()

        private const val MAX_BATCH_SIZE = 1_000

        /** The body key carrying the batch. Reserved against [extraParams]. */
        internal const val LOCATION_KEY: String = "location"

        /** `10.0.2.2` is the emulator's route to the developer's own machine. */
        private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "::1", "[::1]", "10.0.2.2")
    }
}

/**
 * The optional upload half.
 *
 * `fieldtrack-core` never opens a socket; this artifact does, and a host that does not
 * depend on it gets an offline-first SDK with no network code linked at all
 * (PLAN.md §0).
 */
public class TrackerSync internal constructor(
    private val context: Context,
    private val queue: SyncQueue,
    private val trackIt: Tracker,
    private val logger: TrackLogger,
    private val artifacts: TrackerArtifacts,
    private val eventSink: MutableSharedFlow<SyncEvent>,
) {

    @Volatile
    private var config: SyncConfig? = null

    @Volatile
    private var transport: SyncTransport? = null

    /**
     * Whether the server has told us to stop, and why. Set by a 403, cleared by the next
     * [configure] — re-configuring with a working credential is the documented recovery.
     */
    @Volatile
    private var haltedReason: String? = null

    /**
     * The prompt half of "upload when the network comes back" — see [NetworkMonitor] for
     * why the durable half ([SyncWorker]'s network constraint) is not sufficient alone.
     *
     * The queue check and the coroutine it needs live inside the monitor rather than here,
     * for a release-build reason its KDoc spells out: a suspend lambda written in this
     * class reads this class's private fields, and R8 then cannot repackage the class it
     * compiles to out of the published API package.
     */
    private val networkMonitor = NetworkMonitor(
        context = context,
        logger = logger,
        queue = queue,
        isUploadable = { config != null && haltedReason == null },
        onQueued = { queued ->
            eventSink.tryEmit(SyncEvent.NetworkAvailable(queued))
            requestSyncOnReconnect()
        },
    )

    /**
     * What the server said, one event per exchange — including the exchanges the host did
     * not ask for. [syncNow] already returns the outcome of a drain a host requested;
     * [requestSync] hands the work to WorkManager, which may run it minutes later in a
     * process nobody is watching.
     */
    public val events: SharedFlow<SyncEvent> = eventSink.asSharedFlow()

    /**
     * Where uploads are going, or `null` if [configure] has not been called — or if a 401
     * has since torn the configuration down.
     *
     * The headers are deliberately **not** exposed: they carry the host's credential, and a
     * property that hands a bearer token back is a property that ends up in a log.
     */
    public val endpoint: String? get() = config?.url

    /**
     * Derived from [endpoint] rather than tracked separately, so the two cannot disagree.
     *
     * Do not cache it. A 401 clears the configuration with no host involvement, so a
     * remembered value goes stale at exactly the moment it matters.
     */
    public val isConfigured: Boolean get() = endpoint != null

    /**
     * @param transport omit to use the OkHttp default. Supply your own to reuse an
     *   existing authenticated client — then OkHttp is never linked.
     * @throws IllegalArgumentException if [config] does not pass [SyncConfig.validate].
     *   The same deliberate exception to the SDK's no-throw contract that
     *   `TrackerConfig.Builder.build()` makes, for the same reason: this runs on the host's
     *   own thread while it assembles a value, which is where fail-fast belongs. The
     *   alternative — accepting it and failing at upload time — is the silent failure this
     *   validation exists to end.
     */
    public fun configure(config: SyncConfig, transport: SyncTransport? = null) {
        val resolved = config.resolvedAgainst(artifacts.baseUrl)
        val errors = resolved.validate()
        require(errors.isEmpty()) { "Invalid SyncConfig: ${errors.joinToString("; ")}" }

        this.config = resolved
        this.transport = transport ?: defaultTransport()
        this.haltedReason = null
        if (resolved.url != config.url) {
            sdkLog { logger.d(TAG, "Resolved upload endpoint against TrackerConfig.baseUrl") }
        }

        // What makes autoSync mean anything. Core drives the trigger — on an accepted
        // point, and from its supervision loops when rows are queued or the last upload
        // has gone stale — because those are the moments it can see and this module
        // cannot. With autoSync off nothing is registered and the host owns the schedule.
        artifacts.registerSyncTrigger(if (config.autoSync) SyncTrigger(::requestSync) else null)

        // Connectivity is the fourth moment, and the one none of the above can see: a
        // device that has been offline stores points and requests drains that fail, and
        // then nothing changes until the network does. Gated on autoSync for the same
        // reason the trigger is — with it off the host owns the schedule, and a drain it
        // did not ask for is a surprise request against its own credential.
        networkMonitor.stop()
        if (resolved.autoSync) {
            // Registration replays the current default network, so a configure() made
            // while already online is itself a rising edge and drains any backlog left
            // over from a previous run. When the platform refuses to watch at all, that
            // one check still has to happen — so make it by hand.
            if (!networkMonitor.start(resolved.requiresUnmeteredNetwork)) {
                networkMonitor.drainIfQueued()
            }
        }
    }

    public suspend fun pendingCount(): Int = queue.pendingCount()

    /**
     * Enqueues a network-constrained one-shot; safe to call often.
     *
     * A no-op once a 403 has halted uploads — that loop is the battery burn the halt exists
     * to stop, and re-enqueueing work that will be rejected again is how it would continue.
     */
    public fun requestSync() {
        val activeConfig = config ?: return
        if (haltedReason != null) return
        SyncWorker.enqueue(context, activeConfig.requiresUnmeteredNetwork)
    }

    /**
     * The connectivity path's own enqueue, and **not** [requestSync].
     *
     * The difference is `REPLACE` versus `KEEP`, and it is the whole reason the prompt half
     * was not actually prompt. `KEEP` does nothing when a `fieldtrack-sync` request already
     * exists in any unfinished state — and `ENQUEUED` is exactly what a request in linear
     * backoff looks like. WorkManager's `NetworkType.CONNECTED` releases work on *connected*
     * rather than *validated*, so the drain routinely runs a second or two before routing
     * actually works, fails with an `IOException`, and re-enters backoff. The validated
     * rising edge then arrived, read a non-empty queue, emitted
     * [SyncEvent.NetworkAvailable] — and was dropped by `KEEP`, leaving the backlog to wait
     * out a backoff that had already grown to minutes on a flaky link.
     *
     * `REPLACE` is right here for the same reason it is right for a `Retry-After`: this is
     * not a burst. It is one request per rising edge, already throttled by [RisingEdge]'s
     * cooldown and already gated on a non-empty queue, so there is nothing to defend
     * against by keeping a stale backoff — and replacing it resets `runAttemptCount`, which
     * is what stops a long offline stint from compounding into a five-hour delay.
     */
    private fun requestSyncOnReconnect() {
        val activeConfig = config ?: return
        if (haltedReason != null) return
        SyncWorker.enqueueNow(context, activeConfig.requiresUnmeteredNetwork)
    }

    /**
     * Drains inline. Returns what happened so a host can surface it.
     *
     * Runs in the caller's scope, so an upload started from a `viewModelScope` is cancelled
     * with it. Prefer [requestSync] for anything not user-initiated.
     */
    public suspend fun syncNow(): SyncQueue.Result {
        val activeConfig = config
            ?: return SyncQueue.Result.Retry(SyncQueue.REASON_NOT_CONFIGURED)
        val activeTransport = transport
            ?: return SyncQueue.Result.Retry(SyncQueue.REASON_NO_TRANSPORT)
        // Answer from memory rather than spending a request to be told the same thing.
        if (haltedReason != null) return SyncQueue.Result.Forbidden

        val result = queue.drain(activeConfig, activeTransport)
        when (result) {
            SyncQueue.Result.AuthExpired -> tearDown()
            SyncQueue.Result.Forbidden -> halt()
            else -> Unit
        }
        return result
    }

    /**
     * 403 handling: stop uploading, keep everything.
     *
     * Deliberately not [tearDown]. A 401 means the credential this data was recorded under
     * is gone and the next login may be a different user, which is what justifies clearing
     * the queue. A 403 means *this* credential may not write *this* resource — a scope, a
     * rotated key, a server-side permission bug — and it is the same user's data either
     * way. Destroying it to fix a permissions mistake is the more expensive of the two
     * errors, so tracking continues, the rows stay queued, and the retry loop stops.
     */
    private fun halt() {
        haltedReason = "403 — credential rejected by the server"
        sdkLog { logger.w(TAG, "Uploads halted after a 403; rows kept. Re-configure to resume") }
        config = null
        transport = null
        // Core must stop nudging too, or every accepted point re-enters a loop that has
        // already been told to stop.
        artifacts.registerSyncTrigger(null)
        // And so must connectivity. A halted uploader that still drains on every
        // reconnection is the same retry loop by a different door.
        networkMonitor.stop()
    }

    /**
     * Re-enqueues the drain at the server's own schedule.
     *
     * `REPLACE`, unlike the ordinary [requestSync] path, and the difference is the point:
     * `KEEP` exists there so a burst of accepted points cannot reset the backoff clock and
     * hammer a struggling server. This path is reachable only from a `Retry-After` the
     * server itself sent, so there is no burst to defend against — and `KEEP` here would
     * silently discard the instruction in favour of our 30 s default.
     */
    internal fun rescheduleAfter(delayMs: Long) {
        val unmetered = config?.requiresUnmeteredNetwork == true
        SyncWorker.enqueueAfter(context, unmetered, delayMs)
    }

    /**
     * 401 handling: stop tracking, clear the queue, forget the config.
     *
     * Deliberately drastic. A 401 means the credentials this session was recorded under
     * are gone, so continuing to capture would pile up rows that can never be uploaded,
     * and keeping the queue would leak the previous user's positions into the next login
     * (spec §3.3).
     */
    private suspend fun tearDown() {
        sdkLog { logger.w(TAG, "Auth expired — stopping tracking and clearing the upload queue") }
        runCatching { trackIt.stop() }
        queue.clearOnAuthExpiry()
        config = null
        transport = null
        artifacts.registerSyncTrigger(null)
        networkMonitor.stop()
    }

    private fun defaultTransport(): SyncTransport = runCatching { OkHttpSyncTransport() }
        .getOrElse {
            // compileOnly — absent unless the host added OkHttp or supplied a transport.
            sdkLog { logger.w(TAG, "OkHttp not on the classpath; supply your own SyncTransport") }
            NoOpTransport
        }

    public companion object {
        private const val TAG = "TrackerSync"

        @SuppressLint("StaticFieldLeak") // getInstance() stores only applicationContext.
        @Volatile
        private var instance: TrackerSync? = null

        /**
         * The upload half, for this process.
         *
         * Idempotent and thread-safe, and paired with [Tracker.getInstance] by
         * construction: both hang off the same core graph, so the queue drains the same
         * database the ingestor writes.
         *
         * [configure] still has to be called before anything uploads — this hands back
         * the object, not a configured transport.
         */
        @JvmStatic
        public fun getInstance(context: Context): TrackerSync {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }
        }

        private fun build(app: Context): TrackerSync {
            val access = TrackerArtifacts.of(app)
            // replay = 1 so a host opening an upload screen after a background drain sees
            // what happened rather than a blank panel — these events are a handful per
            // drain, minutes apart, so retaining one costs nothing. DROP_OLDEST with spare
            // capacity means tryEmit always succeeds and a slow collector can never stall
            // the drain that is emitting.
            val sink = MutableSharedFlow<SyncEvent>(
                replay = 1,
                extraBufferCapacity = EVENT_BUFFER,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )
            return TrackerSync(
                context = app,
                queue = SyncQueue(access.pendingUploads, access.clock, access.logger) {
                    sink.tryEmit(it)
                },
                trackIt = access.trackIt,
                logger = access.logger,
                artifacts = access,
                eventSink = sink,
            )
        }

        private const val EVENT_BUFFER = 32
    }
}

/**
 * Retries the queue on a linear backoff, only when a network is actually available —
 * there is no point waking to fail.
 */
internal class SyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val sync = TrackerSync.getInstance(applicationContext)

        // Nothing is listening, so there is nothing this attempt can do and nothing a later
        // one could do either — `configure()` is the host's call, not an event this worker
        // can wait for. Reported as done rather than retried because a retry is not free:
        // it leaves this unique work ENQUEUED in backoff, and everything that then asks for
        // a drain under `KEEP` is silently dropped for as long as that lasts. A host that
        // configures sync later than `Application.onCreate` would otherwise poison the
        // queue on every process start.
        if (!sync.isConfigured) return Result.success()

        return when (val result = sync.syncNow()) {
            is SyncQueue.Result.Uploaded, SyncQueue.Result.Empty -> Result.success()
            // Terminal: teardown already happened, so retrying would only re-fail.
            SyncQueue.Result.AuthExpired -> Result.failure()
            // Terminal without a teardown — the rows are still queued, but nothing will
            // accept them until the host re-configures.
            SyncQueue.Result.Forbidden -> Result.failure()
            is SyncQueue.Result.Retry -> reschedule(sync, result)
        }
    }

    /**
     * `Result.retry()` cannot carry a delay — WorkManager applies the *request's* backoff
     * policy, fixed when the request was built, and `setInitialDelay` only affects a newly
     * enqueued one. So a server that named a time is honoured by enqueueing afresh and
     * reporting this attempt as done; without a time, the existing linear backoff stands.
     *
     * **Not every [SyncQueue.Result.Retry] is a failed exchange**, and the ones that are not
     * must never reach `Result.retry()`. A retry permanently increments `runAttemptCount`,
     * which grows the linear backoff for every *genuine* failure after it, and it parks this
     * unique work in `ENQUEUED` where `KEEP` swallows every subsequent drain request. Paying
     * that for a lock collision — a `syncNow()` from the host's UI overlapping this worker,
     * which is a drain succeeding on another thread — is the wrong trade in both directions.
     */
    private fun reschedule(sync: TrackerSync, result: SyncQueue.Result.Retry): Result {
        if (result.reason in NOT_A_FAILURE) return Result.success()

        val delayMs = result.retryAfterMs ?: return Result.retry()
        sync.rescheduleAfter(delayMs)
        return Result.success()
    }

    companion object {
        const val NAME = "fieldtrack-sync"

        /**
         * Retry reasons that describe this worker's own situation rather than a failed
         * exchange with the server. Neither is worth a backoff attempt: the queue is
         * already being drained by somebody else, or there is no configuration for a
         * later attempt to find.
         */
        private val NOT_A_FAILURE = setOf(
            SyncQueue.REASON_ALREADY_DRAINING,
            SyncQueue.REASON_NOT_CONFIGURED,
            SyncQueue.REASON_NO_TRANSPORT,
        )

        fun enqueue(context: Context, requiresUnmetered: Boolean) {
            WorkManager.getInstance(context)
                // KEEP, not REPLACE: a burst of accepted points must not reset the
                // backoff clock and hammer a server that is already struggling.
                .enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, requestFor(requiresUnmetered))
        }

        /**
         * The connectivity path — `REPLACE`, and see `TrackerSync.requestSyncOnReconnect`
         * for why `KEEP` made the rising edge a no-op whenever a previous attempt was
         * sitting in backoff.
         */
        fun enqueueNow(context: Context, requiresUnmetered: Boolean) {
            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    NAME,
                    ExistingWorkPolicy.REPLACE,
                    requestFor(requiresUnmetered),
                )
        }

        /**
         * The `Retry-After` path.
         *
         * `KEEP` above defends against a burst of accepted points resetting the backoff.
         * There is no burst here — this runs once, from a drain the server itself
         * rate-limited — and `KEEP` would discard the server's schedule in favour of our
         * 30 s default, which is the whole gap being closed.
         */
        fun enqueueAfter(context: Context, requiresUnmetered: Boolean, delayMs: Long) {
            val request = requestFor(requiresUnmetered) {
                setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            }
            WorkManager.getInstance(context)
                .enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, request)
        }

        private fun requestFor(
            requiresUnmetered: Boolean,
            extras: OneTimeWorkRequest.Builder.() -> Unit = {},
        ): OneTimeWorkRequest {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(
                    if (requiresUnmetered) NetworkType.UNMETERED else NetworkType.CONNECTED,
                )
                .build()

            return OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.LINEAR, BACKOFF_SECONDS, TimeUnit.SECONDS)
                .apply(extras)
                .build()
        }

        private const val BACKOFF_SECONDS = 30L
    }
}
