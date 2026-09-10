# Release notes — for apps integrating FieldTrack

What changes for **your app** when you move up. Written for the host side: what you must
do, what you can now opt into, and what changes under you whether you touch code or not.

The engineering detail — why each fix was made, which gate moved, which field is stamped
where — is in [`CHANGELOG.md`](../CHANGELOG.md). This file is the integrator's view.

| | |
|---|---|
| Companion docs | [`INTEGRATION-GUIDE.md`](INTEGRATION-GUIDE.md) · [`SYNC-MODULE.md`](SYNC-MODULE.md) · [`SYNC-BACKEND-AND-DASHBOARD.md`](SYNC-BACKEND-AND-DASHBOARD.md) |
| Artifacts | `com.github.fieldtrack360.fieldtrack:<module>:<tag>` |

---

## Unreleased — the next tag

**Applies to every currently released tag**, `1.0.7-alpha2` through `1.0.8-alpha01`. All of
them ship database schema **v8** and the same points wire format, so the migration and
backend facts below hold whichever one you are on.

If you are on one of the later alphas (`1.0.7-alpha3` … `1.0.8-alpha01`) some entries here
are already in your build — they were tagged without being moved out of the changelog's
Unreleased section. Reading them twice costs nothing; none of them needs an action.

### The short version

**Nothing in your code has to change.** There is no breaking API change, no manifest
change, no new permission, and no change to the JSON your backend already receives for
location points.

**One thing on your *server* may have to.** Session logs (§2) are now derived from your
points config rather than waiting for a second call, so a device that uploads points will
also POST to `<same origin>/v1/logs/batch`. Implement that route, or set
`SyncConfig.syncLogs = false` and nothing is sent.

**And the SDK now explains itself without being asked.** Once a log channel exists, the
SDK's own internal logging — the licence verdict, a provider that went quiet, a worker that
gave up — is recorded into the same buffer, with no `log()` call from your app, and it works
in release builds where `adb logcat` is compiled out. At the default `level = INFO` you get
its warnings; `.level(LogLevel.DEBUG)` gets everything, at several rows per fix.

Two things to *check* rather than change, then five things you may want to opt into — one
of which, session logs, is now on by default and needs a backend route (§2).

### 1. Check these two before you ship

**If you are a React Native host, or your build pins OkHttp versions.**
`fieldtrack-core` links OkHttp 5 for its licence check, and OkHttp 5 deleted an internal
class that `okhttp-urlconnection` 4.x still calls. A host holding a 4.x
`okhttp-urlconnection` crashed on its first cookie-bearing request, with a stack naming no
FieldTrack code:

```
java.lang.NoClassDefFoundError: Failed resolution of: Lokhttp3/internal/Util;
    at okhttp3.JavaNetCookieJar.decodeHeaderAsJavaNetCookies(JavaNetCookieJar.kt:81)
```

The SDK now publishes a dependency **constraint** that pulls `okhttp-urlconnection` up to
match. That is enough for most builds. It is not enough if your own Gradle setup forces
OkHttp versions — React Native's plugin can — in which case align the two artifacts on
your side:

```bash
./gradlew :app:dependencies --configuration releaseRuntimeClasspath | grep okhttp
```

Every `okhttp*` artifact must be on the same version. Moving the whole family past ours is
fine; splitting it is not.

**If you tracked this branch before it was tagged** and set
`ServiceConfig.syncNotificationTitle`, it is now `syncNotificationSubText`, and the builder
call is `syncNotification(subText, text)`. This is not a breaking change against any
*released* version — the property never shipped. A persisted config written by an interim
build still decodes; the field simply reverts to its default.

### 2. New — session logs, on their own endpoint

The one addition that needs backend work, and the one worth doing.

Location points answer *where the device was*. They cannot answer *why there is nothing
there* — the permission revoked at 14:31, the provider switched off, the session an OEM
killed. A track with a twenty-minute hole in it is unreadable on its own; the same hole
next to `CaptureSuspended(LOCATION_DISABLED)` and a `CaptureResumed` twenty minutes later
is a closed support ticket.

**On by default from this release, if you configure uploads at all.** It lives entirely in
`fieldtrack-sync`, and `configure()` derives it from the points endpoint you already set up
— so there is no call to add, and **your backend needs the route ready before you ship
this**:

```kotlin
// The points channel, as you already have it.
sync.configure(
    SyncConfig.builder()
        .url("https://api.example.com/v1/location/batch")
        .header("Authorization", "Bearer $token")
        .extraParam("device_id", installId)
        .build(),
)

// The log channel needs no second call: configure() derives it — same host, same
// credential, /v1/logs/batch, device_id inherited. `.syncLogs(false)` opts out.
```

Override anything with a `LogSyncConfig` — and giving this endpoint its **own** credential
is worth the extra line, for the reason in the first bullet below:

```kotlin
sync.configureLogs(
    LogSyncConfig.builder()
        .header("Authorization", "Bearer $logToken")
        .level(LogLevel.INFO)        // DEBUG only while a ticket is open
        .bufferCapacity(5_000)       // oldest evicted first; the gap is reported
        .retentionHours(72)          // applies to entries already shipped
        .build(),
)
```

Your own lines go in through the same buffer, and come back out for a debug screen or a
bug report:

```kotlin
sync.log(LogLevel.WARN, tag = "Dispatch", message = "Job 8812 refused by driver")
sync.logLifecycle(LifecyclePhase.CONFIG_CHANGED, tag = "Host")

val entries: List<LogRecord> = sync.getLogs(sessionId = session.id, limit = 500)
```

**What you need on the server**: one `POST /v1/logs/batch` route. The implemented
contract — field rules, status codes, auth modes, and a "why is it not saving" table — is
[`APP-LOG-API.md`](APP-LOG-API.md); the design behind it is
[§11 of `SYNC-BACKEND-AND-DASHBOARD.md`](SYNC-BACKEND-AND-DASHBOARD.md#11-session-logs).

Four things to know before you enable it:

- **Use a different endpoint and, ideally, a different credential from your points
  endpoint.** A 401 on the *points* URL is destructive by design: it stops tracking and
  clears the unsent point queue. A 401 on the *log* URL stops log shipping and does
  nothing else — it cannot reach `Tracker.stop()`, the point queue, or one row of stored
  locations. Keeping the two credentials apart is what keeps that guarantee useful.
- **The buffer is bounded and drops its oldest entries** (5 000 by default). That is
  correct for diagnostics and would be a data-loss bug for positions, which is exactly why
  the two channels do not share a queue. The loss is reported to your server as a gap in a
  sequence number rather than hidden.
- **Uploads are a 15-minute background heartbeat, except for the entries you are waiting
  on.** An entry at `WARN` or above — a GPS toggle, a permission revocation, a capture
  suspension — asks for a drain immediately, throttled to one per 30 s and with a burst
  deferred to the end of that window rather than dropped. Ordinary `INFO` chatter still
  rides the heartbeat, because a radio wake per log line would undo the battery work
  elsewhere in the SDK. Tune with `.nudgeLevel(...)` / `.nudgeCooldownMs(...)`, or pass
  `null` to make everything wait. `sync.requestLogSync()` asks by hand;
  `sync.syncLogsNow()` runs one inline.
- **`LogType.DECISION` is roughly 29 000 entries per device per 8-hour shift** — the same
  order as your points table, and wider rows. Enable it (`.level(LogLevel.DEBUG)` plus
  `.shipDecisions(true)`) for a named device with a ticket open, not for a fleet. Decisions
  are never copied into the buffer: they are read from the SDK's existing decision log and
  converted at send time, and the opt-in ships the *next* drive rather than the last three
  days of history.

**Redaction is yours.** Whatever you pass to `log()` is stored on disk and uploaded. A log
line is the easiest place in any system to leak a token.

### 3. New — things you can opt into, no backend work

**Upload-status on the notification** — puts the live queue depth on the ongoing foreground
notification, readable with your app's UI dead. A field-debugging tool; meant to stay off
in a shipping build.

```kotlin
ServiceConfig(
    showSyncStatusInNotification = true,
    syncNotificationSubText = "Sync",
    syncNotificationText = "unsynced {pending} · last upload {age}",
)
```

Your notification title is never replaced — title, subtitle and description are three
separate slots, and the status line uses the bottom two.

**Accelerometer veto on stationary drift** — a phone lying on a desk in a Wi-Fi-dense
building can produce points from position data alone. This measures whether the device
physically moved, which indoor multipath cannot fabricate.

```kotlin
MotionConfig(suppressWhileStationary = true)   // off unless you ask
```

It can only *remove* a point the pipeline had already classified as stationary — it never
triggers a capture, is not consulted once speed or displacement read as moving, and is
withdrawn by a single counted step. On a device with no accelerometer it disables itself
and emits a `Diagnostic`. Rejections appear in the decision log as `Reasons.STILLNESS_VETO`.

**`SyncEvent.NetworkAvailable(queued)`** — fires when the device returns to a usable
network *with* rows queued, so an upload badge can go from "offline · 240 queued" to
"syncing" without polling `pendingCount()` on a timer. Silent on a reconnection with an
empty queue.

**`TrackerState.motionQuality` and `TrackerState.effectiveTrackingMode`** — tells
"`MOTION_ONLY` was requested" apart from "`MOTION_ONLY` was downgraded to `CONTINUOUS`
because this device's motion hardware cannot be trusted". If you show the tracking mode in
a UI, show the effective one.

**Road-snap detour bounds** — `TrackOptions.snapMaxDetourFactor` (2.5) and
`snapBridgeFlatM` (200 m), if you use `buildTrack` with a snap provider. Appended to the
end of the constructor, so positional and `@JvmOverloads` call sites are unaffected.
`Double.POSITIVE_INFINITY` restores the old behaviour exactly.

### 4. What changes without you touching anything

These land on the first launch after the upgrade. Most are accuracy fixes you asked for
without filing a ticket.

**Tracks stop leaving the route.** Two field reports are closed by four independent fixes:

- A point plotted on a street the device never entered, mid-drive and again at the end
  (reported from Delhi). Wi-Fi centroids from the fused provider were not being recognised
  as centroids at all, an internal reachability bound extrapolated a stale speed across an
  arbitrarily long silence, and injected road geometry between two snapped fixes had no
  length bound.
- A track that jumped and then drew a confident straight line, on some handsets and not
  others (Redmi A5, vivo V2315, both Android 15). On hardware whose entire accuracy
  distribution sits above the moving ceiling, every fix was dropped until one happened to
  dip under the bar, and the gap between them drew as a chord.

A drive on hardware that was already behaving is unaffected — that is asserted directly,
by replaying a degraded-device capture with the new bounds switched off and comparing the
decision sequences byte for byte.

**A parked phone stops producing points.** A single Wi-Fi hop could latch "departed" and
buy a licence for every hop after it, and the departure tally survived the drift back to
where it started. A 20-fix desk replay went from 11 stored points to 1 — with the
accelerometer veto above switched **off**.

**Offline uploads actually drain when the network returns.** The reconnect path was
enqueuing work that WorkManager silently discarded whenever a previous attempt sat in
backoff, so a backlog waited out a delay that had already grown to minutes on a flaky
link. A flap that settles inside the cooldown now still produces exactly one drain instead
of none.

**The upload queue is FIFO again across a reboot.** A multi-day offline backlog spanning a
restart used to sort its entire post-reboot tail to the front, across every unsent session
at once. If your backend assumed arrival order meant anything, it now does.

**`motionState` in the decision log is real.** It was stamped `STOPPED` on every row ever
written — on a motorway and on a desk alike. It remains a log field: no gate reads it, and
capture is never gated on motion detection.

### 5. Storage and migration

Your app's database moves **v8 → v9** on first launch after the upgrade, in one additive
step. Every released tag from `1.0.7-alpha2` to `1.0.8-alpha01` is at v8, so this is the
same jump for all of them. Nothing is dropped, nothing is rewritten, and
`fallbackToDestructiveMigration` is not called anywhere in this SDK.

| | |
|---|---|
| v8 → v9 | Two `filter_state` columns — every delivered fix is now timestamped whatever the verdict, and the run of accuracy rejections is counted. Both are what stop a run of rejections from faking a signal blackout. |

Defaults are chosen so the first fix after the upgrade behaves exactly as it did before
it. Existing sessions, points and geofences are untouched.

**Session logs use a second database**, created by `fieldtrack-sync` when a log channel
resolves — which now happens inside `configure()`. It is a separate file
(`fieldtrack-logs-<package>.db`) with its own schema and its own lifecycle, which is what
makes a credential failure on the log endpoint structurally unable to touch a stored
position. A host that sets `syncLogs = false`, or that never configures uploads at all,
never creates it.

**Downgrading is not supported.** A build with an older schema opening a v10 database will
refuse to start. Roll forward, or clear app data.

**Storage growth**: none unless you enable session logs. With logs on at the default level
the buffer is capped at the `bufferCapacity` you set (5 000 rows). `LogType.DECISION` adds
no storage at all — those entries are converted from the decision log the SDK already
keeps, and are never written twice.

### 6. Backend impact

- **Location points: no change.** Same URL, same envelope, same keys, same enum values. A
  backend built against any released tag from `1.0.7-alpha2` onward needs no edit. (The
  `provider` key became an object, and the provider *name* moved into `activity_status`,
  back in `1.0.7-alpha2` itself — if your backend predates that tag, see
  [`SYNC-BACKEND-AND-DASHBOARD.md` §1.3](SYNC-BACKEND-AND-DASHBOARD.md#13-field-notes-that-change-your-schema).)
- **Session logs: one new route**, only if you enable them —
  [`SYNC-BACKEND-AND-DASHBOARD.md` §11](SYNC-BACKEND-AND-DASHBOARD.md#11-session-logs).
- **Decision-log rejections gained one new `reason` string**, `"Stillness Veto"`, plus
  `"Accuracy Bridge"`. If you store reasons as text and validate leniently — which that doc
  asks you to — nothing breaks. If you validate against a fixed list, add them.

### 7. Upgrade checklist

```
[ ] Bump the FieldTrack version.
[ ] ./gradlew :app:dependencies | grep okhttp   — one version across the family.
[ ] Build, install OVER the previous version (not a fresh install) and open the app once.
    Confirm it starts: that is the v8 → v9 migration running.
[ ] Confirm a tracked session still uploads points to your existing endpoint.
[ ] Optional: enable session logs, add the /v1/logs/batch route, confirm entries arrive
    with the same device_id as your points.
[ ] Optional: enable suppressWhileStationary and leave a device on a desk for an hour.
    Expect one point, not a cloud.
```

---

## Released tags

`1.0.8-alpha01` (2026-09-01) is the most recent. `1.0.8`, `1.0.7-alpha5`, `1.0.7-alpha4`,
`1.0.7-alpha3`, `1.0.7-alpha2` precede it. All are at database schema v8; see `git log` for
per-tag history.
