package androidx.media3.extractor

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.text.SubtitleParser

// Media3's extractors on iOS: the engine demuxes by itself, so these compile Android's own
// extractor wrappers (CuelessMatroskaSeek) as written and nothing runs them to play.

/** Media3's extractor of a container's tracks and samples. */
interface Extractor {
    fun sniff(input: ExtractorInput): Boolean

    fun getSniffFailureDetails(): List<SniffFailure> = emptyList()

    fun init(output: ExtractorOutput)

    fun read(input: ExtractorInput, seekPosition: PositionHolder): Int

    fun seek(position: Long, timeUs: Long)

    fun release()

    fun getUnderlyingImplementation(): Extractor = this

    companion object {
        const val RESULT_CONTINUE = 0
        const val RESULT_SEEK = 1
        const val RESULT_END_OF_INPUT = C.RESULT_END_OF_INPUT
    }
}

/** Why an extractor did not recognise a stream, Media3's. */
interface SniffFailure

/** What an extractor reads from, Media3's. */
interface ExtractorInput : DataReader {
    fun readFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean

    fun readFully(target: ByteArray, offset: Int, length: Int)

    fun skip(length: Int): Int

    fun skipFully(length: Int, allowEndOfInput: Boolean): Boolean

    fun skipFully(length: Int)

    fun peek(target: ByteArray, offset: Int, length: Int): Int

    fun peekFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean

    fun peekFully(target: ByteArray, offset: Int, length: Int)

    fun advancePeekPosition(length: Int, allowEndOfInput: Boolean): Boolean

    fun advancePeekPosition(length: Int)

    fun resetPeekPosition()

    fun getPeekPosition(): Long

    fun getPosition(): Long

    fun getLength(): Long

    fun <E : Throwable> setRetryPosition(position: Long, e: E)
}

/** Where an extractor writes what it finds, Media3's. */
interface ExtractorOutput {
    fun track(id: Int, type: Int): TrackOutput

    fun endTracks()

    fun seekMap(seekMap: SeekMap)
}

/** One track's output from an extractor, Media3's. */
interface TrackOutput {
    fun format(format: Format)

    /** The track's duration, Media3 1.4's, told before its samples when the container knows it. */
    fun durationUs(durationUs: Long) {}

    fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int

    fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean): Int = sampleData(input, length, allowEndOfInput, SAMPLE_DATA_PART_MAIN)

    fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int)

    fun sampleData(data: ParsableByteArray, length: Int) = sampleData(data, length, SAMPLE_DATA_PART_MAIN)

    fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: CryptoData?)

    /** Media3's encryption data of a sample. */
    class CryptoData(val cryptoMode: Int, val encryptionKey: ByteArray, val encryptedBlocks: Int, val clearBlocks: Int)

    companion object {
        const val SAMPLE_DATA_PART_MAIN = 0
        const val SAMPLE_DATA_PART_ENCRYPTION = 1
        const val SAMPLE_DATA_PART_SUPPLEMENTAL = 2
    }
}

/** Where to seek in a stream for a time, Media3's. */
interface SeekMap {
    fun isSeekable(): Boolean

    fun getDurationUs(): Long

    fun getSeekPoints(timeUs: Long): SeekPoints

    /** The points around a time, Media3's. */
    class SeekPoints(val first: SeekPoint, val second: SeekPoint) {
        constructor(point: SeekPoint) : this(point, point)
    }

    /** A stream that cannot seek, Media3's. */
    open class Unseekable(private val durationUs: Long, private val startPosition: Long) : SeekMap {
        constructor(durationUs: Long) : this(durationUs, 0)

        override fun isSeekable(): Boolean = false

        override fun getDurationUs(): Long = durationUs

        override fun getSeekPoints(timeUs: Long): SeekPoints = SeekPoints(SeekPoint(0, startPosition))
    }
}

/** A time and the byte position to start reading for it, Media3's. */
class SeekPoint(val timeUs: Long, val position: Long) {
    override fun equals(other: Any?): Boolean = other is SeekPoint && other.timeUs == timeUs && other.position == position

    override fun hashCode(): Int = 31 * timeUs.hashCode() + position.hashCode()

    companion object {
        val START: SeekPoint = SeekPoint(0, 0)
    }
}

/** Where an extractor asks to seek to, Media3's. */
class PositionHolder {
    var position: Long = 0
}

/** Media3's factory of extractors. */
interface ExtractorsFactory {
    fun createExtractors(): Array<Extractor>

    fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> = createExtractors()

    fun experimentalSetTextTrackTranscodingEnabled(textTrackTranscodingEnabled: Boolean): ExtractorsFactory = this

    fun setSubtitleParserFactory(subtitleParserFactory: SubtitleParser.Factory): ExtractorsFactory = this

    fun experimentalSetCodecsToParseWithinGopSampleDependencies(codecsToParseWithinGopSampleDependencies: Int): ExtractorsFactory = this

    fun setParseHagcMetadata(parseHagcMetadata: Boolean): ExtractorsFactory = this

    companion object {
        val EMPTY: ExtractorsFactory = object : ExtractorsFactory {
            override fun createExtractors(): Array<Extractor> = emptyArray()
        }
    }
}

/** Media3's extractors for every container, kept as set: the engine reads containers on iOS. */
class DefaultExtractorsFactory : ExtractorsFactory {
    override fun createExtractors(): Array<Extractor> = emptyArray()

    fun setConstantBitrateSeekingEnabled(enabled: Boolean): DefaultExtractorsFactory = this

    fun setConstantBitrateSeekingAlwaysEnabled(enabled: Boolean): DefaultExtractorsFactory = this

    fun setTsExtractorFlags(flags: Int): DefaultExtractorsFactory = this

    fun setTsExtractorTimestampSearchBytes(timestampSearchBytes: Int): DefaultExtractorsFactory = this

    fun setMatroskaExtractorFlags(flags: Int): DefaultExtractorsFactory = this

    fun setMp4ExtractorFlags(flags: Int): DefaultExtractorsFactory = this
}
