package com.tmplayer.desktop.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import com.tmplayer.data.DeviceForm
import com.tmplayer.data.DownloadRequest
import com.tmplayer.data.DownloadRunner
import com.tmplayer.data.SettingsGroup
import com.tmplayer.data.SettingsLink
import com.tmplayer.data.SettingsMap
import com.tmplayer.data.SettingsNeed
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.UpdateScheduler
import com.tmplayer.data.WatchedStore
import com.tmplayer.desktop.DesktopPrefs
import com.tmplayer.desktop.DesktopWatchCache
import com.tmplayer.i18n.L
import com.tmplayer.ui.onboarding.SetupStep
import com.tmplayer.ui.settings.SettingsSeek
import com.tmplayer.ui.theme.TmMaterialTheme
import com.tmplayer.ui.theme.Tone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * The desktop's Settings held to SettingsMap (U20): every page draws the rows the map lists for
 * the desktop and nothing it does not, the list follows the shared order, every link the desktop
 * has lands on a page it lists, and the search box finds a moved row and opens its page.
 */
@OptIn(ExperimentalTestApi::class)
class SettingsMapDesktopTest {

    private val settings = SettingsStore(
        SettingsStore.openDataStore(Files.createTempDirectory("tm-settings-map").resolve(SettingsStore.FILE_NAME).toFile()),
    )
    private val dir = Files.createTempDirectory("tm-settings-map-extras").toFile()
    private val shell = ShellState(
        settings,
        object : DownloadRunner {
            override fun download(request: DownloadRequest) = Unit
            override fun cancel(fileId: Int) = Unit
            override fun pause(fileId: Int) = Unit
        },
        WatchedStore(WatchedStore.openDataStore(Files.createTempDirectory("tm-settings-map-watched").resolve(WatchedStore.FILE_NAME).toFile())),
        services = {
            DesktopExtras(
                prefs = DesktopPrefs(dir.resolve("desktop.properties")),
                updates = UpdateScheduler(settings.updatePrefs, check = { _, _ -> false }, onSkip = {}),
                watchCache = DesktopWatchCache(settings, { dir.resolve("cache") }),
            )
        },
    )

    @After
    fun tearDown() {
        SettingsSeek.drawn = null
    }

    /** The titles of every settings row drawn by [page], in a window tall enough for all of it. */
    private fun drawn(page: SettingsCategory?): List<String> {
        val seen = mutableListOf<String>()
        SettingsSeek.drawn = { seen += it }
        ImageComposeScene(1600, 6000, Density(1f)) {
            TmMaterialTheme(dark = true) {
                Surface(Modifier.fillMaxSize(), color = Tone.background) { SettingsPage(shell, "2.0.0", initialCategory = page) }
            }
        }.use { scene ->
            scene.render(0)
            Thread.sleep(200)
            scene.render(500_000_000)
        }
        SettingsSeek.drawn = null
        return seen
    }

    private val pageTitles = SettingsGroup.entries.map { L.messages.text(it.title) }.toSet()

    @Test
    fun `every page draws its rows and only those`() {
        val problems = mutableListOf<String>()
        for (category in SettingsCategory.entries - SettingsCategory.SetupChoices) {
            // The wide layout draws the list beside the page: the pages' own names are not rows.
            val seen = drawn(category).toSet() - pageTitles
            val rows = SettingsMap.on(DeviceForm.Desktop, category.group)
            val expected = rows.filter { it.need == null || it.need == SettingsNeed.Updates }.map { L.messages.text(it.titleOn(DeviceForm.Desktop)) }
            val known = rows.flatMap { row -> row.titlesOn(DeviceForm.Desktop).map { L.messages.text(it) } }.toSet()
            expected.filter { it !in seen }.takeIf { it.isNotEmpty() }?.let { problems += "$category misses $it" }
            (seen - known).takeIf { it.isNotEmpty() }?.let { problems += "$category draws rows SettingsMap does not list: $it" }
        }
        assertEquals(problems.joinToString("\n"), emptyList<String>(), problems)
    }

    @Test
    fun `the list follows the shared order and every link lands on a page it lists`() {
        val pages = drawn(null).filter { it in pageTitles }.distinct()
        val order = SettingsGroup.entries.map { L.messages.text(it.title) }.filter { it in pages }
        assertEquals(order, pages)
        assertEquals(L.messages.text(SettingsGroup.Playback.title), pages.first())
        for (link in SettingsLink.entries.filter { DeviceForm.Desktop in it.forms }) {
            assertTrue("$link", L.messages.text(link.group.title) in pages)
        }
        // Setup choices' "Also in" goes where the shared links say.
        assertEquals(SettingsCategory.Library, SettingsCategory.holding(SetupStep.Posters))
        assertEquals(SettingsCategory.Storage, SettingsCategory.holding(SetupStep.Storage))
        assertEquals(null, SettingsCategory.holding(SetupStep.Performance))
        assertEquals(null, SettingsCategory.holding(SetupStep.MobileData))
        // The page enum carries the shared names in the shared order.
        assertEquals(SettingsGroup.entries.map { it.name }.filter { n -> SettingsCategory.entries.any { it.name == n } }, SettingsCategory.entries.map { it.name })
    }

    @Test
    fun `the search box finds a moved row and opens its page on it`() = runComposeUiTest {
        setContent {
            TmMaterialTheme(dark = true) {
                Surface(Modifier.fillMaxSize(), color = Tone.background) { SettingsPage(shell, "2.0.0") }
            }
        }
        waitForIdle()
        onNode(hasSetTextAction()).performTextInput("downmix")
        waitForIdle()
        // The hit names the page it lives on now.
        onAllNodesWithText("Subtitles and audio").fetchSemanticsNodes().let { assertTrue(it.isNotEmpty()) }
        onNode(hasText("Downmix to stereo") and hasClickAction()).performClick()
        waitForIdle()
        mainClock.advanceTimeBy(500)
        waitForIdle()
        // The page is open on it: the moved row and its page's neighbours are there.
        assertTrue(onAllNodesWithText("Even out volume").fetchSemanticsNodes().isNotEmpty())
        assertTrue(onAllNodesWithText("Preferred audio language").fetchSemanticsNodes().isNotEmpty())

        onNode(hasSetTextAction()).performTextInput("zzqx")
        waitForIdle()
        assertTrue(onAllNodesWithText("Nothing in Settings matches", substring = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun `Playback has a line that opens the add a TV dialog`() = runComposeUiTest {
        setContent {
            TmMaterialTheme(dark = true) {
                Surface(Modifier.fillMaxSize(), color = Tone.background) { SettingsPage(shell, "2.0.0") }
            }
        }
        waitForIdle()
        val line = hasText("Add a TV by its address") and hasClickAction()
        onNode(line).performClick()
        waitForIdle()
        // The dialog's title carries the same words as the line under it.
        assertEquals(2, onAllNodesWithText("Add a TV by its address").fetchSemanticsNodes().size)
        assertTrue(onAllNodesWithText("Add TV").fetchSemanticsNodes().isNotEmpty())
        assertTrue(onAllNodesWithText("IP address or description link").fetchSemanticsNodes().isNotEmpty())
    }
}
