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

### Added

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
- `docs/MOTION-QUALITY-FINDINGS.md` — the measurements behind the above.
- Sample app: a config console covering the whole `TrackerConfig` surface, and a status screen.
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
