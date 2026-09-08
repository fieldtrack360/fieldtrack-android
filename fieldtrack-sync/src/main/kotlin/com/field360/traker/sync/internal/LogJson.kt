package com.field360.traker.sync.internal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A JSON object as text, or `null` for an empty map.
 *
 * Values are stringified unless they are already a boolean or a number: this is a
 * diagnostic payload, the server stores it as opaque JSONB, and a numeric tower here would
 * buy nothing but a way for a host's `Any` to arrive as something unserializable.
 */
internal fun jsonObjectOrNull(fields: Map<String, Any?>): String? {
    val present = fields.filterValues { it != null }
    if (present.isEmpty()) return null
    return buildJsonObject {
        for ((key, value) in present) {
            when (value) {
                is Boolean -> put(key, value)
                is Number -> put(key, value)
                else -> put(key, JsonPrimitive(value.toString()))
            }
        }
    }.toString()
}

/**
 * True when [text] is a JSON **object or array** — the two shapes the endpoint stores
 * (`docs/APP-LOG-API.md` §3).
 *
 * The only thing validated about a host-supplied payload, and only because the column is
 * structured. Everything inside is deliberately unexamined: the useful field in a log line
 * is the one nobody modelled in advance.
 */
internal fun isJsonStructure(text: String): Boolean = parseJsonOrNull(text) != null

/**
 * Degrades to `null` rather than throwing.
 *
 * A drain is not the place to discover a malformed payload, and losing one entry's detail
 * beats losing the thirty useful entries travelling with it.
 */
internal fun parseJsonOrNull(text: String): JsonElement? = runCatching {
    LENIENT_JSON.parseToJsonElement(text).takeIf { it is JsonObject || it is JsonArray }
}.getOrNull()

private val LENIENT_JSON = Json { isLenient = true; ignoreUnknownKeys = true }
