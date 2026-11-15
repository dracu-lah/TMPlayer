package com.tmplayer.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.Socket
import kotlin.random.Random

/** The link to the receiver: the platforms' answers, the meter, the held rung and what the plan makes of it. */
class LinkSpeedTest {

    private class Clock(var now: Long = 0) : () -> Long {
        override fun invoke(): Long = now
    }

    /** The link rate the ladder hands the plan for a rung: the lowest one the planner turns back into that rung. */
    private fun linkFor(kbps: Int) = (kbps / TranscodePlanner.LINK_SHARE).toInt() + 1

    private val rung1080 = linkFor(8_000)
    private val rung720 = linkFor(4_500)
    private val rung360 = linkFor(1_000)

    // ---- parsers --------------------------------------------------------------------------------

    @Test
    fun `sysfs speed is megabits, minus one and junk are none`() {
        assertEquals(1000.0, LinkSpeeds.sysfsMbps("1000\n")!!, 0.0)
        assertNull(LinkSpeeds.sysfsMbps("-1\n"))
        assertNull(LinkSpeeds.sysfsMbps(""))
        assertNull(LinkSpeeds.sysfsMbps(null))
        assertNull(LinkSpeeds.sysfsMbps("fast"))
        assertNull(LinkSpeeds.sysfsMbps("NaN"))
        assertNull(LinkSpeeds.sysfsMbps("999999999"))
    }

    @Test
    fun `iw link output gives the lower of tx and rx`() {
        val out = """
            Connected to aa:bb:cc:dd:ee:ff (on wlp3s0)
            	SSID: Home
            	freq: 5180
            	signal: -52 dBm
            	rx bitrate: 866.7 MBit/s VHT-MCS 9 80MHz short GI VHT-NSS 2
            	tx bitrate: 650.0 MBit/s VHT-MCS 7 80MHz short GI VHT-NSS 2
        """.trimIndent()
        assertEquals(650.0, LinkSpeeds.iwMbps(out)!!, 0.0)
        assertNull(LinkSpeeds.iwMbps("Not connected."))
        assertNull(LinkSpeeds.iwMbps(null))
        assertNull(LinkSpeeds.iwMbps("tx bitrate: 0.0 MBit/s"))
    }

    @Test
    fun `windows adapter speed is bits per second on a line`() {
        assertEquals(866.7, LinkSpeeds.windowsBpsToMbps("866700000\r\n")!!, 0.01)
        assertEquals(1000.0, LinkSpeeds.windowsBpsToMbps("\r\n1000000000\r\n")!!, 0.01)
        assertNull(LinkSpeeds.windowsBpsToMbps(""))
        assertNull(LinkSpeeds.windowsBpsToMbps("Get-NetAdapter : No MSFT_NetAdapter objects found"))
        assertNull(LinkSpeeds.windowsBpsToMbps("0"))
    }

    @Test
    fun `ifconfig media names the cable speed, wifi names none`() {
        assertEquals(1000.0, LinkSpeeds.ifconfigMbps("en5: flags=8863<UP>\n\tmedia: autoselect (1000baseT <full-duplex>)\n\tstatus: active")!!, 0.0)
        assertEquals(10_000.0, LinkSpeeds.ifconfigMbps("\tmedia: autoselect (10Gbase-T <full-duplex>)")!!, 0.0)
        assertNull(LinkSpeeds.ifconfigMbps("en0: flags=8863<UP>\n\tmedia: autoselect\n\tstatus: active"))
        assertNull(LinkSpeeds.ifconfigMbps(null))
    }

    @Test
    fun `a wifi rate counts for half and a cable for nine tenths, nonsense for nothing`() {
        assertEquals(433_350, LinkSpeeds.wifiEffectiveKbps(866.7))
        assertEquals(900_000, LinkSpeeds.wiredEffectiveKbps(1000.0))
        for (bad in listOf(0.0, -1.0, 0.4, Double.NaN, Double.POSITIVE_INFINITY, 1e12)) {
            assertNull("$bad", LinkSpeeds.wifiEffectiveKbps(bad))
            assertNull("$bad", LinkSpeeds.wiredEffectiveKbps(bad))
        }
    }

    @Test
    fun `hostile text never throws`() {
        val rnd = Random(7)
        val samples = listOf("", "\u0000".repeat(50), "x".repeat(100_000), "tx bitrate: " + "9".repeat(5000) + " MBit/s", "media: (" + "9".repeat(3000) + "base")
        for (text in samples + List(200) { String(CharArray(rnd.nextInt(0, 400)) { rnd.nextInt(0, 0xFFFF).toChar() }) }) {
            LinkSpeeds.sysfsMbps(text)
            LinkSpeeds.iwMbps(text)
            LinkSpeeds.windowsBpsToMbps(text)
            LinkSpeeds.ifconfigMbps(text)
            LinkSpeeds.looksWireless(text)
        }
    }

    // ---- meter ----------------------------------------------------------------------------------

    @Test
    fun `a window of bytes written in a second is its megabits`() {
        val meter = LinkMeter(Clock())
        // 2 MiB in 0.4 s of write time: 41.9 Mb/s.
        val burst = meter.burst()
        repeat(32) { burst.add(64 * 1024, 12_500_000) }
        assertEquals(41_943.0, meter.bestKbps()!!.toDouble(), 5.0)
    }

    @Test
    fun `less than a window is not a sample, and the best of several wins`() {
        val meter = LinkMeter(Clock())
        meter.burst().add(1_000_000, 1_000_000)
        assertNull(meter.bestKbps())
        meter.record(2_000_000, 1_000_000_000) // 16 Mb/s
        meter.record(2_000_000, 400_000_000) // 40 Mb/s
        meter.record(2_000_000, 4_000_000_000) // a paused TV: 4 Mb/s
        assertEquals(40_000, meter.bestKbps())
    }

    @Test
    fun `samples older than five minutes lapse and nonsense is dropped`() {
        val clock = Clock()
        val meter = LinkMeter(clock)
        meter.record(2_000_000, 1_000_000_000)
        clock.now = LinkMeter.TTL_MS + 1
        assertNull(meter.bestKbps())
        meter.record(0, 1_000)
        meter.record(1_000, 0)
        meter.record(-5, -5)
        meter.record(Long.MAX_VALUE / 16, 1) // past 100 Gb/s
        assertNull(meter.bestKbps())
        val burst = meter.burst()
        burst.add(-1, 5)
        burst.add(10, -5)
        assertNull(meter.bestKbps())
    }

    // ---- the held rung --------------------------------------------------------------------------

    @Test
    fun `a clear link gives 1080p and one that cannot carry 8 Mb is held under it`() {
        val ladder = LinkLadder(Clock())
        assertNull(ladder.hold(null))
        assertEquals(rung1080, ladder.hold(40_000))
        val low = LinkLadder(Clock())
        // 9 Mb/s: 60 % is 5.4, which carries 4.5 Mb/s (720p).
        assertEquals(rung720, low.hold(9_000))
        // Nothing carries 1 Mb/s at this link: the bottom rung.
        assertEquals(rung360, LinkLadder(Clock()).hold(500))
    }

    @Test
    fun `a link wobbling around a rung does not flap`() {
        val clock = Clock()
        val ladder = LinkLadder(clock)
        val first = ladder.hold(14_000) // carries 8 Mb/s (8.4 budget)
        assertEquals(rung1080, first)
        // Dips just under (budget 7.8 against 8): inside the margin, stays on 1080p.
        for (raw in listOf(13_000, 13_300, 12_900, 13_100)) {
            clock.now += 10_000
            assertEquals(rung1080, ladder.hold(raw))
        }
        // A clear loss (budget 6): down to 720p at once.
        clock.now += 10_000
        assertEquals(rung720, ladder.hold(10_000))
        // Back over 8 Mb/s of budget but not 8 x 1.25: stays on 720p, however long.
        for (raw in listOf(14_000, 15_000, 16_000)) {
            clock.now += 120_000
            assertEquals(rung720, ladder.hold(raw))
        }
    }

    @Test
    fun `going up needs a margin and a minute on the rung`() {
        val clock = Clock()
        val ladder = LinkLadder(clock)
        assertEquals(rung720, ladder.hold(9_000))
        clock.now += 30_000
        // Plenty of link, but the rung has only held half a minute.
        assertEquals(rung720, ladder.hold(40_000))
        clock.now += 31_000
        assertEquals(rung1080, ladder.hold(40_000))
    }

    @Test
    fun `no reading keeps the held rung`() {
        val ladder = LinkLadder(Clock())
        ladder.hold(9_000)
        assertEquals(rung720, ladder.hold(null))
        assertEquals(rung720, ladder.hold(0))
        ladder.reset()
        assertNull(ladder.hold(null))
    }

    // ---- the estimate ---------------------------------------------------------------------------

    @Test
    fun `measured beats the platform, the platform beats nothing, and a platform that throws is nothing`() {
        val clock = Clock()
        val estimator = LinkEstimator(LinkSpeedSource { 9_000 }, LinkMeter(clock), LinkLadder(clock))
        assertEquals(LinkEstimate(rung720, LinkSource.Platform), estimator.estimate())
        estimator.meter.record(4_000_000, 400_000_000) // 80 Mb/s
        clock.now += LinkLadder.HOLD_UP_MS + 1
        val up = estimator.estimate()
        assertEquals(LinkSource.Measured, up.source)
        assertEquals(rung1080, up.kbps)

        assertEquals(LinkEstimate(null, LinkSource.Unknown), LinkEstimator(null).estimate())
        assertEquals(LinkEstimate(null, LinkSource.Unknown), LinkEstimator(LinkSpeedSource { error("no wifi") }).estimate())
        assertEquals(LinkEstimate(null, LinkSource.Unknown), LinkEstimator(LinkSpeedSource { -5 }).estimate())
    }

    @Test
    fun `a new receiver forgets the old one`() {
        val clock = Clock()
        val estimator = LinkEstimator(null, LinkMeter(clock), LinkLadder(clock))
        estimator.meter.record(2_000_000, 2_000_000_000) // 8 Mb/s
        assertNotNull(estimator.estimate().kbps)
        estimator.reset()
        assertEquals(LinkEstimate(null, LinkSource.Unknown), estimator.estimate())
    }

    // ---- what the plan makes of it --------------------------------------------------------------

    private val probe = MediaProbe(
        Container.Matroska, 60_000,
        VideoTrack(1, Codecs.HEVC, 3840, 2160, tenBit = false),
        listOf(AudioTrack(2, Codecs.AAC, channels = 2, isDefault = true)),
    )
    private val ability = Ffmpeg.ability(setOf("libx264", "aac", "ac3"))!!

    private fun target(link: Int?): TranscodeTarget {
        val plan = CastFormats.plan(probe, ReceiverCaps.LEGACY, null, false, ability, link)
        return plan.routes.filterIsInstance<CastRoute.Transcode>().single().target
    }

    @Test
    fun `the held link sets the rung the plan makes`() {
        assertEquals(1080 to 8_000, target(null).let { it.height to it.videoKbps })
        val clock = Clock()
        val ladder = LinkLadder(clock)
        assertEquals(720 to 4_500, target(ladder.hold(9_000)).let { it.height to it.videoKbps })
        assertEquals(1080 to 8_000, target(LinkLadder(Clock()).hold(30_000)).let { it.height to it.videoKbps })
        assertEquals(360 to 1_000, target(LinkLadder(Clock()).hold(800)).let { it.height to it.videoKbps })
    }

    @Test
    fun `a stream that starts later is planned again with the link and the wrappers as they are`() {
        val route = CastFormats.plan(probe, ReceiverCaps.LEGACY, null, false, ability, null).routes.filterIsInstance<CastRoute.Transcode>().single()
        assertEquals(8_000, route.target.videoKbps)
        val slower = TranscodePlanner.retarget(route, probe, ReceiverCaps.LEGACY, ability, LinkLadder(Clock()).hold(9_000), null)
        assertEquals(720, slower.target.height)
        assertEquals(route.audioTrack, slower.audioTrack)
        assertEquals(route.reason, slower.reason)
        val ts = TranscodePlanner.retarget(route, probe, ReceiverCaps.LEGACY, ability, null, setOf(Container.MpegTs))
        assertEquals(TranscodeContainer.MpegTs, ts.target.container)
        // A soundtrack no longer in the file: no sound, not a crash.
        val gone = TranscodePlanner.retarget(route.copy(audioTrack = 99), probe, ReceiverCaps.LEGACY, ability, null, null)
        assertEquals(TranscodeAudio.None, gone.target.audio)
    }

    // ---- the server feeds the meter -------------------------------------------------------------

    @Test
    fun `a file served as it is gives the meter a sample`() {
        val meter = LinkMeter()
        val server = CastServer(InetAddress.getLoopbackAddress(), meter = meter).apply { start() }
        try {
            val source = PatternSource(5L * 1024 * 1024)
            val url = server.publish("video.mp4", CastResource.Ranged(source, "video/mp4"))
            val path = "/" + url.substringAfter("//").substringAfter('/')
            Socket(InetAddress.getLoopbackAddress(), server.boundPort).use { s ->
                s.getOutputStream().write("GET $path HTTP/1.1\r\nHost: t\r\nConnection: close\r\n\r\n".toByteArray())
                s.getOutputStream().flush()
                val input = s.getInputStream()
                val buffer = ByteArray(65536)
                var total = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                }
                assertTrue(total > 5L * 1024 * 1024)
            }
            val kbps = meter.bestKbps()
            assertNotNull("a 5 MiB body is two windows", kbps)
            assertTrue("$kbps", kbps!! > 100)
        } finally {
            server.stop()
        }
    }
}
