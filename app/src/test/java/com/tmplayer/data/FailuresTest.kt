package com.tmplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class FailuresTest {

    @Test
    fun `a revoked session tells the viewer to sign in again`() {
        val text = Failures.humanise("Unauthorized: AUTH_KEY_UNREGISTERED")
        assertTrue(text, text.contains("sign in again", ignoreCase = true))
    }

    @Test
    fun `a rate limit says how long to wait, in units people use`() {
        assertTrue(Failures.humanise("FLOOD_WAIT_42").contains("42 seconds"))
        assertTrue(Failures.humanise("FLOOD_WAIT_600").contains("10 minutes"))
        assertTrue(Failures.humanise("Too Many Requests: retry after 7200").contains("2 hours"))
    }

    @Test
    fun `a missing chat is explained, not just coded`() {
        val text = Failures.humanise("400: CHAT_NOT_FOUND")
        assertTrue(text, text.contains("isn't there any more"))
    }

    @Test
    fun `a private channel is distinguished from a deleted one`() {
        assertTrue(Failures.humanise("CHANNEL_PRIVATE").contains("not in this channel"))
    }

    @Test
    fun `a stale file reference suggests the refresh that actually fixes it`() {
        val text = Failures.humanise("FILE_REFERENCE_EXPIRED")
        assertTrue(text, text.contains("Refresh"))
    }

    @Test
    fun `network trouble points at the TV's connection`() {
        assertEquals(Failures.OFFLINE, Failures.humanise("Connection reset by peer"))
        assertEquals(Failures.OFFLINE, Failures.humanise("Timeout waiting for response"))
    }

    @Test
    fun `an IOException with no message is treated as being offline`() {
        assertEquals(Failures.OFFLINE, Failures.humanise(IOException()))
    }

    @Test
    fun `an IOException keeps its own message when it has one`() {
        val text = Failures.humanise(IOException("Telegram stopped sending file 7 at byte 900"))
        assertTrue(text, text.contains("byte 900"))
    }

    @Test
    fun `an unrecognised failure never shows the viewer a protocol code`() {
        // The fallback replaces the raw string rather than appending to it.
        val text = Failures.humanise("500: SOMETHING_NEW_AND_ODD")
        assertEquals(Failures.DEFAULT, text)
        assertTrue(text, !text.contains("SOMETHING_NEW_AND_ODD"))
    }

    @Test
    fun `nothing at all still produces a sentence`() {
        assertTrue(Failures.humanise(null as String?).isNotBlank())
        assertTrue(Failures.humanise("   ").isNotBlank())
        assertTrue(Failures.humanise(null as Throwable?).isNotBlank())
    }
}
