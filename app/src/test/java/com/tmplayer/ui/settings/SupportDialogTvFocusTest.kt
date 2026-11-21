package com.tmplayer.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import com.tmplayer.data.FormFactor
import com.tmplayer.data.SupportRegion
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.about.SupportActions
import com.tmplayer.ui.about.SupportDialog
import com.tmplayer.ui.about.SupportStep
import com.tmplayer.ui.theme.Motion
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
import org.robolectric.shadows.ShadowDialog
import org.robolectric.Shadows.shadowOf
import org.robolectric.util.ReflectionHelpers
import android.os.Looper
import java.time.Duration

/**
 * The support window under a television remote (D1): the remote lands on the first card, Down
 * reaches the other way, Sponsor and Star, the ask's two answers, OK presses, and Back closes the
 * window (one level at a time from a page). Nothing opens on its own.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
class SupportDialogTvFocusTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    private val opened = mutableListOf<String>()
    private var closed = 0
    private var never = 0

    @After
    fun tearDown() {
        FormFactor.override(false)
        Motion.reduced = false
    }

    private fun open(order: SupportRegion.Order, asking: Boolean = true, start: SupportStep = SupportStep.Ways) {
        FormFactor.override(true)
        Motion.reduced = true
        rule.mainClock.autoAdvance = true
        rule.setContent {
            TMPlayerTheme {
                Box(Modifier.fillMaxSize().background(Tone.background))
                SupportDialog(
                    order = order,
                    title = "Support TMPlayer",
                    text = "Voluntary.",
                    actions = SupportActions(openLink = { opened += it; false }, copy = { true }),
                    onClose = { closed++ },
                    onNever = if (asking) ({ never++ }) else null,
                    start = start,
                )
            }
        }
        windowTakesKeys()
        waitFor { rule.onNode(androidx.compose.ui.test.isFocused()).assertExists() }
    }

    private fun idle() {
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        repeat(40) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(25))
            Thread.sleep(2)
        }
        rule.waitForIdle()
    }

    private fun leaveTouchMode(root: Any?) {
        ReflectionHelpers.callInstanceMethod<Any>(root, "ensureTouchMode", ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, false))
        ReflectionHelpers.callInstanceMethod<Any>(root, "windowFocusChanged", ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, true))
    }

    /** The newest window (the support window) takes the remote, as on a television. */
    private fun windowTakesKeys() {
        leaveTouchMode(rule.activity.window.decorView.parent)
        ShadowDialog.getLatestDialog()?.window?.decorView?.parent?.let { leaveTouchMode(it) }
        idle()
    }

    private fun waitFor(check: () -> Unit) {
        var last: Throwable? = null
        repeat(100) {
            idle()
            try {
                check()
                return
            } catch (e: AssertionError) {
                last = e
            }
        }
        throw last ?: AssertionError("never")
    }

    private fun press(key: Key) {
        rule.onNode(androidx.compose.ui.test.isFocused()).performKeyInput { pressKey(key) }
        idle()
    }

    private fun back() {
        ShadowDialog.getLatestDialog().onBackPressed()
        idle()
    }

    private fun button(text: String) = rule.onNode(hasText(text, substring = true) and hasClickAction())

    @Test
    fun `the remote starts on the first card and Down walks to the other way, Sponsor, Star and the answers`() {
        open(SupportRegion.order("US", null))
        button("Buy me a coffee").assertIsFocused()
        press(Key.DirectionDown)
        button("Pay by UPI (India)").assertIsFocused()
        press(Key.DirectionDown)
        button("Sponsor on GitHub").assertIsFocused()
        press(Key.DirectionRight)
        button("Star on GitHub").assertIsFocused()
        press(Key.DirectionDown)
        // The ask's answers: Down from Star lands on the one under it, Left reaches the other.
        button("Maybe later").assertIsFocused()
        press(Key.DirectionLeft)
        button("Don't show again").assertIsFocused()
    }

    @Test
    fun `in India UPI is the first card and both cards are reached`() {
        open(SupportRegion.order("IN", null), asking = false)
        button("Pay by UPI").assertIsFocused()
        press(Key.DirectionDown)
        button("Buy me a coffee").assertIsFocused()
        press(Key.DirectionDown)
        button("Sponsor on GitHub").assertIsFocused()
        press(Key.DirectionDown)
        button("Close").assertIsFocused()
    }

    @Test
    fun `OK on UPI opens its page, nothing is opened, and Back returns to the card`() {
        open(SupportRegion.order("IN", null), asking = false)
        press(Key.DirectionCenter)
        waitFor { rule.onNodeWithText("Copy the UPI ID").assertIsFocused() }
        assertTrue("nothing leaves the app on its own: $opened", opened.isEmpty())
        // Back goes one level up, to the cards, and the card that opened the page has the remote.
        back()
        assertEquals("the window stays", 0, closed)
        waitFor { button("Pay by UPI").assertIsFocused() }
        back()
        assertEquals("Back on the cards closes the window", 1, closed)
    }

    @Test
    fun `on a television Buy Me a Coffee shows its code because there is no browser`() {
        open(SupportRegion.order("US", null), asking = false)
        press(Key.DirectionCenter)
        waitFor { rule.onNodeWithText("Back").assertIsFocused() }
        rule.onNodeWithText("buymeacoffee.com/nevil.dev").assertExists()
        assertTrue("the television does not try a browser first: $opened", opened.isEmpty())
    }
}
