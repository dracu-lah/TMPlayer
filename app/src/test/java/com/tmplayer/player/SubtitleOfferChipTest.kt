package com.tmplayer.player

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The "subtitles found for you" offer (U5) on the phone and the television. */
@RunWith(RobolectricTestRunner::class)
class SubtitleOfferChipTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private class Built(val root: FrameLayout, val controls: View, val chip: SubtitleOfferChip, val taps: IntArray)

    private fun built(tv: Boolean): Built {
        val root = FrameLayout(context)
        val controls = View(context)
        root.addView(controls)
        val taps = IntArray(1)
        return Built(root, controls, SubtitleOfferChip(root, controls, tv) { taps[0]++ }, taps)
    }

    private fun texts(chip: SubtitleOfferChip): List<String> =
        (chip.view as LinearLayout).let { box -> (0 until box.childCount).map { (box.getChildAt(it) as TextView).text.toString() } }

    @Test
    fun `a phone shows the message over the action and a tap takes it`() {
        val b = built(tv = false)
        assertFalse(b.chip.isShown)
        b.chip.show("English subtitles from OpenSubtitles", "Not this one, try the next")
        assertTrue(b.chip.isShown)
        assertEquals(listOf("English subtitles from OpenSubtitles", "Not this one, try the next"), texts(b.chip))
        assertTrue(b.chip.view.isClickable)
        b.chip.view.performClick()
        assertEquals(1, b.taps[0])
    }

    @Test
    fun `a television never focuses it and its second line names the key`() {
        val b = built(tv = true)
        b.chip.show("Find subtitles over mobile data?", "Find them")
        assertFalse(b.chip.view.isFocusable)
        assertFalse(b.chip.view.isClickable)
        assertEquals("Up: Find them", texts(b.chip)[1])
    }

    @Test
    fun `it sits under the controls, hides, and lets go of the view`() {
        for (tv in listOf(false, true)) {
            val b = built(tv)
            assertTrue(b.root.indexOfChild(b.chip.view) < b.root.indexOfChild(b.controls))
            b.chip.show("a", "b")
            // Showing again changes the words and does not stack a second one.
            b.chip.show("c", "d")
            assertEquals(2, b.root.childCount)
            assertEquals("c", texts(b.chip)[0])
            b.chip.hide()
            b.chip.release()
            assertNull(b.chip.view.parent)
            b.chip.release()
        }
    }
}
