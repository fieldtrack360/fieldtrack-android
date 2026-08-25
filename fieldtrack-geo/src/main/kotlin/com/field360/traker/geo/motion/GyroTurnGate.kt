package com.field360.traker.geo.motion

import com.field360.traker.geo.filter.TrackerConstants
import kotlin.math.abs
import kotlin.math.sqrt

private const val NANOS_PER_MILLI = 1_000_000L

/**
 * Arms the fast sampling tier from the **gyroscope**, before GNSS heading has moved
 * (EC-45d).
 *
 * [TurnDetector] is honest in its own KDoc about being reactive: it compares the heading
 * of one fix against the last, so at a 12 s cadence the earliest it can know a turn has
 * begun is 12 s after it began, by which time the corner is behind the vehicle. Everything
 * it buys is downstream of that instant — the rest of a long bend, corners 2..n of a
 * roundabout. A single isolated junction taken at speed is still sampled twice: once on
 * the approach and once on the exit, with the chord between them drawn across the corner.
 *
 * A gyroscope has no such delay. Yaw rate rises the moment the wheel turns, which is
 * typically a second or more before the vehicle's heading has changed enough for GNSS to
 * resolve it, and several seconds before the next scheduled fix. Arming from yaw means the
 * burst is running *into* the corner rather than out of it — the one thing that puts a
 * sample near the apex without raising the cadence for the whole drive.
 *
 * Pure, in the same discipline as [TurnDetector] and [MotionStateMachine]: state in, state
 * out, no timers and no clock of its own. The sample's own monotonic timestamp is the only
 * clock, so a recorded drive replays identically.
 *
 * **What this cannot do**, stated here rather than discovered in the field: a gyroscope
 * measures the *phone*, not the vehicle. A phone picked up, handed over, or rolling in a
 * cupholder produces yaw rates far larger than any junction. Two guards address that and
 * neither is perfect — a sustain window, and a ceiling above which the rate is treated as
 * handling rather than driving. The load-bearing guard is outside this class: the caller
 * is expected to run it only while GNSS has recently measured vehicular speed, which is
 * what keeps a phone swinging in a walker's hand from ever reaching it.
 */
public class GyroTurnGate(
    private val c: TrackerConstants = TrackerConstants.Default,
) {

    /**
     * @property aboveSinceNanos when the yaw rate first crossed the enter threshold and
     *   stayed there, or `null` while it is below. Nullable rather than a `0` sentinel for
     *   the reason [TurnDetector.State.lastElapsedNanos] is: `elapsedRealtimeNanos` is 0 at
     *   boot, so a first sample at monotonic zero would be indistinguishable from "not
     *   turning" and the sustain window would measure from the wrong instant.
     * @property burstUntilNanos monotonic deadline; `0` means not bursting — safe as a
     *   sentinel because it is only ever compared with `<`.
     */
    public data class State(
        val aboveSinceNanos: Long? = null,
        val burstUntilNanos: Long = 0L,
    )

    public data class Result(
        val state: State,
        /** True while the faster tier should be requested. */
        val isTurning: Boolean,
        /** The magnitude this sample contributed, deg/s. Zero when it was rejected. */
        val yawRateDegPerSec: Float,
    )

    /**
     * @param yawRateDegPerSec rotation about the world vertical — see [yawRateAboutGravity].
     *   Sign is ignored: a left turn and a right turn both want more samples.
     */
    public fun onSample(state: State, yawRateDegPerSec: Float, elapsedRealtimeNanos: Long): Result {
        val magnitude = abs(yawRateDegPerSec)

        // The ceiling is not a noise guard, it is a *subject* guard. No road vehicle
        // sustains this rate; a phone being picked up reaches it in a tenth of a second.
        // Rejecting it here rather than clamping means such a sample also breaks the
        // sustain run, so handling the phone cannot accumulate into a burst.
        val qualifies = magnitude >= c.gyroTurnEnterDegPerSec && magnitude <= c.gyroTurnMaxDegPerSec

        val aboveSince = when {
            !qualifies -> null
            // A monotonic clock does not run backwards, but a caller replaying a fixture
            // or resuming across a reboot can hand us one that appears to. Restart the run
            // rather than measuring a sustain window against a future timestamp, which
            // would never elapse.
            state.aboveSinceNanos == null || elapsedRealtimeNanos < state.aboveSinceNanos -> elapsedRealtimeNanos
            else -> state.aboveSinceNanos
        }

        val sustained = aboveSince != null &&
            elapsedRealtimeNanos - aboveSince >= c.gyroTurnSustainMs * NANOS_PER_MILLI

        // Each sustained sample re-arms the hold rather than extending a total, so a
        // roundabout stays fast for as long as it stays a roundabout and a straight falls
        // back one hold window after the last corner — the same shape as `TurnDetector`,
        // deliberately, so the two sources cannot disagree about how long a burst lasts.
        val burstUntil = if (sustained) {
            elapsedRealtimeNanos + c.turnBurstHoldMs * NANOS_PER_MILLI
        } else {
            state.burstUntilNanos
        }

        return Result(
            state = State(aboveSinceNanos = aboveSince, burstUntilNanos = burstUntil),
            isTurning = elapsedRealtimeNanos < burstUntil,
            yawRateDegPerSec = if (qualifies) magnitude else 0f,
        )
    }

    /** Forget everything. Called at session start and when the vehicular latch expires. */
    public fun reset(): State = State()

    public companion object {

        /**
         * Rotation about the world vertical, degrees per second, from a raw gyroscope
         * reading and the device's gravity vector.
         *
         * The projection is the whole point. A gyroscope reports rotation about the
         * *device's* three axes, and a phone in a pocket, a cradle, a cupholder or a hand
         * has no fixed relationship to the vehicle — the same turn appears on `z` for a
         * phone lying flat, on `y` for one standing in a windscreen mount, and smeared
         * across all three for one at an angle. Only the component about gravity is the
         * vehicle's yaw, and it is invariant to how the phone is held.
         *
         * Both vectors come from the same sensor stack in the same coordinate frame, so
         * the dot product with the normalised gravity direction extracts that component
         * directly. Gravity may come from `TYPE_GRAVITY` or from a low-passed
         * accelerometer; either works, since only its direction is used.
         *
         * @return `0` when the gravity vector is degenerate — a free-falling or
         *   uncalibrated device has no vertical to project onto, and inventing one would
         *   turn the device's own tumble into a turn.
         */
        @Suppress("LongParameterList")
        public fun yawRateAboutGravity(
            gyroXRadPerSec: Float,
            gyroYRadPerSec: Float,
            gyroZRadPerSec: Float,
            gravityX: Float,
            gravityY: Float,
            gravityZ: Float,
        ): Float {
            val magnitude = sqrt(
                (gravityX * gravityX + gravityY * gravityY + gravityZ * gravityZ).toDouble(),
            )
            if (magnitude < MIN_GRAVITY_MAGNITUDE) return 0f

            val dot = gyroXRadPerSec * gravityX + gyroYRadPerSec * gravityY + gyroZRadPerSec * gravityZ
            return Math.toDegrees(dot / magnitude).toFloat()
        }

        /**
         * Below this the gravity vector carries no usable direction, in m/s². Set well
         * under Earth's 9.81 so a device in a lift or on a bumpy road still projects, and
         * well above zero so a genuinely absent reading does not.
         */
        private const val MIN_GRAVITY_MAGNITUDE = 1.0
    }
}
