package com.tmplayer.data

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatVideoCountTest {

    @After
    fun clear() = ChatVideoCount.forget()

    /** Answers from a script, and counts what was asked. */
    private class Calls(private val answers: List<CountAnswer>) : ChatCountCalls {
        val asked = AtomicInteger()
        val local = AtomicInteger()
        override suspend fun videoCount(chatId: Long, localOnly: Boolean): CountAnswer {
            if (localOnly) local.incrementAndGet()
            val at = asked.getAndIncrement()
            return answers.getOrElse(at) { answers.last() }
        }
    }

    @Test
    fun `the local figure is taken when the database has one, with no call to the server`() = runTest {
        val calls = Calls(listOf(CountAnswer.Counted(42)))
        assertEquals(ChatCount.Known(42), ChatVideoCount.of(calls, 1, now = { currentTime }))
        assertEquals(1, calls.asked.get())
        assertEquals(1, calls.local.get())
    }

    @Test
    fun `minus one is the database saying it does not know, so the server is asked`() = runTest {
        val calls = Calls(listOf(CountAnswer.Counted(-1), CountAnswer.Counted(300)))
        assertEquals(ChatCount.Known(300), ChatVideoCount.of(calls, 1, now = { currentTime }))
        assertEquals(2, calls.asked.get())
    }

    @Test
    fun `a chat with no videos counts zero, which is an answer and not a failure`() = runTest {
        val calls = Calls(listOf(CountAnswer.Counted(0)))
        assertEquals(ChatCount.Known(0), ChatVideoCount.of(calls, 1, now = { currentTime }))
    }

    @Test
    fun `a server figure below zero is read as none rather than passed on`() = runTest {
        val calls = Calls(listOf(CountAnswer.Counted(-1), CountAnswer.Counted(-1)))
        assertEquals(ChatCount.Known(0), ChatVideoCount.of(calls, 1, now = { currentTime }))
    }

    @Test
    fun `a flood wait is waited out, by this call alone, and the count then arrives`() = runTest {
        val calls = Calls(listOf(CountAnswer.Counted(-1), CountAnswer.Failed("FLOOD_WAIT_20"), CountAnswer.Counted(7)))
        val started = currentTime
        assertEquals(ChatCount.Known(7), ChatVideoCount.of(calls, 1, now = { currentTime }))
        // Telegram asked for twenty seconds, and that is about what was waited: not none, and not a guess.
        assertTrue("waited ${currentTime - started} ms", currentTime - started >= 20_000)
        assertTrue("waited ${currentTime - started} ms", currentTime - started < 25_000)
    }

    @Test
    fun `a flood wait longer than the app will hold a row for is not waited out`() = runTest {
        val calls = Calls(listOf(CountAnswer.Counted(-1), CountAnswer.Failed("FLOOD_WAIT_600")))
        val started = currentTime
        assertEquals(ChatCount.Unknown, ChatVideoCount.of(calls, 1, now = { currentTime }))
        assertEquals(2, calls.asked.get())
        assertTrue(currentTime - started < 1_000)
    }

    @Test
    fun `a plain failure is not retried into the ground, and reads as unknown`() = runTest {
        val calls = Calls(listOf(CountAnswer.Counted(-1), CountAnswer.Failed("CHAT_ID_INVALID")))
        assertEquals(ChatCount.Unknown, ChatVideoCount.of(calls, 1, now = { currentTime }))
        assertEquals(2, calls.asked.get())
    }

    @Test
    fun `a call that never answers is given up on and tried again, three times at most`() = runTest {
        val calls = object : ChatCountCalls {
            val asked = AtomicInteger()
            override suspend fun videoCount(chatId: Long, localOnly: Boolean): CountAnswer {
                asked.incrementAndGet()
                if (localOnly) return CountAnswer.Counted(-1)
                delay(ChatVideoCount.TIMEOUT_MS * 2)
                return CountAnswer.Counted(1)
            }
        }
        assertEquals(ChatCount.Unknown, ChatVideoCount.of(calls, 1, now = { currentTime }))
        assertEquals(1 + ChatVideoCount.TRIES, calls.asked.get())
    }

    @Test
    fun `an answer is remembered for a while, a failure never is`() = runTest {
        val good = Calls(listOf(CountAnswer.Counted(5)))
        ChatVideoCount.of(good, 1, now = { 0 })
        ChatVideoCount.of(good, 1, now = { 1_000 })
        assertEquals("asked twice for one chat", 1, good.asked.get())
        // Past its life, it is asked again.
        ChatVideoCount.of(good, 1, now = { ChatVideoCount.TTL_MS + 1 })
        assertEquals(2, good.asked.get())

        val bad = Calls(listOf(CountAnswer.Counted(-1), CountAnswer.Failed("CHAT_ID_INVALID")))
        ChatVideoCount.of(bad, 2, now = { 0 })
        ChatVideoCount.of(bad, 2, now = { 1_000 })
        assertEquals("a chat that failed is asked again", 4, bad.asked.get())
    }

    @Test
    fun `no more than three chats are counted at once`() = runTest {
        val inFlight = AtomicInteger()
        val most = AtomicInteger()
        val calls = object : ChatCountCalls {
            override suspend fun videoCount(chatId: Long, localOnly: Boolean): CountAnswer {
                val now = inFlight.incrementAndGet()
                most.updateAndGet { if (now > it) now else it }
                delay(100)
                inFlight.decrementAndGet()
                return CountAnswer.Counted(chatId.toInt())
            }
        }
        val counts = (1L..20L).map { id ->
            async(start = CoroutineStart.UNDISPATCHED) { ChatVideoCount.of(calls, id, now = { currentTime }) }
        }
        counts.awaitAll()
        assertEquals(ChatVideoCount.AT_ONCE, most.get())
    }
}
