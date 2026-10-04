package com.tmplayer.desktop.os

import com.tmplayer.desktop.os.MediaKeyEcho.Source.Keyboard
import com.tmplayer.desktop.os.MediaKeyEcho.Source.Session
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MediaKeyEchoTest {

    @Before
    fun forget() = MediaKeyEcho.reset()

    @Test
    fun `the second path's copy of one press is dropped, in either order`() {
        assertTrue(MediaKeyEcho.claim(Keyboard, nowMs = 10_000))
        assertFalse(MediaKeyEcho.claim(Session, nowMs = 10_050))
        assertTrue(MediaKeyEcho.claim(Session, nowMs = 20_000))
        assertFalse(MediaKeyEcho.claim(Keyboard, nowMs = 20_120))
    }

    @Test
    fun `presses from one path are never merged`() {
        assertTrue(MediaKeyEcho.claim(Session, nowMs = 30_000))
        assertTrue(MediaKeyEcho.claim(Session, nowMs = 30_100))
    }

    @Test
    fun `a press after the window is a new press`() {
        assertTrue(MediaKeyEcho.claim(Keyboard, nowMs = 40_000))
        assertTrue(MediaKeyEcho.claim(Session, nowMs = 40_000 + MediaKeyEcho.WINDOW_MS))
    }
}
