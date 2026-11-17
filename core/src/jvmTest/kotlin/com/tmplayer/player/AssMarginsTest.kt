package com.tmplayer.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Issue 36: an ASS track whose dialogue style keeps its lines a sixth of the picture up stayed there
 * on the desktop with the position at Bottom. These are the `sub-ass-style-overrides` mpv is given.
 */
class AssMarginsTest {

    private fun header(styles: String, info: String = "PlayResX: 384\nPlayResY: 288") = """
        [Script Info]
        ScriptType: v4.00+
        $info

        [V4+ Styles]
        Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
        $styles

        [Events]
        Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
        Style: Late,Arial,20,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,1,0,2,10,10,90,1
    """.trimIndent()

    private fun style(name: String, alignment: Int, marginV: Int) =
        "Style: $name,Arial,20,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,1,0,$alignment,10,10,$marginV,1"

    @Test
    fun `a high bottom margin comes down to mpv's own for plain lines, in the script's units`() {
        // 22 of 720 lines is 9 of 288 (8.8 rounded).
        assertEquals("Default.MarginV=9", AssMargins.bottomOverrides(header(style("Default", 2, 40))))
        // A 1080 line script: 33.
        assertEquals(
            "Default.MarginV=33",
            AssMargins.bottomOverrides(header(style("Default", 2, 120), "PlayResX: 1920\nPlayResY: 1080")),
        )
    }

    @Test
    fun `top, middle and already low styles are left as the author made them`() {
        val styles = listOf(
            style("Default", 2, 40),
            style("Left", 1, 60),
            style("Song", 8, 40),
            style("Middle", 5, 40),
            style("Low", 2, 5),
        ).joinToString("\n")
        assertEquals("Default.MarginV=9,Left.MarginV=9", AssMargins.bottomOverrides(header(styles)))
    }

    @Test
    fun `a missing script height follows libass`() {
        assertEquals("Default.MarginV=9", AssMargins.bottomOverrides(header(style("Default", 2, 40), info = "")))
        // Width alone: three quarters of it, 1280 being 1024.
        assertEquals("Default.MarginV=16", AssMargins.bottomOverrides(header(style("Default", 2, 40), info = "PlayResX: 680")))
        assertEquals("Default.MarginV=31", AssMargins.bottomOverrides(header(style("Default", 2, 90), info = "PlayResX: 1280")))
    }

    @Test
    fun `nothing to do gives the empty list, which clears an earlier track's`() {
        assertEquals("", AssMargins.bottomOverrides(null))
        assertEquals("", AssMargins.bottomOverrides(""))
        assertEquals("", AssMargins.bottomOverrides("1\n00:00:01,000 --> 00:00:02,000\nHello"))
        assertEquals("", AssMargins.bottomOverrides(header(style("Default", 2, 5))))
        // Styles without their Format line cannot be read.
        assertEquals("", AssMargins.bottomOverrides("[V4+ Styles]\n" + style("Default", 2, 40)))
    }

    @Test
    fun `names mpv's list cannot carry are skipped, the rest kept, and each named once`() {
        val styles = listOf(
            style("Main Dia.log", 2, 40),
            style("Bad=Name", 2, 40),
            style("Main Dia.log", 2, 50),
            "Style: ,Arial",
            "Style: Short",
            style("NoMargin", 2, 0).replace(",10,10,0,1", ",10,10,lots,1"),
        ).joinToString("\n")
        assertEquals("Main Dia.log.MarginV=9", AssMargins.bottomOverrides(header(styles)))
    }

    @Test
    fun `the old SSA numbering counts 1 to 3 as bottom too`() {
        val ssa = """
            [Script Info]
            PlayResY: 288
            [V4 Styles]
            Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, TertiaryColour, BackColour, Bold, Italic, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, AlphaLevel, Encoding
            Style: Default,Arial,20,16777215,65535,65535,0,0,0,1,1,0,2,10,10,40,0,1
            Style: Top,Arial,20,16777215,65535,65535,0,0,0,1,1,0,6,10,10,40,0,1
        """.trimIndent()
        assertEquals("Default.MarginV=9", AssMargins.bottomOverrides(ssa))
    }
}
