# Build Manual

How the Tracker Gradle build is put together, what to run, and what to change when you
add or edit a module.

There is no `build-logic/` composite build and there are no `traker.*` convention
plugins. Every module configures itself in its own `build.gradle.kts`, and all versions
come from `gradle/libs.versions.toml`.

---

## 1. Prerequisites

| Thing | Version | Where it comes from |
|---|---|---|
| JDK | 17 | Must be installed. `javaTarget = "17"` in the catalog drives the Gradle toolchain. |
| Gradle | 9.7.0 | Wrapper — always use `./gradlew`, never a system `gradle`. |
| Android Gradle Plugin | 9.3.1 | `agp` in the catalog. |
| Kotlin | 2.4.10 | `kotlin` in the catalog. |
| Android SDK | compileSdk 37 | `local.properties` → `sdk.dir`, or `ANDROID_HOME`. |

Build configuration is split across two files at the repository root, and which one a key
lives in is a decision about secrecy, not convenience:

| File | Committed? | Holds |
|---|---|---|
| [`configuration.properties`](../configuration.properties) | **yes** | `FIELDTRACK_LICENSE_URL`, `FIELDTRACK_RESPONSE_KEY`, and the shared defaults for `SYNC_URL` and `OSRM_BASE_URL`. Never a credential. |
| `local.properties` | no, gitignored | `sdk.dir`, `MAPS_API_KEY`, `TRACKER_LICENSE`, and per-machine overrides of `SYNC_URL` / `OSRM_BASE_URL`. |

A fresh clone therefore builds against the right licence endpoint with no setup — only the
two credentials need filling in. Copy
[`local.properties.template`](../local.properties.template) for those.

The two lookups differ, on purpose:

- **Product configuration** (`FIELDTRACK_LICENSE_URL`, `FIELDTRACK_RESPONSE_KEY`):
  `-P…` → environment → `configuration.properties`. `local.properties` is **not** consulted.
- **Per-developer** (`SYNC_URL`, `OSRM_BASE_URL`): `local.properties` → `configuration.properties`,
  where blank counts as absent, so overriding one key does not mean restating the rest.
- **Credentials** (`MAPS_API_KEY`, `TRACKER_LICENSE`): `local.properties` only, no fallback.
  A value typed into the committed file is ignored rather than silently used — see §1.1.

### Maps API key

`sample-android` reads `MAPS_API_KEY` from `local.properties` at configuration time and
injects it as a manifest placeholder and a `BuildConfig` field:

```properties
# local.properties
MAPS_API_KEY=AIza...
```

Absent is a supported state. The sample still builds and runs; the Track and Debug
screens say the key is missing instead of showing a blank map.

**There is no committed fallback.** One used to live in `sample-android/build.gradle.kts`
— a live Google API key in version control, directly under a comment claiming keys were
read from `local.properties` "rather than committed". It was removed in favour of the
empty default above.

Removing it from `HEAD` does **not** remove it from history: `git log -S` still finds it in
`b100eaf`, and it is readable in every clone that has ever fetched. **The only real
remediation for a committed credential is rotation at the provider.** If you are picking
this repo up and that key has not been rotated, treat it as public.

Restrict whatever key you use to this app's package name and signing certificate in Google
Cloud Console. An unrestricted Maps key is billable by anyone who finds it, which is the
part that makes a leak expensive rather than merely embarrassing.

### Licence API URL

`fieldtrack-core` reads the licence API root at configuration time and injects it as
`BuildConfig.LICENSE_BASE_URL`, which `LicenseConfig.defaultBaseUrl` returns:

```properties
# configuration.properties — committed, at the repository root
FIELDTRACK_LICENSE_URL=https://licence.example.com/api/v1
FIELDTRACK_RESPONSE_KEY=Base64OfThirtyTwoRawBytes=
```

Include the version segment. `RetrofitLicenseApi` appends `/verify` and nothing else,
so moving to `/api/v2` is a configuration change rather than an SDK release.

Resolution order, first non-blank wins:

| Source | For |
|---|---|
| `-PfieldtrackLicenseUrl=...` | one-off builds, a CI job pointed at staging |
| `FIELDTRACK_LICENSE_URL` environment variable | CI secrets |
| `FIELDTRACK_LICENSE_URL` in `configuration.properties` | everything else — this is the normal source |
| `FieldTrackLicenseUrl` manifest meta-data | per-install override, takes precedence over all of the above at runtime |

**`local.properties` is not in that list, deliberately.** These two values are *product
configuration*: one endpoint and one public verification key that the whole team and every
CI runner build against. A per-machine override is how one laptop ships a build aimed at a
server nobody else tested, so the build does not look there for them at all — a line for
either in `local.properties` does nothing.

Unset is a supported state and the default. With no URL the transport makes no request,
the check returns `CarryOn`, and every start proceeds.

**Committing them gives nothing away.** Both are compiled into `BuildConfig` and readable in
any published AAR or installed APK whichever file they are typed into. An endpoint the
device has to reach cannot be hidden from the device, and the response key is the *public*
half of a signing pair — the private half never leaves the server. Neither is a credential,
and this mechanism is not a place for one: `MAPS_API_KEY` and `TRACKER_LICENSE` stay in
gitignored `local.properties`, with no fallback to the committed file.

The Gradle property and environment variable remain useful for pointing one build somewhere
else for one run, without editing a file everyone else pulls.

---

## 2. Repository layout

```
traker/
├─ settings.gradle.kts        # module list + repository config
├─ build.gradle.kts           # declares plugins with `apply false` only
├─ gradle.properties          # JVM args, parallel, caching, configuration cache
├─ gradle/libs.versions.toml  # THE version catalog — single source of truth
├─ fieldtrack-geo/               # pure-Kotlin engine, published as a minified AAR
├─ fieldtrack-core/              # Android library — the public SDK
├─ fieldtrack-maps/              # Android library — Maps rendering
├─ traker-all/               # empty umbrella AAR — re-exports all SDK modules
├─ fieldtrack-sync/              # Android library — optional upload
├─ fieldtrack-snap/              # Android library — optional road snapping (OSRM)
└─ sample-android/            # Android application — the demo app
```

Dependency direction — nothing points backwards:

```
sample-android ──┐
fieldtrack-sync ────┼─> fieldtrack-core ──> fieldtrack-geo
                                           ^
fieldtrack-maps ─────────────────────────────┤
fieldtrack-snap ─────────────────────────────┘
```

`fieldtrack-snap` depends on `fieldtrack-geo` **only**, not on core. It implements one port
(`RoadSnapProvider`) and turns a list of coordinates into a list of coordinates; it has
no business knowing about capture, storage or Android location.

---

## 3. The version catalog is the only place versions live

`gradle/libs.versions.toml` holds library versions *and* the build-level numbers:

```toml
javaTarget  = "17"
compileSdk  = "37"
minSdk      = "26"
targetSdk   = "37"
```

Module build files read them, never hardcode them:

```kotlin
android {
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }
}
```

Change the SDK level in one place and every module follows. If you hardcode `36` into a
module instead, the modules drift and nothing will tell you.

---

## 4. Module recipes

Copy the block that matches the kind of module you are adding.

### 4.1 Android library (`fieldtrack-core`, `fieldtrack-maps`, `fieldtrack-sync`, `fieldtrack-snap`)

```kotlin
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    // add only what this module needs:
    // alias(libs.plugins.ksp)                    // Room only — there is no DI processor
    // alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.field360.tracker.<module>"

    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.javaTarget.get())
        targetCompatibility = JavaVersion.toVersion(libs.versions.javaTarget.get())
    }

    lint {
        warningsAsErrors = true
        abortOnError = true
    }
}

kotlin {
    jvmToolchain(libs.versions.javaTarget.get().toInt())
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(libs.versions.javaTarget.get()))
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
}
```

An `Android library` module also needs a `consumer-rules.pro` file next to its
`build.gradle.kts` — even an empty one — because `consumerProguardFiles` references it.
What belongs in it, and what does not, is §5.6.

### 4.2 Pure-Kotlin Android library (`fieldtrack-geo`)

```kotlin
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.field360.traker.geo"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
    }
    buildTypes.release {
        isMinifyEnabled = true
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
}

kotlin {
    jvmToolchain(libs.versions.javaTarget.get().toInt())
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(libs.versions.javaTarget.get()))
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.truth)
}
```

`fieldtrack-geo` keeps pure Kotlin source and local JVM tests, but publishes as an Android
AAR so AGP can run R8 and embed consumer rules. The Android plugin is packaging, not
permission to introduce Android imports into the engine.

### 4.3 Android application (`sample-android`)

Same as 4.1, but `alias(libs.plugins.android.application)` and `defaultConfig` also
carries `applicationId`, `targetSdk`, `versionCode`, `versionName`.

### 4.4 Dependency injection — there isn't any

**No module in this project uses a DI framework.** `fieldtrack-core` wires its own graph by
hand in `di/TrackerGraph.kt`: one `internal class` of `by lazy` members, one
double-checked process singleton, reached through `Tracker.getInstance(context)`.

Adding a dependency means adding a `val` to that file. Adding a *module* that needs the
core graph means adding an accessor to `TrackerArtifacts` (the seam `fieldtrack-sync` uses),
because `TrackerGraph` is `internal` and a sibling Gradle module cannot see it.

The trade is stated rather than assumed. What was lost is compile-time graph verification;
what replaces it is `TrackerGraphTest`, which touches every member — a missing edge is
already a compile error in `TrackerGraph`, and a cycle overflows the stack in that test
rather than on a user's device.

> An earlier revision shipped Hilt inside `fieldtrack-core`. The consequence, recorded in
> PLAN.md §0, was that every consuming app inherited the Hilt runtime, had to apply the
> Hilt Gradle plugin, and had to annotate its `Application` with `@HiltAndroidApp`. That
> is not an install story an SDK can require of a host whose `Application` class is not
> its own to annotate — a React Native template's `MainApplication`, a Flutter or Unity
> shell, a modular app whose `Application` belongs to another team. Hilt was removed and
> the position argued in `docs/reference/EKF-DESIGN-REVIEW.md` §S5 restored
> (CROSS-PLATFORM.md B-1).

### 4.5 Registering a new module

Add one line to `settings.gradle.kts`:

```kotlin
include(":traker-newthing")
```

Then create `traker-newthing/build.gradle.kts` from the recipe above. Nothing else
needs to change — there is no plugin registry to update.

---

## 5. Commands

| Goal | Command |
|---|---|
| List modules / sanity-check config | `./gradlew projects` |
| Geo engine tests (T1) | `./gradlew :fieldtrack-geo:testDebugUnitTest` |
| Core unit tests (T2) | `./gradlew :fieldtrack-core:testDebugUnitTest` |
| Optional artifacts | `./gradlew :fieldtrack-sync:test :fieldtrack-snap:test` |
| Publish locally | `./gradlew publishToMavenLocal` |
| Publish to the configured remote | `./gradlew publish` — see §5.5 |
| Audit published bytecode policy | `./gradlew verifyReleaseObfuscation` |
| Exercise the SDK's R8 consumer rules | `./gradlew :sample-android:assembleRelease` — see §5.6 |
| All unit tests | `./gradlew test testDebugUnitTest` |
| Build everything debug | `./gradlew assembleDebug` |
| Compile instrumented tests (no device needed) | `./gradlew assembleDebugAndroidTest` |
| Run instrumented tests (device/emulator needed) | `./gradlew connectedDebugAndroidTest` |
| Lint | `./gradlew lintDebug` |
| Install the sample | `./gradlew :sample-android:installDebug` |
| Everything CI runs | see §8 |
| Clean | `./gradlew clean` |

Reports land in `<module>/build/reports/`. Lint's readable output is at
`<module>/build/intermediates/lint_intermediate_text_report/debug/lintReportDebug/lint-results-debug.txt`.

---

## 5.5 Publishing

Six modules publish: `fieldtrack-geo`, `fieldtrack-core`, `fieldtrack-maps`, `fieldtrack-sync`,
`fieldtrack-snap`, and `traker-all`. Coordinates are `com.github.fieldtrack360.fieldtrack:<module>:<version>`.

`sample-android` does **not** publish.

### The version

One number, in `gradle/libs.versions.toml`:

```toml
[versions]
fieldtrack = "0.1.1-alpha01"
```

Every Maven artifact carries this version. Bump it once in the catalog before publishing.

### Local

```bash
./gradlew publishToMavenLocal
```

Needs no repository configuration and is the loop for testing the sample or another host
against a local build. Add `mavenLocal()` to the host repositories and select the local
Tracker version.

### Remote

A remote repository is added **only if** its URL is configured, so a developer with no
credentials gets a working local publish rather than a configuration error.

```bash
./gradlew publish   -PtrakerMavenUrl=https://maven.pkg.github.com/fieldtrack360/fieldtrack   -PtrakerMavenUser=… -PtrakerMavenToken=…
```

or, for CI, the same three as `TRAKER_MAVEN_URL`, `TRAKER_MAVEN_USER`,
`TRAKER_MAVEN_TOKEN` in the environment.

**No credential has a default and none is read from a file in this repository.** That is
not a principle being restated for its own sake — a live Google Maps API key was committed
here once, is still in the history, and is unfixable by anything short of rotation (§1). A
publishing token has strictly more blast radius than that key did.

### What is in an artifact

The R8-minified `release` AAR and a javadoc JAR. No source JAR is published.

The javadoc JAR contains rendered Kotlin public API documentation for repository tooling
and IDEs. It contains HTML and assets, not `.kt` or `.java` source entries. Source
navigation is intentionally traded away because publishing source would defeat release
obfuscation; `verifyReleaseObfuscation` enforces both archive policies. The publish
workflow also runs `archiveReleaseMappings`, which copies each module's R8 outputs into
`build/release-mappings/<version>/<module>/` for restricted release storage.

POM dependency scopes follow the Gradle configuration, and this is worth checking after any
dependency change:

| Gradle | POM | Example |
|---|---|---|
| `api(project(":fieldtrack-core"))` | `compile` | `traker-all` → `fieldtrack-core` |
| `implementation(project(":fieldtrack-core"))` | `runtime` | `fieldtrack-sync` → `fieldtrack-core` |
| `compileOnly(libs.okhttp)`, `compileOnly(libs.retrofit)` | **absent** | no host inherits an HTTP stack it did not ask for |

### Where the configuration lives

`gradle/publish.gradle.kts`, applied by each publishable module with
`apply(from = rootProject.file("gradle/publish.gradle.kts"))`.

This is a **script plugin**, not a convention plugin and not a reinstated `build-logic`
composite (§10 still stands). It exists because the alternative was six near-identical
forty-line blocks that must agree about group, version, POM metadata and credentials — and
six copies of a thing that must agree eventually do not, with the symptom being one
artifact published under the wrong coordinates, discovered by whoever tries to consume it.

One thing is deliberately *not* shared: the

```kotlin
publishing { singleVariant("release") { withJavadocJar() } }
```

block inside each Android module's `android { }`. A script plugin applied with
`apply(from = …)` is compiled against Gradle's own API, so `LibraryExtension` and
`singleVariant` do not resolve inside it at all. Doing it reflectively to keep five lines
in one place would trade a compile error for a runtime one. The split is still the right
one: what is shared is what drifts, and what is repeated is five lines AGP would fail the
build over if they went missing.

---

## 5.6 R8 and consumer rules

Every code-bearing published library, including `fieldtrack-geo`, ships build-time R8 rules
and a `consumer-rules.pro`. The build-time rules protect Tracker's own AAR; consumer rules
protect runtime behavior during the host application's separate R8 pass.

### The rules are short on purpose

A consumer rule constrains **somebody else's** build. A blanket
`-keep class com.field360.tracker.** { *; }` would add hundreds of KB to every host's APK
to protect against a handful of reflective lookups, and would hide the next one instead of
documenting it. Three of these files therefore keep nothing at all, and say why.

Most of what an SDK like this needs is already shipped by the libraries themselves, and a
local copy of their rules is a stale copy waiting to happen:

| Library | What it already keeps |
|---|---|
| `kotlinx-serialization-core` | `Companion`, `serializer()`, `INSTANCE`, `$$serializer.descriptor`, `RuntimeVisibleAnnotations` |
| `room-runtime` | `-keep class * extends androidx.room.RoomDatabase` |
| `work-runtime` | `-keepnames class * extends ListenableWorker`, plus constructors |
| AGP | keeps generated from the merged manifest — service and receivers |

**Serialization needs no field keeps.** The generated descriptor carries every element
name as a string literal baked in at compile time, so R8 renaming the Kotlin property
`elapsedRealtimeNanos` does not rename the JSON key. The wire format survives obfuscation
by construction.

### The one rule that is ours

```proguard
-keepclassmembers,allowoptimization enum com.field360.tracker.** {
    <fields>;
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
```

Stored points persist `movementStatus`, `detectedActivity` and `motionState` as their enum
*name* and read them back with `valueOf` (`data/db/Mappers.kt`). Those call sites are
`runCatching { … }.getOrDefault(…)`, deliberately, so a row written by a newer version
cannot crash an older one.

Under obfuscation that turns a crash into something worse. Without the rule, R8 renames
the constants — measured, not assumed:

```
    MovementStatus STEADY -> e
    MovementStatus MOVING -> f
```

`valueOf("MOVING")` then throws, `getOrDefault` swallows it, and **every stored point
reads back as `STEADY` / `UNKNOWN` / `STOPPED`**. No exception, no log, no failed request.
Motion history, activity segments and the debug overlay are all quietly wrong — in the one
build configuration that ships.

### Why `sample-android` is minified

It used to be `isMinifyEnabled = false`, which meant every `consumer-rules.pro` in this
repository was shipped to hosts having **never once been executed**. Rules that are never
exercised are guesses, and these guesses were wrong: `fieldtrack-core` kept
`com.field360.tracker.db.**`, a package that does not exist — the real name is `data.db` —
and nothing anywhere preserved the enum names above.

The artifact audit runs first, then the locally published sample release exercises the
SDK's consumer rules:

```bash
./gradlew verifyReleaseObfuscation publishToMavenLocal
./gradlew :sample-android:assembleRelease
```

Debug 15.2 MB → release 1.8 MB.

Verify a change by reading what R8 actually did, in
`sample-android/build/outputs/mapping/release/`:

| File | Answers |
|---|---|
| `configuration.txt` | did my rule reach R8, in the form I wrote it |
| `mapping.txt` | what survived, and under what name |
| `usage.txt` | what was removed — a bare line is a whole class, a trailing `:` is members |
| `seeds.txt` | what a keep rule matched |

The sample deliberately adds **no** compensating keeps of its own. If the SDK needs a rule,
it belongs in the SDK's consumer rules where a host will get it too; adding it to the app
would make the test pass and the hosts fail.

**Not yet verified on a device.** The release APK builds, R8 keeps every manifest-declared
component, both Room `_Impl` classes the sample touches and all three workers — but nobody
has installed and run the minified build. That belongs with the phase 7 field matrix.

---

## 6. Build settings that will bite you

From `gradle.properties`:

```properties
org.gradle.parallel=true
org.gradle.caching=true
org.gradle.configuration-cache=true
```

**Configuration cache is on.** Practical consequences when editing build files:

- Do not read mutable state at execution time (`project.` access inside a task action,
  `System.getenv()` in a doLast, etc.). Read it at configuration time and capture the
  value, the way `sample-android` reads `local.properties` into `val mapsApiKey`.
- Avoid cross-project configuration (`subprojects { }`, `allprojects { }`) in the root
  build file. That is why shared config is repeated per module rather than centralised
  in the root — repetition is the price of project isolation.
- After a build-file change, the first run reports `Calculating task graph as no cached
  configuration is available`. That is normal, not an error.

**Warnings are errors.** Both `allWarningsAsErrors` (Kotlin) and `lint.warningsAsErrors`
are on in every module. A deprecation warning fails the build. This is intentional — fix
it, do not suppress it globally.

---

## 7. Gotchas

### AGP 9 supplies Kotlin — do not apply `kotlin.android`

Applying `org.jetbrains.kotlin.android` alongside AGP 9 is a hard failure:

```
The 'org.jetbrains.kotlin.android' plugin is no longer required for Kotlin support since AGP 9.0.
Solution: Remove the 'org.jetbrains.kotlin.android' plugin from this project's build file.
```

So Android modules apply **only** `com.android.library` / `com.android.application`.
The project-level `kotlin { }` block is still available — AGP's built-in Kotlin support
registers it. See <https://kotl.in/gradle/agp-built-in-kotlin>.

`fieldtrack-geo` also applies `com.android.library` for secure AAR packaging while keeping
its source platform-independent.

### `kotlinOptions { }` inside `android { }` is dead

Use the project-level `kotlin { compilerOptions { } }` block instead. Setting
`jvmTarget` in both places is redundant and the `android.kotlinOptions` path is on its
way out.

### Room schemas are not wired into androidTest assets

`fieldtrack-core` sets `room.schemaLocation` via KSP args and commits the schemas
(EC-83 — migrations are real, never destructive). The schema directory is deliberately
*not* added to androidTest assets: AGP 9 throws
`DefaultAndroidLibrarySourceSet_Decorated cannot be cast to AndroidLibrarySourceSet`
on `sourceSets.getByName(...).assets`.

**This is now an outstanding gap, not a deferred one.** The database is at **version 5**:

| Migration | What it adds | Why the default matters |
|---|---|---|
| v1 → v2 | `bearingDeg` on `raw_fix` and `fix_decision` | `0` is what `TrackFix.bearingDeg` already defaults to when the provider reports no bearing, so existing rows read back exactly as before |
| v2 → v3 | `filter_state.lastCapturedBearingDeg` (EC-45) | `-1` = "no heading captured yet". A `0` would claim the user was last heading due north and fabricate a turn on the very next fix |
| v3 → v4 | `filter_state` constant-velocity state (EC-44a) | `varianceVel` defaults to the seed value, not `0`. A `0` would claim the old scalar filter had been *certain* the user was stationary, and the first fix after upgrade would be gated against a prediction saying they never moved |
| v4 → v5 | `raw_fix` Doppler confidence: `speedAccuracyMps`, `bearingAccuracyDeg` (SMOOTH-NAV-PLAN Phase 1) | Nullable with **no** default: `null` is "the platform reported no confidence". A numeric default would fabricate a confidence the hardware never claimed — the A8 assumption class |

All four are hand-written and additive, and **none has a `MigrationTestHelper` test**,
because that path is still blocked. Revisit with the Room Gradle plugin.

Partial JVM-side mitigation: `SchemaConsistencyTest` (plain unit test) asserts the
committed `5.json` against MIGRATION_4_5's exact column contract — added columns,
affinity, nullability, absence of defaults — so schema/migration drift fails CI
without a device. It does not replace executing the migrations; it pins the surface
Room's runtime validation checks them against.

### The sample installs a `RoadSnapProvider` only if you give it a URL

Set `OSRM_BASE_URL` in `local.properties` — or in the committed `configuration.properties`,
which it falls back to — and `SampleApplication` installs `OsrmSnapProvider`. Leave it unset — the default — and it
installs nothing: `buildTrack` never leaves the device, no `snap_unavailable` warning is
emitted, and the track is drawn from the fixes that were captured.

There is deliberately **no fallback URL**, in the SDK or here. The public OSRM demo server
publishes no availability guarantee and no rate limit worth relying on, so shipping it as
a default would put every host's production traffic on someone else's free instance
without anyone choosing to. Point it at a deployment you run.

The Track tab has a **Snap** chip so the two can be compared on the same fixes, which is
the only honest way to see how close the offline geometry gets — everything the SDK does
without a road network (cornering process noise, the spline) is an approximation of a road
it cannot see.

Installing a provider in your own app is one call, documented in [API.md](API.md) §3.

### Retrofit and OkHttp in `fieldtrack-sync` and `fieldtrack-snap` are `compileOnly`

The default `SyncTransport` and `OsrmSnapProvider` use Retrofit over OkHttp, but a host supplying its
own `SyncTransport` or `RoadSnapProvider` should not inherit the dependency. It is
`compileOnly` in the main source set and `testImplementation` for tests. A consumer using
either shipped implementation must add both themselves. Retrofit 3 requires OkHttp 5, so they are versioned together.

### Known failing check

`./gradlew lintDebug` currently fails on a pre-existing source issue:

```
fieldtrack-core/src/main/kotlin/com/field360/tracker/service/TrackingService.kt:124: Error: Field requires API level 29 (current min is 26): android.content.pm.ServiceInfo#FOREGROUND_SERVICE_TYPE_LOCATION [InlinedApi]
```

This is a code problem, not a build-config problem — the fix is a version guard or a
targeted `@SuppressLint`/`@RequiresApi`, not a lint config change. CI runs `lintDebug`,
so it is red until this is addressed.

---

## 8. CI

`.github/workflows/ci.yml` runs on push to `main` and on every pull request, on
`ubuntu-latest` with Temurin JDK 17:

1. `./gradlew :fieldtrack-geo:testDebugUnitTest` — T1, the geo engine. PLAN.md §6: all T1 green before
   any Android code is trusted.
2. `./gradlew :fieldtrack-core:testDebugUnitTest` — T2.
3. `./gradlew :fieldtrack-sync:test :fieldtrack-snap:test` — the optional artifacts. Their
   tests are the only thing standing between a host and a silently rotting transport or
   snap provider; nothing else compiles them against a server.
4. `./gradlew assembleDebug`
5. `./gradlew assembleDebugAndroidTest` — compiles the instrumented suite so it cannot
   rot silently between field runs.
6. `./gradlew lintDebug`

On failure, `**/build/reports/` is uploaded as the `test-reports` artifact.

Reproduce the whole CI run locally:

```bash
./gradlew :fieldtrack-geo:testDebugUnitTest :fieldtrack-core:testDebugUnitTest :fieldtrack-sync:test :fieldtrack-snap:test \
          assembleDebug assembleDebugAndroidTest lintDebug --stacktrace
```

---

## 9. Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `The 'org.jetbrains.kotlin.android' plugin is no longer required...` | Remove that alias from the module's `plugins` block. See §7. |
| `SDK location not found` | Add `sdk.dir` to `local.properties` or set `ANDROID_HOME`. |
| Map is blank / "no key" message in the sample | `MAPS_API_KEY` missing from `local.properties`. There is no fallback to `configuration.properties` for it. Supported state, not a crash. |
| Build fails on a deprecation warning | `allWarningsAsErrors` is on by design. Fix the call site. |
| Lint fails on a new warning | `lint.warningsAsErrors` is on by design. Fix it or annotate the specific site. |
| Configuration cache errors after editing a build file | You read mutable state at execution time. Capture it at configuration time instead. See §6. |
| Stale weirdness after a big build-file change | `./gradlew clean --no-configuration-cache`, then a normal build. |
| Unresolved `libs.something` | The alias does not exist in `gradle/libs.versions.toml`, or you used `-` where the accessor needs `.` (catalog `kotlinx-coroutines-test` → `libs.kotlinx.coroutines.test`). |

---

## 10. Why there is no `build-logic/`

An earlier revision used a `build-logic` composite build with four convention plugins
(`traker.kotlin.jvm.library`, `traker.android.library`, `traker.android.application`,
`traker.hilt`). It was removed and the configuration inlined into each module.

The trade: shared config is now repeated across four Android modules instead of living
in one plugin. In exchange, the build has no second Gradle build to compile, no
plugin-marker-to-coordinate mapping to maintain, and every module's configuration is
readable in the file you already have open.

The mechanism that actually prevents drift is unchanged and does not depend on
convention plugins: **all SDK levels and the Java target come from
`gradle/libs.versions.toml`**. Keep it that way. If you find yourself typing a literal
`37` or `"17"` into a module build file, put it in the catalog instead.
