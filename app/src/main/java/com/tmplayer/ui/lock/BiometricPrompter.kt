package com.tmplayer.ui.lock

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.tmplayer.i18n.L

/**
 * The system's fingerprint or face prompt for the app lock's pad (WP-41), over androidx.biometric.
 * Android only: iOS has a no-op of the same name (`ios/src/iosMain`), LocalAuthentication later.
 *
 * What it guards against (see [BiometricUnlock]): it reports a success only when the system does.
 * It asks for a Class 2 or 3 biometric ([BiometricManager.Authenticators.BIOMETRIC_WEAK], which
 * includes the strong ones) and never for the device's PIN, pattern or password: its one negative
 * button is "Use the PIN", which is the pad underneath. No cryptographic key is held (enrolment
 * changes cost nothing), the result only lets the pad through, as typing the right PIN does.
 *
 * It shows only while the activity is resumed: asked earlier it waits for that, and a pause takes it
 * down, so no prompt hangs over a locked phone or another app. The prompt dies with a cancel.
 */
class BiometricPrompter(private val activity: FragmentActivity) : BiometricUnlock {

    private var prompt: BiometricPrompt? = null
    private var waiter: DefaultLifecycleObserver? = null

    /** True while a prompt of ours is up or waiting to come up. */
    private var live = false

    override fun available(): Boolean = canAuthenticate(activity) == BiometricManager.BIOMETRIC_SUCCESS

    override fun prompt(onSuccess: () -> Unit, onEnd: () -> Unit) {
        if (live) return
        live = true
        var told = false
        val once: (Boolean) -> Unit = { ok ->
            if (!told) {
                told = true
                live = false
                prompt = null
                if (ok) onSuccess() else onEnd()
            }
        }
        val show = {
            // A sensor that went away since the pad opened is "no": the PIN is the way in.
            if (!available()) {
                once(false)
            } else {
                val callback = object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = once(true)

                    // Every error is an end: a cancel, "Use the PIN", too many tries (lockout), no
                    // hardware, a timeout. None of them opens anything.
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = once(false)

                    // A finger or face that did not match is not an end: the system lets it try again.
                    override fun onAuthenticationFailed() = Unit
                }
                val info = BiometricPrompt.PromptInfo.Builder()
                    .setTitle(L.lockBiometricTitle)
                    .setSubtitle(L.lockBiometricDetail)
                    .setNegativeButtonText(L.lockEnterPin)
                    .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
                    // The pad is the confirmation: no extra tap after a face is recognised.
                    .setConfirmationRequired(false)
                    .build()
                runCatching {
                    val made = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback)
                    prompt = made
                    made.authenticate(info)
                }.onFailure { once(false) }
            }
        }
        val lifecycle = activity.lifecycle
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            show()
        } else {
            // Not before the activity can show it; if it is destroyed first, the prompt never comes.
            val observer = object : DefaultLifecycleObserver {
                override fun onResume(owner: LifecycleOwner) {
                    lifecycle.removeObserver(this)
                    waiter = null
                    if (live) show()
                }

                override fun onDestroy(owner: LifecycleOwner) {
                    lifecycle.removeObserver(this)
                    waiter = null
                    once(false)
                }
            }
            waiter = observer
            lifecycle.addObserver(observer)
        }
    }

    override fun cancel() {
        waiter?.let { activity.lifecycle.removeObserver(it) }
        waiter = null
        live = false
        prompt?.let { runCatching { it.cancelAuthentication() } }
        prompt = null
    }

    companion object {
        /** [BiometricManager.canAuthenticate] for a Class 2 or 3 biometric. */
        fun canAuthenticate(context: Context): Int =
            runCatching { BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) }
                .getOrDefault(BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE)

        /** What the Settings row shows: ready, a sensor with nothing enrolled (the row explains), or nothing (hidden). */
        fun state(context: Context): BiometricState = stateOf(canAuthenticate(context))

        /** The state for one of [BiometricManager]'s answers: only success is ready, and only "none enrolled" is worth a row. */
        fun stateOf(code: Int): BiometricState = when (code) {
            BiometricManager.BIOMETRIC_SUCCESS -> BiometricState.Ready
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricState.NoneEnrolled
            else -> BiometricState.Unavailable
        }
    }
}
