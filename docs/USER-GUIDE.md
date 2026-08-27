# FieldTrack — User Guide

Everything a host app needs to integrate the SDK, in the order you will need it.

This is the **integration** manual. It documents the SDK as it exists in the code today,
not the intended surface. Where a capability is planned but not shipped, it says so.

| I want to… | Section |
|---|---|
| Add the dependency and get a first point on a map | [1](#1-install) · [2](#2-quick-start) |
| Understand what the SDK does before I trust it | [3](#3-mental-model) |
| Ask for permissions correctly | [4](#4-permissions) |
| Configure it — accuracy, provider, cadence, battery | [5](#5-configuration) |
| Read stored points and build a drawable track | [6](#6-reading-data) · [7](#7-plotting) |
| Draw a live, animated position on a map | [8](#8-live-tracking) |
| React to errors, revocations, motion changes | [9](#9-events--state) |
| Work out why a point is missing or wrong | [10](#10-diagnostics) |
| Upload points to my backend | [11](#11-optional-modules) — API [11.2](#112-the-api-surface), request/response [11.4](#114-the-request)·[11.5](#115-the-response) |
| Use it from Java or React Native | [12](#12-java) · [13](#13-react-native) |
| Stop a spoofed device sending fake locations | [15](#15-device-integrity) |
| Fix a problem I am seeing right now | [14](#14-troubleshooting) |

---

## 1. Install

Group `com.github.fieldtrack360.fieldtrack`, version `0.1.1-alpha01`. `minSdk 26`, `compileSdk 37`, JDK 17.

Nothing has been published to a remote repository yet. Until a release is cut, build the
artifacts locally:

```bash
./gradlew publishToMavenLocal
```

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        mavenLocal()          // until a remote is configured
        google()
        mavenCentral()
    }
}
```

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("com.github.fieldtrack360.fieldtrack:fieldtrack-core:0.1.1-alpha01")   // required
    implementation("com.github.fieldtrack360.fieldtrack:fieldtrack-geo:0.1.1-alpha01")    // pulled in transitively; declare if you use the types directly

    // Optional, add only what you use:
    implementation("com.github.fieldtrack360.fieldtrack:fieldtrack-maps:0.1.1-alpha01")   // Google Maps rendering
    implementation("com.github.fieldtrack360.fieldtrack:fieldtrack-sync:0.1.1-alpha01")   // HTTP upload queue
    implementation("com.github.fieldtrack360.fieldtrack:fieldtrack-snap:0.1.1-alpha01")   // OSRM map-matching
    implementation("com.github.fieldtrack360.fieldtrack:fieldtrack-bridge:0.1.1-alpha01") // Java + JSON facades

    // fieldtrack-sync and fieldtrack-snap declare Retrofit and OkHttp as compileOnly
    // — supply your own. Retrofit 3 requires OkHttp 5.
    implementation("com.squareup.retrofit2:retrofit:3.0.0")
    implementation("com.squareup.okhttp3:okhttp:5.4.0")
}
```

### What you do *not* have to add

- **No DI framework.** No Hilt, no `@HiltAndroidApp`, no KSP, no Gradle plugin. The object
  graph is wired by hand inside the SDK.
- **No manifest entries.** Every permission, the foreground service and all three receivers
  are declared in the AAR and merge into your APK. What merges in:

  `ACCESS_COARSE_LOCATION`, `ACCESS_FINE_LOCATION`, `ACCESS_BACKGROUND_LOCATION`,
  `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`, `POST_NOTIFICATIONS`,
  `ACTIVITY_RECOGNITION` (+ the GMS variant), `RECEIVE_BOOT_COMPLETED`, `WAKE_LOCK`,
  `ACCESS_NETWORK_STATE`.

  `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is deliberately **not** declared — it is
  Play-policy sensitive and must be your explicit choice (EC-15).
- **No ProGuard rules.** `consumer-rules.pro` ships in the AAR.

### Play Services

`LocationProviderType.FUSED` (the default) needs Google Play Services. If you must support
devices without it — Huawei, AOSP builds — see [§5.3](#53-provider-type) for `GPS_ONLY`,
which runs on the platform `LocationManager` and needs nothing from Google.

---

## 2. Quick start

Three calls. `getInstance` → `ready` → `start`.

```kotlin
class SampleApplication : Application() {

    val trackIt: Tracker by lazy { Tracker.getInstance(this) }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            trackIt.ready(TrackerConfig())
        }
    }
}
```

```kotlin
// In a ViewModel, after permissions are granted:
suspend fun begin() {
    when (val result = trackIt.start(tag = "commute")) {
        is TrackerResult.Ok -> Log.d("app", "session ${result.value.id}")
        is TrackerResult.Error -> Log.w("app", "${result.code}: ${result.message}")
    }
}

suspend fun end() {
    trackIt.stop()
}
```

```kotlin
// Later — a ready-to-draw track:
val track = trackIt.buildTrack(PointQuery(sessionId = sessionId))
val json = trackIt.exportPolylineJson(PointQuery(sessionId = sessionId))
```

### The three calls, precisely

| Call | What it does | When |
|---|---|---|
| `Tracker.getInstance(context)` | Returns the process-wide instance. Idempotent, thread-safe, cheap — the graph is lazy, so this opens no database and touches no disk. Safe to pass an Activity; the application context is what is retained. | Anywhere |
| `ready(config)` | Resolves the effective config, restores persisted filter state, enqueues the retention worker, and reports a session left open by a crash. | Once, in `Application.onCreate` |
| `start(tag)` | Opens a session and starts the capture pipeline. **Always a new session, and only one is ever active** — anything still running is stopped first, service and workers included. A double tap therefore produces two session ids; gate your Start control on `state.isTracking`. | After permissions |

Call `ready()` from `Application.onCreate`, not from an Activity: it restores filter state
and reports an interrupted session, and both should happen before any UI exists to observe
them.

### Nothing throws

Every fallible entry point returns `TrackerResult<T>`:

```kotlin
public sealed interface TrackerResult<out T> {
    public data class Ok<T>(val value: T) : TrackerResult<T>
    public data class Error(val code: ErrorCode, val message: String) : TrackerResult<Nothing>
}
```

An SDK that throws into a host's coroutine is a crash the host cannot reasonably prevent.
The one deliberate exception is `TrackerConfig.Builder.build()`, which runs on your own
thread while you assemble a value — see [§5.1](#51-the-builder).

---

## 3. Mental model

Worth ten minutes before you configure anything, because most integration problems are a
misunderstanding of one of these five facts.

### 3.1 A fix is not a point

The OS delivers *fixes*. The SDK stores *points*. Between them sits a seven-stage
acceptance pipeline over a constant-velocity Kalman filter, and it drops most fixes on
purpose.

```
OS fix ──▶ FixMapper ──▶ ClockGuard ──▶ TurnDetector ──▶ AcceptancePipeline ──┬──▶ stored point
           (validity)     (reboot,        (cadence)       (7 stages)          │
                           reordering)                                        └──▶ decision log
```

Every fix gets exactly one of three verdicts:

| Verdict | Meaning |
|---|---|
| `Accept` | Stored, emitted as `TrackerEvent.Location` |
| `Skip` | The filter learned from it; nothing stored |
| `Reject` | Dropped entirely |

**A user sitting still for two hours produces one point, not a drift cloud.** That is the
design working, not a bug. If you expected a point per interval, see
[§14](#14-troubleshooting).

### 3.2 Everything belongs to a session

`start()` opens a session; `stop()` closes it. Points, decisions and raw layers are all
keyed by session id. The session also carries a **snapshot of the config that was in
effect**, so a track recorded six weeks ago can still be interpreted after you changed the
config.

Every `start()` is a new session. A session left open by process death is closed first, so
two runs never share an id.

### 3.3 Motion state drives cadence, never capture

```
STOPPED ──▶ MOVING ⇄ STOP_PENDING ⇄ STATIONARY
```

Activity recognition, hardware speed and a stationary geofence feed this machine. It
changes **how often** location is sampled. It never gates whether a fix is captured —
entire 17-minute drives are reported `STILL` on some OnePlus and Xiaomi devices under
battery saver, and gating capture on that would lose the whole trip (EC-53).

### 3.4 Cadence has four tiers

Fastest wins:

| Tier | Default | When |
|---|---|---|
| Navigation | 1 s | `navigationMode = true` — outranks everything |
| Turn burst | 4 s | `TurnDetector` says the vehicle is measurably turning |
| Vehicular | 12 s | A fix reports **≥ 2.5 m/s** (≈ 9 km/h), `adaptiveCadence = true`. Dropped at `STOP_PENDING` and restored if the vehicle pulls away |
| Normal | 60 s | Everything else — **including walking**, which is moving but not vehicular |

### 3.5 Stored points are raw; the puck is filtered

Stored `TrackPoint` coordinates are the fix's own coordinates, deliberately — the record
says where the device was measured, not where a filter believed it was. The filter's
estimate is exposed separately, as `PuckState` on the live feed ([§8](#8-live-tracking)),
which is the display-side use it always deserved.

---

### One session at a time

`start()` enforces this rather than assuming it. Before the new session row is written, and
only after every gate has passed, the SDK brings down whatever was running:

- the capture stream, the motion controller and the ingest channel
- every session-scoped sensor — step detector, gyroscope, activity recognition, the
  significant-motion wake and the stationary fence
- `BackstopWorker` and `RestoreWorker`
- the open session row, **then** the foreground service — in that order, because every
  resurrection path in the SDK gates on "is a session open"

`stop()` runs the identical teardown. It also runs it when no session is open, which is not
a no-op: a service left alive by a sticky restart, or one the host never stopped, is exactly
what needs killing there.

A `start()` that fails a gate — `PERMISSION_DENIED`, `COARSE_ONLY`,
`PLAY_SERVICES_UNAVAILABLE` — tears nothing down. A refused start must not take a healthy
running session with it.

When a start does supersede a live session you get `TrackerEvent.Diagnostic` naming the old
session id, followed by `EnabledChange(true)` for the new one.

---

## 4. Permissions

**The SDK shows no UI.** No dialogs, no activities, no full-screen intents. It answers
questions and hands you permission arrays and a Settings intent. You own every prompt.

```kotlin
val permissions = trackIt.permissions()   // PermissionManager
```

### 4.1 The ladder, in order

Ask in this order. Each step has a reason.

```kotlin
// Step 0 — API 33+. Ask FIRST. An invisible foreground-service notification is a
// transparency failure and an OEM-kill risk (EC-08).
launcher.launch(permissions.notificationPermissions())

// Step 1 — fine + coarse together, in ONE request. Adding background to this array
// makes Android deny it silently (EC-04).
launcher.launch(permissions.foregroundPermissions())

// Step 2 — background, only after fine is granted and you have shown a rationale.
when (val request = permissions.backgroundRequest()) {
    is PermissionManager.BackgroundRequest.AlreadyGranted -> Unit
    is PermissionManager.BackgroundRequest.NotApplicable -> Unit          // API < 29
    is PermissionManager.BackgroundRequest.NeedsForegroundFirst -> askForeground()
    is PermissionManager.BackgroundRequest.Prompt -> launcher.launch(request.permissions)
    is PermissionManager.BackgroundRequest.NeedsSettings -> startActivity(request.intent)
}

// Step 3 — optional. Denial degrades motion detection to speed + displacement (EC-09).
launcher.launch(permissions.activityRecognitionPermissions())
```

From Android 11 the OS will not show a background-location prompt at all, which is why
`NeedsSettings` exists: a runtime request there appears to do nothing. Deep-link to
Settings and explain "Allow all the time" in your own words.

### 4.2 Don't prompt-loop

```kotlin
if (permissions.shouldStopAsking(attempts)) {
    // Only offer the Settings route from here on. Cap is 3.
    startActivity(permissions.appSettingsIntent())
}
```

### 4.3 Tiers, and what each one allows

| `permissions.tier()` | `start()` behaviour |
|---|---|
| `NONE` | Returns `Error(PERMISSION_DENIED)` |
| `FOREGROUND_ONLY` | **Starts anyway.** Tracks while the app is visible; emits `Error(BACKGROUND_PERMISSION_MISSING)` so you can tell the user. Degrade, don't refuse (EC-03) |
| `FULL` | Full background tracking |

Separately, `permissions.accuracy()` returns `APPROXIMATE` or `PRECISE`. A 1–3 km error
circle defeats every gate in the pipeline, so `start()` refuses `CONTINUOUS` and `ADAPTIVE`
under approximate-only with `Error(COARSE_ONLY)`. `MOTION_ONLY` is allowed.

### 4.4 Check before you start, not after

`start()` answers with a typed error and shows nothing. Forwarding `PERMISSION_DENIED` to a
text field leaves the user holding a code they cannot act on, so ask first — every question
`start()` will ask is answerable from `Tracker.permissions()` before you call it:

```kotlin
val permissions = tracker.permissions()
val missing = buildList {
    when (permissions.tier()) {
        PermissionTier.NONE           -> add("Location")            // start() → PERMISSION_DENIED
        PermissionTier.FOREGROUND_ONLY -> add("Location all the time") // degrades, still starts
        PermissionTier.FULL           -> Unit
    }
    // ADAPTIVE and CONTINUOUS refuse approximate-only; MOTION_ONLY allows it (EC-02).
    if (permissions.accuracy() == LocationAccuracy.APPROXIMATE) add("Precise location")
    if (!permissions.hasNotificationPermission()) add("Notifications")
    if (!permissions.hasActivityRecognition()) add("Physical activity")
}

if (missing.isEmpty()) tracker.start(tag = "shift") else showYourOwnDialog(missing)
```

Two of those refuse the session and three only degrade it, so say which is which and offer
"start anyway" for the second group — a dialog that treats a missing activity-recognition
grant like a missing location grant trains users to deny both. `hasNotificationPermission()`
and `hasActivityRecognition()` already answer `true` on the API levels that have no such
permission, so no version checks are needed around them.

Ask for background location **separately and afterwards**: bundling it into the same
request array makes Android deny it silently (EC-04), and from Android 11 there is no
prompt for it at all (EC-05). `sample-android`'s `PermissionAlertDialog` is a working
version of all of this.

### 4.5 Revocation and recovery mid-session

A permission can be revoked, and the GPS switched off, while the foreground service is
running. The SDK watches `AppOpsManager` (both the fine and the coarse op) and the
`PROVIDERS_CHANGED` broadcast, and reacts immediately — no polling.

**The session always stays open.** Whether to end a drive because a user tapped a toggle is
your decision, never a side effect inside the SDK. What the SDK does instead is suspend
capture, tell you, and re-arm itself when the device allows it again:

| What the user did | Events you receive | What capture does |
|---|---|---|
| Revoked location entirely | `PermissionChange(→ NONE)`, `CaptureSuspended(PERMISSION_DENIED)`, `Error(PERMISSION_DENIED)` | Stops. The request is torn down, not left registered against a provider you may no longer read |
| Granted it again | `PermissionChange(NONE →)`, `CaptureResumed` | Restarts in the **same session**, plus one immediate fix so the gap has a boundary |
| Dropped "Allow all the time" to "While using the app" | `PermissionChange(FULL → FOREGROUND_ONLY)`, `Error(BACKGROUND_PERMISSION_MISSING)` | Keeps running. Degrade, never refuse |
| Turned off precise location | `PermissionChange` with `accuracy = APPROXIMATE`, `Error(COARSE_ONLY)` | Keeps running. The acceptance pipeline judges the error circle |
| Turned off GPS, network positioning still on | `ProviderChange` only | Keeps running — this is a degradation, not an outage |
| Turned off every provider, or the master switch | `LocationServicesChange(false)`, `CaptureSuspended(LOCATION_DISABLED)`, `Error(LOCATION_DISABLED)` | Stops |
| Turned location back on | `LocationServicesChange(true)`, `CaptureResumed` | Restarts in the same session |

`TrackerState.isCapturing` carries the same fact as a value: `isTracking = true` with
`isCapturing = false` means the session is open and suspended. Show that state — an open
session with a revoked permission otherwise looks identical to a healthy parked one.

Starting a session while location is switched off is allowed and does **not** return an
error: the session opens, capture is suspended immediately, and it begins for real when the
switch comes back. The record should exist with a documented gap in it rather than not
exist at all.

`Tracker.refreshProviderState()` forces a re-read. The SDK already does this on every
`start()` and `getCurrentLocation()`; call it from `onResume` if a screen's own decisions
depend on the GPS switch, since a broadcast that fires while your process is dead is never
delivered.

---

## 5. Configuration

`TrackerConfig` has five blocks: `geolocation`, `motion`, `service`, `persistence`,
`sensors` — plus `license`, `baseUrl` and `reset`.

### 5.1 The builder

```kotlin
val config = TrackerConfig.builder()
    .provider(LocationProviderType.GPS_ONLY)
    .accuracyProfile(AccuracyProfile.STRICT)
    .trackingMode(TrackingMode.ADAPTIVE)
    .intervalMs(60_000)
    .useStepCorroboration(true)
    .notification("Recording your route", "Tap to open")
    .maxDaysToPersist(7)
    .baseUrl("https://api.example.com")   // for fieldtrack-sync; core never reads it
    .build()

trackIt.ready(config)
```

`build()` runs `validate()` and throws `IllegalArgumentException` on failure. That is the
one fail-fast entry point in the SDK, deliberately: it runs on your own thread while you
assemble a value, which is where fail-fast belongs. `buildUnchecked()` returns the same
value unvalidated if you are assembling config from untrusted input and would rather read
`validate()` yourself.

The Kotlin data-class constructor is unchanged and still idiomatic:

```kotlin
TrackerConfig(
    geolocation = GeolocationConfig(trackingMode = TrackingMode.CONTINUOUS),
    persistence = PersistenceConfig(persistRawPoints = true),
)
```

Use the builder from Java, or when you want to set two knobs without naming five nested
classes.

### 5.2 The `reset` flag — read this one

```kotlin
val reset: Boolean = true
```

- `true` (default) — `ready()` applies your config on top of factory defaults.
- `false` — the **persisted** config wins, and your object is ignored after the first
  launch.

Leave it `true` during development. A `false` here with edited constants is the classic
"my config changes do nothing" bug.

### 5.3 Provider type

Which hardware actually produces the fixes.

```kotlin
.provider(LocationProviderType.FUSED)   // default
```

| Value | Backing | Use when |
|---|---|---|
| `FUSED` | Play Services fused provider | Almost always. Blends GNSS, Wi-Fi, cell and device sensors; best time-to-first-fix, works indoors |
| `GPS_ONLY` | `LocationManager.GPS_PROVIDER` | No Play Services on the device, or a Wi-Fi centroid must never reach the record |
| `NETWORK_ONLY` | `LocationManager.NETWORK_PROVIDER` | Coarse, cheap positioning is enough |
| `PASSIVE` | `LocationManager.PASSIVE_PROVIDER` | You want zero additional battery cost and will take whatever other apps already requested |

The three non-fused values run on the platform `LocationManager` and **need no Play
Services at all**.

Costs, stated rather than discovered:

- **`GPS_ONLY`** — no fix at all indoors, in a car park or in a tunnel. Cold starts of
  30–60 s. Materially more battery than fused at the same interval, because there is no
  cached fix to hand back.
- **`NETWORK_ONLY`** — 20–2000 m accuracy. Needs a matching accuracy ceiling; see below.
- **`PASSIVE`** — cadence, provider and accuracy are all whatever some other app asked for.
  On a device where nothing else is tracking, it delivers nothing. Every cadence tier is
  inert, so `navigationMode` is refused.
- **Platform sources do not batch.** `LocationManager` has no `maxUpdateDelay`, so the
  Doze-window battery win that batching buys is unavailable, and `waitForAccurateLocation`
  does not exist — the first fix after registration arrives as-is.

`desiredAccuracy` is a **different** question: it biases the *fused* provider's choice among
the sources it has, and cannot exclude any of them. `HIGH` → `PRIORITY_HIGH_ACCURACY`,
`BALANCED` → `PRIORITY_BALANCED_POWER_ACCURACY`, `LOW` → `PRIORITY_LOW_POWER`.

### 5.4 The accuracy meter

How good a fix must be before it earns a stored point.

```kotlin
.accuracyProfile(AccuracyProfile.STRICT)     // named point
.maxAccuracyMeters(35f)                      // exact number; implies CUSTOM
.recoveryTrustMeters(18f)                    // optional: post-gap re-anchor bar
```

| Profile | Moving ceiling | Re-anchor bar | Character |
|---|---|---|---|
| `STRICT` | 20 m | 15 m | Sparser track, no zigzag. Urban-canyon safe |
| `BALANCED` | 30 m | 25 m | **Default.** The engine's shipped constants, byte-identical |
| `RELAXED` | 60 m | 40 m | More points, visible wander in poor conditions |
| `CUSTOM` | `maxAccuracyMeters`, 5–500 m | clamped ≤ ceiling | Whatever you set |

**What the ceiling actually is.** A bound on the reported error radius of a fix claimed to
be *moving* — the one unconditional bound in the pipeline. Every other accuracy limit in
the engine is conditional on a motion class that the fix's own displacement helps decide,
which is circular: a 66 m positioning error computes as ~14 m/s, ~14 m/s reads as
vehicular, and vehicular carries the loosest ceiling there is. That circle is how a field
capture stored a 153 m spike and a 173° reversal inside an ordinary city drive (EC-139).

**Two numbers, not one.** The re-anchor bar is separate and stricter because the first fix
after a signal blackout is the least corroborated fix of the session and the most
consequential — every later fix is judged from wherever it lands. A fix above the bar is
*held*, warmed into the filter and stored nowhere, until a second fix lands nearby and
agrees (EC-140).

**Stationary fixes are exempt by design.** They are handled by the anchor and wobble
defences, which treat a poor fix as something to freeze against rather than something to
plot. Tightening this to fix stationary drift is tuning the wrong stage.

**What the meter does not touch.** Classification ceilings (`accuracyHigh`,
`accuracyMedium`, `accuracyStationaryLimit`, `accuracyMaxVehicular`) and the whole
sigma-gate family are left alone: those classify a fix rather than admit it, and dragging
them along would re-tune the motion state machine as a side effect of a storage decision.

**Two combinations are refused by `validate()`:**

- `NETWORK_ONLY` under a ceiling below 50 m — a GNSS-calibrated ceiling rejects every fix a
  Wi-Fi/cell centroid can produce, and the symptom would be an empty track with no error
  anywhere. Pair `NETWORK_ONLY` with `RELAXED` or a `CUSTOM` value ≥ 50 m.
- `PASSIVE` with `navigationMode`.

Under `NETWORK_ONLY` the SDK also lifts its 25 m network-fix rejection to your own ceiling.
That bound exists because on a fused stream a network fix is what a Wi-Fi teleport arrives
as; on `NETWORK_ONLY` it is what *every* fix arrives as.

### 5.5 Tracking mode

```kotlin
.trackingMode(TrackingMode.ADAPTIVE)
```

| Mode | Behaviour | Battery | Stop timing |
|---|---|---|---|
| `CONTINUOUS` | Stream always; the filter does all thinning | Highest | Best |
| `ADAPTIVE` | **Default.** Stream while moving with adaptive cadence, heartbeat-only while stationary | Middle | Good |
| `MOTION_ONLY` | Location fully off while stationary | Lowest | Coarsest |

`ready()` acts on the device's `motionQuality` rather than merely reporting it — running a
motion-gated design on hardware that cannot support motion detection produces gaps users
blame on the SDK (EC-137):

| `motionQuality` | What `ready()` does |
|---|---|
| `POOR` | Forces `CONTINUOUS`, emits `Error(MOTION_DETECTION_DEGRADED)` naming the missing sensors. **Costs battery** — `CONTINUOUS` keeps the stream registered while stationary and `MOTION_ONLY` does not, so a host that chose `MOTION_ONLY` for power gets the opposite |
| `DEGRADED` | Doubles `motion.stopTimeoutMin`, mode untouched. Emits a `Diagnostic` naming the old and new value. Stops are detected later on such hardware, and waiting longer beats declaring a stop that did not happen |

`POOR` is not purely a hardware verdict: a denied `ACTIVITY_RECOGNITION` reaches it on a
device with no significant-motion or step sensor. Check the grant before blaming the phone,
and re-run `ready()` after granting it to get a fresh verdict.

Read the result from `TrackerState`, not from the event:

```kotlin
tracker.state.value.motionQuality        // FULL | DEGRADED | POOR
tracker.state.value.effectiveTrackingMode // what is actually running
```

`MOTION_DETECTION_DEGRADED` fires *inside* `ready()`, and `tracker.events` has
`replay = 0` — a collector created after `ready()` never receives it. The state flow always
has a current value.

### 5.6 Cadence and turn fidelity

```kotlin
.intervalMs(60_000)              // normal tier
.fastestIntervalMs(30_000)       // OS floor; must be <= intervalMs
.maxUpdateDelayMs(60_000)        // OS batching window
.adaptiveCadence(true)
.vehicularIntervalMs(12_000)     // while vehicular
.turnBurst(true)
.turnBurstIntervalMs(4_000)      // while measurably turning
.bearingChangeCaptureDeg(30)     // store on a heading change this large
.cornerAnchorCapture(true)       // restore a rejected fix the next one shows was at a corner
```

**`distanceFilterM` must stay `0`, and `validate()` enforces it.** A non-zero OS distance
filter is a stationary-drift *generator*: the OS only wakes you when noise exceeds the
filter, so every update looks like movement. All thinning is done in software, deliberately
(EC-119).

Turn geometry is handled five ways, all offline and all on by default: adaptive cadence,
the turn-burst tier, bearing-change force-capture, cornering process noise in the filter,
and a centripetal Catmull-Rom spline at plot time.

`turnBurstIntervalMs` must be ≤ the tier it accelerates, or the "faster" tier is slower than
what it replaces and quietly makes turn geometry worse. `validate()` checks it.

### 5.7 Navigation mode

~1 Hz, high accuracy, OS batching off, overriding every adaptive tier.

```kotlin
.navigationMode(true)
.navigationIntervalMs(1_000)
.navigationFastestIntervalMs(500)
.foregroundService(true)          // REQUIRED
```

Requires `service.foregroundService`; `validate()` refuses the combination otherwise. A
1 Hz stream without a foreground service is throttled or killed at the first backgrounding,
which presents as "navigation randomly stops" and is invisible in any log you would think
to read.

While on, `desiredAccuracy` is ignored (forced high) and `maxUpdateDelayMs` is ignored
(batching off) — a single batching window would hold more fixes than the animation they
feed.

### 5.8 Motion and stop detection

```kotlin
.activityRecognition(true)
.activityConfidenceMin(75)
.stopOnStationary(false)          // call stop() automatically on the stop timeout
.stopTimeoutMin(5)
.stationaryRadiusM(150f)
.stationaryGeofenceId("fieldtrack-stationary")
.stationaryGeofenceOnEnterEvent("stationary_fence_enter")
.stationaryGeofenceOnExitEvent("stationary_fence_exit")
.heartbeatIntervalSec(900)
.persistHeartbeat(false)
```

`stationaryGeofenceId` is the unique id for the system fence the SDK arms while the
device is stationary. `stationaryGeofenceOnEnterEvent` and
`stationaryGeofenceOnExitEvent` are the event labels the host sees when that fence is
entered or exited.

`heartbeatIntervalSec` is the **data-plane** heartbeat: it warms the filter and is not
stored. That is what makes a two-hour steady user produce exactly one point. It must be at
least 5× the sampling interval or it fires every fix and defeats stationary suppression
entirely; `validate()` checks it (EC-121).

Distinct from `TrackerEvent.Heartbeat`, which is the control-plane liveness signal.

### 5.9 Sensors

Registered only while a session is active — a pedometer left registered after a session is
battery drain with nothing to show for it (EC-138).

```kotlin
.useSignificantMotion(true)    // permission-free, ~zero-power hardware wake STATIONARY→MOVING
.useStepCorroboration(true)    // step-count veto on stationary drift; confirms indoor walks
.useAccelerometerVeto(true)    // 1 s burst to make the phantom-Doppler correction certain
.useBarometer(false)           // pressure delta distinguishes an elevator from a teleport
.stepBatchLatencyMs(60_000)    // sensor-hub batching, so the AP never wakes for step events
.useGyroTurnPrediction(true)   // arm the turn burst from yaw rate, ahead of GNSS heading
```

Probe what the device actually has:

```kotlin
val sensors: DeviceSensors = trackIt.getSensors()
// accelerometer, gyroscope, magnetometer, significantMotion,
// stepDetector, stepCounter, barometer, rotationVector, motionQuality
```

### 5.10 Service and notification

```kotlin
.foregroundService(true)
.stopOnTerminate(false)        // inverted from the incumbent, on purpose
.startOnBoot(true)             // ditto
.notification("Tracking active", "Recording your location")
.notificationChannel("trackit_tracking", "Location tracking")
.notificationSmallIconResName("ic_stat_track")
.wakeLockMs(20_000)
.backstopIntervalMin(15)
```

`stopOnTerminate = false` and `startOnBoot = true` are both inverted from the incumbent SDK
deliberately: an SDK whose purpose is surviving termination should not ship defaults under
which a swipe-away silently ends tracking, and that failure is invisible until the data is
missing (EC-125).

### 5.11 Persistence and retention

```kotlin
.maxDaysToPersist(7)           // 0 disables TTL pruning
.maxRecords(0)                 // 0 = unbounded
.persistDecisions(true)
.decisionRetentionDays(3)
.decisionMaxRows(50_000)
.persistRawFixes(false)        // debug layer 1
.persistRawPoints(false)       // debug layer 3
```

Pruning runs daily via WorkManager, enqueued by `ready()` and independent of any session.

### 5.12 Full validation list

`ready()` returns `Error(INVALID_CONFIG)` with every problem joined, and `Builder.build()`
throws with the same text. What is checked:

| Rule | Code |
|---|---|
| `intervalMs >= fastestIntervalMs` | EC-120 |
| `distanceFilterM == 0` | EC-119 |
| `heartbeatIntervalSec >= 5 × interval` | EC-121 |
| `turnBurstIntervalMs > 0` and ≤ the tier it accelerates | EC-45 |
| `navigationIntervalMs > 0`, ≥ its fastest, requires `foregroundService` | — |
| `maxDaysToPersist >= 0` | — |
| `CUSTOM` profile requires `maxAccuracyMeters` in 5–500 m | — |
| `maxAccuracyMeters` set against a named profile is rejected, not ignored | — |
| `NETWORK_ONLY` requires a ceiling ≥ 50 m | EC-32 |
| `PASSIVE` cannot use `navigationMode` | — |

### 5.13 Changing config later

There is **no `setConfig()` on `Tracker` yet** — `docs/API.md` §10 documents it as intended
surface, and it is not implemented. Today, config is applied at `ready()` and the
provider/accuracy parts are re-applied at each `start()`. To change config, call `ready()`
again with `reset = true` before the next `start()`.

---

## 6. Reading data

All reads are paged. `PointQuery` defaults to `limit = 500`.

```kotlin
public data class PointQuery(
    val sessionId: String? = null,
    val fromMs: Long? = null,
    val toMs: Long? = null,
    val limit: Int = 500,
    val offset: Int = 0,
)
```

```kotlin
val points: List<TrackPoint> = trackIt.getPoints(PointQuery(sessionId = id))
val count: Int = trackIt.getCount(PointQuery(sessionId = id))
val metres: Double = trackIt.getOdometerMeters()

val sessions: List<TrackSession> = trackIt.getSessions(fromMs, toMs)
val open: TrackSession? = trackIt.currentSession()

// Live, from Room:
trackIt.observePoints(sessionId).collect { points -> render(points) }
```

### `TrackPoint`

```kotlin
public data class TrackPoint(
    val id: Long, val uuid: String, val sessionId: String,
    val timeMs: Long,                   // wall clock — display, day bucketing
    val elapsedRealtimeNanos: Long,     // monotonic — real observation time
    val localDate: String,              // yyyy-MM-dd, in the point's own zone
    val timezone: String,               // IANA id, per point (a session can cross zones)
    val latitude: Double, val longitude: Double,
    val accuracy: Float, val altitude: Double?,
    val speedMps: Float, val bearingDeg: Float,
    val hasSpeed: Boolean, val hasBearing: Boolean,   // read these, never assume
    val provider: String, val isMock: Boolean,
    val movementStatus: MovementStatus,               // STEADY | MOVING
    val detectedActivity: ActivityType?,              // enrichment only
    val activityStartTimeMs: Long,
    val odometerMeters: Double,
    val batteryPct: Int?, val isCharging: Boolean?,
    val extras: String?,
    val acceptReason: String,                         // the Reasons vocabulary
)
```

`hasSpeed` / `hasBearing` matter: `speedMps` is `0f` when the provider reported none, and
`0f` is a legal speed. Defaulting these to `true` silently disables the network-fix
rejection, which is the main defence against Wi-Fi teleports.

### `TrackSession`

```kotlin
public data class TrackSession(
    val id: String,
    val startedAtMs: Long,
    val startedAtElapsedNanos: Long,
    val endedAtMs: Long?,          // null while open
    val tag: String?,
    val configSnapshot: String?,   // the config this session ran under, as JSON
) { val isOpen: Boolean }
```

---

## 7. Plotting

`buildTrack()` is the headline deliverable: a ready-to-draw track, computed entirely
on-device. No backend, no routing key, no quota.

```kotlin
val track: Track = trackIt.buildTrack(
    query = PointQuery(sessionId = id),
    options = TrackOptions(zoom = 14f),
)
```

### What comes back

```kotlin
public data class Track(
    val version: Int,                     // format version, currently 1
    val sessionId: String?,
    val generatedAtMs: Long,
    val from: Long, val to: Long,
    val timezone: String,
    val precision: Int,                   // encoded-polyline precision — READ IT, don't assume 5
    val bounds: Bounds?,                  // null, never NaN-filled, when there are no points
    val stats: TrackStats,
    val encodedPolyline: String,
    val points: List<TrackJsonPoint>,     // `i` is the index every other array references
    val segments: List<TrackSegment>,     // travel/stop spans with speed bands
    val stops: List<StopNode>,            // consolidated dwells
    val arrows: List<ArrowAnchor>,        // precomputed direction anchors with bearings
    val warnings: List<String>,
)
```

`precision` defaults to 6, not the more common 5. A consumer that hardcodes 5 against a
6-precision track puts the user in the wrong hemisphere (EC-110).

`warnings` is an open string set — `snap_unavailable`, `coarse_accuracy`,
`mock_locations_present`, `truncated`, `session_interrupted`. **Nothing is ever silently
dropped**; anything omitted is named here.

`StopNode.isOngoing` means the session is still open and dwell was computed against the
wall clock at build time — pulse that marker rather than showing a fixed duration (EC-111).

### `TrackOptions`

```kotlin
TrackOptions(
    zoom = 14f,                       // selects the arrow spacing tier
    consolidateStops = true,
    stopRadiusM = 60.0,
    stopMinDwellSec = 600,
    smoothing = Smoothing.SPLINE,     // NONE | BEZIER | SPLINE | HEADING_SPLINE
    splineSpacingM = 5.0,
    simplifyEpsilonM = 2.0,           // Douglas-Peucker before smoothing; 0 disables
    snapToRoad = true,                // no-op unless a RoadSnapProvider is installed
    snapMaxOffRoadM = 80.0,
    polylinePrecision = 6,
    speedBandsKmph = listOf(10f, 20f),
    arrowMinSegmentM = 60.0,
)
```

`Smoothing.SPLINE` (default) is centripetal Catmull-Rom through every vertex, resampled.
`BEZIER` only rounds vertices sharper than `bezierMinAngleDeg` and cannot do anything about
a 120 m leg drawn as a chord, which is the usual complaint. `NONE` gives you exactly the
stored vertices.

`HEADING_SPLINE` is `SPLINE` with one change, and it is the one that matters at corners.
Catmull-Rom derives the tangent at a vertex from the vertices either side of it; through a
turn those sit on opposite legs, so the derived tangent is the chord across the corner and
the curve leaves the vertex pointing somewhere the vehicle never pointed — it cuts the
inside of the turn. Every stored point already carries the chipset's Doppler heading, which
is the true tangent, so `HEADING_SPLINE` uses that instead. The corner then appears
*between* two fixes, from two headings, with neither fix having sampled its apex.

It degrades rather than failing: a vertex with no recorded heading — no bearing from the
chipset, or a speed below walking pace, where a reported heading is multipath — takes the
Catmull-Rom tangent, so a track from a chipset that reports no heading draws the shape
`SPLINE` draws. Road-snapped geometry passes through untouched under both.

```kotlin
TrackOptions(smoothing = Smoothing.HEADING_SPLINE)
```

### Export formats

```kotlin
val polylineJson: String = trackIt.exportPolylineJson(query, options)  // POLYLINE-JSON.md §1
val geoJson: String = trackIt.exportGeoJson(query, options)            // RFC 7946, [lng, lat]
```

### Drawing it with `fieldtrack-maps`

```kotlin
val renderer = TrackRenderer(googleMap, TrackRenderer.RendererOptions(
    basePathWidth = 16f,
    showArrows = true,
    showStopMarkers = true,
))

renderer.render(track, fitCamera = true)   // pass false once the user has panned

if (renderer.needsArrowRefresh()) renderer.render(track, fitCamera = false)

renderer.clear()   // when the map goes away
```

`TrackRenderer` consumes the same `Arrows.place()` the JSON export uses, so the drawn track
and the exported track cannot disagree.

Drawing it yourself, from any map library:

```kotlin
val path = PolylineCodec.decode(track.encodedPolyline, track.precision)
track.arrows.forEach { addMarker(it.lat, it.lng, rotation = it.bearing.toFloat()) }
track.stops.forEach { addStopPin(it.lat, it.lng, it.dwellSec) }
```

### Road snapping (optional)

```kotlin
trackIt.setRoadSnapProvider(
    OsrmSnapProvider(
        baseUrl = "https://osrm.example.com",   // no default — point at your own deployment
        profile = "driving",
        minConfidence = 0.6,
    )
)
```

Install it **before** the first `buildTrack()`: a provider set afterwards leaves that track
unsnapped and every later one snapped, which looks like a bug in the SDK rather than a race
in your app.

Every failure degrades to raw geometry plus a `snap_unavailable` warning and an
`Error(SNAP_UNAVAILABLE)` event. Losing a day's track because a routing service was
rate-limited is not a trade any host would choose (EC-100). Snapping never touches stored
points — the route is a claim about where the user intended to go, and writing it into the
record would fabricate evidence.

---

## 8. Live tracking

`buildTrack()` is a product, built on demand. `liveTrack()` is a **feed**: one frame per
processed fix, cheap enough to emit continuously.

```kotlin
trackIt.liveTrack().collect { update ->
    if (update.sequence <= lastSequence) return@collect   // flows across dispatchers can reorder
    lastSequence = update.sequence

    renderer.render(update)
}
```

```kotlin
public data class LiveTrackUpdate(
    val sessionId: String,
    val sequence: Long,               // monotonic per session run — check before drawing
    val precision: Int,
    val frozenTailPolyline: String,   // settled spans; append-only, NEVER re-smooth it
    val liveHead: List<GeoPoint>,     // the unsettled last span, redrawn wholesale
    val puck: PuckState?,             // the filter's own estimate; null until seeded
)

public data class PuckState(
    val latitude: Double, val longitude: Double,
    val speedMps: Float,
    val headingDeg: Double?,          // null when velocity is too small to have a direction
    val accuracyM: Float,             // 1σ uncertainty — the honest radius for a halo
)
```

The feed is **conflated**: collectors always see the latest frame and can never slow capture
down. `liveHead`'s first vertex is the tail's last, so the two polylines join by
construction.

When `headingDeg` is `null`, hold your last rotation — never snap to a fabricated 0°.

With `fieldtrack-maps`:

```kotlin
val live = LiveTrackRenderer(googleMap)
trackIt.liveTrack().collect(live::render)
live.clear()
```

### Route snapping for the puck

The cheapest trick in navigation rendering, and most of why a well-known blue dot never
wobbles off the road during turn-by-turn: the puck is not matched against the road network,
it is projected onto the one polyline your app is already following. Entirely offline — no
provider, no key, no quota.

```kotlin
trackIt.setActiveRoute(routePoints)      // empty list clears it
if (trackIt.isOffRoute()) offerReroute()
```

Only the live puck moves. Stored points and `buildTrack()` are untouched.

---

## 9. Events & state

### State

```kotlin
trackIt.state.collect { state ->
    // isReady, isTracking, motionState, providerState, currentSessionId
}
```

### Events

`SharedFlow`, replay 0, unlimited subscribers — never a `var callback`, which silently lets
the second registrant replace the first (EC-112). Collect from a lifecycle scope for UI, or
an application scope for work that must continue with no UI on screen.

```kotlin
trackIt.events.collect { event ->
    when (event) {
        is TrackerEvent.Location -> onPoint(event.point)
        is TrackerEvent.LocationRejected -> log(event.decision.reason)
        is TrackerEvent.MotionChange -> onMotion(event.state, event.point)
        is TrackerEvent.ActivityChange -> onActivity(event.activity, event.confidence)
        is TrackerEvent.EnabledChange -> onTrackingToggled(event.enabled)
        is TrackerEvent.ProviderChange -> onProviderState(event.state)
        is TrackerEvent.PowerSaveChange -> onPowerSave(event.enabled)
        is TrackerEvent.Heartbeat -> onAlive(event.atMs)
        is TrackerEvent.SessionInterrupted -> offerResume(event.session)
        is TrackerEvent.Diagnostic -> log(event.message)
        is TrackerEvent.Error -> onError(event.code, event.message)
    }
}
```

`SessionInterrupted` fires from `ready()` when a session was found still open at launch —
a crash or force-stop. The SDK does not decide what to do with it; you do (EC-66).

### Battery

```kotlin
val battery = trackIt.batteryInfo()   // now; no session, no permission, no ready() needed
battery.percent       // 0..100, or null when the platform will not say
battery.isCharging    // true / false / null
battery.powerSource   // NONE, AC, USB, WIRELESS, DOCK, UNKNOWN
battery.isLow         // percent != null && percent <= 15

trackIt.batteryState().collect { battery -> render(battery) }
```

`TrackerEvent.BatteryChange` carries the same transitions on the event flow. Events fire on
plug, unplug, low and okay — plus whatever drift the capture path notices while a session
runs — never on a timer, and never when the reading has not changed.

A null percentage means "the platform would not say", never 0 %. The same reading is stamped
on every stored and uploaded point, so a display and its rows cannot disagree.

### Provider state

```kotlin
trackIt.providerState().collect { state ->
    // gpsEnabled, networkEnabled, permission, accuracyAuthorization,
    // fusedAvailable, powerSaveMode
}
```

Updated by broadcast and by `AppOpsManager`, never polled. `fusedAvailable` answers "is Play
Services here" — a fact about the device, useful for deciding whether to switch to
`GPS_ONLY`.

### Error codes

| Code | Meaning | Fatal to `start()` |
|---|---|---|
| `NOT_READY` | `start()` before `ready()` | Yes |
| `PERMISSION_DENIED` | No location permission at all | Yes |
| `BACKGROUND_PERMISSION_MISSING` | Foreground-only grant, or background revoked mid-session | No — degrades |
| `COARSE_ONLY` | Approximate-only under `CONTINUOUS`/`ADAPTIVE` | Yes |
| `LOCATION_DISABLED` | User turned location services off | — |
| `PLAY_SERVICES_UNAVAILABLE` | Selected provider not present. The message names the remedy | Yes |
| `FGS_START_REFUSED` | OS refused the foreground service | — |
| `NOTIFICATION_HIDDEN` | The FGS notification is not visible | — |
| `FIX_TIMEOUT` | One-shot produced nothing | — |
| `STORAGE_FULL` / `STORAGE_RESET` | Database problems | — |
| `TRACKER_DEAD` | Watchdog saw no fixes for too long | — |
| `INVALID_CONFIG` | `validate()` failed; message lists every problem | Yes |
| `MOTION_DETECTION_DEGRADED` | `motionQuality = POOR`; mode forced to `CONTINUOUS` | No |
| `SNAP_UNAVAILABLE` | Road snapping failed. **Never fatal** — raw geometry is returned | No |
| `NO_ACTIVITY` | — | — |
| `INTERNAL` | Something threw where the contract says nothing throws. This is a bug in the SDK | — |

---

## 10. Diagnostics

Three layers, for three different questions.

| Layer | Call | Answers | Config |
|---|---|---|---|
| 1 — raw fixes | `getRawFixes(sessionId)` | "What did the OS actually deliver?" | `persistRawFixes = true` |
| 2 — decisions | `getDecisions(sessionId, limit, offset)` | "Why was this fix rejected?" | `persistDecisions = true` (default) |
| 3 — raw points | `getRawPoints(sessionId)` | "Why is there no point *here*?" | `persistRawPoints = true` |

```kotlin
val decisions: List<FixDecision> = trackIt.getDecisions(sessionId, limit = 200)

decisions.filterNot { it.isAccept }.forEach {
    Log.d("traker", "${it.reason}  σ=${it.sigma} thr=${it.threshold} " +
        "moved=${it.distanceMovedM}m at ${it.effectiveSpeedMps}m/s (${it.motionState})")
}
```

`sigma` and `threshold` are on the record so a `Sigma Gate Outlier` can be argued with: they
show exactly how wide the gate was and by how much the fix missed.

Layer 3 comes back in the same shape as `getPoints()`, so a rejected candidate and the
points either side of it can be read side by side. `RawPoint.uuid` joins back to the stored
`TrackPoint` for the ones that were accepted. It is one wide row per fix — real write
amplification at a 12 s cadence — which is why it is off by default.

### Reason vocabulary

These strings **are API** — every fixture assertion keys on them, and changing one is a
breaking change.

| Group | Reasons |
|---|---|
| Lifecycle | `Init`, `Resume`, `Session Closed`, `Reboot Boundary`, `Out Of Order` |
| Timing | `Burst`, `Stale Fix`, `15-Min Heartbeat`, `HeartBeat Skipped` |
| Accept | `Vehicular`, `Moving/Walking`, `Bearing Change`, `Corner Anchor`, `Arrival`, `Indoor Arrival`, `Walk Arrival`, `Blackout Arrival`, `Stationary Recovery` |
| Reject | `Poor Accuracy`, `Impossible Speed`, `Sigma Gate Outlier`, `Sigma Junk Fail`, `Mock Location`, `Invalid Coordinates`, `Heuristic Gate` |
| Recovery | `Recovery Confirmed`, `Recovery Reset`, `Recovery Held`, `Sigma Forced Reset` |
| Stationary | `Origin Set`, `Departure Held`, `Drift Suppressed` |
| Network fix | `NLP Fallback` |

### Injecting a fix

```kotlin
trackIt.offerFix(trackFix)
```

Judged by exactly the same gates as a live fix — you cannot inject an unvalidated point
(EC-86). Useful for replay and for a custom provider.

---

## 11. Optional modules

### `fieldtrack-sync` — HTTP upload

`fieldtrack-core` never opens a socket. This artifact does; an app that does not depend on it
gets an offline-first SDK with no network code linked at all.

**Store-then-sync, never sync-then-store.** A point is durable in Room before anything is
attempted, so a failed upload costs nothing and a dead network costs nothing. Rows are marked
synced only on a confirmed 2xx — the default state is "still queued", which is the safe
direction.

#### 11.1 Setting it up

`SyncConfig.builder()` is the way in, and the place to set a **base URL** once rather than
carrying a second full URL that drifts when the environment changes:

```kotlin
val sync = TrackerSync.getInstance(context)

sync.configure(
    SyncConfig.builder()
        .baseUrl(BuildConfig.API_BASE_URL)          // "https://api.example.com"
        .path("v1/location/batch")                  // joined with exactly one "/"
        .header("Authorization", "Bearer $token")
        .batchSize(100)
        .build(),
)
```

If your app already sets a base URL for its own API, put it on `TrackerConfig` instead and
give the sync module only a path:

```kotlin
trackIt.ready(
    TrackerConfig.builder()
        .baseUrl(BuildConfig.API_BASE_URL)          // core stores it; core never reads it
        .build(),
)

sync.configure(SyncConfig.builder().path("v1/location/batch").build())
```

**Resolution is a fallback, never an override**, and it runs at `configure()`:

| `SyncConfig` carries | `TrackerConfig.baseUrl` | Endpoint |
|---|---|---|
| an absolute `url` | anything | the absolute `url` — what you wrote closest to the upload wins |
| `baseUrl` + `path` | anything | the sync-level pair |
| `path` only | set | base + path, joined with one `/` |
| `path` only | unset | **`configure()` throws**, naming both places a base can come from |

A path-only `SyncConfig` is the one config that `build()` accepts while still invalid — the
builder cannot see the core config, so it defers that single check to `configure()`. Order
does not matter beyond this: `ready()` must have run before `configure()`, since that is what
loads the base URL.

The data-class constructor is unchanged and still idiomatic from Kotlin:

```kotlin
sync.configure(
    SyncConfig(
        url = "https://api.example.com/v1/location/batch",
        method = "POST",
        headers = mapOf("Authorization" to "Bearer $token"),
        autoSync = true,
        batchSize = 100,
        requiresUnmeteredNetwork = false,
        gzipRequestBody = false,                    // opt-in; most servers reject it
        allowCleartext = false,                     // local dev servers only
        timeouts = SyncTimeouts(readMs = 30_000),   // no OkHttpClient needed
    ),
    // Omit to use the built-in Retrofit-over-OkHttp default. Supply your own to reuse
    // an authenticated client — then neither is linked by this module.
    transport = null,
)
```

| Field | Default | Meaning |
|---|---|---|
| `url` | — | The full endpoint. From the builder, `baseUrl` + `path`, or a `path` resolved against `TrackerConfig.baseUrl`. |
| `method` | `POST` | Any method that carries a body. |
| `headers` | empty | Sent on every request. `Content-Type` is set for you unless you set it. |
| `autoSync` | `true` | The SDK drives its own uploads — see [11.3](#113-who-triggers-an-upload). |
| `batchSize` | `100` | Rows per request. Bigger means fewer requests but a bigger retry unit. |
| `requiresUnmeteredNetwork` | `false` | Wi-Fi only, for the background worker. |
| `gzipRequestBody` | `false` | See [11.4](#114-the-request). |
| `allowCleartext` | `false` | Permit `http://`. Loopback is already exempt. |
| `timeouts` | 5 s / 30 s / 20 s | connect / read / write, without building an `OkHttpClient`. |

**`configure()` throws `IllegalArgumentException`** on an invalid config — a non-`https` URL,
a blank method, a `batchSize` outside 1..1000. Cleartext is blocked at runtime by Android's
own network security policy from API 28, so accepting an `http://` URL here would mean a
generic network error retried forever with nothing naming the cause. `localhost`,
`127.0.0.1`, `::1` and `10.0.2.2` are exempt without a flag; anything else needs
`allowCleartext = true` deliberately. Call `config.validate()` yourself first if the URL comes
from untrusted input — it returns every problem as a list instead of throwing.

#### 11.2 The API surface

| Member | Returns | What it does |
|---|---|---|
| `TrackerSync.getInstance(context)` | `TrackerSync` | Process-wide instance, sharing Tracker's database. Idempotent. Does not configure anything. |
| `configure(config, transport = null)` | `Unit` | Sets the endpoint and optional custom transport. Throws on an invalid config. Clears a previous 403 halt. |
| `syncNow()` | `SyncQueue.Result` | Drains inline and tells you what happened. `suspend`. |
| `requestSync()` | `Unit` | Enqueues network-constrained WorkManager work. Safe to call often. No-op before `configure()` and after a 403. |
| `pendingCount()` | `Int` | Rows still queued. `suspend`. Cheap enough for a badge. |
| `endpoint` | `String?` | Where uploads go, or `null` if unconfigured — including after a 401 tore the config down. |
| `isConfigured` | `Boolean` | Derived from `endpoint`, so the two cannot disagree. **Do not cache it.** |
| `events` | `SharedFlow<SyncEvent>` | One `HttpResponse(statusCode, count)` per exchange, background ones included. |

Headers are deliberately **not** exposed: they carry your credential, and a property that
hands a bearer token back is a property that ends up in a log.

##### `syncNow()`

Drains inline, in **your** coroutine scope — an upload started from a `viewModelScope` is
cancelled with it. Prefer `requestSync()` for anything not user-initiated.

```kotlin
when (val result = sync.syncNow()) {
    is SyncQueue.Result.Uploaded -> toast("Uploaded ${result.count}")
    SyncQueue.Result.Empty -> toast("Nothing to upload")
    is SyncQueue.Result.Retry -> toast("Will retry: ${result.reason}")   // result.retryAfterMs
    SyncQueue.Result.AuthExpired -> forceLogout()
    SyncQueue.Result.Forbidden -> promptForNewCredential()
}
```

One drain runs at a time. A second concurrent call returns `Retry("already draining")` rather
than uploading the same rows twice. A single drain is bounded at 20 batches, so a huge backlog
is spread across calls instead of holding the lock.

##### `requestSync()`

Hands the work to WorkManager: persisted, survives process death and reboot, gated on network
availability. Repeated calls coalesce — a burst of accepted points cannot reset the backoff
and hammer a struggling server.

##### `events`

```kotlin
lifecycleScope.launch {
    sync.events.collect { event ->
        when (event) {
            is SyncEvent.HttpResponse -> when (event.statusCode) {
                null -> show("No connection — ${event.count} points still queued")
                else -> show("HTTP ${event.statusCode} for ${event.count} points")
            }
        }
    }
}
```

`statusCode` is `null` when **no HTTP exchange completed at all** — dead network, DNS failure,
timeout. A device problem and a server problem should not be reported the same way. `count` is
what was *attempted*; on a failure those rows are still queued. The response body is not
carried: it can be megabytes, and a host that needs it implements `SyncTransport`.

The flow replays the last event, so a screen opened after a background upload shows what
happened rather than a blank panel.

#### 11.3 Who triggers an upload

With `autoSync = true` (the default) the SDK drives itself. Three triggers, covering different
failures:

| Trigger | Cadence | Covers |
|---|---|---|
| A point was stored | throttled to one request a minute | Ordinary operation |
| Health loop | every 2 min while the service runs | Rows queued, or last confirmed upload ≥ 16 min old |
| Backstop worker | every 15 min | The same check with a **dead service** — a backlog left by a drain that failed while the process was gone |

The supervision triggers ask the queue first: nothing pending, no worker woken. Set
`autoSync = false` to own the schedule entirely with `requestSync()` / `syncNow()`.

**A parked user uploads nothing, because the filter stores nothing.** That is by design, not a
dead uploader.

#### 11.4 The request

`POST` (or your `method`), `Content-Type: application/json; charset=utf-8`, plus your headers
verbatim. One request per batch — up to `batchSize` points in a single array, never one
request per point.

```json
{
  "location": [
    {
      "uuid": "0f5c8f0e-1c2a-4f0b-9a3c-7d1e2b3a4c5d",
      "time": 1700000000000,
      "local_date": "2026-08-17",
      "latitude": 23.0225,
      "longitude": 72.5714,
      "accuracy": 8.0,
      "movementSpeed": 4.5,
      "provider": {
        "network": true,
        "gps": true,
        "enabled": true,
        "status": 3,
        "accuracyAuthorization": 0,
        "airplane": false
      },
      "hasSpeed": true,
      "hasBearing": true,
      "time_zone": "Asia/Kolkata",
      "activity_status": "fused@moving",
      "detected_activity_type": "WALKING",
      "detected_activity_start_time": 1699999000000,
      "battery_percentage": "82",
      "is_charging": true,
      "is_mock": false
    }
  ]
}
```

| Field | Type | Notes |
|---|---|---|
| `uuid` | string | Stable identity. **Dedupe on this** — a retry re-sends the whole batch. |
| `time` | number | Epoch **milliseconds**, wall clock. |
| `local_date` | string | `yyyy-MM-dd` in the point's own zone, for day bucketing. |
| `latitude` / `longitude` | number | WGS-84 degrees. The fix's own coordinates, not a filtered estimate. |
| `accuracy` | number | Reported error radius, metres. |
| `movementSpeed` | number | Metres per second. `0.0` when the provider reported none — check `hasSpeed` before trusting it. |
| `provider` | object? | The location subsystem **as it was when this point was captured** — see the table below. Absent on points stored before the SDK recorded it. |
| `hasSpeed` / `hasBearing` | boolean | Whether the provider actually supplied them. `0.0` is a legal speed. |
| `time_zone` | string | IANA id, **per point** — a session can cross zones on a flight. |
| `activity_status` | string | `"<provider>@<movementStatus>"`, lowercase — e.g. `fused@moving`, `gps@steady`. **This is where the provider name lives.** |
| `detected_activity_type` | string? | `WALKING`, `IN_VEHICLE`, `ON_BICYCLE`, `RUNNING`, `STILL`, `ON_FOOT`, `TILTING`, `UNKNOWN`. Enrichment only. |
| `detected_activity_start_time` | number | Epoch ms, `0` when unknown. |
| `battery_percentage` | string? | 0..100 **as a string**, e.g. `"82"`. |
| `is_charging` | boolean? | Plugged in or full. Absent when the platform will not say — deliberately not `false`, which would read as "confirmed on battery". |
| `is_mock` | boolean | True when the fix was flagged as mock. See [§11.7](#117-mock-locations-in-development) for when mock fixes are stored at all. |

#### The `provider` object

| Field | Type | Notes |
|---|---|---|
| `network` | boolean | The network (Wi-Fi/cell) provider is enabled. |
| `gps` | boolean | The GPS provider is enabled. |
| `enabled` | boolean | The location **master switch**. Not the union of the two above — a device can report location enabled with GPS switched off. |
| `status` | number | Permission tier: `0` not determined, `1` restricted, `2` denied, `3` always (foreground + background), `4` while in use. Android cannot tell "never asked" from "refused", so it never sends `0`. |
| `accuracyAuthorization` | number | `0` full (fine location), `1` reduced (coarse only). |
| `airplane` | boolean | Airplane mode was on. Not a gate: GPS keeps working in airplane mode on most devices while network positioning does not. |

It is recorded **per point**, not sampled when the queue drains: a batch of 100 rows can
span an hour, and a permission downgrade inside that hour is exactly the event that
explains a gap.

> **Upgrading from a release before this?** `provider` used to be the provider *name* as a
> string (`"fused"`). The name has not been lost — `activity_status` is
> `"<provider>@<movementStatus>"` and always was, so read it from there.

**Nullable fields are omitted, not sent as `null`.** If `detected_activity_type`,
`battery_percentage`, `is_charging` or `provider` is unknown, the key is absent from the
object. A backend that distinguishes absent from null needs to know this; it is pinned by a
test (`SyncPayloadWireTest`) so a change cannot reach a server silently.

**Adding your own top-level fields.** Most backends want the batch inside an envelope
carrying identity rather than on its own. `SyncConfig.extraParams` is merged into the top
level of the body, before `location`:

```kotlin
SyncConfig.builder()
    .baseUrl(BuildConfig.API_BASE_URL)
    .path("v1/location/batch")
    .extraParam("user_id", userId)
    .extraParam("company_id", 7)
    .build()
```

```json
{ "user_id": "u-42", "company_id": 7, "location": [ … ] }
```

Values may be a `String`, `Boolean`, any boxed number, or a `Map`/`List`/array of those.
Types are preserved — a number stays a number. `null` is not a value; omit the key. The key
`location` is reserved, and `configure()` rejects an unusable value by name rather than
failing on the first upload. With none set, the body is byte-identical to earlier releases.

They are static config, like `headers` — a rotating token belongs in a re-`configure()` call
or in your own `SyncTransport`.

`gzipRequestBody = true` adds `Content-Encoding: gzip` and compresses bodies over 1 KB. It is
**off by default and should stay off unless your server expects it** — there is no negotiation
mechanism for request-body encoding, so a server that does not expect it answers 400 or stores
the compressed bytes as the payload.

If your backend's shape differs, remap it in your own `SyncTransport` ([11.6](#116-a-custom-transport))
rather than asking for the payload to be configurable.

#### 11.5 The response

**The body is ignored on success.** Return whatever you like; only the status code decides.

| Status | SDK behaviour | Rows |
|---|---|---|
| **2xx** | Batch accepted. Marked synced, next batch drains immediately. | Removed from the queue |
| **401** | **Terminal.** Tracking stops, the queue is cleared, the config is forgotten. `syncNow()` returns `AuthExpired`. | **Deleted** |
| **403** | **Terminal for retrying only.** The loop stops and the config is forgotten; tracking continues. Returns `Forbidden`. | **Kept** |
| **429 / 5xx / anything else** | Retried with backoff. Returns `Retry`. | Kept |
| No response at all | Retried. `Retry`, and the event carries `statusCode = null`. | Kept |

**Why 401 and 403 differ.** A 401 means the credential this data was recorded under is gone
and the next login may be a different user — keeping the queue would leak one user's positions
into another's session. A 403 means *this* credential may not write *this* resource: a scope,
a rotated key, a server-side permission bug. Same user, same valid data, so it stays on disk.
Re-`configure()` with a working credential to resume.

**404 is not terminal** — as often a mid-deploy blip as a typo, and retrying it is cheap.

**`Retry-After` is honoured** on any failure response, in both RFC 9110 forms —
delta-seconds (`Retry-After: 120`) or an HTTP-date (`Retry-After: Wed, 21 Oct 2026 07:28:00
GMT`). Clamped to 1 s–6 h so one bad header cannot park the queue. The background worker
re-enqueues at your time instead of its own 30-second backoff; a `Retry-After` seen by a
host's own `syncNow()` is reported on `Result.Retry.retryAfterMs`, not acted on, because that
call is inline and the host owns the schedule.

On a non-2xx the SDK keeps up to **4 KB** of your response body on
`SyncResponse.Failure.body`, so `500` can be told apart from `500 {"error":"bad geometry"}`.
It is never logged — an error body can echo a request header.

#### 11.6 A custom transport

For a non-HTTP backend, gRPC, certificate pinning, or an existing authenticated client:

```kotlin
class AppTransport(private val api: MyApi) : SyncTransport {
    override suspend fun upload(request: SyncRequest): SyncResponse = try {
        val response = api.upload(request.url, request.headers, request.jsonBody)
        when (response.code) {
            401 -> SyncResponse.Unauthorized
            403 -> SyncResponse.Forbidden
            in 200..299 -> SyncResponse.Success(response.code)
            else -> SyncResponse.Failure(
                code = response.code,
                message = response.message,
                body = response.errorBody?.take(SyncResponse.Failure.MAX_BODY_CHARS),
                retryAfterMs = response.retryAfterSeconds?.times(1_000),
            )
        }
    } catch (error: IOException) {
        SyncResponse.Failure(null, error.message ?: "network failure")
    }
}
```

**Implementations must not throw.** The queue needs one of the outcomes above, and a thrown
exception cannot distinguish a dead credential from a dropped tunnel. `SyncRequest` also
carries `gzip` and `timeouts` so a custom transport can honour the host's config — ignoring
them is correct behaviour, not a bug.

#### 11.7 Mock locations in development

Two independent settings decide whether a mock fix is stored and uploaded at all:

| Setting | Default | Effect |
|---|---|---|
| `geolocation.mockLocationPolicy` | `MockPolicy.FLAG` | `FLAG` stores mock fixes and marks them `is_mock: true`. `REJECT` drops them in `FixMapper` before anything else runs. |
| `security.mockLocation` | `IntegrityPolicy.BLOCK` | The integrity layer's verdict on a device with mock locations. |

They are resolved against each other at `ready()`: `security.mockLocation = BLOCK` forces
`mockLocationPolicy = REJECT`, because an SDK that refuses to run on a mocked device cannot
also be storing mocked points. The stricter of the two wins, silently.

**A debuggable host app is exempt from that.** Both defaults are strict, so before this
exemption a developer driving a fake route through the emulator got a total, silent data
loss: every fix dropped, nothing in the database, no event saying why. A debuggable build
already waives the whole integrity layer (`IntegrityEnvironment.isWaived`), and this is the
same waiver applied to the one place that was still enforcing mock policy behind its back.

So, out of the box:

- **Debug build** — mock fixes are stored and uploaded with `is_mock: true`.
- **Release build** — mock fixes are dropped. Set `mockLocationIntegrityPolicy(WARN)` *and*
  `mockLocationPolicy(MockPolicy.FLAG)` if you deliberately want them stored in release.

`is_mock` is Android-only; the flag comes from `Location.isMock`, which is the platform's own
answer and cannot be argued with.

### `fieldtrack-snap` — OSRM map-matching

See [§7](#7-plotting). Depends on `fieldtrack-geo` only; Retrofit and OkHttp are `compileOnly`.

### `fieldtrack-maps` — Google Maps rendering

`TrackRenderer`, `LiveTrackRenderer`, `ArrowIcons`. Not a view and not thread-safe:
construct where the map lives, call `render`, call `clear` when it goes away.

This module has **no tests**. It is thin by design, but "thin" is not "verified".

---

## 12. Java

`fieldtrack-bridge` gives you the SDK without `suspend` and without `Flow`.

```java
TrackerClient client = TrackerClient.getInstance(context);

client.ready(new TrackerConfig(), new ResultCallback<TrackerState>() {
    @Override public void onSuccess(TrackerState state) { /* … */ }
    @Override public void onError(ErrorCode code, String message) { /* … */ }
});

client.start("commute", callback);
client.stop(callback);

client.buildTrack(new PointQuery(sessionId, null, null, 500, 0), trackCallback);
client.getPoints(pointsCallback);
client.getOdometerMeters(odometerCallback);

Cancellable sub = client.addEventListener(event -> handle(event));
sub.cancel();
```

Build config fluently — this is what the builder exists for:

```java
TrackerConfig config = TrackerConfig.builder()
    .provider(LocationProviderType.GPS_ONLY)
    .accuracyProfile(AccuracyProfile.STRICT)
    .trackingMode(TrackingMode.ADAPTIVE)
    .notification("Tracking", "Recording your route")
    .build();
```

Synchronous getters where nothing can fail: `getState()`, `getProviderState()`,
`getSensors()`, `permissions()`, `isOffRoute()`.

`TrackerJson` on the same module is the JSON facade for anything that speaks JSON rather
than Kotlin types.

---

## 13. React Native

npm package `@fieldtrack360/react-native-fieldtrack`, version-locked to the Maven artifacts.

```ts
import * as Tracker from '@devstree/react-native-traker';

await Tracker.ready({ geolocation: { providerType: 'GPS_ONLY' } });
const session = await Tracker.start('commute');

const points = await Tracker.getPoints({ sessionId: session.id });
const track = await Tracker.buildTrack({ sessionId: session.id }, { zoom: 14 });

const sub = Tracker.addEventListener(event => console.log(event));
const liveSub = Tracker.addLiveTrackListener(frame => draw(frame));
Tracker.setLiveTrackThrottleMs(200);

sub.remove();
await Tracker.stop();
```

Errors reject with a `TrackerError` carrying the SDK's own `code` — branch on that;
`message` is for humans and is not stable.

**Android only.** The package installs on iOS and every call rejects with a typed
`UNSUPPORTED_PLATFORM`. That is deliberate, not an omission: an iOS version would be a
second implementation of a seven-stage acceptance pipeline, not a port. Check
`Tracker.isSupported` before wiring UI.

---

## 14. Troubleshooting

### "I only get one point every few minutes"

Working as designed. The pipeline stores points that carry information, not points on a
timer. A stationary user produces one point plus a heartbeat every 15 minutes. Confirm with
the decision log: you should see `Drift Suppressed`, `HeartBeat Skipped` and
`Departure Held`.

### "My config changes do nothing"

`reset = false`. The persisted config is winning. Set `reset = true`.

### "The track zigzags around a wrong street after a signal gap"

Tighten the accuracy meter. `AccuracyProfile.STRICT` sets a 20 m moving ceiling and a 15 m
re-anchor bar; the default 30/25 admits fixes that can drag the anchor after a blackout.
Confirm in the decision log — look for `Recovery Reset` where you expected
`Recovery Confirmed` or `Recovery Held`.

### "Tracking stops when I swipe the app away"

Check `service.stopOnTerminate` is `false` (the default) and `foregroundService` is `true`.
On aggressive OEMs (Xiaomi, Oppo, Vivo, Huawei) the user must also exempt your app from
battery optimisation — the SDK deliberately does not request that permission for you.

### "Navigation randomly stops"

`navigationMode` without `foregroundService`. `validate()` refuses that combination now; if
you are seeing it, the config was constructed without going through `ready()`.

### "`start()` returns `PLAY_SERVICES_UNAVAILABLE`"

The device has no Play Services. Switch to `LocationProviderType.GPS_ONLY`, which runs on
the platform `LocationManager`. The error message says this too.

### "`start()` returns `COARSE_ONLY`"

The user granted approximate location. Either request precise location, or drop to
`TrackingMode.MOTION_ONLY`, which is the only mode a 1–3 km error circle can support.

### "No points on a device with no gyroscope"

Check `getSensors().motionQuality`. On `POOR`, `ready()` forces `CONTINUOUS` and emits
`MOTION_DETECTION_DEGRADED` — that is the SDK acting on the hardware rather than merely
reporting it.

### "The polyline is in the wrong hemisphere"

You hardcoded precision 5. Read `track.precision`; it defaults to 6.

### "`buildTrack()` returns a `snap_unavailable` warning"

Your `RoadSnapProvider` could not answer. The track is built from raw geometry and is still
correct — snapping is an enhancement, never a dependency.

---

## 15. Device integrity

Beside the license gate there is a second security layer, and it runs **only in release
builds** — a debuggable host app waives it completely, the same way the license check is
waived there.

It watches for five things: an enabled non-system accessibility service, developer options
or USB debugging, a hooking framework such as Frida, a system clock that disagrees with
GNSS time (or a time zone that does not match the serving network's country), and
mock-location apps.

Each group carries a policy — `ALLOW`, `WARN` or `BLOCK`:

```kotlin
val config = TrackerConfig.builder()
    .hookingPolicy(IntegrityPolicy.BLOCK)                // default
    .mockLocationIntegrityPolicy(IntegrityPolicy.BLOCK)  // default
    .accessibilityPolicy(IntegrityPolicy.WARN)           // default
    .developerModePolicy(IntegrityPolicy.WARN)           // default
    .clockPolicy(IntegrityPolicy.WARN)                   // default
    .build()

when (val result = traker.ready(config)) {
    is TrackerResult.Error ->
        if (result.code == ErrorCode.DEVICE_INTEGRITY_BLOCKED) blocked(traker.integrity())
    is TrackerResult.Ok -> Unit
}
```

`WARN` is not "ignore": the finding reaches you through `Tracker.integrityState()` and
`TrackerEvent.IntegrityChange`, is stamped on every stored point as `integrityFlags`, and is
uploaded with it. Only `BLOCK` refuses to start — and ends a live session, checked again
every fifteen minutes while tracking.

Accessibility defaults to `WARN` deliberately: accessibility services are also how blind
and motor-impaired people use a phone, and blocking on them would lock those users out.

The SDK also ships lint rules inside its AARs, so a release build that disables the layer
(or hardcodes `android:debuggable="true"`) fails **your** `assembleRelease`. Put any
override in `src/debug/`, where the runtime waiver already applies.

Full detail, including the frozen wire-format bit assignments and the known limits, is in
[INTEGRATION-GUIDE.md §19](INTEGRATION-GUIDE.md#19-device-integrity).

---

## 16. Known limitations

Stated rather than discovered:

- **Nothing is published to a remote repository yet.** `publishToMavenLocal` works with no
  configuration.
- **No `setConfig()`, `changePace()`, `getCurrentPosition()`, `insertPoint()`,
  `deletePoints()`, `requestPermission()` or `exportFixture()` on `Tracker`.** Use
  `getCurrentLocation()` for a fresh non-persisted snapshot. The other names remain
  target-surface entries in `API.md` §10 and are not implemented.
- **`fieldtrack-maps` has no tests.**
- **No committed field fixtures.** The replay harness exists and is used in tests, but
  constant tuning against real drives has not happened.
- **No OEM field matrix.** The survival stack is unit-tested, not device-tested across
  Xiaomi/Oppo/Vivo/Huawei.
- **Room migrations are untested.** The schema is at v7, all migrations are hand-written and
  additive, but `MigrationTestHelper` needs the schema directory in androidTest assets,
  which AGP 9 currently rejects.
- **`PlatformLocationSource` has no instrumented test.** GPS/network/passive registration is
  unit-covered at the config level only.
- **`./gradlew lintDebug` is red** on one pre-existing `InlinedApi` error in
  `TrackingService`. Every other check passes.

---

## 17. Where to go next

| Document | What it holds |
|---|---|
| [API.md](API.md) | The real Kotlin: types, pipeline internals, ports, Room schema, config reference |
| [PERMISSIONS.md](PERMISSIONS.md) | The permission ladder in depth, FGS by API level, the survival stack |
| [EDGE-CASES.md](EDGE-CASES.md) | Every catalogued case: trigger, symptom, handling, owner, test |
| [POLYLINE-JSON.md](POLYLINE-JSON.md) | The export contract — polyline JSON, arrows, GeoJSON, fixture format |
| [CROSS-PLATFORM.md](CROSS-PLATFORM.md) | Java and React Native surfaces, and why there is no iOS |
| [BUILD.md](BUILD.md) | Build manual, module recipes, version catalog, CI, AGP 9 gotchas |
| [PLAN.md](PLAN.md) | Scope, architecture, provenance — start here to understand *why* |
| [reference/capture-and-plotting-spec.md](reference/capture-and-plotting-spec.md) | The algorithm bible: every filter stage, constant and plotting rule |
