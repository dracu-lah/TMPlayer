package com.tmplayer.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.assertCountEquals
import com.tmplayer.data.FormFactor
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.SyncStatus
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.theme.TMPlayerTheme
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Sync across my devices under Account (FQ12, moved from Library), on a phone and by
 * remote on a television: the switch is the one question, so it turns on at once with no second
 * dialog, Sync now and "Turn off and delete the sync file" show under it only while it is on, with
 * the last sync's line, and a file that could not be read turns Sync now into Replace the sync
 * file. On the television Down from the switch reaches Sync now.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ResumeSyncRowsTest {
    private companion object {
        /** How long a store write may take on a slow runner before the test gives up. */
        const val WRITE_MS = 10_000L
        const val SWITCH = "Sync across my devices"
    }

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    private val settings get() = SettingsStore(RuntimeEnvironment.getApplication())

    @Before
    fun noWaitingForALine() {
        // A switch turned on here starts a real sync; with no Telegram it gives up at once, not after half a minute.
        com.tmplayer.data.ResumeSync.connectWaitMs = 50L
    }

    @After
    fun tearDown() {
        com.tmplayer.data.ResumeSync.connectWaitMs = 30_000L
        FormFactor.override(false)
        runBlocking { settings.setResumeSync(false) }
    }

    /** Scrolls whichever list holds [matcher] to it: a lazy list has no node for a row it has not drawn. */
    private fun reveal(matcher: androidx.compose.ui.test.SemanticsMatcher) {
        val lists = rule.onAllNodes(androidx.compose.ui.test.hasScrollToNodeAction()).fetchSemanticsNodes().size
        for (i in 0 until lists) {
            if (rule.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()) return
            runCatching { rule.onAllNodes(androidx.compose.ui.test.hasScrollToNodeAction())[i].performScrollToNode(matcher) }
        }
    }

    private fun show(tv: Boolean) {
        FormFactor.override(tv)
        rule.setContent {
            TMPlayerTheme {
                Box(Modifier.fillMaxSize().background(Tone.background)) {
                    SettingsScreen(chats = emptyList(), onLoggedOut = {}, initialPage = SettingsPage.Accounts, accountFolders = emptyList())
                }
            }
        }
        rule.waitForIdle()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun `the switch turns on with no second question and the rows follow it`() {
        runBlocking { settings.setResumeSync(false) }
        show(tv = false)
        reveal(hasText(SWITCH))
        // The privacy line is the switch's words, so nobody has to open anything to know what goes where.
        reveal(hasText("Only TMPlayer's own data goes into one file in your own Saved Messages", substring = true))
        rule.onNodeWithText("Only TMPlayer's own data goes into one file in your own Saved Messages", substring = true).assertExists()
        rule.onAllNodesWithText("Sync now").assertCountEquals(0)

        rule.onNode(hasText(SWITCH) and hasClickAction()).performClick()
        rule.waitUntil(WRITE_MS) { runBlocking { settings.resumeSync.first() } }
        rule.waitForIdle()
        // No dialog stood in the way: the switch is on, the rows are there, and the question card is spent.
        rule.onAllNodesWithText("Sync your progress across devices?").assertCountEquals(0)
        reveal(hasText("Sync now")); rule.onNodeWithText("Sync now").assertExists()
        reveal(hasText("Turn off and delete the sync file")); rule.onNodeWithText("Turn off and delete the sync file").assertExists()
        assertFalse(runBlocking { settings.keepPlaceCardPending.first() })
        // Off again: no question either, the rows go, the file is left for the delete row.
        reveal(hasText(SWITCH) and hasClickAction()); rule.onNode(hasText(SWITCH) and hasClickAction()).performClick()
        rule.waitUntil(WRITE_MS) { !runBlocking { settings.resumeSync.first() } }
        rule.waitForIdle()
        rule.onAllNodesWithText("Sync now").assertCountEquals(0)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun `the last result shows under the switch and a file that cannot be read can be replaced`() {
        runBlocking { settings.setResumeSync(false) }
        show(tv = false)
        reveal(hasText(SWITCH))
        // Turned on from elsewhere (a restored backup): the rows and the last result show.
        runBlocking {
            settings.setResumeSync(true)
            settings.setResumeSyncStatus(SyncStatus(SyncStatus.Kind.NoConnection, System.currentTimeMillis()))
        }
        rule.waitForIdle()
        reveal(hasText("Sync now"))
        rule.onNodeWithText("Waiting for a connection to Telegram.", substring = true).assertExists()
        reveal(hasText("Turn off and delete the sync file")); rule.onNodeWithText("Turn off and delete the sync file").assertExists()

        runBlocking { settings.setResumeSyncStatus(SyncStatus(SyncStatus.Kind.Unreadable, System.currentTimeMillis())) }
        rule.waitForIdle()
        reveal(hasText("Replace the sync file")); rule.onNodeWithText("Replace the sync file").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Replace the sync file?").assertExists()
        rule.onNodeWithText("Cancel").performClick()
        rule.waitForIdle()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
    fun `down from the switch reaches Sync now on a television`() {
        runBlocking { settings.setResumeSync(true) }
        show(tv = true)
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
        val toggle = hasText(SWITCH) and hasClickAction()
        reveal(toggle)
        rule.onNode(toggle).requestFocus()
        rule.onNode(toggle).assertIsFocused()
        rule.onNode(toggle).performKeyInput { pressKey(Key.DirectionDown) }
        rule.waitForIdle()
        rule.onNode(hasText("Sync now") and hasClickAction()).assertIsFocused()
        rule.onNode(hasText("Sync now") and hasClickAction()).performKeyInput { pressKey(Key.DirectionDown) }
        rule.waitForIdle()
        rule.onNode(hasText("Turn off and delete the sync file") and hasClickAction()).assertIsFocused()
        assertTrue(runBlocking { settings.resumeSyncNow() })
    }
}
