package com.tmplayer.data

import java.io.IOException

/**
 * Turns whatever went wrong into a sentence a viewer can act on.
 *
 * TDLib reports failures as terse upper-case codes — `CHAT_NOT_FOUND`, `FLOOD_WAIT_42` — which are
 * meaningful to a protocol implementer and to nobody else. Anything not recognised is passed
 * through rather than replaced by a vague apology: a real message, however technical, at least
 * gives the viewer something to search for or send along in a bug report.
 */
object Failures {

    fun humanise(error: Throwable?): String = when (error) {
        null -> DEFAULT
        is IOException -> error.message?.takeIf { it.isNotBlank() } ?: OFFLINE
        else -> humanise(error.message)
    }

    fun humanise(message: String?): String {
        val raw = message?.trim().orEmpty()
        if (raw.isEmpty()) return DEFAULT

        floodWaitSeconds(raw)?.let { return floodMessage(it) }

        return when {
            raw.startsWith("Unauthorized", ignoreCase = true) ||
                raw.contains("SESSION_REVOKED") ||
                raw.contains("AUTH_KEY_UNREGISTERED") ->
                "This device is no longer signed in to Telegram. Sign in again from Settings."

            raw.contains("CHAT_NOT_FOUND") || raw.contains("PEER_ID_INVALID") ->
                "That chat is no longer available. It may have been deleted or you may have left it."

            raw.contains("CHANNEL_PRIVATE") ->
                "This channel is private and your account can't read it any more."

            raw.contains("FILE_REFERENCE") || raw.contains("FILE_ID_INVALID") ->
                "Telegram's link to this file went stale. Refresh the chat and try again."

            raw.contains("Timeout", ignoreCase = true) ||
                raw.contains("Connection", ignoreCase = true) ||
                raw.contains("Network", ignoreCase = true) ->
                OFFLINE

            // TDLib prefixes many failures with a numeric code; it adds nothing on a TV.
            else -> raw.substringAfter(": ", raw)
        }
    }

    /** `FLOOD_WAIT_42` / `Too Many Requests: retry after 42` both carry the same number. */
    private fun floodWaitSeconds(raw: String): Int? =
        FLOOD.find(raw)?.groupValues?.get(1)?.toIntOrNull()

    private fun floodMessage(seconds: Int): String {
        val wait = when {
            seconds < 60 -> "$seconds seconds"
            seconds < 3600 -> "${seconds / 60} minutes"
            else -> "${seconds / 3600} hours"
        }
        return "Telegram is rate-limiting this account. Try again in about $wait."
    }

    private val FLOOD = Regex("""(?:FLOOD_WAIT_|retry after )(\d+)""", RegexOption.IGNORE_CASE)

    const val OFFLINE = "Can't reach Telegram. Check this TV's network connection."
    private const val DEFAULT = "Something went wrong talking to Telegram."
}
