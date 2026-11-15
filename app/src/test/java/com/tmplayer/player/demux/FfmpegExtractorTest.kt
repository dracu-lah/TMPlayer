package com.tmplayer.player.demux

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.ParserException
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [FfmpegExtractor]'s hand-over between the demux thread and Media3's loading thread, with a fake
 * reader that asks for bytes the way libavformat does (a header, the index at the end of the file,
 * then the packets, and an index seek): the bytes it gets are the right ones, a read elsewhere is a
 * reopen at that byte (RESULT_SEEK), a seek drops the packets from before it, the whole file is
 * never read to open or seek, a refused file is a ParserException, and release ends the thread.
 * libavformat itself is tested on the host by scripts/media3ext/bench/run-demux-tests.sh.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FfmpegExtractorTest {

    private val size = 1_000_000
    private val file = ByteArray(size) { (it % 251).toByte() }
    private val headerSize = 4096L
    private val indexSize = 2000
    private val durationUs = 10_000_000L
    private val packetSize = 1000

    /** Reads like libavformat's ASF demuxer: header, the index at the end, then packets in order. */
    private inner class FakeReader(
        private val refuse: Boolean = false,
        private val garbage: Boolean = false,
        /** A demuxer that cannot go back to the start: reads fail after such a seek until it is opened again. */
        private val stuckOnRestart: Boolean = false,
        /** The same, and opening again does not help. */
        private val stuckForever: Boolean = false,
    ) : ContainerReader {
        lateinit var io: ReaderIo
        var position = 0L
        var wrongBytes = 0
        var closed = false
        var seeks = 0
        var opened = 0
        private var stuck = false
        private var packet = 0

        private fun readFully(length: Int): Boolean {
            var left = length
            while (left > 0) {
                val n = io.read(minOf(left, io.buffer.size))
                if (n <= 0) return false
                for (i in 0 until n) if (io.buffer[i] != ((position + i) % 251).toByte()) wrongBytes++
                position += n
                left -= n
            }
            return true
        }

        private fun seekTo(at: Long) {
            position = io.seek(at)
        }

        override fun open(format: String, io: ReaderIo, size: Long): ContainerInfo? {
            this.io = io
            opened++
            position = 0
            stuck = stuckForever && opened > 1 && stuck
            packet = 0
            if (!readFully(headerSize.toInt())) return null
            if (refuse) return null
            seekTo(size - indexSize)
            if (!readFully(indexSize)) return null
            seekTo(headerSize)
            val times = LongArray(5) { it * 2_000_000L }
            val positions = LongArray(5) { headerSize + it * ((size - indexSize - headerSize) / 5) }
            return ContainerInfo(
                format = format, formatLongName = null, durationUs = durationUs, dataStart = headerSize,
                seekable = true, mainStream = 0,
                streams = listOf(
                    StreamInfo(0, StreamInfo.TYPE_VIDEO, "wmv2", "wmv2", null, width = 320, height = 240, codecTag = 0x32564D57),
                    StreamInfo(1, StreamInfo.TYPE_AUDIO, "wmav2", "wmav2", "eng", sampleRate = 44100, channels = 2, blockAlign = 743, extraData = byteArrayOf(0, 0, 1, 0, 0, 0)),
                    StreamInfo(2, StreamInfo.TYPE_OTHER, null, "bin_data", null),
                ),
                indexTimesUs = times, indexPositions = positions,
            )
        }

        private fun timeAt(at: Long) = (at - headerSize) * durationUs / (size - indexSize - headerSize)

        override fun read(meta: LongArray): Int {
            if (stuck) return -3
            if (position + packetSize > size - indexSize) return ReaderIo.END
            val at = position
            if (!readFully(packetSize)) return ReaderIo.END
            val stream = if (garbage) listOf(0, 1, 7, -3, 2)[packet % 5] else packet % 2
            meta[0] = stream.toLong()
            meta[1] = if (garbage && packet % 7 == 0) Long.MIN_VALUE else timeAt(at)
            meta[2] = 0
            meta[3] = if (stream == 0 && packet % 20 == 0) 1 else 0
            meta[4] = at
            packet++
            return if (garbage && packet % 11 == 0) 0 else packetSize
        }

        override fun copy(out: ByteArray): Int {
            out.fill(1, 0, packetSize)
            return packetSize
        }

        override fun seek(timeUs: Long, bytePosition: Long): Boolean {
            seeks++
            if ((stuckOnRestart || stuckForever) && timeUs <= 0) stuck = true
            val index = (timeUs / 2_000_000L).toInt().coerceIn(0, 4)
            seekTo(headerSize + index * ((size - indexSize - headerSize) / 5))
            packet = 0
            return true
        }

        override fun close() {
            closed = true
        }
    }

    private class Sample(val track: Int, val timeUs: Long, val key: Boolean)

    private class Output : ExtractorOutput {
        var seekMap: SeekMap? = null
        val samples = mutableListOf<Sample>()
        val formats = mutableMapOf<Int, Format>()
        var ended = false
        override fun track(id: Int, type: Int): TrackOutput = Track(id)
        override fun endTracks() {
            ended = true
        }
        override fun seekMap(seekMap: SeekMap) {
            this.seekMap = seekMap
        }

        private inner class Track(private val id: Int) : TrackOutput {
            override fun format(format: Format) {
                formats[id] = format
            }
            override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int = error("not used")
            override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) = data.skipBytes(length)
            override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
                samples += Sample(id, timeUs, flags and C.BUFFER_FLAG_KEY_FRAME != 0)
            }
        }
    }

    private var opens = 0
    private var bytesRead = 0L

    /** Media3's loader: reads from [from], reopening at every RESULT_SEEK, until [enough]. */
    private fun load(extractor: Extractor, from: Long, enough: () -> Boolean): Int {
        var position = from
        val holder = PositionHolder()
        for (open in 0 until 200) {
            opens++
            val source = ByteArrayDataSource(file)
            val length = source.open(DataSpec.Builder().setUri(Uri.parse("tdfile://1")).setPosition(position).build())
            val input = DefaultExtractorInput(source, position, position + length)
            try {
                var reads = 0
                while (true) {
                    if (++reads > 100_000) fail("no progress")
                    val result = extractor.read(input, holder)
                    if (result == Extractor.RESULT_SEEK) {
                        bytesRead += input.position - position
                        position = holder.position
                        break
                    }
                    if (result == Extractor.RESULT_END_OF_INPUT || enough()) {
                        bytesRead += input.position - position
                        return result
                    }
                }
            } finally {
                source.close()
            }
        }
        fail("too many reopens")
        return -1
    }

    private fun extractor(reader: FakeReader, probe: String? = "asf") = FfmpegExtractor({ reader }, { _, _ -> probe })

    private fun sniffed(extractor: FfmpegExtractor): Boolean {
        val source = ByteArrayDataSource(file)
        val length = source.open(DataSpec.Builder().setUri(Uri.parse("tdfile://1")).build())
        return extractor.sniff(DefaultExtractorInput(source, 0, length)).also { source.close() }
    }

    @Test
    fun opensReadsAndPublishesTracksWithTheIndex() {
        val reader = FakeReader()
        val extractor = extractor(reader)
        assertTrue(sniffed(extractor))
        val output = Output()
        extractor.init(output)
        load(extractor, 0) { output.samples.size >= 40 }
        assertEquals("bytes handed to the reader are the file's own at that place", 0, reader.wrongBytes)
        assertTrue(output.ended)
        assertEquals("asf", extractor.demuxer)
        // The data stream is left out, the picture and the sound are named by their decoders.
        assertEquals(setOf(0, 1), output.formats.keys)
        assertEquals("video/x-ffmpeg-wmv2", output.formats[0]!!.sampleMimeType)
        assertEquals("audio/x-ffmpeg-wmav2", output.formats[1]!!.sampleMimeType)
        assertEquals("video/x-ffmpeg-container-asf", output.formats[0]!!.containerMimeType)
        assertNotNull(output.seekMap)
        val map = output.seekMap!!
        assertTrue(map.isSeekable)
        assertEquals(durationUs, map.durationUs)
        val points = map.getSeekPoints(5_000_000L)
        assertEquals(4_000_000L, points.first.timeUs)
        assertEquals(6_000_000L, points.second.timeUs)
        // Header, the tail (the index) and back: three opens, a small part of the file.
        assertTrue("opens $opens", opens <= 4)
        assertTrue("read $bytesRead bytes to open", bytesRead < size / 10)
        // Sound is a key frame always.
        assertTrue(output.samples.filter { it.track == 1 }.all { it.key })
        extractor.release()
    }

    @Test
    fun aSeekReopensAtTheIndexAndDropsTheOldPackets() {
        val reader = FakeReader()
        val extractor = extractor(reader)
        assertTrue(sniffed(extractor))
        val output = Output()
        extractor.init(output)
        load(extractor, 0) { output.samples.size >= 10 }
        val map = output.seekMap!!
        val point = map.getSeekPoints(6_500_000L).first
        output.samples.clear()
        opens = 0
        bytesRead = 0
        extractor.seek(point.position, point.timeUs)
        load(extractor, point.position) { output.samples.size >= 20 }
        assertEquals(1, reader.seeks)
        assertEquals(0, reader.wrongBytes)
        val first = output.samples.first()
        assertTrue("first sample after the seek at ${first.timeUs}", first.timeUs in 5_900_000L..6_200_000L)
        assertTrue("read $bytesRead bytes to seek", bytesRead < 64 * 1024)
        assertTrue("opens $opens", opens <= 2)
        extractor.release()
    }

    @Test
    fun theEndIsTheEndOfInputAndASeekBackPlaysAgain() {
        val reader = FakeReader()
        val extractor = extractor(reader)
        assertTrue(sniffed(extractor))
        val output = Output()
        extractor.init(output)
        assertEquals(Extractor.RESULT_END_OF_INPUT, load(extractor, 0) { false })
        assertTrue(output.samples.size > 900)
        output.samples.clear()
        extractor.seek(headerSize, 0)
        load(extractor, headerSize) { output.samples.size >= 5 }
        assertEquals(0L, output.samples.first().timeUs)
        extractor.release()
    }

    @Test
    fun aDemuxerThatCannotRewindIsOpenedAgainForASeekToTheStart() {
        val reader = FakeReader(stuckOnRestart = true)
        val extractor = extractor(reader)
        assertTrue(sniffed(extractor))
        val output = Output()
        extractor.init(output)
        load(extractor, 0) { output.samples.size >= 10 }
        assertEquals(1, reader.opened)
        output.samples.clear()
        extractor.seek(headerSize, 0)
        load(extractor, headerSize) { output.samples.size >= 5 }
        assertEquals("the file is opened once more", 2, reader.opened)
        assertEquals(0, reader.wrongBytes)
        assertEquals(0L, output.samples.first().timeUs)
        // A seek elsewhere never opens it again.
        extractor.seek(headerSize, 4_000_000L)
        output.samples.clear()
        load(extractor, headerSize) { output.samples.size >= 5 }
        assertEquals(2, reader.opened)
        extractor.release()
    }

    @Test
    fun aFileThatFailsAfterBeingOpenedAgainEndsInsteadOfLooping() {
        val reader = FakeReader(stuckForever = true)
        val extractor = extractor(reader)
        assertTrue(sniffed(extractor))
        val output = Output()
        extractor.init(output)
        load(extractor, 0) { output.samples.size >= 10 }
        extractor.seek(headerSize, 0)
        assertEquals(Extractor.RESULT_END_OF_INPUT, load(extractor, headerSize) { false })
        assertEquals("one more open, no more", 2, reader.opened)
        extractor.release()
    }

    @Test
    fun aVivoFileIsShownAsUnseekable() {
        val vivo = FakeReader()
        val extractor = extractor(vivo, probe = "vivo")
        assertTrue(sniffed(extractor))
        val output = Output()
        extractor.init(output)
        load(extractor, 0) { output.samples.size >= 3 }
        assertFalse(output.seekMap!!.isSeekable)
        extractor.release()
        val asf = FakeReader()
        val other = extractor(asf, probe = "asf")
        assertTrue(sniffed(other))
        val out2 = Output()
        other.init(out2)
        load(other, 0) { out2.samples.size >= 3 }
        assertTrue(out2.seekMap!!.isSeekable)
        other.release()
    }

    @Test
    fun aRefusedFileIsAParserException() {
        val reader = FakeReader(refuse = true)
        val extractor = extractor(reader)
        assertTrue(sniffed(extractor))
        extractor.init(Output())
        try {
            load(extractor, 0) { false }
            fail("no exception")
        } catch (expected: ParserException) {
        }
        extractor.release()
        waitFor { reader.closed }
    }

    @Test
    fun aFileTheProbeDoesNotKnowIsNotSniffed() {
        assertFalse(sniffed(extractor(FakeReader(), probe = null)))
        assertFalse(sniffed(FfmpegExtractor({ FakeReader() }, { _, _ -> error("native failure") })))
    }

    @Test
    fun outOfRangeStreamsEmptyPacketsAndMissingTimesAreSkipped() {
        val reader = FakeReader(garbage = true)
        val extractor = extractor(reader)
        assertTrue(sniffed(extractor))
        val output = Output()
        extractor.init(output)
        load(extractor, 0) { output.samples.size >= 50 }
        assertTrue(output.samples.all { it.track == 0 || it.track == 1 })
        assertTrue(output.samples.all { it.timeUs >= 0 })
        extractor.release()
    }

    @Test
    fun releaseEndsTheDemuxThread() {
        val reader = FakeReader()
        val extractor = extractor(reader)
        assertTrue(sniffed(extractor))
        val output = Output()
        extractor.init(output)
        load(extractor, 0) { output.samples.size >= 3 }
        extractor.release()
        waitFor { reader.closed }
        waitFor { Thread.getAllStackTraces().keys.none { it.name == "TMPlayer demux" && it.isAlive } }
    }

    private fun waitFor(condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!condition()) {
            if (System.currentTimeMillis() > until) fail("timed out")
            Thread.sleep(10)
        }
    }
}
