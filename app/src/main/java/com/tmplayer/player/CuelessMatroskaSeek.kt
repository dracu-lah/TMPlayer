package com.tmplayer.player

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.text.SubtitleParser

/**
 * Makes a Matroska file without Cues seekable ([MkvCuelessSeek]).
 *
 * Media3's Matroska extractor gives such a file a seek map that cannot seek, so the player could
 * only play it from the start: a two hour video written by ffmpeg to a pipe, or a cam rip, had its
 * seek bar turned off ([PlayerActivity.noteSeekIndex]). Here that seek map is swapped for one that
 * guesses a byte position from the time, and each seek then scans forward to the next Cluster
 * before Media3's extractor reads on from it, guessing again while the Cluster is far from the time
 * asked for. Each guess is a fresh open of the data source at that place, so a stream from
 * Telegram downloads from there ([TdDataSource.open]).
 *
 * Only an unseekable seek map is replaced, and only when the file's length and duration are known
 * and its head says its TimecodeScale: a file with Cues, and every other format, is untouched.
 * [durationHintUs] stands in for a duration the file does not state (ffmpeg writing to a pipe
 * leaves it out): Telegram's own, or [C.TIME_UNSET].
 */
@UnstableApi
class CuelessMatroskaSeek(
    private val delegate: ExtractorsFactory,
    private val durationHintUs: () -> Long = { C.TIME_UNSET },
) : ExtractorsFactory {

    override fun createExtractors(): Array<Extractor> = delegate.createExtractors().map(::wrap).toTypedArray()

    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        delegate.createExtractors(uri, responseHeaders).map(::wrap).toTypedArray()

    // What DefaultMediaSourceFactory sets on its extractors, passed on, as DolbyVisionBaseLayer does.
    @Deprecated("Media3's own deprecation, passed through")
    @Suppress("DEPRECATION")
    override fun experimentalSetTextTrackTranscodingEnabled(textTrackTranscodingEnabled: Boolean): ExtractorsFactory {
        delegate.experimentalSetTextTrackTranscodingEnabled(textTrackTranscodingEnabled)
        return this
    }

    override fun setSubtitleParserFactory(subtitleParserFactory: SubtitleParser.Factory): ExtractorsFactory {
        delegate.setSubtitleParserFactory(subtitleParserFactory)
        return this
    }

    override fun experimentalSetCodecsToParseWithinGopSampleDependencies(codecsToParseWithinGopSampleDependencies: Int): ExtractorsFactory {
        delegate.experimentalSetCodecsToParseWithinGopSampleDependencies(codecsToParseWithinGopSampleDependencies)
        return this
    }

    override fun setParseHagcMetadata(parseHagcMetadata: Boolean): ExtractorsFactory {
        delegate.setParseHagcMetadata(parseHagcMetadata)
        return this
    }

    private fun wrap(extractor: Extractor): Extractor =
        if (extractor.underlyingImplementation is MatroskaExtractor) Wrapped(extractor, durationHintUs) else extractor

    /** The Matroska extractor, with a seek map for a file without Cues and the scans it needs. */
    internal class Wrapped(private val inner: Extractor, private val durationHintUs: () -> Long) : Extractor {

        /** The input [inner] is reading, while it reads: where it is when it sends its seek map. */
        private var reading: ExtractorInput? = null

        private var headRead = false
        private var head: MkvClusters.Head? = null

        /** Set once [inner] moved the input itself (to its Cues or Tracks) before its seek map. */
        private var innerJumped = false

        private var plan: MkvCuelessSeek? = null
        private var search: MkvCuelessSeek.Search? = null
        private var scanFrom = 0L
        private var scanned = 0L
        private var lastFoundUs = C.TIME_UNSET
        private var buffer: ByteArray? = null

        override fun sniff(input: ExtractorInput): Boolean = inner.sniff(input)
        override fun getSniffFailureDetails() = inner.sniffFailureDetails
        override fun init(output: ExtractorOutput) = inner.init(Output(output))
        override fun release() = inner.release()
        override fun getUnderlyingImplementation(): Extractor = inner.underlyingImplementation

        override fun seek(position: Long, timeUs: Long) {
            inner.seek(position, timeUs)
            search = plan?.search(timeUs)
            scanFrom = position
            scanned = 0
        }

        override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
            if (!headRead) {
                headRead = true
                if (input.position == 0L) head = runCatching { readHead(input) }.getOrNull()
            }
            search?.let { s -> scan(input, seekPosition, s)?.let { return it } }
            reading = input
            try {
                val result = inner.read(input, seekPosition)
                if (result == Extractor.RESULT_SEEK && plan == null) innerJumped = true
                return result
            } finally {
                reading = null
            }
        }

        /** Peeks the head of the file for its TimecodeScale and first Cluster; reads nothing. */
        private fun readHead(input: ExtractorInput): MkvClusters.Head? {
            val bytes = ByteArray(HEAD_BYTES)
            val n = peekUpTo(input, bytes)
            return MkvClusters.head(bytes, n)
        }

        private fun peekUpTo(input: ExtractorInput, into: ByteArray): Int {
            input.resetPeekPosition()
            var n = 0
            while (n < into.size) {
                val got = input.peek(into, n, into.size - n)
                if (got == C.RESULT_END_OF_INPUT) break
                n += got
            }
            input.resetPeekPosition()
            return n
        }

        /**
         * One chunk of a seek's scan: the step's result for Media3 (go on, or open the file at
         * another place), or null once the input is at the Cluster to play from.
         */
        private fun scan(input: ExtractorInput, seekPosition: PositionHolder, s: MkvCuelessSeek.Search): Int? {
            val plan = plan ?: return null.also { search = null }
            val buf = buffer ?: ByteArray(SCAN_CHUNK).also { buffer = it }
            val n = peekUpTo(input, buf)
            val left = if (input.length != C.LENGTH_UNSET.toLong()) input.length - input.position else -1L
            val atEnd = n < buf.size || (left in 0..n.toLong())
            val step = when (val found = MkvClusters.find(buf, n, atEnd, left)) {
                is MkvClusters.Scan.Hit -> {
                    input.skipFully(found.offset)
                    lastFoundUs = plan.usOf(found.timecode)
                    val end = if (found.length >= 0) input.position + found.length else -1L
                    s.found(scanFrom, input.position, lastFoundUs, end)
                }
                is MkvClusters.Scan.Miss -> {
                    if (atEnd || scanned + found.skip > MAX_SCAN_BYTES) {
                        s.nothing(scanFrom)
                    } else {
                        input.skipFully(found.skip)
                        scanned += found.skip
                        return Extractor.RESULT_CONTINUE
                    }
                }
            }
            return when (step) {
                MkvCuelessSeek.Step.Play -> {
                    search = null
                    Log.i(TAG, "No Cues: seek to ${s.targetUs / 1000} ms plays from ${lastFoundUs / 1000} ms after ${s.steps} more guesses")
                    null
                }
                is MkvCuelessSeek.Step.ScanFrom -> {
                    scanFrom = step.position
                    scanned = 0
                    val ahead = step.position - input.position
                    if (ahead in 0..SCAN_CHUNK.toLong()) {
                        // A step this short is read through rather than opened afresh.
                        input.skipFully(ahead.toInt())
                        Extractor.RESULT_CONTINUE
                    } else {
                        seekPosition.position = step.position
                        Extractor.RESULT_SEEK
                    }
                }
            }
        }

        /** The seek map for a file without Cues, or null to keep [map]. */
        private fun replace(map: SeekMap): SeekMap? {
            if (map.isSeekable) return null
            val input = reading ?: return null
            val scale = head?.timecodeScaleNs ?: return null
            val length = input.length.takeIf { it != C.LENGTH_UNSET.toLong() && it > 0 } ?: return null
            val duration = map.durationUs.takeIf { it != C.TIME_UNSET && it > 0 }
                ?: durationHintUs().takeIf { it != C.TIME_UNSET && it > 0 }
                ?: return null
            // Media3 sends this as it meets the first Cluster, its ID and size just read, unless
            // it went to its Cues first: then only the head knows where the Clusters start.
            val first = head?.firstCluster?.takeIf { it >= 0 }
                ?: if (!innerJumped) maxOf(0L, input.position - CLUSTER_HEADER_MAX) else return null
            if (first >= length) return null
            val made = MkvCuelessSeek(length, first, duration, scale)
            plan = made
            Log.i(TAG, "No Cues in this Matroska file: seeking by estimate (${length} bytes, ${duration / 1000} ms${if (map.durationUs == C.TIME_UNSET) " from Telegram" else ""})")
            return EstimatedSeekMap(made)
        }

        private inner class Output(private val out: ExtractorOutput) : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = out.track(id, type)
            override fun endTracks() = out.endTracks()
            override fun seekMap(seekMap: SeekMap) = out.seekMap(runCatching { replace(seekMap) }.getOrNull() ?: seekMap)
        }
    }

    /** Every time seekable, at the byte position [MkvCuelessSeek.estimate] guesses for it. */
    private class EstimatedSeekMap(private val plan: MkvCuelessSeek) : SeekMap {
        override fun isSeekable() = true
        override fun getDurationUs() = plan.durationUs
        override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
            val at = timeUs.coerceIn(0, plan.durationUs)
            return SeekMap.SeekPoints(SeekPoint(at, plan.estimate(at)))
        }
    }

    private companion object {
        const val TAG = "TMPlayer"

        /** Enough for the Info of every file seen; ffmpeg and mkvmerge write it in the first few KB. */
        const val HEAD_BYTES = 64 * 1024

        /** A scan reads this much at a time, so a new seek cancels it within a chunk. */
        const val SCAN_CHUNK = 64 * 1024

        /** A scan finding no Cluster in this much gives up: no Cluster runs that long. */
        const val MAX_SCAN_BYTES = 64L * 1024 * 1024

        /** A Cluster's ID and its longest size: the first Cluster starts no further back. */
        const val CLUSTER_HEADER_MAX = 12L
    }
}
