package com.field360.traker.sync

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
