package com.tmplayer.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The chat picker's held Down jumps with `scrollToItem` (HX76), which a cache window never
 * refills after: [JumpAheadPrefetch] holds (as [JumpAheadWindow] works out) the screenful past the visible items, in the direction
 * the list last moved, and lets go of what left the window. Whether the held rows really make a
 * jump cheaper is measured on the Mi TV; this checks what is asked for.
 */
class JumpAheadPrefetchTest {

    /** The items composed ahead: asked for and not yet let go of. */
    private val live = HashSet<Int>()
    private var scheduled = 0
    private val window = JumpAheadWindow(behind = 2)

    /** Ten items in sight from [first], of [total]. */
    private fun show(first: Int, total: Int = 1_000) = apply(window.update(first, minOf(first + 10, total) - 1, total))

    private fun apply(change: JumpAheadWindow.Change?) {
        change ?: return
        live -= change.drop.toSet()
        for (index in change.add) {
            assertTrue("$index asked for twice", live.add(index))
            scheduled++
        }
    }
    @Test
    fun `a list at the top holds the screen under it and nothing more`() {
        show(0)
        assertEquals((10..19).toSet(), live)
    }

    @Test
    fun `a jump down holds the screen past it and two behind, and lets go of the rest`() {
        show(0)
        show(100)
        assertEquals((110..119).toSet() + setOf(98, 99), live)
    }

    @Test
    fun `a jump up holds the screen above it`() {
        show(100)
        show(50)
        assertEquals((40..49).toSet() + setOf(60, 61), live)
    }

    @Test
    fun `a list that stays put asks for nothing again`() {
        show(30)
        val before = scheduled
        repeat(5) { show(30) }
        assertEquals(before, scheduled)
    }

    @Test
    fun `a short step keeps what it already holds`() {
        show(0)
        val before = scheduled
        show(4)
        // 14 to 19 were held already; 20 to 23 are new, and 2 and 3 are behind.
        assertEquals(before + 4 + 2, scheduled)
        assertEquals((14..23).toSet() + setOf(2, 3), live)
    }

    @Test
    fun `the ends of the list bound the window`() {
        show(990)
        assertTrue("nothing past the last item: $live", live.all { it < 1_000 })
        show(0, total = 8)
        assertTrue("a list shorter than the screen holds nothing: $live", live.isEmpty())
    }

    @Test
    fun `an empty list lets everything go`() {
        show(0)
        apply(window.update(first = -1, last = -1, total = 0))
        assertTrue(live.isEmpty())
    }
}
