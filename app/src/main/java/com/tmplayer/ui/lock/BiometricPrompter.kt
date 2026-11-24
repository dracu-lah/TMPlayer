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
 * It asks for a Class 3 biometric only ([AUTHENTICATORS], SEC2-07): Class 2 is on many phones a 2D
 * camera face unlock, which Android lets accept a spoof up to one time in five, and on a shared family
 * phone a photo of the parent would open the app lock. A phone with only that sees no row; the PIN
 * stays. Never the device's PIN, pattern or password either: its one negative
 * button is "Use the PIN", which is the pad underneath. No cryptographic key is held (enrolment
 * changes cost nothing), the result only lets the pad through, as typing the right PIN does.
 *
 * It shows only while the activity is resumed: asked earlier it waits for that, and a pause takes it
 * down, so no prompt hangs over a locked phone or another app. The prompt dies with a cancel.
 *
 * Each prompt has a token of its own (SEC2-06): the system tells a cancelled prompt's callback
 * later (ERROR_CANCELED), and that late word must not clear the next prompt, which the pad could then
 * no longer take down. Only the prompt now open is answered.
 *
 * [system] shows the system's prompt and returns how to take it down; tests stand in for it.
 */
class BiometricPrompter internal constructor(
    private val activity: FragmentActivity,
    private val system: (BiometricPrompt.AuthenticationCallback, BiometricPrompt.PromptInfo) -> () -> Unit,
) : BiometricUnlock {

    constructor(activity: FragmentActivity) : this(activity, { callback, info ->
        val made = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback)
        made.authenticate(info)
        val stop: () -> Unit = { made.cancelAuthentication() }
        stop
    })

    /** Takes down the system's prompt now up, if any. */
    private var prompt: (() -> Unit)? = null
    private var waiter: DefaultLifecycleObserver? = null

    /** The prompt now open or waiting; null when none. A callback of another one is not listened to. */
    private var current: Any? = null

    /** True while a prompt of ours is up or waiting to come up. */
    private val live: Boolean get() = current != null

    override fun available(): Boolean = canAuthenticate(activity) == BiometricManager.BIOMETRIC_SUCCESS

    override fun prompt(onSuccess: () -> Unit, onEnd: () -> Unit) {
        if (live) return
        val token = Any()
        current = token
        var told = false
        val once: (Boolean) -> Unit = { ok ->
            // A prompt cancelled since (its late ERROR_CANCELED, or a success after it was taken down)
            // touches nothing: not the next prompt's state, and no answer to a pad that moved on.
            if (!told && current === token) {
                told = true
                current = null
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
                    .setAllowedAuthenticators(AUTHENTICATORS)
                    // The pad is the confirmation: no extra tap after a face is recognised.
                    .setConfirmationRequired(false)
                    .build()
                runCatching {
                    val stop = system(callback, info)
                    // Unless it already answered (a sensor that says no at once).
                    if (current === token) prompt = stop
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
                    if (current === token) show()
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
        current = null
        waiter?.let { activity.lifecycle.removeObserver(it) }
        waiter = null
        prompt?.let { runCatching { it() } }
        prompt = null
    }

    companion object {
        /** What the prompt and the row ask for: a Class 3 (strong) biometric, never a Class 2 one (SEC2-07). */
        const val AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_STRONG

        /** [BiometricManager.canAuthenticate] for a Class 3 biometric. */
        fun canAuthenticate(context: Context): Int =
            runCatching { BiometricManager.from(context).canAuthenticate(AUTHENTICATORS) }
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
