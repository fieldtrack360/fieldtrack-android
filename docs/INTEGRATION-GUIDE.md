# FieldTrack SDK — Integration Guide

<p align="center">
<a href="https://jitpack.io/#fieldtrack360/fieldtrack"><img src="https://jitpack.io/v/fieldtrack360/fieldtrack.svg" alt="JitPack version"/></a>
</p>

Background location tracking and track plotting for Android. This document is the complete
public reference for an app integrating the SDK: install, setup, every configuration option,
every public method, every event and callback.

**Android only. Kotlin-first, Java-callable.**

| | |
|---|---|
| Maven group | `com.github.fieldtrack360.fieldtrack` |
| Distribution | JitPack (`https://jitpack.io`) |
| `minSdk` | 26 (Android 8.0) |
| `compileSdk` / `targetSdk` | 36 |
| Java bytecode | 11 — loads on any JDK 11+ toolchain |
| Kotlin | 2.1.x |
| Host baseline | AGP 8.x · Kotlin 2.0+ · React Native 0.81+ compatible |

---

## Table of contents

1. [Install](#1-install)
2. [License token](#2-license-token)
3. [Quick start](#3-quick-start)
4. [Permissions](#4-permissions)
5. [Configuration reference](#5-configuration-reference)
6. [Public API — `Tracker`](#6-public-api--tracker)
7. [Events, state and callbacks](#7-events-state-and-callbacks)
8. [Data models](#8-data-models)
9. [Plotting and export](#9-plotting-and-export)
10. [Live tracking](#10-live-tracking)
11. [Geofences](#11-geofences)
12. [Battery and sensors](#12-battery-and-sensors)
13. [Maps module](#13-maps-module)
14. [Sync module — upload to your backend](#14-sync-module--upload-to-your-backend)
15. [Log module — diagnostics to your backend](#15-log-module--diagnostics-to-your-backend)
16. [Snap module — road matching](#16-snap-module--road-matching)
17. [Diagnostics](#17-diagnostics)
18. [Java interop](#18-java-interop)
19. [ProGuard / R8](#19-proguard--r8)
20. [Device integrity](#20-device-integrity)
21. [Troubleshooting](#21-troubleshooting)

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
implementation("com.squareup.okhttp3:okhttp:5.1.0")
```

Retrofit 3 requires OkHttp 5 — they are versioned together, not independently.

You can skip both entirely by supplying your own `SyncTransport` (see
[§14.6](#146-custom-transport)) or your own `RoadSnapProvider` (see
[§16](#16-snap-module--road-matching)).

**`fieldtrack-core` is different.** It links Retrofit, OkHttp, Gson and Tink as real
dependencies, because the licence check has to work in a host that brought no HTTP client
of its own. There is no opt-out, deliberately: a licensing layer an integrator could
disable by omitting a dependency would not be a licensing layer.

That OkHttp is version 5, and it will win over an older one already in your build — Gradle
resolves a conflict by taking the highest version. Upgrading `okhttp` on its own is not
always safe, because OkHttp 5 deleted internal classes that OkHttp 4 siblings call. The one
that bites in practice is `okhttp-urlconnection`: React Native ships it at 4.x for its
cookie jar, and 4.x `JavaNetCookieJar` calls the removed `okhttp3.internal.Util`, so the
first cookie-bearing request dies with

```
java.lang.NoClassDefFoundError: Failed resolution of: Lokhttp3/internal/Util;
    at okhttp3.JavaNetCookieJar.decodeHeaderAsJavaNetCookies(JavaNetCookieJar.kt:81)
```

`fieldtrack-core` publishes a constraint that pulls `okhttp-urlconnection` up to match, so
an ordinary Gradle build needs nothing from you. If your build forces OkHttp versions
itself — React Native's Gradle plugin is able to — a `force` beats our constraint and you
have to align the family yourself:

```kotlin
implementation(platform("com.squareup.okhttp3:okhttp-bom:5.1.0"))
```

The rule is the same either way: move every `com.squareup.okhttp3` artifact together, never
one alone.

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

### 1.6 Toolchain compatibility

The published AARs are deliberately built against a conservative baseline so that
mainstream host toolchains — including React Native 0.81+ Android hosts — can consume
them without upgrading anything:

| The AARs ship | Your project needs |
|---|---|
| Kotlin 2.1.x metadata | Kotlin 2.0 or newer |
| `compileSdk` 36 | `compileSdk` 36 or newer |
| Java 11 bytecode | Any JDK 11+ toolchain (JDK 17 recommended) |
| AGP 8.x metadata | AGP 8.x or newer |

`play-services-location` 21.3.x and (for `fieldtrack-maps`) `play-services-maps` 19.2.x
arrive transitively; declaring a newer version in your own app wins through normal Gradle
conflict resolution.

### 1.7 Recommended: take WorkManager off your cold-start path

**Strongly recommended for every host, and it is a manifest block with no Kotlin behind
it.** Add this inside `<application>` in your app's `AndroidManifest.xml`:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">
    <application>

        <provider
            android:name="androidx.startup.InitializationProvider"
            android:authorities="${applicationId}.androidx-startup"
            android:exported="false"
            tools:node="merge">
            <meta-data
                android:name="androidx.work.WorkManagerInitializer"
                android:value="androidx.startup"
                tools:node="remove" />
        </provider>

    </application>
</manifest>
```

#### Why

WorkManager installs itself through `androidx.startup`'s `ContentProvider`, which Android
runs during **Application attach — before `Application.onCreate`, on the main thread, on
every cold start of your process.**

For most apps that is only a slow start. For this SDK it is a crash risk. `TrackingService`
is started with `startForegroundService()`, and the platform's **~10 second
start-foreground deadline opens at that call**, not when the service runs. The process very
often starts cold in answer to it — a sticky restart, a boot resume, an OEM revival — so
every ContentProvider in the merged manifest runs inside that window before the service is
even constructed. Overrun it and the platform kills the app with:

```
Fatal Exception: android.app.RemoteServiceException$ForegroundServiceDidNotStartInTimeException
Context.startForegroundService() did not then call Service.startForeground()
```

That crash is thrown by the system on your main looper. The SDK cannot catch it. Every
millisecond removed from Application attach is budget handed back to the promotion.

#### You do **not** need `Configuration.Provider`

Removing that initializer normally forces your `Application` to implement
`androidx.work.Configuration.Provider`, because `WorkManager.getInstance()` throws once
nothing has initialised it. With this SDK it does not: the SDK routes every handle through
its own accessor, which initialises WorkManager with the identical default configuration
the first time it needs one — off the cold-start path, on whatever thread asked.

#### The one case where you should not do this

If **your own app code** calls `WorkManager.getInstance()` — you schedule your own workers —
then whichever of you touches WorkManager first wins, and your call can throw. In that case
either leave the block out, or add it *and* implement `Configuration.Provider` on your
`Application` the way AndroidX documents. Both are safe; the SDK works either way.

This is why the SDK does **not** ship the removal in its own merged manifest: deleting a
dependency's initializer out from under a host would break hosts that were relying on it.

`sample-android/src/main/AndroidManifest.xml` carries the block as a worked reference.

---

## 2. License token

Release builds require a license token. Debuggable builds are **waived automatically** — you
can develop with no token at all.

> New to how this works? [`how-the-local-licence-works.md`](how-the-local-licence-works.md)
> explains the offline check in plain English, with no prior knowledge assumed.
```kotlin
tracker.ready(
    TrackerConfig.builder()
        .license(BuildConfig.FIELDTRACK_LICENSE)
        .build()
)
```

Keep the token out of source control. `local.properties` is gitignored and already carries
the sample's Maps key:

```properties
# local.properties
FIELDTRACK_LICENSE=TRACKIT-eyJ2IjoxLCJraWQiOjEs…
```

```kotlin
// your app's build.gradle.kts
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }
        ?.inputStream()?.use { load(it) }
}
buildConfigField(
    "String", "FIELDTRACK_LICENSE",
    "\"${localProperties.getProperty("FIELDTRACK_LICENSE", "")}\"",
)
```

This keeps the token out of the repository, not out of the APK — it is compiled into
`BuildConfig` and readable by anyone who unzips your build. That is expected: the token is
bound to your application id and signed, so a copy of it is worth nothing in another app.
It is still worth what you paid, so do not commit it.

The token is bound to your application id. `ready()` returns a `TrackerResult.Error` with
`LICENSE_MISSING`, `LICENSE_INVALID` or `LICENSE_BUNDLE_MISMATCH` when the offline check
fails, and the same failure is emitted on the event flow as `TrackerEvent.Error`.

The `license` field is never persisted with the rest of the config — it is re-read from
config on every `ready()`, so "I updated my licence" never turns into a stale token
resurrected from disk.

### The online check

Beyond the offline gate, the SDK asks the licence server whether the token has been
**revoked or expired** since it was issued. You do not wire anything up for this.

| | |
|---|---|
| **When** | Shortly after every `ready()`, and every 12 hours while installed |
| **Blocking?** | **No.** `ready()` decides from a cached verdict and returns; the call runs unawaited |
| **Offline?** | Carries on. Fail-open by design — a server outage never stops a paying customer |
| **You see** | `TrackerEvent.LicenseChecked`, `tracker.licenseInfo()`, `tracker.checkLicense()` |

```kotlin
tracker.events
    .filterIsInstance<TrackerEvent.LicenseChecked>()
    .onEach { Log.i("licence", "${it.info.status} cached=${it.info.fromCache}") }
    .launchIn(scope)

// any time, no network cost:
when (tracker.licenseInfo()?.status) {
    LicenseStatus.ACTIVE -> Unit
    null -> Unit                      // not checked yet — NOT a refusal
    else -> showLicenceBanner()
}
```

**Silence is not success.** No event is emitted when the network failed or the response
could not be verified — all of those carry on tracking and report nothing, because reading
silence as approval would mean reading a server outage as a valid licence. A successful
`ready()` likewise means "no cached verdict said stop", not "the licence was just checked".

| Member | Type | Meaning |
|---|---|---|
| `status` | `LicenseStatus` | `ACTIVE`, `REVOKED`, `EXPIRED`, `UNKNOWN_KEY`, `INVALID_KEY`, `PACKAGE_MISMATCH`, `SDK_MISMATCH`, `UNRECOGNISED` |
| `valid` | `Boolean` | The server's own flag. Branch on `status`, not this |
| `packageName` | `String` | The application id the licence was issued against |
| `checkedAt` | `String` | ISO-8601, the server's clock, verbatim |
| `ttlSeconds` | `Long` | How long this answer may keep being trusted |
| `reason` | `String?` | The server's explanation, when it sent one |
| `fromCache` | `Boolean` | `true` for a stored verdict. Re-verified on read, so no less trustworthy |

Only `REVOKED` and `EXPIRED` stop tracking. The rest are diagnostics — `UNKNOWN_KEY` and
`INVALID_KEY` mean the token verified offline against a key we compiled in ourselves and
the backend had no matching record, which is our ledger being wrong, not your licence.

---

## 3. Quick start

Three calls: `getInstance` → `ready` → `start`.

```kotlin
class MyApplication : Application() {

    val tracker: Tracker by lazy { Tracker.getInstance(this) }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            when (val result = tracker.ready(TrackerConfig())) {
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
    when (val result = tracker.start(tag = "commute")) {
        is TrackerResult.Ok    -> Log.d("app", "session ${result.value.id}")
        is TrackerResult.Error -> Log.w("app", "${result.code}: ${result.message}")
    }
}

suspend fun end() {
    tracker.stop()
}
```

Read the data back:

```kotlin
val points   = tracker.getPoints(PointQuery(sessionId = sessionId))
val track    = tracker.buildTrack(PointQuery(sessionId = sessionId))
val json     = tracker.exportPolylineJson(PointQuery(sessionId = sessionId))
val distance = tracker.getOdometerMeters()
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
val permissions: PermissionManager = tracker.permissions()
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

`tracker.permissionTier()` is a shortcut for `permissions().tier()`.

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

tracker.ready(config)
```

### 5.1 Top-level `TrackerConfig`

| Field | Type | Default | What it does |
|---|---|---|---|
| `geolocation` | `GeolocationConfig` | defaults | Provider, accuracy, cadence |
| `motion` | `MotionConfig` | defaults | Activity recognition, stop detection, heartbeat |
| `service` | `ServiceConfig` | defaults | Foreground service, notification, survival |
| `persistence` | `PersistenceConfig` | defaults | Retention and diagnostic storage |
| `sensors` | `SensorConfig` | defaults | Hardware motion assists |
| `security` | `SecurityConfig` | defaults | Device-integrity policy — accessibility, developer mode, hooking frameworks, clock tampering, mock-location apps ([§20](#20-device-integrity)). Waived entirely in debuggable builds |
| `license` | `String?` | `null` | Release license token. Never persisted |
| `baseUrl` | `String?` | `null` | Scheme + host for uploads, e.g. `https://api.example.com`. Core never opens a socket; `fieldtrack-sync` resolves a relative path against it |
| `reset` | `Boolean` | `true` | `true` — this config is applied on top of factory defaults. `false` — the persisted config wins and this object is **ignored after the first launch**; only `setConfig()` changes anything after that. **Leave `true` during development** |

Builder methods for the whole blocks: `.geolocation()`, `.motion()`, `.service()`,
`.persistence()`, `.sensors()`, `.security()`, `.license()`, `.baseUrl()`, `.reset()`.

`config.validate(): List<String>` returns everything wrong with a config, or an empty list.
`ready()` runs it and returns `ErrorCode.INVALID_CONFIG` with the joined messages.

### 5.2 `GeolocationConfig`

| Field | Type | Default | What it does |
|---|---|---|---|
| `trackingMode` | `TrackingMode` | `ADAPTIVE` | See below. **Can be overridden at `ready()`** on hardware that cannot detect motion — [§12.1](#121-motion-hardware-and-what-it-can-override) |
| `providerType` | `LocationProviderType` | `FUSED` | Which hardware produces fixes |
| `desiredAccuracy` | `DesiredAccuracy` | `HIGH` | Biases the *fused* provider's own source choice. `HIGH`, `BALANCED`, `LOW` |
| `accuracy` | `AccuracyConfig` | `BALANCED` profile | The accuracy meter — see [§5.6](#56-accuracyconfig) |
| `distanceFilterM` | `Float` | `0f` | **Must stay 0.** A non-zero OS distance filter generates stationary drift; all thinning is done in software |
| `intervalMs` | `Long` | `60_000` | Requested sampling interval |
| `fastestIntervalMs` | `Long` | `30_000` | Fastest the OS may deliver. Must be ≤ `intervalMs` |
| `maxUpdateDelayMs` | `Long` | `60_000` | OS batching window |
| `maxFixAgeMs` | `Long` | `10_000` | Older fixes are treated as stale |
| `deliveryStalenessMs` | `Long` | `60_000` | Delivery-gap threshold |
| `adaptiveCadence` | `Boolean` | `true` | Speed up while vehicular. Off pins every moving fix to `intervalMs` |
| `vehicularIntervalMs` | `Long` | `12_000` | The vehicular tier interval. Raised by **measured speed** (≥ 2.5 m/s ≈ 9 km/h), not by the motion state — a walking session stays on `intervalMs` |
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

**When each tier is in force** (fastest wins: navigation → turn burst → vehicular → base):

| Tier | Raised by | Dropped by |
|---|---|---|
| vehicular | A fix reporting **≥ 2.5 m/s** — the same threshold the turn burst and the gyroscope use. Not the `MOVING` motion state: walking is moving and is not vehicular | `STOP_PENDING` — the moment fixes stop reporting movement, not `stopTimeoutMin` later |
| turn burst | `TurnDetector` (GNSS heading) or `GyroTurnMonitor` (yaw rate), whichever sees the corner first | Both releasing it, or a stop |

A tier dropped at `STOP_PENDING` is *parked*, not forgotten: pulling away restores it on the
`MOVING` transition rather than waiting for another fix to measure speed. A committed stop
(`STATIONARY`) clears the claim, so whatever moves next earns the tier from speed again.

The battery difference between the three modes is mostly *not* the interval — it is whether
the location stream stays registered while the device is parked. `ADAPTIVE` keeps it registered
and lets the filter thin, because the heartbeat is what recovers a device whose wake paths
all failed; `MOTION_ONLY` genuinely unregisters it. A phone parked for eight hours costs
near zero on the third and a full day of radio duty on the first two.

**The mode you set is not always the mode that runs.** On a device where motion detection
cannot be trusted, `ready()` rewrites this to `CONTINUOUS` — which is the most expensive
mode short of `navigationMode`, so a host that chose `MOTION_ONLY` for battery gets the
opposite. Read `TrackerState.effectiveTrackingMode` for what is actually in force, and
[§12.1](#121-motion-hardware-and-what-it-can-override) for when and why.

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
| `suppressWhileStationary` | `Boolean` | `false` | Drop points that only stationary drift explains, when the **accelerometer** agrees the device has not moved — see [§12.3](#123-points-from-a-device-that-is-not-moving). A veto, never a trigger: it can only remove a point the pipeline already read as stationary, is not consulted once displacement or Doppler read as moving, and one counted step withdraws it. Needs an accelerometer; turned off with a `Diagnostic` where there is none |
| `stillnessEscapeMin` | `Int` | `30` | How long `suppressWhileStationary` may suppress before letting one fix through regardless. The safety valve — a wedged accelerometer degrades to the previous behaviour instead of silencing a shift |
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
`.snapshotConfidenceMin()`, `.suppressWhileStationary()`, `.stillnessEscapeMin()`,
`.stopTimeoutMin()`,
`.stationaryRadiusM()`, `.stationaryGeofenceId()`, `.stationaryGeofenceOnEnterEvent()`,
`.stationaryGeofenceOnExitEvent()`, `.motionTriggerDelayMs()`, `.heartbeatIntervalSec()`,
`.persistHeartbeat()`, `.bearingChangeCaptureDeg()`, `.cornerAnchorCapture()`.

### 5.4 `SensorConfig`

| Field | Type | Default | What it does |
|---|---|---|---|
| `useSignificantMotion` | `Boolean` | `true` | Permission-free, ~zero-power hardware wake for STATIONARY → MOVING |
| `useStepCorroboration` | `Boolean` | `true` | Step-count veto on stationary drift; confirms indoor walks |
| `useBarometer` | `Boolean` | `false` | Use pressure sensor when present |
| `stepBatchLatencyMs` | `Long` | `60_000` | Step-counter batching latency |
| `useGyroTurnPrediction` | `Boolean` | `true` | Arm the turn burst from gyroscope yaw rate, ahead of GNSS heading. Needs no permission. The gyroscope is opened only while fixes report vehicular speed and released within a minute of them stopping, so a walking or parked session never touches it. No-op when `geolocation.turnBurst` is off or the device has no gyroscope |

Builder: `.useSignificantMotion()`, `.useStepCorroboration()`, `.useGyroTurnPrediction()`,
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
| `notificationTitle` | `String` | `"Tracking active"` | Foreground notification title. **Never overridden** — the sync diagnostic below cannot take this slot |
| `notificationText` | `String` | `"Recording your location"` | Foreground notification body |
| `notificationChannelId` | `String` | `"trackit_tracking"` | Channel id |
| `notificationChannelName` | `String` | `"Location tracking"` | Channel name shown in system settings |
| `notificationSmallIconResName` | `String?` | `null` | Drawable **resource name** (e.g. `"ic_tracking"`) for the small icon |
| `showSyncStatusInNotification` | `Boolean` | `false` | **Diagnostic — leave off in a shipping app.** Layers a live upload-queue line onto the subtitle and body while tracking; the title is untouched. See below |
| `syncNotificationSubText` | `String?` | `null` | The **subtitle** shown beside `notificationTitle` while the sync line is on screen. `null` = no subtitle. Rendered with `setSubText`; never replaces the title |
| `syncNotificationText` | `String` | `"unsynced {pending} · last upload {age}"` | The sync line template. See the token table below. Ignored unless `showSyncStatusInNotification` is on |

Builder: `.foregroundService()`, `.stopOnTerminate()`, `.startOnBoot()`, `.healthLoopMs()`,
`.watchdogIntervalMs()`, `.watchdogThrottleMs()`, `.backstopIntervalMin()`,
`.deadTrackerMovingMin()`, `.deadTrackerStationaryMin()`, `.wakeLockMs()`,
`.notification(title, text)`, `.notificationChannel(id, name)`, `.notificationSmallIconResName()`,
`.showSyncStatusInNotification()`, `.syncNotification(subText, text)`.

#### The upload-status notification

Off by default, and it should stay off in a shipping app. The ongoing notification is the one
piece of SDK surface a real user reads, and `unsynced 42` means nothing to them while meaning
something alarming.

What it is *for* is the one test that cannot be run from inside the app: kill the host, take the
device offline, wait, restore connectivity, and confirm the queue drains — without launching
anything, because launching the app is itself a sync trigger and would invalidate the test. The
notification is the only readout that survives that, and it needs no debugger, no adb and no
server-side check.

It occupies **the subtitle and the body, never the title.** The notification has three text
slots and the host keeps the one that matters:

```
┌──────────────────────────────────────┐
│ Tracking active   ·   upload         │   ← notificationTitle · syncNotificationSubText
│ unsynced 42 · last upload 21m ago    │   ← syncNotificationText
└──────────────────────────────────────┘
```

With the diagnostic off — or on with no `syncNotificationSubText` set — there is no subtitle at
all and the body is `notificationText`. The title reads the same either way, because it names
the app holding the foreground service and a debug readout must not take that line.

`syncNotificationText` is substituted at post time:

| Token | Becomes |
|---|---|
| `{pending}` | Rows queued and not yet uploaded, e.g. `42` |
| `{age}` | Time since the last confirmed upload, e.g. `21m ago`, or `never` |

Both tokens are optional and may appear in any order — `"{pending} to upload"` is a valid
template, as is a static string with neither. An unrecognised `{token}` is left exactly as
written rather than blanked, so a typo shows up on the notification as itself instead of
silently vanishing.

Keep both defaults unless you have a reason not to. The count alone cannot tell a draining queue
from one that is merely not growing — a parked device stores nothing, so a still count is the
*expected* reading, not a stalled one. `{age}` is what separates them: it resets the moment
anything reaches the server.

Two more things to know before reading the number:

- It refreshes on the `watchdogIntervalMs` tick, so it lags reality by up to that long. A count
  that has not moved for one tick has not necessarily stalled.
- The line only appears while sync is actually configured. With no `configure()` call — or after
  a terminal `401` / `403` clears the config mid-session — the subtitle disappears and the body
  reverts to your own `notificationText`. The title never moved, so the notification simply looks
  as it did before the diagnostic was on. See [§14.4](#144-terminal-failure-semantics).

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
- `stillnessEscapeMin <= 0` while `suppressWhileStationary` is on — checked only when the
  stage is running, since the bound is a safety valve and zero would mean "never expire"
- `baseUrl` that is not an absolute URL with a scheme and host
- `turnBurstIntervalMs <= 0`, or greater than the tier it accelerates
- `navigationIntervalMs <= 0`, `navigationIntervalMs < navigationFastestIntervalMs`, or
  `navigationMode` without `foregroundService`
- `maxDaysToPersist < 0`
- `maxAccuracyMeters` set without `CUSTOM`, missing with `CUSTOM`, or outside 5–500 m
- `recoveryTrustMeters <= 0`
- `NETWORK_ONLY` with an accuracy ceiling below 50 m
- `navigationMode` with `providerType = PASSIVE`
- blank `syncNotificationText` while `showSyncStatusInNotification` is on — checked only when
  the line will actually be posted, so leaving the diagnostic off is never refused over a
  string nothing reads
- `syncNotificationSubText` set to a blank string — use `null` for no subtitle

---

## 6. Public API — `Tracker`

```kotlin
val tracker = Tracker.getInstance(context)   // @JvmStatic, idempotent, thread-safe
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
    tracker.events.collect { event ->
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
            is TrackerEvent.IntegrityChange    -> integrity(event.report)
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
| `ProviderChange` | `state: ProviderState`, `previous: ProviderState?` | GPS toggle, permission change, granularity change, battery saver. `previous` is the snapshot this one replaced — diff the two to find which field moved. `null` on the first reading after `ready()`, which is a starting position rather than a transition |
| `PermissionChange` | `previous: PermissionTier`, `current: PermissionTier`, `accuracy: LocationAccuracy` | The location grant moved, in either direction — revoke, re-grant, all-the-time→while-using, precise→approximate |
| `LocationServicesChange` | `enabled: Boolean`, `state: ProviderState` | The GPS/location master switch was toggled. Both directions, including the recovery |
| `CaptureSuspended` | `reason: ErrorCode`, `message: String` | Capture stopped but the session is **still open**: permission revoked, or every provider off |
| `CaptureResumed` | — | Capture re-armed in the same session after a `CaptureSuspended` |
| `Heartbeat` | `atMs: Long` | **Control-plane** liveness tick (distinct from the data-plane heartbeat) |
| `PowerSaveChange` | `enabled: Boolean` | Battery saver on/off |
| `BatteryChange` | `battery: BatteryInfo` | Plug, unplug, low, okay — and drift the capture path notices |
| `GeofenceAdded` / `GeofenceRemoved` | `geofence` / `geofenceId` | Registry changed |
| `GeofenceEntered` / `GeofenceExited` | `geofence: TrackerGeofence` | A fence was crossed |
| `IntegrityChange` | `report: IntegrityReport` | The device-integrity flag set changed — transitions only, not every check ([§20](#20-device-integrity)) |
| `SessionInterrupted` | `session: TrackSession` | `ready()` found a session left open by a crash or force-stop — you decide what to do |
| `Diagnostic` | `message: String` | Informational |
| `Error` | `code: ErrorCode`, `message: String` | Anything the SDK wants you to know about |

Collect from a lifecycle scope for UI, or from an application-scoped one for work that must
continue with no UI on screen.

**`events` has no replay.** It is a `SharedFlow` with `replay = 0`, so an event emitted
while nothing is collecting is gone — it is a stream of things that happened, not a record
of the current state. That matters for one event in particular:
`MOTION_DETECTION_DEGRADED` is emitted **inside `ready()`**, and the documented startup
order has `ready()` in `Application.onCreate` with your collector created later in an
Activity or view model. If you follow that order you will never see it.

Nothing is lost, because the condition it reports is on the state flow instead — read
`TrackerState.motionQuality` ([§7.2](#72-trackerstate)), which always has a current value
however late you subscribe. The general rule: **conditions live on `TrackerState`,
transitions live on `events`.** If you need an event that fires during `ready()`, start
collecting before you call it.

### 7.2 `TrackerState`

```kotlin
data class TrackerState(
    val isReady: Boolean = false,
    val isTracking: Boolean = false,
    val isCapturing: Boolean = false,          // false while isTracking = suspended
    val motionState: MotionState = MotionState.STOPPED,
    val providerState: ProviderState = ProviderState(),
    val currentSessionId: String? = null,
    val motionQuality: MotionQuality = MotionQuality.FULL,
    val effectiveTrackingMode: TrackingMode = TrackingMode.ADAPTIVE,
)
```

| Field | Read it for |
|---|---|
| `isTracking` vs `isCapturing` | A session with a revoked permission or a switched-off GPS stays open with `isTracking = true` and stops capturing. `false` while tracking means **suspended**, and the reason is on `TrackerEvent.CaptureSuspended` |
| `motionQuality` | Whether this device's motion hardware can support the mode you asked for. The reliable read — the event that reports it fires during `ready()` and is easily missed ([§12.1](#121-motion-hardware-and-what-it-can-override)) |
| `effectiveTrackingMode` | The mode actually in force, which differs from the configured one when the SDK overrode it. Nothing else exposes the resolved config |

Both `motionQuality` and `effectiveTrackingMode` are set by `ready()` and do not change
until the next `ready()` call.

### 7.3 `ProviderState`

```kotlin
data class ProviderState(
    val gpsEnabled: Boolean = false,
    val networkEnabled: Boolean = false,
    val locationServicesEnabled: Boolean = false,   // the Settings master switch
    val permission: PermissionTier = PermissionTier.NONE,
    val accuracyAuthorization: LocationAccuracy = LocationAccuracy.APPROXIMATE,
    val fusedAvailable: Boolean = false,
    val powerSaveMode: Boolean = false,
    val airplaneMode: Boolean = false,
)
```

`locationServicesEnabled` is **not** the union of `gpsEnabled` and `networkEnabled`: a device
can report location enabled with GPS switched off, and the master switch is what a
"turn location on" prompt should be driven by.

`airplaneMode` is a diagnostic, never a gate — GPS keeps working in airplane mode on most
devices while network positioning does not, so it explains a track that degrades to GPS-only
or stops indoors rather than justifying a refusal to start.

Both are emitted on change through `TrackerEvent.ProviderChange`, like the rest of this
object; there is no polling.

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
| `LICENSE_MISSING` / `LICENSE_INVALID` / `LICENSE_BUNDLE_MISMATCH` | Offline license gate, in `ready()` |
| `LICENSE_REVOKED` / `LICENSE_EXPIRED` | Online check. **Stops tracking** |
| `LICENSE_UNKNOWN` / `LICENSE_PACKAGE_MISMATCH` / `LICENSE_SDK_MISMATCH` | Online check. Diagnostic only — tracking continues |
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
    val integrityFlags: Int = 0,         // device-integrity bitmask at capture — see §20.4
    val providerFlags: Int = 0,          // location-subsystem snapshot at capture — see below
    val acceptReason: String,            // the Reasons vocabulary
)
```

`providerFlags` records what the location subsystem looked like **when this point was
captured** — which providers were on, the master switch, the permission tier, accuracy
authorization and airplane mode. Decode it with `ProviderSnapshot`:

```kotlin
data class ProviderSnapshot(
    val recorded: Boolean = false,              // false = no snapshot on this point
    val gpsEnabled: Boolean = false,
    val networkEnabled: Boolean = false,
    val locationServicesEnabled: Boolean = false,
    val airplaneMode: Boolean = false,
    val authorizationStatus: Int = STATUS_DENIED,       // 2 denied, 3 always, 4 while-in-use
    val accuracyAuthorization: Int = ACCURACY_REDUCED,  // 0 full, 1 reduced
)

val snapshot = ProviderSnapshot.fromFlags(point.providerFlags)
if (snapshot.recorded && !snapshot.gpsEnabled) {
    // this point came from network positioning only
}
```

The two numeric fields carry **wire codes**, not an SDK enum, because they are a contract with
your backend and mean the same thing whichever platform sent them. Named constants are on the
companion: `STATUS_NOT_DETERMINED` (0), `STATUS_RESTRICTED` (1), `STATUS_DENIED` (2),
`STATUS_ALWAYS` (3), `STATUS_WHEN_IN_USE` (4), `ACCURACY_FULL` (0), `ACCURACY_REDUCED` (1).
Android cannot tell "never asked" from "asked and refused", so it never emits `0` for status.

`recorded` is `false` — and every other field meaningless — for points captured before the
SDK began recording this. That is deliberately distinct from a snapshot where everything is
off: "we did not look" and "location was disabled" are different answers about a point that
plainly exists.

It is captured per point rather than read when you ask, because the live `ProviderState` tells
you about *now*: a track recorded over an hour can span a permission downgrade, and the point
that stopped being precise is the one that carries the reason.

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
val track = tracker.buildTrack(
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
| `snapMaxDetourFactor` | `Double` | `2.5` | How much longer than its chord an injected road span may be. `Double.POSITIVE_INFINITY` disables the bound |
| `snapBridgeFlatM` | `Double` | `200.0` | Flat allowance under `snapMaxDetourFactor`; carries a junction or roundabout whose chord is near zero |
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
val polylineJson = tracker.exportPolylineJson(PointQuery(sessionId = id))
val geoJson      = tracker.exportGeoJson(PointQuery(sessionId = id))
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
    tracker.liveTrack().collect { update ->
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
tracker.setActiveRoute(routePolylinePoints)   // List<GeoPoint>; pass emptyList() to clear
if (tracker.isOffRoute()) offerReroute()
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

when (val result = tracker.addGeofence(fence)) {
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
val history: List<TrackerGeofenceEvent> = tracker.getGeofenceEvents(
    geofenceId = "warehouse",
    fromMs = startOfDay,
    limit = 100,
)
val deleted: Int = tracker.deleteGeofenceEvents(geofenceId = "warehouse")
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
val now: BatteryInfo = tracker.batteryInfo()            // reads the platform right now
val live: StateFlow<BatteryInfo> = tracker.batteryState()
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
val sensors: DeviceSensors = tracker.getSensors()
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

### 12.1 Motion hardware, and what it can override

`MotionQuality` is not a diagnostic. The SDK acts on it at `ready()`, before any session
opens, because running a motion-gated design on hardware that cannot detect motion produces
gaps a user blames on the SDK rather than on the device (EC-137).

| `MotionQuality` | Derived when | What the SDK does about it |
|---|---|---|
| `FULL` | Accelerometer **and** gyroscope **and** (significant-motion or step-detector) | Nothing — your config runs as written |
| `DEGRADED` | Anything between the two | `motion.stopTimeoutMin` is **doubled**, mode untouched. A `Diagnostic` event names the old and new value |
| `POOR` | No accelerometer, **or** `ACTIVITY_RECOGNITION` denied with no significant-motion and no step-detector | `trackingMode` is forced to `CONTINUOUS` (unless it already is). `TrackerEvent.Error(MOTION_DETECTION_DEGRADED)` names the missing sensors |

Three things about this are worth knowing before you debug a device.

**`POOR` is not purely a hardware verdict.** Read the second half of that row again: a
denied `ACTIVITY_RECOGNITION` runtime permission reaches `POOR` on a device with no
significant-motion or step sensor, and plenty of otherwise-capable mid-range hardware has
neither. The message then reads `accelerometer=true`, which looks self-contradictory until
you notice the permission half. **Check the grant before you blame the phone.** The verdict
changes when the grant does, so re-read it after a permission flow rather than caching it
from startup.

**`POOR` costs battery.** `CONTINUOUS` keeps the location stream registered while
stationary and `MOTION_ONLY` does not — see [§5.2](#52-geolocationconfig). The override is
the right trade (gaps are worse than power) but it is the opposite of what a host choosing
`MOTION_ONLY` asked for, so the event message says so explicitly.

**`DEGRADED` widens rather than overrides.** Stops on such a device are detected later and
less certainly. Waiting longer before believing one is the cheaper error: a late stop costs
a few extra fixes, a false stop costs the rest of the trip. The value is doubled rather
than replaced with a constant, so your own timeout still expresses your use case.

### 12.2 Reading it

```kotlin
// The verdict, and whether it changed your mode. Always current, whenever you subscribe.
tracker.state.value.motionQuality        // FULL | DEGRADED | POOR
tracker.state.value.effectiveTrackingMode

// Which sensors explain that verdict.
tracker.getSensors()
```

Use `TrackerState` for the verdict and `getSensors()` for the detail.

Do **not** rely on the `MOTION_DETECTION_DEGRADED` event alone: it is emitted inside
`ready()`, and `events` has `replay = 0`, so a collector created afterwards — the
documented and usual order — never receives it. See
[§7.1](#71-trackerevent--the-event-flow). The event is still worth handling if you collect
early; it is simply not a reliable way to *discover* the condition.

Both `state` fields are set by `ready()` and hold until the next `ready()` call, so a host
that re-runs `ready()` after granting `ACTIVITY_RECOGNITION` gets a re-evaluated verdict —
which is the recovery path when the cause was a denied permission rather than absent
hardware.

### 12.3 Points from a device that is not moving

The oldest complaint in location tracking: the phone is on a desk, nothing is moving, and
points keep arriving.

Every stationary defence in the acceptance pipeline reasons about **position**, because a
GNSS fix carries nothing else — wobble guards, the measurement-noise penalty, the
net-displacement departure ladder. That makes them statistical, and a statistical gate needs
an escape hatch wide enough for a real journey. Indoor multipath is very good at finding
those hatches: served by fused location, a still phone hops between Wi-Fi and cell centroids,
and each hop is a plausible-looking displacement with a respectable accuracy circle.

Two things address it, and they are independent.

**The pipeline itself was tightened, and this needs no configuration.** A single fix could
confirm a departure on net displacement alone, and confirming one latches `movingMode`, which
switches the departure ladder off until the filter settles — so one 160 m centroid hop bought
a licence for every hop after it. Size now only confirms a departure while the GNSS chip
reports *some* motion; where it reports none, displacement is the only evidence in play and
its size proves nothing, because drift is always able to be a long way from the anchor. A
second fix closed the tally surviving the drift *back* to the anchor, which let two hops
minutes apart satisfy a test written to require two consecutive advancing fixes.

**`motion.suppressWhileStationary` adds a witness of a different kind**, and is off by
default. The accelerometer measures the device rather than its own estimate of itself, and
nothing about a centroid hop can move it. Four conditions must agree before a single point is
withheld:

| Condition | Why |
|---|---|
| You asked for it | Off by default |
| The pipeline already read the fix as stationary | Displacement that measured as travel outranks any sensor window |
| The GNSS chip reports no speed | Doppler is a hardware measurement multipath cannot fabricate; a chip reporting motion wins |
| The pedometer counted no steps, or is absent | One step withdraws the veto |

And it expires. After `stillnessEscapeMin` (30 minutes by default) the claim lapses for one
fix, judged exactly as it would have been before the stage existed. That bound is not about
accuracy — it is so that an accelerometer that stops delivering, or a threshold wrong for
some OEM's part, costs a slow trickle of drift points rather than a silent shift.

Those limits are why it is safe to switch on. **A motion API reporting `STILL` through a
17-minute drive is a documented failure on this SDK's target hardware** (OnePlus and Xiaomi
under battery saver), and every bound above exists so a wrong sensor costs a suppressed drift
point and never a trip. This is also why it is a veto and never a trigger — nothing here can
*cause* a point to be stored, and capture is never gated on motion detection.

Suppressed fixes appear in the decision log as `Reasons.STILLNESS_VETO`
([§17.4](#174-reasons--the-reason-vocabulary-is-api)), so if you think a real point went
missing you can see exactly which fixes were withheld and why.

Requires an accelerometer. Without one the flag is turned off at `ready()` and a `Diagnostic`
says so — the same hardware that reaches `MotionQuality.POOR` in
[§12.1](#121-motion-hardware-and-what-it-can-override).

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

lifecycleScope.launch { tracker.liveTrack().collect(live::render) }

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

The module carries two independent channels: **positions**, below, and **diagnostics**
([§15](#15-log-module--diagnostics-to-your-backend)) — separately configured, separately
credentialed, and unable to affect one another. This section is the first.

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
| `method` | `String` | `"POST"` | HTTP method. **`POST`, `PUT` or `PATCH` only** — see below |
| `headers` | `Map<String, String>` | empty | Sent on every request. **Never exposed back** — they carry your credential |
| `autoSync` | `Boolean` | `true` | Upload as points arrive, plus the connectivity and supervision triggers. With it off you call `syncNow()` / `requestSync()` — **except** that closing a session with rows queued always enqueues a drain, because `stop()` tears down every path that could otherwise notice |
| `batchSize` | `Int` | `100` | Rows per request, 1..1000. Larger = fewer requests but a bigger retry unit |
| `requiresUnmeteredNetwork` | `Boolean` | `false` | Only upload on Wi-Fi |
| `gzipRequestBody` | `Boolean` | `false` | Compress the JSON body. Off by default — there is no negotiation for request-body encoding, so a server that does not expect gzip answers 400 |
| `allowCleartext` | `Boolean` | `false` | Permit an `http://` URL. Local development only. Loopback hosts (`localhost`, `127.0.0.1`, `::1`, `10.0.2.2`) are already exempt |
| `timeouts` | `SyncTimeouts` | 5 s / 30 s / 20 s | Applied by the built-in transport; ignored by a custom one |
| `includePointSessionId` | `Boolean` | `false` | Stamp every uploaded point with the session that recorded it (`session_id` on each row of `location`). The envelope's `session_id` describes a whole batch, and a backlog drained after a process kill can hold rows from two drives — this is the only field that says which is which. Off by default, so the body stays byte-identical to every previous release. **Set it on any host that records offline** |
| `extraParams` | `Map<String, Any>` | empty | Merged into the **top level** of every request body, alongside the `location` array — see [§14.1.1](#1411-extraparams--your-own-body-fields) |

> **`method` accepts `POST`, `PUT` or `PATCH`, and nothing else.** The built-in transport
> is Retrofit, whose verb annotations are compile-time constants — there is no dynamic-verb
> form — so the transport dispatches over a fixed set rather than passing your string
> through. `configure()` rejects anything outside it, which at least surfaces the problem at
> configuration time rather than on the first upload hours later.
>
> This is a narrowing. The previous OkHttp transport passed any verb straight to
> `Request.Builder.method(...)`. If you need another one, supply your own `SyncTransport`
> ([§14.6](#146-custom-transport)) — the interface has no such restriction.

> **The SDK stores nothing about this config, so call `configure()` from
> `Application.onCreate`.** Your credential never reaches disk — but neither does the rest
> of the config, and that has a consequence worth planning for: `WorkManager` persists the
> upload *request*, not your `SyncConfig`, and the process the OS builds to run that request
> has executed none of your code past `Application.onCreate`. A host that configures only
> after login, in an Activity, leaves every such process unconfigured — the worker finds
> nothing, reports done, and the rows wait for the app to be reopened.
>
> Configure with whatever you have at launch and call `configure()` again when the token
> arrives. It is safe to call repeatedly: it replaces the config, re-registers the trigger,
> and clears a 403 halt.

**Builder**: `.url()`, `.baseUrl()`, `.path()`, `.method()`, `.header(name, value)`,
`.headers(map)`, `.autoSync()`, `.batchSize()`, `.requiresUnmeteredNetwork()`,
`.gzipRequestBody()`, `.allowCleartext()`, `.timeouts(SyncTimeouts)`,
`.timeouts(connectMs, readMs, writeMs)`, `.includePointSessionId()`,
`.extraParam(name, value)`, `.extraParams(map)`,
`.build()`, `.buildUnchecked()`.

`baseUrl` and `path` are joined with exactly one `/` regardless of which side carries it.

#### 14.1.1 `extraParams` — your own body fields

Most backends want the batch inside an envelope carrying identity, not on its own. Anything
you put in `extraParams` is merged into the **top level** of the request body, before the
`location` array:

```kotlin
SyncConfig.builder()
    .baseUrl(BuildConfig.API_BASE_URL)
    .path("v1/location/batch")
    .header("Authorization", "Bearer $token")
    .extraParam("user_id", userId)
    .extraParam("device_id", deviceId)
    .extraParam("company_id", 7)
    .build()
```

produces:

```json
{
  "user_id": "u-42",
  "device_id": "d-88",
  "company_id": 7,
  "location": [ { "uuid": "…" } ]
}
```

Values may be a `String`, a `Boolean`, any boxed number, or a `Map` / `List` / array of those
for nested structures. Numbers stay numbers and booleans stay booleans — nothing is
stringified on the way out. `null` is not a value: omit the key instead.

**`configure()` rejects an unusable value rather than failing on the first upload.** An
unserializable object is reported by key name at configuration time — the point where you are
still holding the config you wrote — rather than mid-drain hours later, where a batch that
cannot be sent has no good answer. The key `location` is reserved, since that is the batch
itself.

**With no `extraParams` set, the body is byte-identical to previous releases** — this is
additive, and an existing backend needs no change.

**These are static config, like `headers`.** A rotating token belongs in a re-`configure()`
call, or in your own `SyncTransport` where you can compute it per request.

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
    data class NetworkAvailable(val queued: Int) : SyncEvent
}
```

`statusCode` is `null` when **no HTTP response arrived at all** (dead network, DNS failure,
timeout). That is a device problem; a 500 is a server problem — do not report them the same way.
`count` is what was *attempted*, not what was stored.

`NetworkAvailable` fires when the device returns to a usable network **and** rows are actually
queued — a reconnection with an empty queue is silent. `queued` is the depth at that moment. It
says a drain was *requested*, not that one succeeded; the `HttpResponse` that follows is the
outcome. See [When the network comes back](#when-the-network-comes-back).

```kotlin
lifecycleScope.launch {
    sync.events.collect { event ->
        when (event) {
            is SyncEvent.HttpResponse -> showLastUpload(event.statusCode, event.count)
            is SyncEvent.NetworkAvailable -> showBacklog(event.queued)
        }
    }
}
```

`events` is a `SharedFlow` with `replay = 1`, so a screen opened after a background drain sees
the last event rather than a blank panel.

`TrackerSync` also carries the log channel — `configureLogs`, `log`, `logLifecycle`,
`getLogs`, `syncLogsNow`, `requestLogSync`, `logEvents` and their state. Those are listed in
[§15.4](#154-the-log-api-on-trackersync), because none of them does anything until you turn
that channel on.

### 14.4 Terminal failure semantics

| Status | Behaviour |
|---|---|
| **2xx** | Batch accepted and marked synced |
| **401 Unauthorized** | **Terminal.** Tracking is stopped, the upload queue is cleared, and the config is forgotten. The credentials this session was recorded under are gone; keeping the queue would leak the previous user's positions into the next login |
| **403 Forbidden** | **Terminal, but non-destructive.** Uploads halt, **rows are kept**, tracking continues. Recovery is calling `configure()` again with a working credential |
| **Anything else** | Rows stay queued and retry with linear backoff (30 s base) via WorkManager, network-constrained |
| `Retry-After` header | Honoured — the server's own schedule replaces the SDK's |

Not every `Retry` is a failed exchange. `Retry("already draining")` means another drain holds the
lock and is doing the work; `Retry("sync not configured")` and `Retry("no transport")` mean there
was nothing to attempt. None of the three consume a backoff attempt — surface them as information,
not as an upload error.

One visible side effect of both terminal cases: if you turned on
[`showSyncStatusInNotification`](#55-serviceconfig), the upload-status subtitle and line disappear
with the config and the notification goes back to your own `notificationText`. That is the same
reading as "sync was never configured", so a status line that vanishes mid-session means a `401`
or `403` landed — check `SyncEvent.HttpResponse` for which. After a `403` the rows are still on
disk and resume uploading once `configure()` is called with a working credential; after a `401`
they are gone.

#### When the network comes back

Recovering from offline has two independent halves, and you need neither of them to do anything:

- **Durable.** Every drain is enqueued as network-constrained WorkManager work, persisted in
  WorkManager's own database. It survives process death and reboot, so a backlog recorded offline
  uploads even if the app is killed before connectivity returns.
- **Prompt.** While the process is alive, the SDK watches the default network and asks for a drain
  the moment the device is on a *validated* one — not merely a connected one, so a captive portal
  is not mistaken for internet. Rising edges only, throttled to one request per 15 s, and only
  when the queue is non-empty. This is what emits `SyncEvent.NetworkAvailable`.

The prompt half needs `autoSync = true`; with it off you own the schedule and nothing drains
unless you call `syncNow()`. Both halves stop after a 401 or 403.

**The durable half needs a config, and a cold process has none.** WorkManager persists the
*request*, not your `SyncConfig` — and the process it builds to run that request has executed
none of your code past `Application.onCreate`. Nothing is read back from disk, so if
`configure()` has not run in that process the worker finds nothing configured and reports
done, and the rows stay queued until your app is next opened. **Configure in
`Application.onCreate`** ([§14.1](#141-syncconfig)), and call `configure()` again later if a
login changes the credential.

#### When a session ends

Closing a session with rows still queued enqueues a drain, **whatever `autoSync` says**, as long
as `configure()` has run.

It is not gated on the flag because `autoSync` is a statement about *cadence* — "not as points
arrive" — and the end of a session is not an arrival. It is the last moment the SDK is watching
at all: `stop()` cancels the backstop worker and stops the service the health loop runs in, so
both supervision paths die with the session. A host that configured an endpoint and set
`autoSync = false` therefore had nothing scheduled for a shift it recorded offline, and the rows
waited until it next called `syncNow()` with a network by hand.

`autoSync = false` still means what it says: nothing uploads while the session runs. You get one
drain when it ends, and WorkManager holds it until the network allows.

**Queue order is FIFO** — oldest row first, across every unsent session, by insertion order rather
than by any device clock. A backlog that spans a reboot still uploads in the order it was
recorded.

### 14.5 The wire format

The complete request contract: what the SDK sends, what every field means, and every limit
that applies. JSON, snake_case keys, epoch milliseconds throughout.

#### 14.5.1 The request line and headers

As sent by the built-in transport. A custom `SyncTransport`
([§14.6](#146-custom-transport)) owns the whole exchange and none of the header behaviour
below applies to it.

| Part | Value | Limits |
|---|---|---|
| Method | `SyncConfig.method` | `POST`, `PUT` or `PATCH` only. Rejected at `configure()` otherwise |
| URL | `SyncConfig.url` | Must be `https://`. `http://` only for loopback (`localhost`, `127.0.0.1`, `::1`, `10.0.2.2`) or with `allowCleartext = true` |
| `Content-Type` | `application/json; charset=utf-8` | Not configurable |
| `Content-Encoding` | `gzip`, **only** when `gzipRequestBody = true` *and* the body is at least **1,024 characters** | Below that the gzip header and trailer cost more than the saving, so the body is sent uncompressed and this header is omitted entirely. **Your server must handle both**, on the same endpoint, with the same config |
| Your headers | `SyncConfig.headers` | Sent on every request. Never read back by any SDK API — they carry your credential |

There is no request-body encoding negotiation, so `gzipRequestBody` is off by default:
a server that does not expect gzip answers `400` or stores the compressed bytes as the
payload. Turn it on only once your server is known to decode it.

#### 14.5.2 The body envelope

```json
{
  "user_id": "u-42",
  "device_id": "d-88",
  "location": [
    { "uuid": "0f5c8f0e-…", "time": 1755500000000 },
    { "uuid": "1a6d9e1f-…", "time": 1755500030000 }
  ]
}
```

Points are abbreviated here — the full object is in [§14.5.3](#1453-one-point).

| Key | Type | Notes and limits |
|---|---|---|
| *(your keys)* | any JSON | Whatever you put in [`extraParams`](#1411-extraparams--your-own-body-fields), in insertion order, **before** `location`. Values may be a string, boolean, number, or a map/list of those, nested up to 10 levels. Types are preserved — a number stays a number. Rejected at `configure()` if unserializable |
| `location` | array | The batch. **Reserved** — `extraParams` may not use this key. Never empty: a drain with nothing queued sends no request at all |

Rows per request is `batchSize` (default `100`, valid range **1–1000**). A single drain
uploads at most **20 batches** before returning, so one drain moves at most
`20 × batchSize` rows; a larger backlog is picked up by the next trigger. This bound exists
so one call cannot hold the queue through an unbounded backlog.

#### 14.5.3 One point

```json
{
  "uuid": "0f5c8f0e-1c2a-4f0b-9a3c-7d1e2b3a4c5d",
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
  "activity_status": "fused@moving",
  "detected_activity_type": "IN_VEHICLE",
  "detected_activity_start_time": 1755499000000,
  "battery_percentage": "62",
  "is_charging": false,
  "is_mock": false,
  "integrity_flags": 0,
  "integrity_signals": []
}
```

| Field | Type | Always sent? | Meaning and limits |
|---|---|---|---|
| `uuid` | string | yes | Stable identity for this point. **Dedupe on this** — see §14.5.5 |
| `time` | number | yes | Capture time, epoch **milliseconds**, wall clock. Subject to device clock changes; `integrity_flags` reports when the clock looked untrustworthy |
| `local_date` | string | yes | `yyyy-MM-dd` in the point's own `time_zone`, for day bucketing without server-side zone maths |
| `latitude` / `longitude` | number | yes | WGS-84 degrees. The fix's own coordinates, not a filtered estimate |
| `accuracy` | number | yes | Horizontal error radius in **metres**, as the platform reported it. No upper bound — a bad indoor fix can be thousands |
| `movementSpeed` | number | yes | Metres per second. **`0.0` when the provider reported no speed** — check `hasSpeed` before trusting it |
| `provider` | object | no | Location subsystem at capture time. See §14.5.4. Absent on points captured before the SDK recorded it |
| `hasSpeed` / `hasBearing` | boolean | yes | Whether the provider actually supplied the value. `0.0` is a legal speed, so this is the only way to tell "stationary" from "not reported" |
| `time_zone` | string | yes | IANA id, **per point** — a session can cross zones on a flight, so do not assume one zone per batch |
| `activity_status` | string | yes | `"<provider>@<movementStatus>"`, lowercase — e.g. `fused@moving`, `gps@steady`. Provider is one of `fused`, `gps`, `network`, `passive`, `unknown`; movement is `moving` or `steady`. **This is where the provider name lives** |
| `detected_activity_type` | string | no | One of `IN_VEHICLE`, `ON_BICYCLE`, `ON_FOOT`, `WALKING`, `RUNNING`, `STILL`, `TILTING`, `UNKNOWN`. **Enrichment only** — see the caveat below |
| `detected_activity_start_time` | number | yes | Epoch ms when that activity began; `0` when unknown |
| `battery_percentage` | string | no | 0–100 **as a string**, e.g. `"62"`. Absent when the platform will not say |
| `is_charging` | boolean | no | Plugged in or full. Absent — **not `false`** — when the platform will not say |
| `is_mock` | boolean | yes | The fix was flagged as mock by the OS. Android-only concept. Whether mock points are sent at all depends on policy — see [§20.2](#202-policy) |
| `integrity_flags` | number | yes | Device-integrity bitmask at capture. `0` = nothing observed. Bit values are frozen — see [§20.4](#204-on-the-wire-and-in-storage) |
| `integrity_signals` | array of string | yes | The same information by name, for rules that prefer strings to bits. `[]` when nothing was observed |

> **`detected_activity_type` is not a capture gate and should not be one server-side
> either.** Entire multi-minute drives are reported `STILL` by some devices under battery
> saver. Treat it as a hint, never as ground truth about whether the user moved.

#### 14.5.4 The `provider` object

| Field | Type | Meaning and limits |
|---|---|---|
| `network` | boolean | The network (Wi-Fi/cell) provider is enabled |
| `gps` | boolean | The GPS provider is enabled |
| `enabled` | boolean | The location **master switch**. Not the union of the two above — a device can report location enabled with GPS off. Below Android 9 the platform exposes no master switch, so this falls back to the union |
| `status` | number | Permission tier: `0` not determined, `1` restricted, `2` denied, `3` always (foreground + background), `4` while in use. **Android never sends `0`** — it cannot distinguish "never asked" from "asked and refused", so both are `2` |
| `accuracyAuthorization` | number | `0` full (fine location), `1` reduced (coarse only). Reduced means a 1–3 km error circle |
| `airplane` | boolean | Airplane mode was on. **Not a gate** — GPS keeps working in airplane mode on most devices while network positioning does not |

Recorded **per point**, not sampled when the queue drains. A batch can span an hour, and a
permission downgrade inside that hour is exactly what explains a gap — a single snapshot
taken at upload time would stamp every row with whatever happened to be true minutes later.

The key is **omitted** for points captured before the SDK recorded it. That is deliberately
not an object full of `false`: "we did not look" and "everything was off" are different
answers about a point that plainly exists.

> **Breaking change from earlier releases:** `provider` was the provider *name* as a string
> (`"fused"`). The name is still on the wire — read it from `activity_status`, which is
> `"<provider>@<movementStatus>"` and always was.

#### 14.5.5 Rules your server must follow

**Dedupe on `uuid`.** A failed batch is re-sent **whole** on the next attempt, so duplicate
delivery is guaranteed by design, not an edge case. A batch that your server stored but
failed to acknowledge — a timeout after the write, a 502 from a proxy — arrives again.

**Absent is not null.** Nullable fields are **omitted from the object**, never sent as
`null`. If `detected_activity_type`, `battery_percentage`, `is_charging` or `provider` is
unknown, the key is simply not there. A parser that distinguishes the two needs to know
this; it is pinned by an automated test, so it cannot change silently.

**New fields will appear.** Every field added so far has been additive with a default, and
parsing must tolerate unknown keys. Conversely, `integrity_flags` and `integrity_signals`
are always sent by clients that support them — a client version known to send them that
suddenly stops is worth treating as suspicious.

**Answer with the right status.** The status code alone decides what the SDK does; the
success body is ignored entirely.

| Status | SDK behaviour |
|---|---|
| **2xx** | Accepted. Rows marked synced, next batch drains immediately |
| **401** | Terminal. Tracking stops, the queue is **cleared**, config is forgotten |
| **403** | Terminal for retrying. Uploads halt, rows are **kept**, tracking continues |
| Anything else | Retried with backoff, rows kept |

See [§14.4](#144-terminal-failure-semantics) for why 401 and 403 differ. Send `Retry-After`
on a `429` or `503` to control the next attempt: both RFC 9110 forms are accepted
(delta-seconds or an HTTP-date), and the value is **clamped to 1 second – 6 hours** so one
bad header cannot park the queue indefinitely.

On a non-2xx the SDK keeps at most **4,096 characters** of your response body for the host
to inspect, so `500` can be told apart from `500 {"error":"bad geometry"}`. A longer body is
truncated, not rejected. It is never logged, because an error body can echo a request
header. Success bodies are discarded unread.

**Treat integrity fields as advisory.** `integrity_flags` is input to a server-side rule,
not the defence itself — it is a client-side observation, and a client is not a trustworthy
narrator about itself.

### 14.6 Custom transport

Supply your own `SyncTransport` to reuse an existing authenticated client — then neither
Retrofit nor OkHttp is linked, and you can remap the payload to whatever your backend
expects. The example below uses OkHttp because that is what most hosts already have; the
interface has no opinion.

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

### 14.7 Watching the API calls in logcat

Every upload the SDK makes is written to logcat as one line, under its own tag:

```
$ adb logcat -s FieldTrackApi
D/FieldTrackApi: POST points https://api.acme.test/v1/location/batch -> 200 success in 412ms (18422B gzip)
W/FieldTrackApi: POST logs   https://api.acme.test/v1/logs/batch -> 503 refused in 9004ms (2210B gzip) retryAfter=30000ms
W/FieldTrackApi: POST points https://api.acme.test/v1/location/batch -> - no_response in 30001ms (18422B gzip) "timeout"
```

`-s FieldTrackApi` is the device's whole API conversation and nothing else — both queues in
one stream, `points` and `logs` naming which. A 2xx is `d`, everything else is `w`.

The word after the status is the **outcome**, and it is not derived from the status: a
request that never reached a server has none, and reading that as a 500 is what makes "the
API is down" indistinguishable from "this device has no signal". It is one of `success`,
`unauthorized`, `forbidden`, `refused`, `no_response`, or a `threw <Exception>` for a
transport that raised instead of answering.

Written at the [`SyncTransport`](#146-custom-transport) seam, so it sees **your** client too
if you supplied one — including a request one of your interceptors rewrote.

> **Debug builds only.** The lines go through the SDK's internal `sdkLog`, which the release
> variant compiles out. A released app writes nothing here, which is the right default for
> output that any app on a rooted device can read.

> **Never printed:** headers and bodies, in either direction, and the URL's query string and
> userinfo. A credential lives in all four. This is a diagnostic aid, not a proxy trace — add
> an OkHttp logging interceptor to a debug build if you need the wire itself.

Nothing here is stored or uploaded. The durable, uploaded channel is [§15](#15-log-module--diagnostics-to-your-backend),
and API calls are deliberately not part of it: a row per upload on disk is a different price,
and for the log channel itself it would be an entry that the next log upload has to ship,
which writes another one.

---

## 15. Log module — diagnostics to your backend

**On by default whenever [§14](#14-sync-module--upload-to-your-backend) is configured, and
in the same artifact.** `configure()` derives this channel from the points config it was
just given, so a host that set up uploads gets diagnostics without a second setup call. Set
`SyncConfig.syncLogs = false` and nothing below happens at all — no network, no database
file, no battery. A host that never calls `configure()` never had a channel to begin with.

Points answer *where the device was*. They cannot answer *why there is nothing there*. A
track with a twenty-minute hole in it is unreadable on its own — and every plausible cause
is on the phone, not on your server:

| On the device | What your dashboard sees without logs |
|---|---|
| Battery optimiser killed the service | a track that stops |
| Location downgraded to "approximate" | a track that gets vague |
| `ACCESS_FINE_LOCATION` denied on first run | **no device at all** |
| A fix rejected by the accuracy gate | a straight line where the road bends |
| The process crashed | a track that stops |

This channel makes those durable. `tracker.events` ([§7.1](#71-trackerevent--the-event-flow))
is a live notification that exists only while something is collecting it; the case you most
need explained — a process an OEM killed mid-drive — is exactly the case where nobody was.

### 15.1 It is already on

One line — the one you wrote for points. `configure()` **derives the log channel from the
points endpoint**:

```kotlin
val sync = TrackerSync.getInstance(context)

sync.configure(
    SyncConfig.builder()
        .baseUrl(BuildConfig.API_BASE_URL)
        .path("v1/location/batch")
        .header("Authorization", "Bearer $token")
        .extraParam("device_id", installId)
        .build()
)
// Diagnostics are now shipping to <same origin>/v1/logs/batch. No second call.
```

The derived config takes the URL from the origin of the `SyncConfig` plus `v1/logs/batch`,
inherits `device_id` from `SyncConfig.extraParams`, and reuses the points headers.

**Why on rather than off.** These logs exist to explain the report that arrives as
"tracking stopped on one phone yesterday" — and by the time it arrives, the window to have
been collecting has closed. A channel switched on afterwards collects nothing about the
incident that made someone want it.

**It never fails your `configure()` call.** If the endpoint cannot be derived — no
`device_id` in `extraParams`, an unparseable URL — the SDK logs why under `Tracker/TrackerSync`
and carries on with points working normally:

```
No diagnostic log channel: deviceId must not be blank …. Points are unaffected; call
configureLogs() to set one up.
```

Turn it off with one builder call, and nothing is derived at all:

```kotlin
SyncConfig.builder()
    .url("https://api.example.com/v1/location/batch")
    .syncLogs(false)
    .build()
```

> **`device_id` must be the same string on both channels.** That join — a hole in a track,
> next to the reason for it — is the whole point. Two spellings of the same phone produce
> two unrelated datasets. Inheriting it is the default for that reason.

Point it somewhere else — or change the level, interval or credential — with an explicit
`configureLogs()`. **An explicit call always wins**, and keeps winning: once made, later
`configure()` calls leave your choice alone rather than re-deriving over it.

```kotlin
sync.configureLogs(
    LogSyncConfig.builder()
        .path("internal/diagnostics")               // or .url("https://logs.example.com/v1/logs/batch")
        .header("Authorization", "Bearer $logToken")  // its own credential — recommended
        .level(LogLevel.INFO)
        .uploadIntervalMinutes(15)
        .build()
)
```

**Give this endpoint its own credential.** A 401 on the *points* URL is destructive by
design — it stops tracking and clears the queue ([§14.4](#144-terminal-failure-semantics)).
A 401 here only stops log shipping. Keeping the scopes apart is what stops a diagnostics
mistake reaching the point queue.

`configureLogs()` is idempotent and safe to call on every launch — call it right after
`configure()`, in `Application.onCreate`. Unlike the derived default it **does** throw
`IllegalArgumentException` if the resolved config does not validate, so wrap it the same way
you wrap `configure()`: you asked for this endpoint by name, so a broken one is an error
rather than a line in a log.

**The two channels are independent in both directions.** Either can be set without the
other, neither can tear the other down, and no log failure can reach `Tracker.stop()`, the
upload queue, or a stored position.

### 15.2 How the channel works

One pipeline, four stages: a line is **recorded** on the device, **buffered** in a bounded
ring, **drained** on a schedule, and **settled** against what the server answered. Nothing in
it can reach a position — the two channels share only the transport interface.

```
  Tracker.events ─────┐
  sync.log(…)         ├──►  LogRecorder  ──►  inbox · Channel(256, DROP_OLDEST)
  sync.logLifecycle(…)┘          │                    │
                                 │ level + type       │ never blocks the caller
                                 ▼ filter, truncate   ▼ one writer, ordered
                    log_entry · Room, fieldtrack-logs-<yourPackage>.db
                    a ring of bufferCapacity rows — oldest evicted, shipped or not
                                 │
              ┌──────────────────┴───────────────────┐
              │ heartbeat                            │ nudge
              │ LogSyncWorker, every                 │ an entry at nudgeLevel or above
              │ uploadIntervalMinutes (floor: 15)    │ asks for a drain now, throttled
              │ WorkManager, network-constrained     │ to one per nudgeCooldownMs
              └──────────────────┬───────────────────┘
                                 ▼
                        LogSyncQueue.drain()
                          1. recorded entries — batchSize at a time, ≤ 20 batches
                          2. decisions       — read from the SDK's own decision log,
                                               everything above a per-session watermark
                                 │
                                 ▼
                        SyncTransport.upload()  ──►  POST <logEndpoint>
                                 │                   one logcat line per exchange (§14.7)
                                 ▼
                        2xx → rows marked settled · watermark advanced
                        4xx → batch dropped, or the channel halts (§15.8)
                        5xx → nothing settled, retry with backoff
```

**1 · Record.** `Tracker.events` is collected for you, and `log()` / `logLifecycle()` add your
own lines. Every draft goes through a bounded, single-consumer channel: the call never blocks
the thread it came from, and one writer at the other end is what keeps `seq` meaningful. The
channel drops its **oldest** on overflow, matching the ring below it — a diagnostic buffer that
applied backpressure to the tracker it is diagnosing would be a worse bug than the ones it was
added to find.

The write itself does four things: applies the `level` and `types` filters (a device set to
`INFO` never *stores* a `DEBUG` line, so filtering costs no disk), truncates `message` to 4096,
`tag` and `code` to 64, takes the next `seq` for that `(session, type)` pair, and derives the
row's id — `SHA-1("<sessionId>:<seq>:<type>:<elapsedRealtimeNanos>")`, the dedupe key the whole
channel is built on ([§15.11](#1511-schema-architecture)).

**2 · Buffer.** Rows land in a second Room database, separate from the one holding positions.
Each row is queued or settled, nothing else. The ring is trimmed to `bufferCapacity` newest
rows — **shipped and unshipped alike**, because a buffer that refused to evict unsent rows is
an unbounded buffer on exactly the device that cannot reach a server. Settled rows are pruned
by age at `retentionHours` on the way out of a successful drain — an entry is only safe to
forget once it has actually left the device — and queued rows are never pruned by age at all.

**3 · Trigger.** Two paths, and they cannot cancel each other — they run under separate unique
work names:

| Path | Enqueued as | When |
|---|---|---|
| Heartbeat | periodic, `UPDATE` policy | every `uploadIntervalMinutes`, floor 15 — WorkManager's own |
| Nudge | one-shot, `KEEP` | an entry at `nudgeLevel` (default `WARN`) landed, at most one per `nudgeCooldownMs`; a burst inside the window is deferred to its end, not dropped |
| `Retry-After` | one-shot, `REPLACE` | the server named a delay — its schedule wins over a pending one-shot |
| `requestLogSync()` | one-shot, `KEEP` | you asked |

Positions are shipped as soon as there is a network; diagnostics are read after the fact, by a
person with a ticket open. That difference is the whole reason this channel is a heartbeat with
a nudge rather than an event-driven queue: the radio wakes on a schedule you set, not on every
line the SDK writes.

**4 · Drain.** `LogSyncQueue.drain()` is single-flight — a second one returns
`Retry("already draining")` rather than queueing behind the first. It makes one pass:

- **Recorded entries first**, `batchSize` rows at a time, up to 20 batches in a drain. Rows are
  re-filtered here as well as at record time, because a later `configureLogs()` can have
  narrowed `level` or `types`; an entry that no longer passes is *settled* rather than skipped,
  or it would sit at the head of every future batch forever. A 2xx settles the contiguous
  prefix up to that batch's last row id.
- **Decisions second**, and only when `LogType.DECISION` is enabled. They are not mirrored into
  this buffer — at 1 Hz that is ~29 000 rows a shift written twice. They are read from the
  SDK's existing decision log at send time, for the open session (or the most recent one),
  everything newer than a stored watermark, which advances **per batch** so a failure halfway
  through a backlog does not re-ship what already landed. The scan is bounded at 20 pages of
  500: a device offline for a week ships what it can and moves on.

Entries before decisions is deliberate on a device shipping both. An entry is what explains a
gap; a decision is what fills one in. A drain cut short by a dead network should leave the more
explanatory half already delivered.

**5 · Settle.** The response decides what happens to the batch — shipped, dropped, or retried —
and, for four status codes, whether the channel keeps asking at all. That table is
[§15.8](#158-results-and-failure-semantics).

> **Delivery is at-least-once, by construction.** A batch that reached the server and lost its
> response is re-sent whole, and every entry in it collides on its derived id rather than
> landing twice. Retrying is free, which is what lets the device settle rows on a 2xx it may
> never have seen.

### 15.3 `LogSyncConfig`

Every field has a default derived from the points endpoint, so the common call passes
nothing.

| Field | Type | Default | What it does |
|---|---|---|---|
| `url` | `String` | derived | Full endpoint. Blank means "resolve it" — an absolute `url` here, else a bare `path` against `TrackerConfig.baseUrl`, else the points URL's origin plus `v1/logs/batch` |
| `deviceId` | `String` | inherited | Blank inherits `SyncConfig.extraParams["device_id"]`. Set it only if you genuinely need a different id — you almost certainly do not |
| `method` | `String` | `"POST"` | `POST`, `PUT` or `PATCH`, same restriction as [§14.1](#141-syncconfig) |
| `headers` | `Map<String, String>` | inherited | Empty inherits the points headers. **Never exposed back** |
| `autoSync` | `Boolean` | `true` | Run the periodic drain. With it off you call `syncLogsNow()` / `requestLogSync()` yourself |
| `level` | `LogLevel` | `INFO` | Minimum severity **recorded**. Filtering happens when the entry is written, not when it is sent, so a device set to `INFO` never stores a `DEBUG` line |
| `types` | `Set<LogType>` | `EVENT, LIFECYCLE, MESSAGE` | Which kinds are recorded and shipped. `DECISION` is absent on purpose — read its row in [§15.7](#157-logrecord-and-its-enums) before adding it |
| `bufferCapacity` | `Int` | `5000` | Rows kept on the device. Oldest evicted first, shipped or not — a bounded buffer that refused to drop unsent rows would be unbounded on exactly the device that cannot reach a server. The resulting gap in `seq` is reported, never hidden |
| `retentionHours` | `Int` | `72` | How long a **shipped** entry is kept before pruning. Queued entries are never pruned by age |
| `batchSize` | `Int` | `200` | Entries per request, 1..500. The server answers `413` above its own ceiling and the SDK drops that batch rather than retrying it |
| `requiresUnmeteredNetwork` | `Boolean` | `false` | Only upload on Wi-Fi |
| `gzipRequestBody` | `Boolean` | **`true`** | On by default here, unlike `SyncConfig`. Log bodies are repetitive prose and compress around 8:1 |
| `allowCleartext` | `Boolean` | `false` | Permit `http://`. Local development only; loopback is already exempt |
| `timeouts` | `SyncTimeouts` | 5 s / 30 s / 20 s | Applied by the built-in transport |
| `uploadIntervalMinutes` | `Long` | `15` | Periodic drain cadence. **15 is WorkManager's floor** and the config rejects less |
| `nudgeLevel` | `LogLevel?` | `WARN` | The severity that earns a **prompt** drain instead of waiting for the heartbeat. `null` disables it — see below |
| `nudgeCooldownMs` | `Long` | `30000` | Shortest gap between two prompt drains. A burst inside the window is **deferred to its end, not dropped** |
| `extraParams` | `Map<String, Any>` | empty | Merged into the top level of the body, beside `logs`. Cannot use `logs`, `device_id`, `session_id`, `app`, `device` or `uploaded_at` — the envelope owns those |

**`nudgeLevel` is what makes an incident arrive in seconds rather than in fifteen minutes.**
The channel is otherwise a quarter-hourly heartbeat, which is right for the volume and wrong
for the entries somebody is waiting on — a GPS toggle, a permission revocation, a capture
suspension. Those are `WARN` or above and are released early, throttled to one drain per
`nudgeCooldownMs`. Lowering it to `INFO` is a battery decision, not a diagnostics one: on a
busy device that is a radio wake every cooldown window.

### 15.4 The log API on `TrackerSync`

| Member | Signature | Notes |
|---|---|---|
| `configureLogs` | `fun configureLogs(config: LogSyncConfig = LogSyncConfig(), transport: SyncTransport? = null)` | Turns the channel on. Idempotent. Throws `IllegalArgumentException` on a config that cannot be resolved |
| `disableLogSync` | `fun disableLogSync()` | Stops recording and shipping. **The buffer is kept** — entries written before you turned it off still describe the period they were written in, and still ship if the channel comes back |
| `logEndpoint` | `val logEndpoint: String?` | Where diagnostics go, or `null` if unconfigured — or if the endpoint refused the channel. Headers are deliberately not exposed |
| `isLogSyncConfigured` | `val isLogSyncConfigured: Boolean` | Derived from `logEndpoint`. **Do not cache it** — a refusal clears configuration with no involvement from you |
| `log` | `fun log(level: LogLevel, tag: String, message: String, code: String? = null, data: String? = null)` | Records one host line as a `MESSAGE`. Fire and forget; a no-op until `configureLogs()` and unless the level and type pass its filters |
| `logLifecycle` | `fun logLifecycle(phase: String, tag: String = "Host")` | Records a boundary of your own — a shift starting, a job accepted. `phase` is a `LifecyclePhase` constant or your own string |
| `getLogs` | `suspend fun getLogs(sessionId: String? = null, limit: Int = 200, offset: Int = 0): List<LogRecord>` | The device buffer, newest first. `null` means every session, including entries belonging to none |
| `pendingLogCount` | `suspend fun pendingLogCount(): Int` | Entries waiting to ship |
| `requestLogSync` | `fun requestLogSync()` | Enqueues a network-constrained one-shot drain. Safe to call often. No-op once the endpoint has refused the channel |
| `syncLogsNow` | `suspend fun syncLogsNow(): LogSyncQueue.Result` | Drains inline in the caller's scope. Prefer `requestLogSync()` for anything not user-initiated — diagnostics are read after the fact |
| `logEvents` | `val logEvents: SharedFlow<SyncEvent>` | One event per completed log exchange. Replay 1 |

`logEvents` is a **second** flow rather than a second case on `SyncEvent`, so a screen showing
one "last sync" badge is never forced to conflate a diagnostics upload failing with a
positions upload failing.

### 15.5 What the SDK records without being asked

Once configured, the SDK writes its own `TrackerEvent` stream into the buffer. You do not
subscribe to anything:

| Recorded as | Level | From |
|---|---|---|
| `Error` | `ERROR` | Any `TrackerEvent.Error` — permission revoked, location disabled, storage full |
| `CaptureSuspended` | `WARN` | Capture stopped while the session stayed open |
| `CaptureResumed` | `INFO` | Capture re-armed |
| `PermissionChange` | `WARN` | The grant moved, in either direction |
| `LocationServicesChange` | `WARN` off, `INFO` on | The device stopped or started being able to locate at all |
| `ProviderChange` | **`WARN`** on a provider or master-switch toggle, `INFO` otherwise | A GPS toggle behind an unchanged master switch emits nothing else, so it is logged at `WARN` and earns a prompt drain. A power-save, airplane or permission field moving stays `INFO` — each already has its own entry |
| `PowerSaveChange` | `INFO` | Battery saver on/off |
| `IntegrityChange` | `WARN` with a blocking signal, else `INFO` | The device-integrity flag set changed |
| `MotionChange`, `ActivityChange` | `DEBUG` | Motion state and activity transitions |
| `GeofenceEntered` / `GeofenceExited` | `INFO` | A fence was crossed |
| `EnabledChange` | `INFO`, `LIFECYCLE` | Session start and stop — **the only place a session boundary reaches your server** |
| `SessionInterrupted` | `WARN`, `LIFECYCLE` | `ready()` found a session left open by a crash |
| `DEVICE_MOTION` | `WARN` on `POOR`, else `INFO`, `LIFECYCLE` | The motion hardware this session is being captured on — see below |

**Deliberately not recorded:** `Location` (that is what the points endpoint is for),
`LocationRejected` (already in the SDK's decision log and read from there at send time — see
`LogType.DECISION`), and `Heartbeat` and `BatteryChange` (volume, with nothing a reader
would act on).

#### The SDK's own log lines

The events above are the SDK's *structured* output. Its running commentary — the lines that
go to `adb logcat` under `Tracker/…` — is recorded too, from the moment a channel exists,
with no `log()` call from you:

```
API_CALL        POST /verify -> HTTP 200 in 617ms, 352 bytes
API_CALL        verdict ACTIVE valid=true ttl=21600s -> carry on
SyncScheduler   Sync trigger registered
LocationStream  Provider went quiet for 94s — restarting the request
```

They arrive as `LogType.MESSAGE`, the same type as your own lines, with the SDK's tag naming
where each came from. One ordered stream rather than two you have to merge by hand.

**`LogSyncConfig.level` decides how much of it you get, and the default is not "all of it".**
The SDK logs at two levels internally: `DEBUG` for its commentary, `WARN` for something that
went wrong. At the default `level = INFO` that means **warnings are recorded and the
commentary is not**. Set `.level(LogLevel.DEBUG)` to capture everything — with the volume
warning in [§15.12](#1512-what-it-costs-and-what-to-leave-off) firmly in mind, because the
SDK writes several lines per fix and a shift is thousands of rows.

**In a release build you get the warnings, and only the warnings.** This is the one part of
the channel that survives release at all, and the line is drawn deliberately:

| | Debug build | Release build |
|---|---|---|
| SDK warnings | recorded | **recorded**, once a channel is configured |
| SDK commentary (`DEBUG`) | recorded at `level = DEBUG` | not present in the artifact |
| `Tracker/…` logcat | written | compiled out |

Logcat goes because anything holding `READ_LOGS` can read it, and a shipped app should not
narrate itself there. The commentary goes for a second reason: its *strings* would have to
ship inside the AAR to be writable at runtime, and those strings describe how the SDK works
to anyone who unzips it — the release build is verified against a sample of them. Warnings
are the small, high-value slice worth paying that price for, and at the default
`level = INFO` they are the only thing the buffer keeps anyway.

So a released app records nothing until a log channel is configured, and records its
warnings once one is. That is the point: the incident worth explaining is on a phone in the
field, running a release build. To see the full commentary, reproduce on a debug build.

**`FieldTrackApi` is the one tag never recorded.** That is the upload log — one line per
request, on both channels. Recorded, a log upload would write an entry describing itself,
which the next upload ships, which writes another. It stays in logcat, where it costs
nothing.

A provider transition also carries a filterable `code` — `GPS_OFF`, `GPS_ON`,
`NETWORK_OFF`, `NETWORK_ON`, `LOCATION_OFF`, `LOCATION_ON` — plus `previous_gps`,
`previous_network` and `previous_enabled` in its `data`.

**The session's motion hardware.** One entry at the head of every session, from the SDK's
own `SensorProbe` rather than from a `TrackerEvent`:

```jsonc
{ "phase": "device_motion", "motion_quality": "DEGRADED",
  "accelerometer": true, "gyroscope": false, "magnetometer": true,
  "significant_motion": false, "step_detector": true, "step_counter": true,
  "barometer": false, "rotation_vector": true, "activity_recognition": true }
```

It is the answer to "why does this track have holes in it". Motion gating decides the
capture cadence, and `motion_quality` is what the SDK decided it could trust:

| `motion_quality` | What the SDK does | Level |
|---|---|---|
| `FULL` | Nothing — accelerometer, gyroscope and a trigger sensor are all present | `INFO` |
| `DEGRADED` | Doubles `motion.stopTimeoutMin`; a stop is detected later and less certainly | `INFO` |
| `POOR` | Forces `CONTINUOUS` — motion gating is not trustworthy on this hardware | **`WARN`** |

`DEGRADED` stays at `INFO` on purpose: it is the ordinary state of a great deal of cheap
hardware, the SDK already compensates for it, and warning on it would warn on half a fleet
and therefore on none of it.

`activity_recognition` is reported separately because `SensorProbe` folds that grant into
`step_detector` and `step_counter` — a `false` on either is *no sensor* or *no permission*,
and a different phone and a prompt are not the same remedy.

Written once per session: on the session-start signal, and again if you call
`configureLogs()` part-way through a session that is already open. Never without an open
session to file it against — an entry describing a session belongs to one.

### 15.6 Writing your own lines

```kotlin
sync.log(
    level = LogLevel.WARN,
    tag = "Dispatch",
    message = "Job 8842 accepted with no route",
    code = "NO_ROUTE",
    data = """{"job_id":"8842","stop_count":0}""",
)

sync.logLifecycle(LifecyclePhase.SESSION_START, tag = "Shift")
```

`data` must be a JSON **object or array as text**, or `null`. Anything else is dropped —
the entry is still stored, but without the payload — because the server's column is
structured and one malformed value would spoil a batch carrying thirty useful entries.

> **Redaction is yours.** Whatever you pass to `log()` is stored on disk and uploaded. A log
> line is the easiest place in any system to leak a token.

Nothing written here reaches logcat. This is the durable channel, which is the whole point:
a live log cannot cover the process that was killed.

### 15.7 `LogRecord` and its enums

What `getLogs()` returns, and the shape the endpoint receives:

```kotlin
data class LogRecord(
    val id: String,                    // SHA-1("<sessionId>:<seq>:<type>:<elapsedRealtimeNanos>"), 40 hex
    val sessionId: String?,            // null for an entry that belongs to no session
    val seq: Long,                     // monotonic per session AND per type, from 0
    val timeMs: Long,                  // device wall clock — display only
    val elapsedRealtimeNanos: Long,    // monotonic since boot — the ordering key
    val level: LogLevel,
    val type: LogType,
    val tag: String,                   // subsystem: "CaptureGate", "ProviderState", your own
    val code: String?,                 // ErrorCode name, event name, or a Reasons string
    val message: String,
    val data: String?,                 // JSON object or array as text
)
```

`id` is deterministic, so a batch that reached the server and lost its response is re-sent
whole and **collides instead of duplicating**. Retrying is free by design.

`sessionId` is `null` for an entry emitted between sessions — a boot, a service start, a
config change. That is a real answer, not a gap: bucketing a boot-time entry under whichever
session happened to be current at upload time would make it lie.

`seq` gaps are **data, not errors**. The buffer is bounded; a device logging for six hours
offline drops its oldest entries, and the gap is what says so. A silent gap would read as
"nothing happened", which is the one thing it does not mean.

| `LogLevel` | |
|---|---|
| `DEBUG` | Motion and activity transitions, and the decision log |
| `INFO` | Default. Provider and battery context, session boundaries |
| `WARN` | Something a person has to act on: a permission moved, a provider toggled, capture suspended |
| `ERROR` | Any `TrackerEvent.Error` |

`LogLevel.admits(minimum)` is public if you want to pre-filter your own lines the same way
the recorder does.

| `LogType` | Volume | Notes |
|---|---|---|
| `EVENT` | low | The `TrackerEvent` stream, flattened |
| `LIFECYCLE` | very low | Session and service boundaries, plus one `DEVICE_MOTION` row per session (§15.5). **Advisory** — this channel is lossy, so annotate a session with it, never treat it as the sole truth for the session's bounds |
| `MESSAGE` | yours | `log()` |
| `DECISION` | **~29 000 per device per 8-hour shift** | Why each fix was accepted or rejected, with the arithmetic. Off by default. Turn it on for a **named device with a ticket open**, never for a fleet — and note it is only recorded at `DEBUG`. It is read from the SDK's existing decision log at send time rather than written twice, and the first `configureLogs()` that enables it skips everything already recorded, so an opt-in ships the next drive rather than the last three days |

`LifecyclePhase` holds the `phase` strings a `LIFECYCLE` entry carries in its `data`:
`session_start`, `session_stop`, `session_interrupted`, `service_start`, `service_stop`,
`process_start`, `boot_completed`, `config_changed`, `device_motion`.

`device_motion` is the one the SDK writes for you rather than one you pass to
`logLifecycle()` — the session's motion hardware, described in §15.5.

### 15.8 Results and failure semantics

```kotlin
sealed interface LogSyncQueue.Result {
    data class Shipped(val count: Int) : Result
    data object Empty : Result
    data class Retry(val reason: String, val retryAfterMs: Long? = null) : Result
    data class Rejected(val statusCode: Int) : Result
}
```

| Status | Behaviour |
|---|---|
| **2xx** | Stored. `duplicates` in the response are entries the server already held — not an error |
| **401 / 403** | **Terminal for this channel, non-destructive.** Shipping and recording both halt, the buffer is kept, tracking and the point queue are untouched |
| **404 / 405 / 501** | **There is no endpoint here.** Same halt: every later batch would collect the same answer, so the SDK stops asking rather than discarding your diagnostics a batch at a time. This is the case where your backend has not implemented the endpoint at all — it costs one request per process and nothing else |
| **413** | Over the server's 500-entry ceiling. The batch is **dropped**, not retried — it will never be accepted |
| **Other 4xx** | Permanently unacceptable. Batch dropped, drain moves on |
| **503 / timeout / no response** | Transient. Entries stay buffered and retry with backoff. `Retry-After` is honoured |

**This is the inverse of the points queue, on purpose.** For a position, dropping is data
loss and a retry loop is the lesser evil; for a log, a poison batch that blocks the buffer
forever costs battery and buys nothing.

Recovery from any halt is the next `configureLogs()` — which, if you call it in
`Application.onCreate` as recommended, means the next process start picks up an endpoint you
deployed in the meantime, with the buffer intact.

`Retry("already draining")`, `Retry("log sync not configured")` and `Retry("no transport")`
describe the SDK's own situation rather than a failed exchange. None is an upload error.

### 15.9 The wire format

```
POST <logEndpoint>
Content-Type: application/json; charset=utf-8
Content-Encoding: gzip
Authorization: <your header>
```

```jsonc
{
  "device_id":   "8f14e45f-ceea-467a-9c1a-2b0a1e1f9c31",  // SAME id as the points envelope
  "uploaded_at": 1719400123456,                           // device wall clock at send

  // Constant for the life of a process, so it rides the batch rather than every entry.
  "app":    { "package": "com.acme.field", "version": "3.4.1", "build": 3401, "sdk": "1.0.8" },
  "device": { "manufacturer": "samsung", "model": "SM-A546E", "os": 34 },

  "logs": [
    {
      "id":            "3f1a…",                     // 40 hex, the dedupe key
      "session_id":    "20260907-143512-1f0c8a2e",  // per row, authoritative; null is legal
      "seq":           1482,
      "elapsed_nanos": "918273645000000",           // a STRING — see below
      "time":          1719400000000,
      "level":         "warn",                      // debug | info | warn | error
      "type":          "event",                     // event | decision | message | lifecycle
      "tag":           "ProviderState",
      "code":          "GPS_OFF",
      "message":       "GPS off",
      "data":          { "gps": false, "network": true, "previous_gps": true }
    }
  ]
}
```

**`elapsed_nanos` travels as a string.** The count passes 2^53 after 104 days of uptime and
a JSON number rounds silently past that. Order on it, never on `time` — the wall clock can
jump backwards mid-session; `elapsedRealtimeNanos` cannot.

Expected response:

```jsonc
{ "accepted": 487, "duplicates": 13, "rejected": 0, "batch_id": "lb_01J8…" }
```

Only two things get an entry rejected server-side, and both are structural: a missing `id`
(the dedupe key) and a missing or non-numeric `elapsed_nanos` (the ordering key). Everything
else should be coerced rather than refused — a log line that cannot be stored perfectly is
still worth storing imperfectly.

`data` is type-specific and should be stored **verbatim and unvalidated**. The useful field
is the one nobody modelled in advance.

| `type` | Shape of `data` |
|---|---|
| `event` | The flattened `TrackerEvent` — e.g. `{"gps":false,"network":true,"enabled":true,"permission":"FULL","previous_gps":true}` |
| `decision` | `{"verdict":"REJECT","reason":"NLP Fallback","latitude":23.02,"longitude":72.57,"accuracy":48.0,"bearing_deg":118.4,"has_speed":true,"has_bearing":true,"filter_lat":23.0198,"filter_lng":72.5731,"sigma":6.4,"threshold":4.0,"distance_moved_m":287.4,"effective_speed_mps":23.9,"motion_state":"MOVING","point_uuid":"…"}` — `filter_lat`/`filter_lng` are where the filter thought the device was, which is what makes a rejection readable; `point_uuid` is present only when the verdict is `ACCEPT` |
| `lifecycle` | `{"phase":"session_start"}` |
| `message` | Whatever you passed. May be `{}` |

One complete exchange — headers, a four-entry body, the response, and every other answer the
endpoint can give — is [§15.10](#1510-a-sample-request-and-response).

### 15.10 A sample request and response

One exchange, end to end. This is what a backend implementing the endpoint has to accept, and
what the device does with each answer.

#### The request

```http
POST /v1/logs/batch HTTP/1.1
Host: api.acme.test
Content-Type: application/json; charset=utf-8
Content-Encoding: gzip
Authorization: Bearer f3c1…            <- the log credential, not the points one
Accept-Encoding: gzip
```

The body below is what the server sees **after** decoding `Content-Encoding: gzip`. Four
entries, oldest first — the session's motion line, a flattened event, a host line, and one
decision:

```json
{
  "device_id": "8f14e45f-ceea-467a-9c1a-2b0a1e1f9c31",
  "uploaded_at": 1719400123456,
  "app": {
    "package": "com.acme.field",
    "version": "3.4.1",
    "build": 3401,
    "sdk": "1.0.8"
  },
  "device": {
    "manufacturer": "samsung",
    "model": "SM-A546E",
    "os": 34
  },
  "logs": [
    {
      "id": "aff5f3e0a28c9ec0daf73553fa48fefbe73588b4",
      "session_id": "20260907-143512-1f0c8a2e",
      "seq": 0,
      "time": 1719399512004,
      "elapsed_nanos": "918273645000000",
      "level": "info",
      "type": "lifecycle",
      "tag": "Motion",
      "code": "DEVICE_MOTION",
      "message": "Motion DEGRADED - accelerometer, magnetometer, step detector",
      "data": {
        "phase": "device_motion",
        "motion_quality": "DEGRADED",
        "accelerometer": true,
        "gyroscope": false,
        "magnetometer": true,
        "significant_motion": false,
        "step_detector": true,
        "step_counter": true,
        "barometer": false,
        "rotation_vector": true,
        "activity_recognition": true
      }
    },
    {
      "id": "8cdaa79826af5d2747293c152ecdecb03b3248d3",
      "session_id": "20260907-143512-1f0c8a2e",
      "seq": 1482,
      "time": 1719400000000,
      "elapsed_nanos": "918891245000000",
      "level": "warn",
      "type": "event",
      "tag": "ProviderState",
      "code": "GPS_OFF",
      "message": "GPS off",
      "data": {
        "gps": false,
        "network": true,
        "enabled": true,
        "permission": "FULL",
        "previous_gps": true,
        "previous_network": true,
        "previous_enabled": true
      }
    },
    {
      "id": "eb8761f2fbb52e75294865a668415ac13d981873",
      "session_id": "20260907-143512-1f0c8a2e",
      "seq": 12,
      "time": 1719400004120,
      "elapsed_nanos": "918895365000000",
      "level": "warn",
      "type": "message",
      "tag": "Dispatch",
      "code": "NO_ROUTE",
      "message": "Job 8842 accepted with no route",
      "data": { "job_id": "8842", "stop_count": 0 }
    },
    {
      "id": "c97bceddc5852d219924b0318dfac530a2424b52",
      "session_id": "20260907-143512-1f0c8a2e",
      "seq": 29431,
      "time": 1719400005000,
      "elapsed_nanos": "918896245000000",
      "level": "debug",
      "type": "decision",
      "tag": "AcceptancePipeline",
      "code": "NLP Fallback",
      "message": "REJECT — NLP Fallback",
      "data": {
        "verdict": "REJECT",
        "reason": "NLP Fallback",
        "latitude": 23.0225,
        "longitude": 72.5714,
        "accuracy": 48.0,
        "bearing_deg": 118.4,
        "has_speed": true,
        "has_bearing": true,
        "filter_lat": 23.0198,
        "filter_lng": 72.5731,
        "sigma": 6.4,
        "threshold": 4.0,
        "distance_moved_m": 287.4,
        "effective_speed_mps": 23.9,
        "motion_state": "MOVING"
      }
    }
  ]
}
```

Three things to notice before writing the handler:

- **`elapsed_nanos` is a string**, and it is the ordering key. `time` is the device wall clock
  and is display only — it can jump backwards mid-session.
- **`seq` is per session *and* per type.** The `0`, `1482`, `12` and `29431` above are four
  independent counters, not a broken sequence. A gap inside one counter is the bounded buffer
  evicting its oldest rows: data, not an error.
- **`data` is not the same shape twice.** Store it verbatim and unvalidated — the useful field
  is always the one nobody modelled in advance. An `ACCEPT` decision carries one more key,
  `point_uuid`, naming the point that reached your points endpoint.

#### The response

```http
HTTP/1.1 200 OK
Content-Type: application/json; charset=utf-8
```

```json
{ "accepted": 3, "duplicates": 1, "rejected": 0, "batch_id": "lb_01J8XM4Z7QK2W9R0" }
```

`accepted + duplicates + rejected` must equal the number of entries the device sent — 4 here.
The SDK does not read the counts (a 2xx settles the batch whatever they say), but a person
reconciling a device against a dashboard does, and a number they cannot account for is a number
that starts a bug report. If your server synthesises rows of its own — the reference backend
writes one `DEVICE_INFO` line per session out of the envelope's `app` and `device` blocks —
leave them out of all three counts.

`duplicates` is not an error. It is the expected cost of at-least-once delivery: a batch whose
response was lost is re-sent whole, and every entry in it collides on its derived id.

#### Every other answer, and what the device does with it

| Response | The SDK's behaviour | `LogSyncQueue.Result` |
|---|---|---|
| `200` / `201` / `204` | Rows settled, drain continues with the next batch | `Shipped(n)` |
| `401`, `403` | **Channel halts** — shipping and recording both stop. The buffer is kept, and tracking, the point queue and stored positions are untouched. Recovery is the next `configureLogs()` | `Rejected(401 or 403)` |
| `404`, `405`, `501` | **Channel halts** the same way — there is no endpoint at this URL, and every later batch would collect the same answer. One request per process, then silence | `Rejected(code)` |
| `413` | Batch **dropped**, not retried: it is over the server's entry ceiling and will never be accepted | drain continues |
| Other `4xx` | Batch dropped, drain continues with the next batch | drain continues |
| `408`, `429` | Retryable in 4xx clothing. Nothing settled | `Retry(msg, retryAfterMs)` |
| `5xx`, timeout, no response | Nothing settled, entries stay queued, backoff. A `Retry-After` reschedules the one-shot | `Retry(msg, retryAfterMs)` |

```json
// 503, with Retry-After: 30
{ "error": "database unavailable" }
```

A `503` costs the device one wasted radio wake. A `404` costs it one per process. Both are safe
answers while you are still building the endpoint — [§15.8](#158-results-and-failure-semantics)
is why those two are treated so differently.

#### Reproducing it with `curl`

The id is derived, so it can be computed outside the SDK. This posts one entry, and is the
fastest way to prove a new endpoint accepts the shape:

```bash
SESSION=20260907-143512-1f0c8a2e
NANOS=918891245000000
ID=$(printf '%s' "$SESSION:1482:event:$NANOS" | sha1sum | cut -d' ' -f1)

curl -sS -X POST https://api.acme.test/v1/logs/batch \
  -H 'content-type: application/json; charset=utf-8' \
  -H 'authorization: Bearer <log token>' \
  --data-binary @- <<JSON
{
  "device_id": "8f14e45f-ceea-467a-9c1a-2b0a1e1f9c31",
  "uploaded_at": $(date +%s)000,
  "app":    { "package": "com.acme.field", "version": "3.4.1", "build": 3401, "sdk": "1.0.8" },
  "device": { "manufacturer": "samsung", "model": "SM-A546E", "os": 34 },
  "logs": [{
    "id": "$ID",
    "session_id": "$SESSION",
    "seq": 1482,
    "time": $(date +%s)000,
    "elapsed_nanos": "$NANOS",
    "level": "warn",
    "type": "event",
    "tag": "ProviderState",
    "code": "GPS_OFF",
    "message": "GPS off",
    "data": { "gps": false, "network": true, "previous_gps": true }
  }]
}
JSON

# {"accepted":1,"duplicates":0,"rejected":0,"batch_id":"lb_…"}
# run it a second time:
# {"accepted":0,"duplicates":1,"rejected":0,"batch_id":"lb_…"}
```

The second run is the contract working: same inputs, same id, no second row.

### 15.11 Schema architecture

Two stores, one derived key holding them together.

```
  DEVICE                                     BACKEND
  fieldtrack-logs-<yourPackage>.db           your database
  ────────────────────────────────           ──────────────────────────────────────

   log_entry                                  log_batches       one row per exchange
     id         INTEGER PK  local only          batch_id   PK
     uid        TEXT UNIQUE ────┐               device_id
     sessionId  TEXT NULL       │               status_code, entry_count,
     seq        INTEGER         │               accepted / duplicates / rejected
     …                          │               app_* / device_*   (per batch)
     syncState  0 queued        │                    │
                1 settled       │                    │ batch_id
                                │                    ▼
   log_counter                  │             session_logs      one row per entry
     seq:<session>:<type>       │               id          TEXT PK  ◄── uid
     decision:<session>         │               device_id   ────────►  your devices
                                │               session_id  ────────►  your sessions
   positions live in a          │               seq, elapsed_nanos, time
   SEPARATE file:               │               level, type, tag, code, message
   fieldtrack-<pkg>.db          │               data jsonb ─ point_uuid ─►  your points
                                │               batch_id
                                │
                                └──── POST /v1/logs/batch ────►
```

The device's `uid` **becomes** the server's primary key. That is the whole dedupe design: a
re-sent batch collides instead of duplicating, so the device is free to settle rows on a
response it may never have received.

#### On the device

A second Room database, created only when `configureLogs()` is called, named for your package
so two apps embedding the SDK cannot collide, and kept **separate from the file holding
positions** — which is what makes a credential failure on the log endpoint structurally unable
to reach a stored point.

It is private to the SDK and is not public API: read it through `getLogs()` and
`pendingLogCount()`, never by opening the file. The effective schema, for understanding what
those calls are reading:

```sql
CREATE TABLE log_entry (
    id                   INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,  -- local insert order
    uid                  TEXT    NOT NULL,   -- the wire id; the server's PK
    sessionId            TEXT,               -- NULL for an entry outside any session
    seq                  INTEGER NOT NULL,
    timeMs               INTEGER NOT NULL,
    elapsedRealtimeNanos INTEGER NOT NULL,
    level                TEXT    NOT NULL,
    type                 TEXT    NOT NULL,
    tag                  TEXT    NOT NULL,
    code                 TEXT,
    message              TEXT    NOT NULL,
    data                 TEXT,               -- JSON as text, or NULL
    syncState            INTEGER NOT NULL    -- 0 queued, 1 shipped or dropped
);
CREATE UNIQUE INDEX index_log_entry_uid       ON log_entry(uid);
CREATE        INDEX index_log_entry_syncState ON log_entry(syncState);
CREATE        INDEX index_log_entry_timeMs    ON log_entry(timeMs);

CREATE TABLE log_counter (
    key   TEXT PRIMARY KEY NOT NULL,
    value INTEGER NOT NULL
);
```

Four decisions in that schema are worth knowing about, because they are what the API above
behaves like:

| | |
|---|---|
| `id` is local, `uid` is the identity | Insert order is what a batch is a prefix of; `uid` is what the server dedupes on. Unique on `uid` means a double-record is dropped here rather than sent twice |
| `syncState` is the only send filter | Every `level` and `type` decision is made **before** a row exists, so a queued batch is always a contiguous prefix and settling by cursor is exact |
| The ring evicts by `id`, whatever `syncState` says | Shipped and unshipped alike. A bounded buffer that refused to drop unsent rows would be unbounded on exactly the device that cannot reach a server |
| Two counters outlive the process | `seq:<sessionId>:<type>` is the next sequence number for that pair; `decision:<sessionId>` is how far the decision log has been shipped, as an `elapsedRealtimeNanos`. A `seq` restarting at 0 after a process death would replay ids the server already holds |

The entry id is derived, never allocated:

```
uid = SHA1("<sessionId>:<seq>:<type>:<elapsedRealtimeNanos>")     40 lowercase hex

SHA1("20260907-143512-1f0c8a2e:1482:event:918891245000000")
  = 8cdaa79826af5d2747293c152ecdecb03b3248d3
```

`sessionId` is hashed as the literal `null` when there is none. `elapsedRealtimeNanos` is in the
digest as well as `seq` because `seq` alone repeats if a session's entries are all evicted and
the counter restarts — a legible gap in a log, and a silent overwrite of somebody else's row
without that term.

#### On your backend

Two tables. This is the shape the endpoint implies rather than a schema the SDK enforces —
column types are PostgreSQL and translate directly:

```sql
CREATE TABLE log_batches (                    -- created first: session_logs points at it
    batch_id            text        PRIMARY KEY,
    device_id           text        NOT NULL,
    received_at         timestamptz NOT NULL DEFAULT now(),
    status_code         int         NOT NULL,
    entry_count         int         NOT NULL,
    accepted            int         NOT NULL DEFAULT 0,
    duplicates          int         NOT NULL DEFAULT 0,
    rejected            int         NOT NULL DEFAULT 0,
    error               text        NULL,
    gzip                boolean     NOT NULL DEFAULT false,
    app_package         text,
    app_version         text,
    app_build           bigint,
    sdk_version         text,
    device_manufacturer text,
    device_model        text,
    device_os           int
);

CREATE INDEX ON log_batches (device_id, received_at DESC);

CREATE TABLE session_logs (
    id            text        PRIMARY KEY,          -- the entry's 40-hex id
    device_id     text        NOT NULL,
    session_id    text        NULL,                 -- nullable is required, not optional
    seq           bigint      NOT NULL DEFAULT 0,
    elapsed_nanos bigint      NOT NULL,             -- arrives as a string; store 64-bit
    time          timestamptz NOT NULL,
    received_at   timestamptz NOT NULL DEFAULT now(),
    level         text        NOT NULL DEFAULT 'info',
    type          text        NOT NULL DEFAULT 'message',
    tag           text        NOT NULL DEFAULT 'app',
    code          text        NULL,
    message       text        NOT NULL DEFAULT '',
    data          jsonb       NULL,
    batch_id      text        NOT NULL REFERENCES log_batches(batch_id)
);

CREATE INDEX ON session_logs (device_id, received_at DESC);
CREATE INDEX ON session_logs (session_id, elapsed_nanos);
CREATE INDEX ON session_logs (device_id, code);
```

`session_logs` — one row per entry:

| Column | Type | Notes |
|---|---|---|
| `id` | `text` **PK** | The 40-hex `id` from the entry. Being the primary key is what makes an at-least-once re-send collide instead of duplicating |
| `device_id` | `text` | From the envelope, or from your credential. **The join to your points data** |
| `session_id` | `text` **null** | Per-row value wins, then the envelope's, then `null`. Nullable is required, not optional |
| `seq` | `bigint` | Per session **and per type**. Gaps are eviction, not loss of integrity |
| `elapsed_nanos` | `bigint` | Arrives as a string; store as 64-bit. **Order on this** |
| `time` | `timestamptz` | Device wall clock. Display only |
| `received_at` | `timestamptz` | Your clock, at insert. What you page by |
| `level` | `text` | `debug` / `info` / `warn` / `error`. Coerce an unknown value to `info` rather than rejecting |
| `type` | `text` | `event` / `decision` / `message` / `lifecycle`. Coerce an unknown value to `message` |
| `tag` | `text` | ≤ 64 chars |
| `code` | `text` **null** | ≤ 64 chars. **The column a week of one device's logs gets grouped by** — index it |
| `message` | `text` | ≤ 4096 chars. Truncate, do not reject |
| `data` | `jsonb` **null** | Verbatim, never validated |
| `batch_id` | `text` | The batch it arrived in |

`log_batches` — one row per HTTP exchange, **written whatever the outcome**, including the
`413`s and the auth failures:

| Column | Type | Notes |
|---|---|---|
| `batch_id` | `text` **PK** | Returned to the device as `batch_id` |
| `device_id` | `text` | |
| `received_at` | `timestamptz` | |
| `status_code` | `int` | What you answered |
| `entry_count` | `int` | What arrived |
| `accepted` / `duplicates` / `rejected` | `int` | What you did with them |
| `error` | `text` **null** | Why, when there was one |
| `gzip` | `boolean` | Whether the body was compressed |
| `app_package` / `app_version` / `app_build` / `sdk_version` | `text` / `text` / `bigint` / `text` | From the envelope's `app` |
| `device_manufacturer` / `device_model` / `device_os` | `text` / `text` / `int` | From the envelope's `device` |

Keep it separate from your point-ingest audit trail so a log flood is visible without polluting
it. The per-batch `app` and `device` metadata lives here rather than on every entry — it is the
same 200 bytes on each one otherwise, and it is what a "works on my device" ticket is actually
about.

**No row in `log_batches` at all** means the request never reached your handler: a 404, a
network failure, or it never left the device. **A row** means `status_code`, `rejected` and
`error` say exactly what happened.

Only two things should get an entry rejected, and both are structural: a missing `id` (the
dedupe key) and a missing or non-numeric `elapsed_nanos` (the ordering key). Everything else is
coerced — a log line that cannot be stored perfectly is still worth storing imperfectly.

#### The four joins this schema exists for

| From | To | Answers |
|---|---|---|
| `session_logs.device_id` | your points/devices table | "the track has a hole at 14:02 — what was the phone doing?" This is why the two channels must send the **same** `device_id` string |
| `session_logs.session_id` | your sessions table | The panel: one session's entries in order, `elapsed_nanos` ascending. `null` belongs to the device, not to a session, and is correct |
| `data ->> 'point_uuid'` | a stored point | An `ACCEPT` decision names the point that reached you; a `REJECT` is a point that never did, at the place where the polyline draws a straight line instead |
| `session_logs.batch_id` | `log_batches` | "was this entry the last thing that got through" — and which build the device was running when it did |

#### Retention

`decision` is the same order of volume as your points table and its rows are wider, so it is
worth pruning on its own schedule rather than with everything else:

```sql
DELETE FROM session_logs WHERE type = 'decision' AND received_at < now() - interval '7 days';
DELETE FROM session_logs WHERE level = 'debug'   AND received_at < now() - interval '14 days';
DELETE FROM session_logs WHERE received_at < now() - interval '30 days';
DELETE FROM log_batches  WHERE received_at < now() - interval '7 days';
```

Deleting a session should delete its logs: a log line naming a deleted session is that session's
data. The device does its own pruning independently — settled rows past `retentionHours`, and
the ring at `bufferCapacity` — so nothing here needs the device's cooperation.

### 15.12 What it costs, and what to leave off

| | |
|---|---|
| Entries per request | `batchSize`, default 200, hard ceiling 500 |
| Requests | one per `uploadIntervalMinutes` (default 15), plus one per `nudgeCooldownMs` when a `WARN` lands |
| Body | gzipped by default, roughly 8:1 |
| Device storage | `bufferCapacity` rows, default 5000, oldest evicted |
| Device retention | shipped entries pruned after `retentionHours` (default 72); queued entries are never pruned by age |

Defaults are sized for a fleet: `INFO`, no `DECISION`, a quarter-hourly drain. That is a few
kilobytes per device per shift.

**`LogType.DECISION` is the one to think about.** It is the same order of volume as
`track_point` and the rows are wider. Enable it for one device while a ticket is open, and
turn it off again — a `DEBUG` flag set during a ticket and never cleared is how one device
ends up shipping 29 000 rows a day for a year.

**`level = DEBUG` is the second one.** It turns on the SDK's own running commentary
([§15.5](#155-what-the-sdk-records-without-being-asked)) — several lines per fix, so at a
15-second cadence, thousands of rows a shift. Same rule: one device, while a ticket is open.
At the default `INFO` the SDK contributes its warnings and nothing else, which is what the
"few kilobytes per shift" figure above assumes.

If you never want the channel at all, build your `SyncConfig` with `.syncLogs(false)` — or
call `disableLogSync()` after the fact. Doing nothing now means the channel is on.

---

## 16. Snap module — road matching

Optional. With no provider installed, `buildTrack()` never leaves the device and never emits a
`snap_unavailable` warning.

```kotlin
tracker.setRoadSnapProvider(
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
| `client` | built-in OkHttp | Supply your own. Retrofit runs on top of whatever you pass, so proxies, pinning and interceptors are all still yours |
| `chunkSize` | provider default | Coordinates per `/match` request |
| `searchRadiusM` | provider default | Search radius per coordinate |
| `headers` | empty | Extra request headers |
| `minConfidence` | provider default | Matchings below this are discarded and keep raw coordinates. `0` accepts everything |
| `cacheEntries` | `ChunkCache.DEFAULT_MAX_ENTRIES` | Matched chunks kept between calls — the whole value when a live map rebuilds the track on every fix. `0` disables |

It **degrades per chunk, never wholesale**: a trace split across ten requests does not lose the
nine that succeeded because the tenth was rate-limited.

### 16.1 Writing your own provider

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

## 17. Diagnostics

Three layers, from rawest to most interpreted.

### 17.1 Layer 1 — raw fixes

Requires `persistence.persistRawFixes = true`.

```kotlin
val raw: List<RawFix> = tracker.getRawFixes(sessionId)
```

```kotlin
data class RawFix(
    val timeMs: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val bearingDeg: Float,      // 0f when the provider reported no bearing
    val provider: String,
    val integrityFlags: Int,    // device-integrity bitmask when received — see §20.4
)
```

### 17.2 Layer 2 — raw points

Requires `persistence.persistRawPoints = true`. Every judged fix in point form, accepted or not
— the layer to reach for when the question is "why is there **no** point here" rather than "why
is this point wrong". `RawPoint` has the same columns as `TrackPoint` plus:

| Field | Meaning |
|---|---|
| `verdict` | `"ACCEPT"`, `"SKIP"` or `"REJECT"` |
| `reason` | The `Reasons` vocabulary string |
| `isAccepted` | `verdict == "ACCEPT"` |

`RawPoint.uuid` joins back to the stored `TrackPoint` for accepted fixes.

`RawPoint` carries `providerFlags` too, and on this layer it is worth more than on the accepted
one: a run of rejects whose snapshot shows `accuracyAuthorization = ACCURACY_REDUCED` is a
permission problem, not a filter problem, and the two look identical from the point table alone.

### 17.3 Layer 3 — the decision log

On by default (`persistence.persistDecisions = true`).

```kotlin
val decisions: List<FixDecision> = tracker.getDecisions(sessionId, limit = 200, offset = 0)
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

`motionState` is the motion layer's verdict at the time the fix was judged. **In releases
before this one it was always `STOPPED`** — the field had no writer anywhere in the SDK, so
every row ever written recorded the default, on a motorway and on a desk alike. It is now
stamped for real. It remains a label and nothing more: no gate reads it, because capture is
never gated on motion detection.

### 17.4 `Reasons` — the reason vocabulary **is API**

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
| `STILLNESS_VETO` | `Stillness Veto` |
| `HEURISTIC_GATE` | `Heuristic Gate` |
| `SESSION_CLOSED` | `Session Closed` |
| `MOCK_LOCATION` | `Mock Location` |
| `INVALID_COORDINATES` | `Invalid Coordinates` |
| `STALE_FIX` | `Stale Fix` |
| `REBOOT_BOUNDARY` | `Reboot Boundary` |
| `OUT_OF_ORDER` | `Out Of Order` |

---

## 18. Java interop

Every entry point is Java-callable. `getInstance`, `TrackerConfig.builder()` and
`SyncConfig.builder()` are `@JvmStatic`; `PointQuery`, `TrackOptions` and the paged query
methods carry `@JvmOverloads`.

```java
Tracker tracker = Tracker.getInstance(context);

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

## 19. ProGuard / R8

**You do not need to add any rules.** Each AAR ships `consumer-rules.pro` and the published
artifacts are already R8-minified.

What this means in practice:

- Public API types and the documented extension seams (`TrackLogger`, `RoadSnapProvider`,
  `SyncTransport`) keep their names.
- Model classes (`Track`, `TrackOptions`, `TrackSegment`, `TrackStats`, `TrackJsonPoint`,
  `StopNode`, `ArrowAnchor`, `LiveTrackUpdate`, `PuckState`, `SegmentType`, `Smoothing`, …) keep
  public class and member names, so named accessors survive.
- Enum constants are preserved — persisted rows and wire values use `name`/`valueOf`.
- The log channel's types (`LogSyncConfig` and its `Builder`, `LogRecord`, `LogLevel`,
  `LogType`, `LifecyclePhase`, `LogSyncQueue.Result`, and the wire DTOs) keep their names
  too, so `configureLogs(...)` compiles against the published AAR unchanged.
- SDK logging is compiled out of release builds entirely.
- No sources JAR is published; a Javadoc JAR with rendered public API HTML is.

If you hit a `NoSuchMethodError` or a serialization failure after enabling minification in your
own app, that is a bug worth reporting — do **not** paper over it with
`-keep class com.field360.tracker.** { *; }`, which would disable shrinking for the whole SDK
inside your APK.

---

## 20. Device integrity

A second security layer beside the license gate. It answers one question — *can this
device fabricate the location data it is about to send?* — and lets you decide what to do
about the answer.

**Release only.** Every probe is skipped and every policy ignored when the host app is
debuggable, exactly as the license check is waived there. Development builds, emulators
and instrumentation runs are unaffected, with nothing to remember to switch off and
nothing that could survive into production.

### 20.1 What is checked

| Signal | How | Default |
|---|---|---|
| `ACCESSIBILITY_SERVICE_ACTIVE` | A non-system accessibility service is enabled — the usual driver for UI automation | `WARN` |
| `DEVELOPER_MODE_ENABLED` | `Settings.Global.DEVELOPMENT_SETTINGS_ENABLED` | `WARN` |
| `ADB_ENABLED` | `Settings.Global.ADB_ENABLED` | `WARN` |
| `HOOKING_FRAMEWORK_DETECTED` | Frida/Xposed: mapped libraries, agent thread names, default ports 27042/27043, `TracerPid`. Weighted; raised at confidence ≥ 60 | **`BLOCK`** |
| `DEBUGGER_ATTACHED` | `TracerPid` non-zero or `Debug.isDebuggerConnected()` | **`BLOCK`** |
| `AUTO_TIME_DISABLED` | Automatic date/time **and** automatic time zone both off | `WARN` |
| `TIMEZONE_MISMATCH` | Device time zone not used in the serving cellular network's country | `WARN` |
| `CLOCK_SKEWED` | System clock disagrees with **GNSS UTC** by more than `maxClockSkewMs` | `WARN` |
| `MOCK_LOCATION_APP_SELECTED` | A visible installed package holds the mock-location app-op | **`BLOCK`** |
| `MOCK_LOCATION_FIX` | The platform flagged a delivered fix as mock | **`BLOCK`** |

No new permission is required, and `QUERY_ALL_PACKAGES` is deliberately **not** requested
— see [§20.5](#205-limits-worth-knowing).

### 20.2 Policy

Three levels per group of signals:

| Policy | Reported to the host | Stamped on points and uploaded | Blocks `ready()`/`start()` |
|---|---|---|---|
| `ALLOW` | no | no | no |
| `WARN` | yes | yes | no |
| `BLOCK` | yes | yes | yes |

```kotlin
val config = TrackerConfig.builder()
    .securityEnabled(true)                                   // default
    .hookingPolicy(IntegrityPolicy.BLOCK)                    // default
    .mockLocationIntegrityPolicy(IntegrityPolicy.BLOCK)      // default
    .accessibilityPolicy(IntegrityPolicy.WARN)               // default
    .developerModePolicy(IntegrityPolicy.WARN)               // default
    .clockPolicy(IntegrityPolicy.WARN)                       // default
    .accessibilityAllowlist(setOf("com.yourco.kiosk"))
    .maxClockSkewMs(120_000)                                 // default
    .integrityRecheckIntervalMs(15 * 60_000)                 // default; 0 disables
    .build()
```

`accessibility` defaults to `WARN` on purpose: accessibility services are also how blind
and motor-impaired users operate a phone, and blocking on them would lock those users out
of your app. Services installed as part of the system image never raise a finding.

Setting `mockLocationIntegrityPolicy(BLOCK)` forces `mockLocationPolicy = REJECT`; the two
cannot be left contradicting each other. An SDK that refuses to run on a mocked device cannot
also be storing mocked points, so the stricter of the two wins, silently — a validation error
would fail `ready()` over a combination the SDK can resolve correctly on its own.

**A debuggable build is exempt from that forcing.** Both settings default to strict, so
without the exemption a developer feeding a fake route through the emulator got a total,
silent data loss: every fix dropped before it reached storage, nothing in the database, and
nothing in the event flow saying why. A debuggable build already waives the whole integrity
layer, and this is the same waiver applied consistently.

In practice:

| Build | Mock fixes |
|---|---|
| Debuggable | Stored, and uploaded with `is_mock: true` |
| Release | Dropped, unless you set `mockLocationIntegrityPolicy(WARN)` **and** `mockLocationPolicy(MockPolicy.FLAG)` deliberately |

`isMock` comes from the platform's own `Location.isMock`, which cannot be argued with. It is
Android-only.

### 20.3 Reading the result

```kotlin
when (val result = tracker.ready(config)) {
    is TrackerResult.Error ->
        if (result.code == ErrorCode.DEVICE_INTEGRITY_BLOCKED) {
            // result.message names the blocking signals
            val report = tracker.integrity()
            showBlockedScreen(report.blockingSignals)
        }
    is TrackerResult.Ok -> Unit
}

// Live, and re-checked inside the health loop while a session is open.
tracker.integrityState()
    .onEach { report -> banner.isVisible = report.findings.isNotEmpty() }
    .launchIn(scope)

// Force a fresh evaluation — reads /proc, the package list and a loopback socket.
val fresh = tracker.checkIntegrity()
```

`TrackerEvent.IntegrityChange` is emitted when the flag set changes, not on every
evaluation. A `BLOCK` finding also arrives as `TrackerEvent.Error` with
`ErrorCode.DEVICE_INTEGRITY_BLOCKED`, and mid-session it ends the session.

`IntegrityReport.waived` is `true` in a debuggable build: nothing was probed, and the
empty `findings` list is not a claim that the device is clean.

### 20.4 On the wire and in storage

Every accepted point carries `integrityFlags` — the bitmask of every signal observed when
it was captured, `WARN` and `BLOCK` alike. It is persisted on the point, readable through
`TrackPoint.integrityFlags`, and uploaded by `fieldtrack-sync`:

```json
{
  "uuid": "…",
  "is_mock": false,
  "integrity_flags": 130,
  "integrity_signals": ["DEVELOPER_MODE_ENABLED", "MOCK_LOCATION_APP_SELECTED"]
}
```

The bit assignments are frozen: `ACCESSIBILITY_SERVICE_ACTIVE` = 1, `DEVELOPER_MODE_ENABLED`
= 2, `ADB_ENABLED` = 4, `HOOKING_FRAMEWORK_DETECTED` = 8, `DEBUGGER_ATTACHED` = 16,
`AUTO_TIME_DISABLED` = 32, `TIMEZONE_MISMATCH` = 64, `MOCK_LOCATION_APP_SELECTED` = 128,
`MOCK_LOCATION_FIX` = 256, `CLOCK_SKEWED` = 512.

Both fields default, so a backend that has never seen them keeps parsing. `0` means
"nothing observed" — which is also what a debuggable build and a host with the layer
disabled send, so tell "clean" from "not evaluated" by the client version, not by this
column.

**Evaluate server-side as well.** These flags are advisory input to a server rule, never
the whole defence: an attacker who has already hooked the process can patch the client
that produces them. The value is that tampering has to defeat both sides.

### 20.5 Limits worth knowing

- **Package visibility.** From Android 11 the SDK cannot enumerate every installed app, so
  `MOCK_LOCATION_APP_SELECTED` catches a fake-GPS app only where the platform makes it
  visible. `QUERY_ALL_PACKAGES` would fix that and is deliberately not requested — it is a
  Play-policy declaration for every host, for a signal `MOCK_LOCATION_FIX` already covers
  the moment a fake fix arrives. Add `<queries>` entries in your own manifest if you have a
  specific list you care about.
- **Client-side detection is not proof.** It raises cost; it does not make spoofing
  impossible.
- **The debuggable waiver is a real surface.** A repackaged APK can set the flag — but
  re-signing changes the signing certificate, which is what the license token binds to.
- **Emulators skip the Frida port scan.** CI images run enough loopback tooling to make it
  noise.

### 20.6 Build-time checks

The SDK ships lint rules inside its AARs, so they run in **your** build:

| Issue | Severity | Fires on |
|---|---|---|
| `FieldTrackSecurityDisabled` | fatal | `securityEnabled(false)` or `IntegrityPolicy.ALLOW` outside `src/debug/` |
| `FieldTrackMockLocationAllowed` | fatal | `MockPolicy.ALLOW` |
| `FieldTrackDebuggableRelease` | fatal | `android:debuggable="true"` in the manifest |
| `FieldTrackLicenseHardcoded` | warning | A license token written as a string literal |

Fatal issues fail `assembleRelease` through AGP's `lintVital` — which is the point: the
runtime layer waives itself in debug builds, so only the build can catch a release that
shipped with it switched off. Overrides belong in `src/debug/`, where the rules do not
fire and the runtime waiver already applies.

---

## 21. Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `ready()` returns `LICENSE_MISSING` | Release build with no token | Supply the token via `.license(...)` in config ([§2](#2-license-token)) |
| `start()` returns `NOT_READY` | `ready()` not called or it failed | Check the `TrackerResult` from `ready()` |
| `start()` returns `PERMISSION_DENIED` | No location permission | Walk the ladder in [§4](#4-permissions) |
| `start()` returns `PLAY_SERVICES_UNAVAILABLE` | No Google Play Services | Set `providerType = GPS_ONLY` |
| Empty track, no points at all | `NETWORK_ONLY` with a tight accuracy ceiling | `validate()` rejects this — use `AccuracyProfile.RELAXED` or `CUSTOM` ≥ 50 m |
| Config changes do nothing | `reset = false` with a persisted config | Set `reset = true` (the default) during development |
| Very few points while stationary | Working as designed — the data-plane heartbeat warms the filter without storing | Set `persistHeartbeat = true` if you want them stored |
| Zigzag / drift while stationary | Accuracy ceiling too loose | `AccuracyProfile.STRICT`, or a `CUSTOM` ceiling |
| Points keep arriving from a phone lying on a desk | Indoor Wi-Fi/cell-centroid hops read as travel. The pipeline's own fix for this needs no configuration and is in the SDK already | Update the SDK first. If points still arrive, turn on `motion.suppressWhileStationary` — the accelerometer veto ([§12.3](#123-points-from-a-device-that-is-not-moving)) |
| `suppressWhileStationary` set, still storing points | The fix measured as moving, so the veto was never consulted — Doppler or displacement outranks the sensors by design | Read the decision log: a withheld fix says `Stillness Veto`, a stored one names the gate that kept it ([§17.3](#173-layer-3--the-decision-log)) |
| `suppressWhileStationary` had no effect at all | No accelerometer — the flag is turned off at `ready()` with a `Diagnostic` | Check `tracker.getSensors().accelerometer`. Nothing else to do; the pipeline's own defences still apply |
| Every `FixDecision.motionState` reads `STOPPED` | Fixed — the field had no writer, so every row recorded the default | Update the SDK ([§17.3](#173-layer-3--the-decision-log)) |
| Corners drawn as straight chords | Turn fidelity settings off | Keep `turnBurst = true`, `useGyroTurnPrediction = true`, `cornerAnchorCapture = true`, `bearingChangeCaptureDeg = 30`; use `smoothing = HEADING_SPLINE` where the fixes carry a GNSS bearing |
| Navigation "randomly stops" | 1 Hz stream with no foreground service | `navigationMode` requires `service.foregroundService` — `validate()` enforces it |
| Tracking ends when the user swipes the app away | `stopOnTerminate = true` | Leave it `false` (the default) |
| Uploads retry forever | `http://` URL blocked by Android's default network security policy | Use `https://`, or `allowCleartext = true` for a local dev server |
| Uploads stopped, rows still queued | A 403 halted the queue | Call `sync.configure(...)` again with a working credential |
| Queue does not drain when the network returns | `autoSync = false`, so only the durable half runs — nothing asks for a drain until the next enqueued work is released | Set `autoSync = true`, or call `syncNow()` from your own connectivity handling |
| Rows left queued after `stop()` under `autoSync = false` | Fixed — the session-close drain used to fire only when `autoSync` was on, and `stop()` tears down every other path that could notice | Update the SDK. Closing a session now enqueues a drain whenever sync is configured ([§14.4](#when-a-session-ends)) |
| No background uploads after the OEM kills the app | Your app configures sync after login, so the process WorkManager builds for the worker has no config, and nothing is read back from disk | Call `configure()` in `Application.onCreate` with whatever you have, and again when the token arrives ([§14.1](#141-syncconfig)) |
| Background uploads stopped after adding a custom `SyncTransport` | A worker process runs `Application.onCreate` and nothing else, so your transport is only installed if you install it there | Configure sync, and supply your transport, from `Application.onCreate` so every process has both ([§14.6](#146-custom-transport)) |
| `NetworkAvailable` arrives but nothing uploads | The drain ran and failed — the event says a drain was *requested*, not that it succeeded | Read the `HttpResponse` that follows for the reason; a `null` `statusCode` means the request never completed |
| Backlog uploads in a scrambled order | Fixed — the queue is FIFO by insertion, including across a reboot | Update the SDK; older builds ordered on a monotonic clock that restarts at boot |
| Nothing ever reaches the log endpoint | `syncLogs = false`, or the channel could not be derived — most often no `device_id` in `SyncConfig.extraParams` | Look for `No diagnostic log channel: …` under `Tracker/TrackerSync` at `configure()` time; it names what is missing. Or call `configureLogs()` explicitly ([§15.1](#151-it-is-already-on)) |
| Log entries arrive up to 15 minutes late | Working as designed: the channel is a quarter-hourly heartbeat, and only `nudgeLevel` and above drain promptly | Nothing, or lower `nudgeLevel` to `INFO` — a battery cost, not a free one ([§15.3](#153-logsyncconfig)) |
| Log shipping stopped on its own, entries still buffered | The endpoint refused the channel: 401/403 on the credential, or 404/405/501 meaning there is no endpoint there | Fix the route or the token; the next `configureLogs()` retries with the buffer intact ([§15.8](#158-results-and-failure-semantics)) |
| Logs saved under a device your points are not under | `LogSyncConfig.deviceId` differs from `SyncConfig.extraParams["device_id"]` | Leave `deviceId` blank so it inherits. The join between the two channels is that string ([§15.1](#151-it-is-already-on)) |
| `seq` has gaps | The device buffer is bounded and evicted its oldest entries | Expected, and reported rather than hidden. Raise `bufferCapacity` or shorten `uploadIntervalMinutes` ([§15.7](#157-logrecord-and-its-enums)) |
| A `data` payload is missing from an entry that is otherwise there | It was not a JSON object or array, so it was dropped rather than sent | Pass valid JSON text to `log()`; the entry itself is always kept ([§15.6](#156-writing-your-own-lines)) |
| Log volume far higher than expected | `LogType.DECISION` is enabled — roughly 29 000 entries per device per shift | Remove it from `types`, or keep it on one named device while a ticket is open ([§15.12](#1512-what-it-costs-and-what-to-leave-off)) |
| Tracking stopped and the queue emptied | A 401 tore everything down | Re-authenticate, then `ready()` / `start()` / `configure()` again |
| The upload-status line vanished mid-session | A 401 or 403 cleared the sync config — the line is only posted while sync is configured | Check `SyncEvent.HttpResponse` for which, then the two rows above ([§14.4](#144-terminal-failure-semantics)) |
| The upload-status line never appeared | `showSyncStatusInNotification` left off, or `configure()` never called | Turn the flag on **and** configure sync; it is a diagnostic and stays off by default ([§5.5](#55-serviceconfig)) |
| `{pending}` shown literally on the notification | A typo'd token in `syncNotificationText` | Only `{pending}` and `{age}` are substituted; an unknown token is left as written on purpose |
| The unsynced count looks frozen | It refreshes on the `watchdogIntervalMs` tick, and a parked device queues nothing | Read `{age}` alongside it — a still count with a rising age is a real stall, a still count with a resetting age is not |
| `SNAP_UNAVAILABLE` in `warnings` | Your snap provider could not answer | Never fatal — the raw track is drawn. Check the OSRM server |
| `MOTION_DETECTION_DEGRADED` | `motionQuality = POOR` on this hardware | Capture is forced to `CONTINUOUS`; expect more battery use ([§12.1](#121-motion-hardware-and-what-it-can-override)) |
| `MOTION_ONLY` behaves like `CONTINUOUS`, battery high | `motionQuality = POOR` — the mode was overridden at `ready()` | Read `tracker.state.value.effectiveTrackingMode`. Check `ACTIVITY_RECOGNITION` is granted: a denial reaches `POOR` on hardware that is otherwise fine, and re-running `ready()` after the grant clears it |
| Never saw `MOTION_DETECTION_DEGRADED` on a device you know is degraded | It is emitted inside `ready()`, and `events` has `replay = 0` | Read `TrackerState.motionQuality` instead — it always has a current value. Collect `events` before calling `ready()` if you want the event itself |
| Stops reported minutes late | `motionQuality = DEGRADED` — the SDK doubled `stopTimeoutMin` | Working as designed on hardware with no gyroscope or trigger sensor. The `Diagnostic` at `ready()` names the old and new value |
| `ready()`/`start()` returns `DEVICE_INTEGRITY_BLOCKED` | A `BLOCK`-policy signal fired | Read `tracker.integrity()` for the signals ([§20](#20-device-integrity)); relax that policy to `WARN` if the device is legitimate |
| Session ends by itself with `DEVICE_INTEGRITY_BLOCKED` | The health-loop re-check fired mid-session | Same as above; `integrityRecheckIntervalMs(0)` disables the periodic re-check |
| Integrity findings never appear | The host app is debuggable, so the layer is waived | Expected. Check `IntegrityReport.waived`; exercise the layer in a release build |
| `assembleRelease` fails on `FieldTrackSecurityDisabled` | A release source set disables the integrity layer | Move the override to `src/debug/` ([§20.6](#206-build-time-checks)) |
| Live map jumps backwards | Drawing a stale frame | Drop any `LiveTrackUpdate` whose `sequence` is not newer than the last drawn |
