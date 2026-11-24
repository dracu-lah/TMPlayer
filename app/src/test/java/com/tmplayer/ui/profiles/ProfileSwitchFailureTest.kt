package com.tmplayer.ui.profiles

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A profile switch that fails says whom it could not open and offers Try again and Send logs
 * (WP-96), the remote starting on Try again; while it runs the line says whom it opens, as a live
 * region the screen reader speaks.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
class ProfileSwitchFailureTest {

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
                ProfilesGate(flow, signInStep = false, sendLogs = { Text(LOGS) }) { Text(HOME) }
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

    /** The remote from the first tile to Ben's, and OK. */
    private fun openBen() {
        tile("Asha").performKeyInput { pressKey(Key.DirectionRight) }
        rule.waitForIdle()
        tile("Ben").assertIsFocused()
        tile("Ben").performKeyInput { pressKey(Key.DirectionCenter) }
        rule.waitForIdle()
    }

    private fun has(text: String) = runCatching { rule.onNodeWithText(text).assertExists() }.isSuccess

    @Test
    fun `a failed switch names the profile, offers Try again and Send logs, and the remote starts on Try again`() {
        val k = ProfileKit("Asha", "Ben").also { kit = it }
        k.host.failReopen = true
        show(k.flow())
        tile("Asha").assertIsFocused()
        openBen()
        rule.waitUntil(15_000) { has(L.commonTryAgain) }
        rule.waitForIdle()
        rule.onNodeWithText(L.profilesSwitchFailed(name = "Ben")).assertExists()
        rule.onNodeWithText(LOGS).assertExists()
        rule.onNodeWithText(L.commonOk).assertExists()
        rule.onNodeWithText(L.commonTryAgain).assertIsFocused()
    }

    @Test
    fun `Try again asks for the same switch again and opens the profile`() {
        val k = ProfileKit("Asha", "Ben").also { kit = it }
        k.host.failReopen = true
        show(k.flow())
        openBen()
        rule.waitUntil(15_000) { has(L.commonTryAgain) }
        k.host.failReopen = false
        rule.onNodeWithText(L.commonTryAgain).performKeyInput { pressKey(Key.DirectionCenter) }
        rule.waitUntil(15_000) { has(HOME) }
        assertEquals(k.ids[1], k.book.list.value.activeId)
    }

    @Test
    fun `the line that says whom a switch opens is a live region`() {
        val k = ProfileKit("Asha", "Ben").also { kit = it }
        k.platform.hold = kotlinx.coroutines.CompletableDeferred()
        show(k.flow())
        openBen()
        // "Opening Ben", then "Finishing first: a sync" while the open profile's work stops.
        val line = androidx.compose.ui.test.hasText(L.profilesSwitching(name = "Ben")) or
            androidx.compose.ui.test.hasText(L.profilesWaiting(what = "a sync"))
        rule.waitUntil(15_000) { rule.onAllNodes(line).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(line).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
        k.platform.hold?.complete(Unit)
    }

    private companion object {
        const val HOME = "The shell of the open profile"
        const val LOGS = "Send logs probe"
    }
}
