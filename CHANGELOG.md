# Changelog

All notable changes to the FieldTrack SDK.

Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Versions are the
JitPack tags — a release is `com.github.fieldtrack360.fieldtrack:<module>:<tag>`.

---

## [Unreleased]

Everything below is on `motion_fallback` and not yet tagged. The last release is
[`1.0.7-alpha2`](#107-alpha2--2026-08-26).

**No breaking change against `1.0.7-alpha2`.** The upload-status notification and the motion
fallback layer are both *new* in this range, so the API adjustments listed under Changed are to
surfaces that have never shipped. A host upgrading from `1.0.7-alpha2` has nothing to edit.

### Upgrading from a released build

`fieldtrack-core`'s database goes **v8 -> v9**. Every published tag from `v1.0.2` to `1.0.8`
ships v8, so that is the step a device in the field actually takes; `MIGRATION_8_9` is
additive (two `filter_state` columns, both defaulting to `0`) and is registered in
`addMigrations`. Older installs still upgrade — the chain is unbroken back to v1 and
`fallbackToDestructiveMigration()` is never called, so no host loses a recorded track.

`fieldtrack-sync` creates a **second, separate** database, `fieldtrack-logs-<package>.db`,
at version 1. It has never shipped, so no install has the file and there is nothing to
migrate; Room creates it on the first entry written after a log channel resolves. Since
`configure()` now derives one by default, that is most hosts — a host that sets
`SyncConfig.syncLogs = false`, or that configures no uploads at all, never gets the file.

New: `TrackerDatabaseMigrationTest` seeds a real database from each **committed schema
export** — its own DDL, indices, `room_master_table` identity and `user_version` — then
opens it through the shipped `TrackerDatabase.build(context)` and lets Room run the chain
and validate the result. It covers every exported version, and a companion case fails if a
`@Database(version = ...)` bump ever lands without its export committed beside it.

### Added

- **Every upload is written to logcat as one line, under `FieldTrackApi`.** Debug builds
  only — the lines go through `sdkLog`, which the release variant compiles out.

  ```
  $ adb logcat -s FieldTrackApi
  D/FieldTrackApi: POST points https://api.acme.test/v1/location/batch -> 200 success in 412ms (18422B gzip)
  W/FieldTrackApi: POST logs   https://api.acme.test/v1/logs/batch -> 503 refused in 9004ms (2210B gzip) retryAfter=30000ms
  ```

  `adb logcat -s FieldTrackApi` is the device's whole API conversation and nothing else,
  both queues in one stream. A 2xx is `d`, everything else is `w`. Until now the only trace
  of an exchange was `SyncEvent.HttpResponse`, which carries a status code and a row count —
  no endpoint, no method and no duration, so a server that was *answering but slowly* was
  invisible.

  - The word after the status is the outcome and is deliberately **not** derived from it:
    `success`, `unauthorized`, `forbidden`, `refused`, `no_response`, or
    `threw <Exception>`. A request that never reached a server has no status, and reading
    that as a 500 is what makes "the API is down" indistinguishable from "this device has
    no signal".
  - Written at the `SyncTransport` seam, so it covers a host's own client and anything its
    interceptors did to the request — and it is the only place an exchange can be *timed*.
  - **Never printed:** headers and bodies in either direction, and the URL's query string
    and userinfo. A credential lives in all four, and `SyncResponse.Failure.body` can echo a
    request header back.
  - A transport that throws — which the interface forbids but a host's own client may do
    anyway — is logged before the throw propagates. A `CancellationException` is not: a
    drain cancelled with its `viewModelScope` is a caller changing its mind, not a failed
    call.
  - Nothing is stored or uploaded. API calls are deliberately absent from the log channel of
    §15: a row per upload on disk is a different price, and for the log channel itself it
    would be an entry that the next log upload has to ship, which writes another one — a
    buffer that never drains and a radio that never sleeps.

- **Session logs — a diagnostic channel with its own endpoint, in `fieldtrack-sync`.**
  Points answer *where the device was*; this answers *why there is nothing there*. **On by
  default whenever `configure()` is called**, and entirely inside the optional sync
  artifact — `fieldtrack-core` carries no
  logging code and no log table, so a host that does not depend on `fieldtrack-sync` pays
  nothing. Server contract: [`docs/APP-LOG-API.md`](docs/APP-LOG-API.md); design:
  [`docs/SYNC-BACKEND-AND-DASHBOARD.md` §11](docs/SYNC-BACKEND-AND-DASHBOARD.md#11-session-logs).
  - `SyncConfig.syncLogs` (default `true`) and `SyncConfig.Builder.syncLogs(Boolean)` —
    **`configure()` sets the channel up for you.** It derives the URL from the origin of
    the points `SyncConfig` plus `v1/logs/batch`, inherits `device_id` from
    `SyncConfig.extraParams`, and reuses the points headers. Sending a different `device_id`
    would produce two unrelated datasets, so inheriting it is the default.

    On rather than off because of what the logs are for: they explain the report that
    arrives as "tracking stopped on one phone yesterday", and by then the window to have
    been collecting has closed.

    **Never fatal.** If the channel cannot be derived — no `device_id`, an unparseable URL
    — `configure()` logs why under `Tracker/TrackerSync` and points continue unaffected.
    Deriving a log channel is never a reason to fail a points config. **Backends should
    expect log traffic from any host that uploads points and has not opted out.**
  - **The SDK's own log output is recorded automatically**, with no `log()` call from the
    host. Every internal line — the licence verdict, a provider that went quiet, a worker
    that gave up — goes through `TrackLogger`, and `SdkLogRelay` (new, in `fieldtrack-geo`)
    tees that stream into the buffer for as long as a log channel exists. Recorded as
    `LogType.MESSAGE` with the SDK's own tag, so one device's story reads as one ordered
    stream rather than two to merge by hand.

    **`d` maps to `DEBUG` and `w` to `WARN`, and `LogSyncConfig.level` still decides.** At
    the default `INFO` that means the SDK's warnings are kept and its commentary is not —
    `.level(LogLevel.DEBUG)` captures everything, at several lines per fix.

    **Warnings work in release builds**, which is the point — the incident is never on a
    debug build. `sdkLog` is split in two: it keeps its old debug-only gate for the SDK's
    commentary, and a new `sdkWarn` runs when either `SDK_LOGGING_ENABLED` is set *or* a
    relay sink is installed. A host that never configures log shipping is unchanged:
    `isActive` is a volatile read returning false and the block is skipped.

    The split is where the cost sits. A string can only be written at runtime if it is in
    the artifact, so `sdkWarn`'s messages now ship inside the release AAR where anyone can
    read them; `sdkLog`'s do not, and `verifyReleaseObfuscation` still asserts a sample of
    the commentary never appears. Warnings are the slice worth that trade: small, and the
    only thing the default `LogSyncConfig.level` keeps anyway.

    The relay never recurses (a sink whose write path logs is guarded per thread), never
    throws into its caller, and never relays the `FieldTrackApi` upload log — an entry
    describing a log upload is an entry the next log upload has to ship.
  - `TrackerSync.configureLogs(LogSyncConfig, SyncTransport?)` — the explicit form, for a
    different endpoint, credential, level or interval. **An explicit call wins
    permanently**: once made, later `configure()` calls leave it alone rather than
    re-deriving over it. Unlike the derived default it throws on an invalid config.
  - `TrackerSync.log(level, tag, message, code, data)`, `logLifecycle(phase, tag)`,
    `getLogs(sessionId, limit, offset)`, `pendingLogCount()`, `syncLogsNow()`,
    `requestLogSync()`, `disableLogSync()`, `logEvents`, `logEndpoint`,
    `isLogSyncConfigured`.
  - `LogSyncConfig` (+ `Builder`), `LogRecord`, `LogLevel`, `LogType`, `LifecyclePhase`,
    `LogSyncQueue`, `LogPayload` / `LogEntryDto` — all in `com.field360.traker.sync`.
  - **Its own Room database**, `fieldtrack-logs-<package>.db`, created only once a log
    channel resolves. A separate file from the one holding positions, which is
    what makes a credential failure on the log endpoint structurally unable to reach a
    stored point.
  - **Prompt drain on a severe entry.** The channel is a 15-minute `LogSyncWorker`
    heartbeat, except that an entry at `LogSyncConfig.nudgeLevel` (`WARN` by default) asks
    for an upload straight away, throttled to one per `nudgeCooldownMs` (30 s). A burst
    inside that window is deferred to its end rather than dropped, so entries written after
    the first upload do not wait out the heartbeat. `nudgeLevel(null)` disables it.
  - Filtering happens at **record** time, not upload time: it keeps the buffer a strict
    FIFO the uploader settles with one cursor, and stops a device storing what it will
    never send.
  - Two behaviours that are deliberately the **inverse** of the points queue: a
    permanently-rejected 4xx batch (`413` over the endpoint's 500-entry ceiling included)
    is dropped rather than retried forever, and a 401/403 stops log shipping while keeping
    the buffer. Nothing on this channel can reach `Tracker.stop()`, the point queue, or a
    row of `track_point`.
  - `LogType.DECISION` is read from the SDK's existing decision log through
    `Tracker.getDecisions` and converted at send time, tracked by a per-session watermark
    — never mirrored into a second table. ~29 000 entries per device per shift; enable it
    for a named device with a ticket open, not for a fleet. The first `configureLogs` that
    enables it moves the watermark to the newest row, so an opt-in ships the next drive
    rather than the last three days.
  - **The device's motion hardware, saved with the session.** One `lifecycle` entry at the
    head of every session — `code: DEVICE_MOTION`, `tag: Motion`, `phase: device_motion` —
    carrying `motion_quality` (`FULL` / `DEGRADED` / `POOR`) and the eight sensors behind
    it: `accelerometer`, `gyroscope`, `magnetometer`, `significant_motion`,
    `step_detector`, `step_counter`, `barometer`, `rotation_vector`, plus the
    `activity_recognition` grant. The batch envelope's `device` block names the phone and
    says nothing about whether it can **detect a stop**, which is what decides the capture
    cadence: `POOR` forces `CONTINUOUS` and `DEGRADED` doubles the stop timeout, so a track
    full of holes on that hardware is the hardware's gaps rather than the SDK's — and until
    now the server had no way to reach that answer. `POOR` is recorded at `warn` so it earns
    a prompt drain; `DEGRADED` and `FULL` stay at `info`, because warning on the ordinary
    state of a great deal of cheap hardware would warn on half a fleet and therefore on
    none of it. Written once per session, on the session-start signal and again when
    `configureLogs()` is called mid-session, and never without an open session to file it
    against. `activity_recognition` is carried separately because the SDK folds that grant
    into the two step fields, so a `false` there is either no sensor or no permission.
    `LifecyclePhase.DEVICE_MOTION` is new in the public API.
  - **A provider toggle is a `warn`, so it drains promptly.** `ProviderChange` used to be
    logged at `info` unconditionally, which put it below `nudgeLevel` and left a GPS toggle
    sitting in the buffer for up to `uploadIntervalMinutes`. It is now `warn` when a provider
    or the location master switch actually moved, carries the transition as a `code`
    (`GPS_OFF` / `GPS_ON` / `NETWORK_OFF` / `NETWORK_ON` / `LOCATION_OFF` / `LOCATION_ON`)
    and the `previous_gps` / `previous_network` / `previous_enabled` fields in `data`. A
    power-save, airplane or permission field moving stays at `info` — each already has an
    entry of its own. The first observation after start is a starting position rather than a
    transition and is never raised.
  - **A missing log endpoint costs one request per process, not one per heartbeat.**
    `404`, `405` and `501` now halt this channel the way `401`/`403` already did — buffer
    kept, positions untouched, recovery on the next `configureLogs()`. They are statements
    about the endpoint rather than about the bytes just sent, so dropping a batch and
    retrying at the next heartbeat would discard the host's diagnostics forever to be told
    the same thing. `503` stays retryable, as `docs/APP-LOG-API.md` documents it.
  - **Release packaging.** `proguard-rules.pro` now pins the log API — `LogSyncConfig` (+
    `Builder`, `Companion`), `LogSyncQueue`, `LogRecord`, `LogLevel`, `LogType`,
    `LifecyclePhase`, `LogPayload`, `LogAppInfo`, `LogDeviceInfo`, `LogEntryDto` — and
    `verifyReleaseObfuscation` now requires them. Being reachable from a kept `TrackerSync`
    member is not being kept: R8 shortened all of them to `a.class`…`g.class`, could not
    repackage them out of a package holding pinned classes, and the release build failed the
    obfuscation audit. A published AAR built without these would also have left a host
    unable to name `LogSyncConfig`. `consumer-rules.pro` is unchanged and still empty — the
    host-side pass is covered by Room's, WorkManager's and kotlinx-serialization's own
    consumer rules.
  - The Room log store moved from `sync.internal.db` to `sync.data.db`, and the
    `TrackerSync.build` lambdas that construct the recorder and the queue moved to
    `sync.internal.LogWiring`. Both are packaging, not behaviour: Room pins its database
    class by name and `sync.internal.**` is on the release audit's forbidden list, while a
    lambda written inside the API package becomes a synthetic class R8 renames but cannot
    move out of it. `fieldtrack-core` places its own Room classes and integrity internals
    the same way for the same reason. The exported schema moved with it —
    `fieldtrack-sync/schemas/com.field360.traker.sync.data.db.LogDatabase/1.json`.
  - **No change to `fieldtrack-core`'s database.** The schema stays at v9.

- **Upload-status notification** — a diagnostic that puts the live upload queue on the ongoing
  foreground notification, readable with the host app dead. Off by default and meant to stay off
  in a shipping app.
  - `ServiceConfig.showSyncStatusInNotification` (`false`)
  - `ServiceConfig.syncNotificationSubText` (`null`) — the subtitle
  - `ServiceConfig.syncNotificationText` (`"unsynced {pending} · last upload {age}"`)
  - Builder: `.showSyncStatusInNotification(…)`, `.syncNotification(subText, text)`
  - `{pending}` and `{age}` are substituted at post time; an unknown `{token}` is left as written
    rather than blanked, so a typo shows up as itself. Refreshed on the `watchdogIntervalMs` tick.
  - The line is posted only while sync is actually configured, so a queue depth with no endpoint
    is never reported as a backlog.
- **`TrackerEvent.ProviderChange.previous`** — the `ProviderState` this change replaced, or
  `null` on the first observation after the monitor starts. Appended with a default, so every
  existing `ProviderChange(state)` construction and `event.state` read is unaffected. It exists
  because a GPS provider toggle behind an unchanged master switch emits no other event, and was
  previously indistinguishable from a power-save flip without a consumer keeping its own copy of
  the last snapshot. `null` is deliberate rather than a gap: the initial `ProviderState` is a
  constructor default, and diffing against it would announce a GPS toggle and a permission grant
  on every launch.
- **`SyncEvent.NetworkAvailable(queued)`** — emitted when the device returns to a usable network
  *and* rows are queued. A reconnection with an empty queue is silent.
- **`TrackerState.motionQuality` and `TrackerState.effectiveTrackingMode`** — what the motion
  hardware can actually do, and the tracking mode in force after any override. Lets a host tell
  "`MOTION_ONLY` was requested" from "`MOTION_ONLY` was downgraded to `CONTINUOUS` on this
  device".
- **Motion fallback / battery optimisation** — a cadence controller that parks the vehicular
  tier across an uncommitted stop (`onStopPending`) and restores it when the vehicle pulls away,
  rather than dropping to the base interval and missing the corner immediately after a junction.
  A committed stop clears the claim, so a drive that ends in a walk does not carry the vehicular
  cadence into the walk.
- **`TrackOptions.snapMaxDetourFactor` (`2.5`) and `TrackOptions.snapBridgeFlatM` (`200.0`)** — the
  bound on how much road geometry may be injected between two snapped fixes. Appended to the end of
  the constructor, so existing positional and `@JvmOverloads` call sites are unaffected. See the
  road-snap entry under Fixed.
- **`TrackerConstants.reachableMaxDtSec` (`120f`)** — how far back the accuracy bridge and the sigma
  gate's forced reset may extrapolate a prior speed. See the reachability entry under Fixed.
- `docs/MOTION-QUALITY-FINDINGS.md` — the measurements behind the above.
- Sample app: a config console covering the whole `TrackerConfig` surface, and a status screen.
- **Sample app: geofence crossing notifications, with a durable record.** A notification on every
  ENTER/EXIT of a host-registered fence, on its own channel so it can be silenced separately from
  the SDK's ongoing tracking notification, and a "fence notifications" card on the status screen
  listing what has been posted. No SDK change — `TrackerEvent.GeofenceEntered`/`GeofenceExited`
  and `Tracker.getGeofenceEvents()` already carried everything needed; what a user should be
  *told* about a crossing is a host decision, and the sample now demonstrates making it.
  - `GeofenceAlertLog` — the host's own record, kept across process death. Separate from the SDK's
    history on purpose: that store is what happened, this is what the app reacted to, and the SDK
    trims on its own schedule.
  - The event is treated as a doorbell, not a payload. `Tracker.events` is `replay = 0` and
    `GeofenceEntered` carries no timestamp, so every field — including the key that dedupes a
    crossing — is read back from `getGeofenceEvents()`. A crossing delivered while nothing was
    subscribed is therefore picked up by the next read rather than lost, and the catch-up path and
    the live path are the same code.
  - Collected in `Application.onCreate` via `onSubscription`, not in a view model: `StationaryFenceReceiver`
    is a manifest receiver, so a fence crossed with the app in a pocket starts the process, and a
    collector living on a screen would miss exactly the crossings that matter.
  - The SDK's internal stationary wake fence is recorded but never notified — the motion layer
    re-arms it at every stop, so notifying on it would bury real fences under a notification per
    traffic light.
- **Accelerometer veto on stationary drift** (EC-142) — a third, independent defence against a
  parked device producing points. Every other stationary defence reasons about position, because
  a GNSS fix carries nothing else; this one measures whether the device physically moved, which
  indoor multipath cannot fabricate.
  - `MotionConfig.suppressWhileStationary` (`false`) — off unless the host asks
  - `MotionConfig.stillnessEscapeMin` (`30`) — the bound after which suppression lapses for one
    fix, so a wedged sensor degrades to the previous behaviour rather than silencing a session
  - Builder: `.suppressWhileStationary(…)`, `.stillnessEscapeMin(…)`
  - New rejection reason `Reasons.STILLNESS_VETO` in the decision log
  - **A veto, never a trigger, and never a single witness.** It can only remove a point the
    pipeline had *already* classified as stationary; it is not consulted once displacement or
    Doppler read as moving, and one counted step withdraws it. Those bounds are why it is safe:
    a motion API reporting `STILL` through a 17-minute drive is a documented failure on this
    SDK's target hardware (EC-53), and a wrong sensor must cost a drift point, never a trip.
  - Turned off automatically, with a `Diagnostic`, on a device with no accelerometer.

### Fixed

- **A point plotted on a street the device never entered, mid-session and again at the end of
  it.** Reported from Delhi: the track left the route mid-journey, ran past a school the user
  never passed, and closed with a green (≥ 20 km/h) spur nobody drove. Two independent holes,
  either of which is sufficient on its own:
  - **The reachability envelope extrapolated the prior speed across the entire silence.**
    `reachable()` — the bound shared by the accuracy bridge and the sigma gate's forced reset —
    computes `priorSpeed × Δt × 1.3 + flat`, and `Δt` runs from the last *stored* point. Every
    caller is on a path that stored nothing, and while the run that earns a bridge is bounded
    (`maxHardRejectRun`), the silence in front of it is not: a heuristic-gate rejection, a
    stillness veto, a held recovery candidate and a sigma outlier each decline to store *and*
    clear the hard-reject run. Twenty quiet minutes at a vehicular prior speed therefore produced
    an envelope kilometres wide — arithmetic that reads as a guard while permitting the exact
    teleport it was written to stop. The speed term is now capped at
    `TrackerConstants.reachableMaxDtSec` (120 s); the flat allowance is deliberately outside the
    cap, so a standing start keeps its floor. `Float.MAX_VALUE` restores the previous behaviour
    exactly. Nothing a normal drive produces is affected: a full run is 48 s at the 12 s
    vehicular tier and 16 s at the 4 s turn-burst tier.
  - **Stage 1.5 was dead code on the fused provider.** `TrackFix.looksLikeNetworkFix` read
    `hasSpeed` as if the flag meant what the platform documents. It does not on the fused
    provider: a fix fused from a Wi-Fi or cell centroid routinely arrives with `hasSpeed() ==
    true` and a speed of exactly `0.0`, because the fuser fills the field rather than leaving it
    clear. That one stamped zero meant the centroid was never classified as one, so it faced only
    the moving accuracy ceiling — which the accuracy bridge and the forced reset are both
    entitled to overrule. In a city with Delhi's Wi-Fi density that is the dominant source of an
    off-route point. A speed of exactly zero with no reported speed accuracy now counts as *no
    velocity solution*, alongside the existing no-bearing and no-altitude witnesses. All four
    must agree, which leaves every neighbouring case untouched: a stationary GNSS fix still
    carries an altitude, the phantom-Doppler failure reports 3–8 m/s rather than `0.0` (EC-36),
    and the Unisoc/MediaTek HALs clear `hasSpeed` rather than stamping it.
  - **The injected road span had no bound but its own index.** `Snapper` fills the leg between
    two snapped fixes with `road[previousSegment + 1 .. match.segmentIndex]`, and the only test
    on that span was that the index ran forwards. "Forwards" is an assertion about the matcher:
    the projection is forward-only and cannot rewind, so wherever the returned geometry doubles
    back — an out-and-back spur, a roundabout, a stretch the matcher lost and had replaced by raw
    coordinates — two fixes metres apart project onto segments a kilometre apart and every vertex
    between them is drawn. That is worse than a chord rather than better: it follows real streets
    and turns at real junctions, so it reads as a measurement. The span is now bounded by
    `TrackOptions.snapMaxDetourFactor` (2.5) against the straight line between the two fixes, plus
    `TrackOptions.snapBridgeFlatM` (200 m) so a junction or roundabout — where the chord approaches
    zero and the ratio stops meaning anything — keeps its geometry. Refusing the injection does not
    refuse the snap: both fixes are still within `snapMaxOffRoadM` of the road and keep their
    projected positions, and the leg draws as the chord between them.
    `Double.POSITIVE_INFINITY` restores the previous behaviour exactly (EC-101a).

- **A track that jumped, then drew a straight line, on some devices and not others.** Reported
  on a Redmi A5 and a vivo V2315 (both Android 15) while a third handset on the same build was
  flawless. The points were not lost — consecutive stored points were hundreds of metres apart,
  so the polyline drew a confident chord across a route that was never travelled. Four
  mechanisms, compounding:
  - **The moving accuracy ceiling had no bound on the *run*.** Stage 3.5 (`accuracyMovingMax`)
    and stage 1.5 (`accuracyNlpReject`) were the only gates in the pipeline with no escape
    valve — the sigma gate has its forced reset, recovery has its hold, these two simply
    returned. Correct per fix; on hardware whose whole accuracy distribution sits above the
    ceiling it means every fix is dropped until one happens to dip under the bar, and the gap
    between them is drawn as a straight line. The run is now bounded by
    `TrackerConstants.maxHardRejectRun` (4), after which the next fix that is *reachable* from
    the prior speed is stored as `Reasons.ACCURACY_BRIDGE`. `0` restores the old behaviour
    exactly (EC-139a).
  - **Rejected fixes manufactured a signal blackout.** Only an accept advances the filter clock,
    so a run of rejections aged the filter past `signalGapSec` and recovery re-anchored on a gap
    that never happened — which via `clearMovement()` dropped the captured heading (bearing-change
    capture went blind, so the next corner plotted as a chord) and restarted the departure ladder
    (so the next ~100 m stored nothing). Each manufactured gap made the next one more likely.
    `FilterState.lastSeenElapsedNanos` now records every fix the pipeline is handed, whatever the
    verdict, and recovery requires the provider to have actually stopped delivering. On a genuine
    blackout nothing changes (EC-140a).
  - **The sigma gate's forced reset stored whatever it re-seeded onto**, needing only to clear a
    10 m floor. The re-seed stays unconditional — EC-43's "the filter can never wedge" is kept by
    the seed, not by the store — but the fix now becomes a *vertex* only if the leg is reachable
    at the speed the device was already going, with a 400 m floor for a standing start (EC-43a).
  - **`TrackFix.looksLikeNetworkFix` read the Doppler flags alone.** The Unisoc and MediaTek HALs
    on these handsets clear `hasSpeed`/`hasBearing` on genuine GNSS fixes — cold start, walking
    pace, weak sky view — so those fixes were judged as Wi-Fi centroids and rejected above 25 m.
    Altitude is now a third witness: a network centroid has none, a GNSS fix always does. This
    only ever narrows the classification, and what newly passes is still judged by every gate
    below it.

  Room schema **8 → 9** (`MIGRATION_8_9`), additive, two `filter_state` columns with defaults
  that reproduce the pre-upgrade behaviour on the first fix after the upgrade. A device that
  already met its accuracy ceiling replays byte-identically — asserted directly in
  `DegradedDeviceTest`, which pins the old behaviour with `maxHardRejectRun = 0` and compares
  the decision sequences.

- **A React Native host crashed on its first cookie-bearing request.** `fieldtrack-core` links
  OkHttp 5 for the licence check, and OkHttp 5 deleted the internal class
  `okhttp3.internal.Util`. Gradle's conflict resolution upgrades the `okhttp` module and nothing
  else, so a host that already had `okhttp-urlconnection` 4.x kept it — and that version's
  `JavaNetCookieJar` calls the deleted class, taking the app down with
  `NoClassDefFoundError: Failed resolution of: Lokhttp3/internal/Util;` on a stack naming no
  FieldTrack code. `fieldtrack-core` now publishes a dependency *constraint* pulling
  `okhttp-urlconnection` up to the same version as the core jar. We do not depend on that
  artifact, so this adds nothing to the AAR and is inert in a host that never had it. It is a
  `required` version, not `strictly`: a host may move the whole OkHttp family past us, only
  never split it. A host whose build forces OkHttp versions itself — React Native's Gradle
  plugin can — still has to align the two artifacts on its own side.
- **A phone lying on a desk kept storing points** (EC-142). Two independent halves of the
  net-displacement departure ladder, both of which had to be closed:
  - **One centroid hop confirmed a departure.** `persistConfirmNet` let a single fix latch
    `movingMode` on net displacement alone, and `movingMode` switches the whole ladder off until
    settle detection unwinds it. A 160 m Wi-Fi hop therefore bought a licence for every hop after
    it, which stored as `Vehicular`. The shortcut now requires the GNSS chip to report *some*
    motion: where it reports none, displacement is the only evidence in play and its size says
    nothing, because drift is always able to be a long way from the anchor. This cannot be fixed
    with a speed bar — `dtSec` runs from the last *stored* point, so a run of rejected fixes
    stretches it and a 160 m hop across 60 s computes as an unremarkable 2.6 m/s.
  - **The tally survived the drift back.** Stage 7-A runs only for a fix that measured as moving,
    so an excursion's outward leg was counted and the return to the anchor was never seen.
    `departCount` and the net high-water mark persisted across arbitrary stretches of stillness,
    and two hops minutes apart satisfied a ladder written to require two *consecutive* advancing
    fixes. A rejected fix back inside `persistMinNet` of the origin now clears an unlatched
    tally — someone paused at a crossing is still out at their high-water mark and keeps theirs.
  - A 20-fix desk replay went from 11 stored points to 1 (the session's unconditional `Init`),
    with the accelerometer veto switched **off**. A walk, a blind-chip departure and a drive are
    regression-guarded in `StationaryDriftTest`.
- **Every `FixDecision` recorded `motionState = STOPPED`.** `FilterState.motionState` had no
  writer anywhere in the SDK — it was stamped onto every decision row and every raw-point row as
  its default, so the first column an investigation reaches for said the same word on a motorway
  as on a desk. The motion layer's state is now carried in on `IngestContext` and stamped for
  real. It is a log field and stays one: no gate reads it, because capture is never gated on
  motion detection (EC-53).
- **Offline sync did not drain when connectivity returned.** The connectivity watcher enqueued
  through `ExistingWorkPolicy.KEEP`, which does nothing while a request already exists in any
  unfinished state — and `ENQUEUED` is what a request in linear backoff looks like. Because
  WorkManager's `NetworkType.CONNECTED` releases work on *connected* rather than *validated*, a
  drain routinely ran a second before routing worked, failed, and re-entered backoff; the
  validated rising edge that followed emitted `NetworkAvailable`, read a non-empty queue, and was
  then discarded. The backlog waited out a backoff already grown to minutes on a flaky link. The
  reconnect path now enqueues with `REPLACE`, which also resets `runAttemptCount`.
- **Non-failures consumed retry attempts.** `Retry("already draining")` (another drain holds the
  lock and is doing the work), `Retry("sync not configured")` and `Retry("no transport")` were all
  mapped to `Result.retry()`. Each permanently grew the linear backoff for every genuine failure
  after it *and* parked the unique work where `KEEP` swallowed later drain requests. All three now
  report success.
- **A reconnection could be dropped rather than delayed.** The 15 s rising-edge cooldown returned
  "not a rise" with nothing left to re-check it, so a network that flapped and then settled inside
  the window produced no drain at all — leaving the queue to the next supervision tick (two
  minutes with the service alive, fifteen without). A suppressed rise is now deferred to the end
  of the cooldown; a flap still yields exactly one drain.
- **Upload queue order was scrambled across a reboot.** `pendingUpload` ordered on
  `elapsedRealtimeNanos`, which restarts at zero on reboot, so a backlog spanning one sorted its
  entire post-reboot tail to the front — and it is the only ordering query with no `sessionId`
  filter, so it shuffled every unsent session at once. Now orders by insertion (`id`), matching
  every other ordering query in the DAO. The queue is FIFO again, which is the case a multi-day
  offline backlog most depends on.
- **The upload-status diagnostic took over the notification title**, replacing the one line that
  names the app holding the foreground service with a debug readout.

### Changed

- **Build configuration split into a committed `configuration.properties` and the gitignored
  `local.properties`.** `FIELDTRACK_LICENSE_URL` and `FIELDTRACK_RESPONSE_KEY` are now read
  from the committed file — `-P…` → environment → `configuration.properties` — and
  `local.properties` is **not** consulted for either. A fresh clone and every CI runner
  therefore build against the same licence endpoint with no setup step, and a per-machine
  override can no longer point one laptop's release at a server nobody tested. Neither value
  is a secret: both are compiled into `BuildConfig` and readable in any published AAR or
  installed APK whichever file they were typed into, and the response key is the *public*
  half of the signing pair.

  Credentials are unaffected and unmoved: `MAPS_API_KEY` and `TRACKER_LICENSE` read
  `local.properties` only, with **no** fallback to the committed file, so a value pasted
  there is ignored rather than silently published. `SYNC_URL` and `OSRM_BASE_URL` are
  per-developer and read `local.properties` first, falling back to the committed defaults.

  `FIELDTRACK_LICENSE_KEYS` is documented as **read by nothing** — the offline token
  signature gate it fed was removed, and the response key is the only key still compiled in.

- **The licence client logs request and response headers**, alongside the bodies it already
  logged, under `Tracker/API_CALL` in debug builds. A proxy answering `200 text/html` is a
  captive portal rather than the licence server, and a `Retry-After` explains a 429 that
  otherwise reads as an outage — neither is visible in a body-only log. Header *names* are
  never shortened; values of `Authorization`, `Cookie`, `X-Api-Key` and the rest are cut to
  a 12-character preview, on the same reasoning as `access_key` in the body. Compiled out of
  release builds entirely, like every other `sdkLog` call.

- **The sync status is layered onto the notification, never replacing it.** Title, subtitle and
  description are three slots: the host keeps the title in both states, the sync headline renders
  through `setSubText` while a status line is on screen, and the status line is the description.
  With no subtitle set there is no subtitle at all.
- **`ServiceConfig.syncNotificationTitle` → `syncNotificationSubText`**, and the builder parameter
  `syncNotification(title, text)` → `syncNotification(subText, text)`. The old name described a
  slot the value no longer occupies. Not a breaking change against any release — the property is
  itself new in this unreleased range. It is `@Serializable` and persisted, and `ConfigStore`
  decodes with `ignoreUnknownKeys`, so a config written by an interim build of this branch decodes
  cleanly with the field back at `null`.
- `SyncWorker` checks `isConfigured` before draining and reports success when nothing is
  listening, instead of retrying into a backoff no `configure()` will arrive to satisfy.

### Documentation

- `docs/SYNC-MODULE.md` — the `KEEP`/`REPLACE` reasoning, the deferral mechanics, the worker
  outcome table, and a testing-checklist entry that checks the *drain* rather than the
  `NetworkAvailable` event (the regression emitted the event correctly and uploaded nothing).
- `docs/INTEGRATION-GUIDE.md` — `SyncEvent.NetworkAvailable`, a "When the network comes back"
  section covering the durable and prompt halves, the FIFO ordering guarantee, the notification
  slot diagram, and four troubleshooting rows.

---

## [1.0.7-alpha2] — 2026-08-26

Last tagged release. See `git log` for history at and before this tag; this file starts here.
