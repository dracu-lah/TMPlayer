package com.tmplayer.data

import dev.g000sha256.tdl.dto.AuthenticationCodeInfo
import dev.g000sha256.tdl.dto.AuthenticationCodeTypeSms
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
}
