package com.field360.traker.geo.model

/**
 * A raw sample handed to the acceptance pipeline.
 *
 * Platform-agnostic by construction — produced by `FixMapper` in `fieldtrack-core`,
 * which is the only place `android.location.Location` is ever touched
 * (PLAN.md §3 invariant 2).
 *
 * **Three clocks, deliberately.** The reference implementation carried one, and it was
 * the wrong one: `LatLng.startTime` defaulted to object-construction time and
 * `LatLngFactory.from(location)` never copied `location.time`, so every Δt, gap and
 * speed in the pipeline was measured from when a Kotlin object happened to be built
 * (SOURCE-AUDIT A1). Here the monotonic clock is the only one the filter may use.
 *
 * @property timeMs wall-clock fix time (`Location.getTime()`). **Storage and display
 *   only** — never used for filter arithmetic, because the user or NTP can move it
 *   backwards mid-session (EC-42, EC-88).
 * @property elapsedRealtimeNanos monotonic fix time. THE clock for every delta, gap
 *   and age in the pipeline. Resets across reboot, which `FixIngestor` detects as a
 *   boot boundary (EC-29, EC-92).
 * @property receivedAtElapsedNanos when the SDK received the fix. Diagnostics only;
 *   its gap from [elapsedRealtimeNanos] is how staleness is measured (EC-33).
 * @property accuracy metres. Always `> 0` after `FixMapper` clamping — a zero or NaN
 *   accuracy would drive Kalman variance to 0, gain to 1, and make the filter track
 *   noise exactly (EC-23).
 * @property speedMps hardware speed, m/s. **Meaningless unless [hasSpeed]** — some
 *   OEMs report a value with the flag cleared (EC-27).
 * @property hasSpeed hardware validity flag. Defaults **false**: the reference
 *   defaulted these to `true`, which silently disabled the network-fix rejection that
 *   is the main defence against WiFi-positioning teleports (SOURCE-AUDIT A8).
 * @property hasBearing hardware validity flag. Defaults **false**, same reason.
 */
public data class TrackFix(
    val timeMs: Long,
    val elapsedRealtimeNanos: Long,
    val receivedAtElapsedNanos: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val altitude: Double? = null,
    val verticalAccuracy: Float? = null,
    val speedMps: Float = 0f,
    val bearingDeg: Float = 0f,
    val hasSpeed: Boolean = false,
    val hasBearing: Boolean = false,
    val provider: String = UNKNOWN_PROVIDER,
    val isMock: Boolean = false,
    val satelliteCount: Int? = null,
    /**
     * Hardware 1σ confidence in [speedMps], m/s, when the platform reports one
     * (`Location.hasSpeedAccuracy()`). `null` means "not reported" — which is not `0`:
     * a consumer weighting Doppler by its own confidence must skip the weighting, not
     * treat the value as perfect (SMOOTH-NAV-PLAN Phase 1).
     */
    val speedAccuracyMps: Float? = null,
    /** Hardware 1σ confidence in [bearingDeg], degrees. Same `null` contract as [speedAccuracyMps]. */
    val bearingAccuracyDeg: Float? = null,
) {
    /**
     * A fix with neither hardware speed nor hardware bearing is treated as
     * network-derived (WiFi/cell database). Stage 1.5 rejects these above
     * [TrackerConstants.accuracyNlpReject] unless the vehicular bypass applies —
     * they arrive "fresh", so no timestamp gate catches them (EC-32).
     *
     * [altitude] is the third witness, and it is what stops the flag test misfiring on
     * hardware that is telling the truth. The Doppler flags are the *usual* discriminator
     * because a Wi-Fi centroid has no velocity solution to report — but neither does a
     * GNSS chip that has a position fix and no velocity yet, and the Unisoc and MediaTek
     * HALs this SDK runs on clear both flags routinely: on a cold start, at walking pace,
     * and whenever the constellation drops below a velocity solution. Those fixes were
     * then judged as Wi-Fi centroids and rejected above 25 m, which is how a device that
     * was tracking perfectly well produced a run of `NLP Fallback` rejects and a chord
     * across the map.
     *
     * A trilaterated network position has no altitude — the platform's network provider
     * does not populate one — while a GNSS fix always does, because altitude falls out of
     * the same four-satellite solution as latitude and longitude. So an altitude present
     * with the velocity flags clear says "GNSS, no velocity solution", not "Wi-Fi".
     *
     * This only ever *narrows* the classification, so nothing that used to pass this gate
     * now fails it. What newly passes is still judged by every gate below — the moving
     * accuracy ceiling, the sigma gate and the heuristic branches all remain in front of
     * it, which is where a genuine Wi-Fi teleport is caught anyway.
     */
    val looksLikeNetworkFix: Boolean get() = !hasSpeed && !hasBearing && altitude == null

    public companion object {
        public const val UNKNOWN_PROVIDER: String = "unknown"
    }
}
