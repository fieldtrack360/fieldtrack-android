package com.field360.fieldtrack.sample

import android.content.Context
import androidx.core.content.edit
import com.field360.tracker.domain.model.GeofenceTransition
import com.field360.tracker.domain.model.TrackerGeofenceEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * One geofence crossing, as the sample app saw it.
 *
 * Deliberately a *host* record rather than a copy of [TrackerGeofenceEvent]. The SDK's
 * event says a fence was crossed; this says the app processed it, and the two are
 * different facts with different lifetimes — the SDK trims its history on its own
 * schedule, and a notification the user saw should not vanish because of that.
 *
 * @property crossedAtMs when the fence was crossed, from the SDK's own record. The dedupe
 *   key, so it must be the SDK's timestamp and never the host's clock.
 * @property seenAtMs when this app processed the crossing. Later than [crossedAtMs] by
 *   however long the process took to be started for the broadcast, which on a cold start
 *   is worth being able to see. Deliberately not "when it was notified": whether a
 *   crossing earns a notification is the host's call — `SampleApplication` keeps the SDK's
 *   own stationary wake fence silent — and every crossing is recorded either way.
 */
data class GeofenceAlert(
    val geofenceId: String,
    val transition: GeofenceTransition,
    val eventName: String,
    val latitude: Double,
    val longitude: Double,
    val radiusM: Float,
    val crossedAtMs: Long,
    val seenAtMs: Long,
) {
    /** Identity of the crossing itself — one fence can only be crossed one way at one instant. */
    val key: String get() = "$geofenceId|${transition.name}|$crossedAtMs"

    val isEnter: Boolean get() = transition == GeofenceTransition.ENTER
}

/**
 * The sample's own durable record of every geofence crossing it has acted on.
 *
 * **Why the host keeps its own copy at all.** `Tracker.getGeofenceEvents()` already
 * persists crossings, and this does not duplicate that job — it answers a different
 * question. The SDK's store is what *happened*; this is what this app *saw and reacted
 * to*, and it is the half a support thread actually asks about ("did it reach the app, and
 * when?"). They also age differently: the SDK trims to its own bound, so a screen reading
 * it directly would show a user's notification history quietly disappearing.
 *
 * **Why the crossings come from the store and not from the event.** `Tracker.events` is a
 * `replay = 0` `SharedFlow`, and `TrackerEvent.GeofenceEntered` carries the fence but no
 * timestamp. Taking the host clock at collection time would produce a key that drifts from
 * the SDK's by however long delivery took, and the same crossing would then notify twice
 * across a process restart. So the event is treated as a *doorbell* — it says "go and
 * read" — and every field including the dedupe key comes from
 * [TrackerGeofenceEvent]. That also means a crossing delivered while no collector was
 * subscribed is picked up by the next read rather than lost.
 *
 * Synchronous SharedPreferences for the same reason `GeofenceStore` inside the SDK is: a
 * geofence broadcast can start the process, do its work and let it die again, and an
 * asynchronous write has no guarantee of landing before that happens.
 *
 * `org.json` rather than `kotlinx.serialization` deliberately — the sample has no
 * serialization plugin applied, and a demonstration host should not need to add a build
 * plugin to keep a list of eight fields.
 */
class GeofenceAlertLog(context: Context) {

    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private val lock = Any()

    private val _alerts = MutableStateFlow(load())

    /** Newest first, which is the order every screen wants and no screen should re-sort. */
    val alerts: StateFlow<List<GeofenceAlert>> = _alerts.asStateFlow()

    /**
     * Folds a read of the SDK's crossing history into this log.
     *
     * @param crossings whatever `Tracker.getGeofenceEvents()` returned, in any order.
     * @return only the crossings that were **new** — the caller acts on exactly these, so
     *   a re-read that finds nothing new posts nothing. Newest last, so a caller iterating
     *   the result handles them in the order they happened. Which of them earn a
     *   notification is the caller's decision; all of them are recorded.
     *
     * Two guards, and both are needed. The high-water mark stops an old crossing being
     * re-notified after this log has trimmed the entry that would have deduped it; the key
     * check stops a re-read within the same millisecond band notifying twice. Neither
     * alone is sufficient: several fences can be crossed at one instant, which the mark
     * cannot separate, and a trimmed log cannot answer for what it no longer holds.
     */
    fun record(crossings: List<TrackerGeofenceEvent>, nowMs: Long): List<GeofenceAlert> =
        synchronized(lock) {
            val mark = preferences.getLong(CURSOR_KEY, 0L)
            val candidates = crossings.filter { it.timestampMs > mark }.sortedBy { it.timestampMs }
            if (candidates.isEmpty()) return@synchronized emptyList()

            // Advanced past everything this read *considered*, not merely past what turned
            // out to be new. A crossing above the mark that the key check deduped is one
            // this log already holds, and leaving the mark below it would make it new again
            // the moment that entry is trimmed.
            val advanced = maxOf(mark, candidates.last().timestampMs)

            val existing = _alerts.value
            val known = existing.mapTo(mutableSetOf()) { it.key }
            val fresh = candidates.map { it.toAlert(nowMs) }.filter { known.add(it.key) }

            if (fresh.isEmpty()) {
                preferences.edit { putLong(CURSOR_KEY, advanced) }
                return@synchronized emptyList()
            }

            // Newest first on disk and in the flow; `fresh` is oldest first for the caller.
            val merged = (fresh.asReversed() + existing).take(MAX_ALERTS)
            preferences.edit {
                putString(ALERTS_KEY, encode(merged))
                putLong(CURSOR_KEY, advanced)
            }
            _alerts.value = merged
            fresh
        }

    /**
     * Forgets every recorded crossing.
     *
     * **The high-water mark deliberately stays.** Clearing the list is the user saying "I
     * have dealt with these", and dropping the mark with it would mean the very next read
     * of the SDK's history — which still holds those crossings — re-notified every one of
     * them. The mark is what this app has *seen*; the list is what it still shows, and only
     * the second is the user's to clear.
     */
    fun clear() {
        synchronized(lock) {
            preferences.edit { remove(ALERTS_KEY) }
            _alerts.value = emptyList()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────

    private fun TrackerGeofenceEvent.toAlert(nowMs: Long) = GeofenceAlert(
        geofenceId = geofence.id,
        transition = transition,
        eventName = eventName,
        latitude = geofence.latitude,
        longitude = geofence.longitude,
        radiusM = geofence.radiusM,
        crossedAtMs = timestampMs,
        seenAtMs = nowMs,
    )

    private fun load(): List<GeofenceAlert> {
        val raw = preferences.getString(ALERTS_KEY, null) ?: return emptyList()
        // A decode failure is a corrupt or older payload, and losing the list is a better
        // outcome for a sample than a crash loop in Application.onCreate.
        return runCatching { decode(raw) }.getOrDefault(emptyList())
    }

    private fun encode(alerts: List<GeofenceAlert>): String {
        val array = JSONArray()
        alerts.forEach { alert ->
            array.put(
                JSONObject().apply {
                    put(FIELD_ID, alert.geofenceId)
                    put(FIELD_TRANSITION, alert.transition.name)
                    put(FIELD_EVENT_NAME, alert.eventName)
                    put(FIELD_LATITUDE, alert.latitude)
                    put(FIELD_LONGITUDE, alert.longitude)
                    put(FIELD_RADIUS, alert.radiusM.toDouble())
                    put(FIELD_CROSSED_AT, alert.crossedAtMs)
                    put(FIELD_SEEN_AT, alert.seenAtMs)
                },
            )
        }
        return array.toString()
    }

    private fun decode(raw: String): List<GeofenceAlert> {
        val array = JSONArray(raw)
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val transition = runCatching {
                GeofenceTransition.valueOf(item.optString(FIELD_TRANSITION))
            }.getOrNull() ?: return@mapNotNull null

            GeofenceAlert(
                geofenceId = item.optString(FIELD_ID),
                transition = transition,
                eventName = item.optString(FIELD_EVENT_NAME),
                latitude = item.optDouble(FIELD_LATITUDE, 0.0),
                longitude = item.optDouble(FIELD_LONGITUDE, 0.0),
                radiusM = item.optDouble(FIELD_RADIUS, 0.0).toFloat(),
                crossedAtMs = item.optLong(FIELD_CROSSED_AT),
                seenAtMs = item.optLong(FIELD_SEEN_AT),
            )
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "sample_geofence_alerts"
        const val ALERTS_KEY = "alerts"

        /**
         * High-water mark of the newest crossing already folded in.
         *
         * Kept beside the list rather than derived from it, because the list is bounded and
         * the mark must not be: trimming the oldest entry cannot be allowed to make an old
         * crossing look new again.
         */
        const val CURSOR_KEY = "cursor_ms"

        /**
         * 200 crossings. A drive through a dense set of fences produces a handful an hour,
         * so this is days of history, and the whole list is decoded on every write — which
         * is fine at this size and would not be at ten thousand.
         */
        const val MAX_ALERTS = 200

        const val FIELD_ID = "id"
        const val FIELD_TRANSITION = "transition"
        const val FIELD_EVENT_NAME = "event"
        const val FIELD_LATITUDE = "lat"
        const val FIELD_LONGITUDE = "lng"
        const val FIELD_RADIUS = "radius"
        const val FIELD_CROSSED_AT = "crossed_at"
        const val FIELD_SEEN_AT = "seen_at"
    }
}
