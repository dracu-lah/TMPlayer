package com.tmplayer.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.tmplayer.data.GlobalLine
import com.tmplayer.data.SyncStatus
import com.tmplayer.data.SyncWords
import com.tmplayer.i18n.L
import com.tmplayer.ui.components.GlobalStatusPill
import com.tmplayer.ui.theme.TmMaterialTheme
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The corner's line on the desktop (WP-100, WP-102): Idle draws nothing and takes no space, each
 * state says its words, the ways out are buttons that answer a click (Retry, Open settings, Try
 * again, Send logs, Close), the three failures line has Try again and Send logs and no Open
 * settings, a wait's count is not a live region that changes each second, and the television's
 * pointer to Settings is not added where a button does it.
 */
@OptIn(ExperimentalTestApi::class)
class GlobalStatusPillTest {

    private val stopped = SyncStatus(SyncStatus.Kind.Failed, at = 1_800_000_000_000L, detail = "Telegram said no.", failures = 3)

    private val polite = SemanticsMatcher("is a polite live region") {
        it.config.getOrNull(SemanticsProperties.LiveRegion) == LiveRegionMode.Polite
    }

    private fun ComposeUiTest.shown(text: String, substring: Boolean = false) =
        onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().size

    @Test
    fun `Idle draws no node and takes no space`() = runComposeUiTest {
        setContent {
            TmMaterialTheme(dark = true) {
                Box(Modifier.testTag("host")) { GlobalStatusPill(line = GlobalLine.Idle) }
            }
        }
        val box = onNodeWithTag("host").getBoundsInRoot()
        assertEquals(0f, (box.bottom - box.top).value)
        assertEquals(0, onAllNodes(polite).fetchSemanticsNodes().size)
    }

    @Test
    fun `catching up and a slow answer say their words, and Retry answers a click on the slow one only`() = runComposeUiTest {
        var line by mutableStateOf<GlobalLine>(GlobalLine.Updating)
        var retries = 0
        setContent {
            TmMaterialTheme(dark = true) { GlobalStatusPill(line = line, onRetry = { retries++ }) }
        }
        onNodeWithText(L.stateUpdating).assertExists()
        assertEquals(0, shown(L.commonRetry))
        onNode(polite).assertExists()
        line = GlobalLine.SlowAnswer
        waitForIdle()
        onNodeWithText(L.commonSlowAnswer).assertExists()
        onNodeWithText(L.commonRetry).performClick()
        assertEquals(1, retries)
    }

    @Test
    fun `a wait counts down in words that are not a live region`() = runComposeUiTest {
        var line by mutableStateOf<GlobalLine>(GlobalLine.RateLimited(42))
        setContent {
            TmMaterialTheme(dark = true) { GlobalStatusPill(line = line) }
        }
        onNodeWithText(L.stateRateLimited(seconds = 42)).assertExists()
        assertEquals(0, onAllNodes(polite).fetchSemanticsNodes().size)
        line = GlobalLine.RateLimited(41)
        waitForIdle()
        onNodeWithText(L.stateRateLimited(seconds = 41)).assertExists()
        assertEquals(0, shown(L.stateRateLimited(seconds = 42)))
    }

    @Test
    fun `a sync that needs you has Open settings and Close, and each answers its click`() = runComposeUiTest {
        var opened = 0
        var closed = 0
        setContent {
            TmMaterialTheme(dark = true) {
                GlobalStatusPill(
                    line = GlobalLine.SyncNeedsYou(SyncStatus.Kind.TooNew),
                    syncStatus = SyncStatus(SyncStatus.Kind.TooNew, 1L),
                    onOpenSettings = { opened++ },
                    onTryAgainSync = { error("not for this kind") },
                    onSendLogs = { error("not for this kind") },
                    onDismiss = { closed++ },
                )
            }
        }
        onNodeWithText(L.settingsResumeSyncTooNew, substring = true).assertExists()
        // A button does the pointing here, so the television's "find it in Settings" is not said.
        assertEquals(0, shown(L.syncplaceSwitch, substring = true))
        assertEquals(0, shown(L.commonTryAgain))
        assertEquals(0, shown(L.logsSend))
        onNodeWithText(L.notifyOpenSettings).performClick()
        onNodeWithText(L.commonClose).performClick()
        assertEquals(1, opened)
        assertEquals(1, closed)
    }

    @Test
    fun `three failed tries says it stopped with Try again and Send logs, and no Open settings`() = runComposeUiTest {
        var again = 0
        var logs = 0
        var closed = 0
        setContent {
            TmMaterialTheme(dark = true) {
                GlobalStatusPill(
                    line = GlobalLine.SyncNeedsYou(SyncStatus.Kind.Failed),
                    syncStatus = stopped,
                    onOpenSettings = { error("Settings is not the way out of a stopped sync") },
                    onTryAgainSync = { again++ },
                    onSendLogs = { logs++ },
                    onDismiss = { closed++ },
                )
            }
        }
        onNodeWithText(SyncWords.stopped(stopped)).assertExists()
        assertEquals(0, shown(L.notifyOpenSettings))
        onNodeWithText(L.commonTryAgain).performClick()
        onNodeWithText(L.logsSend).performClick()
        onNodeWithText(L.commonClose).performClick()
        assertEquals(listOf(1, 1, 1), listOf(again, logs, closed))
    }

    @Test
    fun `a line that goes leaves no button behind`() = runComposeUiTest {
        var line by mutableStateOf<GlobalLine>(GlobalLine.SyncNeedsYou(SyncStatus.Kind.Failed))
        setContent {
            TmMaterialTheme(dark = true) {
                GlobalStatusPill(line = line, syncStatus = stopped, onTryAgainSync = {}, onSendLogs = {}, onDismiss = {})
            }
        }
        onNodeWithText(L.commonTryAgain).assertExists()
        line = GlobalLine.Idle
        mainClock.advanceTimeBy(2_000)
        waitForIdle()
        assertEquals(0, shown(L.commonTryAgain))
        assertEquals(0, shown(SyncWords.stopped(stopped)))
    }
}
