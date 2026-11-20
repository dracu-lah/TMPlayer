package com.tmplayer.ui.profiles

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import com.tmplayer.data.FormFactor
import com.tmplayer.i18n.L
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.lock.ProfileFlow
import com.tmplayer.ui.lock.ProfilesGate
import com.tmplayer.ui.theme.TmMaterialTheme
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

/**
 * "Who's watching?" by remote alone (HX88): the first tile has focus, Left and Right move along
 * the row, Down goes to Add account and on to Manage profiles, OK opens, a long OK and the Menu
 * key open the options, the focus comes back to the tile when they close, the PIN pad is walked
 * with the D-pad, and Back leaves the app only from the picker at launch.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
class ProfilePickerTvTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val drain = DrainMainLooperRule()

    private var kit: ProfileKit? = null

    @After
    fun tearDown() {
        kit?.close()
        FormFactor.override(false)
    }

    private fun show(flow: ProfileFlow) {
        FormFactor.override(true)
        rule.setContent {
            TmMaterialTheme(dark = true) {
                ProfilesGate(flow, signInStep = false) { Text(HOME) }
            }
        }
        // A remote, not a finger: out of touch mode, with the window focused.
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

    private fun tile(name: String) = rule.onNodeWithContentDescription(name, substring = true)

    private fun press(node: androidx.compose.ui.test.SemanticsNodeInteraction, key: Key) {
        node.performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    @Test
    fun `the first tile has focus, Left and Right move, Down goes to Add and then Manage`() {
        val k = ProfileKit("Asha", "Ben").also { kit = it }
        show(k.flow())
        tile("Asha").assertIsFocused()
        press(tile("Asha"), Key.DirectionRight)
        tile("Ben").assertIsFocused()
        press(tile("Ben"), Key.DirectionLeft)
        tile("Asha").assertIsFocused()
        press(tile("Asha"), Key.DirectionDown)
        rule.onNodeWithText(L.profilesAdd).assertIsFocused()
        press(rule.onNodeWithText(L.profilesAdd), Key.DirectionDown)
        rule.onNodeWithText(L.profilesManage).assertIsFocused()
    }

    @Test
    fun `OK opens the profile`() {
        val k = ProfileKit("Asha", "Ben").also { kit = it }
        show(k.flow())
        press(tile("Asha"), Key.DirectionRight)
        press(tile("Ben"), Key.DirectionCenter)
        rule.waitUntil(15_000) { runCatching { rule.onNodeWithText(HOME).assertExists() }.isSuccess }
        assertEquals(k.ids[1], k.book.list.value.activeId)
    }

    @Test
    fun `a long OK opens the options, Back closes them and the focus is on the tile again`() {
        val k = ProfileKit("Asha", "Ben").also { kit = it }
        show(k.flow())
        press(tile("Asha"), Key.DirectionRight)
        tile("Ben").performKeyInput {
            keyDown(Key.DirectionCenter)
            advanceEventTime(1_000)
            keyUp(Key.DirectionCenter)
        }
        rule.waitForIdle()
        rule.onNodeWithText(L.profilesRename).assertIsFocused()
        assertEquals("a long OK opens nothing", k.ids[0], k.book.list.value.activeId)
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        rule.onNodeWithText(L.profilesRename).assertDoesNotExist()
        tile("Ben").assertIsFocused()
        assertFalse(rule.activity.isFinishing)
    }

    @Test
    fun `the Menu key opens the options too`() {
        val k = ProfileKit("Asha", "Ben").also { kit = it }
        show(k.flow())
        press(tile("Asha"), Key.Menu)
        rule.onNodeWithText(L.profilesSignOut).assertExists()
        rule.onNodeWithText(L.profilesRename).assertIsFocused()
    }

    @Test
    fun `a locked tile opens its PIN pad, walked with the D-pad`() {
        val k = ProfileKit("Asha", "Ben").also { kit = it }
        k.pin(1, "1234")
        show(k.flow())
        press(tile("Asha"), Key.DirectionRight)
        press(tile("Ben"), Key.DirectionCenter)
        rule.onNodeWithText(L.profilesPinFor(name = "Ben")).assertExists()
        // The pad's first key has the remote; Right walks to 2, OK types it.
        rule.onNodeWithText("1").assertIsFocused()
        press(rule.onNodeWithText("1"), Key.DirectionCenter)
        press(rule.onNodeWithText("1"), Key.DirectionRight)
        rule.onNodeWithText("2").assertIsFocused()
        press(rule.onNodeWithText("2"), Key.DirectionCenter)
        press(rule.onNodeWithText("2"), Key.DirectionRight)
        press(rule.onNodeWithText("3"), Key.DirectionCenter)
        press(rule.onNodeWithText("3"), Key.DirectionDown)
        rule.onNodeWithText("6").assertIsFocused()
        press(rule.onNodeWithText("6"), Key.DirectionLeft)
        press(rule.onNodeWithText("5"), Key.DirectionLeft)
        press(rule.onNodeWithText("4"), Key.DirectionCenter)
        rule.waitUntil(15_000) { runCatching { rule.onNodeWithText(HOME).assertExists() }.isSuccess }
        assertEquals(k.ids[1], k.book.list.value.activeId)
    }

    @Test
    fun `Back leaves the app from the picker at launch`() {
        val k = ProfileKit("Asha", "Ben").also { kit = it }
        show(k.flow())
        tile("Asha").assertIsFocused()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        assertTrue(rule.activity.isFinishing)
    }

    @Test
    fun `a notice keeps the remote in it and gives the focus back to the tile when it closes`() {
        val k = ProfileKit("Asha", "Ben", "Cleo").also { kit = it }
        val flow = k.flow()
        show(flow)
        tile("Asha").assertIsFocused()
        // Add account at the limit: the notice says so.
        flow.add()
        rule.waitUntil(15_000) { flow.notice != null }
        rule.waitForIdle()
        rule.onNodeWithText(L.commonOk).assertIsFocused()
        press(rule.onNodeWithText(L.commonOk), Key.DirectionUp)
        rule.onNodeWithText(L.commonOk).assertIsFocused()
        press(rule.onNodeWithText(L.commonOk), Key.DirectionDown)
        rule.onNodeWithText(L.commonOk).assertIsFocused()
        press(rule.onNodeWithText(L.commonOk), Key.DirectionCenter)
        // Once the row takes presses again (what the failed Add paused is resumed first).
        rule.waitUntil(5_000) { runCatching { tile("Asha").assertIsFocused() }.isSuccess }
        tile("Asha").assertIsFocused()
    }

    @Test
    fun `while a switch waits the remote is on Cancel, and OK on it stays on the open profile`() {
        val k = ProfileKit("Asha", "Ben").also { kit = it }
        k.platform.hold = kotlinx.coroutines.CompletableDeferred()
        show(k.flow())
        press(tile("Asha"), Key.DirectionRight)
        press(tile("Ben"), Key.DirectionCenter)
        rule.waitUntil(15_000) { runCatching { rule.onNodeWithText(L.commonCancel).assertIsFocused() }.isSuccess }
        press(rule.onNodeWithText(L.commonCancel), Key.DirectionCenter)
        rule.waitUntil(5_000) { runCatching { rule.onNodeWithText(L.commonCancel).assertDoesNotExist() }.isSuccess }
        k.platform.hold?.complete(Unit)
        Thread.sleep(300)
        rule.waitForIdle()
        assertEquals("nothing was switched", k.ids[0], k.book.list.value.activeId)
        assertEquals("what the switch paused goes on again", 1, k.platform.resumed)
    }

    private companion object {
        const val HOME = "The shell of the open profile"
    }
}
