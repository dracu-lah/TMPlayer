package com.tmplayer.ui.components

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue

class ToastQueueTest {
    private val queue = ToastQueue<String>()

    @Test
    fun `the first notice shows at once`() {
        val offer = queue.offer("a", "A")
        assertEquals("A", offer.show!!.item)
        assertTrue(!offer.replacesCurrent)
    }

    @Test
    fun `a different notice waits for the one showing, which is not cut off`() {
        val first = queue.offer("backup", "Backup restored").show!!
        val second = queue.offer("other", "Something else")
        assertNull(second.show)
        assertSame(first, queue.current)
        val next = queue.finished(first)
        assertEquals("Something else", next!!.item)
        assertNull(queue.waiting)
    }

    @Test
    fun `the same notice again replaces the one showing, and a replaced one ending moves nothing`() {
        val first = queue.offer("a", "A").show!!
        val again = queue.offer("a", "A again")
        assertTrue(again.replacesCurrent)
        assertEquals("A again", queue.current!!.item)
        assertNull(queue.finished(first))
        assertEquals("A again", queue.current!!.item)
    }

    @Test
    fun `only one waits, a newer one takes the older one's place`() {
        val first = queue.offer("a", "A").show!!
        queue.offer("b", "B")
        val third = queue.offer("c", "C")
        assertEquals("B", third.dropped!!.item)
        assertEquals("C", queue.finished(first)!!.item)
    }

    @Test
    fun `the queue empties once the last one ends`() {
        val first = queue.offer("a", "A").show!!
        assertNull(queue.finished(first))
        assertNull(queue.current)
        assertEquals("B", queue.offer("b", "B").show!!.item)
    }
}
