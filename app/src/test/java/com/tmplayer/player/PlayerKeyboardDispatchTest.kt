package com.tmplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * WP-28, source level (the 11,000 line activity cannot start under a unit test): the keyboard map
 * is read for a touch device only, never on a television, and a sheet, menu or panel keeps its keys.
 * The table itself is tested in core (`PlayerKeyMapTest`).
 */
class PlayerKeyboardDispatchTest {

    private fun source(path: String): String {
        val here = File(path)
        return (if (here.exists()) here else File("app/$path")).readText()
    }

    private val activity get() = source("src/main/java/com/tmplayer/player/PlayerActivity.kt")

    @Test
    fun `the map is read only off a television, and not under a sheet, a menu or a panel`() {
        val keyboard = activity.substringAfter("private fun keyboardKey(event: KeyEvent): Boolean {").substringBefore("\n    }\n")
        assertTrue("if (FormFactor.isTv(this)) return false" in keyboard)
        for (open in listOf("sheets?.isOpen == true", "menuOpen", "detailsOpen", "pickerOpen", "episodesOpen", "morePanel?.isOpen == true")) {
            assertTrue(open, open in keyboard)
        }
        // With the row up the focus keys stay the row's.
        assertTrue("controlsUp && !chord && PlayerKeyMap.walksFocus(code)" in keyboard)
        // The dispatcher reaches it for a touch device only.
        assertTrue("if (!FormFactor.isTv(this) && handleTouchDeviceKey(event)) return true" in activity)
    }

    @Test
    fun `the keys that did something before still do the same`() {
        val touch = activity.substringAfter("private fun handleTouchDeviceKey(event: KeyEvent): Boolean {").substringBefore("/** True while M has the sound off")
        assertTrue("KeyEvent.KEYCODE_DPAD_RIGHT -> jumpFromRemote(forward = true, repeatCount = event.repeatCount)" in touch)
        assertTrue("KeyEvent.KEYCODE_DPAD_LEFT -> jumpFromRemote(forward = false, repeatCount = event.repeatCount)" in touch)
        assertTrue("KeyEvent.KEYCODE_SPACE" in touch)
        assertTrue("if (event.repeatCount == 0) togglePlayback()" in touch)
        // A chord with no action in the map falls through to the old key.
        assertEquals(1, Regex("isMetaPressed\\) && keyboardKey\\(event\\)\\) return true").findAll(touch).count())
    }

    @Test
    fun `the player's own actions are the only ones performed, the rest stay the system's`() {
        val perform = activity.substringAfter("private fun performKeyboardAction(").substringBefore("/** M: the sound off")
        assertTrue("else -> return false" in perform)
        assertFalse("Volume", "Kind.Volume" in perform)
        assertTrue("morePanel?.openKeys(keyboard = true)" in perform)
    }
}
