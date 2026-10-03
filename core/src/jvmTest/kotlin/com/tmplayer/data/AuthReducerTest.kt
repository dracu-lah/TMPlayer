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
import org.junit.Assert.assertEquals
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
        assertEquals(AuthState.Password("my cat"), step.state)
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
        assertEquals(AuthState.Password("my cat"), step.state)
        assertEquals(AuthAction.None, step.action)
    }

    @Test
    fun `an undecided user who is already signed in is not asked to choose`() {
        val step = AuthReducer.reduce(AuthorizationStateReady(), SignInMethod.Undecided)
        assertEquals(AuthState.Ready, step.state)
        assertEquals(AuthAction.OnReady, step.action)
    }
}
