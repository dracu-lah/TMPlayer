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
        assertTrue("openKeyboardKeys()" in perform)
    }

    @Test
    fun `a keyboard's Page keys are episodes and say when there is none, the chapter step stays a television's (PB-03)`() {
        val perform = activity.substringAfter("private fun performKeyboardAction(").substringBefore("/** M: the sound off")
        assertTrue("PlayerKeyMap.Kind.Episode -> if (once && !stepEpisode(action.flag)) {" in perform)
        assertTrue("L.playerNoNextEpisode else L.playerNoPreviousEpisode" in perform)
        // The remote's Page case, chapters or a minute, only answers on a television.
        val page = activity.substringAfter("KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN -> {").substringBefore("return true")
        assertTrue("PlayerRemoteKeys.pageStep(event.keyCode, FormFactor.isTv(this)) ?: return super.dispatchKeyEvent(event)" in page)
        assertFalse("the old comment saying a phone passes Page keys on", "A phone's keyboard passes them on as before" in activity)
    }

    @Test
    fun `M stays off across a new engine, and the gain is set from it (PB-06)`() {
        val exo = activity.substringAfter("        player = exo\n").substringBefore("crashNote.began(\"media3\")")
        assertFalse("a new ExoPlayer does not bring the sound back", "keyMuted = false" in exo)
        assertTrue("exo.volume = PlayerVolume.gain(playerVolumePercent, keyMuted)" in exo)
        assertTrue("vlc.volume = if (keyMuted) 0f else carry?.volume ?: PlayerVolume.gain(playerVolumePercent)" in activity)
        // Only a new video in this screen starts with the sound on again.
        val forget = activity.substringAfter("private fun forgetTheLastVideo() {").substringBefore("\n    }\n")
        assertTrue("keyMuted = false" in forget)
    }

    @Test
    fun `the keys page from the question mark holds the row like the More button does (PB-09)`() {
        val perform = activity.substringAfter("private fun performKeyboardAction(").substringBefore("/** M: the sound off")
        assertTrue("PlayerKeyMap.Kind.Sheet -> if (once) openKeyboardKeys()" in perform)
        val open = activity.substringAfter("private fun openKeyboardKeys() {").substringBefore("\n    }\n")
        assertTrue("menuOpen = true" in open)
        assertTrue("holdControls()" in open)
        assertTrue("moreAnchor = null" in open)
        assertTrue("morePanel?.openKeys(keyboard = true)" in open)
    }
}
