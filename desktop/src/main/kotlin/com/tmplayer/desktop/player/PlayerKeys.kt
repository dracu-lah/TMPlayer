package com.tmplayer.desktop.player

import androidx.compose.ui.input.key.Key
import com.tmplayer.i18n.L

/** Everything a key, a click or a menu entry can ask the player to do. */
sealed interface PlayerAction {
    data object TogglePlay : PlayerAction
    data object Play : PlayerAction
    data object Pause : PlayerAction
    data class SeekBy(val deltaMs: Long) : PlayerAction
    data class JumpToTenth(val tenth: Int) : PlayerAction
    data object JumpToEnd : PlayerAction
    data class FrameStep(val forward: Boolean) : PlayerAction
    data class VolumeBy(val delta: Int) : PlayerAction
    data object ToggleMute : PlayerAction
    data object ToggleFullscreen : PlayerAction
    data object ExitFullscreen : PlayerAction
    data object SubtitleNext : PlayerAction
    data object SubtitlePrevious : PlayerAction
    data object SubtitleToggle : PlayerAction
    data object AudioNext : PlayerAction
    data object AudioPrevious : PlayerAction
    /** Subtitles one [com.tmplayer.player.SyncDelays.STEP_MS] later ([direction] 1) or earlier (-1). */
    data class SubtitleDelay(val direction: Int) : PlayerAction

    /** Subtitles one size bigger (1) or smaller (-1), mpv's Shift+G and Shift+F. */
    data class SubtitleSize(val direction: Int) : PlayerAction

    /** The sound one step later or earlier, as [SubtitleDelay]. */
    data class AudioDelay(val direction: Int) : PlayerAction
    data object SpeedUp : PlayerAction
    data object SpeedDown : PlayerAction
    data object SpeedReset : PlayerAction
    data object NextEpisode : PlayerAction

    /**
     * Past the intro, recap or credits the playhead is in, as the "Skip intro" pill does; nothing
     * while no pill is up. See [com.tmplayer.online.SkipSegments].
     */
    data object SkipSegment : PlayerAction
    data object PreviousEpisode : PlayerAction
    data object AlwaysOnTop : PlayerAction
    data object MiniPlayer : PlayerAction
    data object Stats : PlayerAction
    /** The frame on screen to Pictures/TMPlayer, with the subtitles as drawn or without. */
    data class Screenshot(val withSubtitles: Boolean) : PlayerAction

    /** Marks the loop start, then its end, then clears it. See [AbLoop]. */
    data object AbRepeat : PlayerAction

    /** The next picture shape: fit, crop to fill, stretch, and round again. */
    data object CycleShape : PlayerAction

    /** The next chapter, or back to the start of this one (or the one before). See [Chapters]. */
    data class ChapterStep(val forward: Boolean) : PlayerAction

    /** Saves the moment playing as a bookmark, asking for an optional note. See [com.tmplayer.data.BookmarkStore]. */
    data object AddBookmark : PlayerAction

    /** Back to where the viewer was before a long jump, while the "Back to" chip offers it. See [com.tmplayer.player.SeekUndo]. */
    data object UndoSeek : PlayerAction

    /** Asks for a time to go to, typed. See [com.tmplayer.player.TimeEntry]. */
    data object GoToTime : PlayerAction
    data object Back : PlayerAction
    data object Quit : PlayerAction
    data object ShortcutSheet : PlayerAction
}

/** One key press with its modifiers, free of Compose's event type so the table can be tested. */
data class KeyPress(
    val key: Key,
    val shift: Boolean = false,
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val meta: Boolean = false,
)

/** What the table needs to know about the player to read an ambiguous key. */
data class KeyContext(
    val fullscreen: Boolean = false,
    val speedIsNormal: Boolean = true,
    val mac: Boolean = false,
    /**
     * The big-screen mode (plan 8.7): Esc and Backspace are a remote's Back, so they leave the
     * video, never only full screen (the whole app is) and never the speed.
     */
    val tv: Boolean = false,
)

/**
 * The player's keyboard, as B2.2 of the 2026-10-03 plan lays it out: the YouTube and Jellyfin
 * letters first, the mpv and VLC punctuation as silent aliases.
 *
 * Where an alias in the table collides with a primary key, the primary wins: 9 and 0 jump to 90 %
 * and the start (not volume), J seeks (not subtitles), I opens the stats (not the mini player),
 * Alt+Left/Right seek 10 s (not back), S takes a screenshot (not the next subtitles),
 * Ctrl+Left/Right step through chapters (not a minute) and B adds a bookmark (not VLC's next
 * audio track, which A and # still are). macOS swaps Ctrl for Cmd on the window
 * shortcuts and the chapter keys.
 */
object PlayerKeys {

    const val SEEK_SHORT_MS = 5_000L
    const val SEEK_MEDIUM_MS = 10_000L
    const val SEEK_LONG_MS = 60_000L
    const val SEEK_HUGE_MS = 300_000L
    const val VOLUME_STEP = 5

    fun actionFor(press: KeyPress, context: KeyContext = KeyContext()): PlayerAction? {
        val (key, shift, ctrl, alt, meta) = press
        // The "command" modifier: Cmd on a Mac, Ctrl elsewhere.
        val command = if (context.mac) meta else ctrl
        val plain = !shift && !ctrl && !alt && !meta

        // Window level shortcuts first, since they carry modifiers the rest would misread.
        if (command && key == Key.Q) return PlayerAction.Quit
        if (command && key == Key.T) return PlayerAction.AlwaysOnTop
        if (command && key == Key.P) return PlayerAction.MiniPlayer
        if (command && !shift && !alt && key == Key.G) return PlayerAction.GoToTime
        if (context.mac && ctrl && meta && key == Key.F) return PlayerAction.ToggleFullscreen
        if (alt && (key == Key.Enter || key == Key.NumPadEnter)) return PlayerAction.ToggleFullscreen

        when (key) {
            Key.DirectionLeft, Key.DirectionRight -> {
                // Chapters on the command key alone, as YouTube has them; a file without chapters
                // seeks a minute instead, which is what Ctrl did before chapters had a key.
                if (command && !shift && !alt) return PlayerAction.ChapterStep(forward = key == Key.DirectionRight)
                val sign = if (key == Key.DirectionRight) 1 else -1
                val huge = if (context.mac) meta && shift && alt else ctrl && alt
                val ms = when {
                    huge -> SEEK_HUGE_MS
                    shift || ctrl -> SEEK_LONG_MS
                    alt -> SEEK_MEDIUM_MS
                    else -> SEEK_SHORT_MS
                }
                return PlayerAction.SeekBy(sign * ms)
            }
            Key.DirectionUp -> return PlayerAction.VolumeBy(VOLUME_STEP)
            Key.DirectionDown -> return PlayerAction.VolumeBy(-VOLUME_STEP)
            Key.MediaPlayPause -> return PlayerAction.TogglePlay
            Key.MediaPlay -> return PlayerAction.Play
            Key.MediaPause -> return PlayerAction.Pause
            Key.MediaRewind -> return PlayerAction.SeekBy(-SEEK_MEDIUM_MS)
            Key.MediaFastForward -> return PlayerAction.SeekBy(SEEK_MEDIUM_MS)
            Key.MediaNext -> return PlayerAction.NextEpisode
            Key.MediaPrevious -> return PlayerAction.PreviousEpisode
            Key.PageDown -> return PlayerAction.NextEpisode
            Key.PageUp -> return PlayerAction.PreviousEpisode
            Key.F11 -> return PlayerAction.ToggleFullscreen
            Key.Escape -> return if (context.fullscreen && !context.tv) PlayerAction.ExitFullscreen else PlayerAction.Back
            // mpv's revert-seek. Shift alone: Backspace with nothing held keeps its two meanings.
            Key.Backspace -> return when {
                shift && !ctrl && !alt && !meta -> PlayerAction.UndoSeek
                context.speedIsNormal || context.tv -> PlayerAction.Back
                else -> PlayerAction.SpeedReset
            }
            Key.MoveHome -> return PlayerAction.JumpToTenth(0)
            Key.MoveEnd -> return PlayerAction.JumpToEnd
            Key.Back -> return PlayerAction.Back
            else -> Unit
        }
        // mpv's audio delay pair, with Ctrl on every system as in mpv. Ctrl+Shift+= is the same
        // physical key as Ctrl++, so either reads as later.
        if (ctrl && !alt && !meta) {
            when (key) {
                Key.Minus, Key.NumPadSubtract -> return PlayerAction.AudioDelay(-1)
                Key.Equals, Key.Plus, Key.NumPadAdd -> return PlayerAction.AudioDelay(1)
                else -> Unit
            }
        }
        if (ctrl || alt || meta) {
            // Cmd+[ is the Mac's back; nothing else below takes a modifier other than Shift.
            if (context.mac && meta && key == Key.LeftBracket) return PlayerAction.Back
            return null
        }

        digit(key)?.let { d ->
            // Shift+3 is '#' on most layouts, mpv's audio cycle.
            if (shift && d == 3) return PlayerAction.AudioNext
            return if (shift) null else PlayerAction.JumpToTenth(d)
        }

        return when (key) {
            Key.Spacebar, Key.K, Key.Enter, Key.NumPadEnter -> if (plain) PlayerAction.TogglePlay else null
            Key.P -> if (shift) PlayerAction.PreviousEpisode else PlayerAction.TogglePlay
            Key.N -> if (shift) PlayerAction.NextEpisode else null
            Key.J -> PlayerAction.SeekBy(-SEEK_MEDIUM_MS)
            Key.L -> PlayerAction.SeekBy(SEEK_MEDIUM_MS)
            Key.Comma -> if (shift) PlayerAction.SpeedDown else PlayerAction.FrameStep(forward = false)
            Key.Period -> if (shift) PlayerAction.SpeedUp else PlayerAction.FrameStep(forward = true)
            Key.M -> PlayerAction.ToggleMute
            // Shift+F and Shift+G: smaller and bigger subtitles, as in mpv; F alone is fullscreen.
            Key.F -> if (shift) PlayerAction.SubtitleSize(-1) else PlayerAction.ToggleFullscreen
            // mpv's screenshot keys: s with the subtitles, S the bare picture. The subtitle cycle
            // that S had before moved to V, mpv's own and already its alias.
            Key.S -> PlayerAction.Screenshot(withSubtitles = !shift)
            Key.V -> if (shift) PlayerAction.SubtitlePrevious else PlayerAction.SubtitleNext
            Key.R -> PlayerAction.AbRepeat
            // mpv's own panscan key, and free in both tables.
            Key.W -> if (shift) null else PlayerAction.CycleShape
            Key.C -> PlayerAction.SubtitleToggle
            Key.A -> if (shift) PlayerAction.AudioPrevious else PlayerAction.AudioNext
            Key.B -> if (shift) null else PlayerAction.AddBookmark
            Key.RightBracket -> PlayerAction.SpeedUp
            Key.LeftBracket -> PlayerAction.SpeedDown
            Key.Equals, Key.Plus, Key.NumPadAdd -> PlayerAction.SpeedUp
            Key.Minus, Key.NumPadSubtract -> PlayerAction.SpeedDown
            // mpv's subtitle delay keys: z earlier, Z and x later.
            Key.Z -> PlayerAction.SubtitleDelay(if (shift) 1 else -1)
            Key.X -> PlayerAction.SubtitleDelay(1)
            Key.T -> PlayerAction.AlwaysOnTop
            Key.I -> PlayerAction.Stats
            // "Go past": S, the obvious letter, has been mpv's screenshot key since before there
            // was anything to skip, and G was free in both tables.
            Key.G -> if (shift) PlayerAction.SubtitleSize(1) else PlayerAction.SkipSegment
            Key.Slash -> if (shift) PlayerAction.ShortcutSheet else null
            else -> null
        }
    }

    private fun digit(key: Key): Int? = when (key) {
        Key.Zero, Key.NumPad0 -> 0
        Key.One, Key.NumPad1 -> 1
        Key.Two, Key.NumPad2 -> 2
        Key.Three, Key.NumPad3 -> 3
        Key.Four, Key.NumPad4 -> 4
        Key.Five, Key.NumPad5 -> 5
        Key.Six, Key.NumPad6 -> 6
        Key.Seven, Key.NumPad7 -> 7
        Key.Eight, Key.NumPad8 -> 8
        Key.Nine, Key.NumPad9 -> 9
        else -> null
    }

    /**
     * The sheet behind '?', in the order the plan's table reads: the primary keys of [actionFor],
     * spelled for this OS ([mac] says Cmd where the table's command key is), and the wheel the way
     * the "Mouse wheel seeks" setting has it. PlayerKeysTest reads every key here back through
     * [actionFor], so the sheet cannot promise a key the player does not have.
     */
    fun sheet(mac: Boolean = false, wheelSeeks: Boolean = false): List<Pair<String, String>> =
        sheetGroups(mac, wheelSeeks).flatMap { it.second }

    /**
     * [sheet] in the groups the "?" sheet draws: what a viewer reaches for first, then sound and
     * subtitles, the window, and the keys that are rarely wanted. Every row of [sheet] is in
     * exactly one group.
     */
    fun sheetGroups(mac: Boolean = false, wheelSeeks: Boolean = false): List<Pair<String, List<Pair<String, String>>>> {
        val cmd = if (mac) L.keysCmd else L.keysCtrl
        return listOf(
            L.keysGroupPlayback to listOf(
                L.keysRowPlayPause to L.keysSpaceK,
                L.keysRowSeek5s to L.keysLeftRight,
                L.keysRowSeek10s to "J, L",
                L.keysRowSeek1min to L.keysShiftLeftRight,
                L.keysRowSpeed to L.keysSpeedKeys,
                L.keysRowEpisodes to L.keysEpisodesKeys,
                // The pill's own words name the row; it skips a recap or the credits the same way.
                L.playerSkipIntro to "G",
                L.keysRowJump to L.keysDigits,
                L.playerGoToTime to L.playerKeyCommandG(cmd),
                L.keysRowUndoSeek to L.keysShiftBackspace,
                L.keysRowStartEnd to L.keysHomeEnd,
            ),
            L.keysGroupAudioSubtitles to listOf(
                L.keysRowVolume to if (wheelSeeks) L.keysVolumeShiftWheel else L.keysVolumeWheel,
                L.keysRowMute to "M",
                L.keysRowSubtitles to L.keysSubtitlesKeys,
                L.keysRowAudio to L.keysAudioKeys,
                L.keysRowSubtitleDelay to "Z, X",
                L.keysRowSubtitleSize to L.keysSubtitleSizeKeys,
                L.keysRowAudioDelay to L.keysAudioDelayKeys,
            ),
            L.keysGroupWindow to listOf(
                L.keysRowFullscreen to L.keysFullscreenKeys,
                L.playerPictureShape to "W",
                L.keysRowAlwaysOnTop to "$cmd+T",
                L.keysRowMini to "$cmd+P",
                L.keysRowScreenshot to L.keysScreenshotKeys,
                L.keysRowBack to L.keysBackKeys,
                L.keysRowQuit to "$cmd+Q",
                L.keysRowThisSheet to "?",
            ),
            L.keysGroupOther to listOf(
                L.keysRowSeek5min to if (mac) L.keysSeek5minMac else L.keysSeek5min,
                L.keysRowChapter to L.keysChapter(cmd),
                L.keysRowFrameStep to L.keysCommaPeriod,
                L.keysRowAbRepeat to "R",
                L.keysRowBookmark to "B",
                L.keysRowWheelSeek to if (wheelSeeks) L.keysWheel else L.keysShiftWheel,
                L.keysRowDetails to "I",
            ),
        )
    }
}

/** A remote in the player (plan 8.7): the keys it sends that are not the keyboard's. */
object PlayerRemote {
    /** The keys that press the control the remote is on. */
    val OK = setOf(Key.Enter, Key.NumPadEnter, Key.Spacebar, Key.DirectionCenter)

    fun direction(key: Key): androidx.compose.ui.focus.FocusDirection? = when (key) {
        Key.DirectionUp -> androidx.compose.ui.focus.FocusDirection.Up
        Key.DirectionDown -> androidx.compose.ui.focus.FocusDirection.Down
        Key.DirectionLeft -> androidx.compose.ui.focus.FocusDirection.Left
        Key.DirectionRight -> androidx.compose.ui.focus.FocusDirection.Right
        else -> null
    }

    /**
     * What a key the JDK could not name does ([com.tmplayer.desktop.ui.remoteKeyOf]): Back and
     * Stop leave the video, the media keys play, pause, skip and seek as their names say.
     */
    fun actionFor(key: com.tmplayer.desktop.ui.RemoteKey?): PlayerAction? = when (key) {
        com.tmplayer.desktop.ui.RemoteKey.Back, com.tmplayer.desktop.ui.RemoteKey.Stop -> PlayerAction.Back
        com.tmplayer.desktop.ui.RemoteKey.PlayPause -> PlayerAction.TogglePlay
        com.tmplayer.desktop.ui.RemoteKey.Play -> PlayerAction.Play
        com.tmplayer.desktop.ui.RemoteKey.Pause -> PlayerAction.Pause
        com.tmplayer.desktop.ui.RemoteKey.Next -> PlayerAction.NextEpisode
        com.tmplayer.desktop.ui.RemoteKey.Previous -> PlayerAction.PreviousEpisode
        com.tmplayer.desktop.ui.RemoteKey.Rewind -> PlayerAction.SeekBy(-PlayerKeys.SEEK_MEDIUM_MS)
        com.tmplayer.desktop.ui.RemoteKey.FastForward -> PlayerAction.SeekBy(PlayerKeys.SEEK_MEDIUM_MS)
        com.tmplayer.desktop.ui.RemoteKey.Home, com.tmplayer.desktop.ui.RemoteKey.Menu, null -> null
    }
}
