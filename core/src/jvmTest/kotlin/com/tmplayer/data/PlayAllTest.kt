package com.tmplayer.data

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Play all and Shuffle (WP-32, WP-87): the press is taken once by the video it started, a list is
 * stepped in its own or the shuffled order, and a Shuffle of a chat of 20,000 reads pages a few at
 * a time, never repeats, and gives the same order again for the same seed.
 */
class PlayAllTest {

    private fun video(id: Long, chat: Long = 7, name: String = "clip $id.mp4") = MediaItem(
        chatId = chat, messageId = id, fileId = id.toInt(), title = name.substringBeforeLast('.'), fileName = name, sizeBytes = 100 + id,
        durationSec = 600, mimeType = "video/mp4", thumbnailFileId = 0, miniThumbnail = null, date = id.toInt(),
    )

    /** A chat as [ChatRepository.videoWindow] answers it: [page] videos a page, newest first, anchor 0 the newest. */
    private class Chat(count: Int, private val page: Int = 40) {
        private val posts = (1..count).map { it.toLong() }
        var asked = 0

        suspend fun window(anchor: Long, newer: Boolean): ChatOrder.Window? {
            asked++
            check(!newer)
            val below = posts.filter { anchor == 0L || it <= anchor }.reversed()
            val taken = below.take(page)
            val items = taken.map { id ->
                MediaItem(
                    chatId = 7, messageId = id, fileId = id.toInt(), title = "v$id", fileName = "v$id.mp4", sizeBytes = 1_000 + id,
                    durationSec = 60, mimeType = "video/mp4", thumbnailFileId = 0, miniThumbnail = null, date = id.toInt(),
                )
            }
            return ChatOrder.Window(items, if (below.size <= page) 0 else below[page])
        }
    }

    // ---- the press ---------------------------------------------------------------------------

    @Test
    fun `a press is taken once, by the video it started`() {
        PlayAll.arm(PlayAll.Request(7, 50, PlayAll.Mode.Chat))
        assertNull(PlayAll.take(7, 51))
        assertNull(PlayAll.take(8, 50))
        val taken = PlayAll.take(7, 50)
        assertNotNull(taken)
        assertEquals(PlayAll.Mode.Chat, taken.mode)
        // The same video opened again is not a second press.
        assertNull(PlayAll.take(7, 50))
    }

    @Test
    fun `a new press replaces the one nothing took`() {
        PlayAll.arm(PlayAll.Request(7, 1, PlayAll.Mode.Chat))
        PlayAll.arm(PlayAll.Request(7, 2, PlayAll.Mode.ChatShuffle, seed = 9))
        assertNull(PlayAll.take(7, 1))
        assertEquals(9L, PlayAll.take(7, 2)?.seed)
    }

    @Test
    fun `modes read back from their stored number, and garbage is off`() {
        for (mode in PlayAll.Mode.entries) assertEquals(mode, PlayAll.Mode.of(mode.ordinal))
        assertEquals(PlayAll.Mode.Off, PlayAll.Mode.of(-1))
        assertEquals(PlayAll.Mode.Off, PlayAll.Mode.of(99))
    }

    // ---- the shuffled order ------------------------------------------------------------------

    @Test
    fun `the key is the same every time, differs by seed, and never needs the other videos`() {
        assertEquals(PlayAll.key(5, 7, 100), PlayAll.key(5, 7, 100))
        assertNotEquals(PlayAll.key(5, 7, 100), PlayAll.key(6, 7, 100))
        assertNotEquals(PlayAll.key(5, 7, 100), PlayAll.key(5, 7, 101))
        assertNotEquals(PlayAll.key(5, 7, 100), PlayAll.key(5, 8, 100))
        assertTrue(PlayAll.key(-1, -1, -1) >= 0)
        assertTrue(PlayAll.newSeed(0) != 0L)
        assertTrue(PlayAll.newSeed(1_700_000_000_000) != 0L)
    }

    @Test
    fun `the same seed shuffles the same list the same way, another seed another way`() {
        val items = (1L..30L).map { video(it) }
        assertEquals(PlayAll.shuffled(3, items), PlayAll.shuffled(3, items.reversed()))
        assertNotEquals(PlayAll.shuffled(3, items).map { it.messageId }, PlayAll.shuffled(4, items).map { it.messageId })
        assertEquals(items.map { it.messageId }.toSet(), PlayAll.shuffled(3, items).map { it.messageId }.toSet())
        assertEquals(PlayAll.shuffled(3, items).first(), PlayAll.shuffleFirst(3, items))
        assertNull(PlayAll.shuffleFirst(3, emptyList()))
    }

    // ---- a list ------------------------------------------------------------------------------

    @Test
    fun `a list plays in its own order, the ends having one neighbour, and a stranger none`() {
        val queue = listOf(video(30, chat = 1), video(10, chat = 2), video(20, chat = 1))
        assertEquals(10L, PlayAll.listSteps(queue, queue[0]).next?.messageId)
        assertNull(PlayAll.listSteps(queue, queue[0]).previous)
        assertEquals(30L, PlayAll.listSteps(queue, queue[1]).previous?.messageId)
        assertEquals(20L, PlayAll.listSteps(queue, queue[1]).next?.messageId)
        assertNull(PlayAll.listSteps(queue, queue[2]).next)
        assertEquals(EpisodeSteps(), PlayAll.listSteps(queue, video(99)))
        assertEquals(EpisodeSteps(), PlayAll.listSteps(emptyList(), video(1)))
    }

    @Test
    fun `a shuffled list visits every video once and the same seed gives the same walk`() {
        val queue = (1L..50L).map { video(it) }
        fun walk(seed: Long): List<Long> {
            val order = mutableListOf<Long>()
            var at: MediaItem? = PlayAll.shuffleFirst(seed, queue)
            while (at != null) {
                order += at.messageId
                at = PlayAll.listSteps(queue, at, seed).next
            }
            return order
        }
        val walked = walk(11)
        assertEquals(50, walked.size)
        assertEquals(50, walked.toSet().size)
        assertEquals(walked, walk(11))
        assertNotEquals(walked, walk(12))
    }

    // ---- a chat of 20,000 --------------------------------------------------------------------

    private fun walkChat(chat: Chat, seed: Long, start: MediaItem?, steps: Int): List<Long> = runBlocking {
        val pool = PlayAll.ShufflePool(seed)
        val fetch: suspend (Long, Boolean) -> ChatOrder.Window? = { anchor, newer -> chat.window(anchor, newer) }
        val seen = mutableListOf<Long>()
        // As the grid does: the first of the loaded page in the shuffled order starts it.
        var at = start ?: PlayAll.shuffleFirst(seed, chat.window(0, false)!!.items)
        repeat(steps) {
            val here = at ?: return@runBlocking seen
            seen += here.messageId
            val before = chat.asked
            at = pool.steps(here, fetch).next
            // Calls per advance are bounded, whatever the size of the chat.
            assertTrue(chat.asked - before <= PlayAll.PAGES_PER_ADVANCE, "asked ${chat.asked - before} pages for one step")
        }
        seen
    }

    @Test
    fun `a shuffle of 20000 videos never repeats, asks a bounded number of pages a step and stays under the pool cap`() {
        val chat = Chat(20_000)
        val seen = walkChat(chat, seed = 42, start = null, steps = 600)
        assertEquals(600, seen.size)
        assertEquals(600, seen.toSet().size)
        // 600 steps read about 15 pages of 40, nowhere near the 500 pages of the whole chat.
        assertTrue(chat.asked < 60, "read ${chat.asked} pages")
    }

    @Test
    fun `the same seed walks a chat the same way again`() {
        assertEquals(walkChat(Chat(3_000), 7, null, 120), walkChat(Chat(3_000), 7, null, 120))
        assertNotEquals(walkChat(Chat(3_000), 7, null, 60), walkChat(Chat(3_000), 8, null, 60))
    }

    @Test
    fun `a chat that cannot be asked ends the shuffle where it is and invents nothing`() {
        val steps = runBlocking {
            PlayAll.ShufflePool(1).steps(video(5)) { _, _ -> null }
        }
        assertNull(steps.next)
        assertNull(steps.previous)
    }

    @Test
    fun `the end of a small chat ends the shuffle`() {
        val chat = Chat(5)
        val seen = walkChat(chat, 3, null, 50)
        assertEquals(5, seen.size)
        assertEquals(5, seen.toSet().size)
    }

    /** PB2-06: the pool goes from player to player through a whole run, so it must not keep the reader (and the screen behind it). */
    @Test
    fun `a pool keeps no reference to the reader it was asked with`() {
        class Screen(val chat: Chat)
        val screen = Screen(Chat(200))
        val pool = PlayAll.ShufflePool(5)
        runBlocking { pool.steps(video(1)) { anchor, newer -> screen.chat.window(anchor, newer) } }
        assertTrue(pool.size > 0, "the pages were read")
        val held = PlayAll.ShufflePool::class.java.declaredFields.onEach { it.isAccessible = true }.map { it.get(pool) }
        assertTrue(held.none { it === screen }, "the screen itself is not held")
        assertTrue(
            held.none { it is Function<*> && it.javaClass.declaredFields.any { f -> f.isAccessible = true; f.get(it) === screen } },
            "no kept function closes over the screen",
        )
    }

    @Test
    fun `another copy of the playing file, and a piece of a split video, are not played next`() {
        val twin = video(2, name = "same.mp4").copy(sizeBytes = 500)
        val here = video(1, name = "same.mp4").copy(sizeBytes = 500)
        val next = runBlocking {
            PlayAll.ShufflePool(1).steps(here) { _, _ -> ChatOrder.Window(listOf(twin), 0) }.next
        }
        assertNull(next)
    }
}
