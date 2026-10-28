package com.tmplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A session that stopped answering (HX23): what leads to the sign in, what to a new client, and
 * what to the proxy suggestion. The Mi TV case: a session Telegram ended, Wi-Fi validated,
 * Telegram's data centres reachable, and the banner said "Telegram may be blocked".
 */
class SessionCheckTest {

    private val wifi = NetworkFacts(known = true, kind = NetworkKind.WiFi, id = "100")
    private val dcAnswers = LineEvidence(internet = true, telegram = true)
    private val dcSilent = LineEvidence(internet = true, telegram = false)

    @Test
    fun `refusals that mean signed out are told from everything else`() {
        assertTrue(SessionCheck.isAuthError(401, "Unauthorized"))
        assertTrue(SessionCheck.isAuthError(401, "AUTH_KEY_UNREGISTERED"))
        assertTrue(SessionCheck.isAuthError(400, "Unauthorized: AUTH_KEY_UNREGISTERED"))
        assertTrue(SessionCheck.isAuthError(0, "SESSION_REVOKED"))
        assertTrue(SessionCheck.isAuthError(0, "SESSION_EXPIRED"))
        assertTrue(SessionCheck.isAuthError(0, "USER_DEACTIVATED_BAN"))
        assertTrue(SessionCheck.isAuthError(0, "AUTH_KEY_INVALID"))
    }

    @Test
    fun `a flood wait, an outage or a missing file is not a signed out session`() {
        assertFalse(SessionCheck.isAuthError(429, "Too Many Requests: retry after 30"))
        assertFalse(SessionCheck.isAuthError(420, "FLOOD_WAIT_30"))
        assertFalse(SessionCheck.isAuthError(500, "Request aborted"))
        assertFalse(SessionCheck.isAuthError(400, "Invalid file identifier"))
        assertFalse(SessionCheck.isAuthError(400, "FILE_ID_INVALID"))
        assertFalse(SessionCheck.isAuthError(0, "Timeout"))
        assertFalse(SessionCheck.isAuthError(0, null))
        assertFalse(SessionCheck.isAuthError(0, ""))
        // Asked during sign in for a two-step password: a 401, and nothing ended.
        assertFalse(SessionCheck.isAuthError(401, "SESSION_PASSWORD_NEEDED"))
    }

    @Test
    fun `a logout nobody here asked for is Telegram ending the session`() {
        assertTrue(SessionCheck.endedByTelegram(TdAuthorization.LoggingOut, logOutAsked = false))
        // The viewer's own Sign out, or giving up on a half finished sign in.
        assertFalse(SessionCheck.endedByTelegram(TdAuthorization.LoggingOut, logOutAsked = true))
        // Closing alone is the app replacing its client (a storage move, a retry), not a logout.
        for (state in listOf(TdAuthorization.Closing, TdAuthorization.Closed, TdAuthorization.Ready, TdAuthorization.WaitPhoneNumber, TdAuthorization.WaitParameters)) {
            assertFalse(state.toString(), SessionCheck.endedByTelegram(state, logOutAsked = false))
        }
    }

    @Test
    fun `a sign in screen after a marked account follows a session that ended elsewhere`() {
        assertTrue(SessionCheck.endedElsewhere(signInStep = true, wasSignedIn = true))
        // A first sign in, or one after Sign out, which clears the mark first.
        assertFalse(SessionCheck.endedElsewhere(signInStep = true, wasSignedIn = false))
        // The loader or a failure is not yet the sign in.
        assertFalse(SessionCheck.endedElsewhere(signInStep = false, wasSignedIn = true))
    }

    @Test
    fun `telegram answering a session with no line gets a new client, once`() {
        assertEquals(LineAction.FreshClient, SessionCheck.afterProbe(signedIn = true, foreground = true, proxyOn = false, evidence = dcAnswers))
        assertEquals(LineAction.Wait, SessionCheck.afterProbe(true, true, false, dcAnswers.copy(freshClientTried = true)))
    }

    @Test
    fun `no new client for a block, a proxy, the background, a sign in or a line not yet checked`() {
        assertEquals(LineAction.Wait, SessionCheck.afterProbe(true, true, false, dcSilent))
        assertEquals(LineAction.Wait, SessionCheck.afterProbe(true, true, false, LineEvidence()))
        assertEquals(LineAction.Wait, SessionCheck.afterProbe(true, true, proxyOn = true, evidence = dcAnswers))
        assertEquals(LineAction.Wait, SessionCheck.afterProbe(true, foreground = false, proxyOn = false, evidence = dcAnswers))
        assertEquals(LineAction.Wait, SessionCheck.afterProbe(signedIn = false, foreground = true, proxyOn = false, evidence = dcAnswers))
    }

    @Test
    fun `a block needs other sites answering and telegram not`() {
        // Android's own check page answered, no data centre did.
        assertTrue(SessionCheck.blocked(networkOnline = true, evidence = LineEvidence(telegram = false)))
        // A desktop: the app's own site answered, no data centre did.
        assertTrue(SessionCheck.blocked(networkOnline = false, evidence = dcSilent))
        // Telegram's connections answer and a new client still got no line: its traffic is filtered.
        assertTrue(SessionCheck.blocked(true, dcAnswers.copy(freshClientTried = true)))
    }

    @Test
    fun `a long wait alone is never a block`() {
        assertFalse(SessionCheck.blocked(networkOnline = true, evidence = LineEvidence()))
        assertFalse(SessionCheck.blocked(true, dcAnswers))
        // Nothing answers at all: the network, not Telegram.
        assertFalse(SessionCheck.blocked(false, LineEvidence(internet = false, telegram = false)))
    }

    @Test
    fun `the mi tv case says telegram is not answering, with no proxy suggested`() {
        val before = ConnectAdvice.hint(TelegramLink.Connecting, wifi, proxyOn = false)
        assertEquals(ConnectHint.NotAnswering, before)
        assertFalse(ConnectAdvice.suggestsProxy(before, proxyOn = false))
        val checked = ConnectAdvice.hint(TelegramLink.Connecting, wifi, proxyOn = false, evidence = dcAnswers)
        assertEquals(ConnectHint.NotAnswering, checked)
        assertFalse(ConnectAdvice.suggestsProxy(checked, proxyOn = false))
    }

    @Test
    fun `a block with evidence suggests a proxy, unless one is on`() {
        val blocked = ConnectAdvice.hint(TelegramLink.Connecting, wifi, proxyOn = false, evidence = dcSilent)
        assertEquals(ConnectHint.MaybeBlocked, blocked)
        assertTrue(ConnectAdvice.suggestsProxy(blocked, proxyOn = false))
        assertFalse(ConnectAdvice.suggestsProxy(blocked, proxyOn = true))
        assertEquals(ConnectHint.ProxyNotAnswering, ConnectAdvice.hint(TelegramLink.Connecting, wifi, proxyOn = true, evidence = dcSilent))
    }

    @Test
    fun `an outage keeps its own hint whatever the evidence`() {
        assertEquals(ConnectHint.Offline, ConnectAdvice.hint(TelegramLink.WaitingForNetwork, wifi, false, dcSilent))
        assertEquals(ConnectHint.Offline, ConnectAdvice.hint(TelegramLink.Connecting, wifi.copy(internet = false), false, dcSilent))
        assertEquals(ConnectHint.CaptivePortal, ConnectAdvice.hint(TelegramLink.Connecting, wifi.copy(captivePortal = true), false, dcSilent))
    }
}
