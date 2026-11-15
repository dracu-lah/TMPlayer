package com.tmplayer.player.demux

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.ParserException
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A Media3 [Extractor] over FFmpeg's libavformat, for the containers Media3 has no extractor for
 * (ASF/WMV/WMA, RealMedia, MPEG-1 system streams, Ogg with Theora, NUT, MXF, DV and the others in
 * ffdemux.h's list). [FfmpegExtractors] puts it after every Media3 extractor, so it only sees a file
 * none of them took.
 *
 * libavformat reads synchronously through its own read and seek callbacks, while Media3 hands an
 * extractor one forward-only [ExtractorInput] at a time and moves it with [Extractor.RESULT_SEEK].
 * So the demuxer runs on a thread of its own, and every read it makes is handed to Media3's loading
 * thread, which answers it in [read] from the input it holds: a read at the input's position, or a
 * short way ahead of it ([SKIP_AHEAD]), is served from it; a read anywhere else returns
 * RESULT_SEEK there, and Media3 opens the source at that byte (TdDataSource moving TDLib's download
 * there), the way Media3's own binary-search seekers do. Nothing is read that the demuxer did not
 * ask for, so a seek in a file that is only partly downloaded fetches the index (where the format
 * keeps one) and the packets at the target, never the whole file (ARCHITECTURE.md, Seek matrix).
 *
 * The demux thread is parked on a condition whenever it waits (no CPU while paused) and ends by
 * itself after [release]. A file the demuxer refuses is a [ParserException], which the player shows
 * in its failure sheet; a damaged packet stream ends the input where it breaks.
 */
@OptIn(UnstableApi::class)
class FfmpegExtractor internal constructor(
    private val readerFactory: () -> ContainerReader,
    private val probe: (ByteArray, Int) -> String?,
) : Extractor {

    constructor() : this({ NativeContainerReader() }, NativeContainerReader::probe)

    private sealed class Event
    private class IoRead(val position: Long, val length: Int) : Event()
    private class Opened(val info: ContainerInfo) : Event()
    private class Packet(val generation: Int, val stream: Int, val timeUs: Long, val key: Boolean, val size: Int) : Event()
    private class Ended(val generation: Int) : Event()
    private class Failed(val error: Throwable?) : Event()

    private class SeekRequest(val generation: Int, val timeUs: Long, val bytePosition: Long)

    private val lock = ReentrantLock()
    private val changed = lock.newCondition()

    // Guarded by lock.
    private var event: Event? = null
    private var ioReply: Int? = null
    private var seekRequest: SeekRequest? = null
    private var generation = 0
    private var released = false
    private var opened = false

    // The loading thread's.
    private var format: String? = null
    private var output: ExtractorOutput? = null
    private var thread: Thread? = null
    private var tracks: Array<TrackOutput?> = emptyArray()
    private var trackTypes: IntArray = IntArray(0)
    private var lastTimeUs: LongArray = LongArray(0)
    private var ended = false
    private var failure: ParserException? = null
    private var seekMap: ContainerSeekMap? = null
    private val sample = ParsableByteArray()

    // Shared with the demux thread, which only touches them while the loading thread waits.
    private val ioBuffer = ByteArray(IO_BUFFER)
    private var packetBuffer = ByteArray(64 * 1024)

    /** What the player shows as the container: libavformat's demuxer, once the file is open. */
    @Volatile
    var demuxer: String? = null
        private set

    override fun sniff(input: ExtractorInput): Boolean {
        val buffer = ByteArray(PROBE_BYTES)
        var got = 0
        while (got < buffer.size) {
            val n = input.peek(buffer, got, buffer.size - got)
            if (n == C.RESULT_END_OF_INPUT) break
            got += n
        }
        if (got == 0) return false
        format = try {
            probe(buffer, got)
        } catch (_: Throwable) {
            null
        }
        return format != null
    }

    override fun init(output: ExtractorOutput) {
        this.output = output
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        failure?.let { throw it }
        if (ended) return Extractor.RESULT_END_OF_INPUT
        if (thread == null) start(input.length)
        while (true) {
            val e: Event
            lock.withLock {
                while (event == null && !released) {
                    try {
                        changed.await(200, TimeUnit.MILLISECONDS)
                    } catch (_: InterruptedException) {
                        // The loader is cancelling; it checks that between reads.
                        return Extractor.RESULT_CONTINUE
                    }
                    if (event == null) return Extractor.RESULT_CONTINUE
                }
                if (released) return Extractor.RESULT_END_OF_INPUT
                e = event ?: return Extractor.RESULT_CONTINUE
            }
            when (e) {
                is IoRead -> {
                    // Served outside the lock: the demux thread waits for this answer and nothing else.
                    val answer = serve(input, e)
                    if (answer == null) {
                        seekPosition.position = e.position
                        return Extractor.RESULT_SEEK
                    }
                    lock.withLock {
                        if (event === e) {
                            ioReply = answer
                            event = null
                            changed.signalAll()
                        }
                    }
                    // Back to the loader between reads, so it can cancel or wait for room.
                    return Extractor.RESULT_CONTINUE
                }
                is Opened -> {
                    publish(e.info)
                    lock.withLock { opened = true }
                    consume(e)
                }
                is Packet -> {
                    val current = lock.withLock { generation }
                    // Written before the demux thread is let go: it reuses the buffer.
                    if (e.generation == current) write(e)
                    consume(e)
                    return Extractor.RESULT_CONTINUE
                }
                is Ended -> {
                    val current = lock.withLock { generation }
                    consume(e)
                    if (e.generation == current) {
                        ended = true
                        return Extractor.RESULT_END_OF_INPUT
                    }
                }
                is Failed -> {
                    consume(e)
                    val error = ParserException.createForMalformedContainer(
                        "FFmpeg could not read this ${format ?: "file"}",
                        e.error,
                    )
                    failure = error
                    throw error
                }
            }
            if (ended) return Extractor.RESULT_END_OF_INPUT
        }
    }

    private fun consume(e: Event) {
        lock.withLock {
            if (event === e) {
                event = null
                changed.signalAll()
            }
        }
    }

    /** The bytes for [request] from [input]: a count, [ReaderIo.END], or null for "seek there". */
    private fun serve(input: ExtractorInput, request: IoRead): Int? {
        val length = input.length
        if (length != C.LENGTH_UNSET.toLong() && request.position >= length) return ReaderIo.END
        val ahead = request.position - input.position
        if (ahead < 0 || ahead > SKIP_AHEAD) return null
        var left = ahead
        while (left > 0) {
            val n = input.skip(minOf(left, Int.MAX_VALUE.toLong()).toInt())
            if (n == C.RESULT_END_OF_INPUT) return ReaderIo.END
            left -= n
        }
        val n = input.read(ioBuffer, 0, minOf(request.length, ioBuffer.size))
        return if (n == C.RESULT_END_OF_INPUT) ReaderIo.END else n
    }

    private fun publish(info: ContainerInfo) {
        val out = output ?: return
        demuxer = info.format
        val count = info.streams.maxOfOrNull { it.index + 1 } ?: 0
        tracks = arrayOfNulls(count)
        trackTypes = IntArray(count)
        lastTimeUs = LongArray(count)
        for (stream in info.streams) {
            val type = when (stream.type) {
                StreamInfo.TYPE_VIDEO -> C.TRACK_TYPE_VIDEO
                StreamInfo.TYPE_AUDIO -> C.TRACK_TYPE_AUDIO
                else -> continue
            }
            val format = FfmpegFormats.format(stream, info.format) ?: continue
            tracks[stream.index] = out.track(stream.index, type).also { it.format(format) }
            trackTypes[stream.index] = type
        }
        out.endTracks()
        if (tracks.all { it == null }) {
            val error = ParserException.createForMalformedContainer("No stream of this ${info.format} file can be played", null)
            failure = error
            throw error
        }
        val map = ContainerSeekMap(info, sizeOfFile)
        seekMap = map
        out.seekMap(map)
    }

    private fun write(packet: Packet) {
        val track = tracks.getOrNull(packet.stream) ?: return
        if (packet.size <= 0) return
        val timeUs = if (packet.timeUs == Long.MIN_VALUE) lastTimeUs[packet.stream] else packet.timeUs
        lastTimeUs[packet.stream] = timeUs
        sample.reset(packetBuffer, packet.size)
        track.sampleData(sample, packet.size)
        // Sound has no key frames to wait for after a seek: every packet starts one.
        val key = packet.key || trackTypes[packet.stream] == C.TRACK_TYPE_AUDIO
        track.sampleMetadata(timeUs, if (key) C.BUFFER_FLAG_KEY_FRAME else 0, packet.size, 0, null)
    }

    override fun seek(position: Long, timeUs: Long) {
        lock.withLock {
            generation++
            ended = false
            seekRequest = SeekRequest(generation, timeUs, seekMap?.bytesAt(timeUs) ?: -1)
            if (opened) {
                // A read in flight, or a packet from before the seek, is of no more use.
                when (event) {
                    is IoRead -> {
                        ioReply = ReaderIo.GIVE_UP
                        event = null
                    }
                    is Packet, is Ended -> event = null
                    else -> Unit
                }
            }
            changed.signalAll()
        }
    }

    override fun release() {
        lock.withLock {
            released = true
            if (event is IoRead) ioReply = ReaderIo.GIVE_UP
            event = null
            changed.signalAll()
        }
    }

    // ---- the demux thread -------------------------------------------------------------------------

    private var sizeOfFile = -1L

    private fun start(length: Long) {
        val format = format ?: throw ParserException.createForMalformedContainer("not sniffed", null)
        sizeOfFile = if (length == C.LENGTH_UNSET.toLong()) -1 else length
        val size = sizeOfFile
        thread = Thread({ demux(format, size) }, "TMPlayer demux").apply {
            isDaemon = true
            start()
        }
    }

    private val io = object : ReaderIo {
        var position = 0L
        override val buffer: ByteArray get() = ioBuffer

        override fun read(length: Int): Int {
            if (length <= 0) return 0
            val answer = lock.withLock {
                if (released || (opened && seekRequest != null)) return ReaderIo.GIVE_UP
                val request = IoRead(position, length)
                event = request
                ioReply = null
                changed.signalAll()
                try {
                    while (ioReply == null && !released) changed.await()
                } catch (_: InterruptedException) {
                    return ReaderIo.GIVE_UP
                }
                val reply = ioReply ?: ReaderIo.GIVE_UP
                ioReply = null
                if (event === request) event = null
                reply
            }
            if (answer > 0) position += answer
            return answer
        }

        override fun seek(position: Long): Long {
            if (position < 0 || (sizeOfFile >= 0 && position > sizeOfFile)) return -1
            this.position = position
            return position
        }
    }

    /** Posts [e] and waits until the loading thread took it. False once released. */
    private fun post(e: Event): Boolean = lock.withLock {
        try {
            while (event != null && !released) changed.await()
            if (released) return false
            event = e
            changed.signalAll()
            while (event === e && !released) changed.await()
            !released
        } catch (_: InterruptedException) {
            false
        }
    }

    /**
     * A new reader on the same file, from its first byte, for a demuxer that cannot go back to the
     * start once it has read on (the real vivo, IVR and NSV files libavformat could not rewind: it
     * lands in the middle of a packet and every read after it fails). Null when the file does not
     * open again. The old reader is closed either way.
     */
    private fun reopen(format: String, size: Long, old: ContainerReader): ContainerReader? {
        try {
            old.close()
        } catch (_: Throwable) {
        }
        val fresh = try {
            readerFactory()
        } catch (_: Throwable) {
            return null
        }
        io.seek(0)
        val info = try {
            fresh.open(format, io, size)
        } catch (_: Throwable) {
            null
        }
        if (info == null) {
            try {
                fresh.close()
            } catch (_: Throwable) {
            }
            return null
        }
        return fresh
    }

    private fun demux(format: String, size: Long) {
        var reader = try {
            readerFactory()
        } catch (t: Throwable) {
            post(Failed(t))
            return
        }
        // True from a seek to the start until the next packet: a read that fails in between is a
        // demuxer that did not rewind, and the file is opened again once.
        var restarting = false
        try {
            val info = try {
                reader.open(format, io, size)
            } catch (t: Throwable) {
                post(Failed(t))
                return
            }
            if (info == null) {
                post(Failed(null))
                return
            }
            if (!post(Opened(info))) return
            val meta = LongArray(5)
            var current = 0
            while (true) {
                val seek = lock.withLock {
                    if (released) return
                    seekRequest.also { seekRequest = null }
                }
                if (seek != null) {
                    current = seek.generation
                    reader.seek(seek.timeUs, seek.bytePosition)
                    restarting = seek.timeUs <= 0
                    continue
                }
                val n = reader.read(meta)
                if (n < 0) {
                    if (lock.withLock { seekRequest != null || released }) continue
                    if (restarting) {
                        restarting = false
                        val fresh = reopen(format, size, reader)
                        if (fresh != null) {
                            reader = fresh
                            continue
                        }
                    }
                    if (!post(Ended(current))) return
                    // Wait for a seek (or the end).
                    lock.withLock {
                        try {
                            while (seekRequest == null && !released) changed.await()
                        } catch (_: InterruptedException) {
                            return
                        }
                    }
                    continue
                }
                restarting = false
                val stream = meta[0].toInt()
                if (n > MAX_PACKET || stream < 0) continue
                if (packetBuffer.size < n) packetBuffer = ByteArray(maxOf(n, packetBuffer.size * 2).coerceAtMost(MAX_PACKET))
                val copied = reader.copy(packetBuffer)
                if (copied != n) continue
                if (!post(Packet(current, stream, meta[1], meta[3] == 1L, n))) return
            }
        } catch (t: Throwable) {
            post(Failed(t))
        } finally {
            try {
                reader.close()
            } catch (_: Throwable) {
            }
        }
    }

    companion object {
        const val PROBE_BYTES = 32 * 1024
        const val IO_BUFFER = 64 * 1024

        /** A read this far past the input's position reads through instead of reopening (one TDLib round trip). */
        const val SKIP_AHEAD = 256 * 1024L
        private const val MAX_PACKET = 32 shl 20
    }
}

/**
 * The container's index as Media3 seek points, read from [ContainerInfo] (fixed at open, so the
 * playback thread may ask it anything). Without an index, a byte estimate by the time's share of
 * the file: the first reopen lands near the target and the demuxer's own seek goes on from there.
 */
@OptIn(UnstableApi::class)
internal class ContainerSeekMap(private val info: ContainerInfo, private val size: Long) : SeekMap {
    override fun isSeekable(): Boolean = info.seekable && info.durationUs > 0 && size > 0 && info.format !in NO_TIME_SEEK

    override fun getDurationUs(): Long = if (info.durationUs > 0) info.durationUs else C.TIME_UNSET

    override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
        if (!isSeekable) return SeekMap.SeekPoints(SeekPoint.START)
        val times = info.indexTimesUs
        if (times.isNotEmpty()) {
            var i = java.util.Arrays.binarySearch(times, timeUs)
            if (i < 0) i = -i - 2
            if (i < 0) return SeekMap.SeekPoints(SeekPoint(0, info.dataStart))
            val first = SeekPoint(times[i], info.indexPositions[i])
            if (times[i] == timeUs || i + 1 >= times.size) return SeekMap.SeekPoints(first)
            return SeekMap.SeekPoints(first, SeekPoint(times[i + 1], info.indexPositions[i + 1]))
        }
        return SeekMap.SeekPoints(SeekPoint(timeUs.coerceAtLeast(0), bytesAt(timeUs)))
    }

    /** Where [timeUs] would be in a file of even bitrate. */
    fun bytesAt(timeUs: Long): Long {
        if (info.durationUs <= 0 || size <= 0) return info.dataStart
        val share = (timeUs.coerceIn(0, info.durationUs).toDouble() / info.durationUs)
        return (info.dataStart + share * (size - info.dataStart)).toLong().coerceIn(info.dataStart, (size - 1).coerceAtLeast(info.dataStart))
    }

    companion object {
        /**
         * Demuxers whose seek reads nothing after it, however it is asked (libavformat's vivo has no
         * index and no way to find a packet from the middle: all 5 sample files of the FFmpeg suite
         * give no packet after a seek, the ffmpeg program fails the same way). Playing from the
         * start works; the bar is shown as unseekable instead of ending the video at the first drag.
         */
        val NO_TIME_SEEK = setOf("vivo")
    }
}
