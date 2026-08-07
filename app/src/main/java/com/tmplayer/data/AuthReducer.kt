package com.tmplayer.data

import dev.g000sha256.tdl.dto.AuthorizationState
import dev.g000sha256.tdl.dto.AuthorizationStateClosed
import dev.g000sha256.tdl.dto.AuthorizationStateClosing
import dev.g000sha256.tdl.dto.AuthorizationStateLoggingOut
import dev.g000sha256.tdl.dto.AuthorizationStateReady
import dev.g000sha256.tdl.dto.AuthorizationStateWaitCode
import dev.g000sha256.tdl.dto.AuthorizationStateWaitEmailAddress
import dev.g000sha256.tdl.dto.AuthorizationStateWaitEmailCode
import dev.g000sha256.tdl.dto.AuthorizationStateWaitOtherDeviceConfirmation
import dev.g000sha256.tdl.dto.AuthorizationStateWaitPassword
import dev.g000sha256.tdl.dto.AuthorizationStateWaitPhoneNumber
import dev.g000sha256.tdl.dto.AuthorizationStateWaitRegistration
import dev.g000sha256.tdl.dto.AuthorizationStateWaitTdlibParameters

/** What the UI is showing during login. */
sealed interface AuthState {
    /** Talking to Telegram, nothing for the user to do yet. */
    data object Connecting : AuthState

    /** Scan [link] with Telegram on your phone: Settings -> Devices -> Link Desktop Device. */
    data class Qr(val link: String) : AuthState

    /** Two-step verification is on; [hint] is the user's own password hint (may be blank). */
    data class Password(val hint: String, val wrong: Boolean = false) : AuthState

    data object Ready : AuthState

    data class Failed(val message: String) : AuthState
}

/** The side effect [AuthReducer] wants the client to perform. */
sealed interface AuthAction {
    data object SendParameters : AuthAction
    data object RequestQrCode : AuthAction
    data object RecreateClient : AuthAction
    data object OnReady : AuthAction
    data object None : AuthAction
}

data class AuthStep(val state: AuthState, val action: AuthAction)

/**
 * Pure translation of TDLib's authorization state machine into what we show and what we do next.
 *
 * Kept free of Android and TDLib client references so the whole login flow can be exercised
 * by plain JVM unit tests. This is the part that is easy to get subtly wrong.
 */
object AuthReducer {

    fun reduce(state: AuthorizationState): AuthStep = when (state) {
        // TDLib always asks for its parameters first; nothing works until we answer.
        is AuthorizationStateWaitTdlibParameters ->
            AuthStep(AuthState.Connecting, AuthAction.SendParameters)

        // We never ask for a phone number: answer this by requesting a QR login link instead.
        is AuthorizationStateWaitPhoneNumber ->
            AuthStep(AuthState.Connecting, AuthAction.RequestQrCode)

        is AuthorizationStateWaitOtherDeviceConfirmation ->
            AuthStep(AuthState.Qr(state.link), AuthAction.None)

        // Telegram mandates this screen when the account has two-step verification enabled.
        is AuthorizationStateWaitPassword ->
            AuthStep(AuthState.Password(state.passwordHint), AuthAction.None)

        is AuthorizationStateReady ->
            AuthStep(AuthState.Ready, AuthAction.OnReady)

        is AuthorizationStateLoggingOut, is AuthorizationStateClosing ->
            AuthStep(AuthState.Connecting, AuthAction.None)

        // The client is dead after a logout, so a fresh one is needed to log in again.
        is AuthorizationStateClosed ->
            AuthStep(AuthState.Connecting, AuthAction.RecreateClient)

        // Reachable only if the QR link is confirmed on an account that then demands an SMS
        // code, an e-mail, or a sign-up. None of those can be completed from a TV remote.
        is AuthorizationStateWaitCode,
        is AuthorizationStateWaitEmailAddress,
        is AuthorizationStateWaitEmailCode,
        is AuthorizationStateWaitRegistration ->
            AuthStep(
                AuthState.Failed("This account needs a step TMPlayer can't do on a TV. Finish signing in on your phone first, then try again."),
                AuthAction.None,
            )

        else -> AuthStep(AuthState.Connecting, AuthAction.None)
    }
}
