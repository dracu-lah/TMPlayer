package com.tmplayer.player

import androidx.annotation.OptIn
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi

/**
 * The cues of a track that brings its own size and place, fitted to the viewer's subtitle look.
 *
 * Media3's SubtitleView draws a cue that carries a text size at that size and a cue with a line at
 * that line, so neither the view's text size nor its bottom padding reached an SSA or ASS track:
 * Size and Position in Settings did nothing on those (issue 32). SsaParser gives every line its
 * style's font size, and every bottom line that is not placed with `\pos` the line 0.95 anchored
 * at its end, its own default. So a cue's own size is scaled by [SubtitleSize.cueScale], and a
 * bottom line at that default is handed back to the view's bottom padding, where a plain line
 * sits. Lines placed elsewhere (top, middle, `\pos`) and picture cues keep the file's place.
 */
@OptIn(UnstableApi::class)
object SubtitleCueFit {

    /** SsaParser's line for a bottom aligned cue without a position of its own. */
    const val SSA_BOTTOM_LINE = 0.95f

    /**
     * [cues] for [style], with their own sizes also scaled by [pictureScale] (the floor a small
     * upright picture gets, see PlayerActivity.subtitleTextScale). The same list when nothing changes.
     */
    fun fit(cues: List<Cue>, style: SubtitleStyle, pictureScale: Float): List<Cue> {
        val scale = pictureScale * style.cueScale
        if (cues.none { needsFit(it, scale) }) return cues
        return cues.map { cue ->
            if (!needsFit(cue, scale)) return@map cue
            val built = cue.buildUpon()
            if (hasOwnSize(cue)) built.setTextSize(cue.textSize * scale, cue.textSizeType)
            if (atSsaBottom(cue)) built.setLine(Cue.DIMEN_UNSET, Cue.TYPE_UNSET)
            built.build()
        }
    }

    private fun needsFit(cue: Cue, scale: Float): Boolean =
        cue.bitmap == null && ((hasOwnSize(cue) && scale != 1f) || atSsaBottom(cue))

    private fun hasOwnSize(cue: Cue): Boolean =
        cue.textSizeType != Cue.TYPE_UNSET && cue.textSize != Cue.DIMEN_UNSET && cue.textSize.isFinite()

    /** A bottom line where SsaParser puts every bottom line it has no position for. */
    private fun atSsaBottom(cue: Cue): Boolean =
        cue.lineType == Cue.LINE_TYPE_FRACTION && cue.line == SSA_BOTTOM_LINE && cue.lineAnchor == Cue.ANCHOR_TYPE_END
}
