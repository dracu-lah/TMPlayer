package com.tmplayer.cast

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** The transcode's driver, one-at-a-time slot, guard and cast server stream, on a fake engine. */
class TranscodeRunTest {

    private val probe = MediaProbe(Container.Mp4, 10_000, VideoTrack(1, Codecs.HEVC, 1920, 1080), listOf(AudioTrack(2, Codecs.AAC)))
    private val route = CastFormats.plan(probe, ReceiverCaps.LEGACY, transcoder = TranscodeAbility()).routes.single() as CastRoute.Transcode
    private val source = object : CastSource {
        override val length = 0L
        override fun open(position: Long): CastReader = throw IOException("Not read by the fake")
    }

    private fun job(startMs: Long = 0) = TranscodeJob(source, probe, route, startMs, "video")

    /** Writes [chunks] chunks of [chunk] bytes on its own thread, a second of video each, then ends. */
    private class FakeEngine(
        val chunks: Int = 5,
        val chunk: ByteArray = ByteArray(1000) { it.toByte() },
        val stepMs: Long = 0,
        val failWith: Throwable? = null,
        val hang: Boolean = false,
    ) : TranscodeEngine {
        val cancelled = AtomicBoolean(false)
        val started = CountDownLatch(1)
        @Volatile var thread: Thread? = null

        override fun start(job: TranscodeJob, out: OutputStream, made: (Long) -> Unit, done: (Throwable?) -> Unit) {
            thread = thread(name = "fake-engine") {
                started.countDown()
                try {
                    if (hang) {
                        while (!cancelled.get()) Thread.sleep(5)
                        done(IOException("cancelled"))
                        return@thread
                    }
                    for (i in 1..chunks) {
                        if (cancelled.get()) {
                            done(IOException("cancelled"))
                            return@thread
                        }
                        out.write(chunk)
                        made(i * 1_000_000L)
                        if (stepMs > 0) Thread.sleep(stepMs)
                    }
                    done(failWith)
                } catch (e: Throwable) {
                    done(e)
                }
            }
        }

        override fun cancel() = cancelled.set(true)
    }

    @Test
    fun `an engine's stream reaches the output and its time the progress`() {
        val engine = FakeEngine()
        val out = ByteArrayOutputStream()
        val times = ArrayList<Long>()
        EngineTranscoder({ engine }).write(job(), out) { times += it }
        assertEquals(5_000, out.size())
        assertEquals(listOf(1_000L, 2_000L, 3_000L, 4_000L, 5_000L), times)
    }

    @Test
    fun `an engine's failure comes out as an IOException`() {
        val t = EngineTranscoder({ FakeEngine(failWith = IllegalStateException("No encoder")) })
        try {
            t.write(job(), ByteArrayOutputStream()) {}
            fail("No failure")
        } catch (e: IOException) {
            assertEquals("No encoder", e.message)
        }
    }

    @Test
    fun `cancel stops a running engine and write returns`() {
        val engine = FakeEngine(hang = true)
        val t = EngineTranscoder({ engine })
        val returned = CountDownLatch(1)
        thread {
            runCatching { t.write(job(), ByteArrayOutputStream()) {} }
            returned.countDown()
        }
        assertTrue(engine.started.await(2, TimeUnit.SECONDS))
        t.cancel()
        assertTrue("write did not return", returned.await(3, TimeUnit.SECONDS))
        assertTrue(engine.cancelled.get())
    }

    @Test
    fun `an engine that ignores cancel is given up on`() {
        val stubborn = object : TranscodeEngine {
            override fun start(job: TranscodeJob, out: OutputStream, made: (Long) -> Unit, done: (Throwable?) -> Unit) = Unit
            override fun cancel() = Unit
        }
        val t = EngineTranscoder({ stubborn }, cancelWaitMs = 100)
        val returned = CountDownLatch(1)
        thread {
            runCatching { t.write(job(), ByteArrayOutputStream()) {} }
            returned.countDown()
        }
        Thread.sleep(100)
        t.cancel()
        assertTrue(returned.await(2, TimeUnit.SECONDS))
    }

    @Test
    fun `the slot runs one at a time, a new job stops the one before`() {
        val first = FakeEngine(hang = true)
        val engines = ArrayDeque(listOf<TranscodeEngine>(first, FakeEngine(chunks = 2)))
        val slot = TranscodeSlot({ EngineTranscoder({ engines.removeFirst() }) })
        val firstDone = CountDownLatch(1)
        thread {
            runCatching { slot.run(job(), ByteArrayOutputStream()) }
            firstDone.countDown()
        }
        assertTrue(first.started.await(2, TimeUnit.SECONDS))
        assertTrue(slot.running)
        slot.run(job(30_000), ByteArrayOutputStream())
        assertTrue(firstDone.await(3, TimeUnit.SECONDS))
        assertTrue(first.cancelled.get())
        val p = slot.progress.value!!
        // The progress is the newer job's, from its own start; the replaced one said nothing.
        assertEquals(30_000L, p.startMs)
        assertEquals(32_000L, p.positionMs)
        assertTrue(p.done)
        assertEquals(null, p.stop)
        assertFalse(slot.running)
    }

    @Test
    fun `too hot part way stops the transcode, said so`() {
        val hot = AtomicBoolean(false)
        val now = AtomicInteger(0)
        val engine = FakeEngine(chunks = 50, stepMs = 2)
        val slot = TranscodeSlot(
            factory = { EngineTranscoder({ engine }) },
            health = { if (hot.get()) DeviceHealth(thermal = DeviceHealth.Thermal.Severe) else DeviceHealth() },
            clock = { now.addAndGet(1_000).toLong() },
            checkEveryMs = 1_000,
        )
        val out = object : OutputStream() {
            var n = 0
            override fun write(b: Int) = Unit
            override fun write(b: ByteArray, off: Int, len: Int) {
                if (++n == 3) hot.set(true)
            }
        }
        try {
            slot.run(job(), out)
            fail("Not stopped")
        } catch (_: IOException) {
        }
        assertEquals(TranscodeStop.TooHot, slot.progress.value?.stop)
        assertTrue(engine.cancelled.get())
    }

    @Test
    fun `a low battery before the start does not start it`() {
        var made = 0
        val slot = TranscodeSlot({ EngineTranscoder({ made++; FakeEngine() }) }, health = { DeviceHealth(batteryPercent = 9, charging = false) })
        try {
            slot.run(job(), ByteArrayOutputStream())
            fail("Started")
        } catch (_: IOException) {
        }
        assertEquals(TranscodeStop.BatteryLow, slot.progress.value?.stop)
        assertEquals(0, made)
    }

    @Test
    fun `the receiver hanging up is told from the pipeline failing`() {
        val slot = TranscodeSlot({ EngineTranscoder({ FakeEngine() }) })
        val gone = object : OutputStream() {
            override fun write(b: Int) = throw IOException("Broken pipe")
            override fun write(b: ByteArray, off: Int, len: Int) = throw IOException("Broken pipe")
        }
        runCatching { slot.run(job(), gone) }
        assertEquals(TranscodeStop.ReceiverGone, slot.progress.value?.stop)
        val failing = TranscodeSlot({ EngineTranscoder({ FakeEngine(failWith = IOException("Decoder")) }) })
        runCatching { failing.run(job(), ByteArrayOutputStream()) }
        assertEquals(TranscodeStop.Failed, failing.progress.value?.stop)
        assertEquals("Decoder", failing.progress.value?.message)
    }

    @Test
    fun `the viewer's cancel is said as cancelled, and clear forgets it`() {
        val engine = FakeEngine(hang = true)
        val slot = TranscodeSlot({ EngineTranscoder({ engine }) })
        val done = CountDownLatch(1)
        thread {
            runCatching { slot.run(job(), ByteArrayOutputStream()) }
            done.countDown()
        }
        assertTrue(engine.started.await(2, TimeUnit.SECONDS))
        slot.cancel()
        assertTrue(done.await(3, TimeUnit.SECONDS))
        assertEquals(TranscodeStop.Cancelled, slot.progress.value?.stop)
        slot.clear()
        assertEquals(null, slot.progress.value)
    }

    @Test
    fun `slower than real time for long enough is flagged slow`() {
        val now = AtomicInteger(0)
        // Each second of video takes four on the clock.
        val slot = TranscodeSlot({ EngineTranscoder({ FakeEngine(chunks = 12) }) }, clock = { now.addAndGet(4_000).toLong() })
        slot.run(job(), ByteArrayOutputStream())
        val p = slot.progress.value!!
        assertTrue(p.slow)
        assertTrue(p.speed < 1f)
        assertEquals(12_000L, p.positionMs)
        // Made past the stated length: the bar stays full rather than past its end.
        assertEquals(1f, p.fraction!!, 0.0001f)
    }

    // ---- through the cast server ------------------------------------------------------------

    private val server = CastServer(InetAddress.getLoopbackAddress()).apply { start() }

    @After
    fun stop() = server.stop()

    @Test
    fun `a transcode route is served as a stream from the asked time`() {
        val chunk = ByteArray(4096) { (it * 7).toByte() }
        val starts = ArrayList<Long>()
        val slot = TranscodeSlot({
            object : Transcoder {
                override fun write(job: TranscodeJob, out: OutputStream, made: (Long) -> Unit) {
                    starts += job.startMs
                    repeat(3) { out.write(chunk); made(it * 1000L) }
                }
                override fun cancel() = Unit
            }
        })
        val streams = CastStreams(server, recovery = null, transcodes = slot)
        val pub = streams.publish(source, probe, route, 42_000, "video.mkv")
        assertFalse(pub.seekable)
        assertEquals(42_000L, pub.offsetMs)
        assertEquals("video/mp4", pub.mime)
        assertTrue(pub.url.endsWith(".mp4?t=42000"))
        val body = URL(pub.url).openStream().use { it.readBytes() }
        assertArrayEquals(chunk + chunk + chunk, body)
        assertEquals(listOf(42_000L), starts)
    }

    @Test
    fun `without a slot the transcode route is not published`() {
        try {
            CastStreams(server, recovery = null).publish(source, probe, route, 0, "video")
            fail("Published")
        } catch (_: IOException) {
        }
    }
}
