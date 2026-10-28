package com.tmplayer.desktop.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import com.tmplayer.data.DownloadRequest
import com.tmplayer.data.DownloadRunner
import com.tmplayer.data.MediaItem
import com.tmplayer.data.OfflineDownloads
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.UpdateScheduler
import com.tmplayer.data.WatchedStore
import com.tmplayer.desktop.DesktopPrefs
import com.tmplayer.desktop.DesktopWatchCache
import com.tmplayer.ui.theme.TmMaterialTheme
import com.tmplayer.ui.theme.Tone
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Draws the Downloads page off screen at the window sizes people use, light and dark, with a video
 * coming down, one waiting, a show with three episodes, videos on their own, a file gone from the
 * folder and recently played videos, under the floating bar as the window shows it. Once with the
 * page at its top and once landed on Recently played, unfolded. With TMPLAYER_SHOTS=<dir> in the
 * environment the frames are written there as PNGs for a person to look at.
 */
class DownloadsRenderTest {

    private val settings = SettingsStore(
        SettingsStore.openDataStore(Files.createTempDirectory("tm-dl-render").resolve(SettingsStore.FILE_NAME).toFile()),
    )
    private val dir = Files.createTempDirectory("tm-dl-render-extras").toFile()
    private val prefs = DesktopPrefs(dir.resolve("desktop.properties"))
    private val shell = ShellState(
        settings,
        object : DownloadRunner {
            override fun download(request: DownloadRequest) = Unit
            override fun cancel(fileId: Int) = Unit
            override fun pause(fileId: Int) = Unit
        },
        WatchedStore(WatchedStore.openDataStore(Files.createTempDirectory("tm-dl-render-watched").resolve(WatchedStore.FILE_NAME).toFile())),
        services = {
            DesktopExtras(
                prefs = prefs,
                updates = UpdateScheduler(settings.updatePrefs, check = { _, _ -> false }, onSkip = {}),
                watchCache = DesktopWatchCache(settings, { dir.resolve("cache") }),
            )
        },
    )

    private fun item(id: Long, title: String, size: Long, chat: Long = 2) = MediaItem(
        chatId = chat, messageId = id, fileId = id.toInt(), title = title, sizeBytes = size, durationSec = 0,
        mimeType = "video/x-matroska", thumbnailFileId = 0, miniThumbnail = null, date = 0,
    )

    @Test
    fun downloadsAtEverySize() = kotlinx.coroutines.runBlocking {
        val folder = dir.resolve("Downloads/TMPlayer").apply { mkdirs() }
        suspend fun keep(id: Long, title: String, chat: String, mb: Long) {
            // Sparse, at the size the record names, so the index takes it as whole.
            val file = folder.resolve("$title.mkv").apply { java.io.RandomAccessFile(this, "rw").use { it.setLength(mb * 1024 * 1024) } }
            settings.noteDownload(item(id, title, mb * 1024 * 1024), chat, file.absolutePath)
        }
        keep(21, "Harbour Lights S01E01", "Weekend series", 812)
        keep(22, "Harbour Lights S01E02", "Weekend series", 790)
        keep(23, "Harbour Lights S01E03", "Weekend series", 845)
        keep(24, "Coast walk at dawn, a long and slow video from the north shore 2160p", "Travel clips", 3_400)
        keep(25, "Lecture 7, sorting algorithms", "Old lectures", 420)
        settings.noteDownload(item(26, "Birthday at the lake", 640L * 1024 * 1024), "Family", folder.resolve("gone.mkv").absolutePath)
        // On a drive that is not plugged in: the folder is gone with the file (HX21).
        settings.noteDownload(item(27, "School concert", 520L * 1024 * 1024), "Family", dir.resolve("Unplugged/TMPlayer/concert.mkv").absolutePath)
        settings.rememberCachedVideo(item(31, "Night market walk 1080p", 900L * 1024 * 1024), "Travel clips")
        settings.rememberCachedVideo(item(32, "Lecture 8, graphs", 380L * 1024 * 1024), "Old lectures")
        OfflineDownloads.note(
            OfflineDownloads.Progress(
                request = DownloadRequest.from(item(41, "Mountain railway, the full ride 1080p", 1_500L * 1024 * 1024), "Travel clips"),
                downloadedBytes = 600L * 1024 * 1024,
                totalBytes = 1_500L * 1024 * 1024,
                stage = OfflineDownloads.Stage.Running,
                bytesPerSecond = 3_000_000,
            ),
        )
        OfflineDownloads.note(
            OfflineDownloads.Progress(
                request = DownloadRequest.from(item(42, "Harbour Lights S01E04", 830L * 1024 * 1024), "Weekend series"),
                downloadedBytes = 0,
                totalBytes = 830L * 1024 * 1024,
                stage = OfflineDownloads.Stage.Queued,
                order = 1,
            ),
        )
        try {
            for ((w, h) in SIZES) for (dark in listOf(true, false)) {
                val theme = if (dark) "dark" else "light"
                save("downloads-${w}x$h-$theme.png", render(w, h, dark) { DownloadsPage(shell, folder) })
                shell.downloadsOpenRecent = true
                save("downloads-${w}x$h-$theme-recent.png", render(w, h, dark) { DownloadsPage(shell, folder) })
                shell.downloadsOpenRecent = false
            }
        } finally {
            OfflineDownloads.forget(41)
            OfflineDownloads.forget(42)
        }
    }

    @Test
    fun emptyDownloads() {
        val folder = dir.resolve("Empty/TMPlayer").apply { mkdirs() }
        for (dark in listOf(true, false)) {
            save("downloads-empty-1280x800-${if (dark) "dark" else "light"}.png", render(1280, 800, dark) { DownloadsPage(shell, folder) })
        }
    }

    private fun render(width: Int, height: Int, dark: Boolean, page: @androidx.compose.runtime.Composable () -> Unit): ByteArray =
        ImageComposeScene(width, height, Density(1f)) {
            TmMaterialTheme(dark = dark) {
                Surface(Modifier.fillMaxSize(), color = Tone.background) {
                    NavFrame(shell, null, null) { page() }
                }
            }
        }.use { scene ->
            scene.render(0)
            Thread.sleep(500)
            scene.render(500_000_000)
            Thread.sleep(200)
            scene.render(1_000_000_000).encodeToData(EncodedImageFormat.PNG)!!.bytes
        }

    private fun save(name: String, png: ByteArray) {
        assertTrue(png.size > 1000)
        val out = System.getenv("TMPLAYER_SHOTS")?.takeIf { it.isNotBlank() } ?: return
        File(out).apply { mkdirs() }.resolve(name).writeBytes(png)
    }

    private companion object {
        val SIZES = listOf(960 to 600, 1280 to 800, 1366 to 768, 1920 to 1080, 2560 to 1440)
    }
}
