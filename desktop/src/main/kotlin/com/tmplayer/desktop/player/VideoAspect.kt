package com.tmplayer.desktop.player

import com.tmplayer.player.Stereo3d
import com.tmplayer.player.VideoScale

/**
 * The one owner of mpv's `video-aspect-override` (HX16). Two things want it: a fixed picture shape
 * (16:9, 4:3...) and 3D, whose shader keeps the frame's size and draws one eye (or the VR pair) at
 * that frame's shape. A fixed shape wins, else 3D's eye aspect, else "no", the file's own; so
 * turning 3D on or off never wipes a chosen ratio, and choosing Original gives 3D its shape back.
 *
 * Fill and Stretch need nothing here: mpv's panscan and keepaspect act on the picture after this
 * aspect, so they crop or stretch a 3D eye as they would a flat frame.
 */
internal object VideoAspect {

    /** mpv's word for "the file's own aspect". */
    const val NONE = "no"

    /**
     * What to give `video-aspect-override` for [scale] with [stereo] drawn on a [width] by
     * [height] frame (0 while it is not known yet, which leaves 3D's shape out until it is).
     */
    fun override(scale: VideoScale, stereo: Stereo3d, width: Int, height: Int): String {
        scale.ratio?.let { return format(it) }
        return Stereo3d.aspect(width, height, stereo)?.let(::format) ?: NONE
    }

    /** mpv's keepaspect and panscan for a shape: Fill crops by panscan, Stretch drops the aspect. */
    data class Fill(val keepAspect: Boolean, val panscan: Double)

    fun fill(scale: VideoScale): Fill = when (scale) {
        VideoScale.Crop -> Fill(keepAspect = true, panscan = 1.0)
        VideoScale.Stretch -> Fill(keepAspect = false, panscan = 0.0)
        else -> Fill(keepAspect = true, panscan = 0.0)
    }

    private fun format(aspect: Double): String = String.format(java.util.Locale.ROOT, "%.6f", aspect)
}

/**
 * What [MpvPlaybackEngine] last gave mpv for the shape and the 3D, each change answering with the
 * `video-aspect-override` to set now: the engine's one path to that property, kept apart from
 * libmpv so every case (each shape, each 3D view, 3D on and off mid video) is testable.
 */
internal class AspectOwner {
    private var scale = VideoScale.Fit
    private var drawn: Triple<Stereo3d, Int, Int>? = null

    @Synchronized
    fun scale(to: VideoScale): String {
        scale = to
        return now()
    }

    /** 3D drawn on a [width] by [height] frame. */
    @Synchronized
    fun stereo(stereo: Stereo3d, width: Int, height: Int): String {
        drawn = Triple(stereo, width, height).takeIf { stereo.on }
        return now()
    }

    @Synchronized
    fun stereoOff(): String {
        drawn = null
        return now()
    }

    @Synchronized
    fun now(): String = drawn.let { VideoAspect.override(scale, it?.first ?: Stereo3d.OFF, it?.second ?: 0, it?.third ?: 0) }
}
