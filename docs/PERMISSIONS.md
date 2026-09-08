# Permissions & Background Execution

The single hardest part of an Android background-location SDK is not the filter — it is staying alive and staying permitted. This document is the complete handling model. Edge case IDs reference [EDGE-CASES.md](EDGE-CASES.md).

---

## 1. What the SDK needs, and what happens without it

| Permission | Since | Required for | Without it |
|---|---|---|---|
| `ACCESS_COARSE_LOCATION` | — | Any location | Nothing works |
| `ACCESS_FINE_LOCATION` | — | Usable accuracy | Fixes are 1–3 km; SDK refuses `CONTINUOUS`/`ADAPTIVE` (EC-02) |
| `ACCESS_BACKGROUND_LOCATION` | API 29 | Tracking while the app is not visible | Degrades to `FOREGROUND_ONLY` (EC-03) — **not** a hard failure |
| `POST_NOTIFICATIONS` | API 33 | A *visible* foreground-service notification | FGS still runs, but the user cannot see that tracking is on (EC-08) |
| `ACTIVITY_RECOGNITION` | API 29 | Motion detection via AR | Falls back to speed + displacement only (EC-09) |
| `FOREGROUND_SERVICE` | API 28 | Any FGS | Service cannot start |
| `FOREGROUND_SERVICE_LOCATION` | API 34 | A `location`-typed FGS | `SecurityException` on `startForeground` |
| `RECEIVE_BOOT_COMPLETED` | — | Resume after reboot | Silent stop at reboot (EC-65) |
| `WAKE_LOCK` | — | 20 s soft-wake per delivered fix on aggressive OEMs | Longer gaps on MIUI/ColorOS |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | — | Optional exemption | Never auto-requested; Play-policy sensitive (EC-15) |

**Design decision.** The reference implementation treats `ACCESS_BACKGROUND_LOCATION` as a hard gate — `AttendanceLoggerService.onCreate` stops the service outright without it (`AttendanceLoggerService.kt:119-122, 894-903`). For an attendance product that is defensible. For a general SDK it is wrong: a user who chose "While using the app" gets *nothing*, not even foreground tracking. Tracker degrades instead ([A16](SOURCE-AUDIT.md)).

---

## 2. Permission tiers and degradation

```kotlin
enum class PermissionTier { NONE, FOREGROUND_ONLY, FULL }
enum class Accuracy { APPROXIMATE, PRECISE }

enum class PermissionLevel { FOREGROUND, BACKGROUND, ACTIVITY_RECOGNITION, NOTIFICATIONS }

sealed interface PermissionResult {
    data object Granted : PermissionResult
    data class Denied(val permanent: Boolean) : PermissionResult
    /** API 30+: background can no longer be prompted — only granted in Settings (EC-05). */
    data class NeedsSettings(val intent: Intent, val explain: String) : PermissionResult
    data object NotApplicable : PermissionResult          // e.g. NOTIFICATIONS below API 33
}
```

| Tier | `start()` behaviour |
|---|---|
| `NONE` | `TrackerResult.Error(PERMISSION_DENIED)`. No service, no crash (EC-01). |
| `FOREGROUND_ONLY` | Session opens. Stream runs while the app is visible; pauses on background with `Error(BACKGROUND_PERMISSION_MISSING)`; resumes automatically on return to foreground **and** on a later background grant (EC-03, EC-07). |
| `FULL` | Normal operation. |

`APPROXIMATE` accuracy is orthogonal and always surfaced in `ProviderState`. In `CONTINUOUS`/`ADAPTIVE` the SDK refuses to start on approximate-only unless the host passes `allowApproximate = true`, because a 1–3 km error circle defeats every gate in the pipeline (EC-02, EC-12).

---

## 3. The request ladder

Order matters. Each step is a separate user interaction; bundling them is what makes Android silently deny.

```
1. POST_NOTIFICATIONS      (API 33+)   ─ ask FIRST; an invisible FGS notification is a
                                          transparency failure and an OEM-kill risk
2. ACCESS_FINE + COARSE                ─ single request, both in one array
        │  granted
        ▼
3. rationale screen                    ─ MANDATORY before step 4. Explain, in the host's
                                          own words, why "Allow all the time" is needed
        │  user continues
        ▼
4. ACCESS_BACKGROUND_LOCATION (API 29+)
        ├─ API 29        → runtime prompt works
        └─ API 30+       → CANNOT be prompted. Deep-link to Settings, return
                            NeedsSettings(intent, explain)
5. ACTIVITY_RECOGNITION    (API 29+)   ─ optional; denial is not fatal
6. battery-optimisation exemption      ─ host-invoked only, never automatic
```

```kotlin
class PermissionManager(private val ctx: Context) {

    suspend fun request(activity: Activity, level: PermissionLevel): PermissionResult =
        when (level) {
            NOTIFICATIONS -> if (SDK_INT < 33) NotApplicable
                             else requestRuntime(activity, arrayOf(POST_NOTIFICATIONS))
            FOREGROUND    -> requestRuntime(activity,
                                arrayOf(ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION))
            BACKGROUND    -> requestBackground(activity)
            ACTIVITY_RECOGNITION -> if (SDK_INT < 29) NotApplicable
                             else requestRuntime(activity, arrayOf(ACTIVITY_RECOGNITION))
        }

    private suspend fun requestBackground(activity: Activity): PermissionResult {
        if (SDK_INT < 29) return Granted
        if (has(ACCESS_BACKGROUND_LOCATION)) return Granted
        // EC-04: background is only grantable AFTER fine. Asking together = silent denial.
        if (!has(ACCESS_FINE_LOCATION)) return Denied(permanent = false)
        return if (SDK_INT >= 30) {
            // EC-05: from Android 11 the OS will not show a prompt for background at all.
            NeedsSettings(
                intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", ctx.packageName, null)),
                explain = ctx.getString(R.string.traker_background_settings_explain),
            )
        } else {
            requestRuntime(activity, arrayOf(ACCESS_BACKGROUND_LOCATION))
        }
    }

    /** EC-14: hard cap so a "Don't ask again" user is never prompt-looped. */
    private var attempts = 0
    private suspend fun requestRuntime(a: Activity, perms: Array<String>): PermissionResult {
        if (perms.all { has(it) }) return Granted
        if (attempts++ >= MAX_ATTEMPTS) return NeedsSettings(appSettingsIntent(), "")
        return suspendCancellableCoroutine { … ActivityResultContracts.RequestMultiplePermissions … }
    }
}
```

The reference does the same two-stage chain (`CurrentLocationProvider.kt:401-491`) and caps retries at 3 (`:440`); Tracker keeps the cap and adds the API-30 Settings branch, which the reference does not distinguish.

---

## 4. Live revocation

A permission can be revoked at any moment, including while the FGS is running. Polling is not acceptable — the SDK must react immediately and stop cleanly, never crash.

```kotlin
internal class ProviderStateMonitor(private val ctx: Context) {

    private val appOps = ctx.getSystemService(AppOpsManager::class.java)
    private var opsListener: AppOpsManager.OnOpChangedListener? = null

    fun start() {
        // EC-06 — pattern from AttendanceLoggerService.kt:877-892
        opsListener = AppOpsManager.OnOpChangedListener { op, pkg ->
            if (pkg == ctx.packageName &&
                (op == AppOpsManager.OPSTR_FINE_LOCATION ||
                 op == AppOpsManager.OPSTR_COARSE_LOCATION)) refresh()
        }.also { appOps.startWatchingMode(AppOpsManager.OPSTR_FINE_LOCATION, ctx.packageName, it) }

        ctx.registerReceiver(providerReceiver, IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION))
        ctx.registerReceiver(powerReceiver, IntentFilter(ACTION_POWER_SAVE_MODE_CHANGED))
    }

    fun stop() { opsListener?.let(appOps::stopWatchingMode); … }
}
```

Three sources feed one `StateFlow<ProviderState>`:
`AppOpsManager` (permission changed) · `PROVIDERS_CHANGED_ACTION` (GPS toggled, EC-16) · `ACTION_POWER_SAVE_MODE_CHANGED` (EC-21).

On a downgrade the SDK stops the stream and the service, keeps the session **open**, and emits `ProviderChange` + `Error`. On an upgrade it restarts automatically. The session is never silently closed by a permission change — that is the host's decision.

---

## 5. Location settings (GPS off)

Separate from permissions: the user can hold every permission and still have Location switched off.

```kotlin
val request = LocationSettingsRequest.Builder()
    .setAlwaysShow(false)
    .addLocationRequest(LocationRequests.stream(cfg, vehicular = false))
    .build()

settingsClient.checkLocationSettings(request)
    .addOnSuccessListener { startUpdates() }
    .addOnFailureListener { e ->
        when ((e as? ApiException)?.statusCode) {
            LocationSettingsStatusCodes.RESOLUTION_REQUIRED ->
                // EC-16: hand the IntentSender to the HOST. An SDK must never launch
                // a dialog from a Service; the host owns all UI.
                events.tryEmit(ProviderChange(state.copy(
                    gpsEnabled = false,
                    resolution = (e as ResolvableApiException).resolution)))
            LocationSettingsStatusCodes.SETTINGS_CHANGE_UNAVAILABLE ->
                events.tryEmit(Error(LOCATION_DISABLED, "Cannot be fixed on this device"))
        }
    }
```

Retries are capped (EC-17) — the reference caps at 3 (`BackgroundLocationProvider.kt:272`) and 2 (`CurrentLocationProvider.kt:302`).

**The SDK shows no UI.** No dialogs, no full-screen intents, no activities. The reference fires a full-screen `CATEGORY_CALL` notification when tracking looks dead (`LocationTrackingManager.kt:452-493`); that is an application decision with real policy risk, so Tracker emits `Error(TRACKER_DEAD)` and the host decides.

---

## 6. Foreground service by API level

| API | Rule | Handling |
|---|---|---|
| 26–27 | FGS required for background work | `startForeground` in `onCreate`/`onStartCommand` |
| 28 | `FOREGROUND_SERVICE` permission required | Declared in the AAR manifest |
| 29 | `foregroundServiceType="location"`; background-location permission required for background fixes | Type declared; tier degradation (§2) |
| 31 | **Cannot start an FGS from the background** → `ForegroundServiceStartNotAllowedException` | Catch, stop cleanly, `RestoreWorker` re-promotes when eligible (EC-62) |
| 33 | `POST_NOTIFICATIONS` runtime permission | Requested first (§3) |
| 34 | Location is a foreground-only permission: an FGS of type `location` may only **start** from an eligible state, **even when granted** → `SecurityException` | Same catch block; both exception types handled together |
| 35+ | Continued tightening; `stopWithTask`, type enforcement | `stopWithTask="false"`, explicit type, no implicit starts |

```kotlin
private fun promoteToForeground(): Boolean = try {
    ServiceCompat.startForeground(this, NOTIFICATION_ID, notification,
        ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
    true
} catch (e: Exception) {
    // API 31+: ForegroundServiceStartNotAllowedException
    // API 34+: SecurityException
    // Stop to honour the start-foreground contract — otherwise the platform raises the
    // "did not call startForeground" ANR on top of the original failure. Never crash-loop.
    logger.w(TAG, "startForeground(location) refused: ${e.message}")
    events.tryEmit(Error(FGS_START_REFUSED, e.message.orEmpty()))
    stopSelf()
    false
}
```

**Every caller must honour the boolean.** The reference gets this right (`AttendanceLoggerService.kt:126, 152`) and documents why; it is the single most common way background-location SDKs crash-loop on modern Android.

---

## 7. Staying alive

Layered, because no single mechanism survives every OEM.

| Layer | Cadence | Purpose | Fails when |
|---|---|---|---|
| Foreground service | continuous | Hosts the stream; Doze-exempt while running | OEM kills the process |
| Health loop (in-service) | `healthLoopMs`, 2 min | Backstop worker alive? heartbeat still armed? session still open? → self-stop if not | Process dead |
| Watchdog (in-service) | `watchdogIntervalMs`, 60 s, actions throttled to 15 min | Service dead → `ServiceRestorer`; raw-fix clock stale → `Error(TRACKER_DEAD)` | Process dead |
| `AlarmManager` heartbeat | `serviceHeartbeatMin`, 15 min | Re-promotes the service with the process dead; **the only layer that is not a `JobScheduler` job** | Inexact alarm cannot start an FGS on API 31+ unless the host declares `SCHEDULE_EXACT_ALARM` |
| `WorkManager` backstop | 15 min periodic, linear backoff, 30 s fix timeout | Captures even if the stream died | App standby bucket `RESTRICTED` |
| `WorkManager` restore | on demand, ≤ 5 attempts, 30 s doubling backoff | Re-promotes after a refused `startForeground` | App standby bucket `RESTRICTED` |
| Stationary geofence | while `STATIONARY` | System-registered wake; survives process death | > 100 geofences, GMS error (EC-58) |
| Boot receiver | `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED` | Resume an open session, re-arm the heartbeat | OEM autostart disabled (EC-126) |
| Wake lock | `wakeLockMs`, 20 s, per delivered fix | Keeps the CPU up long enough to judge and store a fix | `PER_FIX` lets the timed loops drift between fixes; the heartbeat bounds that |
| Restriction probe | session start + on change | Reports `isBackgroundRestricted`, the standby bucket and the battery-optimisation exemption as `Diagnostic` | — |

**Note the "fails when" column repeats itself, and that is the finding.** Four of these layers are
`JobScheduler` work, and MIUI/HyperOS puts an app left at its default *Restricted* battery setting
into the `RESTRICTED` standby bucket, where none of them run (EC-22). A fifth needs the OEM's
Autostart toggle, off by default on the same ROMs (EC-126). The health loop and the watchdog both
live *inside* `TrackingService` and structurally cannot report their own absence. So on the devices
that kill hardest, one setting used to disable the entire stack at once — which is why the
`AlarmManager` heartbeat exists: it is a different subsystem with different throttling, and it
re-arms itself, so the chain has no single tick whose loss ends it.

**Restore requests are counted, not looped.** `promoteToForeground` answering a refusal by enqueuing
`RestoreWorker`, whose only action is to start the service again, is a loop on any app that is not
eligible to start an FGS from the background — the normal state after an OEM kill on API 31+. All
requests go through `ServiceRestorer`: first attempt expedited and immediate, then ordinary work at
30 s doubling to 15 min, then stand down in favour of the heartbeat and the backstop rather than
spend the expedited quota both of those still need. Every heartbeat tick resets the budget.

**Liveness is judged on the raw fix clock**, updated *before* the filter on every fix — never on upload or accept recency, because a healthy stationary user accepts and uploads nothing by design (EC-70). This is the subtlety that makes naive watchdogs fire constantly on parked users; the reference documents it at `LocationTrackingManager.kt:378-383`.

```kotlin
internal class Watchdog(private val cfg: ServiceConfig) {
    @Volatile private var lastRawFixElapsedNanos = 0L      // written pre-filter
    private var lastActionElapsedNanos = 0L

    fun onRawFix(fix: TrackFix) { lastRawFixElapsedNanos = fix.elapsedRealtimeNanos }

    suspend fun tick(now: Long, moving: Boolean, powerSave: Boolean) {
        if (!TrackingService.isRunning && SessionManager.hasOpenSession()) {
            throttled(now) { RestoreWorker.enqueueExpedited(ctx) }
            return
        }
        if (lastRawFixElapsedNanos == 0L) return            // no fix yet this session
        val staleMin = (now - lastRawFixElapsedNanos) / 60_000_000_000L
        // EC-21: power save legitimately throttles fixes — widen before declaring death.
        val threshold = (if (moving) cfg.deadTrackerMovingMin else cfg.deadTrackerStationaryMin)
            .let { if (powerSave) it * 2 else it }
        if (staleMin > threshold) throttled(now) { events.emit(Error(TRACKER_DEAD, "…")) }
    }
}
```

---

## 8. Host integration checklist

What an integrating app must do — everything else is inside the AAR.

1. `Tracker.init(application)` in `Application.onCreate()`.
2. Provide a `NotificationConfig` (title, text, small icon, channel) — validated in `ready()` so a missing icon fails there, not inside `startForeground` (EC-77).
3. Drive the permission ladder from an `Activity` (§3), or call `Tracker.requestPermission()` step by step and render your own rationale.
4. Observe `Tracker.providerState()` and surface `NeedsSettings` / `Error(TRACKER_DEAD)` in your UI.
5. Optionally offer the battery-optimisation exemption, and document the OEM autostart setting for MIUI/ColorOS/One UI users (EC-126, EC-127).
   On MIUI/HyperOS (Redmi, POCO, Xiaomi) the two that actually decide whether background tracking
   survives are **Autostart: on** and **Battery saver: No restrictions** — with either left at its
   default, `JobScheduler` does not run the SDK's recovery work at all and no amount of SDK-side
   hardening substitutes for them.
   Optionally declare `SCHEDULE_EXACT_ALARM` **in your own manifest** if your app's Play listing can
   justify it: `ServiceHeartbeat` detects the grant at runtime and upgrades to an exact alarm, which
   on API 31+ is also what makes the heartbeat eligible to restart the foreground service. The SDK
   never declares that permission for you (EC-15).
6. Declare nothing in your manifest — permissions, service and receivers merge from the AAR.

---

## 9. Device integrity needs no permission

The device-integrity layer ([INTEGRATION-GUIDE.md §19](INTEGRATION-GUIDE.md#19-device-integrity))
adds **no** entry to the merged manifest. Everything it reads is already available to any
app about itself:

| Check | Read from | Permission |
|---|---|---|
| Accessibility services | `AccessibilityManager`, `Settings.Secure` | none |
| Developer options, USB debugging | `Settings.Global` | none |
| Frida / Xposed / debugger | `/proc/self/*`, a loopback connect, `Debug` | none |
| Clock and time zone | `Settings.Global`, `TelephonyManager.networkCountryIso`, GNSS fix time | none |
| Mock-location app | `AppOpsManager` over visible packages | none |

`QUERY_ALL_PACKAGES` is deliberately **not** requested. It would widen mock-app detection
on Android 11+, and it would also force a Play policy declaration on every host of this
SDK, for a signal that `MOCK_LOCATION_FIX` already covers the moment a fake fix is
delivered. A host that needs the wider view can add its own `<queries>` entries.
