# Tracker SDK — Implementation Plan

**v2 — 2026-07-30.** Rewritten after a line-by-line read of the reference implementation. v1 was written from the spec document and contained assumptions; those are gone.

**Package namespace:** `com.field360.tracker` · **Repo:** `~/Work/studio/traker` (separate project, no dependency on the source app) · **Status:** plan, not started.

### Document set

| Document | What it holds |
|---|---|
| **PLAN.md** (this file) | Scope, architecture, provenance, phases, risks |
| [API.md](API.md) | Real Kotlin: types, pipeline, ingestor, providers, service, Room schema, public API, config, manifest |
| [PERMISSIONS.md](PERMISSIONS.md) | Permission ladder, tier degradation, live revocation, FGS-by-API-level, survival stack |
| [EDGE-CASES.md](EDGE-CASES.md) | 138 catalogued cases with trigger, symptom, handling, owner, test |
| [POLYLINE-JSON.md](POLYLINE-JSON.md) | The export contract — polyline JSON, arrows, GeoJSON, fixture format |
| [SDK-COMPARISON.md](SDK-COMPARISON.md) | Feature-by-feature identification vs the incumbent SDK; deep dives on stop detection, headless, config reset, device sensors |
| [SOURCE-AUDIT.md](SOURCE-AUDIT.md) | 18 findings from the source read: 8 defects, 5 hazards, 5 smells — with `file:line` |
| [reference/capture-and-plotting-spec.md](reference/capture-and-plotting-spec.md) | The algorithm bible (959 lines, field-verified) |
| [reference/EKF-DESIGN-REVIEW.md](reference/EKF-DESIGN-REVIEW.md) | Review of a third-party EKF-based SDK design document |

---

## 0. Locked decisions

| Decision | Choice | Consequence |
|---|---|---|
| Platform scope | **Android only; Kotlin, Java and React Native hosts** | No iOS and no Flutter — a second implementation of the engine is not on the roadmap. React Native **was** in this row and was moved out: it is a transport for the one engine, not another engine, and `fieldtrack-bridge` carries it (CROSS-PLATFORM.md). `fieldtrack-geo` is a plain Kotlin module with no Android dependencies, which keeps the engine 100 % JVM-unit-testable; that is still the reason it stays separate, and it does not become KMP. |
| Server sync | **Offline-first, sync optional** | `fieldtrack-core` never touches the network. `fieldtrack-sync` is a separate optional artifact. |
| Distribution | **Private Maven + npm + local sample** | `maven-publish` → GitHub Packages / internal Nexus, configured in `gradle/publish.gradle.kts` and driven by URL/credentials from properties or the environment (never from a file in the repo). Six artifacts under `com.field360.tracker`, plus `@devstree/react-native-traker` on npm at the same version, enforced by a build check. BUILD.md §5.5. |
| Name | **Tracker** / `com.field360.tracker` | Artifacts: `fieldtrack-geo`, `fieldtrack-core`, `fieldtrack-maps`, `fieldtrack-sync`, `fieldtrack-snap`, `fieldtrack-bridge`. |
| Dependency injection | **None — the graph is wired by hand** | Clean-architecture layering (`domain` / `data` / `service`) stands; it is assembled in one `internal` file, `di/TrackerGraph.kt`, reached through `Tracker.getInstance(context)`. A host applies no Gradle plugin, annotates no `Application`, and runs no annotation processor. **This reverted an earlier decision to ship Hilt inside the SDK**, which had forced every consumer to adopt Hilt — unacceptable for a host whose `Application` class is not its own to annotate, and a hard blocker for the React Native package (CROSS-PLATFORM.md B-1). The position argued in [reference/EKF-DESIGN-REVIEW.md](reference/EKF-DESIGN-REVIEW.md) §S5 stands after all. Cost, accepted knowingly: no compile-time graph verification — `TrackerGraphTest` constructs every member instead. |

**Single user.** One device, one user, explicit `start()` / `stop()`. Every company / employee / branch / punch / attendance / branch-WiFi / floor-detection concept is stripped — see §5 for the exact list.

---

## 1. What changed after reading the source

v1 assumed the reference implementation was correct and the job was "port it, strip the company". Reading it changed three things.

**The algorithm is excellent; the plumbing around it has real defects.** The 7-stage acceptance pipeline is field-hardened over three generations and every constant has a documented rationale and a named symptom it prevents. But the code that feeds it has eight reproducible defects — [SOURCE-AUDIT.md](SOURCE-AUDIT.md). Three matter enough to change the architecture:

- **The fix clock is object-construction time, not the GPS fix time** ([A1](SOURCE-AUDIT.md)). `LatLngFactory.from(location)` never copies `location.time`. Every Δt, gap and speed in the pipeline is computed from when a Kotlin object happened to be constructed.
- **Restoring the anchor from the database yields `startTime = now`** ([A2](SOURCE-AUDIT.md)). `Location.toLatLng()` doesn't map `time`, so after every process death the "previous point" claims to have been observed this instant — which can route the first post-restart fix into the negative-Δt branch and **accept it unconditionally**, bypassing every gate. That is exactly the failure the resume path was written to prevent.
- **Two entry points, two different anchors, one shared filter** ([A3](SOURCE-AUDIT.md), [A6](SOURCE-AUDIT.md)). The service and the 15-min worker derive `past` differently but mutate the same static `KalmanLatLngFilter` through twelve `@Volatile` fields. The class comment concedes the compound updates are non-atomic and rests on a "service & worker rarely overlap" assumption — which by construction fails about once an hour.

**Architectural answers, adopted into the plan:**

1. **Monotonic time only.** `elapsedRealtimeNanos` is the sole clock for filter maths; wall clock is storage and display. This deletes the entire clock-skew edge-case class — the negative-Δt branch becomes unreachable for live fixes.
2. **One ingest actor.** A single `Channel<TrackFix>` consumer owns `past` and an **immutable** `FilterState`. No statics, no `@Volatile`, no shared mutable filter.
3. **`filter_state` is a persisted table**, restored in `ready()` before the first fix is processed. Not optional.

**Two more the audit surfaced:**

- **Batched fixes are being thrown away** ([A4](SOURCE-AUDIT.md)). The request asks the OS to batch (`setMaxUpdateDelayMillis(60_000)`) and the callback then reads only `locationResult.lastLocation`. A batch of 4–6 fixes collapses to 1 — precisely the samples turn geometry needs. It is masked by [A5](SOURCE-AUDIT.md): the burst gate keys on delivery time, so iterating the batch without also fixing the gate would reject the whole batch. Both are fixed together.
- **`hasSpeed` / `hasBearing` default to `true`** ([A8](SOURCE-AUDIT.md)), so any construction path that forgets them produces a point claiming hardware it never had — silently disabling the network-fix rejection, the main defence against WiFi-positioning teleports. Tracker defaults them `false`.

---

## 2. Scope

### In scope (v1)

1. `start()` / `stop()` for a single user; survives process death, reboot, and OEM battery managers.
2. GPS capture with motion detection — activity recognition + hardware speed + stationary geofence driving a `STOPPED → MOVING ⇄ STOP_PENDING ⇄ STATIONARY` machine.
3. The 7-stage acceptance pipeline: nine named noise classes, nine dedicated gates.
4. Room storage: points, sessions, activity segments, **filter state**, and a **decision log** recording why every fix was accepted, skipped, or rejected.
5. **Polyline JSON + GeoJSON export** with precomputed **arrow anchors and bearings**, stop nodes, travel/dwell segments, and per-session statistics.
6. Native Kotlin sample app: start/stop, live map with arrows, 3-layer debug overlay, decision-log viewer, runtime config, fixture record/replay.

### Out of scope

**iOS · Flutter — permanently, not deferred.**

**React Native was reversed.** This section originally put it in the same sentence as iOS and Flutter, and that was wrong for a reason worth recording: iOS is a *second implementation* of the engine, and React Native is a *transport* for the one that exists. The distinction is the whole argument. `fieldtrack-bridge` carries it, `fieldtrack-geo` is untouched, and every acceptance decision still happens in exactly one place. See [CROSS-PLATFORM.md](CROSS-PLATFORM.md), which supersedes this paragraph.

Also out: reverse geocoding (optional module, off by default) · anything server-side.

**Road snapping shipped after all, in the shape this section originally reserved.** The geometry is pure and in-tree (`geo/plot/Snapper.kt`); the network call is a `RoadSnapProvider` a host installs, with one implementation in the optional `fieldtrack-snap` artifact. Core still carries no HTTP client and no API key, and a track with no provider installed still renders offline from Bézier-rounded raw geometry — so the default posture is unchanged and the promise above still holds.

---

## 3. Single source of truth

Every decision lives in exactly one place. Two invariants, each enforced in CI, not by convention:

1. **No algorithm above `fieldtrack-geo`.** A Konsist rule fails the build if `fieldtrack-core` contains a numeric literal inside a decision expression, or imports `kotlin.math` outside `provider/FixMapper`. Every constant lives in one `TrackerConstants` data class.
2. **No platform types inside `fieldtrack-geo`.** `android.location.Location` never appears, so the entire engine runs and is tested on the JVM with no emulator. Conversion happens once, in `FixMapper`, which is also the only place validity rules live.

The practical test: a behaviour change is a one-file change in `fieldtrack-geo`, and the fixture suite proves it on the JVM before any device sees it.

The same rule produces the arrow guarantee — `Arrows.place()` feeds both the Google Maps renderer and the JSON export, so the drawn track and the exported track cannot disagree. The reference has two divergent arrow ladders precisely because that rule did not exist ([A9](SOURCE-AUDIT.md)).

---

## 4. Architecture

```
                          ┌─────────────────────────────┐
                          │ fieldtrack-geo  (pure Kotlin)  │  no Android, no I/O
                          │  · KalmanFilter             │  100 % unit-testable
                          │  · AcceptancePipeline (7)   │
                          │  · MotionStateMachine       │
                          │  · consolidate / nodes /    │
                          │    clusters / speed stats   │
                          │  · Bézier / Arrows / encode │
                          │  · Track JSON + GeoJSON     │
                          │  · ports: PointStore, Clock │
                          └──────────────┬──────────────┘
                          ┌──────────────▼──────────────┐
                          │  fieldtrack-core  (Android)    │
                          │  Tracker API · FixIngestor  │
                          │  FusedLocation · FGS        │
                          │  WorkManager · Watchdog     │
                          │  ActivityTransition · Room  │
                          │  Permissions · TrackerJava  │
                          └────────┬───────────┬────────┘
                    ┌──────────────▼───┐  ┌────▼─────────┐
                    │ fieldtrack-maps     │  │ fieldtrack-sync │
                    │ (GoogleMap draw) │  │ (optional)   │
                    └──────────────────┘  └──────────────┘
                                sample-android
```

Public API, config surface, Room schema, pipeline signatures, ingestor, providers and service are all specified with real code in [API.md](API.md).

### Tracking modes

- **`CONTINUOUS`** — stream at 60 s always; the filter does all thinning. Highest fidelity, highest battery. (Reference behaviour.)
- **`ADAPTIVE`** *(default)* — stream while moving with adaptive cadence; degrade to heartbeat-only while stationary; wake on activity transition or stationary-geofence exit.
- **`MOTION_ONLY`** — location fully off while stationary. Lowest battery, coarsest stop timing. (Transistor behaviour.)

The hybrid is the point: motion-gated shutdown layered **on top of** the nine-gate filter. Neither parent product has both.

### Deliberate improvements

1. **Adaptive cadence** — 12 s while vehicular. At 60 s a car at 40 km/h covers 660 m between fixes, so a 90° turn happens *between samples* and no filter can recover it. Biggest turn-fidelity win available without a routing API. (EC-45)
2. **Bearing-change force capture** at > 30° — a fix whose heading has turned that far since the last *stored* point is stored regardless of what the speed and distance gates decided, because at a corner the geometry lives entirely in the angle between two otherwise unremarkable samples. Keyed on the last stored point rather than the last fix: keyed on the last fix, a sweeping bend accumulates nothing and plots as one chord end to end. (`Reasons.BEARING_CHANGE`, EC-45)
2a. **Turn-burst cadence** — a third tier below adaptive. While `geo/motion/TurnDetector` measures sustained turning (≥ 1.5 °/s), sampling drops to 4 s; the hold expires 30 s after the last qualifying fix. Adaptive cadence is a guess about the whole drive; this spends the battery only where the geometry is. It is **reactive by construction** — the burst arms partway through the turn that triggered it, so what it buys is the rest of a long bend, corners 2..n of a roundabout or interchange, and the next junction in a grid. A single isolated 90° turn at speed is still under-sampled at its apex; that one needs the routing API. (EC-45)
2b. **Constant-velocity filter** — position *and* an inferred velocity, replacing the scalar position-only filter ported from the reference. A filter with no term for a moving target lags it by a fixed amount every fix; at the 12 s cadence that cost one rejected fix in four on a straight road, each recovered by a forced reset that visibly jumped the track. Not the CTRV model [EKF-DESIGN-REVIEW.md](reference/EKF-DESIGN-REVIEW.md) §C1 rejects — there is no turn state to invent curvature with, and the gate takes whichever prediction is closer, so a corner is judged exactly as the scalar filter judged it. (EC-44a)
2c. **Predictive turn burst from the gyroscope** — the answer to 2a being reactive. Yaw rate about the world vertical rises as the wheel turns, a second or more before GNSS heading has moved enough to resolve it and several seconds before the next scheduled fix, so the burst runs *into* the corner rather than out of it. The gyroscope is projected onto gravity, so a phone in a pocket, a cradle or a cupholder all report the vehicle's yaw rather than their own axes. Two objections and one answer to both: the sensor is opened only while fixes report vehicular speed and released within a minute of them stopping, which is why a walker swinging a phone never reaches the gate and why the power cost is confined to the drive. (`geo/motion/GyroTurnGate`, `motion/GyroTurnMonitor`, EC-45d)
2d. **Deferred corner anchors** — the half item 2 structurally cannot reach. Bearing-change capture compares a fix against the last *stored* point, so it looks backwards, and at a corner's apex only half the turn is behind you: a 90° junction offers its apex fix as a 45° change, under any threshold set to recognise a junction. The apex is dropped, the exit fix is stored on the full 90°, and the line runs straight from approach to exit — the vertex that would have described the corner discarded one fix earlier. So a heuristic-gate rejection is held for one fix and restored if the path bent across it. **Only the heuristic gate's rejections are ever reconsidered**: it is the one gate that says a fix is *unremarkable* rather than wrong, and significance is the one thing a later fix can genuinely change. The latency is one fix, and only for fixes that were being discarded. (`geo/motion/CornerWindow`, `Reasons.CORNER_ANCHOR`, EC-45e)
3. **Persisted filter state** — closes the cold-start teleport hole ([A2](SOURCE-AUDIT.md), EC-51).
4. **Monotonic clock** — deletes the clock-skew class (EC-42, EC-88, EC-92).
5. **Batch ingestion** — recovers the fixes the reference discards ([A4](SOURCE-AUDIT.md)).
6. **Mock-location policy** — `FLAG` / `REJECT` / `ALLOW`, default `FLAG` (EC-28).
7. **Step corroboration** — the pedometer is *independent physical evidence* of motion, immune to multipath. Zero steps across a 60 m indoor excursion proves drift; 30 steps proves a walk the Doppler never saw. No competing SDK uses it. ([SDK-COMPARISON.md §6.2](SDK-COMPARISON.md), EC-133)
8. **Significant-motion hardware wake** — permission-free, no Play Services needed, near-zero power; a third wake path that works when AR is denied, GMS is absent, or geofence registration failed (EC-132, EC-09, EC-19, EC-58).
9. **`motionQuality` auto-degradation** — the incumbent's `getSensors()` reports that motion detection *will be* inaccurate; we act on it, forcing `CONTINUOUS` on hardware that cannot support motion gating ([§6.5](SDK-COMPARISON.md), EC-137).
10. **Decision log + deterministic replay** — every accuracy complaint becomes a regression test.
11. **Reactive storage** — Room `Flow` queries instead of a polled API.
12. **Zero-network plotting** — the entire plotting plane runs on-device.

---

## 5. Provenance — what is lifted, fixed, or dropped

| Reference source | → Tracker | Action |
|---|---|---|
| `utility/location/utils/KalmanLatLngFilter.kt` | `geo/filter/KalmanFilter.kt` | Port the maths. **Drop all 12 `@Volatile` fields**; state becomes an immutable `FilterState`. `predictSigma(state, at, q)` takes `q` explicitly ([A7](SOURCE-AUDIT.md)). Serialisable to `filter_state`. |
| `LocationUtil.isKalmanFilteredLocation` (`:172-669`) + constants (`:69-109`) | `geo/filter/AcceptancePipeline.kt`, `TrackerConstants.kt` | Port stage-for-stage, order preserved. Signature becomes `(fix, past, state) -> (verdict, newState, point?)`. Negative-Δt branch removed (unreachable under a monotonic clock). Gen-1 constants at `:51-61` **not** ported ([A18](SOURCE-AUDIT.md)). |
| `LocationUtil.isKalmanFilteredLocationBackup` (`:695`, ~350 lines) | — | **Drop.** Marked "Currently unused. REVERT TARGET". |
| `LocationUtil.isBetterAndBestLocation`, `isBetterLocation`, `isSpeedSpike`, `isImpossibleJump` | — | **Drop.** Gen-1; `isBetterAndBestLocation` also has a dead conditional ([A15](SOURCE-AUDIT.md)). |
| `providers/base/LocationProviderBase.kt:71-132` | `core/provider/LocationRequests.kt` | Port the five request configs. Drop the low-power request and the speed-based switcher (dead code, `BackgroundLocationProvider.kt:395-413`). |
| `providers/BackgroundLocationProvider.kt` | `core/provider/StreamProvider.kt` | Port structure and the `elapsedRealtimeNanos` staleness gate (`:325-329`). **Fix**: iterate `locationResult.locations` ([A4](SOURCE-AUDIT.md)). Strip the `LocationUpdateViewModel` singleton — emit to a `Flow`. |
| `providers/CurrentLocationProvider.kt` | `core/provider/OneShotProvider.kt` | Port the settings-resolution flow and retry caps. Same batch fix. UI/permission dialogs move out of the provider into `PermissionManager`. |
| `service/AttendanceLoggerService.kt` (1055 lines) | `core/service/TrackingService.kt` | **Rewrite.** Keep: FGS promotion + the dual-exception catch (`:925-954`), the 2-min health loop, the `AppOpsManager` revocation watcher (`:877-892`), notification lifecycle. Drop: WiFi/branch verification, floor detection, attendance approval gates, company notification data, `ACTION_AUTO_ATTEND_MEETING`. Target ≤ 350 lines. |
| `utility/location/LocationTrackingManager.kt` | `core/Tracker.kt`, `core/session/SessionManager.kt`, `core/work/Watchdog.kt` | **Rewrite.** Entirely company-coupled. Keep the watchdog logic in `isLocationHeartBeatActive()` (`:341-395`) and the raw-fix liveness clock. Drop the full-screen-intent nudge — the SDK emits an event, the host owns UI. |
| `activityrecognition/ActivityTransitionManager.kt` + receiver | `core/motion/ActivityRecognizer.kt` | Port the transition set, the `FLAG_MUTABLE` + `setPackage` PendingIntent, segment open/close, 24 h auto-close. **Fix**: add a 30 s watchdog that cancels the snapshot subscription unconditionally ([A12](SOURCE-AUDIT.md)); force-capture over an in-process `SharedFlow`, not `startForegroundService` from a receiver ([A13](SOURCE-AUDIT.md)). ObjectBox → Room. |
| `network/worker/UpdateLocationWorker.kt` | `core/work/BackstopWorker.kt` | Keep the 15-min periodic capture, 30 s timeout, linear backoff, and the `WorkInfo` state inspection. Drop the upload half (→ `fieldtrack-sync`). **Fix**: feed the shared ingestor instead of deriving its own `past` ([A3](SOURCE-AUDIT.md)). |
| `ui/company/.../EmployeeLocationHistoryViewModel.kt` (1446 lines) | `geo/plot/*` | **Mine, don't port.** Extract `filterLocationForStopsAndPunches` (`:664-738`), `generateSegmentNodes` (`:764-900`), `buildNodeSegment` (`:902-1159`), `calculateSegmentMotion` (`:1247-1297`), `durationWeightedPercentile` (`:1304-1330`), `buildActivityTimeline` (`:1334-1390`), `findDominantOverlap` (`:1404-1424`). Make all of them **pure** ([A10](SOURCE-AUDIT.md)). Drop device attribution, punch bookends, verified-office logic, networking, `LiveData`. |
| `routing/processing/RoadSnapperV2.kt` | `geo/plot/Snapper.kt` + `geo/plot/BezierRounding.kt` + `fieldtrack-snap/OsrmSnapProvider.kt` | Split: geometry → `geo` (pure, no mutation, index-based sub-path — [A10](SOURCE-AUDIT.md), [A11](SOURCE-AUDIT.md)); the HTTP call goes behind a `RoadSnapProvider` interface in an optional artifact, so no vendor lock and no API key in core. **Shipped against OSRM, not ORS:** ORS has no matching endpoint, and its `/directions` service re-*routes* between coordinates — it invents a plausible path rather than reporting the one that was driven, and would quietly straighten out any detour the user actually took. OSRM's `/match` is a real Hidden Markov matcher, self-hostable, and testable against a `MockWebServer`. `RoadSnapProvider` is one function, so a host wanting Google Roads writes that function instead of taking this artifact. |
| `routing/processing/RoadSnapper.kt` (V1), `RoadSnapperHybrid.kt`, `RTSSmoother.kt` | — | **Drop.** Superseded / disabled experiment / never wired. |
| `utility/googleMap/MapOverlayUtils.kt` (778 lines) | `geo/plot/Arrows.kt` + `maps/TrackRenderer.kt` | Split: arrow **placement maths** and speed→band → `geo` (one ladder, [A9](SOURCE-AUDIT.md)); bitmap generation, `CustomCap`, `GoogleMap` calls → `fieldtrack-maps`. |
| `database/model/ClusterRecord.kt` | `geo/plot/model/TrackSegment.kt` | Redesign as an immutable data class; drop `BaseObservable`/`@Bindable`/`DiffUtil`. |
| `database/dao/Location.kt` | `core/db/TrackPointEntity.kt` | Reshape. **Strip** `companyId`, `companyBranchId`, `companyEmployeeId`, `isLocationMatch`, `isAppLocation`, `wifiName/wifiId/wifiStatusFlag`, `checkIn`, `checkOut`, `placeId`, `deleted`, `locationName`, `LocationParser`. **Add** `uuid`, `sessionId`, `elapsedRealtimeNanos`, `isMock`, `bearingDeg`, `altitude`, `odometerMeters`, `isCharging`, `acceptReason`, `extras`. **Fix**: `hasSpeed`/`hasBearing` default `false` ([A8](SOURCE-AUDIT.md)). |
| `utility/location/LatLng.kt` | `geo/model/TrackFix.kt`, `TrackPoint.kt` | **Rewrite.** The reference type carries Places SDK objects, static-map URLs, UI flags and a construction-time clock ([A1](SOURCE-AUDIT.md)). New types carry three explicit clocks and nothing else. |
| `utility/location/fence/*`, `floor/*` | — | Out of scope v1. The stationary wake-fence is new code; floor detection is a possible later module. |

Every ported file gets a header citing its provenance and the spec section it implements.

---

## 6. Testing

| Tier | Scope | Runs on |
|---|---|---|
| **T1** | `fieldtrack-geo`: fixture replay with golden verdict files; all plotting maths | Pure JVM, no emulator, in CI on every push |
| **T2** | Room DAOs, migrations, TTL pruning, config validation, state machine with fake clock + fake location source | Robolectric |
| **T3** | FGS promotion and refusal, boot receiver, WorkManager backstop, permission ladder, process-death → filter reseed | Instrumented |
| **T4** | Field matrix, ≥ 4 OEMs | Manual, exports fixtures that join T1 |

Fixture corpus (grows from every T4 run): `steady-indoors-2h` · `urban-drive-30min` · `walk-to-lunch-and-back` · `elevator-gap` · `nlp-teleport` · `phantom-doppler` · `clock-skew` · `highway-turns` · `batched-delivery` · `process-death-resume` · `reboot-boundary`.

**Acceptance criteria** (the pass/fail gates, derived in [EDGE-CASES.md](EDGE-CASES.md)):

1. Steady 2 hours ⇒ **exactly one stored point**.
2. 30-min urban drive ⇒ 25–35 points, **zero** `Sigma Forced Reset`.
3. Walk to lunch and back ⇒ `Origin Set` → `Departure Held` → accepts → `Walk Arrival`, mirrored.
4. Elevator gap ⇒ `Recovery Held` then `Recovery Confirmed`; no teleport plotted.
5. Force-stop → relaunch ⇒ first fix is **judged**, not blind-accepted.
6. Reboot mid-session ⇒ resumes within one watchdog tick.
7. Same fixture replayed twice ⇒ **byte-identical** decision sequence.

---

## 7. Repository layout

```
traker/
├─ settings.gradle.kts · build.gradle.kts · gradle/libs.versions.toml
├─ fieldtrack-geo/          # plain Kotlin/JVM library — no Android dependency
│  └─ main/kotlin/com/field360/traker/geo/
│     ├─ model/   TrackFix, TrackPoint, FilterState, Verdict, FixDecision
│     ├─ filter/  KalmanFilter, AcceptancePipeline, Validation, TrackerConstants
│     ├─ motion/  MotionStateMachine, TurnDetector
│     ├─ plot/    Consolidation, SignificantNodes, Clusters, SpeedStats,
│     │           ActivityLabels, Snapper, Bezier, Arrows, PolylineCodec, TrackBuilder
│     ├─ export/  TrackJson, GeoJson, Fixture
│     ├─ math/    Haversine, Bearing, Geometry
│     └─ port/    PointStore, Clock, TrackLogger, RoadSnapProvider
├─ fieldtrack-core/         # Android library — the public SDK. Clean architecture, hand-wired graph.
│  └─ main/kotlin/com/field360/tracker/
│     ├─ Tracker.kt · TrackerConfig.kt          # public surface
│     ├─ domain/     model/ (TrackSession, TrackerEvent, ProviderState, TrackerResult)
│     │              repository/ (interfaces only — no Android types)
│     │              usecase/ (StartTracking, StopTracking, ResolveConfig)
│     ├─ data/       db/ (entities, daos, TrackerDatabase, mappers)
│     │              repository/ (implementations, RoomPointStore, ConfigStore)
│     │              location/ (FixMapper, LocationRequests, FusedLocationSource)
│     │              platform/ (AndroidClock, AndroidLogger)
│     ├─ capture/    FixIngestor                # the single-consumer actor
│     ├─ service/    TrackingService, CaptureBus, BootReceiver
│     ├─ motion/     ActivityTransitionReceiver, MotionController
│     ├─ work/       BackstopWorker, RestoreWorker, PruneWorker, Watchdog
│     ├─ permission/ PermissionManager, ProviderStateMonitor
│     └─ di/         TrackerModule, TrackerBindings, RepositoryModule
├─ fieldtrack-maps/ · fieldtrack-sync/
├─ fieldtrack-snap/        # optional artifact — OsrmSnapProvider. Depends on fieldtrack-geo
│                       # ONLY (its port), never on core: it turns a list of coordinates
│                       # into a list of coordinates and knows nothing else.
├─ sample-android/
├─ fixtures/ · docs/
```

---

## 8. Phases

| # | Phase | Deliverable | Est. |
|---|---|---|---|
| **0** | Scaffold | Repo, version catalog, convention plugins, CI (build + T1 + the two architecture rules from §3), publishing config | 4 d |
| **1** | Geo engine | `fieldtrack-geo` complete: Kalman, all 7 stages, motion machine, validation, constants, fixture harness, first 6 fixtures + golden files. **All T1 green before any Android code is written.** | 10 d |
| **2** | Android capture | Config + DataStore + validation, Room + DAOs + `filter_state` + migrations, `FixMapper`, `LocationSource` (fused + platform fallback), `StreamProvider` with batch ingestion, `FixIngestor` actor, AR, motion controller, `SensorProbe` + significant-motion wake + step corroboration, events, `start()`/`stop()` | 16 d |
| **3** | Permissions & resilience | Permission ladder + tier degradation, `ProviderStateMonitor`, FGS with the dual-exception path, health loop, watchdog, backstop, restore, boot receiver, stationary fence, prune worker, `motionQuality` degradation. T2 + T3 | 9 d |
| **4** | Plotting | consolidate → nodes → clusters → speed stats → labels → Bézier → arrows → `Track` → polyline JSON + GeoJSON + fixture export/replay. T1 plotting suite | 10 d |
| **5** | Sample app | 7 screens incl. 3-layer debug overlay, decision log, replay, export. First field runs → fixture corpus grows | 7 d |
| **6** | Optional modules | `fieldtrack-maps` renderer (consuming `Arrows.place()`); `fieldtrack-sync` HTTP upload with retry queue + 401 teardown | 5 d |
| **7** | Harden & ship | Field matrix on ≥ 4 OEMs, constant tuning against fixtures, Dokka + guides, private Maven publish, versioning policy | 10 d |

**≈ 71 working days (~14 weeks) for one developer.** The audit added the ingestor rework, the clock migration, `filter_state`, batch handling, the permission-tier model and a much larger test surface; the incumbent comparison added the sensor layer (§6 of SDK-COMPARISON.md). Dropping the cross-platform bridges removed 12 days. With two developers ≈ 9 weeks (phases 4–5 parallelise with 2–3).

**MVP cut** — phases 0 → 1 → 2 → 4 → 5 gives a working native sample with start/stop, motion detection, and polyline JSON in **≈ 47 days (~9.5 weeks)**. Phase 3 is not optional for production, only for a demo.

---

## 9. Risks

| Risk | Mitigation |
|---|---|
| Porting 500 lines of interlocking gates introduces silent regressions | Fixture-first: phase 1 ends with golden files before any Android code exists. Any constant change that flips a golden verdict fails CI and has to be argued for. |
| The clock migration (wall → monotonic) subtly changes behaviour | Recorded fixtures carry **both** clocks, so the same fixture can be replayed under either and the diff inspected. |
| OEM battery managers kill the service anyway | Full survival stack ([PERMISSIONS.md](PERMISSIONS.md) §7) plus a liveness event so the host can nudge. A mitigation, not a cure — no Android SDK solves this, including Transistor's. |
| Android 15/16/17 FGS tightening | `compileSdk 37`; the FGS-refusal path is a tested code path, not an exception handler. |
| No routing API ⇒ weaker turn geometry than the reference manager view | **Retired.** Four layers now, three of them offline: bearing-change capture and the turn-burst cadence tier recover geometry *at capture time*; Bézier rounds what is left; and `RoadSnapProvider` is wired end to end, with `fieldtrack-snap` shipping an OSRM implementation. A host with no provider is exactly where this row left it, which is why the offline layers came first. |
| A routing service is slow, rate-limited or offline at render time | Every provider failure degrades to raw geometry plus a `snap_unavailable` warning and a `SNAP_UNAVAILABLE` event — never an exception, never a lost track (EC-100). `OsrmSnapProvider` degrades **per chunk**, so a five-request trace losing one to a 429 keeps the other four. |
| Snapping puts the user on the wrong street | The 80 m off-road guard (EC-101) and the both-endpoints-on-road rule are the whole point of keeping the merge pure and in-tree: a parallel service road or a tunnel exit returns geometry that *looks* plausible, and the guard is the only thing between it and a confidently wrong track. |
| Room migrations in a library | `exportSchema = true`, schemas committed, never `fallbackToDestructiveMigration()`. **Migration *tests* are still outstanding** — the database is at v4 with three hand-written additive migrations and none has a `MigrationTestHelper` test, because the schema directory cannot be added to androidTest assets under AGP 9 ([BUILD.md](BUILD.md) §7). |
| Replacing the ported scalar filter with a constant-velocity model | The scalar filter was field-verified in the reference; swapping the motion model is the highest-risk change in the engine. Mitigated by keeping the change *provably non-regressive at the gate*: the pipeline measures against whichever of the two predictions is closer, so on any single fix the new filter is judged no more loosely than the old one. The gate widened 404 m → 460 m; every stationary-drift test stayed green, and `MixedModeTraceTest` pins the drive and walk capture counts. |
| Battery complaints | Three presets (Battery saver / Balanced / Max fidelity), measured cost per preset published from phase 7 field runs, default `ADAPTIVE`. |

---

## 10. Open questions

1. **Reverse geocoding** — ship as an optional enrichment (off by default; it costs quota and battery) or leave entirely to the host? Leaning optional-off.
2. **Encrypted storage** — SQLCipher for `track_point`. Config flag reserved, phase 7.

---

## 11. Next step

Phases 0 and 1 carry the whole technical risk. Once `fieldtrack-geo` replays a recorded steady-indoors hour and emits exactly one point — with the decision sequence matching a golden file byte for byte — the rest is well-understood Android plumbing.

Scaffold on approval:

```
traker/
  settings.gradle.kts · build.gradle.kts · gradle/libs.versions.toml
  fieldtrack-geo/   KalmanFilter + FilterState + fixture harness + first golden test
  .github/workflows/ci.yml   build + T1 + architecture rules
```
