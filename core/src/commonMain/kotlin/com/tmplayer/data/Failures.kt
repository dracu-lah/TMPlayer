package com.tmplayer.data

import com.tmplayer.i18n.L
import com.tmplayer.platform.Logger
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
            raw.startsWith("Unauthorized", ignoreCase = true) || // i18n-ok: TDLib text, matched
                raw.contains("SESSION_REVOKED") ||
                raw.contains("AUTH_KEY_UNREGISTERED") ->
                L.errorsSignedOut

            raw.contains("CHAT_NOT_FOUND") || raw.contains("PEER_ID_INVALID") ->
                L.errorsChatGone

            raw.contains("CHANNEL_PRIVATE") ->
                L.errorsChannelPrivate

            // The size check before playback only knows what the file claims to be. A remux that
            // was under-reported, or a second video arriving alongside this one, still fills the
            // disk part-way through, and "Telegram didn't answer" is a poor account of that.
            raw.contains("No space left", ignoreCase = true) || // i18n-ok: TDLib text, matched
                raw.contains("ENOSPC") ||
                raw.contains("Not enough disk space", ignoreCase = true) -> // i18n-ok: TDLib text, matched
                L.errorsNoSpace

            raw.contains("FILE_REFERENCE") || raw.contains("FILE_ID_INVALID") ->
                L.errorsFileMoved

            raw.contains("Timeout", ignoreCase = true) || // i18n-ok: TDLib text, matched
                raw.contains("Connection", ignoreCase = true) || // i18n-ok: TDLib text, matched
                raw.contains("Network", ignoreCase = true) -> // i18n-ok: TDLib text, matched
                OFFLINE

            else -> {
                Logger.w(TAG, "Unmapped failure: $raw")
                DEFAULT
            }
        }
    }

    /**
     * Whether the only useful answer to this error is to ask Telegram for the message again.
     *
     * A TDLib file id belongs to the run of the client that issued it, and TDLib will not fetch a
     * file it cannot trace back to a message it has seen this session. A download queued before the
     * process was killed holds an id in exactly that state, and retrying it fails identically every
     * time. Re-fetching the message hands back a current id.
     *
     * Separate from [humanise], because this decides an action and that decides a sentence. The
     * same string can want both.
     */
    fun needsFreshFileReference(message: String?): Boolean {
        val raw = message?.trim().orEmpty()
        if (raw.isEmpty()) return false
        return STALE_FILE.any { raw.contains(it, ignoreCase = true) }
    }

    /**
     * What TDLib says about a file id it no longer recognises, in the several ways it says it.
     *
     * The wording differs by which layer refused: the file manager, the request parser, or
     * Telegram itself answering a request built from an expired reference.
     */
    private val STALE_FILE = listOf(
        "FILE_REFERENCE",
        "FILE_ID_INVALID",
        "Invalid file identifier", // i18n-ok: TDLib text, matched
        "Invalid file id", // i18n-ok: TDLib text, matched
        "Unknown file id", // i18n-ok: TDLib text, matched
        "File not found", // i18n-ok: TDLib text, matched
        "Can't download file", // i18n-ok: TDLib text, matched
        "File is not downloadable", // i18n-ok: TDLib text, matched
    )

    /**
     * `FLOOD_WAIT_42` / `Too Many Requests: retry after 42` both carry the same number.
     *
     * Public because the number is worth more than the sentence built out of it: Telegram is
     * saying exactly how long to wait, so callers can time a retry rather than guess at one.
     */
    fun floodWaitSeconds(raw: String?): Int? =
        raw?.let { FLOOD.find(it)?.groupValues?.get(1)?.toIntOrNull() }

    private fun floodMessage(seconds: Int): String {
        val wait = when {
            seconds < 60 -> L.unitSeconds(seconds)
            seconds < 3600 -> L.unitMinutes(seconds / 60)
            else -> L.unitHours(seconds / 3600)
        }
        return L.errorsFloodWait(wait)
    }

    private val FLOOD = Regex("""(?:FLOOD_WAIT_|retry after )(\d+)""", RegexOption.IGNORE_CASE)

    private const val TAG = "Failures"

    val OFFLINE: String get() = L.errorsOffline
    val DEFAULT: String get() = L.errorsNoAnswer
}
