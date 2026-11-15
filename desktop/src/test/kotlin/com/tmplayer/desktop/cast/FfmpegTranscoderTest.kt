package com.tmplayer.desktop.cast

import com.tmplayer.cast.CastReader
import com.tmplayer.cast.CastRoute
import com.tmplayer.cast.CastSource
import com.tmplayer.cast.MediaProbe
import com.tmplayer.cast.TranscodeAudio
import com.tmplayer.cast.TranscodeJob
import com.tmplayer.cast.TranscodeReason
import com.tmplayer.cast.TranscodeTarget
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** The desktop's ffmpeg runner on fake processes: no ffmpeg is needed. */
class FfmpegTranscoderTest {

    /** A process that prints [stdout] and [stderr] and exits with [exit]; [hang] keeps its output open until destroyed. */
    private class FakeProcess(stdout: ByteArray, stderr: String, private val exit: Int, private val hang: Boolean = false) : Process() {
        private val pipe = PipedOutputStream()
        private val outIn = PipedInputStream(pipe, 1 shl 20)
        val destroyed = CountDownLatch(1)

        init {
            thread(isDaemon = true) {
                runCatching {
                    pipe.write(stdout)
                    if (hang) destroyed.await(10, TimeUnit.SECONDS)
                    pipe.close()
                }
            }
        }

        private val err = ByteArrayInputStream(stderr.toByteArray())
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = outIn
        override fun getErrorStream(): InputStream = err
        override fun waitFor(): Int = exit
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = true
        override fun exitValue(): Int = exit
        override fun destroy() {
            destroyed.countDown()
            runCatching { pipe.close() }
        }
        override fun destroyForcibly(): Process = also { destroy() }
    }

    private val source = object : CastSource {
        override val length = 0L
        override fun open(position: Long): CastReader = throw IOException()
    }

    private fun job(copy: Boolean = false, toneMap: Boolean = false) = TranscodeJob(
        source,
        // An AVI is known by its name alone: no tracks, which the runner needs none of.
        MediaProbe.of(com.tmplayer.cast.FileCastSource(File.createTempFile("cast", ".avi").apply { writeBytes(ByteArray(64)); deleteOnExit() }), "v.avi"),
        CastRoute.Transcode(null, TranscodeReason.Container, TranscodeTarget(copy, 720, 576, 2_500, 30, TranscodeAudio.None, 0, 0, toneMap = toneMap)),
        5_000,
        "v.avi",
    )

    private val ffmpeg = DesktopFfmpeg(File("/usr/bin/ffmpeg"), setOf("h264_nvenc", "libx264", "aac"))

    private class Served : AutoCloseable {
        var closed = false
        override fun close() {
            closed = true
        }
    }

    @Test
    fun `the stream is copied to the receiver and the progress heard`() {
        val runs = ArrayList<List<String>>()
        val body = ByteArray(200_000) { (it % 251).toByte() }
        val served = Served()
        val t = FfmpegTranscoder(
            DesktopFfmpeg(File("ffmpeg"), setOf("libx264", "aac")),
            launcher = { args -> runs += args; FakeProcess(body, "out_time_us=2000000\nspeed=1.5x\nprogress=continue\nout_time_us=4000000\nprogress=end\n", 0) },
            serve = { "http://127.0.0.1:9/t/video" to served },
        )
        val out = ByteArrayOutputStream()
        val made = ArrayList<Long>()
        t.write(job(), out) { made += it }
        assertArrayEquals(body, out.toByteArray())
        assertEquals(listOf(2_000L, 4_000L), made)
        assertTrue(served.closed)
        val line = runs.single().joinToString(" ")
        assertTrue(line, line.startsWith("ffmpeg ") && "-i http://127.0.0.1:9/t/video" in line && "-ss 5.000" in line)
    }

    @Test
    fun `a hardware encoder that fails before its first byte falls to the next, which is remembered`() {
        val encoders = ArrayList<String>()
        val t = FfmpegTranscoder(
            ffmpeg,
            launcher = { args ->
                val enc = args[args.indexOf("-c:v") + 1]
                encoders += enc
                if (enc == "h264_nvenc") FakeProcess(ByteArray(0), "[h264_nvenc @ 0x1] No capable devices found\n", 1)
                else FakeProcess(ByteArray(10), "progress=end\n", 0)
            },
            serve = { "u" to Served() },
        )
        t.write(job(), ByteArrayOutputStream()) {}
        assertEquals(listOf("h264_nvenc", "libx264"), encoders)
        assertEquals("libx264", ffmpeg.working)
        // The next cast starts on the one that worked.
        encoders.clear()
        FfmpegTranscoder(ffmpeg, launcher = { args -> encoders += args[args.indexOf("-c:v") + 1]; FakeProcess(ByteArray(1), "", 0) }, serve = { "u" to Served() })
            .write(job(), ByteArrayOutputStream()) {}
        assertEquals(listOf("libx264"), encoders)
    }

    @Test
    fun `a build without zimg converts again without tone mapping`() {
        val filters = ArrayList<Boolean>()
        val t = FfmpegTranscoder(
            DesktopFfmpeg(File("ffmpeg"), setOf("libx264", "aac")),
            launcher = { args ->
                val tone = args.joinToString(" ").contains("tonemap")
                filters += tone
                if (tone) FakeProcess(ByteArray(0), "No such filter: 'zscale'\n", 1) else FakeProcess(ByteArray(4), "", 0)
            },
            serve = { "u" to Served() },
        )
        t.write(job(toneMap = true), ByteArrayOutputStream()) {}
        assertEquals(listOf(true, false), filters)
    }

    @Test
    fun `a failure after the stream began is not retried`() {
        var runs = 0
        val t = FfmpegTranscoder(ffmpeg, launcher = { runs++; FakeProcess(ByteArray(100), "Error while decoding stream\n", 1) }, serve = { "u" to Served() })
        try {
            t.write(job(), ByteArrayOutputStream()) {}
            fail("No failure")
        } catch (e: IOException) {
            assertTrue(e.message!!, "Error while decoding" in e.message!!)
        }
        assertEquals(1, runs)
    }

    @Test
    fun `cancel kills the process and closes its loopback`() {
        val served = Served()
        lateinit var p: FakeProcess
        val t = FfmpegTranscoder(ffmpeg, launcher = { FakeProcess(ByteArray(10), "", 0, hang = true).also { p = it } }, serve = { "u" to served })
        val done = CountDownLatch(1)
        var error: IOException? = null
        thread {
            try {
                t.write(job(), ByteArrayOutputStream()) {}
            } catch (e: IOException) {
                error = e
            }
            done.countDown()
        }
        Thread.sleep(200)
        t.cancel()
        assertTrue(done.await(3, TimeUnit.SECONDS))
        assertEquals(0L, p.destroyed.count)
        assertTrue(served.closed)
        assertEquals("Cancelled", error?.message)
    }

    @Test
    fun `the receiver hanging up kills the process`() {
        lateinit var p: FakeProcess
        val t = FfmpegTranscoder(ffmpeg, launcher = { FakeProcess(ByteArray(100_000), "", 0, hang = true).also { p = it } }, serve = { "u" to Served() })
        val gone = object : OutputStream() {
            override fun write(b: Int) = throw IOException("Broken pipe")
            override fun write(b: ByteArray, off: Int, len: Int) = throw IOException("Broken pipe")
        }
        try {
            t.write(job(), gone) {}
            fail("No failure")
        } catch (e: IOException) {
            assertEquals("Broken pipe", e.message)
        }
        assertEquals(0L, p.destroyed.count)
    }

    @Test
    fun `the picture passed through is tried once`() {
        var runs = 0
        val t = FfmpegTranscoder(ffmpeg, launcher = { runs++; FakeProcess(ByteArray(0), "Invalid data\n", 1) }, serve = { "u" to Served() })
        runCatching { t.write(job(copy = true), ByteArrayOutputStream()) {} }
        assertEquals(1, runs)
    }

    @Test
    fun `ffmpeg is looked for in the user's places, then on the path`() {
        val data = File("/data")
        val c = FfmpegLocator.candidates(mapOf("TMPLAYER_FFMPEG" to "/opt/ff/ffmpeg", "PATH" to "/usr/bin${File.pathSeparator}/bin"), data, File("/app"), windows = false)
        assertEquals(
            listOf("/opt/ff/ffmpeg", "/data/ffmpeg/ffmpeg", "/data/ffmpeg/bin/ffmpeg", "/app/ffmpeg", "/app/ffmpeg/ffmpeg", "/usr/bin/ffmpeg", "/bin/ffmpeg").map { File(it) },
            c,
        )
        assertTrue(FfmpegLocator.candidates(emptyMap(), data, null, windows = true).all { it.name == "ffmpeg.exe" })
        assertNull(FfmpegLocator.find(mapOf("PATH" to "/nonexistent-tmplayer"), File("/nonexistent-tmplayer"), null, windows = false))
    }

    @Test
    fun `the encoder listing is read from the process, and none means no ffmpeg`() {
        val listing = " ------\n V....D libx264 H.264\n A....D aac AAC\n"
        val found = DesktopFfmpeg.read(File("ffmpeg"), { FakeProcess(listing.toByteArray(), "", 0) })!!
        assertEquals(setOf("libx264", "aac"), found.encoders)
        assertFalse(found.ability!!.hardware)
        assertNull(DesktopFfmpeg.read(File("ffmpeg"), { FakeProcess("garbage".toByteArray(), "", 0) }))
    }
}
