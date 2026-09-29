package com.field360.tracker.domain.model

/**
 * What [com.field360.tracker.Tracker.wake] found and did.
 *
 * Returned for the host to log or report back to its server — the server sent the wake
 * because it had stopped hearing from this device, and this is the device's side of that
 * story. The same value is also emitted as a `remote wake: <name>` diagnostic, so it
 * reaches the SDK log channel without the host doing anything.
 */
public enum class WakeResult {
    /** The service was already running. A fix and an upload were requested. */
    ALIVE,

    /** A session was open with no service behind it, and the service was started. */
    REVIVED,

    /**
     * The platform refused the foreground-service start. The counted restore path
     * (`ServiceRestorer`) was queued, which retries with backoff.
     */
    REFUSED,

    /** No session is open. Nothing was started — a wake never creates a session. */
    NO_SESSION,

    /** The host configured `ServiceConfig.foregroundService = false`; nothing to start. */
    DISABLED,

    /** Called on the main thread, so the work was handed to a background coroutine. */
    DISPATCHED,

    /** The session lookup did not finish inside the wake window; nothing was started. */
    TIMED_OUT,
}
