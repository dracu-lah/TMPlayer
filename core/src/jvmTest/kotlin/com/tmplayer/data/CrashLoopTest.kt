package com.tmplayer.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Two early crashes in a row open the next start on Chats, not Home (HX8). */
class CrashLoopTest {

    private var now = 1_000_000_000L
    private val file = kotlin.io.path.createTempFile("early", ".txt").toFile().also { it.delete(); it.deleteOnExit() }
    private val loop = CrashLoop(file) { now }

    @Test
    fun `one early crash is not a loop, two are`() {
        loop.note(sinceStartMs = 30_000)
        assertFalse(loop.looping())
        // The reporter's pattern: reopened a couple of minutes later, the same crash a minute in.
        now += 150_000
        loop.note(sinceStartMs = 60_000)
        assertTrue(loop.looping())
    }

    @Test
    fun `a late crash, a run that stayed up and an old loop do not count`() {
        loop.note(sinceStartMs = 30 * 60_000)
        loop.note(sinceStartMs = 40 * 60_000)
        assertFalse(loop.looping())
        loop.note(sinceStartMs = 1_000)
        loop.note(sinceStartMs = 1_000)
        assertTrue(loop.looping())
        now += CrashLoop.RECENT_MS + 1
        assertFalse(loop.looping())
        loop.clean()
        loop.note(sinceStartMs = 1_000)
        assertFalse(loop.looping())
    }
}
