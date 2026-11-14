package com.tmplayer.ui.browse

import com.tmplayer.data.MediaCursors
import com.tmplayer.data.MediaItem
import com.tmplayer.data.SeriesShelf
import com.tmplayer.data.VideoFaceting
import com.tmplayer.data.VideoFacets
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.minutes

/**
 * A chat's filters by what a video is (U6) on [SyntheticAccount]'s chat of 20,000 videos, paged as
 * a chat's grid pages it: the first page is screened as soon as it lands (well within 3 s), and a
 * filter that hides everything lets the grid ask for [VideoFaceting.AUTO_PAGES] more pages by
 * itself, then stops for Keep looking; it never reads the chat to its start.
 */
class VideoFilterScaleTest {

    @Test
    fun `a filter that hides everything reads a few pages of a 20,000 video chat, then stops`() = runTest(timeout = 5.minutes) {
        val account = SyntheticAccount(backgroundScope, { testScheduler.currentTime })
        val chat = 1L
        assertEquals(20_000, account.videoCount(chat))
        val facets = VideoFacets(hdr = true)
        val screenful = 12
        val start = testScheduler.currentTime
        var cursors = MediaCursors()
        val loaded = ArrayList<MediaItem>()
        var pages = 0
        var autoPages = 0
        var firstAt = -1L
        while (true) {
            val page = account.mediaPage(chat, cursors, 40)
            pages++
            cursors = page.cursors
            loaded += page.items
            val shown = VideoFaceting.screen(SeriesShelf.arrange(loaded), facets, nowSec = 2_000_000_000L).shown
            if (firstAt < 0) firstAt = testScheduler.currentTime - start
            if (page.endReached) break
            // The grid near its end: the next page only if it may ask by itself.
            if (!VideoFaceting.mayAutoLoad(filtersOn = true, shown = shown.size, screenful = screenful, autoLoaded = autoPages)) break
            if (shown.size < screenful) autoPages++
        }
        assertTrue("first page screened after $firstAt ms", firstAt in 0..3_000)
        assertEquals(1 + VideoFaceting.AUTO_PAGES, pages)
        assertTrue("loaded ${loaded.size}", loaded.size < 1_000)
    }
}
