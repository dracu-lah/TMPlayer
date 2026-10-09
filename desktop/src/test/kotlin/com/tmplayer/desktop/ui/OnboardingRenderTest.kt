package com.tmplayer.desktop.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import com.tmplayer.data.DownloadRequest
import com.tmplayer.data.DownloadRunner
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.UpdateScheduler
import com.tmplayer.data.WatchedStore
import com.tmplayer.desktop.DesktopPrefs
import com.tmplayer.desktop.DesktopWatchCache
import com.tmplayer.i18n.Languages
import com.tmplayer.i18n.Translator
import com.tmplayer.online.OnlineMetadata
import com.tmplayer.ui.onboarding.OnboardingPage
import com.tmplayer.ui.onboarding.OnboardingTour
import com.tmplayer.ui.onboarding.TourState
import com.tmplayer.ui.theme.TmMaterialTheme
import com.tmplayer.ui.theme.Tone
import org.jetbrains.skia.EncodedImageFormat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The two page tour (CP42) and the first-run Home on the desktop: both pages light and dark, the
 * language picker over the Welcome page, the pseudo-locale, and the posters switch telling the
 * truth. With TMPLAYER_SHOTS=<dir> the frames are written there as PNGs.
 */
class OnboardingRenderTest {

    private val settings = SettingsStore(
        SettingsStore.openDataStore(Files.createTempDirectory("tm-tour-render").resolve(SettingsStore.FILE_NAME).toFile()),
    )
    private val dir = Files.createTempDirectory("tm-tour-render-extras").toFile()
    private val shell = ShellState(
        settings,
        object : DownloadRunner {
            override fun download(request: DownloadRequest) = Unit
            override fun cancel(fileId: Int) = Unit
            override fun pause(fileId: Int) = Unit
        },
        WatchedStore(WatchedStore.openDataStore(Files.createTempDirectory("tm-tour-watched").resolve(WatchedStore.FILE_NAME).toFile())),
        services = {
            DesktopExtras(
                prefs = DesktopPrefs(dir.resolve("desktop.properties")),
                updates = UpdateScheduler(settings.updatePrefs, check = { _, _ -> false }, onSkip = {}),
                watchCache = DesktopWatchCache(settings, { dir.resolve("cache") }),
            )
        },
    )

    @After
    fun reset() {
        OnlineMetadata.current = null
        Translator.use(Languages.ENGLISH)
    }

    @Test
    fun bothPagesLightAndDark() {
        MetaFixture.install("on")
        for (page in OnboardingPage.entries) {
            for (dark in listOf(true, false)) {
                save("tour-${page.name.lowercase()}-${if (dark) "dark" else "light"}.png", render(dark) {
                    OnboardingTour(settings, onDone = {}, firstRun = true, tour = remember { TourState(true, start = page.ordinal) })
                })
            }
        }
        save("tour-language-picker.png", render(true) {
            OnboardingTour(settings, onDone = {}, firstRun = true, tour = remember { TourState(true).also { it.choosingLanguage = true } })
        })
        Translator.use(Languages.PSEUDO)
        for (page in OnboardingPage.entries) {
            save("tour-xa-${page.name.lowercase()}.png", render(true) {
                OnboardingTour(settings, onDone = {}, firstRun = true, tour = remember { TourState(true, start = page.ordinal) })
            })
        }
    }

    /**
     * The tour leaves posters exactly as the switch on the Welcome page shows them: the switch
     * reads the setting, nothing else writes it when the tour ends, and a viewer's "off" made on
     * the page survives the rest of the walk, a Skip, and the tour drawn again.
     */
    @Test
    fun postersEndAsTheSwitchSays() {
        val online = MetaFixture.install("on")
        var done = false
        render(true) {
            OnboardingTour(settings, onDone = { done = true }, firstRun = true, tour = remember { TourState(true) })
        }
        assertTrue("on, as the switch showed it", online.settings.value.enabled)
        // The viewer turns it off on the page, then walks on and finishes.
        online.setEnabled(false)
        render(true) {
            val tour = remember { TourState(true, start = OnboardingPage.HowItWorks.ordinal) }
            OnboardingTour(settings, onDone = { done = true }, firstRun = true, tour = tour)
            if (!tour.next()) done = true
        }
        assertTrue(done)
        assertEquals("off, as the switch was left", false, online.settings.value.enabled)
        // A tour Settings asks for again changes nothing either.
        render(true) { OnboardingTour(settings, onDone = {}, firstRun = false) }
        assertEquals(false, online.settings.value.enabled)
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

    private fun render(dark: Boolean, page: @Composable () -> Unit): ByteArray =
        ImageComposeScene(WIDTH, HEIGHT, Density(1f)) {
            TmMaterialTheme(dark = dark) {
                Surface(Modifier.fillMaxSize(), color = Tone.background) { page() }
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
    }
}
