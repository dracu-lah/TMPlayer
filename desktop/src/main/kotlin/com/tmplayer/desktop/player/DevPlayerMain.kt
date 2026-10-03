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
import com.tmplayer.desktop.DesktopPrefs
import kotlinx.coroutines.delay
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
 * - `--sub <file>` loads a subtitle file once playing, as dropping it on the picture does.
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
    val prefs = DesktopPrefs(File(settingsDir, "desktop.properties"))
    val (w, h) = (value("--size") ?: "1280x720").split("x").map { it.toInt() }
    val seekAfter = value("--seek-after")?.toLongOrNull()
    val seekTo = value("--seek-to")?.toLongOrNull()
    val pauseAfter = value("--pause-after")?.toLongOrNull()
    val quitAfter = value("--quit-after")?.toLongOrNull()
    val sub = value("--sub")

    application {
        val state = rememberWindowState(size = DpSize(w.dp, h.dp))
        var fullscreen by remember { mutableStateOf(false) }
        LaunchedEffect(fullscreen) {
            state.placement = if (fullscreen) WindowPlacement.Fullscreen else WindowPlacement.Floating
        }
        Window(onCloseRequest = ::exitApplication, state = state, title = "TMPlayer player (dev): ${file.name}") {
            val media = remember { LocalPlayerMedia(file, rate, settingsDir) }
            var engine by remember { mutableStateOf<PlaybackEngine?>(null) }
            PlayerScreen(
                media = media,
                startFromBeginning = flag("--from-start"),
                onBack = ::exitApplication,
                fullscreen = fullscreen,
                onToggleFullscreen = { fullscreen = !fullscreen },
                settings = store,
                prefs = prefs,
                modifier = Modifier.fillMaxSize(),
                onToggleAlwaysOnTop = { window.isAlwaysOnTop = !window.isAlwaysOnTop },
                onQuit = ::exitApplication,
                onEngine = { engine = it },
                detailsOpen = flag("--details"),
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
