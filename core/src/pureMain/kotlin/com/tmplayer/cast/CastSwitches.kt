package com.tmplayer.cast

import com.tmplayer.i18n.L

/**
 * Subtitles on the TV (CP73). While it is false the cast screens offer
 * no Subtitles button, an HLS master carries no subtitle renditions, a LOAD declares no side-loaded
 * text tracks, and a subtitle file loaded mid-cast stays on the phone, so a cast can never fail
 * for its subtitles' sake. The work for them stays behind this switch.
 */
const val CAST_SUBTITLES = true

/**
 * What the cast screen's subtitle picker says (WP-82), one rule for every platform that casts.
 *
 * While subtitles are sent ([sent], that is [CAST_SUBTITLES]): the file's own text subtitles stay
 * behind unless the cast carries them in an HLS master ([hls]), so a file with some says so. While
 * they are not sent: a file with subtitles at all (its own text tracks, or a file the viewer
 * loaded) says "Subtitles are not sent to the TV yet" instead of offering tracks that do nothing,
 * and a file with none says nothing, the picker having no button then.
 */
object CastSubtitleNote {
    fun note(sent: Boolean, hasText: Boolean, hasLoadedFiles: Boolean, hls: Boolean): String? = when {
        !sent -> if (hasText || hasLoadedFiles) L.castSubtitlesNotSent else null
        !hls && hasText -> L.castSubtitlesInside
        else -> null
    }

    /** Whether the Subtitles button stays while subtitles are not sent: only for a file that has some, to say why. */
    fun buttonStays(sent: Boolean, hasText: Boolean, hasLoadedFiles: Boolean): Boolean = sent || hasText || hasLoadedFiles
}
