package com.tmplayer.player

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import com.tmplayer.data.FormFactor
import com.tmplayer.i18n.L
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.components.ChoiceLine
import com.tmplayer.ui.components.ChoiceList
import com.tmplayer.ui.theme.TmMaterialTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Player volume (#33) and Rotate picture (#34) by remote alone. The More panel's line steps the
 * volume with Left and Right and keeps the focus, so Down still walks on; the volume's page lands
 * on Quieter, steps with OK and with the arrows from any line; the rotation's page lands on the
 * turn in force and OK turns the picture with the page left up.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
class VolumeAndRotationFocusTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    @After
    fun tearDown() {
        FormFactor.override(false)
    }

    /** The page drawn in place, as the More panel draws it, rather than in a window of its own. */
    @androidx.compose.runtime.Composable
    private fun Inline(content: @androidx.compose.runtime.Composable () -> Unit) {
        androidx.compose.runtime.CompositionLocalProvider(
            com.tmplayer.ui.components.LocalChoicePageHost provides { page -> Column { page.content(this) } },
            content = content,
        )
    }

    /** Out of touch mode with the window focused, as a television's remote leaves it. */
    private fun remoteMode() {
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

    @Test
    fun `the panel's Player volume line steps with Left and Right and keeps the focus`() {
        FormFactor.override(true)
        val entries = PlayerMenu.tvEntries(pictureInPicture = false, playerVolume = true, rotatePicture = true, pictureShape = true)
        val at = entries.indexOf(PlayerMenuEntry.PlayerVolume)
        // The last of the Picture group, then the Sound group opens with Player volume (WP-20).
        assertEquals(PlayerMenuEntry.RotatePicture, entries[at - 1])
        assertEquals(PlayerMenuEntry.VolumeBoost, entries[at + 1])
        assertEquals(entries.indexOf(PlayerMenuEntry.PictureShape) + 1, entries.indexOf(PlayerMenuEntry.RotatePicture))
        var volume by mutableIntStateOf(50)
        var opened = 0
        rule.setContent {
            TmMaterialTheme(dark = true) {
                Column(Modifier.fillMaxSize()) {
                    ChoiceList {
                        ChoiceLine(title = L.playerSpeed, onClick = {})
                        ChoiceLine(
                            title = L.playerVolumeLevel,
                            detail = "$volume",
                            modifier = Modifier.volumeStepKeys { up -> volume = PlayerVolume.step(volume, up) },
                            onClick = { opened++ },
                        )
                        ChoiceLine(title = L.playerVolumeBoost, onClick = {})
                    }
                }
            }
        }
        remoteMode()

        rule.onNodeWithText(L.playerSpeed).requestFocus()
        rule.onNodeWithText(L.playerSpeed).performKeyInput { pressKey(Key.DirectionDown) }
        rule.waitForIdle()
        rule.onNodeWithText(L.playerVolumeLevel).assertIsFocused()

        rule.onNodeWithText(L.playerVolumeLevel).performKeyInput { pressKey(Key.DirectionLeft) }
        rule.onNodeWithText(L.playerVolumeLevel).performKeyInput { pressKey(Key.DirectionLeft) }
        rule.waitForIdle()
        assertEquals(48, volume)
        rule.onNodeWithText(L.playerVolumeLevel).assertIsFocused()
        rule.onNodeWithText(L.playerVolumeLevel).performKeyInput { pressKey(Key.DirectionRight) }
        rule.waitForIdle()
        assertEquals(49, volume)
        rule.onNodeWithText(L.playerVolumeLevel).assertIsFocused()

        // OK opens the page; the arrows never did.
        rule.onNodeWithText(L.playerVolumeLevel).performKeyInput { pressKey(Key.DirectionCenter) }
        rule.waitForIdle()
        assertEquals(1, opened)

        rule.onNodeWithText(L.playerVolumeLevel).performKeyInput { pressKey(Key.DirectionDown) }
        rule.waitForIdle()
        rule.onNodeWithText(L.playerVolumeBoost).assertIsFocused()
    }

    @Test
    fun `the volume page lands on Quieter, steps with OK and the arrows, and goes down to 0`() {
        FormFactor.override(true)
        var volume by mutableIntStateOf(2)
        val heard = mutableListOf<Int>()
        // Out of touch mode before the page opens, so its first focus request lands as on a television.
        remoteMode()
        rule.setContent {
            TmMaterialTheme(dark = true) {
                Column(Modifier.fillMaxSize()) {
                    Inline { VolumeSheet(current = volume, onChange = { volume = it; heard += it }, onDismiss = {}, onClose = {}) }
                }
            }
        }
        remoteMode()
        // FocusOnOpen puts the remote on the line a frame or two after the page is drawn.
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
        rule.onNodeWithText(L.playerVolumeLevelQuieter).assertIsFocused()

        rule.onNodeWithText(L.playerVolumeLevelQuieter).performKeyInput { pressKey(Key.DirectionCenter) }
        rule.waitForIdle()
        assertEquals(1, volume)
        rule.onNodeWithText(L.playerVolumeLevelQuieter).performKeyInput { pressKey(Key.DirectionLeft) }
        rule.waitForIdle()
        assertEquals(0, volume)
        // At 0 a further step changes nothing and says nothing.
        rule.onNodeWithText(L.playerVolumeLevelQuieter).performKeyInput { pressKey(Key.DirectionLeft) }
        rule.waitForIdle()
        assertEquals(listOf(1, 0), heard)
        rule.onNodeWithText(L.playerVolumeLevelQuieter).assertIsFocused()

        rule.onNodeWithText(L.playerVolumeLevelQuieter).performKeyInput { pressKey(Key.DirectionDown) }
        rule.waitForIdle()
        rule.onNodeWithText(L.playerVolumeLevelLouder).assertIsFocused()
        rule.onNodeWithText(L.playerVolumeLevelLouder).performKeyInput { pressKey(Key.DirectionCenter) }
        rule.onNodeWithText(L.playerVolumeLevelLouder).performKeyInput { pressKey(Key.DirectionRight) }
        rule.waitForIdle()
        assertEquals(2, volume)

        val full = L.playerVolumeLevelFull(L.messages.formatter.scalePercent(PlayerVolume.FULL))
        rule.onNodeWithText(L.playerVolumeLevelLouder).performKeyInput { pressKey(Key.DirectionDown) }
        rule.waitForIdle()
        rule.onNodeWithText(full).assertIsFocused()
        rule.onNodeWithText(full).performKeyInput { pressKey(Key.DirectionCenter) }
        rule.waitForIdle()
        assertEquals(PlayerVolume.FULL, volume)
        rule.onNodeWithText(full).assertIsFocused()
    }

    @Test
    fun `the rotation page lands on the turn in force and OK turns with the page left up`() {
        FormFactor.override(true)
        var rotation by mutableIntStateOf(90)
        val picked = mutableListOf<Int>()
        // Out of touch mode before the page opens, so its first focus request lands as on a television.
        remoteMode()
        rule.setContent {
            TmMaterialTheme(dark = true) {
                Column(Modifier.fillMaxSize()) {
                    Inline { RotationSheet(current = rotation, refused = null, onPick = { rotation = it; picked += it }, onDismiss = {}, onClose = {}) }
                }
            }
        }
        remoteMode()
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
        rule.onNodeWithText(L.playerRotatePictureRight).assertIsFocused()
        // Named as the picture's turn, never as the screen's orientation.
        assertTrue(L.playerRotatePicture != L.playerLabelRotate)

        rule.onNodeWithText(L.playerRotatePictureRight).performKeyInput { pressKey(Key.DirectionDown) }
        rule.waitForIdle()
        rule.onNodeWithText(L.playerRotatePictureHalf).assertIsFocused()
        rule.onNodeWithText(L.playerRotatePictureHalf).performKeyInput { pressKey(Key.DirectionCenter) }
        rule.waitForIdle()
        assertEquals(listOf(180), picked)
        rule.onNodeWithText(L.playerRotatePictureHalf).assertIsFocused()

        rule.onNodeWithText(L.playerRotatePictureHalf).performKeyInput { pressKey(Key.DirectionUp) }
        rule.onNodeWithText(L.playerRotatePictureRight).performKeyInput { pressKey(Key.DirectionUp) }
        rule.waitForIdle()
        rule.onNodeWithText(L.playerRotatePictureNone).assertIsFocused()
        rule.onNodeWithText(L.playerRotatePictureNone).performKeyInput { pressKey(Key.DirectionCenter) }
        rule.waitForIdle()
        assertEquals(listOf(180, 0), picked)
    }
}
