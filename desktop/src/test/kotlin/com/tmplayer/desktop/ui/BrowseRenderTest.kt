package com.tmplayer.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import com.tmplayer.data.ChatKind
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.DownloadRequest
import com.tmplayer.data.DownloadRunner
import com.tmplayer.data.MediaItem
import com.tmplayer.data.Release
import com.tmplayer.data.ReleaseAsset
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.WatchedStore
import com.tmplayer.data.UpdateScheduler
import com.tmplayer.desktop.DesktopPrefs
import com.tmplayer.desktop.DesktopWatchCache
import androidx.compose.foundation.layout.padding
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.TmMaterialTheme
import org.jetbrains.skia.Color
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface as SkSurface
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Draws the chat list and a chat's grid off screen with made-up chats and videos, as a check that
 * the shared Material 3 pieces compose on the JVM. With TMPLAYER_SHOTS=<dir> in the
 * environment the frames are written there as PNGs for a person to look at.
 */
class BrowseRenderTest {

    private val settings = SettingsStore(
        SettingsStore.openDataStore(Files.createTempDirectory("tm-render").resolve(SettingsStore.FILE_NAME).toFile()),
    )
    private val dir = Files.createTempDirectory("tm-render-extras").toFile()
    private val prefs = DesktopPrefs(dir.resolve("desktop.properties"))
    private val shell = ShellState(
        settings,
        object : DownloadRunner {
            override fun download(request: DownloadRequest) = Unit
            override fun cancel(fileId: Int) = Unit
            override fun pause(fileId: Int) = Unit
        },
        WatchedStore(WatchedStore.openDataStore(Files.createTempDirectory("tm-render-watched").resolve(WatchedStore.FILE_NAME).toFile())),
        services = {
            DesktopExtras(
                prefs = prefs,
                updates = UpdateScheduler(settings.updatePrefs, check = { _, _ -> false }, onSkip = {}),
                watchCache = DesktopWatchCache(settings, { dir.resolve("cache") }),
            )
        },
    )

    @Test
    fun chatList() {
        val chats = listOf(
            chat(1, "Saved Messages", ChatKind.Saved, 0, 0xFF2AABEE.toInt()),
            chat(2, "Film Club", ChatKind.Channel, 12, 0xFFE5484D.toInt()),
            chat(3, "Documentaries in 4K", ChatKind.Channel, 0, 0xFF46A758.toInt()),
            chat(4, "Weekend series", ChatKind.Group, 3, 0xFFF5A524.toInt()),
            chat(5, "Anna", ChatKind.Direct, 0, 0xFF8E4EC6.toInt()),
            chat(6, "Old lectures", ChatKind.Channel, 0, 0xFF0090FF.toInt()),
        )
        val png = render {
            Column(Modifier.fillMaxSize()) {
                PageHeader("Chats", "Everything, newest first")
                Column(Modifier.fillMaxSize()) {
                    chats.forEachIndexed { at, chat -> ChatRow(chat, favourite = at == 1, onOpen = {}, onStar = {}) }
                }
            }
        }
        save("chats.png", png)
    }

    @Test
    fun mediaGrid() {
        val titles = listOf(
            "The Long Road S01E01", "The Long Road S01E02", "The Long Road S01E03",
            "Night Train (2019) 1080p", "Mountains, a film", "Lecture 7: Compilers",
            "Harbour Lights S02E05", "Harbour Lights S02E06",
        )
        val colours = listOf(0xFF2AABEE, 0xFFE5484D, 0xFF46A758, 0xFFF5A524, 0xFF8E4EC6, 0xFF0090FF, 0xFF12A594, 0xFFD6409F)
        val items = titles.mapIndexed { at, title ->
            MediaItem(
                chatId = 2, messageId = at + 1L, fileId = 0, title = title,
                sizeBytes = (300L + at * 170L) * 1024 * 1024, durationSec = 1500 + at * 431,
                mimeType = "video/mp4", thumbnailFileId = 0, miniThumbnail = jpeg(colours[at].toInt()),
                date = 0, fileName = "$title.mkv", onDevice = at == 2,
            )
        }
        val png = render {
            Column(Modifier.fillMaxSize()) {
                PageHeader("Film Club", "8 videos")
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(shell.posterWidth),
                    contentPadding = PaddingValues(24.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    items(items, key = { it.id }) { MediaTile(shell, it, "Film Club") }
                }
            }
        }
        save("grid.png", png)
    }

    @Test
    fun watchedTicksAndSizeNote() = kotlinx.coroutines.runBlocking {
        val items = (1..6).map { at ->
            MediaItem(
                chatId = 2, messageId = at.toLong(), fileId = 0, title = "Harbour Lights S02E0$at",
                sizeBytes = 400L * 1024 * 1024, durationSec = 2400, mimeType = "video/mp4", thumbnailFileId = 0,
                miniThumbnail = jpeg(0xFF12A594.toInt()), date = 0, fileName = "Harbour Lights S02E0$at.mkv",
            )
        }
        val now = System.currentTimeMillis()
        shell.watched.markWatched(com.tmplayer.data.WatchedRecord.of(items[0], "Weekend series", now - 3 * 60_000, manual = false))
        shell.watched.markWatched(com.tmplayer.data.WatchedRecord.of(items[1], "Weekend series", now - 26 * 3_600_000L, manual = true))
        // A second viewing part way through: the tick stays, the bar shows where it is.
        settings.saveResumePosition(2, 2, 600_000, 2_400_000, "")
        val grid = render { VideoGrid(shell, items, "Weekend series", hiddenBySize = 3) }
        save("watched-grid.png", grid)
        val page = render { WatchedPage(shell) }
        save("watched-page.png", page)
    }

    @Test
    fun downloadsPage() = kotlinx.coroutines.runBlocking {
        val folder = dir.resolve("Downloads/TMPlayer").apply { mkdirs() }
        fun item(id: Long, title: String, size: Long) = MediaItem(
            chatId = 2, messageId = id, fileId = id.toInt(), title = title, sizeBytes = size, durationSec = 0,
            mimeType = "video/x-matroska", thumbnailFileId = 0, miniThumbnail = null, date = 0,
        )
        val kept = folder.resolve("Night Train (2019) 1080p.mkv").apply { writeBytes(ByteArray(2048)) }
        settings.noteDownload(item(11, "Night Train (2019) 1080p", 2048), "Film Club", kept.absolutePath)
        settings.noteDownload(item(12, "The Long Road S01E01", 4096), "Film Club", folder.resolve("gone.mkv").absolutePath)
        settings.rememberCachedVideo(item(13, "Harbour Lights S02E05", 900L * 1024 * 1024), "Weekend series")
        com.tmplayer.data.OfflineDownloads.note(
            com.tmplayer.data.OfflineDownloads.Progress(
                request = DownloadRequest.from(item(14, "Mountains, a film", 1_500L * 1024 * 1024), "Documentaries"),
                downloadedBytes = 600L * 1024 * 1024,
                totalBytes = 1_500L * 1024 * 1024,
                stage = com.tmplayer.data.OfflineDownloads.Stage.Running,
                bytesPerSecond = 3_000_000,
            ),
        )
        try {
            val png = render { DownloadsPage(shell, folder) }
            save("downloads.png", png)
        } finally {
            com.tmplayer.data.OfflineDownloads.forget(14)
        }
    }

    @Test
    fun settingsAndUpdatePopup() {
        val release = Release(
            version = "2.0.1",
            pageUrl = "https://github.com/dracu-lah/TMPlayer/releases/tag/v2.0.1",
            notes = "Downloads leave the cache and live in a folder you choose.",
            assets = mapOf("windows-x64-msi" to ReleaseAsset("https://x/TMPlayer-2.0.1-windows-x64.msi", size = 155_000_000)),
        )
        val png = render(update = NavUpdate("Update", "2.0.1")) {
            Box(Modifier.fillMaxSize()) {
                SettingsPage(shell, "2.0.0")
                UpdatePopup(
                    release = release,
                    skipped = false,
                    installed = "2.0.0",
                    kind = com.tmplayer.desktop.InstallKind.WindowsMsi,
                    canUpdate = true,
                    progress = com.tmplayer.desktop.UpdateProgress.Downloading(0.42f),
                    onUpdate = {},
                    onOpenPage = {},
                    onLater = {},
                    onSkip = {},
                    onRestart = {},
                    onClose = {},
                )
            }
        }
        save("settings.png", png)
    }

    @Test
    fun updatePopupOffers() {
        val release = Release("2.0.1", "page", notes = "Downloads leave the cache.")
        val png = render(update = NavUpdate("Update", "2.0.1")) {
            UpdatePopup(
                release = release,
                skipped = false,
                installed = "2.0.0",
                kind = com.tmplayer.desktop.InstallKind.Flatpak,
                canUpdate = false,
                progress = com.tmplayer.desktop.UpdateProgress.Idle,
                onUpdate = {},
                onOpenPage = {},
                onLater = {},
                onSkip = {},
                onRestart = {},
                onClose = {},
            )
        }
        save("update-popup.png", png)
    }

    private fun render(update: NavUpdate? = null, page: @androidx.compose.runtime.Composable () -> Unit): ByteArray =
        ImageComposeScene(WIDTH, HEIGHT, Density(1f)) {
            TmMaterialTheme(dark = true) {
                Surface(Modifier.fillMaxSize(), color = Tone.background) {
                    Row(Modifier.fillMaxSize()) {
                        Sidebar(shell, update)
                        VerticalDivider(color = Tone.outline)
                        Box(Modifier.weight(1f).fillMaxHeight()) { page() }
                    }
                }
            }
        }.use { scene ->
            scene.render(0)
            // A second frame after the thumbnails have decoded off the composition thread.
            Thread.sleep(400)
            scene.render(500_000_000)
            scene.render(1_000_000_000).encodeToData(EncodedImageFormat.PNG)!!.bytes
        }

    private fun save(name: String, png: ByteArray) {
        assertTrue(png.size > 1000)
        val dir = System.getenv("TMPLAYER_SHOTS")?.takeIf { it.isNotBlank() } ?: return
        File(dir).apply { mkdirs() }.resolve(name).writeBytes(png)
    }

    private fun chat(id: Long, title: String, kind: ChatKind, unread: Int, argb: Int) = ChatSummary(
        id = id, title = title, miniThumbnail = jpeg(argb), photoFileId = 0, kind = kind,
        unreadCount = unread, isPinned = id == 1L,
    )

    /** A tiny JPEG, a gradient in [argb], standing in for Telegram's minithumbnail. */
    private fun jpeg(argb: Int): ByteArray {
        val surface = SkSurface.makeRasterN32Premul(40, 24)
        surface.canvas.drawRect(Rect.makeWH(40f, 24f), Paint().apply { color = argb })
        surface.canvas.drawRect(Rect.makeXYWH(0f, 14f, 40f, 10f), Paint().apply { color = Color.makeRGB(16, 20, 24) })
        return surface.makeImageSnapshot().encodeToData(EncodedImageFormat.JPEG, 80)!!.bytes
    }

    private companion object {
        const val WIDTH = 1280
        const val HEIGHT = 800
    }
}
