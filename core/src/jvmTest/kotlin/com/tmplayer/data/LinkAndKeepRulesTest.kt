package com.tmplayer.data

import com.tmplayer.i18n.L
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkAndKeepRulesTest {

    private fun video(chatId: Long = 10, messageId: Long = 100, canBeSaved: Boolean = true) = MediaItem(
        chatId = chatId, messageId = messageId, fileId = 1, title = "Clip", sizeBytes = 1000, durationSec = 60,
        mimeType = "video/mp4", thumbnailFileId = 0, miniThumbnail = null, date = 0, canBeSaved = canBeSaved,
    )

    private fun chat(id: Long, kind: ChatKind, title: String = "Chat $id") =
        ChatSummary(id = id, title = title, miniThumbnail = null, photoFileId = 0, kind = kind)

    @Test
    fun `a post with a video plays, from the link's own time before telegram's`() {
        val open = LinkRules.decide(null, 10, isPublic = true, found = true, video = video(), chat = chat(10, ChatKind.Channel), linkStart = 90, telegramStart = 30)
        assertTrue(open is PostOpen.Play)
        assertEquals(90, (open as PostOpen.Play).startSec)
        val fromTelegram = LinkRules.decide(null, 10, true, true, video(), null, linkStart = 0, telegramStart = 30) as PostOpen.Play
        assertEquals(30, fromTelegram.startSec)
    }

    @Test
    fun `a post with no video opens its chat and says why`() {
        val open = LinkRules.decide(null, 10, true, found = true, video = null, chat = chat(10, ChatKind.Channel), linkStart = 0, telegramStart = 0)
        assertEquals(PostOpen.OpenChat(chat(10, ChatKind.Channel), L.linkNoVideo), open)
        val gone = LinkRules.decide(null, 10, true, found = false, video = null, chat = chat(10, ChatKind.Channel), linkStart = 0, telegramStart = 0)
        assertEquals(L.linkPostGone, (gone as PostOpen.OpenChat).note)
    }

    @Test
    fun `a private chat not joined and an unknown post are refused with words`() {
        assertEquals(PostOpen.Refused(L.linkPrivateNotMember), LinkRules.decide(null, 0, isPublic = false, found = false, video = null, chat = null, linkStart = 0, telegramStart = 0))
        assertEquals(PostOpen.Refused(L.linkNotFound), LinkRules.decide(null, 0, isPublic = true, found = false, video = null, chat = null, linkStart = 0, telegramStart = 0))
    }

    @Test
    fun `the kids lock stops a link to a chat it does not allow, and previewing any other`() {
        val locked = LinkRules.decide(setOf(5L), 10, true, true, video(), chat(10, ChatKind.Channel), 0, 0)
        assertEquals(PostOpen.Refused(L.linkKidsLock), locked)
        val allowed = LinkRules.decide(setOf(10L), 10, true, true, video(), chat(10, ChatKind.Channel), 0, 0)
        assertTrue(allowed is PostOpen.Play)
        assertFalse(LinkRules.mayJoin(setOf(10L)))
        assertFalse(LinkRules.mayJoin(emptySet()))
        assertTrue(LinkRules.mayJoin(null))
    }

    @Test
    fun `the invite check's errors are said plainly`() {
        assertEquals(L.linkInviteInvalid, LinkRules.inviteFailure("INVITE_HASH_INVALID"))
    }

    @Test
    fun `a copy out needs saving allowed and the whole download here`() {
        assertTrue(KeepVideo.mayExport(canBeSaved = true, inDownloads = true))
        assertFalse(KeepVideo.mayExport(canBeSaved = false, inDownloads = true))
        assertFalse(KeepVideo.mayExport(canBeSaved = true, inDownloads = false))
    }

    @Test
    fun `the detail page offers a copy out and comments only where they apply`() {
        val base = DetailContext(canBeSaved = true)
        assertTrue(DetailActions.of(base.copy(canExport = true, inDownloads = true)).contains(DetailAction.ExportCopy))
        assertFalse(DetailActions.of(base.copy(canExport = true, inDownloads = false)).contains(DetailAction.ExportCopy))
        assertFalse(DetailActions.of(base.copy(canBeSaved = false, canExport = true, inDownloads = true)).contains(DetailAction.ExportCopy))
        assertTrue(DetailActions.keepsACopy(DetailAction.ExportCopy))
        assertTrue(DetailActions.of(base.copy(comments = 3)).contains(DetailAction.CommentVideos))
        assertFalse(DetailActions.of(base).contains(DetailAction.CommentVideos))
    }

    @Test
    fun `a pinned post shows its own video, else a post it links to, else nothing`() {
        val withVideo = PinnedPosts.decide(5, video(), "Episode one\nmore words", emptyList())
        assertEquals("Episode one", withVideo?.text)
        assertEquals(100L, withVideo?.video?.messageId)
        val linking = PinnedPosts.decide(5, null, "Start here: https://t.me/chan_name/9", emptyList())
        assertEquals(TgLink.Post("chan_name", null, 9), linking?.link)
        val hidden = PinnedPosts.decide(5, null, "Start here", listOf("https://t.me/c/123456/7"))
        assertEquals(TgLink.Post(null, 123456, 7), hidden?.link)
        assertNull(PinnedPosts.decide(5, null, "Be kind. No spam. https://example.com", emptyList()))
        assertNull(PinnedPosts.decide(5, null, "Join us https://t.me/+AbCdEf123456", emptyList()))
    }

    @Test
    fun `the comments' group has to be allowed by the kids lock as well as the channel`() {
        assertTrue(PostComments.allowed(null, 1, 2))
        assertTrue(PostComments.allowed(setOf(1L, 2L), 1, 2))
        assertFalse(PostComments.allowed(setOf(1L), 1, 2))
    }
}
