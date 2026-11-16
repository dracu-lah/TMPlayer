package com.tmplayer.data

import kotlinx.coroutines.runBlocking
import org.junit.After
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What plays after a video that is not an episode (EG-F30), and Repeat this video (EG-F52): the
 * chat's order follows the grid's sort, a chat is read a page at a time from the video playing,
 * and the end of the chat stops.
 */
class ChatOrderTest {

    @After
    fun tearDown() = RepeatVideo.reset()

    private fun video(id: Long, size: Long = 100, name: String = "clip $id.mp4", chat: Long = 7) = MediaItem(
        chatId = chat, messageId = id, fileId = id.toInt(), title = name.substringBeforeLast('.'), fileName = name, sizeBytes = size,
        durationSec = 600, mimeType = "video/mp4", thumbnailFileId = 0, miniThumbnail = null, date = id.toInt(),
    )

    /**
     * A chat that answers as [ChatRepository.videoWindow] does: [page] videos a page, around an
     * anchor, and the next anchor 0 once a side has nothing further. Counts the pages asked for.
     */
    private class Chat(private val posts: List<MediaItem>, private val page: Int = 3) {
        var asked = 0
        var offline = false

        suspend fun window(anchor: Long, newer: Boolean): ChatOrder.Window? {
            asked++
            if (offline) return null
            val sorted = posts.sortedBy { it.messageId }
            return if (newer) {
                val above = sorted.filter { it.messageId > anchor }
                val taken = above.take(page - 1)
                // The anchor's own post comes with the page, as a search with a negative offset has it.
                val items = taken + sorted.filter { it.messageId <= anchor }.takeLast(1)
                ChatOrder.Window(items, if (above.size < page - 1) 0 else taken.last().messageId)
            } else {
                val below = sorted.filter { it.messageId <= anchor }.reversed()
                val taken = below.take(page)
                ChatOrder.Window(taken, if (below.size < page) 0 else taken.last().messageId)
            }
        }
    }

    private val chat = (1L..10L).map { video(it * 10) }

    private fun steps(current: Long, sort: VideoSort, source: Chat = Chat(chat)) = runBlocking {
        ChatOrder.steps(chat.first { it.messageId == current }, sort) { anchor, newer -> source.window(anchor, newer) }
    }

    @Test
    fun `a newest first grid goes on to the next older video, oldest first to the next newer one`() {
        val newest = steps(50, VideoSort.Newest)
        assertEquals(40L, newest.next?.messageId)
        assertEquals(60L, newest.previous?.messageId)
        val oldest = steps(50, VideoSort.Oldest)
        assertEquals(60L, oldest.next?.messageId)
        assertEquals(40L, oldest.previous?.messageId)
    }

    @Test
    fun `every other sort follows the chat's own order, newest first`() {
        for (sort in VideoSort.entries.filter { it != VideoSort.Oldest }) {
            assertEquals(40L, steps(50, sort).next?.messageId, sort.name)
        }
    }

    @Test
    fun `a change of sort between two videos is followed by the next lookup`() {
        assertEquals(40L, steps(50, VideoSort.Newest).next?.messageId)
        assertEquals(60L, steps(50, VideoSort.Oldest).next?.messageId)
    }

    @Test
    fun `the last video of the chat has no next one, the first none before it`() {
        assertNull(steps(10, VideoSort.Newest).next)
        assertEquals(20L, steps(10, VideoSort.Newest).previous?.messageId)
        assertNull(steps(100, VideoSort.Oldest).next)
        assertNull(steps(100, VideoSort.Newest).previous)
    }

    @Test
    fun `the last video of a page loads the next page, far from the first one`() {
        // Pages of three, so the video next to the playing one is a page away.
        val sparse = (1L..8L).map { video(it * 100) }
        val source = Chat(sparse, page = 2)
        val found = runBlocking {
            ChatOrder.lookUp(sparse.last(), ChatOrder.Way.Older, fetch = { a, n -> source.window(a, n) })
        }
        assertEquals(700L, found?.messageId)
        // A video a long way down: the page after is asked from where the last one stopped.
        val down = runBlocking {
            ChatOrder.lookUp(sparse[6], ChatOrder.Way.Older, fits = { it.messageId <= 300 }, fetch = { a, n -> source.window(a, n) })
        }
        assertEquals(300L, down?.messageId)
        assertTrue(source.asked > 2)
    }

    @Test
    fun `a next video above the size limit is skipped as the grid skips it`() {
        val big = chat.map { if (it.messageId == 40L) it.copy(sizeBytes = 9_000) else it }
        val source = Chat(big)
        val found = runBlocking {
            ChatOrder.lookUp(big.first { it.messageId == 50L }, ChatOrder.Way.Older, fits = { SizeFilter.matches(it.sizeBytes, 0, 1_000) }) { a, n -> source.window(a, n) }
        }
        assertEquals(30L, found?.messageId)
    }

    @Test
    fun `a copy of the same file posted next to it is not played again`() {
        val posts = listOf(video(10), video(20, name = "talk.mp4", size = 5), video(30, name = "talk.mp4", size = 5), video(40))
        val found = ChatOrder.pick(posts[2], posts, ChatOrder.Way.Older)
        assertEquals(10L, found?.messageId)
    }

    @Test
    fun `a deleted next message is not in the chat, so the one after it plays`() {
        val without = chat.filterNot { it.messageId == 40L }
        assertEquals(30L, steps(50, VideoSort.Newest, Chat(without)).next?.messageId)
    }

    @Test
    fun `a chat that cannot be asked has no next video and never loops`() {
        val offline = Chat(chat).also { it.offline = true }
        val none = steps(50, VideoSort.Newest, offline)
        assertNull(none.next)
        assertNull(none.previous)
        // A page that answers the anchor it was asked from ends the search rather than asking again.
        val stuck = runBlocking {
            ChatOrder.lookUp(chat[4], ChatOrder.Way.Older) { anchor, _ -> ChatOrder.Window(emptyList(), anchor) }
        }
        assertNull(stuck)
        val endless = Chat(chat)
        val bounded = runBlocking {
            ChatOrder.lookUp(chat[4], ChatOrder.Way.Older) { anchor, _ -> endless.asked++; ChatOrder.Window(emptyList(), anchor - 1) }
        }
        assertNull(bounded)
        assertEquals(ChatOrder.MAX_PAGES, endless.asked)
    }

    @Test
    fun `another chat's video is never the next one, and the size filter is asked of each`() {
        val posts = listOf(video(10), video(20, chat = 8), video(30), video(40))
        assertEquals(30L, ChatOrder.pick(posts[3], posts, ChatOrder.Way.Older)?.messageId)
        assertEquals(10L, ChatOrder.pick(posts[3], posts, ChatOrder.Way.Older) { it.messageId != 30L }?.messageId)
    }

    // ---- Repeat this video ----------------------------------------------------------------------

    @Test
    fun `repeat lasts for the video it was set on and lapses when another starts`() {
        val first = video(10)
        val second = video(20)
        assertFalse(RepeatVideo.replaysAtEnd(first))
        RepeatVideo.set(true, first)
        assertTrue(RepeatVideo.replaysAtEnd(first))
        assertFalse(RepeatVideo.replaysAtEnd(second))
        assertFalse(RepeatVideo.repeats(second))
        RepeatVideo.set(false)
        assertFalse(RepeatVideo.replaysAtEnd(first))
    }

    @Test
    fun `Keep repeat on carries it to the next video`() {
        RepeatVideo.set(true, video(10))
        assertTrue(RepeatVideo.replaysAtEnd(video(20), keepRepeat = true))
        assertFalse(RepeatVideo.replaysAtEnd(video(20), keepRepeat = false))
    }

    @Test
    fun `a sleep timer for the end of the video wins over repeat`() {
        val clip = video(10)
        RepeatVideo.set(true, clip)
        assertFalse(RepeatVideo.replaysAtEnd(clip, sleepAtTheEnd = true))
        assertTrue(RepeatVideo.replaysAtEnd(clip, sleepAtTheEnd = false))
    }

    @Test
    fun `turning repeat on with no video leaves it off`() {
        RepeatVideo.set(true, null)
        assertFalse(RepeatVideo.repeats(video(10), keepRepeat = true))
    }
}
