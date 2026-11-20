package com.tmplayer.ui.browse

import com.tmplayer.data.HomeRow
import com.tmplayer.data.HomeRows
import com.tmplayer.data.MediaCursors
import com.tmplayer.data.MediaItem
import com.tmplayer.data.ShelfEntry
import com.tmplayer.data.WatchLaterRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.minutes

/**
 * HX58, the scale set that sits beside [HomeScaleTest], [HomeLayoutScaleTest],
 * [ChatPickerScaleTest], [OwnChatsSearchScaleTest] and [VideoFilterScaleTest]: the account-wide
 * reads and the long list, on [SyntheticAccount]'s 3,000 chats, 1,500 archived, 100,000 videos
 * and three chats of 20,000, with the budgets the AGENTS.md scale rule states (first content
 * within 3 s, TDLib calls counted, memory bounded). Prints what it measured on "HX58|" lines.
 *
 * Covers: Recently added across chats (HX66), the search across every chat that feeds it, a chat
 * of 20,000 paged to its end, and a Home list of 20,000 lines. The pure JVM clock is virtual, so
 * the seconds are the model's latency and flood waits, not this machine's speed: on a real
 * device the same counts, measured with the fake TDLib flavor (see the plan), give the seconds.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScaleSetTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.at() = testScheduler.currentTime

    private fun report(line: String) = println("HX58| $line")

    private fun TestScope.model(account: SyntheticAccount) = HomeViewModel(
        account.homeSource, work = dispatcher, disk = dispatcher, connected = MutableStateFlow(true), clock = { testScheduler.currentTime },
    )

    private fun chatOf(entry: ShelfEntry): Long = when (entry) {
        is ShelfEntry.File -> entry.item.chatId
        is ShelfEntry.Show -> entry.series.cover.chatId
    }

    @Test
    fun `Recently added on forty starred chats comes in within 3 s, spread over chats, at most three from one`() = runTest(dispatcher, timeout = 5.minutes) {
        val account = SyntheticAccount(backgroundScope, { testScheduler.currentTime })
        val model = model(account)
        val starred = HomeRows.favouriteChats(account.mainList(), account.starred)
        assertEquals(40, starred.size)
        var firstAt = -1L
        var row: HomeRow.Recent? = null
        backgroundScope.launch {
            model.requestRecent()
            model.fill(starred)
            combine(model.loaded, model.recent) { loaded, recent -> loaded to recent }.collect { (loaded, recent) ->
                val rows = HomeRows.build(emptyList(), starred, loaded, recent, starred = account.starred, autoFill = true)
                val recentRow = rows.firstOrNull { it is HomeRow.Recent } as HomeRow.Recent?
                if (recentRow != null && recentRow.loaded && recentRow.entries.isNotEmpty()) {
                    if (firstAt < 0) firstAt = at()
                    // The row as it settles: the first draw may hold only the chats that answered first.
                    row = recentRow
                }
            }
        }
        while (at() < 60_000L) advanceTimeBy(1_000)
        val entries = row?.entries.orEmpty()
        val perChat = entries.groupingBy(::chatOf).eachCount()
        report("Recently added: first row at ${firstAt / 1000.0} s, settled ${entries.size} tiles from ${perChat.size} chats, calls ${account.calls}")
        assertTrue("first at $firstAt ms", firstAt in 0..3_000)
        assertTrue("tiles ${entries.size}", entries.size in 1..HomeRows.LIMIT)
        // Many chats have videos, so the cap holds and the row is not one chat's.
        assertTrue("one chat took ${perChat.values.maxOrNull()}", (perChat.values.maxOrNull() ?: 0) <= HomeRows.RECENT_PER_CHAT)
        assertTrue("only ${perChat.size} chats", perChat.size >= HomeRows.LIMIT / HomeRows.RECENT_PER_CHAT)
        // Newest first, whichever chat a tile came from.
        val dates = entries.map { (it as? ShelfEntry.File)?.item?.date ?: (it as ShelfEntry.Show).series.cover.date }
        assertEquals(dates.sortedDescending(), dates)
    }

    @Test
    fun `the search across every chat asks Telegram twice, whatever the account holds`() = runTest(dispatcher, timeout = 5.minutes) {
        val account = SyntheticAccount(backgroundScope, { testScheduler.currentTime })
        assertTrue("total ${account.totalVideos}", account.totalVideos in 99_000..101_000)
        var answered = emptyList<MediaItem>()
        var doneAt = -1L
        backgroundScope.launch {
            answered = account.homeSource.recentMedia()
            doneAt = at()
        }
        while (doneAt < 0 && at() < 30_000L) advanceTimeBy(100)
        report("search across chats: ${answered.size} videos at ${doneAt / 1000.0} s, calls ${account.calls}")
        assertTrue("answered at $doneAt ms", doneAt in 0..3_000)
        // One searchMessages per filter, a bounded page each: not a read of the 3,000 chats.
        assertEquals(2, account.total())
        assertTrue("held ${answered.size}", answered.size <= 80)
        assertEquals(answered.size, answered.map { it.id }.toSet().size)
    }

    @Test
    fun `a chat of 20,000 videos pages to its end, the first page within 3 s, three reads a page, no repeats`() = runTest(dispatcher, timeout = 10.minutes) {
        val account = SyntheticAccount(backgroundScope, { testScheduler.currentTime })
        // Chat 1 is one of the three of 20,000; its files are all videos or documents Home keeps.
        assertEquals(20_000, account.videoCount(1))
        val seen = HashSet<String>()
        var firstAt = -1L
        var pages = 0
        var finishedAt = -1L
        var largestPage = 0
        backgroundScope.launch {
            var cursors = MediaCursors()
            while (true) {
                val page = account.mediaPage(1, cursors, PAGE)
                pages++
                if (firstAt < 0) firstAt = at()
                largestPage = maxOf(largestPage, page.items.size)
                page.items.forEach { seen += it.id }
                cursors = page.cursors
                if (page.endReached || pages > 5_000) break
            }
            finishedAt = at()
        }
        while (finishedAt < 0 && at() < 3_600_000L) advanceTimeBy(1_000)
        report("20,000 chat: first page at ${firstAt / 1000.0} s, ${pages} pages, ${seen.size} videos, done at ${finishedAt / 1000.0} s, calls ${account.calls}")
        assertTrue("first at $firstAt ms", firstAt in 0..3_000)
        assertTrue("never finished", finishedAt >= 0)
        assertEquals(20_000, seen.size)
        // A page is a video search and a document search (the animation one answers once): the
        // reads grow with the pages, never with the chat's size or with the items on a page.
        val reads = account.calls["searchChatMessages"] ?: 0
        assertTrue("reads $reads for $pages pages", reads <= pages * 3)
        // A page holds what it was asked for, so the list a grid keeps grows by pages, not at once.
        assertTrue("a page held $largestPage", largestPage <= PAGE * 2)
    }

    @Test
    fun `a Home list of 20,000 lines costs its first lines only and still says how many`() {
        val records = (1..20_000).map { i ->
            WatchLaterRecord(
                chatId = (i % 50).toLong() + 1, messageId = i.toLong() * 16, fileId = i, title = "Holiday clip $i",
                fileName = "Holiday clip $i.mp4", chatTitle = "Chat", sizeBytes = 1, durationSec = 60,
                mimeType = "video/mp4", addedAt = i.toLong(),
            )
        }
        val info = com.tmplayer.data.ListInfo(id = "big", name = "Big", at = 0)
        val started = System.nanoTime()
        val rows = HomeRows.listRows(listOf(com.tmplayer.data.VisibleList(info, records)))
        val ms = (System.nanoTime() - started) / 1_000_000
        report("a list of 20,000 lines: row built in $ms ms")
        val row = rows.single() as HomeRow.Listed
        assertEquals(HomeRows.LIMIT, row.records.size)
        assertEquals(20_000, row.count)
        // Wall clock here, generously: the build reads 200 lines, so seconds would mean it read them all.
        assertTrue("took $ms ms", ms < 3_000)
    }

    private companion object {
        const val PAGE = 40
    }
}
