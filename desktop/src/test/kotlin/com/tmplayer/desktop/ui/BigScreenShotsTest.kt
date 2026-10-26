package com.tmplayer.desktop.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.tmplayer.data.UiScale
import com.tmplayer.data.UpdateScheduler
import com.tmplayer.data.WatchedStore
import com.tmplayer.desktop.DesktopLayout
import com.tmplayer.desktop.DesktopPrefs
import com.tmplayer.desktop.DesktopWatchCache
import com.tmplayer.ui.theme.FocusVisibility
import com.tmplayer.ui.theme.TmMaterialTheme
import com.tmplayer.ui.theme.Tone
import org.jetbrains.skia.Color
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import org.jetbrains.skia.Surface as SkSurface

/**
 * The big-screen mode drawn off screen (plan 8.7): the TV bar over Home's rows, a chat's grid
 * with the remote on a poster, the picture's menu under the bar, and Settings, at 1920 by 1080
 * and 1280 by 720 with the scale a television gets. With TMPLAYER_SHOTS=<dir> the frames are
 * written there as `tv-<name>.png`.
 */
class BigScreenShotsTest {

    private val settings = SettingsStore(
        SettingsStore.openDataStore(Files.createTempDirectory("tm-tv-shots").resolve(SettingsStore.FILE_NAME).toFile()),
    )
    private val dir = Files.createTempDirectory("tm-tv-shots-extras").toFile()
    private val shell = ShellState(
        settings,
        object : DownloadRunner {
            override fun download(request: DownloadRequest) = Unit
            override fun cancel(fileId: Int) = Unit
            override fun pause(fileId: Int) = Unit
        },
        WatchedStore(WatchedStore.openDataStore(Files.createTempDirectory("tm-tv-shots-watched").resolve(WatchedStore.FILE_NAME).toFile())),
        services = {
            DesktopExtras(
                prefs = DesktopPrefs(dir.resolve("desktop.properties")),
                updates = UpdateScheduler(settings.updatePrefs, check = { _, _ -> false }, onSkip = {}),
                watchCache = DesktopWatchCache(settings, { dir.resolve("cache") }),
            )
        },
    )

    @Before
    fun television() {
        BigScreen.override = DesktopLayout.Tv
        FocusVisibility.visible = true
    }

    @After
    fun desktop() {
        BigScreen.override = null
        shell.profileOpen = false
    }

    private val colours = listOf(0xFF2AABEE, 0xFFE5484D, 0xFF46A758, 0xFFF5A524, 0xFF8E4EC6, 0xFF0090FF, 0xFF12A594, 0xFFD6409F)
    private var id = 1L

    private fun video(chatId: Long, title: String, date: Int) = MediaItem(
        chatId = chatId, messageId = id++, fileId = 0, title = title,
        sizeBytes = (300L + id * 37L) * 1024 * 1024, durationSec = 1500 + (id * 131 % 2000).toInt(),
        mimeType = "video/mp4", thumbnailFileId = 0, miniThumbnail = jpeg(colours[(id % colours.size).toInt()].toInt()),
        date = date, fileName = "$title.mkv",
    )

    private val chats = listOf(
        chat(2, "Film Club", ChatKind.Channel, 0xFFE5484D.toInt()),
        chat(4, "Weekend series", ChatKind.Group, 0xFFF5A524.toInt()),
        chat(6, "Old lectures", ChatKind.Channel, 0xFF0090FF.toInt()),
    )
    private val film = listOf("Night Train (2019) 1080p", "Mountains, a film", "The Quiet Year", "Paper Moons", "Low Tide", "Salt and Iron", "North Window")
        .mapIndexed { at, title -> video(2, title, 900 - at) }
    private val series = (1..6).map { video(4, "Harbour.Lights.S02E%02d".format(it), 800 + it) }
    private val lectures = (1..9).map { video(6, "Lecture $it: Compilers", 500 - it) }

    @Test
    fun televisionShots() {
        val resume = listOf(film[1], series[2]).mapIndexed { at, item ->
            com.tmplayer.data.ResumeRecord(
                chatId = item.chatId, messageId = item.messageId, fileId = 1, title = item.title,
                chatTitle = chats.first { it.id == item.chatId }.title, sizeBytes = item.sizeBytes,
                durationSec = item.durationSec, positionMs = (at + 1) * 400_000L, durationMs = item.durationSec * 1000L,
                updatedAt = 10L - at,
            )
        }
        val art = listOf(film[1], series[2]).associateBy { SettingsStore.progressKey(it.chatId, it.messageId) }
        val rows = com.tmplayer.data.HomeRows.build(
            continueWatching = resume,
            favourites = com.tmplayer.data.HomeRows.favouriteChats(chats, setOf(2L, 4L)),
            loaded = mapOf(2L to film, 4L to series),
            recent = lectures,
        )
        shell.go(Destination.Home)
        shot("home-1080p", 1920, 1080) { HomeRowsView(shell, rows, chats, art, onRowShown = {}, onArtWanted = {}, onRefresh = {}) }
        shot("home-720p", 1280, 720) { HomeRowsView(shell, rows, chats, art, onRowShown = {}, onArtWanted = {}, onRefresh = {}) }
        shot("home-light-1080p", 1920, 1080, dark = false) { HomeRowsView(shell, rows, chats, art, onRowShown = {}, onArtWanted = {}, onRefresh = {}) }

        // The remote on a poster of a chat opened from Chats.
        shell.go(Destination.Chats)
        shot("grid-focus-1080p", 1920, 1080) {
            VideoGrid(shell, film + series, "Film Club")
            LaunchedEffect(Unit) {
                kotlinx.coroutines.delay(100)
                shell.focusPage()
            }
        }
        // The remote on the bar's place for the page.
        shot("bar-focus-1080p", 1920, 1080) {
            VideoGrid(shell, film + series, "Film Club")
            LaunchedEffect(Unit) {
                androidx.compose.runtime.withFrameNanos { }
                shell.focusBar()
            }
        }
        shell.profileOpen = true
        shot("menu-1080p", 1920, 1080) { VideoGrid(shell, film + series, "Film Club") }
        shell.profileOpen = false
        shell.go(Destination.Settings)
        shot("settings-1080p", 1920, 1080) { SettingsPage(shell, "1.29.0") }
        // The Layout row, Interface size and Start in full screen, as Appearance lists them.
        shot("appearance-1080p", 1920, 1080) {
            androidx.compose.foundation.layout.Column(
                Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState(1400)),
            ) { AppearanceRows(shell) }
        }
    }

    private fun shot(name: String, width: Int, height: Int, dark: Boolean = true, page: @Composable () -> Unit) {
        val png = ImageComposeScene(width, height, Density(1f)) {
            ProvideInterfaceScale(UiScale.Default, bigScreen = true) {
                TmMaterialTheme(dark = dark) {
                    Surface(Modifier.fillMaxSize(), color = Tone.background) {
                        NavFrame(shell, null, null) { page() }
                    }
                }
            }
        }.use { scene ->
            scene.render(0)
            Thread.sleep(400)
            scene.render(500_000_000)
            Thread.sleep(200)
            scene.render(1_000_000_000).encodeToData(EncodedImageFormat.PNG)!!.bytes
        }
        assertTrue(png.size > 1000)
        val out = System.getenv("TMPLAYER_SHOTS")?.takeIf { it.isNotBlank() } ?: return
        File(out).apply { mkdirs() }.resolve("tv-$name.png").writeBytes(png)
    }

    private fun chat(id: Long, title: String, kind: ChatKind, argb: Int) = ChatSummary(
        id = id, title = title, miniThumbnail = jpeg(argb), photoFileId = 0, kind = kind,
        unreadCount = 0, isPinned = false,
    )

    /** A tiny JPEG, a gradient in [argb], standing in for Telegram's minithumbnail. */
    private fun jpeg(argb: Int): ByteArray {
        val surface = SkSurface.makeRasterN32Premul(40, 24)
        surface.canvas.drawRect(Rect.makeWH(40f, 24f), Paint().apply { color = argb })
        surface.canvas.drawRect(Rect.makeXYWH(0f, 14f, 40f, 10f), Paint().apply { color = Color.makeRGB(16, 20, 24) })
        return surface.makeImageSnapshot().encodeToData(EncodedImageFormat.JPEG, 80)!!.bytes
    }
}
