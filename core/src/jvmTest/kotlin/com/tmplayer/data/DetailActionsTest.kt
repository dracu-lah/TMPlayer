package com.tmplayer.data

import com.tmplayer.data.DetailAction.CancelDownload
import com.tmplayer.data.DetailAction.CopyLink
import com.tmplayer.data.DetailAction.Download
import com.tmplayer.data.DetailAction.InDownloads
import com.tmplayer.data.DetailAction.MarkUnwatched
import com.tmplayer.data.DetailAction.MarkWatched
import com.tmplayer.data.DetailAction.OpenChat
import com.tmplayer.data.DetailAction.OpenElsewhere
import com.tmplayer.data.DetailAction.Play
import com.tmplayer.data.DetailAction.RemoveDownload
import com.tmplayer.data.DetailAction.Resume
import com.tmplayer.data.DetailAction.SaveToDownloads
import com.tmplayer.data.DetailAction.SelectVideos
import com.tmplayer.data.DetailAction.Share
import com.tmplayer.data.DetailAction.StartOver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailActionsTest {

    /** The phone's grid: everything a platform can offer is on. */
    private val phoneGrid = DetailContext(canBeSaved = true, canSelect = true, canHandOff = true, hasClipboard = true)

    @Test
    fun `a fresh video on the phone's grid`() {
        assertEquals(
            listOf(Play, MarkWatched, Download, SelectVideos, CopyLink),
            DetailActions.of(phoneGrid),
        )
    }

    @Test
    fun `a saved position offers Resume and Start over in place of Play`() {
        val actions = DetailActions.of(phoneGrid.copy(resumeMs = 2_050_000))
        assertEquals(listOf(Resume, StartOver, MarkWatched), actions.take(3))
        assertFalse(Play in actions)
    }

    @Test
    fun `watched turns the mark around`() {
        val actions = DetailActions.of(phoneGrid.copy(finished = true))
        assertTrue(MarkUnwatched in actions)
        assertFalse(MarkWatched in actions)
    }

    @Test
    fun `a protected chat streams and nothing more`() {
        val restricted = phoneGrid.copy(canBeSaved = false, cached = true)
        val actions = DetailActions.of(restricted)
        assertEquals(listOf(Play, MarkWatched), actions)
        assertTrue(actions.none(DetailActions::keepsACopy))
        // Watching state still works there, with a saved position.
        assertEquals(listOf(Resume, StartOver, MarkUnwatched), DetailActions.of(restricted.copy(resumeMs = 1, finished = true)))
    }

    @Test
    fun `a copy kept before the chat was protected can still be removed, never shared`() {
        val actions = DetailActions.of(phoneGrid.copy(canBeSaved = false, inDownloads = true))
        assertEquals(listOf(Play, MarkWatched, InDownloads, RemoveDownload), actions)
    }

    @Test
    fun `downloaded on the phone offers removing, sharing and another app`() {
        assertEquals(
            listOf(Play, MarkWatched, InDownloads, RemoveDownload, SelectVideos, Share, OpenElsewhere, CopyLink),
            DetailActions.of(phoneGrid.copy(inDownloads = true)),
        )
    }

    @Test
    fun `whole in the cache saves to Downloads rather than fetching`() {
        val actions = DetailActions.of(phoneGrid.copy(cached = true))
        assertTrue(SaveToDownloads in actions)
        assertFalse(Download in actions)
        assertTrue(Share in actions)
    }

    @Test
    fun `on the queue, the one download line is cancelling it`() {
        val actions = DetailActions.of(phoneGrid.copy(downloading = true, cached = true))
        assertTrue(CancelDownload in actions)
        assertFalse(Download in actions)
        assertFalse(SaveToDownloads in actions)
    }

    @Test
    fun `the television has no other app and no clipboard`() {
        val tv = DetailContext(canBeSaved = true, inDownloads = true, canSelect = true)
        assertEquals(listOf(Play, MarkWatched, InDownloads, RemoveDownload, SelectVideos), DetailActions.of(tv))
    }

    @Test
    fun `outside the chat, Home and search offer the chat and no selecting`() {
        val home = DetailContext(canBeSaved = true, outsideChat = true)
        assertEquals(listOf(Play, MarkWatched, Download, OpenChat), DetailActions.of(home))
    }
}
