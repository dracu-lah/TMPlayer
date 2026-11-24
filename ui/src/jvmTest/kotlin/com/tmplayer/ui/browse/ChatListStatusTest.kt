package com.tmplayer.ui.browse

import com.tmplayer.data.ChatKind
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.ChatSync
import com.tmplayer.data.WorkStatus
import com.tmplayer.data.WorkStatusWords
import com.tmplayer.i18n.L
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a chat list sync ends on the status line (WP-93, LS-01): a failed sync with chats on screen
 * says what is kept, a flood wait gives the countdown, a list short of the account is Partial, a
 * success after a failure clears it, and only a pressed refresh says "Up to date".
 */
class ChatListStatusTest {

    private val now = 1_800_000_000_000L
    private val kept = now - 5 * 60_000L

    private val chats = (1L..3L).map { ChatSummary(id = it, title = "Chat $it", miniThumbnail = null, photoFileId = 0, kind = ChatKind.Channel) }

    @Test
    fun `a failed second sync with chats held says what is kept`() {
        val status = ChatListStatus.afterSync(ChatSync(chats, failure = "Connection closed"), held = true, keptAt = kept, asked = false, now = now)
        assertTrue(status is WorkStatus.Failed)
        assertEquals(kept, (status as WorkStatus.Failed).keptAt)
        assertTrue(WorkStatusWords.line(status, now).startsWith("Could not update."))
    }

    @Test
    fun `a flood wait gives the countdown and a short one is a plain failure`() {
        val long = ChatListStatus.afterSync(ChatSync(chats, failure = "FLOOD_WAIT_120"), held = true, keptAt = kept, asked = true, now = now)
        assertEquals(WorkStatus.RateLimited(now + 120_000), long)
        val short = ChatListStatus.afterSync(ChatSync(chats, failure = "FLOOD_WAIT_3"), held = true, keptAt = kept, asked = true, now = now)
        assertTrue(short is WorkStatus.Failed)
    }

    @Test
    fun `the archive or chats left out is partial`() {
        val archive = ChatListStatus.afterSync(ChatSync(chats, archiveFailure = "Timeout"), held = true, keptAt = kept, asked = true, now = now)
        assertTrue(archive is WorkStatus.Partial)
        assertEquals(L.chatsSomeNotLoaded, WorkStatusWords.line(archive, now))
        val dropped = ChatListStatus.afterSync(ChatSync(chats, dropped = 4), held = true, keptAt = kept, asked = false, now = now)
        assertEquals(4, (dropped as WorkStatus.Partial).failedCount)
    }

    @Test
    fun `a success after a failure clears it and only a pressed one says up to date`() {
        val whole = ChatSync(chats)
        assertEquals(WorkStatus.Idle, ChatListStatus.afterSync(whole, held = true, keptAt = kept, asked = false, now = now))
        assertEquals(WorkStatus.Updated(now), ChatListStatus.afterSync(whole, held = true, keptAt = kept, asked = true, now = now))
    }

    @Test
    fun `nothing is said while no list is held`() {
        assertEquals(WorkStatus.Idle, ChatListStatus.afterSync(ChatSync(emptyList(), failure = "Connection closed"), held = false, keptAt = null, asked = true, now = now))
        assertEquals(WorkStatus.Idle, ChatListStatus.afterError(IllegalStateException("boom"), held = false, keptAt = null, now = now))
    }

    @Test
    fun `a thrown failure with chats held is said in the line and the foot`() {
        val status = ChatListStatus.afterError(IllegalStateException("boom"), held = true, keptAt = kept, now = now)
        assertTrue(status is WorkStatus.Failed)
        val foot = ChatListStatus.footWords(status, IllegalStateException("boom"), now)
        assertTrue("$foot", foot != null && foot.startsWith("Could not update."))
        // Offline is the banner's to say; nothing failed says nothing.
        assertNull(ChatListStatus.footWords(WorkStatus.Offline(kept), null, now))
        assertNull(ChatListStatus.footWords(WorkStatus.Idle, null, now))
    }

    @Test
    fun `a garbage failure text is never a wait and never shown raw`() {
        for (garbage in listOf("", "FLOOD_WAIT_", "FLOOD_WAIT_abc", "\u0000", "x".repeat(50_000))) {
            val status = ChatListStatus.afterSync(ChatSync(chats, failure = garbage), held = true, keptAt = null, asked = false, now = now)
            assertTrue("$garbage", status is WorkStatus.Failed)
            assertTrue(WorkStatusWords.line(status, now).length < 500)
        }
    }
}
