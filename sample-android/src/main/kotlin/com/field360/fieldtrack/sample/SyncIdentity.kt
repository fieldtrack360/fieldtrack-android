package com.field360.fieldtrack.sample

import android.content.Context
import java.util.UUID

/**
 * The `device_id` the sample sends in every upload's `extraParams`.
 *
 * **A random UUID stored in app storage, not `Settings.Secure.ANDROID_ID`.** That is
 * Google's own guidance for identifiers that are not advertising-related, and the reasons
 * are practical rather than ceremonial: `ANDROID_ID` is scoped per signing key and per
 * user, changes on factory reset, is unavailable to instant apps, and is a hardware
 * identifier that a play-store review will ask about. A per-install UUID is stable for as
 * long as the install is, which is what a tracking backend actually needs to group a
 * device's uploads.
 *
 * It is also honest about what it identifies: an *install*, not a person and not a piece
 * of hardware. Clearing app data or reinstalling issues a new one, which is the correct
 * privacy behaviour — the identifier disappears when the user removes the app.
 *
 * A production host with real device provisioning would use its own server-issued id
 * instead. This exists so the sample sends a plausible one rather than a hardcoded string.
 */
internal class SyncIdentity(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Generated once, then read back forever.
     *
     * `commit()` rather than `apply()`: this runs during `Application.onCreate`, and an
     * asynchronous write that loses a race with a process death would issue a *second*
     * device id for the same install — the one failure mode this whole class exists to
     * avoid. The write happens once in the lifetime of an install, so the blocking cost
     * is paid once.
     */
    val deviceId: String by lazy {
        prefs.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_DEVICE_ID, it).commit()
        }
    }

    private companion object {
        const val PREFS = "fieldtrack-sample-identity"
        const val KEY_DEVICE_ID = "device_id"
    }
}
