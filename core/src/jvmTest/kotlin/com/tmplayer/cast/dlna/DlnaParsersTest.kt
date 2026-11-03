package com.tmplayer.cast.dlna

import com.tmplayer.cast.CastPlan
import com.tmplayer.cast.CastRefusal
import com.tmplayer.cast.CastRoute
import com.tmplayer.cast.Container
import com.tmplayer.cast.IdleReason
import com.tmplayer.cast.ReceiverState
import com.tmplayer.cast.Support
import com.tmplayer.cast.WebVtt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DlnaParsersTest {

    // XML safety

    @Test
    fun doctypeAndEntitiesAreRefusedBeforeParsing() {
        val bomb = """<?xml version="1.0"?><!DOCTYPE lolz [<!ENTITY a "aaaa"><!ENTITY b "&a;&a;&a;&a;">]><root>&b;</root>"""
        val xxe = """<?xml version="1.0"?><!DOCTYPE r [<!ENTITY x SYSTEM "file:///etc/passwd">]><root>&x;</root>"""
        assertNull(Xml.parse(bomb, 10_000))
        assertNull(Xml.parse(xxe, 10_000))
        assertNull(Xml.parse("<!doctype r><r/>", 10_000))
        assertNull(DeviceDescription.parse(xxe, "http://192.168.1.5:80/d.xml"))
        assertEquals(SoapResult.Fault(500, null, null), Soap.parse(500, xxe))
    }

    @Test
    fun oversizedAndBrokenXmlIsNull() {
        assertNull(Xml.parse("<a>" + "x".repeat(70_000) + "</a>", Xml.SOAP_MAX))
        assertNotNull(Xml.parse("<a>" + "x".repeat(70_000) + "</a>", Xml.DESCRIPTION_MAX))
        assertNull(Xml.parse("<a><b></a>", 1000))
        assertNull(Xml.parse("", 1000))
        assertNull(Xml.parse(null, 1000))
    }

    // SSDP

    @Test
    fun searchRequestIsWellFormed() {
        val text = String(Ssdp.search(Ssdp.MEDIA_RENDERER, 2))
        assertTrue(text.startsWith("M-SEARCH * HTTP/1.1\r\n"))
        assertTrue("MAN: \"ssdp:discover\"\r\n" in text && "ST: ${Ssdp.MEDIA_RENDERER}\r\n" in text && text.endsWith("\r\n\r\n"))
    }

    @Test
    fun ssdpIgnoresWhatIsNotAnAnswerOrAnAnnouncement() {
        assertNull(Ssdp.parse("M-SEARCH * HTTP/1.1\r\nST: x\r\n\r\n".toByteArray()))
        assertNull(Ssdp.parse("HTTP/1.1 404 Not Found\r\n\r\n".toByteArray()))
        assertNull(Ssdp.parse("NOTIFY * HTTP/1.1\r\nNTS: ssdp:weird\r\nUSN: uuid:a\r\n\r\n".toByteArray()))
        assertNull("an answer with no LOCATION", Ssdp.parse("HTTP/1.1 200 OK\r\nST: x\r\nUSN: uuid:a::x\r\n\r\n".toByteArray()))
        assertNull(Ssdp.parse(ByteArray(0)))
        assertNull(Ssdp.parse(ByteArray(9000) { 'a'.code.toByte() }))
        val bye = Ssdp.parse("NOTIFY * HTTP/1.1\r\nNT: upnp:rootdevice\r\nNTS: ssdp:byebye\r\nUSN: UUID:ABC-1::upnp:rootdevice\r\n\r\n".toByteArray())!!
        assertEquals(SsdpMessage.Kind.ByeBye, bye.kind)
        assertEquals("uuid:abc-1", bye.udn)
    }

    @Test
    fun maxAgeToleratesSpaces() {
        assertEquals(1800, Ssdp.maxAge("max-age = 1800"))
        assertEquals(60, Ssdp.maxAge("no-cache, MAX-AGE=60"))
        assertNull(Ssdp.maxAge("no-cache"))
        assertNull(Ssdp.maxAge(null))
    }

    // Descriptions

    private fun description(controlUrl: String, urlBase: String? = null, extraService: String = ""): String = """<?xml version="1.0"?>
<root xmlns="urn:schemas-upnp-org:device-1-0">${urlBase?.let { "<URLBase>$it</URLBase>" } ?: ""}
<device><deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType><friendlyName>TV &amp; Co</friendlyName><UDN>uuid:ABC</UDN>
<serviceList><service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><controlURL>$controlUrl</controlURL></service>$extraService</serviceList></device></root>"""

    @Test
    fun relativeControlUrlsResolveTheWayTvsWriteThem() {
        val loc = "http://192.168.1.9:7676/smp_2_"
        assertEquals("http://192.168.1.9:7676/upnp/control", DeviceDescription.parse(description("/upnp/control"), loc)?.avTransport?.controlUrl)
        // Samsung style: no slash, a colon in the first segment.
        assertEquals("http://192.168.1.9:7676/_urn:schemas-upnp-org:service:AVTransport_control", DeviceDescription.parse(description("_urn:schemas-upnp-org:service:AVTransport_control"), loc)?.avTransport?.controlUrl)
        assertEquals("http://192.168.1.9:7676/x", DeviceDescription.parse(description("x", "http://192.168.1.9:7676/"), loc)?.avTransport?.controlUrl)
        val d = DeviceDescription.parse(description("/c"), loc)!!
        assertEquals("TV & Co", d.friendlyName)
        assertEquals("uuid:abc", d.udn)
    }

    @Test
    fun aDescriptionCannotPointTheCallsAtAnotherMachine() {
        val loc = "http://192.168.1.9:7676/d.xml"
        assertNull("absolute control URL on another host", DeviceDescription.parse(description("http://10.0.0.99/ctl"), loc))
        assertEquals("URLBase on another host is ignored", "http://192.168.1.9:7676/ctl", DeviceDescription.parse(description("ctl", "http://10.0.0.99/"), loc)?.avTransport?.controlUrl)
        assertNull("file scheme", DeviceDescription.parse(description("file:///etc/passwd"), loc))
        assertNull("javascript", DeviceDescription.parse(description("javascript:alert(1)"), loc))
        assertNull("a location that is not http", DeviceDescription.parse(description("/c"), "ftp://192.168.1.9/d.xml"))
    }

    @Test
    fun aRendererWithoutAvTransportIsNoRow() {
        val xml = description("/c").replace("AVTransport", "Other")
        assertNull(DeviceDescription.parse(xml, "http://192.168.1.9:80/d.xml"))
        assertNull(DeviceDescription.parse(description("/c").replace("MediaRenderer", "MediaServer"), "http://192.168.1.9:80/d.xml"))
    }

    // SOAP

    @Test
    fun envelopeValuesAreEscapedAndComeBackIntact() {
        val nasty = "http://192.168.1.2:8000/t/video.mp4?a=1&b=<2>&q=\"x\"&c=%26 'y'"
        val xml = Soap.envelope("urn:schemas-upnp-org:service:AVTransport:1", "SetAVTransportURI", listOf("InstanceID" to "0", "CurrentURI" to nasty))
        val parsed = Soap.parse(200, xml) as SoapResult.Ok
        assertEquals(nasty, parsed["CurrentURI"])
        assertFalse("raw ampersand must not survive", "&b=" in xml)
        try {
            Soap.envelope("t", "Bad Action", emptyList())
            fail()
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun faultsAreReadWhateverTheStatusText() {
        val body = """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><s:Fault><faultcode>s:Client</faultcode><detail><UPnPError xmlns="urn:schemas-upnp-org:control-1-0"><errorCode> 716 </errorCode><errorDescription>Resource not found</errorDescription></UPnPError></detail></s:Fault></s:Body></s:Envelope>"""
        assertEquals(SoapResult.Fault(500, 716, "Resource not found"), Soap.parse(500, body))
        assertEquals(SoapResult.Fault(500, null, null), Soap.parse(500, "<html>oops</html>"))
        assertTrue(Soap.parse(200, "not xml") is SoapResult.Failed)
    }

    // DIDL

    @Test
    fun didlRoundTripsAnAwkwardTitleAndAddress() {
        val title = "Tom & Jerry <1> \"x\" 'y' \u0001 😀"
        val url = "http://192.168.1.2:8000/tok/video.mp4?start=5&x=a%26b"
        val didl = Didl.item(title, url, "video/mp4", DlnaFeatures(), subtitleUrl = "http://192.168.1.2:8000/tok/subtitle.srt&", durationMs = 3_723_004, sizeBytes = 99)
        val item = Didl.parse(didl)!!
        assertEquals("Tom & Jerry <1> \"x\" 'y'  😀", item.title)
        assertEquals(url, item.resources[0].url)
        assertTrue(item.resources[0].protocolInfo.startsWith("http-get:*:video/mp4:DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000"))
        assertEquals("http://192.168.1.2:8000/tok/subtitle.srt&", item.resources[1].url)
        assertEquals("http-get:*:text/srt:*", item.resources[1].protocolInfo)
        assertTrue("duration=\"1:02:03.004\"" in didl)
        // And through the SOAP body, the second escape, the way a renderer receives it.
        val soap = Soap.envelope("urn:schemas-upnp-org:service:AVTransport:1", "SetAVTransportURI", listOf("CurrentURIMetaData" to didl))
        assertEquals(didl, (Soap.parse(200, soap) as SoapResult.Ok)["CurrentURIMetaData"])
    }

    @Test
    fun featuresFieldsFollowTheServing() {
        assertEquals("DLNA.ORG_OP=00;DLNA.ORG_CI=1;DLNA.ORG_FLAGS=${DlnaFeatures.FLAGS}", DlnaFeatures(rangeSeek = false, converted = true).field())
        assertTrue(DlnaFeatures(profile = "AVC_MP4_HP_HD_AAC").field().startsWith("DLNA.ORG_PN=AVC_MP4_HP_HD_AAC;"))
        assertFalse("a profile that could end the field is dropped", "PN" in DlnaFeatures(profile = "A;DLNA.ORG_OP=11").field())
        assertEquals("http-get:*:video/mp4:DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=${DlnaFeatures.FLAGS}", Didl.protocolInfo("video/mp4;x:y", DlnaFeatures()).replace("video/mp4xy", "video/mp4"))
    }

    // Sink list

    @Test
    fun emptyOrMissingSinkListAssumesTheSafeSet() {
        for (s in listOf(null, "", "  ", ",,,", "garbage", "http-get:*")) assertEquals(SinkCaps.SAFE, SinkCaps.parse(s))
        assertEquals(setOf(Container.Mp4), SinkCaps.SAFE.containers)
        assertEquals(Support.No, SinkCaps.SAFE.caps.hevc)
        assertEquals(Support.No, SinkCaps.SAFE.caps.matroska)
    }

    @Test
    fun sinkProfilesAndMimesMapToCaps() {
        val caps = SinkCaps.parse("http-get:*:video/mp4:DLNA.ORG_PN=HEVC_MP4_MAIN_UHD_AAC,http-get:*:video/x-matroska:*,http-get:*:audio/x-ac3:*,http-get:*:audio/mpeg:*")
        assertEquals(Support.Yes, caps.caps.hevc)
        assertEquals(Support.Yes, caps.caps.uhd)
        assertEquals(Support.Yes, caps.caps.matroska)
        assertEquals(Support.Yes, caps.caps.dolbyPassthrough)
        assertEquals(setOf(Container.Mp4, Container.Matroska), caps.containers)
        val audioOnly = SinkCaps.parse("http-get:*:audio/mpeg:*,http-get:*:audio/flac:*")
        assertFalse("a speaker is not a TV", audioOnly.takesVideo)
    }

    @Test
    fun aHugeSinkListIsCapped() {
        val sink = (1..50_000).joinToString(",") { "http-get:*:video/x-fake$it:*" }
        assertTrue(SinkCaps.parse(sink).declared)
    }

    @Test
    fun hlsIsDroppedForDlna() {
        val plan = CastPlan(listOf(CastRoute.Hls(null, emptyList()), CastRoute.Remux(null)), null)
        assertEquals(listOf<CastRoute>(CastRoute.Remux(null)), plan.forDlna().routes)
        val onlyHls = CastPlan(listOf(CastRoute.Hls(null, emptyList())), null).forDlna()
        assertFalse(onlyHls.castable)
        assertEquals(CastRefusal.Container, onlyHls.refusal)
        val direct = CastPlan(listOf(CastRoute.Direct("video/mp4")), null)
        assertTrue(direct.forDlna() === direct)
    }

    // Times and actions

    @Test
    fun timesInEveryStyleParse() {
        assertEquals(30_000L, UpnpTime.parseMs("0:00:30"))
        assertEquals(5_000L, UpnpTime.parseMs("00:00:05"))
        assertEquals(1_016L, UpnpTime.parseMs("0:00:01.016"))
        assertEquals(60_500L, UpnpTime.parseMs("+00:01:00.5"))
        assertEquals(3_723_004L, UpnpTime.parseMs("1:02:03.004"))
        assertEquals(1_000L, UpnpTime.parseMs(" 0:00:01 "))
        for (bad in listOf(null, "", "NOT_IMPLEMENTED", "abc", "-0:00:05", "0:61:00", "0:00:61", "1:2", "99999999:00:00")) {
            assertNull("\"$bad\"", UpnpTime.parseMs(bad))
        }
        assertEquals("01:02:03", UpnpTime.format(3_723_999))
        assertEquals("00:00:00", UpnpTime.format(-5))
    }

    @Test
    fun actionsLists() {
        assertEquals(TransportActions(play = true, pause = false, stop = true, seek = true), TransportActions.parse("Play,Stop,Seek"))
        assertTrue(TransportActions.parse("X_DLNA_SeekTime")!!.seek)
        assertFalse(TransportActions.parse("Stop,Pause")!!.seek)
        assertTrue(TransportActions.parse("PLAY, STOP , pause")!!.pause)
        assertNull(TransportActions.parse(""))
        assertNull(TransportActions.parse(null))
        assertTrue(TransportActions.parse("X_DLNA_PS=1/16\\,1/8,Seek")!!.seek)
    }

    // The place on the TV

    @Test
    fun placeRunsWhilePlayingAndHoldsWhilePaused() {
        val p = DlnaPlayback(120_000)
        p.loaded(0, 0)
        assertEquals(10_000L, p.update("PLAYING", "OK", 10_000, 120_000, 10_000).positionMs)
        assertEquals(15_000L, p.update("PLAYING", "OK", null, null, 15_000).positionMs)
        assertEquals(ReceiverState.Paused, p.update("PAUSED_PLAYBACK", "OK", 15_500, null, 16_000).state)
        assertEquals(15_500L, p.update("PAUSED_PLAYBACK", "OK", null, null, 40_000).positionMs)
        assertEquals(ReceiverState.Buffering, p.update("TRANSITIONING", "OK", null, null, 41_000).state)
    }

    @Test
    fun aSeekIsBelievedUntilTheTvGetsThereAndStepsBackAreIgnored() {
        val p = DlnaPlayback(300_000)
        p.loaded(0, 0)
        p.update("PLAYING", "OK", 60_000, null, 60_000)
        p.seeking(200_000, 61_000)
        // Rygel answers zero straight after a Seek.
        assertEquals(200_000L, p.update("PLAYING", "OK", 0, null, 61_500).positionMs)
        assertEquals(200_000L, p.update("PLAYING", "OK", 60_900, null, 62_000).positionMs)
        assertEquals(201_000L, p.update("PLAYING", "OK", 201_000, null, 63_000).positionMs)
        // Re-buffering steps back 20 s: the place does not.
        assertEquals(204_000L, p.update("PLAYING", "OK", 181_000, null, 66_000).positionMs)
        // A seek the TV never reaches is given up on.
        p.seeking(250_000, 70_000)
        assertEquals(250_000L, p.update("PLAYING", "OK", 205_000, null, 72_000).positionMs)
        assertEquals(205_000L + 1000, p.update("PLAYING", "OK", 206_000, null, 80_000).positionMs)
    }

    @Test
    fun whyItStoppedIsToldApart() {
        fun stoppedAfter(setup: (DlnaPlayback) -> Unit): IdleReason? {
            val p = DlnaPlayback(100_000)
            p.loaded(0, 0)
            setup(p)
            return p.update("STOPPED", "OK", 0, null, 50_000).idleReason
        }
        assertEquals("ran to the end", IdleReason.Finished, stoppedAfter { it.update("PLAYING", "OK", 99_000, null, 40_000) })
        assertEquals("stopped on the remote", IdleReason.Interrupted, stoppedAfter { it.update("PLAYING", "OK", 40_000, null, 40_000) })
        assertEquals("stopped here", IdleReason.Cancelled, stoppedAfter { it.update("PLAYING", "OK", 40_000, null, 40_000); it.stoppedHere() })
        assertNull("never played: still loading", stoppedAfter { })
        val p = DlnaPlayback(100_000)
        p.loaded(0, 0)
        assertEquals(IdleReason.Error, p.update("STOPPED", "ERROR_OCCURRED", 0, null, 1000).idleReason)
        // And the place survives a stopped player's zero.
        val q = DlnaPlayback(100_000)
        q.loaded(0, 0)
        q.update("PLAYING", "OK", 42_000, null, 42_000)
        assertEquals(42_000L, q.update("STOPPED", "OK", 0, null, 43_000).positionMs)
        assertEquals(IdleReason.Interrupted, q.update("NO_MEDIA_PRESENT", "OK", null, null, 44_000).idleReason)
    }

    @Test
    fun aStoppedAnswerAfterOurPauseIsReadAsPaused() {
        val p = DlnaPlayback(100_000)
        p.loaded(0, 0)
        p.update("PLAYING", "OK", 30_000, null, 30_000)
        p.pausedHere()
        val m = p.update("STOPPED", "OK", 30_500, null, 31_000)
        assertEquals(ReceiverState.Paused, m.state)
        assertEquals(30_500L, m.positionMs)
        p.playedHere()
        assertEquals(ReceiverState.Idle, p.update("STOPPED", "OK", 0, null, 32_000).state)
        // An error is an error even then.
        p.pausedHere()
        assertEquals(IdleReason.Error, p.update("STOPPED", "ERROR_OCCURRED", 0, null, 33_000).idleReason)
    }

    @Test
    fun lengthComesFromTheTvWhenItKnowsAndFromTheProbeWhenNot() {
        val p = DlnaPlayback(90_000)
        assertEquals(90_000L, p.durationMs)
        p.update("PLAYING", "OK", 1000, null, 1000)
        assertEquals(90_000L, p.durationMs)
        p.update("PLAYING", "OK", 2000, 0, 2000)
        assertEquals("zero is not a length", 90_000L, p.durationMs)
        p.update("PLAYING", "OK", 3000, 91_500, 3000)
        assertEquals(91_500L, p.durationMs)
    }

    @Test
    fun srtIsNumberedWithCommas() {
        val srt = WebVtt.writeSrt(listOf(WebVtt.Cue(2000, 3500, "two --> x"), WebVtt.Cue(0, 1000, "one\n\nmore"), WebVtt.Cue(5000, 4000, "bad"), WebVtt.Cue(6000, 7000, "  ")))
        assertEquals("1\n00:00:00,000 --> 00:00:01,000\none\nmore\n\n2\n00:00:02,000 --> 00:00:03,500\ntwo -> x\n\n", srt)
        assertEquals("", WebVtt.writeSrt(emptyList()))
    }
}
