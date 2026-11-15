package com.tmplayer.data

import dev.g000sha256.tdl.dto.ConnectionStateConnecting
import dev.g000sha256.tdl.dto.ConnectionStateConnectingToProxy
import dev.g000sha256.tdl.dto.ConnectionStateReady
import dev.g000sha256.tdl.dto.ConnectionStateUpdating
import dev.g000sha256.tdl.dto.ConnectionStateWaitingForNetwork
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The connecting screen's reasoning: what it says, whether it is held up, and what to suggest. */
class ConnectAdviceTest {

    private val wifi = NetworkFacts(known = true, kind = NetworkKind.WiFi, id = "100")

    @Test
    fun `every TDLib connection state has its own link and step`() {
        assertEquals(TelegramLink.WaitingForNetwork, TelegramLink.of(ConnectionStateWaitingForNetwork()))
        assertEquals(TelegramLink.ConnectingToProxy, TelegramLink.of(ConnectionStateConnectingToProxy()))
        assertEquals(TelegramLink.Connecting, TelegramLink.of(ConnectionStateConnecting()))
        assertEquals(TelegramLink.Updating, TelegramLink.of(ConnectionStateUpdating()))
        assertEquals(TelegramLink.Ready, TelegramLink.of(ConnectionStateReady()))
        assertEquals(TelegramLink.entries.size, TelegramLink.entries.map(ConnectAdvice::step).toSet().size)
    }

    @Test
    fun `only updating and ready count as a line`() {
        assertEquals(setOf(TelegramLink.Updating, TelegramLink.Ready), TelegramLink.entries.filter { it.isUp }.toSet())
    }

    @Test
    fun `the line is told apart as connected, device offline, through a proxy, or online and not answering`() {
        assertEquals(Reach.Connected, ConnectAdvice.reach(TelegramLink.Ready, wifi))
        assertEquals(Reach.Connected, ConnectAdvice.reach(TelegramLink.Updating, wifi))
        assertEquals(Reach.DeviceOffline, ConnectAdvice.reach(TelegramLink.WaitingForNetwork, wifi))
        assertEquals(Reach.DeviceOffline, ConnectAdvice.reach(TelegramLink.Connecting, wifi.copy(hasNetwork = false)))
        assertEquals(Reach.DeviceOffline, ConnectAdvice.reach(TelegramLink.Connecting, wifi.copy(internet = false)))
        assertEquals(Reach.ThroughProxy, ConnectAdvice.reach(TelegramLink.ConnectingToProxy, wifi))
        assertEquals(Reach.TelegramNotAnswering, ConnectAdvice.reach(TelegramLink.Connecting, wifi))
        assertEquals(Reach.TelegramNotAnswering, ConnectAdvice.reach(TelegramLink.Starting, wifi))
        // A platform that reports nothing (the desktop) is never "offline": TDLib's own word decides.
        assertEquals(Reach.TelegramNotAnswering, ConnectAdvice.reach(TelegramLink.Connecting, NetworkFacts()))
        assertEquals(Reach.DeviceOffline, ConnectAdvice.reach(TelegramLink.WaitingForNetwork, NetworkFacts()))
        for (link in TelegramLink.entries) ConnectAdvice.reach(link, wifi)
    }

    @Test
    fun `a signed in session online with no line is unreachable, only while the app is on view`() {
        for (link in listOf(TelegramLink.Connecting, TelegramLink.ConnectingToProxy, TelegramLink.Starting)) {
            assertTrue(ConnectAdvice.unreachable(AuthState.Ready, link, wifi, shown = true))
            assertFalse(ConnectAdvice.unreachable(AuthState.Ready, link, wifi, shown = false))
        }
        // A captive portal and a VPN are online as far as the platform says: they get the banner, with its one line.
        assertTrue(ConnectAdvice.unreachable(AuthState.Ready, TelegramLink.Connecting, wifi.copy(captivePortal = true), shown = true))
        assertTrue(ConnectAdvice.unreachable(AuthState.Ready, TelegramLink.Connecting, wifi.copy(vpn = true), shown = true))
    }

    @Test
    fun `no banner for a line that is up, a device that is offline, or a session not signed in`() {
        for (link in listOf(TelegramLink.Ready, TelegramLink.Updating)) {
            assertFalse(ConnectAdvice.unreachable(AuthState.Ready, link, wifi, shown = true))
        }
        assertFalse(ConnectAdvice.unreachable(AuthState.Ready, TelegramLink.WaitingForNetwork, wifi, shown = true))
        assertFalse(ConnectAdvice.unreachable(AuthState.Ready, TelegramLink.Connecting, wifi.copy(hasNetwork = false), shown = true))
        assertFalse(ConnectAdvice.unreachable(AuthState.Connecting, TelegramLink.Connecting, wifi, shown = true))
        assertFalse(ConnectAdvice.unreachable(AuthState.Failed("x"), TelegramLink.Connecting, wifi, shown = true))
    }

    @Test
    fun `a captive portal and a VPN each get their one line, and a proxy is suggested for neither`() {
        assertEquals(ConnectHint.CaptivePortal, ConnectAdvice.hint(TelegramLink.Connecting, wifi.copy(captivePortal = true), proxyOn = false))
        assertEquals(ConnectHint.Vpn, ConnectAdvice.hint(TelegramLink.Connecting, wifi.copy(vpn = true), proxyOn = false))
        assertFalse(ConnectAdvice.suggestsProxy(ConnectHint.CaptivePortal, proxyOn = false))
        assertFalse(ConnectAdvice.suggestsProxy(ConnectHint.Vpn, proxyOn = false))
    }

    @Test
    fun `connecting is always waiting, a signed in session or a failure never`() {
        for (link in TelegramLink.entries) {
            assertTrue(ConnectAdvice.waiting(AuthState.Connecting, link))
            assertFalse(ConnectAdvice.waiting(AuthState.Ready, link))
            assertFalse(ConnectAdvice.waiting(AuthState.Failed("x"), link))
        }
    }

    @Test
    fun `a sign in pane waits only while there is no line`() {
        val panes = listOf(AuthState.ChooseMethod, AuthState.Phone(), AuthState.Qr("tg://login?token=a"), AuthState.EmailCode("a***@b.c"))
        for (pane in panes) {
            assertTrue(ConnectAdvice.waiting(pane, TelegramLink.Connecting))
            assertTrue(ConnectAdvice.waiting(pane, TelegramLink.ConnectingToProxy))
            assertFalse(ConnectAdvice.waiting(pane, TelegramLink.Ready))
            assertFalse(ConnectAdvice.waiting(pane, TelegramLink.Updating))
        }
    }

    @Test
    fun `the hint follows the evidence in order`() {
        assertEquals(ConnectHint.Offline, ConnectAdvice.hint(TelegramLink.WaitingForNetwork, wifi, proxyOn = true))
        assertEquals(ConnectHint.Offline, ConnectAdvice.hint(TelegramLink.Connecting, wifi.copy(hasNetwork = false), proxyOn = false))
        assertEquals(ConnectHint.CaptivePortal, ConnectAdvice.hint(TelegramLink.Connecting, wifi.copy(captivePortal = true, validated = false), proxyOn = true))
        assertEquals(ConnectHint.ProxyNotAnswering, ConnectAdvice.hint(TelegramLink.ConnectingToProxy, wifi, proxyOn = false))
        assertEquals(ConnectHint.ProxyNotAnswering, ConnectAdvice.hint(TelegramLink.Connecting, wifi.copy(vpn = true), proxyOn = true))
        assertEquals(ConnectHint.Vpn, ConnectAdvice.hint(TelegramLink.Connecting, wifi.copy(vpn = true), proxyOn = false))
        // A wait with nothing found out about it is not a block (HX23).
        assertEquals(ConnectHint.NotAnswering, ConnectAdvice.hint(TelegramLink.Connecting, wifi, proxyOn = false))
        assertEquals(ConnectHint.MaybeBlocked, ConnectAdvice.hint(TelegramLink.Connecting, wifi, proxyOn = false, evidence = LineEvidence(telegram = false)))
    }

    @Test
    fun `a desktop that reports nothing is never told it is offline`() {
        assertEquals(ConnectHint.NotAnswering, ConnectAdvice.hint(TelegramLink.Connecting, NetworkFacts(), proxyOn = false))
        assertEquals(NetworkStatus.Unknown, NetworkFacts().status)
        assertTrue(ConnectAdvice.networkNotes(NetworkFacts()).isEmpty())
    }

    @Test
    fun `an unvalidated network is unknown, not offline`() {
        // A captive portal, or a country where Google's check page is blocked: the internet may well work.
        assertEquals(NetworkStatus.Unknown, wifi.copy(validated = false).status)
        assertEquals(NetworkStatus.Unknown, wifi.copy(captivePortal = true).status)
        assertEquals(NetworkStatus.Online, wifi.status)
        assertEquals(NetworkStatus.Offline, wifi.copy(internet = false).status)
        assertEquals(NetworkStatus.Offline, NetworkFacts(known = true, hasNetwork = false, internet = false).status)
    }

    @Test
    fun `the network line names the kind, then what is worth checking`() {
        assertEquals(listOf(NetworkNote.WiFi), ConnectAdvice.networkNotes(wifi))
        assertEquals(listOf(NetworkNote.Mobile, NetworkNote.Vpn, NetworkNote.Unchecked), ConnectAdvice.networkNotes(wifi.copy(kind = NetworkKind.Mobile, vpn = true, validated = false)))
        assertEquals(listOf(NetworkNote.WiFi, NetworkNote.Portal), ConnectAdvice.networkNotes(wifi.copy(captivePortal = true, validated = false)))
        assertEquals(listOf(NetworkNote.None), ConnectAdvice.networkNotes(wifi.copy(hasNetwork = false)))
    }

    @Test
    fun `only a real switch of network is passed on to TDLib`() {
        assertTrue(wifi.isSwitchFrom(NetworkFacts()))
        assertFalse(wifi.copy(validated = false).isSwitchFrom(wifi))
        assertTrue(wifi.copy(id = "101", kind = NetworkKind.Mobile).isSwitchFrom(wifi))
        assertTrue(wifi.copy(vpn = true).isSwitchFrom(wifi))
        assertTrue(wifi.isSwitchFrom(NetworkFacts(known = true, hasNetwork = false, internet = false)))
        // Losing the network is never passed on: told it has none, TDLib stops trying.
        assertFalse(NetworkFacts(known = true, hasNetwork = false, internet = false).isSwitchFrom(wifi))
    }
}
