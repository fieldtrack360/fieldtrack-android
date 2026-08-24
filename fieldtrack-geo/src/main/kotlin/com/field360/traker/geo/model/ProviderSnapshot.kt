package com.field360.traker.geo.model

/**
 * What the location subsystem looked like when a fix was ingested.
 *
 * Carried per point rather than sampled at upload time, for the same reason [timezone] is
 * on the row and not on the session: a batch of 100 points can span an hour, and a
 * permission downgrade or an airplane-mode toggle inside that hour is exactly the event a
 * backend investigating a gap needs to see. Reading the live state when the queue drains
 * would stamp all 100 rows with whatever happened to be true minutes later.
 *
 * The numeric fields carry the **wire codes** directly rather than an SDK enum. They are a
 * contract with the backend, not an internal taxonomy — `status = 3` means the same thing
 * to the server whichever platform sent it, and a Kotlin enum in the middle would be one
 * more mapping to keep in step for no reader's benefit.
 *
 * `fieldtrack-geo` never asks Android anything, so this is filled in by `fieldtrack-core`
 * (`ProviderStateMonitor`) and passed through [IngestContext] unread by the engine.
 *
 * @property recorded `false` for a row written before this existed, and for any path that
 *   could not sample the state. Distinct from a snapshot whose every field is off — "we did
 *   not look" and "location is disabled" are different answers, and [flags] of `0` is the
 *   first, never the second.
 * @property locationServicesEnabled the master switch, not the union of the two providers.
 *   From API 28 the platform answers this directly; below it the union is the best
 *   available reading.
 */
public data class ProviderSnapshot(
    val recorded: Boolean = false,
    val gpsEnabled: Boolean = false,
    val networkEnabled: Boolean = false,
    val locationServicesEnabled: Boolean = false,
    val airplaneMode: Boolean = false,
    val authorizationStatus: Int = STATUS_DENIED,
    val accuracyAuthorization: Int = ACCURACY_REDUCED,
) {

    /**
     * Packed into one `Int` for storage.
     *
     * A single column rather than six, following `integrityFlags`: the same trade already
     * made on the same tables, and the same escape hatch — [fromFlags] round-trips, so the
     * column is readable in a debugger and in a SQL client with one call rather than being
     * opaque.
     */
    public fun toFlags(): Int {
        if (!recorded) return NOT_RECORDED
        var flags = BIT_RECORDED
        if (gpsEnabled) flags = flags or BIT_GPS
        if (networkEnabled) flags = flags or BIT_NETWORK
        if (locationServicesEnabled) flags = flags or BIT_ENABLED
        if (airplaneMode) flags = flags or BIT_AIRPLANE
        flags = flags or ((authorizationStatus and STATUS_MASK) shl STATUS_SHIFT)
        flags = flags or ((accuracyAuthorization and ACCURACY_MASK) shl ACCURACY_SHIFT)
        return flags
    }

    public companion object {
        /** No snapshot on this row. Deliberately not a snapshot with everything off. */
        public const val NOT_RECORDED: Int = 0

        /** The platform has not been asked, or the question does not apply. */
        public const val STATUS_NOT_DETERMINED: Int = 0

        /** Denied by policy (a managed device, a restricted profile) rather than by the user. */
        public const val STATUS_RESTRICTED: Int = 1

        /**
         * No location permission.
         *
         * Android reports "never asked" and "asked and refused" identically, so this covers
         * both. [STATUS_NOT_DETERMINED] exists for wire compatibility with platforms that
         * can tell them apart; nothing on Android emits it.
         */
        public const val STATUS_DENIED: Int = 2

        /** Foreground **and** background location — `PermissionTier.FULL`. */
        public const val STATUS_ALWAYS: Int = 3

        /** Foreground only — `PermissionTier.FOREGROUND_ONLY`. */
        public const val STATUS_WHEN_IN_USE: Int = 4

        /** `ACCESS_FINE_LOCATION` held. */
        public const val ACCURACY_FULL: Int = 0

        /** Coarse only: a 1–3 km error circle, which defeats every gate in the pipeline. */
        public const val ACCURACY_REDUCED: Int = 1

        /** The inverse of [toFlags]. `0` answers a snapshot with `recorded = false`. */
        public fun fromFlags(flags: Int): ProviderSnapshot {
            if (flags and BIT_RECORDED == 0) return ProviderSnapshot()
            return ProviderSnapshot(
                recorded = true,
                gpsEnabled = flags and BIT_GPS != 0,
                networkEnabled = flags and BIT_NETWORK != 0,
                locationServicesEnabled = flags and BIT_ENABLED != 0,
                airplaneMode = flags and BIT_AIRPLANE != 0,
                authorizationStatus = (flags shr STATUS_SHIFT) and STATUS_MASK,
                accuracyAuthorization = (flags shr ACCURACY_SHIFT) and ACCURACY_MASK,
            )
        }

        private const val BIT_RECORDED = 1 shl 0
        private const val BIT_GPS = 1 shl 1
        private const val BIT_NETWORK = 1 shl 2
        private const val BIT_ENABLED = 1 shl 3
        private const val BIT_AIRPLANE = 1 shl 4
        private const val STATUS_SHIFT = 5
        private const val STATUS_MASK = 0b111
        private const val ACCURACY_SHIFT = 8
        private const val ACCURACY_MASK = 0b11
    }
}
