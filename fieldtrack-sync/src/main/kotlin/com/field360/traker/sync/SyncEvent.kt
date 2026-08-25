package com.field360.traker.sync

/**
 * What the upload half is doing, as it happens.
 *
 * [TrackerSync.syncNow] already returns the outcome of a drain the host asked for. This
 * exists for the drains it did not: [TrackerSync.requestSync] hands the work to WorkManager,
 * which may run it minutes later in a process the host is not watching, and until now the
 * only trace of what the server said was a debug log.
 *
 * Deliberately **not** a case on `TrackerEvent`. That flow belongs to `fieldtrack-core`, which
 * never opens a socket; putting HTTP status codes in it would mean a host with no upload
 * module compiling against events it can never receive.
 */
public sealed interface SyncEvent {

    /**
     * One per completed exchange — so a three-batch drain emits three.
     *
     * @property statusCode what the server answered, or `null` when no HTTP response arrived
     *   at all (a dead network, a DNS failure, a timeout). The distinction matters: a `null`
     *   is a device problem and a 500 is a server problem, and a host showing "last upload"
     *   in a diagnostics screen should not report them the same way.
     * @property count rows in that batch. On a failure they are still queued — a count here
     *   is what was *attempted*, not what was stored.
     *
     * The response body is deliberately absent. It can be megabytes, and a host that needs
     * it implements [SyncTransport] and sees the whole exchange.
     */
    public data class HttpResponse(val statusCode: Int?, val count: Int) : SyncEvent

    /**
     * The device came back onto a usable network with rows still queued, and a drain was
     * requested because of it.
     *
     * The event a host needs to turn "offline, 240 queued" into "syncing" without polling
     * [TrackerSync.pendingCount] on a timer. Emitted on the rising edge only — a
     * reconnection, not a heartbeat — and never when the queue is empty, because a
     * reconnection with nothing to send is not something a user needs to be told about.
     *
     * Only fires while the process is alive. A drain that WorkManager runs later, in a
     * process the host is not watching, reports itself through [HttpResponse] as usual.
     *
     * @property queued rows waiting at the moment the network returned. What was
     *   *attempted*, not what was stored — the drain itself reports that.
     */
    public data class NetworkAvailable(val queued: Int) : SyncEvent
}
