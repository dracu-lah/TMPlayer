package com.tmplayer.ui.lock

/**
 * iOS has no fingerprint or face unlock on the app lock yet (WP-41; LocalAuthentication later, see
 * docs/IOS.md). The same name as Android's prompter, which the shared screens construct: it is never
 * available, so nothing is offered and nothing can open the pad but the PIN.
 */
class BiometricPrompter(@Suppress("UNUSED_PARAMETER") activity: Any?) : BiometricUnlock {

    override fun available(): Boolean = false

    override fun prompt(onSuccess: () -> Unit, onEnd: () -> Unit) = onEnd()

    override fun cancel() = Unit

    companion object {
        @Suppress("UNUSED_PARAMETER")
        fun state(context: Any?): BiometricState = BiometricState.Unavailable
    }
}
