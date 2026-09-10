package com.field360.tracker

import com.field360.traker.geo.port.SdkLogRelay

/**
 * Runs [block] only when something will read what it writes.
 *
 * Two readers, and the second is why this is not simply `if (BuildConfig.SDK_LOGGING_ENABLED)`:
 *
 * 1. **logcat** — debug builds. `SDK_LOGGING_ENABLED` is false in release, so the whole
 *    block, message construction included, is gone from release bytecode.
 * 2. **the diagnostic buffer** — whenever a host has configured log shipping, in *any*
 *    build type. A released app that never turns it on is unchanged: `isActive` is a
 *    volatile read returning false, and the block is skipped exactly as before.
 *
 * The second reader is the point of the whole channel. The incident worth diagnosing is a
 * phone in the field running a release build, and gating its own explanation on the build
 * type it cannot have is how that incident stays unexplained.
 */
internal inline fun sdkLog(block: () -> Unit) {
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
