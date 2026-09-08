package com.field360.traker.sync.internal

import java.security.MessageDigest

/**
 * The log channel's dedupe key (`docs/APP-LOG-API.md` §3).
 *
 * Same construction and same purpose as `Uuids.forFix` in `fieldtrack-geo`: a batch that
 * reached the server and lost its response is re-sent whole, so a re-delivered entry has
 * to collide on the server's primary key rather than land twice. Retrying is free.
 *
 * `elapsedRealtimeNanos` is in the digest as well as `seq` on purpose. `seq` alone would
 * repeat if a session's entries were all evicted from the bounded buffer and the counter
 * restarted — a legible gap in a log, and a silent overwrite of somebody else's row
 * without this term. It is also what makes re-shipping a decision after a reboot safe.
 *
 * @param sessionId `null` for an entry emitted outside any session. Hashed as the literal
 *   `"null"`, which is stable and is not a session id any device produces.
 */
internal fun logEntryId(
    sessionId: String?,
    seq: Long,
    type: String,
    elapsedRealtimeNanos: Long,
): String = sha1Hex("$sessionId:$seq:$type:$elapsedRealtimeNanos")

private fun sha1Hex(input: String): String {
    val digest = MessageDigest.getInstance("SHA-1").digest(input.toByteArray(Charsets.UTF_8))
    return buildString(digest.size * 2) {
        digest.forEach { byte ->
            val v = byte.toInt() and 0xFF
            append(HEX[v ushr 4])
            append(HEX[v and 0x0F])
        }
    }
}

private val HEX = "0123456789abcdef".toCharArray()
