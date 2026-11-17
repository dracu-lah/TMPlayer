package com.tmplayer.player

import android.graphics.Bitmap
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Issue 32: Size and Position in Settings did nothing to an SSA or ASS track, whose cues carry the
 * style's own size and SsaParser's bottom line, which SubtitleView draws as given.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class SubtitleCueFitTest {

    /** A cue as SsaParser builds a bottom line: the style's size, line 0.95 anchored at its end. */
    private fun ssaBottom(size: Float = 0.07f) = Cue.Builder()
        .setText("I like this one.")
        .setTextSize(size, Cue.TEXT_SIZE_TYPE_FRACTIONAL_IGNORE_PADDING)
        .setLine(SubtitleCueFit.SSA_BOTTOM_LINE, Cue.LINE_TYPE_FRACTION)
        .setLineAnchor(Cue.ANCHOR_TYPE_END)
        .setPosition(0.5f)
        .setPositionAnchor(Cue.ANCHOR_TYPE_MIDDLE)
        .build()

    @Test
    fun `an ssa line follows the size setting`() {
        val small = SubtitleCueFit.fit(listOf(ssaBottom()), SubtitleStyle(SubtitleSize.Small), 1f).single()
        assertEquals(0.07f * SubtitleSize.Small.cueScale, small.textSize, 0.0001f)
        assertEquals(Cue.TEXT_SIZE_TYPE_FRACTIONAL_IGNORE_PADDING, small.textSizeType)
        val large = SubtitleCueFit.fit(listOf(ssaBottom()), SubtitleStyle(SubtitleSize.ExtraLarge), 1f).single()
        assertEquals(0.07f * SubtitleSize.ExtraLarge.cueScale, large.textSize, 0.0001f)
        // The small picture floor still multiplies in.
        val upright = SubtitleCueFit.fit(listOf(ssaBottom()), SubtitleStyle(SubtitleSize.Large), 2f).single()
        assertEquals(0.07f * 2f * SubtitleSize.Large.cueScale, upright.textSize, 0.0001f)
    }

    @Test
    fun `an ssa bottom line is handed to the view's bottom padding, where the position setting sits`() {
        val cue = SubtitleCueFit.fit(listOf(ssaBottom()), SubtitleStyle(position = SubtitlePosition.High), 1f).single()
        assertEquals(Cue.DIMEN_UNSET, cue.line, 0f)
        assertEquals(Cue.TYPE_UNSET, cue.lineType)
        // Its horizontal place is the file's.
        assertEquals(0.5f, cue.position, 0f)
    }

    @Test
    fun `a line placed elsewhere keeps its place`() {
        val top = Cue.Builder().setText("Sign").setTextSize(0.05f, Cue.TEXT_SIZE_TYPE_FRACTIONAL_IGNORE_PADDING)
            .setLine(0.05f, Cue.LINE_TYPE_FRACTION).setLineAnchor(Cue.ANCHOR_TYPE_START).build()
        val placed = Cue.Builder().setText("Pos").setLine(0.6f, Cue.LINE_TYPE_FRACTION).setLineAnchor(Cue.ANCHOR_TYPE_END).build()
        val out = SubtitleCueFit.fit(listOf(top, placed), SubtitleStyle(SubtitleSize.Large), 1f)
        assertEquals(0.05f, out[0].line, 0f)
        assertEquals(0.05f * SubtitleSize.Large.cueScale, out[0].textSize, 0.0001f)
        assertSame(placed, out[1])
    }

    @Test
    fun `plain lines and pictures are left alone, and the default look changes nothing it need not`() {
        val plain = Cue.Builder().setText("Plain").build()
        val picture = Cue.Builder().setBitmap(Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888))
            .setLine(SubtitleCueFit.SSA_BOTTOM_LINE, Cue.LINE_TYPE_FRACTION).setLineAnchor(Cue.ANCHOR_TYPE_END).build()
        val cues = listOf(plain, picture)
        assertSame(cues, SubtitleCueFit.fit(cues, SubtitleStyle(SubtitleSize.ExtraLarge), 1.5f))
        val sized = listOf(ssaBottom().buildUpon().setLine(0.9f, Cue.LINE_TYPE_FRACTION).build())
        assertSame(sized, SubtitleCueFit.fit(sized, SubtitleStyle(), 1f))
    }
}
