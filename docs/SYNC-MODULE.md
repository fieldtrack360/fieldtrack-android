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

Four paths lead to an upload:

1. **autoSync (default on).** `configure()` registers a `SyncTrigger` with core. Core fires
   it when a point is accepted, from its supervision loops when rows are queued or the last
   upload has gone stale, and once more when a session closes. Each firing calls
   `requestSync()`.
2. **Connectivity (autoSync only).** `configure()` also registers a
   `ConnectivityManager.registerDefaultNetworkCallback`. When the device returns to a
   *usable* network with rows queued, a drain is requested. See §3.5 — this is the path
   that empties a queue recorded in a tunnel, a basement or on a plane.
3. **`requestSync()`.** Enqueues a network-constrained one-shot `SyncWorker`. Safe to call
   often: unique work with `ExistingWorkPolicy.KEEP`, so a burst of accepted points cannot
   reset the backoff clock and hammer a struggling server. A no-op while halted by a 403.
4. **`syncNow()`.** Drains inline in the caller's coroutine scope and returns the result.
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
| Not configured (checked before draining) | `success()` — no attempt can make a `configure()` appear |
| `Retry("already draining")` / `("sync not configured")` / `("no transport")` | `success()` — this worker's own situation, not a failed exchange |
| `Retry` (no `Retry-After`) | `retry()` — linear backoff, 30 s steps |
| `Retry` with `retryAfterMs` | `success()` + re-enqueue with `setInitialDelay` at the server's own schedule (`ExistingWorkPolicy.REPLACE`, so the server's instruction is not discarded in favour of the 30 s default) |
| `AuthExpired` / `Forbidden` | `failure()` — terminal, retrying would only re-fail |

**Why the three non-failures must not reach `retry()`.** A retry permanently increments
`runAttemptCount`, which grows the linear backoff for every *genuine* failure after it, and
it parks the unique work in `ENQUEUED` — where `KEEP` swallows every later drain request,
including the one a reconnection makes. Paying that for a lock collision (a host's
`syncNow()` overlapping the worker, which is a drain *succeeding* on another thread), or for
a host that calls `configure()` later than `Application.onCreate`, is the wrong trade in
both directions. The reason strings are `internal` constants on `SyncQueue`; they are
documentation for a host, not a protocol, and nothing outside the module should branch on
them.

`Retry-After` parsing (RFC 9110) accepts both delta-seconds and HTTP-date forms, and clamps
the result to **1 second – 6 hours**. Unparseable, negative or already-past values read as
"no server opinion" and the SDK's own backoff applies.

Network constraint: `CONNECTED` by default, `UNMETERED` when
`requiresUnmeteredNetwork(true)`.

### 3.5 Coming back online

Offline capture is the normal case, not the exception, so recovering from it has two
halves. They are not redundant — neither covers the other's failure.

**The durable half — `SyncWorker`'s network constraint.** WorkManager persists the request
in its own database and releases it when the constraint is met, so an enqueued drain
survives process death and reboot. What it cannot do is resurrect itself: the constraint
only holds back work that is *already enqueued*, and a drain that has already finished
leaves nothing for connectivity to release. Three places therefore make sure something is
always left waiting when it matters — the 15-minute `BackstopWorker` and the health loop
both run their supervision tick whether or not a session is open, and `stopTracking()`
leaves a final network-constrained drain enqueued if any rows are still queued.

**The prompt half — `NetworkMonitor`.** While the process is alive, a network transition
asks for a drain directly rather than waiting for the next accepted point (up to a minute)
or the next supervision tick (up to fifteen). Details that matter:

- **`registerDefaultNetworkCallback`, not `CONNECTIVITY_ACTION`.** That broadcast is
  deprecated since API 28 and has been unavailable to manifest-declared receivers since
  API 24 — a background app never sees it. The default-network callback also reports only
  the network the process would actually use, so a Wi-Fi/cellular handover is one
  transition rather than an interleaved pair across two networks.
- **Validated, not merely connected.** A captive portal answers with
  `NET_CAPABILITY_INTERNET` and then intercepts the upload. `NET_CAPABILITY_VALIDATED` is
  what distinguishes "attached to a network" from "packets reach the internet".
- **Metered-aware.** Under `requiresUnmeteredNetwork(true)`, losing Wi-Fi is a falling
  edge, so re-joining it later is a rising one.
- **Rising edges, throttled 15 s — and the throttle defers rather than drops.**
  `onCapabilitiesChanged` fires for signal strength and link speed — many times a minute in
  a moving vehicle. Only unusable → usable counts, and a flapping validation on a weak
  network produces one request rather than a burst. A real reconnection is never one
  callback (`onLost`, then unvalidated, then validated, all inside a couple of seconds), so
  a rise suppressed by the cooldown books a drain for when the cooldown expires instead of
  returning "not a rise" with nothing left to re-check it. Dropping it stranded the queue
  until the next supervision tick — two minutes with the service alive, fifteen without.
- **`REPLACE`, not `KEEP`.** The connectivity edge enqueues through its own path
  (`SyncWorker.enqueueNow`). `KEEP` — correct for the accepted-point burst — does nothing
  when a request already exists in any unfinished state, and `ENQUEUED` is what a request in
  linear backoff looks like. Since WorkManager's `NetworkType.CONNECTED` releases work on
  *connected* rather than *validated*, the drain routinely runs a second before routing
  works, fails, and re-enters backoff; the validated edge that followed was then silently
  swallowed, leaving the backlog to wait out a backoff already grown to minutes. `REPLACE`
  also resets `runAttemptCount`, which is what stops a long offline stint compounding into
  a five-hour delay.
- **Queue-gated.** The queue depth is read before anything is enqueued: a reconnection
  with an empty queue is the common case and is not worth a worker.
- **autoSync only.** With `autoSync(false)` the host owns the schedule, and a drain it did
  not ask for is a surprise request against its own credential.
- **Stopped by 401 and 403.** A halted uploader that still drains on every reconnection is
  the same retry loop by a different door.
- **Never fatal.** No `ConnectivityManager`, a vendor that throws, or the platform's
  100-callback-per-process limit all degrade to "durable half only" — which is exactly the
  behaviour that shipped before this existed.

Requires `ACCESS_NETWORK_STATE`, declared by the AAR and merged into the host manifest.

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

#### What an envelope field can and cannot say

`extraParams` is fixed when `configure()` runs and describes the **whole batch**. Both
facts constrain what belongs there.

A value that changes needs `configure()` to run again — there is no per-drain hook. The
sample does exactly this for `session_id`, re-configuring on every session change.

More importantly, a batch is `pending(batchSize)`: oldest rows first, **across every unsent
session**. So a per-batch field is only truthful if it is genuinely true of every row in it:

| Field | Envelope-safe? | Why |
|---|---|---|
| `user_id`, `device_id`, an auth token | ✅ | Constant for the install. Every row in every batch shares it. |
| `app_version`, `platform` | ⚠️ | True of the *uploader*, not necessarily of the row — an old queued row may predate an upgrade. |
| `session_id` | ❌ for a backlog | A device that recorded three sessions offline uploads all three under whichever session was current at the last `configure()`. |

`SyncPoint` carries no session id of its own, so nothing downstream can correct a
mislabelled envelope. A top-level `session_id` is right when the queue drains before each
session ends — which for a mostly-online device it effectively does — and wrong for exactly
the offline backlog this SDK exists to survive.

If per-row session attribution matters, do not solve it here. Either drain and confirm
before closing a session, or carry the id on each point: `TrackPoint.sessionId` is already
stored on every row, and a custom `SyncTransport` can rewrite the body to hoist it per
point without changing the SDK.

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
        when (event) {
            // One per completed exchange — a three-batch drain emits three.
            is SyncEvent.HttpResponse -> log(event.statusCode, event.count)
            // The device came back online with rows queued and a drain was requested.
            // Rising edge only, never with an empty queue, process-lifetime only (§3.5).
            is SyncEvent.NetworkAvailable -> showSyncing(event.queued)
        }
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
   retries on reconnect. Batches that already succeeded stay marked synced.
3. **Reconnect while running** — airplane mode on, drive/simulate until rows accumulate,
   airplane mode off. A `SyncEvent.NetworkAvailable` arrives within seconds and the queue
   drains without waiting for the next accepted point. **Check the drain, not just the
   event**: the regression this covers emitted `NetworkAvailable` correctly and uploaded
   nothing, because the enqueue behind it was a `KEEP` no-op against a request already in
   backoff. Point at an unreachable host first so a few attempts fail and the backoff grows
   past a minute, then restore the endpoint and toggle the network — the drain must go out
   on the edge, not on the expired backoff.
4. **Reconnect after the process is gone** — airplane mode on, capture, `stop()`,
   force-stop the app, airplane mode off. `NetworkMonitor` cannot help here; the drain
   `stopTracking()` left enqueued is what runs (§3.5). Confirm the rows arrive.
5. **Captive portal** — join a Wi-Fi network with a sign-in page. Nothing should be
   attempted until it is validated: an unvalidated network is not a rising edge.
6. **Handover** — drive out of Wi-Fi range onto cellular with `requiresUnmeteredNetwork`
   both `false` (uploads continue) and `true` (queue parks until Wi-Fi returns).
7. **401** — return it once: tracking stops, queue clears, `isConfigured == false`.
8. **403** — return it once: uploads halt, rows kept, `requestSync()` no-ops until
   re-`configure()`.
9. **429 + `Retry-After: 120`** — next drain arrives ~120 s later, not 30 s.
10. **Duplicate delivery** — force a 500 after the server stored the batch: the retry
   re-sends it; confirm the server dedupes on `uuid`.
11. **Provider snapshot** — revoke background location mid-session, or toggle airplane mode:
   points captured after the change carry the new `provider.status` / `provider.airplane`
   while earlier points in the same batch keep the old one.
12. **Mock fixes** — push a fake route from a mock-location app on a debug build: points
   arrive with `is_mock: true`. The same build in release drops them.
