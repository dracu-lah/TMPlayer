package com.tmplayer.ios.media3

import android.graphics.Paint
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.style.StyleSpan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The toolkit's StaticLayout, which Media3's SubtitleView lays its cues out with. */
class SubtitleLayoutTest {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 40f }

    @Test
    fun aLongLineWrapsAtSpacesWithinTheWidth() {
        val text = "the quick brown fox jumps over the lazy dog again and again"
        val width = (paint.measureText(text) / 3).toInt()
        val layout = StaticLayout(text, paint, width, Layout.Alignment.ALIGN_CENTER, 1f, 0f, true)
        assertTrue(layout.lineCount >= 3, "lines ${layout.lineCount}")
        for (i in 0 until layout.lineCount) assertTrue(layout.getLineWidth(i) <= width, "line $i is ${layout.getLineWidth(i)} of $width")
    }

    @Test
    fun aNewlineStartsALineAndLinesStackDown() {
        val layout = StaticLayout("one\ntwo", paint, 1000, Layout.Alignment.ALIGN_NORMAL, 1f, 0f, true)
        assertEquals(2, layout.lineCount)
        assertEquals(layout.getLineBottom(0), layout.getLineTop(1))
        assertTrue(layout.height > 0)
        assertEquals(0f, layout.getLineLeft(0))
    }

    @Test
    fun centredLinesStandInTheMiddle() {
        val layout = StaticLayout("hi", paint, 1000, Layout.Alignment.ALIGN_CENTER, 1f, 0f, true)
        assertEquals((1000 - layout.getLineWidth(0)) / 2f, layout.getLineLeft(0))
    }

    @Test
    fun boldTextIsWiderThanPlain() {
        val plain = StaticLayout("Wide words", paint, 2000, Layout.Alignment.ALIGN_NORMAL, 1f, 0f, true)
        val bold = SpannableStringBuilder("Wide words").also { it.setSpan(StyleSpan(android.graphics.Typeface.BOLD), 0, it.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        val boldLayout = StaticLayout(bold, paint, 2000, Layout.Alignment.ALIGN_NORMAL, 1f, 0f, true)
        assertTrue(boldLayout.getLineWidth(0) > plain.getLineWidth(0))
    }
}
