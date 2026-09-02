# Plugin Identification — Tracker vs. the incumbent SDK

The benchmark is Transistor Software's Background Geolocation SDK (`com.transistorsoft:tslocationmanager`), the de-facto standard for background location and the library any Android team will compare Tracker against.

Tracker is a **native Android library** — no iOS, no React Native, no Flutter. Comparisons below are on the Android surface only; the incumbent's iOS and cross-platform reach is out of scope by design, not by schedule (§7).

This document identifies, feature by feature, where Tracker is at **parity**, where it **adapts**, where it is **deliberately different**, and where it **will not compete**. Four areas are covered in depth because they were called out specifically: stop detection, application lifecycle, config reset/persistence, and device sensors.

Sources: [Config](https://docs.transistorsoft.com/kotlin/Config/) · [ActivityConfig](https://docs.transistorsoft.com/kotlin/ActivityConfig/) · [AppConfig](https://docs.transistorsoft.com/kotlin/AppConfig/) · [BackgroundGeolocation API](https://docs.transistorsoft.com/react-native/BackgroundGeolocation/) · [Setup](https://docs.transistorsoft.com/kotlin/setup/)

---

## 1. Positioning in one paragraph

They solve **capture**: get location in the background, cheaply, without the OS killing you. They do it very well and they do not touch what happens next — the SDK hands you points and stops. Tracker solves capture **and plotting**: the nine-gate acceptance pipeline (which they do not have) plus a full on-device plotting plane that emits a ready-to-draw polyline with arrows, stop nodes and statistics (which nothing on the market has). Where their capture design is better than ours, we adopt it — stop detection, lifecycle defaults, config persistence and the sensor API in this document are all cases of that. What we give up in exchange is reach: they ship five platforms, we ship one, deeply.

---

## 2. Feature matrix

**Legend:** ✅ parity · 🔁 adapted (same goal, different mechanism) · ⭐ Tracker-only · ⛔ not planned

### Lifecycle & configuration

| Capability | Incumbent | Tracker | Notes |
|---|---|---|---|
| `ready(config)` once at startup | ✅ | ✅ | Same contract |
| Config persisted across launches | ✅ | ✅ | DataStore |
| `reset` flag on `ready()` | ✅ | ✅ | §5 — **our default matches theirs (`true`)** |
| `setConfig()` at runtime | ✅ | ✅ | Applied atomically, provider restart debounced (EC-122) |
| `reset()` to factory defaults | ✅ | ✅ | |
| `start()` / `stop()` | ✅ | ✅ | `start()` always opens a NEW session and stops any previous service first — one session at a time. `stop()` is safe to repeat and sweeps a stale service (EC-72, EC-74) |
| `changePace(moving)` | ✅ | ✅ | |
| `getState()` | ✅ | 🔁 | `StateFlow<TrackerState>` — reactive, not a one-shot getter |
| Scheduling (`schedule`, `startSchedule`) | ✅ | ⛔ | Host owns scheduling; `WorkManager` in the app is a one-liner |
| License key required for release builds | ✅ | ⭐ | Release builds check `TrackerConfig.license` or `TrackItLicense`; debuggable installs are waived |

### Geolocation

| Capability | Incumbent | Tracker | Notes |
|---|---|---|---|
| `desiredAccuracy` | ✅ | ✅ | |
| `distanceFilter` | ✅ | 🔁 | Ours defaults to **0 and is documented as a footgun** — a non-zero OS distance filter is a stationary-drift *generator*. Theirs is the primary sampling control; ours does all thinning in software |
| `stationaryRadius` | ✅ | ✅ | Wake fence while stationary |
| `stopTimeout` | ✅ | ✅ | §3 |
| `elasticityMultiplier`, `desiredOdometerAccuracy` | ✅ | ⛔ | Superseded by adaptive cadence + the acceptance pipeline |
| Odometer | ✅ | ✅ | `getOdometerMeters` / `resetOdometer` |
| Current location snapshot | `getCurrentPosition()` | `getCurrentLocation()` | Android returns a raw `TrackFix` snapshot without persisting it |
| `watchPosition()` | ✅ | 🔁 | `observePoints()` as a `Flow` |
| Adaptive cadence at speed | ⛔ | ⭐ | 12 s while vehicular — the biggest turn-fidelity win available without a routing API |
| Predictive turn burst (gyroscope) | ⛔ | ⭐ | Yaw rate about the world vertical arms the fast sampling tier as the wheel turns — before GNSS heading has moved. Every other SDK's turn handling, this one's included until now, can only react to a corner already taken |
| Deferred corner anchors | ⛔ | ⭐ | A fix the heuristic gate dropped is held for one fix and restored when the next one shows a corner turned across it — the apex vertex no backward-looking gate can recognise at the time |
| Bearing-change force capture | ⛔ | ⭐ | A fix whose heading turned > 30° since the last **stored** point is stored whatever the speed and distance gates decided; at a corner the geometry lives entirely in that angle |
| Turn-burst cadence tier | ⛔ | ⭐ | 4 s while measurably turning (≥ 3 °/s), 30 s hold. Adaptive cadence is a guess about the whole drive; this spends battery only where the geometry is |
| Nine-gate acceptance pipeline | ⛔ | ⭐ | Burst · NLP · phantom-Doppler · sanity · recovery · sigma · persistence · heartbeat · mock |
| Per-fix decision log with reason strings | ⛔ | ⭐ | Queryable table, not a log file |
| Deterministic fixture replay | ⛔ | ⭐ | Every accuracy complaint becomes a regression test |
| Mock-location policy | ⛔ | ⭐ | `FLAG` / `REJECT` / `ALLOW` |

### Motion & activity

| Capability | Incumbent | Tracker | Notes |
|---|---|---|---|
| Motion-triggered stop detection | ✅ | ✅ | §3 |
| `disableStopDetection` | ✅ | ⛔ | Declared but never implemented; deprecated for removal |
| `stopOnStationary` | ✅ | ⛔ | Declared but never implemented; deprecated for removal. Ending a session on a stop is the host's decision — `suppressWhileStationary` covers the case hosts actually want |
| Accelerometer veto on stationary drift | ⛔ | ✅ | `suppressWhileStationary` (EC-142). No competing SDK has it |
| `motionTriggerDelay` (Android) | ✅ | ✅ | |
| `stopDetectionDelay` | iOS only | ⛔ | Not applicable |
| `activityRecognitionInterval` | ✅ | ✅ | Default 10 s |
| `minimumActivityRecognitionConfidence` | ✅ | ✅ | Android default 75 |
| `onActivityChange` | ✅ | ✅ | |
| AR as a **capture gate** | — | ⛔ | Deliberately not. AR is enrichment only — entire drives are reported `STILL` on some OEMs under battery saver |
| Significant-motion hardware wake | ⛔ | ⭐ | §6 — permission-free, near-zero power |
| Step-count drift veto | ⛔ | ⭐ | §6 — the strongest available signal against stationary drift |

### Persistence & sync

| Capability | Incumbent | Tracker | Notes |
|---|---|---|---|
| SQLite persistence | ✅ | 🔁 | Room with reactive `Flow` queries instead of a polled API |
| `maxDaysToPersist`, `maxRecords` | ✅ | ✅ | |
| `getLocations` / `getCount` / `destroyLocations` | ✅ | ✅ | Paged (EC-80) |
| `insertLocation` | ✅ | ✅ | Validated like a real fix (EC-86) |
| HTTP `url`/`method`/`headers`/`params`/`autoSync`/`batchSync` | ✅ | 🔁 | Optional `fieldtrack-sync` module — core never touches the network |
| JWT / SAS authorization with auto-refresh | ✅ | ⛔ | Host's concern |
| `onHttp` event | ✅ | 🔁 | `TrackerSync.events` → `SyncEvent.HttpResponse(statusCode, count)`, one per exchange. In `fieldtrack-sync` only. No response body — it can be megabytes; implement `SyncTransport` if you need it |
| `Retry-After` honoured | ✅ | ✅ | Both delta-seconds and HTTP-date, clamped 1 s–6 h; the worker re-enqueues at the server's time |
| Terminal auth failures | ✅ (401) | ⭐ (401 **and** 403) | 401 clears the queue, 403 keeps it — a revoked key is the same user, an expired session may not be |
| **Session as a first-class entity** | ⛔ | ⭐ | Every point belongs to a session with a config snapshot |
| **Persisted filter state** | ⛔ | ⭐ | Survives process death; closes the cold-start teleport hole |

### Application lifecycle

| Capability | Incumbent | Tracker | Notes |
|---|---|---|---|
| `stopOnTerminate` | ✅ (default `true`) | 🔁 (default **`false`**) | §4 |
| `startOnBoot` | ✅ (default `false`) | 🔁 (default **`true`**) | §4 |
| `enableHeadless` + `registerHeadlessTask` | ✅ | ⛔ | §4 — the concept exists to survive a dead JS/Dart context. A Kotlin host collects `Tracker.events` from an application-scoped `CoroutineScope` instead |
| Foreground service + notification config | ✅ | ✅ | |
| `onNotificationAction` | ✅ | ✅ | |
| `heartbeatInterval` / `onHeartbeat` | ✅ (disabled on Android) | 🔁 | Ours is the **stationary filter heartbeat** — a different, load-bearing concept (§3) |

### Geofencing

| Capability | Incumbent | Tracker | Notes |
|---|---|---|---|
| Geofence CRUD, crossing history, enter/exit events | ✅ | ✅ | Both platforms support 19 persistent SDK-managed fences; Android uses Play Services system geofencing |

### Diagnostics & device

| Capability | Incumbent | Tracker | Notes |
|---|---|---|---|
| `getSensors()` | ✅ | ✅ ⭐ | §6 — we match the API **and act on it** |
| `getDeviceInfo()` | ✅ | ✅ | |
| `isPowerSaveMode` / `onPowerSaveChange` | ✅ | ✅ | |
| `onProviderChange` | ✅ | ✅ | Richer: permission tier + granularity + fused availability |
| `onLocationFilter` (rejected fixes) | ✅ | 🔁 | `LocationRejected(FixDecision)` — carries the reason, sigma, threshold |
| Logging with `logMaxDays`, log upload | ✅ | 🔁 | Decision table + fixture export |
| Debug sounds | ✅ | ⛔ | |

### Plotting — the whole differentiator

| Capability | Incumbent | Tracker |
|---|---|---|
| Stop consolidation (centroid, dwell) | ⛔ | ⭐ |
| Significant-node detection | ⛔ | ⭐ |
| Travel/dwell segmentation with duration-weighted p75 speed | ⛔ | ⭐ |
| Activity labelling with speed-bucket override | ⛔ | ⭐ |
| Bézier corner rounding | ⛔ | ⭐ |
| Pluggable road snapping, with an off-road guard | ⛔ | ⭐ |
| **Precomputed arrow anchors with bearings** | ⛔ | ⭐ |
| Encoded polyline + per-segment speed bands | ⛔ | ⭐ |
| GeoJSON export | ⛔ | ⭐ |
| Day/session statistics + activity breakdown | ⛔ | ⭐ |

---

## 3. Deep dive — stop detection

### How theirs works

Two states, `moving` and `stationary`. Location services run only while moving; while stationary they are off and the device is watched for motion. Transition back to moving comes from the motion API or from exiting a stationary geofence (~200 m on iOS, often < 10 m on Android).

| Property | Default | Behaviour |
|---|---|---|
| `disableStopDetection` | `false` | `true` disables motion-based stop detection entirely. Android then runs location continuously until `changePace(false)` or `stop()` |
| `stopTimeout` | platform | How long the SDK waits in the stop-pending state before committing to stationary |
| `stopOnStationary` | `false` | `true` calls `stop()` outright when `stopTimeout` elapses — ends the session, not just the sampling |
| `stopDetectionDelay` | 0 | **iOS only.** Grace period before stop detection engages |
| `motionTriggerDelay` | 0 | **Android only.** Delays committing to *moving*; cancelled if the device settles again |
| `activityRecognitionInterval` | 10 000 ms (min 500) | AR polling rate |
| `minimumActivityRecognitionConfidence` | 75 (Android), 70 (iOS) | Below this, transitions are ignored |

### What Tracker does

Parity on all Android-applicable knobs, plus two changes:

**1. Stop detection never gates capture, only sampling rate.** Their design treats the motion API as authoritative — if it says still, location goes off. On Android that is a real risk: the reference implementation documents entire 17-minute drives during which AR reported `STILL` on OnePlus and Xiaomi devices under battery saver. Tracker's `MOVING` transition has three independent triggers, any of which suffices:

```
AR ENTER(vehicle|walk|run|bike)   ─┐
significant-motion hardware wake  ─┼→  MOVING
stationary-fence EXIT             ─┤
a heartbeat fix > stationaryRadius from anchor ─┘
```

The fourth is the safety net their design lacks: even in `ADAPTIVE` mode a heartbeat fix still runs every 15 minutes while stationary, so a device whose AR is broken and whose geofence never fired still self-corrects within one heartbeat instead of never.

**2. Two different things are called "heartbeat", and conflating them breaks stationary accuracy.** Theirs is a control-plane timer (`heartbeatInterval`, disabled on Android) that fires an event while stationary. Ours is a **data-plane** mechanism: while stationary, one fix per 900 s is accepted *into the Kalman filter* and deliberately **not stored**. It keeps the prediction clock warm so the next real movement is not gated as an outlier, while producing zero rows for a parked user. That is what makes "2 hours steady ⇒ exactly one stored point" achievable (EC-48). We ship both, named differently: `motion.heartbeatIntervalSec` (data) and `TrackerEvent.Heartbeat` (control).

```kotlin
data class MotionConfig(
    val activityRecognition: Boolean = true,
    val activityRecognitionIntervalMs: Long = 10_000,      // parity
    val activityConfidenceMin: Int = 75,                   // parity (Android)
    val snapshotConfidenceMin: Int = 50,                   // one-shot seed only
    val disableStopDetection: Boolean = false,             // DEPRECATED — never implemented
    val stopOnStationary: Boolean = false,                 // DEPRECATED — never implemented
    val suppressWhileStationary: Boolean = false,          // accelerometer veto (EC-142)
    val stillnessEscapeMin: Int = 30,                      // its safety valve
    val stopTimeoutMin: Int = 5,                           // parity
    val motionTriggerDelayMs: Long = 0,                    // parity (Android)
    val stationaryRadiusM: Float = 150f,
    val heartbeatIntervalSec: Int = 900,                   // DATA-plane
    val persistHeartbeat: Boolean = false,
    val bearingChangeCaptureDeg: Int = 30,
    val useSignificantMotion: Boolean = true,              // §6
    val useStepCorroboration: Boolean = true,              // §6
)
```

---

## 4. Deep dive — application lifecycle & termination

### How theirs works

Three related knobs. `stopOnTerminate` (default **`true`**) decides whether a swipe-away ends tracking. `startOnBoot` (default **`false`**) decides whether a reboot resumes it. `enableHeadless` (Android only, default `false`) lets the SDK deliver events to a registered callback after the app's *JS* context is gone.

The crucial nuance from their docs: with `stopOnTerminate: false` the SDK records and uploads locations **regardless** of `enableHeadless`. Headless is only needed when you want *custom* work to run with no UI — a local notification, a bespoke upload.

### What Tracker does

```kotlin
data class ServiceConfig(
    val foregroundService: Boolean = true,
    val notification: NotificationConfig,
    val stopOnTerminate: Boolean = false,     // ← their default is true
    val startOnBoot: Boolean = true,          // ← their default is false
    …
)
```

**Why our defaults are inverted.** Theirs are conservative — safe for an app that only sometimes wants background tracking, and they document flipping them. But an SDK whose entire purpose is surviving termination should not ship defaults under which a swipe-away silently ends tracking; the failure is invisible and the user only discovers it as missing data. We flip them and document the flip.

**No headless API, because the problem it solves does not exist here.** `enableHeadless` is a bridge artifact: it exists because a JS context or a Flutter isolate can die while the native process lives on, so events need somewhere else to land. Tracker is consumed by Kotlin and Java directly — capture, filtering and storage all run in `fieldtrack-core` inside the foreground service, in the same process as the host. A host that wants work to continue with no UI on screen collects `Tracker.events` from an application- or service-scoped `CoroutineScope`; there is nothing to re-register and nothing to keep alive (EC-114).

The invariant that matters is unchanged and stated plainly: **capture never depends on a host collector being alive.** If every subscriber goes away, fixes are still filtered and still written to Room. Collectors are for reacting, never for recording.

The one genuine loss versus their design is force-quit on aggressive OEMs — no Android SDK survives that, theirs included. It is handled by the survival stack in [PERMISSIONS.md](PERMISSIONS.md) §7 and surfaced as `SessionInterrupted` on next launch (EC-66).

---

## 5. Deep dive — config reset & persistence

### How theirs works

The SDK persists its configuration across app launches; `ready()` is called once at startup and subsequent launches load the persisted config. The `reset` flag (**default `true`**) controls whether `ready()` applies your `Config` on top of factory defaults, or leaves the persisted config in place.

Their warning is worth repeating verbatim in spirit: during development always leave `reset: true`, because with `reset: false` the SDK **ignores your `Config` after the first launch** — you edit constants, rebuild, and nothing changes. It is a genuinely confusing failure mode and the single most common support question in this category.

### What Tracker does

Same semantics, same default:

```kotlin
data class TrackerConfig(
    …
    /**
     * true  (default) — ready() applies this config on top of factory defaults.
     * false           — the persisted config wins and THIS OBJECT IS IGNORED after
     *                   the first launch. Only setConfig() can change anything.
     *
     * Leave true during development. A false here with edited constants is the
     * classic "my config changes do nothing" bug.
     */
    val reset: Boolean = true,
)
```

Three additions on top of parity:

1. **`ready()` logs the effective config** at `INFO` on every launch, with a `source` marker per section (`default` / `persisted` / `supplied`). The "why is my config ignored" question is answerable from a logcat line instead of a support thread.
2. **The config is snapshotted onto the session row.** `track_session.configSnapshot` records exactly what was in effect, so a track recorded six weeks ago can be interpreted correctly even after the config changed. This also makes fixture replay honest — a fixture carries the config it was recorded under.
3. **Forward-compatible decoding.** Config persisted by a newer SDK version, then read by an older one, drops unknown keys with a log rather than failing to start (EC-124). A library that bricks itself on downgrade is not shippable.

> **Correction to earlier drafts:** [API.md](API.md) previously stated `reset` defaulted to `false` "for BGGeo parity". That was wrong — their default is `true`, and ours now matches. Fixed.

---

## 6. Deep dive — device sensors

### How theirs works

`getSensors()` reports availability of accelerometer, gyroscope and magnetometer. Their documentation is blunt about why it matters: these sensors power the motion activity-recognition system, and when any are missing — common on cheap Android hardware — motion detection is *"severely degraded and highly inaccurate."*

It is a **diagnostic**: it tells you the SDK will behave badly. It does not change what the SDK does.

### What Tracker does — parity, then use them

We match the API and then act on the answer, and we enumerate more than three sensors because several are directly useful for accuracy and for battery management.

```kotlin
data class DeviceSensors(
    val accelerometer: Boolean,
    val gyroscope: Boolean,
    val magnetometer: Boolean,
    val significantMotion: Boolean,
    val stepDetector: Boolean,
    val stepCounter: Boolean,
    val barometer: Boolean,
    val rotationVector: Boolean,
    val motionQuality: MotionQuality,     // FULL | DEGRADED | POOR
)

suspend fun Tracker.getSensors(): DeviceSensors
```

| Sensor | `Sensor.TYPE_*` | Permission | Power | Tracker use |
|---|---|---|---|---|
| Accelerometer | `ACCELEROMETER` | none | low | Stationarity corroboration; phantom-Doppler veto |
| Gyroscope | `GYROSCOPE` | none | medium | AR quality signal; turn assist |
| Magnetometer | `MAGNETIC_FIELD` | none | low | Heading when GNSS bearing is invalid |
| **Significant motion** | `SIGNIFICANT_MOTION` | **none** | ~zero (hardware interrupt) | **Permission-free stationary → moving wake** |
| **Step detector** | `STEP_DETECTOR` | `ACTIVITY_RECOGNITION` (API 29+) | ~zero (hardware) | **Drift veto / walk confirmation** |
| Step counter | `STEP_COUNTER` | `ACTIVITY_RECOGNITION` | ~zero | Cumulative steps between fixes |
| Barometer | `PRESSURE` | none | low | Elevator / floor change; explains signal gaps |
| Rotation vector | `ROTATION_VECTOR` | none | medium | Pocket vs. mounted; arrow heading at low speed |

#### 6.1 Significant motion as a wake source ⭐

`TYPE_SIGNIFICANT_MOTION` is a hardware trigger sensor: the SoC's sensor hub raises a one-shot interrupt when the device has genuinely moved. It costs effectively nothing, needs **no runtime permission**, and needs **no Google Play Services**.

That makes it a strictly better third wake path than either of the two the incumbent relies on:

- it works when `ACTIVITY_RECOGNITION` is denied (EC-09);
- it works when Play Services is missing — Huawei, AOSP builds (EC-19);
- it works when geofence registration failed (EC-58);
- it fires on movement far below the 150 m stationary radius, so a user who walks to a different room and stays there is detected in seconds rather than at the next 15-minute heartbeat (EC-57).

```kotlin
internal class SignificantMotionWake(private val sm: SensorManager) {
    private val sensor = sm.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
    private var listener: TriggerEventListener? = null

    fun arm(onMotion: () -> Unit) {
        val s = sensor ?: return
        // One-shot by contract: the listener is auto-disabled after firing and MUST
        // be re-armed. Forgetting this is why trigger sensors "stop working".
        listener = object : TriggerEventListener() {
            override fun onTrigger(e: TriggerEvent) { onMotion(); arm(onMotion) }
        }.also { sm.requestTriggerSensor(it, s) }
    }

    fun disarm() { listener?.let { sensor?.let { s -> sm.cancelTriggerSensor(it, s) } }; listener = null }
}
```

#### 6.2 Step corroboration — a hard veto on stationary drift ⭐

This is the most valuable of the four topics, because it attacks the hardest problem in the domain with a signal no competing SDK uses.

Stationary drift is *displacement without motion*. Every existing defence is statistical — wobble guards, R-penalties, net-displacement persistence — because position is the only evidence available. The pedometer is **independent physical evidence**: it is hardware-batched, near-zero power, and it cannot be fooled by multipath.

New pipeline stage, inserted after motion-state determination and gated on sensor availability:

```
Stage 2a — pedestrian corroboration (skipped entirely when the sensor is absent
           or ACTIVITY_RECOGNITION is denied; behaviour then falls back to today's)

steps = stepsSince(past.elapsedRealtimeNanos)

if isHardwareStationary && distanceMoved in 10.0..80.0:
    if steps == 0        → force STATIONARY classification, bypass the isMoving branch
                           reason: "No Steps"       // displacement cannot be walking
    if steps >= 20 && distanceMoved > 15 → treat as genuine pedestrian movement even
                           with hwSpeed ≈ 0         // indoor Doppler is often absent
```

Two symptoms this fixes that position-only logic cannot:

- **False walks from drift.** A 60 m indoor excursion with zero steps is definitively noise. Today it must survive the wobble guard *and* net-displacement persistence, which costs two to three fixes of latency and occasionally lets a slow drift-loop through (EC-39).
- **Missed indoor walks.** Walking inside a large building often yields no hardware speed at all, so `isHardwareStationary` is true and the 40 m wobble guard suppresses real movement until net displacement passes 100 m. With 30 steps on the clock, the `Walk Arrival` branch can fire immediately (EC-46).

#### 6.3 Accelerometer veto for phantom Doppler

Some chipsets report a 3–8 m/s hardware speed while physically parked (documented on the Moto G34 family). Stage 2 already corrects this by trusting point-to-point displacement over the chip. A one-second accelerometer window makes the correction certain rather than heuristic: if variance sits at the at-rest floor, the reported speed is false, full stop (EC-36, EC-37).

#### 6.4 Barometer to explain signal gaps

A pressure delta of roughly 0.4 hPa ≈ 3–4 m of altitude. A signal gap accompanied by a monotonic pressure change is an **elevator**, not a teleport — so the tiered-recovery stage can prefer *hold-and-confirm* over *immediate reset* with high confidence, and the plotting side can label the dwell correctly. The reference implementation already has a `FloorDetectionProvider` doing the altitude maths; this reuses that idea for a different purpose.

#### 6.5 `motionQuality` drives automatic degradation

Where the incumbent reports the problem, Tracker reacts to it:

| Quality | Condition | Automatic response |
|---|---|---|
| `FULL` | accelerometer + gyroscope + (significant motion ∨ step detector) | Default behaviour |
| `DEGRADED` | accelerometer present, gyroscope or trigger sensors missing | Widen `stopTimeout` ×2; weight AR lower; prefer hardware speed |
| `POOR` | no accelerometer, or AR permission denied and no trigger sensor | Force `CONTINUOUS` mode (motion gating is not trustworthy); emit `Error(MOTION_DETECTION_DEGRADED)` so the host can warn; log the missing sensors by name |

This is the honest answer to "cheap Android devices behave badly": detect it, say so, and change strategy — rather than run a motion-gated design on hardware that cannot support it.

#### 6.6 Battery discipline

Sensors are only registered while a session is active, and unregistered in `stop()` and `onDestroy` alongside the location stream:

- significant motion — armed **only** in `STATIONARY`, disarmed on `MOVING`;
- step detector — `SENSOR_DELAY_NORMAL` with `maxReportLatencyUs = 60 s` so the sensor hub batches and the AP never wakes for it;
- accelerometer — sampled in ~1 s bursts on demand for the phantom-Doppler veto, never streamed;
- barometer — `SENSOR_DELAY_NORMAL`, batched, and only while `useBarometer` is enabled;
- gyroscope and rotation vector — availability probed at `ready()`, not subscribed by default.

```kotlin
data class SensorConfig(
    val useSignificantMotion: Boolean = true,
    val useStepCorroboration: Boolean = true,
    val useAccelerometerVeto: Boolean = true,              // DEPRECATED — never implemented
    val useBarometer: Boolean = false,
    val stepBatchLatencyMs: Long = 60_000,
)
```

---

## 7. What we will not compete on

Stated plainly so nobody is surprised:

- **Every platform except Android.** Theirs is mature on iOS, React Native, Flutter, Capacitor and Cordova. Ours is a native Android library and is not planned to be anything else. A team that needs one tracking SDK across iOS and Android should use theirs; this is the deliberate trade that buys the acceptance pipeline and the plotting plane.
- **Geofencing.** Their infinite-geofencing implementation is genuinely excellent and hard to match. Out of scope for v1.
- **Scheduling.** `schedule` / `startSchedule` are convenient but the host can do it with `WorkManager`.
- **Auth token refresh.** JWT/SAS handling belongs in the app.
- **Field maturity.** They have years of production hours across thousands of apps. We have a field-hardened *algorithm* from one large deployment and a plan; phase 7 exists to close that gap on real hardware, not on paper.

---

## 8. Net position

| Dimension | Verdict |
|---|---|
| Battery-efficient background capture | Parity by adoption — their stop-detection model, plus a third permission-free wake path |
| Noise rejection and stationary accuracy | **Clear advantage** — nine dedicated gates plus hardware step corroboration; they have none of this |
| Turn fidelity | **Advantage at capture** — three offline layers (adaptive cadence, bearing-change capture, turn-burst tier) plus Bézier at render. Optional map-matching on top via `RoadSnapProvider`; the offline layers came first precisely so a host with no provider is not left behind |
| Debuggability | **Clear advantage** — decision table with reason strings and deterministic replay |
| Plotting | **No competition** — they don't have it |
| Cross-platform reach | **Behind, permanently** — Android only, by design |
| Geofencing | **Behind**, deliberately |
| Maturity | **Behind** — the honest gap, and the reason phase 7 is ten days of field work |
