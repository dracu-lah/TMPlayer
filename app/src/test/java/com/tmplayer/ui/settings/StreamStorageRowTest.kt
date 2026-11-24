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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
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
import androidx.compose.ui.graphics.asAndroidBitmap
import com.tmplayer.data.FormFactor
import com.tmplayer.i18n.L
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.theme.TMPlayerTheme
import com.tmplayer.ui.theme.Tone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * "While streaming: Keep video | Save space" ([StreamStorageRow]) on a phone and a television: the
 * two tabs, the line under the chosen one (which says a kept download is never trimmed), a touch
 * picking a tab, and on the TV the remote: the pair is one stop, Left and Right pick a side and OK
 * swaps, with the focus ring on it. PNGs under `build/device-test/stream/` for checking by eye.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StreamStorageRowTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    private var saveSpace by mutableStateOf(false)
    private val picks = mutableListOf<Boolean>()

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
                        StreamStorageRow(saveSpace = saveSpace, onPick = { picks += it; saveSpace = it })
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun shot(name: String) {
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        val file = File("../build/device-test/stream/$name").absoluteFile
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(file.length() > 0)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun `a phone shows both tabs and the line of the chosen one`() {
        show(tv = false)
        rule.onNodeWithText(L.settingsStreamTitle).assertIsDisplayed()
        rule.onNodeWithTag(STREAM_KEEP_TAG).assertIsDisplayed().assertIsSelected()
        rule.onNodeWithTag(STREAM_SAVE_TAG).assertIsDisplayed().assertIsNotSelected()
        rule.onNodeWithText(L.settingsStreamKeepDetail).assertIsDisplayed()
        shot("stream-phone-keep-dark.png")

        rule.onNodeWithTag(STREAM_SAVE_TAG).performClick()
        rule.waitForIdle()
        assertEquals(listOf(true), picks)
        rule.onNodeWithTag(STREAM_SAVE_TAG).assertIsSelected()
        rule.onNodeWithText(L.settingsStreamSaveDetail).assertIsDisplayed()
        rule.onNodeWithText(L.settingsStreamKeepDetail).assertDoesNotExist()
        shot("stream-phone-save-dark.png")

        // The chosen tab pressed again does nothing: no write for no change.
        rule.onNodeWithTag(STREAM_SAVE_TAG).performClick()
        rule.waitForIdle()
        assertEquals(listOf(true), picks)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-notnight-xhdpi")
    fun `a phone in light shows the same`() {
        show(tv = false)
        rule.onNodeWithTag(STREAM_KEEP_TAG).assertIsDisplayed()
        shot("stream-phone-keep-light.png")
    }

    @Test
    @Config(sdk = [35], qualifiers = "w360dp-h640dp-port-night-xhdpi")
    fun `a narrow phone at twice the font size still shows both tabs and the whole line`() {
        show(tv = false, fontScale = 2f)
        rule.onNodeWithTag(STREAM_KEEP_TAG).assertIsDisplayed()
        rule.onNodeWithTag(STREAM_SAVE_TAG).assertIsDisplayed()
        rule.onNodeWithText(L.settingsStreamKeepDetail).assertIsDisplayed()
        shot("stream-phone-narrow-2x.png")
    }

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
    fun `a television takes the pair as one stop, Left and Right pick a side and OK swaps`() {
        show(tv = true)
        rule.onNodeWithTag(STREAM_SWITCH_TAG).requestFocus()
        rule.waitForIdle()
        rule.onNodeWithTag(STREAM_SWITCH_TAG).assertIsFocused()
        rule.onNodeWithTag(STREAM_KEEP_TAG).assertIsSelected()
        shot("stream-tv-keep-focused.png")

        rule.onNodeWithTag(STREAM_SWITCH_TAG).performKeyInput { pressKey(Key.DirectionRight) }
        rule.waitForIdle()
        assertTrue(saveSpace)
        rule.onNodeWithTag(STREAM_SAVE_TAG).assertIsSelected()
        rule.onNodeWithText(L.settingsStreamSaveDetail).assertIsDisplayed()
        rule.onNodeWithTag(STREAM_SWITCH_TAG).assertIsFocused()
        shot("stream-tv-save-focused.png")

        // Right again stays on Save space: no second write.
        rule.onNodeWithTag(STREAM_SWITCH_TAG).performKeyInput { pressKey(Key.DirectionRight) }
        rule.waitForIdle()
        assertEquals(listOf(true), picks)

        rule.onNodeWithTag(STREAM_SWITCH_TAG).performKeyInput { pressKey(Key.DirectionLeft) }
        rule.waitForIdle()
        assertFalse(saveSpace)
        rule.onNodeWithTag(STREAM_SWITCH_TAG).performKeyInput { pressKey(Key.DirectionCenter) }
        rule.waitForIdle()
        assertTrue(saveSpace)
        assertEquals(listOf(true, false, true), picks)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
    fun `the television's switch is one named button that says which side is chosen (F-08)`() {
        show(tv = true)
        val config = rule.onNodeWithTag(STREAM_SWITCH_TAG).fetchSemanticsNode().config
        assertEquals(L.settingsStreamTitle, config[SemanticsProperties.ContentDescription].single())
        assertEquals(Role.Button, config[SemanticsProperties.Role])
        assertEquals(L.settingsStreamKeep, config[SemanticsProperties.StateDescription])
        rule.onNodeWithTag(STREAM_SWITCH_TAG).requestFocus()
        rule.waitForIdle()
        rule.onNodeWithTag(STREAM_SWITCH_TAG).performKeyInput { pressKey(Key.DirectionRight) }
        rule.waitForIdle()
        assertEquals(L.settingsStreamSave, rule.onNodeWithTag(STREAM_SWITCH_TAG).fetchSemanticsNode().config[SemanticsProperties.StateDescription])
    }

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-notnight-xhdpi")
    fun `a television in light`() {
        show(tv = true)
        rule.onNodeWithTag(STREAM_SWITCH_TAG).requestFocus()
        rule.waitForIdle()
        shot("stream-tv-keep-light.png")
    }
}
