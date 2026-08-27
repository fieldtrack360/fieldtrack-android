# Motion-quality fallback — findings and proposed changes

**Status:** applied 2026-08-27 — code and documentation. One item outstanding: the
`ResolveConfigUseCase` test, which needs a design decision first (see Finding 1).
**Raised:** 2026-08-27, from a review of what happens when `TrackingMode.MOTION_ONLY` runs on
a device that cannot support motion detection.
**Scope:** `fieldtrack-core` (2 changes), `sample-android` (1 change), `docs` (1 change).

| Finding | Resolution |
|---|---|
| 1 — degraded event unobservable | **Applied (1a).** `motionQuality` and `effectiveTrackingMode` added to `TrackerState`. The suggested `ResolveConfigUseCase` test was **not** added — see the note under that finding |
| 2 — `DEGRADED` never consumed | **Applied (2a).** `stopTimeoutMin` doubled, `Diagnostic` emitted, KDoc corrected to match |
| 3 — battery regression unstated | **Applied.** The `MOTION_DETECTION_DEGRADED` message now names the consequence |
| 4 — sample cannot show it | **Applied.** `motion` card on the Status tab |

---

## What already works

Worth stating first, because the fallback itself is correct and none of the changes below
touch it.

`ResolveConfigUseCase` runs inside `ready()`, before any session opens. It probes the
hardware, and on `MotionQuality.POOR` it rewrites the mode and tells the host —
`fieldtrack-core/src/main/kotlin/com/field360/tracker/domain/usecase/TrackingUseCases.kt:428`:

```kotlin
val sensors = sensorProbe.probe()
val effective = if (sensors.motionQuality == MotionQuality.POOR &&
    resolved.geolocation.trackingMode != TrackingMode.CONTINUOUS
) {
    events.tryEmit(
        TrackerEvent.Error(
            ErrorCode.MOTION_DETECTION_DEGRADED,
            "motionQuality=POOR (accelerometer=${sensors.accelerometer}, " +
                "gyroscope=${sensors.gyroscope}, significantMotion=${sensors.significantMotion}, " +
                "stepDetector=${sensors.stepDetector}); forcing CONTINUOUS",
        ),
    )
    resolved.copy(geolocation = resolved.geolocation.copy(trackingMode = TrackingMode.CONTINUOUS))
} else { resolved }
```

So a host that asks for `MOTION_ONLY` on unsupported hardware gets tracking on the plain
`intervalMs` timer, and gets told which sensors are missing. That is the intended
behaviour and it is implemented.

`MotionQuality` is derived in `SensorProbe.kt:54`:

| Condition | Quality |
|---|---|
| No accelerometer | `POOR` |
| `ACTIVITY_RECOGNITION` denied **and** no significant-motion **and** no step-detector | `POOR` |
| Accelerometer + gyroscope + (SMD or step) | `FULL` |
| Anything else | `DEGRADED` |

Note the second row. `POOR` is not only a hardware verdict — a denied
`ACTIVITY_RECOGNITION` runtime permission on a device with no SMD sensor reaches it too,
and the message then reads `accelerometer=true`, which looks self-contradictory until you
notice the permission half.

---

## Finding 1 — the degraded event can never be observed by a normal host

**Severity: high.** The fallback is silent in practice for the integration shape the SDK's
own sample uses.

### The problem

`tracker.events` is created with no replay —
`fieldtrack-core/src/main/kotlin/com/field360/tracker/di/TrackerGraph.kt:151`:

```kotlin
MutableSharedFlow(
    replay = 0,
    extraBufferCapacity = EVENT_BUFFER,
    onBufferOverflow = BufferOverflow.DROP_OLDEST,
)
```

The `MOTION_DETECTION_DEGRADED` emit happens *inside* `ready()`. A `tryEmit` to a
`SharedFlow` with `replay = 0` and no active collector is dropped on the floor.

The documented integration order makes that the common case, not the edge case. From
`INTEGRATION-GUIDE.md` §3 and from `SampleApplication` itself, `ready()` is called in
`Application.onCreate` — deliberately, so filter state is restored before any UI exists.
The host's collector is created later, in an Activity or a ViewModel. Between those two
points the event has already been emitted and discarded.

**This reproduces in our own sample.** `SampleApplication.onCreate` calls `ready()`;
`TrackerViewModel`'s `tracker.events.collect` starts when `MainActivity` builds the view
model. On a `POOR` device the sample prints nothing at all, and the tester concludes the
device is fine.

### Why not just add replay

Tempting, and wrong as a general fix: `replay = 1` on the whole event flow means every new
collector receives one stale event of whatever kind happened last — a `ProviderChange`, an
`Error` from a session that has since ended. That is a behaviour change for every event in
the SDK to fix one of them.

### Proposed fix

Make the state readable rather than making the event stickier. Two parts:

**1a. Expose the resolved quality on `TrackerState`.**

`Tracker.getSensors()` already exists and already answers this (`Tracker.kt:539`), so a
host *can* poll it today. But nothing in the guide tells them to, and a fact that only
exists if you ask for it will not be asked for. Carrying `motionQuality` on the state flow
makes it observable the same way `isCapturing` and `providerState` are — a late collector
still gets the current value, because a `StateFlow` has one by construction.

Sketch, in `TrackerState`:

```kotlin
public data class TrackerState(
    // …existing fields…
    /**
     * What the SDK concluded about this device's motion hardware, and therefore whether
     * the configured `trackingMode` is the one actually running. `POOR` means the mode was
     * forced to CONTINUOUS — see ResolveConfigUseCase.
     */
    val motionQuality: MotionQuality = MotionQuality.FULL,
)
```

Set it where `resolveConfig` already stores its result, alongside `this.sensors = resolved.sensors`
in `Tracker.ready()` (`Tracker.kt:265`).

**1b. Also carry the *effective* mode.**

The host asked for `MOTION_ONLY` and is running `CONTINUOUS`. Nothing readable says so —
`Tracker` exposes no resolved config at all, which is the same gap the sample works around
by keeping its own copy of what it passed in. Either add `effectiveTrackingMode` to
`TrackerState`, or expose the resolved `TrackerConfig`; the second is more useful and is
worth considering on its own merits, since it would also let the sample's config console
show what is actually running rather than what it last sent.

**Keep the event.** It is still the right channel for a host that *is* collecting early,
and removing it would break anyone who already handles it.

### Test to add — still outstanding

A JVM test over `ResolveConfigUseCase` with a `POOR` probe asserting the returned config's
`trackingMode == CONTINUOUS`, and one with a `DEGRADED` probe asserting the widened
`stopTimeoutMin`.

**Not written, and it needs a design decision first.** `SensorProbe` is `final` with an
`internal constructor(Context, PermissionManager)`, so it cannot be faked on the JVM.
Testing this path needs either Robolectric or `SensorProbe` put behind an interface that
`ResolveConfigUseCase` depends on instead. The second is the better shape — the use case
wants a `DeviceSensors`, not a probe — but it is an API change to an `internal` seam and is
out of scope for these findings.

---

## Finding 2 — `MotionQuality.DEGRADED` is produced and never consumed

**Severity: medium.** A documented mitigation that does not exist.

### The problem

`SensorProbe.kt:80` documents the tier as:

```kotlin
/** Accelerometer present, gyroscope or trigger sensors missing. Widen stop timeout. */
DEGRADED,
```

Nothing widens the stop timeout. `MotionQuality.DEGRADED` appears exactly once in the whole
source tree — at `SensorProbe.kt:61`, where it is produced. No consumer reads it.

So a device with an accelerometer but no gyroscope and no significant-motion sensor runs
full `MOTION_ONLY` gating with `stopTimeoutMin` untouched. That device is precisely the one
whose stop detection is least reliable, and it currently gets no compensation at all —
while the KDoc tells a reader it does.

The failure this produces in the field is a session that reports STOPPED while the vehicle
is still moving slowly, or the reverse, on mid-range hardware — and it is invisible in
review because the comment says the case is handled.

### Proposed fix

Pick one. Both are defensible; they should not both stay unmade.

**2a. Implement it.** In `ResolveConfigUseCase`, alongside the `POOR` branch:

```kotlin
// A device with no gyroscope and no hardware trigger detects a stop later and less
// reliably than one with both. Widening the timeout trades stop *precision* for not
// declaring a stop that did not happen — the cheaper error of the two, because a late
// stop costs a few extra fixes and a false stop costs the rest of the trip.
val widened = if (sensors.motionQuality == MotionQuality.DEGRADED) {
    effective.copy(
        motion = effective.motion.copy(
            stopTimeoutMin = effective.motion.stopTimeoutMin * DEGRADED_STOP_TIMEOUT_FACTOR,
        ),
    )
} else { effective }
```

If this route is taken it needs the same treatment as the `POOR` branch: a `Diagnostic`
event saying the timeout was widened and from what to what, or it becomes the next silent
config rewrite nobody can explain from logcat.

**2b. Correct the KDoc.** If the widening is not wanted, the comment must stop claiming it:

```kotlin
/**
 * Accelerometer present, gyroscope or trigger sensors missing.
 *
 * Reported, not acted on: motion gating still runs as configured. Stop detection on such a
 * device is slower and less certain than on a `FULL` one, and a host that cares can read
 * this from `getSensors()` and widen `stopTimeoutMin` itself.
 */
DEGRADED,
```

**Recommendation: 2a.** The tier exists to be acted on — that is the stated difference
between this SDK and the incumbent's diagnostic-only `getSensors()`, and it is argued for
in `SensorProbe`'s own class KDoc. Leaving one of the three tiers inert undercuts the
claim.

---

## Finding 3 — the fallback is a battery regression and says nothing about it

**Severity: low.** Correct behaviour, incompletely communicated.

### The problem

`MOTION_ONLY` is the cheapest mode the SDK offers. `CONTINUOUS` is the most expensive one
short of `navigationMode`. The `POOR` fallback moves a host from the first to the second.

That is the right call — a motion-gated design on hardware that cannot detect motion
produces random gaps, and gaps are worse than battery. But a host that chose `MOTION_ONLY`
*for battery* gets the exact opposite of what it asked for, and the message it receives
(if it receives it at all — see Finding 1) talks only about sensors.

The two modes differ most where it is least visible. From
`LocationStreamController.kt:213`:

```
MOTION_ONLY  → stop() the stream while stationary. Wakes on SMD / activity-recognition / geofence exit.
ADAPTIVE     → keeps it running and lets the filter thin.
CONTINUOUS   → keeps it running, no tiering at all.
```

A phone parked for eight hours costs near zero on `MOTION_ONLY` and a full day of radio
duty on `CONTINUOUS`. The fallback silently converts the first into the second.

### Proposed fix

Extend the existing message rather than adding a channel:

```
"motionQuality=POOR (accelerometer=…, gyroscope=…, significantMotion=…, stepDetector=…); "
"forcing CONTINUOUS — the location stream will now run while stationary, which costs "
"materially more battery than the requested MOTION_ONLY"
```

One sentence, in the string that already exists. It is the difference between a host
filing "your SDK drains battery" and a host knowing why.

---

## Finding 4 — the sample cannot show any of this

**Severity: low.** Applies to `sample-android` only.

The Status tab reports the permission tier, provider state, battery and sync, but nothing
about motion hardware. On a `POOR` device it looks identical to a healthy one, and the
event that would have said otherwise is the one Finding 1 shows is lost.

### Proposed change

Add a `motion` card to `StatusScreen`, reading `tracker.getSensors()` — which needs no new
SDK surface and works today regardless of how Findings 1–3 are resolved:

```
[ MOTION ]                                   quality POOR
  accelerometer      ✔      gyroscope        ✘
  significant motion ✘      step detector    ✘
  activity recognition   DENIED
  ! motionQuality=POOR — trackingMode forced to CONTINUOUS
```

Wiring: probe once in `TrackerViewModel.init` and again on `refreshPermissions()` — the
verdict depends on `ACTIVITY_RECOGNITION`, so it changes when a grant changes, and a card
that reports a stale `POOR` after the user granted the permission is worse than no card.

Add `deviceSensors: DeviceSensors?` to `UiState`, populate from `tracker.getSensors()`.

---

## Documentation to update — applied

All of the below are done. `USER-GUIDE.md` §5.5 was updated too, which this list originally
missed: it documented the `POOR` override and nothing else, so leaving it would have had the
two guides disagreeing about what `DEGRADED` does.

`SDK-COMPARISON.md` §369 needed no change — it already specified "Widen `stopTimeout` ×2",
so Finding 2a implements what the design always intended. Note that the same row also calls
for "weight AR lower; prefer hardware speed" on `DEGRADED`, and **that half is still not
implemented**. Worth raising as its own finding.

`INTEGRATION-GUIDE.md`:

- **§5.2 `GeolocationConfig`** — the `trackingMode` row should say the mode can be
  overridden at `ready()` on `POOR` hardware, and link to where that is described.
- **New subsection under §12 Battery and sensors** — the `MotionQuality` table, what forces
  `CONTINUOUS`, and the fact that `ACTIVITY_RECOGNITION` being denied can trigger it on
  otherwise capable hardware. §12 already covers sensors and is the right home.
- **§7.1 `TrackerEvent`** — if Finding 1a lands, document `motionQuality` on `TrackerState`
  as the reliable read, and note explicitly that the `MOTION_DETECTION_DEGRADED` event
  fires during `ready()` and will be missed by a collector started afterwards. That caveat
  is worth stating even if nothing else here is done.
- **§20 Troubleshooting** — two rows:

| Symptom | Likely cause | Fix |
|---|---|---|
| `MOTION_ONLY` behaves like `CONTINUOUS`, battery high | `motionQuality = POOR` — the mode was forced at `ready()` | Read `tracker.getSensors()`. Check `ACTIVITY_RECOGNITION` is granted; a denial can cause this on hardware that is otherwise fine |
| Never saw `MOTION_DETECTION_DEGRADED` | The event is emitted during `ready()`, and `events` has `replay = 0` | Collect `tracker.events` before calling `ready()`, or read `getSensors()` on demand |

---

## Suggested order

1. **Finding 1a** — state exposure. Everything else is easier to verify once the condition
   is observable.
2. **Finding 4** — the sample card. Turns 1a into something testable by hand on a real
   device.
3. **Finding 2** — decide 2a or 2b and make the code and the comment agree.
4. **Finding 3** — one string.
5. Docs, once the above have settled.

Findings 3 and 4 are independent of the rest and can be taken alone if the SDK surface is
frozen.

---

## Appendix — battery cost by mode, for reference

Highest to lowest. Interval selection is `LocationSource.kt:41`; navigation beats turn
burst beats vehicular beats base.

| Mode | Interval used | Stream while stationary | Cost |
|---|---|---|---|
| `navigationMode = true` | `navigationIntervalMs` (1 s), forced `PRIORITY_HIGH_ACCURACY` | yes | Highest by a wide margin |
| `CONTINUOUS` | `intervalMs` always, no tiering | yes | High |
| `ADAPTIVE` | `intervalMs` / `vehicularIntervalMs` / `turnBurstIntervalMs` | yes — filter thins, radio stays registered | Medium |
| `MOTION_ONLY` | same tiers while moving | **no** — stream unregistered | Lowest |

Where the drain comes from, in rough order of size:

- **GNSS duty cycle.** A long interval lets the receiver power down between fixes. Below
  roughly a few seconds it cannot, and the chip stays in continuous-tracking mode. This is
  why 1 Hz navigation is a different power regime rather than "60× a 60 s request", and why
  `turnBurstIntervalMs = 4000` briefly enters that regime on every corner.
- **Priority.** `desiredAccuracy` maps to request priority; `HIGH` means GNSS, while
  `BALANCED` / `LOW` lean on Wi-Fi and cell, whose radios are usually already awake.
  Dropping `HIGH` → `BALANCED` often saves more than halving the interval, at the cost of
  the accuracy gate.
- **Process wakeups.** Every delivered fix wakes the process, runs the acceptance pipeline
  and writes Room. `maxUpdateDelayMs` asks the OS to batch — one wakeup per N fixes instead
  of N. Setting it to 0 removes that saving while changing nothing about the GNSS rate.
  `wakeLockMs` (20 s) is held per capture burst.
- **The stationary case**, which is where `ADAPTIVE` and `MOTION_ONLY` genuinely diverge.
  `ADAPTIVE` keeps the stream registered on purpose: the heartbeat is what self-corrects a
  device whose wake paths all failed (EC-57). That recovery guarantee is bought with
  battery.
- **Sensors are the cheap half.** Significant-motion and step-detector run on the sensor
  hub and wake the AP only when they fire. The gyroscope is the exception, which is why
  `useGyroTurnPrediction` opens it only at vehicular speed and releases it within a minute
  of the fixes stopping.
