package com.tmplayer.player

import android.graphics.Color
import android.graphics.Typeface
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat

/**
 * [SubtitleStyle] in the terms Media3's SubtitleView draws with. A file's own styled cues (ASS
 * colours, a WebVTT class) keep their spans: the caption style fills in only what a cue leaves
 * unset, which for plain SRT is everything.
 */
object SubtitleLook {

    /** The family for [face] in [bold] or regular weight; null leaves SubtitleView's own default. */
    fun typeface(face: SubtitleFace, bold: Boolean): Typeface? {
        val family = when (face) {
            SubtitleFace.Default -> if (bold) Typeface.DEFAULT else return null
            SubtitleFace.Serif -> Typeface.SERIF
            SubtitleFace.Monospace -> Typeface.MONOSPACE
        }
        return Typeface.create(family, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    @OptIn(UnstableApi::class)
    fun edgeType(edge: SubtitleEdge): Int = when (edge) {
        SubtitleEdge.Outline -> CaptionStyleCompat.EDGE_TYPE_OUTLINE
        SubtitleEdge.Shadow -> CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
        SubtitleEdge.None -> CaptionStyleCompat.EDGE_TYPE_NONE
    }

    /**
     * The caption style for [style]. With a box the edge is dropped, as on the desktop: the box
     * is what separates the letters then, and an outline inside it only thickens them. [script]
     * is a bundled font the lines need ([SubtitleScripts]), which then stands in for the face.
     */
    @OptIn(UnstableApi::class)
    fun captionStyle(style: SubtitleStyle, script: Typeface? = null): CaptionStyleCompat = CaptionStyleCompat(
        style.colour.argb,
        if (style.box) style.boxLevel.argb else Color.TRANSPARENT,
        Color.TRANSPARENT,
        if (style.box) CaptionStyleCompat.EDGE_TYPE_NONE else edgeType(style.edge),
        Color.BLACK,
        script?.let { Typeface.create(it, if (style.bold) Typeface.BOLD else Typeface.NORMAL) }
            ?: typeface(style.face, style.bold),
    )
}
