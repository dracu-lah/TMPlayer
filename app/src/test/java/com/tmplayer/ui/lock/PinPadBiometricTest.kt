package com.tmplayer.ui.lock

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.tmplayer.data.PinClock
import com.tmplayer.data.SettingsStore
import com.tmplayer.i18n.L
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.theme.TMPlayerTheme
import kotlinx.coroutines.runBlocking
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
 * WP-41, security: a fingerprint opens the app lock's pad and nothing else. It is offered only with a
 * ready sensor, never while the pad waits out wrong PINs, a cancel or an error leaves the pad locked,
 * and the pad of a profile's PIN (the checker overload) cannot be given one at all.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-port-night-xhdpi")
class PinPadBiometricTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val drain = DrainMainLooperRule()

    /** A sensor that records what it was asked and answers as scripted. */
    private class FakeSensor(var ready: Boolean = true, var outcome: Outcome = Outcome.Success) : BiometricUnlock {
        enum class Outcome { Success, Cancel, Hold }

        var prompts = 0
        var cancels = 0
        var pendingSuccess: (() -> Unit)? = null

        override fun available(): Boolean = ready

        override fun prompt(onSuccess: () -> Unit, onEnd: () -> Unit) {
            prompts++
            when (outcome) {
                Outcome.Success -> onSuccess()
                Outcome.Cancel -> onEnd()
                Outcome.Hold -> pendingSuccess = onSuccess
            }
        }

        override fun cancel() {
            cancels++
        }
    }

    private val settings get() = SettingsStore(ApplicationProvider.getApplicationContext<android.content.Context>())
    private var done = 0
    private var unlockedByFinger = 0

    private fun show(sensor: BiometricUnlock?) {
        rule.setContent {
            TMPlayerTheme {
                PinPad(
                    settings = settings,
                    purpose = PinPurpose.Check,
                    title = "Enter the PIN",
                    onDone = { done++ },
                    onCancel = null,
                    biometric = sensor,
                    onBiometric = { unlockedByFinger++ },
                )
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun aReadySensorIsAskedOnceWhenThePadOpensAndItsSuccessUnlocksWithoutAPin() {
        val sensor = FakeSensor()
        show(sensor)
        assertEquals("asked once, by itself", 1, sensor.prompts)
        assertEquals(1, unlockedByFinger)
        assertEquals("the PIN's own way out is not taken", 0, done)
    }

    @Test
    fun aCancelLeavesThePadLockedWithTheButtonToTryAgain() {
        val sensor = FakeSensor(outcome = FakeSensor.Outcome.Cancel)
        show(sensor)
        assertEquals(1, sensor.prompts)
        assertEquals(0, unlockedByFinger)
        assertEquals(0, done)
        rule.onNodeWithText(L.lockUseBiometric).performClick()
        rule.waitForIdle()
        assertEquals("the button asks again", 2, sensor.prompts)
        assertEquals(0, unlockedByFinger)
    }

    @Test
    fun noSensorIsNoButtonAndNoPrompt() {
        val sensor = FakeSensor(ready = false)
        show(sensor)
        assertEquals(0, sensor.prompts)
        assertTrue(rule.onAllNodesWithTextCount(L.lockUseBiometric) == 0)
    }

    @Test
    fun noBiometricGivenIsTheOldPad() {
        show(null)
        assertEquals(0, unlockedByFinger)
        assertTrue(rule.onAllNodesWithTextCount(L.lockUseBiometric) == 0)
    }

    @Test
    fun aPadInsideAWaitIsNeverAskedAndShowsNoButton() = runBlocking {
        // Three wrong PINs earn the first wait.
        settings.setPin("1234")
        repeat(3) { settings.checkPin("0000", now = PinClock.now()) }
        assertTrue("the wait is earned", settings.pinAttemptsNow().waitMs(PinClock.now()) > 0)
        val sensor = FakeSensor()
        show(sensor)
        assertEquals("a pad in its wait takes no prompt", 0, sensor.prompts)
        assertEquals(0, unlockedByFinger)
        assertTrue(rule.onAllNodesWithTextCount(L.lockUseBiometric) == 0)
    }

    @Test
    fun theCheckerOverloadOfProfilePinsCannotTakeABiometric() {
        // Compile time and run time: no parameter of the public checker overload is a BiometricUnlock.
        val pads = Class.forName("com.tmplayer.ui.lock.PinPadKt").declaredMethods.filter { it.name == "PinPad" }
        val checkerOverload = pads.filter { m -> m.parameterTypes.any { it.name == "com.tmplayer.ui.lock.PinChecker" } }
        assertTrue(checkerOverload.isNotEmpty())
        assertFalse(
            checkerOverload.any { m -> m.parameterTypes.any { it == BiometricUnlock::class.java } },
        )
        // The app lock's own overload does take one.
        val settingsOverload = pads.filter { m -> m.parameterTypes.any { it == SettingsStore::class.java } }
        assertTrue(settingsOverload.any { m -> m.parameterTypes.any { it == BiometricUnlock::class.java } })
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String): Int =
        onAllNodesWithText(text).fetchSemanticsNodes().size
}
