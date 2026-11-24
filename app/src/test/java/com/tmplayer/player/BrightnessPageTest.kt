package com.tmplayer.player

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.tmplayer.data.FormFactor
import com.tmplayer.i18n.L
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.theme.TMPlayerTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** WP-27: Brightness is a line of More beside Player volume, and its page steps by ten and follows the system on request. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-port-night-xhdpi")
class BrightnessPageTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val drain = DrainMainLooperRule()

    @After
    fun tearDown() {
        FormFactor.override(false)
    }

    @Test
    fun theLineSitsBesidePlayerVolumeAndIsAPhonesOnly() {
        val phone = PlayerMenu.phoneEntries(pictureInPicture = false, openInAnotherApp = false, saveToDownloads = false, markWatched = false, playerVolume = true, brightness = true)
        assertEquals(phone.indexOf(PhoneMenuEntry.PlayerVolume) + 1, phone.indexOf(PhoneMenuEntry.Brightness))
        assertTrue(PhoneMenuEntry.Brightness !in PlayerMenu.phoneEntries(pictureInPicture = false, openInAnotherApp = false, saveToDownloads = false, markWatched = false, playerVolume = true))
        assertTrue(PlayerMenuEntry.entries.none { it.name == "Brightness" })
    }

    @Test
    fun theSheetStepsAndFollowsTheSystem() {
        FormFactor.override(false)
        val levels = mutableListOf<Int>()
        rule.setContent {
            TMPlayerTheme { BrightnessSheet(current = 50, onChange = { levels += it }, onDismiss = {}, onClose = {}) }
        }
        rule.waitForIdle()
        rule.onNodeWithContentDescription(L.playerBrightnessBrighter).performClick()
        rule.onNodeWithContentDescription(L.playerBrightnessDimmer).performClick()
        rule.onNodeWithText(L.playerBrightnessSystem).performClick()
        assertEquals(listOf(60, 40, TouchPrefs.BRIGHTNESS_SYSTEM), levels)
    }
}
