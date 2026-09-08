package com.field360.traker.sync

import kotlinx.serialization.Serializable

/**
 * The log endpoint's payload (SYNC-BACKEND-AND-DASHBOARD.md §11.3–§11.4).
 *
 * snake_case keys and epoch milliseconds, matching the points contract next door. Remap in
 * your own [SyncTransport] if your backend differs.
 */
@Serializable
public data class LogPayload(
    val device_id: String,
    val uploaded_at: Long,
    val app: LogAppInfo,
    val device: LogDeviceInfo,
    val logs: List<LogEntryDto>,
)

/**
 * The host app, as it was when these entries were recorded.
 *
 * Per batch rather than per entry: it is the same two hundred bytes on every row
 * otherwise, and "which build was this" is most of what a "works on my device" ticket
 * turns out to be about.
 */
@Serializable
public data class LogAppInfo(
    val `package`: String,
    val version: String,
    val build: Long,
    /** The SDK's own version, so a fleet mid-rollout is readable. */
    val sdk: String,
)

@Serializable
public data class LogDeviceInfo(
    val manufacturer: String,
    val model: String,
    /** `Build.VERSION.SDK_INT`. */
    val os: Int,
)

/**
 * One entry.
 *
 * @property id the dedupe key and the server's primary key —
 *   `SHA-1("<session_id>:<seq>:<type>:<elapsed_nanos>")`. A batch that reached the server
 *   and lost its response is re-sent whole, so a re-delivered entry has to collide rather
 *   than duplicate (§11.6).
 * @property session_id `null` for an entry emitted outside any session — a boot, a service
 *   start, a `configure()`. Omitted rather than sent null, since `explicitNulls` is off in
 *   [LogSyncQueue]: a backend stores those against the device, not a session.
 * @property seq monotonic per session **and per type**, from 0. A gap is the honest record
 *   of a bounded buffer dropping its oldest entries, and is meant to be rendered rather
 *   than hidden.
 * @property elapsed_nanos monotonic since boot, and what the server orders on. Sent as a
 *   **string**, because a JSON number above 2^53 loses precision in every JavaScript
 *   backend that will ever read it — and a boot-relative nanosecond count crosses that
 *   after 104 days of uptime.
 * @property time device wall clock. Display only: it can jump backwards mid-session.
 * @property data type-specific detail as a JSON object, passed through verbatim. Omitted
 *   when the entry carries none.
 */
@Serializable
public data class LogEntryDto(
    val id: String,
    val session_id: String? = null,
    val seq: Long,
    val time: Long,
    val elapsed_nanos: String,
    val level: String,
    val type: String,
    val tag: String,
    val code: String? = null,
    val message: String,
    val data: kotlinx.serialization.json.JsonElement? = null,
)
