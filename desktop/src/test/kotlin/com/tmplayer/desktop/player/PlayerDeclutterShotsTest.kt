package com.tmplayer.desktop.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import com.tmplayer.data.MediaItem
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The declutter of CP38 drawn: the bar without the ten second buttons, the clock and the shape
 * button, the reduced main menu, its two new pages, the speed list the pill opens, and the grouped
 * shortcut sheet. With TMPLAYER_SHOTS=<dir> the frames are written there as `player-<name>.png`.
 */
class PlayerDeclutterShotsTest {

    private val status = PlaybackStatus(
        opened = true, durationMs = 2_634_000, positionMs = 1_260_000, bufferedMs = 1_700_000, playing = true, speed = 1.25f,
    )

    private fun item(id: Long, title: String) = MediaItem(
        chatId = 1, messageId = id, fileId = id.toInt(), title = title, sizeBytes = 1, durationSec = 2634,
        mimeType = "video/x-matroska", thumbnailFileId = 0, miniThumbnail = null, date = 0,
    )

    @Test
    fun `back goes up one page, and a button's own menu has no back line`() {
        assertNull(MenuAt(MenuPage.Main, MenuAt.Anchor.Overflow).parentPage())
        assertNull(MenuAt(MenuPage.Speed, MenuAt.Anchor.Speed).parentPage())
        assertNull(MenuAt(MenuPage.Subtitles, MenuAt.Anchor.Subtitles).parentPage())
        assertEquals(MenuPage.Main, MenuAt(MenuPage.Options, MenuAt.Anchor.Overflow).parentPage())
        assertEquals(MenuPage.Options, MenuAt(MenuPage.Sleep, MenuAt.Anchor.Overflow).parentPage())
        assertEquals(MenuPage.Options, MenuAt(MenuPage.Shape, MenuAt.Anchor.Cursor).parentPage())
        assertEquals(MenuPage.Main, MenuAt(MenuPage.SubtitleStyle, MenuAt.Anchor.Overflow).parentPage())
        assertEquals(MenuPage.Subtitles, MenuAt(MenuPage.SubtitleStyle, MenuAt.Anchor.Subtitles).parentPage())
    }

    @Test
    fun `the shortcut groups hold every row once`() {
        val groups = PlayerKeys.sheetGroups()
        assertEquals(4, groups.size)
        val rows = PlayerKeys.sheet()
        assertEquals(rows.size, groups.sumOf { it.second.size })
        assertEquals(rows.size, rows.map { it.first }.toSet().size)
    }

    @Test
    fun shots() {
        shot("bar") { overlay(null) }
        shot("menu-main") { overlay(MenuAt(MenuPage.Main, MenuAt.Anchor.Overflow)) }
        shot("menu-options") { overlay(MenuAt(MenuPage.Options, MenuAt.Anchor.Overflow)) }
        shot("menu-subtitle-style") { overlay(MenuAt(MenuPage.SubtitleStyle, MenuAt.Anchor.Overflow)) }
        shot("menu-speed") { overlay(MenuAt(MenuPage.Speed, MenuAt.Anchor.Speed)) }
        shot("shortcuts") { Box(Modifier.fillMaxSize()) { ShortcutSheet(onClose = {}) } }
        shot("shortcuts-narrow", width = 800) { Box(Modifier.fillMaxSize()) { ShortcutSheet(onClose = {}) } }
    }

    @Composable
    private fun overlay(menu: MenuAt?) {
        Box(Modifier.fillMaxSize().background(Color(0xFF203040))) {
            PlayerOverlay(
                visible = true,
                status = status,
                title = "Night Train",
                subtitle = "S01E04  ·  The Lighthouse",
                episodes = Episodes(previous = item(3, "Three"), next = item(5, "Five")),
                tracks = emptyList(),
                downloaded = null,
                showRemaining = false,
                fullscreen = false,
                menu = menu,
                ignoreClicks = false,
                miniPlayerAvailable = true,
                alwaysOnTopAvailable = true,
                fromTelegram = true,
                watchedLabel = "Mark as watched",
                onHoverControls = {},
                onBack = {},
                onTogglePlay = {},
                onSeekBy = {},
                onSeekTo = {},
                onEpisode = {},
                onToggleRemaining = {},
                onVolume = {},
                onToggleMute = {},
                onToggleFullscreen = {},
                onMiniPlayer = {},
                onOpenMenu = {},
                onCloseMenu = {},
                onMenuAction = {},
            )
        }
    }

    private fun shot(name: String, width: Int = 1280, content: @Composable () -> Unit) {
        ImageComposeScene(width, 720, Density(1f)) {
            PlayerTheme { content() }
        }.use { scene ->
            scene.render(0)
            Thread.sleep(300)
            scene.render(500_000_000)
            Thread.sleep(200)
            val png = scene.render(1_000_000_000).encodeToData(EncodedImageFormat.PNG)!!.bytes
            assertTrue(png.size > 1000)
            System.getenv("TMPLAYER_SHOTS")?.takeIf { it.isNotBlank() }?.let {
                File(it).apply { mkdirs() }.resolve("player-$name.png").writeBytes(png)
            }
        }
    }
}
