package com.tmplayer.cast.dlna

import com.tmplayer.cast.BytesCastSource
import com.tmplayer.cast.CastResource
import com.tmplayer.cast.IdleReason
import com.tmplayer.cast.LanAddress
import com.tmplayer.cast.ReceiverMedia
import com.tmplayer.cast.ReceiverState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The whole flow on loopback against [FakeRenderer]: discover, describe, load, play, seek, poll,
 * byebye, lost, a stranger turned away. The renderer's clock only moves when a test says so, and
 * the polls are called by the tests, so nothing here waits on a timer.
 */
class DlnaFlowTest {

    private val loopback = InetAddress.getLoopbackAddress()
    private val fake = FakeRenderer()
    private var now = 0L
    private val closes = LinkedBlockingQueue<DlnaClosed>()
    private val medias = ArrayList<ReceiverMedia>()
    private var cast: DlnaCast? = null
    private var discovery: DlnaDiscovery? = null

    private val bytes = ByteArray(300_000) { (it * 7).toByte() }

    @After
    fun tearDown() {
        discovery?.stop()
        cast?.close()
        fake.close()
    }

    private fun freeUdpPort(): Int = DatagramSocket(0, loopback).use { it.localPort }

    private class Heard : DlnaDiscovery.Listener {
        val found = LinkedBlockingQueue<DlnaDevice>()
        val gone = LinkedBlockingQueue<String>()
        val finished = LinkedBlockingQueue<Int>()
        override fun onFound(device: DlnaDevice) { found += device }
        override fun onGone(udn: String) { gone += udn }
        override fun onFinished(found: Int) { finished += found }
    }

    private fun discovery(heard: Heard, notifyPort: Int, quietStopMs: Long = 600): DlnaDiscovery =
        DlnaDiscovery(
            heard,
            interfaces = { listOf(LanAddress.Candidate("lo", loopback, 8)) },
            searchTarget = InetSocketAddress(loopback, fake.udpPort),
            notifyPort = notifyPort,
            allowLoopback = true,
            repeatMs = 150,
            quietStopMs = quietStopMs,
            giveUpMs = 3_000,
        ).also { discovery = it }

    private fun discover(): DlnaDevice {
        val heard = Heard()
        discovery(heard, freeUdpPort()).start()
        return heard.found.poll(5, TimeUnit.SECONDS) ?: error("nothing found")
    }

    private fun video(mime: String = "video/mp4", subtitle: String? = null) = DlnaVideo(
        title = "A & B", resource = CastResource.Ranged(BytesCastSource(bytes), mime), mime = mime,
        durationMs = 120_000, sizeBytes = bytes.size.toLong(), subtitleSrt = subtitle,
    )

    private fun newCast(device: DlnaDevice, idleLimitMs: Long = 30 * 60_000L): DlnaCast =
        DlnaCast(device, loopback, clock = { now }, idleLimitMs = idleLimitMs, autoPoll = false, onClosed = { closes += it }).also { cast = it }

    /** Time passes on the TV and on the phone's clock together. */
    private fun pass(ms: Long) {
        now += ms
        fake.advance(ms)
    }

    // Discovery

    @Test
    fun aRendererIsFoundOnceDescribedAndDiscoveryEndsByItself() {
        val heard = Heard()
        discovery(heard, freeUdpPort()).start()
        val device = heard.found.poll(5, TimeUnit.SECONDS)!!
        assertEquals("Living room TV", device.name)
        assertEquals(fake.udn, device.udn)
        assertEquals(loopback, device.address)
        assertEquals("Fake & Sons", device.description.manufacturer)
        assertTrue(device.description.avTransport.controlUrl.endsWith("/ctl/AVTransport"))
        assertTrue("the larger icon", device.description.iconUrl!!.endsWith("/icon120.png"))
        assertEquals("quiet for 600 ms after the one device", 1, heard.finished.poll(5, TimeUnit.SECONDS))
        assertFalse(discovery!!.isRunning)
        assertEquals("one row however often it answered", 1, discovery!!.devices().size)
        assertTrue("searched more than once", fake.searches.get() >= 2)
        assertNull("no second onFound for the repeated answers", heard.found.poll(300, TimeUnit.MILLISECONDS))
    }

    @Test
    fun nothingFoundEndsWithAnEmptyList() {
        val port = fake.udpPort
        fake.close()
        val heard = Heard()
        DlnaDiscovery(
            heard, interfaces = { listOf(LanAddress.Candidate("lo", loopback, 8)) },
            searchTarget = InetSocketAddress(loopback, port), listenForNotify = false,
            allowLoopback = true, repeatMs = 100, giveUpMs = 400,
        ).also { discovery = it }.start()
        assertEquals(0, heard.finished.poll(5, TimeUnit.SECONDS))
        assertFalse(discovery!!.isRunning)
    }

    @Test
    fun aLocationOutsideThisNetworkIsNeverFetched() {
        // Loopback is refused unless a test allows it, and so is a LOCATION on another host than the sender.
        val heard = Heard()
        DlnaDiscovery(
            heard, interfaces = { listOf(LanAddress.Candidate("lo", loopback, 8)) },
            searchTarget = InetSocketAddress(loopback, fake.udpPort), listenForNotify = false,
            allowLoopback = false, repeatMs = 100, giveUpMs = 700,
        ).also { discovery = it }.start()
        assertEquals(0, heard.finished.poll(5, TimeUnit.SECONDS))
        assertTrue(heard.found.isEmpty())
    }

    @Test
    fun byeByeTakesTheRowAway() {
        val heard = Heard()
        val port = freeUdpPort()
        discovery(heard, port, quietStopMs = 60_000).start()
        heard.found.poll(5, TimeUnit.SECONDS)!!
        fake.answersSearches = false
        repeat(3) { fake.byebye(port); Thread.sleep(50) }
        assertEquals(fake.udn, heard.gone.poll(5, TimeUnit.SECONDS))
        assertTrue(discovery!!.devices().isEmpty())
    }

    // The cast

    @Test
    fun aCastLoadsPlaysSeeksAndReportsThePlace() {
        val c = newCast(discover())
        val srt = "1\n00:00:01,000 --> 00:00:02,000\nhello\n"
        assertEquals(DlnaLoad.Loaded, c.open(video(subtitle = srt), 0) { medias += it })

        // What the TV saw when it looked at the address.
        assertEquals(1, fake.setUris.size)
        assertTrue(fake.setUris[0].startsWith(c.server.urlFor("video.mp4")))
        assertEquals(200, fake.headStatus)
        val features = fake.headHeaders["contentfeatures.dlna.org"]!!
        assertTrue(features, "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000" in features)
        assertEquals("Streaming", fake.headHeaders["transfermode.dlna.org"])
        assertEquals(c.server.urlFor("subtitle.srt"), fake.headHeaders["captioninfo.sec"])
        assertEquals("a range was asked for and given", 206, fake.getStatus)
        assertEquals("bytes 0-99/300000", fake.getContentRange)
        assertEquals(srt, fake.fetchedSubtitle)
        val item = Didl.parse(fake.didl)!!
        assertEquals("A & B", item.title)
        assertTrue(item.resources.any { it.protocolInfo == "http-get:*:text/srt:*" })
        assertEquals("PLAYING", fake.state)

        pass(10_000)
        val first = c.renderer.pollOnce()!!
        assertEquals(ReceiverState.Playing, first.state)
        assertEquals(10_000L, first.positionMs)
        assertTrue("Seek was listed after the first poll", c.canSeek)
        assertEquals(120_000L, c.renderer.durationMs)

        assertEquals(SeekOutcome.Done, c.seek(90_000))
        assertEquals(90_000L, fake.positionMs)
        pass(1_000)
        assertEquals(91_000L, c.renderer.pollOnce()!!.positionMs)

        assertTrue(c.pause())
        assertEquals(ReceiverState.Paused, c.renderer.pollOnce()!!.state)
        pass(5_000)
        assertEquals("a pause stops the clock", 91_000L, c.renderer.pollOnce()!!.positionMs)
        assertEquals(DlnaLoad.Loaded, c.play())
        pass(40_000)
        val end = c.renderer.pollOnce()!!
        assertEquals("the TV ran to the end and stopped", ReceiverState.Idle, end.state)
        assertEquals(IdleReason.Finished, end.idleReason)
        assertEquals(medias.last(), end)
    }

    @Test
    fun aTypeTheTvRefusesComesBackAsRefusedAndTheNextRouteGoesOnTheSameServer() {
        val c = newCast(discover())
        val refused = c.open(video("video/x-matroska"), 0) { }
        assertEquals(DlnaLoad.Refused(LoadRefusal.BadType, 714, "Illegal MIME-type"), refused)
        assertTrue("the server stays up for the next route", c.server.running)
        assertEquals(DlnaLoad.Loaded, c.open(video("video/mp4"), 0) { })
        assertEquals(2, fake.setUris.size)
        assertEquals("PLAYING", fake.state)
    }

    @Test
    fun aRendererBusyInAnotherAppIsStoppedAndAskedAgain() {
        fake.busyOnce = true
        val c = newCast(discover())
        assertEquals(DlnaLoad.Loaded, c.open(video(), 0) { })
        assertEquals(listOf("SetAVTransportURI", "Stop", "SetAVTransportURI", "Play"), fake.calls.filter { it in setOf("SetAVTransportURI", "Stop", "Play") })
    }

    @Test
    fun anAddressTheTvCannotFetchIsSaidPlainly() {
        val c = newCast(discover())
        c.server.stop() // the TV's HEAD finds nothing listening
        val r = c.renderer.load("http://127.0.0.1:1/x.mp4", Didl.item("t", "http://127.0.0.1:1/x.mp4", "video/mp4", DlnaFeatures()))
        assertTrue(r is DlnaLoad.Refused && r.reason == LoadRefusal.NotFound && r.code == 716)
    }

    @Test
    fun aSeekTheTvDoesNotListIsNotAskedAndNotFaked() {
        fake.allowSeek = false
        val c = newCast(discover())
        c.open(video(), 0) { }
        c.renderer.pollOnce()
        assertFalse(c.canSeek)
        assertEquals(SeekOutcome.NotAllowed, c.seek(50_000))
        assertFalse("Seek" in fake.calls)
    }

    @Test
    fun aSeekTheTvRefusesIsReported() {
        val c = newCast(discover())
        c.open(video(), 0) { }
        c.renderer.pollOnce()
        fake.allowSeek = false // lists Seek no more after the next state change, but still answers 710 now
        assertEquals(SeekOutcome.Refused, c.seek(50_000))
    }

    @Test
    fun aStartPastZeroIsAppliedOnceThePlayerPlays() {
        val c = newCast(discover())
        c.open(video(), 45_000) { medias += it }
        assertEquals(0L, fake.positionMs)
        pass(500)
        c.renderer.pollOnce()
        assertEquals(45_000L, fake.positionMs)
        assertEquals(45_000L, c.renderer.placeMs)
        pass(1_000)
        c.renderer.pollOnce()
        assertEquals(46_000L, medias.last().positionMs)
    }

    @Test
    fun playAfterALongPauseLoadsTheVideoAgainAtThePlace() {
        val c = newCast(discover())
        c.open(video(), 0) { }
        pass(30_000)
        c.renderer.pollOnce()
        assertTrue(c.pause())
        c.renderer.pollOnce()
        fake.playFaultOnce = 716
        assertEquals(DlnaLoad.Loaded, c.play())
        assertEquals("loaded again", 2, fake.setUris.size)
        assertEquals("PLAYING", fake.state)
        c.renderer.pollOnce()
        assertEquals("at the place it was", 30_000L, fake.positionMs)
    }

    @Test
    fun aStreamSeeksByLoadingFromTheNewStartAndPositionsAreShifted() {
        val c = newCast(discover())
        val stream = DlnaVideo("S", CastResource.Stream("video/mp4") { q, out -> out.write("start=${q["start"]}".toByteArray()) }, "video/mp4", 120_000)
        assertEquals(DlnaLoad.Loaded, c.open(stream, 0) { medias += it })
        assertTrue(fake.setUris[0].endsWith("/video.mp4?start=0"))
        val features = fake.headHeaders["contentfeatures.dlna.org"]!!
        assertTrue(features, "DLNA.ORG_OP=00;DLNA.ORG_CI=1" in features)
        assertEquals(SeekOutcome.Done, c.seek(30_000))
        assertTrue(fake.setUris[1].endsWith("/video.mp4?start=30000"))
        pass(2_000)
        c.renderer.pollOnce()
        assertEquals("the TV counts from the start of the new stream", 32_000L, medias.last().positionMs)
        assertTrue("a stream can always be sought", c.canSeek)
    }

    @Test
    fun volumeGoesThroughRenderingControlWhenThereIsOne() {
        val device = discover()
        val r = DlnaRenderer(device)
        assertTrue(r.hasVolume)
        assertEquals(30, r.volume())
        assertTrue(r.setVolume(55))
        assertEquals(55, fake.volume)
        assertTrue(r.setMuted(true))
        assertTrue(fake.muted)
        val without = DlnaRenderer(device.copy(description = device.description.copy(renderingControl = null)))
        assertFalse(without.hasVolume)
        assertNull(without.volume())
        assertFalse(without.setVolume(10))
    }

    @Test
    fun theSinkListBecomesCaps() {
        val caps = DlnaRenderer(discover()).sinkCaps()
        assertTrue(caps.declared && caps.takesVideo)
        assertEquals(com.tmplayer.cast.Support.Yes, caps.caps.matroska)
    }

    // Safety and loss

    @Test
    fun aStrangerOnAnotherAddressIsAnswered403() {
        val c = newCast(discover())
        c.open(video(), 0) { }
        val other = try {
            java.net.Socket().apply { bind(InetSocketAddress(InetAddress.getByName("127.0.0.2"), 0)) }
        } catch (e: Exception) {
            org.junit.Assume.assumeNoException("no second loopback address here", e)
            return
        }
        other.use { s ->
            s.connect(InetSocketAddress(loopback, c.server.boundPort), 2000)
            s.soTimeout = 3000
            s.getOutputStream().write("GET /${c.server.token}/video.mp4 HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
            val first = s.getInputStream().bufferedReader().readLine()
            assertEquals("HTTP/1.1 403 Forbidden", first)
        }
    }

    @Test
    fun threeSilentPollsLoseTheRendererAndClosePort() {
        val c = newCast(discover())
        c.open(video(), 0) { }
        assertTrue(c.server.running)
        fake.powerOff()
        c.renderer.pollOnce(); c.renderer.pollOnce()
        assertTrue("two misses are not a loss", c.isOpen)
        c.renderer.pollOnce()
        assertEquals(DlnaClosed.Lost, closes.poll(2, TimeUnit.SECONDS))
        assertFalse(c.isOpen)
        assertFalse("port closed", c.server.running)
    }

    @Test
    fun byeByeFromTheTvAlsoEndsTheCast() {
        val c = newCast(discover())
        c.open(video(), 0) { }
        c.renderer.markGone()
        assertEquals(DlnaClosed.Lost, closes.poll(2, TimeUnit.SECONDS))
        assertFalse(c.server.running)
    }

    @Test
    fun aLongPauseWithNoRequestClosesThePort() {
        val c = newCast(discover(), idleLimitMs = 30 * 60_000L)
        c.open(video(), 0) { }
        pass(1_000)
        c.renderer.pollOnce()
        assertTrue(c.pause())
        now += 29 * 60_000L
        assertEquals(ReceiverState.Paused, c.renderer.pollOnce()!!.state)
        assertTrue(c.isOpen)
        now += 2 * 60_000L
        c.renderer.pollOnce()
        assertEquals(DlnaClosed.Idle, closes.poll(2, TimeUnit.SECONDS))
        assertFalse(c.server.running)
    }

    @Test
    fun stopStopsTheTvAndClosesEverything() {
        val c = newCast(discover())
        c.open(video(), 0) { }
        c.stop()
        assertEquals(DlnaClosed.Stopped, closes.poll(2, TimeUnit.SECONDS))
        assertEquals("STOPPED", fake.state)
        assertFalse(c.server.running)
        assertEquals("a second stop does nothing", null, run { c.stop(); closes.poll(200, TimeUnit.MILLISECONDS) })
        assertEquals(DlnaLoad.Failed("The cast is closed"), c.open(video(), 0) { })
    }
}
