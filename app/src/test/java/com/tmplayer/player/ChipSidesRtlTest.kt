package com.tmplayer.player

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.tmplayer.online.SkipKind
import com.tmplayer.online.SkipSegment
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The end side chips (Next up, Skip intro) stand clear of the screen's edge both ways round: right
 * to left the end is the left, where a bare right margin left them flush against the edge (R8).
 * The inset split names the side each edge is on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w800dp-h400dp-land-mdpi")
class ChipSidesRtlTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun root(rtl: Boolean): Pair<FrameLayout, View> {
        val root = FrameLayout(context)
        root.layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        val below = View(context)
        root.addView(below)
        return root to below
    }

    private fun lay(root: FrameLayout) {
        root.measure(
            View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, 800, 400)
    }

    /** The gap between [view] and the edge it stands at: the left one right to left. */
    private fun endGap(root: FrameLayout, view: View, rtl: Boolean): Int =
        if (rtl) view.left else root.width - view.right

    @Test
    fun `the next up card keeps its margin from the end edge both ways round`() {
        for (rtl in listOf(false, true)) {
            val (root, below) = root(rtl)
            val card = NextUpCard(root, below, tv = false, onHide = {}, onPlay = {})
            card.show("S01E05", 12)
            lay(root)
            assertEquals("rtl=$rtl", NextUpCard.marginPx(root), endGap(root, card.view, rtl))
        }
    }

    @Test
    fun `the skip pill keeps its margin from the end edge both ways round`() {
        for (rtl in listOf(false, true)) {
            val (root, below) = root(rtl)
            val pill = SkipPill(root, below, tv = false, onSkip = {})
            pill.show(SkipSegment(SkipKind.entries.first(), 0, 60_000))
            lay(root)
            assertEquals("rtl=$rtl", NextUpCard.marginPx(root), endGap(root, pill.view, rtl))
        }
    }

    @Test
    fun `an inset counts at the start or the end by the reading direction`() {
        assertEquals(SideInsets(start = 10, end = 40), SideInsets.of(left = 10, right = 40, rtl = false))
        assertEquals(SideInsets(start = 40, end = 10), SideInsets.of(left = 10, right = 40, rtl = true))
    }

    @Test
    fun `a margin set at the end or the start is read by the layout`() {
        val (root, below) = root(rtl = true)
        val card = NextUpCard(root, below, tv = false, onHide = {}, onPlay = {})
        card.show("S01E05", 12)
        (card.view.layoutParams as FrameLayout.LayoutParams).let { SideInsets.atEnd(it, 70) }
        card.view.requestLayout()
        lay(root)
        assertEquals(70, card.view.left)
    }
}
