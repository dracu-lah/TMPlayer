package com.tmplayer.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import com.tmplayer.data.DownloadRequest
import com.tmplayer.data.DownloadRunner
import com.tmplayer.data.MediaItem
import com.tmplayer.data.Release
import com.tmplayer.data.ReleaseAsset
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.UpdateScheduler
import com.tmplayer.data.WatchedStore
import com.tmplayer.desktop.DesktopPrefs
import com.tmplayer.desktop.DesktopWatchCache
import com.tmplayer.desktop.InstallKind
import com.tmplayer.desktop.UpdateProgress
import com.tmplayer.desktop.WatchedWords
import com.tmplayer.desktop.player.MenuAt
import com.tmplayer.desktop.player.MenuPage
import com.tmplayer.desktop.player.PlaybackStatus
import com.tmplayer.desktop.player.PlayerMenu
import com.tmplayer.desktop.player.PlayerTheme
import com.tmplayer.desktop.player.ShortcutSheet
import com.tmplayer.i18n.L
import com.tmplayer.i18n.Languages
import com.tmplayer.i18n.Translator
import com.tmplayer.ui.i18n.ProvideStrings
import com.tmplayer.ui.theme.TmMaterialTheme
import com.tmplayer.ui.theme.Tone
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface as SkSurface
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The desktop's text in English and in the en-XA pseudo-locale, so text that does not go through
 * the catalog shows up unaccented and text that does not fit shows up cut: browse, Settings, the
 * player's menu, the "?" sheet, the update popup and a dialog. With TMPLAYER_SHOTS=<dir> the frames
 * are written there as `lang-<tag>-<name>.png`.
 */
class LanguageShotsTest {

    private val settings = SettingsStore(
        SettingsStore.openDataStore(Files.createTempDirectory("tm-lang").resolve(SettingsStore.FILE_NAME).toFile()),
    )
    private val dir = Files.createTempDirectory("tm-lang-extras").toFile()
    private val shell = ShellState(
        settings,
        object : DownloadRunner {
            override fun download(request: DownloadRequest) = Unit
            override fun cancel(fileId: Int) = Unit
            override fun pause(fileId: Int) = Unit
        },
        WatchedStore(WatchedStore.openDataStore(Files.createTempDirectory("tm-lang-watched").resolve(WatchedStore.FILE_NAME).toFile())),
        services = {
            DesktopExtras(
                prefs = DesktopPrefs(dir.resolve("desktop.properties")),
                updates = UpdateScheduler(settings.updatePrefs, check = { _, _ -> false }, onSkip = {}),
                watchCache = DesktopWatchCache(settings, { dir.resolve("cache") }),
            )
        },
    )

    private val items = listOf("Coast walk, day 2", "Chickpea salad recipe", "Build a small shelf", "Birthday highlights", "Shape basics tutorial", "Forest trail morning")
        .mapIndexed { at, title ->
            MediaItem(
                chatId = 1, messageId = at + 1L, fileId = 0, title = title,
                sizeBytes = (180L + at * 90L) * 1024 * 1024, durationSec = 700 + at * 300,
                mimeType = "video/mp4", thumbnailFileId = 0, miniThumbnail = jpeg(0xFF12A594.toInt() + at * 0x203040),
                date = 0, fileName = "$title.mkv",
            )
        }

    private val release = Release(
        "2.0.0",
        "https://github.com/dracu-lah/TMPlayer/releases/tag/v2.0.0",
        notes = "Made-up notes for the screenshot.",
        assets = mapOf("TMPlayer-2.0.0.AppImage" to ReleaseAsset("https://example.invalid/TMPlayer-2.0.0.AppImage", size = 140L * 1024 * 1024)),
    )

    @Test
    fun englishAndPseudo() {
        for (tag in listOf(Languages.ENGLISH, Languages.PSEUDO)) {
            Translator.use(tag)
            try {
                shots(tag)
            } finally {
                Translator.use(Languages.ENGLISH)
            }
        }
    }

    private fun shots(tag: String) {
        fun shot(name: String, sidebar: Boolean = true, page: @Composable () -> Unit) =
            save("lang-$tag-$name.png", render(sidebar, page))

        shot("browse") { Grid() }
        shot("settings") { SettingsPage(shell, "2.0.0") }
        shot("playermenu", sidebar = false) {
            PlayerTheme {
                Box(Modifier.fillMaxSize().background(Color(0xFF203040))) {
                    Box(Modifier.align(Alignment.TopEnd).padding(top = 40.dp, end = 300.dp)) {
                        PlayerMenu(
                            menu = MenuAt(MenuPage.Main, MenuAt.Anchor.Overflow),
                            status = PlaybackStatus(opened = true, durationMs = 2_634_000, positionMs = 1_260_000, playing = true),
                            tracks = emptyList(),
                            fullscreen = false,
                            ignoreClicks = false,
                            miniPlayerAvailable = true,
                            alwaysOnTopAvailable = true,
                            fromTelegram = true,
                            savable = true,
                            watchedLabel = WatchedWords.markLabel(false),
                            sleepTimer = null,
                            onOpenMenu = {},
                            onClose = {},
                            onAction = {},
                        )
                    }
                }
            }
        }
        shot("shortcuts", sidebar = false) {
            PlayerTheme {
                Box(Modifier.fillMaxSize().background(Color(0xFF203040))) { ShortcutSheet(onClose = {}) }
            }
        }
        shot("update") {
            Box(Modifier.fillMaxSize()) {
                Grid()
                UpdatePopup(
                    release = release, skipped = false, installed = "1.22.1", kind = InstallKind.AppImage, canUpdate = true,
                    progress = UpdateProgress.Idle, onUpdate = {}, onOpenPage = {}, onLater = {}, onSkip = {}, onRestart = {}, onClose = {},
                )
            }
        }
        shot("dialog") {
            Box(Modifier.fillMaxSize()) {
                SettingsPage(shell, "2.0.0")
                SignOutDialog(shell, onDismiss = {})
            }
        }
    }

    @Composable
    private fun Grid() {
        Column(Modifier.fillMaxSize()) {
            PageHeader("Weekend Clips", L.browseVideosCount(items.size))
            LazyVerticalGrid(
                columns = GridCells.Adaptive(shell.posterWidth),
                contentPadding = PaddingValues(24.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                items(items, key = { it.id }) { MediaTile(shell, it, "Weekend Clips") }
            }
        }
    }

    private fun render(sidebar: Boolean, page: @Composable () -> Unit): ByteArray =
        ImageComposeScene(WIDTH, HEIGHT, Density(1f)) {
            TmMaterialTheme(dark = true) {
                ProvideStrings {
                    Surface(Modifier.fillMaxSize(), color = Tone.background) {
                        if (sidebar) {
                            Row(Modifier.fillMaxSize()) {
                                Sidebar(shell, null, emptyList())
                                VerticalDivider(color = Tone.outline)
                                Box(Modifier.weight(1f).fillMaxHeight()) { page() }
                            }
                        } else {
                            page()
                        }
                    }
                }
            }
        }.use { scene ->
            scene.render(0)
            Thread.sleep(400)
            scene.render(500_000_000)
            scene.render(1_000_000_000).encodeToData(EncodedImageFormat.PNG)!!.bytes
        }

    private fun save(name: String, png: ByteArray) {
        assertTrue(png.size > 1000)
        val out = System.getenv("TMPLAYER_SHOTS")?.takeIf { it.isNotBlank() } ?: return
        File(out).apply { mkdirs() }.resolve(name).writeBytes(png)
    }

    /** A tiny JPEG in [argb], standing in for Telegram's minithumbnail. */
    private fun jpeg(argb: Int): ByteArray {
        val surface = SkSurface.makeRasterN32Premul(40, 24)
        surface.canvas.drawRect(Rect.makeWH(40f, 24f), Paint().apply { color = argb or 0xFF000000.toInt() })
        return surface.makeImageSnapshot().encodeToData(EncodedImageFormat.JPEG, 80)!!.bytes
    }

    private companion object {
        const val WIDTH = 1280
        const val HEIGHT = 800
    }
}
