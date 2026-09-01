# FieldTrack SDK — Integration Guide

Background location tracking and track plotting for Android. This document is the complete
public reference for an app integrating the SDK: install, setup, every configuration option,
every public method, every event and callback.

**Android only. Kotlin-first, Java-callable.**

| | |
|---|---|
| Maven group | `com.github.fieldtrack360.fieldtrack` |
| Distribution | JitPack (`https://jitpack.io`) |
| `minSdk` | 26 (Android 8.0) |
| `compileSdk` / `targetSdk` | 37 |
| JDK / Java target | 17 |
| Kotlin | 2.4.x |

---

## Table of contents

1. [Install](#1-install)
2. [License token](#2-license-token)
3. [Quick start](#3-quick-start)
4. [Permissions](#4-permissions)
5. [Configuration reference](#5-configuration-reference)
6. [Public API — `Tracker`](#6-public-api--traker)
7. [Events, state and callbacks](#7-events-state-and-callbacks)
8. [Data models](#8-data-models)
9. [Plotting and export](#9-plotting-and-export)
10. [Live tracking](#10-live-tracking)
11. [Geofences](#11-geofences)
12. [Battery and sensors](#12-battery-and-sensors)
13. [Maps module](#13-maps-module)
14. [Sync module — upload to your backend](#14-sync-module--upload-to-your-backend)
15. [Snap module — road matching](#15-snap-module--road-matching)
16. [Diagnostics](#16-diagnostics)
17. [Java interop](#17-java-interop)
18. [ProGuard / R8](#18-proguard--r8)
19. [Troubleshooting](#19-troubleshooting)

---

## 1. Install

### 1.1 Add the JitPack repository

JitPack must be declared where your project resolves dependencies.

**Gradle 7+ / `settings.gradle.kts` (recommended):**

```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

**Groovy `settings.gradle`:**

```groovy
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url 'https://jitpack.io' }
    }
}
```

**Older projects (`build.gradle` at root):**

```groovy
allprojects {
    repositories {
        google()
        mavenCentral()
        maven { url 'https://jitpack.io' }
    }
}
```

### 1.2 Add the dependency

The **umbrella artifact** pulls the whole SDK in transitively. Replace `<version>` with the
release tag you want (for example `0.1.1-alpha01`).

```groovy
// app/build.gradle
dependencies {
    implementation 'com.github.fieldtrack360.fieldtrack:fieldtrack:<version>'
}
```

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("com.github.fieldtrack360.fieldtrack:fieldtrack:<version>")
}
```

With a version catalog:

```toml
# gradle/libs.versions.toml
[versions]
fieldtrack = "<version>"

[libraries]
fieldtrack = { group = "com.github.fieldtrack360.fieldtrack", name = "fieldtrack", version.ref = "fieldtrack" }
```

```kotlin
dependencies {
    implementation(libs.fieldtrack)
}
```

### 1.3 Retrofit and OkHttp are `compileOnly` in the optional modules

`fieldtrack-sync` and `fieldtrack-snap` declare **both** as `compileOnly`, so neither is
pulled into your app. If you use their built-in HTTP paths, add them yourself:

```kotlin
implementation("com.squareup.retrofit2:retrofit:3.0.0")
implementation("com.squareup.okhttp3:okhttp:5.4.0")
```

You can skip it entirely by supplying your own `SyncTransport` (see [§14.6](#146-custom-transport))
or your own `RoadSnapProvider` (see [§15](#15-snap-module--road-matching)).

### 1.4 What you do *not* have to add

- **No DI framework.** No Hilt, no `@HiltAndroidApp`, no KSP, no Gradle plugin. The SDK's
  object graph is wired internally.
- **No manifest entries.** The AAR declares every permission, the foreground service and
  all three broadcast receivers; they merge into your APK automatically. Merged in:

  `ACCESS_COARSE_LOCATION`, `ACCESS_FINE_LOCATION`, `ACCESS_BACKGROUND_LOCATION`,
  `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`, `POST_NOTIFICATIONS`,
  `ACTIVITY_RECOGNITION` (+ the Google Play Services variant), `RECEIVE_BOOT_COMPLETED`,
  `WAKE_LOCK`, `ACCESS_NETWORK_STATE`.

  `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is deliberately **not** declared — it is
  Play-policy sensitive and must be your own explicit choice.
- **No ProGuard rules.** `consumer-rules.pro` ships inside each AAR.

### 1.5 Google Play Services

The default provider (`LocationProviderType.FUSED`) needs Google Play Services. For devices
without it (Huawei, AOSP builds), use `LocationProviderType.GPS_ONLY`, `NETWORK_ONLY` or
`PASSIVE` — these run on the platform `LocationManager` and need nothing from Google. See
[§5.2](#52-geolocationconfig).

---

## 2. License token

Release builds require a license token. Debuggable builds are **waived automatically** — you
can develop with no token at all.

Supply it either in your manifest:

```xml
<application>
    <meta-data
        android:name="TrackItLicense"
        android:value="YOUR_LICENSE_TOKEN" />
</application>
```

or in config, which takes precedence:

```kotlin
traker.ready(
    TrackerConfig.builder()
        .license(BuildConfig.FIELDTRACK_LICENSE)
        .build()
)
```

The token is bound to your application id. `ready()` returns a `TrackerResult.Error` with
`LICENSE_MISSING`, `LICENSE_INVALID` or `LICENSE_BUNDLE_MISMATCH` when the check fails, and
the same failure is emitted on the event flow as `TrackerEvent.Error`.

The `license` field is never persisted with the rest of the config.

---

## 3. Quick start

Three calls: `getInstance` → `ready` → `start`.

```kotlin
class MyApplication : Application() {

    val traker: Tracker by lazy { Tracker.getInstance(this) }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            when (val result = traker.ready(TrackerConfig())) {
                is TrackerResult.Ok    -> Log.d("app", "ready: ${result.value}")
                is TrackerResult.Error -> Log.w("app", "${result.code}: ${result.message}")
            }
        }
    }
}
```

```kotlin
// After permissions are granted:
suspend fun begin() {
    when (val result = traker.start(tag = "commute")) {
        is TrackerResult.Ok    -> Log.d("app", "session ${result.value.id}")
        is TrackerResult.Error -> Log.w("app", "${result.code}: ${result.message}")
    }
}

suspend fun end() {
    traker.stop()
}
```

Read the data back:

```kotlin
val points   = traker.getPoints(PointQuery(sessionId = sessionId))
val track    = traker.buildTrack(PointQuery(sessionId = sessionId))
val json     = traker.exportPolylineJson(PointQuery(sessionId = sessionId))
val distance = traker.getOdometerMeters()
```

### Contract notes

- `Tracker.getInstance(context)` is **idempotent and thread-safe** — one instance per process.
  It retains only the application context, so passing an `Activity` leaks nothing. It is
  cheap: no database is opened and no disk touched until `ready()`.
- **Nothing on the `Tracker` surface throws.** Every fallible call returns `TrackerResult` with a
  typed `ErrorCode`. The only deliberate exceptions are `TrackerConfig.Builder.build()` and
  `SyncConfig.Builder.build()`, which fail fast with `IllegalArgumentException` on your own
  thread while you assemble the value. Use `buildUnchecked()` + `validate()` if you prefer to
  read the errors yourself.
- `ready()` must be called before `start()` or `getCurrentLocation()`; otherwise you get
  `ErrorCode.NOT_READY`.

---

## 4. Permissions

**The SDK shows no UI.** No dialogs, no activities, no full-screen intents. It answers
questions and hands you the permission arrays and the Settings intent; your app owns every
prompt.

```kotlin
val permissions: PermissionManager = traker.permissions()
```

### 4.1 `PermissionManager` API

| Method | Returns | Notes |
|---|---|---|
| `tier()` | `PermissionTier` | `NONE`, `FOREGROUND_ONLY` or `FULL` |
| `accuracy()` | `LocationAccuracy` | `PRECISE` when `ACCESS_FINE_LOCATION` is granted, else `APPROXIMATE` |
| `hasActivityRecognition()` | `Boolean` | Always `true` below API 29 |
| `hasNotificationPermission()` | `Boolean` | Always `true` below API 33 |
| `foregroundPermissions()` | `Array<String>` | Step 1 — fine + coarse together |
| `notificationPermissions()` | `Array<String>` | Ask **first** on API 33+; empty below |
| `activityRecognitionPermissions()` | `Array<String>` | Optional; empty below API 29 |
| `backgroundRequest()` | `BackgroundRequest` | Step 2 — what to do next for `FULL` |
| `shouldStopAsking(attempts: Int)` | `Boolean` | `true` at 3 attempts — stop prompt-looping |
| `appSettingsIntent()` | `Intent` | Deep link to your app's settings page |

`traker.permissionTier()` is a shortcut for `permissions().tier()`.

### 4.2 The ladder

`PermissionManager.BackgroundRequest` is a sealed interface:

| Case | Meaning |
|---|---|
| `AlreadyGranted` | Nothing to do |
| `NotApplicable` | Below API 29 — no separate background permission exists |
| `NeedsForegroundFirst` | Ask for fine location first; asking for background before it is a silent denial |
| `Prompt(permissions)` | API 29 only — a runtime prompt still works |
| `NeedsSettings(intent)` | API 30+ — the OS shows no prompt; deep-link to Settings and explain "Allow all the time" |

```kotlin
// Step 0 — API 33+: notifications, before starting a foreground service
launcher.launch(permissions.notificationPermissions())

// Step 1 — foreground location (fine + coarse in ONE request)
launcher.launch(permissions.foregroundPermissions())

// Step 2 — background, only after fine is granted and you have shown a rationale
when (val request = permissions.backgroundRequest()) {
    is PermissionManager.BackgroundRequest.Prompt        -> launcher.launch(request.permissions)
    is PermissionManager.BackgroundRequest.NeedsSettings -> startActivity(request.intent)
    PermissionManager.BackgroundRequest.NeedsForegroundFirst -> askForegroundFirst()
    PermissionManager.BackgroundRequest.AlreadyGranted,
    PermissionManager.BackgroundRequest.NotApplicable    -> Unit
}

// Optional — activity recognition. Denial degrades motion detection; never fatal.
launcher.launch(permissions.activityRecognitionPermissions())
```

### 4.3 Tier behaviour

- `NONE` — `start()` and `getCurrentLocation()` return `ErrorCode.PERMISSION_DENIED`.
- `FOREGROUND_ONLY` — tracking runs while your app is in the foreground. Background location
  is not a hard gate; you still get data.
- `FULL` — background tracking works.

`accuracy()` is orthogonal and always surfaced. A 1–3 km error circle defeats every gate in
the pipeline, so `CONTINUOUS`/`ADAPTIVE` refuse to start on approximate-only access.

---

## 5. Configuration reference

`TrackerConfig` is a `data class` with five nested blocks. Kotlin hosts can use named
arguments and `copy()`; Java hosts (and anyone who prefers fluency) use `TrackerConfig.builder()`.

```kotlin
val config = TrackerConfig.builder()
    .provider(LocationProviderType.FUSED)
    .accuracyProfile(AccuracyProfile.STRICT)
    .intervalMs(30_000)
    .notification("Delivery in progress", "Recording your route")
    .baseUrl("https://api.example.com")
    .build()          // validates; throws IllegalArgumentException on failure

traker.ready(config)
```

### 5.1 Top-level `TrackerConfig`

| Field | Type | Default | What it does |
|---|---|---|---|
| `geolocation` | `GeolocationConfig` | defaults | Provider, accuracy, cadence |
| `motion` | `MotionConfig` | defaults | Activity recognition, stop detection, heartbeat |
| `service` | `ServiceConfig` | defaults | Foreground service, notification, survival |
| `persistence` | `PersistenceConfig` | defaults | Retention and diagnostic storage |
| `sensors` | `SensorConfig` | defaults | Hardware motion assists |
| `license` | `String?` | `null` | Release license token. Never persisted |
| `baseUrl` | `String?` | `null` | Scheme + host for uploads, e.g. `https://api.example.com`. Core never opens a socket; `fieldtrack-sync` resolves a relative path against it |
| `reset` | `Boolean` | `true` | `true` — this config is applied on top of factory defaults. `false` — the persisted config wins and this object is **ignored after the first launch**; only `setConfig()` changes anything after that. **Leave `true` during development** |

Builder methods for the whole blocks: `.geolocation()`, `.motion()`, `.service()`,
`.persistence()`, `.sensors()`, `.license()`, `.baseUrl()`, `.reset()`.

`config.validate(): List<String>` returns everything wrong with a config, or an empty list.
`ready()` runs it and returns `ErrorCode.INVALID_CONFIG` with the joined messages.

### 5.2 `GeolocationConfig`

| Field | Type | Default | What it does |
|---|---|---|---|
| `trackingMode` | `TrackingMode` | `ADAPTIVE` | See below |
| `providerType` | `LocationProviderType` | `FUSED` | Which hardware produces fixes |
| `desiredAccuracy` | `DesiredAccuracy` | `HIGH` | Biases the *fused* provider's own source choice. `HIGH`, `BALANCED`, `LOW` |
| `accuracy` | `AccuracyConfig` | `BALANCED` profile | The accuracy meter — see [§5.6](#56-accuracyconfig) |
| `distanceFilterM` | `Float` | `0f` | **Must stay 0.** A non-zero OS distance filter generates stationary drift; all thinning is done in software |
| `intervalMs` | `Long` | `60_000` | Requested sampling interval |
| `fastestIntervalMs` | `Long` | `30_000` | Fastest the OS may deliver. Must be ≤ `intervalMs` |
| `maxUpdateDelayMs` | `Long` | `60_000` | OS batching window |
| `maxFixAgeMs` | `Long` | `10_000` | Older fixes are treated as stale |
| `deliveryStalenessMs` | `Long` | `60_000` | Delivery-gap threshold |
| `adaptiveCadence` | `Boolean` | `true` | Speed up while vehicular |
| `vehicularIntervalMs` | `Long` | `12_000` | The vehicular tier interval |
| `turnBurst` | `Boolean` | `true` | Third tier: sample faster while measurably turning |
| `turnBurstIntervalMs` | `Long` | `4_000` | Must be > 0 and ≤ the tier it accelerates |
| `oneShotTimeoutMs` | `Long` | `30_000` | `getCurrentLocation()` timeout |
| `mockLocationPolicy` | `MockPolicy` | `FLAG` | `FLAG` (store + mark), `REJECT`, `ALLOW` |
| `navigationMode` | `Boolean` | `false` | ~1 Hz high-accuracy profile that overrides every adaptive tier. **Requires `service.foregroundService`** |
| `navigationIntervalMs` | `Long` | `1_000` | Navigation interval |
| `navigationFastestIntervalMs` | `Long` | `500` | Navigation floor |

**`TrackingMode`**

| Value | Behaviour |
|---|---|
| `CONTINUOUS` | Stream at `intervalMs` always; the filter does all thinning. Highest fidelity, highest battery |
| `ADAPTIVE` | Stream while moving with adaptive cadence; heartbeat-only while stationary (default) |
| `MOTION_ONLY` | Location fully off while stationary. Lowest battery, coarsest stop timing |

**`LocationProviderType`**

| Value | Behaviour |
|---|---|
| `FUSED` | Play Services fused provider. Blends GNSS, Wi-Fi, cell and sensors. Best time-to-first-fix. **Default** |
| `GPS_ONLY` | `LocationManager.GPS_PROVIDER`. Satellite-only — no Wi-Fi teleports, but no fix indoors/tunnels, 30–60 s cold starts, more battery. **Works without Play Services** |
| `NETWORK_ONLY` | Wi-Fi/cell centroids. Coarse (20–2000 m), cheap. Needs an accuracy ceiling ≥ 50 m — `validate()` rejects a tighter one |
| `PASSIVE` | Fixes other apps requested, for free. No power cost, no guarantee of any data. Every cadence tier is inert; `navigationMode` is refused |

Builder: `.trackingMode()`, `.provider()`, `.desiredAccuracy()`, `.accuracy()`,
`.accuracyProfile()`, `.maxAccuracyMeters()`, `.recoveryTrustMeters()`, `.intervalMs()`,
`.fastestIntervalMs()`, `.maxUpdateDelayMs()`, `.maxFixAgeMs()`, `.adaptiveCadence()`,
`.vehicularIntervalMs()`, `.turnBurst()`, `.turnBurstIntervalMs()`, `.navigationMode()`,
`.navigationIntervalMs()`, `.navigationFastestIntervalMs()`, `.oneShotTimeoutMs()`,
`.mockLocationPolicy()`.

### 5.3 `MotionConfig`

| Field | Type | Default | What it does |
|---|---|---|---|
| `activityRecognition` | `Boolean` | `true` | Use Play Services activity recognition as enrichment |
| `activityRecognitionIntervalMs` | `Long` | `10_000` | AR polling interval |
| `activityConfidenceMin` | `Int` | `75` | Minimum confidence for a transition |
| `snapshotConfidenceMin` | `Int` | `50` | Minimum confidence for a snapshot read |
| `disableStopDetection` | `Boolean` | `false` | **Declared but unimplemented** — nothing in the SDK reads it |
| `stopOnStationary` | `Boolean` | `false` | **Declared but unimplemented** — nothing in the SDK reads it. Ending a session because the user parked is an application decision the SDK is not entitled to make, which is why it never shipped. For the thing hosts want here, see `suppressWhileStationary` |
| `suppressWhileStationary` | `Boolean` | `false` | Drop points that only stationary drift explains, when the accelerometer agrees the device has not moved (EC-142). Every other stationary defence reasons about *position*, because a GNSS fix carries nothing else; this one measures whether the device physically moved. **A veto, never a trigger:** it can only remove a point already classified as stationary, is not consulted once displacement or Doppler read as moving, and one counted step withdraws it. Requires an accelerometer — turned off with a `Diagnostic` where there is none |
| `stillnessEscapeMin` | `Int` | `30` | How long `suppressWhileStationary` may suppress before letting one fix through regardless. The valve: a wedged accelerometer degrades to the previous behaviour instead of silencing the session |
| `stopTimeoutMin` | `Int` | `5` | Minutes of no movement before STATIONARY |
| `stationaryRadiusM` | `Float` | `150f` | Radius of the internal stationary wake geofence. Must be > 0 |
| `stationaryGeofenceId` | `String` | `"trackit-stationary"` | Id of that fence. Must not be blank |
| `stationaryGeofenceOnEnterEvent` | `String` | `"stationary_fence_enter"` | Event name on enter |
| `stationaryGeofenceOnExitEvent` | `String` | `"stationary_fence_exit"` | Event name on exit |
| `motionTriggerDelayMs` | `Long` | `0` | Delay before acting on a motion trigger |
| `heartbeatIntervalSec` | `Int` | `900` | **Data-plane** heartbeat: warms the filter, stores nothing. This is what makes a two-hour steady user produce exactly one point. Must be ≥ 5 × the sampling interval |
| `persistHeartbeat` | `Boolean` | `false` | Also store the heartbeat point |
| `bearingChangeCaptureDeg` | `Int` | `30` | Store a point whenever heading turned this far since the last stored one, regardless of speed/distance gates. `0` disables. 30° sits below a motorway interchange and above lane-change/GPS heading noise. It was `40`, which is a junction threshold rather than a bend threshold — a long curve turning 35° between stored points never crossed it, so the track kept the straight legs and dropped the curve |
| `cornerAnchorCapture` | `Boolean` | `true` | Restore a rejected fix once the *next* fix shows a corner turned across it. Bearing-change capture compares against the last **stored** point, so at a corner's apex only half the turn is behind you and the apex is dropped; this holds the rejection for one fix and keeps it if the path bent across it (`Reasons.CORNER_ANCHOR`). Only the heuristic gate's rejections are reconsidered — never impossible speed, poor accuracy or the sigma gate. One fix of latency, and only for fixes that were being discarded |

Builder: `.activityRecognition()`, `.activityRecognitionIntervalMs()`, `.activityConfidenceMin()`,
`.snapshotConfidenceMin()`, `.disableStopDetection()`, `.stopOnStationary()`,
`.suppressWhileStationary()`, `.stillnessEscapeMin()`, `.stopTimeoutMin()`,
`.stationaryRadiusM()`, `.stationaryGeofenceId()`, `.stationaryGeofenceOnEnterEvent()`,
`.stationaryGeofenceOnExitEvent()`, `.motionTriggerDelayMs()`, `.heartbeatIntervalSec()`,
`.persistHeartbeat()`, `.bearingChangeCaptureDeg()`, `.cornerAnchorCapture()`.

### 5.4 `SensorConfig`

| Field | Type | Default | What it does |
|---|---|---|---|
| `useSignificantMotion` | `Boolean` | `true` | Permission-free, ~zero-power hardware wake for STATIONARY → MOVING |
| `useStepCorroboration` | `Boolean` | `true` | Step-count veto on stationary drift; confirms indoor walks |
| `useAccelerometerVeto` | `Boolean` | `true` | Reject "movement" with no accelerometer support |
| `useBarometer` | `Boolean` | `false` | Use pressure sensor when present |
| `stepBatchLatencyMs` | `Long` | `60_000` | Step-counter batching latency |
| `useGyroTurnPrediction` | `Boolean` | `true` | Arm the turn burst from gyroscope yaw rate, ahead of GNSS heading. Needs no permission. The gyroscope is opened only while fixes report vehicular speed and released within a minute of them stopping, so a walking or parked session never touches it. No-op when `geolocation.turnBurst` is off or the device has no gyroscope |

Builder: `.useSignificantMotion()`, `.useStepCorroboration()`, `.useAccelerometerVeto()`,
`.useGyroTurnPrediction()`,
`.useBarometer()`, `.stepBatchLatencyMs()`.

### 5.5 `ServiceConfig`

| Field | Type | Default | What it does |
|---|---|---|---|
| `foregroundService` | `Boolean` | `true` | Run capture in a foreground service |
| `stopOnTerminate` | `Boolean` | `false` | **Inverted from the common default on purpose** — a swipe-away does not silently end tracking |
| `startOnBoot` | `Boolean` | `true` | Resume an open session after reboot / app update |
| `healthLoopMs` | `Long` | `120_000` | Supervision loop period |
| `watchdogIntervalMs` | `Long` | `60_000` | Watchdog check period |
| `watchdogThrottleMs` | `Long` | `900_000` | Minimum gap between watchdog restarts |
| `backstopIntervalMin` | `Int` | `15` | WorkManager backstop period |
| `deadTrackerMovingMin` | `Int` | `30` | Minutes with no fix while moving before declaring the tracker dead |
| `deadTrackerStationaryMin` | `Int` | `60` | Same, while stationary |
| `wakeLockMs` | `Long` | `20_000` | Wake-lock hold during a capture burst |
| `notificationTitle` | `String` | `"Tracking active"` | Foreground notification title |
| `notificationText` | `String` | `"Recording your location"` | Foreground notification body |
| `notificationChannelId` | `String` | `"trackit_tracking"` | Channel id |
| `notificationChannelName` | `String` | `"Location tracking"` | Channel name shown in system settings |
| `notificationSmallIconResName` | `String?` | `null` | Drawable **resource name** (e.g. `"ic_tracking"`) for the small icon |

Builder: `.foregroundService()`, `.stopOnTerminate()`, `.startOnBoot()`, `.healthLoopMs()`,
`.watchdogIntervalMs()`, `.watchdogThrottleMs()`, `.backstopIntervalMin()`,
`.deadTrackerMovingMin()`, `.deadTrackerStationaryMin()`, `.wakeLockMs()`,
`.notification(title, text)`, `.notificationChannel(id, name)`, `.notificationSmallIconResName()`.

### 5.6 `AccuracyConfig`

A ceiling on the reported error radius, applied to fixes claiming to be **moving**. Stationary
fixes are deliberately governed by the anchor/wobble defences instead.

| Field | Type | Default | Notes |
|---|---|---|---|
| `profile` | `AccuracyProfile` | `BALANCED` | Named ceiling |
| `maxAccuracyMeters` | `Float?` | `null` | **Required** by `CUSTOM`, **rejected** by every other profile |
| `recoveryTrustMeters` | `Float?` | `null` | Overrides the profile's post-gap re-anchor bar. Must be > 0 |

| Profile | Moving ceiling | Re-anchor bar | Use when |
|---|---|---|---|
| `STRICT` | 20 m | 15 m | Urban canyon; sparser track, no zigzag |
| `BALANCED` | 30 m | 25 m | Default, set from field data |
| `RELAXED` | 60 m | 40 m | Indoor / network-assisted / coverage-first |
| `CUSTOM` | `maxAccuracyMeters` (5–500 m) | 25 m unless overridden | You know your own bar |

Derived read-only properties: `accuracy.maxAccuracyM` and `accuracy.recoveryTrustM` (always
coerced to ≤ `maxAccuracyM`).

```kotlin
TrackerConfig.builder().accuracyProfile(AccuracyProfile.STRICT).build()
TrackerConfig.builder().maxAccuracyMeters(35f).build()   // implies CUSTOM
```

### 5.7 `PersistenceConfig`

| Field | Type | Default | What it does |
|---|---|---|---|
| `maxDaysToPersist` | `Int` | `7` | TTL for stored points. `0` = unlimited. Must be ≥ 0 |
| `maxRecords` | `Int` | `0` | Row cap. `0` = unlimited |
| `persistRawFixes` | `Boolean` | `false` | Store fixes exactly as the OS delivered them (debug layer 1) |
| `rawRingCapacity` | `Int` | `5_000` | Ring size for raw fixes |
| `persistRawPoints` | `Boolean` | `false` | Store **every judged fix** in point form, accepted or not (debug layer 2). One wide row per fix — real write amplification |
| `rawPointRingCapacity` | `Int` | `20_000` | Rows kept **per session**, not globally |
| `persistDecisions` | `Boolean` | `true` | Keep the decision log |
| `decisionRetentionDays` | `Int` | `3` | Decision log TTL |
| `decisionMaxRows` | `Int` | `50_000` | Decision log row cap |

Builder: `.maxDaysToPersist()`, `.maxRecords()`, `.persistRawFixes()`, `.rawRingCapacity()`,
`.persistRawPoints()`, `.rawPointRingCapacity()`, `.persistDecisions()`,
`.decisionRetentionDays()`, `.decisionMaxRows()`.

### 5.8 Validation rules

`validate()` (and therefore `build()` / `ready()`) rejects:

- `intervalMs < fastestIntervalMs`
- `distanceFilterM > 0`
- `heartbeatIntervalSec < 5 × (intervalMs / 1000)`
- `stationaryRadiusM <= 0`, blank `stationaryGeofenceId` / enter / exit event names
- `baseUrl` that is not an absolute URL with a scheme and host
- `turnBurstIntervalMs <= 0`, or greater than the tier it accelerates
- `navigationIntervalMs <= 0`, `navigationIntervalMs < navigationFastestIntervalMs`, or
  `navigationMode` without `foregroundService`
- `maxDaysToPersist < 0`
- `maxAccuracyMeters` set without `CUSTOM`, missing with `CUSTOM`, or outside 5–500 m
- `recoveryTrustMeters <= 0`
- `NETWORK_ONLY` with an accuracy ceiling below 50 m
- `navigationMode` with `providerType = PASSIVE`

---

## 6. Public API — `Tracker`

```kotlin
val traker = Tracker.getInstance(context)   // @JvmStatic, idempotent, thread-safe
```

### 6.1 Lifecycle

| Method | Signature | Notes |
|---|---|---|
| `ready` | `suspend fun ready(config: TrackerConfig = TrackerConfig()): TrackerResult<TrackerState>` | Verifies the license, resolves and validates config, restores persisted filter state, starts provider/battery monitoring, enqueues the daily prune, and emits `SessionInterrupted` if a session was left open by a crash or force-stop |
| `start` | `suspend fun start(tag: String? = null): TrackerResult<TrackSession>` | Opens a session. `NOT_READY` if `ready()` was not called |
| `stop` | `suspend fun stop(): TrackerResult<TrackSession?>` | Closes the open session |
| `state` | `val state: StateFlow<TrackerState>` | Coarse lifecycle state |
| `events` | `val events: SharedFlow<TrackerEvent>` | Replay 0, unlimited subscribers |

### 6.2 Location

| Method | Signature | Notes |
|---|---|---|
| `getCurrentLocation` | `suspend fun getCurrentLocation(): TrackerResult<TrackFix>` | One fresh fix. **Snapshot only** — not accepted, persisted, added to the odometer, or emitted as a tracking location. Errors: `NOT_READY`, `PERMISSION_DENIED`, `LOCATION_DISABLED`, `FIX_TIMEOUT` |
| `providerState` | `fun providerState(): StateFlow<ProviderState>` | GPS toggle, permission tier, granularity, fused availability, battery saver. Broadcast-driven, never polled |
| `permissionTier` | `fun permissionTier(): PermissionTier` | |
| `permissions` | `fun permissions(): PermissionManager` | The permission ladder as data |
| `offerFix` | `fun offerFix(fix: TrackFix)` | Feed a fix from a source the SDK does not own (a test, a replay, a custom provider). It is judged by exactly the same gates — you cannot inject an unvalidated point |

### 6.3 Reading data

| Method | Signature |
|---|---|
| `getPoints` | `suspend fun getPoints(query: PointQuery = PointQuery()): List<TrackPoint>` |
| `observePoints` | `fun observePoints(sessionId: String): Flow<List<TrackPoint>>` |
| `getCount` | `suspend fun getCount(query: PointQuery = PointQuery()): Int` |
| `getOdometerMeters` | `suspend fun getOdometerMeters(): Double` |
| `getSessions` | `suspend fun getSessions(fromMs: Long? = null, toMs: Long? = null): List<TrackSession>` |
| `currentSession` | `suspend fun currentSession(): TrackSession?` |

All reads are paged — `PointQuery(limit = 500, offset = 0)` by default.

### 6.4 Plotting

| Method | Signature |
|---|---|
| `buildTrack` | `suspend fun buildTrack(query: PointQuery = PointQuery(), options: TrackOptions = TrackOptions()): Track` |
| `exportPolylineJson` | `suspend fun exportPolylineJson(query, options): String` |
| `exportGeoJson` | `suspend fun exportGeoJson(query, options): String` |
| `setRoadSnapProvider` | `fun setRoadSnapProvider(provider: RoadSnapProvider)` |

### 6.5 Live tracking

| Method | Signature |
|---|---|
| `liveTrack` | `fun liveTrack(): Flow<LiveTrackUpdate>` |
| `setActiveRoute` | `fun setActiveRoute(route: List<GeoPoint>)` |
| `isOffRoute` | `fun isOffRoute(): Boolean` |

### 6.6 Geofences

| Method | Signature |
|---|---|
| `addGeofence` | `suspend fun addGeofence(geofence: TrackerGeofence): TrackerResult<TrackerGeofence>` |
| `removeGeofence` | `suspend fun removeGeofence(id: String = TrackerGeofence.DEFAULT_ID): TrackerResult<Boolean>` |
| `removeAllGeofences` | `suspend fun removeAllGeofences(): TrackerResult<Int>` |
| `getGeofence` | `fun getGeofence(id: String = TrackerGeofence.DEFAULT_ID): TrackerGeofence?` |
| `getGeofences` | `fun getGeofences(): List<TrackerGeofence>` |
| `getGeofenceEvents` | `fun getGeofenceEvents(geofenceId: String? = null, fromMs: Long? = null, toMs: Long? = null, limit: Int = 500, offset: Int = 0): List<TrackerGeofenceEvent>` |
| `deleteGeofenceEvents` | `fun deleteGeofenceEvents(geofenceId: String? = null, fromMs: Long? = null, toMs: Long? = null): Int` |

### 6.7 Device state

| Method | Signature |
|---|---|
| `batteryInfo` | `fun batteryInfo(): BatteryInfo` |
| `batteryState` | `fun batteryState(): StateFlow<BatteryInfo>` |
| `getSensors` | `fun getSensors(): DeviceSensors` |

### 6.8 Diagnostics

| Method | Signature |
|---|---|
| `getRawFixes` | `suspend fun getRawFixes(sessionId: String): List<RawFix>` |
| `getRawPoints` | `suspend fun getRawPoints(sessionId: String): List<RawPoint>` |
| `getDecisions` | `suspend fun getDecisions(sessionId: String? = null, limit: Int = 200, offset: Int = 0): List<FixDecision>` |

---

## 7. Events, state and callbacks

The SDK has **no `var callback` properties** — a second registrant would silently replace the
first. Everything is a Kotlin `Flow`.

### 7.1 `TrackerEvent` — the event flow

```kotlin
lifecycleScope.launch {
    traker.events.collect { event ->
        when (event) {
            is TrackerEvent.Location           -> draw(event.point)
            is TrackerEvent.LocationRejected   -> log(event.decision)
            is TrackerEvent.MotionChange       -> updateUi(event.state, event.point)
            is TrackerEvent.ActivityChange     -> show(event.activity, event.confidence)
            is TrackerEvent.EnabledChange      -> toggle(event.enabled)
            is TrackerEvent.ProviderChange     -> render(event.state)
            is TrackerEvent.Heartbeat          -> touch(event.atMs)
            is TrackerEvent.PowerSaveChange    -> warn(event.enabled)
            is TrackerEvent.BatteryChange      -> battery(event.battery)
            is TrackerEvent.GeofenceAdded      -> Unit
            is TrackerEvent.GeofenceRemoved    -> Unit
            is TrackerEvent.GeofenceEntered    -> arrive(event.geofence)
            is TrackerEvent.GeofenceExited     -> depart(event.geofence)
            is TrackerEvent.SessionInterrupted -> offerResume(event.session)
            is TrackerEvent.Diagnostic         -> log(event.message)
            is TrackerEvent.Error              -> handle(event.code, event.message)
        }
    }
}
```

| Event | Payload | Fires when |
|---|---|---|
| `Location` | `point: TrackPoint` | A fix was accepted and stored |
| `LocationRejected` | `decision: FixDecision` | A fix was skipped or rejected, with the numeric reason |
| `MotionChange` | `state: MotionState`, `point: TrackPoint?` | `STOPPED ⇄ MOVING ⇄ STOP_PENDING ⇄ STATIONARY` |
| `ActivityChange` | `activity: ActivityType`, `confidence: Int` | Activity recognition transition |
| `EnabledChange` | `enabled: Boolean` | Location services toggled |
| `ProviderChange` | `state: ProviderState` | GPS toggle, permission change, granularity change, battery saver |
| `PermissionChange` | `previous: PermissionTier`, `current: PermissionTier`, `accuracy: LocationAccuracy` | The location grant moved, in either direction — revoke, re-grant, all-the-time→while-using, precise→approximate |
| `LocationServicesChange` | `enabled: Boolean`, `state: ProviderState` | The GPS/location master switch was toggled. Both directions, including the recovery |
| `CaptureSuspended` | `reason: ErrorCode`, `message: String` | Capture stopped but the session is **still open**: permission revoked, or every provider off |
| `CaptureResumed` | — | Capture re-armed in the same session after a `CaptureSuspended` |
| `Heartbeat` | `atMs: Long` | **Control-plane** liveness tick (distinct from the data-plane heartbeat) |
| `PowerSaveChange` | `enabled: Boolean` | Battery saver on/off |
| `BatteryChange` | `battery: BatteryInfo` | Plug, unplug, low, okay — and drift the capture path notices |
| `GeofenceAdded` / `GeofenceRemoved` | `geofence` / `geofenceId` | Registry changed |
| `GeofenceEntered` / `GeofenceExited` | `geofence: TrackerGeofence` | A fence was crossed |
| `SessionInterrupted` | `session: TrackSession` | `ready()` found a session left open by a crash or force-stop — you decide what to do |
| `Diagnostic` | `message: String` | Informational |
| `Error` | `code: ErrorCode`, `message: String` | Anything the SDK wants you to know about |

Collect from a lifecycle scope for UI, or from an application-scoped one for work that must
continue with no UI on screen.

### 7.2 `TrackerState`

```kotlin
data class TrackerState(
    val isReady: Boolean = false,
    val isTracking: Boolean = false,
    val motionState: MotionState = MotionState.STOPPED,
    val providerState: ProviderState = ProviderState(),
    val currentSessionId: String? = null,
)
```

### 7.3 `ProviderState`

```kotlin
data class ProviderState(
    val gpsEnabled: Boolean = false,
    val networkEnabled: Boolean = false,
    val permission: PermissionTier = PermissionTier.NONE,
    val accuracyAuthorization: LocationAccuracy = LocationAccuracy.APPROXIMATE,
    val fusedAvailable: Boolean = false,
    val powerSaveMode: Boolean = false,
)
```

### 7.4 `TrackerResult` and `ErrorCode`

```kotlin
sealed interface TrackerResult<out T> {
    data class Ok<T>(val value: T) : TrackerResult<T>
    data class Error(val code: ErrorCode, val message: String) : TrackerResult<Nothing>
}
```

| `ErrorCode` | Meaning |
|---|---|
| `NOT_READY` | `ready()` has not been called |
| `PERMISSION_DENIED` | No location permission at all |
| `BACKGROUND_PERMISSION_MISSING` | Background location needed for the requested behaviour |
| `COARSE_ONLY` | Approximate-only access defeats the pipeline's gates |
| `LOCATION_DISABLED` | GPS and network providers both off |
| `PLAY_SERVICES_UNAVAILABLE` | Fused provider unavailable — switch to `GPS_ONLY` |
| `FGS_START_REFUSED` | The OS refused the foreground service start |
| `NOTIFICATION_HIDDEN` | The foreground notification is not visible |
| `FIX_TIMEOUT` | No usable fix within `oneShotTimeoutMs` |
| `STORAGE_FULL` | No room to persist |
| `STORAGE_RESET` | The store had to be reset |
| `TRACKER_DEAD` | No fix for `deadTrackerMovingMin` / `deadTrackerStationaryMin` |
| `INVALID_CONFIG` | `validate()` reported errors |
| `LICENSE_MISSING` / `LICENSE_INVALID` / `LICENSE_BUNDLE_MISMATCH` | License gate |
| `NO_ACTIVITY` | An Activity was required and none supplied |
| `MOTION_DETECTION_DEGRADED` | `motionQuality = POOR` — motion gating is untrustworthy on this hardware |
| `GEOFENCE_REGISTRATION_FAILED` / `GEOFENCE_REMOVAL_FAILED` / `GEOFENCE_LIMIT_REACHED` | Geofence operations |
| `SNAP_UNAVAILABLE` | A `RoadSnapProvider` could not answer. **Never fatal** — the track is built from raw geometry with a `snap_unavailable` warning |
| `INTERNAL` | Something threw where the contract says nothing throws. A bug in the SDK, not a condition to handle |

---

## 8. Data models

### 8.1 `TrackPoint` — an accepted, stored point

```kotlin
data class TrackPoint(
    val id: Long = 0,
    val uuid: String,
    val sessionId: String,
    val timeMs: Long,                    // wall clock, for display and day bucketing
    val elapsedRealtimeNanos: Long,      // monotonic, the real observation time
    val localDate: String,
    val timezone: String,                // IANA id, stored PER POINT (a session can cross zones)
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val altitude: Double? = null,
    val speedMps: Float = 0f,
    val bearingDeg: Float = 0f,
    val hasSpeed: Boolean = false,
    val hasBearing: Boolean = false,
    val provider: String = "unknown",
    val isMock: Boolean = false,
    val movementStatus: MovementStatus = MovementStatus.STEADY,
    val detectedActivity: ActivityType? = null,
    val activityStartTimeMs: Long = 0,
    val odometerMeters: Double = 0.0,
    val batteryPct: Int? = null,
    val isCharging: Boolean? = null,
    val extras: String? = null,
    val acceptReason: String,            // the Reasons vocabulary
)
```

### 8.2 `TrackSession`

```kotlin
data class TrackSession(
    val id: String,
    val startedAtMs: Long,
    val startedAtElapsedNanos: Long,
    val endedAtMs: Long? = null,
    val tag: String? = null,
    val configSnapshot: String? = null,   // the config in effect, so old tracks stay interpretable
) {
    val isOpen: Boolean get() = endedAtMs == null
}
```

### 8.3 `PointQuery`

```kotlin
data class PointQuery(
    val sessionId: String? = null,
    val fromMs: Long? = null,
    val toMs: Long? = null,
    val limit: Int = 500,
    val offset: Int = 0,
)
```

### 8.4 Enums

| Type | Values |
|---|---|
| `MovementStatus` | `STEADY`, `MOVING` |
| `MotionState` | `STOPPED`, `MOVING`, `STOP_PENDING`, `STATIONARY` |
| `ActivityType` | `IN_VEHICLE`, `ON_BICYCLE`, `ON_FOOT`, `WALKING`, `RUNNING`, `STILL`, `TILTING`, `UNKNOWN` (+ `isLowTier`) |
| `MockPolicy` | `FLAG` (default), `REJECT`, `ALLOW` |
| `PermissionTier` | `NONE`, `FOREGROUND_ONLY`, `FULL` |
| `LocationAccuracy` | `APPROXIMATE`, `PRECISE` |
| `PowerSource` | `NONE`, `AC`, `USB`, `WIRELESS`, `DOCK`, `UNKNOWN` |
| `MotionQuality` | `FULL`, `DEGRADED`, `POOR` |
| `GeofenceTransition` | `ENTER`, `EXIT` |

`ActivityType` is **enrichment only, never a capture gate** — some devices report entire
17-minute drives as `STILL` under battery saver.

### 8.5 `TrackFix` — a raw fix

Returned by `getCurrentLocation()` and accepted by `offerFix()`.

```kotlin
data class TrackFix(
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
    val provider: String = TrackFix.UNKNOWN_PROVIDER,
    val isMock: Boolean = false,
    val satelliteCount: Int? = null,
    val speedAccuracyMps: Float? = null,
    val bearingAccuracyDeg: Float? = null,
)
```

---

## 9. Plotting and export

`buildTrack()` is the headline deliverable: a ready-to-draw track that any map library can
render without doing geometry. It runs entirely on-device — no backend, no routing key, no
quota — unless you install a `RoadSnapProvider`.

```kotlin
val track = traker.buildTrack(
    query   = PointQuery(sessionId = sessionId),
    options = TrackOptions(zoom = 15f, smoothing = Smoothing.SPLINE),
)
```

### 9.1 `TrackOptions`

| Field | Type | Default | What it does |
|---|---|---|---|
| `zoom` | `Float` | `14f` | Selects the arrow spacing tier |
| `includeRawPoints` | `Boolean` | `true` | Emit the `points` array |
| `consolidateStops` | `Boolean` | `true` | Collapse dwell clusters into stop nodes |
| `stopRadiusM` | `Double` | `60.0` | Cluster radius for a stop |
| `stopMinDwellSec` | `Long` | `600` | Minimum dwell to count as a stop |
| `smoothing` | `Smoothing` | `SPLINE` | `NONE`, `BEZIER`, `SPLINE`, `HEADING_SPLINE` |
| `splineSpacingM` | `Double` | `5.0` | Resample spacing for `SPLINE` |
| `bezierMinAngleDeg` | `Double` | `30.0` | Only rounds vertices sharper than this (BEZIER) |
| `bezierCutbackM` | `Double` | `25.0` | Corner cutback distance (BEZIER) |
| `snapToRoad` | `Boolean` | `true` | Use road geometry if a provider is installed. Costs nothing with no provider |
| `snapMaxOffRoadM` | `Double` | `80.0` | Beyond this from the returned road, a fix keeps its captured position |
| `polylinePrecision` | `Int` | `6` | Encoded-polyline precision |
| `speedBandsKmph` | `List<Float>` | `[10f, 20f]` | Thresholds for per-segment speed bands |
| `arrowMinSegmentM` | `Double` | `60.0` | Minimum segment length to place an arrow |
| `simplifyEpsilonM` | `Double` | `2.0` | Douglas-Peucker tolerance before smoothing. `0` disables |

**`Smoothing`**

| Value | Behaviour |
|---|---|
| `NONE` | Chords between stored vertices, exactly as captured |
| `BEZIER` | Round vertices sharper than `bezierMinAngleDeg`; every leg stays a chord |
| `SPLINE` | Centripetal Catmull-Rom through every vertex, resampled. **Default** — a 120 m leg becomes a curve, not a chord |
| `HEADING_SPLINE` | As `SPLINE`, but each vertex's recorded GNSS heading is the curve's tangent there instead of a direction inferred from its neighbours. Turns are the only place it differs, and there it is the difference between drawing the corner and cutting it. Falls back per vertex to the `SPLINE` tangent where no heading was recorded |

### 9.2 `Track` — the output

```kotlin
data class Track(
    val version: Int = 1,
    val sessionId: String? = null,
    val generatedAtMs: Long = 0,
    val from: Long = 0,
    val to: Long = 0,
    val timezone: String = "UTC",
    val precision: Int = 6,              // stated explicitly — never assume 5
    val bounds: Bounds? = null,          // null (never NaN-filled) when there are no points
    val stats: TrackStats = TrackStats(),
    val encodedPolyline: String = "",
    val points: List<TrackJsonPoint> = emptyList(),
    val segments: List<TrackSegment> = emptyList(),
    val stops: List<StopNode> = emptyList(),
    val arrows: List<ArrowAnchor> = emptyList(),
    val warnings: List<String> = emptyList(),
)
```

`warnings` is an open string set: `snap_unavailable`, `coarse_accuracy`,
`mock_locations_present`, `truncated`, `session_interrupted`. **Nothing is ever silently
dropped** — anything omitted is named here.

**`TrackStats`** — `distanceMeters`, `durationSec`, `movingSec`, `stoppedSec`, `maxSpeedMps`,
`avgMovingSpeedMps`, `pointCount`, `stopCount`, `activityBreakdownSec`.

**`TrackSegment`** — `from`/`to` (inclusive indices into `points`), `type` (`TRAVEL` / `STOP`),
`startMs`, `endMs`, `distanceMeters`, `durationSec`, `avgSpeedMps`, `maxSpeedMps`,
`p75SpeedMps`, `activity`, `activityIcon`, `speedBand`, `encodedPolyline`, `stopIndex`.

**`StopNode`** — `index`, `lat`, `lng`, `arrivalMs`, `departureMs`, `dwellSec`, `radiusM`,
`pointCount`, `address`, `isOngoing` (pulse this marker — the session is still open).

**`ArrowAnchor`** — `lat`, `lng`, `bearing`, `segment`. Precomputed so the renderer and the
export cannot disagree about arrow placement.

**`TrackJsonPoint`** — `i` (the index every other array references), `t`, `lat`, `lng`, `acc`,
`spd`, `brg`, `act`, `src`, `mock`.

**`Bounds`** — `north`, `south`, `east`, `west`.

### 9.3 Export

```kotlin
val polylineJson = traker.exportPolylineJson(PointQuery(sessionId = id))
val geoJson      = traker.exportGeoJson(PointQuery(sessionId = id))
```

`exportGeoJson` produces an RFC 7946 `FeatureCollection` — coordinates are `[lng, lat]`.

Encode/decode helpers are public:

```kotlin
val encoded = PolylineCodec.encode(points, precision = 6)
val decoded = PolylineCodec.decode(encoded, precision = 6)

val json  = TrackJson.encode(track)
val back  = TrackJson.decode(json)
```

`PolylineCodec.Encoder` and `PolylineCodec.Decoder` are streaming variants — `add()` /
`snapshot()` and `drain()`.

---

## 10. Live tracking

`liveTrack()` emits one frame per processed fix while a session is active: an append-only
smoothed tail, the re-smoothed last span, and the filter's own position estimate for an
animated puck. It is **conflated** — collectors always see the latest frame and can never slow
capture down.

Use `liveTrack()` for a map that follows the user; use `buildTrack()` for the consolidated,
snapped, segmented historical product.

```kotlin
lifecycleScope.launch {
    traker.liveTrack().collect { update ->
        renderer.render(update)
    }
}
```

### 10.1 `LiveTrackUpdate`

```kotlin
data class LiveTrackUpdate(
    val sessionId: String,
    val sequence: Long,               // monotonic per session run — DROP a frame not newer than the last drawn
    val precision: Int,
    val frozenTailPolyline: String,   // encoded; grows by appending. Never re-smooth it
    val liveHead: List<GeoPoint>,     // the unsettled last span, including both end vertices
    val puck: PuckState?,             // null until the filter seeds
)

data class PuckState(
    val latitude: Double,
    val longitude: Double,
    val speedMps: Float,
    val headingDeg: Double?,          // null when velocity is too small; hold your last rotation
    val accuracyM: Float,             // 1σ uncertainty — the honest halo radius
)
```

`liveHead`'s first vertex is the tail's last, so the two polylines join seamlessly.

### 10.2 Route snapping for the puck

```kotlin
traker.setActiveRoute(routePolylinePoints)   // List<GeoPoint>; pass emptyList() to clear
if (traker.isOffRoute()) offerReroute()
```

This projects the live puck onto the route your app is already navigating — entirely offline,
no provider, no key, no quota. **Only the puck moves.** Stored points and `buildTrack()` are
untouched, because the route is your claim about where the user intends to go, not evidence of
where they were measured.

`isOffRoute()` becomes `true` only after the position misses the route for enough consecutive
fixes to be a wrong turn rather than a multipath spike. Always `false` with no route set.

---

## 11. Geofences

Up to **19** host fences. The SDK's internal stationary wake fence uses a reserved slot and does
not count.

```kotlin
val fence = TrackerGeofence(
    id = "warehouse",
    latitude = 23.0225,
    longitude = 72.5714,
    radiusM = 200f,
    onEnterEvent = "warehouse_enter",
    onExitEvent = "warehouse_exit",
)

when (val result = traker.addGeofence(fence)) {
    is TrackerResult.Ok    -> Unit
    is TrackerResult.Error -> when (result.code) {
        ErrorCode.GEOFENCE_LIMIT_REACHED        -> pruneOldFences()
        ErrorCode.GEOFENCE_REGISTRATION_FAILED  -> retryLater()
        ErrorCode.INVALID_CONFIG                -> fixCoordinates()
        else                                    -> Unit
    }
}
```

Validation: non-blank `id`, latitude in −90..90, longitude in −180..180, `radiusM > 0`.

Crossings arrive as `TrackerEvent.GeofenceEntered` / `GeofenceExited` and are also persisted:

```kotlin
val history: List<TrackerGeofenceEvent> = traker.getGeofenceEvents(
    geofenceId = "warehouse",
    fromMs = startOfDay,
    limit = 100,
)
val deleted: Int = traker.deleteGeofenceEvents(geofenceId = "warehouse")
```

```kotlin
data class TrackerGeofenceEvent(
    val geofence: TrackerGeofence,
    val transition: GeofenceTransition,   // ENTER | EXIT
    val timestampMs: Long,
    val eventName: String,
)
```

Constants: `TrackerGeofence.MAX_GEOFENCES = 19`, `DEFAULT_ID = "trackit-stationary"`,
`DEFAULT_ENTER_EVENT`, `DEFAULT_EXIT_EVENT`.

---

## 12. Battery and sensors

```kotlin
val now: BatteryInfo = traker.batteryInfo()            // reads the platform right now
val live: StateFlow<BatteryInfo> = traker.batteryState()
```

`batteryInfo()` needs no session, no permission and no `ready()` call. It is a binder call —
put it in a refresh, not a per-frame render; collect `batteryState()` for a live display.

```kotlin
data class BatteryInfo(
    val percent: Int? = null,             // null means "we do not know" — never 0 %
    val isCharging: Boolean? = null,
    val powerSource: PowerSource = PowerSource.UNKNOWN,
) {
    val isLow: Boolean                    // percent != null && percent <= 15
}
```

This is the same reading stamped on every stored point, so your display and your uploaded rows
cannot disagree. `TrackerEvent.BatteryChange` carries the same transitions.

```kotlin
val sensors: DeviceSensors = traker.getSensors()
```

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
```

| `MotionQuality` | Meaning |
|---|---|
| `FULL` | Accelerometer + gyroscope + a trigger sensor. Default behaviour |
| `DEGRADED` | Accelerometer present, gyroscope or trigger sensors missing. Stop timeout widens |
| `POOR` | Motion gating is untrustworthy — capture is forced to `CONTINUOUS` and `MOTION_DETECTION_DEGRADED` is reported |

---

## 13. Maps module

`fieldtrack-maps` renders a `Track` and a `LiveTrackUpdate` on a `GoogleMap`. Both renderers are
**main-thread only, not views** — construct where the map lives, call `render()`, call `clear()`
when the map goes away.

### 13.1 `TrackRenderer` — historical track

```kotlin
val renderer = TrackRenderer(googleMap, TrackRenderer.RendererOptions())
renderer.render(track, fitCamera = true)

googleMap.setOnCameraIdleListener {
    if (renderer.needsArrowRefresh()) {
        // rebuild with the new zoom, then render again
    }
}

renderer.clear()
```

**`TrackRenderer.RendererOptions`**

| Field | Default |
|---|---|
| `basePathColor` | `Color.argb(190, 66, 66, 66)` |
| `basePathWidth` | `16f` |
| `speedOverlayWidth` | `16f` |
| `speedOverlayAlpha` | `160` |
| `cameraPaddingPx` | `80` |
| `cameraPaddingFallbackPx` | `50` |
| `arrowSizePx` | `48` |
| `arrowColor` | `Color.WHITE` |
| `showStopMarkers` | `true` |
| `showArrows` | `true` |

### 13.2 `LiveTrackRenderer` — live puck

```kotlin
val live = LiveTrackRenderer(
    googleMap,
    LiveTrackRenderer.Options(cameraFollow = LiveTrackRenderer.CameraFollowMode.FOLLOW_BEARING),
)

lifecycleScope.launch { traker.liveTrack().collect(live::render) }

live.cameraFollow = LiveTrackRenderer.CameraFollowMode.NONE   // switchable at runtime
live.clear()
```

**`LiveTrackRenderer.CameraFollowMode`**

| Value | Behaviour |
|---|---|
| `NONE` | Camera untouched — you own it |
| `FOLLOW` | Centre on the puck, north-up, keeping the user's zoom |
| `FOLLOW_BEARING` | Navigation look: puck-centred, heading-up, tilted |

**`LiveTrackRenderer.Options`**

| Field | Default |
|---|---|
| `tailColor` / `headColor` | `Color.argb(230, 26, 115, 232)` |
| `tailWidth` / `headWidth` | `14f` |
| `puckSizePx` | `56` |
| `puckColor` | `Color.rgb(26, 115, 232)` |
| `showAccuracyHalo` | `true` |
| `haloFillColor` | `Color.argb(26, 26, 115, 232)` |
| `haloStrokeColor` | `Color.argb(90, 26, 115, 232)` |
| `animationDurationMs` | `1_000` — ease duration ≈ the fix interval |
| `lookaheadMs` | `1_000` — dead-reckoning horizon; match `animationDurationMs` |
| `cameraFollow` | `CameraFollowMode.NONE` |
| `followZoom` | `17f` — applied on the first followed frame only |
| `followTilt` | `50f` |

Stale frames (sequence not newer than the last drawn) are dropped automatically.

### 13.3 `ArrowIcons`

```kotlin
ArrowIcons.chevron(sizePx = 48, color = Color.WHITE)
ArrowIcons.numberedPin(/* … */)
ArrowIcons.puck(sizePx = 56, color = Color.rgb(26, 115, 232))
```

---

## 14. Sync module — upload to your backend

`fieldtrack-core` **never opens a socket**. `fieldtrack-sync` does. An app that does not depend
on it gets an offline-first SDK with no network code linked at all.

```kotlin
val sync = TrackerSync.getInstance(context)   // @JvmStatic, idempotent, paired with Tracker.getInstance

sync.configure(
    SyncConfig.builder()
        .baseUrl(BuildConfig.API_BASE_URL)          // "https://api.example.com"
        .path("v1/location/batch")
        .header("Authorization", "Bearer $token")
        .batchSize(100)
        .autoSync(true)
        .build()
)
```

If you set `TrackerConfig.baseUrl`, you can supply only a path here — the base is resolved from
config. An absolute `url` on `SyncConfig` always **wins** over `TrackerConfig.baseUrl`; the base
is a fallback, never an override.

### 14.1 `SyncConfig`

| Field | Type | Default | What it does |
|---|---|---|---|
| `url` | `String` | — | Full endpoint. Must be `https://` (or `http://` for loopback / with `allowCleartext`) |
| `method` | `String` | `"POST"` | HTTP method. **`POST`, `PUT` or `PATCH` only** — Retrofit's verb annotations are compile-time constants |
| `headers` | `Map<String, String>` | empty | Sent on every request. **Never exposed back** — they carry your credential |
| `autoSync` | `Boolean` | `true` | Upload as points arrive. With it off, you call `syncNow()` / `requestSync()` |
| `batchSize` | `Int` | `100` | Rows per request, 1..1000. Larger = fewer requests but a bigger retry unit |
| `requiresUnmeteredNetwork` | `Boolean` | `false` | Only upload on Wi-Fi |
| `gzipRequestBody` | `Boolean` | `false` | Compress the JSON body. Off by default — there is no negotiation for request-body encoding, so a server that does not expect gzip answers 400 |
| `allowCleartext` | `Boolean` | `false` | Permit an `http://` URL. Local development only. Loopback hosts (`localhost`, `127.0.0.1`, `::1`, `10.0.2.2`) are already exempt |
| `timeouts` | `SyncTimeouts` | 5 s / 30 s / 20 s | Applied by the built-in transport; ignored by a custom one |

**Builder**: `.url()`, `.baseUrl()`, `.path()`, `.method()`, `.header(name, value)`,
`.headers(map)`, `.autoSync()`, `.batchSize()`, `.requiresUnmeteredNetwork()`,
`.gzipRequestBody()`, `.allowCleartext()`, `.timeouts(SyncTimeouts)`,
`.timeouts(connectMs, readMs, writeMs)`, `.build()`, `.buildUnchecked()`.

`baseUrl` and `path` are joined with exactly one `/` regardless of which side carries it.

```kotlin
data class SyncTimeouts(
    val connectMs: Long = 5_000,
    val readMs: Long = 30_000,
    val writeMs: Long = 20_000,
)
```

### 14.2 `TrackerSync` API

| Member | Signature | Notes |
|---|---|---|
| `getInstance` | `@JvmStatic fun getInstance(context: Context): TrackerSync` | Idempotent, thread-safe |
| `configure` | `fun configure(config: SyncConfig, transport: SyncTransport? = null)` | Throws `IllegalArgumentException` on an invalid config. Omit `transport` to use the built-in Retrofit-over-OkHttp default |
| `endpoint` | `val endpoint: String?` | Where uploads go, or `null` if unconfigured — or if a 401 tore it down. Headers are deliberately not exposed |
| `isConfigured` | `val isConfigured: Boolean` | Derived from `endpoint`. **Do not cache it** — a 401 clears configuration with no involvement from you |
| `pendingCount` | `suspend fun pendingCount(): Int` | Rows waiting to upload |
| `requestSync` | `fun requestSync()` | Enqueues a network-constrained one-shot via WorkManager. Safe to call often. No-op once halted by a 403 |
| `syncNow` | `suspend fun syncNow(): SyncQueue.Result` | Drains inline in the caller's scope. Prefer `requestSync()` for anything not user-initiated |
| `events` | `val events: SharedFlow<SyncEvent>` | One event per completed exchange, including background drains. Replay 1 |

### 14.3 Results and events

```kotlin
sealed interface SyncQueue.Result {
    data class Uploaded(val count: Int) : Result
    data object Empty : Result
    data class Retry(val reason: String, val retryAfterMs: Long? = null) : Result
    data object AuthExpired : Result     // 401
    data object Forbidden : Result       // 403
}
```

```kotlin
sealed interface SyncEvent {
    data class HttpResponse(val statusCode: Int?, val count: Int) : SyncEvent
}
```

`statusCode` is `null` when **no HTTP response arrived at all** (dead network, DNS failure,
timeout). That is a device problem; a 500 is a server problem — do not report them the same way.
`count` is what was *attempted*, not what was stored.

```kotlin
lifecycleScope.launch {
    sync.events.collect { event ->
        when (event) {
            is SyncEvent.HttpResponse -> showLastUpload(event.statusCode, event.count)
        }
    }
}
```

### 14.4 Terminal failure semantics

| Status | Behaviour |
|---|---|
| **2xx** | Batch accepted and marked synced |
| **401 Unauthorized** | **Terminal.** Tracking is stopped, the upload queue is cleared, and the config is forgotten. The credentials this session was recorded under are gone; keeping the queue would leak the previous user's positions into the next login |
| **403 Forbidden** | **Terminal, but non-destructive.** Uploads halt, **rows are kept**, tracking continues. Recovery is calling `configure()` again with a working credential |
| **Anything else** | Rows stay queued and retry with linear backoff (30 s base) via WorkManager, network-constrained |
| `Retry-After` header | Honoured — the server's own schedule replaces the SDK's |

### 14.5 The wire format

The default payload is `POST` JSON, snake_case keys, epoch milliseconds:

```json
{
  "location": [
    {
      "uuid": "…",
      "time": 1755500000000,
      "local_date": "2026-08-18",
      "latitude": 23.0225,
      "longitude": 72.5714,
      "accuracy": 8.4,
      "movementSpeed": 12.5,
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
      "activity_status": "MOVING",
      "detected_activity_type": "IN_VEHICLE",
      "detected_activity_start_time": 1755499000000,
      "battery_percentage": "62",
      "is_charging": false,
      "is_mock": false
    }
  ]
}
```

Your server should answer **2xx** for accepted, **401** for expired credentials, **403** for a
rejected credential, and any other status to have the batch retried.

`provider` is the location subsystem as it was when the point was captured — providers,
master switch, permission tier (`status`) and accuracy authorization as numeric codes, and
airplane mode. It replaces the provider *name* that this key used to carry; the name is still
available in `activity_status`, which is `"<provider>@<movementStatus>"`. See
[USER-GUIDE §11](docs/USER-GUIDE.md#11-optional-modules) for the full field table.

### 14.6 Custom transport

Supply your own `SyncTransport` to reuse an existing authenticated client — then neither
Retrofit nor OkHttp is linked, and OkHttp is
never linked, and you can remap the payload to whatever your backend expects.

```kotlin
class MyTransport(private val client: OkHttpClient) : SyncTransport {
    override suspend fun upload(request: SyncRequest): SyncResponse = try {
        val response = client.newCall(request.toOkHttp()).execute()
        when (response.code) {
            in 200..299 -> SyncResponse.Success(response.code)
            401         -> SyncResponse.Unauthorized
            403         -> SyncResponse.Forbidden
            else        -> SyncResponse.Failure(response.code, response.message)
        }
    } catch (e: IOException) {
        SyncResponse.Failure(null, e.message ?: "network error")
    }
}

sync.configure(config, MyTransport(myClient))
```

**Implementations must not throw** — a network failure is an expected state, and the queue
depends on being told which of the three it was.

```kotlin
data class SyncRequest(
    val url: String,
    val method: String,
    val headers: Map<String, String>,
    val jsonBody: String,
    val gzip: Boolean = false,
    val timeouts: SyncTimeouts = SyncTimeouts(),
)

sealed interface SyncResponse {
    data class Success(val code: Int) : SyncResponse
    data object Unauthorized : SyncResponse
    data object Forbidden : SyncResponse
    data class Failure(
        val code: Int?,
        val message: String,
        val body: String? = null,        // at most 4096 chars; never logged by the SDK
        val retryAfterMs: Long? = null,
    ) : SyncResponse
}
```

---

## 15. Snap module — road matching

Optional. With no provider installed, `buildTrack()` never leaves the device and never emits a
`snap_unavailable` warning.

```kotlin
traker.setRoadSnapProvider(
    OsrmSnapProvider(baseUrl = "https://osrm.example.com")
)
```

**There is no default `baseUrl` on purpose** — the public OSRM demo server has no availability
guarantee. Point this at your own deployment.

**`OsrmSnapProvider` parameters**

| Parameter | Default | What it does |
|---|---|---|
| `baseUrl` | — | Your OSRM server |
| `profile` | `"driving"` | OSRM profile |
| `client` | built-in OkHttp | Supply your own. Retrofit runs on top of whatever you pass |
| `chunkSize` | provider default | Coordinates per `/match` request |
| `searchRadiusM` | provider default | Search radius per coordinate |
| `headers` | empty | Extra request headers |
| `minConfidence` | provider default | Matchings below this are discarded and keep raw coordinates. `0` accepts everything |
| `cacheEntries` | `ChunkCache.DEFAULT_MAX_ENTRIES` | Matched chunks kept between calls — the whole value when a live map rebuilds the track on every fix. `0` disables |

It **degrades per chunk, never wholesale**: a trace split across ten requests does not lose the
nine that succeeded because the tenth was rate-limited.

### 15.1 Writing your own provider

```kotlin
interface RoadSnapProvider {
    suspend fun snap(path: List<GeoPoint>): List<GeoPoint>
    suspend fun snap(request: SnapRequest): List<GeoPoint> = snap(request.path)   // richer, optional

    object Disabled : RoadSnapProvider
}

data class SnapFix(val point: GeoPoint, val timeMs: Long = 0, val accuracyM: Float = 0f)
data class SnapRequest(val fixes: List<SnapFix>) {
    val path: List<GeoPoint>
    val hasTimestamps: Boolean
}
```

Implementations **must degrade rather than fail**: returning an empty list makes the builder fall
back to raw geometry and emit `snap_unavailable` rather than losing the track. Any exception you
do throw is caught and turned into `ErrorCode.SNAP_UNAVAILABLE` — it is never fatal.

The 80 m `snapMaxOffRoadM` guard means a parallel service road can never relocate the user.

---

## 16. Diagnostics

Three layers, from rawest to most interpreted.

### 16.1 Layer 1 — raw fixes

Requires `persistence.persistRawFixes = true`.

```kotlin
val raw: List<RawFix> = traker.getRawFixes(sessionId)
```

```kotlin
data class RawFix(
    val timeMs: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val bearingDeg: Float,      // 0f when the provider reported no bearing
    val provider: String,
)
```

### 16.2 Layer 2 — raw points

Requires `persistence.persistRawPoints = true`. Every judged fix in point form, accepted or not
— the layer to reach for when the question is "why is there **no** point here" rather than "why
is this point wrong". `RawPoint` has the same columns as `TrackPoint` plus:

| Field | Meaning |
|---|---|
| `verdict` | `"ACCEPT"`, `"SKIP"` or `"REJECT"` |
| `reason` | The `Reasons` vocabulary string |
| `isAccepted` | `verdict == "ACCEPT"` |

`RawPoint.uuid` joins back to the stored `TrackPoint` for accepted fixes.

### 16.3 Layer 3 — the decision log

On by default (`persistence.persistDecisions = true`).

```kotlin
val decisions: List<FixDecision> = traker.getDecisions(sessionId, limit = 200, offset = 0)
```

```kotlin
data class FixDecision(
    val fix: TrackFix,
    val verdict: Verdict,             // Accept | Skip | Reject, each carrying a reason
    val filterLat: Double,
    val filterLng: Double,
    val sigma: Float,                 // how far the fix was, in filter sigmas
    val threshold: Float,             // how wide the gate was
    val distanceMovedM: Double,
    val effectiveSpeedMps: Float,
    val motionState: MotionState,
) {
    val reason: String
    val isAccept: Boolean
}
```

The numeric fields exist so a `Sigma Gate Outlier` can be argued with.

### 16.4 `Reasons` — the reason vocabulary **is API**

These exact strings appear on `TrackPoint.acceptReason`, `RawPoint.reason` and
`FixDecision.reason`. They are stable; changing one is a breaking change.

| Constant | String |
|---|---|
| `INIT` | `Init` |
| `RESUME` | `Resume` |
| `BURST` | `Burst` |
| `NLP_FALLBACK` | `NLP Fallback` |
| `IMPOSSIBLE_SPEED` | `Impossible Speed` |
| `POOR_ACCURACY` | `Poor Accuracy` |
| `RECOVERY_CONFIRMED` | `Recovery Confirmed` |
| `RECOVERY_RESET` | `Recovery Reset` |
| `RECOVERY_HELD` | `Recovery Held` |
| `SIGMA_GATE_OUTLIER` | `Sigma Gate Outlier` |
| `SIGMA_FORCED_RESET` | `Sigma Forced Reset` |
| `SIGMA_JUNK_FAIL` | `Sigma Junk Fail` |
| `VEHICULAR` | `Vehicular` |
| `MOVING_WALKING` | `Moving/Walking` |
| `INDOOR_ARRIVAL` | `Indoor Arrival` |
| `BEARING_CHANGE` | `Bearing Change` |
| `CORNER_ANCHOR` | `Corner Anchor` |
| `ARRIVAL` | `Arrival` |
| `STATIONARY_RECOVERY` | `Stationary Recovery` |
| `BLACKOUT_ARRIVAL` | `Blackout Arrival` |
| `WALK_ARRIVAL` | `Walk Arrival` |
| `HEARTBEAT` | `15-Min Heartbeat` |
| `ORIGIN_SET` | `Origin Set` |
| `DEPARTURE_HELD` | `Departure Held` |
| `DRIFT_SUPPRESSED` | `Drift Suppressed` |
| `HEARTBEAT_SKIPPED` | `HeartBeat Skipped` |
| `HEURISTIC_GATE` | `Heuristic Gate` |
| `SESSION_CLOSED` | `Session Closed` |
| `MOCK_LOCATION` | `Mock Location` |
| `INVALID_COORDINATES` | `Invalid Coordinates` |
| `STALE_FIX` | `Stale Fix` |
| `REBOOT_BOUNDARY` | `Reboot Boundary` |
| `OUT_OF_ORDER` | `Out Of Order` |

---

## 17. Java interop

Every entry point is Java-callable. `getInstance`, `TrackerConfig.builder()` and
`SyncConfig.builder()` are `@JvmStatic`; `PointQuery`, `TrackOptions` and the paged query
methods carry `@JvmOverloads`.

```java
Tracker traker = Tracker.getInstance(context);

TrackerConfig config = TrackerConfig.builder()
        .provider(LocationProviderType.GPS_ONLY)
        .accuracyProfile(AccuracyProfile.STRICT)
        .intervalMs(30_000L)
        .notification("Tracking", "Recording your route")
        .build();
```

`suspend` functions need a coroutine. From Java, call them from Kotlin glue, or wrap them in
your own `CoroutineScope` helper. Flows are consumed the same way.

`TrackerConfig.Builder.build()` and `SyncConfig.Builder.build()` throw
`IllegalArgumentException` on an invalid config — use `buildUnchecked()` plus `validate()` if
you are assembling config from untrusted input.

---

## 18. ProGuard / R8

**You do not need to add any rules.** Each AAR ships `consumer-rules.pro` and the published
artifacts are already R8-minified.

What this means in practice:

- Public API types and the documented extension seams (`TrackLogger`, `RoadSnapProvider`,
  `SyncTransport`) keep their names.
- Model classes (`Track`, `TrackOptions`, `TrackSegment`, `TrackStats`, `TrackJsonPoint`,
  `StopNode`, `ArrowAnchor`, `LiveTrackUpdate`, `PuckState`, `SegmentType`, `Smoothing`, …) keep
  public class and member names, so named accessors survive.
- Enum constants are preserved — persisted rows and wire values use `name`/`valueOf`.
- SDK logging is compiled out of release builds entirely.
- No sources JAR is published; a Javadoc JAR with rendered public API HTML is.

If you hit a `NoSuchMethodError` or a serialization failure after enabling minification in your
own app, that is a bug worth reporting — do **not** paper over it with
`-keep class com.field360.tracker.** { *; }`, which would disable shrinking for the whole SDK
inside your APK.

---

## 19. Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `ready()` returns `LICENSE_MISSING` | Release build with no token | Add the `TrackItLicense` manifest meta-data or `.license(...)` in config ([§2](#2-license-token)) |
| `start()` returns `NOT_READY` | `ready()` not called or it failed | Check the `TrackerResult` from `ready()` |
| `start()` returns `PERMISSION_DENIED` | No location permission | Walk the ladder in [§4](#4-permissions) |
| `start()` returns `PLAY_SERVICES_UNAVAILABLE` | No Google Play Services | Set `providerType = GPS_ONLY` |
| Empty track, no points at all | `NETWORK_ONLY` with a tight accuracy ceiling | `validate()` rejects this — use `AccuracyProfile.RELAXED` or `CUSTOM` ≥ 50 m |
| Config changes do nothing | `reset = false` with a persisted config | Set `reset = true` (the default) during development |
| Very few points while stationary | Working as designed — the data-plane heartbeat warms the filter without storing | Set `persistHeartbeat = true` if you want them stored |
| Zigzag / drift while stationary | Accuracy ceiling too loose | `AccuracyProfile.STRICT`, or a `CUSTOM` ceiling |
| Corners drawn as straight chords | Turn fidelity settings off | Keep `turnBurst = true`, `useGyroTurnPrediction = true`, `cornerAnchorCapture = true`, `bearingChangeCaptureDeg = 30`; use `smoothing = HEADING_SPLINE` where the fixes carry a GNSS bearing |
| Navigation "randomly stops" | 1 Hz stream with no foreground service | `navigationMode` requires `service.foregroundService` — `validate()` enforces it |
| Tracking ends when the user swipes the app away | `stopOnTerminate = true` | Leave it `false` (the default) |
| Uploads retry forever | `http://` URL blocked by Android's default network security policy | Use `https://`, or `allowCleartext = true` for a local dev server |
| Uploads stopped, rows still queued | A 403 halted the queue | Call `sync.configure(...)` again with a working credential |
| Tracking stopped and the queue emptied | A 401 tore everything down | Re-authenticate, then `ready()` / `start()` / `configure()` again |
| `SNAP_UNAVAILABLE` in `warnings` | Your snap provider could not answer | Never fatal — the raw track is drawn. Check the OSRM server |
| `MOTION_DETECTION_DEGRADED` | `motionQuality = POOR` on this hardware | Capture is forced to `CONTINUOUS`; expect more battery use |
| Live map jumps backwards | Drawing a stale frame | Drop any `LiveTrackUpdate` whose `sequence` is not newer than the last drawn |
