package com.field360.tracker.data.remote

import com.field360.tracker.API_TAG
import com.field360.tracker.domain.model.ApiError
import com.field360.tracker.domain.model.ApiErrorCode
import com.field360.tracker.domain.model.ApiResult
import com.field360.tracker.sdkLog
import com.field360.tracker.sdkWarn
import com.field360.traker.geo.port.TrackLogger
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import retrofit2.Response

/**
 * Runs one Retrofit call and turns everything that can happen into [ApiResult].
 *
 * The point of a shared executor is not saving lines — it is that the mapping from "what
 * went wrong" to a reported code exists **once**. A second endpoint added later inherits
 * the same taxonomy, the same redaction rules and the same never-throws guarantee, rather
 * than growing its own slightly different opinion about what a 429 means.
 *
 * **Bodies come back as the exact bytes that arrived**, which is why every service method
 * routed through here declares `Response<ResponseBody>` rather than a parsed type. The
 * licence response is signed over its exact text, so a converter that parses and
 * re-serialises breaks verification — and verification fails open, so it breaks invisibly.
 * See [LicenseService]. Parsing belongs to the caller, after the signature has been checked.
 */
internal class ApiCall(
    private val logger: TrackLogger = TrackLogger.NoOp,
) {

    /**
     * Never throws, and never returns a partial result. [label] appears in the log in
     * place of the URL's identifying parts.
     *
     * Takes the call as a lambda rather than a `retrofit2.Call` so the suspending service
     * method is invoked *inside* the guard: a Retrofit method can throw while building the
     * request — a malformed `@Url`, a converter refusing the argument — and that throw has
     * to become an [ApiError] like any other.
     */
    suspend fun execute(
        label: String,
        call: suspend () -> Response<ResponseBody>,
    ): ApiResult<String> = withContext(Dispatchers.IO) {
        val startedAt = System.nanoTime()

        runCatching {
            val response = call()

            // `errorBody` on failure: a 4xx from this API carries a JSON explanation, and
            // reading it is what lets the log say more than the status code.
            val body = (if (response.isSuccessful) response.body() else response.errorBody())
                ?.use { it.string() }
                .orEmpty()

            when {
                !response.isSuccessful -> fail(response.code().toApiError(), label, startedAt)

                body.isEmpty() ->
                    fail(ApiError(ApiErrorCode.EMPTY_BODY, response.code()), label, startedAt)

                else -> {
                    sdkLog {
                        logger.d(
                            API_TAG,
                            "$label -> HTTP ${response.code()} in " +
                                "${startedAt.msSince()}ms, ${body.length} bytes",
                        )
                    }
                    ApiResult.Success(body, response.code())
                }
            }
        }.getOrElse { thrown ->
            fail(thrown.toApiError(), label, startedAt)
        }
    }

    private fun fail(error: ApiError, label: String, startedAt: Long): ApiResult.Failure {
        // Warn, not error: nothing here is a verdict about a licence, and the caller
        // carries on regardless. It is worth noticing, not worth alarming about.
        sdkWarn {
            logger.w(API_TAG, "$label -> ${error.describe()} after ${startedAt.msSince()}ms")
        }
        return ApiResult.Failure(error)
    }

    /** Monotonic, so a wall-clock jump mid-call cannot produce a negative duration. */
    private fun Long.msSince(): Long = (System.nanoTime() - this) / NANOS_PER_MILLI

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}

/**
 * HTTP status to code. The only place this mapping lives.
 *
 * A reader will reasonably wonder why 401 gets its own value when the caller treats it the
 * same as a 500: because "the server refused to talk to us" and "the server fell over" send
 * a support conversation in completely different directions, and both are invisible in a
 * layer that fails open.
 */
internal fun Int.toApiError(): ApiError = ApiError(
    code = when (this) {
        HTTP_UNAUTHORIZED, HTTP_FORBIDDEN -> ApiErrorCode.UNAUTHORIZED
        HTTP_NOT_FOUND -> ApiErrorCode.NOT_FOUND
        HTTP_TOO_MANY_REQUESTS -> ApiErrorCode.RATE_LIMITED
        in CLIENT_ERROR_RANGE -> ApiErrorCode.CLIENT_ERROR
        in SERVER_ERROR_RANGE -> ApiErrorCode.SERVER_ERROR
        else -> ApiErrorCode.UNKNOWN
    },
    httpStatus = this,
)

/**
 * Exception to code. Type only — never the message of an arbitrary throwable and never a
 * stack trace: some frames carry the request URL with its parameters.
 */
internal fun Throwable.toApiError(): ApiError = when (this) {
    // Only reachable if a service method is ever declared without `Response<…>`; Retrofit
    // throws this instead of returning a value. Mapped rather than lumped into UNKNOWN so
    // the status still reaches the log if someone changes a signature.
    is retrofit2.HttpException -> code().toApiError()
    else -> genericApiError()
}

private fun Throwable.genericApiError(): ApiError = ApiError(
    code = when (this) {
        is SocketTimeoutException, is TimeoutException -> ApiErrorCode.TIMEOUT
        is UnknownHostException -> ApiErrorCode.NO_NETWORK
        is IOException -> ApiErrorCode.NO_NETWORK
        else -> ApiErrorCode.UNKNOWN
    },
    detail = javaClass.simpleName,
)

private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private const val HTTP_NOT_FOUND = 404
private const val HTTP_TOO_MANY_REQUESTS = 429
private val CLIENT_ERROR_RANGE = 400..499
private val SERVER_ERROR_RANGE = 500..599
