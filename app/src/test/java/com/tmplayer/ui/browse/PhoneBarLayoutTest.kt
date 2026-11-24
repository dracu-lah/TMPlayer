package com.tmplayer.ui.browse

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.tmplayer.data.Account
import com.tmplayer.data.FormFactor
import com.tmplayer.i18n.L
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.components.captionStyle
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.TMPlayerTheme
import com.tmplayer.ui.theme.Tone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs

/**
 * The phone's floating bar as it looks (user, 2026-11-25): one caption size for every item (the
 * longest word, "Downloads", is never shrunk alone), the same icon-to-caption gap, slots of one
 * width evenly spread, in a portrait phone, a phone on its side (the rail), a tablet and at 1.3 times
 * the font; and the account's picture, which is the shell's, never blinking, moving or doubling as
 * the page changes or Home and Chats are swiped between. Frames go to build/device-test/review.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneBarLayoutTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val drain = DrainMainLooperRule()

    @After
    fun tearDown() {
        FormFactor.override(false)
        Motion.reduced = false
    }

    private val current = mutableStateOf<TouchDestination?>(TouchDestination.Home)
    private val pageKey = mutableStateOf("home")
    private val position = mutableFloatStateOf(0f)
    private val swiping = mutableStateOf(false)

    private fun show(fontScale: Float = 1f, running: Int = 0, hidden: MutableState<Boolean> = mutableStateOf(false)) {
        FormFactor.override(false)
        Motion.reduced = true
        val nav = TouchNav(
            account = Account(name = "Asha Rao", username = "asha", miniThumbnail = null, photoFileId = 0),
            favoriteCount = 3, downloadCount = running, watchingCount = 2, watchedCount = 5, updateVersion = "1.30.0",
            onHome = {}, onChats = {}, onSearch = {}, onSettings = {},
            onFavourites = {}, onDownloads = {}, onHistory = {}, onUpdate = {},
        )
        val chrome = TouchChrome()
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                TMPlayerTheme {
                    Box(Modifier.fillMaxSize().background(Tone.background)) {
                        TouchNavHost(nav, chrome, pageKey.value) {
                            CompositionLocalProvider(LocalTouchPageKey provides pageKey.value) {
                                TouchPage(
                                    TouchPageSpec(
                                        current = current.value,
                                        hidden = hidden.value,
                                        position = if (swiping.value) ({ position.floatValue }) else null,
                                    ),
                                )
                                Box(Modifier.fillMaxSize())
                            }
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private val density get() = rule.activity.resources.displayMetrics.density

    private fun places() = listOf(L.browseTabHome, L.browseTabChats, L.commonSearch, L.navDownloads)

    private fun frame(name: String) {
        val bitmap: Bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        val file = File("../build/device-test/review/$name").absoluteFile
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(file.length() > 0)
    }

    /** The font size each caption is drawn at, from its text layout; empty when the bar shows icons only. */
    private fun captionSizes(): List<Float> = places().mapNotNull { label ->
        val nodes = rule.onAllNodes(androidx.compose.ui.test.hasText(label), useUnmergedTree = true).fetchSemanticsNodes()
        val layout = nodes.firstNotNullOfOrNull { node ->
            val results = ArrayList<androidx.compose.ui.text.TextLayoutResult>()
            node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
            results.firstOrNull()
        }
        layout?.layoutInput?.style?.fontSize?.value
    }

    private fun checkEven(out: String) {
        val slots = places().map { rule.onNode(androidx.compose.ui.test.hasContentDescription(it, substring = true)).fetchSemanticsNode().boundsInRoot }
        val pill = rule.onNodeWithTag(BAR_PILL_TAG).fetchSemanticsNode().boundsInRoot
        val wide = slots.map { it.width }
        val high = slots.map { it.height }
        assertTrue("slots of one width: $wide", wide.all { abs(it - wide.first()) < 1.5f })
        assertTrue("slots of one height: $high", high.all { abs(it - high.first()) < 1.5f })
        assertTrue("each a 48 dp target at the least: $wide x $high", wide.all { it >= 48f * density - 1f } && high.all { it >= 48f * density - 1f })
        // Evenly spread: the gap between neighbours is one gap, whichever way the bar runs.
        val vertical = pill.height > pill.width
        val gaps = slots.zipWithNext { a, b -> if (vertical) b.top - a.bottom else b.left - a.right }
        assertTrue("one gap between slots: $gaps", gaps.all { abs(it - gaps.first()) < 1.5f })
        assertTrue("every slot inside the pill", slots.all { it.left >= pill.left - 1f && it.right <= pill.right + 1f && it.top >= pill.top - 1f && it.bottom <= pill.bottom + 1f })
        // One caption size for all four, or none (icons only) for all four: never one shrunk alone.
        val sizes = captionSizes()
        assertTrue("all four captions or none: $sizes", sizes.size == 4 || sizes.isEmpty())
        assertTrue("one caption size: $sizes", sizes.all { it == sizes.first() })
        // Search is in the pill.
        val search = rule.onNode(androidx.compose.ui.test.hasContentDescription(L.commonSearch)).fetchSemanticsNode().boundsInRoot
        assertTrue("Search inside the pill", search.left >= pill.left && search.right <= pill.right && search.top >= pill.top && search.bottom <= pill.bottom)
        frame(out)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w360dp-h800dp-port-night-xhdpi")
    fun `portrait, 360 dp`() {
        show(running = 2)
        checkEven("bar-360-port.png")
    }

    @Test
    @Config(sdk = [35], qualifiers = "w320dp-h640dp-port-night-xhdpi")
    fun `a narrow 320 dp phone takes width from every slot alike`() {
        show(running = 12)
        checkEven("bar-320-port.png")
    }

    @Test
    @Config(sdk = [35], qualifiers = "w360dp-h800dp-port-night-xhdpi")
    fun `at 1_3 times the font, all four drop together or none`() {
        show(fontScale = 1.3f)
        checkEven("bar-360-font1.3.png")
    }

    @Test
    @Config(sdk = [35], qualifiers = "w360dp-h800dp-port-night-xhdpi")
    fun `at twice the font the captions stay at the capped size, all alike`() {
        show(fontScale = 2f)
        checkEven("bar-360-font2.png")
    }

    @Test
    @Config(sdk = [35], qualifiers = "w800dp-h360dp-land-night-xhdpi")
    fun `a phone on its side, the rail`() {
        show()
        checkEven("bar-800-land.png")
    }

    @Test
    @Config(sdk = [35], qualifiers = "sw600dp-w800dp-h1280dp-port-night-xhdpi")
    fun `a tablet, the rail`() {
        show(fontScale = 1.3f)
        checkEven("bar-tablet.png")
    }

    @Test
    @Config(sdk = [35], qualifiers = "w360dp-h800dp-port-night-xhdpi")
    fun `the caption size is the largest all four fit at, and none when even the smallest is too big`() {
        var fits: androidx.compose.ui.unit.TextUnit? = null
        var tooLong: androidx.compose.ui.unit.TextUnit? = null
        var roomy: androidx.compose.ui.unit.TextUnit? = null
        rule.setContent {
            TMPlayerTheme {
                val measurer = rememberTextMeasurer()
                val density = Density(2f, 1f)
                val base = captionStyle()
                fits = pickBarCaptionSize(places(), 56.dp, density, measurer, base)
                roomy = pickBarCaptionSize(listOf("Home", "Chats"), 80.dp, density, measurer, base)
                tooLong = pickBarCaptionSize(places() + "A very long translated word", 56.dp, density, measurer, base)
            }
        }
        rule.waitForIdle()
        assertEquals("short words get the full size", BAR_CAPTION_SP.toFloat(), roomy?.value ?: -1f, 0.01f)
        assertTrue("one of the steps for the four: $fits", fits == null || BAR_CAPTION_STEPS_SP.any { it.toFloat() == fits!!.value })
        assertNull("a word that fits no step takes the captions from all", tooLong)
    }

    // The sheet's content, as drawn on its own (a sheet is a window the test cannot photograph) --------

    private fun sheetContent(fontScale: Float, several: Boolean, out: String) {
        FormFactor.override(false)
        Motion.reduced = true
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                TMPlayerTheme {
                    Box(Modifier.fillMaxSize().background(com.tmplayer.ui.theme.FloatingTone.sheet)) {
                        AccountMenuContent(
                            account = Account(name = "Asha Rao", username = "asha", miniThumbnail = null, photoFileId = 0),
                            profile = null,
                            name = "Asha Rao",
                            first = androidx.compose.ui.focus.FocusRequester(),
                            onRefresh = {},
                            favoriteCount = 7,
                            watchingCount = 3,
                            watchedCount = 12,
                            onFavourites = {}, onHistory = {}, onWatchLater = {}, onSettings = {}, onUpdate = {}, onAbout = {},
                            onSwitchProfile = if (several) ({}) else null,
                            onSwitchOrAdd = {},
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
        val menu = rule.onNodeWithTag(ACCOUNT_MENU_TAG).fetchSemanticsNode().boundsInRoot
        // Header, tiles, two rows and the version: the height the user's "too many options" asked to keep short.
        assertTrue("the content is ${menu.height / density} dp high", menu.height / density < 420f * maxOf(1f, fontScale))
        frame(out)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w360dp-h640dp-port-night-xhdpi")
    fun `the sheet's content at 360 x 640, one profile`() = sheetContent(1f, several = false, out = "sheet-content-360x640.png")

    @Test
    @Config(sdk = [35], qualifiers = "w360dp-h640dp-port-night-xhdpi")
    fun `the sheet's content at 360 x 640 and 1_3 times the font, two profiles`() = sheetContent(1.3f, several = true, out = "sheet-content-360x640-font1.3.png")

    // The account's picture is the shell's -------------------------------------------------------

    private fun avatar() = rule.onAllNodesWithTag(ACCOUNT_BUTTON_TAG)

    @Test
    @Config(sdk = [35], qualifiers = "w360dp-h800dp-port-night-xhdpi")
    fun `the picture stays put, shown and single, through every change of page and a swipe`() {
        show()
        avatar().assertCountEquals(1)
        val first = rule.onNodeWithTag(ACCOUNT_BUTTON_TAG).fetchSemanticsNode().boundsInRoot
        fun check(step: String) {
            rule.waitForIdle()
            avatar().assertCountEquals(1)
            rule.onNodeWithTag(ACCOUNT_BUTTON_TAG).assertIsDisplayed()
            val now = rule.onNodeWithTag(ACCOUNT_BUTTON_TAG).fetchSemanticsNode().boundsInRoot
            assertEquals("$step: same left", first.left, now.left, 0.5f)
            assertEquals("$step: same top", first.top, now.top, 0.5f)
        }
        frame("avatar-swipe-0-home.png")
        // Home and Chats are swiped between: the highlight moves, the picture does not.
        rule.runOnUiThread { swiping.value = true; position.floatValue = 0.5f }
        check("mid swipe")
        frame("avatar-swipe-1-mid.png")
        rule.runOnUiThread { position.floatValue = 1f; current.value = TouchDestination.Chats; pageKey.value = "chats" }
        check("Chats")
        rule.runOnUiThread { swiping.value = false; current.value = TouchDestination.Downloads; pageKey.value = "downloads" }
        check("Downloads")
        rule.runOnUiThread { current.value = null; pageKey.value = "favourites" }
        check("a page behind the picture")
        rule.runOnUiThread { current.value = TouchDestination.Home; pageKey.value = "home" }
        check("Home again")
        frame("avatar-swipe-2-home-again.png")
    }

    @Test
    @Config(sdk = [35], qualifiers = "w360dp-h800dp-port-night-xhdpi")
    fun `the picture goes with a search open or a selection under way, and comes back`() {
        val hidden = mutableStateOf(false)
        show(hidden = hidden)
        avatar().assertCountEquals(1)
        rule.runOnUiThread { hidden.value = true }
        rule.waitForIdle()
        avatar().assertCountEquals(0)
        rule.runOnUiThread { hidden.value = false }
        rule.waitForIdle()
        avatar().assertCountEquals(1)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w800dp-h360dp-land-night-xhdpi")
    fun `beside a rail the picture is the same one, at the top end`() {
        show()
        avatar().assertCountEquals(1)
        val b = rule.onNodeWithTag(ACCOUNT_BUTTON_TAG).fetchSemanticsNode().boundsInRoot
        assertTrue(b.left > 800f * density / 2)
        rule.runOnUiThread { current.value = TouchDestination.Chats; pageKey.value = "chats" }
        rule.waitForIdle()
        avatar().assertCountEquals(1)
        assertEquals(b.left, rule.onNodeWithTag(ACCOUNT_BUTTON_TAG).fetchSemanticsNode().boundsInRoot.left, 0.5f)
    }
}
