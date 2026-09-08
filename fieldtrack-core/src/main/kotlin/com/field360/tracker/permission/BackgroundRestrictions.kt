package com.field360.tracker.permission

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.PowerManager

/**
 * What the OS and the OEM have decided this app is allowed to do in the background.
 *
 * ### Why this exists
 *
 * Every field report about background tracking arrives in the same shape: "it stops after a
 * while on some devices." The SDK could say a great deal about *what* it observed — a dead
 * tracker, a refused foreground service, a gap in the fixes — and nothing at all about
 * *why*, because the three settings that decide the answer were never read. So a support
 * engineer could not tell a genuine SDK defect from an app sitting in the `RESTRICTED`
 * standby bucket with `JobScheduler` switched off underneath it, and both were reported as
 * the former.
 *
 * All three reads are about *this* app, need no permission, and are cheap. None of them can
 * be acted on from inside the SDK — every remedy is a Settings screen a user has to visit,
 * and a library that opens one uninvited is making an application decision (PERMISSIONS.md
 * §5). They are reported, and the host decides whether to nudge.
 *
 * ### What each one means when it is false
 *
 * @property ignoringBatteryOptimizations the user granted the battery-optimisation
 *   exemption. `false` is the default for every app and is *not* itself a fault — Doze is
 *   survivable. It matters as the cheapest remedy a host can offer once something else here
 *   is already wrong.
 * @property backgroundRestricted the user (or an OEM battery manager acting as the user)
 *   put the app in "Restricted" — MIUI/HyperOS's default *Battery saver* setting does
 *   exactly this. **This is the one that ends background tracking**: the app gets no
 *   background services and no jobs, so the backstop, the restore worker and every other
 *   `JobScheduler` layer in the survival stack stop running at once.
 * @property standbyBucket the App Standby bucket, `UsageStatsManager.STANDBY_BUCKET_*`.
 *   `RESTRICTED` (45) means jobs may never run (EC-22); `RARE` (40) means heavily deferred.
 *   `null` below API 28, where the concept does not exist.
 */
internal data class BackgroundRestrictions(
    val ignoringBatteryOptimizations: Boolean,
    val backgroundRestricted: Boolean,
    val standbyBucket: Int?,
) {

    /**
     * True when something here is actively expected to break background recovery.
     *
     * The battery-optimisation exemption is deliberately **not** part of this. Not holding
     * it is the normal, healthy state of almost every app on Android, and treating it as a
     * fault would make this warn on every device and therefore on none.
     */
    val degraded: Boolean
        get() = backgroundRestricted || standbyBucket?.let { it >= BUCKET_RARE } == true

    /** One line for a log or a `Diagnostic`, naming only what is actually wrong. */
    fun describe(): String = buildString {
        append("background restrictions: ")
        append("restricted=$backgroundRestricted")
        append(", bucket=${bucketName()}")
        append(", batteryOptimised=${!ignoringBatteryOptimizations}")
        if (degraded) {
            append(
                " — the OS will defer or refuse this app's background jobs, so the SDK's " +
                    "WorkManager recovery layers may not run. On MIUI/HyperOS set Battery " +
                    "saver to \"No restrictions\" and enable Autostart",
            )
        }
    }

    private fun bucketName(): String = when (standbyBucket) {
        null -> "n/a"
        BUCKET_ACTIVE -> "ACTIVE"
        BUCKET_WORKING_SET -> "WORKING_SET"
        BUCKET_FREQUENT -> "FREQUENT"
        BUCKET_RARE -> "RARE"
        BUCKET_RESTRICTED -> "RESTRICTED"
        else -> standbyBucket.toString()
    }

    internal companion object {
        /**
         * The `UsageStatsManager.STANDBY_BUCKET_*` ladder, restated here rather than
         * referenced.
         *
         * The platform constants arrived at API 28 (`RESTRICTED` at 30) and this module has
         * a `minSdk` of 26, so referencing them is an `InlinedApi` lint error — correctly,
         * because a constant compiled into a build that can run on API 26 is a value the
         * device may know nothing about. Restating them is safe for the same reason it is
         * flagged: [standbyBucket] is `null` below API 28, so nothing is ever compared
         * against these on a device that predates the concept.
         *
         * The values are ordered, and that ordering is what [degraded] tests: higher is
         * more restricted.
         */
        const val BUCKET_ACTIVE = 10
        const val BUCKET_WORKING_SET = 20
        const val BUCKET_FREQUENT = 30
        const val BUCKET_RARE = 40
        const val BUCKET_RESTRICTED = 45

        /**
         * Reads all three. Every one is wrapped: an OEM that refuses a service lookup, or a
         * `UsageStatsManager` that throws for an app with no usage history, must degrade to
         * "unknown" rather than take down whatever was asking. A diagnostic that can crash
         * the thing it diagnoses is worse than no diagnostic.
         */
        fun read(context: Context): BackgroundRestrictions = BackgroundRestrictions(
            ignoringBatteryOptimizations = runCatching {
                context.getSystemService(PowerManager::class.java)
                    .isIgnoringBatteryOptimizations(context.packageName)
            }.getOrDefault(false),

            backgroundRestricted = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    context.getSystemService(ActivityManager::class.java).isBackgroundRestricted
                } else {
                    false
                }
            }.getOrDefault(false),

            standbyBucket = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    context.getSystemService(UsageStatsManager::class.java).appStandbyBucket
                } else {
                    null
                }
            }.getOrNull(),
        )
    }
}
