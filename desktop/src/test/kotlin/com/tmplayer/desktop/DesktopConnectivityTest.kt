package com.tmplayer.desktop

import com.tmplayer.data.NetworkStatus
import com.tmplayer.desktop.DesktopConnectivity.Interface
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetAddress

class DesktopConnectivityTest {

    private fun ip(text: String) = InetAddress.getByName(text)

    private val loopback = Interface(up = true, loopback = true, addresses = listOf(ip("127.0.0.1"), ip("::1")))

    @Test
    fun `only the loopback is offline`() {
        assertEquals(NetworkStatus.Offline, DesktopConnectivity.statusOf(listOf(loopback)))
    }

    @Test
    fun `an interface that is up with a routable address is online`() {
        val wifi = Interface(up = true, loopback = false, addresses = listOf(ip("fe80::1"), ip("192.168.1.20")))
        assertEquals(NetworkStatus.Online, DesktopConnectivity.statusOf(listOf(loopback, wifi)))
    }

    @Test
    fun `a link local address alone is offline, as is an interface that is down`() {
        val cableOut = Interface(up = true, loopback = false, addresses = listOf(ip("fe80::1"), ip("169.254.10.2")))
        val down = Interface(up = false, loopback = false, addresses = listOf(ip("192.168.1.20")))
        assertEquals(NetworkStatus.Offline, DesktopConnectivity.statusOf(listOf(loopback, cableOut, down)))
    }

    @Test
    fun `no interfaces at all is offline`() {
        assertEquals(NetworkStatus.Offline, DesktopConnectivity.statusOf(emptyList()))
    }
}
