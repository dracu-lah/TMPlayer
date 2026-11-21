package com.tmplayer.ui.connect

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.pressKey
import com.tmplayer.data.FormFactor
import com.tmplayer.i18n.L
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.TMPlayerTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

/**
 * The connecting help and the banner after Raju group F and FQ2 and FQ7: the lead is plain words
 * and Try again, and the proxy, the device's report and the logs wait behind More help. On a
 * television Try again has the remote, Right reaches More help, and what it opens is reached by
 * Down. At a font twice the size every button is still on screen.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConnectHelpFocusTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    private var retried = 0
    private var proxies = 0

    @After
    fun tearDown() {
        FormFactor.override(false)
        Motion.reduced = false
    }

    /** A remote has put the window out of touch mode, so a button can hold the focus. */
    private fun leaveTouchMode() {
        val root = rule.activity.window.decorView.parent
        ReflectionHelpers.callInstanceMethod<Any>(
            root, "ensureTouchMode",
            ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, false),
        )
        ReflectionHelpers.callInstanceMethod<Any>(
            root, "windowFocusChanged",
            ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, true),
        )
    }

    private fun showHelp(tv: Boolean) {
        FormFactor.override(tv)
        if (tv) leaveTouchMode()
        Motion.reduced = true
        rule.setContent {
            TMPlayerTheme {
                ConnectingHelpView(
                    status = "Connecting",
                    stalled = true,
                    hint = L.connectHintBlockedPlain,
                    network = "This device reports: Wi-Fi",
                    proxyOn = false,
                    onRetry = { retried++ },
                    onOpenProxies = { proxies++ },
                    sendLogs = { Text("Save logs") },
                )
            }
        }
        rule.waitForIdle()
    }

    private fun showBanner(tv: Boolean) {
        FormFactor.override(tv)
        if (tv) leaveTouchMode()
        Motion.reduced = true
        rule.setContent {
            TMPlayerTheme {
                ConnectBannerView(
                    text = L.connectHintBlockedPlain,
                    onRetry = { retried++ },
                    onOpenProxies = { proxies++ },
                )
            }
        }
        rule.waitForIdle()
    }

    private fun assertHidden(vararg words: String) {
        for (w in words) rule.onNodeWithText(w).assertDoesNotExist()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
    fun `tv, the connecting help leads with Try again and keeps the jargon behind More help`() {
        showHelp(tv = true)
        rule.onNodeWithText(L.connectSlowPlain).assertIsDisplayed()
        rule.onNodeWithText(L.commonTryAgain).assertIsFocused()
        assertHidden(L.connectUseProxy, "Save logs", "This device reports: Wi-Fi")

        // Right reaches More help, the centre key opens it, Down reaches what it opened.
        rule.onNodeWithText(L.commonTryAgain).performKeyInput { pressKey(Key.DirectionRight) }
        rule.onNodeWithText(L.connectMoreHelp).assertIsFocused()
        rule.onNodeWithText(L.connectMoreHelp).performKeyInput { pressKey(Key.DirectionCenter) }
        rule.waitForIdle()
        rule.onNodeWithText(L.connectLessHelp).assertIsFocused()
        rule.onNodeWithText(L.connectUseProxy).assertIsDisplayed()
        rule.onNodeWithText("Save logs").assertIsDisplayed()
        rule.onNodeWithText(L.connectLessHelp).performKeyInput { pressKey(Key.DirectionDown) }
        rule.onNodeWithText(L.connectUseProxy).assertIsFocused()
        rule.onNodeWithText(L.connectUseProxy).performKeyInput { pressKey(Key.DirectionCenter) }
        assertEquals(1, proxies)
        assertEquals(0, retried)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
    fun `tv, the centre key on the first focus presses Try again`() {
        showHelp(tv = true)
        rule.onNodeWithText(L.commonTryAgain).performKeyInput { pressKey(Key.DirectionCenter) }
        assertEquals(1, retried)
        assertEquals(0, proxies)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun `phone, the banner leads with Try again and More help, and never says proxy`() {
        showBanner(tv = false)
        rule.onNodeWithText(L.connectBanner).assertIsDisplayed()
        rule.onNodeWithText(L.commonTryAgain).assertIsDisplayed()
        assertHidden(L.connectUseProxy, L.connectProxyOn)
        rule.onNodeWithText(L.commonTryAgain).performClick()
        assertEquals(1, retried)
        rule.onNodeWithText(L.connectMoreHelp).performClick()
        assertEquals(1, proxies)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
    fun `tv, the banner's buttons are reached by Right and pressed by the centre key`() {
        showBanner(tv = true)
        rule.onNodeWithText(L.commonTryAgain).requestFocus()
        rule.onNodeWithText(L.commonTryAgain).assertIsFocused()
        rule.onNodeWithText(L.commonTryAgain).performKeyInput { pressKey(Key.DirectionRight) }
        rule.onNodeWithText(L.connectMoreHelp).assertIsFocused()
        rule.onNodeWithText(L.connectMoreHelp).performKeyInput { pressKey(Key.DirectionCenter) }
        assertEquals(1, proxies)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi", fontScale = 2.0f)
    fun `phone at twice the font, the banner keeps both buttons on screen`() {
        showBanner(tv = false)
        rule.onNodeWithText(L.commonTryAgain).assertIsDisplayed()
        rule.onNodeWithText(L.connectMoreHelp).assertIsDisplayed()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi", fontScale = 2.0f)
    fun `phone at twice the font, the connecting help keeps Try again and More help on screen`() {
        showHelp(tv = false)
        rule.onNodeWithText(L.connectSlowPlain).assertIsDisplayed()
        rule.onNodeWithText(L.commonTryAgain).assertIsDisplayed()
        rule.onNodeWithText(L.connectMoreHelp).assertIsDisplayed()
    }
}
