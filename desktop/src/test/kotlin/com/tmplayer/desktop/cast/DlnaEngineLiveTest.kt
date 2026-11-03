package com.tmplayer.desktop.cast

import com.tmplayer.cast.LanAddress
import com.tmplayer.cast.dlna.DlnaDevice
import com.tmplayer.cast.dlna.DlnaDiscovery
import com.tmplayer.desktop.player.OpenPrefs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.openani.mediamp.source.UriMediaData
import java.io.File
import java.net.InetAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The desktop cast engine against a real DLNA renderer on the real network, by hand: skipped unless
 * `DLNA_LIVE_IFACE` is this machine's LAN address and `DLNA_LIVE_MP4` an H.264 MP4. Start
 * gmrender-resurrect or Rygel first (`scripts/dlna/Containerfile.*`), then:
 *
 *     DLNA_LIVE_IFACE=192.168.1.20 DLNA_LIVE_MP4=/path/a.mp4 ./gradlew :desktop:test --rerun --tests '*DlnaEngineLiveTest*'
 *
 * `DLNA_LIVE_NAME` picks a renderer by name when several answer. Real time passes: about 40 seconds.
 */
class DlnaEngineLiveTest {

    @Test
    fun `the engine plays, seeks, pauses and stops a video on a DLNA renderer`() {
        val iface = System.getenv("DLNA_LIVE_IFACE")
        val file = System.getenv("DLNA_LIVE_MP4")?.let(::File)
        assumeTrue("set DLNA_LIVE_IFACE and DLNA_LIVE_MP4 to run against a real renderer", iface != null && file?.isFile == true)
        val me = InetAddress.getByName(iface)
        val wanted = System.getenv("DLNA_LIVE_NAME")

        val found = LinkedBlockingQueue<DlnaDevice>()
        val discovery = DlnaDiscovery(
            object : DlnaDiscovery.Listener {
                override fun onFound(device: DlnaDevice) { println("found ${device.name} at ${device.address}"); found += device }
                override fun onGone(udn: String) = Unit
                override fun onFinished(found: Int) = Unit
            },
            interfaces = { LanAddress.candidates().filter { it.address == me } },
        )
        discovery.start()
        var device: DlnaDevice? = null
        val until = System.nanoTime() + 15_000_000_000L
        while (device == null && System.nanoTime() < until) {
            device = found.poll(1, TimeUnit.SECONDS)?.takeIf { wanted == null || it.name.contains(wanted, ignoreCase = true) }
        }
        discovery.stop()
        assertTrue("no renderer found on $iface", device != null)

        val target = CastTarget.of(device!!)
        val receiver = Receiver.of(target)
        runBlocking { receiver.connect() }
        println("caps ${receiver.caps}")
        val engine = CastPlaybackEngine(receiver) { CastTitle("TMPlayer live test") }
        try {
            runBlocking { engine.open(UriMediaData(file!!.absolutePath), 0, OpenPrefs()) }
            waitFor("plays") { engine.state.value.playing && engine.state.value.positionMs > 0 }
            println("playing at ${engine.state.value.positionMs} of ${engine.state.value.durationMs}")
            assertTrue(engine.state.value.error == null)

            engine.seekTo(engine.state.value.durationMs / 2)
            waitFor("seeks") { engine.state.value.positionMs >= engine.state.value.durationMs / 2 - 1_000 && !engine.state.value.seekPending }
            println("sought to ${engine.state.value.positionMs}")

            engine.pause()
            waitFor("pauses") { !engine.state.value.playing }
            val at = engine.state.value.positionMs
            Thread.sleep(2_500)
            assertTrue("a pause holds the place", kotlin.math.abs(engine.state.value.positionMs - at) < 1_500)

            engine.play()
            waitFor("plays again") { engine.state.value.playing }
            engine.stop()
            waitFor("stops") { !engine.state.value.playing }
            assertEquals(null, engine.state.value.error)
        } finally {
            engine.close()
            receiver.close()
        }
    }

    private fun waitFor(what: String, ms: Long = 20_000, check: () -> Boolean) {
        val end = System.nanoTime() + ms * 1_000_000
        while (!check() && System.nanoTime() < end) Thread.sleep(100)
        assertTrue("timed out waiting for it to $what", check())
    }
}
