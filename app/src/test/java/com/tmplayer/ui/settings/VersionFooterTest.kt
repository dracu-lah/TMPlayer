package com.tmplayer.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import com.tmplayer.data.FormFactor
import com.tmplayer.data.Updates
import com.tmplayer.data.VersionInfo
import com.tmplayer.data.VersionLine
import com.tmplayer.data.VersionState
import com.tmplayer.i18n.L
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.components.VERSION_MARK_ICON_TAG
import com.tmplayer.ui.components.VERSION_MARK_TAG
import com.tmplayer.ui.components.VersionMark
import com.tmplayer.ui.theme.TMPlayerTheme
import com.tmplayer.ui.theme.Tone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The compact version in Settings (user, 2026-11-25): the app's tile beside "Settings" in the title,
 * and the tile with "v1.28.7" as the very last thing of the main list, on a phone upright and on its
 * side, in the left list of the two panes, and at the foot of a television's rail, where it is read
 * only (no focus stop, so the remote's order through the rows is unchanged) and a longer state text
 * moves no row. The long line is gone from the top of the list. Pre-release and preview suffixes are
 * left off the number; About keeps them.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VersionFooterTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val drain = DrainMainLooperRule()

    @Before
    fun setUp() {
        Updates.enabled = true
        Updates.noteAnswered(1L)
    }

    @After
    fun tearDown() {
        FormFactor.override(false)
        Updates.enabled = true
    }

    private var generation by mutableIntStateOf(0)

    private fun settings(tv: Boolean) {
        FormFactor.override(tv)
        rule.setContent {
            TMPlayerTheme {
                Box(Modifier.fillMaxSize().background(Tone.background)) {
                    key(generation) {
                        SettingsScreen(chats = emptyList(), onLoggedOut = {}, initialPage = null, accountFolders = emptyList())
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun compact() = VersionLine.compact(com.tmplayer.BuildConfig.VERSION_NAME)

    /** The list's scrolling node: the one that can bring a node into view by its tag. */
    private fun scrollToVersion() {
        rule.onAllNodes(hasScrollToNodeAction())[0].performScrollToNode(hasTestTag(VERSION_MARK_TAG))
        rule.waitForIdle()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w360dp-h800dp-port-night-xhdpi")
    fun `the phone's list ends with the tile and the number, the title has the tile, and the old line is gone`() {
        settings(tv = false)
        rule.onNodeWithTag(SETTINGS_ICON_TAG, useUnmergedTree = true).assertExists()
        assertTrue("no long sentence", rule.onAllNodes(hasText(L.aboutVersionBuild(com.tmplayer.BuildConfig.VERSION_NAME, com.tmplayer.BuildConfig.VERSION_CODE.toString()), substring = true)).fetchSemanticsNodes().isEmpty())
        scrollToVersion()
        val mark = rule.onNodeWithTag(VERSION_MARK_TAG)
        mark.assertExists()
        rule.onNodeWithTag(VERSION_MARK_ICON_TAG, useUnmergedTree = true).assertExists()
        assertTrue(rule.onAllNodes(hasText(compact())).fetchSemanticsNodes().isNotEmpty())
        assertTrue("no Up to date in the compact row", rule.onAllNodes(hasText(L.updateStateUpToDate, substring = true)).fetchSemanticsNodes().isEmpty())
        // One centred group in the list's width.
        val group = rule.onNodeWithTag(VERSION_MARK_TAG).fetchSemanticsNode().boundsInRoot
        val holder = rule.onNodeWithTag(com.tmplayer.ui.components.VERSION_MARK_ROW_TAG).fetchSemanticsNode().boundsInRoot
        assertEquals("centred", holder.center.x, group.center.x, rule.activity.resources.displayMetrics.density)
        // Below the last category (About), and a press goes to About.
        val about = rule.onAllNodes(hasText(L.settingsGroupAboutPageSummary(com.tmplayer.BuildConfig.VERSION_NAME), substring = true))
        if (about.fetchSemanticsNodes().isNotEmpty()) {
            assertTrue(mark.fetchSemanticsNode().boundsInRoot.top >= about[0].fetchSemanticsNode().boundsInRoot.bottom - 1f)
        }
        assertTrue("a press target", mark.fetchSemanticsNode().config.contains(SemanticsActions.OnClick))
    }

    @Test
    @Config(sdk = [35], qualifiers = "w800dp-h360dp-land-night-xhdpi")
    fun `a phone on its side has it at the foot of the list too`() {
        settings(tv = false)
        scrollToVersion()
        val mark = rule.onNodeWithTag(VERSION_MARK_TAG)
        mark.assertExists()
        val root = rule.onNodeWithTag(VERSION_MARK_TAG).fetchSemanticsNode().boundsInRoot
        assertTrue("inside the window: $root", root.left >= 0f && root.top >= 0f)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w1280dp-h800dp-land-night-mdpi")
    fun `the two panes have it at the foot of the left list, not over the page`() {
        settings(tv = false)
        scrollToVersion()
        rule.onNode(hasTestTag(VERSION_MARK_TAG) and hasAnyAncestor(hasTestTag(SETTINGS_TWO_PANE_LIST_TAG))).assertExists()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
    fun `the television's version is at the foot of the rail, read only, and moves no row`() {
        settings(tv = true)
        val mark = rule.onNode(hasTestTag(VERSION_MARK_TAG) and hasAnyAncestor(hasTestTag(SETTINGS_RAIL_TAG)), useUnmergedTree = true)
        mark.assertExists()
        val node = mark.fetchSemanticsNode()
        assertFalse("no click action", node.config.contains(SemanticsActions.OnClick))
        assertFalse("no focus stop", node.config.contains(SemanticsActions.RequestFocus))
        // Positions, not clipped bounds: the foot of a rail on a 540 dp screen is scrolled out of sight.
        val lastNode = rule.onNodeWithTag(railTag(SettingsRail.pages().last()), useUnmergedTree = true).fetchSemanticsNode()
        val lastEnd = lastNode.positionInRoot.y + lastNode.size.height
        val markY = node.positionInRoot.y
        assertTrue("below the last category: mark at $markY, last item ends at $lastEnd", markY >= lastEnd - 1f)
        val first = rule.onNodeWithTag(railTag(SettingsPage.Playback), useUnmergedTree = true)
        val before = first.fetchSemanticsNode().boundsInRoot.top
        // The longest state text, drawn afresh: the rail's first item stays where it was.
        Updates.enabled = false
        generation++
        rule.waitForIdle()
        assertEquals(before, rule.onNodeWithTag(railTag(SettingsPage.Playback), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top, 0.5f)
        // The compact row holds the number alone here: the update source is About's sentence, not the foot's.
        assertTrue(rule.onAllNodes(hasText(compact())).fetchSemanticsNodes().isNotEmpty())
        assertTrue(rule.onAllNodes(hasText("Updates come from", substring = true)).fetchSemanticsNodes().isEmpty())
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun `a pre-release and the preview build show the number alone, as v1 28 6`() {
        val versions = listOf("1.28.6-beta.3", "1.28.6-test", "1.0.0-dev")
        rule.setContent {
            TMPlayerTheme {
                Column(Modifier.fillMaxSize().background(Tone.background)) {
                    versions.forEach { version ->
                        VersionMark(VersionInfo(version, "12806003", "Android 14, Test, phone"), state = VersionState.None)
                    }
                }
            }
        }
        rule.waitForIdle()
        assertTrue(rule.onAllNodes(hasText("v1.28.6")).fetchSemanticsNodes().size == 2)
        assertTrue(rule.onAllNodes(hasText("v1.0.0")).fetchSemanticsNodes().size == 1)
        assertEquals(3, rule.onAllNodes(hasTestTag(VERSION_MARK_TAG)).fetchSemanticsNodes().size)
        val said = rule.onAllNodes(hasTestTag(VERSION_MARK_TAG))[0].fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()
        assertEquals("TMPlayer v1.28.6", said)
    }
}
