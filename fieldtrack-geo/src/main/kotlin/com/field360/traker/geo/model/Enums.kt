package com.field360.traker.geo.model

/** Per-point stamp, distinct from [MotionState] which is the machine's own state. */
public enum class MovementStatus { STEADY, MOVING }

/**
 * Activity-recognition label. **Enrichment only, never a capture gate** — entire
 * 17-minute drives are reported `STILL` on some OnePlus and Xiaomi devices under
 * battery saver, so gating capture on this would lose the whole trip (EC-53).
 */
public enum class ActivityType {
    IN_VEHICLE, ON_BICYCLE, ON_FOOT, WALKING, RUNNING, STILL, TILTING, UNKNOWN;

    /** Labels the plot side distrusts when measured motion contradicts them (EC-53). */
    public val isLowTier: Boolean
        get() = this == WALKING || this == ON_FOOT || this == STILL || this == RUNNING
}

/** `STOPPED → MOVING ⇄ STOP_PENDING ⇄ STATIONARY` (PLAN.md §2 item 2). */
public enum class MotionState { STOPPED, MOVING, STOP_PENDING, STATIONARY }

/** Mock-location handling. Default [FLAG] — table stakes for a data-integrity SDK (A17, EC-28). */
public enum class MockPolicy { FLAG, REJECT, ALLOW }

/** What the pipeline decided, and why. */
public sealed interface Verdict {
    public val reason: String

    /** Store the point and emit it. */
    public data class Accept(override val reason: String) : Verdict

    /** Filter state was updated ("warmed") but nothing is stored. */
    public data class Skip(override val reason: String) : Verdict

    /** Dropped entirely. */
    public data class Reject(override val reason: String) : Verdict
}

/**
 * The reason vocabulary **is API**.
 *
 * These strings are the field-debugging language and every fixture assertion keys on
 * them, so they are kept byte-identical to the reference implementation. Changing one
 * is a breaking change (API.md §2).
 */
public object Reasons {
    public const val INIT: String = "Init"
    public const val RESUME: String = "Resume"
    public const val BURST: String = "Burst"
    public const val NLP_FALLBACK: String = "NLP Fallback"
    public const val IMPOSSIBLE_SPEED: String = "Impossible Speed"

    /**
     * A fix claiming movement with an error circle too wide to place it (EC-139). Its
     * displacement is indistinguishable from its own uncertainty, so plotting it draws
     * noise as a road.
     */
    public const val POOR_ACCURACY: String = "Poor Accuracy"

    public const val RECOVERY_CONFIRMED: String = "Recovery Confirmed"
    public const val RECOVERY_RESET: String = "Recovery Reset"
    public const val RECOVERY_HELD: String = "Recovery Held"

    public const val SIGMA_GATE_OUTLIER: String = "Sigma Gate Outlier"
    public const val SIGMA_FORCED_RESET: String = "Sigma Forced Reset"
    public const val SIGMA_JUNK_FAIL: String = "Sigma Junk Fail"

    public const val VEHICULAR: String = "Vehicular"
    public const val MOVING_WALKING: String = "Moving/Walking"
    public const val INDOOR_ARRIVAL: String = "Indoor Arrival"

    /**
     * Stored because the heading turned, not because the speed or distance gates asked
     * for it. This is the vertex at a corner (EC-45).
     */
    public const val BEARING_CHANGE: String = "Bearing Change"

    /**
     * Stored on second thoughts: the heuristic gate dropped this fix, and the fix *after*
     * it showed that a corner had turned across it (EC-45e).
     *
     * The only reason in this vocabulary a fix cannot earn on its own evidence.
     * [BEARING_CHANGE] compares a fix against the last stored point and so can only see a
     * turn that has already happened; the fix sitting *at* a corner's apex has turned only
     * halfway and reads as unremarkable against everything before it. It takes the next
     * fix to show the other half.
     */
    public const val CORNER_ANCHOR: String = "Corner Anchor"

    public const val ARRIVAL: String = "Arrival"
    public const val STATIONARY_RECOVERY: String = "Stationary Recovery"
    public const val BLACKOUT_ARRIVAL: String = "Blackout Arrival"
    public const val WALK_ARRIVAL: String = "Walk Arrival"
    public const val HEARTBEAT: String = "15-Min Heartbeat"

    public const val ORIGIN_SET: String = "Origin Set"
    public const val DEPARTURE_HELD: String = "Departure Held"
    public const val DRIFT_SUPPRESSED: String = "Drift Suppressed"
    public const val HEARTBEAT_SKIPPED: String = "HeartBeat Skipped"

    /**
     * Dropped because non-GNSS hardware says the device did not move (EC-142).
     *
     * Distinct from [HEURISTIC_GATE], which says the fix was *unremarkable*. This one says
     * the displacement was not travel at all, on evidence the GNSS receiver never saw —
     * and it is the only rejection in this vocabulary sourced from outside the fix.
     *
     * Only ever reached from the stationary branch: a fix the pipeline's own speed or
     * displacement measures call moving is never vetoed, whatever the sensors think. That
     * is the whole EC-53 guarantee, and it is what stops this becoming the incumbent's
     * "activity recognition said STILL, so the drive never happened".
     */
    public const val STILLNESS_VETO: String = "Stillness Veto"

    public const val HEURISTIC_GATE: String = "Heuristic Gate"
    public const val SESSION_CLOSED: String = "Session Closed"
    public const val MOCK_LOCATION: String = "Mock Location"
    public const val INVALID_COORDINATES: String = "Invalid Coordinates"
    public const val STALE_FIX: String = "Stale Fix"
    public const val REBOOT_BOUNDARY: String = "Reboot Boundary"

    /**
     * A fix whose monotonic timestamp is older than one already processed — two batched
     * deliveries interleaving. Dropped, and deliberately **not** a reboot: the filter
     * keeps everything it knows (EC-92a).
     */
    public const val OUT_OF_ORDER: String = "Out Of Order"
}
