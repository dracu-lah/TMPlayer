package com.tmplayer.player

import com.tmplayer.i18n.L

/**
 * What a key means in the player on a computer keyboard: one table, read by the web player (through
 * `:core:webRules`) and by Android tablets, Chromebooks and a phone with a Bluetooth keyboard (WP-28).
 * The desktop app keeps its own Compose table (`PlayerKeys.actionFor`); this one is the part a
 * browser and a tablet can do, taken from the web's `actionFor`.
 *
 * A key is named by its physical code, the vocabulary of the web's `KeyboardEvent.code` ("KeyJ",
 * "ArrowLeft", "Digit1", "BracketRight"), so a locale keyboard changes nothing: the letters and
 * brackets are where the US layout has them. The character matters only for "?" and a typed digit
 * ([Press.key]). Android's key codes come through [codeOfAndroid].
 *
 * Pure: no Android, no JVM only API, no DOM. The screen decides what it can do with an [Action]; a
 * key whose action a screen lacks is left alone there. A television never reads this table: its
 * remote has its own rules (docs/agents/tv-keys.md).
 *
 * Page Up and Page Down are the previous and next episode here, as the desktop's own table has had
 * them, and the web's: one meaning for every keyboard. On a television the remote's Page keys step
 * a chapter instead (`PlayerRemoteKeys.pageStep`, WP-18), a meaning only a TV has (PB-03). A bare
 * Left or Right on an Android keyboard over the bare picture keeps that player's jump (5 s back,
 * 10 s forward); with a modifier it is this table's.
 */
object PlayerKeyMap {

    const val SEEK_SHORT_S = 5
    const val SEEK_MEDIUM_S = 10
    const val SEEK_LONG_S = 60
    const val SEEK_HUGE_S = 300
    const val VOLUME_STEP_PERCENT = 5

    /** What a key asks for. [id] is the name the web reads. */
    enum class Kind(val id: String) {
        TogglePlay("togglePlay"),

        /** [Action.value] seconds, negative for back. */
        Seek("seek"),

        /** [Action.flag] is forward. */
        Chapter("chapter"),

        /** [Action.value] percent points, negative for down. */
        Volume("volume"),
        Mute("mute"),

        /** [Action.flag] is next. */
        Episode("episode"),

        /** [Action.flag] is faster. */
        Speed("speed"),
        SpeedReset("speedReset"),

        /** While paused; [Action.flag] is forward. */
        Frame("frame"),

        /** The next or, with [Action.flag], the previous track. */
        Subtitles("subtitles"),
        SubtitlesToggle("subtitlesToggle"),
        Audio("audio"),

        /** [Action.value] tenths of a second, negative for earlier. */
        SubtitleDelay("subtitleDelay"),

        /** [Action.value] 1 bigger, -1 smaller. */
        SubtitleSize("subtitleSize"),
        Fullscreen("fullscreen"),
        ExitFullscreen("exitFullscreen"),
        Shape("shape"),
        Rotate("rotate"),

        /** [Action.value] 1 in, -1 out. */
        Zoom("zoom"),
        ZoomReset("zoomReset"),

        /** [Action.flag] with the subtitles. */
        Screenshot("screenshot"),
        Repeat("repeat"),
        Bookmark("bookmark"),
        Skip("skip"),
        GoToTime("goToTime"),
        UndoSeek("undoSeek"),
        Pip("pip"),
        Details("details"),
        Sheet("sheet"),

        /** [Action.value] the tenth, 0 to 9. */
        Jump("jump"),
        End("end"),
        Back("back"),
    }

    /** One thing a key asks for; [value] and [flag] as [Kind] says. */
    data class Action(val kind: Kind, val value: Int = 0, val flag: Boolean = false)

    /** One key press: [code] is the physical key, [key] the character it typed (only "?" and digits read it). */
    data class Press(
        val code: String,
        val key: String = "",
        val shift: Boolean = false,
        val ctrl: Boolean = false,
        val alt: Boolean = false,
        val meta: Boolean = false,
    )

    /** What the table needs to know about the player to read an ambiguous key. */
    data class Context(
        val mac: Boolean = false,
        /** The file has chapters: the command key with an arrow steps through them, else it is a minute. */
        val chapters: Boolean = false,
        val paused: Boolean = false,
        val speedNormal: Boolean = true,
        val fullscreen: Boolean = false,
        /** A skip offer (intro, recap, credits) is up: G takes it. */
        val skipShown: Boolean = false,
    )

    /** What a key does in the player, or null for a key it leaves to the page, the system and the screen. */
    fun actionFor(press: Press, context: Context = Context()): Action? {
        val code = press.code
        val shift = press.shift
        val ctrl = press.ctrl
        val alt = press.alt
        val meta = press.meta
        val command = if (context.mac) meta else ctrl
        val plain = !shift && !ctrl && !alt && !meta

        // Window level keys first, since they carry modifiers the rest would misread.
        if (command && !alt && code == "KeyP") return Action(Kind.Pip)
        if (command && !shift && !alt && code == "KeyG") return Action(Kind.GoToTime)
        if (alt && (code == "Enter" || code == "NumpadEnter")) return Action(Kind.Fullscreen)
        // The zoom: mpv's Alt+= and Alt+- (Option on a Mac), Alt+0 and Alt+Backspace back to 100 per cent.
        if (alt && !ctrl && !meta) {
            if (code == "Equal" || code == "NumpadAdd") return Action(Kind.Zoom, 1)
            if ((code == "Minus" || code == "NumpadSubtract") && !shift) return Action(Kind.Zoom, -1)
            if ((code == "Digit0" || code == "Numpad0" || code == "Backspace") && !shift) return Action(Kind.ZoomReset)
        }

        when (code) {
            "ArrowLeft", "ArrowRight" -> {
                val forward = code == "ArrowRight"
                // Chapters on the command key alone, as YouTube has them; a file without chapters seeks a minute.
                if (command && !shift && !alt) {
                    return if (context.chapters) Action(Kind.Chapter, flag = forward) else seek(forward, SEEK_LONG_S)
                }
                val huge = if (context.mac) meta && shift && alt else ctrl && alt
                val seconds = when {
                    huge -> SEEK_HUGE_S
                    shift || ctrl -> SEEK_LONG_S
                    alt -> SEEK_MEDIUM_S
                    else -> SEEK_SHORT_S
                }
                return seek(forward, seconds)
            }
            "ArrowUp" -> return Action(Kind.Volume, VOLUME_STEP_PERCENT)
            "ArrowDown" -> return Action(Kind.Volume, -VOLUME_STEP_PERCENT)
            "MediaPlayPause" -> return Action(Kind.TogglePlay)
            "MediaTrackNext", "PageDown" -> return Action(Kind.Episode, flag = true)
            "MediaTrackPrevious", "PageUp" -> return Action(Kind.Episode, flag = false)
            "Escape" -> return if (context.fullscreen) Action(Kind.ExitFullscreen) else Action(Kind.Back)
            // mpv's revert seek on Shift; Backspace alone puts a changed speed back, or leaves.
            "Backspace" -> {
                if (shift && !ctrl && !alt && !meta) return Action(Kind.UndoSeek)
                if (!plain) return null
                return if (context.speedNormal) Action(Kind.Back) else Action(Kind.SpeedReset)
            }
            "Home" -> return if (plain) Action(Kind.Jump, 0) else null
            "End" -> return if (plain) Action(Kind.End) else null
        }
        // Every other chord with Ctrl, Alt or Cmd is the browser's or the system's (Ctrl+C, V, X, Z,
        // A, F, Ctrl+- and Ctrl+= among them), never a player key.
        if (ctrl || alt || meta) return null

        // A digit typed is a tenth of the way in, Shift or not (a French keyboard needs Shift for
        // one); the digit row unshifted is one too, whatever it types (& on that keyboard).
        val typed = press.key
        if (typed.length == 1 && typed[0] in '0'..'9') return Action(Kind.Jump, typed[0] - '0')
        // Shift+3 is '#' on most layouts (the pound sign on a British one), mpv's audio cycle.
        if (typed == "#" || (shift && code == "Digit3")) return Action(Kind.Audio, flag = false)
        digitOf(code)?.let { if (!shift) return Action(Kind.Jump, it) }

        if (typed == "?") return Action(Kind.Sheet)
        when (code) {
            "Space", "Enter", "NumpadEnter" -> return if (plain) Action(Kind.TogglePlay) else null
            "BracketRight", "Equal", "NumpadAdd" -> return Action(Kind.Speed, flag = true)
            "BracketLeft", "Minus", "NumpadSubtract" -> return Action(Kind.Speed, flag = false)
            "Period" -> return if (shift) Action(Kind.Speed, flag = true) else if (context.paused) Action(Kind.Frame, flag = true) else null
            "Comma" -> return if (shift) Action(Kind.Speed, flag = false) else if (context.paused) Action(Kind.Frame, flag = false) else null
            "KeyK" -> return if (plain) Action(Kind.TogglePlay) else null
            "KeyP" -> return if (shift) Action(Kind.Episode, flag = false) else Action(Kind.TogglePlay)
            "KeyN" -> return if (shift) Action(Kind.Episode, flag = true) else null
            "KeyJ" -> return seek(false, SEEK_MEDIUM_S)
            "KeyL" -> return seek(true, SEEK_MEDIUM_S)
            "KeyM" -> return Action(Kind.Mute)
            // Shift+F and Shift+G: smaller and bigger subtitles, as in mpv; F alone is full screen.
            "KeyF" -> return if (shift) Action(Kind.SubtitleSize, -1) else Action(Kind.Fullscreen)
            "KeyG" -> return if (shift) Action(Kind.SubtitleSize, 1) else if (context.skipShown) Action(Kind.Skip) else null
            // mpv's screenshot keys: s with the subtitles, S the bare picture.
            "KeyS" -> return Action(Kind.Screenshot, flag = !shift)
            "KeyV" -> return Action(Kind.Subtitles, flag = shift)
            "KeyC" -> return Action(Kind.SubtitlesToggle)
            "KeyA" -> return Action(Kind.Audio, flag = shift)
            // R is A-B repeat; Shift+R turns the picture a quarter.
            "KeyR" -> return if (shift) Action(Kind.Rotate) else Action(Kind.Repeat)
            "KeyB" -> return if (shift) null else Action(Kind.Bookmark)
            "KeyW" -> return if (shift) null else Action(Kind.Shape)
            // mpv's subtitle delay keys: z earlier, Z and x later.
            "KeyZ" -> return Action(Kind.SubtitleDelay, if (shift) 1 else -1)
            "KeyX" -> return Action(Kind.SubtitleDelay, 1)
            "KeyI" -> return Action(Kind.Details)
        }
        return null
    }

    private fun seek(forward: Boolean, seconds: Int) = Action(Kind.Seek, if (forward) seconds else -seconds)

    private fun digitOf(code: String): Int? {
        val tail = when {
            code.startsWith("Digit") -> code.substring(5)
            code.startsWith("Numpad") -> code.substring(6)
            else -> return null
        }
        return if (tail.length == 1 && tail[0] in '0'..'9') tail[0] - '0' else null
    }

    /**
     * Keys that, with the transport row up, walk the focus or press the focused button: a keyboard
     * reaches the row with them, so the map never takes them there. With a modifier held they are
     * the map's again (Ctrl+Left is a chapter, Shift+Left a minute).
     */
    fun walksFocus(code: String): Boolean = when (code) {
        "ArrowLeft", "ArrowRight", "ArrowUp", "ArrowDown", "Space", "Enter", "NumpadEnter", "Tab", "Escape", "Backspace" -> true
        else -> false
    }

    /**
     * Android's key code as the table's physical code, or null for a key the table has none for
     * (the media keys, the volume keys and the TV remote's keys among them stay with their own
     * handlers). The values are Android's `KeyEvent.KEYCODE_*`, which are part of its ABI.
     */
    fun codeOfAndroid(keyCode: Int): String? = when (keyCode) {
        in 29..54 -> "Key" + ('A' + (keyCode - 29))
        in 7..16 -> "Digit" + (keyCode - 7)
        in 144..153 -> "Numpad" + (keyCode - 144)
        19 -> "ArrowUp"
        20 -> "ArrowDown"
        21 -> "ArrowLeft"
        22 -> "ArrowRight"
        62 -> "Space"
        66 -> "Enter"
        160 -> "NumpadEnter"
        111 -> "Escape"
        67 -> "Backspace"
        61 -> "Tab"
        122 -> "Home"
        123 -> "End"
        92 -> "PageUp"
        93 -> "PageDown"
        55 -> "Comma"
        56 -> "Period"
        69 -> "Minus"
        70 -> "Equal"
        71 -> "BracketLeft"
        72 -> "BracketRight"
        76 -> "Slash"
        81, 157 -> "NumpadAdd"
        156 -> "NumpadSubtract"
        else -> null
    }

    /** One line of the keyboard sheet: what it does, the keys, and the presses it promises (the tests read them back). */
    data class Row(val what: String, val keys: String, val presses: List<Press>)

    /**
     * The keys a tablet or a Chromebook has in the player, for the "?" sheet ([L.keysKeyboardTitle]):
     * only what the Android player does with them, so the sheet promises nothing the keys lack.
     */
    fun androidSheet(): List<Row> = listOf(
        Row(L.keysRowPlayPause, L.keysSpaceK, listOf(Press("Space"), Press("KeyK"))),
        Row(L.keysRowSeek10s, "J, L", listOf(Press("KeyJ"), Press("KeyL"))),
        Row(L.keysRowSeek1min, L.keysShiftLeftRight, listOf(Press("ArrowLeft", shift = true), Press("ArrowRight", shift = true))),
        Row(L.keysRowChapter, L.keysChapter(L.keysCtrl), listOf(Press("ArrowLeft", ctrl = true), Press("ArrowRight", ctrl = true))),
        Row(L.keysRowSpeed, L.keysSpeedKeys, listOf(Press("BracketRight"), Press("BracketLeft"), Press("Backspace"))),
        Row(L.keysRowEpisodes, L.keysEpisodesKeys, listOf(Press("KeyN", shift = true), Press("KeyP", shift = true))),
        Row(L.keysRowJump, L.keysDigits, listOf(Press("Digit0", "0"), Press("Digit9", "9"))),
        Row(L.keysRowMute, "M", listOf(Press("KeyM"))),
        Row(L.playerSubtitles, "C", listOf(Press("KeyC"))),
        Row(L.keysRowDetails, "I", listOf(Press("KeyI"))),
        Row(L.keysRowThisSheet, "?", listOf(Press("Slash", "?", shift = true))),
    )

    /** What a key asks of the app around the player: the shell's keys, on a tablet or a Chromebook with a keyboard. */
    enum class ShellKey { Search, Settings, Refresh }

    /**
     * Ctrl+F or "/" is search and Ctrl+comma is Settings, as in every other app with a keyboard. Ctrl+A
     * in grids follows with its screens (WP-39). F5 and Ctrl+R (Command+R on a Mac) refresh the
     * page, as in a browser or a file manager (WP-40). The caller reads this only
     * where the focused control did not take the key (a search field keeps its "/").
     */
    fun shellKeyFor(press: Press, mac: Boolean = false): ShellKey? {
        val command = if (mac) press.meta else press.ctrl
        return when {
            command && !press.alt && !press.shift && press.code == "KeyF" -> ShellKey.Search
            command && !press.alt && !press.shift && press.code == "Comma" -> ShellKey.Settings
            command && !press.alt && !press.shift && press.code == "KeyR" -> ShellKey.Refresh
            press.code == "F5" && !press.ctrl && !press.alt && !press.meta && !press.shift -> ShellKey.Refresh
            press.code == "Slash" && !press.ctrl && !press.alt && !press.meta && !press.shift -> ShellKey.Search
            else -> null
        }
    }
}
