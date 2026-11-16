package com.tmplayer.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import com.tmplayer.data.DownloadRequest
import com.tmplayer.data.DownloadRunner
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.ThemeChoice
import com.tmplayer.data.UiScale
import com.tmplayer.data.UpdateScheduler
import com.tmplayer.data.WatchedStore
import com.tmplayer.desktop.DesktopPrefs
import com.tmplayer.desktop.DesktopWatchCache
import com.tmplayer.i18n.Languages
import com.tmplayer.i18n.Translator
import com.tmplayer.online.OnlineMetadata
import com.tmplayer.ui.nav.BackStack
import com.tmplayer.ui.nav.LocalBackStack
import com.tmplayer.ui.onboarding.Tour
import com.tmplayer.ui.onboarding.TourProgress
import com.tmplayer.ui.onboarding.TourScreen
import com.tmplayer.ui.theme.TmMaterialTheme
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The quick tour (CP69) and the first-run Home on the desktop: every page light and dark,
 * in Arabic (right to left) and the pseudo-locale, then walked from the keyboard alone. With
 * TMPLAYER_SHOTS=<dir> the frames are written there as PNGs.
 */
@OptIn(ExperimentalTestApi::class)
class SetupRenderTest {

    private val settings = SettingsStore(
        SettingsStore.openDataStore(Files.createTempDirectory("tm-setup-render").resolve(SettingsStore.FILE_NAME).toFile()),
    )
    private val dir = Files.createTempDirectory("tm-setup-render-extras").toFile()
    private val shell = ShellState(
        settings,
        object : DownloadRunner {
            override fun download(request: DownloadRequest) = Unit
            override fun cancel(fileId: Int) = Unit
            override fun pause(fileId: Int) = Unit
        },
        WatchedStore(WatchedStore.openDataStore(Files.createTempDirectory("tm-setup-watched").resolve(WatchedStore.FILE_NAME).toFile())),
        services = {
            DesktopExtras(
                prefs = DesktopPrefs(dir.resolve("desktop.properties")),
                updates = UpdateScheduler(settings.updatePrefs, check = { _, _ -> false }, onSkip = {}),
                watchCache = DesktopWatchCache(settings, { dir.resolve("cache") }),
            )
        },
    )


    // FocusVisibility is one flag for the whole process, and a test before this one in the same
    // JVM can leave it off (a mouse press through FocusVisible): then no focus fill is drawn here
    // and the focus tests read the resting colour. The setup is a keyboard and remote screen, so
    // focus is shown, as the app starts.
    @org.junit.Before
    fun focusShown() {
        com.tmplayer.ui.theme.FocusVisibility.visible = true
    }

    @After
    fun reset() {
        com.tmplayer.data.DeviceFitOverride.forced = null
        OnlineMetadata.current = null
        Translator.use(Languages.ENGLISH)
    }

    /** CP69: every tour page light and dark, in Arabic and the pseudo-locale, and narrow. */
    @Test
    fun tourEveryPage() {
        val pages = Tour.pages(com.tmplayer.data.DeviceForm.Desktop)
        for ((i, page) in pages.withIndex()) {
            for (dark in listOf(true, false)) {
                save("tour-${page.imageName}-${if (dark) "dark" else "light"}.png", render(dark) {
                    TourScreen(remember { TourProgress(pages, start = i) }, onDone = {}, before = 5, groups = listOf(1, 4, pages.size))
                })
            }
        }
        Translator.use("ar")
        save("tour-ar-videos.png", render(true, rtl = true) { TourScreen(remember { TourProgress(pages) }, onDone = {}) })
        Translator.use(Languages.PSEUDO)
        save("tour-xa-settings.png", render(true) { TourScreen(remember { TourProgress(pages, start = pages.lastIndex) }, onDone = {}) })
        Translator.use(Languages.ENGLISH)
        save("tour-narrow.png", render(true, width = 520, height = 900) { TourScreen(remember { TourProgress(pages) }, onDone = {}) })
    }

    /** CP69: from the keyboard the tour starts on Next, Enter walks it, Done ends it. */
    @Test
    fun tourFromTheKeyboard() = runComposeUiTest {
        val pages = Tour.pages(com.tmplayer.data.DeviceForm.Desktop)
        val tour = TourProgress(pages)
        var done = false
        setContent {
            TmMaterialTheme(dark = true) {
                Box(Modifier.size(1280.dp, 800.dp)) { TourScreen(tour, onDone = { done = true }) }
            }
        }
        for (i in pages.indices) {
            val label = if (i == pages.lastIndex) "Done" else "Next"
            waitUntil(timeoutMillis = WRITE_MS) {
                onAllNodes(hasText(label) and hasClickAction() and isFocused()).fetchSemanticsNodes().isNotEmpty()
            }
            onNode(isFocused()).performKeyInput { pressKey(Key.Enter) }
            waitForIdle()
        }
        // Done on the last page reports once its answers are written (ON-16), off the UI thread.
        waitUntil(timeoutMillis = WRITE_MS) { done }
        assertTrue(done)
    }

    @Test
    fun firstRunHome() {
        shell.go(Destination.Home)
        for (dark in listOf(true, false)) {
            save("home-first-run-${if (dark) "dark" else "light"}.png", render(dark) {
                HomeRowsView(shell, emptyList(), emptyList(), emptyMap(), onRowShown = {}, onArtWanted = {}, onRefresh = {})
            })
        }
    }

    private fun render(
        dark: Boolean,
        width: Int = WIDTH,
        height: Int = HEIGHT,
        rtl: Boolean = false,
        page: @Composable () -> Unit,
    ): ByteArray =
        ImageComposeScene(width, height, Density(1f)) {
            CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                TmMaterialTheme(dark = dark) {
                    Surface(Modifier.fillMaxSize(), color = Tone.background) { page() }
                }
            }
        }.use { scene ->
            scene.render(0)
            Thread.sleep(300)
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

        /** How long a choice has to reach the settings file, which is written off the UI thread. */
        const val WRITE_MS = 3_000L
    }
}
