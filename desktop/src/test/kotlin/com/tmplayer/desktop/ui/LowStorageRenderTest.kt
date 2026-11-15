package com.tmplayer.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import com.tmplayer.data.DownloadRequest
import com.tmplayer.data.DownloadRunner
import com.tmplayer.data.MediaItem
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.UpdateScheduler
import com.tmplayer.data.WatchedStore
import com.tmplayer.desktop.DesktopPrefs
import com.tmplayer.desktop.DesktopWatchCache
import com.tmplayer.i18n.L
import com.tmplayer.i18n.Languages
import com.tmplayer.i18n.Translator
import com.tmplayer.ui.theme.TmMaterialTheme
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * "While streaming: Save space" on the desktop (CP66): the question [LowStorageQuestionHost] asks when a video
 * or a download does not fit, answered both ways, and the two tabs in Settings with the line under them.
 * With TMPLAYER_SHOTS=<dir> the frames (dark, light, Arabic) are written there as PNGs.
 */
@OptIn(ExperimentalTestApi::class, kotlinx.coroutines.DelicateCoroutinesApi::class)
class LowStorageRenderTest {

    private val settings = SettingsStore(
        SettingsStore.openDataStore(Files.createTempDirectory("tm-low-storage").resolve(SettingsStore.FILE_NAME).toFile()),
    )
    private val dir = Files.createTempDirectory("tm-low-storage-extras").toFile()
    private val shell = ShellState(
        settings,
        object : DownloadRunner {
            override fun download(request: DownloadRequest) = Unit
            override fun cancel(fileId: Int) = Unit
            override fun pause(fileId: Int) = Unit
        },
        WatchedStore(WatchedStore.openDataStore(Files.createTempDirectory("tm-low-storage-watched").resolve(WatchedStore.FILE_NAME).toFile())),
        services = {
            DesktopExtras(
                prefs = DesktopPrefs(dir.resolve("desktop.properties")),
                updates = UpdateScheduler(settings.updatePrefs, check = { _, _ -> false }, onSkip = {}),
                watchCache = DesktopWatchCache(settings, { dir.resolve("cache") }),
            )
        },
    )

    private val film = MediaItem(
        chatId = 1, messageId = 7, fileId = 7, title = "Harbour Notes", sizeBytes = 2_700L * 1024 * 1024, durationSec = 7200,
        mimeType = "video/x-matroska", thumbnailFileId = 0, miniThumbnail = null, date = 1, fileName = "Harbour.Notes.2021.mkv",
    )

    private val playQuestion = L.mainStreamAskPlay
    private val keepQuestion = L.mainStreamAskKeep

    @Test
    fun `a video that does not fit is asked about, and Not now leaves the mode off`() = runComposeUiTest {
        setContent { TmMaterialTheme(dark = true) { Box(Modifier.size(1280.dp, 800.dp)) { LowStorageQuestionHost(shell) } } }
        val answer = GlobalScope.async { LowStorageQuestion.ask(film) }
        waitUntil { LowStorageQuestion.pending.value != null }
        waitForIdle()
        onNodeWithText(L.settingsStreamSwitchTitle).assertExists()
        onNodeWithText(playQuestion).assertExists()
        onNodeWithText(L.settingsStreamSaveDetail).assertExists()
        // Focus starts on the answer that does what the viewer pressed.
        onNodeWithText(L.mainLowStorageSwitchPlay).assertIsFocused()
        onNodeWithText(L.mainLowStorageNotNow).performClick()
        waitForIdle()
        assertFalse(runBlocking { answer.await() })
        assertNull(LowStorageQuestion.pending.value)
        assertFalse(runBlocking { settings.lowStorageModeNow() })
        onNodeWithText(playQuestion).assertDoesNotExist()
    }

    @Test
    fun `Switch and play turns the mode on and the player carries on`() = runComposeUiTest {
        setContent { TmMaterialTheme(dark = true) { Box(Modifier.size(1280.dp, 800.dp)) { LowStorageQuestionHost(shell) } } }
        val answer = GlobalScope.async { LowStorageQuestion.ask(film) }
        waitUntil { LowStorageQuestion.pending.value != null }
        waitForIdle()
        onNodeWithText(L.mainLowStorageSwitchPlay).performClick()
        waitUntil(timeoutMillis = 5_000) { answer.isCompleted }
        assertTrue(runBlocking { answer.await() })
        assertTrue(runBlocking { settings.lowStorageModeNow() })
        runBlocking { settings.setLowStorageMode(false) }
    }

    @Test
    fun `a download that does not fit is offered streaming instead`() = runComposeUiTest {
        setContent { TmMaterialTheme(dark = true) { Box(Modifier.size(1280.dp, 800.dp)) { LowStorageQuestionHost(shell) } } }
        LowStorageQuestion.offer(film)
        waitForIdle()
        onNodeWithText(keepQuestion).assertExists()
        onNodeWithText(playQuestion).assertDoesNotExist()
        onNodeWithText(L.mainLowStorageNotNow).performClick()
        waitForIdle()
        assertNull(LowStorageQuestion.pending.value)
        assertFalse(runBlocking { settings.lowStorageModeNow() })
    }

    @Test
    fun `While streaming is two tabs and the line under them says what the chosen one costs`() = runComposeUiTest {
        setContent { TmMaterialTheme(dark = true) { Box(Modifier.size(1280.dp, 3200.dp)) { SettingsPage(shell, "2.0.0", initialCategory = SettingsCategory.Storage) } } }
        waitForIdle()
        onNodeWithText(L.settingsStreamTitle).performScrollTo().assertExists()
        // Off is Keep video: fresh settings in this test store start off, as an upgraded install does.
        onNodeWithText(L.settingsStreamKeepDetail).assertExists()
        onNodeWithText(L.settingsStreamSaveDetail).assertDoesNotExist()
        // A kept download is never windowed, and the line says so under either tab.
        assertTrue(L.settingsStreamKeepDetail.contains("never trimmed"))
        assertTrue(L.settingsStreamSaveDetail.contains("never trimmed"))
        onNode(hasText(L.settingsStreamKeep) and hasClickAction()).assertIsSelected()
        onNode(hasText(L.settingsStreamSave) and hasClickAction()).assertIsNotSelected()
        onNode(hasText(L.settingsStreamSave) and hasClickAction()).performClick()
        waitUntil(timeoutMillis = 5_000) { runBlocking { settings.lowStorageModeNow() } }
        waitForIdle()
        onNodeWithText(L.settingsStreamSaveDetail).assertExists()
        onNodeWithText(L.settingsStreamKeepDetail).assertDoesNotExist()
        onNode(hasText(L.settingsStreamSave) and hasClickAction()).assertIsSelected()
        // Back to Keep video with a click, the stored boolean off again.
        onNode(hasText(L.settingsStreamKeep) and hasClickAction()).performClick()
        waitUntil(timeoutMillis = 5_000) { !runBlocking { settings.lowStorageModeNow() } }
        runBlocking { settings.setLowStorageMode(false) }
    }

    @Test
    fun `from the keyboard Left and Right move between the tabs and Space picks one`() = runComposeUiTest {
        runBlocking { settings.setLowStorageMode(false) }
        setContent { TmMaterialTheme(dark = true) { Box(Modifier.size(1280.dp, 3200.dp)) { SettingsPage(shell, "2.0.0", initialCategory = SettingsCategory.Storage) } } }
        waitForIdle()
        val keep = onNode(hasText(L.settingsStreamKeep) and hasClickAction())
        val save = onNode(hasText(L.settingsStreamSave) and hasClickAction())
        keep.performScrollTo().requestFocus()
        keep.assertIsFocused()
        // Right walks to Save space and the focus ring goes with it; Space (the segment's click) picks it.
        keep.performKeyInput { pressKey(Key.DirectionRight) }
        waitForIdle()
        save.assertIsFocused()
        save.performKeyInput { pressKey(Key.Spacebar) }
        waitUntil(timeoutMillis = 5_000) { runBlocking { settings.lowStorageModeNow() } }
        waitForIdle()
        save.assertIsSelected()
        save.performKeyInput { pressKey(Key.DirectionLeft) }
        waitForIdle()
        keep.assertIsFocused()
        keep.performKeyInput { pressKey(Key.Spacebar) }
        waitUntil(timeoutMillis = 5_000) { !runBlocking { settings.lowStorageModeNow() } }
        keep.assertIsSelected()
    }

    @Test
    fun shots() {
        for (keep in listOf(false, true)) {
            val name = if (keep) "keep" else "play"
            pending(keep) {
                save("low-storage-$name.png", render(dark = true) { LowStorageQuestionHost(shell) })
                save("low-storage-$name-light.png", render(dark = false) { LowStorageQuestionHost(shell) })
                Translator.use("ar")
                try {
                    save("low-storage-$name-ar.png", render(dark = true, rtl = true) { LowStorageQuestionHost(shell) })
                } finally {
                    Translator.use(Languages.ENGLISH)
                }
            }
        }
        save("low-storage-settings.png", render(dark = true, height = 3200) { SettingsPage(shell, "2.0.0", initialCategory = SettingsCategory.Storage) })
        runBlocking { settings.setLowStorageMode(true) }
        save("low-storage-settings-on.png", render(dark = true, height = 3200) { SettingsPage(shell, "2.0.0", initialCategory = SettingsCategory.Storage) })
        runBlocking { settings.setLowStorageMode(false) }
    }

    /** Runs [block] with a question pending, as the player ([keep] false) or the download picker leaves it. */
    private fun pending(keep: Boolean, block: () -> Unit) {
        val waiting = if (keep) {
            LowStorageQuestion.offer(film)
            null
        } else {
            GlobalScope.async { LowStorageQuestion.ask(film) }
        }
        while (LowStorageQuestion.pending.value == null) Thread.sleep(10)
        try {
            block()
        } finally {
            LowStorageQuestion.pending.value?.let { LowStorageQuestion.answered(it, false) }
            waiting?.let { runBlocking { it.await() } }
        }
    }

    private fun render(dark: Boolean, height: Int = HEIGHT, rtl: Boolean = false, page: @Composable () -> Unit): ByteArray =
        ImageComposeScene(WIDTH, height, Density(1f)) {
            CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                TmMaterialTheme(dark = dark) {
                    Surface(Modifier.fillMaxSize(), color = Tone.background) { page() }
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

    private companion object {
        const val WIDTH = 1280
        const val HEIGHT = 800
    }
}
