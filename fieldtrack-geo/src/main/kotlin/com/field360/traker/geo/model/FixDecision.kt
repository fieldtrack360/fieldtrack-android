package com.field360.traker.geo.model

/**
 * Why one fix got the verdict it got.
 *
 * Every fix produces one of these, accepted or not. Together they are the decision log —
 * a queryable table rather than a log file — and they are what turns any accuracy
 * complaint into a replayable regression test (PLAN.md §4 improvement 10).
 *
 * The numeric fields exist so a `Sigma Gate Outlier` can be argued with: [sigma] and
 * [threshold] show exactly how wide the gate was and by how much the fix missed.
 */
public data class FixDecision(
    val fix: TrackFix,
    val verdict: Verdict,
    val filterLat: Double,
    val filterLng: Double,
    val sigma: Float,
    val threshold: Float,
    val distanceMovedM: Double,
    val effectiveSpeedMps: Float,
    val motionState: MotionState,
) {
    val reason: String get() = verdict.reason
    val isAccept: Boolean get() = verdict is Verdict.Accept
}

/**
 * The pipeline's return value.
 *
 * [state] is **always** returned — a rejected fix still advances the burst clock and
 * may have burned a reject count, so the caller must never discard it. [point] is
 * non-null if and only if the verdict was [Verdict.Accept].
 */
public data class PipelineResult(
    val decision: FixDecision,
    val state: FilterState,
    val point: TrackPoint? = null,
    /**
     * The outcome this fix would have had if the pipeline had kept it — non-null only on
     * a [Reasons.HEURISTIC_GATE] rejection, and only when the caller asked for it via
     * [IngestContext.cornerAnchorCapture] (EC-45e).
     *
     * **This is an alternative, not an addition.** The caller adopts either the fields
     * above or every field of [Deferred], never a mixture: the two describe the same fix
     * taking two different routes through the filter, and their [FilterState]s diverge
     * from that instant on. Adopting one after having already fed a later fix through the
     * other is the one way to corrupt this, which is why the seam sits in the caller's
     * per-fix loop rather than behind a timer.
     *
     * The pipeline offers it and expresses no opinion about it: whether a corner turned
     * across this fix is a question about the fix that comes *next*, and the pipeline has
     * a strict contract of judging one fix against what precedes it. [CornerWindow] is
     * where the question is answered.
     */
    val deferred: Deferred? = null,
)

/**
 * A rejected fix's counterfactual: what the filter would hold, and what point would have
 * been stored, had the heuristic gate kept it (EC-45e).
 *
 * Carries a whole [FilterState] rather than a delta because the two routes are not a
 * delta apart — an accepted fix runs a Kalman correction, advances the burst clock and
 * the captured heading, and clears the reject counter, while a rejected one runs settle
 * detection instead.
 */
public data class Deferred(
    val decision: FixDecision,
    val state: FilterState,
    val point: TrackPoint,
)
