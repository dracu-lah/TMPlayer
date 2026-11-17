package com.tmplayer.desktop.trailer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.tmplayer.i18n.L
import com.tmplayer.online.MetaTrailer
import com.tmplayer.online.TrailerEmbed
import com.tmplayer.online.TrailerRules
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.TmMaterialTheme
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * The desktop's trailer on screen ([DesktopTrailerHost] over [DesktopTrailers]) with a fake engine,
 * at the window sizes the desktop meets (the 960 by 600 minimum, a 1366 by 768 laptop, 1920 by
 * 1080): the consent dialog says the sizes and the free space and starts on Watch on YouTube, no
 * room offers no Download, the download plays the trailer in the sheet, the sheet's keys run the
 * bridge's scripts, Esc closes it, disposes the browser and gives the focus back to the trailer
 * button, and a failed download, a cancel, a YouTube error and the ready timeout each open YouTube
 * once with their line.
 */
@OptIn(ExperimentalTestApi::class)
class TrailerHostTest {

    private val trailer = MetaTrailer("YE7VzlLtp-4")
    private val native = CefNative("linux-amd64", 152_141_475L, 375_763_385L, "00")
    private val place = File("/home/viewer/.local/share/TMPlayer/browser")
    private val installedDir = File(place, "jcef")
    private val sizes = listOf(960 to 600, 1366 to 768, 1920 to 1080)

    private var reduced = false

    @Before
    fun still() {
        reduced = Motion.reduced
        Motion.reduced = true
    }

    @After
    fun restore() {
        Motion.reduced = reduced
    }

    private class FakeBrowser(val listener: TrailerBrowser.Listener) : TrailerBrowser {
        val scripts = mutableListOf<String>()
        var disposed = 0
        override val component: java.awt.Component? = null
        override fun run(script: String) {
            scripts += script
        }
        override fun dispose() {
            disposed++
        }
    }

    private class FakeEngine(var status: EngineStatus) : TrailerEngine {
        var installs = 0
        var cancelled = false
        var failInstall = false
        var holdInstall = false
        val held = CompletableDeferred<Unit>()
        val browsers = mutableListOf<FakeBrowser>()

        override fun status(): EngineStatus = status

        override suspend fun install(offer: EngineOffer.Fits, onProgress: (EngineProgress) -> Unit): File {
            installs++
            onProgress(EngineProgress.Downloading(offer.native.bytes / 2, offer.native.bytes))
            if (holdInstall) {
                held.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    cancelled = true
                }
            }
            if (failInstall) throw IOException("HTTP 503")
            onProgress(EngineProgress.Unpacking(offer.native.unpackedBytes, offer.native.unpackedBytes))
            return File(offer.dir, "jcef")
        }

        override suspend fun start(dir: File): Boolean = true

        override fun browser(html: String, listener: TrailerBrowser.Listener): TrailerBrowser {
            assertTrue("the page is T1's, for this key", html.contains("/embed/YE7VzlLtp-4"))
            return FakeBrowser(listener).also { browsers += it }
        }
    }

    private class Run(val engine: FakeEngine) {
        lateinit var trailers: DesktopTrailers
        val notices = mutableListOf<String>()
        var fallbacks = 0
    }

    private fun scene(width: Int, height: Int, status: EngineStatus, body: ComposeUiTest.(Run) -> Unit) = runComposeUiTest {
        val run = Run(FakeEngine(status))
        mainClock.autoAdvance = false
        setContent {
            val scope = rememberCoroutineScope()
            run.trailers = remember { DesktopTrailers(run.engine, scope, notify = { run.notices += it }) }
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                TmMaterialTheme(dark = true) {
                    Box(Modifier.size(width.dp, height.dp)) {
                        Surface(Modifier.size(width.dp, height.dp), color = Tone.background) {
                            Button(onClick = { run.trailers.open(trailer) { run.fallbacks++ } }) { Text(L.metadataTrailer) }
                        }
                        DesktopTrailerHost(run.trailers, video = { _, _ -> })
                    }
                }
            }
        }
        step()
        body(run)
    }

    private fun ComposeUiTest.press() {
        val button = onNodeWithText(L.metadataTrailer)
        button.requestFocus()
        step()
        button.assertIsFocused()
        button.performClick()
    }

    private fun ComposeUiTest.step() {
        Thread.sleep(20)
        mainClock.advanceTimeBy(50)
    }

    private fun ComposeUiTest.until(what: String, check: () -> Boolean) {
        repeat(300) {
            step()
            if (check()) return
        }
        throw AssertionError("timed out waiting for $what")
    }

    private fun ComposeUiTest.shown(text: String) = onAllNodes(androidx.compose.ui.test.hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.sheetKey(key: Key) {
        onNodeWithContentDescription(L.trailerTitle).performKeyInput { pressKey(key) }
        step()
    }

    @Test
    fun `the consent dialog says what downloads, how much, where and what is free, and starts on YouTube`() = sizes.forEach { (w, h) ->
        val offer = EngineOffer.Fits(native, place, freeBytes = 20L * 1024 * 1024 * 1024)
        scene(w, h, EngineStatus.Missing(offer)) { run ->
            press()
            until("the dialog") { shown(L.trailerEngineTitle) }
            assertTrue(shown(L.trailerEngineIntro))
            assertTrue("the download's size", shown(com.tmplayer.data.StorageRelocationPlan.size(native.bytes)))
            assertTrue("the free space", shown(com.tmplayer.data.StorageRelocationPlan.size(offer.freeBytes)))
            assertTrue("the folder", shown(place.path))
            onNodeWithText(L.metadataTrailerOnYoutube).assertIsFocused()
            onNodeWithText(L.metadataTrailerOnYoutube).performClick()
            until("the fallback") { run.fallbacks == 1 }
            assertEquals("nothing downloaded", 0, run.engine.installs)
            assertEquals("the viewer's own choice needs no line", emptyList<String>(), run.notices)
            assertFalse(shown(L.trailerEngineTitle))
        }
    }

    @Test
    fun `no room offers no Download and says what is needed and free`() = sizes.forEach { (w, h) ->
        val offer = EngineOffer.NoRoom(native, place, freeBytes = 50L * 1024 * 1024)
        scene(w, h, EngineStatus.Missing(offer)) { run ->
            press()
            until("the dialog") { shown(L.trailerEngineTitle) }
            assertEquals("no Download button", 0, onAllNodes(androidx.compose.ui.test.hasText(L.trailerEngineDownload)).fetchSemanticsNodes().size)
            assertTrue(shown(com.tmplayer.data.StorageRelocationPlan.size(offer.neededBytes)))
            assertTrue(shown(com.tmplayer.data.StorageRelocationPlan.size(offer.freeBytes)))
            assertEquals(0, run.engine.installs)
        }
    }

    @Test
    fun `Download plays it in the sheet, the keys drive the bridge, Esc closes and the focus comes back`() = sizes.forEach { (w, h) ->
        scene(w, h, EngineStatus.Missing(EngineOffer.Fits(native, place, freeBytes = 20L * 1024 * 1024 * 1024))) { run ->
            press()
            until("the dialog") { shown(L.trailerEngineDownload) }
            onNodeWithText(L.trailerEngineDownload).performClick()
            until("the browser") { run.engine.browsers.isNotEmpty() }
            val browser = run.engine.browsers.single()
            assertTrue(shown(L.trailerKeysHint))
            browser.listener.onMessage("ready")
            browser.listener.onMessage("state:1")
            until("the player to say it plays") { (run.trailers.screen as? TrailerScreen.Playing)?.state?.controllable == true }
            onNodeWithContentDescription(L.trailerTitle).assertIsFocused()
            sheetKey(Key.Spacebar)
            sheetKey(Key.DirectionLeft)
            sheetKey(Key.DirectionRight)
            assertEquals(listOf(TrailerEmbed.TOGGLE, TrailerEmbed.seekBy(-10), TrailerEmbed.seekBy(10)), browser.scripts)
            // A key from inside the browser (CefKeyboardHandler) does the same.
            browser.listener.onKey(TrailerKey.Toggle)
            step()
            assertEquals(TrailerEmbed.TOGGLE, browser.scripts.last())
            sheetKey(Key.Escape)
            until("the sheet to close") { run.trailers.screen == TrailerScreen.None }
            assertEquals("the browser is freed once", 1, browser.disposed)
            onNodeWithText(L.metadataTrailer).assertIsFocused()
            assertEquals(0, run.fallbacks)
            assertEquals(1, run.engine.installs)
        }
    }

    @Test
    fun `an installed player plays at once and its Close button closes`() {
        scene(1366, 768, EngineStatus.Installed(installedDir)) { run ->
            press()
            until("the browser") { run.engine.browsers.isNotEmpty() }
            assertFalse("no question when it is there", shown(L.trailerEngineTitle))
            onNodeWithContentDescription(L.trailerClose).performClick()
            until("the sheet to close") { run.trailers.screen == TrailerScreen.None }
            assertEquals(1, run.engine.browsers.single().disposed)
        }
    }

    @Test
    fun `a failed download opens YouTube once and says so`() {
        scene(1366, 768, EngineStatus.Missing(EngineOffer.Fits(native, place, freeBytes = 20L * 1024 * 1024 * 1024))) { run ->
            run.engine.failInstall = true
            press()
            until("the dialog") { shown(L.trailerEngineDownload) }
            onNodeWithText(L.trailerEngineDownload).performClick()
            until("the fallback") { run.fallbacks == 1 }
            assertEquals(listOf(L.trailerEngineFailed), run.notices)
            repeat(5) { step() }
            assertEquals("once", 1, run.fallbacks)
        }
    }

    @Test
    fun `Cancel stops the download, which cleans up, and opens YouTube`() {
        scene(1366, 768, EngineStatus.Missing(EngineOffer.Fits(native, place, freeBytes = 20L * 1024 * 1024 * 1024))) { run ->
            run.engine.holdInstall = true
            press()
            until("the dialog") { shown(L.trailerEngineDownload) }
            onNodeWithText(L.trailerEngineDownload).performClick()
            until("the progress") { shown(L.trailerEngineDownloading) }
            assertTrue("how far it is", shown(com.tmplayer.data.StorageRelocationPlan.size(native.bytes)))
            onNodeWithText(L.commonCancel).performClick()
            until("the install job to stop") { run.engine.cancelled }
            assertEquals(1, run.fallbacks)
            assertEquals(listOf(L.trailerEngineCancelled), run.notices)
        }
    }

    @Test
    fun `a YouTube error closes the sheet, frees the browser and opens YouTube once`() {
        scene(1366, 768, EngineStatus.Installed(installedDir)) { run ->
            press()
            until("the browser") { run.engine.browsers.isNotEmpty() }
            val browser = run.engine.browsers.single()
            browser.listener.onMessage("ready")
            browser.listener.onMessage("error:150")
            until("the fallback") { run.fallbacks == 1 }
            assertEquals(1, browser.disposed)
            assertEquals(listOf(L.trailerUnavailableInApp), run.notices)
            browser.listener.onMessage("error:150")
            repeat(5) { step() }
            assertEquals("a closed page's late message does nothing", 1, run.fallbacks)
        }
    }

    @Test
    fun `no ready within the timeout opens YouTube`() {
        scene(1366, 768, EngineStatus.Installed(installedDir)) { run ->
            press()
            until("the browser") { run.engine.browsers.isNotEmpty() }
            mainClock.advanceTimeBy(TrailerRules.READY_TIMEOUT_MS - 1_000)
            step()
            assertEquals(0, run.fallbacks)
            mainClock.advanceTimeBy(2_000)
            until("the fallback") { run.fallbacks == 1 }
            assertEquals(listOf(L.trailerUnavailableInApp), run.notices)
        }
    }

    @Test
    fun `an unsupported computer opens YouTube with its line`() {
        scene(1366, 768, EngineStatus.Unsupported) { run ->
            press()
            until("the fallback") { run.fallbacks == 1 }
            assertEquals(listOf(L.trailerEngineUnsupported), run.notices)
        }
    }
}
