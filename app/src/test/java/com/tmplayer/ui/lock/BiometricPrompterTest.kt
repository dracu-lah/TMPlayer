package com.tmplayer.ui.lock

import androidx.biometric.BiometricManager
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBiometricManager

/**
 * WP-41: the Android prompter over the states a device can be in, and the paths that must never
 * open anything: no sensor, none enrolled, an activity that goes before the prompt can show.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class BiometricPrompterTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    /** The framework's own manager, which androidx asks on Android 11: a sensor that works, or none at all. */
    private fun sensor(works: Boolean) {
        val manager = context.getSystemService(android.hardware.biometrics.BiometricManager::class.java)
        Shadow.extract<ShadowBiometricManager>(manager).setCanAuthenticate(works)
    }

    @Test
    fun aWorkingSensorIsReady() {
        sensor(true)
        assertEquals(BiometricState.Ready, BiometricPrompter.state(context))
    }

    @Test
    fun noSensorIsUnavailable() {
        sensor(false)
        assertEquals(BiometricState.Unavailable, BiometricPrompter.state(context))
    }

    @Test
    fun theStatesOfTheRow() {
        assertEquals(BiometricState.Ready, BiometricPrompter.stateOf(BiometricManager.BIOMETRIC_SUCCESS))
        assertEquals(BiometricState.NoneEnrolled, BiometricPrompter.stateOf(BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED))
        val others = listOf(
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE,
            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED,
            BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED,
            BiometricManager.BIOMETRIC_STATUS_UNKNOWN,
        )
        for (code in others) assertEquals(code.toString(), BiometricState.Unavailable, BiometricPrompter.stateOf(code))
    }

    @Test
    fun aPromptWithoutASensorEndsAndNeverSucceeds() {
        sensor(false)
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        var success = 0
        var ended = 0
        BiometricPrompter(activity).prompt(onSuccess = { success++ }, onEnd = { ended++ })
        assertEquals(0, success)
        assertEquals(1, ended)
    }

    @Test
    fun anActivityDestroyedBeforeItResumedEndsThePromptWithoutSuccess() {
        sensor(true)
        val controller = Robolectric.buildActivity(FragmentActivity::class.java).create()
        var success = 0
        var ended = 0
        BiometricPrompter(controller.get()).prompt(onSuccess = { success++ }, onEnd = { ended++ })
        // Not resumed yet: nothing shown, nothing told.
        assertEquals(0, success + ended)
        controller.destroy()
        assertEquals(0, success)
        assertEquals(1, ended)
    }

    @Test
    fun cancelTakesAWaitingPromptDownWithoutAnAnswer() {
        sensor(true)
        val controller = Robolectric.buildActivity(FragmentActivity::class.java).create()
        var told = 0
        val prompter = BiometricPrompter(controller.get())
        prompter.prompt(onSuccess = { told++ }, onEnd = { told++ })
        prompter.cancel()
        controller.start().resume()
        assertEquals("a cancelled prompt never shows nor answers", 0, told)
    }

    /** The system's success (its constructor is the library's own). */
    private fun result(): androidx.biometric.BiometricPrompt.AuthenticationResult =
        androidx.biometric.BiometricPrompt.AuthenticationResult::class.java
            .getDeclaredConstructor(androidx.biometric.BiometricPrompt.CryptoObject::class.java, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .newInstance(null, androidx.biometric.BiometricPrompt.AUTHENTICATION_RESULT_TYPE_BIOMETRIC)

    @Test
    fun onlyAStrongBiometricIsAskedFor_SEC2_07() {
        assertEquals(BiometricManager.Authenticators.BIOMETRIC_STRONG, BiometricPrompter.AUTHENTICATORS)
        var asked: androidx.biometric.BiometricPrompt.PromptInfo? = null
        sensor(true)
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        BiometricPrompter(activity) { _, info ->
            asked = info
            val stop: () -> Unit = {}
            stop
        }.prompt(onSuccess = {}, onEnd = {})
        assertEquals(BiometricManager.Authenticators.BIOMETRIC_STRONG, asked?.allowedAuthenticators)
    }

    @Test
    fun aLateCancelOfAnEarlierPromptLeavesTheNextOneInPlace_SEC2_06() {
        sensor(true)
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val callbacks = mutableListOf<androidx.biometric.BiometricPrompt.AuthenticationCallback>()
        var stopped = 0
        val prompter = BiometricPrompter(activity) { callback, _ ->
            callbacks += callback
            val stop: () -> Unit = { stopped++ }
            stop
        }
        var firstTold = 0
        prompter.prompt(onSuccess = { firstTold++ }, onEnd = { firstTold++ })
        // A digit typed: the pad takes the prompt down; then "Use fingerprint" pressed at once.
        prompter.cancel()
        assertEquals(1, stopped)
        var secondSuccess = 0
        var secondEnded = 0
        prompter.prompt(onSuccess = { secondSuccess++ }, onEnd = { secondEnded++ })
        assertEquals(2, callbacks.size)
        // The system's late word to the first prompt: nothing answers, nothing is cleared.
        callbacks[0].onAuthenticationError(androidx.biometric.BiometricPrompt.ERROR_CANCELED, "cancelled")
        assertEquals(0, firstTold)
        assertEquals(0, secondSuccess + secondEnded)
        // The pad going away still takes the second prompt down.
        prompter.cancel()
        assertEquals(2, stopped)
        // And a success that comes to it after that opens nothing.
        callbacks[1].onAuthenticationSucceeded(result())
        assertEquals(0, secondSuccess)
    }

    @Test
    fun theOpenPromptStillAnswers() {
        sensor(true)
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val callbacks = mutableListOf<androidx.biometric.BiometricPrompt.AuthenticationCallback>()
        val prompter = BiometricPrompter(activity) { callback, _ ->
            callbacks += callback
            val stop: () -> Unit = {}
            stop
        }
        var success = 0
        prompter.prompt(onSuccess = { success++ }, onEnd = {})
        callbacks[0].onAuthenticationSucceeded(result())
        assertEquals(1, success)
        // Answered once only.
        callbacks[0].onAuthenticationSucceeded(result())
        assertEquals(1, success)
    }
}
