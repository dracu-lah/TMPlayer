package com.tmplayer.desktop.ui

import androidx.compose.ui.geometry.Offset
import com.tmplayer.data.UiScale
import com.tmplayer.desktop.DesktopLayout
import com.tmplayer.desktop.player.PlayerAction
import com.tmplayer.desktop.player.PlayerRemote
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The big-screen mode's pure parts (plan 8.7): the flags, the scale, the remote's odd keys, the pointer. */
class BigScreenTest {

    @Test
    fun `the launch flags`() {
        assertEquals(LaunchOptions(), LaunchOptions.parse(emptyList()))
        assertEquals(LaunchOptions(DesktopLayout.Tv, fullscreen = true), LaunchOptions.parse(listOf("--tv")))
        assertEquals(LaunchOptions(DesktopLayout.Tv, fullscreen = true), LaunchOptions.parse(listOf("--layout=TV")))
        // --windowed wins whichever side of --tv it is on.
        assertEquals(LaunchOptions(DesktopLayout.Tv, fullscreen = false), LaunchOptions.parse(listOf("--tv", "--windowed")))
        assertEquals(LaunchOptions(DesktopLayout.Tv, fullscreen = false), LaunchOptions.parse(listOf("--windowed", "--tv")))
        assertEquals(LaunchOptions(DesktopLayout.Desktop, null), LaunchOptions.parse(listOf("--desktop")))
        assertEquals(LaunchOptions(null, fullscreen = true), LaunchOptions.parse(listOf("--fullscreen")))
        // A second launch's other arguments (a toast's link, --minimized) are not the layout's.
        assertEquals(LaunchOptions(), LaunchOptions.parse(listOf("--minimized", "tmplayer://open/1/2", "--self-test")))
    }

    @Test
    fun `the layout setting reads back whatever was stored`() {
        assertEquals(DesktopLayout.Tv, DesktopLayout.from("Tv"))
        assertEquals(DesktopLayout.Tv, DesktopLayout.from(" tv "))
        assertEquals(DesktopLayout.Desktop, DesktopLayout.from("Desktop"))
        assertEquals(DesktopLayout.Auto, DesktopLayout.from(null))
        assertEquals(DesktopLayout.Auto, DesktopLayout.from("Phone"))
    }

    @Test
    fun `big screen is the setting unless the command line says otherwise`() {
        try {
            BigScreen.layout = DesktopLayout.Auto
            BigScreen.override = null
            assertFalse("Auto is the desktop's on a computer", BigScreen.on)
            BigScreen.layout = DesktopLayout.Tv
            assertTrue(BigScreen.on)
            BigScreen.override = DesktopLayout.Desktop
            assertFalse(BigScreen.on)
            BigScreen.layout = DesktopLayout.Desktop
            BigScreen.override = DesktopLayout.Tv
            assertTrue(BigScreen.on)
        } finally {
            BigScreen.layout = DesktopLayout.Auto
            BigScreen.override = null
        }
    }

    @Test
    fun `the tv scale fills a television and never shrinks the desktop's layouts`() {
        // 1920 by 1080 at 100 per cent.
        assertEquals(1.6f, TvScale.factor(1920f, 1080f, UiScale.Default), 0.001f)
        assertEquals(1.45f, TvScale.factor(1920f, 1080f, UiScale.Smaller), 0.001f)
        // Larger and Largest stop where the window still holds 960 by 600.
        assertEquals(1.8f, TvScale.factor(1920f, 1080f, UiScale.Larger), 0.001f)
        assertEquals(1.8f, TvScale.factor(1920f, 1080f, UiScale.Largest), 0.001f)
        // A 4K television at 100 per cent, and the same at 200 (which reports 1920 by 1080).
        assertEquals(3.2f, TvScale.factor(3840f, 2160f, UiScale.Default), 0.001f)
        // 1280 by 720: the room is the desktop's least already.
        assertEquals(1.05f, TvScale.factor(1280f, 720f, UiScale.Default), 0.001f)
        // A window smaller than that is never drawn smaller than the desktop's 1.
        assertEquals(1f, TvScale.factor(800f, 500f, UiScale.Largest), 0.001f)
        // No window yet: the interface size alone.
        assertEquals(UiScale.Larger.factor, TvScale.factor(0f, 0f, UiScale.Larger), 0.001f)
    }

    @Test
    fun `a remote's keys the JDK has no name for, from their raw codes`() {
        val win = RemoteKeys.System.Windows
        val x11 = RemoteKeys.System.Linux
        assertEquals(RemoteKey.Back, RemoteKeys.classify(0, 0xA6, win))
        assertEquals(RemoteKey.PlayPause, RemoteKeys.classify(0, 0xB3, win))
        assertEquals(RemoteKey.Stop, RemoteKeys.classify(0, 0xB2, win))
        assertEquals(RemoteKey.Home, RemoteKeys.classify(0, 0xAC, win))
        assertEquals(RemoteKey.Back, RemoteKeys.classify(0, 166, x11))
        assertEquals(RemoteKey.PlayPause, RemoteKeys.classify(0, 172, x11))
        assertEquals(RemoteKey.FastForward, RemoteKeys.classify(0, 216, x11))
        // The same number is another key on the other system: 172 is Windows' browser Home.
        assertEquals(RemoteKey.Home, RemoteKeys.classify(0, 172, win))
        // A key the JDK names (Esc, Enter) is never read from its raw code.
        assertNull(RemoteKeys.classify(java.awt.event.KeyEvent.VK_ESCAPE, 0xA6, win))
        // Unknown codes, and a Mac, whose remotes reach the system's media controls instead.
        assertNull(RemoteKeys.classify(0, 0x12345, win))
        assertNull(RemoteKeys.classify(0, 166, RemoteKeys.System.Mac))
    }

    @Test
    fun `the raw code is read from what AWT says of the event`() {
        val event = java.awt.event.KeyEvent(
            javax.swing.JPanel(), java.awt.event.KeyEvent.KEY_PRESSED, 0L, 0,
            java.awt.event.KeyEvent.VK_UNDEFINED, java.awt.event.KeyEvent.CHAR_UNDEFINED,
        )
        assertEquals(0L, RemoteKeys.rawCodeIn(event.paramString()))
        assertEquals(166L, RemoteKeys.rawCodeIn("KEY_PRESSED,keyCode=0,keyText=Unknown,rawCode=166,primaryLevelUnicode=0,scancode=0"))
        assertNull(RemoteKeys.rawCodeIn("KEY_PRESSED,keyCode=0"))
    }

    @Test
    fun `the player reads the remote's media keys`() {
        assertEquals(PlayerAction.Back, PlayerRemote.actionFor(RemoteKey.Back))
        assertEquals(PlayerAction.Back, PlayerRemote.actionFor(RemoteKey.Stop))
        assertEquals(PlayerAction.TogglePlay, PlayerRemote.actionFor(RemoteKey.PlayPause))
        assertEquals(PlayerAction.NextEpisode, PlayerRemote.actionFor(RemoteKey.Next))
        assertEquals(PlayerAction.SeekBy(-10_000), PlayerRemote.actionFor(RemoteKey.Rewind))
        assertNull(PlayerRemote.actionFor(RemoteKey.Menu))
        assertNull(PlayerRemote.actionFor(null))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `the pointer hides after resting, at once for a key, and nothing ticks while it is hidden`() = runTest {
        val idle = CursorIdle(idleMs = 3_000L, now = { testScheduler.currentTime })
        val job = launch { idle.run() }
        runCurrent()
        assertFalse(idle.hidden)
        advanceTimeBy(2_999)
        assertFalse(idle.hidden)
        advanceTimeBy(2)
        assertTrue("hidden after three seconds at rest", idle.hidden)
        // Hidden, the loop waits on the next movement: running everything due ends at once,
        // with the clock where it was, rather than spinning.
        val before = testScheduler.currentTime
        advanceUntilIdle()
        assertEquals(before, testScheduler.currentTime)
        idle.moved(Offset(10f, 10f))
        runCurrent()
        assertFalse("a movement shows it", idle.hidden)
        advanceTimeBy(1_500)
        // Compose's "move" for a page scrolled under a still pointer is not the viewer.
        idle.moved(Offset(10f, 10f))
        advanceTimeBy(1_501)
        assertTrue(idle.hidden)
        idle.moved(Offset(20f, 10f))
        runCurrent()
        assertFalse(idle.hidden)
        idle.keyed()
        assertTrue("a key is the remote: the pointer goes now", idle.hidden)
        job.cancel()
    }
}
