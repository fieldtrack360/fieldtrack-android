package com.field360.traker.sync

/**
 * One diagnostic entry, in the shape the log endpoint receives it
 * (`docs/APP-LOG-API.md` §3).
 *
 * The other half of a support ticket. Points answer *where the device was*; they cannot
 * answer *why there is nothing there* — the permission revoked at 14:31, the provider
 * switched off, the process an OEM killed. A track with a twenty-minute hole in it is
 * unreadable on its own; the same hole next to a `CaptureSuspended(LOCATION_DISABLED)` and
 * a `CaptureResumed` twenty minutes later is a closed ticket.
 *
 * Deliberately **not** a `TrackerEvent`. An event is a live notification with typed
 * payloads a host branches on; this is a durable, flattened, uploadable record, and the two
 * have opposite requirements — an event gains fields freely, a wire record cannot.
 */
public data class LogRecord(
    /**
     * The dedupe key the server stores as its primary key —
     * `SHA-1("<sessionId>:<seq>:<type>:<elapsedRealtimeNanos>")`, 40 hex characters.
     *
     * Deterministic for the same reason `TrackPoint.uuid` is: a batch that reached the
     * server and lost its response is re-sent whole, and a re-delivered entry has to
     * collide rather than duplicate. Retrying is therefore free by design.
     */
    val id: String,
    /**
     * The session this entry belongs to, or `null` for one emitted outside any session —
     * a boot, a service start, a `configure()`.
     *
     * `null` is a real value here and not a gap. Bucketing a boot-time entry under
     * whichever session happened to be current at upload time would make it lie; the
     * server stores those against the device instead.
     */
    val sessionId: String?,
    /**
     * Monotonic per session **and per type**, from 0.
     *
     * Two device sources with two independent counters — recorded entries and converted
     * decisions — so a shared space would collide on every session that carried both, and
     * the server reads a collision as loss. The buffer is bounded, so a gap in this number
     * is the honest record of eviction. Nothing here hides it.
     */
    val seq: Long,
    /** Device wall clock. Display only — [elapsedRealtimeNanos] is the ordering key. */
    val timeMs: Long,
    /**
     * Monotonic since boot, and what everything downstream sorts on.
     *
     * The wall clock can jump backwards mid-session (a manual change, an NTP correction).
     * This cannot — it only resets on reboot, which is exactly what [seq] disambiguates.
     * It travels as a **string**, because the count passes 2^53 after 104 days of uptime
     * and a JSON number rounds silently past that.
     */
    val elapsedRealtimeNanos: Long,
    val level: LogLevel,
    val type: LogType,
    /** Subsystem that emitted it — `CaptureGate`, `AcceptancePipeline`, a host tag. */
    val tag: String,
    /**
     * `ErrorCode` name, `TrackerEvent` class name, or a `Reasons` string. Nullable and
     * non-unique: this is what a week of one device's logs gets grouped by.
     */
    val code: String?,
    val message: String,
    /**
     * Type-specific detail as a JSON **object or array**, or `null`.
     *
     * Carried as text and never inspected beyond "is it valid JSON". The server stores it
     * verbatim as JSONB for the same reason: the value of a log line is the field nobody
     * modelled in advance.
     */
    val data: String?,
)

/**
 * Severity, and the knob that decides what is recorded at all.
 *
 * Ordered: a configured level admits itself and everything above it, so `WARN` records
 * warnings and errors and drops the rest — at **record** time, before a row is written,
 * which is what keeps the upload queue a strict FIFO and stops a device storing what it
 * will never send.
 */
public enum class LogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR,
    ;

    /** True when an entry at this level should be kept by a recorder set to [minimum]. */
    public fun admits(minimum: LogLevel): Boolean = ordinal >= minimum.ordinal

    /** The lowercase wire form. */
    public val wireName: String get() = name.lowercase()
}

/**
 * What kind of entry this is. Decides which shape the server expects in [LogRecord.data] —
 * and is the second gate, because one member is three orders of magnitude noisier than the
 * rest.
 */
public enum class LogType {
    /** From `TrackerEvent`: permission and provider changes, errors, capture suspensions. */
    EVENT,

    /**
     * Why one fix was accepted, skipped or rejected, with the arithmetic.
     *
     * **~29 000 entries per device per 8-hour shift** — the same order as `track_point`,
     * and wider. Off by default, and worth turning on for a named device with a ticket
     * open rather than for a fleet.
     *
     * Not buffered like the others: these already exist in the SDK's decision log, and are
     * read and converted at send time rather than written twice.
     */
    DECISION,

    /** A free-form host line, via `TrackerSync.log(...)`. */
    MESSAGE,

    /**
     * Session and service boundaries — the entries the points endpoint has no way to send.
     *
     * The only place a session start or stop ever reaches the server, and **advisory**
     * because this channel is lossy: annotate a session with it, never treat it as the
     * sole truth for the session's bounds.
     */
    LIFECYCLE,
    ;

    public val wireName: String get() = name.lowercase()
}

/** The `phase` values a [LogType.LIFECYCLE] entry carries in its `data` object. */
public object LifecyclePhase {
    public const val SESSION_START: String = "session_start"
    public const val SESSION_STOP: String = "session_stop"
    public const val SESSION_INTERRUPTED: String = "session_interrupted"
    public const val SERVICE_START: String = "service_start"
    public const val SERVICE_STOP: String = "service_stop"
    public const val PROCESS_START: String = "process_start"
    public const val BOOT_COMPLETED: String = "boot_completed"
    public const val CONFIG_CHANGED: String = "config_changed"
}
