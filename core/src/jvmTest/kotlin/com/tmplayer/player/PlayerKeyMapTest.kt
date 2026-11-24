package com.tmplayer.player

import com.tmplayer.player.PlayerKeyMap.Action
import com.tmplayer.player.PlayerKeyMap.Context
import com.tmplayer.player.PlayerKeyMap.Kind
import com.tmplayer.player.PlayerKeyMap.Press
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one keyboard table the web, the tablets and the Chromebooks read (WP-28). */
class PlayerKeyMapTest {

    private fun act(press: Press, context: Context = Context()) = PlayerKeyMap.actionFor(press, context)

    @Test
    fun `the seek ladder is 5 s, 10 s on J and L, a minute on Shift or Ctrl, 5 min on Ctrl+Alt`() {
        assertEquals(Action(Kind.Seek, 5), act(Press("ArrowRight")))
        assertEquals(Action(Kind.Seek, -5), act(Press("ArrowLeft")))
        assertEquals(Action(Kind.Seek, 10), act(Press("ArrowRight", alt = true)))
        assertEquals(Action(Kind.Seek, -10), act(Press("KeyJ")))
        assertEquals(Action(Kind.Seek, 10), act(Press("KeyL", shift = true)))
        assertEquals(Action(Kind.Seek, 60), act(Press("ArrowRight", shift = true)))
        assertEquals(Action(Kind.Seek, -300), act(Press("ArrowLeft", ctrl = true, alt = true)))
        assertEquals(Action(Kind.Seek, -300), act(Press("ArrowLeft", meta = true, shift = true, alt = true), Context(mac = true)))
    }

    @Test
    fun `the command key with an arrow is a chapter where the file has them, else a minute`() {
        assertEquals(Action(Kind.Chapter, flag = true), act(Press("ArrowRight", ctrl = true), Context(chapters = true)))
        assertEquals(Action(Kind.Chapter, flag = false), act(Press("ArrowLeft", meta = true), Context(mac = true, chapters = true)))
        assertEquals(Action(Kind.Seek, -60), act(Press("ArrowLeft", ctrl = true)))
        // Ctrl on a Mac is not its command key.
        assertEquals(Action(Kind.Seek, 60), act(Press("ArrowRight", ctrl = true), Context(mac = true, chapters = true)))
    }

    @Test
    fun `the letters are read by physical key, not by character`() {
        assertEquals(Action(Kind.Mute), act(Press("KeyM", "m")))
        assertEquals(Action(Kind.Mute), act(Press("KeyM", "M", shift = true)))
        // A Dvorak or AZERTY layout types something else on the same key: the code decides.
        assertEquals(Action(Kind.Seek, 10), act(Press("KeyL", "n")))
        assertEquals(Action(Kind.Speed, flag = true), act(Press("BracketRight", "+")))
        assertEquals(Action(Kind.Speed, flag = false), act(Press("BracketLeft", "[")))
        assertEquals(Action(Kind.SubtitlesToggle), act(Press("KeyC")))
        assertEquals(Action(Kind.Screenshot, flag = true), act(Press("KeyS")))
        assertEquals(Action(Kind.Screenshot, flag = false), act(Press("KeyS", shift = true)))
    }

    @Test
    fun `digits jump to a tenth, Home is the start, and the question mark opens the sheet`() {
        for (d in 0..9) {
            assertEquals(Action(Kind.Jump, d), act(Press("Digit$d", "$d")))
            assertEquals(Action(Kind.Jump, d), act(Press("Numpad$d", "$d")))
        }
        // A French keyboard types a digit with Shift: still that tenth.
        assertEquals(Action(Kind.Jump, 4), act(Press("Digit4", "4", shift = true)))
        // A US Shift+1 types "!": not a digit.
        assertNull(act(Press("Digit1", "!", shift = true)))
        assertEquals(Action(Kind.Audio, flag = false), act(Press("Digit3", "#", shift = true)))
        assertEquals(Action(Kind.Sheet), act(Press("Slash", "?", shift = true)))
        assertNull(act(Press("Slash", "/")))
        assertEquals(Action(Kind.Jump, 0), act(Press("Home")))
        assertEquals(Action(Kind.End), act(Press("End")))
    }

    @Test
    fun `play, episodes, frames and speed reset read the player's state`() {
        assertEquals(Action(Kind.TogglePlay), act(Press("Space")))
        assertEquals(Action(Kind.TogglePlay), act(Press("KeyK")))
        assertNull(act(Press("Space", shift = true)))
        assertEquals(Action(Kind.Episode, flag = true), act(Press("KeyN", shift = true)))
        assertEquals(Action(Kind.Episode, flag = false), act(Press("KeyP", shift = true)))
        assertNull(act(Press("KeyN")))
        assertNull(act(Press("Comma")))
        assertEquals(Action(Kind.Frame, flag = false), act(Press("Comma"), Context(paused = true)))
        assertEquals(Action(Kind.Speed, flag = true), act(Press("Period", shift = true)))
        assertEquals(Action(Kind.Back), act(Press("Backspace")))
        assertEquals(Action(Kind.SpeedReset), act(Press("Backspace"), Context(speedNormal = false)))
        assertEquals(Action(Kind.UndoSeek), act(Press("Backspace", shift = true)))
        assertNull(act(Press("KeyG")))
        assertEquals(Action(Kind.Skip), act(Press("KeyG"), Context(skipShown = true)))
        assertEquals(Action(Kind.ExitFullscreen), act(Press("Escape"), Context(fullscreen = true)))
        assertEquals(Action(Kind.Back), act(Press("Escape")))
    }

    @Test
    fun `other chords belong to the page and the system`() {
        for (code in listOf("KeyC", "KeyV", "KeyX", "KeyA", "KeyF", "KeyZ", "KeyR", "KeyW", "KeyL")) {
            assertNull(code, act(Press(code, ctrl = true)))
            assertNull(code, act(Press(code, meta = true), Context(mac = true)))
        }
        assertEquals(Action(Kind.Pip), act(Press("KeyP", ctrl = true)))
        assertEquals(Action(Kind.GoToTime), act(Press("KeyG", ctrl = true)))
        assertEquals(Action(Kind.Zoom, 1), act(Press("Equal", alt = true)))
        assertEquals(Action(Kind.ZoomReset), act(Press("Digit0", alt = true)))
        assertNull(act(Press("Unidentified")))
    }

    @Test
    fun `an Android key code maps to its physical code and the media and TV keys have none`() {
        assertEquals("KeyJ", PlayerKeyMap.codeOfAndroid(38))
        assertEquals("KeyA", PlayerKeyMap.codeOfAndroid(29))
        assertEquals("KeyZ", PlayerKeyMap.codeOfAndroid(54))
        assertEquals("Digit0", PlayerKeyMap.codeOfAndroid(7))
        assertEquals("Digit9", PlayerKeyMap.codeOfAndroid(16))
        assertEquals("Numpad5", PlayerKeyMap.codeOfAndroid(149))
        assertEquals("BracketLeft", PlayerKeyMap.codeOfAndroid(71))
        assertEquals("BracketRight", PlayerKeyMap.codeOfAndroid(72))
        assertEquals("Space", PlayerKeyMap.codeOfAndroid(62))
        // Play/Pause (85), Next (87), Volume up (24), Back (4), Menu (82), DPAD centre (23), Info (165).
        for (tv in listOf(85, 87, 88, 89, 90, 24, 25, 4, 82, 23, 165, 166, 175, 222)) assertNull(tv.toString(), PlayerKeyMap.codeOfAndroid(tv))
    }

    @Test
    fun `the keys that walk the focus are the row's while it is up, with no modifier`() {
        for (code in listOf("ArrowLeft", "ArrowRight", "ArrowUp", "ArrowDown", "Space", "Enter", "Tab", "Escape")) {
            assertTrue(code, PlayerKeyMap.walksFocus(code))
        }
        for (code in listOf("KeyJ", "KeyM", "BracketRight", "Digit5", "Slash")) assertTrue(code, !PlayerKeyMap.walksFocus(code))
    }

    @Test
    fun `every press the keyboard sheet names is read back through the table`() {
        val rows = PlayerKeyMap.androidSheet()
        assertTrue(rows.size >= 8)
        for (row in rows) {
            assertTrue(row.what, row.what.isNotBlank() && row.keys.isNotBlank())
            for (press in row.presses) assertTrue("${row.what}: $press", act(press, Context(chapters = true, speedNormal = press.code != "Backspace")) != null)
        }
    }

    @Test
    fun `the shell keys are search and Settings, and nothing for the player's own letters`() {
        assertEquals(PlayerKeyMap.ShellKey.Search, PlayerKeyMap.shellKeyFor(Press("KeyF", ctrl = true)))
        assertEquals(PlayerKeyMap.ShellKey.Search, PlayerKeyMap.shellKeyFor(Press("Slash", "/")))
        assertEquals(PlayerKeyMap.ShellKey.Settings, PlayerKeyMap.shellKeyFor(Press("Comma", ",", ctrl = true)))
        assertEquals(PlayerKeyMap.ShellKey.Search, PlayerKeyMap.shellKeyFor(Press("KeyF", meta = true), mac = true))
        // WP-40: F5 and Ctrl+R (Command+R on a Mac) refresh the page; a bare R or Ctrl+Shift+R is not that.
        assertEquals(PlayerKeyMap.ShellKey.Refresh, PlayerKeyMap.shellKeyFor(Press("F5")))
        assertEquals(PlayerKeyMap.ShellKey.Refresh, PlayerKeyMap.shellKeyFor(Press("KeyR", "r", ctrl = true)))
        assertEquals(PlayerKeyMap.ShellKey.Refresh, PlayerKeyMap.shellKeyFor(Press("KeyR", meta = true), mac = true))
        assertNull(PlayerKeyMap.shellKeyFor(Press("KeyR", "r")))
        assertNull(PlayerKeyMap.shellKeyFor(Press("KeyR", "R", ctrl = true, shift = true)))
        assertNull(PlayerKeyMap.shellKeyFor(Press("F5", ctrl = true)))
        assertNull(PlayerKeyMap.shellKeyFor(Press("KeyF")))
        assertNull(PlayerKeyMap.shellKeyFor(Press("Comma")))
        assertNull(PlayerKeyMap.shellKeyFor(Press("Slash", "?", shift = true)))
    }

    @Test
    fun `Page Up and Page Down are the previous and next episode on a keyboard, as on the desktop and the web (PB-03)`() {
        assertEquals(PlayerKeyMap.Action(PlayerKeyMap.Kind.Episode, flag = true), PlayerKeyMap.actionFor(PlayerKeyMap.Press("PageDown")))
        assertEquals(PlayerKeyMap.Action(PlayerKeyMap.Kind.Episode, flag = false), PlayerKeyMap.actionFor(PlayerKeyMap.Press("PageUp")))
        assertEquals("PageUp", PlayerKeyMap.codeOfAndroid(92))
        assertEquals("PageDown", PlayerKeyMap.codeOfAndroid(93))
    }
}
