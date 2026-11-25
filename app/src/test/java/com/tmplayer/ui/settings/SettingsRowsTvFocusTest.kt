package com.tmplayer.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.unit.Dp
import androidx.compose.runtime.SideEffect
import com.tmplayer.ui.theme.contrastRatio
import com.tmplayer.ui.theme.focusRingColor
import kotlin.math.abs
import com.tmplayer.data.AppTypeface
import com.tmplayer.data.ChatGate
import com.tmplayer.data.FormFactor
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.ThemeChoice
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.components.ValueChevronLook
import com.tmplayer.ui.theme.TMPlayerTheme
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.flow.first
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

/**
 * The television's Settings rows under the remote (WP-06): rows that remove themselves hand the
 * remote to the next row, a stepper moves with Right and Left, OK takes the next value except on
 * Interface size, a stepper keeps its height under focus, the switch rows are switches to a screen
 * reader, Right on Theme moves no row, and the chevrons dim at the ends.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
class SettingsRowsTvFocusTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    private val settings get() = SettingsStore(RuntimeEnvironment.getApplication())

    @After
    fun tearDown() {
        FormFactor.override(false)
    }

    /** [focusRingColor] as the theme on screen draws it, read from inside the composition. */
    private var ringColour: Color = Color.Unspecified
    private var mutedColour: Color = Color.Unspecified
    private var surfaceColour: Color = Color.Unspecified
    private var fillColour: Color = Color.Unspecified

    private fun show(page: SettingsPage) {
        FormFactor.override(true)
        rule.setContent {
            TMPlayerTheme {
                val ring = focusRingColor()
                val muted = Tone.muted
                val surface = Tone.surface
                val fill = Tone.focusFill
                SideEffect { ringColour = ring; mutedColour = muted; surfaceColour = surface; fillColour = fill }
                Box(Modifier.fillMaxSize().background(Tone.background)) {
                    SettingsScreen(chats = emptyList(), onLoggedOut = {}, initialPage = page, accountFolders = emptyList())
                }
            }
        }
        rule.waitForIdle()
        // A window the remote works in: out of touch mode and holding focus, as on a television.
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
    }

    /** The row titled [title] as a node the remote can be on. */
    private fun row(title: String) = hasText(title) and androidx.compose.ui.test.isFocusable()

    /**
     * Brings the row [matcher] finds into the lazy list the way a remote does: Down (then Up) from the
     * focused row until it is composed. `performScrollToNode` never returned on the Appearance page (the
     * idle check kept spinning once the list had scrolled), and a remote is what the tests are about.
     */
    private fun reveal(matcher: SemanticsMatcher) {
        fun there() = rule.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
        if (there()) return
        for (key in listOf(Key.DirectionDown, Key.DirectionUp)) {
            repeat(60) {
                if (there()) return
                val focusedNow = rule.onAllNodes(isFocused()).fetchSemanticsNodes()
                if (focusedNow.isEmpty()) return
                rule.onAllNodes(isFocused()).onFirst().performKeyInput { pressKey(key) }
                rule.waitForIdle()
            }
        }
    }

    private fun focus(title: String) {
        reveal(row(title))
        rule.onNode(row(title)).requestFocus()
        rule.onNode(row(title)).assertIsFocused()
    }

    private fun press(title: String, key: Key) {
        rule.onNode(row(title)).performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    /** The prompt's Clear, by the remote (the TV button takes keys, not taps). */
    private fun confirmClear() {
        val clear = hasText("Clear") and hasClickAction()
        rule.onNode(clear).requestFocus()
        rule.onNode(clear).performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()
    }

    private fun focused(title: String): Boolean = rule.onAllNodes(row(title) and isFocused()).fetchSemanticsNodes().isNotEmpty()

    private fun waitFocused(title: String) {
        rule.waitUntil(5_000) { rule.waitForIdle(); focused(title) }
        rule.onNode(row(title)).assertIsFocused()
    }

    private fun seedLibrary(favourites: Boolean, history: Boolean, lastChat: Boolean) = runBlocking {
        ChatGate.set(null)
        val store = settings
        if (favourites) store.toggleFavorite(3L)
        if (history) {
            val line = com.tmplayer.data.ResumeRecord.encode(
                fileId = 4, title = "Coast S01E04", chatTitle = "Nature", sizeBytes = 1_000_000,
                durationSec = 2_640, updatedAt = System.currentTimeMillis(),
            )
            store.saveResumePosition(-100L, 4L, 1_320_000L, 2_640_000L, description = line)
        }
        if (lastChat) {
            store.setOpenLastChat(true)
            store.rememberChatOpened(5L)
        }
    }

    // ---- rows that remove themselves ---------------------------------------------------------

    @Test
    fun `Clear favourites, confirmed, leaves the remote on Clear history under it`() {
        seedLibrary(favourites = true, history = true, lastChat = false)
        show(SettingsPage.Library)
        focus("Clear favourites")
        press("Clear favourites", Key.Enter)
        assertEquals("the prompt is open", 1, rule.onAllNodesWithText("Clear favourites?").fetchSemanticsNodes().size)
        // One prompt at a time: a second press on the row while it is open asks nothing more.
        rule.onNode(row("Clear favourites")).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        rule.waitForIdle()
        assertEquals("still one prompt", 1, rule.onAllNodesWithText("Clear favourites?").fetchSemanticsNodes().size)
        assertEquals("with one Clear", 1, rule.onAllNodes(hasText("Clear") and hasClickAction()).fetchSemanticsNodes().size)
        confirmClear()
        rule.waitForIdle()
        assertEquals("answered once, it is gone", 0, rule.onAllNodesWithText("Clear favourites?").fetchSemanticsNodes().size)
        waitFocused("Clear history")
        assertTrue(runBlocking { settings.favorites.first().isEmpty() })
        assertEquals(0, rule.onAllNodesWithText("Clear favourites").fetchSemanticsNodes().size)
    }

    @Test
    fun `Clear favourites with no Clear history leaves the remote on the next row, or the row above`() {
        seedLibrary(favourites = true, history = false, lastChat = false)
        show(SettingsPage.Library)
        focus("Clear favourites")
        press("Clear favourites", Key.Enter)
        confirmClear()
        rule.waitForIdle()
        // No Clear history here: the posters switch under it, or in a build without the lookups the row above.
        rule.waitUntil(5_000) { rule.waitForIdle(); focused("Show posters and overviews") || focused("Chosen chats") }
        assertEquals(0, rule.onAllNodesWithText("Clear favourites").fetchSemanticsNodes().size)
    }

    @Test
    fun `Forget it leaves the remote on Remember searches under it`() {
        seedLibrary(favourites = false, history = false, lastChat = true)
        show(SettingsPage.Library)
        focus("Forget it")
        press("Forget it", Key.Enter)
        waitFocused("Remember searches")
        rule.waitUntil(5_000) { runBlocking { settings.lastChatId.first() } == 0L }
        assertEquals(0, rule.onAllNodesWithText("Forget it").fetchSemanticsNodes().size)
    }

    @Test
    fun `Clear history, confirmed, leaves the remote on the next row, or the row above`() {
        seedLibrary(favourites = false, history = true, lastChat = false)
        show(SettingsPage.Library)
        focus("Clear history")
        press("Clear history", Key.Enter)
        confirmClear()
        rule.waitForIdle()
        // The posters switch under it; a build without the lookups has none, and the remote goes up to Chosen chats.
        rule.waitUntil(5_000) { rule.waitForIdle(); focused("Show posters and overviews") || focused("Chosen chats") }
    }

    // ---- steppers ----------------------------------------------------------------------------

    @Test
    fun `Right changes a safe stepper, OK cycles it, and its height does not change under the remote`() {
        show(SettingsPage.Appearance)
        reveal(row("Typeface"))
        val atRest = rule.onNode(row("Typeface")).getBoundsInRoot().let { it.bottom - it.top }
        focus("Typeface")
        val focusedHeight = rule.onNode(row("Typeface")).getBoundsInRoot().let { it.bottom - it.top }
        assertEquals("the row keeps its height under the remote", atRest.value, focusedHeight.value, 0.5f)

        assertEquals(AppTypeface.System, runBlocking { settings.typeface.first() })
        press("Typeface", Key.DirectionRight)
        rule.waitUntil(5_000) { runBlocking { settings.typeface.first() } == AppTypeface.Serif }
        // OK takes the next value, and from the last one goes round to the first.
        press("Typeface", Key.Enter)
        rule.waitUntil(5_000) { runBlocking { settings.typeface.first() } == AppTypeface.Typewriter }
        press("Typeface", Key.Enter)
        rule.waitUntil(5_000) { runBlocking { settings.typeface.first() } == AppTypeface.System }
        // Left at the first value changes nothing.
        press("Typeface", Key.DirectionLeft)
        assertEquals(AppTypeface.System, runBlocking { settings.typeface.first() })
        // Down leaves the row.
        press("Typeface", Key.DirectionDown)
        rule.onAllNodes(row("Typeface") and isFocused()).assertCountEquals(0)
    }

    /** A button of the "Keep this size?" prompt, by the remote (the TV button takes keys, not taps). */
    private fun answer(label: String) {
        val button = hasText(label) and hasClickAction()
        rule.onNode(button).requestFocus()
        rule.onNode(button).performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()
    }

    private fun scale() = runBlocking { settings.uiScale.first() }

    @Test
    fun `OK on Interface size steps it and asks Keep this size, Undo and the countdown put it back, Keep keeps it (WP-10)`() {
        show(SettingsPage.Appearance)
        focus("Interface size")
        val start = scale()
        press("Interface size", Key.Enter)
        rule.waitUntil(5_000) { scale() != start }
        // The size applied at once, and the question is up with its countdown (it follows the store write by a frame).
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("Keep this size?")).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(hasText("Keep this size?")).assertCountEquals(1)
        rule.onAllNodes(hasText("It changes back in 10 s", substring = true) or hasText("It changes back in 9 s", substring = true)).assertCountEquals(1)
        answer("Undo")
        rule.waitUntil(5_000) { scale() == start }
        rule.onAllNodes(hasText("Keep this size?")).assertCountEquals(0)
        assertEquals("a size put back leaves no mark", null, runBlocking { settings.revertUiScale() })

        // Right asks too; Keep keeps it and clears the mark.
        press("Interface size", Key.DirectionRight)
        rule.waitUntil(5_000) { scale() != start }
        val tried = scale()
        answer("Keep")
        rule.onAllNodes(hasText("Keep this size?")).assertCountEquals(0)
        assertEquals(tried, scale())
        assertEquals("kept: nothing to put back", null, runBlocking { settings.revertUiScale() })

        // No answer for 10 s: the old size comes back by itself.
        press("Interface size", Key.DirectionLeft)
        rule.waitUntil(5_000) { scale() != tried }
        // The countdown starts when the screen has the answer to the step (after the store write): wait for the question, or a slow runner moves the clock before it starts.
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("Keep this size?")).fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(11))
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitUntil(5_000) { scale() == tried }
        rule.onAllNodes(hasText("Keep this size?")).assertCountEquals(0)
    }

    @Test
    fun `the chevrons dim at the first and last value`() {
        assertEquals(ValueChevronLook.DimAlpha, ValueChevronLook.alpha(available = false, focused = true), 0f)
        assertEquals(ValueChevronLook.DimAlpha, ValueChevronLook.alpha(available = false, focused = false), 0f)
        assertEquals(1f, ValueChevronLook.alpha(available = true, focused = true), 0f)
        assertEquals(ValueChevronLook.RestAlpha, ValueChevronLook.alpha(available = true, focused = false), 0f)
        assertTrue(ValueChevronLook.RestAlpha < 1f && ValueChevronLook.DimAlpha < ValueChevronLook.RestAlpha)
    }

    // ---- switches ----------------------------------------------------------------------------

    @Test
    fun `a switch row is a switch with its state`() {
        show(SettingsPage.Appearance)
        reveal(row("Reduce motion"))
        val node = rule.onNode(row("Reduce motion"))
        assertEquals(Role.Switch, node.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Role))
        node.assertIsOff()
        focus("Reduce motion")
        press("Reduce motion", Key.Enter)
        rule.waitUntil(5_000) { runBlocking { settings.reduceMotion.first() } }
        rule.onNode(row("Reduce motion")).assertIsOn()
    }

    // ---- nothing moves under Theme -----------------------------------------------------------

    @Test
    fun `Right on Theme moves no row, and Pure black stays under Light, faded and skipped`() {
        runBlocking { settings.setThemeChoice(ThemeChoice.Dark) }
        show(SettingsPage.Appearance)
        // Typeface first: the remote walks down to it, so Pure black above it is composed as well.
        reveal(row("Typeface"))
        reveal(row("Pure black"))
        val tops = listOf("Pure black", "Typeface", "Interface size").filter { rule.onAllNodes(hasText(it)).fetchSemanticsNodes().isNotEmpty() }.associateWith { top(it) }
        assertTrue("the rows under Theme are in view: $tops", tops.size >= 2)
        focus("Theme")
        // Dark to Light is the move that used to fold Pure black away.
        val before = runBlocking { settings.themeChoice.first() }
        assertEquals(ThemeChoice.Dark, before)
        press("Theme", Key.DirectionLeft)
        rule.waitUntil(5_000) { runBlocking { settings.themeChoice.first() } == ThemeChoice.Light }
        rule.waitForIdle()
        for ((title, was) in tops) assertEquals("$title did not move", was.value, top(title).value, 0.5f)
        rule.onNodeWithText("Only with the dark theme").assertExists()
        // The remote skips it: Down from Theme goes past Pure black.
        rule.onAllNodes(hasText("Pure black")).onFirst().assertExists()
        press("Theme", Key.DirectionDown)
        rule.onAllNodes(hasText("Pure black") and isFocused()).assertCountEquals(0)
    }

    // ---- the ring ----------------------------------------------------------------------------

    /**
     * The distinct colours down the row titled [title]'s middle column over its top 10 px, past
     * the outermost pixel (anti-aliasing): a fill alone is one colour, a fill with a ring is two or more.
     */
    private fun topEdgeColours(title: String): Set<Int> {
        val image = rule.onNode(row(title)).captureToImage().toPixelMap()
        val x = image.width / 2
        return (2 until minOf(10, image.height)).map { y -> image[x, y].toArgb() }.toSet()
    }

    /** One indicator per row (user, 2026-11-25): the fill alone, or with High contrast focus the ring bands too. */
    private fun indicatorIn(theme: ThemeChoice, high: Boolean) {
        runBlocking { settings.setThemeChoice(theme); settings.setHighContrastFocus(high) }
        show(SettingsPage.Appearance)
        // Language and the row under it are both on screen at the top of the page: the remote is on the first.
        focus("Language")
        rule.waitForIdle()
        val focusedColours = topEdgeColours("Language")
        if (high) {
            assertTrue("High contrast focus adds its ring bands to the fill ($theme): $focusedColours", focusedColours.size >= 2)
        } else {
            assertTrue("the focused row is the fill alone, no ring on it ($theme): $focusedColours", focusedColours == setOf(fillColour.toArgb()))
        }
        assertTrue("a row the remote is not on has no fill ($theme, high contrast $high)", fillColour.toArgb() !in topEdgeColours("Theme"))
    }

    @Test
    fun `a focused row is the fill alone in the Light theme`() = indicatorIn(ThemeChoice.Light, high = false)

    @Test
    fun `a focused row is the fill alone in the Dark theme`() = indicatorIn(ThemeChoice.Dark, high = false)

    @Test
    fun `a focused row adds the ring bands with High contrast focus`() = indicatorIn(ThemeChoice.Dark, high = true)

    @Test
    fun `a focused stepper is the fill alone too`() {
        runBlocking { settings.setThemeChoice(ThemeChoice.Light) }
        show(SettingsPage.Appearance)
        focus("Typeface")
        rule.waitForIdle()
        assertTrue("the stepper draws one indicator: ${topEdgeColours("Typeface")}", topEdgeColours("Typeface") == setOf(fillColour.toArgb()))
    }

    @Test
    fun `the faded Pure black row keeps 4_5 to 1 for its words`() {
        runBlocking { settings.setThemeChoice(ThemeChoice.Dark) }
        show(SettingsPage.Appearance)
        reveal(row("Pure black"))
        focus("Theme")
        press("Theme", Key.DirectionLeft)
        rule.waitUntil(5_000) { rule.waitForIdle(); rule.onAllNodesWithText("Only with the dark theme").fetchSemanticsNodes().isNotEmpty() }
        // The tokens: the muted colour on the row's own fill.
        assertTrue("muted on the row ${contrastRatio(mutedColour, surfaceColour)}", contrastRatio(mutedColour, surfaceColour) >= 4.5f)
        // And the pixels: the strongest pixel of the words against the row's fill, read off the drawn row.
        val image = rule.onNode(hasText("Pure black")).captureToImage().toPixelMap()
        val bg = image[image.width / 2, image.height / 2]
        var best = 1f
        for (y in 0 until image.height) for (x in image.width / 8 until image.width / 3) best = maxOf(best, contrastRatio(bg, image[x, y]))
        assertTrue("the words read at $best:1", best >= 4.5f)
    }

    private fun top(title: String): Dp = rule.onNode(hasText(title)).getBoundsInRoot().top
}
