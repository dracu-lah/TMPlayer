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
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import com.tmplayer.BuildConfig
import com.tmplayer.data.FormFactor
import com.tmplayer.data.Updates
import com.tmplayer.data.VersionInfo
import com.tmplayer.data.VersionState
import com.tmplayer.i18n.L
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.components.VERSION_NOTE_TAG
import com.tmplayer.ui.components.VersionNote
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
 * The version line at the top of Settings and in About (WP-104): it is under the account header
 * and the search box on the category list; on a phone a press copies it, on a television it is read
 * only (no focus stop, so the remote's order through the rows is unchanged) and a longer state text
 * moves no row. Pre-release and preview names are shown as built.
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

    private fun lineText() = L.aboutVersionBuild(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toString())

    @Test
    @Config(sdk = [35], qualifiers = "w360dp-h800dp-port-night-xhdpi")
    fun `the phone's Settings list has the line near its top and a press copies it`() {
        settings(tv = false)
        val line = rule.onNodeWithTag(VERSION_NOTE_TAG)
        line.assertExists()
        assertTrue(rule.onAllNodes(hasText(lineText() + ", " + L.updateStateUpToDate)).fetchSemanticsNodes().isNotEmpty())
        val bounds = line.fetchSemanticsNode().boundsInRoot
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("inside the list: $bounds in $root", bounds.left >= 0f && bounds.right <= root.right && bounds.top >= 0f)
        assertTrue("a press target", line.fetchSemanticsNode().config.contains(SemanticsActions.OnClick) ||
            rule.onAllNodes(androidx.compose.ui.test.hasClickAction() and hasText(lineText(), substring = true)).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
    fun `the television's line is read only and moves no row`() {
        settings(tv = true)
        val line = rule.onNodeWithTag(VERSION_NOTE_TAG)
        line.assertExists()
        val node = line.fetchSemanticsNode()
        assertFalse("no click action", node.config.contains(SemanticsActions.OnClick))
        assertFalse("no focus stop", node.config.contains(SemanticsActions.RequestFocus))
        val first = L.messages.text(com.tmplayer.data.SettingsGroup.Playback.title)
        val before = rule.onNode(hasText(first)).fetchSemanticsNode().boundsInRoot.top
        val lineHeight = line.fetchSemanticsNode().boundsInRoot.height
        // The longest state text on the same line, drawn afresh: one line, and the first row stays where it was.
        Updates.enabled = false
        generation++
        rule.waitForIdle()
        assertEquals(lineHeight, rule.onNodeWithTag(VERSION_NOTE_TAG).fetchSemanticsNode().boundsInRoot.height, 0.5f)
        assertTrue(rule.onAllNodes(hasText(lineText() + ", " + L.updateStateOff)).fetchSemanticsNodes().isNotEmpty())
        val after = rule.onNode(hasText(first)).fetchSemanticsNode().boundsInRoot.top
        assertEquals(before, after, 0.5f)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun `a pre-release and the preview build are shown as built`() {
        val versions = listOf("1.28.6-beta.3", "1.28.6-test", "1.0.0-dev")
        rule.setContent {
            TMPlayerTheme {
                Column(Modifier.fillMaxSize().background(Tone.background)) {
                    versions.forEach { version ->
                        VersionNote(VersionInfo(version, "12806003", "Android 14, Test, phone"), state = VersionState.None, copyable = false)
                    }
                }
            }
        }
        rule.waitForIdle()
        versions.forEach { version ->
            assertTrue(version, rule.onAllNodes(hasText("TMPlayer $version (12806003)")).fetchSemanticsNodes().isNotEmpty())
        }
    }
}
