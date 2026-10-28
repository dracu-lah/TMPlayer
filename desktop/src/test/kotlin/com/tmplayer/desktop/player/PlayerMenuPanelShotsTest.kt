package com.tmplayer.desktop.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.use
import com.tmplayer.data.MediaItem
import com.tmplayer.player.Stereo3d
import com.tmplayer.player.VideoScale
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image as SkImage
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * HX16's side panel drawn: More options and its Picture shape page over a dark frame and a white
 * one (the player is dark in both app themes, so the frame behind is what changes), right to
 * left, and in a mini player's small window. The frames land in build/panel-shots for a look.
 */
class PlayerMenuPanelShotsTest {

    private val status = PlaybackStatus(
        opened = true, durationMs = 2_634_000, positionMs = 1_260_000, bufferedMs = 1_700_000, playing = true, speed = 1.25f,
        scale = VideoScale.Ratio16x9, stereo = Stereo3d(Stereo3d.Layout.SideBySide),
    )

    private val tracks = listOf(
        MediaTrack(id = 1, type = TrackType.Audio, title = null, language = "en", codec = "eac3", channels = 6, selected = true, isDefault = true, external = false),
        MediaTrack(id = 2, type = TrackType.Subtitle, title = null, language = "en", codec = null, channels = null, selected = true, isDefault = true, external = false),
    )

    private fun item(id: Long, title: String) = MediaItem(
        chatId = 1, messageId = id, fileId = id.toInt(), title = title, sizeBytes = 1, durationSec = 2634,
        mimeType = "video/x-matroska", thumbnailFileId = 0, miniThumbnail = null, date = 0,
    )

    @Test
    fun shots() {
        for ((name, frame) in listOf("dark" to Color(0xFF203040), "light" to Color(0xFFF2F2EE))) {
            shot("main-$name") { overlay(frame, MenuAt(MenuPage.Main, MenuAt.Anchor.Overflow)) }
            shot("shape-$name") { overlay(frame, MenuAt(MenuPage.Shape, MenuAt.Anchor.Overflow)) }
        }
        shot("main-rtl") {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                overlay(Color(0xFF203040), MenuAt(MenuPage.Main, MenuAt.Anchor.Cursor))
            }
        }
        shot("shape-mini", width = 480, height = 270) { overlay(Color(0xFF203040), MenuAt(MenuPage.Shape, MenuAt.Anchor.Cursor)) }
    }

    /** The panel sits at the end edge: its column is the menu's tone, the far side the frame's. */
    @Test
    fun `the panel is docked to the end edge`() {
        fun columns(rtl: Boolean): Pair<Int, Int> {
            val png = shot(if (rtl) "probe-rtl" else "probe") {
                CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                    overlay(Color.White, MenuAt(MenuPage.Main, MenuAt.Anchor.Overflow), visible = false)
                }
            }
            val bitmap = org.jetbrains.skia.Bitmap.makeFromImage(SkImage.makeFromEncoded(png))
            fun luma(x: Int) = bitmap.getColor(x, 400).let { (it shr 16 and 0xFF) * 0.3 + (it shr 8 and 0xFF) * 0.59 + (it and 0xFF) * 0.11 }.toInt()
            return luma(100) to luma(1180)
        }
        val (ltrLeft, ltrRight) = columns(rtl = false)
        assertTrue("left should be the white frame: $ltrLeft", ltrLeft > 200)
        assertTrue("right should be the panel: $ltrRight", ltrRight < 100)
        val (rtlLeft, rtlRight) = columns(rtl = true)
        assertTrue("left should be the panel in RTL: $rtlLeft", rtlLeft < 100)
        assertTrue("right should be the white frame in RTL: $rtlRight", rtlRight > 200)
    }

    @Composable
    private fun overlay(frame: Color, menu: MenuAt, visible: Boolean = true) {
        Box(Modifier.fillMaxSize().background(frame)) {
            PlayerOverlay(
                visible = visible,
                status = status,
                title = "Night Train",
                subtitle = "S01E04  ·  The Lighthouse",
                episodes = Episodes(previous = item(3, "Three"), next = item(5, "Five")),
                tracks = tracks,
                downloaded = null,
                showRemaining = false,
                fullscreen = false,
                menu = menu,
                ignoreClicks = false,
                miniPlayerAvailable = true,
                alwaysOnTopAvailable = true,
                fromTelegram = true,
                watchedLabel = "Mark as watched",
                sleepTimer = "25 minutes left",
                bookmarks = emptyList(),
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

    private fun shot(name: String, width: Int = 1280, height: Int = 720, content: @Composable () -> Unit): ByteArray =
        ImageComposeScene(width, height, Density(1f)) {
            PlayerTheme { content() }
        }.use { scene ->
            scene.render(0)
            Thread.sleep(300)
            scene.render(500_000_000)
            Thread.sleep(200)
            val png = scene.render(1_000_000_000).encodeToData(EncodedImageFormat.PNG)!!.bytes
            assertTrue(png.size > 1000)
            File(OUT).apply { mkdirs() }.resolve("player-panel-$name.png").writeBytes(png)
            png
        }

    private companion object {
        const val OUT = "build/panel-shots"
    }
}
