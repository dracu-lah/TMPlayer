package com.tmplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class SubtitleStyleTest {

    @Test
    fun `unknown or missing names fall back to the defaults`() {
        assertEquals(SubtitleStyle(), SubtitleStyle.from(null, null, null))
        assertEquals(SubtitleStyle(), SubtitleStyle.from("Huge", null, "Sideways"))
        assertEquals(
            SubtitleStyle(SubtitleSize.Large, boxLevel = SubtitleBox.LEGACY_ON, position = SubtitlePosition.High),
            SubtitleStyle.from("Large", true, "High"),
        )
    }

    @Test
    fun `medium keeps the size both players used before the setting`() {
        assertEquals(0.065f, SubtitleSize.Medium.fraction, 0f)
        assertEquals(46, SubtitleSize.Medium.mpvFontSize)
    }

    /**
     * Issues 32 and 36: an ASS track brings its own size, which `sub-font-size` (mpv) and the view's
     * text size (Media3) never reach. The size is a scale both players put on a track's own size,
     * and a plain line still lands on the size it had.
     */
    @Test
    fun `the size reaches a track that brings its own, as a scale against medium`() {
        fun props(size: SubtitleSize) = SubtitleStyle(size).mpvProperties("sans-serif").toMap()
        for (size in SubtitleSize.entries) {
            // The font size stays Medium's, so mpv's sub-scale is all that moves a line.
            assertEquals("46", props(size)["sub-font-size"])
            val scale = props(size)["sub-scale"]?.toDouble() ?: error("no sub-scale for $size")
            assertEquals(size.mpvFontSize.toDouble(), 46 * scale, 0.05)
        }
        assertEquals("0.826", SubtitleSize.Small.mpvScale)
        assertEquals("1.000", SubtitleSize.Medium.mpvScale)
        assertEquals("1.435", SubtitleSize.ExtraLarge.mpvScale)
        assertEquals(1f, SubtitleSize.Medium.cueScale, 0f)
        assertEquals(0.8f, SubtitleSize.Small.cueScale, 0.001f)
        assertTrue(SubtitleSize.entries.zipWithNext().all { (a, b) -> a.cueScale < b.cueScale })
    }

    @Test
    fun `size and position wrap`() {
        val style = SubtitleStyle(SubtitleSize.ExtraLarge, position = SubtitlePosition.High)
        assertEquals(SubtitleSize.Small, style.nextSize().size)
        assertEquals(SubtitlePosition.Bottom, style.nextPosition().position)
    }

    @Test
    fun `every choice reads back by name and a step beats the old box switch`() {
        val style = SubtitleStyle(
            size = SubtitleSize.Small,
            boxLevel = SubtitleBox.Light,
            position = SubtitlePosition.Raised,
            colour = SubtitleColour.Yellow,
            edge = SubtitleEdge.Shadow,
            face = SubtitleFace.Monospace,
            bold = true,
        )
        val back = SubtitleStyle.from(
            style.size.name, style.box, style.boxLevel.name, style.position.name,
            style.colour.name, style.edge.name, style.face.name, style.bold,
        )
        assertEquals(style, back)
        // A store from before the steps had the switch alone: on reads as the nearest step.
        assertEquals(SubtitleBox.Dark, SubtitleStyle.from("Medium", true, null, null, null, null, null, null).boxLevel)
        assertEquals(SubtitleBox.Off, SubtitleStyle.from("Medium", false, null, null, null, null, null, null).boxLevel)
        // Unknown names fall back one by one, the rest kept.
        val odd = SubtitleStyle.from("Large", null, "Murky", null, "Purple", "Glow", "Comic", null)
        assertEquals(SubtitleStyle(size = SubtitleSize.Large), odd)
    }

    @Test
    fun `the default look is the one before the setting`() {
        val style = SubtitleStyle()
        assertTrue(style.isDefault)
        assertEquals(SubtitleColour.White, style.colour)
        assertEquals(SubtitleEdge.Outline, style.edge)
        assertFalse(style.box)
        assertFalse(style.nextColour().isDefault)
    }

    @Test
    fun `box darkness is black at its percentage`() {
        assertEquals(0, SubtitleBox.Off.argb)
        assertEquals(0xFF000000.toInt(), SubtitleBox.Solid.argb)
        assertEquals(0x59000000, SubtitleBox.Light.argb) // 35 % of 255 is 89
        assertEquals(0x99000000.toInt(), SubtitleBox.Medium.argb)
        assertEquals(0xCC000000.toInt(), SubtitleBox.Dark.argb)
        assertEquals(listOf(0, 35, 60, 80, 100), SubtitleBox.entries.map { it.percent })
    }

    @Test
    fun `colours are opaque and spelt for mpv`() {
        SubtitleColour.entries.forEach { assertEquals(0xFF, it.argb ushr 24) }
        assertEquals("#FFFFFF", SubtitleColour.White.hex)
        assertEquals("#FFE14D", SubtitleColour.Yellow.hex)
        assertEquals("#CC000000", SubtitleStyle.mpvColour(SubtitleBox.Dark.argb))
    }

    @Test
    fun `mpv draws the outline, the shadow or neither`() {
        fun props(style: SubtitleStyle) = style.mpvProperties("sans-serif").toMap()
        val outline = props(SubtitleStyle())
        assertEquals("outline-and-shadow", outline["sub-border-style"])
        assertEquals(SubtitleStyle.MPV_OUTLINE, outline["sub-border-size"])
        assertEquals("0", outline["sub-shadow-offset"])
        assertEquals("#FFFFFF", outline["sub-color"])
        assertEquals("no", outline["sub-bold"])
        assertEquals("46", outline["sub-font-size"])
        assertEquals("100", outline["sub-pos"])

        val shadow = props(SubtitleStyle(edge = SubtitleEdge.Shadow, bold = true))
        assertEquals("0", shadow["sub-border-size"])
        assertEquals(SubtitleStyle.MPV_SHADOW_OFFSET, shadow["sub-shadow-offset"])
        assertEquals("yes", shadow["sub-bold"])

        val none = props(SubtitleStyle(edge = SubtitleEdge.None))
        assertEquals("0", none["sub-border-size"])
        assertEquals("0", none["sub-shadow-offset"])
    }

    @Test
    fun `mpv boxes the text at the chosen darkness and drops the edge`() {
        val boxed = SubtitleStyle(boxLevel = SubtitleBox.Medium, colour = SubtitleColour.Cyan).mpvProperties("serif").toMap()
        assertEquals("background-box", boxed["sub-border-style"])
        assertEquals("#99000000", boxed["sub-back-color"])
        assertEquals("0", boxed["sub-border-size"])
        assertEquals("serif", boxed["sub-font"])
        assertEquals("#5CE6FF", boxed["sub-color"])
        // The border style is set after the colours it uses, so a libmpv that refuses it keeps them.
        val names = SubtitleStyle(boxLevel = SubtitleBox.Medium).mpvProperties("x").map { it.first }
        assertTrue(names.indexOf("sub-back-color") < names.indexOf("sub-border-style"))
    }

    @Test
    fun `every look choice cycles through its steps`() {
        var style = SubtitleStyle()
        repeat(SubtitleBox.entries.size) { style = style.nextBox() }
        assertEquals(SubtitleBox.Off, style.boxLevel)
        assertEquals(SubtitleColour.Yellow, SubtitleStyle().nextColour().colour)
        assertEquals(SubtitleEdge.Shadow, SubtitleStyle().nextEdge().edge)
        assertEquals(SubtitleFace.Default, SubtitleStyle(face = SubtitleFace.Monospace).nextFace().face)
    }

    @Test
    fun `delays round trip and nothing is stored for none`() {
        assertNull(SyncDelays().encode())
        val delays = SyncDelays(subtitleMs = -300, audioMs = 1200)
        assertEquals(delays, SyncDelays.decode(delays.encode()))
        assertEquals(SyncDelays(), SyncDelays.decode("garbage"))
        assertEquals(SyncDelays(), SyncDelays.decode(null))
    }

    @Test
    fun `delays step by a tenth and stop at the limit`() {
        assertEquals(100L, SyncDelays.step(0, 1))
        assertEquals(-100L, SyncDelays.step(0, -1))
        assertEquals(SyncDelays.MAX_MS, SyncDelays.step(SyncDelays.MAX_MS, 1))
        assertEquals(-SyncDelays.MAX_MS, SyncDelays.decode("-99999,0").subtitleMs)
        assertEquals(300L, SyncDelays.clamp(251))
    }

    @Test
    fun `labels read as signed seconds`() {
        assertEquals("0 s", SyncDelays.label(0))
        assertEquals("+0.3 s", SyncDelays.label(300))
        assertEquals("-1.2 s", SyncDelays.label(-1200))
        assertEquals("+10.0 s", SyncDelays.label(10_000))
    }
}
