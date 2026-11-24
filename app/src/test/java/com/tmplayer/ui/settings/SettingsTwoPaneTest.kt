package com.tmplayer.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.tmplayer.data.FormFactor
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.theme.TMPlayerTheme
import com.tmplayer.ui.theme.Tone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Settings on a tablet (WP-76): from 840 dp the category list (280 dp, its search box over it) and
 * the open page sit side by side; a press on a category changes the page, the category is drawn
 * selected, a screen reader reads the list and then the page, the open category survives a
 * recreation (rotation, unfolding), and a window narrower than 840 dp keeps the single column.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsTwoPaneTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val drain = DrainMainLooperRule()

    @After
    fun tearDown() = FormFactor.override(false)

    private fun content(onPage: (SettingsPage?) -> Unit = {}): @androidx.compose.runtime.Composable () -> Unit = {
        TMPlayerTheme {
            Box(Modifier.fillMaxSize().background(Tone.background)) {
                SettingsScreen(
                    chats = emptyList(),
                    onLoggedOut = {},
                    onPageChange = onPage,
                    accountFolders = emptyList(),
                )
            }
        }
    }

    private fun show() {
        FormFactor.override(false)
        rule.setContent(content())
        rule.waitForIdle()
    }

    private fun inList(text: String) = hasText(text) and hasAnyAncestor(hasTestTag(SETTINGS_TWO_PANE_LIST_TAG))
    private fun inPage(text: String) = hasText(text) and hasAnyAncestor(hasTestTag(SETTINGS_TWO_PANE_PAGE_TAG))

    @Test
    @Config(sdk = [35], qualifiers = "w1280dp-h800dp-land-night-mdpi")
    fun `at 1280 by 800 dp the categories and a page are both there, the list 280 dp wide`() {
        show()
        rule.onNodeWithTag(SETTINGS_TWO_PANE_LIST_TAG).assertIsDisplayed()
        rule.onNodeWithTag(SETTINGS_TWO_PANE_PAGE_TAG).assertIsDisplayed()
        val list = rule.onNodeWithTag(SETTINGS_TWO_PANE_LIST_TAG).fetchSemanticsNode().boundsInRoot
        val page = rule.onNodeWithTag(SETTINGS_TWO_PANE_PAGE_TAG).fetchSemanticsNode().boundsInRoot
        val density = rule.activity.resources.displayMetrics.density
        assertEquals("the list is 280 dp", 280f * density, list.width, 1f)
        assertTrue("the page is beside the list, not under it", page.left >= list.right - 1f && page.top < list.bottom)
        // The categories in the list, Playback's own rows in the page.
        val words = com.tmplayer.i18n.L
        assertTrue(rule.onAllNodes(inList(words.settingsPageSubtitlesAudio)).fetchSemanticsNodes().isNotEmpty())
        assertTrue(
            "Playback's page is open by default",
            rule.onAllNodes(inPage("Play the next episode automatically")).fetchSemanticsNodes().isNotEmpty(),
        )
    }

    @Test
    @Config(sdk = [35], qualifiers = "w1280dp-h800dp-land-night-mdpi")
    fun `a press on a category opens its page beside the list and draws it selected`() {
        show()
        val words = com.tmplayer.i18n.L
        rule.onAllNodes(inList(words.settingsPageSubtitlesAudio)).onFirst().performClick()
        rule.waitForIdle()
        rule.onAllNodes(inPage(words.settingsSubtitleStyle)).onFirst().assertIsDisplayed()
        val selected = rule.onAllNodes(inList(words.settingsPageSubtitlesAudio)).onFirst().fetchSemanticsNode()
        assertTrue("the open category is selected", selected.config.getOrNull(SemanticsProperties.Selected) == true)
        val other = rule.onAllNodes(inList(words.settingsPlayback)).onFirst().fetchSemanticsNode()
        assertTrue("another category is not", other.config.getOrNull(SemanticsProperties.Selected) != true)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w1280dp-h800dp-land-night-mdpi")
    fun `a screen reader reads the list and then the page`() {
        show()
        val list = rule.onNodeWithTag(SETTINGS_TWO_PANE_LIST_TAG).fetchSemanticsNode().config
        val page = rule.onNodeWithTag(SETTINGS_TWO_PANE_PAGE_TAG).fetchSemanticsNode().config
        assertTrue(list.getOrNull(SemanticsProperties.IsTraversalGroup) == true)
        assertTrue(page.getOrNull(SemanticsProperties.IsTraversalGroup) == true)
        assertTrue(
            "the list comes first",
            (list.getOrNull(SemanticsProperties.TraversalIndex) ?: 0f) < (page.getOrNull(SemanticsProperties.TraversalIndex) ?: 0f),
        )
    }

    @Test
    @Config(sdk = [35], qualifiers = "w1280dp-h800dp-land-night-mdpi")
    fun `the open category survives a recreation`() {
        FormFactor.override(false)
        val tester = StateRestorationTester(rule)
        tester.setContent(content())
        rule.waitForIdle()
        val words = com.tmplayer.i18n.L
        rule.onAllNodes(inList(words.settingsPageSubtitlesAudio)).onFirst().performClick()
        rule.waitForIdle()
        tester.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        rule.onAllNodes(inPage(words.settingsSubtitleStyle)).onFirst().assertIsDisplayed()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w700dp-h1000dp-port-night-mdpi")
    fun `below 840 dp the single column stays`() {
        show()
        assertEquals(0, rule.onAllNodes(hasTestTag(SETTINGS_TWO_PANE_LIST_TAG)).fetchSemanticsNodes().size)
    }
}
