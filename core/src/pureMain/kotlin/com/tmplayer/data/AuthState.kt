package com.tmplayer.data

import com.tmplayer.i18n.L
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map

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

    /**
     * Scan [link] with Telegram on your phone: Settings -> Devices -> Link Desktop Device.
     * [scanned] only on the screen's own copy held for a moment after the scan ([rememberScanHold]
     * in :ui); TDLib never reports one.
     */
    data class Qr(val link: String, val scanned: Boolean = false) : AuthState

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
        /**
         * Whether TDLib will send the code again at all: only when it has a next route, the same
         * route included. With none, `resendAuthenticationCode` is refused, so no button is shown.
         */
        val canResend: Boolean = false,
    ) : AuthState

    /**
     * Telegram wants an email address for this account before it sends a code there. Asked of
     * accounts Telegram will not send a text to any more.
     */
    data class EmailAddress(val wrong: Boolean = false) : AuthState

    /**
     * Waiting for the code Telegram mailed to the account's login email, which it uses in place of
     * a text for some accounts. [pattern] is the address with most of it starred out, as Telegram
     * gives it; [length] is the number of digits, zero when not said.
     */
    data class EmailCode(
        val pattern: String,
        val length: Int = 0,
        val wrong: Boolean = false,
        /** Whether the address can be reset, and when, as TDLib reports it. */
        val reset: EmailReset = EmailReset.None,
    ) : AuthState

    /**
     * Two-step verification is on; [hint] is the user's own password hint (may be blank).
     * [recoveryEmail] is the starred out recovery address when the account has one, which is
     * what "Forgot password" mails a code to; null when it has none.
     */
    data class Password(
        val hint: String,
        val wrong: Boolean = false,
        val recoveryEmail: String? = null,
    ) : AuthState

    /**
     * "Forgot password": Telegram mailed a recovery code to [pattern]. TDLib stays on its
     * password question meanwhile, so this is the client's own step, set by the client rather
     * than by [AuthFlow]. Once the code is accepted ([code] set) the viewer may choose a new
     * password, or none, which turns two-step verification off.
     */
    data class PasswordRecovery(
        val pattern: String,
        val code: String? = null,
        val wrong: Boolean = false,
    ) : AuthState

    data object Ready : AuthState

    /**
     * [waitSeconds] when Telegram said how long to wait before trying again (a flood wait), so the
     * screen can count it down rather than offer a Try again that will fail the same way.
     */
    data class Failed(val message: String, val waitSeconds: Int = 0) : AuthState
}

/**
 * Whether the login email of an account Telegram signs in by email can be reset, which is the
 * way out for somebody who no longer has that mailbox. TDLib reports it on the email code step.
 */
sealed interface EmailReset {
    /** No reset on offer. */
    data object None : EmailReset

    /** A reset may be asked for now; Telegram then waits [waitSeconds] before carrying it out. */
    data class Available(val waitSeconds: Int) : EmailReset

    /** A reset is under way and completes in [resetInSeconds]; a code then comes by text. */
    data class Pending(val resetInSeconds: Int) : EmailReset
}

/** The side effect [AuthReducer] wants the client to perform. */
sealed interface AuthAction {
    data object SendParameters : AuthAction
    data object RequestQrCode : AuthAction
    data object RecreateClient : AuthAction

    /**
     * Close this client on purpose so a new one starts, as when TDLib's files directory moves.
     *
     * TDLib reads its directories once, in its parameters, and has no call to change them. The
     * session is kept: it lives in the database directory, which does not move, so the new client
     * goes through [AuthState.Connecting] back to [AuthState.Ready] without a sign in.
     */
    data object Close : AuthAction
    data object OnReady : AuthAction
    data object None : AuthAction
}

data class AuthStep(val state: AuthState, val action: AuthAction)

/**
 * TDLib's authorization state with nothing of TDLib's in it, so the sign in flow is the same code
 * whatever client TDLib arrives through.
 * Each platform's client maps its own form onto this; [AuthFlow] does the rest.
 */
sealed interface TdAuthorization {
    data object WaitParameters : TdAuthorization
    data object WaitPhoneNumber : TdAuthorization
    data class WaitCode(val info: CodeInfo) : TdAuthorization
    data class WaitOtherDevice(val link: String) : TdAuthorization
    data class WaitPassword(val hint: String, val recoveryEmail: String? = null) : TdAuthorization
    data object WaitEmailAddress : TdAuthorization
    data class WaitEmailCode(val pattern: String, val length: Int, val reset: EmailReset = EmailReset.None) : TdAuthorization
    data object WaitRegistration : TdAuthorization
    data object WaitPremiumPurchase : TdAuthorization
    data object Ready : TdAuthorization
    data object LoggingOut : TdAuthorization
    data object Closing : TdAuthorization
    data object Closed : TdAuthorization

    /** A state newer than this app. */
    data object Unknown : TdAuthorization
}

/** What TDLib says about a login code, already reduced to what the code screen needs. */
data class CodeInfo(
    val phoneNumber: String,
    val delivery: CodeDelivery,
    /** Zero where the delivery has no length of its own. */
    val length: Int,
    val timeout: Int,
    val next: CodeDelivery?,
    /** Whether TDLib has any next route at all, the same one included. */
    val hasNext: Boolean = next != null,
)

/**
 * Pure translation of TDLib's authorization state machine into what we show and what we do next.
 *
 * Kept free of any TDLib client so the whole login flow can be exercised by plain unit tests, on
 * the JVM and in the browser. This is the part that is easy to get subtly wrong.
 */
object AuthFlow {

    fun reduce(state: TdAuthorization, method: SignInMethod = SignInMethod.Qr): AuthStep = when (state) {
        // TDLib always asks for its parameters first; nothing works until we answer.
        TdAuthorization.WaitParameters -> AuthStep(AuthState.Connecting, AuthAction.SendParameters)

        // The one question with two answers: a QR link, or the number typed on this device.
        TdAuthorization.WaitPhoneNumber -> when (method) {
            SignInMethod.Undecided -> AuthStep(AuthState.ChooseMethod, AuthAction.None)
            SignInMethod.Qr -> AuthStep(AuthState.Connecting, AuthAction.RequestQrCode)
            SignInMethod.Phone -> AuthStep(AuthState.Phone(), AuthAction.None)
        }

        // Only the phone route asks for a code, and only it can answer one: nothing typed on a TV
        // remote reaches the phone that a QR-linked account would be sending the code to.
        is TdAuthorization.WaitCode -> when (method) {
            SignInMethod.Phone -> AuthStep(codeState(state.info), AuthAction.None)
            else -> AuthStep(AuthState.Failed(UNSUPPORTED_STEP), AuthAction.None)
        }

        is TdAuthorization.WaitOtherDevice -> AuthStep(AuthState.Qr(state.link), AuthAction.None)

        // Telegram mandates this screen when the account has two-step verification enabled.
        is TdAuthorization.WaitPassword ->
            AuthStep(AuthState.Password(hint = state.hint, recoveryEmail = state.recoveryEmail), AuthAction.None)

        TdAuthorization.Ready -> AuthStep(AuthState.Ready, AuthAction.OnReady)

        TdAuthorization.LoggingOut, TdAuthorization.Closing -> AuthStep(AuthState.Connecting, AuthAction.None)

        // The client is dead after a logout, so a fresh one is needed to log in again.
        TdAuthorization.Closed -> AuthStep(AuthState.Connecting, AuthAction.RecreateClient)

        // An account Telegram signs in by email: an address first if it has none, then the code.
        TdAuthorization.WaitEmailAddress -> AuthStep(AuthState.EmailAddress(), AuthAction.None)

        is TdAuthorization.WaitEmailCode ->
            AuthStep(AuthState.EmailCode(pattern = state.pattern, length = state.length, reset = state.reset), AuthAction.None)

        // A number with no account behind it. Signing up is the Telegram app's job.
        TdAuthorization.WaitRegistration -> AuthStep(AuthState.Failed(L.errorsNoAccount), AuthAction.None)

        // Telegram wants Premium bought in a store before it sends this number a code, which only
        // its own apps can do. This used to fall through to Connecting, with no action, so the
        // screen said "Connecting to Telegram" for good on a perfectly good connection. A QR code
        // from the Telegram app needs no code at all.
        TdAuthorization.WaitPremiumPurchase -> AuthStep(AuthState.Failed(L.errorsPremiumSignIn), AuthAction.None)

        // A state newer than this app. Waiting on it would be Connecting for ever; saying so at
        // least gives the viewer a Try again and a way to report it.
        TdAuthorization.Unknown -> AuthStep(AuthState.Failed(UNSUPPORTED_STEP), AuthAction.None)
    }

    /**
     * The app, not TDLib, asking for the client to be replaced: show Connecting and close it.
     *
     * TDLib then passes through Closing to Closed, which [reduce] already answers with
     * [AuthAction.RecreateClient]; that is what starts the replacement.
     */
    fun close(): AuthStep = AuthStep(AuthState.Connecting, AuthAction.Close)

    /** Everything the code screen needs, from TDLib's code info. */
    fun codeState(info: CodeInfo, wrong: Boolean = false): AuthState.Code =
        AuthState.Code(
            phoneNumber = info.phoneNumber,
            wrong = wrong,
            delivery = info.delivery,
            length = info.length,
            timeout = info.timeout,
            // A next type equal to the current one says nothing: the button would offer to send
            // the same thing by the same route, which is what plain "Resend" already means.
            next = info.next?.takeIf { it != info.delivery },
            canResend = info.hasNext,
        )

    private val UNSUPPORTED_STEP: String get() = L.errorsUnsupportedSignInStep
}

/**
 * Notices a sign in that has been [AuthState.Connecting] for too long.
 *
 * TDLib does not fail a request it cannot send: with no route to Telegram (offline, a firewall, a
 * VPN that drops the traffic) it waits, and the spinner on the sign in screen waits with it. After
 * [timeoutMs] in that one state, [stalled] turns true so the screen can say what is likely wrong
 * and offer a Retry. Any other state, or a new emission of [auth] (a retry counts as one), clears
 * it and starts the count again.
 */
object AuthStall {

    suspend fun watch(auth: Flow<AuthState>, stalled: MutableStateFlow<Boolean>, timeoutMs: Long) =
        watchWaiting(auth.map { it is AuthState.Connecting }, stalled, timeoutMs)

    /**
     * The same count over anything that says whether the sign in is held up
     * ([ConnectAdvice.waiting]). Every emission starts it again, so a caller that wants a retry to
     * count as a fresh start emits once more, and one that does not filters repeats out first.
     */
    suspend fun watchWaiting(waiting: Flow<Boolean>, stalled: MutableStateFlow<Boolean>, timeoutMs: Long) {
        waiting.collectLatest { held ->
            stalled.value = false
            if (held) {
                delay(timeoutMs)
                stalled.value = true
            }
        }
    }
}
