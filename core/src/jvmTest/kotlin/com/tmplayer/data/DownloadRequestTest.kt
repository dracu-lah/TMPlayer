package com.tmplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stored form of a queued download.
 *
 * This is what a crash comes back to, so the encoding is worth pinning: a line that cannot be read
 * is a video the viewer asked for and never gets, and a title full of the punctuation release names
 * carry is the likeliest thing to break a delimiter.
 */
class DownloadRequestTest {

    private val request = DownloadRequest(
        fileId = 42,
        title = "Some.Film.2019.1080p.x265 [Malayalam] - Part 1",
        sizeBytes = 3_221_225_472,
        chatId = -100123456789,
        messageId = 987654321,
        chatTitle = "Films · HD",
        durationSec = 8340,
        mimeType = "video/x-matroska",
        fileName = "Some.Film.2019.mkv",
    )

    @Test
    fun `survives a round trip`() {
        assertEquals(request, DownloadRequest.decode(request.encode()))
    }

    @Test
    fun `a whole queue survives a round trip in order`() {
        val queue = listOf(request, request.copy(fileId = 7, title = "Another"))
        assertEquals(queue, DownloadRequest.decodeAll(DownloadRequest.encodeAll(queue)))
    }

    @Test
    fun `an empty store is an empty queue rather than one broken entry`() {
        assertTrue(DownloadRequest.decodeAll(null).isEmpty())
        assertTrue(DownloadRequest.decodeAll("").isEmpty())
    }

    /** Preferences outlive app versions, so a line from an older build has to be dropped. */
    @Test
    fun `a line that cannot be trusted is dropped rather than guessed at`() {
        assertNull(DownloadRequest.decode("not a request"))
        assertNull(DownloadRequest.decode(""))
        // A file id of zero is the id the notification's buttons use for "all of them".
        assertNull(DownloadRequest.decode(request.copy(fileId = 0).encode()))
    }

    @Test
    fun `a broken line does not take the rest of the queue with it`() {
        val stored = DownloadRequest.encodeAll(listOf(request)) + "" + "rubbish"
        assertEquals(listOf(request), DownloadRequest.decodeAll(stored))
    }

    /** The same video queued twice is one download, whatever the store happens to hold. */
    @Test
    fun `a repeated file id appears once`() {
        val stored = DownloadRequest.encodeAll(listOf(request, request))
        assertEquals(1, DownloadRequest.decodeAll(stored).size)
    }
}
