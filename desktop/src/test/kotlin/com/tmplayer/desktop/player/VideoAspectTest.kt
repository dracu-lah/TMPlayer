package com.tmplayer.desktop.player

import com.tmplayer.player.Stereo3d
import com.tmplayer.player.VideoScale
import org.junit.Assert.assertEquals
import org.junit.Test

/** HX16: the one owner of mpv's `video-aspect-override`, weighing the picture shape against 3D. */
class VideoAspectTest {

    private val sbs = Stereo3d(Stereo3d.Layout.SideBySide)

    @Test
    fun `a flat video at Original, Fill or Stretch keeps the file's own aspect`() {
        for (scale in listOf(VideoScale.Fit, VideoScale.Crop, VideoScale.Stretch)) {
            assertEquals(VideoAspect.NONE, VideoAspect.override(scale, Stereo3d.OFF, 1920, 1080))
        }
    }

    @Test
    fun `a fixed shape sets its ratio, the frame's size known or not`() {
        assertEquals("1.777778", VideoAspect.override(VideoScale.Ratio16x9, Stereo3d.OFF, 1920, 1080))
        assertEquals("1.333333", VideoAspect.override(VideoScale.Ratio4x3, Stereo3d.OFF, 0, 0))
        assertEquals("2.333333", VideoAspect.override(VideoScale.Ratio21x9, Stereo3d.OFF, 1920, 800))
        assertEquals("2.350000", VideoAspect.override(VideoScale.Ratio235, Stereo3d.OFF, 1920, 800))
        assertEquals("1.000000", VideoAspect.override(VideoScale.Square, Stereo3d.OFF, 1920, 1080))
    }

    @Test
    fun `3D gives one eye's shape unless a fixed shape is chosen, which wins`() {
        val eye = Stereo3d.aspect(1920, 1080, sbs)!!
        assertEquals(String.format(java.util.Locale.ROOT, "%.6f", eye), VideoAspect.override(VideoScale.Fit, sbs, 1920, 1080))
        // Fill and Stretch work on the eye through panscan and keepaspect, after this aspect.
        assertEquals(VideoAspect.override(VideoScale.Fit, sbs, 1920, 1080), VideoAspect.override(VideoScale.Crop, sbs, 1920, 1080))
        assertEquals(VideoAspect.override(VideoScale.Fit, sbs, 1920, 1080), VideoAspect.override(VideoScale.Stretch, sbs, 1920, 1080))
        assertEquals("1.333333", VideoAspect.override(VideoScale.Ratio4x3, sbs, 1920, 1080))
        // In the VR view the shape is each eye's, as on Android: two 4:3 eyes side by side.
        assertEquals("2.666667", VideoAspect.override(VideoScale.Ratio4x3, sbs.copy(view = Stereo3d.View.Vr), 1920, 1080))
    }

    @Test
    fun `3D before the frame's size is known leaves the aspect alone`() {
        assertEquals(VideoAspect.NONE, VideoAspect.override(VideoScale.Fit, sbs, 0, 0))
    }

    /**
     * The engine's own path ([AspectOwner], [VideoAspect.fill]) over every shape, with 3D off and
     * in each view, on a squeezed side by side frame, a full one and a top and bottom one. The
     * table of what mpv is given lands in build/aspect-matrix.txt.
     */
    @Test
    fun `every shape on side by side and top and bottom files, 3D off and in each view`() {
        val files = listOf(
            Triple("SBS squeezed 1920x1080", Stereo3d.Layout.SideBySide, 1920 to 1080),
            Triple("SBS full 3840x1080", Stereo3d.Layout.SideBySide, 3840 to 1080),
            Triple("TAB squeezed 1920x1080", Stereo3d.Layout.TopBottom, 1920 to 1080),
        )
        val rows = mutableListOf("file | shape | 3D | video-aspect-override | keepaspect | panscan")
        for ((name, layout, size) in files) {
            for (scale in VideoScale.entries) {
                val owner = AspectOwner()
                owner.scale(scale)
                val fill = VideoAspect.fill(scale)
                assertEquals(scale == VideoScale.Stretch, !fill.keepAspect)
                assertEquals(if (scale == VideoScale.Crop) 1.0 else 0.0, fill.panscan, 0.0)
                for (stereo in listOf(Stereo3d.OFF) + Stereo3d.View.entries.map { Stereo3d(layout).copy(view = it) }) {
                    val got = if (stereo.on) owner.stereo(stereo, size.first, size.second) else owner.stereoOff()
                    val want = when {
                        // In VR a fixed shape is each eye's: the pair is twice as wide.
                        scale.ratio != null -> String.format(java.util.Locale.ROOT, "%.6f", scale.ratio!! * if (stereo.on && stereo.view == Stereo3d.View.Vr) 2 else 1)
                        stereo.on -> String.format(java.util.Locale.ROOT, "%.6f", Stereo3d.aspect(size.first, size.second, stereo)!!)
                        else -> VideoAspect.NONE
                    }
                    assertEquals("$name $scale $stereo", want, got)
                    rows += "$name | ${scale.label} | ${if (stereo.on) stereo.view.name else "off"} | $got | ${fill.keepAspect} | ${fill.panscan}"
                }
            }
        }
        java.io.File("build").apply { mkdirs() }.resolve("aspect-matrix.txt").writeText(rows.joinToString("\n") + "\n")
    }

    @Test
    fun `3D turned on and off mid video keeps a chosen ratio, and Original gets the eye's shape back`() {
        val owner = AspectOwner()
        val eye = String.format(java.util.Locale.ROOT, "%.6f", Stereo3d.aspect(1920, 1080, sbs)!!)
        assertEquals(VideoAspect.NONE, owner.scale(VideoScale.Fit))
        assertEquals(eye, owner.stereo(sbs, 1920, 1080))
        assertEquals("1.333333", owner.scale(VideoScale.Ratio4x3))
        assertEquals("1.333333", owner.stereoOff())
        assertEquals("1.333333", owner.stereo(sbs, 1920, 1080))
        assertEquals(eye, owner.scale(VideoScale.Fit))
        assertEquals(eye, owner.scale(VideoScale.Stretch))
        assertEquals("2.350000", owner.scale(VideoScale.Ratio235))
        assertEquals("2.350000", owner.stereoOff())
        assertEquals(VideoAspect.NONE, owner.scale(VideoScale.Crop))
    }

    @Test
    fun `turning 3D off goes back to the chosen ratio, not to none`() {
        assertEquals("2.350000", VideoAspect.override(VideoScale.Ratio235, Stereo3d.OFF, 1920, 1080))
    }
}
