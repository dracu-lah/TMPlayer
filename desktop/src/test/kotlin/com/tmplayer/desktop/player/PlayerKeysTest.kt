package com.tmplayer.desktop.player

import androidx.compose.ui.input.key.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerKeysTest {

    private fun action(key: Key, shift: Boolean = false, ctrl: Boolean = false, alt: Boolean = false, meta: Boolean = false, context: KeyContext = KeyContext()) =
        PlayerKeys.actionFor(KeyPress(key, shift, ctrl, alt, meta), context)

    @Test
    fun `play and pause keys`() {
        listOf(Key.Spacebar, Key.K, Key.P, Key.Enter, Key.MediaPlayPause).forEach {
            assertEquals("$it", PlayerAction.TogglePlay, action(it))
        }
    }

    @Test
    fun `seek strides follow the table`() {
        assertEquals(PlayerAction.SeekBy(-5_000), action(Key.DirectionLeft))
        assertEquals(PlayerAction.SeekBy(5_000), action(Key.DirectionRight))
        assertEquals(PlayerAction.SeekBy(-10_000), action(Key.J))
        assertEquals(PlayerAction.SeekBy(10_000), action(Key.L))
        assertEquals(PlayerAction.SeekBy(10_000), action(Key.DirectionRight, alt = true))
        assertEquals(PlayerAction.SeekBy(-60_000), action(Key.DirectionLeft, shift = true))
        assertEquals(PlayerAction.SeekBy(60_000), action(Key.DirectionRight, ctrl = true))
        assertEquals(PlayerAction.SeekBy(300_000), action(Key.DirectionRight, ctrl = true, alt = true))
        assertEquals(PlayerAction.SeekBy(-300_000), action(Key.DirectionLeft, shift = true, alt = true, meta = true, context = KeyContext(mac = true)))
        assertEquals(PlayerAction.SeekBy(-10_000), action(Key.MediaRewind))
        assertEquals(PlayerAction.SeekBy(10_000), action(Key.MediaFastForward))
    }

    @Test
    fun `digits jump to tenths and beat the volume aliases`() {
        assertEquals(PlayerAction.JumpToTenth(0), action(Key.Zero))
        assertEquals(PlayerAction.JumpToTenth(9), action(Key.Nine))
        assertEquals(PlayerAction.JumpToTenth(5), action(Key.NumPad5))
        assertEquals(PlayerAction.JumpToTenth(0), action(Key.MoveHome))
        assertEquals(PlayerAction.JumpToEnd, action(Key.MoveEnd))
    }

    @Test
    fun `volume and mute`() {
        assertEquals(PlayerAction.VolumeBy(5), action(Key.DirectionUp))
        assertEquals(PlayerAction.VolumeBy(-5), action(Key.DirectionDown))
        assertEquals(PlayerAction.ToggleMute, action(Key.M))
    }

    @Test
    fun `escape leaves fullscreen first, then the player`() {
        assertEquals(PlayerAction.ExitFullscreen, action(Key.Escape, context = KeyContext(fullscreen = true)))
        assertEquals(PlayerAction.Back, action(Key.Escape, context = KeyContext(fullscreen = false)))
    }

    @Test
    fun `backspace resets a changed speed and otherwise goes back`() {
        assertEquals(PlayerAction.Back, action(Key.Backspace, context = KeyContext(speedIsNormal = true)))
        assertEquals(PlayerAction.SpeedReset, action(Key.Backspace, context = KeyContext(speedIsNormal = false)))
    }

    @Test
    fun `fullscreen keys`() {
        assertEquals(PlayerAction.ToggleFullscreen, action(Key.F))
        assertEquals(PlayerAction.ToggleFullscreen, action(Key.F11))
        assertEquals(PlayerAction.ToggleFullscreen, action(Key.Enter, alt = true))
        assertEquals(PlayerAction.ToggleFullscreen, action(Key.F, ctrl = true, meta = true, context = KeyContext(mac = true)))
    }

    @Test
    fun `tracks and speed`() {
        assertEquals(PlayerAction.SubtitleNext, action(Key.S))
        assertEquals(PlayerAction.SubtitlePrevious, action(Key.S, shift = true))
        assertEquals(PlayerAction.SubtitleToggle, action(Key.C))
        assertEquals(PlayerAction.AudioNext, action(Key.A))
        assertEquals(PlayerAction.AudioPrevious, action(Key.A, shift = true))
        assertEquals(PlayerAction.AudioNext, action(Key.Three, shift = true))
        assertEquals(PlayerAction.SpeedUp, action(Key.RightBracket))
        assertEquals(PlayerAction.SpeedDown, action(Key.LeftBracket))
        assertEquals(PlayerAction.SpeedUp, action(Key.Period, shift = true))
        assertEquals(PlayerAction.FrameStep(forward = true), action(Key.Period))
        assertEquals(PlayerAction.FrameStep(forward = false), action(Key.Comma))
    }

    @Test
    fun `episodes, window and sheets`() {
        assertEquals(PlayerAction.NextEpisode, action(Key.N, shift = true))
        assertEquals(PlayerAction.PreviousEpisode, action(Key.P, shift = true))
        assertEquals(PlayerAction.NextEpisode, action(Key.PageDown))
        assertEquals(PlayerAction.AlwaysOnTop, action(Key.T, ctrl = true))
        assertEquals(PlayerAction.MiniPlayer, action(Key.P, ctrl = true))
        assertEquals(PlayerAction.MiniPlayer, action(Key.P, meta = true, context = KeyContext(mac = true)))
        assertEquals(PlayerAction.Stats, action(Key.I))
        assertEquals(PlayerAction.Stats, action(Key.I, shift = true))
        assertEquals(PlayerAction.Quit, action(Key.Q, ctrl = true))
        assertEquals(PlayerAction.ShortcutSheet, action(Key.Slash, shift = true))
    }

    @Test
    fun `unbound keys and stray modifiers do nothing`() {
        assertNull(action(Key.Z))
        assertNull(action(Key.K, ctrl = true))
        assertNull(action(Key.Q))
        assertNull(action(Key.Spacebar, shift = true))
    }
}
