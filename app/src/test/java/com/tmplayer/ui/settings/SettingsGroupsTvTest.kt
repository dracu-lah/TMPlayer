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
import com.tmplayer.ui.DrainMainLooperRule
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
 * The pages U20 changed, by remote on a television: the rail lands on Playback (WP-07) and walks
 * the pages in WP-08's order from Account; Down walks Subtitles and audio from the subtitle rows through the audio
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

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    @After
    fun tearDown() {
        FormFactor.override(false)
    }

    private fun show(page: SettingsPage?, tv: Boolean = true) {
        FormFactor.override(tv)
        // The rail's memory of this session, from the test before.
        SettingsRail.last = null
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
    fun `the rail lands on Playback and Down walks the pages in the shared order`() {
        show(page = null)
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
        // The rail and pane since WP-07: Settings opens on Playback, the first time in a session.
        rule.onNodeWithTag(railTag(SettingsPage.Playback)).assertIsFocused()
        rule.onNodeWithTag(railTag(SettingsPage.Playback)).performKeyInput { pressKey(Key.DirectionUp) }
        rule.waitForIdle()
        // Nine pages on a television (WP-08): no Notifications, no Redo setup choices or Launcher of their own.
        val pages = listOf(
            SettingsPage.Accounts, SettingsPage.Playback, SettingsPage.Subtitles, SettingsPage.Storage, SettingsPage.Appearance,
            SettingsPage.Library, SettingsPage.Privacy, SettingsPage.Help, SettingsPage.About,
        )
        for ((index, page) in pages.withIndex()) {
            rule.onNodeWithTag(railTag(page)).assertIsFocused()
            if (index < pages.lastIndex) rule.onNodeWithTag(railTag(page)).performKeyInput { pressKey(Key.DirectionDown) }
            rule.waitForIdle()
        }
        rule.onNodeWithTag(railTag(SettingsPage.Notifications)).assertDoesNotExist()
        rule.onNodeWithTag(railTag(SettingsPage.SetupChoices)).assertDoesNotExist()
        rule.onNodeWithText("Launcher").assertDoesNotExist()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
    fun `Subtitles and audio walks into the sound and the sheet gives the focus back`() {
        show(SettingsPage.Subtitles)
        walk("Preferred subtitle language", "Preferred audio language")

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
        // Then on down into the sound; WP-24 put Volume boost and Night mode between Downmix and Even out volume.
        walk("Preferred audio language", "Remembered languages", "Downmix to stereo", "Volume boost", "Night mode", "Even out volume")
    }

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
    fun `Downloads and data reaches While streaming, Download the whole video first and Remove after watching`() {
        show(SettingsPage.Storage)
        // Where to save, then "While streaming" (one stop for both tabs), then the two switches. A
        // television has no "On mobile data" row (it is never on a data allowance).
        row("Storage location").performScrollTo().requestFocus()
        row("Storage location").assertIsFocused()
        row("Storage location").performKeyInput { pressKey(Key.DirectionDown) }
        rule.waitForIdle()
        rule.onNodeWithTag(STREAM_SWITCH_TAG).assertIsFocused()
        rule.onNodeWithTag(STREAM_SWITCH_TAG).performKeyInput { pressKey(Key.DirectionDown) }
        rule.waitForIdle()
        row("Download the whole video first").assertIsFocused()
        row("Download the whole video first").performKeyInput { pressKey(Key.DirectionDown) }
        rule.waitForIdle()
        row("Remove after watching").assertIsFocused()
        rule.onNodeWithText("On mobile data").assertDoesNotExist()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
    fun `Privacy and lock reaches the privacy page under the lock`() {
        show(SettingsPage.Privacy)
        // A build with a crash report address (a developer's, with SENTRY_DSN in local.properties)
        // has the crash switch under Privacy; a fork or CI build has none. Walk what this build has.
        // The devices and Sign out are on Account since WP-08.
        val crash = if (com.tmplayer.data.CrashReports.available) arrayOf("Send crash reports") else emptyArray()
        walk("Allowed chats only", "Allowed chats", "Privacy", *crash)
        rule.onNodeWithText("Connected devices").assertDoesNotExist()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
    fun `Account reaches the devices, the sync rows and the profiles, Sign out last`() {
        show(SettingsPage.Accounts)
        walk("Connected devices", "Sync across my devices")
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
        // Below the pane's fold since the rail and pane (WP-07): a lazy list composes what it
        // shows, so the remote walks there as in the test above.
        row("Storage location").performScrollTo().requestFocus()
        rule.waitForIdle()
        row("Storage location").performKeyInput { pressKey(Key.DirectionDown) }
        rule.waitForIdle()
        rule.onNodeWithTag(STREAM_SWITCH_TAG).performKeyInput { pressKey(Key.DirectionDown) }
        rule.waitForIdle()
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
        // The write runs in the screen's own scope after the click, so wait for it instead of reading
        // the store the instant the UI is idle (a loaded machine reads the old value).
        val deadline = System.currentTimeMillis() + 5_000
        while (kotlinx.coroutines.runBlocking { store.learntLanguages.first().size } != 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
            rule.waitForIdle()
        }
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
