package com.tmplayer.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OfflineModeTest {

    private fun record(message: Long, at: Long = message, position: Long = 0, file: Int = message.toInt()) = ResumeRecord(
        chatId = -1L, messageId = message, fileId = file, title = "Video $message", chatTitle = "Films",
        sizeBytes = 1_000, durationSec = 100, positionMs = position, durationMs = 100_000, updatedAt = at,
    )

    private fun key(message: Long) = ProgressKeys.of(-1L, message)

    // The debounce: a flaky link must not flash.

    @Test
    fun `an offline that holds for two seconds is shown, and so is the way back`() = runTest {
        val raw = Channel<Boolean>(Channel.UNLIMITED)
        val shown = ArrayList<Pair<Long, Boolean>>()
        val job = launch {
            OfflineSettle.settled(flow { for (v in raw) emit(v) }).collect { shown += testScheduler.currentTime to it }
        }
        advanceTimeBy(1)
        raw.send(true)
        advanceTimeBy(OfflineSettle.HOLD_MS + 1)
        raw.send(false)
        advanceTimeBy(OfflineSettle.HOLD_MS + 1)
        job.cancel()
        assertEquals(listOf(false, true, false), shown.map { it.second })
        assertTrue("offline is believed only after the hold", shown[1].first >= OfflineSettle.HOLD_MS)
    }

    @Test
    fun `a blink of offline inside the hold never shows`() = runTest {
        val raw = Channel<Boolean>(Channel.UNLIMITED)
        val shown = ArrayList<Boolean>()
        val job = launch { OfflineSettle.settled(flow { for (v in raw) emit(v) }).collect { shown += it } }
        advanceTimeBy(1)
        repeat(10) {
            raw.send(true)
            advanceTimeBy(OfflineSettle.HOLD_MS / 2)
            raw.send(false)
            advanceTimeBy(OfflineSettle.HOLD_MS / 2)
        }
        advanceTimeBy(OfflineSettle.HOLD_MS * 2)
        job.cancel()
        assertEquals("ten flaps, nothing shown", listOf(false), shown)
    }

    @Test
    fun `a blink of online inside an offline stretch keeps it offline`() = runTest {
        val raw = Channel<Boolean>(Channel.UNLIMITED)
        val shown = ArrayList<Boolean>()
        val job = launch { OfflineSettle.settled(flow { for (v in raw) emit(v) }).collect { shown += it } }
        advanceTimeBy(1)
        raw.send(true)
        advanceTimeBy(OfflineSettle.HOLD_MS + 1)
        raw.send(false)
        advanceTimeBy(OfflineSettle.HOLD_MS / 2)
        raw.send(true)
        advanceTimeBy(OfflineSettle.HOLD_MS * 2)
        job.cancel()
        assertEquals(listOf(false, true), shown)
    }

    @Test
    fun `the first answer is the start state at once`() = runTest {
        val all = OfflineSettle.settled(flow<Boolean> { delay(10) }, start = false).toList()
        assertEquals(listOf(false), all)
    }

    // The rows.

    private fun home(offline: Boolean = true, downloads: List<ResumeRecord> = emptyList(), cached: Set<String> = emptySet()) =
        OfflineHome(offline, downloads, cached)

    private val chatRow = HomeRow.Chat(-5L, "Clips", listOf(ShelfEntry.File(record(30).toMediaItem())), loaded = true)

    @Test
    fun `online is the identity`() {
        val rows = listOf(HomeRow.Continue(listOf(record(1))), chatRow)
        assertEquals(rows, OfflineHomeRows.apply(rows, home(offline = false, downloads = listOf(record(2)))))
    }

    @Test
    fun `On this phone leads with played videos that play, then downloads, and nothing appears twice`() {
        val rows = listOf(
            HomeRow.Continue(listOf(record(1, at = 5), record(2, at = 4), record(3, at = 3))),
            chatRow,
        )
        val out = OfflineHomeRows.apply(rows, home(downloads = listOf(record(3), record(9, at = 99)), cached = setOf(key(1))))
        val lead = out.first() as HomeRow.Continue
        assertTrue(lead.onPhone)
        assertEquals("on-phone", lead.key)
        // 1 is cached, 3 is a download (both from Continue, so with their place), 9 is a download alone.
        assertEquals(listOf(1L, 3L, 9L), lead.records.map { it.messageId })
        val rest = out.filterIsInstance<HomeRow.Continue>().single { !it.onPhone }
        assertEquals("the one that cannot play stays, greyed", listOf(2L), rest.records.map { it.messageId })
        assertEquals(3, out.size)
        assertTrue(out.last() === chatRow)
    }

    @Test
    fun `the ordinary Continue row goes when everything in it plays`() {
        val out = OfflineHomeRows.apply(listOf(HomeRow.Continue(listOf(record(1)))), home(cached = setOf(key(1))))
        assertEquals(1, out.size)
        assertTrue((out.single() as HomeRow.Continue).onPhone)
    }

    @Test
    fun `placeholder rows that can never fill are dropped`() {
        val waiting = HomeRow.Chat(-6L, "Waiting", emptyList(), loaded = false)
        val recent = HomeRow.Recent(emptyList(), loaded = false)
        val out = OfflineHomeRows.apply(listOf(waiting, recent, chatRow), home())
        assertEquals(listOf(chatRow), out)
    }

    @Test
    fun `with nothing on the phone there is no lead row and no hero`() {
        val rows = listOf(HomeRow.Continue(listOf(record(1))), chatRow)
        val out = OfflineHomeRows.apply(rows, home())
        assertEquals(2, out.size)
        assertTrue(out.filterIsInstance<HomeRow.Continue>().none { it.onPhone })
        assertNull(OfflineHomeRows.hero(out))
    }

    @Test
    fun `the hero is the first video on this phone`() {
        val out = OfflineHomeRows.apply(listOf(HomeRow.Continue(listOf(record(1), record(2)))), home(cached = setOf(key(2))))
        assertEquals(2L, OfflineHomeRows.hero(out)?.messageId)
    }

    @Test
    fun `a long on this phone row keeps ten and the rest as more`() {
        val downloads = (1L..14L).map { record(it, at = it) }
        val lead = OfflineHomeRows.apply(emptyList(), home(downloads = downloads)).single() as HomeRow.Continue
        assertEquals(HomeRows.LIMIT, lead.records.size)
        assertEquals(4, lead.more.size)
        assertEquals(14L, lead.records.first().messageId)
    }

    @Test
    fun `plays says yes to downloads and cached videos, and to everything online`() {
        val offline = home(downloads = listOf(record(1)), cached = setOf(key(2)))
        assertTrue(offline.plays(-1L, 1))
        assertTrue(offline.plays(-1L, 2))
        assertFalse(offline.plays(-1L, 3))
        assertTrue(home(offline = false).plays(-1L, 3))
        val remote = record(3).toMediaItem()
        assertTrue(offline.needsInternet(remote))
        assertFalse(offline.needsInternet(record(1).toMediaItem()))
        assertFalse(offline.needsInternet(remote.withLocality(MediaItem.Locality.Cached)))
    }

    @Test
    fun `only played and saved videos not already downloads are asked about in the cache, bounded`() {
        val many = (1L..80L).map { record(it) }
        val later = WatchLaterRecord(-1L, 500, 500, "L", "L", "Films", 1, 1, "video/mp4", 1)
        val rows = listOf(HomeRow.Continue(many), HomeRow.WatchLater(listOf(later)))
        val asked = OfflineHomeRows.cacheCandidates(rows, downloads = listOf(record(1)))
        assertEquals(OfflineHomeRows.CACHE_CHECKS, asked.size)
        assertTrue("a download needs no cache check", asked.none { it.first == key(1) })
        val few = OfflineHomeRows.cacheCandidates(listOf(HomeRow.Continue(listOf(record(1), record(2))), HomeRow.WatchLater(listOf(later))), emptyList())
        assertEquals(listOf(key(1), key(2), later.key), few.map { it.first })
    }
}
