package com.tmplayer.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.tmplayer.data.ChatKind
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.DownloadRequest
import com.tmplayer.data.DownloadRunner
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.UpdateScheduler
import com.tmplayer.data.WatchedStore
import com.tmplayer.desktop.DesktopPrefs
import com.tmplayer.desktop.DesktopWatchCache
import com.tmplayer.ui.about.About
import com.tmplayer.ui.auth.QrCode
import com.tmplayer.ui.theme.TmMaterialTheme
import com.tmplayer.ui.theme.Tone
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * "Support TMPlayer" on the desktop: the Settings row and its QR codes, the About row, and the
 * Buy Me a Coffee window over the chat list. With TMPLAYER_SHOTS=<dir> the frames are written there as PNGs.
 */
class SupportRenderTest {

    private val settings = SettingsStore(
        SettingsStore.openDataStore(Files.createTempDirectory("tm-support-render").resolve(SettingsStore.FILE_NAME).toFile()),
    )
    private val dir = Files.createTempDirectory("tm-support-extras").toFile()
    private val shell = ShellState(
        settings,
        object : DownloadRunner {
            override fun download(request: DownloadRequest) = Unit
            override fun cancel(fileId: Int) = Unit
            override fun pause(fileId: Int) = Unit
        },
        WatchedStore(WatchedStore.openDataStore(Files.createTempDirectory("tm-support-watched").resolve(WatchedStore.FILE_NAME).toFile())),
        services = {
            DesktopExtras(
                prefs = DesktopPrefs(dir.resolve("desktop.properties")),
                updates = UpdateScheduler(settings.updatePrefs, check = { _, _ -> false }, onSkip = {}),
                watchCache = DesktopWatchCache(settings, { dir.resolve("cache") }),
            )
        },
    )

    @Test
    fun `each support QR code reads back as its own link`() {
        assertEquals("https://github.com/sponsors/dracu-lah", About.SPONSORS)
        assertEquals("https://buymeacoffee.com/nevil.dev", About.COFFEE)
        for (link in About.supportLinks("card1") + About.supportLinks("settings") + About.supportLinks("about")) {
            val bitmap = requireNotNull(QrCode.render(link.url, 400))
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.readPixels(pixels)
            val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
            assertEquals(link.url, QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source))).text)
        }
    }

    @Test
    fun settingsRowAndCodes() {
        save("support-settings.png", render(dark = true, height = 3200) { SettingsPage(shell, "2.0.0") })
        save("support-codes.png", render(dark = true) { SupportPopup(onClose = {}) })
        save("support-codes-light.png", render(dark = false) { SupportPopup(onClose = {}) })
    }

    @Test
    fun aboutRow() {
        save("support-about.png", render(dark = true, height = 1400) { AboutPage("2.0.0", onBack = {}) })
        save("support-about-thanks.png", render(dark = true, height = 1400) { AboutPage("2.0.0", supporter = true, onBack = {}) })
    }

    @Test
    fun coffeeWindow() {
        val chats = listOf(
            ChatSummary(id = 1, title = "Saved Messages", miniThumbnail = null, photoFileId = 0, kind = ChatKind.Saved),
            ChatSummary(id = 2, title = "Video Club", miniThumbnail = null, photoFileId = 0, kind = ChatKind.Channel, unreadCount = 12),
            ChatSummary(id = 3, title = "Weekend series", miniThumbnail = null, photoFileId = 0, kind = ChatKind.Group),
        )
        val counters = com.tmplayer.data.SupportReminder.Counters(completedWatches = 7, watchTimeMs = 11L * 60 * 60 * 1000)
        for (dark in listOf(true, false)) {
            val name = "support-coffee" + if (dark) "" else "-light"
            save(
                "$name.png",
                render(dark = dark) {
                    Box(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize()) {
                            PageHeader("Chats", "Everything, newest first")
                            chats.forEach { ChatRow(it, favourite = false, onOpen = {}, onStar = {}) }
                        }
                        CoffeeDialog(counters = counters, asking = true, onLater = {}, onNever = {})
                    }
                },
            )
        }
    }

    @Test
    fun errorWithSendLogs() {
        for (dark in listOf(true, false)) {
            save(
                "error-send-logs" + (if (dark) "" else "-light") + ".png",
                render(dark = dark) {
                    StateBox<Unit>(com.tmplayer.ui.components.UiState.Error("Telegram did not answer. Check the connection and try again."), onRetry = {}) {}
                },
            )
        }
    }

    @Test
    fun `the coffee code reads back as the Buy Me a Coffee page`() {
        val bitmap = requireNotNull(QrCode.render(About.COFFEE, 400))
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.readPixels(pixels)
        val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
        assertEquals("https://buymeacoffee.com/nevil.dev", QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source))).text)
    }

    private fun render(dark: Boolean, height: Int = HEIGHT, page: @Composable () -> Unit): ByteArray =
        ImageComposeScene(WIDTH, height, Density(1f)) {
            TmMaterialTheme(dark = dark) {
                Surface(Modifier.fillMaxSize(), color = Tone.background) { page() }
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

    private companion object {
        const val WIDTH = 1280
        const val HEIGHT = 800
    }
}
