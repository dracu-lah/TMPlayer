package com.tmplayer.data

import dev.g000sha256.tdl.dto.AuthenticationCodeInfo
import dev.g000sha256.tdl.dto.AuthenticationCodeType
import dev.g000sha256.tdl.dto.AuthenticationCodeTypeCall
import dev.g000sha256.tdl.dto.AuthenticationCodeTypeFirebaseAndroid
import dev.g000sha256.tdl.dto.AuthenticationCodeTypeFirebaseIos
import dev.g000sha256.tdl.dto.AuthenticationCodeTypeFlashCall
import dev.g000sha256.tdl.dto.AuthenticationCodeTypeFragment
import dev.g000sha256.tdl.dto.AuthenticationCodeTypeMissedCall
import dev.g000sha256.tdl.dto.AuthenticationCodeTypeSms
import dev.g000sha256.tdl.dto.AuthenticationCodeTypeSmsPhrase
import dev.g000sha256.tdl.dto.AuthenticationCodeTypeSmsWord
import dev.g000sha256.tdl.dto.AuthenticationCodeTypeTelegramMessage
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

/**
 * How the user has said they want to sign in.
 *
 * TDLib asks one question, [AuthorizationStateWaitPhoneNumber], for both routes: a QR link and a
 * real phone number are two different answers to it. Nothing but the user can tell them apart, so
 * the choice is carried in here rather than inferred from the TDLib state.
 */
enum class SignInMethod {
    /** Nothing picked yet, so the login screen offers the choice. */
    Undecided,
    Qr,
    Phone,
}

/**
 * The route a login code took to reach the user.
 *
 * TDLib has a class per delivery method, each carrying its own extras; the screen only ever needs
 * to name the route and count the digits, so the whole family collapses to this and a length. It
 * also keeps the reducer's output comparable in a test, which a TDLib DTO hierarchy is not.
 */
enum class CodeDelivery {
    /** Sent to Telegram itself on a device already signed in. The usual case. */
    TelegramApp,
    Sms,
    /** Read out by an automated call. */
    Call,
    /** The call is not answered: the last digits of the calling number are the code. */
    MissedCall,
    /** The call is not answered either, and the number it came from is the code. */
    FlashCall,
    /** Bought through Fragment, and collected there rather than on the phone. */
    Fragment,
    /** A text holding a word, not digits. */
    SmsWord,
    /** A text holding a phrase, not digits. */
    SmsPhrase,
    /** Handled by Play Services before a text is sent at all. */
    Firebase,
    Unknown,
}

/** What the UI is showing during login. */
sealed interface AuthState {
    /** Talking to Telegram, nothing for the user to do yet. */
    data object Connecting : AuthState

    /** Telegram wants an answer and the user has not said which way they want to give it. */
    data object ChooseMethod : AuthState

    /** Scan [link] with Telegram on your phone: Settings -> Devices -> Link Desktop Device. */
    data class Qr(val link: String) : AuthState

    /** Waiting for a phone number, in international form. */
    data class Phone(val wrong: Boolean = false) : AuthState

    /**
     * Waiting for the login code Telegram just sent to [phoneNumber].
     *
     * [delivery] is how it was sent and [length] how many digits to expect, both of which the code
     * screen needs before it can draw anything: a row of boxes has to know how many boxes, and
     * telling somebody to check their texts when the code went to the Telegram app is the fastest
     * way to have them sitting there waiting for a message that will never arrive.
     */
    data class Code(
        val phoneNumber: String,
        val wrong: Boolean = false,
        val delivery: CodeDelivery = CodeDelivery.Unknown,
        /** Zero when TDLib does not say, which the screen reads as "the usual five". */
        val length: Int = 0,
        /** Seconds before another code may be asked for. */
        val timeout: Int = 0,
        /** What asking again would send, when that differs from what was sent. */
        val next: CodeDelivery? = null,
    ) : AuthState

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

    fun reduce(
        state: AuthorizationState,
        method: SignInMethod = SignInMethod.Qr,
    ): AuthStep = when (state) {
        // TDLib always asks for its parameters first; nothing works until we answer.
        is AuthorizationStateWaitTdlibParameters ->
            AuthStep(AuthState.Connecting, AuthAction.SendParameters)

        // The one question with two answers: a QR link, or the number typed on this device.
        is AuthorizationStateWaitPhoneNumber -> when (method) {
            SignInMethod.Undecided -> AuthStep(AuthState.ChooseMethod, AuthAction.None)
            SignInMethod.Qr -> AuthStep(AuthState.Connecting, AuthAction.RequestQrCode)
            SignInMethod.Phone -> AuthStep(AuthState.Phone(), AuthAction.None)
        }

        // Only the phone route asks for a code, and only it can answer one: nothing typed on a TV
        // remote reaches the phone that a QR-linked account would be sending the code to.
        is AuthorizationStateWaitCode -> when (method) {
            SignInMethod.Phone -> AuthStep(codeState(state.codeInfo), AuthAction.None)
            else -> AuthStep(AuthState.Failed(UNSUPPORTED_STEP), AuthAction.None)
        }

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

        // Reachable when the account demands an e-mail address or a sign-up. Neither can be
        // completed from here, whichever way the sign-in was started.
        is AuthorizationStateWaitEmailAddress,
        is AuthorizationStateWaitEmailCode,
        is AuthorizationStateWaitRegistration ->
            AuthStep(AuthState.Failed(UNSUPPORTED_STEP), AuthAction.None)

        else -> AuthStep(AuthState.Connecting, AuthAction.None)
    }

    /** Everything the code screen needs, lifted out of TDLib's DTO in one place. */
    fun codeState(info: AuthenticationCodeInfo, wrong: Boolean = false): AuthState.Code =
        AuthState.Code(
            phoneNumber = info.phoneNumber,
            wrong = wrong,
            delivery = delivery(info.type),
            length = length(info.type),
            timeout = info.timeout,
            // A next type equal to the current one says nothing: the button would offer to send
            // the same thing by the same route, which is what plain "Resend" already means.
            next = info.nextType?.let(::delivery)?.takeIf { it != delivery(info.type) },
        )

    fun delivery(type: AuthenticationCodeType): CodeDelivery = when (type) {
        is AuthenticationCodeTypeTelegramMessage -> CodeDelivery.TelegramApp
        is AuthenticationCodeTypeSms -> CodeDelivery.Sms
        is AuthenticationCodeTypeCall -> CodeDelivery.Call
        is AuthenticationCodeTypeMissedCall -> CodeDelivery.MissedCall
        is AuthenticationCodeTypeFlashCall -> CodeDelivery.FlashCall
        is AuthenticationCodeTypeFragment -> CodeDelivery.Fragment
        is AuthenticationCodeTypeSmsWord -> CodeDelivery.SmsWord
        is AuthenticationCodeTypeSmsPhrase -> CodeDelivery.SmsPhrase
        is AuthenticationCodeTypeFirebaseAndroid, is AuthenticationCodeTypeFirebaseIos ->
            CodeDelivery.Firebase
        else -> CodeDelivery.Unknown
    }

    /** Zero where the type has no length of its own, which the screen turns into its default. */
    private fun length(type: AuthenticationCodeType): Int = when (type) {
        is AuthenticationCodeTypeTelegramMessage -> type.length
        is AuthenticationCodeTypeSms -> type.length
        is AuthenticationCodeTypeCall -> type.length
        is AuthenticationCodeTypeMissedCall -> type.length
        is AuthenticationCodeTypeFragment -> type.length
        is AuthenticationCodeTypeFirebaseAndroid -> type.length
        is AuthenticationCodeTypeFirebaseIos -> type.length
        else -> 0
    }

    private const val UNSUPPORTED_STEP =
        "This account needs a step TMPlayer can't do on a TV. Finish signing in on your phone first, then try again."
}
