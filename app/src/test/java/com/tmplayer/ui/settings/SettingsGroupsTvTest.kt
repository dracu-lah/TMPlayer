package com.tmplayer.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import com.tmplayer.data.FormFactor
import com.tmplayer.ui.theme.TMPlayerTheme
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The pages U20 changed, by remote on a television: the category list lands on Playback, its
 * first page now; Down walks Subtitles and audio from the subtitle rows through the audio
 * language and over the Sound heading to the sound rows, and the language sheet hands the focus
 * back to its row when it closes; Storage and downloads reaches Download the whole video first
 * under the location; Privacy and lock reaches the privacy page under the lock. And the phone's
 * search box finds a moved row and opens its page on it.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsGroupsTvTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @After
    fun tearDown() {
        FormFactor.override(false)
    }

    private fun show(page: SettingsPage?, tv: Boolean = true) {
        FormFactor.override(tv)
        rule.setContent {
            TMPlayerTheme {
                Box(Modifier.fillMaxSize().background(Tone.background)) {
                    SettingsScreen(chats = emptyList(), onLoggedOut = {}, initialPage = page, accountFolders = emptyList())
                }
            }
        }
        rule.waitForIdle()
        if (!tv) return
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
        rule.waitForIdle()
    }

    private fun row(title: String): SemanticsNodeInteraction = rule.onNode(hasText(title) and hasClickAction())

    private fun walk(vararg titles: String) {
        row(titles.first()).performScrollTo().requestFocus()
        for ((index, title) in titles.withIndex()) {
            row(title).assertIsFocused()
            if (index < titles.lastIndex) row(title).performKeyInput { pressKey(Key.DirectionDown) }
            rule.waitForIdle()
        }
    }

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
    fun `the list lands on Playback, its first page`() {
        show(page = null)
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
        row("Playback").assertIsFocused()
        row("Playback").performKeyInput { pressKey(Key.DirectionDown) }
        rule.waitForIdle()
        // No Player gestures on a television: Subtitles and audio is next.
        row("Subtitles and audio").assertIsFocused()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
    fun `Subtitles and audio walks into the sound and the sheet gives the focus back`() {
        show(SettingsPage.Subtitles)
        walk("Preferred subtitle language", "Preferred audio language", "Remembered languages", "Downmix to stereo", "Even out volume")

        row("Preferred audio language").performScrollTo().requestFocus()
        row("Preferred audio language").performKeyInput { pressKey(Key.DirectionCenter) }
        rule.waitForIdle()
        // The sheet is up: its title says the row's name a second time.
        org.junit.Assert.assertTrue(rule.onAllNodes(hasText("Preferred audio language")).fetchSemanticsNodes().size >= 2)
        // OK on the line already chosen closes the sheet, as picking any line does.
        rule.onAllNodes(hasText("Automatic", substring = true) and hasClickAction()).onLast()
            .performKeyInput { pressKey(Key.DirectionCenter) }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
        row("Preferred audio language").assertIsFocused()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
    fun `Storage and downloads reaches Download the whole video first`() {
        show(SettingsPage.Storage)
        walk("Storage location", "Download the whole video first", "Remove after watching", "Low storage mode")
    }

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
    fun `Privacy and lock reaches the privacy page under the lock`() {
        show(SettingsPage.Account)
        // A build with a crash report address (a developer's, with SENTRY_DSN in local.properties)
        // has the crash switch under Privacy; a fork or CI build has none. Walk what this build has.
        val crash = if (com.tmplayer.data.CrashReports.available) arrayOf("Send crash reports") else emptyArray()
        walk("Allowed chats only", "Allowed chats", "Privacy", *crash, "Connected devices")
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun `the phone's search finds a moved row and opens its page on it`() {
        show(page = null, tv = false)
        rule.onNodeWithTag(SEARCH_TAG).performTextInput("audio lang")
        rule.waitForIdle()
        row("Preferred audio language").assertIsDisplayed().performClick()
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
        // The page's own row, on Subtitles and audio, brought into view.
        rule.onNodeWithText("Subtitles and audio").assertExists()
        row("Preferred audio language").assertIsDisplayed()
        row("Remembered languages").assertExists()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun `a search that finds nothing says so`() {
        show(page = null, tv = false)
        rule.onNodeWithTag(SEARCH_TAG).performTextInput("zzqx")
        rule.waitForIdle()
        rule.onNodeWithText("Nothing in Settings matches zzqx", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Playback").assertDoesNotExist()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
    fun `a switch row on a television says On or Off to a screen reader`() {
        show(SettingsPage.Storage)
        val state = rule.onNode(hasText("Download the whole video first") and hasClickAction())
            .fetchSemanticsNode().config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.StateDescription)
        org.junit.Assert.assertTrue("the row says its state, got $state", state == "On" || state == "Off")
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun `Forget all in the remembered languages asks first and Cancel keeps them`() {
        val store = com.tmplayer.data.SettingsStore(org.robolectric.RuntimeEnvironment.getApplication())
        kotlinx.coroutines.runBlocking {
            store.forgetAllLearntLanguages()
            store.setTrackChoice("Harbour Notes", com.tmplayer.data.TrackChoice(audioLanguage = "fr", subtitlesOn = true))
        }
        show(page = SettingsPage.Subtitles, tv = false)
        row("Remembered languages").performScrollTo().performClick()
        rule.waitForIdle()
        row("Forget all").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Forget the languages of every series?").assertExists()
        // Cancel leaves them.
        rule.onNodeWithText("Cancel").performClick()
        rule.waitForIdle()
        org.junit.Assert.assertEquals(1, kotlinx.coroutines.runBlocking { store.learntLanguages.first().size })
        row("Forget all").performClick()
        rule.waitForIdle()
        // The sheet's line and the dialog's button both read Forget all; the dialog is the last.
        rule.onAllNodes(hasText("Forget all") and hasClickAction()).onLast().performClick()
        rule.waitForIdle()
        org.junit.Assert.assertEquals(0, kotlinx.coroutines.runBlocking { store.learntLanguages.first().size })
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun `the search's clear control empties the box and Back clears before it leaves`() {
        show(page = null, tv = false)
        rule.onNodeWithTag(SEARCH_TAG).performTextInput("audio lang")
        rule.waitForIdle()
        row("Preferred audio language").assertIsDisplayed()
        // The shared round clear control, one press empties the box and the list comes back.
        rule.onNode(hasContentDescription("Clear the search") and hasClickAction()).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Playback").assertIsDisplayed()

        // Back clears first: with words in the box the list stays up and the box is empty after it.
        rule.onNodeWithTag(SEARCH_TAG).performTextInput("zzqx")
        rule.waitForIdle()
        rule.onNodeWithText("Nothing in Settings matches zzqx", substring = true).assertIsDisplayed()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        rule.onNodeWithText("Nothing in Settings matches zzqx", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Playback").assertIsDisplayed()
    }
}
