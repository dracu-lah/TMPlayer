package com.tmplayer.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.tmplayer.data.VersionInfo
import com.tmplayer.data.VersionState
import com.tmplayer.ui.components.VERSION_FOOTER_TAG
import com.tmplayer.ui.components.VERSION_NOTE_TAG
import com.tmplayer.ui.components.VersionFooter
import com.tmplayer.ui.components.VersionNote
import com.tmplayer.ui.theme.TmMaterialTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The version line on the desktop (WP-104): the popover's footer, the Settings line and the
 * pre-release suffix as built. The clipboard is a list, so nothing here touches the real one.
 */
@OptIn(ExperimentalTestApi::class)
class VersionFooterDesktopTest {

    private val info = VersionInfo("1.28.6-beta.3", "1.28.6", "Linux 6.8, amd64")
    private val copied = mutableListOf<String>()

    @Test
    fun `the footer shows the version, the build and the state, and a click copies the support line`() = runComposeUiTest {
        setContent {
            TmMaterialTheme(dark = true) {
                VersionFooter(info, state = VersionState.UpToDate, copy = { copied += it; true })
            }
        }
        waitForIdle()
        onNodeWithText("TMPlayer 1.28.6-beta.3 (1.28.6), Up to date").assertIsDisplayed()
        onNodeWithTag(VERSION_FOOTER_TAG).performClick()
        waitForIdle()
        assertEquals(listOf("TMPlayer 1.28.6-beta.3 (1.28.6) Linux 6.8, amd64"), copied)
        // The same node now says so, in a live region.
        onNodeWithText("Version details copied").assertIsDisplayed()
    }

    @Test
    fun `an available update is its own button and the copy target is the version alone`() = runComposeUiTest {
        var opened = 0
        setContent {
            TmMaterialTheme(dark = true) {
                VersionFooter(info, state = VersionState.Available("1.29.0"), onUpdate = { opened++ }, copy = { copied += it; true })
            }
        }
        waitForIdle()
        onNodeWithText("TMPlayer 1.28.6-beta.3 (1.28.6)").assertIsDisplayed()
        onNodeWithText("Update available: 1.29.0").performClick()
        waitForIdle()
        assertEquals(1, opened)
        assertTrue(copied.isEmpty())
    }

    @Test
    fun `the Settings line and the states in words`() = runComposeUiTest {
        setContent {
            TmMaterialTheme(dark = true) {
                Column {
                    VersionNote(info, state = VersionState.Off)
                    VersionNote(info, state = VersionState.None)
                    VersionNote(info, state = VersionState.Checking, copyable = false)
                }
            }
        }
        waitForIdle()
        onNodeWithText("TMPlayer 1.28.6-beta.3 (1.28.6), Updates come from where you installed TMPlayer").assertIsDisplayed()
        // Never checked: the version alone, never a false "Up to date".
        assertEquals(1, onAllNodesWithExactText("TMPlayer 1.28.6-beta.3 (1.28.6)"))
        onNodeWithText("TMPlayer 1.28.6-beta.3 (1.28.6), Checking…").assertIsDisplayed()
        assertFalse(onAllNodesWithTag().isEmpty())
    }

    private fun androidx.compose.ui.test.ComposeUiTest.onAllNodesWithExactText(text: String): Int =
        onAllNodes(hasText(text, substring = false)).fetchSemanticsNodes().size

    private fun androidx.compose.ui.test.ComposeUiTest.onAllNodesWithTag() =
        onAllNodes(androidx.compose.ui.test.hasTestTag(VERSION_NOTE_TAG)).fetchSemanticsNodes()
}
