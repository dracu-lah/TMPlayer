package com.tmplayer.player

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HX79 (RL9): in Low storage mode only the last [LowStorageWindow.INDEX_TAIL_BYTES] of a file
 * counted as its index, so a film whose moov or Cues is larger had its index read taken for a
 * seek, and with the head of an earlier play on the disk that seek deleted the head. Once the
 * index is known from the file's head ([StreamIndex.locate]), a read anywhere in it is an index
 * read: never a seek that deletes, its bytes held on top of the window.
 */
class LowStorageIndexTest {

    private val mb = 1024L * 1024
    private val film = 2_000 * mb
    private val w = LowStorageWindow.WINDOW_BYTES
    private val slack = LowStorageWindow.SLACK_BYTES

    /** A 12 MB moov: bigger than the 8 MB the mode used to read as an index. */
    private val index = film - 12 * mb
    private val fileId = 7401

    @After
    fun cleanUp() {
        LowStorageStreams.revoke(fileId)
        LowStorageStreams.forgetIndex(fileId)
    }

    /** The head of an earlier play on the disk (10 MB), read, and the window followed from its end. */
    private fun headPlayedBefore(indexStart: Long): List<LowStorageWindow.Range> {
        val head = LowStorageWindow.onDisk(emptyList(), 0, 10 * mb, film, w, indexStart)
        assertEquals(LowStorageWindow.OnDisk.Use.ReadAndFollow, head.use)
        return LowStorageWindow.plan(head.ranges, 10 * mb, film, w, indexStart = indexStart).ranges
    }

    @Test
    fun `the old rule deletes the head for a 12 MB index`() {
        // What HX79 saw, kept as the reason for the fix: the index unknown, its read is a seek.
        val ranges = headPlayedBefore(indexStart = -1)
        assertFalse(LowStorageWindow.nearTheEnd(index, film))
        assertTrue(LowStorageWindow.plan(ranges, index, film, w).deleteFirst)
    }

    @Test
    fun `a 12 MB index read never deletes the head`() {
        val ranges = headPlayedBefore(indexStart = index)
        // An index read: the reader probes it, the download stays with the picture.
        assertTrue(LowStorageWindow.nearTheEnd(index, film, index))
        assertTrue(LowStorageWindow.nearTheEnd(film - 1, film, index))
        assertTrue(LowStorageWindow.probeFits(ranges, index, index + 2 * mb, film, w, index))
        // Even asked for as a seek it deletes nothing, and the whole index is held to the end.
        val read = LowStorageWindow.plan(ranges, index, film, w, indexStart = index)
        assertFalse(read.deleteFirst)
        assertEquals(12 * mb, read.limit)
        // Reading through it never rolls the window.
        assertFalse(LowStorageWindow.shouldRoll(film - 2 * mb, read.ranges, film))
        // The jump back to the picture deletes nothing either.
        val back = LowStorageWindow.plan(read.ranges, 10 * mb, film, w, indexStart = index)
        assertFalse(back.deleteFirst)
        // The disk holds the window, its slack and the index, and no more.
        assertTrue(LowStorageWindow.bytes(back.ranges) <= w + slack + 12 * mb)
    }

    @Test
    fun `the warm-up's first 8 MB of a 12 MB index are read, never fetched again`() {
        // StreamIndex fetches MOOV_BYTES of the moov ahead of the player: the stretch stops 4 MB short.
        val ranges = headPlayedBefore(indexStart = index)
        val found = LowStorageWindow.onDisk(ranges, index, StreamIndex.MOOV_BYTES, film, w, index)
        assertEquals(LowStorageWindow.OnDisk.Use.Read, found.use)
        assertTrue(found.ranges.contains(LowStorageWindow.Range(index, film)))
        assertFalse(LowStorageWindow.plan(found.ranges, 10 * mb, film, w, indexStart = index).deleteFirst)
    }

    @Test
    fun `an index too large to be one is not taken for one`() {
        val huge = film - LowStorageWindow.MAX_INDEX_BYTES - 1
        assertFalse(LowStorageWindow.nearTheEnd(huge, film, huge))
        assertEquals(
            LowStorageWindow.bytes(listOf(LowStorageWindow.Range(0, w), LowStorageWindow.Range(huge, film))),
            LowStorageWindow.counted(listOf(LowStorageWindow.Range(0, w), LowStorageWindow.Range(huge, film)), huge, film),
        )
        // Nonsense starts change nothing.
        assertFalse(LowStorageWindow.nearTheEnd(film - 20 * mb, film, film + 5))
        assertFalse(LowStorageWindow.nearTheEnd(film - 20 * mb, film, -7))
    }

    @Test
    fun `a whole film after a 12 MB index still holds no more than the window, its slack and the index`() {
        var ranges = headPlayedBefore(indexStart = index)
        ranges = LowStorageWindow.plan(ranges, index, film, w, indexStart = index).ranges
        var position = 10 * mb
        while (position < index) {
            if (LowStorageWindow.shouldRoll(position, ranges, film)) {
                ranges = LowStorageWindow.plan(ranges, LowStorageWindow.rollStart(position), film, w, roll = true, indexStart = index).ranges
            }
            assertTrue(LowStorageWindow.bytes(ranges) <= w + slack + 12 * mb)
            position += 1 * mb
        }
    }

    @Test
    fun `the streams learn the index once and keep it across the player's open`() {
        // The warm-up finds the index before the player allows the mode for the file.
        LowStorageStreams.knowIndex(fileId, index, film)
        LowStorageStreams.allow(fileId, w)
        assertTrue(LowStorageStreams.nearTheEnd(fileId, index + 1, film))
        assertFalse(LowStorageStreams.nearTheEnd(fileId, index - 1, film))
        LowStorageStreams.onDisk(fileId, 0, 10 * mb, film)
        assertFalse(LowStorageStreams.plan(fileId, 10 * mb, film)!!.deleteFirst)
        assertTrue(LowStorageStreams.probe(fileId, index, index + 2 * mb, film))
        assertFalse("the tail read deletes nothing", LowStorageStreams.wouldDelete(fileId, index, film))
        assertFalse("nor the way back", LowStorageStreams.wouldDelete(fileId, 10 * mb, film))
        // Garbage is refused: an index outside the file, or one too large to be one.
        LowStorageStreams.knowIndex(fileId, film + 1, film)
        assertTrue(LowStorageStreams.nearTheEnd(fileId, index + 1, film))
        LowStorageStreams.knowIndex(fileId, 0, film)
        assertTrue(LowStorageStreams.nearTheEnd(fileId, index + 1, film))
    }
}
