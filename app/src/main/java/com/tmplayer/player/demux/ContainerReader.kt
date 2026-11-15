package com.tmplayer.player.demux

/**
 * Where a [ContainerReader] gets its bytes. [read] fills [buffer] from its start with up to
 * [length] bytes at the current position and returns the count, [END] at the end of the file or
 * [GIVE_UP] when the read is to stop (a seek or a release came); [seek] moves the position and
 * returns it, or below 0. Both are called on the reader's own thread.
 */
interface ReaderIo {
    val buffer: ByteArray
    fun read(length: Int): Int
    fun seek(position: Long): Long

    companion object {
        const val END = -1
        const val GIVE_UP = -2
    }
}

/** One stream of a container, as the reader found it. Every number is checked and bounded. */
class StreamInfo(
    val index: Int,
    /** [TYPE_VIDEO], [TYPE_AUDIO] or [TYPE_OTHER]. */
    val type: Int,
    /** This build's FFmpeg decoder for it, or null when it has none. */
    val decoder: String?,
    /** FFmpeg's name of the codec ("wmv2", "cook"), or null. */
    val codec: String?,
    val language: String?,
    val width: Int = 0,
    val height: Int = 0,
    val sampleRate: Int = 0,
    val channels: Int = 0,
    val bitRate: Int = 0,
    val blockAlign: Int = 0,
    val bitsPerCodedSample: Int = 0,
    val codecTag: Int = 0,
    /** Frames per second, or 0 when unknown. */
    val frameRate: Float = 0f,
    val default: Boolean = false,
    /** A cover picture (an MP3-style attached picture), not a video. */
    val attachedPicture: Boolean = false,
    val extraData: ByteArray? = null,
) {
    companion object {
        const val TYPE_VIDEO = 0
        const val TYPE_AUDIO = 1
        const val TYPE_OTHER = 2
    }
}

/** What a container holds, read at open. */
class ContainerInfo(
    /** libavformat's demuxer name ("asf", "rm", "mpeg"). */
    val format: String,
    val formatLongName: String?,
    /** Microseconds, or -1 when unknown. */
    val durationUs: Long,
    /** The byte where the packets start. */
    val dataStart: Long,
    val seekable: Boolean,
    /** The stream seeks go by (the picture, else the sound), or -1. */
    val mainStream: Int,
    val streams: List<StreamInfo>,
    /** The main stream's key frames: [indexTimesUs] (increasing) and their [indexPositions]. */
    val indexTimesUs: LongArray = LongArray(0),
    val indexPositions: LongArray = LongArray(0),
)

/**
 * A container reader that [FfmpegExtractor] drives: libavformat's ([NativeContainerReader]) on a
 * phone, a fake in tests. Every call comes from one thread.
 */
interface ContainerReader {
    /** Opens [format] over [io] for a file of [size] bytes (-1 when unknown); null when refused. */
    fun open(format: String, io: ReaderIo, size: Long): ContainerInfo?

    /**
     * Reads the next packet: its size, with [meta] = [stream, time in us or Long.MIN_VALUE,
     * duration in us, key 0/1, byte position]; or [ReaderIo.END] at the end, below that an error.
     */
    fun read(meta: LongArray): Int

    /** Copies the packet [read] returned into [out], which is at least its size. Its size, or below 0. */
    fun copy(out: ByteArray): Int

    /** Seeks to the key frame at or before [timeUs], else to [bytePosition] (when not negative). */
    fun seek(timeUs: Long, bytePosition: Long): Boolean

    fun close()
}

/** libavformat's reader, through [FfmpegDemuxNative]. */
class NativeContainerReader : ContainerReader {
    private var handle = 0L

    override fun open(format: String, io: ReaderIo, size: Long): ContainerInfo? {
        check(handle == 0L) { "already open" }
        val error = IntArray(1)
        handle = FfmpegDemuxNative.nativeOpen(format, io, io.buffer, size, error)
        if (handle == 0L) return null
        return info(handle, size)
    }

    override fun read(meta: LongArray): Int = if (handle == 0L) ReaderIo.END else FfmpegDemuxNative.nativeRead(handle, meta)

    override fun copy(out: ByteArray): Int = if (handle == 0L) -1 else FfmpegDemuxNative.nativeCopy(handle, out)

    override fun seek(timeUs: Long, bytePosition: Long): Boolean =
        handle != 0L && FfmpegDemuxNative.nativeSeek(handle, timeUs, bytePosition) >= 0

    override fun close() {
        val h = handle
        handle = 0L
        if (h != 0L) FfmpegDemuxNative.nativeClose(h)
    }

    companion object {
        /** The demuxer that recognises the first [length] bytes of [data], or null. */
        fun probe(data: ByteArray, length: Int): String? =
            if (!FfmpegDemuxNative.available()) null else FfmpegDemuxNative.nativeProbe(data, length)

        private fun info(handle: Long, size: Long): ContainerInfo? {
            val info = FfmpegDemuxNative.nativeInfo(handle) ?: return null
            val streams = (0 until ContainerValues.streamCount(info)).mapNotNull { i ->
                ContainerValues.stream(
                    index = i,
                    values = FfmpegDemuxNative.nativeStream(handle, i),
                    decoder = FfmpegDemuxNative.nativeText(handle, i, 0),
                    codec = FfmpegDemuxNative.nativeText(handle, i, 1),
                    language = FfmpegDemuxNative.nativeText(handle, i, 2),
                    extraData = FfmpegDemuxNative.nativeExtraData(handle, i),
                )
            }
            val main = ContainerValues.mainStream(info, streams)
            return ContainerValues.container(
                format = FfmpegDemuxNative.nativeText(handle, -1, 3) ?: return null,
                longName = FfmpegDemuxNative.nativeText(handle, -1, 4),
                info = info,
                streams = streams,
                index = if (main >= 0) FfmpegDemuxNative.nativeIndex(handle, main) else null,
                size = size,
            )
        }
    }
}

/**
 * The checks on what the native side hands over: arrays of the wrong length, negative or absurd
 * numbers and indexes out of order are dropped or bounded, never trusted (AGENTS.md, Error
 * handling). Pure, so [GarbageContainerValuesTest] can feed it anything.
 */
object ContainerValues {
    const val MAX_STREAMS = 32
    private const val MAX_SIDE = 16_384
    private const val MAX_RATE = 768_000
    private const val MAX_CHANNELS = 64
    private const val MAX_EXTRA = 1 shl 20

    fun streamCount(info: LongArray?): Int =
        info?.getOrNull(1)?.coerceIn(0L, MAX_STREAMS.toLong())?.toInt() ?: 0

    fun mainStream(info: LongArray?, streams: List<StreamInfo>): Int {
        val main = info?.getOrNull(2) ?: return -1
        return if (streams.any { it.index.toLong() == main && it.type != StreamInfo.TYPE_OTHER }) main.toInt() else -1
    }

    private fun bounded(value: Long?, max: Int): Int = value?.takeIf { it in 0..max.toLong() }?.toInt() ?: 0

    fun stream(index: Int, values: LongArray?, decoder: String?, codec: String?, language: String?, extraData: ByteArray?): StreamInfo? {
        if (values == null || values.size < 14) return null
        val type = values[0].takeIf { it in 0L..2L }?.toInt() ?: return null
        val num = values[9]
        val den = values[10]
        val fps = if (num > 0 && den > 0) (num.toDouble() / den).takeIf { it in 0.5..1000.0 }?.toFloat() ?: 0f else 0f
        return StreamInfo(
            index = index,
            type = type,
            decoder = decoder?.takeIf(::isName),
            codec = codec?.takeIf(::isName),
            language = language?.takeIf { it.length in 1..16 && it.all { c -> c.isLetterOrDigit() || c == '-' } },
            width = bounded(values[1], MAX_SIDE),
            height = bounded(values[2], MAX_SIDE),
            sampleRate = bounded(values[3], MAX_RATE),
            channels = bounded(values[4], MAX_CHANNELS),
            bitRate = bounded(values[5], Int.MAX_VALUE),
            blockAlign = bounded(values[6], 1 shl 20),
            bitsPerCodedSample = bounded(values[7], 64),
            // A FourCC is any 32 bits.
            codecTag = values[8].toInt(),
            frameRate = fps,
            default = values[12] == 1L,
            attachedPicture = values[13] == 1L,
            extraData = extraData?.takeIf { it.size in 1..MAX_EXTRA },
        )
    }

    /** An FFmpeg decoder, codec or demuxer name: lower case letters, digits, '_', at most 40. */
    fun isName(name: String): Boolean =
        name.length in 1..40 && name.all { it in 'a'..'z' || it in '0'..'9' || it == '_' }

    fun container(format: String, longName: String?, info: LongArray?, streams: List<StreamInfo>, index: LongArray?, size: Long): ContainerInfo? {
        if (info == null || info.size < 5 || !isName(format) || streams.isEmpty()) return null
        val duration = info[0].takeIf { it > 0 && it < 100L * 365 * 24 * 3600 * 1_000_000 } ?: -1L
        val dataStart = info[3].takeIf { it >= 0 && (size < 0 || it <= size) } ?: 0L
        val (times, positions) = index(index, size)
        return ContainerInfo(
            format = format,
            formatLongName = longName?.take(60),
            durationUs = duration,
            dataStart = dataStart,
            seekable = info[4] == 1L && size > 0,
            mainStream = mainStream(info, streams),
            streams = streams,
            indexTimesUs = times,
            indexPositions = positions,
        )
    }

    /** The index pairs that are in order and inside the file; the rest are dropped. */
    fun index(pairs: LongArray?, size: Long): Pair<LongArray, LongArray> {
        if (pairs == null || pairs.size < 2) return LongArray(0) to LongArray(0)
        val times = ArrayList<Long>(pairs.size / 2)
        val positions = ArrayList<Long>(pairs.size / 2)
        var i = 0
        while (i + 1 < pairs.size) {
            val t = pairs[i]
            val p = pairs[i + 1]
            if (t >= 0 && p >= 0 && (size < 0 || p < size) && (times.isEmpty() || t > times.last())) {
                times += t
                positions += p
            }
            i += 2
        }
        return times.toLongArray() to positions.toLongArray()
    }
}
