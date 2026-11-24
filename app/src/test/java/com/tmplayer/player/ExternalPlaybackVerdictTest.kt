package com.tmplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

/** SEC2-01: iOS's AirPlay (any button, Control Center's too) follows the player's savable verdict. */
class ExternalPlaybackVerdictTest {

    @Test
    fun `a restricted chat and one not asked yet are told no, a chat that allows saving yes`() {
        val told = mutableListOf<Boolean>()
        val verdict = ExternalPlaybackVerdict { told += it }
        assertNull(verdict.savable)

        // A video carried in with canBeSaved = false (PlayerActivity: `savable = false`).
        verdict.savable = false
        assertEquals(listOf(false), told)

        // The next video, not asked yet, then Telegram says it may be saved.
        verdict.savable = null
        verdict.savable = true
        assertEquals(listOf(false, false, true), told)

        // A verdict that turns back to no is told too (the engine pauses a video on another screen).
        verdict.savable = false
        assertEquals(listOf(false, false, true, false), told)
    }

    @Test
    fun `the player activity's savable goes through the verdict`() {
        val source = java.io.File("src/main/java/com/tmplayer/player/PlayerActivity.kt").readText()
        assertTrue("PlayerActivity tells the engine each verdict", source.contains("ExternalPlaybackVerdict { setExternalPlaybackAllowed(it) }"))
        assertFalse("savable is not a plain field that skips the verdict", source.contains("private var savable: Boolean? = null"))
    }
}
