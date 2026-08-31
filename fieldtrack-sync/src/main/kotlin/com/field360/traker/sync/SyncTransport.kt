package com.field360.traker.sync

import kotlinx.serialization.Serializable

/**
 * How a batch actually reaches a server.
 *
 * Pluggable on purpose. Most apps already have an HTTP client configured with their own
 * auth interceptors, certificate pinning, retry policy and logging; forcing a second one
 * on them is exactly the version-conflict problem that keeps Retrofit and OkHttp out of
 * `fieldtrack-core` (EKF-DESIGN-REVIEW §S5).
 *
 * The default [OkHttpSyncTransport] exists for convenience, and OkHttp is `compileOnly`
 * here — supply your own transport and you never pull it in.
 */
public interface SyncTransport {

    /**
     * Implementations must **not** throw: a network failure is an expected state, not an
     * exception, and the queue depends on being told which of the three it was.
     */
    public suspend fun upload(request: SyncRequest): SyncResponse
}

/**
 * Request timeouts, as plain numbers.
 *
 * Deliberately not an OkHttp type. The whole point of the [SyncTransport] seam is that a
 * host supplying its own client never links OkHttp, so the one place timeouts are
 * *configured* must not be typed against it — otherwise overriding a read timeout drags
 * the dependency back in through the front door.
 */
public data class SyncTimeouts(
    val connectMs: Long = DEFAULT_CONNECT_MS,
    val readMs: Long = DEFAULT_READ_MS,
    val writeMs: Long = DEFAULT_WRITE_MS,
) {
    public companion object {
        public const val DEFAULT_CONNECT_MS: Long = 5_000
        public const val DEFAULT_READ_MS: Long = 30_000
        public const val DEFAULT_WRITE_MS: Long = 20_000
    }
}

/**
 * @property gzip the host asked for a compressed body. A transport that cannot compress is
 *   free to ignore it and send the JSON as-is — the server sees a valid request either way,
 *   which is not true in reverse.
 */
public data class SyncRequest(
    val url: String,
    val method: String,
    val headers: Map<String, String>,
    val jsonBody: String,
    val gzip: Boolean = false,
    val timeouts: SyncTimeouts = SyncTimeouts(),
)

/**
 * @property Success the batch was accepted and may be marked synced.
 * @property Unauthorized a 401. Distinct from [Failure] because it is **terminal**:
 *   retrying cannot help, and the SDK responds by tearing the session down rather than
 *   looping (spec §3.3, §11.2).
 * @property Forbidden a 403. Also terminal, but for a different reason and with different
 *   consequences — see [SyncQueue.Result.Forbidden]. Before this existed a revoked key
 *   retried forever, which is the exact silent battery burn 401 handling exists to prevent.
 * @property Failure anything else — the rows stay queued and are retried with backoff.
 */
public sealed interface SyncResponse {
    public data class Success(val code: Int) : SyncResponse
    public data object Unauthorized : SyncResponse
    public data object Forbidden : SyncResponse

    /**
     * @property body at most [MAX_BODY_CHARS] characters of the error body, or `null` if the
     *   server sent none. Bounded because an error page can be megabytes, and present at all
     *   because "500" alone cannot tell a host whether it sent bad JSON or hit a dead
     *   database. Never logged by the SDK — an error body can echo a request header.
     * @property retryAfterMs the server's own retry delay, parsed from `Retry-After`. When
     *   set it wins over the SDK's schedule: a 429 means the server has already said when it
     *   wants to be asked again.
     */
    public data class Failure(
        val code: Int?,
        val message: String,
        val body: String? = null,
        val retryAfterMs: Long? = null,
    ) : SyncResponse {
        public companion object {
            public const val MAX_BODY_CHARS: Int = 4_096
        }
    }
}

/**
 * The upload payload.
 *
 * snake_case keys and epoch milliseconds, matching the reference server contract
 * (spec §11.2). Remap in your own [SyncTransport] if your backend differs — that is
 * cheaper than making this configurable.
 */
@Serializable
public data class SyncPayload(
    val location: List<SyncPoint>,
)

@Serializable
public data class SyncPoint(
    val uuid: String,
    val time: Long,
    val local_date: String,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val movementSpeed: Float,
    /**
     * The location subsystem as it was **when this point was captured** — providers,
     * permission, accuracy authorization and airplane mode.
     *
     * `null` for a point stored before the SDK recorded it, which is deliberately not an
     * object full of `false`: "we did not look" and "everything was off" are different
     * answers about a point that plainly exists.
     *
     * **Breaking change.** This key previously carried the provider *name* as a string
     * (`"gps"`, `"fused"`). That name has not been lost — [activity_status] is
     * `"<provider>@<movementStatus>"` and always was, so a backend that needs the string
     * reads it from there.
     */
    val provider: SyncProvider? = null,
    val hasSpeed: Boolean,
    val hasBearing: Boolean,
    val time_zone: String,
    val activity_status: String,
    val detected_activity_type: String? = null,
    val detected_activity_start_time: Long = 0,
    /** 0–100 as a string, matching the reference contract. `null` when the platform will not say. */
    val battery_percentage: String? = null,
    /**
     * Plugged in or full. `null` when the platform will not say — deliberately not `false`,
     * which would read as "confirmed on battery".
     */
    val is_charging: Boolean? = null,
    val is_mock: Boolean = false,
    /**
     * Device-integrity bitmask observed when this point was captured — `IntegrityReport.flags`
     * in `fieldtrack-core`, whose bit assignments are frozen.
     *
     * `0` means "nothing observed", which is also what a debuggable build and a host with
     * the layer disabled send. Defaulted so a backend that has never seen the field keeps
     * parsing, and so a payload recorded before this field existed still deserializes.
     */
    val integrity_flags: Int = 0,
    /**
     * The same signals by name, for a backend rule that would rather match on
     * `"HOOKING_FRAMEWORK_DETECTED"` than on bit 3. Redundant with [integrity_flags] and
     * deliberately so — the mask is the durable storage form, this is the readable one.
     */
    val integrity_signals: List<String> = emptyList(),
    /**
     * The id of the session that recorded this point, or `null` when
     * `SyncConfig.includePointSessionId` is off (the default, and the shape every release
     * before this one sent).
     *
     * **Why a per-row field exists at all.** A host that puts its session id in
     * `SyncConfig.extraParams` puts it in the *envelope*, and an envelope field describes
     * the whole batch. That is true for an online device, whose queue drains before each
     * session ends — and false for exactly the offline backlog this SDK is built to
     * survive: rows recorded across two drives upload in one batch, under whichever id was
     * current at the last `configure()`. Worse, `configure()` usually runs from a UI that
     * is not alive when a killed process's `SyncWorker` drains the queue, so the envelope
     * field is stale or missing precisely when the backlog is largest.
     *
     * This field cannot be any of those things: it is read from the row.
     *
     * Serialized as `session_id`, and omitted rather than sent null — `explicitNulls` is
     * off in [SyncQueue], so a backend that has never seen this key keeps parsing.
     */
    val session_id: String? = null,
)

/**
 * The location subsystem at capture time, as the backend expects it.
 *
 * The numeric fields carry codes rather than names because that is the existing wire
 * contract on the server side; `ProviderSnapshot` in `fieldtrack-geo` is where they are
 * defined and documented.
 *
 * @property network the network (Wi-Fi/cell) provider is enabled.
 * @property gps the GPS provider is enabled.
 * @property enabled the location master switch. Not the union of the two above — a device
 *   can report location enabled with GPS switched off.
 * @property status permission tier: `0` not determined, `1` restricted, `2` denied,
 *   `3` always (foreground + background), `4` while in use. Android cannot distinguish
 *   "never asked" from "refused", so it never emits `0`.
 * @property accuracyAuthorization `0` full (fine location), `1` reduced (coarse only).
 * @property airplane airplane mode was on. Not a gate: GPS keeps working in airplane mode
 *   on most devices while network positioning does not.
 */
@Serializable
public data class SyncProvider(
    val network: Boolean,
    val gps: Boolean,
    val enabled: Boolean,
    val status: Int,
    val accuracyAuthorization: Int,
    val airplane: Boolean,
)
