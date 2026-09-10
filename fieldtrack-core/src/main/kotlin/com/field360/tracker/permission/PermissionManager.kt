package com.field360.tracker.permission

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.field360.tracker.domain.model.LocationAccuracy
import com.field360.tracker.domain.model.PermissionTier

/**
 * Permission state, and the ladder needed to improve it.
 *
 * **The SDK shows no UI.** No dialogs, no activities, no full-screen intents. This
 * class answers questions and hands back the permission arrays and the Settings
 * intent; the host owns every prompt. The reference fired a full-screen `CATEGORY_CALL`
 * notification when tracking looked dead — an application decision with real Play
 * policy risk, and not an SDK's to make (PERMISSIONS.md §5).
 *
 * That is a deliberate deviation from the `suspend fun request(activity, level)` sketch
 * in PERMISSIONS.md §3: owning an `ActivityResultLauncher` from inside a library means
 * registering against a host lifecycle before RESUMED, which is fragile and forces the
 * host to hand over its Activity. Query + arrays + intent achieves the same ladder with
 * none of that coupling.
 */
public class PermissionManager internal constructor(
    private val context: Context,
) {

    /**
     * The tier decides what `start()` may do.
     *
     * The reference treated background location as a hard gate and stopped the service
     * outright without it, so a user who chose "While using the app" got *zero*
     * tracking — not even in the foreground. Defensible for an attendance product,
     * wrong for a general SDK (SOURCE-AUDIT A16, EC-03).
     */
    public fun tier(): PermissionTier = when {
        !hasAny(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION) ->
            PermissionTier.NONE
        hasBackgroundLocation() -> PermissionTier.FULL
        else -> PermissionTier.FOREGROUND_ONLY
    }

    /**
     * Orthogonal to [tier] and always surfaced. A 1–3 km error circle defeats every gate
     * in the pipeline, so `CONTINUOUS`/`ADAPTIVE` refuse to start on approximate-only
     * unless the host explicitly opts in (EC-02, EC-12).
     */
    public fun accuracy(): LocationAccuracy =
        if (has(Manifest.permission.ACCESS_FINE_LOCATION)) {
            LocationAccuracy.PRECISE
        } else {
            LocationAccuracy.APPROXIMATE
        }

    public fun hasActivityRecognition(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            has(Manifest.permission.ACTIVITY_RECOGNITION)
        } else {
            true
        }

    public fun hasNotificationPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            has(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            true
        }

    /**
     * Step 1 of the ladder. Fine and coarse go together in one request; asking for
     * background in the same array makes Android deny it silently (EC-04).
     */
    public fun foregroundPermissions(): Array<String> = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    /**
     * Ask **first** on API 33+. An invisible foreground-service notification is a
     * transparency failure and an OEM-kill risk (EC-08).
     */
    public fun notificationPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            emptyArray()
        }

    /** Optional. Denial degrades motion detection to speed + displacement, never fatal (EC-09). */
    public fun activityRecognitionPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf(Manifest.permission.ACTIVITY_RECOGNITION)
        } else {
            emptyArray()
        }

    /**
     * Step 2, and only after fine is granted and the host has shown a rationale.
     *
     * From Android 11 the OS will not show a background-location prompt at all, so
     * [BackgroundRequest.NeedsSettings] is the only honest answer — a runtime request
     * there appears to do nothing (EC-05).
     */
    public fun backgroundRequest(): BackgroundRequest = when {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> BackgroundRequest.NotApplicable
        hasBackgroundLocation() -> BackgroundRequest.AlreadyGranted
        // Background is only grantable AFTER fine. Asking before is a silent denial.
        !has(Manifest.permission.ACCESS_FINE_LOCATION) -> BackgroundRequest.NeedsForegroundFirst
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> BackgroundRequest.NeedsSettings(appSettingsIntent())
        else -> BackgroundRequest.Prompt(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
    }

    /**
     * Hard cap so a "Don't ask again" user is never prompt-looped; after this only the
     * Settings route is offered. The reference caps at 3 for the same reason (EC-14).
     */
    public fun shouldStopAsking(attempts: Int): Boolean = attempts >= MAX_ATTEMPTS

    public fun appSettingsIntent(): Intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * What the OS and the OEM currently allow this app to do in the background.
     *
     * Three reads, none of which needs a permission — see [BackgroundRestrictions] for what
     * each one means when it is false. Cheap enough to call from a settings screen's
     * `onResume` so the UI reflects a change the user just made.
     *
     * **`ignoringBatteryOptimizations == false` is the normal, healthy state of almost every
     * app** and is not on its own a fault. [BackgroundRestrictions.degraded] deliberately
     * ignores it: the exemption is the cheapest remedy to *offer* once something else is
     * already wrong, not a box that has to be ticked.
     */
    public fun backgroundRestrictions(): BackgroundRestrictions =
        BackgroundRestrictions.read(context)

    /**
     * The system list of apps and their battery-optimisation setting, for the host to open.
     *
     * **Needs no permission and is always safe to launch**, which is why it is the route
     * this SDK can offer unconditionally. It costs the user two taps — find the app, choose
     * "Don't optimise" — where [batteryExemptionRequestIntent] costs one, and it carries
     * none of that one's Play-policy weight.
     *
     * Launch it from an Activity. `FLAG_ACTIVITY_NEW_TASK` is set so a host with only an
     * application `Context` is not left with an intent it cannot start, but a Settings
     * screen opened from a real Activity behaves better on Back.
     */
    public fun batteryOptimizationSettingsIntent(): Intent =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * The one-tap "allow this app to run in the background?" dialog, or `null`.
     *
     * **Null unless the host app declares `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` in its own
     * manifest**, which this checks at runtime rather than assuming. The SDK does not
     * declare it: that permission is Play-policy reviewed, and a library that quietly adds
     * it to a host's merged manifest has made the host's store submission its own decision
     * (EC-15). `ServiceHeartbeat` treats `SCHEDULE_EXACT_ALARM` the same way, for the same
     * reason.
     *
     * So the contract is: declare it if your app qualifies — Play expects a core feature
     * that genuinely cannot work under Doze, which continuous location tracking can be —
     * and this returns the intent. Do not declare it, and the SDK falls back to
     * [batteryOptimizationSettingsIntent], which always works.
     *
     * Returns `null` when the exemption is already held, so a host can use a non-null
     * result as "there is something to ask for" without reading the status separately.
     *
     * Launching it when the permission is undeclared throws on some OEMs and silently does
     * nothing on others, which is exactly the failure this null is here to prevent.
     */
    public fun batteryExemptionRequestIntent(): Intent? {
        if (backgroundRestrictions().ignoringBatteryOptimizations) return null
        if (!declaresBatteryExemptionPermission()) return null

        @SuppressLint("BatteryLife") // The host declared the permission; the ask is its call.
        return Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.fromParts("package", context.packageName, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /**
     * Whether the merged manifest declares the exemption permission.
     *
     * `requestedPermissions` rather than a `checkSelfPermission`: this is a normal-level
     * permission, so it is granted at install time and a runtime check answers "is it in
     * the manifest" in a roundabout way that returns the wrong thing on the OEMs that
     * pre-grant it. Asking the package manager what was declared is the direct question.
     */
    private fun declaresBatteryExemptionPermission(): Boolean = runCatching {
        context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            ?.contains(Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS) == true
    }.getOrDefault(false)

    private fun hasBackgroundLocation(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            has(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        } else {
            // Before Android 10 there was no separate background permission.
            has(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun has(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun hasAny(vararg permissions: String): Boolean = permissions.any(::has)

    /** What the host must do next to reach [PermissionTier.FULL]. */
    public sealed interface BackgroundRequest {
        public data object AlreadyGranted : BackgroundRequest
        public data object NotApplicable : BackgroundRequest
        public data object NeedsForegroundFirst : BackgroundRequest

        /** API 29 only — a runtime prompt still works. */
        public data class Prompt(val permissions: Array<String>) : BackgroundRequest {
            override fun equals(other: Any?): Boolean =
                this === other || (other is Prompt && permissions.contentEquals(other.permissions))

            override fun hashCode(): Int = permissions.contentHashCode()
        }

        /** API 30+ — deep-link to Settings and explain "Allow all the time". */
        public data class NeedsSettings(val intent: Intent) : BackgroundRequest
    }

    internal companion object {
        const val MAX_ATTEMPTS = 3
    }
}
