package com.tmplayer.desktop.player

import com.tmplayer.player.Stereo3d
import com.tmplayer.player.VideoScale

// The desktop's VideoAspect is internal to the module it is compiled into, this one on iOS; the
// engines' recipes in :ios:kit (MpvRecipes) read it through here, so the shapes stay upstream's.

/** mpv's keepaspect, panscan and video-aspect-override for a picture shape, as the desktop sets them. */
object PictureShapeOnIos {
    fun keepAspect(scale: VideoScale): Boolean = VideoAspect.fill(scale).keepAspect

    fun panscan(scale: VideoScale): Double = VideoAspect.fill(scale).panscan

    fun aspectOverride(scale: VideoScale, stereo: Stereo3d, width: Int, height: Int): String =
        VideoAspect.override(scale, stereo, width, height)
}
