package com.tmplayer.desktop.player

import androidx.compose.ui.input.key.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The "?" sheet against [PlayerKeys.actionFor]: every key the sheet names is read back through the
 * real table and must do what its row says, and every action a key can reach has a row.
 */
class ShortcutSheetTest {

    /** What each row promises, as a test on the action its keys produce. */
    private val promises: Map<String, (PlayerAction) -> Boolean> = mapOf(
        "Play or pause" to { it == PlayerAction.TogglePlay },
        "Seek 5 s" to { it is PlayerAction.SeekBy && kotlin.math.abs(it.deltaMs) == 5_000L },
        "Seek 10 s" to { it is PlayerAction.SeekBy && kotlin.math.abs(it.deltaMs) == 10_000L },
        "Seek 1 min" to { it is PlayerAction.SeekBy && kotlin.math.abs(it.deltaMs) == 60_000L },
        "Seek 5 min" to { it is PlayerAction.SeekBy && kotlin.math.abs(it.deltaMs) == 300_000L },
        "Frame step while paused" to { it is PlayerAction.FrameStep },
        "Jump to 0 to 90 %" to { it is PlayerAction.JumpToTenth },
        "Start, end" to { it == PlayerAction.JumpToTenth(0) || it == PlayerAction.JumpToEnd },
        "Volume" to { it is PlayerAction.VolumeBy },
        "Seek 10 s with the wheel" to { false },
        "Mute" to { it == PlayerAction.ToggleMute },
        "Fullscreen" to { it == PlayerAction.ToggleFullscreen },
        "Subtitles next, previous, on or off" to {
            it == PlayerAction.SubtitleNext || it == PlayerAction.SubtitlePrevious || it == PlayerAction.SubtitleToggle
        },
        "Audio next, previous" to { it == PlayerAction.AudioNext || it == PlayerAction.AudioPrevious },
        "Speed up, down, reset" to { it == PlayerAction.SpeedUp || it == PlayerAction.SpeedDown || it == PlayerAction.SpeedReset },
        "Next, previous episode" to { it == PlayerAction.NextEpisode || it == PlayerAction.PreviousEpisode },
        "Always on top" to { it == PlayerAction.AlwaysOnTop },
        "Mini player" to { it == PlayerAction.MiniPlayer },
        "Playback details" to { it == PlayerAction.Stats },
        "Back" to { it == PlayerAction.Back },
        "Quit" to { it == PlayerAction.Quit },
        "This sheet" to { it == PlayerAction.ShortcutSheet },
    )

    @Test
    fun `every key on the sheet does what its row says`() {
        for (mac in listOf(false, true)) {
            for (wheelSeeks in listOf(false, true)) {
                val sheet = PlayerKeys.sheet(mac, wheelSeeks)
                assertEquals(promises.keys, sheet.map { it.first }.toSet())
                for ((row, keys) in sheet) {
                    val promise = promises.getValue(row)
                    for (press in presses(keys)) {
                        // Backspace resets the speed only when the speed is not normal; the row says so.
                        val context = KeyContext(mac = mac, speedIsNormal = row != "Speed up, down, reset")
                        val action = PlayerKeys.actionFor(press, context)
                            ?: fail("$row: $press does nothing (mac=$mac)") as Nothing
                        assertTrue("$row: $press gave $action (mac=$mac)", promise(action))
                    }
                }
            }
        }
    }

    @Test
    fun `every action a key reaches has a row`() {
        val reached = PlayerKeys.sheet().flatMap { (row, keys) ->
            presses(keys).mapNotNull { PlayerKeys.actionFor(it, KeyContext(speedIsNormal = row != "Speed up, down, reset")) }
        }.map { it.javaClass }.toSet()
        // Play, Pause and ExitFullscreen come from media keys and Esc in fullscreen, which the
        // sheet covers under Play or pause and Back.
        val expected = PlayerAction::class.java.declaredClasses.toSet() -
            setOf(PlayerAction.Play::class.java, PlayerAction.Pause::class.java, PlayerAction.ExitFullscreen::class.java)
        assertEquals(expected - reached, emptySet<Any>())
    }

    /** The key presses a cell of the sheet spells out; mouse gestures are skipped. */
    private fun presses(keys: String): List<KeyPress> =
        if (keys == ", and .") listOf(KeyPress(Key.Comma), KeyPress(Key.Period)) else keys.split(", ", " and ").flatMap { token ->
            when {
                token == "0 to 9" -> (0..9).map { KeyPress(digit(it)) }
                token.contains("wheel") || token.contains("click") -> emptyList()
                else -> listOf(parse(token))
            }
        }

    private fun parse(token: String): KeyPress {
        if (token == "?") return KeyPress(Key.Slash, shift = true)
        // "+" alone would split into nothing; no row uses it.
        val parts = token.split('+')
        val name = parts.last()
        val mods = parts.dropLast(1).toSet()
        val key = when (name) {
            "Space" -> Key.Spacebar
            "Left" -> Key.DirectionLeft
            "Right" -> Key.DirectionRight
            "Up" -> Key.DirectionUp
            "Down" -> Key.DirectionDown
            "Esc" -> Key.Escape
            "Backspace" -> Key.Backspace
            "Home" -> Key.MoveHome
            "End" -> Key.MoveEnd
            "F11" -> Key.F11
            "," -> Key.Comma
            "." -> Key.Period
            "]" -> Key.RightBracket
            "[" -> Key.LeftBracket
            else -> letter(name)
        }
        return KeyPress(key, shift = "Shift" in mods, ctrl = "Ctrl" in mods, alt = "Alt" in mods, meta = "Cmd" in mods)
    }

    private fun letter(name: String): Key = LETTERS[name] ?: error("unknown key name '$name' on the sheet")

    private val LETTERS = mapOf(
        "A" to Key.A, "C" to Key.C, "F" to Key.F, "I" to Key.I, "J" to Key.J, "K" to Key.K, "L" to Key.L,
        "M" to Key.M, "N" to Key.N, "P" to Key.P, "Q" to Key.Q, "S" to Key.S, "T" to Key.T,
    )

    private fun digit(d: Int): Key = listOf(
        Key.Zero, Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine,
    )[d]
}
