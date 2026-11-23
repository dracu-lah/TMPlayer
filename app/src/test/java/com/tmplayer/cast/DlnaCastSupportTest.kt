package com.tmplayer.cast

import com.tmplayer.cast.dlna.DeviceDescription
import com.tmplayer.cast.dlna.DlnaDevice
import com.tmplayer.cast.dlna.DlnaDiscovery
import com.tmplayer.cast.dlna.DlnaRenderer
import com.tmplayer.cast.dlna.DlnaService
import com.tmplayer.cast.dlna.ManualAddress
import com.tmplayer.cast.dlna.ManualTvStore
import com.tmplayer.cast.dlna.SoapResult
import com.tmplayer.cast.dlna.SoapTransport
import com.tmplayer.i18n.L
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The phone's DLNA session without a TV: [DlnaCastSupport] against a scripted SOAP transport and a
 * fake platform, and the real discovery on loopback for the "stops when the picker closes" claim.
 */
class DlnaCastSupportTest {

    private val tv = InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 1, 50))
    private val stranger = InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 1, 66))

    private class Platform(var lan: Boolean = true) : DlnaPlatform {
        var multicast = 0
        var keptFor: Pair<String, String>? = null
        var keepAlives = 0
        var releases = 0
        override fun holdMulticast() { multicast++ }
        override fun releaseMulticast() { multicast-- }
        override fun hasLan() = lan
        override fun keepAlive(title: String, device: String) { keptFor = title to device; keepAlives++ }
        override fun releaseKeepAlive() { releases++; keptFor = null }
    }

    private class Script(var state: String = "PLAYING", var actions: String = "Play,Pause,Stop,Seek", var setUri: SoapResult = SoapResult.Ok(emptyMap())) : SoapTransport {
        val calls = CopyOnWriteArrayList<String>()
        @Volatile var down = false
        override fun call(service: DlnaService, action: String, args: List<Pair<String, String>>): SoapResult {
            calls += action
            if (down) return SoapResult.Failed("no answer")
            return when (action) {
                "SetAVTransportURI" -> setUri
                "GetTransportInfo" -> SoapResult.Ok(mapOf("CurrentTransportState" to state, "CurrentTransportStatus" to "OK"))
                "GetPositionInfo" -> SoapResult.Ok(mapOf("RelTime" to "0:00:12", "TrackDuration" to "0:10:00"))
                "GetCurrentTransportActions" -> SoapResult.Ok(mapOf("Actions" to actions))
                "GetProtocolInfo" -> SoapResult.Ok(mapOf("Sink" to "http-get:*:video/mp4:*,http-get:*:video/x-matroska:*"))
                "GetVolume" -> SoapResult.Ok(mapOf("CurrentVolume" to "30"))
                else -> SoapResult.Ok(emptyMap())
            }
        }
    }

    private fun device(withVolume: Boolean = true) = DlnaDevice(
        DeviceDescription(
            udn = "uuid:tv-1",
            friendlyName = "Living room TV",
            manufacturer = "Acme",
            modelName = "T1000",
            iconUrl = null,
            avTransport = DlnaService("urn:schemas-upnp-org:service:AVTransport:1", "http://192.168.1.50/av"),
            renderingControl = if (withVolume) DlnaService("urn:schemas-upnp-org:service:RenderingControl:1", "http://192.168.1.50/rc") else null,
            connectionManager = DlnaService("urn:schemas-upnp-org:service:ConnectionManager:1", "http://192.168.1.50/cm"),
        ),
        tv,
        "http://192.168.1.50/description.xml",
    )

    private var listener: DlnaDiscovery.Listener? = null
    private val made = ArrayList<DlnaCastSupport>()

    private fun support(script: Script, platform: Platform = Platform(), withVolume: Boolean = true): DlnaCastSupport {
        val s = DlnaCastSupport(
            platform,
            discovery = { l ->
                listener = l
                DlnaDiscovery(l, interfaces = { emptyList() }, listenForNotify = false)
            },
            rendererFor = { DlnaRenderer(it, script) },
            dispatcher = Dispatchers.Unconfined,
        )
        made += s
        s.startDiscovery()
        listener!!.onFound(device(withVolume))
        return s
    }

    @After
    fun tearDown() {
        made.forEach { it.endSession(); it.stopDiscovery() }
    }

    private val id = DlnaCastSupport.ID_PREFIX + "uuid:tv-1"

    private fun load(url: String = "http://192.168.1.10:5000/t/video.mp4", seekable: Boolean = true) =
        CastLoadRequest(url, "video/mp4", "Episode 4", "", 600_000, 0, seekable = seekable)

    private fun waitFor(ms: Long = 8_000, check: () -> Boolean) {
        val end = System.nanoTime() + ms * 1_000_000
        while (!check() && System.nanoTime() < end) Thread.sleep(20)
        assertTrue("timed out", check())
    }

    @Test
    fun `a found TV is a DLNA row with its model and address`() {
        val s = support(Script())
        val row = s.devices.value.single()
        assertEquals(id, row.id)
        assertEquals("Living room TV", row.name)
        assertEquals("Acme T1000", row.model)
        assertEquals(CastKind.Dlna, row.kind)
        assertEquals(tv, row.address)
    }

    @Test
    fun `connect reads what the TV plays and opens the server to its address alone`() {
        val s = support(Script())
        s.connect(id)
        val up = s.session.value as CastSessionState.Connected
        assertEquals(CastKind.Dlna, up.kind)
        assertEquals(tv, up.address)
        assertNotNull(s.caps)
        val access = s.serverAccess!!
        assertTrue(access.allowed(tv))
        assertFalse(access.allowed(stranger))
        assertEquals(30 * 60_000L, access.idleLimitMs)
        assertTrue(s.devices.value.single().connected)
    }

    @Test
    fun `a TV that does not answer fails the connection`() {
        val script = Script().also { it.down = true }
        val s = support(script)
        s.connect(id)
        assertTrue(s.session.value is CastSessionState.Failed)
        assertNull(s.caps)
        assertNull(s.serverAccess)
    }

    @Test
    fun `a load keeps the process alive and the cast screen hears the TV's place`() {
        val platform = Platform()
        val s = support(Script(), platform)
        s.connect(id)
        var took: Boolean? = null
        s.load(load()) { took = it }
        assertEquals(true, took)
        assertEquals("Episode 4" to "Living room TV", platform.keptFor)
        assertEquals("http://192.168.1.10:5000/t/video.mp4", s.remote.value.contentId)
        waitFor { s.remote.value.media != null }
        assertEquals(ReceiverState.Playing, s.remote.value.media?.state)
    }

    @Test
    fun `a TV that refuses the type is told plainly and nothing is kept alive`() {
        val platform = Platform()
        val script = Script(setUri = SoapResult.Fault(500, 714, "Illegal MIME-type"))
        val s = support(script, platform)
        s.connect(id)
        var took: Boolean? = null
        s.load(load()) { took = it }
        assertEquals(false, took)
        assertEquals(L.castRefusedType, s.refusal)
        assertEquals(0, platform.keepAlives)
    }

    @Test
    fun `a load the TV does not answer, or turns down with a code, names that step`() {
        val quiet = Script(setUri = SoapResult.Failed("Read timed out"))
        val s = support(quiet)
        s.connect(id)
        s.load(load()) { }
        assertEquals(L.castFailedNoAnswer, s.refusal)
        quiet.setUri = SoapResult.Fault(500, 718, "Invalid InstanceID")
        s.load(load()) { }
        assertEquals(L.castFailedRefusedCode(718), s.refusal)
    }

    @Test
    fun `the server lets in the TV connected now, so moving the cast to another TV is not turned away`() {
        val s = support(Script())
        s.connect(id)
        // The player makes its server once, with the access of the first TV.
        val access = s.serverAccess!!
        val other = InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 1, 51))
        listener!!.onFound(DlnaDevice(device().description.copy(udn = "uuid:tv-2", friendlyName = "Bedroom TV"), other, "http://192.168.1.51/description.xml"))
        s.connect(DlnaCastSupport.ID_PREFIX + "uuid:tv-2")
        waitFor { (s.session.value as? CastSessionState.Connected)?.name == "Bedroom TV" }
        assertTrue(access.allowed(other))
        assertFalse(access.allowed(tv))
        access.onRequest!!(other)
    }

    @Test
    fun `a TV that leaves Seek out says so`() {
        val s = support(Script(actions = "Play,Pause,Stop"))
        s.connect(id)
        s.load(load()) { }
        waitFor { s.remote.value.media != null }
        assertFalse(s.remote.value.canSeek)
    }

    @Test
    fun `no RenderingControl is a fixed volume`() {
        val s = support(Script(), withVolume = false)
        s.connect(id)
        s.load(load()) { }
        assertTrue(s.remote.value.volumeFixed)
    }

    @Test
    fun `ending the session stops the TV, lets the service go and clears the remote`() {
        val platform = Platform()
        val script = Script()
        val s = support(script, platform)
        s.connect(id)
        s.load(load()) { }
        s.endSession()
        assertTrue(s.session.value is CastSessionState.Idle)
        assertNull(s.serverAccess)
        assertNull(s.remote.value.contentId)
        assertNull(platform.keptFor)
        waitFor { "Stop" in script.calls }
    }

    @Test
    fun `a TV that stops answering is lost, not ended`() {
        val platform = Platform()
        val script = Script()
        val s = support(script, platform)
        s.connect(id)
        s.load(load()) { }
        waitFor { s.remote.value.media != null }
        script.down = true
        waitFor(12_000) { s.session.value is CastSessionState.Lost }
        assertEquals("Living room TV", (s.session.value as CastSessionState.Lost).name)
        assertNull(platform.keptFor)
        assertNull(s.serverAccess)
    }

    @Test
    fun `stopping a video keeps the session and lets the service go`() {
        val platform = Platform()
        val s = support(Script(), platform)
        s.connect(id)
        s.load(load()) { }
        s.stopMedia()
        assertTrue(s.session.value is CastSessionState.Connected)
        assertNull(platform.keptFor)
    }

    @Test
    fun `plans for DLNA drop HLS`() {
        val s = support(Script())
        val plan = CastPlan(listOf(CastRoute.Hls(null, emptyList()), CastRoute.Direct("video/mp4")), audioTrack = null)
        assertTrue(s.adapt(plan).routes.none { it is CastRoute.Hls })
    }

    @Test
    fun `without Wi-Fi or Ethernet the picker is told`() {
        val s = support(Script(), Platform(lan = false))
        assertTrue(s.offLan.value)
        s.stopDiscovery()
        s.startDiscovery()
        assertTrue(s.offLan.value)
    }

    @Test
    fun `the multicast lock is held only while a picker is open`() {
        val platform = Platform()
        // A live search (loopback), not the helper's empty interface list: with no network the search ends by
        // itself on its own thread and releases the lock, which raced the first assertion on a loaded machine.
        val lo = com.tmplayer.cast.LanAddress.Candidate("lo", InetAddress.getLoopbackAddress(), 8)
        val s = DlnaCastSupport(
            platform,
            discovery = { l ->
                DlnaDiscovery(l, interfaces = { listOf(lo) }, searchTarget = java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), 9), listenForNotify = false, allowLoopback = true)
            },
            dispatcher = Dispatchers.Unconfined,
        )
        made += s
        s.startDiscovery()
        assertEquals(1, platform.multicast)
        s.startDiscovery()
        assertEquals(1, platform.multicast)
        s.stopDiscovery()
        assertEquals(1, platform.multicast)
        s.stopDiscovery()
        assertEquals(0, platform.multicast)
    }

    @Test
    fun `discovery and its threads are gone within 10 seconds of the picker closing`() {
        val platform = Platform()
        val lo = com.tmplayer.cast.LanAddress.Candidate("lo", InetAddress.getLoopbackAddress(), 8)
        var real: DlnaDiscovery? = null
        val s = DlnaCastSupport(
            platform,
            discovery = { l ->
                DlnaDiscovery(l, interfaces = { listOf(lo) }, searchTarget = java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), 9), listenForNotify = false, allowLoopback = true)
                    .also { real = it }
            },
            dispatcher = Dispatchers.Unconfined,
        )
        made += s
        s.startDiscovery()
        waitFor { real?.isRunning == true }
        assertTrue(Thread.getAllStackTraces().keys.any { it.isAlive && it.name.startsWith("dlna-search") })
        val closedAt = System.nanoTime()
        s.stopDiscovery()
        assertFalse(real!!.isRunning)
        assertEquals(0, platform.multicast)
        waitFor(10_000) { Thread.getAllStackTraces().keys.none { it.isAlive && (it.name.startsWith("dlna-search") || it.name.startsWith("dlna-timer") || it.name.startsWith("dlna-fetch")) } }
        assertTrue((System.nanoTime() - closedAt) / 1_000_000 < 10_000)
    }

    @Test
    fun `a live search stays on after a quiet spell and the lock is held until the picker closes`() {
        val platform = Platform()
        val lo = com.tmplayer.cast.LanAddress.Candidate("lo", InetAddress.getLoopbackAddress(), 8)
        var real: DlnaDiscovery? = null
        val s = DlnaCastSupport(
            platform,
            discovery = { l ->
                DlnaDiscovery(
                    l, interfaces = { listOf(lo) }, searchTarget = java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), 9),
                    listenForNotify = false, allowLoopback = true, repeatMs = 100, quietStopMs = 200, giveUpMs = 300, live = true,
                ).also { real = it }
            },
            dispatcher = Dispatchers.Unconfined,
        )
        made += s
        s.startDiscovery()
        waitFor { real?.isRunning == true }
        Thread.sleep(1_200)
        assertTrue("past the give-up time a live search still listens", real!!.isRunning)
        assertEquals("and the lock is still held", 1, platform.multicast)
        s.stopDiscovery()
        assertFalse(real!!.isRunning)
        assertEquals(0, platform.multicast)
    }

    private fun tempStore(): ManualTvStore {
        val file = java.io.File.createTempFile("tvs", ".txt").also { it.delete(); it.deleteOnExit() }
        return ManualTvStore(file)
    }

    @Test
    fun `a TV added by its address is listed, remembered and can be removed`() {
        val store = tempStore()
        val platform = Platform()
        val found = device()
        val s = DlnaCastSupport(
            platform, store = store,
            resolve = { if (it == "192.168.1.50") ManualAddress.Outcome.Found(found.copy(manual = true)) else ManualAddress.Outcome.Silent(it) },
            discovery = { l -> listener = l; DlnaDiscovery(l, interfaces = { emptyList() }, listenForNotify = false) },
            dispatcher = Dispatchers.Unconfined,
        )
        made += s
        s.startDiscovery()
        val manual = s.manualTvs!!
        assertTrue(manual.add("192.168.1.99") is ManualAddress.Outcome.Silent)
        assertTrue("nothing answered: no row, nothing saved", s.devices.value.isEmpty() && manual.saved.value.isEmpty())
        assertTrue(manual.add("192.168.1.50") is ManualAddress.Outcome.Found)
        assertEquals(id, s.devices.value.single().id)
        assertEquals("Living room TV", manual.saved.value.single().name)
        assertEquals("a new run still has it", listOf("http://192.168.1.50/description.xml"), store.locations())
        manual.remove("http://192.168.1.50/description.xml")
        assertTrue(s.devices.value.isEmpty() && manual.saved.value.isEmpty() && store.locations().isEmpty())
    }

    @Test
    fun `without a store there is no add a TV`() {
        val s = DlnaCastSupport(Platform(), discovery = { DlnaDiscovery(it, interfaces = { emptyList() }, listenForNotify = false) }, dispatcher = Dispatchers.Unconfined)
        assertNull(s.manualTvs)
    }
}
