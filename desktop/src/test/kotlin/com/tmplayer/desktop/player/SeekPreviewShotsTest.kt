package com.tmplayer.desktop.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import com.tmplayer.data.MediaItem
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Paint
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The timebar's hover (HX3) drawn at both edges of the bar, narrow and wide, over a dark and a
 * light picture, with the video's own frame, with nothing to show but the time, and with the
 * scrub pictures off. The frames land in build/seek-preview-shots for a look.
 */
@OptIn(ExperimentalComposeUiApi::class)
class SeekPreviewShotsTest {

    private val status = PlaybackStatus(
        opened = true, durationMs = 2_634_000, positionMs = 1_260_000, bufferedMs = 1_700_000, playing = true,
    )

    private fun item(id: Long, title: String) = MediaItem(
        chatId = 1, messageId = id, fileId = id.toInt(), title = title, sizeBytes = 1, durationSec = 2634,
        mimeType = "video/x-matroska", thumbnailFileId = 0, miniThumbnail = null, date = 0,
    )

    /** A 320 by 180 stand-in for a decoded frame, a coloured field with a light diagonal. */
    private fun frame(): ImageBitmap {
        val bitmap = Bitmap().apply { allocPixels(ImageInfo.makeN32(320, 180, ColorAlphaType.PREMUL)) }
        Canvas(bitmap).apply {
            drawPaint(Paint().apply { color = 0xFF2E6F95.toInt() })
            drawLine(0f, 180f, 320f, 0f, Paint().apply { color = 0xFFF2D16B.toInt(); strokeWidth = 14f })
        }
        return bitmap.asComposeImageBitmap()
    }

    private fun shot(
        name: String,
        width: Int,
        hoverFraction: Float,
        backdrop: Color,
        frames: FrameSource? = null,
        board: DesktopStoryboard? = null,
        settleMs: Long = 300,
    ) {
        var before = ByteArray(0)
        val png = ImageComposeScene(width, 540, Density(1f)) {
            PlayerTheme {
                CompositionLocalProvider(LocalFrameSource provides frames, LocalStoryboard provides board) {
                    Box(Modifier.fillMaxSize().background(backdrop)) {
                        PlayerOverlay(
                            visible = true,
                            status = status,
                            title = "Night Train",
                            subtitle = "S01E04  ·  The Lighthouse",
                            episodes = Episodes(next = item(5, "Five")),
                            tracks = emptyList(),
                            downloaded = null,
                            showRemaining = false,
                            fullscreen = false,
                            menu = null,
                            ignoreClicks = false,
                            miniPlayerAvailable = true,
                            alwaysOnTopAvailable = true,
                            fromTelegram = true,
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
            }
        }.use { scene ->
            scene.render(0)
            Thread.sleep(300)
            before = scene.render(500_000_000).encodeToData(EncodedImageFormat.PNG)!!.bytes
            val y = BAR_Y
            val barLeft = 24f
            val barRight = width - 24f
            val x = barLeft + (barRight - barLeft) * hoverFraction.coerceIn(0.002f, 0.998f)
            scene.sendPointerEvent(PointerEventType.Enter, Offset(x, y))
            scene.sendPointerEvent(PointerEventType.Move, Offset(x, y))
            scene.render(600_000_000)
            Thread.sleep(500)
            scene.render(900_000_000)
            Thread.sleep(settleMs)
            // The picture fades in from the frame it lands on: drawn again once the fade is over.
            scene.render(1_200_000_000)
            scene.render(1_600_000_000).encodeToData(EncodedImageFormat.PNG)!!.bytes
        }
        assertTrue(png.size > 1000)
        // What the hover added above the times row: there is a card, and it is whole inside the
        // bar's two ends (the bar runs from 24 to the width less 24), never cut by the window.
        val box = addedAbove(before, png, rows = 420)
        assertTrue("$name: nothing was drawn over the bar", box != null)
        assertTrue("$name: card cut at the left, ${box!![0]}", box[0] >= 24 - 2)
        assertTrue("$name: card cut at the right, ${box[2]} of $width", box[2] <= width - 24 + 2)
        File("build/seek-preview-shots").apply { mkdirs() }.resolve("$name.png").writeBytes(png)
    }

    /** The box (left, top, right, bottom) of the pixels that differ between two frames above row [rows]. */
    private fun addedAbove(a: ByteArray, b: ByteArray, rows: Int): IntArray? {
        fun pixels(png: ByteArray): Pair<org.jetbrains.skia.Pixmap, Bitmap> {
            val image = org.jetbrains.skia.Image.makeFromEncoded(png)
            val bitmap = Bitmap.makeFromImage(image)
            return bitmap.peekPixels()!! to bitmap
        }
        val (pa, ba) = pixels(a)
        val (pb, bb) = pixels(b)
        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = -1
        var bottom = -1
        for (y in 0 until minOf(rows, ba.height)) {
            for (x in 0 until ba.width) {
                if (pa.getColor(x, y) != pb.getColor(x, y)) {
                    left = minOf(left, x); right = maxOf(right, x); top = minOf(top, y); bottom = maxOf(bottom, y)
                }
            }
        }
        ba.close(); bb.close()
        return if (right < 0) null else intArrayOf(left, top, right, bottom)
    }

    @Test
    fun hoverShots() {
        val dark = Color(0xFF14181D)
        val light = Color(0xFFE8E4DA)
        for ((w, label) in listOf(480 to "narrow", 1280 to "wide")) {
            for ((edge, f) in listOf("left" to 0.0f, "mid" to 0.5f, "right" to 1.0f)) {
                shot("frame-$label-$edge-dark", w, f, dark, FrameSource { frame() })
                shot("frame-$label-$edge-light", w, f, light, FrameSource { frame() })
                shot("fallback-$label-$edge-dark", w, f, dark, FrameSource { null }, settleMs = 1_800)
                shot("waiting-$label-$edge-dark", w, f, dark, FrameSource { kotlinx.coroutines.delay(10_000); null })
                shot("time-only-$label-$edge-dark", w, f, dark, null)
                shot("time-only-$label-$edge-light", w, f, light, null)
            }
        }
    }

    /** A storyboard as Telegram sends one: a sprite of tiles and a WebVTT map, on the disk. */
    private fun storyboard(): DesktopStoryboard {
        val dir = File("build/seek-preview-shots/board").apply { mkdirs() }
        val colours = intArrayOf(0xFFB5483A.toInt(), 0xFF3A8E5B.toInt(), 0xFF3A62B5.toInt(), 0xFF8E3AB5.toInt())
        val bitmap = Bitmap().apply { allocPixels(ImageInfo.makeN32(320, 180, ColorAlphaType.PREMUL)) }
        Canvas(bitmap).apply {
            for (i in 0 until 4) {
                drawRect(
                    org.jetbrains.skia.Rect.makeXYWH((i % 2) * 160f, (i / 2) * 90f, 160f, 90f),
                    Paint().apply { color = colours[i] },
                )
            }
        }
        val sprite = dir.resolve("sprite.jpg")
        sprite.writeBytes(org.jetbrains.skia.Image.makeFromBitmap(bitmap).encodeToData(EncodedImageFormat.JPEG, 90)!!.bytes)
        val map = dir.resolve("map.vtt")
        map.writeText(
            "WEBVTT\n\n" + (0 until 4).joinToString("\n\n") { i ->
                val from = i * 659
                "00:%02d:%02d.000 --> 00:%02d:%02d.000\nsprite.jpg#xywh=%d,%d,160,90".format(
                    from / 60, from % 60, (from + 659) / 60, (from + 659) % 60, (i % 2) * 160, (i / 2) * 90,
                )
            },
        )
        val files = com.tmplayer.data.StoryboardFiles(imageFileId = 1, mapFileId = 2, tileWidth = 160, tileHeight = 90)
        return DesktopStoryboard(files, durationMs = { 2_634_000L }, fetch = { id -> (if (id == 1) sprite else map).path })
    }

    @Test
    fun storyboardShots() {
        val dark = Color(0xFF14181D)
        val light = Color(0xFFE8E4DA)
        for ((w, label) in listOf(480 to "narrow", 1280 to "wide")) {
            for ((edge, f) in listOf("left" to 0.0f, "mid" to 0.6f, "right" to 1.0f)) {
                shot("board-$label-$edge-dark", w, f, dark, board = storyboard())
                shot("board-$label-$edge-light", w, f, light, board = storyboard())
            }
        }
    }

    private companion object {
        const val BAR_Y = 462f
    }
}
