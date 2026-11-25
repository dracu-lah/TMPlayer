package com.tmplayer.ui.settings

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import com.tmplayer.data.FormFactor
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.ThemeChoice
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.TMPlayerTheme
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.Tv
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The television's Settings rail and pane drawn (WP-07), at 960 x 540 dp on a 1080p panel and on a
 * 960 x 540 pixel one, Dark and Light, right to left and at 1.3 times the font: frames in
 * `build/device-test/review/`, each held to numbers. The header is pinned above the pane and the
 * focused row never sits under it; the 9 rail items are each 48 dp and inside the safe area; one
 * frame holds a rest, a selected and a focused rail item, told apart by their pixels (the focus
 * fill on the focused one, the accent bar on the selected one's leading edge, neither on the rest);
 * no text in the header or the rail is below 14 sp.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsPaneTvRenderTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    private val settings get() = SettingsStore(RuntimeEnvironment.getApplication())

    private var fill = Color.Unspecified
    private var accent = Color.Unspecified
    private var page = Color.Unspecified

    @After
    fun tearDown() {
        FormFactor.override(false)
        Motion.reduced = false
        SettingsRail.last = null
        runBlocking { settings.setThemeChoice(ThemeChoice.Default) }
    }

    private val density get() = rule.activity.resources.displayMetrics.density

    private fun open(light: Boolean = false, fontScale: Float = 1f) {
        FormFactor.override(true)
        Motion.reduced = true
        SettingsRail.last = null
        runBlocking { settings.setThemeChoice(if (light) ThemeChoice.Light else ThemeChoice.Dark) }
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                TMPlayerTheme {
                    fill = Tone.focusFill
                    accent = Tone.accent
                    page = Tone.background
                    Box(Modifier.fillMaxSize().background(Tone.background)) {
                        SettingsScreen(chats = emptyList(), onLoggedOut = {}, accountFolders = emptyList())
                    }
                }
            }
        }
        rule.waitForIdle()
        val viewRoot = rule.activity.window.decorView.parent
        org.robolectric.util.ReflectionHelpers.callInstanceMethod<Any>(
            viewRoot, "ensureTouchMode",
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, false),
        )
        org.robolectric.util.ReflectionHelpers.callInstanceMethod<Any>(
            viewRoot, "windowFocusChanged",
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, true),
        )
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
    }

    private fun frame(out: String): Bitmap {
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        val file = File("../build/device-test/review/$out").absoluteFile
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(file.length() > 0)
        return bitmap
    }

    private fun bounds(tag: String): Rect = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private fun near(a: Color, b: Color, tolerance: Float = 0.06f): Boolean =
        kotlin.math.abs(a.red - b.red) < tolerance && kotlin.math.abs(a.green - b.green) < tolerance &&
            kotlin.math.abs(a.blue - b.blue) < tolerance

    private fun pixel(frame: Bitmap, x: Float, y: Float): Color =
        Color(frame.getPixel(x.toInt().coerceIn(0, frame.width - 1), y.toInt().coerceIn(0, frame.height - 1)))

    /**
     * The remote on Playback, then one Down caught before the pane follows (the 150 ms settle):
     * Subtitles and audio focused, Playback still selected, Account at rest, in one frame.
     */
    private fun threeStates(name: String, rtl: Boolean = false): Bitmap {
        rule.onNodeWithTag(railTag(SettingsPage.Playback)).assertExists()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag(railTag(SettingsPage.Playback)).performKeyInput { pressKey(Key.DirectionDown) }
        rule.mainClock.advanceTimeBy(60)
        val frame = frame(name)
        val focused = rule.onNodeWithTag(railTag(SettingsPage.Subtitles)).fetchSemanticsNode()
        assertEquals(true, focused.config.getOrNull(SemanticsProperties.Focused))
        val selected = bounds(railTag(SettingsPage.Playback))
        val rest = bounds(railTag(SettingsPage.Accounts))
        val inside = 8f * density
        val lead = { r: Rect -> if (rtl) r.right - 1.5f * density else r.left + 1.5f * density }
        val trail = { r: Rect -> if (rtl) r.left + inside else r.right - inside }
        val subtitles = focused.boundsInRoot
        assertTrue("$name: the focused item has the focus fill", near(pixel(frame, trail(subtitles), subtitles.center.y), fill))
        assertTrue("$name: the selected item has the accent bar on its leading edge", near(pixel(frame, lead(selected), selected.center.y), accent))
        assertTrue("$name: the selected item has no focus fill", near(pixel(frame, trail(selected), selected.center.y), page))
        assertTrue("$name: the rest item has no bar", !near(pixel(frame, lead(rest), rest.center.y), accent))
        assertTrue("$name: the rest item has no fill", near(pixel(frame, trail(rest), rest.center.y), page))
        rule.mainClock.autoAdvance = true
        rule.mainClock.advanceTimeBy(400)
        rule.waitForIdle()
        return frame
    }

    /** The rail: 9 items, each at least 48 dp, all inside the safe area; the rail on the leading side. */
    private fun assertRail(rtl: Boolean = false) {
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val items = SettingsRail.pages().map { bounds(railTag(it)) }
        assertEquals(9, items.size)
        val safeH = Tv.SafeH.value * density
        val safeV = Tv.SafeV.value * density
        for (item in items) {
            assertTrue("each rail item is 48 dp, got ${item.height / density}", item.height >= 48f * density - 1f)
            assertTrue("inside the safe area at the top", item.top >= safeV - 1f)
            assertTrue("inside the safe area at the foot, ${item.bottom} of ${root.bottom}", item.bottom <= root.bottom - safeV + 1f)
            assertTrue("inside the safe area at the sides", item.left >= safeH - 1f && item.right <= root.right - safeH + 1f)
        }
        val rail = bounds(SETTINGS_RAIL_TAG)
        if (rtl) assertTrue("right to left: the rail on the right", rail.center.x > root.center.x)
        else assertTrue("the rail on the left", rail.center.x < root.center.x)
    }

    /** Into the pane: the focused row's top is never above the pinned header's foot. */
    private fun assertHeaderPinned(rtl: Boolean = false) {
        rule.onNodeWithTag(railTag(SettingsPage.Subtitles)).performKeyInput { pressKey(if (rtl) Key.DirectionLeft else Key.DirectionRight) }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
        val header = bounds(SETTINGS_HEADER_TAG)
        assertTrue("the header is 48 dp", header.height <= 48f * density + 1f)
        // Down a few rows, so the list has scrolled under where a sticky header would be.
        repeat(6) {
            val focused = rule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Focused, true)).fetchSemanticsNode()
            assertTrue("the focused row's top ${focused.boundsInRoot.top} under the header's foot ${header.bottom}", focused.boundsInRoot.top >= header.bottom - 1f)
            rule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Focused, true)).performKeyInput { pressKey(Key.DirectionDown) }
            rule.waitForIdle()
        }
        // No empty band under the pane: the page's list runs down to the detail strip (WP-74), which
        // ends at the pane's foot (F-27).
        val pane = bounds(SETTINGS_PANE_TAG)
        val list = rule.onNode(hasAnyAncestor(hasTestTag(SETTINGS_PANE_TAG)) and SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))
            .fetchSemanticsNode().boundsInRoot
        val strip = bounds(SETTINGS_DETAIL_TAG)
        assertTrue("the strip ends at the pane's foot: strip ${strip.bottom}, pane ${pane.bottom}", kotlin.math.abs(strip.bottom - pane.bottom) <= 1f)
        assertTrue("the page's list reaches the strip: list ${list.bottom}, strip ${strip.top}", list.bottom >= strip.top - 1f)
    }

    /** No text in the header or the rail below 14 sp; the rail's words on one line. */
    private fun assertText(fontScale: Float = 1f) {
        for (tag in listOf(SETTINGS_HEADER_TAG, SETTINGS_RAIL_TAG)) {
            val nodes = rule.onAllNodes(
                hasAnyAncestor(hasTestTag(tag)) and SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult),
                useUnmergedTree = true,
            ).fetchSemanticsNodes()
            assertTrue("$tag has text", nodes.isNotEmpty())
            for (node in nodes) {
                val layouts = mutableListOf<TextLayoutResult>()
                node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
                val layout = layouts.firstOrNull() ?: continue
                val sp = layout.layoutInput.style.fontSize.value
                assertTrue("$tag: ${layout.layoutInput.text} at $sp sp", sp >= 14f)
                // The header's words stay on one line; a rail label may wrap to a second (never an ellipsis at 1.0, TV fix 2026-11-25).
                val most = if (tag == SETTINGS_RAIL_TAG) 2 else 1
                assertTrue("$tag: ${layout.layoutInput.text} on at most $most line(s) at $fontScale, got ${layout.lineCount}", layout.lineCount <= most)
            }
        }
    }

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
    fun `dark at 1920 by 1080`() {
        open()
        assertRail()
        assertText()
        threeStates("settings-pane-tv-dark-1080.png")
        assertHeaderPinned()
        frame("settings-pane-tv-dark-1080-pane.png")
    }

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-mdpi")
    fun `dark at 960 by 540 pixels`() {
        open()
        assertRail()
        threeStates("settings-pane-tv-dark-540.png")
        assertHeaderPinned()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-notnight-xhdpi")
    fun `light at 1920 by 1080`() {
        open(light = true)
        assertRail()
        threeStates("settings-pane-tv-light-1080.png")
        assertHeaderPinned()
    }

    @Test
    @Config(sdk = [35], qualifiers = "ar-ldrtl-w960dp-h540dp-land-television-night-xhdpi")
    fun `right to left puts the rail on the right with its bar mirrored`() {
        open()
        assertRail(rtl = true)
        threeStates("settings-pane-tv-rtl-1080.png", rtl = true)
        assertHeaderPinned(rtl = true)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
    fun `at 1_3 times the font the rail keeps 48 dp items on one line`() {
        open(fontScale = 1.3f)
        assertRail()
        assertText(fontScale = 1.3f)
        frame("settings-pane-tv-font13-1080.png")
        assertHeaderPinned()
    }
}
