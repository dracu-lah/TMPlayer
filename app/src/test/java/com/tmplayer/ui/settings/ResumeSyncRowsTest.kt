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
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.assertCountEquals
import com.tmplayer.data.FormFactor
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.SyncStatus
import com.tmplayer.ui.theme.TMPlayerTheme
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
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
 * Resume on other devices on Library (U7), on a phone and by remote on a television: the switch
 * asks before it turns on, Sync now and "Turn off and delete the sync file" show under it only
 * while it is on, with the last sync's line, and a file that could not be read turns Sync now into
 * Replace the sync file. On the television Down from the switch reaches Sync now.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ResumeSyncRowsTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val settings get() = SettingsStore(RuntimeEnvironment.getApplication())

    @After
    fun tearDown() {
        FormFactor.override(false)
        runBlocking { settings.setResumeSync(false) }
    }

    private fun show(tv: Boolean) {
        FormFactor.override(tv)
        rule.setContent {
            TMPlayerTheme {
                Box(Modifier.fillMaxSize().background(Tone.background)) {
                    SettingsScreen(chats = emptyList(), onLoggedOut = {}, initialPage = SettingsPage.Library, accountFolders = emptyList())
                }
            }
        }
        rule.waitForIdle()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun `the switch asks first and the rows follow it`() {
        runBlocking { settings.setResumeSync(false) }
        show(tv = false)
        rule.onNodeWithText("Resume on your other devices").performScrollTo()
        rule.onAllNodesWithText("Sync now").assertCountEquals(0)

        // On asks first, and Cancel leaves it off.
        rule.onNode(hasText("Resume on your other devices") and hasClickAction()).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Resume on your other devices?").assertExists()
        rule.onNodeWithText("Cancel").performClick()
        rule.waitForIdle()
        assertFalse(runBlocking { settings.resumeSyncNow() })

        // Turned on from elsewhere (a restored backup): the rows and the last result show.
        runBlocking {
            settings.setResumeSync(true)
            settings.setResumeSyncStatus(SyncStatus(SyncStatus.Kind.NoConnection, System.currentTimeMillis()))
        }
        rule.waitForIdle()
        rule.onNodeWithText("Sync now").performScrollTo()
        rule.onNodeWithText("Waiting for a connection to Telegram.", substring = true).assertExists()
        rule.onNodeWithText("Turn off and delete the sync file").performScrollTo().assertExists()

        runBlocking { settings.setResumeSyncStatus(SyncStatus(SyncStatus.Kind.Unreadable, System.currentTimeMillis())) }
        rule.waitForIdle()
        rule.onNodeWithText("Replace the sync file").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Replace the sync file?").assertExists()
        rule.onNodeWithText("Cancel").performClick()
        rule.waitForIdle()

        // Off from the switch: no question, the rows go, the file is left for the delete row.
        rule.onNode(hasText("Resume on your other devices") and hasClickAction()).performScrollTo().performClick()
        rule.waitForIdle()
        assertFalse(runBlocking { settings.resumeSync.first() })
        rule.onAllNodesWithText("Sync now").assertCountEquals(0)
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
        val toggle = hasText("Resume on your other devices") and hasClickAction()
        rule.onNode(toggle).performScrollTo().requestFocus()
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
