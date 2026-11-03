package com.tmplayer.cast

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/** The phone's one Cast list over Google Cast and DLNA: ids, the Cast-wins row, which side is the session. */
class MergedCastSupportTest {

    private class Fake(override val available: Boolean = true) : CastSupport {
        val list = MutableStateFlow<List<CastDeviceInfo>>(emptyList())
        val state = MutableStateFlow<CastSessionState>(CastSessionState.Idle)
        val status = MutableStateFlow(RemoteStatus())
        override val devices: StateFlow<List<CastDeviceInfo>> get() = list
        override val session: StateFlow<CastSessionState> get() = state
        override val remote: StateFlow<RemoteStatus> get() = status
        override var caps: ReceiverCaps? = null
        val calls = ArrayList<String>()
        var discovering = 0
        override fun startDiscovery() { discovering++ }
        override fun stopDiscovery() { discovering-- }
        override fun connect(id: String) {
            calls += "connect $id"
            state.value = CastSessionState.Connecting(id)
        }
        override fun endSession() {
            calls += "end"
            state.value = CastSessionState.Idle
        }
        override fun load(request: CastLoadRequest, onResult: (Boolean) -> Unit) { calls += "load"; onResult(true) }
        override fun play() { calls += "play" }
        override fun pause() { calls += "pause" }
        override fun seek(positionMs: Long) { calls += "seek $positionMs" }
        override fun stopMedia() { calls += "stopMedia" }
        override fun setVolume(level: Double) { calls += "volume $level" }
        override fun setMuted(muted: Boolean) { calls += "mute $muted" }
        override fun setActiveTracks(ids: List<Long>) = Unit
        override fun setRate(rate: Double) = Unit
    }

    private fun ip(last: Int) = InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 1, last.toByte()))

    private fun merged(google: Fake, dlna: Fake) = MergedCastSupport(google, dlna, Dispatchers.Unconfined)

    @Test
    fun `one list, Google Cast first, ids carry their kind`() {
        val g = Fake().also { it.list.value = listOf(CastDeviceInfo("route1", "Bedroom", "Chromecast", address = ip(20))) }
        val d = Fake().also { it.list.value = listOf(CastDeviceInfo("udn:a", "Attic TV", "Acme T1", kind = CastKind.Dlna, address = ip(30))) }
        val rows = merged(g, d).devices.value
        assertEquals(listOf("gc:route1", "dlna:udn:a"), rows.map { it.id })
        assertEquals(listOf(CastKind.GoogleCast, CastKind.Dlna), rows.map { it.kind })
    }

    @Test
    fun `a TV that answers both ways is one row and Cast wins`() {
        val g = Fake().also { it.list.value = listOf(CastDeviceInfo("route1", "Sony TV", "BRAVIA", address = ip(40))) }
        val d = Fake().also {
            it.list.value = listOf(
                CastDeviceInfo("udn:sony", "Sony TV (DLNA)", "Sony", kind = CastKind.Dlna, address = ip(40)),
                CastDeviceInfo("udn:other", "Den TV", "Acme", kind = CastKind.Dlna, address = ip(41)),
            )
        }
        assertEquals(listOf("gc:route1", "dlna:udn:other"), merged(g, d).devices.value.map { it.id })
    }

    @Test
    fun `a row from either side follows its side when the lists change`() {
        val g = Fake()
        val d = Fake()
        val m = merged(g, d)
        assertTrue(m.devices.value.isEmpty())
        d.list.value = listOf(CastDeviceInfo("udn:a", "TV", "", kind = CastKind.Dlna))
        assertEquals(listOf("dlna:udn:a"), m.devices.value.map { it.id })
        g.list.value = listOf(CastDeviceInfo("r", "Chromecast", ""))
        assertEquals(listOf("gc:r", "dlna:udn:a"), m.devices.value.map { it.id })
    }

    @Test
    fun `connect goes to the side the id names, with the prefix off`() {
        val g = Fake()
        val d = Fake()
        val m = merged(g, d)
        m.connect("dlna:udn:a")
        assertEquals(listOf("connect udn:a"), d.calls)
        assertTrue(g.calls.isEmpty())
        m.connect("gc:route1")
        assertEquals(listOf("connect route1"), g.calls)
        m.connect("nonsense")
        assertEquals(1, g.calls.size)
    }

    @Test
    fun `the session, remote and controls are the connected side's`() {
        val g = Fake()
        val d = Fake()
        val m = merged(g, d)
        m.connect("dlna:udn:a")
        d.state.value = CastSessionState.Connected("Attic TV", "Acme", ip(30), CastKind.Dlna)
        d.status.value = RemoteStatus(contentId = "http://x/video.mp4", canSeek = false)
        assertEquals(CastKind.Dlna, (m.session.value as CastSessionState.Connected).kind)
        assertEquals("http://x/video.mp4", m.remote.value.contentId)
        assertFalse(m.remote.value.canSeek)
        m.play()
        m.seek(5_000)
        assertEquals(listOf("connect udn:a", "play", "seek 5000"), d.calls)
        assertTrue(g.calls.isEmpty())
    }

    @Test
    fun `picking a TV on the other side lets the first one go, the new one first`() {
        val g = Fake()
        val d = Fake()
        val m = merged(g, d)
        g.state.value = CastSessionState.Connected("Bedroom", "Chromecast", ip(20))
        m.connect("dlna:udn:a")
        // The new TV is the session at once, so the player never sees an idle moment between them.
        assertTrue(m.session.value is CastSessionState.Connecting)
        assertEquals(listOf("end"), g.calls)
    }

    @Test
    fun `an idle side does not speak, and a stale failure of the other is not shown`() {
        val g = Fake()
        val d = Fake()
        val m = merged(g, d)
        d.state.value = CastSessionState.Failed("Old TV")
        m.connect("gc:r")
        g.state.value = CastSessionState.Idle
        // Google is idle and was last; DLNA's old failure is neither connected nor connecting.
        assertTrue(m.session.value is CastSessionState.Idle)
    }

    @Test
    fun `a session left from before on the other side is picked up`() {
        val g = Fake()
        val d = Fake()
        val m = merged(g, d)
        g.state.value = CastSessionState.Connected("Bedroom", "Chromecast", ip(20))
        assertEquals("Bedroom", (m.session.value as CastSessionState.Connected).name)
    }

    @Test
    fun `caps, server rules and refusal come from the connected side`() {
        val g = Fake()
        val d = Fake().also { it.caps = ReceiverCaps.forModel("Chromecast") }
        val m = merged(g, d)
        assertNull(m.caps)
        d.state.value = CastSessionState.Connected("TV", "m", ip(30), CastKind.Dlna)
        m.connect("dlna:udn:a")
        assertSame(d.caps, m.caps)
    }

    @Test
    fun `discovery starts and stops on both sides`() {
        val g = Fake()
        val d = Fake()
        val m = merged(g, d)
        m.startDiscovery()
        assertEquals(1, g.discovering)
        assertEquals(1, d.discovering)
        m.stopDiscovery()
        assertEquals(0, g.discovering)
        assertEquals(0, d.discovering)
    }

    @Test
    fun `available when either side is`() {
        assertTrue(merged(Fake(available = false), Fake()).available)
        assertFalse(merged(Fake(available = false), Fake(available = false)).available)
    }

    @Test
    fun `end session ends both`() {
        val g = Fake()
        val d = Fake()
        merged(g, d).endSession()
        assertEquals(listOf("end"), g.calls)
        assertEquals(listOf("end"), d.calls)
    }
}
