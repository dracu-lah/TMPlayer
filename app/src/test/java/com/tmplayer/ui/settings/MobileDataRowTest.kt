package com.tmplayer.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.tmplayer.data.FormFactor
import com.tmplayer.data.MobileData
import com.tmplayer.i18n.L
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.theme.TMPlayerTheme
import com.tmplayer.ui.theme.Tone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * "On mobile data: Ask first | Never | Allow" ([MobileDataRow], R2) on a phone at a normal and at
 * twice the font size, and under a remote: the three tabs, the line under the chosen one, a touch
 * picking a tab, and Left and Right walking the three with the focus ring on the group.
 * PNGs under `build/device-test/downloads-tab/` for checking by eye.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MobileDataRowTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    private var choice by mutableStateOf(MobileData.Ask)
    private val picks = mutableListOf<MobileData>()

    @After
    fun tearDown() {
        FormFactor.override(false)
    }

    private fun show(tv: Boolean, fontScale: Float = 1f) {
        FormFactor.override(tv)
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                TMPlayerTheme {
                    Box(Modifier.fillMaxSize().background(Tone.background).padding(16.dp)) {
                        MobileDataRow(choice = choice, onPick = { picks += it; choice = it })
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun shot(name: String) {
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        val file = File("../build/device-test/downloads-tab/$name").absoluteFile
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(file.length() > 0)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun `a phone shows the three answers and the line of the chosen one`() {
        show(tv = false)
        rule.onNodeWithText(L.settingsMobileDataTitle).assertIsDisplayed()
        rule.onNodeWithTag(mobileDataTag(MobileData.Ask)).assertIsDisplayed().assertIsSelected()
        rule.onNodeWithTag(mobileDataTag(MobileData.Never)).assertIsDisplayed().assertIsNotSelected()
        rule.onNodeWithTag(mobileDataTag(MobileData.Allow)).assertIsDisplayed().assertIsNotSelected()
        rule.onNodeWithText(L.settingsMobileDataAskDetail).assertIsDisplayed()
        shot("mobile-data-phone-ask-dark.png")

        rule.onNodeWithTag(mobileDataTag(MobileData.Never)).performClick()
        rule.waitForIdle()
        assertEquals(listOf(MobileData.Never), picks)
        rule.onNodeWithTag(mobileDataTag(MobileData.Never)).assertIsSelected()
        rule.onNodeWithText(L.settingsMobileDataNeverDetail).assertIsDisplayed()
        rule.onNodeWithText(L.settingsMobileDataAskDetail).assertDoesNotExist()
        shot("mobile-data-phone-never-dark.png")

        // The chosen answer pressed again writes nothing.
        rule.onNodeWithTag(mobileDataTag(MobileData.Never)).performClick()
        rule.waitForIdle()
        assertEquals(listOf(MobileData.Never), picks)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-notnight-xhdpi")
    fun `a phone in light shows the same`() {
        show(tv = false)
        rule.onNodeWithTag(mobileDataTag(MobileData.Ask)).assertIsDisplayed()
        shot("mobile-data-phone-ask-light.png")
    }

    @Test
    @Config(sdk = [35], qualifiers = "w360dp-h640dp-port-night-xhdpi")
    fun `a narrow phone at twice the font size still shows all three answers and the whole line`() {
        show(tv = false, fontScale = 2f)
        for (answer in MobileData.entries) rule.onNodeWithTag(mobileDataTag(answer)).assertIsDisplayed()
        rule.onNodeWithText(L.settingsMobileDataAskDetail).assertIsDisplayed()
        shot("mobile-data-phone-narrow-2x.png")
    }

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
    fun `under a remote the group is one stop and Left and Right walk the three answers`() {
        show(tv = true)
        rule.onNodeWithTag(MOBILE_DATA_SWITCH_TAG).requestFocus()
        rule.waitForIdle()
        rule.onNodeWithTag(MOBILE_DATA_SWITCH_TAG).assertIsFocused()
        rule.onNodeWithTag(mobileDataTag(MobileData.Ask)).assertIsSelected()
        shot("mobile-data-tv-ask-focused.png")

        rule.onNodeWithTag(MOBILE_DATA_SWITCH_TAG).performKeyInput { pressKey(Key.DirectionRight) }
        rule.waitForIdle()
        assertEquals(MobileData.Never, choice)
        rule.onNodeWithTag(MOBILE_DATA_SWITCH_TAG).performKeyInput { pressKey(Key.DirectionRight) }
        rule.waitForIdle()
        assertEquals(MobileData.Allow, choice)
        rule.onNodeWithTag(MOBILE_DATA_SWITCH_TAG).assertIsFocused()
        rule.onNodeWithText(L.settingsMobileDataAllowDetail).assertIsDisplayed()
        shot("mobile-data-tv-allow-focused.png")

        // At the end Right stays on Allow: no second write.
        rule.onNodeWithTag(MOBILE_DATA_SWITCH_TAG).performKeyInput { pressKey(Key.DirectionRight) }
        rule.waitForIdle()
        assertEquals(listOf(MobileData.Never, MobileData.Allow), picks)

        rule.onNodeWithTag(MOBILE_DATA_SWITCH_TAG).performKeyInput { pressKey(Key.DirectionLeft) }
        rule.onNodeWithTag(MOBILE_DATA_SWITCH_TAG).performKeyInput { pressKey(Key.DirectionLeft) }
        rule.waitForIdle()
        assertEquals(MobileData.Ask, choice)
        assertEquals(listOf(MobileData.Never, MobileData.Allow, MobileData.Never, MobileData.Ask), picks)
    }
}
