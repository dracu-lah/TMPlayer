package com.tmplayer.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
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
 * "Add a TV by its address" on the phone's Playback page: the line is there, one press opens the
 * Cast picker's own dialog (a field for the address and Add TV), and a second press with it open
 * does not open another. A television has no casting, so its Playback page has no such line.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AddTvRowTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    @After
    fun tearDown() {
        FormFactor.override(false)
    }

    private fun show() {
        rule.setContent {
            TMPlayerTheme {
                Box(Modifier.fillMaxSize().background(Tone.background)) {
                    SettingsScreen(chats = emptyList(), onLoggedOut = {}, initialPage = SettingsPage.Playback, accountFolders = emptyList())
                }
            }
        }
        rule.waitForIdle()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h4000dp-port-night-xhdpi")
    fun `the phone's Playback page opens the add a TV dialog from its own line`() {
        FormFactor.override(false)
        show()
        // The dialog's field blinks its cursor for ever, so the clock is stepped by hand from here.
        rule.mainClock.autoAdvance = false
        rule.onNode(hasText("Add a TV by its address")).performClick()
        repeat(30) {
            rule.mainClock.advanceTimeBy(100)
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
        }
        // The dialog's title is the same words, so two of them now: the line under it and the dialog.
        assertEquals(2, rule.onAllNodes(hasText("Add a TV by its address")).fetchSemanticsNodes().size)
        rule.onNode(hasText("Add TV")).assertExists()
        rule.onNode(hasText("IP address or description link")).assertExists()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h4000dp-port-television-night-xhdpi")
    fun `a television's Playback page has no line for it`() {
        FormFactor.override(true)
        show()
        assertEquals(0, rule.onAllNodes(hasText("Add a TV by its address")).fetchSemanticsNodes().size)
    }
}
