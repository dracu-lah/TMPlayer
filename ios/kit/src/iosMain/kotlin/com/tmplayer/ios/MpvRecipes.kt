package com.tmplayer.ios

import com.tmplayer.desktop.player.BOOSTED_VOLUME_MAX
import com.tmplayer.desktop.player.PictureShapeOnIos
import com.tmplayer.player.Stereo3d
import com.tmplayer.player.SubtitleFace
import com.tmplayer.player.SubtitleStyle
import com.tmplayer.player.VideoScale

/** One mpv property and the value to give it. */
data class MpvProperty(val name: String, val value: String)

/**
 * What upstream's shared code already knows about driving mpv, for an mpv engine on iOS: the same
 * properties the desktop's MpvPlaybackEngine sets, from the same functions, so an mpv engine here
 * follows upstream when those change. Other engines have no use for it.
 */
object MpvRecipes {
    /** mpv's `volume-max` with Volume boost on. */
    val boostedVolumeMax: Int = BOOSTED_VOLUME_MAX

    /** The picture's shape: panscan, keepaspect and the aspect override, as the desktop's applyScale. */
    fun shape(scale: VideoScale, stereo: Stereo3d, width: Int, height: Int): List<MpvProperty> = listOf(
        MpvProperty("panscan", PictureShapeOnIos.panscan(scale).toString()),
        MpvProperty("keepaspect", if (PictureShapeOnIos.keepAspect(scale)) "yes" else "no"),
        MpvProperty("video-aspect-override", PictureShapeOnIos.aspectOverride(scale, stereo, width, height)),
    )

    /**
     * Plain text subtitles in [style], as the desktop's applySubtitleStyle: inside the picture
     * unless [zoomed], where the window holds them. Applied in order; where `sub-border-style`
     * refuses `background-box`, [boxFallback] stands in for it.
     */
    fun subtitleStyle(style: SubtitleStyle, zoomed: Boolean): List<MpvProperty> =
        listOf(MpvProperty("sub-use-margins", if (zoomed) "yes" else "no")) +
            style.mpvProperties(font(style.face)).map { MpvProperty(it.first, it.second) }

    /** The opaque box an older mpv draws in place of `background-box`. */
    fun boxFallback(style: SubtitleStyle): List<MpvProperty> = listOf(
        MpvProperty("sub-outline-color", SubtitleStyle.mpvColour(style.boxLevel.argb)),
        MpvProperty("sub-border-style", "opaque-box"),
    )

    /** The shader that draws [stereo] for a [width] by [height] frame, to load through `glsl-shaders`. */
    fun stereoShader(stereo: Stereo3d, width: Int, height: Int): String = Stereo3d.mpvShader(stereo, width, height)

    /** The desktop's fonts on a Mac, which iOS has too. */
    private fun font(face: SubtitleFace): String = when (face) {
        SubtitleFace.Default -> "sans-serif"
        SubtitleFace.Serif -> "Times New Roman"
        SubtitleFace.Monospace -> "Menlo"
    }
}
