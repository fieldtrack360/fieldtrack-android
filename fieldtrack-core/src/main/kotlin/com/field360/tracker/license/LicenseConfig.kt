package com.field360.tracker.license

import android.content.Context
import android.content.pm.PackageManager
import com.field360.tracker.BuildConfig
import java.util.Base64

/**
 * Where the online check's two build-time inputs come from.
 *
 * Both default to empty, and empty means the check never runs — `VerdictAuthenticator.isConfigured`
 * is false, `CheckLicenseRevocationUseCase` returns `CarryOn` immediately, and no request
 * is ever made. That is the honest default for an unconfigured build: a licence layer that
 * fell back to trusting whatever answered would be worse than none.
 *
 * **Both must be filled before release enforcement means anything.**
 */
internal object LicenseConfig {

    /**
     * The licence API **root, including the version segment** — e.g.
     * `https://licence.example.com/api/v1`. `RetrofitLicenseApi` appends `/verify`
     * to it and nothing else, so the version lives here rather than in the SDK: moving
     * to `/api/v2` becomes a configuration change instead of a release.
     *
     * Not hardcoded. It comes from `BuildConfig.LICENSE_BASE_URL`, which the module's
     * build file resolves from the Gradle property `fieldtrackLicenseUrl`, the
     * environment variable `FIELDTRACK_LICENSE_URL`, or the committed
     * `configuration.properties` at the repository root — never `local.properties`, so
     * every clone and CI runner builds against the same endpoint:
     *
     * ```properties
     * FIELDTRACK_LICENSE_URL=https://licence.example.com/api/v1
     * ```
     *
     * Committed on purpose, because it is not a secret. The value is compiled into the
     * artifact and is readable in any published AAR or installed APK. An endpoint the
     * device has to reach cannot be hidden from the device, and nothing here should ever
     * carry a credential.
     *
     * Overridable per install from the host manifest, which takes precedence, for
     * pointing a build at a local server without rebuilding the SDK:
     *
     * ```xml
     * <meta-data android:name="FieldTrackLicenseUrl" android:value="http://10.0.2.2:5858/api/v1" />
     * ```
     *
     * `10.0.2.2` is the emulator's route to the host machine, and reaching it over
     * cleartext needs a **debug-only** network-security config. Release builds use https.
     */
    val defaultBaseUrl: String get() = BuildConfig.LICENSE_BASE_URL

    /**
     * The key that verifies `/verify` **responses**, standard base64 of 32 raw bytes,
     * from `GET /api/v1/public-key`.
     *
     * Not hardcoded, and not fetched. It comes from `BuildConfig.LICENSE_RESPONSE_KEY`,
     * which the build file resolves from `-PfieldtrackResponseKey`, the environment
     * variable `FIELDTRACK_RESPONSE_KEY`, or the committed `configuration.properties`:
     *
     * ```properties
     * FIELDTRACK_RESPONSE_KEY=Base64OfThirtyTwoRawBytes=
     * ```
     *
     * This key authenticates what the server says about the licence today — it is the
     * only compiled-in key since the offline token gate was removed.
     *
     * **Build-time, never runtime.** Fetching it from the same server whose answers it
     * authenticates would be circular, and a device owner with a proxy would simply serve
     * their own. It is committed and it ships inside the artifact, and both are correct —
     * a public key is not a secret, and the whole point of compiling it in is that nobody
     * can substitute it.
     */
    val responsePublicKeyBase64: String get() = BuildConfig.LICENSE_RESPONSE_KEY

    /** Empty when unset or malformed, which the authenticator reads as "not configured". */
    fun responsePublicKey(): ByteArray =
        runCatching { Base64.getDecoder().decode(responsePublicKeyBase64) }
            .getOrNull()
            ?.takeIf { it.size == Ed25519.PUBLIC_KEY_BYTES }
            ?: ByteArray(0)

    /** The manifest override, else [defaultBaseUrl]. Blank means no check is made. */
    fun baseUrl(context: Context): String {
        val fromManifest = runCatching {
            context.packageManager
                .getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
                .metaData
                ?.getString(BASE_URL_META_DATA)
        }.getOrNull()

        return fromManifest?.takeIf { it.isNotBlank() } ?: defaultBaseUrl
    }

    const val BASE_URL_META_DATA: String = "FieldTrackLicenseUrl"
}
