package com.tmplayer.data

import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What a chat's audio plays next: posted order, shuffle, repeat and a chat read a page at a time (U11). */
class QueuePlanTest {

    @After
    fun tearDown() = PlayQueue.reset()

    private fun audio(id: Long, chat: Long = 7) = MediaItem(
        chatId = chat, messageId = id, fileId = id.toInt(), title = "Lecture $id", sizeBytes = 1, durationSec = 60,
        mimeType = "audio/mpeg", thumbnailFileId = 0, miniThumbnail = null, date = 0,
    )

    private fun ids(plan: QueuePlan) = plan.order.map { it.messageId }

    @Test
    fun `files play oldest first however the pages came`() {
        val plan = QueuePlan.of(listOf(audio(30), audio(10), audio(20)))
        assertEquals(listOf(10L, 20L, 30L), ids(plan))
        val steps = plan.stepsFor(audio(20))!!
        assertEquals(10L, steps.previous?.messageId)
        assertEquals(30L, steps.next?.messageId)
        assertNull(plan.stepsFor(audio(30))!!.next)
        assertNull(plan.stepsFor(audio(10))!!.previous)
        assertNull(plan.stepsFor(audio(99)))
        assertNull(plan.stepsFor(audio(20, chat = 8)))
    }

    @Test
    fun `repeat all wraps at both ends, a single file does not loop on itself`() {
        val plan = QueuePlan.of(listOf(audio(1), audio(2), audio(3)), repeatAll = true)
        assertEquals(1L, plan.stepsFor(audio(3))!!.next?.messageId)
        assertEquals(3L, plan.stepsFor(audio(1))!!.previous?.messageId)
        val single = QueuePlan.of(listOf(audio(1)), repeatAll = true)
        assertNull(single.stepsFor(audio(1))!!.next)
        assertNull(single.stepsFor(audio(1))!!.previous)
        assertNull(plan.withRepeatAll(false).stepsFor(audio(3))!!.next)
    }

    @Test
    fun `a shuffle starts on the file chosen, plays every file once and is the same for one seed`() {
        val files = (1L..200L).map { audio(it) }
        val plan = QueuePlan.of(files, shuffle = true, seed = 5, current = audio(77))
        assertEquals(77L, plan.order.first().messageId)
        assertEquals(files.map { it.messageId }.toSet(), ids(plan).toSet())
        assertEquals(200, ids(plan).size)
        assertNotEquals(ids(plan), ids(QueuePlan.of(files)))
        assertEquals(ids(plan), ids(QueuePlan.of(files, shuffle = true, seed = 5, current = audio(77))))
        assertNull(plan.stepsFor(audio(77))!!.previous)
        // Walking Next from the first visits all of them, ending where the order ends.
        var at = plan.order.first()
        val seen = HashSet<Long>()
        while (true) {
            seen += at.messageId
            at = plan.stepsFor(at)!!.next ?: break
        }
        assertEquals(200, seen.size)
    }

    @Test
    fun `turning shuffle on keeps the file heard and off goes back to the posted order`() {
        val plan = QueuePlan.of((1L..50L).map { audio(it) })
        val mixed = plan.withShuffle(true, current = audio(20))
        assertEquals(20L, mixed.order.first().messageId)
        assertEquals((1L..50L).toList(), ids(mixed.withShuffle(false)))
        assertTrue(mixed.shuffle)
        assertEquals(mixed, mixed.withShuffle(true))
    }

    @Test
    fun `another page joins the posted order by date, and a shuffle after the file playing`() {
        val newer = QueuePlan.of(listOf(audio(40), audio(50), audio(60)))
        val withOlder = newer.merged(listOf(audio(10), audio(20), audio(50)))
        assertEquals(listOf(10L, 20L, 40L, 50L, 60L), ids(withOlder))
        // The same page again adds nothing, and is the same plan.
        assertEquals(withOlder, withOlder.merged(listOf(audio(10), audio(20))))

        val mixed = QueuePlan.of((1L..30L).map { audio(it) }, shuffle = true, seed = 3)
        val playing = mixed.order[4]
        val grown = mixed.merged((100L..110L).map { audio(it) }, current = playing)
        assertEquals(mixed.order.subList(0, 5).map { it.messageId }, ids(grown).subList(0, 5))
        assertEquals(41, grown.size)
        assertEquals(playing.messageId, grown.order[4].messageId)
        assertEquals((1L..30L).toSet() + (100L..110L).toSet(), ids(grown).toSet())
    }

    @Test
    fun `a chat of thousands is cheap to plan and to walk`() {
        val files = (1L..20_000L).map { audio(it) }
        val started = System.nanoTime()
        val plan = QueuePlan.of(files, shuffle = true, seed = 1)
        files.forEach { plan.stepsFor(it) }
        val grown = plan.merged((20_001L..20_050L).map { audio(it) }, current = plan.order[100])
        assertEquals(20_050, grown.size)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000)
    }

    @Test
    fun `an empty plan has no steps`() {
        assertNull(QueuePlan.Empty.stepsFor(audio(1)))
        assertEquals(0, QueuePlan.Empty.withShuffle(true).size)
    }

    @Test
    fun `the queue keeps shuffle and repeat for the next course`() {
        PlayQueue.setShuffle(true)
        PlayQueue.setRepeatAll(true)
        PlayQueue.set((1L..20L).map { audio(it) }, current = audio(9))
        assertEquals(9L, PlayQueue.items.first().messageId)
        PlayQueue.clear()
        assertTrue(PlayQueue.shuffle && PlayQueue.repeatAll)
        assertNull(PlayQueue.stepsFor(audio(9)))
        PlayQueue.reset()
        assertTrue(!PlayQueue.shuffle && !PlayQueue.repeatAll)
    }

    @Test
    fun `repeat all makes the last file go on to the first through the queue`() {
        PlayQueue.setRepeatAll(true)
        PlayQueue.set(listOf(audio(1), audio(2)))
        assertEquals(1L, PlayQueue.stepsFor(audio(2))!!.next?.messageId)
        PlayQueue.setRepeatAll(false)
        assertNull(PlayQueue.stepsFor(audio(2))!!.next)
    }

    private fun waitFor(what: String, check: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!check()) {
            check(System.currentTimeMillis() < until) { "Timed out waiting for $what" }
            Thread.sleep(10)
        }
    }

    @Test
    fun `near the oldest loaded file the next page is read in the background`() {
        val calls = AtomicInteger()
        val older = listOf(audio(1), audio(2), audio(3))
        PlayQueue.set((10L..30L).map { audio(it) }, more = QueueFeed {
            if (calls.incrementAndGet() == 1) older else null
        })
        // In the middle of the loaded files nothing is asked.
        PlayQueue.stepsFor(audio(20))
        Thread.sleep(100)
        assertEquals(0, calls.get())
        // Within five files of the start: asked once, and the older ones are joined in front.
        PlayQueue.stepsFor(audio(12))
        waitFor("the older page") { PlayQueue.items.size == 24 }
        assertEquals(1L, PlayQueue.items.first().messageId)
        assertEquals(3L, PlayQueue.stepsFor(audio(10))!!.previous?.messageId)
        // The chat had no more: the feed is let go, and no further start asks again.
        waitFor("the end of the feed") { PlayQueue.stepsFor(audio(2)); calls.get() == 2 }
        Thread.sleep(100)
        repeat(5) { PlayQueue.stepsFor(audio(2)); Thread.sleep(20) }
        assertEquals(2, calls.get())
    }

    @Test
    fun `a shuffle asks for more near the end of its order, and a feed that fails is survived`() {
        val calls = AtomicInteger()
        PlayQueue.setShuffle(true)
        PlayQueue.set((1L..30L).map { audio(it) }, current = audio(1), more = QueueFeed {
            when (calls.incrementAndGet()) {
                1 -> error("network gone")
                else -> listOf(audio(500))
            }
        })
        // At the start of a shuffle nothing is asked.
        PlayQueue.stepsFor(audio(1))
        Thread.sleep(100)
        assertEquals(0, calls.get())
        val last = PlayQueue.items.last()
        PlayQueue.stepsFor(last)
        waitFor("the failing call") { calls.get() == 1 }
        // The failure is dropped and the next start near the end asks again.
        Thread.sleep(100)
        waitFor("the second call") { PlayQueue.stepsFor(last); PlayQueue.items.size == 31 }
        assertTrue(PlayQueue.items.any { it.messageId == 500L })
    }

    @Test
    fun `repeat one replays the file in the plan, the last one too, and nothing outside it`() {
        val plan = QueuePlan.of(listOf(audio(1), audio(2), audio(3)))
        assertEquals(QueuePlan.AtEnd.Continue, plan.atEnd(audio(2)))
        val one = plan.withRepeatOne(true)
        assertEquals(QueuePlan.AtEnd.Replay, one.atEnd(audio(2)))
        assertEquals(QueuePlan.AtEnd.Replay, one.atEnd(audio(3)))
        assertEquals(QueuePlan.AtEnd.Continue, one.atEnd(audio(99)))
        assertEquals(QueuePlan.AtEnd.Continue, one.atEnd(audio(2, chat = 8)))
        assertEquals(QueuePlan.AtEnd.Continue, one.withRepeatOne(false).atEnd(audio(2)))
        assertEquals(QueuePlan.AtEnd.Continue, QueuePlan.Empty.withRepeatOne(true).atEnd(audio(1)))
    }

    @Test
    fun `the three repeat modes exclude one another, and survive a shuffle and another page`() {
        val plan = QueuePlan.of(listOf(audio(1), audio(2), audio(3))).withRepeatAll(true)
        val one = plan.withRepeatOne(true)
        assertTrue(one.repeatOne)
        assertTrue(!one.repeatAll)
        val all = one.withRepeatAll(true)
        assertTrue(all.repeatAll)
        assertTrue(!all.repeatOne)
        val kept = one.withShuffle(true, audio(2)).merged(listOf(audio(10)), audio(2))
        assertTrue(kept.repeatOne)
        assertEquals(QueuePlan.AtEnd.Replay, kept.atEnd(audio(10)))
        assertTrue(!QueuePlan.of(listOf(audio(1)), repeatAll = true, repeatOne = true).repeatOne)
    }

    @Test
    fun `the queue keeps repeat one for the session, drops it for repeat all, and never repeats a video`() {
        PlayQueue.setRepeatOne(true)
        PlayQueue.set(listOf(audio(1), audio(2)))
        assertTrue(PlayQueue.repeatOne)
        assertEquals(QueuePlan.AtEnd.Replay, PlayQueue.atEnd(audio(1)))
        assertEquals(QueuePlan.AtEnd.Continue, PlayQueue.atEnd(audio(1).copy(mimeType = "video/mp4")))
        assertEquals(QueuePlan.AtEnd.Continue, PlayQueue.atEnd(audio(77)))
        PlayQueue.setRepeatAll(true)
        assertTrue(!PlayQueue.repeatOne)
        assertEquals(QueuePlan.AtEnd.Continue, PlayQueue.atEnd(audio(1)))
        PlayQueue.setRepeatOne(true)
        assertTrue(!PlayQueue.repeatAll)
        PlayQueue.clear()
        assertTrue(PlayQueue.repeatOne)
        PlayQueue.reset()
        assertTrue(!PlayQueue.repeatOne)
    }
}
