package com.tmplayer.data

import com.tmplayer.data.DetailAction.AddWatchLater
import com.tmplayer.data.DetailAction.CancelDownload
import com.tmplayer.data.DetailAction.Download
import com.tmplayer.data.DetailAction.InDownloads
import com.tmplayer.data.DetailAction.MarkUnwatched
import com.tmplayer.data.DetailAction.MarkWatched
import com.tmplayer.data.DetailAction.OpenChat
import com.tmplayer.data.DetailAction.OpenElsewhere
import com.tmplayer.data.DetailAction.Play
import com.tmplayer.data.DetailAction.RemoveDownload
import com.tmplayer.data.DetailAction.RemoveWatchLater
import com.tmplayer.data.DetailAction.Resume
import com.tmplayer.data.DetailAction.SaveToDownloads
import com.tmplayer.data.DetailAction.Share
import com.tmplayer.data.DetailAction.StartOver
import com.tmplayer.data.DetailAction.JoinCopies
import com.tmplayer.data.DetailAction.ChoosePoster
import com.tmplayer.data.DetailAction.Rename
import com.tmplayer.data.DetailAction.SplitCopy
import com.tmplayer.data.DetailAction.UseMatch
import com.tmplayer.data.DetailAction.WrongMatch
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
            listOf(Play, MarkWatched, Download),
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
            listOf(Play, MarkWatched, InDownloads, RemoveDownload, Share, OpenElsewhere),
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
        assertEquals(listOf(Play, MarkWatched, InDownloads, RemoveDownload), DetailActions.of(tv))
    }

    @Test
    fun `outside the chat, Home and search offer the chat and no selecting`() {
        val home = DetailContext(canBeSaved = true, outsideChat = true)
        assertEquals(listOf(Play, MarkWatched, Download, OpenChat), DetailActions.of(home))
    }

    @Test
    fun `watch later sits beside the watched mark, restricted chats included`() {
        assertEquals(listOf(Play, MarkWatched, AddWatchLater), DetailActions.of(DetailContext(canBeSaved = false, watchLater = false)))
        val saved = DetailActions.of(phoneGrid.copy(watchLater = true))
        assertEquals(listOf(Play, MarkWatched, RemoveWatchLater, Download), saved.take(4))
        assertFalse(DetailActions.keepsACopy(AddWatchLater))
    }

    @Test
    fun `corrections come last, each with its undo, and none of them keeps a copy`() {
        val base = DetailContext(canBeSaved = false, canCorrect = true)
        assertEquals(listOf(Play, MarkWatched, ChoosePoster, Rename), DetailActions.of(base))
        assertEquals(listOf(SplitCopy, WrongMatch, ChoosePoster, Rename), DetailActions.of(base.copy(grouped = true, matched = true)).takeLast(4))
        assertEquals(listOf(JoinCopies, UseMatch, ChoosePoster, Rename), DetailActions.of(base.copy(separated = true, wrongMatch = true)).takeLast(4))
        assertFalse(DetailActions.of(DetailContext(canBeSaved = true)).contains(Rename))
        assertFalse(listOf(SplitCopy, JoinCopies, WrongMatch, UseMatch, ChoosePoster, Rename).any(DetailActions::keepsACopy))
    }

    @Test
    fun `another copy kept says so and offers no second download`() {
        val actions = DetailActions.of(phoneGrid.copy(grouped = true, copyInDownloads = true))
        assertTrue(InDownloads in actions)
        assertFalse(Download in actions)
        assertFalse(SaveToDownloads in actions)
        // The copy that is kept is removed from its own page, not from this one's.
        assertFalse(RemoveDownload in actions)
        // Nothing of this file is here to hand over.
        assertFalse(Share in actions)
    }

    @Test
    fun `this file kept or coming down wins over another copy kept`() {
        assertEquals(
            listOf(InDownloads, RemoveDownload),
            DetailActions.of(phoneGrid.copy(inDownloads = true, copyInDownloads = true)).filter { it == InDownloads || it == RemoveDownload },
        )
        val coming = DetailActions.of(phoneGrid.copy(downloading = true, copyInDownloads = true))
        assertTrue(CancelDownload in coming)
        assertFalse(InDownloads in coming)
    }
}
