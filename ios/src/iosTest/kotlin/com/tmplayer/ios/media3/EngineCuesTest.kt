package com.tmplayer.ios.media3

import android.graphics.Typeface
import android.text.Layout
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import androidx.media3.common.text.Cue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The engine's subtitle text as the cues Media3's SubripDecoder and SsaDecoder make. */
class EngineCuesTest {
    @Test
    fun noTextNoCues() {
        assertTrue(EngineCues.of(null, ass = false).isEmpty())
        assertTrue(EngineCues.of("", ass = true).isEmpty())
    }

    @Test
    fun subripWithoutTagsIsAPlainCueAtTheDefaultPlace() {
        val cue = EngineCues.of("Hello\\Nthere", ass = false).single()
        assertEquals("Hello\nthere", cue.text.toString())
        assertEquals(Cue.DIMEN_UNSET, cue.line)
        assertEquals(Cue.DIMEN_UNSET, cue.position)
        assertNull(cue.textAlignment)
    }

    @Test
    fun subripItalicAndColourBecomeSpans() {
        val cue = EngineCues.of("{\\i1}Whisper{\\i0} and {\\c&H0000FF&}red{\\c}", ass = false).single()
        val text = cue.text as Spanned
        assertEquals("Whisper and red", text.toString())
        val italic = text.spanRanges.single { it.what is StyleSpan }
        assertEquals(Typeface.ITALIC, (italic.what as StyleSpan).style)
        assertEquals(0 to 7, italic.start to italic.end)
        val red = text.spanRanges.single { it.what is ForegroundColorSpan }
        assertEquals(0xFFFF0000.toInt(), (red.what as ForegroundColorSpan).foregroundColor)
        assertEquals(12 to 15, red.start to red.end)
    }

    @Test
    fun subripTopAlignmentStandsEightPercentFromTheTop() {
        val cue = EngineCues.of("{\\an8}On top", ass = false).single()
        assertEquals("On top", cue.text.toString())
        assertEquals(0.08f, cue.line)
        assertEquals(Cue.LINE_TYPE_FRACTION, cue.lineType)
        assertEquals(Cue.ANCHOR_TYPE_START, cue.lineAnchor)
        assertEquals(0.5f, cue.position)
        assertEquals(Cue.ANCHOR_TYPE_MIDDLE, cue.positionAnchor)
    }

    @Test
    fun assDropsItsOverridesAndStandsBottomCentre() {
        val cue = EngineCues.of("{\\fad(200,200)\\i1}Line one\\Nline two", ass = true).single()
        assertEquals("Line one\nline two", cue.text.toString())
        assertTrue((cue.text as Spanned).spanRanges.isEmpty())
        assertEquals(0.95f, cue.line)
        assertEquals(Cue.ANCHOR_TYPE_END, cue.lineAnchor)
        assertEquals(Layout.Alignment.ALIGN_CENTER, cue.textAlignment)
    }

    @Test
    fun assEventsInTwoPlacesAreTwoCues() {
        val cues = EngineCues.of("{\\an7}Sign\nDialogue", ass = true)
        assertEquals(2, cues.size)
        assertEquals("Sign", cues[0].text.toString())
        assertEquals(0.05f, cues[0].line)
        assertEquals(Cue.ANCHOR_TYPE_START, cues[0].positionAnchor)
        assertEquals(Layout.Alignment.ALIGN_NORMAL, cues[0].textAlignment)
        assertEquals("Dialogue", cues[1].text.toString())
        assertEquals(0.95f, cues[1].line)
    }

    @Test
    fun assLegacyAlignmentTopIsNumpadEight() {
        val cue = EngineCues.of("{\\a6}Top", ass = true).single()
        assertEquals(Cue.ANCHOR_TYPE_START, cue.lineAnchor)
        assertEquals(Cue.ANCHOR_TYPE_MIDDLE, cue.positionAnchor)
    }

    @Test
    fun hardSpaceAndUnclosedBraceSurvive() {
        val cue = EngineCues.of("a\\hb {not closed", ass = false).single()
        assertEquals("a b {not closed", cue.text.toString())
    }
}
