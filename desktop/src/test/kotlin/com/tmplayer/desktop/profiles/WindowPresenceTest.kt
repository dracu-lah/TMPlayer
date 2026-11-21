package com.tmplayer.desktop.profiles

import com.tmplayer.data.ProfileUnlock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The 60 s re-lock on the desktop (HX88): the window's minimise, restore, hide and show events
 * ([WindowPresence.update], what its AWT listeners call) tell [ProfileUnlock] when the app leaves
 * the screen and comes back. A window minimised for more than a minute asks the PIN again; a short
 * minimise does not; a second window on screen keeps the app on screen.
 */
class WindowPresenceTest {
    private var now = 1_000_000L
    private val presence = WindowPresence(now = { now })
    private val window = Any()

    @After
    fun tearDown() {
        presence.update(window, true)
        ProfileUnlock.lock()
    }

    @Test
    fun `minimised for more than a minute, the PIN is asked again`() {
        presence.update(window, true)
        ProfileUnlock.unlock("p1")
        presence.update(window, false)
        now += ProfileUnlock.GRACE_MS + 1_000
        presence.update(window, true)
        assertEquals(null, ProfileUnlock.unlockedId.value)
    }

    @Test
    fun `a short minimise keeps the profile open`() {
        presence.update(window, true)
        ProfileUnlock.unlock("p1")
        presence.update(window, false)
        now += 30_000
        presence.update(window, true)
        assertEquals("p1", ProfileUnlock.unlockedId.value)
    }

    @Test
    fun `hidden counts as minimised, and one window still shown keeps the app on screen`() {
        val other = Any()
        presence.update(window, true)
        presence.update(other, true)
        ProfileUnlock.unlock("p1")
        presence.update(window, false)
        now += ProfileUnlock.GRACE_MS + 1_000
        presence.update(window, true)
        assertEquals("another window was on screen: not away", "p1", ProfileUnlock.unlockedId.value)
        presence.update(window, false)
        presence.update(other, false)
        now += ProfileUnlock.GRACE_MS + 1_000
        presence.update(other, true)
        assertEquals(null, ProfileUnlock.unlockedId.value)
    }

    @Test
    fun `the same event twice is one change`() {
        val heard = mutableListOf<String>()
        val counting = WindowPresence(now = { now }, background = { heard += "away" }, foreground = { heard += "back" })
        counting.update(window, true)
        counting.update(window, false)
        counting.update(window, false)
        counting.update(window, true)
        counting.update(window, true)
        assertEquals(listOf("away", "back"), heard)
    }

    @Test
    fun `the app hidden as a whole (macOS Cmd+H) counts as away while its window still says shown`() {
        presence.update(window, true)
        ProfileUnlock.unlock("p1")
        presence.appHidden(true)
        now += ProfileUnlock.GRACE_MS + 1_000
        presence.appHidden(false)
        assertEquals(null, ProfileUnlock.unlockedId.value)
    }

    @Test
    fun `a short Hide keeps the profile open`() {
        presence.update(window, true)
        ProfileUnlock.unlock("p1")
        presence.appHidden(true)
        now += 30_000
        presence.appHidden(false)
        assertEquals("p1", ProfileUnlock.unlockedId.value)
    }
}
