package com.tmplayer.data

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The grouping and matching a large account runs through, timed (HX8): one chat of 20,000 videos
 * arranged into shows and films, the search across chats folding its pages, Home picking its
 * chats out of 3,000. Prints "HX8|" lines; the limits are loose, there to catch a step that grows
 * with the square of the videos.
 */
class ScaleCpuTest {

    private fun report(line: String) = println("HX8| $line")

    /** [n] videos as a big chat posts them: shows with seasons, films with copies, loose clips. */
    private fun chat(n: Int, chatId: Long = 1): List<MediaItem> = (0 until n).map { i ->
        val name = when (i % 4) {
            0, 1 -> "Show.${i % 150}.S0${i % 6 + 1}E${(i / 6) % 24 + 1}.1080p.WEB-DL.mkv"
            2 -> "Film.Title.${i % 900}.${1980 + i % 40}.${if (i % 8 == 2) "720p" else "1080p"}.BluRay.mkv"
            else -> "clip_${i}.mp4"
        }
        MediaItem(
            chatId = chatId, messageId = (n - i).toLong() * 16, fileId = i, title = name.substringBeforeLast('.'),
            sizeBytes = 700L * 1024 * 1024, durationSec = 1_400 + i % 3_000, mimeType = "video/x-matroska",
            thumbnailFileId = 0, miniThumbnail = null, date = 2_000_000_000 - i * 600, fileName = name,
        )
    }

    private inline fun timed(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / 1_000_000
    }

    @Test
    fun `a chat of twenty thousand videos is grouped in linear time`() {
        // Warm the JIT first, so the sizes compare like with like.
        repeat(3) { SeriesShelf.arrange(chat(2_000), emptyMap()) }
        val warm = HashMap<Int, Long>()
        val times = listOf(1_000, 5_000, 20_000).associateWith { n ->
            val items = chat(n)
            timed { SeriesShelf.arrange(items, emptyMap()) }.also {
                // Again over the same videos, whose names are read once each ([MediaItem.parsedName]):
                // what a grid pays when a page lands on what it holds.
                warm[n] = timed { SeriesShelf.arrange(items, emptyMap()) }
            }
        }
        report("SeriesShelf.arrange, first time: ${times.entries.joinToString { "${it.key} videos ${it.value} ms" }}; again: ${warm.entries.sortedBy { it.key }.joinToString { "${it.key} ${it.value} ms" }}")
        // A grid read to its end arranges again at every page of 40: the sum over its pages.
        val pages = 20_000 / 40
        report("a 20,000 video grid arranged again at each of its $pages pages: about ${warm.getValue(20_000) * pages / 2 / 1000} s of CPU in all")
        val perItemSmall = times.getValue(1_000).coerceAtLeast(1).toDouble() / 1_000
        val perItemLarge = times.getValue(20_000).toDouble() / 20_000
        assertTrue("grouping grows faster than the videos: $times", perItemLarge < perItemSmall * 8 + 0.05)
    }

    @Test
    fun `home picks its chats out of three thousand quickly`() {
        val chats = (1L..3_000L).map { ChatSummary(it, "Chat $it", null, 0, ChatKind.Channel, isPinned = it in 11L..15L) }
        val recent = (1L..400L).flatMap { chat(2, it) }
        val starred = (1L..400L step 10).toSet()
        repeat(3) { HomeRows.homeChats(chats, starred, recent) }
        val ms = timed { repeat(10) { HomeRows.homeChats(chats, starred, recent) } } / 10.0
        report("HomeRows.homeChats over 3,000 chats: $ms ms")
        assertTrue(ms < 50)
    }

    @Test
    fun `the search across chats folds three hundred cards quickly`() {
        val query = GlobalSearch.parse("Show")
        val pages = (0 until 10).map { p -> chat(60, chatId = p + 1L) }
        repeat(2) { pages.fold(emptyList<ResultGroup>()) { acc, page -> GlobalSearch.merge(acc, page, query) } }
        var groups = emptyList<ResultGroup>()
        val ms = timed { pages.forEach { page -> groups = GlobalSearch.merge(groups, page, query) } }
        report("GlobalSearch.merge: 10 pages of 60 into ${groups.size} cards in $ms ms")
        assertTrue(ms < 2_000)
    }
}
