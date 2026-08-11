package com.tmplayer.data

import java.io.IOException

/**
 * Turns whatever went wrong into a sentence a viewer can act on.
 *
 * TDLib reports failures as terse upper-case codes (`CHAT_NOT_FOUND`, `FLOOD_WAIT_42`) that are
 * meaningful to a protocol implementer and to nobody else. Anything unrecognised gets the generic
 * message and the raw string goes to logcat, where it stays diagnosable from a bug report.
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
                "Telegram signed this device out. Sign in again to carry on."

            raw.contains("CHAT_NOT_FOUND") || raw.contains("PEER_ID_INVALID") ->
                "That chat isn't there any more. It was deleted, or you left it."

            raw.contains("CHANNEL_PRIVATE") ->
                "You're not in this channel any more, so its videos aren't available."

            // The size check before playback only knows what the file claims to be. A remux that
            // was under-reported, or a second video arriving alongside this one, still fills the
            // disk part-way through, and "Telegram didn't answer" is a poor account of that.
            raw.contains("No space left", ignoreCase = true) ||
                raw.contains("ENOSPC") ||
                raw.contains("Not enough disk space", ignoreCase = true) ->
                "This device has run out of storage. Clear space in Settings, then try again."

            raw.contains("FILE_REFERENCE") || raw.contains("FILE_ID_INVALID") ->
                "Telegram moved this video. Press Refresh, then try again."

            raw.contains("Timeout", ignoreCase = true) ||
                raw.contains("Connection", ignoreCase = true) ||
                raw.contains("Network", ignoreCase = true) ->
                OFFLINE

            else -> {
                android.util.Log.w(TAG, "Unmapped failure: $raw")
                DEFAULT
            }
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
        return "Telegram has asked us to slow down. Try again in about $wait."
    }

    private val FLOOD = Regex("""(?:FLOOD_WAIT_|retry after )(\d+)""", RegexOption.IGNORE_CASE)

    private const val TAG = "Failures"

    const val OFFLINE = "Can't reach Telegram. Check this device's internet connection."
    const val DEFAULT = "Telegram didn't answer. Try again in a moment."
}
