# Device Tracking Failure Matrix

Every known way background location tracking stops, degrades or never starts on an Android
device, by device family, with the reason and the fix. Companion to
[PERMISSIONS.md](PERMISSIONS.md) (the mechanism) and
[INTEGRATION-GUIDE.md](INTEGRATION-GUIDE.md) (the API). This document is the field-support
view: **a phone stopped recording — which row is it?**

## How to read this

| Column | Meaning |
|---|---|
| **Device / ROM** | Which hardware or skin the row applies to. `All` = every Android device. |
| **Android** | API level or version range where the behaviour exists. |
| **Scenario → why tracking breaks** | The trigger and the mechanism. |
| **SDK signal** | What the SDK emits so a host or the diagnostic log can name the row. `Error(X)` = `TrackerEvent.Error` with `ErrorCode.X`. `Diagnostic` = `TrackerEvent.Diagnostic`. `DEVICE_LOCATION` = the session-start log entry in the sync log channel. |
| **Fix — host app** | What the integrating app can do in code or config. |
| **Fix — user / device** | The setting the user (or an MDM) has to change. The SDK **never** opens Settings on its own; the host launches the intents it exposes. |

Severity, where it matters: **KILL** = tracking stops and stays stopped until something outside
the SDK acts. **GAP** = tracking pauses and the SDK's revival layers bring it back within
minutes. **DEGRADE** = points keep arriving but fewer, later or worse.

Recovery layers referenced below (PERMISSIONS.md §7): foreground service (FGS), in-service
health loop + watchdog, `AlarmManager` heartbeat (15 min, inexact unless the host declares
`SCHEDULE_EXACT_ALARM`), `WorkManager` backstop (15 min) and restore worker, stationary geofence
+ significant-motion wake, boot receiver.

---

## 1. Platform rules — every device, by Android version

| Device / ROM | Android | Scenario → why tracking breaks | SDK signal | Fix — host app | Fix — user / device |
|---|---|---|---|---|---|
| All | 6+ (23) | Runtime location permission denied → no provider access. **KILL** at start. | `TrackerResult.Error(PERMISSION_DENIED)`; `Error(PERMISSION_DENIED)` if revoked mid-session; `CaptureSuspended` | Drive the ladder from an Activity (`PermissionManager.backgroundRequest()`, `foregroundPermissions()`); after `shouldStopAsking()` deep-link with `appSettingsIntent()` | Grant Location |
| All | 6+ (23) | Doze: device idle → network deferred, alarms batched, jobs held. FGS is exempt; **everything else waits for a maintenance window**. **GAP** after a kill. | `Diagnostic` from `BackgroundRestrictions.describe()`; `Heartbeat` cadence stretches | Keep `foregroundService = true` (default). Heartbeat uses `setAndAllowWhileIdle`, which pierces Doze once per ~9 min; declare `SCHEDULE_EXACT_ALARM` in **your** manifest if your listing qualifies → exact heartbeat | Battery → App → *Unrestricted* / *Don't optimise* (`batteryOptimizationSettingsIntent()`) |
| All | 8+ (26) | Background location limit: an app **not** in an FGS gets a few fixes per hour. Only bites when the FGS is dead. **DEGRADE→GAP** | Watchdog: `Error(TRACKER_DEAD)` after `deadTrackerMovingMin` | Nothing beyond the FGS; make sure the host never sets `foregroundService = false` in production | — |
| All | 8+ (26) | `startService` from background throws; SDK uses `startForegroundService`, but the platform then holds a **~10 s start-foreground timer**. Host `Application.onCreate` + every `ContentProvider` runs inside it on a cold start → `ForegroundServiceDidNotStartInTimeException`, a **fatal crash the SDK cannot catch**. **KILL** (crash loop on every revival) | `Diagnostic("foreground promotion took Nms …")` when ≥ 5 s — the near-miss warning | Keep `Application.onCreate` lean; remove WorkManager's `androidx.startup` initializer (INTEGRATION-GUIDE §1.7 — `WorkManagerAccess` makes this manifest-only); no blocking I/O or network in `onCreate` | — |
| All | 9+ (28) | App Standby Buckets: `RARE` defers jobs hours; `RESTRICTED` (10+) runs jobs ~once a day and blocks FGS start from background. Backstop + restore worker stop firing. **GAP→KILL** after a kill | `DEVICE_LOCATION.standby_bucket`; `Diagnostic` "background restrictions: … bucket=RESTRICTED" (WARN when `degraded`) | Read `PermissionManager.backgroundRestrictions().degraded` in your settings screen; offer the exemption; heartbeat is the layer that still runs | Open the app regularly; Battery → *Unrestricted* |
| All | 9+ (28) | **Background restricted** toggle (Settings → Apps → Battery → Restricted): no jobs, no alarms delivered, FGS start refused. **KILL** after the first kill | `DEVICE_LOCATION.background_restricted = true`; `Diagnostic` "restricted=true" | Surface `backgroundRestrictions().backgroundRestricted` prominently; block Start until fixed if your product can afford it | Battery → *Unrestricted* |
| All | 9+ (28) | Background apps cannot receive sensor events (accelerometer, step, gyro) unless in an FGS. Motion stages go blind when the FGS dies. **DEGRADE** | `MotionChange` stops; `Diagnostic` motion probe lines | FGS on; nothing else | — |
| All | 10+ (29) | `ACCESS_BACKGROUND_LOCATION` separate. **While-using only**: fixes continue while the FGS lives, but every *revival from the background* comes up without location. **GAP** becomes permanent after the first OEM kill | `Error(BACKGROUND_PERMISSION_MISSING)` at start (tier `FOREGROUND_ONLY`) | Run the full ladder; on 11+ the "Allow all the time" step is a Settings deep-link (`backgroundRequest()` returns the step) | Location permission → *Allow all the time* |
| All | 10+ (29) | `ACTIVITY_RECOGNITION` denied → no activity transitions. **DEGRADE** (motion falls back to speed + displacement) | `Diagnostic("activity_recognition_unavailable")`; WARN "ACTIVITY_RECOGNITION not granted" | Request via `activityRecognitionPermissions()`; optional | Grant Physical activity |
| All | 11+ (30) | **Permission auto-reset / app hibernation**: an app unused for ~months loses runtime permissions (incl. background location) and is force-stopped. Backported to 6–10 via Play services (Dec 2021). **KILL** for occasional-use installs | `Error(PERMISSION_DENIED)` on next start; `SessionInterrupted` if a session was open | Detect with `PackageManager.isAutoRevokeWhitelisted()`; send users to `Intent.ACTION_AUTO_REVOKE_PERMISSIONS`; re-run the ladder on every launch | App info → *Pause app activity if unused* OFF |
| All | 12+ (31) | **FGS start from background refused** (`ForegroundServiceStartNotAllowedException`). Hits every revival path — heartbeat, restore worker, backstop — unless an exemption applies (exact alarm, geofence/AR PendingIntent, BOOT_COMPLETED). **GAP→KILL** | `Error(FGS_START_REFUSED)`; `Diagnostic` "foreground service REFUSED at start()" | Declare `SCHEDULE_EXACT_ALARM` yourself (exact alarm = exemption); keep activity recognition on (its transition PendingIntent is an exemption); `ServiceRestorer` backs off rather than loops | Battery → *Unrestricted*; on 14+ also grant *Alarms & reminders* |
| All | 12+ (31) | **Approximate location** toggle → 1–3 km error. SDK refuses to start in any mode except `MOTION_ONLY`. **KILL** at start | `TrackerResult.Error(COARSE_ONLY)` | Explain precise location in your rationale; `permissions.accuracy()` tells you before `start()` | Permission → *Use precise location* ON |
| All | 12 (31) only | `SCHEDULE_EXACT_ALARM` granted by default on 12; can be revoked in Settings. Heartbeat falls back to inexact. **GAP** widens | Heartbeat logs "inexact" (`sdkLog`) | Check `AlarmManager.canScheduleExactAlarms()` in your settings UI | *Alarms & reminders* → Allow |
| All | 13+ (33) | `POST_NOTIFICATIONS` denied → FGS notification invisible. Tracking runs, but the user cannot see or stop it, and some OEMs treat an invisible FGS as killable. **DEGRADE** | `Error(NOTIFICATION_HIDDEN)` | Request `notificationPermissions()` first in the ladder | Notifications → Allow |
| All | 13+ (33) | **"Active apps" → Stop** in the notification shade (and Task Manager on One UI): equivalent to Force stop. All alarms cancelled, receivers muted, jobs cancelled. **KILL** until the user opens the app | Nothing at the time (process is gone). `SessionInterrupted` on next `ready()` (EC-66) | Detect `SessionInterrupted` and prompt to resume; educate. MDM: `DISALLOW_APPS_CONTROL` | Don't tap Stop |
| All | 14+ (34) | Location-type FGS may only **start** from an eligible state even when permission is granted → `SecurityException`. Same paths as the 12+ row. **GAP→KILL** | `Error(FGS_START_REFUSED)` | As 12+ row | As 12+ row |
| All | 14+ (34) | `SCHEDULE_EXACT_ALARM` **denied by default** for apps targeting 34+ (except alarm/clock apps). Heartbeat inexact (≥ 9 min windows, Doze-batched) unless the user grants it. **GAP** | Heartbeat inexact | Either declare `USE_EXACT_ALARM` (Play-reviewed, alarm/calendar apps only) or send users to `ACTION_REQUEST_SCHEDULE_EXACT_ALARM` | *Alarms & reminders* → Allow |
| All | 14+ (34) | Users can **swipe away the ongoing FGS notification**. Service keeps running; the user thinks it stopped, or an OEM that judges by notification visibility kills it (EC-76). **DEGRADE** | none | Show tracking state inside the app; don't rely on the notification as the only indicator | — |
| All | 14+ (34) | **Cached-app freezing**: processes with no FGS are frozen (threads stopped). Irrelevant while the FGS lives; after a kill, a revived-but-not-promoted process is frozen mid-work. **GAP** | `Error(FGS_START_REFUSED)` precedes it | Nothing beyond keeping the FGS alive | — |
| All | 15+ (35) | **Private Space**: apps inside it are stopped when the space is locked. **KILL** while locked | none (process stopped) | Document: do not install the app in Private Space | Keep the app in the main profile |
| All | 15+ (35) | FGS **time limits** for `dataSync`/`mediaProcessing` types — **not** `location`. No effect on this SDK. Listed so nobody spends time on it. | — | — | — |
| All | 16+ (36) | JobScheduler **runtime quota** tightened per standby bucket → backstop/restore work deferred longer in `WORKING_SET`/`FREQUENT`. **GAP** widens after a kill. Verify on each targetSdk bump. | Backstop misses visible in `ensureBackstopAlive` WARN | Heartbeat (AlarmManager) is unaffected; keep it exact where possible | Battery → *Unrestricted* |

---

## 2. OEM battery managers — the aggressive ROMs

These skins kill foreground services that stock Android would keep. Each row is **KILL** unless
the listed settings are changed; the SDK's revival layers then bring tracking back, but on
these ROMs those layers are themselves the thing being disabled (PERMISSIONS.md §7).
[dontkillmyapp.com](https://dontkillmyapp.com) tracks the per-model menu paths as they move.

| Device / ROM | Android | Scenario → why tracking breaks | SDK signal | Fix — host app | Fix — user / device |
|---|---|---|---|---|---|
| **Xiaomi / Redmi / POCO** — MIUI 10–14, HyperOS 1–2 | 9+ | Default per-app battery saver *Restricted* + Autostart OFF: the app is put in the `RESTRICTED` bucket → **no `JobScheduler` work at all** (EC-22), no revival after boot (EC-126), process killed minutes after screen-off. Swipe from recents kills the process in the same breath. The single most common KILL in the field. | `DEVICE_LOCATION.standby_bucket = RESTRICTED`, `background_restricted`; `Error(FGS_START_REFUSED)` on revival; `SessionInterrupted` next launch | Detect `Build.MANUFACTURER == "Xiaomi"` and show a one-time setup screen; call `backgroundRestrictions()` on resume to confirm; `WakeLockPolicy.CONTINUOUS` if fix gaps persist (battery cost) | Security app → Permissions → **Autostart ON**; Settings → Apps → [app] → Battery saver → **No restrictions**; Recents → long-press → **Lock**; Battery & performance → *Battery saver* OFF |
| Xiaomi — MIUI *Optimization* toggle | 9+ | Developer option "MIUI optimization" ON (default) adds an extra app-freeze layer; OFF disables MIUI's own killers. Only reachable via developer options. | as above | — | Developer options → *Turn on MIUI optimization* OFF (advanced users only) |
| **OnePlus** — OxygenOS 9–11 | 9–11 | *Battery optimization* default + *Advanced optimization → Sleep standby optimization / Deep optimization* kill the FGS after ~15–20 min screen-off. Documented target-hardware failure in this repo (INTEGRATION-GUIDE §5.2: "17-minute drive"). | `Error(TRACKER_DEAD)`; `SessionInterrupted` | OnePlus setup screen; exemption intent; consider `CONTINUOUS` wake lock on OOS ≤ 11 | Battery → Battery optimization → [app] → **Don't optimize**; Battery → Advanced → **Sleep standby optimization OFF**, **Deep optimization OFF**; Recents → Lock |
| **OnePlus** — OxygenOS 12+ (ColorOS base) | 12+ | *App battery management* default "Optimize" freezes background apps; *Adaptive battery* deprioritises. | as above | as above | Battery → **App battery management** → [app] → *Allow background activity* / *Don't optimize*; *Adaptive battery* OFF |
| **OPPO / Realme / OnePlus 12+** — ColorOS 7–14, Realme UI | 9+ | *Background freeze* after ~5 min screen-off; *Auto-launch* OFF by default; "Optimize battery use" ON. Autostart off = no boot revival. | `Error(FGS_START_REFUSED)`; `SessionInterrupted` | Setup screen keyed on `Build.MANUFACTURER` ∈ {OPPO, realme}; exemption intent | Settings → Battery → More settings → *Optimize battery use* → [app] → **Don't optimize**; Apps → [app] → **Allow auto-launch**, **Allow background activity**; Recents → Lock; *Power saving mode* OFF |
| **Vivo / iQOO** — Funtouch OS 9–13, OriginOS | 9+ | *Background power consumption* limited by default; *Autostart* OFF; process killed on swipe (noted in `StartTrackingUseCase` — the `NonCancellable` block exists for this ROM). | as above | Setup screen for `vivo`; exemption intent | i Manager → App manager → **Autostart manager** → allow; Settings → Battery → **Background power consumption management** → *Allow high background power consumption*; Recents → Lock |
| **Samsung** — One UI 2–7 | 10+ | *Sleeping apps* / *Deep sleeping apps* lists and **"Put unused apps to sleep"** (auto-adds apps after ~3 days idle). Deep sleep = no background at all. *Adaptive battery* deprioritises. Device care's nightly auto-optimisation can kill. | `DEVICE_LOCATION.standby_bucket` RARE/RESTRICTED; `Error(TRACKER_DEAD)` | Setup screen for `samsung`; `batteryOptimizationSettingsIntent()` lands on the right screen | Battery → **Background usage limits** → add to **Never sleeping apps**, remove from Sleeping/Deep sleeping; *Put unused apps to sleep* OFF; *Adaptive battery* OFF; Apps → [app] → Battery → **Unrestricted** |
| Samsung — *Power saving* / *Maximum power saving* | 10+ | Maximum power saving restricts background data and location for non-allowlisted apps. | `PowerSaveChange(true)`; watchdog threshold doubles (EC-21) | Show a banner on `PowerSaveChange` | Turn off, or add the app to the allowed list |
| Samsung — Secure Folder / Dual Messenger clone | 10+ | Clone runs as a secondary user profile; background location and FGS for that profile are paused when the primary profile is active. Not a supported install location. | none (process not started) | Document: install in the main profile only | Use the primary app |
| **Huawei** — EMUI 9–12 / HarmonyOS 2–4; **Honor** — Magic UI / MagicOS | 9+ | *App launch → Manage automatically* kills anything not allowlisted; older EMUI *Protected apps*. **PowerGenie** (EMUI 9, pre-installed) kills unlisted apps and is not user-editable. Plus **no Google Play Services** on 2019+ devices (see §4). | `Error(PLAY_SERVICES_UNAVAILABLE)` at start (default `FUSED`); `Error(TRACKER_DEAD)` | `geolocation.providerType = GPS_ONLY` or `NETWORK_ONLY` for GMS-less builds; setup screen for `HUAWEI`/`HONOR` | Settings → Battery → **App launch** → [app] → *Manage manually* → **Auto-launch, Secondary launch, Run in background ON**; Recents → Lock; PowerGenie: remove via `adb shell pm uninstall -k --user 0 com.huawei.powergenie` (advanced) |
| **Tecno / Infinix / itel** — HiOS, XOS, itelOS | 10+ | *Phone Master* auto-start manager and power-saver whitelist default to off/kill. Cheap GNSS chips add slow fixes (see §5). | `Error(FGS_START_REFUSED)`; `Error(FIX_TIMEOUT)` | Setup screen for `TECNO`/`INFINIX`/`itel` | Phone Master → **Auto-start management** → allow; Power Saver → **whitelist**; Battery → [app] → Unrestricted |
| **Asus** — ZenUI | 9+ | *Mobile Manager → PowerMaster* "Clean up in suspend", "Auto-deny apps from auto starting". | as above | Setup screen for `asus` | Mobile Manager → PowerMaster → **Auto-start manager** → allow; Battery-saving options → *Clean up in suspend* OFF, *Auto-deny…* OFF |
| **Sony Xperia** | 9+ | *STAMINA mode* limits background; standard battery optimisation. | `PowerSaveChange`; `Error(TRACKER_DEAD)` | Standard exemption flow | Battery → **STAMINA mode** OFF or app exempted; Battery optimization → *Don't optimize* |
| **Lenovo / Motorola (Lenovo-era)** | 9+ | Motorola is near-stock (Adaptive battery only). Lenovo tablets/phones ship an autostart manager. | as stock | Standard flow | Lenovo: Settings → Apps → **Background app management**; Moto: Battery → *Adaptive battery* OFF, Battery optimization → Don't optimize |
| **Meizu** — Flyme | 9+ | *Security → Permissions → Background management* kills by default. | as above | Setup screen for `Meizu` | Security → Permissions → **Background management** → *Keep alive*; Autostart → allow |
| **Nokia (HMD)** — Android 8–9 era | 8–9 | Pre-installed **Evenwell power saver** (`com.evenwell.powersaving.g3`) killed every FGS ~20 min after screen-off. Removed in later HMD updates. | `Error(TRACKER_DEAD)`; `SessionInterrupted` | Detect `Build.MANUFACTURER == "HMD Global"` + API ≤ 28 and warn | Update firmware; otherwise `adb shell pm disable-user --user 0 com.evenwell.powersaving.g3` |
| **Google Pixel / stock AOSP** | 9+ | Baseline behaviour — the rows in §1 only. *Adaptive Battery* moves idle apps to worse buckets. **Extreme Battery Saver** pauses every app not marked essential → **KILL**. | `PowerSaveChange`; `DEVICE_LOCATION.standby_bucket` | Standard flow | Battery Saver → Extreme: add the app to **Essential apps**; Adaptive Battery OFF if gaps persist |
| **Nothing OS, Fairphone, Motorola (current), Sony (current)** | 12+ | Near-stock; §1 rules apply. | as stock | Standard flow | Battery → Unrestricted |
| **Android Go edition** (≤ 2 GB RAM) | 8+ | Low-memory killer reclaims the FGS process under pressure far sooner than a normal device; fewer sensors (§5). **GAP** frequent | `SessionInterrupted`; `Error(MOTION_DETECTION_DEGRADED)` | Keep the host process small; avoid heavy in-process work while tracking; expect more revival cycles | Close other apps; nothing else |

---

## 3. User actions and app lifecycle — every device

| Device / ROM | Android | Scenario → why tracking breaks | SDK signal | Fix — host app | Fix — user / device |
|---|---|---|---|---|---|
| All | all | **Force stop** (App info → Force stop). App enters *stopped state*: alarms cancelled, jobs cancelled, no broadcasts (BOOT_COMPLETED included until next launch). **KILL** | `SessionInterrupted` on next `ready()` (EC-66) | Resume prompt on `SessionInterrupted`; MDM `DISALLOW_APPS_CONTROL` | Open the app |
| All | all | **Swipe from recents.** Stock: `stopWithTask="false"` keeps the FGS. MIUI/ColorOS/Funtouch/EMUI: process killed → revival layers rebuild it in minutes if allowed (§2). **GAP** on those ROMs | `Diagnostic` "capture resumed for session … after the process was killed" | Nothing (already handled) | Lock the app in recents on §2 ROMs |
| All | all | **Clear storage / Clear data**: sessions, config, filter state gone. **KILL** (nothing to resume) | `Error(NOT_READY)` until `ready()`; `STORAGE_RESET` if the DB was recreated | Re-run `ready()` and `configure()` on every launch | — |
| All | all | **Uninstall / reinstall**: same as clear data plus permissions reset. | as above | as above | — |
| All | all | **App update**: process killed by the installer. `MY_PACKAGE_REPLACED` → `BootReceiver` resumes the open session (`startOnBoot = true`). **GAP** seconds | `Diagnostic` capture resumed | Keep `startOnBoot` on | — |
| All | all | **Reboot**: `BOOT_COMPLETED` → resume. On §2 ROMs the receiver is muted when Autostart is OFF (EC-126). Until the user unlocks the device (file-based encryption), the broadcast waits. **GAP** until unlock / **KILL** on ROMs without autostart | `Diagnostic` capture resumed, or nothing | `startOnBoot` on; setup screen for autostart | Autostart ON |
| All | all | **Location permission revoked mid-session** (Settings, or "Remove permission if unused"). Stream torn down, session kept open, resumes automatically when re-granted (EC-07). **GAP** | `CaptureSuspended(PERMISSION_DENIED)`; `Error(PERMISSION_DENIED)`; `CaptureResumed` | Watch `providerState()`, show a fix-it banner | Re-grant |
| All | all | **Location services master switch OFF** mid-session. Same suspend/resume path (EC-06). **GAP** | `CaptureSuspended(LOCATION_DISABLED)`; `ProviderState.locationServicesEnabled = false` | Show a banner; deep-link `Settings.ACTION_LOCATION_SOURCE_SETTINGS` | Location ON |
| All | 9–11 | **Location mode "Battery saving"** (network only, no GPS). Fixes 50–2000 m; most rejected by the accuracy filter. **DEGRADE→GAP** | `ProviderState.gpsEnabled = false`; `LocationRejected` streak | Banner on `gpsEnabled == false` | Location mode → *High accuracy* |
| All | all | **Battery Saver ON**: location throttled or, on some builds, disabled when the screen is off; jobs deferred. **DEGRADE** | `PowerSaveChange(true)`; watchdog widens thresholds ×2 (EC-21) | Banner | Battery Saver OFF or app exempted |
| All | all | **Airplane mode**: GNSS still works, but no A-GPS download and no network provider → cold-start fix can take minutes; uploads pause. **DEGRADE** | `Error(FIX_TIMEOUT)` early in session; sync `Retry` | Nothing; expected | — |
| All | all | **Wi-Fi scanning / Bluetooth scanning OFF**, **Google Location Accuracy OFF**: fused provider indoors degrades to GNSS-only → few or no fixes indoors, in car parks, urban canyons. **DEGRADE** | `LocationRejected` (accuracy); `FIX_TIMEOUT` | Nothing; document | Location → *Location services* → Wi-Fi scanning ON, Google Location Accuracy ON |
| All | 10+ | **Multiple users / Guest**: apps of a background user are stopped. Switching user kills tracking. **KILL** while switched | none | Document | Stay on the owner user |
| All | 7+ | **Work profile OFF / paused** (managed devices): every work app stops. **KILL** while paused | none | Document; MDM policy | Work profile ON |
| All | 10+ | **MDM / DPC restrictions**: `DISALLOW_SHARE_LOCATION`, `setLocationEnabled(false)`, `DISALLOW_CONFIG_LOCATION`, per-app permission grant state `DENIED`. **KILL** | `Error(PERMISSION_DENIED)` / `Error(LOCATION_DISABLED)` | Coordinate with the MDM admin; the SDK cannot override policy | Admin lifts the restriction |
| All | all | **Cloned app** (Xiaomi Dual Apps, Samsung Dual Messenger, Huawei App Twin, OPPO Clone Apps, Vivo App Clone). Runs under a secondary user; background location and FGS start are restricted for that user. **Not supported.** | none or `FGS_START_REFUSED` | Refuse `ready()` when `Process.myUserHandle()` is not the system user, with a clear message | Use the primary install |
| All | all | Host sets **`stopOnTerminate = true`** or **`foregroundService = false`**: swipe-away ends tracking / no FGS at all. **KILL** by configuration | `Diagnostic` "foreground service REFUSED at start()" (misleading when `foregroundService=false` — see §6) | Leave both at their defaults in production | — |
| All | all | Host calls `Tracker.start()` from a scope that dies (ViewModel cleared on swipe). **Handled**: the transition runs `NonCancellable`. Listed for older SDK versions (< 1.0.10-alpha04) where it was a **KILL** on MIUI/Funtouch. | — | Upgrade | — |

---

## 4. Google Play Services and providers

| Device / ROM | Android | Scenario → why tracking breaks | SDK signal | Fix — host app | Fix — user / device |
|---|---|---|---|---|---|
| **Huawei 2019+ (Mate 30 →), Honor (2020–2021 builds), Amazon Fire, AOSP/custom ROMs without GMS** | all | No Play Services → fused provider, activity recognition and geofencing all absent. Default `providerType = FUSED` refuses to start (EC-19). **KILL** at start | `TrackerResult.Error(PLAY_SERVICES_UNAVAILABLE)` with the remedy in the message | `geolocation.providerType = GPS_ONLY` (outdoor) or `NETWORK_ONLY`; disable `motion.activityRecognition`; stationary wake relies on significant-motion sensor only (§5) | — |
| All with GMS | all | **Play Services outdated / disabled / updating**: `LocationServices` API unavailable or returns errors transiently. **GAP** at start | `Error(PLAY_SERVICES_UNAVAILABLE)`; geofence `Diagnostic("geofence_unavailable: …")` | Check `GoogleApiAvailability` before `start()`; fall back to `GPS_ONLY` | Update Play Services; re-enable it |
| All with GMS | all | **Geofence limit**: > 100 registered geofences → registration fails → stationary wake lost (EC-58). **GAP** on resume from parked | `Diagnostic("geofence_unavailable")` | Keep host-registered geofences well under 100 | — |
| All with GMS | all | Activity-recognition transitions require `ACTIVITY_RECOGNITION` **and** GMS; a wake from AR is also an FGS-start exemption on 12+. Without it, revival relies on alarms/jobs. **GAP** | `Diagnostic("activity_recognition_unavailable")` | Keep `motion.activityRecognition = true` and request the permission | Grant Physical activity |
| **GrapheneOS** (sandboxed Play), **/e/OS, CalyxOS** (microG) | 12+ | Sandboxed or reimplemented GMS: fused location works via the OS provider or microG, but geofencing and AR may be missing or partial. GrapheneOS also has per-app **Sensors** and **Network** permission toggles — sensors denied = no motion stages. **DEGRADE** | `MOTION_DETECTION_DEGRADED`; `activity_recognition_unavailable`; `geofence_unavailable` | Test `GPS_ONLY` on these ROMs; treat as GMS-less | Grant Sensors permission |
| **Emulator** | all | No GNSS; fixes come from the emulator's mock provider → `mockLocation = BLOCK` rejects them all (release) → zero points. **KILL** in release builds | `Error(DEVICE_INTEGRITY_BLOCKED)` / `LocationRejected(mock)` | Debug builds are waived (`IntegrityEnvironment.isWaived`); test releases on hardware | — |

---

## 5. Hardware and environment

| Device / ROM | Android | Scenario → why tracking breaks | SDK signal | Fix — host app | Fix — user / device |
|---|---|---|---|---|---|
| Wi-Fi-only tablets, some Chromebooks, Android TV | all | **No GNSS receiver**. Network provider only → 20–2000 m fixes. **DEGRADE** (mostly rejected) | `ProviderState.gpsEnabled = false` permanently; `LocationRejected` | `providerType = NETWORK_ONLY` + relaxed `AccuracyConfig`, or refuse on `!hasSystemFeature(FEATURE_LOCATION_GPS)` | — |
| Budget devices (many Tecno/Infinix/itel, Android Go, older Samsung A0x/M0x) | all | **Weak GNSS front-end**: cold TTFF 1–5 min, accuracy 20–50 m in motion. Strict `AccuracyConfig` rejects most fixes. **DEGRADE** | `Error(FIX_TIMEOUT)`; long `LocationRejected` runs with `accuracy` reason | Widen `accuracy` thresholds per fleet; `waitForAccurateLocation = false` for faster first point | Keep the phone out of metal enclosures |
| Devices without **gyroscope** (most budget phones) | all | Turn detection falls back to GNSS bearing only; corner points coarser. **DEGRADE** (cosmetic) | `Diagnostic` motion probe: `gyroscope=false`; `useGyroTurnPrediction` silently off | Nothing | — |
| Devices without **significant-motion** trigger and with GMS absent | all | After the motion machine declares `STATIONARY`, wake paths are geofence (needs GMS) and significant motion (needs the sensor). With neither, the next drive is noticed only by the 15-min backstop/heartbeat. **GAP** at the start of every trip | `Diagnostic` probe: `significantMotion=false`; `MOTION_DETECTION_DEGRADED` | `trackingMode = CONTINUOUS` on such devices (`ResolveConfigUseCase` already forces it when `motionQuality = POOR`) | — |
| Devices without **accelerometer** (rare, some TV/IoT) | all | `MotionQuality.POOR` → mode forced to `CONTINUOUS`; `suppressWhileStationary` disabled. **DEGRADE** (battery, not gaps) | `Error(MOTION_DETECTION_DEGRADED)` | Accept the override or exclude the device class | — |
| Devices without **step detector** | all | Step corroboration off; stationary-drift filter has one fewer witness. **DEGRADE** (a few more drift points while parked) | probe `stepDetector=false` | Nothing | — |
| All | all | **Indoors, underground, dense urban canyon, metallised windscreens, phone in a metal case** | `FIX_TIMEOUT`; `LocationRejected` | Expected; document for dispatch | Mount the phone with sky view |
| All | all | **Thermal throttling / very low battery** (< 5–15 % on many OEMs): OEM battery saver auto-enables, GNSS duty-cycled. **DEGRADE→GAP** | `PowerSaveChange`; `BatteryChange` | Warn below 15 % using `BatteryChange` | Charge |
| All | all | **Storage full**: Room insert fails → points dropped until space frees. **KILL** (silent for points) | `Error(STORAGE_FULL)` | Prune (`PruneWorker` runs; tune `PersistenceConfig`); sync more often | Free space |
| All | all | **Database corruption / downgrade**: store recreated, history lost. **KILL** for history, session resumes fresh | `Error(STORAGE_RESET)` | Sync frequently so the server has the data | — |

---

## 6. SDK gates that stop tracking on every device (by design)

| Device / ROM | Android | Scenario → why tracking breaks | SDK signal | Fix — host app | Fix — user / device |
|---|---|---|---|---|---|
| All | all | **License** missing / invalid / expired / revoked / wrong package / SDK mismatch. `ready()` or `start()` refuses. **KILL** | `Error(LICENSE_*)`; `LicenseChecked` | Valid token in the host manifest; package name matches the license; network reachable for the periodic revocation check (fails open when offline) | — |
| All | all | **Invalid config** (validation rules, INTEGRATION-GUIDE §5.8). **KILL** at `ready()` | `TrackerResult.Error(INVALID_CONFIG)` with the list | Fix the values | — |
| All | all | `start()` before `ready()`. | `Error(NOT_READY)` | Call order | — |
| All | all | **Mock-location app** installed and selected (Fake GPS etc.) with `security.mockLocation = BLOCK` (default): every fix rejected; mid-session recheck stops tracking. **KILL** | `Error(DEVICE_INTEGRITY_BLOCKED)`; `IntegrityChange` | Keep BLOCK for payroll-grade data; `WARN` for consumer apps | Developer options → *Select mock location app* → none |
| All | all | **Rooted / hooking frameworks** (Magisk + Zygisk modules, Frida, Xposed/LSPosed) with `hooking = BLOCK` (default). **KILL** | `Error(DEVICE_INTEGRITY_BLOCKED)` | Policy decision; `WARN` if your fleet has rooted devices legitimately | Unroot |
| All | all | **Developer options ON**, **clock skew** > `maxClockSkewMs`, **accessibility automation services** — `WARN` by default: tracking continues, flagged in `IntegrityChange`. Only a **KILL** if the host raises the policy to `BLOCK`. | `IntegrityChange`; `DEVICE_LOCATION.integrity` | Leave at WARN unless required | Fix the clock (automatic date & time) |
| All | all | **Upload 401** with the sync module: by design the SDK **stops tracking and clears the upload queue** ("Auth expired"). A token that expires mid-shift ends the session on every phone at once. **KILL** | sync WARN "Auth expired — stopping tracking and clearing the upload queue"; `SyncEvent` | Refresh credentials before expiry; call `TrackerSync.configure()` with new headers on token rotation; make sure the backend never returns 401 for a still-valid session | — |
| All | all | **Upload 403**: uploads halt, rows kept, tracking continues. Not a tracking break, listed to distinguish from 401. | sync WARN "403 … rows stay queued" | Re-configure with a valid credential | — |
| All | all | **No notification icon** configured / unresolvable `notificationSmallIconResName`: validated in `ready()`; at runtime falls back to a platform icon (EC-77). Not a break since 1.0.10. | WARN "notificationSmallIconResName … not found" | Ship a proper icon | — |
| All | 8+ | **Notification channel deleted or blocked by the user**: on some OEMs an FGS with no visible notification is killed (EC-76). SDK recreates the channel on every start; a *blocked* channel stays blocked. **DEGRADE→KILL** on those OEMs | `Error(NOTIFICATION_HIDDEN)` | Deep-link `Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS` for `notificationChannelId` | Unblock the channel |

---

## 7. Known SDK-side issues (this repository, 1.0.10-alpha02 → alpha06)

Found in the 10–15 Sep 2026 review. Device-independent unless stated.

| Version | Scenario → why tracking breaks | SDK signal | Status / fix |
|---|---|---|---|
| alpha04 → alpha06 (`master` before the lifecycle-lock change) | `start()` issues the FGS start **before** writing the session row and launching the pipeline; the service's `ResumeCaptureUseCase` runs concurrently on `Dispatchers.Default`, sees `FixIngestor.isRunning == false`, and launches a **second pipeline**: two location registrations, two ingest consumers sharing filter state → duplicate fixes, `OUT_OF_ORDER`/`Burst` rejections, corrupted points on the first session after a cold start. | `Diagnostic` "capture resumed for session … after the process was killed" **immediately after a normal `start()`** is the tell | **Fixed** on `master` (uncommitted at time of writing): `CaptureLifecycleLock` serialises start/stop/resume; resume backs off via `tryLock` while a transition is in flight |
| alpha05 (release AAR) | R8 renamed `WorkManagerAccess` in core while `fieldtrack-sync` linked by name → `NoClassDefFoundError` on the first point-sync enqueue in release, or JitPack build failure. Points captured, never uploaded. | crash log `Missing class com.field360.tracker.work.WorkManagerAccess` | **Fixed** in alpha06 (`proguard-rules.pro` keep rule). Do not ship alpha05 |
| alpha05+ | Supervision moved off the main thread; `teardown()` can race the synchronous stretch of `startSupervision` → stale partial wake lock held until `wakeLockMs`, ghost "Tracking active" notification after stop. Cosmetic / battery, not a tracking break. | none | Open. Serialise `teardown()` against the supervision job (join or a state flag) |
| alpha05+ | After a refused promotion, `startRequestedAtMs` is never cleared; the next user `stop()` takes the `ACTION_STOP`-via-`startService` route and spins up a throwaway service instance that flashes the notification. Harmless. | none | Open. Clear the stamp in the `promoteToForeground` catch path |
| alpha02+ | `LogRecorder` write failure logs a WARN → relay → new draft → write fails again: unbounded churn while the diagnostics DB is persistently failing (disk full / corrupt). Battery, not tracking. | WARN "Log write failed" repeating | Open. Mute the recorder's own tag in `SdkLogRelay.MUTED_TAGS` or back off after N failures |
| alpha04+ | `Diagnostic` "foreground service REFUSED at start(); … check Autostart/battery settings" also fires when the host set `foregroundService = false`. Misleading, not a break. | as quoted | Open. Branch the message on `config.service.foregroundService` |

---

## 8. Triage order for "tracking stopped on one phone"

1. **Pull the session-start `DEVICE_LOCATION` log entry.** `permission`, `providers` explain a
   session that recorded nothing; `battery_optimised`, `background_restricted`,
   `standby_bucket` explain one that started fine and stopped later.
2. **Manufacturer** (`Build.MANUFACTURER`) → §2 row → confirm Autostart / battery setting.
3. **Last events before the gap**: `FGS_START_REFUSED` → §1 12+/14+ rows; `TRACKER_DEAD` with the
   service alive → §5 (environment / hardware); `CaptureSuspended` → §3 (permission / location
   switch); `SessionInterrupted` on next launch with nothing before it → force stop or OEM kill
   with revival blocked.
4. **Play Services present?** → §4.
5. **Integrity / license events** → §6.
6. **Version** → §7.

---

## 9. What the host should ship

Minimum viable "keep tracking alive" UX, derived from the rows above:

| Step | API |
|---|---|
| Full permission ladder incl. notifications, physical activity, "Allow all the time" | `PermissionManager.backgroundRequest()`, `foregroundPermissions()`, `notificationPermissions()`, `activityRecognitionPermissions()`, `appSettingsIntent()` |
| Battery/background status on the settings screen, re-read in `onResume` | `PermissionManager.backgroundRestrictions()` → `degraded`, `backgroundRestricted`, `standbyBucket`, `ignoringBatteryOptimizations` |
| Two-tap exemption always; one-tap when your listing qualifies | `batteryOptimizationSettingsIntent()`; `batteryExemptionRequestIntent()` (null unless **you** declared `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) |
| Exact heartbeat where policy allows | Declare `SCHEDULE_EXACT_ALARM` in **your** manifest; `ServiceHeartbeat` upgrades at runtime |
| OEM setup screen keyed on `Build.MANUFACTURER` with the §2 menu paths | — |
| Banner on `PowerSaveChange`, `CaptureSuspended`, `Error(TRACKER_DEAD)`, `Error(FGS_START_REFUSED)`, `SessionInterrupted` | `Tracker.events` |
| Lean `Application.onCreate`; WorkManager initializer removed | INTEGRATION-GUIDE §1.7 |
| Diagnostics shipped by default so the row can be identified after the fact | `SyncConfig.syncLogs = true` (default) |
