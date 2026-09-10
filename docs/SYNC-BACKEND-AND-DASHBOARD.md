# Sync Backend & Tracking Dashboard

A reference implementation guide for the server side of `fieldtrack-sync`: a Node.js
ingest API that stores every point the SDK uploads, and a single-screen React dashboard
that selects a device, lists its sessions, draws the track on a map, filters by date,
deletes a session, and defines geofences that the device then enforces.

This document is the contract. It is written against the SDK as it actually behaves —
every case below is reachable from `fieldtrack-sync`, not hypothetical.

| | |
|---|---|
| Backend | Node.js 20+, TypeScript, Express 4 |
| ORM | Prisma 5 (PostgreSQL 15+) |
| Frontend | React 18 + Vite, `react-leaflet` (OpenStreetMap tiles — no API key) |
| Companion doc | [`SYNC-MODULE.md`](SYNC-MODULE.md) — the device side of the same contract |

---

## Table of contents

1. [What the SDK sends](#1-what-the-sdk-sends)
2. [What your server must answer](#2-what-your-server-must-answer) ← **read this first**
3. [Database structure](#3-database-structure)
4. [Ingest endpoint](#4-ingest-endpoint)
5. [Dashboard API](#5-dashboard-api)
6. [Geofences](#6-geofences) ← **the SDK uploads none of this; you own it**
7. [React screen](#7-react-screen)
8. [Case matrix](#8-case-matrix)
9. [Test checklist](#9-test-checklist)
10. [Running it](#10-running-it)
11. [Session logs](#11-session-logs) ← **a second endpoint, and a second credential**

---

## 1. What the SDK sends

### 1.1 The request

`POST` (the verb is configurable via `SyncConfig.method`) to the single URL the host set
with `SyncConfig.url(...)` or `baseUrl(...) + path(...)`.

```
POST /v1/location/batch HTTP/1.1
Content-Type: application/json; charset=utf-8
Authorization: Bearer <whatever the host put in SyncConfig.headers>
Content-Encoding: gzip          <-- ONLY if the host set gzipRequestBody(true)
```

- **HTTPS is mandatory** on the device side. Cleartext is accepted only for loopback hosts
  (`localhost`, `127.0.0.1`, `::1`, `10.0.2.2` — the emulator's view of your machine) or
  with `allowCleartext(true)`. So local development works over plain HTTP on `10.0.2.2`;
  production must be TLS.
- **One endpoint, one verb.** There is no per-batch routing, no path parameters and no
  query string. Everything the server needs is in the body or the headers.
- **Headers are opaque to the SDK** and are never read back. Auth lives there.

### 1.2 The body

```jsonc
{
  // Anything the host put in SyncConfig.extraParams is merged at the TOP LEVEL,
  // before "location". Types are preserved: string, boolean, number, or a
  // map/list of those. Your parser must tolerate keys it has never seen.
  "device_id": "8f14e45f-ceea-467a-9c1a-2b0a1e1f9c31",
  "session_id": "20260907-143512-1f0c8a2e",

  "location": [ /* 1..batchSize points, oldest first */ ]
}
```

One element of `location`:

```jsonc
{
  "uuid": "0b7c9a2e4f1d…",              // SHA-1 hex, 40 chars. THE dedupe key.
  "time": 1719400000000,                // epoch ms, DEVICE wall clock
  "local_date": "2026-08-24",           // yyyy-MM-dd in the device's zone at capture
  "latitude": 23.0225,
  "longitude": 72.5714,
  "accuracy": 12.5,                     // metres
  "movementSpeed": 1.4,                 // m/s  (camelCase — yes, really; see §1.3)
  "provider": {                         // OMITTED entirely if never recorded
    "network": true,
    "gps": true,
    "enabled": true,                    // location master switch
    "status": 3,                        // permission tier
    "accuracyAuthorization": 0,         // 0 full / 1 reduced
    "airplane": false
  },
  "hasSpeed": true,
  "hasBearing": true,
  "time_zone": "Asia/Kolkata",          // IANA id, per point (a session can cross zones)
  "activity_status": "gps@moving",      // "<provider>@<movementStatus>"
  "detected_activity_type": "WALKING",  // nullable
  "detected_activity_start_time": 1719399990000,   // 0 when unknown
  "battery_percentage": "87",           // STRING, or absent
  "is_charging": true,                  // absent = "platform would not say", not false
  "is_mock": false,
  "integrity_flags": 0,                 // bitmask
  "integrity_signals": [],              // the same signals by name
  "session_id": "20260907-143512-1f0c8a2e"   // only when includePointSessionId(true)
}
```

### 1.3 Field notes that change your schema

| Field | What bites you |
|---|---|
| `uuid` | Deterministic `SHA-1(sessionId:elapsedRealtimeNanos)`, **not** random. Two writers racing on the same fix produce the same uuid on purpose. This is your primary key. |
| `movementSpeed`, `hasSpeed`, `hasBearing`, `accuracyAuthorization` | camelCase in an otherwise snake_case payload. Not a typo — it is the frozen contract. Do not "fix" it. |
| `time` | The **device** clock. A device with a manual or skewed clock sends skewed times, and the SDK tells you so via `integrity_flags` bit 5 (`AUTO_TIME_DISABLED`) and bit 9 (`CLOCK_SKEWED`). Always store your own `received_at` alongside it. |
| `provider` absent | Means *"the SDK did not look"*, which is not the same as *"everything was off"*. Store `provider_recorded = false` and leave the six columns `NULL`. Never coerce to `false`. |
| `battery_percentage` | A **string** `"0"`–`"100"`, or absent. Absent ≠ `"0"`. |
| `is_charging` | `true` / `false` / absent. Absent ≠ `false`. |
| `session_id` per row | Off by default. When on, it is read from the row at encode time and is authoritative. See §2.5. |
| `local_date` / `time_zone` | Recorded per point, because a session can cross a time zone mid-drive. Do not derive the session's date from the device's *current* zone. |

`activity_status` is the only place the **provider name** (`gps`, `fused`, `network`) still
lives — the `provider` key became an object in a breaking change. Split it on `@`:

```
"gps@moving"  →  provider name = "gps",  movement status = "moving"  (steady | moving)
```

### 1.4 Enum vocabularies

Fixed sets. Store as text; validate leniently — a newer SDK may add members.

| Enum | Values |
|---|---|
| movement status (right of `@`) | `steady`, `moving` |
| `detected_activity_type` | `IN_VEHICLE`, `ON_BICYCLE`, `ON_FOOT`, `WALKING`, `RUNNING`, `STILL`, `TILTING`, `UNKNOWN`, or absent |
| `provider.status` | `0` not determined (Android never sends it), `1` restricted, `2` denied, `3` always, `4` while-in-use |
| `provider.accuracyAuthorization` | `0` full (fine), `1` reduced (coarse) |
| `integrity_signals` (bit) | `ACCESSIBILITY_SERVICE_ACTIVE` (0), `DEVELOPER_MODE_ENABLED` (1), `ADB_ENABLED` (2), `HOOKING_FRAMEWORK_DETECTED` (3), `DEBUGGER_ATTACHED` (4), `AUTO_TIME_DISABLED` (5), `TIMEZONE_MISMATCH` (6), `MOCK_LOCATION_APP_SELECTED` (7), `MOCK_LOCATION_FIX` (8), `CLOCK_SKEWED` (9) |

`integrity_flags` is the bitmask of the same set (`1 shl bit`). Both are sent; the mask is
the durable form, the array is the readable one. Store both — the mask indexes cheaply, the
array reads well in a UI.

### 1.5 What the SDK never sends

Design your schema knowing these gaps exist:

- **No logs, no diagnostics, no crash reports.** Not one line of the SDK's own logging
  leaves the device on this channel. Points carry `integrity_flags` and a provider snapshot
  and nothing else — the *reason* a fix was rejected, a permission revoked mid-drive, or a
  capture suspended is on the device and stays there until host code ships it. That channel
  is a separate endpoint with a separate contract; see §11.
- **No session lifecycle events.** There is no "session started" or "session ended" call.
  Sessions materialise on the server purely from the `session_id` on arriving points.
  A session's start and end are `MIN(time)` / `MAX(time)` over its points, and both move as
  a backlog arrives.
- **No delete, no correction, no backfill instruction.** Uploads are append-only.
- **No device registration.** `device_id` exists only because the host put it in
  `extraParams`. If the host does not, you have nothing to select on — see §5.1.
- **No geofences and no geofence crossings.** `fieldtrack-sync` uploads points and nothing
  else — there is not one geofence reference in the whole module. Fences are registered on
  the device by host code, and crossings are stored on the device by the SDK. Getting either
  to your server is work you write yourself; see §6.
- **No pagination cursor and no batch id.** Batches are anonymous and repeatable.
- **No ordering guarantee across batches.** Within a batch rows are oldest-first, but a
  batch is `pending(batchSize)` across **every unsent session**, so one batch can hold rows
  from three different sessions and two different days.

---

## 2. What your server must answer

This is the part that actually matters. The SDK reads your status code as an instruction,
and two of those instructions are destructive.

### 2.1 The status code table

| You return | SDK does | Reversible? |
|---|---|---|
| **2xx** | Marks those rows synced locally and loads the next batch. | The rows leave the device queue. Only return 2xx after your transaction commits. |
| **401** | **Stops tracking. Clears the entire upload queue. Forgets the config and the credential.** Unsent points are destroyed on the device. | ❌ **Data loss.** |
| **403** | Halts all uploading until the host calls `configure()` again. Rows stay queued, tracking continues. | ⚠️ Recoverable, but only by host app code — nothing your server does will restart it. |
| **429 / 5xx / timeout / connection failure** | Rows stay queued; `SyncWorker` retries with linear 30 s backoff, or at your `Retry-After`. | ✅ Safe. |
| **4xx that is not 401/403** (400, 404, 409, 422…) | Treated exactly like 5xx: **retried forever.** | ⚠️ Infinite loop — see §2.3. |

### 2.2 The two destructive codes

**Never return 401 for a transient condition.** Not for an expired-but-refreshable token,
not when your auth service is down, not when your token cache is cold. 401 means *"the
credential this data was recorded under is gone, and the next login may be a different
user"* — and the SDK acts on that by wiping the device's queue so one user's positions
cannot leak into another user's login. An auth backend having a bad thirty seconds must
answer **503**, not 401.

**Never return 403 for rate limiting or maintenance.** 403 stops uploads permanently from
your side of the wire; the only recovery is the host app calling `configure()` again with a
new credential, which typically needs the user to reopen the app. Use **429 with
`Retry-After`** for load shedding and **503** for maintenance.

Correct uses:

- `401` — the token is genuinely revoked, the user was deleted, the session was signed out
  server-side. You *want* the device queue cleared.
- `403` — this credential is authenticated but may never write this resource: wrong tenant,
  wrong scope, device deprovisioned. You *want* uploads to stop.

### 2.3 Do not return 400 for a bad payload

A 400 is retried with the same bytes, forever, on a 30-second linear backoff. One
permanently malformed row therefore blocks that device's entire queue and burns its battery
indefinitely — the row can never be marked synced, so it is re-sent at the head of every
future batch.

**Accept and quarantine instead.** Validate per row, store what parses, write what does not
to a dead-letter table, and return `200` with a body reporting the split. The device moves
on; you keep the evidence.

```jsonc
// 200 OK
{ "accepted": 98, "duplicates": 1, "rejected": 1, "batch_id": "b_01J8…" }
```

The SDK ignores the response body entirely — it reads only the status code. The body is for
your own logs and for a human running `curl`.

The single exception: a body that is not JSON at all, or has no `location` array. That is a
misconfiguration (wrong URL, a proxy injecting HTML), not a data problem, and it will not
fix itself with a retry either. Return `200` with `{"accepted":0,"rejected":0}` and alert on
your side; returning 400 achieves nothing except a retry loop.

### 2.4 Deduplication is mandatory

> **Duplicates are guaranteed by design.** A batch that fails after the server stored it is
> re-sent whole on the next attempt. The SDK marks rows synced only on a confirmed 2xx, so
> any response lost in flight — a timeout after your commit, a load balancer dropping the
> connection, a client-side read timeout at 30 s — produces an exact re-delivery.

Dedupe on `uuid`, in the database, not in application code:

```sql
INSERT INTO points (...) VALUES (...) ON CONFLICT (uuid) DO NOTHING;
```

`uuid` is deterministic (`SHA-1(sessionId:elapsedRealtimeNanos)`), so a re-delivered row
carries the identical key. In Prisma: `createMany({ data, skipDuplicates: true })`.

Do **not** dedupe on `(lat, lng, time)` — a stationary device legitimately produces points
with identical coordinates, and two devices can share a timestamp.

### 2.5 Resolving which session a point belongs to

Three sources, in strict priority order:

1. **`point.session_id`** (per-row, present when the host set `includePointSessionId(true)`).
   Authoritative. Read from the row at encode time, so it survives a backlog spanning two
   drives and a cold-process drain.
2. **Envelope `session_id`** (from `extraParams`). Only correct when the queue drains before
   each session ends. For an offline backlog it is **wrong** — a device that recorded three
   sessions in a tunnel uploads all three under whichever id was current at the last
   `configure()`, and a `SyncWorker` drain from a process with no UI often carries no
   envelope session id at all.
3. **Neither present.** Do not drop the point. Bucket it into a synthetic session per device
   per `local_date`: `unassigned-<device_id>-<local_date>`. It keeps the track visible in the
   dashboard and stays obviously distinguishable from a real session id.

```ts
const sessionId =
  point.session_id ??
  envelope.session_id ??
  `unassigned-${deviceId}-${point.local_date}`;
```

**Recommend `includePointSessionId(true)` to every host integrating against this backend.**
It is off by default only for byte-compatibility with older releases.

### 2.6 Session ids are human-readable and sortable

Current SDK session ids look like:

```
20260907-143512-1f0c8a2e
└──┬───┘ └─┬──┘ └───┬──┘
  date    time   random suffix
```

`yyyyMMdd-HHmmss` in the **device's** local zone at session start, plus 8 random hex
characters. Consequences for the backend:

- They sort chronologically as plain strings. Handy, but **do not rely on it** — a device
  crossing a time zone or changing its clock breaks the ordering. Sort on a real timestamp
  column.
- **Never parse one back into an instant.** It carries no zone offset. It is a display
  label; `MIN(points.time)` is the truth.
- Older devices still send **UUID v4** session ids. Both shapes must be accepted. Your
  column is `TEXT`, not `UUID`.

### 2.7 Deleted sessions and late arrivals

The dashboard can delete a session. The device cannot be told, and a device that was offline
during the deletion will happily upload that session's remaining points afterwards —
resurrecting a session the user deliberately removed.

**Soft-delete with a tombstone.** Mark the session `deleted_at`, delete its points, and have
the ingest path drop any later point whose resolved session is tombstoned (counting them as
`rejected`, so the drop is visible rather than silent). A hard `DELETE` of the session row
would let the next batch recreate it.

### 2.8 The rest of the response contract

- **`Retry-After`** is honoured on any retryable response, parsed per RFC 9110 in both
  delta-seconds (`Retry-After: 120`) and HTTP-date forms, and clamped to **1 second – 6
  hours**. Unparseable, negative or already-past values read as "no opinion", and the SDK's
  own 30 s linear backoff applies. Send it with every 429 and every 503.
- **Respond inside the read timeout.** The default is **30 s** (connect 5 s, write 20 s). A
  batch of 1000 rows must commit and answer well inside that, or the device times out,
  re-sends the whole batch, and you do the work twice. Bulk-insert; never row-by-row.
- **Accept `Content-Encoding: gzip`** if any host enables `gzipRequestBody(true)`. There is
  no negotiation for request-body encoding — if you do not decode it you will store
  compressed bytes or answer 400 into a retry loop.
- **Body size.** `batchSize` is 1–1000, default 100. A 1000-row batch is roughly 600 KB of
  JSON. Set the body-parser limit to **10 MB** and be done with it.
- **Tolerate unknown keys**, top-level and per-row. `extraParams` is host-defined, and a
  newer SDK may add point fields; both are designed so an older backend keeps parsing.
- **Do not require a request id or idempotency key.** The SDK sends neither. `uuid` is the
  idempotency mechanism.

---

## 3. Database structure

PostgreSQL. Six tables plus a dead-letter. No PostGIS required — the dashboard needs
ordered lat/lng and circular fences, not spatial joins. (Add a `geography(Point,4326)` column
later if you start asking "which points fall inside this polygon".)

```
devices ──< sessions ──< points
   │            │
   │            └──< soft-deleted: deleted_at set, points removed, tombstone kept
   │
   └──< geofences ──< geofence_events
        (device_id NULL = applies to every device)

ingest_batches   — audit trail, one row per HTTP exchange
rejected_points  — dead letter, one row per unparseable element
```

### 3.1 Prisma schema

```prisma
// prisma/schema.prisma
generator client {
  provider = "prisma-client-js"
}

datasource db {
  provider = "postgresql"
  url      = env("DATABASE_URL")
}

/// One installation of the host app. Created on first sight — the SDK never registers.
model Device {
  id        String   @id                       // envelope "device_id", verbatim
  label     String?                            // human name, set from the dashboard
  firstSeen DateTime @default(now()) @map("first_seen")
  lastSeen  DateTime @updatedAt      @map("last_seen")

  sessions Session[]
  points   Point[]
  batches  IngestBatch[]
  geofences      Geofence[]
  geofenceEvents GeofenceEvent[]

  /// Bumped whenever a fence that applies to this device changes. The device polls this
  /// one integer and re-registers only when it moved — see §6.4.
  geofenceRevision Int @default(0) @map("geofence_revision")

  @@map("devices")
}

/// A tracking run. Materialised from points — the SDK sends no lifecycle event.
model Session {
  id       String @id                          // "20260907-143512-1f0c8a2e" or a UUID
  deviceId String @map("device_id")

  /// MIN(points.time) / MAX(points.time). Recomputed on every ingest; both move as a
  /// backlog arrives, which is why neither is derived from the id.
  startedAt DateTime? @map("started_at")
  endedAt   DateTime? @map("ended_at")

  /// Denormalised so the session list renders without touching `points`. A device
  /// recording all day produces tens of thousands of rows per session; COUNT(*) plus a
  /// window-function distance over that, for twenty listed sessions, is the whole query.
  pointCount Int   @default(0) @map("point_count")
  distanceM  Float @default(0) @map("distance_m")

  /// The zone of the FIRST point. Display only — every point carries its own.
  timezone  String?
  /// local_date of the first point. What the date filter matches (§5.2).
  localDate String? @map("local_date")

  /// Server receive time. Distinct from startedAt, which is a device wall clock and can
  /// be skewed or manually set.
  createdAt DateTime  @default(now()) @map("created_at")
  /// Tombstone. Points are hard-deleted; this row survives so a late batch from an
  /// offline device cannot resurrect the session (§2.7).
  deletedAt DateTime? @map("deleted_at")

  device Device  @relation(fields: [deviceId], references: [id], onDelete: Cascade)
  points Point[]

  @@index([deviceId, startedAt(sort: Desc)])
  @@index([deviceId, localDate])
  @@index([deletedAt])
  @@map("sessions")
}

/// One accepted fix. `uuid` is the SDK's own deterministic id and the dedupe key.
model Point {
  uuid      String @id                          // SHA-1 hex, 40 chars
  sessionId String @map("session_id")
  deviceId  String @map("device_id")

  /// Device wall clock, as sent. Indexed for the polyline query.
  time       DateTime
  /// Server receive time. The only clock you control — keep it for skew forensics.
  receivedAt DateTime @default(now()) @map("received_at")

  localDate String @map("local_date")
  timezone  String

  latitude   Float
  longitude  Float
  accuracy   Float
  speedMps   Float   @map("speed_mps")          // wire: movementSpeed
  hasSpeed   Boolean @map("has_speed")
  hasBearing Boolean @map("has_bearing")

  /// Split out of activity_status ("gps@moving"), and kept verbatim as well: the raw
  /// string is the contract, the two columns are what queries actually filter on.
  activityStatus String @map("activity_status")
  providerName   String @map("provider_name")   // gps | fused | network | …
  movementStatus String @map("movement_status") // steady | moving

  detectedActivityType      String?   @map("detected_activity_type")
  detectedActivityStartTime DateTime? @map("detected_activity_start_time")

  /// null means the platform would not say — deliberately not 0 / false (§1.3).
  batteryPct Int?     @map("battery_pct")
  isCharging Boolean? @map("is_charging")

  isMock Boolean @default(false) @map("is_mock")

  integrityFlags   Int      @default(0)  @map("integrity_flags")
  integritySignals String[] @default([]) @map("integrity_signals")

  /// false = the `provider` key was absent = the SDK never looked. The six columns
  /// below are then NULL, which is a different answer from "everything was off".
  providerRecorded Boolean  @default(false) @map("provider_recorded")
  provGps          Boolean? @map("prov_gps")
  provNetwork      Boolean? @map("prov_network")
  provEnabled      Boolean? @map("prov_enabled")
  provStatus       Int?     @map("prov_status")
  provAccuracyAuth Int?     @map("prov_accuracy_auth")
  provAirplane     Boolean? @map("prov_airplane")

  /// The element exactly as received. Cheap insurance: a field added by a newer SDK is
  /// still recoverable after the fact, without a re-upload that can never happen.
  raw Json?

  session Session @relation(fields: [sessionId], references: [id], onDelete: Cascade)
  device  Device  @relation(fields: [deviceId], references: [id], onDelete: Cascade)

  /// The polyline query, verbatim: one session, ordered by time.
  @@index([sessionId, time])
  @@index([deviceId, time])
  @@index([localDate])
  @@map("points")
}

/// One row per HTTP exchange. The SDK retries silently; without this you cannot tell a
/// device that uploaded nothing from a device that uploaded the same batch forty times.
model IngestBatch {
  id         String   @id @default(cuid())
  deviceId   String?  @map("device_id")
  receivedAt DateTime @default(now()) @map("received_at")

  pointCount Int @map("point_count")
  accepted   Int
  duplicates Int
  rejected   Int

  statusCode Int     @map("status_code")
  /// extraParams as received, minus `location`. Where a stale envelope session_id shows up.
  envelope   Json?
  gzip       Boolean @default(false)
  error      String?

  device Device? @relation(fields: [deviceId], references: [id], onDelete: SetNull)

  @@index([deviceId, receivedAt(sort: Desc)])
  @@map("ingest_batches")
}

/// A circular fence defined in the dashboard and pushed to devices, which register it
/// with `Tracker.addGeofence`. Nothing about this comes from the SDK — see §6.
model Geofence {
  id    String  @id @default(cuid())
  /// What the device registers as TrackerGeofence.id. Must be unique per device, must not
  /// collide with the SDK's reserved "fieldtrack-stationary", and must survive a rename —
  /// so it is a slug, not the display name (§6.2).
  fenceId String @map("fence_id")
  name    String

  /// null = applies to every device. A fence for one device names it here.
  deviceId String? @map("device_id")

  latitude  Float
  longitude Float
  /// Metres. The SDK rejects <= 0; Play Services is unreliable below ~100 m (§6.2).
  radiusM   Float  @map("radius_m")

  /// The event names the SDK stamps on a crossing. Defaulted to the SDK's own.
  onEnterEvent String @default("stationary_fence_enter") @map("on_enter_event")
  onExitEvent  String @default("stationary_fence_exit")  @map("on_exit_event")

  /// Off keeps the row but stops distributing it — the device removes it on next poll.
  /// Preferable to deleting a fence whose crossing history you still want to read.
  enabled Boolean @default(true)
  color   String  @default("#2563eb")

  createdAt DateTime  @default(now()) @map("created_at")
  updatedAt DateTime  @updatedAt      @map("updated_at")
  deletedAt DateTime? @map("deleted_at")

  device Device?         @relation(fields: [deviceId], references: [id], onDelete: Cascade)
  events GeofenceEvent[]

  /// One fence id per device. Two rows sharing a fenceId for the same device would make
  /// the device's second addGeofence() silently replace the first — the SDK's registry is
  /// keyed on id and `add` is a register-or-replace.
  @@unique([deviceId, fenceId])
  @@index([deviceId, enabled])
  @@map("geofences")
}

/// One crossing, pushed up by host app code (§6.5). The SDK persists these locally and
/// never uploads them.
model GeofenceEvent {
  /// Derived, not sent: TrackerGeofenceEvent carries no uuid. See §6.5 for the recipe.
  id String @id

  geofenceId String  @map("geofence_id")
  deviceId   String  @map("device_id")
  fenceId    String  @map("fence_id")     // as the device knew it, for orphan crossings

  transition String   @map("transition")  // ENTER | EXIT
  eventName  String   @map("event_name")
  /// Device wall clock, from TrackerGeofenceEvent.timestampMs.
  occurredAt DateTime @map("occurred_at")
  receivedAt DateTime @default(now()) @map("received_at")

  /// The fence's centre and radius AS THE DEVICE HAD THEM. A crossing is evidence about a
  /// fence at a moment; editing the fence afterwards must not rewrite history.
  latitude  Float
  longitude Float
  radiusM   Float @map("radius_m")

  geofence Geofence @relation(fields: [geofenceId], references: [id], onDelete: Cascade)
  device   Device   @relation(fields: [deviceId], references: [id], onDelete: Cascade)

  @@index([deviceId, occurredAt(sort: Desc)])
  @@index([geofenceId, occurredAt(sort: Desc)])
  @@map("geofence_events")
}

/// Dead letter. Written instead of returning 400, which the SDK would retry forever (§2.3).
model RejectedPoint {
  id         String   @id @default(cuid())
  batchId    String?  @map("batch_id")
  deviceId   String?  @map("device_id")
  uuid       String?                            // not unique: the same bad row re-arrives
  reason     String
  raw        Json
  receivedAt DateTime @default(now()) @map("received_at")

  @@index([deviceId, receivedAt(sort: Desc)])
  @@map("rejected_points")
}
```

### 3.2 Why these choices

| Decision | Reason |
|---|---|
| `Point.uuid` is the primary key | It is the SDK's dedupe key. A surrogate `id` with a unique index on `uuid` costs a second B-tree for nothing. |
| `Session.id` is `TEXT`, not `UUID` | Session ids are `20260907-143512-1f0c8a2e` on current SDKs and UUID v4 on older ones. A `UUID` column rejects the current format outright. |
| `pointCount` / `distanceM` denormalised | The session list is the screen's first paint. Aggregating a day of points for every listed session makes it the slowest query in the app. |
| `startedAt` **and** `createdAt` | Device clock vs. server clock. They disagree by hours on a device with `AUTO_TIME_DISABLED`, and a date filter has to say which one it means (§5.2). |
| `providerRecorded` flag | Preserves "we did not look" vs. "everything was off". Six nullable columns without it are ambiguous. |
| `raw Json` per point | Uploads are one-way and append-only. A field you did not model is otherwise unrecoverable — the device has already marked the row synced. |
| `integritySignals String[]` | A Postgres array reads well in the UI without a bit-twiddling join; the mask stays for indexing. |
| Soft-delete on `Session` only | The tombstone is the whole mechanism (§2.7). Points are hard-deleted because they are the bulk and carry no independent meaning. |
| `Geofence.fenceId` separate from `id` | `id` is yours; `fenceId` is the string the device registers and the string a crossing arrives under. Renaming a fence in the UI must not change what the device has registered, or every device re-registers and the crossing history stops joining. |
| `@@unique([deviceId, fenceId])` | `Tracker.addGeofence` is register-**or-replace** keyed on id. Two rows with the same `fenceId` for one device means the second silently overwrites the first on the device, and nothing tells you. |
| `Geofence.enabled` as well as `deletedAt` | Turning a fence off stops distributing it while keeping its crossing history readable. Deleting it cascades the history away. |
| Crossing stores its own lat/lng/radius | A crossing is evidence about a fence *as it was*. Moving the fence next week must not silently relocate last week's arrival. |
| `Device.geofenceRevision` | The device polls one integer instead of diffing a fence list on every wake (§6.4). |

### 3.3 Retention

Points dominate the volume: a device at 1 Hz for an 8-hour shift is ~29 000 rows per day.
Partition `points` by month, or run a nightly prune:

```sql
DELETE FROM points         WHERE time        < now() - interval '180 days';
DELETE FROM sessions       WHERE deleted_at  < now() - interval  '90 days';  -- expire tombstones
DELETE FROM ingest_batches WHERE received_at < now() - interval  '30 days';
```

Do **not** put `geofence_events` on this list without asking first. Crossings are low volume
(a handful per device per day against 29 000 points) and they are usually the record someone
needs months later — "was the van at the depot on the 3rd" outlives any track.


Expire tombstones only well past any plausible offline window. A device that spent a week in
a basement, against a tombstone dropped after a day, means the deleted session comes back.

---

## 4. Ingest endpoint

### 4.1 Route

`POST /v1/location/batch` — the URL the host puts in `SyncConfig.url(...)`.

```ts
// src/routes/ingest.ts
import { Router } from 'express';
import { prisma } from '../db';
import { parsePoint } from '../ingest/parsePoint';
import { resolveSessions } from '../ingest/resolveSessions';
import { recomputeSessionStats } from '../ingest/recomputeStats';
import { authenticateDevice, AuthError } from '../auth';

export const ingest = Router();

ingest.post('/v1/location/batch', async (req, res) => {
  // ── Auth ────────────────────────────────────────────────────────────────
  // 401 and 403 are destructive on the device (§2.2). Anything transient —
  // auth service down, token cache cold, database unreachable — must be 503.
  let principal;
  try {
    principal = await authenticateDevice(req.header('authorization'));
  } catch (e) {
    if (e instanceof AuthError && e.kind === 'revoked')
      return res.status(401).json({ error: 'credential revoked' });
    if (e instanceof AuthError && e.kind === 'forbidden')
      return res.status(403).json({ error: 'not permitted' });
    res.setHeader('Retry-After', '30');
    return res.status(503).json({ error: 'auth unavailable' });   // NOT 401
  }

  const { location, ...envelope } = req.body ?? {};

  // A body with no `location` array is a misconfiguration, not a data problem, and a
  // 400 would only start a retry loop (§2.3). Record it and answer 200.
  if (!Array.isArray(location)) {
    await prisma.ingestBatch.create({
      data: {
        pointCount: 0, accepted: 0, duplicates: 0, rejected: 0,
        statusCode: 200, envelope, error: 'no location array',
      },
    });
    return res.status(200).json({ accepted: 0, duplicates: 0, rejected: 0 });
  }

  const deviceId = String(envelope.device_id ?? principal.deviceId ?? 'unknown');

  const parsed = [];
  const rejected = [];
  for (const rawPoint of location) {
    const result = parsePoint(rawPoint, { deviceId, envelope });
    if (result.ok) parsed.push(result.value);
    else rejected.push({ uuid: rawPoint?.uuid, reason: result.reason, raw: rawPoint });
  }

  const outcome = await prisma.$transaction(async (tx) => {
    await tx.device.upsert({
      where:  { id: deviceId },
      create: { id: deviceId },
      update: { lastSeen: new Date() },
    });

    // Sessions first: points carry a FK. Tombstoned sessions are filtered out here, so a
    // late batch cannot resurrect a session the user deleted (§2.7).
    const live = await resolveSessions(tx, deviceId, parsed);
    const insertable = parsed.filter((p) => live.has(p.sessionId));
    const tombstoned = parsed.length - insertable.length;

    // Bulk insert; skipDuplicates is the ON CONFLICT DO NOTHING of §2.4.
    const { count } = await tx.point.createMany({
      data: insertable,
      skipDuplicates: true,
    });

    await recomputeSessionStats(tx, [...new Set(insertable.map((p) => p.sessionId))]);

    return {
      accepted: count,
      duplicates: insertable.length - count,
      rejected: rejected.length + tombstoned,
    };
  });

  const batch = await prisma.ingestBatch.create({
    data: {
      deviceId, pointCount: location.length, ...outcome,
      statusCode: 200, envelope,
      gzip: req.header('content-encoding') === 'gzip',
    },
  });

  if (rejected.length) {
    await prisma.rejectedPoint.createMany({
      data: rejected.map((r) => ({ ...r, batchId: batch.id, deviceId })),
    });
  }

  // 200 only after the transaction committed. The SDK marks these rows synced on the
  // strength of this code and will never send them again.
  res.status(200).json({ ...outcome, batch_id: batch.id });
});
```

Everything else — an unexpected throw, a dead database — must reach a 503 handler. Never a
bare 500 that a proxy might rewrite, and never a 401.

```ts
// src/app.ts — order matters
import express from 'express';

const app = express();

// gzip request bodies: express.json() decompresses Content-Encoding: gzip natively via
// its `inflate` option (on by default). The limit is the part you must raise — batchSize
// can be 1000 rows (§2.8).
app.use(express.json({ limit: '10mb', inflate: true }));

app.use(ingest);
app.use(dashboardApi);

app.use((err, _req, res, _next) => {
  console.error(err);
  res.setHeader('Retry-After', '60');
  res.status(503).json({ error: 'temporarily unavailable' });   // retryable, never 401/403
});
```

### 4.2 Parsing one point

```ts
// src/ingest/parsePoint.ts
export function parsePoint(raw: any, ctx: Ctx): Parsed {
  if (typeof raw?.uuid !== 'string' || raw.uuid.length === 0)
    return { ok: false, reason: 'missing uuid' };
  if (!Number.isFinite(raw.latitude) || !Number.isFinite(raw.longitude))
    return { ok: false, reason: 'non-finite coordinate' };
  if (Math.abs(raw.latitude) > 90 || Math.abs(raw.longitude) > 180)
    return { ok: false, reason: 'coordinate out of range' };
  if (!Number.isFinite(raw.time) || raw.time <= 0)
    return { ok: false, reason: 'invalid time' };

  // "gps@moving" — the provider NAME lives only here now that `provider` is an object.
  const [providerName = 'unknown', movementStatus = 'steady'] =
    String(raw.activity_status ?? '').split('@');

  // `provider` absent means the SDK never looked. Do not synthesise an object of false.
  const p = raw.provider;
  const providerRecorded = p != null && typeof p === 'object';

  return {
    ok: true,
    value: {
      uuid: raw.uuid,
      sessionId: raw.session_id ?? ctx.envelope.session_id
                 ?? `unassigned-${ctx.deviceId}-${raw.local_date}`,
      deviceId: ctx.deviceId,
      time: new Date(raw.time),
      localDate: String(raw.local_date ?? ''),
      timezone: String(raw.time_zone ?? 'UTC'),
      latitude: raw.latitude,
      longitude: raw.longitude,
      accuracy: Number(raw.accuracy ?? 0),
      speedMps: Number(raw.movementSpeed ?? 0),     // camelCase on the wire
      hasSpeed: Boolean(raw.hasSpeed),
      hasBearing: Boolean(raw.hasBearing),
      activityStatus: String(raw.activity_status ?? ''),
      providerName,
      movementStatus,
      detectedActivityType: raw.detected_activity_type ?? null,
      // 0 is the SDK's "unknown", not the epoch.
      detectedActivityStartTime: raw.detected_activity_start_time
        ? new Date(raw.detected_activity_start_time) : null,
      // Absent stays null. "87" -> 87. Never coerce absent to 0.
      batteryPct: raw.battery_percentage != null
        ? Number.parseInt(String(raw.battery_percentage), 10) : null,
      // Absent stays null. `false` means "confirmed on battery" and is a real answer.
      isCharging: typeof raw.is_charging === 'boolean' ? raw.is_charging : null,
      isMock: raw.is_mock === true,
      integrityFlags: Number(raw.integrity_flags ?? 0),
      integritySignals: Array.isArray(raw.integrity_signals) ? raw.integrity_signals : [],
      providerRecorded,
      provGps:          providerRecorded ? Boolean(p.gps) : null,
      provNetwork:      providerRecorded ? Boolean(p.network) : null,
      provEnabled:      providerRecorded ? Boolean(p.enabled) : null,
      provStatus:       providerRecorded ? Number(p.status) : null,
      provAccuracyAuth: providerRecorded ? Number(p.accuracyAuthorization) : null,
      provAirplane:     providerRecorded ? Boolean(p.airplane) : null,
      raw,
    },
  };
}
```

### 4.3 Resolving and creating sessions

```ts
// src/ingest/resolveSessions.ts
/** Creates any session that does not exist; returns the ids that are NOT tombstoned. */
export async function resolveSessions(tx: Tx, deviceId: string, points: ParsedPoint[]) {
  const ids = [...new Set(points.map((p) => p.sessionId))];

  const existing = await tx.session.findMany({
    where: { id: { in: ids } },
    select: { id: true, deletedAt: true },
  });
  const known = new Map(existing.map((s) => [s.id, s]));

  for (const id of ids) {
    if (known.has(id)) continue;
    const first = points.find((p) => p.sessionId === id)!;
    await tx.session.create({
      data: { id, deviceId, timezone: first.timezone, localDate: first.localDate },
    });
    known.set(id, { id, deletedAt: null });
  }

  // Tombstoned sessions are dropped, not resurrected (§2.7).
  return new Set([...known.values()].filter((s) => !s.deletedAt).map((s) => s.id));
}
```

### 4.4 Recomputing session stats

Run this inside the same transaction, once per touched session. Both bounds move as a
backlog arrives, so it is a recompute, never an increment.

```sql
UPDATE sessions s SET
  started_at  = agg.min_time,
  ended_at    = agg.max_time,
  point_count = agg.n,
  distance_m  = agg.dist
FROM (
  SELECT
    session_id,
    MIN(time) AS min_time,
    MAX(time) AS max_time,
    COUNT(*)  AS n,
    COALESCE(SUM(
      -- Haversine against the previous point in time order.
      6371000 * 2 * asin(sqrt(
        power(sin(radians(latitude - prev_lat) / 2), 2) +
        cos(radians(prev_lat)) * cos(radians(latitude)) *
        power(sin(radians(longitude - prev_lng) / 2), 2)
      ))
    ), 0) AS dist
  FROM (
    SELECT session_id, time, latitude, longitude,
           LAG(latitude)  OVER w AS prev_lat,
           LAG(longitude) OVER w AS prev_lng
    FROM points
    WHERE session_id = ANY($1)
    WINDOW w AS (PARTITION BY session_id ORDER BY time)
  ) t
  GROUP BY session_id
) agg
WHERE s.id = agg.session_id;
```

---

## 5. Dashboard API

Kept separate from ingest: different auth (a human's session, not a device token), and it
may return 4xx freely — no SDK is listening.

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/devices` | Populate the device selector. |
| `GET` | `/api/sessions?deviceId=&from=&to=&dateField=&page=` | The session list, date-filtered. |
| `GET` | `/api/sessions/:id/track` | Points for the polyline. |
| `DELETE` | `/api/sessions/:id` | Soft-delete + purge points. |
| `PATCH` | `/api/devices/:id` | Rename a device (`label`). |

Geofence routes live in [§6.3](#63-dashboard-api), with the device-facing pair in §6.4–6.5.

### 5.1 Devices

```ts
app.get('/api/devices', async (_req, res) => {
  const devices = await prisma.device.findMany({
    orderBy: { lastSeen: 'desc' },
    select: {
      id: true, label: true, lastSeen: true,
      _count: { select: { sessions: { where: { deletedAt: null } } } },
    },
  });
  res.json(devices.map((d) => ({
    id: d.id,
    label: d.label ?? d.id,
    lastSeen: d.lastSeen,
    sessionCount: d._count.sessions,
  })));
});
```

> **A note on `device_id`.** It is whatever the host app put in `SyncConfig.extraParams`.
> The bundled sample uses `Build.BRAND`, which is a **brand name** — every Samsung in the
> fleet collides into one "device". Tell integrating hosts to send a per-install UUID
> (generated once, persisted in `SharedPreferences`). Without a stable, unique `device_id`,
> device selection cannot work, and there is no server-side fix.

### 5.2 Sessions, with the date filter

The filter has to name its clock. `startedAt` is the device's wall clock — what the user
means by "the day I drove" — while `createdAt` is when your server received it, which for an
offline backlog can be days later. Offer both; default to `startedAt`.

```ts
app.get('/api/sessions', async (req, res) => {
  const { deviceId, from, to, dateField = 'startedAt', page = '0' } =
    req.query as Record<string, string>;

  const field = dateField === 'createdAt' ? 'createdAt' : 'startedAt';
  const range: any = {};
  if (from) range.gte = new Date(`${from}T00:00:00.000Z`);
  if (to)   range.lte = new Date(`${to}T23:59:59.999Z`);

  const where = {
    deletedAt: null,                                  // tombstones are never listed
    ...(deviceId ? { deviceId } : {}),
    ...(from || to ? { [field]: range } : {}),
  };

  const take = 50, skip = Number(page) * 50;
  const [rows, total] = await Promise.all([
    prisma.session.findMany({
      where, take, skip,
      orderBy: { startedAt: 'desc' },                 // never order by id (§2.6)
      select: {
        id: true, deviceId: true, startedAt: true, endedAt: true,
        pointCount: true, distanceM: true, timezone: true,
        localDate: true, createdAt: true,
      },
    }),
    prisma.session.count({ where }),
  ]);

  res.json({ sessions: rows, total, page: Number(page), pageSize: take });
});
```

A session with `startedAt = null` has no points yet — created by a batch whose rows were all
duplicates or all tombstoned. It sorts last and renders as "no points".

### 5.3 The track

```ts
app.get('/api/sessions/:id/track', async (req, res) => {
  const session = await prisma.session.findUnique({ where: { id: req.params.id } });
  if (!session || session.deletedAt) return res.status(404).json({ error: 'not found' });

  const points = await prisma.point.findMany({
    where: { sessionId: session.id },
    orderBy: { time: 'asc' },                         // the @@index([sessionId, time])
    select: {
      uuid: true, time: true, latitude: true, longitude: true, accuracy: true,
      speedMps: true, movementStatus: true, providerName: true,
      batteryPct: true, isCharging: true, isMock: true, integritySignals: true,
    },
  });

  res.json({
    session,
    // GeoJSON order is [lng, lat]; Leaflet's is [lat, lng]. Send Leaflet order explicitly
    // rather than making the client guess — a swap puts the track in the ocean.
    path: points.map((p) => [p.latitude, p.longitude]),
    points,
    bounds: bbox(points),
  });
});
```

For sessions past ~20 000 points, downsample server-side (Douglas–Peucker at ~5 m, or take
every *n*th point) and send the full set only on demand. Leaflet renders a 50 000-vertex
polyline slowly, and the difference is invisible at city zoom.

### 5.4 Delete

```ts
app.delete('/api/sessions/:id', async (req, res) => {
  const session = await prisma.session.findUnique({ where: { id: req.params.id } });
  if (!session || session.deletedAt) return res.status(404).json({ error: 'not found' });

  await prisma.$transaction([
    prisma.point.deleteMany({ where: { sessionId: session.id } }),
    // Soft delete: the row survives as a tombstone so an offline device's later batch
    // cannot recreate the session (§2.7).
    prisma.session.update({
      where: { id: session.id },
      data: { deletedAt: new Date(), pointCount: 0, distanceM: 0 },
    }),
  ]);

  res.status(204).end();
});
```

> **Confirm in the UI before calling this.** Points are hard-deleted, and the device has
> already marked them synced — there is no re-upload, no undo, and no copy anywhere else.

---

## 6. Geofences

### 6.1 The SDK does not sync geofences. At all.

Before writing a line of this: **`fieldtrack-sync` contains no geofence code.** Not a push,
not a pull, not a crossing upload. The module uploads `location` rows and stops.

What does exist is split across two places that never meet:

| Where | What it does |
|---|---|
| `fieldtrack-core` — `Tracker.addGeofence(...)` | Registers a fence with Play Services. Up to **19** host fences; the SDK's own stationary wake fence uses a reserved slot and does not count. |
| `fieldtrack-core` — `Tracker.getGeofenceEvents(...)` | Reads back crossings the SDK persisted on the device. Survives process death; also emitted live as `TrackerEvent.GeofenceEntered` / `GeofenceExited`. |
| `fieldtrack-sync` | Nothing. |

So the wiring is yours to write, in both directions:

```
  ┌────────────┐   1. define a fence      ┌──────────────┐
  │ Dashboard  │ ───────────────────────▶ │              │
  └────────────┘                          │   Backend    │
                                          │              │
  ┌────────────┐   2. GET /v1/geofences   │ (source of   │
  │ Host app   │ ◀─────────────────────── │   truth)     │
  │     │      │                          │              │
  │     ▼      │   3. POST crossings      │              │
  │ tracker    │ ───────────────────────▶ │              │
  │ .addGeo…() │        (host code)       └──────────────┘
  └────────────┘
        ▲
        │ 4. TrackerEvent.GeofenceEntered / getGeofenceEvents()
        └── SDK, on the device, offline-capable
```

Steps 2 and 3 are host app code — a plain `fetch`/Retrofit call in the integrating app, not
something the SDK does for you. Points still flow through `fieldtrack-sync` untouched; none
of this changes §4.

**Consequence for the dashboard:** a fence you draw is not live until the device next polls
and calls `addGeofence`. Show that state in the UI (§7.3) rather than implying the fence is
armed the moment it is saved.

### 6.2 Validation, mirrored from the SDK

`Tracker.addGeofence` returns `TrackerResult.Error(INVALID_CONFIG, "Invalid geofence")` for
any of the rules below, and from a dashboard user's point of view it fails *silently* — a
fence that fails validation is simply never armed, hours later, on a device nobody is
watching. Reject at save time instead, where a human is present to read the message.

| Rule | SDK behaviour if violated |
|---|---|
| `id` not blank | `INVALID_CONFIG` |
| `latitude` in `-90..90` | `INVALID_CONFIG` |
| `longitude` in `-180..180` | `INVALID_CONFIG` |
| `radiusM > 0` | `INVALID_CONFIG` |
| At most 19 fences per device | `GEOFENCE_LIMIT_REACHED` — the *20th* `addGeofence` fails; the first 19 stay armed |
| `fenceId` ≠ `"fieldtrack-stationary"` | Not an SDK error — worse. `addGeofence` is register-**or-replace**, so this silently overwrites the SDK's internal stationary wake fence and degrades its stationary detection. |

```ts
// src/geofence/validate.ts
export const RESERVED_FENCE_IDS = ['fieldtrack-stationary', 'trackit-stationary'];
export const MAX_FENCES_PER_DEVICE = 19;
/// Play Services stops firing reliably much below this: GPS accuracy in a city is 10–30 m,
/// so a 30 m fence is mostly noise. Not an SDK rule — a physics one.
export const MIN_PRACTICAL_RADIUS_M = 100;

export function validateFence(f: FenceInput): string | null {
  if (!f.fenceId?.trim())                           return 'fenceId must not be blank';
  if (RESERVED_FENCE_IDS.includes(f.fenceId))       return `fenceId "${f.fenceId}" is reserved by the SDK`;
  if (!(f.latitude  >= -90  && f.latitude  <= 90))  return 'latitude out of range';
  if (!(f.longitude >= -180 && f.longitude <= 180)) return 'longitude out of range';
  if (!(f.radiusM > 0))                             return 'radiusM must be greater than 0';
  return null;
}
```

> The published `INTEGRATION-GUIDE.md` §11 says `DEFAULT_ID = "trackit-stationary"`; the code
> says `"fieldtrack-stationary"` (`TrackerGeofence.DEFAULT_ID`). Trust the code, and block
> **both** strings — a host on an older SDK may still be using the other one.

### 6.3 Dashboard API

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/geofences?deviceId=` | Fences applying to a device — its own plus the global ones. |
| `POST` | `/api/geofences` | Create. |
| `PATCH` | `/api/geofences/:id` | Move, resize, rename, enable/disable. |
| `DELETE` | `/api/geofences/:id` | Soft-delete; stops being distributed. |
| `GET` | `/api/geofences/:id/events?from=&to=` | Crossing history for one fence. |
| `GET` | `/api/devices/:id/geofence-events?from=&to=` | Crossings for the map's date range. |

```ts
// src/routes/geofences.ts
app.get('/api/geofences', async (req, res) => {
  const { deviceId } = req.query as Record<string, string>;
  const fences = await prisma.geofence.findMany({
    where: {
      deletedAt: null,
      // A global fence (device_id NULL) applies everywhere. Selecting a device shows its
      // own fences AND the global ones, because that is what the device will actually arm.
      ...(deviceId ? { OR: [{ deviceId }, { deviceId: null }] } : {}),
    },
    orderBy: { createdAt: 'desc' },
  });
  res.json(fences);
});

app.post('/api/geofences', async (req, res) => {
  const error = validateFence(req.body);
  if (error) return res.status(422).json({ error });   // a human reads this; 4xx is fine here

  const { deviceId } = req.body;
  if (deviceId) {
    const n = await prisma.geofence.count({
      where: { deviceId, deletedAt: null, enabled: true },
    });
    // The 20th addGeofence is the one that fails, and it fails on the device, hours later,
    // where nobody sees it. Fail here instead.
    if (n >= MAX_FENCES_PER_DEVICE)
      return res.status(422).json({ error: `a device may hold ${MAX_FENCES_PER_DEVICE} fences` });
  }

  const fence = await prisma.$transaction(async (tx) => {
    const created = await tx.geofence.create({ data: req.body });
    await bumpRevision(tx, deviceId);
    return created;
  });
  res.status(201).json(fence);
});

app.patch('/api/geofences/:id', async (req, res) => {
  const existing = await prisma.geofence.findUnique({ where: { id: req.params.id } });
  if (!existing || existing.deletedAt) return res.status(404).json({ error: 'not found' });

  const error = validateFence({ ...existing, ...req.body });
  if (error) return res.status(422).json({ error });

  const fence = await prisma.$transaction(async (tx) => {
    const updated = await tx.geofence.update({ where: { id: req.params.id }, data: req.body });
    await bumpRevision(tx, updated.deviceId);
    return updated;
  });
  res.json(fence);
});

app.delete('/api/geofences/:id', async (req, res) => {
  await prisma.$transaction(async (tx) => {
    // Soft delete. A hard delete cascades the crossing history away with it, and that
    // history is usually the reason the fence existed.
    const deleted = await tx.geofence.update({
      where: { id: req.params.id },
      data: { deletedAt: new Date(), enabled: false },
    });
    await bumpRevision(tx, deleted.deviceId);
  });
  res.status(204).end();
});

/// A global fence (deviceId null) changes what every device should be arming.
async function bumpRevision(tx: Tx, deviceId: string | null) {
  await tx.device.updateMany({
    where: deviceId ? { id: deviceId } : {},
    data:  { geofenceRevision: { increment: 1 } },
  });
}
```

### 6.4 Distributing fences to the device

The device pulls; the server never pushes. There is no channel to push over — the SDK opens
no socket of its own, and adding FCM so a fence arrives two minutes sooner is rarely worth
the moving parts.

```ts
// GET /v1/geofences?device_id=…&known_revision=7
app.get('/v1/geofences', async (req, res) => {
  const { device_id, known_revision } = req.query as Record<string, string>;

  const device = await prisma.device.findUnique({ where: { id: String(device_id) } });
  // A device that has not uploaded a point yet is unknown here. Answer "no fences", not
  // 404: the host will call this before its first upload on a fresh install.
  if (!device) return res.status(200).json({ revision: 0, geofences: [] });

  // The whole point of the revision. A device polling every fifteen minutes asks a
  // question whose answer is almost always "nothing changed", and re-registering 19 fences
  // with Play Services for no reason is not free.
  if (known_revision && Number(known_revision) === device.geofenceRevision)
    return res.status(304).end();

  const fences = await prisma.geofence.findMany({
    where: {
      deletedAt: null, enabled: true,
      OR: [{ deviceId: device.id }, { deviceId: null }],
    },
    orderBy: { createdAt: 'asc' },
    take: MAX_FENCES_PER_DEVICE,   // never hand a device a list it cannot arm
  });

  res.json({
    revision: device.geofenceRevision,
    // Shaped as TrackerGeofence so the host maps it one to one, with no field guessing.
    geofences: fences.map((f) => ({
      id: f.fenceId,
      latitude: f.latitude,
      longitude: f.longitude,
      radiusM: f.radiusM,
      onEnterEvent: f.onEnterEvent,
      onExitEvent: f.onExitEvent,
    })),
  });
});
```

Host-side, in the integrating app — again, the SDK does not do this for you:

```kotlin
// Host app code. Run after ready(), on session start, and from a periodic worker.
suspend fun syncGeofences(tracker: Tracker, api: MyApi, deviceId: String) {
    // 304 -> null: nothing changed, arm nothing.
    val remote = api.geofences(deviceId, knownRevision = prefs.geofenceRevision) ?: return
    val wanted = remote.geofences.associateBy { it.id }

    // Remove fences the server dropped. NEVER touch the SDK's reserved fence: getGeofences()
    // includes it, and removing it breaks the stationary wake path.
    tracker.getGeofences()
        .filter { it.id != TrackerGeofence.DEFAULT_ID && it.id !in wanted }
        .forEach { tracker.removeGeofence(it.id) }

    // addGeofence is register-or-replace, so this covers both new and moved fences.
    var allArmed = true
    wanted.values.forEach { f ->
        val result = tracker.addGeofence(
            TrackerGeofence(
                id = f.id,
                latitude = f.latitude,
                longitude = f.longitude,
                radiusM = f.radiusM,
                onEnterEvent = f.onEnterEvent,
                onExitEvent = f.onExitEvent,
            ),
        )
        if (result is TrackerResult.Error) {
            allArmed = false
            reportFenceFailure(f.id, result.code)
        }
    }

    // Only after every fence armed. Storing it earlier means a failed registration is never
    // retried, because the next poll answers 304.
    if (allArmed) prefs.geofenceRevision = remote.revision
}
```

Three failure modes worth handling on the host, because the server cannot see any of them:

- `GEOFENCE_LIMIT_REACHED` — the device already holds 19. Your API caps at 19, but a host
  that also registers fences of its own can still reach it.
- `GEOFENCE_REGISTRATION_FAILED` — Play Services refused, usually because background
  location was denied. Retry later; do not advance the stored revision.
- **Background location revoked after arming.** The fences stay registered and stop firing,
  and nothing reports it. `Tracker.state.providerState.permission` is where a host checks.

### 6.5 Receiving crossings

`TrackerGeofenceEvent` is:

```kotlin
data class TrackerGeofenceEvent(
    val geofence: TrackerGeofence,        // the fence AS ARMED — centre and radius included
    val transition: GeofenceTransition,   // ENTER | EXIT
    val timestampMs: Long,                // device wall clock
    val eventName: String,                // onEnterEvent / onExitEvent
)
```

**There is no uuid.** Unlike a point, a crossing carries no server-friendly identity, so a
host retrying a failed upload duplicates it unless you derive one. Compose it from the
fields that are stable across a retry:

```ts
// src/geofence/crossingId.ts
import { createHash } from 'node:crypto';

/// Deterministic, so a retried upload collides instead of duplicating — deliberately the
/// same shape as the SDK's own point uuid (SHA-1 over a joined key), for the same reason.
export const crossingId = (c: Crossing) =>
  createHash('sha1')
    .update(`${c.device_id}:${c.fence_id}:${c.transition}:${c.timestamp_ms}`)
    .digest('hex');
```

`timestamp_ms` is what keeps it unique: the same device can legitimately enter the same
fence twice in a day, and both crossings must survive.

```ts
// POST /v1/geofence-events   { device_id, events: [ … ] }
app.post('/v1/geofence-events', async (req, res) => {
  const { device_id, events } = req.body ?? {};
  if (!Array.isArray(events)) return res.status(200).json({ accepted: 0 });

  const rows = [];
  for (const e of events) {
    // Match on (device, fenceId) — the id the device armed, not your primary key, which
    // the device has never seen.
    const fence = await prisma.geofence.findFirst({
      where: { fenceId: e.fence_id, OR: [{ deviceId: device_id }, { deviceId: null }] },
    });
    // An orphan crossing: the fence was deleted between arming and reporting. Dropping it
    // loses the one record that a worker did visit the site, so quarantine rather than
    // discard.
    if (!fence) { await quarantineOrphanCrossing(device_id, e); continue; }

    rows.push({
      id: crossingId({ device_id, ...e }),
      geofenceId: fence.id,
      deviceId: device_id,
      fenceId: e.fence_id,
      transition: e.transition,
      eventName: e.event_name,
      occurredAt: new Date(e.timestamp_ms),
      // From the event, not from the fence row: the fence may have been moved since.
      latitude: e.latitude, longitude: e.longitude, radiusM: e.radius_m,
    });
  }

  const { count } = await prisma.geofenceEvent.createMany({ data: rows, skipDuplicates: true });
  res.status(200).json({ accepted: count, duplicates: rows.length - count });
});
```

Host-side, the same two-path shape the SDK's own sample uses for crossings — the live event
is a doorbell, the store is the payload:

```kotlin
// Application.onCreate, NOT a view model: a fence is crossed with the app in a pocket, and
// StationaryFenceReceiver is a manifest receiver, so the system may build the process for
// the broadcast and let it die again shortly after.
scope.launch {
    tracker.events
        .onSubscription { uploadPendingCrossings() }   // catch up on anything missed
        .collect {
            if (it is TrackerEvent.GeofenceEntered || it is TrackerEvent.GeofenceExited)
                uploadPendingCrossings()
        }
}

private suspend fun uploadPendingCrossings() {
    val crossings = tracker.getGeofenceEvents(fromMs = prefs.lastCrossingUploadMs, limit = 500)
    if (crossings.isEmpty()) return
    if (api.postCrossings(crossings).isSuccess) {
        // Advance the watermark only on success. deleteGeofenceEvents() is available if you
        // would rather prune than watermark — but never before the upload confirms.
        prefs.lastCrossingUploadMs = crossings.maxOf { it.timestampMs } + 1
    }
}
```

> **This endpoint is not `fieldtrack-sync`.** No SDK is reading its status code, so the
> destructive 401/403 semantics of §2 do not apply to it — return whatever your host code
> handles. Keep the two endpoints separate for exactly that reason: a shared handler
> eventually returns a dashboard-shaped 400 to the point ingest and starts a retry loop
> (§2.3).

---

## 7. React screen

One screen. Left rail: device selector, date filter, and two tabs — sessions and
geofences. Right: the map, showing the selected track and every fence for the device.

```
┌──────────────────────────────────────────────────────────────────────────┐
│  FieldTrack                                        [ Device ▾ ]          │
├───────────────────────────┬──────────────────────────────────────────────┤
│  From [2026-09-01]        │              ╭╌╌╌╌╌╌╌╌╮                      │
│  To   [2026-09-07]        │             ╱  Depot   ╲   ← geofence        │
│  ○ Recorded  ● Received   │            │  r=200 m   │                    │
│                           │             ╲          ╱                     │
│  [ Sessions ] [ Fences ]  │              ╰╌╌╌╌╌╌╌╌╯    ╭─── ● end        │
│  ── Sessions (12) ────    │          ╭─────────────────╯                 │
│  ▸ 20260907-143512  🗑    │       ●──╯ start                             │
│    07 Sep 14:35 · 8.2 km  │                                              │
│    412 pts · 1h 12m       │        [OpenStreetMap tiles]                 │
│  ▸ 20260906-091200  🗑    │                                              │
│    06 Sep 09:12 · 2.1 km  │   [ + Add fence ]  ← then click the map      │
└───────────────────────────┴──────────────────────────────────────────────┘
```

Fences render on the map whichever tab is open — a track is usually read *against* the
places that matter, and hiding them behind a tab makes the one question the screen exists
to answer ("did they get to the depot?") take two clicks.

### 7.1 The screen

```tsx
// src/TrackingScreen.tsx
import { useEffect, useState } from 'react';
import { MapContainer, TileLayer } from 'react-leaflet';
import 'leaflet/dist/leaflet.css';
import { TrackLayer } from './components/TrackLayer';
import { GeofenceLayer } from './components/GeofenceLayer';

export default function TrackingScreen() {
  const [devices, setDevices]     = useState<Device[]>([]);
  const [deviceId, setDeviceId]   = useState('');
  const [from, setFrom]           = useState(isoDaysAgo(7));
  const [to, setTo]               = useState(isoToday());
  const [dateField, setDateField] = useState<'startedAt' | 'createdAt'>('startedAt');
  const [sessions, setSessions]   = useState<Session[]>([]);
  const [selected, setSelected]   = useState<string | null>(null);
  const [track, setTrack]         = useState<Track | null>(null);

  const [tab, setTab]             = useState<'sessions' | 'fences'>('sessions');
  const [fences, setFences]       = useState<Geofence[]>([]);
  /// null = not placing. Non-null = the next map click sets this fence's centre (§7.3).
  const [draft, setDraft]         = useState<FenceDraft | null>(null);

  useEffect(() => {
    fetch('/api/devices').then((r) => r.json()).then((d) => {
      setDevices(d);
      if (d.length && !deviceId) setDeviceId(d[0].id);   // auto-select the most recent
    });
  }, []);

  useEffect(() => {
    if (!deviceId) return;
    const q = new URLSearchParams({ deviceId, from, to, dateField });
    fetch(`/api/sessions?${q}`).then((r) => r.json()).then((d) => {
      setSessions(d.sessions);
      // The selected session may not survive a filter change.
      if (!d.sessions.some((s: Session) => s.id === selected)) {
        setSelected(null);
        setTrack(null);
      }
    });
  }, [deviceId, from, to, dateField]);

  useEffect(() => {
    if (!selected) { setTrack(null); return; }
    fetch(`/api/sessions/${selected}/track`).then((r) => r.json()).then(setTrack);
  }, [selected]);

  // Fences follow the device, not the date filter: a fence is a place, not an event.
  useEffect(() => {
    if (!deviceId) { setFences([]); return; }
    fetch(`/api/geofences?deviceId=${deviceId}`).then((r) => r.json()).then(setFences);
  }, [deviceId]);

  async function saveFence(fence: FenceDraft) {
    const res = await fetch(fence.id ? `/api/geofences/${fence.id}` : '/api/geofences', {
      method: fence.id ? 'PATCH' : 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ ...fence, deviceId: fence.global ? null : deviceId }),
    });
    if (!res.ok) {
      // 422 from the validator (§6.2). Show the message — the alternative is a fence that
      // silently never arms on the device.
      alert((await res.json()).error);
      return;
    }
    const saved = await res.json();
    setFences((prev) => [saved, ...prev.filter((f) => f.id !== saved.id)]);
    setDraft(null);
  }

  async function deleteFence(id: string) {
    if (!confirm('Delete this geofence? Devices stop watching it at their next poll.')) return;
    await fetch(`/api/geofences/${id}`, { method: 'DELETE' });
    setFences((prev) => prev.filter((f) => f.id !== id));
  }

  async function deleteSession(id: string) {
    const ok = confirm(
      `Delete session ${id}?\n\n` +
      'Its points are removed permanently. The device has already marked them ' +
      'uploaded, so they cannot be recovered.',
    );
    if (!ok) return;
    await fetch(`/api/sessions/${id}`, { method: 'DELETE' });
    setSessions((prev) => prev.filter((s) => s.id !== id));
    if (selected === id) { setSelected(null); setTrack(null); }
  }

  return (
    <div className="screen">
      <header>
        <h1>FieldTrack</h1>
        <select value={deviceId} onChange={(e) => setDeviceId(e.target.value)}>
          {devices.map((d) => (
            <option key={d.id} value={d.id}>
              {d.label} — {d.sessionCount} sessions
            </option>
          ))}
        </select>
      </header>

      <aside>
        <div className="filters">
          <label>From <input type="date" value={from} onChange={(e) => setFrom(e.target.value)} /></label>
          <label>To   <input type="date" value={to}   onChange={(e) => setTo(e.target.value)} /></label>
          {/* Device clock vs server clock. A backlog uploaded on Friday was recorded on
              Tuesday — the two filters give different answers on purpose (§5.2). */}
          <fieldset>
            <legend>Filter by</legend>
            <label>
              <input type="radio" checked={dateField === 'startedAt'}
                     onChange={() => setDateField('startedAt')} /> Recorded
            </label>
            <label>
              <input type="radio" checked={dateField === 'createdAt'}
                     onChange={() => setDateField('createdAt')} /> Received
            </label>
          </fieldset>
        </div>

        <nav className="tabs">
          <button className={tab === 'sessions' ? 'on' : ''}
                  onClick={() => setTab('sessions')}>Sessions</button>
          <button className={tab === 'fences' ? 'on' : ''}
                  onClick={() => setTab('fences')}>Fences ({fences.length}/19)</button>
        </nav>

        {tab === 'fences' && (
          <FencePanel
            fences={fences}
            draft={draft}
            onStartPlacing={() => setDraft({ name: '', radiusM: 200, global: false })}
            onChangeDraft={setDraft}
            onSave={saveFence}
            onDelete={deleteFence}
          />
        )}

        <ul className="sessions" hidden={tab !== 'sessions'}>
          {sessions.map((s) => (
            <li key={s.id}
                className={s.id === selected ? 'selected' : ''}
                onClick={() => setSelected(s.id)}>
              <div className="id">{s.id}</div>
              <div className="meta">
                {s.startedAt ? fmt(s.startedAt, s.timezone) : 'no points'}
                {' · '}{(s.distanceM / 1000).toFixed(1)} km
                {' · '}{s.pointCount} pts
                {' · '}{duration(s.startedAt, s.endedAt)}
              </div>
              <button className="delete"
                      onClick={(e) => { e.stopPropagation(); deleteSession(s.id); }}>
                🗑
              </button>
            </li>
          ))}
          {!sessions.length && <li className="empty">No sessions in this range.</li>}
        </ul>
      </aside>

      <main>
        <MapContainer center={[23.0225, 72.5714]} zoom={12} style={{ height: '100%' }}>
          <TileLayer
            url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png"
            attribution="&copy; OpenStreetMap contributors"
          />
          {/* Fences under the track: the track is what you are reading, the fences are
              the context you read it against. */}
          <GeofenceLayer
            fences={fences}
            draft={draft}
            onPlace={(latlng) => setDraft((d) => d && { ...d, ...latlng })}
            onSelect={(f) => { setTab('fences'); setDraft(f); }}
          />
          {track && <TrackLayer track={track} />}
        </MapContainer>
      </main>
    </div>
  );
}
```

### 7.2 The track layer

```tsx
// src/components/TrackLayer.tsx
import { useEffect } from 'react';
import { Polyline, CircleMarker, Popup, useMap } from 'react-leaflet';

export function TrackLayer({ track }: { track: Track }) {
  const map = useMap();

  // Refit on every session change, not once on mount — otherwise the second session you
  // click renders off-screen at the first session's zoom.
  useEffect(() => {
    if (track.path.length) map.fitBounds(track.path as any, { padding: [40, 40] });
  }, [track.session.id]);

  const first = track.points[0];
  const last  = track.points[track.points.length - 1];

  return (
    <>
      <Polyline positions={track.path} pathOptions={{ weight: 4, opacity: 0.85 }} />

      {/* Mock and low-accuracy points are worth showing, not hiding: they are usually the
          explanation for a track that looks wrong. */}
      {track.points
        .filter((p) => p.isMock || p.accuracy > 50)
        .map((p) => (
          <CircleMarker key={p.uuid} center={[p.latitude, p.longitude]}
                        radius={5} pathOptions={{ color: p.isMock ? 'red' : 'orange' }}>
            <Popup>
              {new Date(p.time).toLocaleString()}<br />
              ±{p.accuracy.toFixed(0)} m · {p.speedMps.toFixed(1)} m/s · {p.providerName}
              {p.isMock && <><br /><b>mock fix</b></>}
              {p.integritySignals.length > 0 && <><br />{p.integritySignals.join(', ')}</>}
            </Popup>
          </CircleMarker>
        ))}

      {first && <CircleMarker center={[first.latitude, first.longitude]}
                              radius={8} pathOptions={{ color: 'green' }} />}
      {last  && <CircleMarker center={[last.latitude, last.longitude]}
                              radius={8} pathOptions={{ color: 'black' }} />}
    </>
  );
}
```

### 7.3 The geofence layer and editor

Drawing a fence is two gestures, not a drawing tool: **click the map to place the centre,
type the radius.** A drag-to-size circle looks better in a demo and is worse in use — a
radius the operator can read and repeat ("200 m") is the thing that has to match what the
device arms, and Play Services is unreliable below ~100 m either way (§6.2).

```tsx
// src/components/GeofenceLayer.tsx
import { Circle, Tooltip, useMapEvents } from 'react-leaflet';

export function GeofenceLayer({ fences, draft, onPlace, onSelect }: Props) {
  // Only capture clicks while placing. A permanent map-click handler makes every
  // stray click on the track create a fence.
  useMapEvents({
    click(e) {
      if (draft) onPlace({ latitude: e.latlng.lat, longitude: e.latlng.lng });
    },
  });

  return (
    <>
      {fences.map((f) => (
        <Circle
          key={f.id}
          center={[f.latitude, f.longitude]}
          // Leaflet's Circle radius is METRES (CircleMarker's is pixels). Getting these
          // two components confused gives you a fence that resizes as you zoom.
          radius={f.radiusM}
          pathOptions={{
            color: f.color,
            // A disabled fence is still shown — it is the reason no crossings appear —
            // but drawn so it cannot be mistaken for an armed one.
            dashArray: f.enabled ? undefined : '6 6',
            fillOpacity: f.enabled ? 0.12 : 0.04,
          }}
          eventHandlers={{ click: () => onSelect(f) }}
        >
          <Tooltip>
            {f.name} · {f.radiusM} m
            {!f.deviceId && ' · all devices'}
            {!f.enabled && ' · disabled'}
          </Tooltip>
        </Circle>
      ))}

      {/* The draft, once a centre has been clicked. Rendered separately so an in-progress
          fence never looks saved. */}
      {draft?.latitude != null && (
        <Circle
          center={[draft.latitude, draft.longitude!]}
          radius={draft.radiusM}
          pathOptions={{ color: '#f59e0b', dashArray: '4 4', fillOpacity: 0.15 }}
        />
      )}
    </>
  );
}
```

The panel is a plain form. The two fields that are not obvious:

```tsx
// src/components/FencePanel.tsx  (the parts that matter)
<label>
  Radius (m)
  <input type="number" min={50} step={10} value={draft.radiusM}
         onChange={(e) => onChangeDraft({ ...draft, radiusM: Number(e.target.value) })} />
  {draft.radiusM < 100 && (
    <small className="warn">
      Below ~100 m Play Services fires unreliably — city GPS accuracy is 10–30 m.
    </small>
  )}
</label>

<label>
  <input type="checkbox" checked={draft.global}
         onChange={(e) => onChangeDraft({ ...draft, global: e.target.checked })} />
  Apply to every device
  {/* A global fence counts against all 19 slots on every device, not just this one. */}
</label>
```

**Show that a saved fence is not yet armed.** The device learns about it on its next poll,
which can be fifteen minutes away or, for a phone in a basement, tomorrow. A UI that says
nothing here trains the operator to believe a fence is live the moment it is saved, and the
first missed crossing gets blamed on the SDK:

```tsx
<div className="fence-status">
  Saved. Devices arm this at their next poll —
  {device.geofenceRevision === device.lastAckedRevision
    ? ' armed on this device.'
    : ' not yet confirmed by this device.'}
</div>
```

That comparison needs the host app to report back the revision it successfully armed. It is
one extra field on the crossing upload, or its own tiny endpoint; without it, "is this fence
actually live?" is a question the dashboard cannot answer at all.

### 7.4 Crossings on the map

Crossings are the payoff, and they are cheap to draw: each one is already a lat/lng.

```tsx
{crossings.map((c) => (
  <CircleMarker key={c.id} center={[c.latitude, c.longitude]} radius={6}
                pathOptions={{ color: c.transition === 'ENTER' ? '#16a34a' : '#dc2626' }}>
    <Popup>
      {c.transition === 'ENTER' ? 'Entered' : 'Left'} {c.fenceName}<br />
      {new Date(c.occurredAt).toLocaleString()}
    </Popup>
  </CircleMarker>
))}
```

Two things to get right:

- **Draw the crossing at the fence's stored centre, not at today's.** The row carries the
  fence's `latitude`/`longitude`/`radiusM` as armed (§3.1); using the live fence row would
  silently relocate last month's arrivals when someone nudges the fence.
- **A crossing has no matching track point.** Play Services fires the transition; the SDK
  does not necessarily store a fix at that instant, and under `MOTION_ONLY` on a parked
  device it usually does not. Do not try to join crossings onto the polyline — render them
  as their own layer and let them sit off the line.

### 7.5 Frontend gotchas

| Gotcha | Fix |
|---|---|
| Coordinate order | Leaflet wants `[lat, lng]`; GeoJSON wants `[lng, lat]`. A swap puts Ahmedabad in the Indian Ocean. The `/track` response sends `path` in Leaflet order for exactly this reason. |
| Leaflet CSS | `import 'leaflet/dist/leaflet.css'`, or the map renders as scrambled tiles. |
| Map height | Leaflet needs an explicit pixel height, or `100%` of a sized parent. `height: 100%` inside an unsized div gives a 0 px map. |
| Timestamps | `startedAt` is a device wall clock in **its** zone. Render with `session.timezone`, not the browser's, or a track recorded in Kolkata reads five and a half hours wrong in London. |
| Stale selection | Changing device or date range can remove the selected session from the list. Clear the selection when it is no longer present (handled above). |
| Big tracks | Downsample server-side past ~20 000 points (§5.3). |
| `Circle` vs `CircleMarker` | `Circle`'s radius is **metres**; `CircleMarker`'s is **pixels**. A fence built from `CircleMarker` changes size as you zoom and matches nothing the device armed. |
| Map click handler | Register it only while placing a fence, or every click on the track starts a new one. |
| Fence edits | `PATCH` bumps the device's revision, so every edit costs a re-registration of that device's whole fence set. Batch a move and a resize into one save. |
| "Saved" ≠ "armed" | The device arms fences at its next poll. Say so in the UI (§7.3), or the first missed crossing is blamed on the SDK. |

---

## 8. Case matrix

Every row here is reachable from `fieldtrack-sync`.

### 8.1 Transport-level

| Case | Server behaviour |
|---|---|
| Batch arrives twice (retry after a lost 2xx) | `ON CONFLICT (uuid) DO NOTHING`. Return 200 with `duplicates > 0`. |
| Batch arrives while the first is still committing | Two concurrent transactions on the same uuids. Let the unique constraint arbitrate; do not take an advisory lock. |
| `Content-Encoding: gzip` | Decompress. `express.json({ inflate: true })` handles it. |
| 1000-row batch (`batchSize` max) | Bulk insert in one statement. Must commit inside the 30 s read timeout. |
| Client read timeout at 30 s *after* your commit | The device re-sends. Dedupe absorbs it. Never let ingest get slow enough for this to be routine. |
| Non-`POST` verb (`SyncConfig.method`) | Route the verb the host configured, or the request 404s into a retry loop. |
| Unknown top-level keys (`extraParams`) | Ignore, but persist in `ingest_batches.envelope`. |
| Unknown per-point keys (newer SDK) | Ignore, but persist in `points.raw`. |
| Body is not JSON / has no `location` array | Record it, return 200. Never 400 (§2.3). |
| `location: []` | Return 200. Not an error — a race between a drain and a queue that emptied. |

### 8.2 Auth

| Case | Return |
|---|---|
| Valid token | 200 |
| Token expired but refreshable by the client | **503** + `Retry-After`. Never 401 — 401 wipes the device queue. |
| Auth service unreachable | **503** + `Retry-After` |
| Token genuinely revoked / user deleted | **401** (intended: stop tracking, clear queue) |
| Token valid, wrong tenant / device deprovisioned | **403** (intended: halt uploads, keep rows) |
| Rate limit hit | **429** + `Retry-After: <seconds>` |
| Maintenance window | **503** + `Retry-After` |
| No `Authorization` header at all | 401 only if you are certain. Both codes are destructive — prefer 403 while an integration is still being debugged. |

### 8.3 Data-level

| Case | Server behaviour |
|---|---|
| `provider` key absent | `provider_recorded = false`, six columns `NULL`. Not an object of `false`. |
| `battery_percentage` absent | `battery_pct = NULL`. Not 0. |
| `is_charging` absent | `is_charging = NULL`. Not false. |
| `detected_activity_start_time: 0` | `NULL`, not `1970-01-01`. |
| `detected_activity_type` absent | `NULL`. Common — activity recognition is optional and often denied. |
| `integrity_flags: 0` + empty signals | Normal. Also what a debuggable build and an integrity-disabled host send. |
| `is_mock: true` | Store it and **surface it in the UI**. A debug build legitimately uploads mock fixes. |
| Row-level `session_id` absent | Fall back to the envelope, then to `unassigned-<device>-<local_date>` (§2.5). |
| Envelope `session_id` disagrees with the row | The row wins, always. |
| One batch spanning three sessions | Normal for an offline backlog. Group by resolved session; recompute stats for all three. |
| Points for a tombstoned session | Drop; count as `rejected`. Do not resurrect (§2.7). |
| A session whose `startedAt` moves backwards | Expected — an older backlog arrives after a newer one. `startedAt`/`endedAt` are recomputed, never incremented. |
| Device clock skewed by hours (`CLOCK_SKEWED`, `AUTO_TIME_DISABLED`) | Store `time` as sent, keep `received_at`, flag the session in the UI. Do not "correct" it. |
| Session crossing a time zone | `time_zone` is per point. The session's is the first point's, for display only. |
| Duplicate `uuid` with **different** coordinates | Impossible by construction (`uuid` = `SHA-1(sessionId:elapsedRealtimeNanos)`). If it happens, a host is rewriting the payload — log it and keep the first. |
| Coordinate `NaN` / out of range | Dead-letter the row; keep the batch. |
| A 40-char uuid that is not hex | Accept. It is an opaque string to you. |
| A UUID v4 session id (older SDK) | Accept. The column is `TEXT` (§2.6). |
| A backlog uploaded a week late | Normal. This is what the SDK exists for. Filter by `startedAt` for "when recorded", `createdAt` for "when received". |

### 8.4 Geofences

None of these come from `fieldtrack-sync` — they are your endpoints and the host's code
(§6), so the failure modes are different in kind from §8.1–8.3.

| Case | Server behaviour |
|---|---|
| Fence saved with `radiusM <= 0` or a bad coordinate | Reject with **422** at save time. The SDK would answer `INVALID_CONFIG` on the device, silently, hours later. |
| `fenceId` = `fieldtrack-stationary` / `trackit-stationary` | Reject. `addGeofence` is register-or-replace and this overwrites the SDK's own stationary wake fence. |
| 20th fence for one device | Reject with 422. On the device it would be `GEOFENCE_LIMIT_REACHED`, and the fence simply never arms. |
| Two fences sharing a `fenceId` on one device | Impossible — `@@unique([deviceId, fenceId])`. Without it the device's second `addGeofence` silently replaces the first. |
| Device polls with a current `known_revision` | **304**, empty body. Do not re-send 19 fences to a device that already has them. |
| Device polls before its first point upload | 200 with `{revision: 0, geofences: []}`. It is not in `devices` yet; a 404 would look like a broken integration. |
| Fence disabled or deleted | Drops out of `/v1/geofences`; the host removes it at the next poll. History is kept — the row is soft-deleted. |
| Crossing arrives for a deleted fence | Quarantine it. It is still evidence that a worker visited the site; dropping it loses the only record. |
| Same crossing uploaded twice (host retry) | Deduped on the derived `crossingId` — `TrackerGeofenceEvent` carries no uuid of its own (§6.5). |
| Same fence entered twice in one day | Two rows. `timestamp_ms` is inside the derived id, which is exactly why. |
| Fence moved after a crossing | The crossing keeps the centre and radius it was armed with. Never re-read them from the fence row. |
| Background location revoked after arming | Fences stay registered and stop firing. Nothing reports it, server-side or otherwise — the host checks `providerState.permission`. |
| Crossing with no nearby track point | Normal. Play Services fires the transition; the SDK does not necessarily store a fix at that moment, and under `MOTION_ONLY` on a parked device it usually does not. |
| Global fence (`device_id` NULL) edited | Bumps the revision on **every** device. Fine, but it is a fleet-wide re-registration — do not do it in a loop. |

---

## 9. Test checklist

The server-side counterpart to `SYNC-MODULE.md` §9.

1. **Payload shape** — point a debug build at `http://10.0.2.2:3000` (cleartext-exempt) and
   log the raw body. Confirm `movementSpeed`, `hasSpeed` and `hasBearing` arrive camelCase.
2. **Dedupe** — replay the same batch three times. `accepted` is non-zero once, then 0;
   `duplicates` climbs; the `points` row count never changes.
3. **Commit-then-ack** — kill the process between the insert and the response. The device
   re-sends; the row count still ends correct.
4. **Gzip** — `gzipRequestBody(true)` on the device. The body decodes; nothing stores as bytes.
5. **1000-row batch** — `batchSize(1000)`. Measure end to end; it must be far inside 30 s.
6. **`provider` absent** — replay a payload with the key removed. Columns are `NULL`,
   `provider_recorded` is false, and the UI does not claim GPS was off.
7. **Per-row vs envelope session id** — send a batch whose rows carry two different
   `session_id`s and whose envelope carries a third. Two sessions are created; the envelope
   value creates nothing.
8. **No session id anywhere** — `includePointSessionId(false)` and no envelope param. Points
   land in `unassigned-<device>-<date>` and are visible on the map.
9. **Tombstone** — delete a session in the UI, then replay one of its batches. `rejected`
   increments; the session does not come back and does not appear in the list.
10. **401 discipline** — grep the codebase for every `401`. Each one must be a genuinely
    revoked credential. This is the single most expensive mistake available here.
11. **429 + `Retry-After: 120`** — the next drain arrives ~120 s later, not 30 s.
12. **503 on database failure** — stop Postgres mid-drain. The device retries, nothing is
    marked synced, and no 401 or 403 escapes.
13. **Bad row** — inject a point with `latitude: null`. It lands in `rejected_points`, the
    other 99 store, the status is 200, and the device does not loop.
14. **Clock skew** — set the device clock two days back. Points store with the skewed `time`,
    `received_at` is correct, and the "Received" date filter still finds the session.
15. **Two devices, same brand** — confirms whether the host is sending a real per-install
    `device_id` or `Build.BRAND` (§5.1). Do this before demoing device selection.

Geofences (§6) — a separate pass, because none of it goes through `fieldtrack-sync`:

16. **Fence reaches the device** — save a fence, poll `/v1/geofences` by hand, then check
    `tracker.getGeofences()` on the device contains it. Until this works, nothing else here
    is testable.
17. **Revision short-circuit** — poll twice with the returned revision. The second answers
    **304**. Then edit the fence and poll again: 200 with the new list.
18. **Reserved id** — try to save `fieldtrack-stationary`. Rejected with 422. Then confirm
    the device's stationary detection still works — this is the one that breaks quietly.
19. **20th fence** — create 19, then one more. 422 from the API. Bypass the API and arm 20
    on the device directly to see `GEOFENCE_LIMIT_REACHED` for yourself.
20. **Real crossing** — walk or mock-drive into a fence with the app backgrounded and the
    device offline. The crossing appears in `tracker.getGeofenceEvents()` immediately and on
    the server once the network returns.
21. **Duplicate crossing** — replay the same crossing upload. `accepted` is 0 the second
    time; one row in `geofence_events`.
22. **Two crossings, same fence, same day** — both survive. If only one does, `timestamp_ms`
    is missing from your derived id.
23. **Move a fence with history** — edit the centre, then re-open the crossing history. The
    old crossings still render at the old centre.
24. **Delete a fence with history** — soft-deleted, drops off the device at the next poll,
    history still readable.
25. **Crossing for a deleted fence** — delete a fence, then replay a crossing for it. It is
    quarantined, not dropped and not 500.

---

## 10. Running it

```
backend/
  prisma/schema.prisma
  src/
    app.ts               # express wiring, 10mb json limit, 503 error handler
    db.ts                # PrismaClient singleton
    auth.ts              # authenticateDevice — the 401/403/503 decision lives here
    routes/ingest.ts     # POST /v1/location/batch
    routes/logs.ts       # POST /v1/logs/batch  +  GET /v1/log-config  (§11)
    routes/dashboard.ts  # /api/devices, /api/sessions, /api/sessions/:id/track, DELETE
    routes/geofences.ts  # /api/geofences CRUD  +  /v1/geofences, /v1/geofence-events
    ingest/
      parsePoint.ts
      parseLogEntry.ts   # §11.10
      resolveSessions.ts
      recomputeStats.ts
    geofence/
      validate.ts        # the SDK's own rules, mirrored (§6.2)
      crossingId.ts      # derived id — TrackerGeofenceEvent has no uuid (§6.5)
frontend/
  src/TrackingScreen.tsx
  src/components/TrackLayer.tsx
  src/components/GeofenceLayer.tsx
  src/components/FencePanel.tsx
  src/api.ts
```

```bash
# backend
npm i express @prisma/client
npm i -D prisma typescript tsx @types/express
npx prisma migrate dev --name init
npx tsx src/app.ts

# frontend
npm create vite@latest frontend -- --template react-ts
npm i leaflet react-leaflet
npm i -D @types/leaflet
npm run dev        # proxy /api and /v1 to the backend in vite.config.ts
```

Device side, pointed at a local backend:

```kotlin
sync.configure(
    SyncConfig.builder()
        .url("http://10.0.2.2:3000/v1/location/batch")  // cleartext-exempt loopback
        .header("Authorization", "Bearer $token")
        .batchSize(100)
        .autoSync(true)
        // Not optional against this backend: it is what makes an offline backlog
        // attributable to the session that actually recorded it (§2.5).
        .includePointSessionId(true)
        .extraParams(mapOf("device_id" to installId))   // a per-install UUID, not Build.BRAND
        .build(),
)
```

Geofences are **not** part of that call and never travel with it. Wire them separately, in
host code (§6.4–6.5):

```kotlin
// After ready(), on session start, and from a periodic worker.
syncGeofences(tracker, api, installId)

// Application.onCreate — crossings are pushed by you, not by fieldtrack-sync.
watchGeofenceCrossings(tracker, api)
```

---

## 11. Session logs

Everything above is about *positions*. This section is about the other half of a field
support ticket: **why** a device produced the positions it did — or produced none.

A track with a twenty-minute hole in it is unreadable on its own. The same hole next to
`CaptureSuspended(LOCATION_DISABLED)` at 14:31 and `CaptureResumed` at 14:52 is a closed
ticket. That is what this endpoint stores.

### 11.1 What a "log" is here

Four device-side sources, one wire format. All four are already public API — nothing in
this section needs a new SDK release.

| Source | Public API | What it answers |
|---|---|---|
| **Event stream** | `Tracker.events` → `TrackerEvent` | What the SDK told the app: `Error`, `PermissionChange`, `ProviderChange`, `CaptureSuspended` / `CaptureResumed`, `LocationServicesChange`, `PowerSaveChange`, `SessionInterrupted`, `IntegrityChange`, geofence crossings, `Diagnostic`. |
| **Decision log** | `Tracker.getDecisions(sessionId)` → `FixDecision` | Why one fix was accepted, skipped or rejected, *with the arithmetic*: `sigma`, `threshold`, `distanceMovedM`, `effectiveSpeedMps`, `motionState`. |
| **Raw points** | `Tracker.getRawPoints(sessionId)` → `RawPoint` | The discarded candidates in point shape — `verdict` + `reason` on the same columns as a stored point, so a gap and the points either side of it read side by side. Needs `persistence.persistRawPoints`. |
| **Host lines** | `Tracker.log(...)` / `Tracker.logLifecycle(...)` | Whatever your app wants on the record. The SDK's own `sdkLog` lines are logcat-only and compiled out of release builds — they do **not** reach this endpoint, and should not. |

The wire format below carries all four. One table, one endpoint, a `type` discriminator.
The first three are recorded by the SDK with no host code at all; the fourth is the host's
own `log()` calls.

### 11.2 The SDK ships this — but on its own endpoint

The device half is implemented, and it lives **entirely in `fieldtrack-sync`**:
`TrackerSync.configureLogs(...)` arms a recorder, its own small Room database buffers the
entries, and a `LogSyncWorker` heartbeat drains it. `fieldtrack-core` carries no logging
code and no log table — a host that never depends on the sync module pays nothing.

The endpoint **follows the points endpoint**, and it is derived for you. `configure()`
builds the log channel from the `SyncConfig` it was just given — the origin of that URL plus
`v1/logs/batch`, `device_id` out of `SyncConfig.extraParams`, and the points headers — so a
backend that implements this contract starts receiving without a second SDK call. Set
`SyncConfig.syncLogs = false` to opt out, or call `configureLogs(...)` to override any of it;
an explicit call wins permanently. If the channel cannot be derived (no `device_id`, an
unparseable URL) the SDK logs why and points continue unaffected. See §11.13.

**What this means for the backend: assume every SDK 1.1+ device that uploads points will
also POST logs, unless its host opted out.** Have the route in place with the same auth the
points route uses before rolling the upgrade out — see §11.11 for volume, which is the part
worth reading twice.

> The implemented server contract is [`APP-LOG-API.md`](APP-LOG-API.md) — read that for the
> field-by-field rules, the auth modes, and the "why is it not saving" table. This section
> is the design behind it.

**Use a separate endpoint, and preferably a separate credential.** Two reasons, both hard:

1. **401 on the points URL is destructive** (§2.2) — it stops tracking and wipes the
   unsent point queue. A log-shipping bug that trips your auth path must not be able to
   destroy positions. Keep the two blast radii apart.
2. **Logs are lossy by design and points are not.** A log ring buffer that overflows drops
   its oldest entries and that is correct behaviour. Applying the same policy to positions
   would be a data-loss bug. Different guarantees belong on different queues.

Do **not** route logs through `TrackerSync.configure(...)`. That is the *points* endpoint:
the `location` key is the batch itself and is reserved, so a second `SyncConfig` pointed at
a log URL would upload positions to it. `configureLogs(...)` takes a [`LogSyncConfig`], is
independent in both directions, and neither call can tear the other down.

### 11.3 The request

`POST /v1/logs/batch`

```
POST /v1/logs/batch HTTP/1.1
Content-Type: application/json; charset=utf-8
Authorization: Bearer <a credential scoped to logs, not the points credential>
Content-Encoding: gzip          <-- recommended; logs compress ~8:1
```

```jsonc
{
  "device_id": "8f14e45f-ceea-467a-9c1a-2b0a1e1f9c31",  // same id as the points envelope
  "session_id": "20260907-143512-1f0c8a2e",             // may be absent — see §11.6
  "uploaded_at": 1719400123456,                         // device wall clock at send

  // Constant for the life of a process. Sent per batch rather than per entry because it
  // is the same 200 bytes on every row otherwise, and it is what a "works on my device"
  // ticket is actually about.
  "app": { "package": "com.acme.field", "version": "3.4.1", "build": 3401, "sdk": "1.9.0" },
  "device": {
    "manufacturer": "samsung", "model": "SM-A546E",
    "os": 34, "fingerprint": "samsung/a54xnaxx/..."
  },

  "logs": [ /* 1..500 entries, oldest first */ ]
}
```

- **Same `device_id` as the points envelope.** This is the whole point of the endpoint: the
  join between a hole in a track and the reason for it. If the two ids differ you have two
  unrelated datasets.
- **`app` and `device` are per batch, not per entry.** Store them on the batch row and on
  the session's first sighting; do not denormalise onto every log line.
- **Cap the batch at 500 entries.** Logs are chattier than points and each one carries a
  free-form `data` object. 500 gzipped entries is roughly 150 KB.

### 11.4 One entry

```jsonc
{
  "id": "3f1a...",            // 40 hex chars. THE dedupe key. Deterministic — see §11.6.
  "session_id": "20260907-143512-1f0c8a2e",   // per row, and authoritative (§2.5 logic)
  "seq": 1482,                // monotonic per session, from 0. Gaps mean dropped entries.
  "time": 1719400000000,      // device wall clock, epoch ms. Display only.
  "elapsed_nanos": "918273645000000", // monotonic since boot. THE ordering key. A STRING.
  "level": "warn",            // debug | info | warn | error
  "type": "event",            // event | decision | message | lifecycle
  "tag": "CaptureGate",       // subsystem. Free text; index it, do not enumerate it.
  "code": "LOCATION_DISABLED",// nullable — ErrorCode name, event class name, or reject reason
  "message": "Suspending capture: Location services unavailable (gps=false, network=false, master=false)",
  "data": { }                 // type-specific, JSONB. Tolerate any shape.
}
```

| Field | Rule |
|---|---|
| `id` | Required, unique, deterministic. Your primary key and your only idempotency mechanism — same rule as `uuid` on a point (§2.4). |
| `elapsed_nanos` | Required, and **sent as a JSON string**. Order on this, not on `time`: the device wall clock can jump backwards mid-session (manual change, NTP correction), `elapsedRealtimeNanos` cannot. It resets to 0 on reboot, which is exactly why `seq` is the tiebreak. It is a string because a boot-relative nanosecond count passes 2^53 after 104 days of uptime, and every JavaScript backend silently rounds past that — the reference parser accepts both forms, so an older server keeps working. |
| `time` | Device wall clock. Store it, render it, never sort by it. Always keep your own `received_at` next to it. |
| `seq` | Per **session and per type**, not per batch and not per process. Entries and decisions come from two tables with two counters, so group by `(session_id, type)` before looking for gaps. A gap is the honest record of a ring-buffer overflow — surface it rather than hiding it (§11.6). |
| `level` | Four values, fixed. Validate leniently. |
| `type` | Four values, fixed. Drives which `data` shape to expect — but never *require* the shape; a newer SDK adds keys. |
| `code` | Nullable and non-unique. `ErrorCode` name for errors, `TrackerEvent` class name for events, the `Reasons` string for decisions. This is what you group a device's week by. |
| `message` | Human text, may be long. Cap at 4 KB server-side and truncate rather than reject. |
| `data` | Store as JSONB verbatim. Never validate its interior — the whole value of a log line is the field you did not think to model. |

### 11.5 The `data` object, per type

**`type: "decision"`** — from `FixDecision` / `RawPoint`. The highest-volume type by far;
see the retention warning in §11.9.

```jsonc
{
  "verdict": "REJECT",              // ACCEPT | SKIP | REJECT
  "reason": "NLP Fallback",         // the Reasons vocabulary, verbatim
  "latitude": 23.0225, "longitude": 72.5714, "accuracy": 48.0,
  "bearing_deg": 0.0, "has_speed": false, "has_bearing": false,
  "filter_lat": 23.0219, "filter_lng": 72.5710,   // where the Kalman filter thought it was
  "sigma": 6.4, "threshold": 4.0,                 // the gate, and the bar it failed
  "distance_moved_m": 287.4,
  "effective_speed_mps": 23.9,
  "motion_state": "MOVING",
  "point_uuid": "0b7c9a2e4f1d..."   // present only when verdict = ACCEPT
}
```

`point_uuid` is the join that makes this worth storing: an `ACCEPT` row links to the point
that reached the dashboard, and a `REJECT` row is a point that never did, at a place where
the polyline has a straight line instead. It is derived on the device the same way the
point's own `uuid` was — `SHA-1(sessionId:elapsedRealtimeNanos)` — so the two always agree.

Provider name, `is_mock` and `integrity_flags` are **not** in this payload. They belong to
`raw_points`, a different device-side table; the decision log carries the *arithmetic* of a
verdict, and the fix's own provenance is on the point row next to it.

**`type: "event"`** — from `TrackerEvent`. `data` is the event's own payload, flattened.

```jsonc
// TrackerEvent.ProviderChange
{ "gps": false, "network": true, "enabled": true, "permission": "FULL",
  "accuracy_authorization": "PRECISE", "power_save": false, "airplane": false,
  "fused_available": true }

// TrackerEvent.PermissionChange
{ "previous": "FULL", "current": "FOREGROUND_ONLY", "accuracy": "PRECISE" }

// TrackerEvent.CaptureSuspended / TrackerEvent.Error
{ "error_code": "LOCATION_DISABLED" }

// TrackerEvent.IntegrityChange
{ "flags": 288, "signals": ["MOCK_LOCATION_APP_SELECTED", "MOCK_LOCATION_FIX"],
  "blocking": ["MOCK_LOCATION_FIX"] }
```

**`type: "lifecycle"`** — the events the points channel has no way to send (§1.5).

```jsonc
{ "phase": "session_start" }   // session_start | session_stop | session_interrupted
                               // service_start | service_stop | process_start
                               // boot_completed | config_changed | device_motion
```

One row per session is the device's **motion hardware** rather than a boundary —
`code: "DEVICE_MOTION"`, `tag: "Motion"`, at the head of the session:

```jsonc
{ "phase": "device_motion", "motion_quality": "DEGRADED",
  "accelerometer": true, "gyroscope": false, "magnetometer": true,
  "significant_motion": false, "step_detector": true, "step_counter": true,
  "barometer": false, "rotation_vector": true, "activity_recognition": true }
```

`motion_quality` is `FULL`, `DEGRADED` or `POOR`, and it is the answer to "why does this
track have holes in it": `POOR` means motion gating is untrustworthy on this hardware and
the SDK forced `CONTINUOUS`, `DEGRADED` means it doubled the stop timeout. `POOR` is sent at
`warn`; the other two at `info`. `activity_recognition` is carried separately because the
SDK folds that grant into the two step fields, so a `false` there is either no sensor or no
permission.

> **This is the only place a session start and stop ever reach your server.** §1.5 still
> holds for the points endpoint: sessions there materialise from arriving points, and their
> bounds are `MIN`/`MAX` over those points. A `lifecycle` entry is *better* evidence — it
> carries the real start instant even when the first minute of fixes was rejected — but it
> is **advisory**, because this channel is lossy. Use it to annotate the session, never as
> the sole source of truth for its bounds.

**`type: "message"`** — free-form host lines. `data` may be `{}`.

### 11.6 Ordering, ids, and admitting loss

**Deriving `id`.** Deterministic, so a re-delivered batch collides instead of duplicating:

```
id = SHA-1("<session_id>:<seq>:<type>:<elapsed_nanos>")   ->  40 hex chars
```

Same construction as a point's `uuid` (`SHA-1(sessionId:elapsedRealtimeNanos)`), and the
same reason: two writers racing on the same entry must produce the same key on purpose.

**Which session an entry belongs to.** Same three-step resolution as §2.5 — per-row
`session_id` first, envelope second, synthetic `unassigned-<device_id>-<local_date>` third.
Unlike points, a per-row `session_id` here is **not optional**: an entry emitted between two
sessions (a service start, a boot, a config change) legitimately has none, and bucketing
those under whatever session was current at upload time would make them lie.

For those, set `session_id` to `null` and store them against the device, not a session. A
device-scoped log view (§11.11) is what reads them.

**`seq` is per session *and per type*.** Entries and decisions are drained from two device
tables with two independent counters — a shared space would collide on every session that
recorded both, and the collision reads as loss. Group by `(session_id, type)` everywhere you
look at ordering or gaps.

**Gaps are data.** The device buffer is bounded; a device that logs for six hours offline
drops its oldest entries. `seq` makes that visible:

```sql
-- entries missing between two stored rows, within one type
SELECT type, seq, LEAD(seq) OVER (PARTITION BY type ORDER BY seq) - seq - 1 AS missing
FROM session_logs WHERE session_id = $1 ORDER BY type, seq;
```

Render a `-- 412 entries dropped --` marker in the log view. A silent gap reads as "nothing
happened", which is the one thing it does not mean.

**Reboots.** `elapsed_nanos` restarts at 0. Sorting a session that spans a reboot purely on
it braids the log back on itself. Sort on `(type, seq, elapsed_nanos)` — `seq` is monotonic
across the reboot because the device primes it from what is still in its buffer.

### 11.7 Response contract

The same status-code table as §2.1 applies, with one change that matters:

| You return | Device should do | Notes |
|---|---|---|
| **2xx** | Drop those entries from the local buffer. | Only after your transaction commits. |
| **429 / 5xx / timeout** | Keep them buffered, retry with backoff, honour `Retry-After`. | Safe. |
| **4xx (400, 404, 413, 422...)** | **Drop the batch and move on.** | <- the difference from §2.3 |
| **401 / 403** | Stop shipping logs. **Never touch the point queue.** | See §11.2. |

**A permanently-rejected log batch must be droppable.** This inverts §2.3 on purpose. For
points, dropping is data loss and a retry loop is the lesser evil; for logs, a poison batch
that blocks the buffer forever costs battery and buys nothing. Your uploader drops on any
non-retryable 4xx — and your server should still prefer `200 { accepted, rejected }` with a
dead-letter row over a 400, because quarantining keeps the evidence either way.

```jsonc
// 200 OK
{ "accepted": 487, "duplicates": 13, "rejected": 0, "batch_id": "lb_01J8..." }
```

Respond inside 30 s, accept `Content-Encoding: gzip`, tolerate unknown keys. Same as §2.8.

### 11.8 Prisma schema

```prisma
/// One log entry from one device. Append-only, deduped on the SDK-derived id.
model SessionLog {
  id       String @id                         // SHA-1 hex, 40 chars (§11.6)
  deviceId String @map("device_id")
  /// null for entries emitted outside any session — boot, service start, config change.
  sessionId String? @map("session_id")

  seq Int
  /// Monotonic since boot. The ordering key, with seq as the reboot tiebreak.
  elapsedNanos BigInt @map("elapsed_nanos")
  /// Device wall clock, as sent. Display only — never ORDER BY this.
  time       DateTime
  receivedAt DateTime @default(now()) @map("received_at")

  level   LogLevel
  type    LogType
  tag     String
  code    String?
  message String  @db.Text

  /// Verbatim. Never validated, never flattened — the useful field is the one nobody
  /// modelled in advance.
  data Json?

  batchId String? @map("batch_id")

  device  Device   @relation(fields: [deviceId], references: [id], onDelete: Cascade)
  session Session? @relation(fields: [sessionId], references: [id], onDelete: Cascade)

  /// The log-view query, verbatim: one session, in order. `type` is in the key because
  /// `seq` is per session AND per type — two device tables, two counters (§11.6).
  @@index([sessionId, type, seq])
  /// "every error this device raised this week"
  @@index([deviceId, time(sort: Desc)])
  @@index([deviceId, level, time(sort: Desc)])
  @@index([code])
  @@map("session_logs")
}

enum LogLevel { debug info warn error }

enum LogType { event decision message lifecycle }

/// One HTTP exchange on the log endpoint. Mirrors IngestBatch, kept separate so a log
/// flood is visible without polluting the point-ingest audit trail.
model LogBatch {
  id          String   @id @default(cuid())
  deviceId    String?  @map("device_id")
  sessionId   String?  @map("session_id")
  entryCount  Int      @map("entry_count")
  accepted    Int
  duplicates  Int
  rejected    Int
  statusCode  Int      @map("status_code")
  gzip        Boolean  @default(false)
  appVersion  String?  @map("app_version")
  sdkVersion  String?  @map("sdk_version")
  osApi       Int?     @map("os_api")
  deviceModel String?  @map("device_model")
  envelope    Json?
  error       String?
  receivedAt  DateTime @default(now()) @map("received_at")

  @@index([deviceId, receivedAt(sort: Desc)])
  @@map("log_batches")
}
```

Add the back-relations to the existing models from §3.1:

```prisma
model Device  { /* ... */ logs SessionLog[] }
model Session { /* ... */ logs SessionLog[] }
```

### 11.9 Retention — read this before you enable `decision`

Volume, per device, per 8-hour shift:

| Type | Rows/shift | Why |
|---|---|---|
| `lifecycle` | ~10 | Session and service boundaries, plus one `DEVICE_MOTION` row per session. |
| `event` | ~200–2 000 | Provider and permission changes, heartbeats, errors. |
| `message` | host-defined | |
| **`decision`** | **~29 000** | **One row per delivered fix at 1 Hz — the same order as `points`, and each row is wider.** |

`decision` doubles your storage for the whole fleet to answer a question you ask about three
devices a month. **Ship it selectively**: default a device's log level to `info` (which
excludes `decision`), and raise it to `debug` for a named device while a ticket is open —
that is what §11.12 exists for.

```sql
DELETE FROM session_logs WHERE type = 'decision' AND received_at < now() - interval  '7 days';
DELETE FROM session_logs WHERE level = 'debug'   AND received_at < now() - interval '14 days';
DELETE FROM session_logs                         WHERE received_at < now() - interval '30 days';
DELETE FROM log_batches                          WHERE received_at < now() - interval  '7 days';
```

Partition `session_logs` by week if you keep `decision` on for a fleet. Logs are the one
table where dropping old rows is uncontroversial — unlike `geofence_events` (§3.3).

### 11.10 Ingest route

```ts
// src/routes/logs.ts
import { Router } from 'express';
import { prisma } from '../db';
import { parseLogEntry } from '../ingest/parseLogEntry';
import { authenticateDevice, AuthError } from '../auth';

export const logs = Router();

logs.post('/v1/logs/batch', async (req, res) => {
  let principal;
  try {
    principal = await authenticateDevice(req.header('authorization'), { scope: 'logs' });
  } catch (e) {
    if (e instanceof AuthError && e.kind === 'revoked')   return res.status(401).json({ error: 'revoked' });
    if (e instanceof AuthError && e.kind === 'forbidden') return res.status(403).json({ error: 'not permitted' });
    res.setHeader('Retry-After', '60');
    return res.status(503).json({ error: 'auth unavailable' });    // NOT 401
  }

  const { logs: entries, ...envelope } = req.body ?? {};
  const deviceId = String(envelope.device_id ?? principal.deviceId ?? 'unknown');

  if (!Array.isArray(entries)) {
    await prisma.logBatch.create({
      data: { deviceId, entryCount: 0, accepted: 0, duplicates: 0, rejected: 0,
              statusCode: 200, envelope, error: 'no logs array' },
    });
    return res.status(200).json({ accepted: 0, duplicates: 0, rejected: 0 });
  }

  const parsed = [];
  let rejected = 0;
  for (const raw of entries) {
    const r = parseLogEntry(raw, { deviceId, envelope });
    if (r.ok) parsed.push(r.value); else rejected++;
  }

  const outcome = await prisma.$transaction(async (tx) => {
    await tx.device.upsert({
      where: { id: deviceId }, create: { id: deviceId }, update: { lastSeen: new Date() },
    });

    // A log entry may name a session no point has created yet — the first fix of a drive
    // can be rejected, so the session row does not exist while the reasons for that
    // rejection are already being logged. Create the shell rather than dropping the entry.
    const sessionIds = [...new Set(parsed.map((e) => e.sessionId).filter(Boolean))];
    for (const id of sessionIds) {
      await tx.session.upsert({ where: { id }, create: { id, deviceId }, update: {} });
    }

    // Tombstoned sessions (§2.7) keep their logs droppable too: a deleted session must
    // not come back as a log view.
    const live = new Set(
      (await tx.session.findMany({
        where: { id: { in: sessionIds }, deletedAt: null }, select: { id: true },
      })).map((s) => s.id),
    );
    const insertable = parsed.filter((e) => e.sessionId === null || live.has(e.sessionId));

    const { count } = await tx.sessionLog.createMany({ data: insertable, skipDuplicates: true });
    return {
      accepted: count,
      duplicates: insertable.length - count,
      rejected: rejected + (parsed.length - insertable.length),
    };
  });

  const batch = await prisma.logBatch.create({
    data: {
      deviceId, sessionId: envelope.session_id ?? null,
      entryCount: entries.length, ...outcome, statusCode: 200,
      gzip: req.header('content-encoding') === 'gzip',
      appVersion: envelope.app?.version, sdkVersion: envelope.app?.sdk,
      osApi: envelope.device?.os, deviceModel: envelope.device?.model,
      envelope,
    },
  });

  res.status(200).json({ ...outcome, batch_id: batch.id });
});
```

```ts
// src/ingest/parseLogEntry.ts
const LEVELS = new Set(['debug', 'info', 'warn', 'error']);
const TYPES  = new Set(['event', 'decision', 'message', 'lifecycle']);

export function parseLogEntry(raw: any, ctx: Ctx) {
  if (typeof raw?.id !== 'string' || raw.id.length === 0)
    return { ok: false, reason: 'missing id' };
  if (raw.elapsed_nanos === undefined || raw.elapsed_nanos === null)
    return { ok: false, reason: 'missing elapsed_nanos' };

  return {
    ok: true,
    value: {
      id: raw.id,
      deviceId: ctx.deviceId,
      sessionId: raw.session_id ?? ctx.envelope.session_id ?? null,
      seq: Number(raw.seq ?? 0),
      // Sent as a JSON number today; accept a string so a client that switches to one
      // (JS loses precision above 2^53) does not need a server release first.
      elapsedNanos: BigInt(raw.elapsed_nanos),
      time: new Date(Number(raw.time ?? Date.now())),
      // Unknown members are coerced, never rejected — a newer SDK may add a level.
      level: LEVELS.has(raw.level) ? raw.level : 'info',
      type:  TYPES.has(raw.type)   ? raw.type  : 'message',
      tag: String(raw.tag ?? 'app').slice(0, 64),
      code: raw.code ? String(raw.code).slice(0, 64) : null,
      message: String(raw.message ?? '').slice(0, 4096),
      data: raw.data ?? null,
    },
  };
}
```

Wire it next to the point ingest, under the same 10 MB body limit and the same 503 handler:

```ts
app.use(ingest);
app.use(logs);          // <- new
app.use(dashboardApi);
```

### 11.11 Dashboard API

```ts
// One session's log, paged on seq — the @@index([sessionId, type, seq]).
app.get('/api/sessions/:id/logs', async (req, res) => {
  const { level, type, code, after_seq, limit = '200' } = req.query;

  const rows = await prisma.sessionLog.findMany({
    where: {
      sessionId: req.params.id,
      ...(level ? { level: { in: String(level).split(',') } } : {}),
      ...(type  ? { type:  { in: String(type).split(',')  } } : {}),
      ...(code  ? { code: String(code) } : {}),
      ...(after_seq ? { seq: { gt: Number(after_seq) } } : {}),
    },
    orderBy: [{ type: 'asc' }, { seq: 'asc' }],
    take: Math.min(Number(limit), 1000),
  });

  res.json({
    // BigInt does not survive JSON.stringify — send elapsed_nanos as a string.
    entries: rows.map((r) => ({ ...r, elapsedNanos: r.elapsedNanos.toString() })),
    next_after_seq: rows.length ? rows[rows.length - 1].seq : null,
    // Renders the "-- N entries dropped --" marker, per type: `seq` is per session AND
    // per type, so one sequence space's gap is not the other's. A silent gap reads as
    // "nothing happened", which is the one thing it does not mean (§11.6).
    gaps: gapsByType(rows),
  });
});

// Device-scoped: the entries with no session, plus a whole-device error feed.
app.get('/api/devices/:id/logs', async (req, res) => {
  const { from, to, level = 'warn,error' } = req.query;
  const rows = await prisma.sessionLog.findMany({
    where: {
      deviceId: req.params.id,
      level: { in: String(level).split(',') },
      ...(from || to
        ? { time: { gte: from ? new Date(String(from)) : undefined,
                    lte: to   ? new Date(String(to))   : undefined } }
        : {}),
    },
    orderBy: [{ time: 'desc' }],
    take: 500,
  });
  res.json({ entries: rows.map((r) => ({ ...r, elapsedNanos: r.elapsedNanos.toString() })) });
});

// The whole session as NDJSON — what you attach to a ticket. Streamed, not buffered: a
// debug-level session is tens of thousands of lines.
app.get('/api/sessions/:id/logs.ndjson', async (req, res) => {
  res.setHeader('Content-Type', 'application/x-ndjson');
  res.setHeader('Content-Disposition', `attachment; filename="${req.params.id}.ndjson"`);

  let after = -1;
  for (;;) {
    const page = await prisma.sessionLog.findMany({
      where: { sessionId: req.params.id, seq: { gt: after } },
      orderBy: { seq: 'asc' }, take: 2000,
    });
    if (!page.length) break;
    for (const r of page) {
      res.write(JSON.stringify({ ...r, elapsedNanos: r.elapsedNanos.toString() }) + '\n');
    }
    after = page[page.length - 1].seq;
  }
  res.end();
});

// The summary that closes tickets: what went wrong on this session, grouped.
app.get('/api/sessions/:id/log-summary', async (req, res) => {
  const grouped = await prisma.sessionLog.groupBy({
    by: ['level', 'type', 'code'],
    where: { sessionId: req.params.id },
    _count: { _all: true },
    _min: { time: true },
    _max: { time: true },
  });
  res.json(grouped);
});
```

On the map screen (§7), the payoff is a single overlay: plot every `warn`/`error` entry that
has coordinates in its `data` as a marker on the track, and the twenty-minute hole explains
itself without anyone opening a log file.

### 11.12 Remote verbosity — turning `decision` on for one device

> **Server-side only so far.** The device does **not** poll this endpoint yet — verbosity
> is set locally, through `LogSyncConfig.level` and `.types` (§11.13). The device half is a
> `GET` the current `SyncTransport` seam cannot express, since it returns a status code and
> no body; wiring it means adding a response body to that seam.

Mirrors the geofence-revision poll (§6.4): the device asks, the server answers, nothing is
pushed.

```ts
// GET /v1/log-config?device_id=... — called by the device on ready() and hourly.
app.get('/v1/log-config', async (req, res) => {
  const device = await prisma.device.findUnique({ where: { id: String(req.query.device_id) } });
  res.json({
    level: device?.logLevel ?? 'info',          // debug | info | warn | error
    types: device?.logTypes ?? ['event', 'lifecycle', 'message'],
    // Expiry is not optional. A `debug` flag set during a ticket and never cleared is how
    // one device ends up shipping 29 000 decision rows a day for a year.
    until: device?.logLevelUntil ?? null,       // epoch ms; device reverts to `info` after
    batch_size: 500,
    upload_interval_s: 900,
  });
});

// PATCH /api/devices/:id/log-config — the dashboard control.
app.patch('/api/devices/:id/log-config', async (req, res) => {
  const { level, types, hours = 24 } = req.body;
  const device = await prisma.device.update({
    where: { id: req.params.id },
    data: {
      logLevel: level, logTypes: types,
      logLevelUntil: new Date(Date.now() + hours * 3600_000),
    },
  });
  res.json(device);
});
```

```prisma
model Device {
  // ... existing fields ...
  logLevel      String    @default("info")                          @map("log_level")
  logTypes      String[]  @default(["event","lifecycle","message"]) @map("log_types")
  logLevelUntil DateTime?                                           @map("log_level_until")
}
```

The device honours `until` **locally** — it reverts to `info` when its own clock passes it,
with no server round trip. A device that goes offline the day you raise its verbosity must
not come back a month later still at `debug`.

### 11.13 Device-side wiring

One call, if the points endpoint is already configured. The SDK does the rest — recording,
sequencing, id derivation, the bounded ring, batching, gzip, retry, and the retention prune.

```kotlin
// The points channel, as you already have it.
sync.configure(
    SyncConfig.builder()
        .url("https://api.example.com/v1/location/batch")
        .header("Authorization", "Bearer $token")
        .extraParam("device_id", installId)
        .includePointSessionId(true)
        .build(),
)

// The log channel needs no call: configure() derived it above. Same host, same
// credential, /v1/logs/batch, device_id inherited. `.syncLogs(false)` opts out.
```

That inheritance is not a convenience. Sending a *different* `device_id` on this channel
produces two unrelated datasets, and the join between a hole in a track and the reason for
it is the whole point of the endpoint (§11.3).

Override any of it — and giving this endpoint its **own** credential is worth the extra
line, since a 401 on the points URL is destructive by design:

```kotlin
sync.configureLogs(
    LogSyncConfig.builder()
        .path("v1/logs/batch")               // against the points origin, or TrackerConfig.baseUrl
        .header("Authorization", "Bearer $logToken")
        .level(LogLevel.INFO)                // DEBUG only while a ticket is open
        .bufferCapacity(5_000)               // oldest evicted first; the gap is reported
        .retentionHours(72)                  // applies to entries already shipped
        .uploadIntervalMinutes(15)
        .build(),
)
```

Host lines and host boundaries go in through the same buffer:

```kotlin
sync.log(LogLevel.WARN, tag = "Dispatch", message = "Job 8812 refused by driver")
sync.log(
    level = LogLevel.INFO,
    tag = "Dispatch",
    message = "Shift accepted",
    code = "SHIFT_ACCEPTED",
    data = """{"shift_id":"S-441","depot":"AHM-2"}""",   // a JSON object or array, or null
)
sync.logLifecycle(LifecyclePhase.CONFIG_CHANGED, tag = "Host")
```

And the local view, for a debug screen or a bug-report attachment:

```kotlin
val entries: List<LogRecord> = sync.getLogs(sessionId = session.id, limit = 500)
```

#### Turning the decision log on for one device

```kotlin
sync.configureLogs(
    LogSyncConfig.builder()
        .level(LogLevel.DEBUG)      // DECISION entries are DEBUG
        .shipDecisions(true)
        .build(),
)
```

Decisions are **not** copied into the buffer. They are read from the SDK's own decision log
through `Tracker.getDecisions`, converted at send time, and tracked with a per-session
watermark — so nothing is written twice. The first `configureLogs` that enables them moves
that watermark to the newest row, so an opt-in ships the *next* drive rather than the last
three days (§11.9).

#### What the SDK guarantees, and what it does not

- **Durable, not live.** Entries are in SQLite before anything is attempted, so a process
  the OEM kills mid-drive keeps what it recorded — the case this channel exists for, and
  the one a live log stream cannot cover by definition.
- **Never blocking.** `log()` hands off to a bounded channel and returns; a diagnostic
  buffer that applied backpressure to the tracker it is diagnosing would be a worse bug
  than the ones it was added to find.
- **Bounded, and honest about it.** The ring evicts the oldest rows, shipped or not. A
  buffer that refused to drop unsent rows would be unbounded on exactly the device that
  cannot reach a server. `seq` is what makes the loss legible.
- **A separate blast radius.** A 401 or 403 here stops log shipping and keeps the buffer.
  It cannot reach `Tracker.stop()`, the point queue, or a single row of stored locations —
  the log buffer is a different database file from the one holding positions.
- **Periodic, with a prompt exception.** The drain is a 15-minute `LogSyncWorker`
  heartbeat, not a wake per entry — but an entry at `WARN` or above asks for one straight
  away, throttled to one per `nudgeCooldownMs` (30 s). That is what puts a GPS toggle, a
  permission revocation or a capture suspension on the server in seconds while ordinary
  `INFO` chatter still rides the heartbeat. A burst inside the window is **deferred, not
  dropped**: one further drain is scheduled for the end of it, so the entries written after
  the first upload do not wait fifteen minutes. `nudgeLevel(null)` turns it off;
  `sync.requestLogSync()` asks by hand; `sync.syncLogsNow()` runs one inline and returns
  the outcome.
- **Redaction is yours.** Whatever the host passes to `log()` is stored and uploaded. A log
  line is the easiest place in any system to leak a token, and the buffer is on disk.

### 11.14 Case matrix additions

| # | Case | Expected |
|---|---|---|
| 26 | Same batch POSTed twice | `accepted` on the first, `duplicates` on the second. Row count unchanged. |
| 27 | Entry naming a session no point has created | Session shell upserted, entry stored. Never dropped. |
| 28 | Entry naming a tombstoned session | Counted as `rejected`. Not stored, and the session is not resurrected. |
| 29 | Entry with `session_id: null` (boot, service start) | Stored against the device. Visible in `/api/devices/:id/logs`, absent from every session view. |
| 30 | `seq` jumps 1200 -> 1612 | Log view renders `-- 411 entries dropped --`. No error. |
| 31 | Session spanning a reboot | Ordered by `(type, seq, elapsed_nanos)`; the log does not braid back on itself. |
| 32 | `level: "trace"` from a newer client | Coerced to `info`, stored, not rejected. |
| 33 | 4 MB gzipped batch | Decoded and stored, or `413` — and the device drops it rather than retrying forever (§11.7). |
| 34 | Auth service down | `503` + `Retry-After`. **Never 401** — see §11.2. |
| 35 | An entry and a decision sharing a `seq` in one session | Both stored. `seq` is per session **and per type** — dedupe is on `id`, which mixes the type in. |
| 36 | Decision shipping turned on mid-life | Only entries recorded *after* the opt-in arrive. The device marks the existing `fix_decision` backlog as dealt with rather than uploading three days of it (§11.9). |
| 37 | `syncLogs = false`, or a points config with no `device_id` | Nothing arrives, and nothing is recorded either: the recorder is armed only when a channel resolves, so a device without one writes no buffer. The second case is the common one — the log envelope requires `device_id` and the points envelope does not. |
| 38 | 401 on the log endpoint while the points endpoint is healthy | Log shipping stops, the buffer is kept, **and positions keep uploading**. Verify this one explicitly — it is the whole reason the channels are separate. |
