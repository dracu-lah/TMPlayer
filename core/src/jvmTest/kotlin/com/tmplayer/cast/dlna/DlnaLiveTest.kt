package com.tmplayer.cast.dlna

import com.tmplayer.cast.CastResource
import com.tmplayer.cast.FileCastSource
import com.tmplayer.cast.ReceiverMedia
import com.tmplayer.cast.ReceiverState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Against a real renderer on the real network, by hand: skipped unless `DLNA_LIVE_IFACE` is this
 * machine's LAN address and `DLNA_LIVE_MP4` a small H.264 MP4. Start gmrender-resurrect or Rygel
 * first (`scripts/dlna/Containerfile.*`), then:
 *
 *     DLNA_LIVE_IFACE=192.168.1.20 DLNA_LIVE_MP4=/path/a.mp4 ./gradlew :core:jvmTest --tests '*DlnaLiveTest*'
 *
 * `DLNA_LIVE_NAME` picks a renderer by name when several answer. It sends real multicast, then
 * loads, plays, seeks, pauses and stops the video on the renderer, with real time passing.
 */
class DlnaLiveTest {

    @Test
    fun discoverCastSeekPauseStop() {
        val iface = System.getenv("DLNA_LIVE_IFACE")
        val file = System.getenv("DLNA_LIVE_MP4")?.let(::File)
        assumeTrue("set DLNA_LIVE_IFACE and DLNA_LIVE_MP4 to run against a real renderer", iface != null && file?.isFile == true)
        val me = InetAddress.getByName(iface)
        val wanted = System.getenv("DLNA_LIVE_NAME")

        val found = LinkedBlockingQueue<DlnaDevice>()
        val discovery = DlnaDiscovery(
            object : DlnaDiscovery.Listener {
                override fun onFound(device: DlnaDevice) { println("found ${device.name} at ${device.address} ${device.description.manufacturer} ${device.description.modelName}"); found += device }
                override fun onGone(udn: String) = Unit
                override fun onFinished(found: Int) = println("discovery finished with $found")
            },
            interfaces = { com.tmplayer.cast.LanAddress.candidates().filter { it.address == me } },
        )
        discovery.start()
        var device: DlnaDevice? = null
        val until = System.nanoTime() + 15_000_000_000L
        while (device == null && System.nanoTime() < until) {
            device = found.poll(1, TimeUnit.SECONDS)?.takeIf { wanted == null || it.name.contains(wanted, ignoreCase = true) }
        }
        discovery.stop()
        assertTrue("no renderer found on $iface", device != null)
        device!!

        val closed = LinkedBlockingQueue<DlnaClosed>()
        val medias = LinkedBlockingQueue<ReceiverMedia>()
        val cast = DlnaCast(device, me, onClosed = { closed += it })
        val sink = cast.renderer.sinkCaps()
        println("sink: declared=${sink.declared} containers=${sink.containers} caps=${sink.caps}")
        val video = DlnaVideo("TMPlayer live test", CastResource.Ranged(FileCastSource(file!!), "video/mp4"), "video/mp4", durationMs = 30_000, sizeBytes = file.length())
        assertEquals(DlnaLoad.Loaded, cast.open(video, 0) { println("  $it"); medias += it })

        fun nextPlaying(): ReceiverMedia {
            val end = System.nanoTime() + 8_000_000_000L
            while (System.nanoTime() < end) {
                val m = medias.poll(1, TimeUnit.SECONDS) ?: continue
                if (m.state == ReceiverState.Playing) return m
            }
            error("never playing")
        }
        val first = nextPlaying()
        Thread.sleep(2_500)
        val later = nextPlaying()
        assertTrue("the place moves on while playing: ${first.positionMs} then ${later.positionMs}", later.positionMs > first.positionMs)
        println("canSeek=${cast.canSeek} actions=${cast.renderer.actions}")
        if (cast.canSeek) {
            println("seek -> ${cast.seek(20_000)}")
            Thread.sleep(2_500)
        }
        println("pause=${cast.pause()}")
        Thread.sleep(2_500)
        println("state after pause: ${cast.renderer.media}")
        println("play=${cast.play()}")
        Thread.sleep(2_500)
        cast.stop()
        assertEquals(DlnaClosed.Stopped, closed.poll(3, TimeUnit.SECONDS))
        assertTrue(!cast.server.running)
    }
}
