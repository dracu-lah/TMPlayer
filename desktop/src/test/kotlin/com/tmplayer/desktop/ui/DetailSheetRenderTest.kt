package com.tmplayer.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import com.tmplayer.data.ChatKind
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.DownloadRequest
import com.tmplayer.data.DownloadRunner
import com.tmplayer.data.MediaItem
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.UpdateScheduler
import com.tmplayer.data.WatchedStore
import com.tmplayer.desktop.DesktopPrefs
import com.tmplayer.desktop.DesktopWatchCache
import com.tmplayer.online.MetaQuery
import com.tmplayer.online.MetaResult
import com.tmplayer.ui.theme.TmMaterialTheme
import com.tmplayer.ui.theme.Tone
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
 * A video's detail sheet floating over Home, a show's page, at 900, 1280, 1920 and 2560 dp wide
 * in both themes: the sheet over a scrim with Home unmoved under it, a
 * film and an episode, and the sheet's whole length drawn in a tall window. With
 * TMPLAYER_SHOTS=<dir> the frames are written there.
 */
class DetailSheetRenderTest {

    private val settings = SettingsStore(
        SettingsStore.openDataStore(Files.createTempDirectory("tm-sheet").resolve(SettingsStore.FILE_NAME).toFile()),
    )
    private val dir = Files.createTempDirectory("tm-sheet-extras").toFile()
    private val prefs = DesktopPrefs(dir.resolve("desktop.properties"))
    private val shell = ShellState(
        settings,
        object : DownloadRunner {
            override fun download(request: DownloadRequest) = Unit
            override fun cancel(fileId: Int) = Unit
            override fun pause(fileId: Int) = Unit
        },
        WatchedStore(WatchedStore.openDataStore(Files.createTempDirectory("tm-sheet-watched").resolve(WatchedStore.FILE_NAME).toFile())),
        services = {
            DesktopExtras(
                prefs = prefs,
                updates = UpdateScheduler(settings.updatePrefs, check = { _, _ -> false }, onSkip = {}),
                watchCache = DesktopWatchCache(settings, { dir.resolve("cache") }),
            )
        },
    )

    private fun item(id: Long, chatId: Long, name: String, colour: Long, date: Int) = MediaItem(
        chatId = chatId, messageId = id, fileId = 0, title = name, sizeBytes = 885L * 1024 * 1024,
        durationSec = 596 + (id % 10).toInt() * 300, mimeType = "video/x-matroska", thumbnailFileId = 0,
        miniThumbnail = jpeg(colour.toInt()), date = date, fileName = name, width = 1920, height = 1080,
    )

    @Test
    fun sheetOverHome() = kotlinx.coroutines.runBlocking {
        val online = MetaFixture.install("on")
        try {
            val bunny = item(500, 2, "Big.Buck.Bunny.2008.1080p.BluRay.x264.mkv", 0xFF46A758, 1_790_000_000)
            val episodes = (1..6).map { n -> item(600L + n, 4, "Harbour.Lights.S02E%02d.1080p.WEB-DL.mkv".format(n), 0xFF2AABEE, 1_790_000_000 + n) }
            val clips = listOf("Lecture 7 Compilers.mp4", "coast-walk-day-2-1080p.mp4").mapIndexed { at, n -> item(700L + at, 2, n, 0xFFF5A524, 1_780_000_000) }
            for (it in listOf(bunny, episodes[4], clips[0], clips[1])) {
                val q = MetaQuery.of(it.fileName)!!
                online.lookup(q)
                val info = (online.lookup(q.showOnly()) as? MetaResult.Found)?.info ?: continue
                online.extras(info)
                for (url in listOfNotNull(info.posterUrl, info.backdropUrl, info.wideUrl)) {
                    for (width in listOf(240, 342, 480, 800, 1280)) com.tmplayer.data.Thumbnails.online(url, width) { online.image(url) }
                }
            }
            val chats = listOf(
                chat(2, "Film Club", ChatKind.Channel, 0xFFE5484D.toInt()),
                chat(4, "Weekend series", ChatKind.Group, 0xFFF5A524.toInt()),
            )
            shell.noteChatTitles(chats)
            com.tmplayer.online.KnownMedia.note(episodes + bunny + clips)
            val rows = com.tmplayer.data.HomeRows.build(
                continueWatching = emptyList(),
                favourites = com.tmplayer.data.HomeRows.favouriteChats(chats, setOf(2L, 4L)),
                loaded = mapOf(2L to listOf(bunny) + clips, 4L to episodes),
                recent = clips,
            )
            shell.go(Destination.Home)
            val home: @androidx.compose.runtime.Composable () -> Unit = {
                HomeRowsView(shell, rows, chats, emptyMap(), onRowShown = {}, onArtWanted = {}, onRefresh = {})
            }
            for ((w, h) in listOf(900 to 700, 1280 to 800, 1920 to 1080, 2560 to 1440)) {
                for (dark in listOf(true, false)) {
                    val theme = if (dark) "dark" else "light"
                    shell.openDetail(DetailRequest(bunny, "Film Club", outsideChat = true))
                    save("sheet-film-$w-$theme.png", render(w, h, dark, home))
                    shell.closeDetail()
                    shell.openDetail(DetailRequest(episodes[4], "Weekend series", outsideChat = true))
                    save("sheet-episode-$w-$theme.png", render(w, h, dark, home))
                    shell.closeDetail()
                }
                // The sheet's whole length, in a window tall enough to show it.
                shell.openDetail(DetailRequest(bunny, "Film Club", outsideChat = true))
                save("sheet-film-$w-full.png", render(w, 2400, true, home))
                shell.closeDetail()
                shell.openDetail(DetailRequest(episodes[4], "Weekend series", outsideChat = true))
                save("sheet-episode-$w-full.png", render(w, 2600, false, home))
                shell.closeDetail()
                val show = (com.tmplayer.data.SeriesShelf.arrange(episodes).first() as com.tmplayer.data.ShelfEntry.Show).series
                for (dark in listOf(true, false)) {
                    val theme = if (dark) "dark" else "light"
                    save("show-$w-$theme.png", render(w, h, dark) { SeriesPage(shell, show, rememberSeriesWatch(shell), onClose = {}) })
                }
                save("show-$w-full.png", render(w, 2000, true) { SeriesPage(shell, show, rememberSeriesWatch(shell), onClose = {}) })
            }
        } finally {
            com.tmplayer.online.OnlineMetadata.current = null
            shell.go(Destination.Chats)
        }
    }

    private fun render(width: Int, height: Int, dark: Boolean, page: @androidx.compose.runtime.Composable () -> Unit): ByteArray =
        ImageComposeScene(width, height, Density(1f)) {
            TmMaterialTheme(dark = dark) {
                Surface(Modifier.fillMaxSize(), color = Tone.background) {
                    Box(Modifier.fillMaxSize()) {
                        NavFrame(shell, null, null) { page() }
                        DetailPaneHost(shell)
                    }
                }
            }
        }.use { scene ->
            scene.render(0)
            Thread.sleep(600)
            scene.render(500_000_000)
            Thread.sleep(400)
            scene.render(1_000_000_000)
            Thread.sleep(200)
            scene.render(2_000_000_000)
            scene.render(3_000_000_000).encodeToData(EncodedImageFormat.PNG)!!.bytes
        }

    private fun save(name: String, png: ByteArray) {
        assertTrue(png.size > 1000)
        val dir = System.getenv("TMPLAYER_SHOTS")?.takeIf { it.isNotBlank() } ?: return
        File(dir).apply { mkdirs() }.resolve(name).writeBytes(png)
    }

    private fun chat(id: Long, title: String, kind: ChatKind, argb: Int) = ChatSummary(
        id = id, title = title, miniThumbnail = jpeg(argb), photoFileId = 0, kind = kind, unreadCount = 0, isPinned = false,
    )

    private fun jpeg(argb: Int): ByteArray {
        val surface = SkSurface.makeRasterN32Premul(40, 24)
        surface.canvas.drawRect(Rect.makeWH(40f, 24f), Paint().apply { color = argb })
        surface.canvas.drawRect(Rect.makeXYWH(0f, 14f, 40f, 10f), Paint().apply { color = Color.makeRGB(16, 20, 24) })
        surface.canvas.drawRect(Rect.makeXYWH(24f, 2f, 10f, 8f), Paint().apply { color = Color.makeRGB(240, 220, 160) })
        return surface.makeImageSnapshot().encodeToData(EncodedImageFormat.JPEG, 80)!!.bytes
    }
}
