# Saving logs from the app

How the app ships log entries to this backend, and how to tell what happened to them.

The contract this implements is §11 of [`SYNC-BACKEND-AND-DASHBOARD.md`](SYNC-BACKEND-AND-DASHBOARD.md).
Android wiring for the SDK's own recorder is in [`../client/android/README.md`](../client/android/README.md).

---

## 1. Why this endpoint exists at all

`fieldtrack-sync` uploads **points and nothing else**. So when a track has a twenty-minute hole
in it, the server can see the hole and nothing whatsoever about its cause — and every plausible
cause is on the device:

| On the phone | What the dashboard sees without logs |
|---|---|
| Battery optimiser killed the service | a track that stops |
| Location downgraded to "approximate" | a track that gets vague |
| `ACCESS_FINE_LOCATION` denied on first run | **no device at all** |
| Play Services refused to arm a fence | a fence nobody ever crossed |
| A fix rejected by the filter | a straight line where the road bends |
| The process crashed | a track that stops |

Each row on the left is one log entry. Each row on the right is a supervisor being told the
worker turned their phone off.

---

## 2. The endpoint

```
POST /v1/logs/batch
Content-Type: application/json; charset=utf-8
Content-Encoding: gzip            (optional, recommended — logs compress ~8:1)
Authorization: Bearer <credential>
```

> **Not `/v1/logs`.** That path was an earlier draft and now returns **404**. If nothing is
> saving, check this first.

### Credential

Ideally **not the points token** (§11.2). A 401 on the *points* URL is destructive — it stops
tracking and wipes the device's unsent point queue — so a log-shipping bug must not be able to
reach it. This endpoint has its own router and its own auth scope for that reason.

| `AUTH_MODE` | What to send |
|---|---|
| `dev` (default) | `Authorization: Bearer dev.<deviceId>.<tenantId>` |
| `open` | No header required. The envelope's `device_id` becomes the identity. |
| `external` | Whatever your provider issues. Unimplemented ⇒ **503**, which is retryable. |

Identity precedence: **token first, envelope second**. The envelope is an unauthenticated claim
(§2.9) — it is used only when the credential is not itself bound to a device.

---

## 3. The request body

```jsonc
{
  "device_id":   "8f14e45f-ceea-467a-9c1a-2b0a1e1f9c31",  // SAME id as the points envelope
  "session_id":  "20260907-143512-1f0c8a2e",              // optional fallback for entries
  "uploaded_at": 1719400123456,                           // device wall clock at send

  // Constant for the life of a process. Stored on the BATCH row, not on every entry — it is
  // the same 200 bytes on each one otherwise, and it is what a "works on my device" ticket is
  // actually about.
  "app":    { "package": "com.acme.field", "version": "3.4.1", "build": 3401, "sdk": "1.9.0" },
  "device": { "manufacturer": "samsung", "model": "SM-A546E", "os": 34 },

  "logs": [ /* 1..500 entries, oldest first */ ]
}
```

> **`device_id` must be the same string the points envelope sends.** That join — a hole in a
> track, next to the reason for it — is the entire point of the endpoint. Two spellings of the
> same phone produce two unrelated datasets.

### The hardware line

`app` and `device` land on the batch row **and** on the session's first sighting: the server
turns them into one synthetic log entry at the head of every session named by the batch.

```jsonc
{
  "seq": -1,                      // below the device's own counter, which starts at 0
  "level": "info", "type": "lifecycle", "tag": "Device", "code": "DEVICE_INFO",
  "message": "samsung SM-A546E · Android 34 · com.acme.field 3.4.1 (3401) · sdk 1.9.0",
  "data": { "synthetic": "server", "source": "log-batch envelope",
            "device": { /* verbatim */ }, "app": { /* verbatim */ } }
}
```

- **The device does not send it and must not.** The SDK ships the metadata once per envelope,
  never as an entry; the entry is synthesised server-side from what the envelope already
  carried. It costs the device nothing and no wire format changed.
- **`elapsed_nanos` is one below the batch's earliest entry**, and `seq` is `-1`, so it is first
  in both orders — the panel's `elapsed_nanos` and the export's `(type, seq)`. `-1 → 0` is
  contiguous, so it does not invent a dropped-entry marker.
- **Idempotent, and honest about upgrades.** The id is `SHA-1("<session>:device-info:<the two
  blocks, canonical>")`: a re-delivered batch collides, and an app upgrade mid-session writes a
  *second* line rather than overwriting the first.
- **It is not counted.** `accepted` answers for the entries the device sent, so a device can
  still reconcile the response against its own batch.
- **The two exclusions.** A tombstoned session gets none (§2.7 — a synthetic row would
  resurrect it on the dashboard), and an envelope carrying neither `app` nor `device` gets none:
  an empty hardware line would claim the phone reported nothing, which is a different fact from
  a client too old to send it.

### One entry

```jsonc
{
  "id":            "3f1a…",                    // 40 hex. THE dedupe key. Required.
  "session_id":    "20260907-143512-1f0c8a2e", // per row, authoritative. null is legal.
  "seq":           1482,                       // monotonic per session AND per type, from 0
  "elapsed_nanos": "918273645000000",          // monotonic since boot. A STRING. Required.
  "time":          1719400000000,              // device wall clock, epoch ms. Display only.
  "level":         "warn",                     // debug | info | warn | error
  "type":          "event",                    // event | decision | message | lifecycle
  "tag":           "CaptureGate",              // subsystem, free text
  "code":          "LOCATION_DISABLED",        // nullable
  "message":       "Suspending capture: location services unavailable",
  "data":          { "error_code": "LOCATION_DISABLED" }   // type-specific, stored verbatim
}
```

### Field rules as implemented

| Field | Required | Rule |
|---|---|---|
| `id` | **yes** | `SHA-1("<session_id>:<seq>:<type>:<elapsed_nanos>")`, 40 hex. Truncated at 128 chars. Missing ⇒ the entry is **rejected**. |
| `elapsed_nanos` | **yes** | Digits only. Accepted as a **string** or a number; send the string — the count passes 2^53 after 104 days of uptime and JSON numbers round silently past it. Missing or non-numeric ⇒ **rejected**. |
| `session_id` | no | Per-row wins, then the envelope's, then `null`. Capped at 200 chars. |
| `seq` | no | Defaults to 0. Per session **and per type** — see §5. `-1` is reserved for the server's hardware line; a device sends from 0. |
| `time` | no | Epoch ms, epoch **seconds** (10 digits, detected), or ISO. Unparseable ⇒ server receive time. |
| `level` | no | Unknown value ⇒ coerced to `info`. Never rejected. |
| `type` | no | Unknown value ⇒ coerced to `message`. Never rejected. |
| `tag` | no | Defaults to `"app"`. Truncated at 64. |
| `code` | no | Nullable. Truncated at 64. |
| `message` | no | Truncated at 4096. An empty one is fine. |
| `data` | no | Object or array, stored as JSONB **verbatim and never validated** — the useful field is the one nobody modelled in advance. |

**Only two things get an entry rejected: a missing `id` and a missing `elapsed_nanos`.** Both are
structural — one is the dedupe key, the other the ordering key. Everything else is coerced,
because a log line that cannot be stored perfectly is still worth storing imperfectly.

---

## 4. Response

```jsonc
// 200 OK
{ "accepted": 487, "duplicates": 13, "rejected": 0, "batch_id": "lb_01J8…" }
```

| Code | Meaning | What the device should do |
|---|---|---|
| **200** | Stored. `duplicates` are entries already held. | Drop those entries from the buffer — after your transaction commits. |
| **413** | More than 500 entries in one batch. | **Drop the batch.** Do not retry it — it will never be accepted. |
| **4xx** | Permanently unacceptable. | Drop the batch and move on. |
| **401 / 403** | Credential refused. | Stop shipping logs. **Never touch the point queue.** |
| **503 / timeout** | Transient. `Retry-After` is set. | Keep buffered, retry with backoff. |

Note the inversion from §2.3: for *points*, dropping is data loss and a retry loop is the lesser
evil. For *logs*, a poison batch that blocks the buffer forever costs battery and buys nothing.

Retrying is free — `id` is deterministic, so a re-sent batch collides instead of duplicating.
Delivery is **at-least-once by design**: a connection dropped after the server committed costs
one `duplicates`, which the database ignores.

**The three counts add up to the entries you sent.** The hardware line (§3) is written in the
same transaction and deliberately left out of all three — a device must be able to reconcile the
response against its own batch, and a number it cannot account for is a number that starts a bug
report.

---

## 5. `seq`, ordering, and gaps

`seq` is **per session and per type**. Entries and decisions come from two device tables with two
independent counters; a shared space would collide on every session that recorded both, and the
collision reads as loss.

Order on `elapsed_nanos`, never on `time` — the wall clock can jump backwards mid-session (manual
change, NTP correction) and `elapsedRealtimeNanos` cannot. It resets to 0 on reboot, which is why
`seq` is the tiebreak.

**Gaps are data, not errors.** The device buffer is bounded; a device logging for six hours
offline drops its oldest entries. `seq` makes that visible, and the dashboard renders
`— 411 entries dropped —` between the two rows. A silent gap reads as "nothing happened", which
is the one thing it does not mean.

The hardware line sits at `seq -1` in the `lifecycle` space for this reason: `-1 → 0` is
contiguous, so it sorts ahead of everything the device sent without drawing a marker claiming an
entry went missing ahead of it. Its `elapsed_nanos` is one below the earliest entry of the batch
that carried the metadata, which puts it first in the panel's order too.

### `session_id: null` is legal and sometimes correct

An entry emitted **between** sessions — a boot, a service start, a config change — genuinely
belongs to no session. Send `null`. Bucketing it under whatever session happened to be current
would make it lie.

Those entries are stored against the device and are visible **only** in
`GET /api/devices/:id/logs?unassigned=1`. No session view will ever show them.

---

## 6. `data`, per type

**`decision`** — the highest-volume type. One row per delivered fix at 1 Hz, ~29 000 per shift.

```jsonc
{ "verdict": "REJECT", "reason": "NLP Fallback",
  "latitude": 23.0225, "longitude": 72.5714, "accuracy": 48.0,
  "sigma": 6.4, "threshold": 4.0,            // the gate, and the bar it failed
  "distance_moved_m": 287.4, "effective_speed_mps": 23.9, "motion_state": "MOVING",
  "point_uuid": "0b7c9a2e…" }                // present only when verdict = ACCEPT
```

**`event`** — the `TrackerEvent` payload, flattened.

```jsonc
{ "gps": false, "network": true, "enabled": true, "permission": "FULL" }   // ProviderChange
{ "previous": "FULL", "current": "FOREGROUND_ONLY" }                       // PermissionChange
{ "error_code": "LOCATION_DISABLED" }                                      // CaptureSuspended
```

**`lifecycle`** — the only place a session start or stop ever reaches the server. **Advisory**:
this channel is lossy, so annotate the session with it, never treat it as the sole truth for the
session's bounds.

```jsonc
{ "phase": "session_start" }   // session_start | session_stop | session_interrupted
                               // service_start | service_stop | process_start
                               // boot_completed | config_changed | device_motion
```

One `lifecycle` row per session comes from the device's **sensors** rather than from a
boundary: `code: "DEVICE_MOTION"`, `tag: "Motion"`, written once at the head of the session.

```jsonc
{ "phase": "device_motion", "motion_quality": "DEGRADED",
  "accelerometer": true, "gyroscope": false, "magnetometer": true,
  "significant_motion": false, "step_detector": true, "step_counter": true,
  "barometer": false, "rotation_vector": true,
  "activity_recognition": true }
```

The envelope's `device` block names the phone; nothing in it says whether that phone can
**detect a stop**, and motion gating is what decides the capture cadence. `motion_quality`
is the SDK's own verdict on the three fields above it:

| `motion_quality` | What the SDK does about it | How the track reads |
|---|---|---|
| `FULL` | Nothing. Accelerometer, gyroscope and a trigger sensor are all present. | As configured. |
| `DEGRADED` | Doubles the stop timeout, because a stop is detected later and less certainly. | Stops linger; a few extra fixes after the vehicle parks. |
| `POOR` | Forces `CONTINUOUS` — motion gating is not trustworthy on this hardware. | Not the cadence the host configured. Sent at **`warn`**, so it earns a prompt drain. |

`activity_recognition` is reported separately on purpose: the SDK folds that grant into
`step_detector` and `step_counter`, so a `false` on either could mean *no sensor* or *no
permission* — a different phone and a prompt are not the same remedy.

One `lifecycle` row per session is **not** from the device: `code: "DEVICE_INFO"`, `tag:
"Device"`, `seq: -1`, the hardware line of §3. Its `data` is the envelope's two metadata blocks
verbatim, under a marker saying where it came from.

```jsonc
{ "synthetic": "server", "source": "log-batch envelope",
  "device": { "manufacturer": "samsung", "model": "SM-A546E", "os": 34, "fingerprint": "…" },
  "app":    { "package": "com.acme.field", "version": "3.4.1", "build": 3401, "sdk": "1.9.0" } }
```

**`message`** — free-form lines, `data` may be `{}`. Two sources share this type:

- **the host's own**, from `TrackerSync.log(...)`, with whatever `tag` the host chose.
- **the SDK's own commentary**, recorded automatically for as long as a log channel exists —
  no host call involved. The `tag` names the SDK subsystem (`API_CALL`, `SyncScheduler`,
  `LocationStream`, …) and the level is `debug` or `warn`.

They are one type rather than two on purpose: a reader following one device through one
incident wants a single ordered stream, not two to interleave by hand. Filter on `tag` if you
need them apart — the SDK's tags are stable.

Expect **only `warn`** from the SDK on a fleet: the device-side `level` filter defaults to
`info`, so its `debug` commentary is dropped before it is ever stored. A device with `level`
turned down to `debug` for a ticket will send several `message` rows per fix, which is worth
knowing before you size a table on the fleet average.

Any `warn`/`error` entry whose `data` carries `latitude`/`longitude` is **plotted on the track**
in the dashboard — so a rejected fix appears exactly where the polyline draws a straight line
instead of going there.

---

## 7. A call that works

```bash
SESSION=20260907-143512-1f0c8a2e
ID=$(printf '%s' "$SESSION:1:event:918273645000001" | sha1sum | cut -d' ' -f1)

curl -X POST http://localhost:3000/v1/logs/batch \
  -H 'content-type: application/json' \
  -H 'authorization: Bearer dev.my-device.default' \
  -d "{
    \"device_id\": \"my-device\",
    \"session_id\": \"$SESSION\",
    \"uploaded_at\": $(date +%s)000,
    \"app\":    { \"package\": \"com.acme.field\", \"version\": \"3.4.1\", \"sdk\": \"1.9.0\" },
    \"device\": { \"manufacturer\": \"samsung\", \"model\": \"SM-A546E\", \"os\": 34 },
    \"logs\": [{
      \"id\": \"$ID\",
      \"session_id\": \"$SESSION\",
      \"seq\": 1,
      \"elapsed_nanos\": \"918273645000001\",
      \"time\": $(date +%s)000,
      \"level\": \"warn\",
      \"type\": \"event\",
      \"tag\": \"CaptureGate\",
      \"code\": \"LOCATION_DISABLED\",
      \"message\": \"Suspending capture\",
      \"data\": { \"error_code\": \"LOCATION_DISABLED\" }
    }]
  }"

# => {"accepted":1,"duplicates":0,"rejected":0,"batch_id":"..."}
```

Read it back:

```bash
curl "http://localhost:3000/api/sessions/$SESSION/logs" -H 'authorization: Bearer dash.default'
```

**Two entries come back, not one.** The second is the hardware line the server derived from the
`app` and `device` blocks above — `DEVICE_INFO`, at the head of the session. Post the same batch
again and it stays at two: the response counts one `duplicates`, and the hardware line collides
on its own id.

---

## 8. Why it is not saving

**Read the response first — it says which of these happened.**

| Symptom | Cause | Fix |
|---|---|---|
| **404** | Posting to `/v1/logs`. | Use `/v1/logs/batch`. |
| **403** | `AUTH_MODE=dev` and no/oddly-shaped token. | `Bearer dev.<deviceId>.<tenantId>`. |
| **413** | Over 500 entries. | Batch smaller. The device should drop, not retry. |
| **503** | Auth provider unimplemented (`AUTH_MODE=external`), or the DB is down. | Retryable — check `/readyz`. |
| `accepted:0, rejected:0`, no error | The body has no `logs` **array**. Answered 200 on purpose — a 4xx would buy an infinite retry (§2.3). | Send `logs`, not `entries`/`items`. |
| `rejected: n` | Missing `id`, or missing/non-numeric `elapsed_nanos`. | Both are required and structural. |
| `rejected: n`, entries look valid | `session_id` names a **tombstoned** session, or one **belonging to a different device**. | A deleted session is never resurrected by a log. Check the `device_id`/`session_id` pairing. |
| `duplicates: n` | Same `id` already stored. | Not an error — retrying is free by design. |
| `accepted: n` but nothing in the panel | Entries have `session_id: null`. | They are device-scoped: `GET /api/devices/:id/logs?unassigned=1`. |
| Saved under the wrong device | `device_id` here ≠ `extraParams["device_id"]` on the points channel. | Make them the same string. |
| An entry the device never sent | A `lifecycle` row, `code: DEVICE_INFO`, `seq: -1`. | Not a bug and not a duplicate: the server's hardware line (§3). One per session, uncounted. |
| Gaps on a track the fixes cannot explain | The phone cannot detect a stop. | Read the session's `DEVICE_MOTION` row (§6): `POOR` forced `CONTINUOUS`, `DEGRADED` doubled the stop timeout. |
| No `DEVICE_MOTION` row on a session | The channel was off during it — `syncLogs = false`, or it could not be derived from the points config — or `lifecycle` is not in the device's `types`. | It is written at session start and again when the channel is turned on mid-session. |
| No hardware line on a session | The envelope carried neither `app` nor `device`, or no log batch has named that session yet. | Send both blocks. A session built from points alone has no hardware to report — the metadata only ever arrives on this endpoint. |

### The audit table

**Every exchange is recorded whatever the outcome**, including the 413s and the auth failures:

```sql
SELECT received_at, status_code, entry_count, accepted, duplicates, rejected, error,
       app_version, sdk_version, device_model
FROM log_batches
WHERE device_id = 'my-device'
ORDER BY received_at DESC
LIMIT 20;
```

- **No row at all** ⇒ the request never reached the handler. 404, a network failure, or it never
  left the device.
- **A row** ⇒ `status_code`, `rejected` and `error` say exactly what happened.

Then the entries themselves:

```sql
SELECT time, level, type, tag, code, left(message, 80)
FROM session_logs
WHERE device_id = 'my-device'
ORDER BY received_at DESC
LIMIT 50;
```

---

## 9. Reading logs back

| Route | For |
|---|---|
| `GET /api/sessions/:id/logs` | The panel. `?level=`, `?type=`, `?code=`, `?after_id=`, `?limit=`. Newest first, ordered on `elapsed_nanos`. Returns `entries`, `counts`, `gaps`, `total`, `next_after_id`. |
| `GET /api/sessions/:id/log-summary` | One row per `(level, type, code)` with a count and the window it spans. The ticket-closer. |
| `GET /api/sessions/:id/logs.ndjson` | The whole session, streamed. What you attach to a ticket. |
| `GET /api/devices/:id/logs` | Whole-device feed, defaults to `warn,error`. `?unassigned=1` for entries with no session. |

All four take the dashboard credential (`Bearer dash.<tenantId>`), never the device token, and all
four are scoped to the caller's tenant.

`elapsed_nanos` comes back as a **string** — it does not survive `JSON.stringify` as a number.

---

## 10. Limits and retention

| | |
|---|---|
| Entries per request | 500 (`MAX_LOGS_PER_REQUEST`) — over it, 413 |
| `message` | 4096 chars, truncated not rejected |
| Body size | 10 MB (`BODY_LIMIT`), gzip decoded automatically |
| Page size on read | 20 default, 1000 max (`LOG_LIMIT`, `LOG_LIMIT_MAX`) — page with `?after_id=`, the id of the last row you hold |

Retention is tiered, because `decision` is the same order of volume as `points` and each row is
wider (`npm run prune`, `--apply` to delete):

```
decision entries   7 days
debug entries     14 days
everything        30 days
log_batches        7 days
```

The hardware line is `info`/`lifecycle`, so it prunes with everything else at 30 days. A session
older than that keeps its track and loses the record of which phone drew it — `points` has its
own, longer retention. If that matters for your fleet, lift the hardware line onto the session
row rather than lengthening log retention for all of it.

**Ship `decision` selectively.** Default a device to `info`, which excludes it, and raise one
device while a ticket is open:

```bash
curl -X PATCH http://localhost:3000/api/devices/<id>/log-config \
  -H 'content-type: application/json' -H 'authorization: Bearer dash.default' \
  -d '{"level":"debug","types":["event","decision"],"hours":24}'
```

The expiry is not optional and is capped at 14 days — a `debug` flag set during a ticket and
never cleared is how one device ends up shipping 29 000 rows a day for a year. The device honours
`until` locally, so one that goes offline that day cannot come back a month later still at
`debug`.

---

## 11. Where it lands

| Table | Holds |
|---|---|
| `session_logs` | One row per entry. PK `id`; `device_id`, `session_id` (nullable), `seq`, `elapsed_nanos`, `time`, `received_at`, `level`, `type`, `tag`, `code`, `message`, `data`, `batch_id`. Plus the server's hardware line per session — same table, `seq -1`, `code DEVICE_INFO`. |
| `log_batches` | One row per HTTP exchange: counts, `status_code`, `gzip`, and the per-batch `app`/`device` metadata. |

Kept separate from `ingest_batches` so a log flood is visible without polluting the point-ingest
audit trail. Deleting a session deletes its logs — the confirmation says "permanently", and a log
line naming a deleted session is that session's data.

The `app`/`device` metadata is therefore in two places on purpose. `log_batches` answers "what
was this device running last Tuesday at 14:02", one row per exchange; the hardware line answers
"what phone was this session" without a second query, and travels with the NDJSON attached to a
ticket.

---

## 12. Redaction is yours

Whatever the app passes to `log()` is stored and uploaded, and the device buffer is on disk. A log
line is the easiest place in any system to leak a token.
