package com.field360.tracker.data.remote

import com.field360.tracker.API_TAG
import com.field360.tracker.domain.model.ApiError
import com.field360.tracker.domain.model.ApiErrorCode
import com.field360.tracker.domain.model.ApiResult
import com.field360.tracker.domain.model.LicenseCheckRequest
import com.field360.tracker.domain.repository.LicenseApi
import com.field360.tracker.sdkLog
import com.field360.traker.geo.port.TrackLogger
import com.google.gson.GsonBuilder
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/**
 * The default [LicenseApi]. The only place in the licence layer that knows about HTTP.
 *
 * Timeouts are short on purpose. This work is never urgent — it runs unawaited from
 * `ready()` and on a WorkManager tick — and a long timeout only keeps a wakelock alive for
 * a call that fails open anyway. `retryOnConnectionFailure` is off for the same reason: the
 * next scheduled check is the retry.
 *
 * [baseUrl] is the API root including its version segment, e.g.
 * `https://licence.example.com/api/v1` — see `LicenseConfig.defaultBaseUrl`. Blank is a
 * supported state and means an unconfigured build: no request is attempted, and no Retrofit
 * instance is built.
 *
 * Logs to `Tracker/API_CALL` in debug builds — see [API_TAG] for what is deliberately kept
 * out of those lines, and [RedactingLogInterceptor] for how.
 */
internal class RetrofitLicenseApi(
    private val baseUrl: String,
    private val logger: TrackLogger = TrackLogger.NoOp,
    private val client: OkHttpClient = defaultClient(logger),
) : LicenseApi {

    private val call = ApiCall(logger)

    /**
     * `disableHtmlEscaping` matters on the request side too. It is not what the server
     * signs, but keeping one Gson configuration for the whole layer means the canonical
     * form in [CanonicalJson] and the bytes we send cannot drift apart in a future edit.
     */
    private val gson = GsonBuilder().disableHtmlEscaping().create()

    /**
     * Built lazily, and null when [baseUrl] is unusable.
     *
     * `Retrofit.Builder().baseUrl(...)` throws on a malformed URL, and this is constructed
     * from a build property that a human typed. Throwing at graph-construction time would
     * turn a typo in `configuration.properties` into a crash at launch, in a layer whose entire
     * contract is to fail open.
     */
    private val service: LicenseService? by lazy {
        runCatching {
            Retrofit.Builder()
                // Retrofit requires a trailing slash, and drops the last path segment
                // without one — `…/api/v1` + `verify` would resolve to `…/api/verify`,
                // a 404 that fails open and looks exactly like a healthy licence.
                .baseUrl(baseUrl.trimEnd('/') + "/")
                .client(client)
                .addConverterFactory(GsonConverterFactory.create(gson))
                .build()
                .create(LicenseService::class.java)
        }.getOrNull()
    }

    override suspend fun verify(request: LicenseCheckRequest): ApiResult<String> {
        if (baseUrl.isBlank()) {
            sdkLog { logger.d(API_TAG, "skipped: no licence URL configured for this build") }
            return ApiResult.Failure(ApiError(ApiErrorCode.NOT_CONFIGURED))
        }

        val api = service ?: run {
            sdkLog { logger.w(API_TAG, "licence URL is not a usable base URL: $baseUrl") }
            return ApiResult.Failure(ApiError(ApiErrorCode.NOT_CONFIGURED, detail = "bad base URL"))
        }

        // `request.accessKey` is deliberately absent from this line and from every line
        // ApiCall writes. The package and SDK identity are not secrets; the access key is.
        sdkLog {
            logger.d(
                API_TAG,
                "POST ${baseUrl.trimEnd('/')}$VERIFY_PATH pkg=${request.packageName} " +
                    "sdk=${request.sdkType}/${request.sdkVersion ?: "?"}",
            )
        }

        return call.execute(label = "POST $VERIFY_PATH") { api.verify(request.toDto()) }
    }

    internal companion object {
        /**
         * Only for the log and for tests — the real path lives in [LicenseService]'s
         * annotation. The `/api/v1` half deliberately sits in the configured URL, so a
         * version bump on the server is a properties change instead of an SDK release.
         */
        const val VERIFY_PATH: String = "/verify"

        private const val TIMEOUT_SECONDS = 10L

        /**
         * The OkHttp client Retrofit runs on. Retrofit builds one for you if you do not
         * supply one, and that default has a 10-second connect timeout, no read timeout
         * worth the name, and no logging — so it is supplied here rather than inherited.
         *
         * [RedactingLogInterceptor] rather than `okhttp3:logging-interceptor`: the latter
         * at `BODY` level would write `access_key` to logcat in full.
         */
        fun defaultClient(logger: TrackLogger = TrackLogger.NoOp): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .addInterceptor(RedactingLogInterceptor(logger))
                .build()
    }
}
