package com.tmplayer.ui.components

import androidx.compose.ui.input.key.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where a Page or Channel key lands (WP-12), as numbers: a screenful of whole rows times the columns,
 * at the same place on the screen, clamped to the first and last item. A huge list costs what the
 * visible rows cost: the plan reads only the items on screen, never the 100,000 behind them.
 */
class PageKeysPlanTest {

    /** A grid [columns] wide whose first [firstIndex] items are scrolled away, each row 100 px, 540 px tall. */
    private fun grid(total: Int, firstIndex: Int, columns: Int = 4, rowsShown: Int = 6, offset: Int = 0): PageView {
        val items = (0 until rowsShown * columns).map { n ->
            val index = firstIndex + n
            val row = index / columns
            val top = (row - firstIndex / columns) * 100 - offset
            PageItem(index, index, row, top, top + 100)
        }.filter { it.index < total }
        return PageView(total, firstIndex, offset, viewStart = 0, viewEnd = 540, columns = columns, items = items)
    }

    @Test
    fun `a screenful is the whole rows in sight times the columns`() {
        // 540 px of 100 px rows: 5 whole rows, the sixth is cut off.
        val plan = PageKeys.plan(grid(total = 100_000, firstIndex = 2_000), focused = 2_001, dir = 1)!!
        assertEquals(2_021, plan.target)
        assertEquals(2_020, plan.scrollIndex)
    }

    @Test
    fun `up from deep in the list is the same distance back`() {
        val plan = PageKeys.plan(grid(total = 100_000, firstIndex = 2_000), focused = 2_001, dir = -1)!!
        assertEquals(1_981, plan.target)
        assertEquals(1_980, plan.scrollIndex)
    }

    @Test
    fun `Page Up near the top goes to the first item and the top`() {
        val plan = PageKeys.plan(grid(total = 100_000, firstIndex = 0), focused = 5, dir = -1)!!
        assertEquals(0, plan.target)
        assertEquals(0, plan.scrollIndex)
        assertEquals(0, plan.scrollOffset)
    }

    @Test
    fun `Page Down at the end lands on the last item and never past it`() {
        val plan = PageKeys.plan(grid(total = 100_000, firstIndex = 99_976), focused = 99_990, dir = 1)!!
        assertEquals(99_999, plan.target)
        assertTrue(plan.scrollIndex <= 99_999)
    }

    @Test
    fun `nothing focused starts from the first whole item in sight`() {
        val plan = PageKeys.plan(grid(total = 1_000, firstIndex = 40), focused = null, dir = 1)!!
        assertEquals(60, plan.target)
    }

    @Test
    fun `an empty list and a key that is not a page key plan nothing`() {
        assertNull(PageKeys.plan(PageView(0, 0, 0, 0, 540, 1, emptyList()), focused = null, dir = 1))
        assertNull(PageKeys.plan(grid(total = 100, firstIndex = 0), focused = 0, dir = 0))
        assertEquals(0, PageKeys.step(Key.DirectionDown))
        assertEquals(0, PageKeys.step(Key.Enter))
    }

    @Test
    fun `a list of one column moves by the rows in sight`() {
        val items = (50 until 58).map { PageItem(it, it, it, (it - 50) * 80, (it - 50) * 80 + 80) }
        val view = PageView(total = 3_000, firstIndex = 50, firstOffset = 0, viewStart = 0, viewEnd = 540, columns = 1, items = items)
        // 6 whole rows of 80 px in 540 px.
        assertEquals(56, PageKeys.plan(view, focused = 50, dir = 1)!!.target)
    }

    @Test
    fun `the keys of a Fire TV remote page too, and only the Page and Channel keys count as arrival`() {
        assertEquals(1, PageKeys.step(Key.MediaFastForward))
        assertEquals(-1, PageKeys.step(Key.MediaRewind))
        assertEquals(1, PageKeys.step(Key.ChannelDown))
        assertEquals(-1, PageKeys.step(Key.PageUp))
        assertFalse(PageKeys.isPageOrChannel(Key.MediaFastForward))
        assertTrue(PageKeys.isPageOrChannel(Key.ChannelUp))
    }

    @Test
    fun `the plan of a 100000 item list reads only what is on screen`() {
        val view = grid(total = 100_000, firstIndex = 50_000)
        assertTrue(view.items.size <= 24)
        repeat(10_000) { PageKeys.plan(view, focused = 50_001, dir = 1) }
    }

    @Test
    fun `a headset's Fast forward and Rewind page only on a television`() {
        assertEquals(0, PageKeys.step(Key.MediaFastForward, tv = false))
        assertEquals(0, PageKeys.step(Key.MediaRewind, tv = false))
        assertEquals(1, PageKeys.step(Key.PageDown, tv = false))
        assertEquals(-1, PageKeys.step(Key.ChannelUp, tv = false))
    }

    /** A source whose items are 0..9, and a plan aimed at 50 that no tile of the screen answers for a while. */
    private class FakeSource : PageSource {
        var scrolled: Pair<Int, Int>? = null
        override fun view() = PageView(
            total = 100, firstIndex = 40, firstOffset = 0, viewStart = 0, viewEnd = 540, columns = 1,
            items = (40..49).map { PageItem(it, "k$it", it, (it - 40) * 50, (it - 40) * 50 + 50) },
        )
        override suspend fun scrollTo(index: Int, offset: Int) {
            scrolled = index to offset
        }
    }

    @Test
    fun `a landing tile that is composed late still gets the remote`() = kotlinx.coroutines.runBlocking {
        var frames = 0
        val asked = ArrayList<Any>()
        val plan = PagePlan(target = 45, scrollIndex = 40, scrollOffset = 0)
        // Nothing answers for 6 frames (the rows are still being composed), then every tile does.
        val landed = PageKeys.landWith(
            FakeSource(), plan,
            request = { key -> asked += key; frames >= 6 },
            requestAny = { false },
            nextFrame = { frames++ },
        )
        assertTrue(landed)
        // The tile that took it was the target, not a neighbour.
        assertEquals("k45", asked.last())
    }

    @Test
    fun `when no visible tile ever answers any tile of the list takes the remote, never nothing`() = kotlinx.coroutines.runBlocking {
        var any = 0
        val landed = PageKeys.landWith(
            FakeSource(), PagePlan(45, 40, 0),
            request = { false },
            requestAny = { any++; true },
            nextFrame = { },
        )
        assertTrue(landed)
        assertEquals(1, any)
    }

    @Test
    fun `a list with no tile at all lands nothing and says so`() = kotlinx.coroutines.runBlocking {
        assertFalse(PageKeys.landWith(FakeSource(), PagePlan(45, 40, 0), request = { false }, requestAny = { false }, nextFrame = { }))
    }
}
