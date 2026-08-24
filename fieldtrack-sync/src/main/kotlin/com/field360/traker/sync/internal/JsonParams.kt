package com.field360.traker.sync.internal

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Converts a host-supplied `extraParams` value into JSON.
 *
 * Deliberately accepts only stdlib types. `kotlinx-serialization-json` is an
 * `implementation` dependency of this module, so a `JsonElement` in the public
 * `SyncConfig` signature would be a type the host cannot name — the reason the API takes
 * `Any` and this function is the gate rather than the host building the tree itself.
 *
 * Returns `null` for anything unsupported, which is how `SyncConfig.validate` reports the
 * offending key by name instead of throwing from inside a drain hours later.
 *
 * @param depth recursion guard. A map that contains itself is a stack overflow otherwise,
 *   and a config assembled from untrusted input is exactly where that arrives.
 */
internal fun jsonParamOrNull(value: Any?, depth: Int = 0): JsonElement? {
    if (depth > MAX_PARAM_DEPTH) return null
    return when (value) {
        null -> null
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        // Every boxed numeric type, not just Int and Double: a Java host handing over a
        // `long` id or a `float` reading must not be told its own primitive is unsupported.
        is Byte, is Short, is Int, is Long, is Float, is Double -> JsonPrimitive(value as Number)
        is Map<*, *> -> nestedObject(value, depth)
        is Iterable<*> -> nestedArray(value.toList(), depth)
        is Array<*> -> nestedArray(value.toList(), depth)
        else -> null
    }
}

private fun nestedObject(value: Map<*, *>, depth: Int): JsonObject? {
    val entries = LinkedHashMap<String, JsonElement>(value.size)
    for ((key, nested) in value) {
        // JSON object keys are strings. A map keyed by anything else would silently
        // stringify, which is a shape the backend never agreed to.
        if (key !is String || key.isBlank()) return null
        entries[key] = jsonParamOrNull(nested, depth + 1) ?: return null
    }
    return JsonObject(entries)
}

private fun nestedArray(values: List<*>, depth: Int): JsonArray? =
    JsonArray(values.map { jsonParamOrNull(it, depth + 1) ?: return null })

/**
 * Deep enough for any realistic auth envelope, shallow enough that a cycle is caught long
 * before the stack is.
 */
internal const val MAX_PARAM_DEPTH: Int = 10
