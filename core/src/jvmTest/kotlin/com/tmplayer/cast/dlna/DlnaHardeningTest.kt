package com.tmplayer.cast.dlna

import com.tmplayer.cast.IdleReason
import com.tmplayer.cast.ReceiverState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * The real-TV pitfalls audited on 2026-11-23 (DLNA "did not work" for testers, no details), one test
 * per fix: what a TV's parser, HTTP stack or transport does that the two software renderers here
 * never did. See `scripts/dlna/README.md` for the real-renderer check that runs the same client.
 */
class DlnaHardeningTest {

    // DIDL and XML

    @Test
    fun aTitleWithApostrophesAmpersandsAndOtherScriptsSurvivesBothEscapes() {
        val title = "Tom's & Jerry's <Best> \"Cut\" Привет 日本語 🎬 é"
        val didl = Didl.item(title, "http://192.168.1.2:5000/t/video.mp4?a=1&b=2", "video/mp4", DlnaFeatures())
        assertFalse("an apostrophe goes as itself, not as &apos; some TVs show", didl.contains("&apos;"))
        assertTrue(didl.contains("Tom's"))
        val back = Didl.parse(didl)!!
        // No markup character reaches a renderer that unescapes once too often (Rygel does).
        assertEquals("Tom's + Jerry's (Best) \"Cut\" Привет 日本語 \uD83C\uDFAC é", back.title)
        assertEquals("http://192.168.1.2:5000/t/video.mp4?a=1&b=2", back.resources.single().url)
        // Inside the SOAP body it is escaped once more, and comes back whole.
        val soap = Soap.envelope("urn:schemas-upnp-org:service:AVTransport:1", "SetAVTransportURI", listOf("CurrentURIMetaData" to didl))
        assertEquals(didl, (Soap.parse(200, soap) as SoapResult.Ok)["CurrentURIMetaData"])
    }

    @Test
    fun aLongTitleIsCutWithoutSplittingACharacter() {
        val title = "a".repeat(199) + "🎬" + "tail"
        val back = Didl.parse(Didl.item(title, "http://192.168.1.2/v.mp4", "video/mp4", DlnaFeatures()))!!.title!!
        assertEquals("a".repeat(199), back)
        assertTrue(Didl.parse(Didl.item("x".repeat(5_000), "http://192.168.1.2/v.mp4", "video/mp4", DlnaFeatures()))!!.title!!.length <= 200)
    }

    @Test
    fun didlDeclaresOnlyTheNamespacesItUses() {
        val plain = Didl.item("A", "http://192.168.1.2/v.mp4", "video/mp4", DlnaFeatures())
        assertFalse(plain.contains("xmlns:dlna"))
        assertFalse("no subtitle, no sec:", plain.contains("xmlns:sec"))
        val withSub = Didl.item("A", "http://192.168.1.2/v.mp4", "video/mp4", DlnaFeatures(), subtitleUrl = "http://192.168.1.2/s.srt")
        assertTrue(withSub.contains("xmlns:sec") && withSub.contains("sec:CaptionInfoEx"))
        assertNotNull(Xml.parse(withSub, Xml.DIDL_MAX))
    }

    @Test
    fun theFallbackMetadataIsPlainAndThenNone() {
        val full = Didl.item("Tom's", "http://192.168.1.2/v.mkv", "video/x-matroska", DlnaFeatures(profile = "AVC_MKV"), subtitleUrl = "http://192.168.1.2/s.srt", durationMs = 1000)
        val (plain, none) = Didl.fallbacks(full)
        assertEquals("", none)
        val p = Didl.parse(plain)!!
        assertEquals("Tom's", p.title)
        assertEquals(listOf(DidlResource("http://192.168.1.2/v.mkv", "http-get:*:video/x-mkv:*")), p.resources)
        assertFalse(plain.contains("sec:"))
        assertEquals(listOf(""), Didl.fallbacks("not xml"))
    }

    @Test
    fun aByteOrderMarkOrABlankLineBeforeTheXmlLineStillParses() {
        val xml = "<?xml version=\"1.0\"?><root><a>1</a></root>"
        assertNotNull(Xml.parse("﻿" + xml, 1000))
        assertNotNull(Xml.parse("\r\n  " + xml, 1000))
        assertNotNull(Xml.parse(Xml.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + xml.toByteArray()), 1000))
    }

    @Test
    fun aDocumentIsReadInTheCharsetItNames() {
        val latin = "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><r>Télé du salon</r>".toByteArray(Charsets.ISO_8859_1)
        assertTrue(Xml.decode(latin).contains("Télé du salon"))
        val byHeader = "<r>Télé</r>".toByteArray(Charsets.ISO_8859_1)
        assertTrue(Xml.decode(byHeader, "text/xml; charset=\"iso-8859-1\"").contains("Télé"))
        assertTrue(Xml.decode("<r>Télé</r>".toByteArray()).contains("Télé"))
        assertTrue("an unknown charset is UTF-8", Xml.decode("<?xml version='1.0' encoding='x-nope'?><r>é</r>".toByteArray()).contains("é"))
        assertEquals("<r/>", Xml.decode(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "<r/>".toByteArray(Charsets.UTF_16LE)))
    }

    // Device description

    @Test
    fun servicesAreFoundByTypeAtAnyVersionOrWithNoneAndByServiceId() {
        fun desc(type: String, id: String) = """<?xml version="1.0"?><root xmlns="urn:schemas-upnp-org:device-1-0"><device>
            <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType><friendlyName>TV</friendlyName><UDN>uuid:1</UDN>
            <serviceList><service><serviceType>$type</serviceType><serviceId>$id</serviceId><controlURL>/av</controlURL></service></serviceList>
            </device></root>"""
        val at = "http://192.168.1.5:9000/d.xml"
        assertNotNull(DeviceDescription.parse(desc("urn:schemas-upnp-org:service:AVTransport:3", "x"), at))
        assertNotNull("no version", DeviceDescription.parse(desc("urn:schemas-upnp-org:service:AVTransport", "x"), at))
        val vendor = DeviceDescription.parse(desc("urn:vendor-com:service:TvTransport:1", "urn:upnp-org:serviceId:AVTransport"), at)!!
        assertEquals("the calls go to the type it gave", "urn:vendor-com:service:TvTransport:1", vendor.avTransport.type)
        assertNull("AVTransportX is not AVTransport", DeviceDescription.parse(desc("urn:schemas-upnp-org:service:AVTransportX:1", "x"), at))
    }

    // The renderer's calls

    private class Script : SoapTransport {
        val calls = CopyOnWriteArrayList<Pair<String, Map<String, String>>>()
        var answer: (String, Map<String, String>) -> SoapResult = { _, _ -> SoapResult.Ok(emptyMap()) }
        var state = "STOPPED"
        var status = "OK"

        override fun call(service: DlnaService, action: String, args: List<Pair<String, String>>): SoapResult {
            val map = args.toMap()
            calls += action to map
            if (action == "GetTransportInfo") return SoapResult.Ok(mapOf("CurrentTransportState" to state, "CurrentTransportStatus" to status))
            if (action == "GetPositionInfo") return SoapResult.Ok(mapOf("RelTime" to "0:00:00", "TrackDuration" to "0:01:00"))
            if (action == "GetCurrentTransportActions") return SoapResult.Ok(mapOf("Actions" to "Play,Pause,Stop,Seek"))
            return answer(action, map)
        }

        fun actions(vararg only: String) = calls.map { it.first }.filter { only.isEmpty() || it in only }
    }

    private fun device() = DlnaDevice(
        DeviceDescription(
            udn = "uuid:1", friendlyName = "TV", manufacturer = null, modelName = null, iconUrl = null,
            avTransport = DlnaService("urn:schemas-upnp-org:service:AVTransport:1", "http://192.168.1.50/av"),
            renderingControl = null, connectionManager = null,
        ),
        InetAddress.getByName("192.168.1.50"), "http://192.168.1.50/d.xml",
    )

    private val didl = Didl.item("A", "http://192.168.1.2/v.mp4", "video/mp4", DlnaFeatures())

    private fun fault(code: Int?) = SoapResult.Fault(500, code, "no")

    @Test
    fun metadataTheTvWillNotTakeIsSentPlainerAndTheWorkingOneIsKept() {
        for (code in listOf(714, 402, 501, 720, null)) {
            val s = Script()
            var sets = 0
            s.answer = { a, _ -> if (a == "SetAVTransportURI" && sets++ == 0) fault(code) else SoapResult.Ok(emptyMap()) }
            val r = DlnaRenderer(device(), s, sleep = {})
            assertEquals("code $code", DlnaLoad.Loaded, r.load("http://192.168.1.2/v.mp4", didl))
            val metas = s.calls.filter { it.first == "SetAVTransportURI" }.map { it.second.getValue("CurrentURIMetaData") }
            assertEquals(2, metas.size)
            assertTrue(metas[1].contains("http-get:*:video/mp4:*"))
            assertEquals(listOf("SetAVTransportURI", "SetAVTransportURI", "Play"), s.actions("SetAVTransportURI", "Play", "Stop"))
        }
    }

    @Test
    fun aRefusalThatIsNotAboutMetadataIsNotRetried() {
        val s = Script()
        s.answer = { a, _ -> if (a == "SetAVTransportURI") fault(716) else SoapResult.Ok(emptyMap()) }
        val r = DlnaRenderer(device(), s, sleep = {})
        assertEquals(DlnaLoad.Refused(LoadRefusal.NotFound, 716, "no"), r.load("http://192.168.1.2/v.mp4", didl))
        assertEquals(1, s.actions("SetAVTransportURI").size)
    }

    @Test
    fun everyMetadataRefusedReportsTheFirstRefusal() {
        val s = Script()
        var n = 0
        s.answer = { a, _ -> if (a == "SetAVTransportURI") fault(if (n++ == 0) 714 else 501) else SoapResult.Ok(emptyMap()) }
        val r = DlnaRenderer(device(), s, sleep = {})
        val out = r.load("http://192.168.1.2/v.mp4", didl)
        assertEquals(LoadRefusal.BadType, (out as DlnaLoad.Refused).reason)
        assertEquals(listOf(""), s.calls.filter { it.first == "SetAVTransportURI" }.drop(2).map { it.second.getValue("CurrentURIMetaData") })
        assertEquals(DlnaFailure.BadType, DlnaFailure.of(out))
    }

    @Test
    fun aTvStillPlayingIsStoppedBeforeTheNextAddress() {
        val s = Script()
        val r = DlnaRenderer(device(), s, sleep = {})
        s.state = "PLAYING"
        r.pollOnce()
        s.calls.clear()
        assertEquals(DlnaLoad.Loaded, r.load("http://192.168.1.2/v.mp4", didl))
        assertEquals(listOf("Stop", "SetAVTransportURI", "Play"), s.actions("Stop", "SetAVTransportURI", "Play"))
        // Stopped, or never asked: no Stop first.
        val quiet = Script()
        DlnaRenderer(device(), quiet, sleep = {}).apply { pollOnce() }.load("http://192.168.1.2/v.mp4", didl)
        assertEquals(listOf("SetAVTransportURI", "Play"), quiet.actions("Stop", "SetAVTransportURI", "Play"))
    }

    @Test
    fun aPlayTooSoonAfterTheLoadIsAskedAgain() {
        val s = Script()
        var plays = 0
        s.answer = { a, _ -> if (a == "Play" && plays++ == 0) fault(701) else SoapResult.Ok(emptyMap()) }
        val slept = CopyOnWriteArrayList<Long>()
        val r = DlnaRenderer(device(), s, sleep = { slept += it })
        assertEquals(DlnaLoad.Loaded, r.load("http://192.168.1.2/v.mp4", didl))
        assertEquals(2, s.actions("Play").size)
        assertEquals(listOf(700L), slept)
    }

    @Test
    fun aTvThatStartsByItselfAndRefusesPlayIsLoaded() {
        val s = Script()
        s.answer = { a, _ -> if (a == "Play") fault(701) else SoapResult.Ok(emptyMap()) }
        s.state = "TRANSITIONING"
        val r = DlnaRenderer(device(), s, sleep = {})
        assertEquals(DlnaLoad.Loaded, r.load("http://192.168.1.2/v.mp4", didl))
        s.state = "STOPPED"
        assertTrue(DlnaRenderer(device(), s, sleep = {}).load("http://192.168.1.2/v.mp4", didl) is DlnaLoad.Refused)
    }

    @Test
    fun aSeekModeTheTvDoesNotHaveGoesAsAbsTimeAndIsRemembered() {
        val s = Script()
        s.answer = { a, args -> if (a == "Seek" && args["Unit"] == "REL_TIME") fault(710) else SoapResult.Ok(emptyMap()) }
        val r = DlnaRenderer(device(), s, sleep = {})
        assertEquals(SeekOutcome.Done, r.seek(65_000))
        assertEquals(SeekOutcome.Done, r.seek(70_000))
        assertEquals(listOf("REL_TIME", "ABS_TIME", "ABS_TIME"), s.calls.filter { it.first == "Seek" }.map { it.second["Unit"] })
        assertEquals("00:01:05", s.calls.first { it.first == "Seek" }.second["Target"])
        assertEquals("0", s.calls.first { it.first == "Seek" }.second["InstanceID"])
    }

    @Test
    fun aSeekWhileTheTvChangesStateIsAskedAgainOnceAndABadTargetIsNotRetriedInTheOtherUnit() {
        val s = Script()
        var seeks = 0
        s.answer = { a, _ -> if (a == "Seek" && seeks++ == 0) fault(701) else SoapResult.Ok(emptyMap()) }
        assertEquals(SeekOutcome.Done, DlnaRenderer(device(), s, sleep = {}).seek(10_000))
        assertEquals(2, s.actions("Seek").size)

        val bad = Script()
        bad.answer = { a, _ -> if (a == "Seek") fault(711) else SoapResult.Ok(emptyMap()) }
        assertEquals(SeekOutcome.Refused, DlnaRenderer(device(), bad, sleep = {}).seek(10_000))
        assertEquals(1, bad.actions("Seek").size)
    }

    @Test
    fun pollsBackOffWhileTheTvDoesNotAnswer() {
        val s = object : SoapTransport {
            override fun call(service: DlnaService, action: String, args: List<Pair<String, String>>) = SoapResult.Failed("down")
        }
        val slept = CopyOnWriteArrayList<Long>()
        val lost = java.util.concurrent.CountDownLatch(1)
        val r = DlnaRenderer(device(), s, sleep = { slept += it })
        r.startPolling(onMedia = {}, onLost = { lost.countDown() })
        assertTrue(lost.await(5, java.util.concurrent.TimeUnit.SECONDS))
        r.close()
        assertEquals(listOf(1_000L, 2_000L), slept.take(2))
    }

    // A TV told to play that never does

    @Test
    fun stoppedWithoutEverPlayingTurnsIntoAFailureAfterTenSeconds() {
        val p = DlnaPlayback(60_000)
        p.loaded(0, 0)
        p.playedHere(0)
        assertNull("settling", p.update("STOPPED", "OK", 0, null, 5_000).idleReason)
        assertEquals(ReceiverState.Buffering, p.update("TRANSITIONING", "OK", 0, null, 30_000).state)
        assertEquals(IdleReason.Error, p.update("STOPPED", "OK", 0, null, 31_000).idleReason)
        // Never told to play (a load without autoplay): STOPPED is just waiting.
        val q = DlnaPlayback(60_000)
        q.loaded(0, 0)
        assertNull(q.update("STOPPED", "OK", 0, null, 60_000).idleReason)
    }

    @Test
    fun theFailureSaysWhetherTheTvEverCameForTheVideo() {
        var now = 0L
        fun cast(): Pair<Script, DlnaRenderer> {
            val s = Script()
            val r = DlnaRenderer(device(), s, clock = { now }, sleep = {})
            now = 0
            assertEquals(DlnaLoad.Loaded, r.load("http://192.168.1.2/v.mp4", didl))
            return s to r
        }
        cast().let { (_, r) ->
            now = 11_000
            assertEquals(IdleReason.Error, r.pollOnce()?.idleReason)
            assertEquals(DlnaFailure.NeverFetched, r.failure)
        }
        cast().let { (_, r) ->
            r.noteFetched()
            now = 11_000
            r.pollOnce()
            assertEquals(DlnaFailure.StoppedBeforePlaying, r.failure)
            // The next load starts clean.
            r.load("http://192.168.1.2/v.mp4", didl)
            assertNull(r.failure)
        }
        cast().let { (s, r) ->
            s.state = "PLAYING"
            now = 1_000
            r.pollOnce()
            s.state = "STOPPED"
            s.status = "ERROR_OCCURRED"
            now = 5_000
            assertEquals(IdleReason.Error, r.pollOnce()?.idleReason)
            assertEquals(DlnaFailure.StoppedWithError, r.failure)
        }
    }

    @Test
    fun aTvThatSaysPlayingButNeverCameForTheVideoIsAFailureWhenTheServerIsWatched() {
        var now = 0L
        val s = Script()
        s.state = "PLAYING"
        val r = DlnaRenderer(device(), s, clock = { now }, sleep = {}, watchFetches = true)
        assertEquals(DlnaLoad.Loaded, r.load("http://192.168.1.2/v.mp4", didl))
        now = 10_000
        assertEquals(ReceiverState.Playing, r.pollOnce()?.state)
        now = 16_000
        val m = r.pollOnce()!!
        assertEquals(IdleReason.Error, m.idleReason)
        assertEquals(DlnaFailure.NeverFetched, r.failure)
        // It came after all: no failure any more.
        r.noteFetched()
        assertEquals(ReceiverState.Playing, r.pollOnce()?.state)
        assertNull(r.failure)

        // A TV that came for it at once is never flagged, and nor is a renderer nobody watches.
        val fetching = DlnaRenderer(device(), s, clock = { now }, sleep = {}, watchFetches = true)
        now = 0
        fetching.load("http://192.168.1.2/v.mp4", didl)
        fetching.noteFetched()
        now = 60_000
        assertEquals(ReceiverState.Playing, fetching.pollOnce()?.state)
        val unwatched = DlnaRenderer(device(), s, clock = { now }, sleep = {})
        now = 0
        unwatched.load("http://192.168.1.2/v.mp4", didl)
        now = 60_000
        assertEquals(ReceiverState.Playing, unwatched.pollOnce()?.state)
    }

    @Test
    fun eachFailureHasItsOwnWords() {
        val all = listOf(
            DlnaFailure.NoAnswer, DlnaFailure.BadType, DlnaFailure.CouldNotOpen, DlnaFailure.Busy, DlnaFailure.Refused(718),
            DlnaFailure.NeverFetched, DlnaFailure.StoppedBeforePlaying, DlnaFailure.StoppedWithError,
        ).map { it.text() }
        assertEquals("no two steps say the same", all.size, all.toSet().size)
        assertTrue(all.none { it.isBlank() || it.startsWith("cast.") })
        assertTrue(DlnaFailure.Refused(718).text().contains("718"))
        assertEquals(DlnaFailure.NoAnswer, DlnaFailure.of(DlnaLoad.Failed("timeout")))
        assertEquals(DlnaFailure.Refused(null), DlnaFailure.of(DlnaLoad.Refused(LoadRefusal.Other, null, null)))
        assertNull(DlnaFailure.of(DlnaLoad.Loaded))
    }

    // SOAP over a real socket

    /** One HTTP exchange on loopback: [reply] gets the request head and body and writes the raw answer. */
    private fun serveOnce(reply: (head: String, body: String) -> ByteArray): Pair<String, CopyOnWriteArrayList<String>> {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val seen = CopyOnWriteArrayList<String>()
        thread(isDaemon = true) {
            server.use { ss ->
                ss.accept().use { c ->
                    val input = c.getInputStream()
                    val reader = BufferedReader(InputStreamReader(input, Charsets.ISO_8859_1))
                    val head = StringBuilder()
                    var length = 0
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        head.append(line).append('\n')
                        if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
                    }
                    val body = CharArray(length)
                    var read = 0
                    while (read < length) {
                        val n = reader.read(body, read, length - read)
                        if (n < 0) break
                        read += n
                    }
                    seen += head.toString()
                    seen += String(body, 0, read)
                    c.getOutputStream().apply { write(reply(head.toString(), String(body, 0, read))); flush() }
                }
            }
        }
        return "http://127.0.0.1:${server.localPort}/ctl" to seen
    }

    private fun envelope(inner: String) =
        """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>$inner</s:Body></s:Envelope>"""

    @Test
    fun theRequestCarriesTheHeadersTvsCheckAndAChunkedAnswerIsRead() {
        val answer = envelope("""<u:GetTransportInfoResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1"><CurrentTransportState>PLAYING</CurrentTransportState></u:GetTransportInfoResponse>""").toByteArray()
        val (url, seen) = serveOnce { _, _ ->
            val half = answer.size / 2
            ("HTTP/1.1 200 OK\r\nContent-Type: text/xml; charset=\"utf-8\"\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n" +
                Integer.toHexString(half) + "\r\n").toByteArray() + answer.copyOfRange(0, half) +
                ("\r\n" + Integer.toHexString(answer.size - half) + "\r\n").toByteArray() + answer.copyOfRange(half, answer.size) + "\r\n0\r\n\r\n".toByteArray()
        }
        val r = SoapClient().call(DlnaService("urn:schemas-upnp-org:service:AVTransport:1", url), "GetTransportInfo", listOf("InstanceID" to "0"))
        assertEquals("PLAYING", (r as SoapResult.Ok)["CurrentTransportState"])
        val head = seen[0]
        assertTrue(head, head.startsWith("POST /ctl HTTP/1.1"))
        assertTrue(head, head.contains("SOAPAction: \"urn:schemas-upnp-org:service:AVTransport:1#GetTransportInfo\""))
        assertTrue(head, head.contains("Content-Type: text/xml; charset=\"utf-8\""))
        assertTrue(head, head.contains("Connection: close"))
        assertTrue(head, head.contains("User-Agent: ${Ssdp.USER_AGENT}"))
        assertTrue(seen[1], seen[1].contains("<InstanceID>0</InstanceID>"))
    }

    @Test
    fun aFaultIsReadFromA500OrA200AndAnHttp10AnswerWithNoLength() {
        val fault = envelope("""<s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail><UPnPError xmlns="urn:schemas-upnp-org:control-1-0"><errorCode>714</errorCode><errorDescription>Illegal MIME-type</errorDescription></UPnPError></detail></s:Fault>""")
        val (url, _) = serveOnce { _, _ -> "HTTP/1.1 500 Internal Server Error\r\nContent-Type: text/xml\r\nContent-Length: ${fault.toByteArray().size}\r\n\r\n$fault".toByteArray() }
        assertEquals(SoapResult.Fault(500, 714, "Illegal MIME-type"), SoapClient().call(DlnaService("urn:x:service:AVTransport:1", url), "SetAVTransportURI", emptyList()))
        val (url2, _) = serveOnce { _, _ -> "HTTP/1.0 200 OK\r\nContent-Type: text/xml\r\n\r\n$fault".toByteArray() }
        assertEquals(714, (SoapClient().call(DlnaService("urn:x:service:AVTransport:1", url2), "Play", emptyList()) as SoapResult.Fault).code)
    }
}
