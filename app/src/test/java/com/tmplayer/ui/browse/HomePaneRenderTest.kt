package com.tmplayer.ui.browse

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.unit.dp
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.ChatKind
import com.tmplayer.data.FormFactor
import com.tmplayer.data.HomeRow
import com.tmplayer.data.HomeRows
import com.tmplayer.data.MediaItem
import com.tmplayer.data.ResumeRecord
import com.tmplayer.ui.theme.TMPlayerTheme
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Home's rows on a television and a phone, composed for real and drawn to a PNG under
 * `build/device-test/review/` for a look: every tile 16:9, the See all tile only on rows that
 * leave something out, its mosaic, and the placeholder row the same height as a loaded one.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomePaneRenderTest {

    private var nextId = 1L

    @After
    fun tearDown() {
        FormFactor.override(false)
    }

    /** A gradient picture of [w] by [h], as Telegram's inline preview would carry it. */
    private fun picture(w: Int, h: Int, from: Int, to: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply { shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), from, to, Shader.TileMode.CLAMP) }
        Canvas(bitmap).drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        // A bar across the top third, so a crop and a letterbox are easy to tell apart.
        Canvas(bitmap).drawRect(0f, h * 0.1f, w.toFloat(), h * 0.2f, Paint().apply { color = Color.WHITE })
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }

    private val palette = listOf(
        Color.rgb(0x2E, 0x5E, 0xAA) to Color.rgb(0xF2, 0x8C, 0x28),
        Color.rgb(0x7B, 0x1F, 0xA2) to Color.rgb(0x26, 0xA6, 0x9A),
        Color.rgb(0xC6, 0x28, 0x28) to Color.rgb(0xFF, 0xD5, 0x4F),
        Color.rgb(0x1B, 0x5E, 0x20) to Color.rgb(0x81, 0xD4, 0xFA),
    )

    private fun video(chatId: Long, name: String, portrait: Boolean = false): MediaItem {
        val id = nextId++
        val (from, to) = palette[(id % palette.size).toInt()]
        return MediaItem(
            chatId = chatId,
            messageId = id,
            fileId = 0,
            title = name,
            sizeBytes = 1_400_000_000,
            durationSec = 5_400,
            mimeType = "video/mp4",
            thumbnailFileId = 0,
            miniThumbnail = if (portrait) picture(36, 64, from, to) else picture(64, 36, from, to),
            date = id.toInt(),
            fileName = "$name.mkv",
        )
    }

    private fun resume(item: MediaItem) = ResumeRecord(
        chatId = item.chatId,
        messageId = item.messageId,
        fileId = 0,
        title = item.title,
        chatTitle = "Films",
        sizeBytes = item.sizeBytes,
        durationSec = item.durationSec,
        positionMs = 1_800_000,
        durationMs = 5_400_000,
        updatedAt = item.messageId,
    )

    private fun render(tv: Boolean, out: String) {
        FormFactor.override(tv)
        val films = (1..14).map { video(1, "Film number $it", portrait = it == 12) }
        val shorts = (1..3).map { video(2, "Short clip $it") }
        val history = (1..13).map { resume(video(3, "Episode $it")) }
        val art = history.associate { com.tmplayer.data.SettingsStore.progressKey(it.chatId, it.messageId) to video(3, it.title) }
        val rows: List<HomeRow> = HomeRows.build(
            continueWatching = history,
            favourites = listOf(
                ChatSummary(1, "Films", null, 0, ChatKind.Channel),
                ChatSummary(2, "Shorts", null, 0, ChatKind.Channel),
                ChatSummary(4, "Still loading", null, 0, ChatKind.Channel),
            ),
            loaded = mapOf(1L to films, 2L to shorts),
            recent = emptyList(),
        )

        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            TMPlayerTheme {
                HomePane(
                    rows = rows,
                    watch = SeriesWatch(point = { null }, finished = { false }),
                    art = art,
                    chatTitle = { "Films" },
                    start = if (tv) 48.dp else 16.dp,
                    end = if (tv) 48.dp else 16.dp,
                    bottom = 24.dp,
                    onRowShown = {},
                    onArtWanted = {},
                    onResume = {},
                    onPlay = { _, _ -> },
                    onHoldMedia = { _, _ -> },
                    onSeeContinue = {},
                    onOpenChat = {},
                )
            }
        }
        repeat(20) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100)) }
        val root = activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val file = File("../build/device-test/review/$out").absoluteFile
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(file.length() > 0)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w2600dp-h1000dp-land-television-mdpi")
    fun television() = render(tv = true, out = "home-tv.png")

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-xhdpi")
    fun phone() = render(tv = false, out = "home-phone.png")
}
