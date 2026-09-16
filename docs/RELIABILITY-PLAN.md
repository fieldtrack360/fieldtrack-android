# Reliability Plan — tracking that survives every device

Derived row by row from [DEVICE-TRACKING-FAILURE-MATRIX.md](DEVICE-TRACKING-FAILURE-MATRIX.md).
This is the engineering plan; the matrix is the evidence.

## 1. Definition of "seamless"

An SDK cannot make Android do what Android forbids. "Seamless" therefore means all four of:

| Tier | Meaning | Target |
|---|---|---|
| **S0 — Never our bug** | No SDK-side defect ever ends or corrupts a session. | 100 % of matrix §7 closed, regression-tested. |
| **S1 — Auto-recover** | Wherever the platform offers *any* legal wake path, the SDK uses it and tracking is back without a human. | p95 gap after a kill ≤ 5 min on a correctly configured device, any OEM. |
| **S2 — Guided fix** | Where only a user or admin can change a setting, the SDK detects it, names it, and hands the host a one-tap `Intent` to the exact screen. The host has to render one screen. | Every **KILL** row that has a user fix is covered by the readiness API. |
| **S3 — Detect-only** | Where nothing can be done (force stop, MDM denial, no GNSS hardware), the SDK records it so the fleet dashboard shows *why* — never a silent gap. | 100 % of KILL rows produce a durable, shipped log entry. |

What stays outside any tier, and must be said to clients (matrix §3, §5, §6): force stop, MDM
location restrictions, no GNSS receiver, Private Space / clones / secondary users, licence, and
the environment. We detect and report; we do not work around.

---

## 2. Coverage map — matrix section → plan item → resulting tier

| Matrix section | Rows | Plan items | Tier after plan |
|---|---|---|---|
| §1 Platform rules | 22 | P1-1 readiness API, P1-2 exact-alarm helpers, P1-5 auto-revoke check, P2-1 revival geofence, P2-4 remote wake, P0-4 cold-start budget | S1 for revival rows; S2 for permission rows |
| §2 OEM killers | 20 | P1-1 readiness, P1-3 OEM deep-link intents, P2-1 revival geofence, P2-3 `WakeLockPolicy.AUTO`, P3-1 setup-UI module, P3-2 remote OEM knowledge, P3-4 MDM templates | S2 (one screen per device) + S1 once configured |
| §3 User actions | 19 | P1-4 clone/user detection, P1-6 auto-resume after interruption, P1-1 readiness (location/permission banners), P3-1 setup UI | S1 for reversible ones; S3 for force stop |
| §4 Play Services | 6 | P1-7 provider fallback, P2-5 stationary no-wake fallback | S1 |
| §5 Hardware / environment | 10 | P2-6 adaptive accuracy relax, P2-5 stationary fallback, P1-1 sensor report in readiness | S1 / S3 (environment is expected) |
| §6 SDK gates | 11 | P1-8 auth-expiry policy + credential refresh hook, P1-1 readiness (channel blocked, integrity) | S2 |
| §7 SDK defects | 6 | P0-1 … P0-3 | S0 |

---

## 3. Phases

Effort is in engineer-days, single engineer, excluding device-lab time. "Rows" cites the matrix.

### Phase 0 — never our bug (this week, ~4 d)

| # | Item | What | Why / rows | Effort |
|---|---|---|---|---|
| P0-1 | **Commit the lifecycle lock** | `CaptureLifecycleLock` already on `master`, uncommitted. Add `CaptureLifecycleLockTest` (start vs resume race under `runTest`, stop vs resume, revival no-op). | §7 row 1 — the only defect that corrupts points. | 1 |
| P0-2 | **Close the three open §7 items** | (a) `teardown()` joins/flags the supervision job before releasing wake lock and notification; (b) clear `startRequestedAtMs` in `promoteToForeground`'s catch; (c) `LogRecorder` tag in `SdkLogRelay.MUTED_TAGS` + back-off after 5 consecutive write failures; (d) branch the "foreground service REFUSED" diagnostic on `foregroundService`. | §7 rows 3–6. | 1.5 |
| P0-3 | **Unblock the test suite** | `SyncSchedulerTest` references a removed `registerFlush`; fix or delete. Make `:fieldtrack-core:testDebugUnitTest` a required check before any tag. | Nothing in §7 had a test that could have caught it. | 0.5 |
| P0-4 | **Cold-start budget** | `Tracker.init` must do no I/O on main; measure `TrackerGraph` construction; assert < 200 ms in an instrumentation test. Ship a Gradle check that warns when the host manifest still carries `WorkManagerInitializer`. | §1 row 4 — the one **fatal** crash we cannot catch. | 1 |

### Phase 1 — detect and guide (2–3 weeks, ~12 d)

| # | Item | What | Why / rows | Effort |
|---|---|---|---|---|
| P1-1 | **`Tracker.readiness()` API** | One structured snapshot: `List<ReadinessItem(id, severity KILL/GAP/DEGRADE, state OK/FIX/UNKNOWN, fixIntent: Intent?, userText)>`. Items: location tier, precise location, notifications + channel importance, physical activity, background-restricted, standby bucket, battery exemption, exact-alarm grant, auto-revoke whitelisted, Play Services availability, provider in use, sensor quality, user profile (clone/secondary), Private Space, OEM autostart (UNKNOWN + deep link — see P1-3), storage headroom. Also emitted as `TrackerEvent.ReadinessChange` when any item flips, and written to the `DEVICE_LOCATION` log entry. | Every S2 row. Today the host has to know 15 platform APIs; this collapses it to one list. | 4 |
| P1-2 | **Exact-alarm helpers** | `PermissionManager.canScheduleExactAlarms()`, `exactAlarmSettingsIntent()` (`ACTION_REQUEST_SCHEDULE_EXACT_ALARM`). Heartbeat already upgrades at runtime; the host just lacked the button. | §1 rows 12+/14+ — exact alarm is also the FGS-start exemption. | 0.5 |
| P1-3 | **OEM deep-link intents** | `PermissionManager.oemBackgroundSettingsIntents(): List<LabeledIntent>` — the known autostart / battery-manager activities for Xiaomi, Huawei, OPPO, Vivo, Samsung, Asus, Tecno, Meizu, Lenovo; each `resolveActivity`-guarded, returned only if present. Table lives in a resource JSON (see P3-2). | §2 — turns "go find Autostart" into one tap. | 2 |
| P1-4 | **Clone / secondary-user guard** | In `ready()`: `UserManager.isSystemUser` false or `Process.myUserHandle() != SYSTEM` → `TrackerResult.Error(UNSUPPORTED_PROFILE)` with text; readiness item KILL. | §3 clones, multi-user, Private Space. Silent today. | 0.5 |
| P1-5 | **Auto-revoke check** | Readiness item from `PackageManager.isAutoRevokeWhitelisted()`; fix intent `ACTION_AUTO_REVOKE_PERMISSIONS`. | §1 row 11+ — the "worked for months, then died" report. | 0.5 |
| P1-6 | **Auto-resume after interruption** | `ServiceConfig.autoResumeInterruptedWithinMin` (default 0 = off). On `ready()` finding an open session younger than N minutes with no pipeline, resume it instead of only emitting `SessionInterrupted`. | §3 force stop / OEM kill: the driver reopens the app and tracking is back without a tap. | 1 |
| P1-7 | **Provider fallback** | `GeolocationConfig.providerFallback = true` (default): if `FUSED` and `GoogleApiAvailability` ≠ SUCCESS → use `LocationManager.FUSED_PROVIDER` (API 31+) else `GPS_ONLY`; emit `Diagnostic` naming the fallback. Disable AR/geofence stages automatically on the same signal. | §4 rows 1–2 — a KILL at start becomes a DEGRADE. | 1.5 |
| P1-8 | **Auth-expiry policy** | `SyncConfig.onAuthExpired = STOP_TRACKING_AND_CLEAR` (current) \| `PAUSE_UPLOADS_KEEP_TRACKING`. Add `SyncConfig.credentialProvider: suspend () -> Map<String,String>?` called once on 401 before either policy runs; a non-null result retries the batch and nothing stops. | §6 row 401 — the only row that kills a whole fleet at once. | 1.5 |
| P1-9 | **Docs + sample** | Sample app renders `readiness()` as a checklist with the fix buttons; INTEGRATION-GUIDE §4 rewritten around it. | Adoption. | 1 |

### Phase 2 — recover without a human (3–4 weeks, ~14 d)

| # | Item | What | Why / rows | Effort |
|---|---|---|---|---|
| P2-1 | **Revival geofence** | Today a geofence is armed only when `STATIONARY`. Add a rolling **exit** geofence (radius `max(150 m, 3 × accuracy)`) re-centred on every accepted point while `MOVING`, and one armed on `onDestroy`/`SessionTeardown` skipped paths around the last point. Exit → `StationaryFenceReceiver` → `reviveServiceIfNeeded` — an FGS-start **exemption** on 12+ that does not depend on JobScheduler, standby bucket or exact-alarm grant. Cost: one GMS geofence update per accepted point (throttle to ≥ 60 s / ≥ 100 m). | §1 12+/14+ revival rows, §2 every OEM: after a kill while driving, tracking is back within ~150 m instead of ≤ 15 min. | 3 |
| P2-2 | **Revival audit** | Trace every wake path (AR transition, geofence, heartbeat, backstop, boot, restore) through `reviveServiceIfNeeded` on API 31/34/35 with the app background-restricted, in `RESTRICTED` bucket, and with exact alarms denied. Record which paths still deliver. Fix what does not (e.g. AR registration lost after process death → re-register from the receiver). | We assume the exemptions work; nobody has measured them per bucket. | 2 |
| P2-3 | **`WakeLockPolicy.AUTO`** | New default: `CONTINUOUS` while `MOVING` on manufacturers in the aggressive list (Xiaomi, OPPO/Realme, Vivo, OnePlus ≤ OOS 11, Huawei, Tecno), `PER_FIX` otherwise and while stationary. Manufacturer list from P3-2. | §2 — the 5–20 min post-screen-off kill is a CPU-suspend problem; a held partial wake lock while moving is the cheapest countermeasure we have not turned on. | 1 |
| P2-4 | **Remote wake entry point** | `Tracker.wake(context)` (static, safe from any receiver/FCM service): if a session is open and the service is dead → `TrackingService.start`. Document the FCM high-priority pattern: backend sends a data message when a device with an open shift has not uploaded for N minutes; a high-priority FCM message is an FGS-start exemption and pierces Doze. Optional `fieldtrack-push` module wraps `FirebaseMessagingService`. | §2 — the only wake path an OEM cannot disable without also breaking WhatsApp. Requires host FCM. | 2 (+2 module) |
| P2-5 | **Stationary fallback without wake sources** | When neither GMS geofence nor significant-motion sensor is available, do not stop the stream on `STATIONARY`; drop to a slow cadence (`intervalMs × 5`) instead. | §5 row "no SMD + no GMS" — 15-min gap at the start of every trip becomes ≤ 1 interval. | 1 |
| P2-6 | **Adaptive accuracy relax** | If > 80 % of fixes in a 5-min window are rejected for accuracy and the device is moving (speed > 3 m/s from raw fixes), widen the threshold stepwise up to a `maxRelaxedAccuracyM` cap; tighten back when accuracy recovers. Flag relaxed points in `providerFlags`. | §5 budget GNSS rows — "zero points" on cheap phones becomes "coarser points, flagged". | 2 |
| P2-7 | **OEM kill telemetry** | On revival, log `kill_gap_s`, `wake_path`, manufacturer, bucket, restricted flag, exact-alarm state into the diagnostic channel. | Turns S1 into a measured number per OEM (the acceptance metric below). | 1 |

### Phase 3 — make it a product, not a checklist (4–6 weeks, ~18 d)

| # | Item | What | Why / rows | Effort |
|---|---|---|---|---|
| P3-1 | **`fieldtrack-setup-ui` module** | Compose `DeviceSetupScreen(readiness)` + View equivalent: one card per readiness item, fix button, done-state, brand-specific step text and screenshots-as-text. Host launches one activity. | §2 + §3: the host currently has to build this; most will not. | 6 |
| P3-2 | **Remote-updatable OEM knowledge** | Bundle `oem-rules.json` (manufacturer/brand/OS-version → autostart intents, menu paths, aggressive flag, recommended wake-lock policy); allow the host to supply a newer file or URL. New ROM releases then need no SDK release. | §2 menu paths drift every ROM version. | 2 |
| P3-3 | **Fleet readiness on the backend** | Define the `DEVICE_LOCATION` + readiness log schema for the dashboard (SYNC-BACKEND-AND-DASHBOARD.md): per-device readiness score, last kill gap, wake path histogram, "devices that will lose tracking tonight" list. | S3: every unexplained gap becomes explained at the fleet level. | 3 (spec + reference queries) |
| P3-4 | **MDM templates** | Android Enterprise managed-config and OEMConfig snippets (Samsung Knox battery allowlist, Zebra power manager) that pre-apply the §2 settings and set `DISALLOW_APPS_CONTROL`. | Managed fleets can be seamless on day one. | 2 |
| P3-5 | **Device-lab regression** | Six real devices (Xiaomi HyperOS, OnePlus OOS 14, Samsung One UI 6, Vivo, OPPO, Pixel), a scripted 4-hour screen-off "drive" (mock route via ADB on debuggable build, real GNSS on a car rig monthly), kill injection (`am kill`, `am force-stop`, `cmd deviceidle force-idle`, `am set-standby-bucket restricted`). Report p50/p95 recovery gap per device per wake path. Run before every tag. | Without this, S1 is a claim. | 5 (setup) + ongoing |

---

## 4. Acceptance metrics

| Metric | Now | Target | Measured by |
|---|---|---|---|
| Duplicate-launch diagnostics per 1 000 sessions | unknown, > 0 | 0 | P2-7 telemetry |
| p95 recovery gap after process kill, configured device, driving | 15 min (heartbeat) | ≤ 5 min; ≤ 150 m with P2-1 | P3-5 lab + P2-7 |
| p95 recovery gap, **unconfigured** Xiaomi/ColorOS device | ∞ (never) | ≤ 15 min with FCM (P2-4); unchanged without | P3-5 lab |
| KILL rows with a user fix covered by `readiness()` | 0 (host builds it) | 100 % | code review vs matrix |
| Start refusals that are silent to the fleet | most | 0 | P3-3 dashboard |
| Cold-start promotion latency p95 | unmeasured | < 3 s (of 10 s) | existing `Diagnostic` + P0-4 test |
| Sessions ended by 401 | all | 0 with `credentialProvider`; policy-controlled otherwise | P1-8 |
| Unit test suite | does not compile | green, required for tag | P0-3 |

---

## 5. Decisions needed from the host / product owner

These are Play-policy or infrastructure decisions the SDK deliberately does not make (EC-15):

1. **`SCHEDULE_EXACT_ALARM`** in the host manifest — exact heartbeat + FGS-start exemption on 12+. Needs a justification in the Play listing.
2. **`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`** in the host manifest — one-tap exemption dialog. Play-reviewed; continuous location tracking is an accepted use case.
3. **FCM** in the host app — enables P2-4, the only wake path OEM battery managers do not block.
4. **Auth-expiry policy** (P1-8) — whether a 401 should ever end a shift for this product.
5. **Wake-lock budget** — `AUTO` (P2-3) costs battery on the aggressive ROMs; confirm acceptable.

---

## 6. Risks

| Risk | Mitigation |
|---|---|
| OEM deep-link activities are undocumented and change | `resolveActivity` guard; remote JSON (P3-2); always fall back to the standard Settings intents |
| Revival geofence adds GMS traffic | Throttle re-centre to ≥ 60 s / ≥ 100 m; skip when accuracy > 100 m; measure battery in P3-5 |
| `WakeLockPolicy.AUTO` battery cost | Moving-only; manufacturer-gated; telemetry on hold time |
| Exemptions behave differently per OEM build | P2-2 audit before relying on any; keep the layered design |
| Scope creep in the UI module | Ship readiness API first (P1-1); UI is a consumer of it, not a dependency |

---

## 7. Order of work, one line

P0 (bug-free) → P1-1 readiness (the lever for everything else) → P1-7/P1-8 (two KILL-at-start rows) → P2-1 revival geofence + P2-2 audit (the S1 win) → P2-4 remote wake → UI, telemetry, lab.
