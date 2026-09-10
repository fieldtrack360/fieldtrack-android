package com.field360.tracker

import com.field360.traker.geo.port.SdkLogRelay

/**
 * Debug-build logging: the SDK's running commentary.
 *
 * `SDK_LOGGING_ENABLED` is false in release, so the whole block — message construction
 * included — is absent from release bytecode, and R8 strips the string constants with it.
 * That is not only a size decision: those strings narrate what the SDK does internally, and
 * a published AAR is readable by anyone. `verifyReleaseObfuscation` asserts a sample of them
 * never ships.
 *
 * Use this for anything a developer reads while holding the device. For a line that has to
 * survive to explain an incident on somebody else's phone, use [sdkWarn].
 */
internal inline fun sdkLog(block: () -> Unit) {
    if (BuildConfig.SDK_LOGGING_ENABLED) block()
}

/**
 * Logging that survives into release builds — but only while a host is listening.
 *
 * Two readers, and the second is the whole reason this is not [sdkLog]:
 *
 * 1. **logcat** — debug builds, exactly as [sdkLog].
 * 2. **the diagnostic buffer** — whenever a host has configured log shipping, in *any* build
 *    type. A released app that never turns it on is unchanged: `isActive` is a volatile read
 *    returning false and the block is skipped.
 *
 * The incident worth diagnosing is a phone in the field running a release build, and gating
 * its own explanation on the build type it cannot have is how that incident stays
 * unexplained. **This is warnings only**, deliberately: the price is that these strings do
 * ship in the release artifact, and paying it for every debug line would put the SDK's whole
 * internal narration in a published AAR to say something the default `LogSyncConfig.level`
 * drops anyway.
 */
internal inline fun sdkWarn(block: () -> Unit) {
    if (BuildConfig.SDK_LOGGING_ENABLED || SdkLogRelay.isActive) block()
}

/**
 * The licence API's logcat tag. `AndroidLogger` prefixes it, so it reads as
 * `Tracker/API_CALL`:
 *
 * ```
 * adb logcat -s Tracker/API_CALL
 * ```
 *
 * One constant shared by the transport and the use case, so a filtered log shows the
 * whole path — the request, what came back, and what was decided about it — rather than
 * the HTTP half on its own.
 *
 * **Nothing logged under this tag identifies the licence.** Bodies and headers are written
 * in full *except* for the values that would: `access_key`, `key_id`, `signature` and the
 * usual credential-bearing headers are cut to a twelve-character preview. Logcat is
 * readable by `adb` on any developer machine and by anything holding `READ_LOGS`, so an
 * access key written here would be an access key handed to whoever is watching — and
 * unlike a leaked URL, that one is a credential. Release builds compile these calls out
 * entirely (`SDK_LOGGING_ENABLED` is false), but the redaction is deliberate rather than a
 * side effect of that.
 */
internal const val API_TAG: String = "API_CALL"
