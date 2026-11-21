package com.tmplayer.ui.trailer

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.DpRect
import androidx.lifecycle.Lifecycle
import com.tmplayer.data.FormFactor
import com.tmplayer.i18n.L
import com.tmplayer.online.MetaTrailer
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.TMPlayerTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The trailer dialog's own chrome and behaviour on each form factor, at the normal font and at
 * twice it (TRAILERS-PLAN T3): Close (and on a touch screen "Watch on YouTube") whole, on screen
 * and apart; the "cannot play here" notice whole; a failure falls back once after the notice; a
 * device without a WebView falls back at once; the app going to the background pauses the page;
 * closing destroys it. Drawn to `build/device-test/review/trailer-*.png`.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TrailerDialogTest {

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    @get:Rule
    val rule = createEmptyComposeRule()

    @After
    fun tearDown() {
        FormFactor.override(false)
        Motion.reduced = false
        RuntimeEnvironment.setFontScale(1f)
    }

    private val trailer = MetaTrailer("YE7VzlLtp-4")
    private var fellBack = 0
    private lateinit var activity: ActivityController<ComponentActivity>
    private val pages = FakeTrailerPages()

    private fun show(fontScale: Float, tv: Boolean): AndroidTrailerOpener {
        FormFactor.override(tv)
        Motion.reduced = true
        RuntimeEnvironment.setFontScale(fontScale)
        val opener = AndroidTrailerOpener(network = { true }, hasWebView = { true })
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        activity.get().setContent { TMPlayerTheme { TrailerPlayerHost(opener, pages, readyTimeoutMs = 10 * 60_000L) } }
        rule.waitForIdle()
        opener.open(trailer) { fellBack++ }
        rule.waitForIdle()
        return opener
    }

    /** The chrome is whole, inside the screen, and its buttons do not overlap. */
    private fun checkChrome(name: String, tv: Boolean) {
        rule.waitForIdle()
        draw(name)
        val screen = rule.onNode(isDialog()).getUnclippedBoundsInRoot()
        val words = buildList {
            add(L.trailerClose)
            if (tv) add(L.trailerRemoteHint) else add(L.metadataTrailerOnYoutube)
        }
        val boxes = words.map { word ->
            val node = rule.onNodeWithText(word)
            rule.onNodeWithText(word, useUnmergedTree = true).assertWhole(word)
            val box = node.getUnclippedBoundsInRoot()
            assertTrue("\"$word\" is on screen: $box in $screen", box.left >= screen.left && box.right <= screen.right && box.top >= screen.top && box.bottom <= screen.bottom)
            box
        }
        assertFalse("${words[0]} and ${words[1]} overlap: $boxes", boxes[0].toRect().overlaps(boxes[1].toRect()))
    }

    private fun DpRect.toRect() = Rect(left.value, top.value, right.value, bottom.value)

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertWhole(word: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode().config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
        assertTrue("\"$word\" has a layout", layouts.isNotEmpty())
        // Cut is a line past maxLines or past the height. A sub-pixel width overflow is not: the
        // labels are one line laid out at their own width, which rounds.
        val cut = layouts.filter { it.didOverflowHeight || it.multiParagraph.didExceedMaxLines }
            .map { "width ${it.didOverflowWidth} height ${it.didOverflowHeight}, ${it.size} in ${it.layoutInput.constraints}, lines ${it.lineCount}" }
        assertTrue("\"$word\" is cut: $cut", cut.isEmpty())
    }

    private fun draw(name: String) {
        val root = activity.get().window.decorView
        if (root.width <= 0 || root.height <= 0) return
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        root.draw(canvas)
        ShadowDialog.getLatestDialog()?.window?.decorView?.draw(canvas)
        val file = File("../build/device-test/review/trailer-$name.png").absoluteFile
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** A YouTube error: the notice, whole, and the external open once, a moment later. */
    private fun checkFallback(name: String) {
        val page = pages.last ?: error("no page")
        rule.runOnIdle { page.events.onMessage("error:150") }
        rule.waitForIdle()
        rule.onNodeWithText(L.trailerUnavailableInApp).assertWhole(L.trailerUnavailableInApp)
        draw("$name-unavailable")
        rule.mainClock.advanceTimeBy(3_000)
        rule.waitForIdle()
        assertEquals("falls back once", 1, fellBack)
        assertEquals(1, page.destroyed)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w360dp-h740dp-port-night-xhdpi")
    fun `phone upright at the normal font`() {
        show(1f, tv = false)
        checkChrome("phone-1x", tv = false)
        checkFallback("phone-1x")
    }

    @Test
    @Config(sdk = [35], qualifiers = "w360dp-h740dp-port-night-xhdpi")
    fun `phone upright at twice the font`() {
        show(2f, tv = false)
        checkChrome("phone-2x", tv = false)
        checkFallback("phone-2x")
    }

    @Test
    @Config(sdk = [35], qualifiers = "w740dp-h360dp-land-night-xhdpi")
    fun `phone on its side at twice the font`() {
        show(2f, tv = false)
        checkChrome("phone-land-2x", tv = false)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w1280dp-h800dp-land-night-xhdpi")
    fun `tablet at the normal font and at twice it`() {
        show(1f, tv = false)
        checkChrome("tablet-1x", tv = false)
        activity.pause().stop().destroy()
        show(2f, tv = false)
        checkChrome("tablet-2x", tv = false)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
    fun `television at the normal font and at twice it`() {
        show(1f, tv = true)
        checkChrome("tv-1x", tv = true)
        assertTrue(pages.last?.remote == true)
        checkFallback("tv-1x")
    }

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
    fun `television at twice the font`() {
        show(2f, tv = true)
        checkChrome("tv-2x", tv = true)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w360dp-h740dp-port-night-xhdpi")
    fun `the app in the background pauses the trailer, Close destroys it`() {
        val opener = show(1f, tv = false)
        val page = pages.last ?: error("no page")
        activity.pause().stop()
        rule.waitForIdle()
        assertEquals(1, page.paused)
        activity.start().resume()
        rule.waitForIdle()
        assertEquals(1, page.resumed)
        assertTrue("never played again by itself", page.scripts.none { it == com.tmplayer.online.TrailerEmbed.PLAY })
        rule.onNodeWithText(L.trailerClose).performClick()
        rule.waitForIdle()
        assertNull(opener.request)
        assertEquals(1, page.destroyed)
        assertEquals(0, fellBack)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w360dp-h740dp-port-night-xhdpi")
    fun `Watch on YouTube closes and opens it outside`() {
        val opener = show(1f, tv = false)
        rule.onNodeWithText(L.metadataTrailerOnYoutube).performClick()
        rule.waitForIdle()
        assertNull(opener.request)
        assertEquals(1, fellBack)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w360dp-h740dp-port-night-xhdpi")
    fun `no WebView falls back, at once or after the notice`() {
        // The package is missing: nothing is shown, the trailer opens outside.
        val none = AndroidTrailerOpener(network = { true }, hasWebView = { false })
        none.open(trailer) { fellBack++ }
        assertEquals(1, fellBack)
        assertNull(none.request)
        // The WebView cannot be made after all: the notice, then outside.
        pages.fail = true
        show(1f, tv = false)
        rule.onNodeWithText(L.trailerUnavailableInApp).assertExists()
        rule.mainClock.advanceTimeBy(3_000)
        rule.waitForIdle()
        assertEquals(2, fellBack)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w360dp-h740dp-port-night-xhdpi")
    fun `no ready within the wait falls back`() {
        FormFactor.override(false)
        val opener = AndroidTrailerOpener(network = { true }, hasWebView = { true })
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        activity.get().setContent { TMPlayerTheme { TrailerPlayerHost(opener, pages, readyTimeoutMs = 5_000) } }
        rule.waitForIdle()
        opener.open(trailer) { fellBack++ }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(8_000)
        rule.waitForIdle()
        assertEquals(1, fellBack)
        assertNull(opener.request)
        assertEquals(Lifecycle.State.RESUMED, activity.get().lifecycle.currentState)
    }
}
