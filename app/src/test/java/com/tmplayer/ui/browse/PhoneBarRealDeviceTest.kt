package com.tmplayer.ui.browse

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.tmplayer.data.Account
import com.tmplayer.data.FormFactor
import com.tmplayer.i18n.L
import com.tmplayer.i18n.Languages
import com.tmplayer.i18n.Translator
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.components.captionStyle
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.TMPlayerTheme
import com.tmplayer.ui.theme.Tone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
 * The phone's floating bar on the real test phone's window (393 x 873 dp at 440 dpi), where
 * "Downloads" lost its first and last letters (user, 2026-11-25), then the roomier fix was "a bit too
 * big": no caption overflows or leaves its slot at font 1.0, 1.3 and 2.0, in English, German and the
 * pseudo (longest) language; the four slots and caption sizes are equal; the pill is compact (at most
 * 64 dp high and 85 percent of the window, about 80 percent of 393 dp), its two ends are the same and
 * the highlight sits the same distance in from the top, bottom and ends.
 * Pictures go to design/phone-bar.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneBarRealDeviceTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val drain = DrainMainLooperRule()

    @After
    fun tearDown() {
        FormFactor.override(false)
        Motion.reduced = false
        Translator.use(Languages.ENGLISH)
    }

    private val density get() = rule.activity.resources.displayMetrics.density

    private fun show(fontScale: Float, language: String = Languages.ENGLISH) {
        FormFactor.override(false)
        Motion.reduced = true
        Translator.use(language)
        val nav = TouchNav(
            account = Account(name = "Asha Rao", username = "asha", miniThumbnail = null, photoFileId = 0),
            favoriteCount = 0, downloadCount = 0, watchingCount = 0, watchedCount = 0, updateVersion = null,
            onHome = {}, onChats = {}, onSearch = {}, onSettings = {},
            onFavourites = {}, onDownloads = {}, onHistory = {}, onUpdate = {},
        )
        val chrome = TouchChrome()
        val current = mutableStateOf<TouchDestination?>(TouchDestination.Home)
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                TMPlayerTheme {
                    Box(Modifier.fillMaxSize().background(Tone.background)) {
                        TouchNavHost(nav, chrome, "home") {
                            CompositionLocalProvider(LocalTouchPageKey provides "home") {
                                TouchPage(TouchPageSpec(current = current.value))
                                Box(Modifier.fillMaxSize())
                            }
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun picture(name: String) {
        val bitmap: Bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        val file = File("../design/phone-bar/$name").absoluteFile
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(file.length() > 0)
    }

    private fun labels() = listOf(L.browseTabHome, L.browseTabChats, L.commonSearch, L.navDownloads)

    private fun check(name: String, fontScale: Float, language: String = Languages.ENGLISH) {
        show(fontScale, language)
        val window = rule.onRoot().fetchSemanticsNode().boundsInRoot
        // The tag sits inside the pill's padding: the pill itself is that much wider each side.
        val inner = rule.onNodeWithTag(BAR_PILL_TAG).fetchSemanticsNode().boundsInRoot
        val pad = PILL_PADDING.value * density
        val pill = androidx.compose.ui.geometry.Rect(inner.left - pad, inner.top, inner.right + pad, inner.bottom)
        val slots = labels().map { rule.onNode(hasContentDescription(it, substring = true)).fetchSemanticsNode().boundsInRoot }
        val wide = slots.map { it.width }
        assertTrue("equal slot widths: $wide", wide.all { abs(it - wide.first()) < 1.5f })
        assertTrue("48 dp targets: $wide", wide.all { it >= 48f * density - 1f } && slots.all { it.height >= 48f * density - 1f })
        // Symmetric ends: the room from the pill's start to the first slot is the last slot's to its end.
        val start = slots.first().left - pill.left
        val end = pill.right - slots.last().right
        assertEquals("pill ends alike (start $start, end $end)", start, end, 1.5f)
        assertEquals("the end room is the pill's padding", PILL_PADDING.value * density, start, 1.5f)
        // Compact: at most 64 dp high and 85 percent of the window (and 520 dp), never past its width less the margins.
        val share = pill.width / window.width
        assertTrue("pill ${pill.height / density} dp high", pill.height <= 64f * density + 1f)
        assertTrue("pill $share of the window", share <= 0.85f + 0.003f && pill.width <= 520f * density + 1f)
        assertTrue("pill within the margins", pill.width <= window.width - 2 * BAR_SIDE_MARGIN.value * density + 1f)
        assertTrue("the highlight is 48 to 52 dp high: ${slots.first().height / density}", slots.first().height in 48f * density - 1f..52f * density + 1f)
        // The usual phone is about 80 percent of the window; a narrow one or a long word takes what it has.
        if (language == Languages.ENGLISH && window.width / density >= 390f && fontScale <= 1.3f) {
            assertTrue("pill about 80 percent: $share", share in 0.70f..0.85f + 0.003f)
        }
        // Highlight room: the button is the same distance from the pill's top and bottom as from its ends.
        val top = slots.first().top - pill.top
        val bottom = pill.bottom - slots.first().bottom
        assertEquals("highlight room top and bottom", top, bottom, 1.5f)
        assertEquals("highlight room all round (top $top, side $start)", top, start, 1.5f)
        // Captions: every one drawn, none overflowing or leaving its slot, all one size; or none at all.
        val sizes = ArrayList<Float>()
        labels().forEachIndexed { i, label ->
            val nodes = rule.onAllNodes(hasText(label), useUnmergedTree = true).fetchSemanticsNodes()
            val found = nodes.firstNotNullOfOrNull { node ->
                val results = ArrayList<TextLayoutResult>()
                node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
                results.firstOrNull()?.let { node to it }
            }
            if (found != null) {
                val (node, layout) = found
                // (Compose's hasVisualOverflow is always true for a text laid out unbounded, so the width
                // it needs, its intrinsic width, is compared with the room it has instead.)
                assertTrue("$label needs ${layout.multiParagraph.maxIntrinsicWidth} px and has ${node.boundsInRoot.width} ($name)", layout.multiParagraph.maxIntrinsicWidth <= node.boundsInRoot.width + 1f)
                val box = node.boundsInRoot
                assertTrue(
                    "$label ($box) inside its slot (${slots[i]}) with room either side ($name)",
                    box.left >= slots[i].left + 1f && box.right <= slots[i].right - 1f,
                )
                sizes += layout.layoutInput.style.fontSize.value
            }
        }
        assertTrue("all four captions or none: $sizes", sizes.size == 4 || sizes.isEmpty())
        assertTrue("one caption size: $sizes", sizes.all { it == sizes.first() })
        if (language == Languages.ENGLISH) assertEquals("English shows its captions at $name", 4, sizes.size)
        picture(name)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w393dp-h873dp-port-440dpi")
    fun `the real phone, font 1_0, English`() = check("bar-393-font1.0-en.png", 1f)

    @Test
    @Config(sdk = [35], qualifiers = "w393dp-h873dp-port-440dpi")
    fun `the real phone, font 1_3, English`() = check("bar-393-font1.3-en.png", 1.3f)

    @Test
    @Config(sdk = [35], qualifiers = "w393dp-h873dp-port-440dpi")
    fun `the real phone, font 2_0, English`() = check("bar-393-font2.0-en.png", 2f)

    @Test
    @Config(sdk = [35], qualifiers = "w393dp-h873dp-port-440dpi")
    fun `the real phone, font 1_0, German`() = check("bar-393-font1.0-de.png", 1f, "de")

    @Test
    @Config(sdk = [35], qualifiers = "w393dp-h873dp-port-440dpi")
    fun `the real phone, font 1_3, the longest pseudo language`() = check("bar-393-font1.3-pseudo.png", 1.3f, Languages.PSEUDO)

    @Test
    @Config(sdk = [35], qualifiers = "w360dp-h800dp-port-420dpi")
    fun `a 360 dp phone, font 1_0, English`() = check("bar-360-font1.0-en.png", 1f)

    @Test
    @Config(sdk = [35], qualifiers = "w360dp-h800dp-port-420dpi")
    fun `a 360 dp phone, font 1_3, English`() = check("bar-360-font1.3-en.png", 1.3f)

    @Test
    @Config(sdk = [35], qualifiers = "w320dp-h640dp-port-420dpi")
    fun `a 320 dp phone, font 1_0, English`() = check("bar-320-font1.0-en.png", 1f)

    @Test
    @Config(sdk = [35], qualifiers = "w393dp-h873dp-port-440dpi")
    fun `a 14 character label steps all four captions down together and never clips`() {
        var wide: BarSizing? = null
        var narrow: BarSizing? = null
        var tooLong: BarSizing? = null
        rule.setContent {
            TMPlayerTheme {
                val measurer = rememberTextMeasurer()
                val d = Density(2.75f, 1f)
                val base = captionStyle()
                val words = listOf("Home", "Chats", "Search", "Downloads")
                wide = sizeBar(words, 393.dp, 393.dp - 24.dp, d, measurer, base)
                narrow = sizeBar(words + "Herunterladen!", 393.dp, 393.dp - 24.dp, d, measurer, base)
                tooLong = sizeBar(words + "A very very long translated word", 393.dp, 393.dp - 24.dp, d, measurer, base)
            }
        }
        rule.waitForIdle()
        val cap = 393.dp - 24.dp
        val fixed = PILL_PADDING * 2 + BAR_SLOT_GAP * (BAR_BUTTONS - 1)
        for (sizing in listOfNotNull(wide, narrow, tooLong)) {
            assertTrue("pill ${sizing.width} within $cap", sizing.width <= cap + 0.01.dp)
            assertTrue("pill ${sizing.width} within 85 percent of the window", sizing.width <= 393.dp * 0.85f + 0.01.dp)
            assertEquals("four equal slots make the pill", (sizing.slot * BAR_BUTTONS + fixed).value, sizing.width.value, 0.05f)
            assertTrue("a 48 dp target at the least: ${sizing.slot}", sizing.slot >= 48.dp)
        }
        assertEquals("the full size when the words fit", BAR_CAPTION_SP.toFloat(), wide?.caption?.value ?: -1f, 0.01f)
        assertTrue("the pill is about 80 percent of the window: ${wide?.width}", (wide?.width ?: 0.dp) in 393.dp * 0.7f..393.dp * 0.85f)
        val n = narrow?.caption
        assertNotNull("a 14 character word still gets captions", n)
        assertTrue("one of the steps: $n", BAR_CAPTION_STEPS_SP.any { it.toFloat() == n!!.value })
        assertNull("a word that fits no step takes the captions from all", tooLong?.caption)
    }
}
