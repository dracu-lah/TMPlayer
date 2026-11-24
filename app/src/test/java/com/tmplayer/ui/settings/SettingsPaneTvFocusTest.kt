package com.tmplayer.ui.settings

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.Density
import com.tmplayer.data.ChatGate
import com.tmplayer.data.FormFactor
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.UiScale
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.TMPlayerTheme
import com.tmplayer.ui.theme.UiScaled
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.runBlocking
import org.junit.After
import androidx.compose.ui.semantics.getOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The television's Settings rail and pane by remote (WP-07): Settings opens on Playback in the rail
 * (or the category used last), the pane follows the rail once it rests, Right and OK go into the
 * pane on its first row, Left comes back to the open category drawn selected, Back goes from the
 * pane to the rail and from the rail out of Settings, the rail's ends and far side hold, and a held
 * Down over the rail shows one page when it stops, not every page it passed.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
class SettingsPaneTvFocusTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    /** Back that reached the caller: Settings left. */
    private var leaves = 0

    /** Every page the screen told its caller about, in order. */
    private val pagesShown = mutableListOf<SettingsPage?>()

    @Before
    fun setUp() {
        SettingsRail.last = null
        Motion.reduced = true
    }

    @After
    fun tearDown() {
        FormFactor.override(false)
        SettingsRail.last = null
        Motion.reduced = false
    }

    private fun show(page: SettingsPage? = null, scale: UiScale = UiScale.Default, font: Float = 1f) {
        FormFactor.override(true)
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, font)) {
                // The interface size multiplies the density as the app does (UiScaled).
                UiScaled(scale, keepTextSize = true) {
                    TMPlayerTheme {
                        // The caller's own Back, composed first as MainActivity's is: it leaves Settings.
                        BackHandler { leaves++ }
                        Box(Modifier.fillMaxSize().background(Tone.background)) {
                            SettingsScreen(
                                chats = emptyList(),
                                onLoggedOut = {},
                                initialPage = page,
                                onPageChange = { pagesShown += it },
                                accountFolders = emptyList(),
                            )
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        // A window the remote works in, as on a television.
        val viewRoot = rule.activity.window.decorView.parent
        org.robolectric.util.ReflectionHelpers.callInstanceMethod<Any>(
            viewRoot, "ensureTouchMode",
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, false),
        )
        org.robolectric.util.ReflectionHelpers.callInstanceMethod<Any>(
            viewRoot, "windowFocusChanged",
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, true),
        )
        settle()
    }

    private fun settle(ms: Long = 500) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private fun rail(page: SettingsPage): SemanticsNodeInteraction = rule.onNodeWithTag(railTag(page))

    private fun row(title: String): SemanticsNodeInteraction = rule.onNode(hasText(title) and hasClickAction())

    private fun SemanticsNodeInteraction.press(key: Key) {
        performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    private fun back() {
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        settle()
    }

    /**
     * A row further down the pane than it shows, reached as a remote does: Down from the focused
     * row until it has the remote (a lazy list composes only what it shows).
     */
    private fun reach(title: String) {
        var presses = 0
        while (rule.onAllNodes(hasText(title) and hasClickAction() and androidx.compose.ui.test.isFocused()).fetchSemanticsNodes().isEmpty()) {
            check(presses++ < 30) { "$title not reached" }
            rule.onNode(androidx.compose.ui.test.isFocused()).performKeyInput { pressKey(Key.DirectionDown) }
            rule.waitForIdle()
        }
    }

    private fun crumb(): String = rule.onNodeWithTag(SETTINGS_CRUMB_TAG, useUnmergedTree = true).fetchSemanticsNode()
        .config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString { it.text }

    @Test
    fun `nine categories, the remote on Playback, the pane follows Down, Right enters, Left comes back selected`() {
        show()
        // Nine on a television, in the shared order (WP-08).
        assertEquals(
            listOf(
                SettingsPage.Accounts, SettingsPage.Playback, SettingsPage.Subtitles, SettingsPage.Storage,
                SettingsPage.Appearance, SettingsPage.Library, SettingsPage.Privacy, SettingsPage.Help, SettingsPage.About,
            ),
            SettingsRail.pages(),
        )
        rail(SettingsPage.Playback).assertIsFocused().assertIsSelected()
        rule.onNodeWithTag(railTag(SettingsPage.Notifications)).assertDoesNotExist()
        row("Play the next episode automatically").assertExists()

        rail(SettingsPage.Playback).press(Key.DirectionDown)
        settle()
        rail(SettingsPage.Subtitles).assertIsFocused().assertIsSelected()
        rail(SettingsPage.Playback).assertIsNotSelected()
        assertEquals("Subtitles and audio", crumb())
        row("Subtitle style").assertExists()
        // The pane followed, the remote stayed on the rail.
        row("Subtitle style").assertIsNotFocused()

        rail(SettingsPage.Subtitles).press(Key.DirectionRight)
        settle()
        row("Subtitle style").assertIsFocused()

        row("Subtitle style").press(Key.DirectionLeft)
        settle()
        rail(SettingsPage.Subtitles).assertIsFocused().assertIsSelected()
    }

    @Test
    fun `the strip shows the focused row's sentence and is empty with the remote on the rail (WP-74)`() {
        show()
        fun strip(): String = rule.onNodeWithTag(SETTINGS_DETAIL_TAG, useUnmergedTree = true).fetchSemanticsNode().children
            .firstNotNullOfOrNull { it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text) }
            ?.joinToString { it.text } ?: ""
        assertEquals("", strip())
        rail(SettingsPage.Playback).press(Key.DirectionRight)
        settle()
        // A row's sentence moved out of the row into the strip: it is the focused row's own.
        val first = strip()
        assertTrue("the first Playback row has a sentence", first.isNotBlank())
        rule.onNode(isFocused()).press(Key.DirectionDown)
        settle()
        assertTrue("the next row writes its own", strip().isNotBlank() && strip() != first)
        rule.onNode(isFocused()).press(Key.DirectionLeft)
        settle()
        rail(SettingsPage.Playback).assertIsFocused()
        assertEquals("", strip())
    }

    /** The words of the row the remote is on. */
    private fun focusedText(): String = rule.onNode(isFocused()).fetchSemanticsNode().config
        .getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.joinToString { it.text } ?: ""

    @Test
    fun `Right from a rail item lands on the row used last in that category (WP-75)`() {
        show()
        rail(SettingsPage.Playback).press(Key.DirectionRight)
        settle()
        val first = focusedText()
        repeat(3) { rule.onNode(isFocused()).press(Key.DirectionDown) }
        settle()
        val fourth = focusedText()
        assertTrue("three Down moved off the first row", fourth != first)
        // Left, over to Subtitles and audio (the pane follows), in, and back out.
        rule.onNode(isFocused()).press(Key.DirectionLeft)
        settle()
        rail(SettingsPage.Playback).assertIsFocused()
        rail(SettingsPage.Playback).press(Key.DirectionDown)
        settle()
        rail(SettingsPage.Subtitles).press(Key.DirectionRight)
        settle()
        val subtitlesFirst = focusedText()
        rule.onNode(isFocused()).press(Key.DirectionLeft)
        settle()
        rail(SettingsPage.Subtitles).press(Key.DirectionUp)
        settle()
        rail(SettingsPage.Playback).assertIsFocused()
        // The first time into a category is its first row; the second time, the row left on.
        rail(SettingsPage.Playback).press(Key.DirectionRight)
        settle()
        assertEquals(fourth, focusedText())
        // Subtitles and audio, once more: the row it was left on, which was its first.
        rule.onNode(isFocused()).press(Key.DirectionLeft)
        settle()
        rail(SettingsPage.Playback).press(Key.DirectionDown)
        settle()
        rail(SettingsPage.Subtitles).press(Key.DirectionRight)
        settle()
        assertEquals(subtitlesFirst, focusedText())
    }

    @Test
    fun `Page Down jumps to the first row of the next section, Page Up back, and the hint waits for a Page key (WP-75)`() {
        show()
        val hint = "Page or Channel keys jump between sections"
        rail(SettingsPage.Playback).press(Key.DirectionDown)
        settle()
        rail(SettingsPage.Subtitles).press(Key.DirectionRight)
        settle()
        val first = focusedText()
        val firstSection = crumb()
        rule.onAllNodesWithText(hint).assertCountEquals(0)
        rule.onNode(isFocused()).press(Key.PageDown)
        settle()
        val second = focusedText()
        assertTrue("Page Down moved the remote to another section's first row", second != first)
        assertTrue("it is in another section: ${crumb()} after $firstSection", crumb() != firstSection)
        // The key having arrived, the hint is allowed: once, in the pane.
        rule.onAllNodesWithText(hint).assertCountEquals(1)
        // Down one row and Page Up: this section's first row, which is where Page Down put the remote.
        rule.onNode(isFocused()).press(Key.DirectionDown)
        settle()
        rule.onNode(isFocused()).press(Key.PageUp)
        settle()
        assertEquals(second, focusedText())
        // Once more: the section before, whose first row is the page's first.
        rule.onNode(isFocused()).press(Key.PageUp)
        settle()
        assertEquals(first, focusedText())
        // Channel Down and Fast forward do the same as Page Down.
        rule.onNode(isFocused()).press(Key.ChannelDown)
        settle()
        assertEquals(second, focusedText())
    }

    @Test
    fun `Up from the first row reaches Find a setting, a typed word lands on its row, and Back returns to the panel with the word kept (WP-09)`() {
        show()
        rail(SettingsPage.Playback).press(Key.DirectionRight)
        settle()
        // Up from the page's first row: the header's button, not a dead end.
        rule.onNode(isFocused()).press(Key.DirectionUp)
        settle()
        rule.onNodeWithTag(FIND_BUTTON_TAG).assertIsFocused()
        rule.onNodeWithTag(FIND_BUTTON_TAG).press(Key.DirectionCenter)
        settle()
        // The panel opens on its field; an empty query shows the rows people come for.
        rule.onNode(androidx.compose.ui.test.hasSetTextAction()).assertIsFocused()
        rule.onAllNodesWithText("Back up").assertCountEquals(1)
        rule.onNode(androidx.compose.ui.test.hasSetTextAction()).performTextInput("back")
        settle()
        // OK on the first result opens its page on the row, the remote on it. (The panel is a window of
        // its own: moving the remote inside it, Down from the field, is checked on the device.)
        val result = rule.onAllNodes(hasText("Back up") and hasClickAction()).onFirst()
        result.performClick()
        settle()
        rail(SettingsPage.Storage).assertIsSelected()
        rule.onNode(isFocused() and hasText("Back up", substring = true)).assertExists()
        // Back from the result's page: the panel, with the word still in the field.
        back()
        rule.onNode(androidx.compose.ui.test.hasSetTextAction()).assertTextEquals("back")
        assertEquals("Back stayed in Settings", 0, leaves)
        // Back from the panel itself (the button again) is its own window's Back: checked on the device.
    }

    @Test
    fun `OK on a rail item goes in on its first row, Back goes to the rail and then out`() {
        show()
        rail(SettingsPage.Playback).press(Key.DirectionUp)
        settle()
        rail(SettingsPage.Accounts).assertIsFocused()
        rail(SettingsPage.Accounts).press(Key.DirectionCenter)
        settle()
        row("Connected devices").assertIsFocused()
        rail(SettingsPage.Accounts).assertIsSelected()

        back()
        rail(SettingsPage.Accounts).assertIsFocused()
        assertEquals("Back in the pane stays in Settings", 0, leaves)
        back()
        assertEquals("Back on the rail leaves Settings", 1, leaves)
    }

    @Test
    fun `the rail's ends and far side hold, and Up from the pane's first row stays`() {
        show()
        rail(SettingsPage.Playback).press(Key.DirectionUp)
        settle()
        rail(SettingsPage.Accounts).press(Key.DirectionUp)
        settle()
        rail(SettingsPage.Accounts).assertIsFocused()
        rail(SettingsPage.Accounts).press(Key.DirectionLeft)
        settle()
        rail(SettingsPage.Accounts).assertIsFocused()

        // The whole rail fits (no scroll): its last item is on screen.
        rail(SettingsPage.About).requestFocus()
        settle()
        rail(SettingsPage.About).press(Key.DirectionDown)
        settle()
        rail(SettingsPage.About).assertIsFocused()

        // Up from a page's first row: never the rail by accident; the header's Find a setting (WP-09).
        rail(SettingsPage.Accounts).requestFocus()
        settle()
        rail(SettingsPage.Accounts).press(Key.DirectionRight)
        settle()
        row("Connected devices").assertIsFocused()
        row("Connected devices").press(Key.DirectionUp)
        settle()
        rule.onNodeWithTag(FIND_BUTTON_TAG).assertIsFocused()
        // And Up again goes nowhere: the header is one line.
        rule.onNodeWithTag(FIND_BUTTON_TAG).press(Key.DirectionUp)
        settle()
        rule.onNodeWithTag(FIND_BUTTON_TAG).assertIsFocused()
    }

    @Test
    fun `a held Down over the rail shows one page when it stops`() {
        show()
        rule.mainClock.autoAdvance = false
        pagesShown.clear()
        // Four presses 50 ms apart, each well inside the settle: no page follows on the way.
        val order = listOf(SettingsPage.Playback, SettingsPage.Subtitles, SettingsPage.Storage, SettingsPage.Appearance)
        for (from in order) {
            rail(from).performKeyInput { pressKey(Key.DirectionDown) }
            rule.mainClock.advanceTimeBy(50)
        }
        rail(SettingsPage.Library).assertIsFocused()
        assertEquals("nothing followed while the key was held", emptyList<SettingsPage?>(), pagesShown)
        rule.mainClock.advanceTimeBy(400)
        rule.mainClock.autoAdvance = true
        settle()
        assertEquals(listOf<SettingsPage?>(SettingsPage.Library), pagesShown)
        rail(SettingsPage.Library).assertIsFocused().assertIsSelected()
    }

    @Test
    fun `Settings opens on the category used last in this session`() {
        SettingsRail.last = SettingsPage.Privacy
        show()
        rail(SettingsPage.Privacy).assertIsFocused().assertIsSelected()
    }

    @Test
    fun `a deep link opens its page with the remote on the first row, Back goes to its rail item`() {
        show(SettingsPage.Appearance)
        row("Language").assertIsFocused()
        rail(SettingsPage.Appearance).assertIsSelected()
        back()
        rail(SettingsPage.Appearance).assertIsFocused()
        assertEquals(0, leaves)
    }

    @Test
    fun `the header names the focused row's section`() {
        show(SettingsPage.Subtitles)
        reach("Downmix to stereo")
        settle()
        assertEquals("Subtitles and audio, Sound", crumb())
        // On the rail: the page alone.
        row("Downmix to stereo").press(Key.DirectionLeft)
        settle()
        rail(SettingsPage.Subtitles).assertIsFocused()
        assertEquals("Subtitles and audio", crumb())
    }

    @Test
    fun `a sub-page keeps Help selected and Back closes it on the row that opened it`() {
        // Help's Troubleshooting opens in the pane, under Help on the rail.
        show(SettingsPage.Help)
        reach("Troubleshooting")
        row("Troubleshooting").press(Key.DirectionCenter)
        settle()
        assertEquals("Troubleshooting", crumb().substringBefore(","))
        rail(SettingsPage.Help).assertIsSelected()
        // Back from a sub-page closes it first, on the row that opened it, still in the pane.
        back()
        row("Troubleshooting").assertIsFocused()
        assertEquals(0, leaves)
    }

    /** Down from Playback to the last category: every item is reached, shown, and the end holds. */
    private fun walkRailToTheEnd() {
        val order = SettingsRail.pages()
        rail(SettingsPage.Playback).assertIsFocused()
        repeat(order.size) { rule.onNode(isFocused()).press(Key.DirectionDown) }
        settle()
        rail(SettingsPage.About).assertIsFocused().assertIsDisplayed()
    }

    @Test
    fun `the rail reaches About at Default (F-04)`() {
        show()
        walkRailToTheEnd()
    }

    @Test
    fun `the rail reaches About at Larger (F-04)`() {
        show(scale = UiScale.Larger, font = 1.3f)
        walkRailToTheEnd()
    }

    @Test
    fun `the rail scrolls to About at Largest with the font at 1_3 (F-04)`() {
        show(scale = UiScale.Largest, font = 1.3f)
        rail(SettingsPage.Playback).assertIsFocused()
        // Down 7 times over the nine items, the rail scrolling to keep the remote's item in view.
        repeat(8) { rule.onNode(isFocused()).press(Key.DirectionDown) }
        settle()
        rail(SettingsPage.About).assertIsFocused().assertIsDisplayed()
        // And back up: the first item is shown again.
        repeat(9) { rule.onNode(isFocused()).press(Key.DirectionUp) }
        settle()
        rail(SettingsPage.Accounts).assertIsFocused().assertIsDisplayed()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w1920dp-h1080dp-land-television-night-mdpi")
    fun `the rail reaches About at 1920 by 1080 dp at Largest (F-04)`() {
        show(scale = UiScale.Largest, font = 1.3f)
        walkRailToTheEnd()
    }

    @Test
    fun `Right on the open page's rail item lands on a row after the pane was scrolled (F-05)`() {
        show()
        rail(SettingsPage.Playback).press(Key.DirectionRight)
        settle()
        val inPane = hasAnyAncestor(hasTestTag(SETTINGS_PANE_TAG)) and isFocused()
        rule.onNode(inPane).assertExists()
        // Far down the page: the first row is gone from the lazy list.
        repeat(15) { rule.onNode(isFocused()).press(Key.DirectionDown) }
        settle()
        rule.onNode(isFocused()).press(Key.DirectionLeft)
        settle()
        rail(SettingsPage.Playback).assertIsFocused()
        rail(SettingsPage.Playback).press(Key.DirectionRight)
        settle()
        rule.onNode(inPane).assertExists()
        rule.onNode(inPane).assertIsDisplayed()
    }

    // ---- rows that remove themselves, reached in the pane (WP-06 under WP-07) --------------------

    private fun seedLibrary(favourites: Boolean, history: Boolean, lastChat: Boolean) = runBlocking {
        ChatGate.set(null)
        val store = SettingsStore(RuntimeEnvironment.getApplication())
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

    /** Library from the rail, as a remote opens it: the rail item, then Right into the pane. */
    private fun intoLibrary() {
        SettingsRail.last = SettingsPage.Library
        show()
        rail(SettingsPage.Library).assertIsFocused()
        rail(SettingsPage.Library).press(Key.DirectionRight)
        settle()
        rule.onNode(hasAnyAncestor(hasTestTag(SETTINGS_PANE_TAG)) and isFocused()).assertExists()
    }

    /** The prompt's Clear, by the remote. */
    private fun confirmClear() {
        val clear = hasText("Clear") and hasClickAction()
        rule.onNode(clear).requestFocus()
        rule.onNode(clear).performKeyInput { pressKey(Key.Enter) }
        settle()
    }

    /** The remote is on the pane's row titled [title], waited for while the row before it goes. */
    private fun assertPaneRowFocused(title: String) {
        val there = hasText(title) and hasClickAction() and isFocused() and hasAnyAncestor(hasTestTag(SETTINGS_PANE_TAG))
        rule.waitUntil(5_000) { rule.waitForIdle(); rule.onAllNodes(there).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(there).assertIsFocused()
    }

    @Test
    fun `in the pane, Forget it hands the remote to Remember searches under it`() {
        seedLibrary(favourites = false, history = false, lastChat = true)
        intoLibrary()
        reach("Forget it")
        row("Forget it").press(Key.Enter)
        settle()
        assertPaneRowFocused("Remember searches")
        assertEquals(0, rule.onAllNodes(hasText("Forget it")).fetchSemanticsNodes().size)
    }

    @Test
    fun `in the pane, Clear favourites, confirmed, hands the remote to Clear history under it`() {
        seedLibrary(favourites = true, history = true, lastChat = false)
        intoLibrary()
        reach("Clear favourites")
        row("Clear favourites").press(Key.Enter)
        confirmClear()
        assertPaneRowFocused("Clear history")
        assertEquals(0, rule.onAllNodes(hasText("Clear favourites")).fetchSemanticsNodes().size)
    }

    @Test
    fun `in the pane, Clear history, confirmed, hands the remote to the row under it`() {
        seedLibrary(favourites = false, history = true, lastChat = false)
        intoLibrary()
        reach("Clear history")
        row("Clear history").press(Key.Enter)
        confirmClear()
        // The posters switch under it; a build without the lookups has none, and the remote goes up.
        assertPaneRowFocused(if (com.tmplayer.online.OnlineMetadata.current != null) "Show posters and overviews" else "Chosen chats")
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("Clear history") and hasClickAction()).fetchSemanticsNodes().isEmpty() }
    }
}
