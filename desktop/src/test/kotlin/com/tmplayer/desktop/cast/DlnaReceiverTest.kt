package com.tmplayer.desktop.cast

import com.tmplayer.cast.dlna.DeviceDescription
import com.tmplayer.cast.dlna.DlnaDevice
import com.tmplayer.cast.dlna.DlnaDiscovery
import com.tmplayer.cast.dlna.DlnaService
import com.tmplayer.cast.dlna.SoapResult
import com.tmplayer.cast.dlna.SoapTransport
import com.tmplayer.i18n.L
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The DLNA side of the desktop cast without a network: [DlnaReceiver] against a scripted SOAP
 * transport, and the picker's merged list ([CastFinder]).
 */
class DlnaReceiverTest {

    private val tv = InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 1, 50))

    private fun description(withVolume: Boolean = true) = DeviceDescription(
        udn = "uuid:tv-1",
        friendlyName = "Living room TV",
        manufacturer = "Acme",
        modelName = "T1000",
        iconUrl = null,
        avTransport = DlnaService("urn:schemas-upnp-org:service:AVTransport:1", "http://192.168.1.50/av"),
        renderingControl = if (withVolume) DlnaService("urn:schemas-upnp-org:service:RenderingControl:1", "http://192.168.1.50/rc") else null,
        connectionManager = DlnaService("urn:schemas-upnp-org:service:ConnectionManager:1", "http://192.168.1.50/cm"),
    )

    private fun device(withVolume: Boolean = true) = DlnaDevice(description(withVolume), tv, "http://192.168.1.50/description.xml")

    /** A renderer that answers from a table and remembers what it was asked. */
    private class Script(var state: String = "STOPPED", var setUri: SoapResult = SoapResult.Ok(emptyMap())) : SoapTransport {
        val calls = CopyOnWriteArrayList<Pair<String, Map<String, String>>>()
        @Volatile var down = false

        override fun call(service: DlnaService, action: String, args: List<Pair<String, String>>): SoapResult {
            calls += action to args.toMap()
            if (down) return SoapResult.Failed("no answer")
            return when (action) {
                "SetAVTransportURI" -> setUri
                "GetTransportInfo" -> SoapResult.Ok(mapOf("CurrentTransportState" to state, "CurrentTransportStatus" to "OK"))
                "GetPositionInfo" -> SoapResult.Ok(mapOf("RelTime" to "0:00:12", "TrackDuration" to "0:10:00"))
                "GetCurrentTransportActions" -> SoapResult.Ok(mapOf("Actions" to "Play,Pause,Stop,Seek"))
                "GetProtocolInfo" -> SoapResult.Ok(mapOf("Sink" to "http-get:*:video/mp4:*,http-get:*:video/x-matroska:*"))
                "GetVolume" -> SoapResult.Ok(mapOf("CurrentVolume" to "30"))
                else -> SoapResult.Ok(emptyMap())
            }
        }
    }

    private fun load(url: String = "http://192.168.1.10:5000/t/video.mp4", seekable: Boolean = true) =
        ReceiverLoad(url, "video/mp4", "Episode 4", "", 600_000, 0, true, hls = false, seekable = seekable)

    private fun receiver(soap: Script, withVolume: Boolean = true): DlnaReceiver {
        val d = device(withVolume)
        return DlnaReceiver(CastTarget.of(d), d, soap)
    }

    private fun waitFor(ms: Long = 6_000, check: () -> Boolean) {
        val end = System.nanoTime() + ms * 1_000_000
        while (!check() && System.nanoTime() < end) Thread.sleep(20)
        assertTrue("timed out", check())
    }

    @Test
    fun `connect reads what the TV plays and its volume`() = runBlocking {
        val r = receiver(Script())
        r.connect()
        assertTrue(r.caps.matroska != com.tmplayer.cast.Support.No)
        assertEquals(0.3, r.volume.value.level, 0.001)
        assertFalse(r.volume.value.fixed)
        r.close()
    }

    @Test
    fun `a TV without RenderingControl is a fixed volume`() = runBlocking {
        val r = receiver(Script(), withVolume = false)
        r.connect()
        assertTrue(r.volume.value.fixed)
        r.close()
    }

    @Test
    fun `a TV that does not answer is not connected`() {
        val s = Script().also { it.down = true }
        val r = receiver(s)
        val failed = runCatching { runBlocking { r.connect() } }.exceptionOrNull()
        assertTrue(failed is IOException)
        r.close()
    }

    @Test
    fun `a load sends the address and a DIDL with ranges, then the polls become the status`() = runBlocking {
        val s = Script(state = "PLAYING")
        val r = receiver(s)
        r.connect()
        val answer = r.load(load())
        assertEquals(ReceiverAnswer.Loaded, answer)
        val set = s.calls.first { it.first == "SetAVTransportURI" }.second
        assertEquals("http://192.168.1.10:5000/t/video.mp4", set["CurrentURI"])
        assertTrue(set.getValue("CurrentURIMetaData").contains("DLNA.ORG_OP=01"))
        waitFor { r.media.value.playerState == "PLAYING" }
        assertEquals(12_000, r.media.value.currentTimeMs)
        assertEquals("http://192.168.1.10:5000/t/video.mp4", r.media.value.contentId)
        r.close()
    }

    @Test
    fun `a rewrapped stream is sent without range seeking`() = runBlocking {
        val s = Script(state = "PLAYING")
        val r = receiver(s)
        r.connect()
        r.load(load(seekable = false))
        val didl = s.calls.first { it.first == "SetAVTransportURI" }.second.getValue("CurrentURIMetaData")
        assertTrue(didl.contains("DLNA.ORG_OP=00"))
        r.close()
    }

    @Test
    fun `a bad type is a refusal the viewer can read`() = runBlocking {
        val s = Script(setUri = SoapResult.Fault(500, 714, "Illegal MIME-type"))
        val r = receiver(s)
        r.connect()
        val answer = r.load(load())
        assertEquals(ReceiverAnswer.Refused(L.castRefusedType), answer)
        assertEquals(L.castRefusedType, r.lastError)
        r.close()
    }

    @Test
    fun `a TV that stops answering is lost after three polls`() = runBlocking {
        val s = Script(state = "PLAYING")
        val r = receiver(s)
        r.connect()
        r.load(load())
        waitFor { r.media.value.playerState == "PLAYING" }
        s.down = true
        waitFor(10_000) { r.lost.value }
        r.close()
    }

    @Test
    fun `only the TV may fetch the video, and HLS is not offered`() {
        val r = receiver(Script())
        val allowed = r.allowedClient!!
        assertTrue(allowed(tv))
        assertFalse(allowed(InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 1, 51))))
        assertEquals(30 * 60_000L, r.idleLimitMs)
        r.close()
    }

    @Test
    fun `a TV with both Cast and DLNA is one row, and Cast wins`() {
        val cast = MutableStateFlow(
            listOf(CastDevice("cast-id", "Living room TV", "Chromecast", tv)),
        )
        val other = DlnaDevice(description().copy(udn = "uuid:other", friendlyName = "Bedroom"), InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 1, 60)), "http://192.168.1.60/d.xml")
        var listener: DlnaDiscovery.Listener? = null
        val finder = CastFinder(cast, {}, {}) { l ->
            listener = l
            DlnaDiscovery(l, interfaces = { emptyList() })
        }
        finder.start()
        listener!!.onFound(device())
        listener!!.onFound(other)
        val rows = finder.devices.value
        assertEquals(listOf("Bedroom", "Living room TV"), rows.map { it.name })
        assertEquals(ReceiverKind.GoogleCast, rows.first { it.name == "Living room TV" }.kind)
        assertEquals(ReceiverKind.Dlna, rows.first { it.name == "Bedroom" }.kind)
        assertEquals("dlna:uuid:other", rows.first { it.name == "Bedroom" }.id)
        finder.stop()
    }
}
