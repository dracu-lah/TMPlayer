package com.tmplayer.player.demux

import android.net.Uri
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.ts.PsExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Which files reach [FfmpegExtractor]: the ones none of Media3's own extractors sniffs. An MPEG-2
 * program stream (VOB) stays with Media3's PsExtractor, which reads its MPEG-2 picture and AC-3
 * sound and seeks; ASF/WMV, RealMedia, an MPEG-1 system stream and an Ogg with Theora are sniffed by
 * none of them, so the FFmpeg extractor takes them, and [FfmpegExtractors] adds it after the rest
 * (only where the native reader is there). The files are made by ffmpeg when it is on the machine,
 * and the tests are skipped when it is not.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ContainerCoverageTest {

    private val ffmpeg = listOf("/usr/bin/ffmpeg", "/usr/local/bin/ffmpeg", "/opt/homebrew/bin/ffmpeg").map(::File).firstOrNull { it.canExecute() }

    private fun make(extension: String, vararg args: String): ByteArray {
        assumeTrue("ffmpeg is not installed", ffmpeg != null)
        val out = File.createTempFile("clip", ".$extension")
        try {
            val command = listOf(ffmpeg!!.path, "-y", "-hide_banner", "-loglevel", "error",
                "-f", "lavfi", "-i", "testsrc2=size=160x120:rate=25:duration=4",
                "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100:duration=4") + args + out.path
            val process = ProcessBuilder(command).start()
            assumeTrue("ffmpeg did not finish", process.waitFor(120, TimeUnit.SECONDS) && process.exitValue() == 0)
            return out.readBytes()
        } finally {
            out.delete()
        }
    }

    private fun input(file: ByteArray, position: Long = 0): DefaultExtractorInput {
        val source = ByteArrayDataSource(file)
        val length = source.open(DataSpec.Builder().setUri(Uri.parse("tdfile://1")).setPosition(position).build())
        return DefaultExtractorInput(source, position, position + length)
    }

    /** The first of Media3's extractors that sniffs [file], the way BundledExtractorsAdapter asks them. */
    private fun claimedBy(file: ByteArray): Extractor? {
        for (extractor in DefaultExtractorsFactory().createExtractors()) {
            val input = input(file)
            val sniffed = try {
                extractor.sniff(input)
            } catch (_: java.io.EOFException) {
                false
            }
            if (sniffed) return extractor
        }
        return null
    }

    private class Output : ExtractorOutput {
        val formats = mutableMapOf<Int, Format>()
        val types = mutableMapOf<Int, Int>()
        val samples = mutableMapOf<Int, Int>()
        var seekMap: SeekMap? = null
        override fun track(id: Int, type: Int): TrackOutput {
            types[id] = type
            return object : TrackOutput {
                override fun format(format: Format) {
                    formats[id] = format
                }
                override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
                    val scratch = ByteArray(length)
                    return input.read(scratch, 0, length)
                }
                override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) = data.skipBytes(length)
                override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
                    samples[id] = (samples[id] ?: 0) + 1
                }
            }
        }
        override fun endTracks() = Unit
        override fun seekMap(seekMap: SeekMap) {
            this.seekMap = seekMap
        }
    }

    @Test
    fun aVobStaysWithMedia3sPsExtractorAndPlaysPictureAndSound() {
        val vob = make("vob", "-f", "vob", "-c:v", "mpeg2video", "-g", "12", "-b:v", "800k", "-c:a", "ac3", "-b:a", "128k")
        val extractor = claimedBy(vob)
        assertTrue("claimed by ${extractor?.javaClass?.simpleName}", extractor?.underlyingImplementation is PsExtractor)
        val output = Output()
        extractor!!.init(output)
        // Media3's loader: a RESULT_SEEK (PsExtractor reads the end for the duration) reopens there.
        var input = input(vob)
        val holder = PositionHolder()
        var reads = 0
        while (reads++ < 200_000) {
            val result = extractor.read(input, holder)
            if (result == Extractor.RESULT_END_OF_INPUT) break
            if (result == Extractor.RESULT_SEEK) input = input(vob, holder.position)
        }
        val mimes = output.formats.values.map { it.sampleMimeType }.toSet()
        assertTrue("tracks $mimes", "video/mpeg2" in mimes && "audio/ac3" in mimes)
        assertTrue("samples ${output.samples}", output.samples.values.all { it > 10 })
        assertTrue("seekable", output.seekMap?.isSeekable == true)
    }

    @Test
    fun filesMedia3CannotReadAreSniffedByNoneOfItsExtractors() {
        val files = mapOf(
            "asf" to make("wmv", "-c:v", "wmv2", "-c:a", "wmav2", "-b:a", "64k"),
            "rm" to make("rm", "-c:v", "rv20", "-c:a", "ac3"),
            "mpeg1" to make("mpg", "-f", "mpeg", "-c:v", "mpeg1video", "-c:a", "mp2"),
            "ogg" to make("ogv", "-c:v", "libtheora", "-c:a", "libvorbis"),
        )
        for ((name, bytes) in files) {
            assertNull("$name is claimed by ${claimedBy(bytes)?.javaClass?.simpleName}", claimedBy(bytes))
        }
    }

    @Test
    fun theFfmpegExtractorComesLastAndOnlyWithTheNativeReader() {
        val media3 = DefaultExtractorsFactory().createExtractors().size
        val with = FfmpegExtractors(DefaultExtractorsFactory(), available = { true }).createExtractors()
        assertEquals(media3 + 1, with.size)
        assertTrue(with.last() is FfmpegExtractor)
        assertEquals(media3, FfmpegExtractors(DefaultExtractorsFactory(), available = { false }).createExtractors().size)
        // On the JVM there is no native library: the real check says so instead of throwing.
        assertEquals(false, FfmpegDemuxNative.available())
    }
}
