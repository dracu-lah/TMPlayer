package com.tmplayer.player

import com.tmplayer.player.Stereo3d.Layout
import com.tmplayer.player.Stereo3d.View
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Stereo3dTest {

    @Test
    fun releaseTagsNameTheLayout() {
        assertEquals(Layout.SideBySide, Stereo3d.detect("Ocean.Trip.2019.3D.HSBS.1080p.mkv"))
        assertEquals(Layout.SideBySide, Stereo3d.detect("ocean trip (2019) half-sbs.mp4"))
        assertEquals(Layout.SideBySide, Stereo3d.detect("Ocean_Trip_Full_SBS.mkv"))
        assertEquals(Layout.SideBySide, Stereo3d.detect("Ocean Trip [Side by Side].mkv"))
        assertEquals(Layout.TopBottom, Stereo3d.detect("Ocean.Trip.3D.HTAB.mkv"))
        assertEquals(Layout.TopBottom, Stereo3d.detect("Ocean Trip Top and Bottom.mp4"))
        assertEquals(Layout.TopBottom, Stereo3d.detect("Ocean.Trip.3D.Half-OU.mkv"))
        assertEquals(Layout.TopBottom, Stereo3d.detect("Ocean.Trip.3D.HOU.1080p.mkv"))
        assertEquals(Layout.TopBottom, Stereo3d.detect("Ocean Trip 3D Over-Under.mkv"))
        assertEquals(Layout.TopBottom, Stereo3d.detect("Ocean.Trip.3D.HTB.mkv"))
        assertEquals(Layout.TopBottom, Stereo3d.detect("Ocean Trip (3D Half-TB).mp4"))
    }

    @Test
    fun wordsThatOnlyLookLikeTagsDoNot() {
        assertNull(Stereo3d.detect("Subsonic.Tabs.Guide.mkv"))
        assertNull(Stereo3d.detect("Holiday sbsx clip.mp4"))
        // "ou" is French for "or": only beside a 3D tag.
        assertNull(Stereo3d.detect("Vrai ou faux.mp4"))
        // "TB" alone is as likely a size.
        assertNull(Stereo3d.detect("Backup of the 1 TB drive.mp4"))
        assertNull(Stereo3d.detect("Ocean.Trip.2019.1080p.mkv"))
    }

    @Test
    fun aFullSizeFrameIsHalvedAndASqueezedOneKeepsItsShape() {
        assertEquals(1920 to 1080, Stereo3d.outputSize(3840, 1080, Layout.SideBySide))
        assertEquals(1920 to 1080, Stereo3d.outputSize(1920, 1080, Layout.SideBySide))
        assertEquals(1920 to 1080, Stereo3d.outputSize(1920, 2160, Layout.TopBottom))
        assertEquals(1920 to 1080, Stereo3d.outputSize(1920, 1080, Layout.TopBottom))
        // A scope film squeezed side by side keeps its own 2.39.
        assertFalse(Stereo3d.fullSize(1920, 804, Layout.SideBySide))
        assertTrue(Stereo3d.fullSize(3840, 804, Layout.SideBySide))
        assertEquals(1280 to 720, Stereo3d.outputSize(1280, 720, Layout.Off))
        assertNull(Stereo3d.aspect(1920, 1080, Layout.Off))
    }

    @Test
    fun duboisRowsKeepWhiteRoughlyWhite() {
        // Grey in both eyes stays near grey through the glasses: each output row sums near 1.
        for (view in listOf(View.RedCyan, View.GreenMagenta, View.YellowBlue)) {
            val m = Stereo3d.matrix(view)!!
            for (row in 0 until 3) {
                val sum = (0 until 6).sumOf { m[row * 6 + it].toDouble() }
                assertTrue(sum in 0.8..1.4, "$view row $row sums to $sum")
            }
        }
        assertNull(Stereo3d.matrix(View.OneEye))
    }

    @Test
    fun theShaderSamplesEachEyeFromItsOwnHalf() {
        val body = Stereo3d.glslBody(Stereo3d(Layout.SideBySide), "tex", "p", yDown = true, texelX = 0.001f, texelY = 0.001f)
        assertTrue("min(p.x * 0.5" in body)
        assertTrue("max(0.5 + p.x * 0.5" in body)
        val swapped = Stereo3d.glslBody(Stereo3d(Layout.SideBySide, swapEyes = true), "tex", "p", yDown = true, texelX = 0.001f, texelY = 0.001f)
        assertTrue(swapped.indexOf("max(0.5 + p.x") < swapped.indexOf("min(p.x * 0.5"))
        val flat = Stereo3d.glslBody(Stereo3d(Layout.TopBottom, View.OneEye), "tex", "p", yDown = false, texelX = 0f, texelY = 0f)
        assertEquals("return vec4(tex(vec2(p.x, max(0.5 + p.y * 0.5, 0.5))).rgb, 1.0);", flat)
        assertTrue(Stereo3d.mpvShader(Stereo3d(Layout.SideBySide), 1920, 1080).startsWith("//!HOOK MAIN\n"))
    }

    @Test
    fun topAndBottomTakesTheLeftEyeFromTheTopHalf() {
        // mpv: y runs down the picture, so the top half is y from 0 to 0.5.
        val mpv = Stereo3d.glslBody(Stereo3d(Layout.TopBottom), "tex", "p", yDown = true, texelX = 0f, texelY = 0.001f)
        val left = mpv.lines().first { it.startsWith("vec3 l") }
        val right = mpv.lines().first { it.startsWith("vec3 r") }
        assertTrue("min(p.y * 0.5, 0.4995)" in left, left)
        assertTrue("max(0.5 + p.y * 0.5, 0.5005)" in right, right)
        // The x of either eye is the whole width: a top and bottom frame is not cut down the middle.
        assertTrue("vec2(p.x, " in left && "vec2(p.x, " in right)
        // Media3's textures: y runs up, so the top half is y from 0.5 to 1.
        val media3 = Stereo3d.glslBody(Stereo3d(Layout.TopBottom), "tex", "p", yDown = false, texelX = 0f, texelY = 0.001f)
        assertTrue("max(0.5 + p.y * 0.5, 0.5005)" in media3.lines().first { it.startsWith("vec3 l") })
        assertTrue("min(p.y * 0.5, 0.4995)" in media3.lines().first { it.startsWith("vec3 r") })
        // Swapped, the bottom half is the left eye.
        val swapped = Stereo3d.glslBody(Stereo3d(Layout.TopBottom, swapEyes = true), "tex", "p", yDown = true, texelX = 0f, texelY = 0.001f)
        assertTrue("0.5 + p.y * 0.5" in swapped.lines().first { it.startsWith("vec3 l") })
        // The shape: a full size frame is halved in height, never in width.
        assertEquals(1920 to 1080, Stereo3d.outputSize(1920, 2160, Layout.TopBottom))
        assertEquals(16.0 / 9, Stereo3d.aspect(1920, 2160, Layout.TopBottom)!!, 0.001)
        assertEquals(16.0 / 9, Stereo3d.aspect(1920, 1080, Layout.TopBottom)!!, 0.001)
        assertTrue(Stereo3d.mpvShader(Stereo3d(Layout.TopBottom), 1920, 1080).contains("HOOKED_pos.y * 0.5"))
    }

    @Test
    fun interleavedEyesReadTheirOwnLines() {
        // A 1920x1080 frame: 540 pairs of lines, the top line of each the left eye's.
        val mpv = Stereo3d.glslBody(Stereo3d(Layout.RowInterleaved), "tex", "p", yDown = true, texelX = 1f / 1920, texelY = 1f / 1080)
        val left = mpv.lines().first { it.startsWith("vec3 l") }
        val right = mpv.lines().first { it.startsWith("vec3 r") }
        assertTrue("floor(min(p.y, 0.99999) * 540.0) * 2.0 + 0.5) / 1080.0" in left, left)
        assertTrue("* 2.0 + 1.5) / 1080.0" in right, right)
        // Media3's y runs up: the line is counted from the top all the same.
        val media3 = Stereo3d.glslBody(Stereo3d(Layout.RowInterleaved), "tex", "p", yDown = false, texelX = 1f / 1920, texelY = 1f / 1080)
        assertTrue("(1.0 - p.y)" in media3 && "1.0 - (floor" in media3, media3)
        val columns = Stereo3d.glslBody(Stereo3d(Layout.ColumnInterleaved), "tex", "p", yDown = true, texelX = 1f / 1920, texelY = 1f / 1080)
        assertTrue("floor(min(p.x, 0.99999) * 960.0) * 2.0 + 0.5) / 1920.0" in columns.lines().first { it.startsWith("vec3 l") })
        // Each eye has half the lines, stretched back: the frame keeps its shape.
        assertEquals(1920 to 1080, Stereo3d.outputSize(1920, 1080, Layout.RowInterleaved))
        assertEquals(1920 to 1080, Stereo3d.outputSize(1920, 1080, Layout.ColumnInterleaved))
    }

    @Test
    fun vrPutsEachEyeInItsOwnHalf() {
        val vr = Stereo3d(Layout.TopBottom, View.Vr)
        val body = Stereo3d.glslBody(vr, "tex", "p", yDown = true, texelX = 0f, texelY = 0.001f)
        assertTrue("vec2 q = vec2(second ? p.x * 2.0 - 1.0 : p.x * 2.0, p.y);" in body, body)
        // The right half reads the bottom picture, the left half the top one.
        assertTrue(body.lines().first { it.startsWith("if (second)") }.contains("0.5 + q.y * 0.5"))
        assertTrue(body.lines().last().contains("min(q.y * 0.5"))
        assertTrue("dot(" !in body)
        // Two eyes wide, each at its own shape: a squeezed side by side frame doubles in width.
        assertEquals(3840 to 1080, Stereo3d.outputSize(1920, 1080, Stereo3d(Layout.SideBySide, View.Vr)))
        assertEquals(3840 to 1080, Stereo3d.outputSize(3840, 1080, Stereo3d(Layout.SideBySide, View.Vr)))
        assertEquals(3840 to 1080, Stereo3d.outputSize(1920, 2160, vr))
        // A 4K pair is kept within 3840 wide, its shape kept.
        assertEquals(3840 to 1080, Stereo3d.outputSize(3840, 2160, Stereo3d(Layout.SideBySide, View.Vr)))
        assertEquals(32.0 / 9, Stereo3d.aspect(1920, 1080, Stereo3d(Layout.SideBySide, View.Vr))!!, 0.001)
        assertNull(Stereo3d.matrix(View.Vr))
        // Off is off, whatever the view.
        assertEquals(1920 to 1080, Stereo3d.outputSize(1920, 1080, Stereo3d(Layout.Off, View.Vr)))
    }

    @Test
    fun viewsKeepTheirKeys() {
        View.entries.forEach { assertEquals(it, View.ofKey(it.key)) }
        assertEquals(View.RedCyan, View.ofKey(null))
    }

    @Test
    fun aShapedVrPairFillsTheScreenWithinTheCap() {
        assertEquals(1920 to 1080, Stereo3d.boxedSize(1920, 1080))
        assertEquals(3840 to 1080, Stereo3d.boxedSize(7680, 2160))
        assertEquals(0 to 0, Stereo3d.boxedSize(0, 1080))
    }

    @Test
    fun aVrEyeShareMovesEachEyeIntoItsBox() {
        val vr = Stereo3d(Layout.SideBySide, View.Vr)
        fun body(share: Pair<Float, Float>?) =
            Stereo3d.glslBody(vr, "texture2D", "p", yDown = false, texelX = 0.001f, texelY = 0.001f, eyeShare = share)
        val shaped = body(1f to 0.5f)
        assertTrue("vec2(1.0, 0.5)" in shaped)
        assertTrue("vec4(0.0, 0.0, 0.0, 1.0)" in shaped)
        // A whole half, or a share that makes no sense, keeps the plain pair.
        assertEquals(body(null), body(1f to 1f))
        assertEquals(body(null), body(Float.NaN to 0f))
    }
}
