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
import com.tmplayer.data.AuthState
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
import com.tmplayer.desktop.player.MenuAt
import com.tmplayer.desktop.player.MenuPage
import com.tmplayer.desktop.player.PlaybackStatus
import com.tmplayer.desktop.player.PlayerMenu
import com.tmplayer.desktop.player.PlayerTheme
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
 * The screens the one-theme audit compares across the television, the phone and the desktop:
 * the chat list with its sidebar, a chat's grid, a tile's menu, the sign out prompt, Settings,
 * the player's menu, sign in, and the support codes and card. Each is drawn in the dark and the
 * light theme. With TMPLAYER_SHOTS=<dir> the frames are written there as `desktop-<name>-<theme>.png`.
 */
class ThemeAuditRenderTest {

    private val settings = SettingsStore(
        SettingsStore.openDataStore(Files.createTempDirectory("tm-audit").resolve(SettingsStore.FILE_NAME).toFile()),
    )
    private val dir = Files.createTempDirectory("tm-audit-extras").toFile()
    private val shell = ShellState(
        settings,
        object : DownloadRunner {
            override fun download(request: DownloadRequest) = Unit
            override fun cancel(fileId: Int) = Unit
            override fun pause(fileId: Int) = Unit
        },
        WatchedStore(WatchedStore.openDataStore(Files.createTempDirectory("tm-audit-watched").resolve(WatchedStore.FILE_NAME).toFile())),
        services = {
            DesktopExtras(
                prefs = DesktopPrefs(dir.resolve("desktop.properties")),
                updates = UpdateScheduler(settings.updatePrefs, check = { _, _ -> false }, onSkip = {}),
                watchCache = DesktopWatchCache(settings, { dir.resolve("cache") }),
            )
        },
    )

    private val chats = listOf(
        ChatSummary(1, "Weekend Clips", jpeg(0xFF2AABEE.toInt()), 0, ChatKind.Group),
        ChatSummary(2, "Home Projects", jpeg(0xFFF5A524.toInt()), 0, ChatKind.Channel),
        ChatSummary(3, "Recipe Notes", jpeg(0xFF46A758.toInt()), 0, ChatKind.Group),
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

    @Test
    fun auditScreens() {
        for (dark in listOf(true, false)) {
            val theme = if (dark) "dark" else "light"
            fun shot(name: String, sidebar: Boolean = true, page: @Composable () -> Unit) =
                save("desktop-$name-$theme.png", render(dark, sidebar, page))

            shot("chats") { ChatsList() }
            shot("grid") { Grid() }
            shot("menu") {
                Box(Modifier.fillMaxSize()) {
                    Grid()
                    Box(Modifier.padding(start = 200.dp, top = 220.dp)) {
                        TileMenu(shell, items[0], "Weekend Clips", expanded = true, onDismiss = {}, extra = null, watchedToggle = false)
                    }
                }
            }
            shot("confirm") {
                Box(Modifier.fillMaxSize()) {
                    SettingsPage(shell, "2.0.0")
                    ConfirmDialog(
                        title = "Sign out of Telegram?",
                        message = "You'll be signed out and taken back to the sign-in screen. The cache, your " +
                            "favourites, your watched list and everything you were part-way through go with it.",
                        confirmLabel = "Sign out",
                        onConfirm = {},
                        onDismiss = {},
                    )
                }
            }
            // The real sign out prompt on the shared ConfirmDialog, its tick box in the extra slot.
            shot("signout") {
                Box(Modifier.fillMaxSize()) {
                    SettingsPage(shell, "2.0.0")
                    SignOutDialog(shell, onDismiss = {})
                }
            }
            // One of the prompts the CP41 sweep added, with a detail line.
            shot("confirm-remove-after-watching") {
                Box(Modifier.fillMaxSize()) {
                    SettingsPage(shell, "2.0.0")
                    ConfirmDialog(
                        title = com.tmplayer.i18n.L.confirmRemoveAfterWatchingTitle,
                        message = com.tmplayer.i18n.L.confirmRemoveAfterWatchingMessage,
                        detail = com.tmplayer.i18n.L.confirmRemoveAfterWatchingDetail,
                        confirmLabel = com.tmplayer.i18n.L.confirmTurnOn,
                        onConfirm = {},
                        onDismiss = {},
                    )
                }
            }
            shot("settings") { SettingsPage(shell, "2.0.0") }
            shot("support") {
                Box(Modifier.fillMaxSize()) {
                    SettingsPage(shell, "2.0.0")
                    SupportPopup(from = "settings", onClose = {})
                }
            }
            shot("card") {
                Box(Modifier.fillMaxSize()) {
                    ChatsList()
                    SupportCard(rung = 1, counters = com.tmplayer.data.SupportReminder.Counters(completedWatches = 7, watchTimeMs = 11L * 60 * 60 * 1000), onSupport = {}, onStar = {}, onShare = {}, onLater = {}, onAlready = {}, modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp))
                }
            }
            shot("signin", sidebar = false) { SignInScreen(AuthState.Phone()) }
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
                                watchedLabel = "Mark as watched",
                                sleepTimer = null,
                                onOpenMenu = {},
                                onClose = {},
                                onAction = {},
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ChatsList() {
        Column(Modifier.fillMaxSize()) {
            PageHeader("Recent", "Chats with something new")
            chats.forEach { ChatRow(it, favourite = it.id == 2L, onOpen = {}, onStar = {}) }
        }
    }

    @Composable
    private fun Grid() {
        Column(Modifier.fillMaxSize()) {
            PageHeader("Weekend Clips", "6 videos")
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

    private fun render(dark: Boolean, sidebar: Boolean, page: @Composable () -> Unit): ByteArray =
        ImageComposeScene(WIDTH, HEIGHT, Density(1f)) {
            TmMaterialTheme(dark = dark) {
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
