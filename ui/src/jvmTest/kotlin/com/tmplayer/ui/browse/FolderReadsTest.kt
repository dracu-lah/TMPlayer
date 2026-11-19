package com.tmplayer.ui.browse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the folders' members are read (HX29): only while the picker is open, and again for a folder that appears later. */
class FolderReadsTest {

    @Test
    fun `a chat list load reads no folder until the picker has asked for them`() {
        val reads = FolderReads()
        assertFalse(reads.readAfterLoad(listOf(1, 2)))
        assertFalse(reads.readOnWant(listOf(1, 2)))
        reads.want()
        assertTrue(reads.readOnWant(listOf(1, 2)))
        assertTrue(reads.readAfterLoad(listOf(1, 2)))
    }

    @Test
    fun `leaving the picker stops the reads, and the loads after it read nothing`() {
        val reads = FolderReads()
        reads.want()
        reads.markRead(listOf(1, 2))
        // While open, a load keeps the members current.
        assertTrue(reads.readAfterLoad(listOf(1, 2)))
        reads.unwant()
        assertFalse(reads.wanted)
        assertFalse(reads.readAfterLoad(listOf(1, 2)))
        assertFalse(reads.readOnWant(listOf(1, 3)))
        // What was read stays known for the next opening.
        assertFalse(reads.unread(listOf(1, 2)))
        reads.want()
        assertFalse("nothing new, nothing to read", reads.readOnWant(listOf(1, 2)))
    }

    @Test
    fun `a folder that appears after the last read is unread, not empty, and is read again`() {
        val reads = FolderReads()
        reads.want()
        reads.markRead(listOf(1, 2))
        assertFalse(reads.unread(listOf(1, 2)))
        assertTrue(reads.unread(listOf(1, 2, 7)))
        assertTrue(reads.readOnWant(listOf(1, 2, 7)))
        reads.markRead(listOf(1, 2, 7))
        assertFalse(reads.unread(listOf(1, 2, 7)))
        // A folder deleted since needs no read.
        assertFalse(reads.unread(listOf(2, 7)))
    }

    @Test
    fun `no folders at all is never unread, and a new account starts clean`() {
        val reads = FolderReads()
        reads.want()
        assertFalse(reads.unread(emptyList()))
        assertFalse(reads.readAfterLoad(emptyList()))
        reads.markRead(listOf(1))
        reads.reset()
        assertFalse(reads.wanted)
        assertTrue(reads.unread(listOf(1)))
        assertEquals(false, reads.readAfterLoad(listOf(1)))
    }
}
