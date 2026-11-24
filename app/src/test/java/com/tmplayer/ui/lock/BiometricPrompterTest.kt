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
}
