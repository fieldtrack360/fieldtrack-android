package com.field360.traker.sync.internal

import com.field360.traker.geo.port.Clock
import com.field360.traker.geo.port.TrackLogger
import com.field360.traker.sync.SyncRequest
import com.field360.traker.sync.SyncResponse
import com.field360.traker.sync.SyncTransport
import com.field360.traker.sync.sdkLog
import kotlinx.coroutines.CancellationException
import java.net.URI

/**
 * Writes one **logcat** line per HTTP exchange, then hands the response back untouched.
 *
 * ```
 * D/FieldTrackApi: POST points https://api.acme.test/v1/location/batch -> 200 success in 412ms (18422B gzip)
 * W/FieldTrackApi: POST logs   https://api.acme.test/v1/logs/batch -> 503 refused in 9004ms (2210B gzip) retryAfter=30000ms
 * ```
 *
 * ### Why logcat and not the diagnostic buffer
 *
 * These lines are for whoever has the device in their hand — `adb logcat -s FieldTrackApi`
 * during an integration, a QA run, a reproduction. That reader wants every exchange as it
 * happens, at no storage cost and with no upload attached.
 *
 * Putting the same lines in the uploaded buffer would be a different feature with a
 * different price: a row per upload stored on disk and shipped to a server, and — for the
 * log channel itself — an entry describing a log upload that the next log upload has to
 * ship, which writes another one. Here that problem does not exist, so **both** channels are
 * wrapped and `/v1/logs/batch` is logged like everything else.
 *
 * ### Debug builds only
 *
 * Every line goes through [sdkLog], so the whole block is compiled out when
 * `SDK_LOGGING_ENABLED` is false — which is what the release variant sets. A released app
 * writes nothing here, which is the right default for output any app on a rooted device can
 * read.
 *
 * ### What is never written
 *
 * Headers and bodies, in either direction. The credential is in a request header, and
 * `SyncResponse.Failure.body` can echo one back — the same reason that field is documented
 * as never logged by the SDK. The URL is written with its query string and userinfo removed
 * by [loggableUrl], because a token in a query parameter is the other half of that leak.
 * This is a diagnostic aid, not a proxy trace; use an OkHttp interceptor in a debug build if
 * you need the wire itself.
 */
internal class LoggingSyncTransport(
    private val delegate: SyncTransport,
    private val logger: TrackLogger,
    private val clock: Clock,
    /** `points` or `logs` — which queue this exchange belongs to. */
    private val channel: String,
) : SyncTransport {

    override suspend fun upload(request: SyncRequest): SyncResponse {
        val startedNanos = clock.elapsedRealtimeNanos()
        val response = try {
            delegate.upload(request)
        } catch (cancelled: CancellationException) {
            // A drain started from a `viewModelScope` is cancelled with the screen. That is
            // the caller changing its mind, not an exchange with an outcome, and logging it
            // as a failed call would put a warning in logcat every time a user pressed Back.
            throw cancelled
        } catch (failure: Throwable) {
            // The interface says implementations must not throw. A host's own transport may
            // anyway, and the throw is then the most useful thing this line could carry — so
            // it is written before it propagates, not swallowed.
            sdkLog {
                logger.w(
                    TAG,
                    line(request, null, elapsedMs(startedNanos)) +
                        " threw ${failure::class.java.simpleName}",
                )
            }
            throw failure
        }

        sdkLog {
            val text = line(request, response, elapsedMs(startedNanos))
            if (response is SyncResponse.Success) logger.d(TAG, text) else logger.w(TAG, text)
        }
        return response
    }

    /**
     * One line, and it has to read at a glance in a scrolling logcat: what was called, what
     * came back, how long it took, how big it was.
     *
     * `outcome` is printed beside the status rather than derived from it, because the case
     * that matters most has no status — a request that never reached a server is not a 500,
     * and reading them as one is what makes "the API is down" indistinguishable from "this
     * device has no signal".
     */
    private fun line(request: SyncRequest, response: SyncResponse?, durationMs: Long): String =
        buildString {
            append(request.method).append(' ').append(channel).append(' ')
            append(loggableUrl(request.url))
            append(" -> ").append(response?.httpCode() ?: NO_STATUS)
            append(' ').append(response.outcomeName())
            append(" in ").append(durationMs).append("ms")
            append(" (").append(request.jsonBody.length).append('B')
            if (request.gzip) append(" gzip")
            append(')')
            (response as? SyncResponse.Failure)?.let { failure ->
                // Only where the server named one. Its absence and a value of zero are
                // different facts about a 429.
                failure.retryAfterMs?.let { append(" retryAfter=").append(it).append("ms") }
                // The transport's own summary — a timeout, a DNS error. Never the body.
                if (failure.message.isNotBlank()) append(" \"").append(failure.message).append('"')
            }
        }

    private fun SyncResponse.httpCode(): Int? = when (this) {
        is SyncResponse.Success -> code
        SyncResponse.Unauthorized -> HTTP_UNAUTHORIZED
        SyncResponse.Forbidden -> HTTP_FORBIDDEN
        is SyncResponse.Failure -> code
    }

    private fun SyncResponse?.outcomeName(): String = when {
        this is SyncResponse.Success -> "success"
        this is SyncResponse.Unauthorized -> "unauthorized"
        this is SyncResponse.Forbidden -> "forbidden"
        this is SyncResponse.Failure && code == null -> "no_response"
        this is SyncResponse.Failure -> "refused"
        else -> "no_response"
    }

    private fun elapsedMs(startedNanos: Long): Long =
        (clock.elapsedRealtimeNanos() - startedNanos) / NANOS_PER_MILLI

    internal companion object {
        /** Its own tag, so `adb logcat -s FieldTrackApi` is the whole API conversation. */
        const val TAG = "FieldTrackApi"

        const val CHANNEL_POINTS = "points"
        const val CHANNEL_LOGS = "logs"

        /**
         * Printed where a status code would go when there was no HTTP exchange to give one.
         *
         * Deliberately not `0`: a reader scanning a column of status codes should not have
         * to know that one of them is a fiction.
         */
        const val NO_STATUS = "-"

        const val NANOS_PER_MILLI = 1_000_000L
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
    }
}

/**
 * Scheme, host, port and path. **The query string and any userinfo are dropped.**
 *
 * Not cosmetic. `?token=…` and `https://user:secret@host/…` are both common, both are
 * credentials, and logcat on a rooted or developer device is readable. The path is kept
 * because "which endpoint" is the whole value of the line.
 *
 * A URL that will not parse degrades to the part before the first `?`, which is the honest
 * answer when the structure cannot be trusted: better a truncated string than a whole one
 * that might carry the query.
 */
internal fun loggableUrl(url: String): String {
    val parsed = runCatching { URI(url) }.getOrNull()
        ?: return url.substringBefore('?')
    val scheme = parsed.scheme ?: return url.substringBefore('?')
    val host = parsed.host ?: return url.substringBefore('?')
    return buildString {
        append(scheme).append("://").append(host)
        if (parsed.port != -1) append(':').append(parsed.port)
        parsed.rawPath?.let { append(it) }
    }
}
