package com.tmplayer.desktop.player

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.WatchedStore
import com.tmplayer.desktop.DesktopPrefs
import com.tmplayer.desktop.os.MiniPlayerWindow
import com.tmplayer.desktop.os.NativeFullscreen
import com.tmplayer.desktop.os.NonReparentingWm
import kotlinx.coroutines.delay
import java.awt.Frame
import java.awt.Rectangle
import java.awt.Toolkit
import java.io.File
import kotlin.system.exitProcess

/**
 * Plays a file on disk through the same [PlayerScreen] the app uses, without a Telegram account.
 *
 * ```
 * ./gradlew :desktop:runPlayerDev --args="--file /path/to/clip.mkv"
 * ```
 *
 * Options:
 * - `--growing <MB/s>` streams the file through [TdMediaData] off a copy that fills at that rate,
 *   the way TDLib's partial file does, re-aiming on a far seek.
 * - `--settings <dir>` keeps resume points and the like there (default: a temp folder).
 * - `--from-start` ignores the saved position.
 * - `--size <W>x<H>` the window size.
 * - `--seek-after <ms> --seek-to <ms>` and `--pause-after <ms>` script a run, for screenshots.
 * - `--details` opens the Playback details panel from the start.
 * - `--menu <page>` opens the menu at that page from the start (`Main`, `Sleep`, as in [MenuPage]).
 * - `--idle-limit <ms>` asks "Still watching?" after that much playback without input, in place
 *   of two hours, to see the card.
 * - `--volume-boost on|off` sets the Volume boost setting before the player opens.
 * - `--mini-after <ms>` turns the mini player on, prints the window's bounds, and off again a second later.
 * - `--sub <file>` loads a subtitle file once playing, as dropping it on the picture does.
 * - `--fullscreen-after <ms>` goes fullscreen, prints the window's bounds, and back a second later.
 *   Each print carries the frame's bounds, its client area, the work area of its monitor, the
 *   Compose placement and AWT's `extendedState`, so a script can check where the window landed.
 * - `--fullscreen-rounds <n>` repeats that round trip n times (default 1), to show drift.
 * - `--maximized` starts maximized, so the round trip above is the maximized one.
 * - `--keys-after <ms> --keys <chars>` types the keys into the window through `java.awt.Robot`,
 *   so a run goes through the real keyboard table: letters as themselves, `-` and `=` with Ctrl
 *   held (the audio delay pair). Pair it with `--pause-after` to read the result off the log.
 * - `--sub-style-after <ms> --sub-style <Size>[,box][,<Position>]` writes the subtitle look to
 *   the settings mid run (names as in [com.tmplayer.player.SubtitleSize] and
 *   [com.tmplayer.player.SubtitlePosition]), the way the Settings page does, to show it reaching
 *   the video already playing.
 * - `--quit-after <ms>` closes the window, so a scripted run ends on its own.
 */
fun main(argv: Array<String>) {
    val args = argv.toList()
    fun value(name: String): String? = args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
    fun flag(name: String): Boolean = name in args

    val file = value("--file")?.let(::File)
    if (file == null || !file.isFile) {
        System.err.println("Usage: runPlayerDev --args=\"--file <video> [--growing <MB/s>] [--from-start]\"")
        exitProcess(2)
    }
    val rate = value("--growing")?.toDoubleOrNull()?.let { (it * 1024 * 1024).toLong() }
    val settingsDir = File(value("--settings") ?: File(System.getProperty("java.io.tmpdir"), "tmplayer-dev").path)
        .apply { mkdirs() }
    val store = SettingsStore(SettingsStore.openDataStore(File(settingsDir, SettingsStore.FILE_NAME)))
    val watched = WatchedStore(WatchedStore.openDataStore(File(settingsDir, WatchedStore.FILE_NAME)))
    val prefs = DesktopPrefs(File(settingsDir, "desktop.properties"))
    val (w, h) = (value("--size") ?: "1280x720").split("x").map { it.toInt() }
    val seekAfter = value("--seek-after")?.toLongOrNull()
    val seekTo = value("--seek-to")?.toLongOrNull()
    val pauseAfter = value("--pause-after")?.toLongOrNull()
    val quitAfter = value("--quit-after")?.toLongOrNull()
    val sub = value("--sub")
    val keysAfter = value("--keys-after")?.toLongOrNull()
    val keys = value("--keys").orEmpty()
    val styleAfter = value("--sub-style-after")?.toLongOrNull()
    val style = value("--sub-style")?.split(',')?.let { parts ->
        com.tmplayer.player.SubtitleStyle.from(parts.first(), "box" in parts, parts.drop(1).firstOrNull { it != "box" })
    }
    val miniAfter = value("--mini-after")?.toLongOrNull()
    val fullscreenAfter = value("--fullscreen-after")?.toLongOrNull()
    val fullscreenRounds = value("--fullscreen-rounds")?.toIntOrNull() ?: 1
    val menuPage = value("--menu")
    val idleLimit = value("--idle-limit")?.toLongOrNull()
    // Written before the player opens, as the menu's toggle would have left it.
    value("--volume-boost")?.let { on -> kotlinx.coroutines.runBlocking { store.setVolumeBoost(on == "on") } }

    NonReparentingWm.applyIfNeeded()
    application {
        val state = rememberWindowState(
            placement = if (flag("--maximized")) WindowPlacement.Maximized else WindowPlacement.Floating,
            size = DpSize(w.dp, h.dp),
        )
        var fullscreen by remember { mutableStateOf(false) }
        Window(onCloseRequest = ::exitApplication, state = state, title = "TMPlayer player (dev): ${file.name}") {
            // The same way in as the app's window takes (see Main.kt).
            val native = remember(window) { NativeFullscreen(window) }
            LaunchedEffect(fullscreen) {
                if (native.set(fullscreen)) return@LaunchedEffect
                state.placement = if (fullscreen) WindowPlacement.Fullscreen else WindowPlacement.Floating
            }
            val media = remember { LocalPlayerMedia(file, rate, settingsDir) }
            var engine by remember { mutableStateOf<PlaybackEngine?>(null) }
            val mini = remember { MiniPlayerWindow(window) }
            PlayerScreen(
                media = media,
                startFromBeginning = flag("--from-start"),
                onBack = ::exitApplication,
                fullscreen = fullscreen,
                onToggleFullscreen = { fullscreen = !fullscreen },
                settings = store,
                watched = watched,
                prefs = prefs,
                modifier = Modifier.fillMaxSize(),
                onToggleAlwaysOnTop = { window.isAlwaysOnTop = !window.isAlwaysOnTop },
                onQuit = ::exitApplication,
                onEngine = { engine = it },
                onMiniPlayer = mini::toggle,
                detailsOpen = flag("--details"),
                menuOpen = menuPage,
                idleLimitMs = idleLimit ?: com.tmplayer.player.StillWatching.IDLE_LIMIT_MS,
            )
            LaunchedEffect(engine) {
                val e = engine ?: return@LaunchedEffect
                val t0 = System.currentTimeMillis()
                suspend fun at(ms: Long) {
                    // Counted from the first frame, not from launch, so a slow open does not eat the script.
                    while (!e.state.value.opened) delay(20)
                    val wait = ms - (System.currentTimeMillis() - t0)
                    if (wait > 0) delay(wait)
                }
                if (sub != null) {
                    at(500)
                    println("dev: sub-add ${e.addSubtitle(File(sub).absolutePath)}")
                }
                if (miniAfter != null) {
                    at(miniAfter)
                    println("dev: window ${window.bounds}")
                    mini.enter()
                    delay(1_000)
                    println("dev: mini ${window.bounds} on top ${window.isAlwaysOnTop}")
                    mini.leave()
                    delay(500)
                    println("dev: back ${window.bounds} on top ${window.isAlwaysOnTop}")
                }
                if (fullscreenAfter != null) {
                    at(fullscreenAfter)
                    println("dev: start ${geometry(window, state.placement)}")
                    repeat(fullscreenRounds) {
                        fullscreen = true
                        delay(1_000)
                        println("dev: fullscreen ${geometry(window, state.placement)}")
                        fullscreen = false
                        delay(1_000)
                        println("dev: back ${geometry(window, state.placement)}")
                    }
                }
                if (styleAfter != null && style != null) {
                    at(styleAfter)
                    store.setSubtitleStyle(style)
                    println("dev: style $style")
                }
                if (keysAfter != null && keys.isNotEmpty()) {
                    at(keysAfter)
                    window.toFront()
                    delay(300)
                    typeKeys(keys)
                    delay(300)
                    println("dev: after keys '$keys' ${e.state.value.delays}")
                }
                if (seekAfter != null && seekTo != null) {
                    at(seekAfter)
                    e.seekTo(seekTo)
                }
                if (pauseAfter != null) {
                    at(pauseAfter)
                    e.pause()
                    // What the run ended up with, for whoever reads the log.
                    println("dev: state ${e.state.value}")
                    e.tracks.value.forEach { println("dev: track $it") }
                    e.details().forEach { (k, v) -> println("dev: $k = $v") }
                }
                if (quitAfter != null) {
                    at(quitAfter)
                    exitApplication()
                }
            }
        }
    }
}

/** See `--keys`: each character pressed and let go, `-` and `=` with Ctrl held. */
private suspend fun typeKeys(keys: String) {
    val robot = java.awt.Robot()
    for (c in keys) {
        val ctrl = c == '-' || c == '='
        val code = java.awt.event.KeyEvent.getExtendedKeyCodeForChar(c.code)
        if (ctrl) robot.keyPress(java.awt.event.KeyEvent.VK_CONTROL)
        robot.keyPress(code)
        robot.keyRelease(code)
        if (ctrl) robot.keyRelease(java.awt.event.KeyEvent.VK_CONTROL)
        delay(120)
    }
}

/**
 * Where [window] is, as one line a script can read: the frame's bounds, its client area (the
 * frame less its insets, whose bottom edge is the one a maximized window lines up with the work
 * area), the work area of its monitor, the Compose [placement] and AWT's `extendedState`.
 * Rectangles read `x,y,WxH`.
 */
private fun geometry(window: Frame, placement: WindowPlacement): String {
    fun Rectangle.text() = "$x,$y,${width}x$height"
    val b = window.bounds
    val i = window.insets
    val client = Rectangle(b.x + i.left, b.y + i.top, b.width - i.left - i.right, b.height - i.top - i.bottom)
    val gc = window.graphicsConfiguration
    val s = Toolkit.getDefaultToolkit().getScreenInsets(gc)
    val work = gc.bounds.let { Rectangle(it.x + s.left, it.y + s.top, it.width - s.left - s.right, it.height - s.top - s.bottom) }
    return "bounds=${b.text()} client=${client.text()} work=${work.text()} " +
        "placement=$placement extendedState=${window.extendedState}"
}
