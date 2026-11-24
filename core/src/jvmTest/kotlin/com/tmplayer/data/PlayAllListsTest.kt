package com.tmplayer.data

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Play all and Shuffle on a list's page, and "skip watched" on the way forward (WP-32, WP-87): a show saved
 * whole is a page and not a video, the press starts with the first line (or the first of the seeded order),
 * the same seed gives the same order again, and Previous still goes back to what was played.
 */
class PlayAllListsTest {

    private fun line(id: Long, chat: Long = 7, series: String = "") = WatchLaterRecord(
        chatId = chat, messageId = id, fileId = id.toInt(), title = "Clip $id", fileName = "clip $id.mp4", chatTitle = "Chat",
        sizeBytes = 1_000 + id, durationSec = 60, mimeType = "video/mp4", addedAt = id, series = series,
    )

    @Test
    fun `a list plays its videos in the order they were added and leaves a show saved whole out`() {
        val records = listOf(line(3), line(1), line(2, series = "show-a"), line(5))
        assertEquals(3, PlayAllLists.playable(records))
        assertEquals(listOf(3L, 1L, 5L), PlayAllLists.queue(records).map { it.messageId })
        val press = PlayAllLists.request(records, shuffle = false, seed = 99)!!
        assertEquals(PlayAll.Mode.List, press.mode)
        assertEquals(3L, press.messageId)
        assertEquals(0L, press.seed)
        // The player steps through it: after 3 comes 1, then 5, and 5 is the last.
        val queue = press.queue
        assertEquals(1L, PlayAll.listSteps(queue, queue[0]).next?.messageId)
        assertEquals(5L, PlayAll.listSteps(queue, queue[1]).next?.messageId)
        assertNull(PlayAll.listSteps(queue, queue[2]).next)
        assertEquals(1L, PlayAll.listSteps(queue, queue[2]).previous?.messageId)
    }

    @Test
    fun `a list of shows alone, or an empty one, has nothing to play`() {
        assertNull(PlayAllLists.request(emptyList(), shuffle = false, seed = 1))
        assertNull(PlayAllLists.request(listOf(line(1, series = "a"), line(2, series = "b")), shuffle = true, seed = 1))
        assertEquals(0, PlayAllLists.playable(listOf(line(1, series = "a"))))
    }

    @Test
    fun `a shuffle starts with the first of the seeded order and the same seed gives the same order`() {
        val records = (1L..30L).map { line(it) }
        val a = PlayAllLists.request(records, shuffle = true, seed = 42)!!
        val b = PlayAllLists.request(records.reversed(), shuffle = true, seed = 42)!!
        assertEquals(PlayAll.Mode.ListShuffle, a.mode)
        assertEquals(42L, a.seed)
        assertEquals(a.messageId, b.messageId)
        val order = PlayAll.shuffled(42, a.queue).map { it.messageId }
        assertEquals(order.first(), a.messageId)
        assertEquals(30, order.toSet().size)
        // Following the run from its first video visits every video once.
        var at = a.queue.first { it.messageId == a.messageId }
        val seen = mutableListOf(at.messageId)
        while (true) {
            at = PlayAll.listSteps(a.queue, at, a.seed).next ?: break
            seen += at.messageId
        }
        assertEquals(order, seen)
    }

    // ---- skip watched ---------------------------------------------------------------------------

    private fun video(id: Long) = MediaItem(
        chatId = 7, messageId = id, fileId = id.toInt(), title = "v$id", fileName = "v$id.mp4", sizeBytes = 1_000 + id,
        durationSec = 60, mimeType = "video/mp4", thumbnailFileId = 0, miniThumbnail = null, date = id.toInt(),
    )

    /** A chat of [count] videos, newest first, [page] to a page, as [ChatRepository.videoWindow] answers. */
    private fun window(count: Int, page: Int = 10): suspend (Long, Boolean) -> ChatOrder.Window? = { anchor, newer ->
        if (newer) {
            val above = (1L..count).filter { it > anchor }
            ChatOrder.Window(above.take(page).map(::video), if (above.size <= page) 0 else above[page - 1])
        } else {
            val below = (1L..count).filter { anchor == 0L || it <= anchor }.reversed()
            ChatOrder.Window(below.take(page).map(::video), if (below.size <= page) 0 else below[page])
        }
    }

    @Test
    fun `the way forward goes past watched videos and Previous goes back to what was played`() = runBlocking {
        val watched = setOf(49L, 48L, 47L, 40L)
        val steps = ChatOrder.steps(
            current = video(50),
            sort = VideoSort.DEFAULT,
            fitsNext = { it.messageId !in watched },
            fetch = window(100),
        )
        // Newest first: after 50 comes 49, and 49, 48, 47 are watched, so the next is 46.
        assertEquals(46L, steps.next?.messageId)
        // The video before it in the order is 51, watched or not: Previous does not skip.
        assertEquals(51L, steps.previous?.messageId)
        val fromSeen = ChatOrder.steps(current = video(50), sort = VideoSort.DEFAULT, fetch = window(100))
        assertEquals(49L, fromSeen.next?.messageId)
    }

    @Test
    fun `a chat of 20,000 where everything ahead is watched ends the run within the page budget`() = runBlocking {
        var asked = 0
        val base = window(20_000, page = 40)
        val steps = ChatOrder.steps(
            current = video(20_000),
            sort = VideoSort.DEFAULT,
            fitsNext = { false },
            fetch = { anchor, newer -> asked++; base(anchor, newer) },
        )
        assertNull(steps.next)
        assertTrue(asked <= ChatOrder.MAX_PAGES * 2, "asked $asked pages")
        assertFalse(asked == 0)
    }
}
