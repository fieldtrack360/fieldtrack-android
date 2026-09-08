# Polyline JSON — the export contract

The headline deliverable: a ready-to-draw track that any map library on any platform can render without doing geometry. Produced entirely on-device by `fieldtrack-geo`; no backend, no routing key, no quota.

```kotlin
val json = Tracker.exportPolylineJson(
    TrackQuery.Session(sessionId),
    TrackOptions(zoom = 14f, includeRawPoints = true),
)
```

---

## 1. Wire format v1

```jsonc
{
  "version": 1,
  "sessionId": "20260530-091200-1f0c8a2e",   // local start time + random suffix. A label, not a parseable timestamp
  "generatedAtMs": 1785500000000,
  "from": 1785456000000,
  "to": 1785542399000,
  "timezone": "Asia/Kolkata",
  "precision": 6,                       // encoded-polyline precision. Explicit — never assumed (EC-110)

  "bounds": { "north": 23.0512, "south": 22.9803, "east": 72.6041, "west": 72.5117 },

  "stats": {
    "distanceMeters": 24380.5,
    "durationSec": 28800,
    "movingSec": 5400,
    "stoppedSec": 23400,
    "maxSpeedMps": 18.4,
    "avgMovingSpeedMps": 4.5,
    "pointCount": 142,
    "stopCount": 6,
    "activityBreakdownSec": { "DRIVING": 4100, "WALKING": 1300 }
  },

  "encodedPolyline": "_p~iF~ps|U_ulLnnqC_mqNvxq`@",

  "points": [
    { "i": 0, "t": 1785456123000, "lat": 23.022500, "lng": 72.571400,
      "acc": 8.2, "spd": 0.0, "brg": 0.0, "act": "STILL", "src": "gps", "mock": false }
  ],

  "segments": [
    { "from": 0, "to": 14, "type": "travel",
      "startMs": 1785456123000, "endMs": 1785456603000,
      "distanceMeters": 3420.0, "durationSec": 480,
      "avgSpeedMps": 7.1, "maxSpeedMps": 12.0, "p75SpeedMps": 8.4,
      "activity": "DRIVING", "activityIcon": "🚗", "speedBand": "green",
      "encodedPolyline": "…" },

    { "from": 14, "to": 15, "type": "stop",
      "startMs": 1785456603000, "endMs": 1785459303000,
      "durationSec": 2700, "stopIndex": 1 }
  ],

  "stops": [
    { "index": 1, "lat": 23.030100, "lng": 72.581000,
      "arrivalMs": 1785459000000, "departureMs": 1785461700000,
      "dwellSec": 2700, "radiusM": 42.0, "pointCount": 9,
      "address": "…", "isOngoing": false }
  ],

  "arrows": [
    { "lat": 23.024200, "lng": 72.573100, "bearing": 47.3, "segment": 0 }
  ],

  "warnings": ["snap_unavailable"]
}
```

### Field notes

- **`points[].i`** is the index other arrays reference. `segments[].from/to` and `stops[].index` are indices into `points`, never into the encoded polyline.
- **`encodedPolyline`** is Google's [encoded polyline algorithm](https://developers.google.com/maps/documentation/utilities/polylinealgorithm) at the stated `precision` (default 6, ~0.11 m resolution; pass 5 for libraries that hardcode it).
- **`segments[].encodedPolyline`** lets a renderer draw each span in its own colour without decoding the whole track.
- **A `stop` segment carries no geometry**, so the drawn line *breaks* across it rather than running through it. Expect that break to be wide: a stop is often recognised from the silence between two fixes that are hundreds of metres apart, because the pipeline stops sampling once a device settles and the first fix good enough to catch the departure is rarely the one at the kerb (EC-140). Renderers that want a continuous line should bridge consecutive travel segments themselves — the SDK will not draw a leg it has no evidence for. For the same reason such a stop reports `pointCount: 2` and a `radiusM` inflated by that departure fix, while `lat`/`lng` are the arrival's and are the trustworthy part.
- **`speedBand`** is derived from `speedBandsKmph` (default `[10, 20]`): `≥ 20 km/h → "green"`, `≥ 10 → "yellow"`, else `"red"`.
- **`isOngoing`** on the last stop means the session is still open; dwell was computed against wall clock at build time (EC-111). Renderers should pulse this marker.
- **`warnings`** is an open string array: `snap_unavailable`, `coarse_accuracy`, `mock_locations_present`, `truncated`, `session_interrupted`. **Never silently truncate** — anything dropped is named here.
- **`snap_unavailable`** means a `RoadSnapProvider` was installed and could not answer, so `encodedPolyline` is raw geometry where road geometry was intended. It is **not** emitted when no provider is installed: not asking and asking-and-failing are different facts about a track, and a warning that fired for both would mean nothing. A track carrying it is complete and drawable — the provider costs geometry, never the track (EC-100).
- **Snapping is invisible in this format, on purpose.** There is no `snapped: true` field and no per-point provenance flag. `encodedPolyline`, `segments[].encodedPolyline` and `arrows[]` are all computed from the same post-snap path, so a renderer draws whatever geometry the build had and needs to know nothing about where it came from. Provenance lives on the internal `PlotPoint.tag`, which deliberately never reaches the wire — writing a render tag back onto stored data is the mistake [A10](SOURCE-AUDIT.md) is about. Points more than 80 m off the returned road keep their captured position, so `points[]` and the polyline can legitimately diverge (EC-101).
- **Empty and single-point tracks are well-formed**: `bounds: null`, zeroed stats, empty arrays. No `NaN`, ever (EC-93, EC-94).

---

## 2. `arrows` — why it exists

Direction arrows are the one thing every consumer wants and nobody wants to compute. Placement needs bearings, geodesic offsets, zoom-adaptive spacing and jump handling — and if the renderer and the export compute it separately they drift, which is exactly what happened in the reference implementation ([A9](SOURCE-AUDIT.md): two ladders, visibly different arrow density before and after the first pinch).

Tracker computes arrow anchors **once**, in `fieldtrack-geo`, and that same function feeds both `fieldtrack-maps` and this JSON. A consumer draws a rotated marker at each anchor:

```kotlin
track.arrows.forEach { a ->
    map.addMarker(
        MarkerOptions()
            .position(LatLng(a.lat, a.lng))
            .anchor(0.5f, 0.5f)
            .rotation(a.bearing.toFloat())
            .flat(true)
            .icon(arrowIcon)
    )
}
```

### Placement rules

| Condition | Result |
|---|---|
| `zoom < 10` | no arrows |
| segment length < `arrowMinSegmentM` (60 m) | skipped |
| segment length > 50 km (data jump) | exactly two arrows, at ¼ and ¾ |
| segment length ≤ 250 m | one centred arrow |
| route length > 40 km | every 5 km |
| route length > 10 km | every 2.5 km |
| otherwise, by zoom | `z ≥ 18 → 80 m`, `z ≥ 15 → 300 m`, `z ≥ 13 → 800 m`, else 4 km |

`TrackOptions.zoom` selects the tier. Re-request only when zoom changes by more than 0.5 — below that the placement is visually identical and re-rendering is wasted work.

---

## 3. GeoJSON

`exportGeoJson()` emits a `FeatureCollection` — the Maps SDK's `GeoJsonLayer` renders it directly, and it is the interchange format for handing a track to any other tool (MapLibre, Mapbox, QGIS, a web viewer):

- one `LineString` per segment, `properties`: `type`, `speedBand`, `activity`, `distanceMeters`, `durationSec`, `avgSpeedMps`;
- one `Point` per stop, `properties`: `index`, `dwellSec`, `arrivalMs`, `departureMs`, `address`, `isOngoing`;
- one `Point` per arrow, `properties`: `bearing`, `segment` — style with `icon-rotate: ["get", "bearing"]`.

Coordinates are `[longitude, latitude]` per RFC 7946 — the opposite order from `points[]` in the polyline JSON, which is a classic source of "my track is in the ocean". The distinction is deliberate and documented in both places.

---

## 4. Fixture format (diagnostics)

`exportFixture()` emits raw fixes plus the decision each one received. `replayFixture()` runs them back through the pipeline with no device attached and returns the new decisions — so any accuracy complaint becomes a regression test, and constants can be tuned without a phone.

```jsonc
{
  "version": 1,
  "recordedAtMs": 1785500000000,
  "device": { "model": "Pixel 8", "sdkInt": 35, "oem": "Google" },
  "config": { /* full TrackerConfig snapshot */ },
  "fixes": [
    { "timeMs": 1785456123000, "elapsedNs": 91827364500000,
      "lat": 23.0225, "lng": 72.5714, "acc": 8.2,
      "spd": 0.0, "brg": 0.0, "hasSpeed": true, "hasBearing": false,
      "provider": "gps", "mock": false }
  ],
  "expected": [
    { "i": 0, "verdict": "ACCEPT", "reason": "Init" },
    { "i": 1, "verdict": "SKIP",   "reason": "Drift Suppressed" }
  ]
}
```

`expected[]` is the golden file. A constant change that flips any entry fails CI and has to be argued for.
