package com.tmplayer.ui.settings

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.tmplayer.data.FormFactor
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.theme.TMPlayerTheme
import com.tmplayer.ui.theme.Tone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * UX rule 3: Settings' search words are per visit. They are kept while a page opened from a result
 * is up (Back returns to the results) and through a turn of the screen, and gone once the viewer has
 * left Settings and comes back. The host here keeps screens' saved state the way the phone's
 * MainActivity does, which is what used to hand the old words back.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsSearchLeaveTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    @After
    fun tearDown() {
        FormFactor.override(false)
    }

    private var shown by mutableStateOf("settings")
    private var page by mutableStateOf<SettingsPage?>(null)
    private var left = 0

    private fun host(restoration: StateRestorationTester? = null) {
        FormFactor.override(false)
        val set: (@Composable () -> Unit) -> Unit = { restoration?.setContent(it) ?: rule.setContent(it) }
        set {
            TMPlayerTheme {
                Box(Modifier.fillMaxSize().background(Tone.background)) {
                    // The app's own handler, composed first as MainActivity's leaveSettings is.
                    BackHandler { left++ }
                    val holder = rememberSaveableStateHolder()
                    holder.SaveableStateProvider(shown) {
                        if (shown == "settings") {
                            SettingsScreen(
                                chats = emptyList(),
                                onLoggedOut = {},
                                initialPage = page,
                                onPageChange = { page = it },
                                accountFolders = emptyList(),
                            )
                        } else {
                            Text("elsewhere")
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun row(title: String) = rule.onNode(hasText(title) and hasClickAction())

    /** The words in the box, or "" while it shows its hint. */
    private fun typed(): String = rule.onNodeWithTag(SEARCH_TAG).fetchSemanticsNode().config
        .getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

    private fun settle() {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun `leaving Settings and returning finds the box empty`() {
        host()
        rule.onNodeWithTag(SEARCH_TAG).performTextInput("audio lang")
        rule.waitForIdle()
        shown = "elsewhere"
        settle()
        rule.onNodeWithText("elsewhere").assertExists()
        shown = "settings"
        settle()
        assertEquals("", typed())
        rule.onNodeWithText("Playback").assertExists()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun `a page opened from a result keeps the words for Back, and leaving from the list clears them`() {
        host()
        rule.onNodeWithTag(SEARCH_TAG).performTextInput("audio lang")
        rule.waitForIdle()
        row("Preferred audio language").performClick()
        settle()
        assertEquals(SettingsPage.Subtitles, page)
        // Away to About (a screen of its own) and back with the page: still the results' words.
        shown = "elsewhere"
        settle()
        shown = "settings"
        settle()
        rule.activity.onBackPressedDispatcher.onBackPressed()
        settle()
        assertEquals("audio lang", typed())
        row("Preferred audio language").assertExists()
        // Now the list, with words in the box: leaving clears them.
        page = null
        shown = "elsewhere"
        settle()
        shown = "settings"
        settle()
        assertEquals("", typed())
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun `turning the screen keeps the words`() {
        val restoration = StateRestorationTester(rule)
        host(restoration)
        rule.onNodeWithTag(SEARCH_TAG).performTextInput("audio lang")
        rule.waitForIdle()
        restoration.emulateSavedInstanceStateRestore()
        settle()
        assertEquals("audio lang", typed())
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun `Back clears the words first and only then leaves`() {
        host()
        rule.onNodeWithTag(SEARCH_TAG).performTextInput("audio lang")
        rule.waitForIdle()
        rule.activity.onBackPressedDispatcher.onBackPressed()
        settle()
        assertEquals("", typed())
        assertEquals(0, left)
        rule.activity.onBackPressedDispatcher.onBackPressed()
        settle()
        assertEquals(1, left)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun `the clear control empties the box and the focus stays in it`() {
        host()
        rule.onNodeWithTag(SEARCH_TAG).performTextInput("audio lang")
        rule.onNodeWithTag(SEARCH_TAG).requestFocus()
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Clear the search").performClick()
        settle()
        assertEquals("", typed())
        rule.onNodeWithTag(SEARCH_TAG).assertIsFocused()
    }
}
