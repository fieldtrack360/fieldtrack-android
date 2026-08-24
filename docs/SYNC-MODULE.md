# Sync Module (`fieldtrack-sync`)

How tracked points get from the device's local database to your server: the architecture,
the upload lifecycle, every failure mode, and how a host application wires it up.

---

## 1. Design at a glance

`fieldtrack-core` **never opens a socket**. All networking lives in this optional artifact.
A host that does not depend on `fieldtrack-sync` gets an offline-first SDK with no network
code linked at all.

```
┌─────────────────────┐        ┌──────────────────────────────────────────────┐
│   fieldtrack-core   │        │              fieldtrack-sync                 │
│                     │        │                                              │
│  FixIngestor        │        │  TrackerSync ── configure() / syncNow()      │
│    │ stores point   │        │      │                                       │
│    ▼                │ trigger│      ▼                                       │
│  Room (track_point) │───────▶│  SyncWorker (WorkManager, network-gated)     │
│                     │        │      │                                       │
│  SyncTrigger seam   │◀───────│      ▼                                       │
│  (registered by     │        │  SyncQueue.drain() ── batches rows           │
│   TrackerSync)      │        │      │                                       │
└─────────────────────┘        │      ▼                                       │
                               │  SyncTransport ── HTTP (OkHttp default,      │
                               │                   or host-supplied)          │
                               └──────────────────────────────────────────────┘
```

Key principles:

- **Store-then-sync, never sync-then-store.** A point is durable in Room before any upload
  is attempted. A failed upload costs nothing; a dead network costs nothing. Rows are marked
  synced *only* on a confirmed 2xx — the default state is "still queued", which is the safe
  direction.
- **Pluggable transport.** Most apps already have an HTTP client with their own auth
  interceptors, certificate pinning and logging. OkHttp and Retrofit are `compileOnly`
  here: supply your own `SyncTransport` and neither is ever linked.
- **The server's word is final.** 401 tears the session down, 403 halts uploads,
  `Retry-After` overrides the SDK's own backoff schedule.

---

## 2. The main classes

| Class | Role |
|---|---|
| `TrackerSync` | Public entry point. Singleton via `TrackerSync.getInstance(context)`. Holds config + transport, exposes `configure()`, `syncNow()`, `requestSync()`, `events`, `pendingCount()`. |
| `SyncConfig` | Endpoint, method, headers, batch size, network constraints, timeouts. Built via `SyncConfig.builder()`; validated at `configure()` time. |
| `SyncQueue` | Drains pending rows in batches, maps them to the wire format, interprets the response. One drain at a time (mutex). |
| `SyncTransport` | The seam: `suspend fun upload(SyncRequest): SyncResponse`. Must never throw. |
| `OkHttpSyncTransport` | Default transport. Accepts a host `OkHttpClient` (pinning, proxies, interceptors survive). |
| `SyncWorker` | WorkManager `CoroutineWorker`. Network-constrained, linear 30 s backoff, unique work. |
| `SyncEvent` | `SharedFlow` of per-exchange outcomes, for drains the host did not initiate. |

---

## 3. Upload lifecycle

### 3.1 Configure

```kotlin
val sync = TrackerSync.getInstance(context)

sync.configure(
    SyncConfig.builder()
        .baseUrl("https://api.example.com")     // or rely on TrackerConfig.baseUrl
        .path("v1/location/batch")
        .header("Authorization", "Bearer $token")
        .batchSize(100)
        .autoSync(true)
        .build(),
    // transport = myTransport                  // optional; omit for OkHttp default
)
```

- A relative `path(...)` with no `baseUrl(...)` resolves against `TrackerConfig.baseUrl`.
  An absolute `url(...)` always wins over both.
- `configure()` validates and **throws `IllegalArgumentException`** on a bad config — the
  one deliberate exception to the SDK's no-throw contract, because it runs on your own
  thread while you assemble a value. HTTPS is mandatory; `http://` is accepted only for
  loopback hosts (`localhost`, `127.0.0.1`, `::1`, `10.0.2.2`) or with
  `allowCleartext(true)`.
- Re-calling `configure()` is the documented recovery from a 403 halt and the way to
  rotate an auth header.

### 3.2 Triggering

Three paths lead to an upload:

1. **autoSync (default on).** `configure()` registers a `SyncTrigger` with core. Core fires
   it when a point is accepted and from its supervision loops when rows are queued or the
   last upload has gone stale. Each firing calls `requestSync()`.
2. **`requestSync()`.** Enqueues a network-constrained one-shot `SyncWorker`. Safe to call
   often: unique work with `ExistingWorkPolicy.KEEP`, so a burst of accepted points cannot
   reset the backoff clock and hammer a struggling server. A no-op while halted by a 403.
3. **`syncNow()`.** Drains inline in the caller's coroutine scope and returns the result.
   For user-initiated refresh; an upload started from a `viewModelScope` is cancelled with
   it. Prefer `requestSync()` for anything not user-initiated.

### 3.3 Draining (`SyncQueue.drain`)

- Takes a mutex with `tryLock` — a scheduled retry and a manual `syncNow()` colliding would
  upload the same rows twice. The loser returns `Retry("already draining")`.
- Reads up to `batchSize` rows, serializes them as `SyncPayload` JSON, hands the request to
  the transport, and repeats — bounded at **20 batches per drain** so one call cannot hold
  the lock through an enormous backlog.
- On `Success`: rows are marked synced with the wall-clock time and the next batch loads.
- On anything else: the drain stops and reports (see §4).
- Every completed exchange emits one `SyncEvent.HttpResponse(statusCode, count)` — three
  batches, three events. `statusCode == null` means no HTTP exchange completed at all
  (dead network, DNS failure, timeout): a device problem, distinct from a server problem.

### 3.4 Scheduling and retry

`SyncWorker` maps the drain result to WorkManager:

| Drain result | Worker outcome |
|---|---|
| `Uploaded` / `Empty` | `success()` |
| `Retry` (no `Retry-After`) | `retry()` — linear backoff, 30 s steps |
| `Retry` with `retryAfterMs` | `success()` + re-enqueue with `setInitialDelay` at the server's own schedule (`ExistingWorkPolicy.REPLACE`, so the server's instruction is not discarded in favour of the 30 s default) |
| `AuthExpired` / `Forbidden` | `failure()` — terminal, retrying would only re-fail |

`Retry-After` parsing (RFC 9110) accepts both delta-seconds and HTTP-date forms, and clamps
the result to **1 second – 6 hours**. Unparseable, negative or already-past values read as
"no server opinion" and the SDK's own backoff applies.

Network constraint: `CONNECTED` by default, `UNMETERED` when
`requiresUnmeteredNetwork(true)`.

---

## 4. Failure semantics

The three-way split is the heart of the module. **A network failure is an expected state,
not an exception** — transports must never throw; they report which of these it was:

### 401 Unauthorized — tear down

The credential this data was recorded under is gone, and the next login may be a different
user.

- Tracking is stopped (`Tracker.stop()`).
- The upload queue is **cleared** — carrying the rows forward would leak one user's
  positions into the next login.
- Config and transport are forgotten; `isConfigured` becomes `false`.

### 403 Forbidden — halt, keep everything

This credential may not write this resource: a scope, a rotated key, a server-side
permission bug. It is the same user's data either way, and destroying it to fix a
permissions mistake is the more expensive of the two errors.

- Uploads stop; the retry loop — the battery burn worth stopping — ends.
- Rows **stay queued**. Tracking continues.
- `requestSync()` becomes a no-op until the host calls `configure()` again with a working
  credential.

### Everything else — retry

Timeouts, 5xx, connection failures: rows stay queued, `SyncWorker` retries with backoff, or
at the server's `Retry-After` when it named one. Error bodies are captured (bounded at
4 096 chars, never logged by the SDK) so a host can distinguish "bad JSON" from "dead
database".

---

## 5. Wire format

`POST` (configurable verb) with body `{"location": [ ...points ]}`. snake_case keys, epoch
milliseconds.

Anything the host sets in `SyncConfig.extraParams` is merged into the **top level** of the
body, before `location` — the envelope shape most backends want, where the batch travels
with identity (`user_id`, `device_id`, a session token) rather than alone:

```json
{"user_id": "u-42", "device_id": "d-88", "location": [ ... ]}
```

Values may be a string, boolean, number, or a map/list of those; types are preserved rather
than stringified. `SyncConfig.validate()` rejects anything unserializable at `configure()`
time, so a drain never discovers a config problem. With no extra params the encoder takes
its original path and the body is byte-identical to earlier releases.

One point:

```json
{
  "uuid": "0b7c…",
  "time": 1719400000000,
  "local_date": "2026-08-24",
  "latitude": 23.0225,
  "longitude": 72.5714,
  "accuracy": 12.5,
  "movementSpeed": 1.4,
  "provider": {
    "network": true,
    "gps": true,
    "enabled": true,
    "status": 3,
    "accuracyAuthorization": 0,
    "airplane": false
  },
  "hasSpeed": true,
  "hasBearing": true,
  "time_zone": "Asia/Kolkata",
  "activity_status": "gps@moving",
  "detected_activity_type": "WALKING",
  "detected_activity_start_time": 1719399990000,
  "battery_percentage": "87",
  "is_charging": true,
  "is_mock": false,
  "integrity_flags": 0,
  "integrity_signals": []
}
```

Notes for the server:

- **Dedupe on `uuid`.** A failed batch is re-sent whole; duplicates are guaranteed by
  design.
- `activity_status` is `"<provider>@<movementStatus>"`, stored verbatim and parsed back by
  the plotting side. **This is where the provider name lives** now that `provider` carries
  the subsystem object.
- `integrity_flags` is the device-integrity bitmask captured with the point;
  `integrity_signals` is the same information by name (e.g.
  `"HOOKING_FRAMEWORK_DETECTED"`) for backend rules that prefer strings to bits. `0` /
  empty also covers debuggable builds and hosts with the integrity layer disabled.
- Both fields are defaulted so older backends keep parsing.

### The `provider` object

| Field | Meaning |
|---|---|
| `network` | The network (Wi-Fi/cell) provider is enabled. |
| `gps` | The GPS provider is enabled. |
| `enabled` | The location **master switch** — not the union of the two above. |
| `status` | Permission tier: `0` not determined, `1` restricted, `2` denied, `3` always, `4` while in use. Android never sends `0` (it cannot tell "never asked" from "refused"). |
| `accuracyAuthorization` | `0` full (fine), `1` reduced (coarse only). |
| `airplane` | Airplane mode was on. |

Recorded **per point**, not sampled when the queue drains: a batch of 100 rows can span an
hour, and a permission downgrade or an airplane-mode toggle inside that hour is exactly the
event that explains a gap. Stored as a packed `providerFlags` column
(`ProviderSnapshot.toFlags`) and decoded at the sync boundary.

The key is **omitted** for a point stored before the SDK recorded it. That is deliberately
not an object full of `false` — "we did not look" and "everything was off" are different
answers about a point that plainly exists, and the packed value carries a `recorded` bit to
keep them apart.

> **Breaking change.** `provider` was previously the provider *name* as a string
> (`"gps"`, `"fused"`). Backends reading it as a string should switch to `activity_status`,
> which has always carried the same name.

### Power state

`battery_percentage` is 0–100 **as a string**; `is_charging` is `true` while plugged in or
full. Both are **absent rather than null-or-zero** when the platform will not say — a phone
that will not report its charge has not told you it is at 0 %, and `is_charging: false`
would read as "confirmed on battery".

### Mock fixes

`is_mock` is `true` when `Location.isMock` was set. Whether such a fix is stored at all
depends on two settings that resolve against each other at `ready()`:

- `geolocation.mockLocationPolicy` — `FLAG` (default) stores and marks; `REJECT` drops in
  `FixMapper` before anything else runs.
- `security.mockLocation` — `BLOCK` (default) forces `mockLocationPolicy = REJECT`, because
  an SDK that refuses to run on a mocked device cannot also be storing mocked points.

**A debuggable host is exempt from that forcing**, under the same waiver the integrity layer
itself takes (`IntegrityEnvironment.isWaived`). So a debug build stores mock fixes and
uploads them with `is_mock: true`, while a release build drops them unless the host sets
`mockLocationIntegrityPolicy(WARN)` *and* `mockLocationPolicy(FLAG)` deliberately.

A backend with a different contract remaps in its own `SyncTransport` — that is cheaper
than making the payload configurable.

### Request body compression

`gzipRequestBody(true)` sends `Content-Encoding: gzip`. **Off by default and deliberately
so**: there is no negotiation for request-body encoding, and a server that does not expect
it answers 400 or stores compressed bytes as the payload. Turn it on only after the server
confirms it decodes gzip. A custom transport is free to ignore the flag and send plain
JSON — the server sees a valid request either way.

---

## 6. Observing what happened

```kotlin
// Drains the host asked for — the result comes back directly:
val result = sync.syncNow()   // Uploaded(n) | Empty | Retry | AuthExpired | Forbidden

// Drains it did not — WorkManager may run them minutes later, in a process
// nobody is watching:
scope.launch {
    sync.events.collect { event ->            // SharedFlow, replay = 1
        // SyncEvent.HttpResponse(statusCode: Int?, count: Int)
    }
}

// For a UI badge:
val pending = sync.pendingCount()

// Where uploads go (headers deliberately NOT exposed — they carry the credential):
val where: String? = sync.endpoint
val ready: Boolean = sync.isConfigured   // do not cache: a 401 clears it asynchronously
```

`SyncEvent` is deliberately not a case on core's `TrackerEvent`: that flow belongs to
`fieldtrack-core`, which never opens a socket, and a host with no upload module must not
compile against HTTP status codes it can never receive.

A parked user uploads nothing by design, because the filter stores nothing. **Absence of
uploads is not evidence of a dead tracker** — the raw-fix watchdog covers that.

---

## 7. Supplying your own transport

```kotlin
class MyTransport(private val api: MyApiClient) : SyncTransport {
    override suspend fun upload(request: SyncRequest): SyncResponse = try {
        val response = api.post(request.url, request.headers, request.jsonBody)
        when (response.code) {
            in 200..299 -> SyncResponse.Success(response.code)
            401 -> SyncResponse.Unauthorized
            403 -> SyncResponse.Forbidden
            else -> SyncResponse.Failure(
                code = response.code,
                message = "HTTP ${response.code}",
                body = response.body?.take(SyncResponse.Failure.MAX_BODY_CHARS),
                retryAfterMs = null,   // parse Retry-After yourself to honour it
            )
        }
    } catch (e: IOException) {
        SyncResponse.Failure(code = null, message = e.message ?: "network error")
    }
}

sync.configure(config, MyTransport(apiClient))
```

Contract:

- **Never throw.** Catch everything; report it as `Failure`.
- Map 401 → `Unauthorized` and 403 → `Forbidden` faithfully — the tear-down/halt semantics
  in §4 depend on it.
- `SyncTimeouts` arrives on every `SyncRequest` as plain numbers (not OkHttp types), so a
  custom transport honours them without linking OkHttp.

The default `OkHttpSyncTransport(client)` accepts a host-configured `OkHttpClient`, so
existing pinning, proxies and interceptors survive. Default timeouts: connect 5 s,
read 30 s, write 20 s — override per-config via `SyncConfig.timeouts`.

---

## 8. Configuration reference

| `SyncConfig` field | Default | Notes |
|---|---|---|
| `url` / `baseUrl` + `path` | — (required) | HTTPS enforced; loopback exempt. `url` wins over `baseUrl`+`path`; relative `path` falls back to `TrackerConfig.baseUrl`. |
| `method` | `POST` | Must be one of the transport's supported verbs; validated at `configure()`. |
| `headers` | empty | Auth lives here. Never exposed back via any property. |
| `autoSync` | `true` | Off → nothing is registered with core; the host owns the schedule. |
| `batchSize` | `100` | 1–1000. Larger = fewer requests, but a failure re-sends the whole batch. |
| `requiresUnmeteredNetwork` | `false` | Wi-Fi-only uploads. |
| `gzipRequestBody` | `false` | See §5 — enable only with server agreement. |
| `allowCleartext` | `false` | Local development servers only. |
| `timeouts` | 5 s / 30 s / 20 s | connect / read / write, in ms. |

---

## 9. Testing checklist

1. **Payload shape** — point at a local server (`10.0.2.2` from the emulator; cleartext
   exempt) and inspect the JSON.
2. **Offline resilience** — kill the network mid-drain: rows stay queued, WorkManager
   retries on reconnect.
3. **401** — return it once: tracking stops, queue clears, `isConfigured == false`.
4. **403** — return it once: uploads halt, rows kept, `requestSync()` no-ops until
   re-`configure()`.
5. **429 + `Retry-After: 120`** — next drain arrives ~120 s later, not 30 s.
6. **Duplicate delivery** — force a 500 after the server stored the batch: the retry
   re-sends it; confirm the server dedupes on `uuid`.
7. **Provider snapshot** — revoke background location mid-session, or toggle airplane mode:
   points captured after the change carry the new `provider.status` / `provider.airplane`
   while earlier points in the same batch keep the old one.
8. **Mock fixes** — push a fake route from a mock-location app on a debug build: points
   arrive with `is_mock: true`. The same build in release drops them.
