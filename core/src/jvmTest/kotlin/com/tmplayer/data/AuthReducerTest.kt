package com.tmplayer.data

import dev.g000sha256.tdl.dto.AuthenticationCodeInfo
import dev.g000sha256.tdl.dto.AuthenticationCodeTypeCall
import dev.g000sha256.tdl.dto.AuthenticationCodeTypeFlashCall
import dev.g000sha256.tdl.dto.AuthenticationCodeTypeFragment
import dev.g000sha256.tdl.dto.AuthenticationCodeTypeMissedCall
import dev.g000sha256.tdl.dto.AuthenticationCodeTypeSms
import dev.g000sha256.tdl.dto.AuthenticationCodeTypeSmsPhrase
import dev.g000sha256.tdl.dto.AuthenticationCodeTypeSmsWord
import dev.g000sha256.tdl.dto.AuthenticationCodeTypeTelegramMessage
import dev.g000sha256.tdl.dto.AuthorizationStateClosed
import dev.g000sha256.tdl.dto.AuthorizationStateClosing
import dev.g000sha256.tdl.dto.AuthorizationStateLoggingOut
import dev.g000sha256.tdl.dto.AuthorizationStateReady
import dev.g000sha256.tdl.dto.AuthorizationStateWaitCode
import dev.g000sha256.tdl.dto.AuthorizationStateWaitOtherDeviceConfirmation
import dev.g000sha256.tdl.dto.AuthorizationStateWaitPassword
import dev.g000sha256.tdl.dto.AuthorizationStateWaitPhoneNumber
import dev.g000sha256.tdl.dto.AuthorizationStateWaitTdlibParameters
import com.tmplayer.i18n.L
import dev.g000sha256.tdl.dto.AuthorizationStateWaitEmailAddress
import dev.g000sha256.tdl.dto.AuthorizationStateWaitEmailCode
import dev.g000sha256.tdl.dto.AuthorizationStateWaitPremiumPurchase
import dev.g000sha256.tdl.dto.AuthorizationStateWaitRegistration
import dev.g000sha256.tdl.dto.EmailAddressAuthenticationCodeInfo
import dev.g000sha256.tdl.dto.EmailAddressResetStateAvailable
import dev.g000sha256.tdl.dto.EmailAddressResetStatePending
import dev.g000sha256.tdl.dto.FormattedText
import dev.g000sha256.tdl.dto.TermsOfService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthReducerTest {

    @Test
    fun `parameters are sent first and the user sees nothing to do`() {
        val step = AuthReducer.reduce(AuthorizationStateWaitTdlibParameters())
        assertEquals(AuthAction.SendParameters, step.action)
        assertEquals(AuthState.Connecting, step.state)
    }

    @Test
    fun `a phone number prompt is answered with a QR request, never a phone screen`() {
        val step = AuthReducer.reduce(AuthorizationStateWaitPhoneNumber())
        assertEquals(AuthAction.RequestQrCode, step.action)
        assertTrue(step.state is AuthState.Connecting)
    }

    @Test
    fun `the login link is handed to the UI untouched`() {
        val link = "tg://login?token=AbCdEf"
        val step = AuthReducer.reduce(AuthorizationStateWaitOtherDeviceConfirmation(link))
        assertEquals(AuthState.Qr(link), step.state)
        assertEquals(AuthAction.None, step.action)
    }

    @Test
    fun `two-step verification surfaces the account's own hint`() {
        val step = AuthReducer.reduce(
            AuthorizationStateWaitPassword("my cat", true, false, "a**@b.com"),
        )
        assertEquals(AuthState.Password("my cat", recoveryEmail = "a**@b.com"), step.state)
        assertEquals(AuthAction.None, step.action)
    }

    @Test
    fun `becoming ready triggers the post-login work`() {
        val step = AuthReducer.reduce(AuthorizationStateReady())
        assertEquals(AuthState.Ready, step.state)
        assertEquals(AuthAction.OnReady, step.action)
    }

    @Test
    fun `signing out leaves the user on a loader, not an error`() {
        assertEquals(AuthState.Connecting, AuthReducer.reduce(AuthorizationStateLoggingOut()).state)
        assertEquals(AuthState.Connecting, AuthReducer.reduce(AuthorizationStateClosing()).state)
    }

    @Test
    fun `a closed client is rebuilt so the user can log in again`() {
        val step = AuthReducer.reduce(AuthorizationStateClosed())
        assertEquals(AuthAction.RecreateClient, step.action)
    }

    @Test
    fun `closing on purpose shows connecting and asks for the client to close`() {
        val step = AuthReducer.close()
        assertEquals(AuthState.Connecting, step.state)
        assertEquals(AuthAction.Close, step.action)
    }

    @Test
    fun `a client closed on purpose goes through closing to a rebuild, whatever the sign in route`() {
        // The restart over new paths relies on exactly this: Close, then TDLib's own Closing and
        // Closed, and the Closed is what starts the replacement.
        for (method in SignInMethod.values()) {
            assertEquals(AuthAction.None, AuthReducer.reduce(AuthorizationStateClosing(), method).action)
            val closed = AuthReducer.reduce(AuthorizationStateClosed(), method)
            assertEquals(AuthState.Connecting, closed.state)
            assertEquals(AuthAction.RecreateClient, closed.action)
        }
    }

    @Test
    fun `the replacement client is answered with its parameters, as at first start`() {
        assertEquals(AuthAction.SendParameters, AuthReducer.reduce(AuthorizationStateWaitTdlibParameters()).action)
        assertEquals(AuthAction.OnReady, AuthReducer.reduce(AuthorizationStateReady()).action)
    }

    @Test
    fun `an SMS code request fails loudly instead of hanging on a loader`() {
        val codeInfo = AuthenticationCodeInfo("+10000000000", AuthenticationCodeTypeSms(5), null, 60)
        val step = AuthReducer.reduce(AuthorizationStateWaitCode(codeInfo))
        assertTrue(step.state is AuthState.Failed)
        assertEquals(AuthAction.None, step.action)
    }

    @Test
    fun `until a method is picked the same prompt offers the choice and sends nothing`() {
        val step = AuthReducer.reduce(
            AuthorizationStateWaitPhoneNumber(),
            SignInMethod.Undecided,
        )
        assertEquals(AuthState.ChooseMethod, step.state)
        assertEquals(AuthAction.None, step.action)
    }

    @Test
    fun `picking the phone route asks for a number instead of a QR code`() {
        val step = AuthReducer.reduce(AuthorizationStateWaitPhoneNumber(), SignInMethod.Phone)
        assertEquals(AuthState.Phone(), step.state)
        assertEquals(AuthAction.None, step.action)
    }

    @Test
    fun `picking QR still requests a link`() {
        val step = AuthReducer.reduce(AuthorizationStateWaitPhoneNumber(), SignInMethod.Qr)
        assertEquals(AuthAction.RequestQrCode, step.action)
        assertEquals(AuthState.Connecting, step.state)
    }

    @Test
    fun `the code screen names the number Telegram texted`() {
        val codeInfo = AuthenticationCodeInfo("+447700900000", AuthenticationCodeTypeSms(5), null, 60)
        val step = AuthReducer.reduce(AuthorizationStateWaitCode(codeInfo), SignInMethod.Phone)
        assertEquals("+447700900000", (step.state as AuthState.Code).phoneNumber)
        assertEquals(AuthAction.None, step.action)
    }

    @Test
    fun `the code screen is told how the code was sent and how long it is`() {
        val codeInfo = AuthenticationCodeInfo(
            "+447700900000",
            AuthenticationCodeTypeTelegramMessage(6),
            AuthenticationCodeTypeSms(5),
            60,
        )
        val state = AuthReducer.reduce(
            AuthorizationStateWaitCode(codeInfo),
            SignInMethod.Phone,
        ).state as AuthState.Code
        assertEquals(CodeDelivery.TelegramApp, state.delivery)
        assertEquals(6, state.length)
        assertEquals(60, state.timeout)
        // Named, so the resend button can offer the route rather than just "again".
        assertEquals(CodeDelivery.Sms, state.next)
    }

    @Test
    fun `a next type that repeats the current one is not offered as an alternative`() {
        val codeInfo = AuthenticationCodeInfo(
            "+447700900000",
            AuthenticationCodeTypeSms(5),
            AuthenticationCodeTypeSms(5),
            30,
        )
        assertEquals(null, AuthReducer.codeState(codeInfo).next)
        // Still sent again, by the same route: the resend button stays.
        assertTrue(AuthReducer.codeState(codeInfo).canResend)
    }

    @Test
    fun `with no next route TDLib will not resend, so no resend is offered`() {
        val codeInfo = AuthenticationCodeInfo("+447700900000", AuthenticationCodeTypeSms(5), null, 0)
        assertFalse(AuthReducer.codeState(codeInfo).canResend)
    }

    @Test
    fun `a code with no length of its own reports zero rather than inventing one`() {
        val codeInfo = AuthenticationCodeInfo(
            "+447700900000",
            AuthenticationCodeTypeSmsWord("k"),
            null,
            0,
        )
        val state = AuthReducer.codeState(codeInfo)
        assertEquals(CodeDelivery.SmsWord, state.delivery)
        assertEquals(0, state.length)
    }

    @Test
    fun `every delivery route TDLib can name is one the screen can name back`() {
        assertEquals(CodeDelivery.Call, AuthReducer.delivery(AuthenticationCodeTypeCall(5)))
        assertEquals(
            CodeDelivery.MissedCall,
            AuthReducer.delivery(AuthenticationCodeTypeMissedCall("+44", 5)),
        )
        assertEquals(
            CodeDelivery.FlashCall,
            AuthReducer.delivery(AuthenticationCodeTypeFlashCall("pattern")),
        )
        assertEquals(
            CodeDelivery.Fragment,
            AuthReducer.delivery(AuthenticationCodeTypeFragment("https://fragment.com", 5)),
        )
        assertEquals(
            CodeDelivery.SmsPhrase,
            AuthReducer.delivery(AuthenticationCodeTypeSmsPhrase("word")),
        )
    }

    @Test
    fun `a code still awaited when the app starts again goes back to the code step`() {
        // iOS closed the app while the viewer read the code in Telegram: no method is known any
        // more, and TDLib still waits for that code. A dead end here sent them to start over.
        val codeInfo = AuthenticationCodeInfo("+10000000000", AuthenticationCodeTypeTelegramMessage(5), null, 60)
        val step = AuthReducer.reduce(AuthorizationStateWaitCode(codeInfo), SignInMethod.Undecided)
        val code = step.state as? AuthState.Code
        assertTrue("the code step, not ${step.state}", code != null)
        assertEquals(CodeDelivery.TelegramApp, code!!.delivery)
        assertEquals(5, code.length)
        assertEquals(AuthAction.None, step.action)
    }

    @Test
    fun `a code demanded of the QR route is still a dead end`() {
        val codeInfo = AuthenticationCodeInfo("+10000000000", AuthenticationCodeTypeSms(5), null, 60)
        val step = AuthReducer.reduce(AuthorizationStateWaitCode(codeInfo), SignInMethod.Qr)
        assertTrue(step.state is AuthState.Failed)
        assertEquals(AuthAction.None, step.action)
    }

    @Test
    fun `two-step verification follows the phone route as well`() {
        val step = AuthReducer.reduce(
            AuthorizationStateWaitPassword("my cat", true, false, "a**@b.com"),
            SignInMethod.Phone,
        )
        assertEquals(AuthState.Password("my cat", recoveryEmail = "a**@b.com"), step.state)
        assertEquals(AuthAction.None, step.action)
    }

    @Test
    fun `an undecided user who is already signed in is not asked to choose`() {
        val step = AuthReducer.reduce(AuthorizationStateReady(), SignInMethod.Undecided)
        assertEquals(AuthState.Ready, step.state)
        assertEquals(AuthAction.OnReady, step.action)
    }

    @Test
    fun `an account signed in by email gets its address and code screens, on any route`() {
        for (method in SignInMethod.entries) {
            assertEquals(AuthState.EmailAddress(), AuthReducer.reduce(AuthorizationStateWaitEmailAddress(false, false), method).state)
            val code = AuthReducer.reduce(
                AuthorizationStateWaitEmailCode(false, false, EmailAddressAuthenticationCodeInfo("a***@gmail.com", 6), EmailAddressResetStateAvailable(0)),
                method,
            )
            assertEquals(AuthState.EmailCode("a***@gmail.com", 6, reset = EmailReset.Available(0)), code.state)
            assertEquals(AuthAction.None, code.action)
        }
    }

    @Test
    fun `a Premium purchase to sign in is a failure that says what to do, never Connecting for good`() {
        val step = AuthReducer.reduce(AuthorizationStateWaitPremiumPurchase("id", 30, "help@x", "s"), SignInMethod.Phone)
        assertEquals(AuthState.Failed(L.errorsPremiumQr), step.state)
    }

    @Test
    fun `a number with no account says so`() {
        val step = AuthReducer.reduce(AuthorizationStateWaitRegistration(TermsOfService(FormattedText("", emptyArray()), 0, false)), SignInMethod.Phone)
        assertEquals(AuthState.Failed(L.errorsNoAccount), step.state)
    }

    @Test
    fun `no TDLib state leaves the screen on Connecting without anything under way`() {
        // Connecting is only ever shown with an action that moves things on, or while TDLib is
        // closing, which it finishes on its own.
        val states = listOf(
            AuthorizationStateWaitTdlibParameters(), AuthorizationStateWaitPhoneNumber(),
            AuthorizationStateWaitEmailAddress(false, false),
            AuthorizationStateWaitPremiumPurchase("id", 30, "help@x", "s"),
            AuthorizationStateWaitRegistration(TermsOfService(FormattedText("", emptyArray()), 0, false)),
            AuthorizationStateReady(), AuthorizationStateClosed(),
        )
        for (state in states) for (method in SignInMethod.entries) {
            val step = AuthReducer.reduce(state, method)
            if (step.state == AuthState.Connecting) {
                assertTrue("${state::class.simpleName} with $method", step.action != AuthAction.None)
            }
        }
    }

    @Test
    fun `a password without a recovery email offers no recovery address`() {
        val step = AuthReducer.reduce(AuthorizationStateWaitPassword("", false, false, ""), SignInMethod.Qr)
        assertEquals(AuthState.Password(""), step.state)
    }

    @Test
    fun `an email reset under way carries the time left as TDLib reports it`() {
        val pending = AuthReducer.reduce(
            AuthorizationStateWaitEmailCode(false, false, EmailAddressAuthenticationCodeInfo("a***@b.c", 6), EmailAddressResetStatePending(3600)),
            SignInMethod.Phone,
        )
        assertEquals(EmailReset.Pending(3600), (pending.state as AuthState.EmailCode).reset)
        val none = AuthReducer.reduce(
            AuthorizationStateWaitEmailCode(false, false, EmailAddressAuthenticationCodeInfo("a***@b.c", 6), null),
            SignInMethod.Phone,
        )
        assertEquals(EmailReset.None, (none.state as AuthState.EmailCode).reset)
    }
}
