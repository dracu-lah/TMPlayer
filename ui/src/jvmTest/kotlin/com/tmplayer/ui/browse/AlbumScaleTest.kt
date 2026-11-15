package com.tmplayer.ui.browse

import com.tmplayer.data.AlbumPosts
import com.tmplayer.data.MediaItem
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.minutes

/**
 * Albums read as one post (EchoGram B11, B16) on [SyntheticAccount]'s 20,000 video chat: a listing
 * never reads for albums, a page of search hits reads one window per album and at most
 * [AlbumPosts.ALBUMS_PER_PAGE] of them, whatever the page holds, and a read that is flooded is
 * waited out by that read alone.
 */
class AlbumScaleTest {

    private val chat = 1L

    @Test
    fun `a page of search hits costs at most six reads, never one per item`() = runTest(timeout = 5.minutes) {
        val account = SyntheticAccount(backgroundScope, { testScheduler.currentTime })
        assertEquals(20_000, account.videoCount(chat))
        val real = account.albumReader(chat)
        var asked = 0
        val reader = com.tmplayer.data.AlbumReader { c, anchor -> asked++; real.read(c, anchor) }
        var shown = 0
        // Forty pages of 48 hits, each in an album of four: twelve albums a page, six of them read.
        for (page in 0 until 40) {
            val hits = (0 until 48).map { account.albumItem(chat, page * 48 + it) }
            val before = asked
            val out = AlbumPosts.enrich(hits, emptyList(), reader)
            val reads = asked - before
            assertTrue("page $page read $reads albums", reads in 1..AlbumPosts.ALBUMS_PER_PAGE)
            assertEquals(hits.size, out.size)
            shown += out.count { it.albumPhotoFileId != 0 }
        }
        // Six albums of four videos a page get their picture; the rest are listed as the hit showed them.
        // A read the history budget turned away for longer than a read sits out is left as the hit showed it.
        assertTrue("$shown pictures", shown in 800..40 * AlbumPosts.ALBUMS_PER_PAGE * 4)
        assertEquals(40 * AlbumPosts.ALBUMS_PER_PAGE, asked)
        // The server saw those reads, and the FLOOD_WAIT the history budget answered some with, retried by TDLib itself.
        assertTrue(account.calls.getValue("getChatHistory") >= asked)
    }

    @Test
    fun `a listing of the whole chat makes no read at all`() = runTest(timeout = 5.minutes) {
        val account = SyntheticAccount(backgroundScope, { testScheduler.currentTime })
        val reader = account.albumReader(chat)
        // 20,000 videos already carrying their picture (as a cached listing does), or none in an album:
        // nothing is asked, and the cost is the loop's.
        val listing: List<MediaItem> = (0 until 20_000).map { account.albumItem(chat, it).copy(albumId = 0) }
        val started = System.nanoTime()
        val out = AlbumPosts.enrich(listing, emptyList(), reader)
        val ms = (System.nanoTime() - started) / 1_000_000
        assertEquals(listing, out)
        assertEquals(null, account.calls["getChatHistory"])
        assertTrue("took $ms ms", ms < 2_000)
        val folded = AlbumPosts.fold(listing, emptyMap())
        assertTrue(folded === listing)
    }

    @Test
    fun `a flooded read waits for its own seconds and the page still finishes`() = runTest(timeout = 5.minutes) {
        val account = SyntheticAccount(backgroundScope, { testScheduler.currentTime })
        val real = account.albumReader(chat)
        var flooded = 0
        val reader = com.tmplayer.data.AlbumReader { c, anchor ->
            if (flooded++ == 0) throw IllegalStateException("Too Many Requests: retry after 12")
            real.read(c, anchor)
        }
        val hits = (0 until 8).map { account.albumItem(chat, it * 4) }
        val started = testScheduler.currentTime
        val out = AlbumPosts.enrich(hits, emptyList(), reader)
        assertTrue("waited ${testScheduler.currentTime - started} ms", testScheduler.currentTime - started >= 12_250)
        // Six albums read (one of them twice, for the flood), seven calls in all.
        assertEquals(7, flooded)
        assertEquals(6 * 1, out.count { it.albumPhotoFileId != 0 })
    }
}
