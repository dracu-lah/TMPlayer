package com.tmplayer.desktop.player

import com.tmplayer.i18n.L

/**
 * mpv's error codes in words a viewer can act on (HX10, GitHub #14).
 *
 * mediamp hands a failure on as "mpv playback failed (mpv_error=-17)", which said nothing to the
 * viewer who saw it. Each code mpv's client API can end a load with gets a plain line with the next
 * step; the code itself stays on a second line, so "Copy details" and a bug report still carry it.
 * The numbers are `mpv_error` in mpv's client.h.
 */
object MpvErrors {

    const val NOMEM = -2
    const val LOADING_FAILED = -13
    const val AO_INIT_FAILED = -14
    const val VO_INIT_FAILED = -15
    const val NOTHING_TO_PLAY = -16
    const val UNKNOWN_FORMAT = -17
    const val UNSUPPORTED = -18

    private val CODE = Regex("""mpv_error\s*=\s*(-?\d{1,4})(?!\d)""")

    /** The `mpv_error` number in [message], or null when it carries none. */
    fun codeIn(message: String?): Int? =
        message?.let { CODE.find(it) }?.groupValues?.getOrNull(1)?.toIntOrNull()

    /**
     * What the player's error sheet says for a failure whose text is [message] and whose kind is
     * [kind] (mediamp's PlaybackErrorCode, as text). Never empty, never throws.
     */
    fun describe(message: String?, kind: String?): String {
        val code = codeIn(message)
        val plain = code?.let(::plainFor)
        return when {
            plain != null -> "$plain\n(mpv_error=$code)"
            !message.isNullOrBlank() -> message
            else -> L.playerPlaybackFailed(kind ?: "?")
        }
    }

    /** The plain line for [code], or null for a code with nothing better to say than its number. */
    fun plainFor(code: Int): String? = when (code) {
        NOMEM -> L.playerMpvNoMemory
        LOADING_FAILED -> L.playerMpvLoadingFailed
        AO_INIT_FAILED -> L.playerMpvAudioFailed
        VO_INIT_FAILED -> L.playerMpvVideoFailed
        NOTHING_TO_PLAY -> L.playerMpvNothingToPlay
        UNKNOWN_FORMAT -> L.playerMpvUnknownFormat
        UNSUPPORTED -> L.playerMpvUnsupported
        else -> null
    }
}
