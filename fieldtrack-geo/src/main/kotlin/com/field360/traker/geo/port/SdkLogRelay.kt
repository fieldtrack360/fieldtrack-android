package com.field360.traker.geo.port

/**
 * The SDK's own log output, made available to something other than logcat.
 *
 * Every internal line the SDK writes — a provider that went quiet, a fix the accuracy gate
 * rejected, a licence verdict, a worker that gave up — goes through `TrackLogger` and ends
 * at `Log.d`/`Log.w`. That is a stream with no memory: it exists while somebody has the
 * device in their hand, and a released build does not write it at all. The one incident
 * anyone ever needs it for — a phone in the field, yesterday, that stopped recording — is
 * exactly the one nobody was watching.
 *
 * Installing a [sink] here makes that stream durable without the host writing a single
 * `log()` call. `fieldtrack-sync` installs one when a log channel is configured, so the
 * same lines that would have gone to logcat are also buffered and shipped.
 *
 * ### Why a mutable global rather than a constructor parameter
 *
 * The logger is built once, at graph construction, and handed to fifteen collaborators that
 * hold it for the process's lifetime. A log channel is configured later — usually inside
 * `Application.onCreate`, sometimes after a login — and can be turned off again. There is no
 * point at which a sink could have been passed down, and rebuilding the graph to attach one
 * would restart tracking to change a diagnostic setting.
 *
 * ### Off unless something installs a sink
 *
 * [isActive] is `false` in a process that never configured log shipping, and every method
 * here is then a volatile read and a branch. It also gates the `sdkLog` blocks in core and
 * sync: those compile out in release builds, *except* when a sink is installed, which is the
 * only way a released app can diagnose itself. A host that never turns logs on pays exactly
 * what it paid before.
 */
public object SdkLogRelay {

    @Volatile
    private var sink: TrackLogger? = null

    /**
     * Guards against a sink that logs.
     *
     * The sink writes to a database, and that write path logs when it drops a row or fails
     * to parse one — through the same logger that feeds this relay. Without this flag that
     * is unbounded recursion on the thread that emitted the first line. Thread-local rather
     * than a single flag because the relay is called from every thread the SDK uses, and one
     * thread suppressing another's line would lose entries rather than loops.
     */
    private val dispatching: ThreadLocal<Boolean> = ThreadLocal.withInitial { false }

    /**
     * Whether anything is listening.
     *
     * Read on the hot path by `sdkLog`, so it is a volatile field read and nothing else.
     * Never cache it: a host can configure or disable log shipping at any point.
     */
    public val isActive: Boolean get() = sink != null

    /**
     * Starts or stops relaying. `null` stops it.
     *
     * Idempotent, and last call wins — a second log channel replaces the first rather than
     * fanning out to both, matching `configureLogs()`, which replaces its config rather than
     * accumulating configs.
     */
    public fun install(sink: TrackLogger?) {
        this.sink = sink
    }

    /** Relays a debug line. Cheap and silent when nothing is installed. */
    public fun d(tag: String, message: String) {
        dispatch(tag) { it.d(tag, message) }
    }

    /** Relays a warning. */
    public fun w(tag: String, message: String) {
        dispatch(tag) { it.w(tag, message) }
    }

    private inline fun dispatch(tag: String, send: (TrackLogger) -> Unit) {
        val target = sink ?: return
        if (tag in MUTED_TAGS) return
        // `== true` rather than a bare read: ThreadLocal.get() is a platform type, and the
        // null case means "this thread has never dispatched", which is not re-entrant.
        if (dispatching.get() == true) return

        dispatching.set(true)
        try {
            // Never allowed to throw into the caller. This sits inside `sdkLog` blocks all
            // over the SDK, and a diagnostic channel that can crash the thing it is
            // diagnosing is worse than no diagnostic channel.
            runCatching { send(target) }
        } finally {
            dispatching.set(false)
        }
    }

    /**
     * Tags that are written to logcat but never relayed.
     *
     * `FieldTrackApi` is the upload log — one line per request, on **both** channels. Relayed,
     * a log upload would write an entry describing itself, which the next log upload ships,
     * which writes another: a channel that can never go quiet and a periodic drain that
     * always has something to send. `LoggingSyncTransport` documents that this is exactly
     * why it stays out of the buffer, and this preserves it.
     */
    private val MUTED_TAGS = setOf("FieldTrackApi")
}
