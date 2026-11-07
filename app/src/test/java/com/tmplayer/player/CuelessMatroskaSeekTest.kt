package com.tmplayer.player

import android.net.Uri
import androidx.media3.common.C
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
import androidx.media3.extractor.mkv.MatroskaExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Media3's own Matroska extractor, wrapped by [CuelessMatroskaSeek], reading MKVs ffmpeg wrote:
 * one without Cues gets a seek map that seeks, and a seek lands on a keyframe near the time
 * asked for with the sound beside it; one with Cues keeps Media3's own seek map. The files are
 * made by ffmpeg when it is on the machine, and the tests are skipped when it is not.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CuelessMatroskaSeekTest {

    private class Sample(val track: Int, val timeUs: Long, val key: Boolean)

    private class Output : ExtractorOutput {
        var seekMap: SeekMap? = null
        val samples = mutableListOf<Sample>()
        val types = mutableMapOf<Int, Int>()
        override fun track(id: Int, type: Int): TrackOutput {
            types[id] = type
            return Track(id)
        }
        override fun endTracks() = Unit
        override fun seekMap(seekMap: SeekMap) {
            this.seekMap = seekMap
        }

        private inner class Track(private val id: Int) : TrackOutput {
            private val scratch = ByteArray(64 * 1024)
            override fun format(format: Format) = Unit
            override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
                val got = input.read(scratch, 0, minOf(length, scratch.size))
                if (got == C.RESULT_END_OF_INPUT && !allowEndOfInput) throw java.io.EOFException()
                return got
            }
            override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) = data.skipBytes(length)
            override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
                samples += Sample(id, timeUs, flags and C.BUFFER_FLAG_KEY_FRAME != 0)
            }
        }
    }

    /** How many times the reader opened the file at a new place (each one a Telegram request). */
    private var opens = 0

    /** Reads [file] from [from] the way Media3's loader does, following every jump, until [enough]. */
    private fun read(extractor: Extractor, file: ByteArray, from: Long, enough: () -> Boolean) {
        var position = from
        val holder = PositionHolder()
        while (true) {
            opens++
            val source = ByteArrayDataSource(file)
            val length = source.open(DataSpec.Builder().setUri(Uri.parse("tdfile://1")).setPosition(position).build())
            val input = DefaultExtractorInput(source, position, position + length)
            while (true) {
                when (extractor.read(input, holder)) {
                    Extractor.RESULT_SEEK -> {
                        position = holder.position
                        break
                    }
                    Extractor.RESULT_END_OF_INPUT -> return
                    else -> if (enough()) return
                }
            }
            source.close()
        }
    }

    private class Opened(val extractor: Extractor, val output: Output)

    private fun open(file: ByteArray, hintUs: Long = C.TIME_UNSET): Opened {
        val factory = CuelessMatroskaSeek(DefaultExtractorsFactory(), durationHintUs = { hintUs })
        val extractor = factory.createExtractors().single { it.underlyingImplementation is MatroskaExtractor }
        val output = Output()
        extractor.init(output)
        read(extractor, file, 0) { output.seekMap != null && output.samples.isNotEmpty() }
        assertNotNull("no seek map", output.seekMap)
        return Opened(extractor, output)
    }

    /** Seeks to [targetUs] the way the player does and returns the first samples after it. */
    private fun seek(opened: Opened, file: ByteArray, targetUs: Long): List<Sample> {
        val point = opened.output.seekMap!!.getSeekPoints(targetUs).first
        opened.output.samples.clear()
        opened.extractor.seek(point.position, targetUs)
        read(opened.extractor, file, point.position) { opened.output.samples.size >= 120 }
        return opened.output.samples.toList()
    }

    /**
     * The seek's sound starts near the target, and its picture too, on a keyframe when
     * [clustersAtKeyframes]; else (ffmpeg writing to a pipe cuts Clusters by size, not at
     * keyframes) its first keyframe is still within a GOP (2 s here) of the target.
     */
    private fun assertLandsNear(opened: Opened, file: ByteArray, targetUs: Long, video: Boolean = true, clustersAtKeyframes: Boolean = true) {
        val samples = seek(opened, file, targetUs)
        val label = "seek to ${targetUs / 1000} ms"
        val audioTrack = opened.output.types.entries.first { it.value == C.TRACK_TYPE_AUDIO }.key
        val firstAudio = samples.first { it.track == audioTrack }
        assertTrue("$label: sound from ${firstAudio.timeUs / 1000} ms", firstAudio.timeUs in targetUs - 6_000_000..targetUs + 1_000_000)
        if (video) {
            val videoTrack = opened.output.types.entries.first { it.value == C.TRACK_TYPE_VIDEO }.key
            val firstVideo = samples.first { it.track == videoTrack }
            assertTrue("$label: picture from ${firstVideo.timeUs / 1000} ms", firstVideo.timeUs in targetUs - 6_000_000..targetUs + 1_000_000)
            if (clustersAtKeyframes) assertTrue("$label: picture starts on a keyframe", firstVideo.key)
            val key = samples.firstOrNull { it.track == videoTrack && it.key }
            assertNotNull("$label: no keyframe", key)
            assertTrue("$label: first keyframe at ${key!!.timeUs / 1000} ms", key.timeUs in targetUs - 6_000_000..targetUs + 3_000_000)
        }
    }

    // ---- the files ---------------------------------------------------------------------------------

    private val ffmpeg = listOf("/usr/bin/ffmpeg", "/usr/local/bin/ffmpeg", "/opt/homebrew/bin/ffmpeg").map(::File).firstOrNull { it.canExecute() }

    private fun ffmpeg(vararg args: String, toPipe: Boolean): ByteArray {
        assumeTrue("ffmpeg is not installed", ffmpeg != null)
        val out = File.createTempFile("mkv", ".mkv")
        try {
            val command = listOf(ffmpeg!!.path, "-y", "-hide_banner", "-loglevel", "error") + args +
                if (toPipe) listOf("-f", "matroska", "pipe:1") else listOf("-f", "matroska", out.path)
            // "-loglevel error" keeps its error stream too short to fill a pipe nobody reads.
            val builder = ProcessBuilder(command)
            if (toPipe) builder.redirectOutput(out)
            val process = builder.start()
            assumeTrue("ffmpeg did not finish", process.waitFor(120, TimeUnit.SECONDS) && process.exitValue() == 0)
            return out.readBytes()
        } finally {
            out.delete()
        }
    }

    private val videoArgs = arrayOf(
        "-f", "lavfi", "-i", "testsrc2=size=160x90:rate=25:duration=$SECONDS",
        "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=22050:duration=$SECONDS",
        "-c:v", "libx264", "-preset", "veryfast", "-g", "50", "-crf", "30", "-c:a", "aac", "-b:a", "24k", "-shortest",
    )

    private fun indexOf(bytes: ByteArray, pattern: ByteArray, from: Int = 0): Int =
        (from..bytes.size - pattern.size).firstOrNull { i -> pattern.indices.all { bytes[i + it] == pattern[it] } } ?: -1

    /**
     * [file] with its Cues turned into a Void of the same length and its SeekHead's entry for them
     * pointed at Tags: a file with a Duration and no Cues, as cam rips come.
     */
    private fun withoutCues(file: ByteArray): ByteArray {
        val bytes = file.copyOf()
        fun vint(at: Int): Pair<Long, Int> {
            val first = bytes[at].toInt() and 0xFF
            var length = 1
            while (first and (0x80 shr (length - 1)) == 0) length++
            var value = (first and (0xFF shr length)).toLong()
            for (k in 1 until length) value = (value shl 8) or (bytes[at + k].toLong() and 0xFF)
            return value to length
        }
        val cuesId = byteArrayOf(0x1C, 0x53, 0xBB.toByte(), 0x6B)
        val segment = indexOf(bytes, byteArrayOf(0x18, 0x53, 0x80.toByte(), 0x67))
        val segmentData = segment + 4 + vint(segment + 4).second
        val seekEntry = indexOf(bytes, byteArrayOf(0x53, 0xAB.toByte(), 0x84.toByte()) + cuesId)
        assertTrue(seekEntry > 0)
        byteArrayOf(0x12, 0x54, 0xC3.toByte(), 0x67).copyInto(bytes, seekEntry + 3)
        // SeekPosition, right after: where the Cues are, counted from the Segment's data.
        val position = seekEntry + 7
        assertEquals(0x53, bytes[position].toInt())
        assertEquals(0xAC, bytes[position + 1].toInt() and 0xFF)
        val (positionSize, positionSizeLength) = vint(position + 2)
        var offset = 0L
        for (k in 0 until positionSize.toInt()) offset = (offset shl 8) or (bytes[position + 2 + positionSizeLength + k].toLong() and 0xFF)
        val cues = (segmentData + offset).toInt()
        assertTrue(cuesId.indices.all { bytes[cues + it] == cuesId[it] })
        val (size, sizeLength) = vint(cues + 4)
        val total = 4 + sizeLength + size
        assertTrue(total >= 9)
        bytes[cues] = 0xEC.toByte()
        val voidSize = total - 9
        bytes[cues + 1] = 0x01
        for (k in 0 until 7) bytes[cues + 2 + k] = (voidSize shr (8 * (6 - k))).toByte()
        return bytes
    }

    // ---- the tests ---------------------------------------------------------------------------------

    @Test
    fun aFileWithoutCuesSeeks() {
        val file = withoutCues(ffmpeg(*videoArgs, toPipe = false))
        val opened = open(file)
        val map = opened.output.seekMap!!
        assertTrue("seekable", map.isSeekable)
        assertEquals(SECONDS * 1_000_000.0, map.durationUs.toDouble(), 100_000.0)
        for (target in listOf(SECONDS / 2, SECONDS * 9 / 10, 1, 0, SECONDS / 4, SECONDS - 2, SECONDS * 3 / 4).map { it * 1_000_000L + 370_000L }) {
            opens = 0
            assertLandsNear(opened, file, target)
            assertTrue("${opens} opens for one seek", opens <= MkvCuelessSeek.MAX_STEPS + 1)
        }
    }

    @Test
    fun ffmpegPipeOutputSeeksWithTelegramsDuration() {
        val file = ffmpeg(*videoArgs, toPipe = true)
        // No Duration and no Cues: without a duration from Telegram nothing changes.
        assertFalse(open(file).output.seekMap!!.isSeekable)
        val opened = open(file, hintUs = SECONDS * 1_000_000L)
        assertTrue(opened.output.seekMap!!.isSeekable)
        assertEquals(SECONDS * 1_000_000L, opened.output.seekMap!!.durationUs)
        for (target in listOf(SECONDS / 2, SECONDS * 9 / 10, 3).map { it * 1_000_000L }) {
            assertLandsNear(opened, file, target, clustersAtKeyframes = false)
        }
    }

    @Test
    fun audioOnlyWithoutCuesSeeks() {
        val file = ffmpeg(
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=22050:duration=$SECONDS", "-c:a", "aac", "-b:a", "32k",
            toPipe = true,
        )
        val opened = open(file, hintUs = SECONDS * 1_000_000L)
        assertTrue(opened.output.seekMap!!.isSeekable)
        for (target in listOf(SECONDS / 2, SECONDS * 9 / 10).map { it * 1_000_000L }) assertLandsNear(opened, file, target, video = false)
    }

    @Test
    fun aFileWithCuesKeepsMediasOwnSeekMap() {
        val file = ffmpeg(*videoArgs, toPipe = false)
        val wrapped = open(file).output.seekMap!!
        val plain = MatroskaExtractor().let { extractor ->
            val output = Output()
            extractor.init(output)
            read(extractor, file, 0) { output.seekMap != null && output.samples.isNotEmpty() }
            output.seekMap!!
        }
        assertEquals(plain.javaClass, wrapped.javaClass)
        for (t in listOf(0L, 30_000_000L, 61_000_000L, 110_000_000L)) {
            assertEquals(plain.getSeekPoints(t).first, wrapped.getSeekPoints(t).first)
        }
    }

    private companion object {
        const val SECONDS = 120
    }
}
