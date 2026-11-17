package androidx.media3.extractor.text

import androidx.media3.common.Format
import androidx.media3.common.text.Cue
import androidx.media3.common.util.Consumer

// Media3's subtitle parsing on iOS: the engine reads and draws subtitles itself (mpv through
// libass), so these compile Android's parser wrappers (SubtitleEncoding) as written.

/** Cues and the time they show, Media3's. */
class CuesWithTiming(val cues: List<Cue>, val startTimeUs: Long, val durationUs: Long) {
    val endTimeUs: Long = if (durationUs == androidx.media3.common.C.TIME_UNSET || startTimeUs == androidx.media3.common.C.TIME_UNSET) {
        androidx.media3.common.C.TIME_UNSET
    } else {
        startTimeUs + durationUs
    }
}

/** Media3's parser of one subtitle format. */
interface SubtitleParser {
    fun parse(data: ByteArray, offset: Int, length: Int, outputOptions: OutputOptions, output: Consumer<CuesWithTiming>)

    fun parse(data: ByteArray, outputOptions: OutputOptions, output: Consumer<CuesWithTiming>) =
        parse(data, 0, data.size, outputOptions, output)

    fun reset() {}

    fun getCueReplacementBehavior(): Int

    /** Which cues a parse hands out, Media3's. */
    class OutputOptions private constructor(val startTimeUs: Long, val outputAllCues: Boolean) {
        companion object {
            fun allCues(): OutputOptions = OutputOptions(androidx.media3.common.C.TIME_UNSET, false)

            fun onlyCuesAfter(startTimeUs: Long): OutputOptions = OutputOptions(startTimeUs, false)

            fun cuesAfterThenRemainingCuesBefore(startTimeUs: Long): OutputOptions = OutputOptions(startTimeUs, true)
        }
    }

    /** Media3's factory of subtitle parsers. */
    interface Factory {
        fun supportsFormat(format: Format): Boolean

        fun getCueReplacementBehavior(format: Format): Int

        fun create(format: Format): SubtitleParser

        companion object {
            val UNSUPPORTED: Factory = object : Factory {
                override fun supportsFormat(format: Format): Boolean = false

                override fun getCueReplacementBehavior(format: Format): Int = Format.CUE_REPLACEMENT_BEHAVIOR_MERGE

                override fun create(format: Format): SubtitleParser = throw IllegalStateException("No parser on iOS")
            }
        }
    }
}

/** Media3's subtitle parsers for every format it knows; on iOS the engine parses subtitles. */
class DefaultSubtitleParserFactory : SubtitleParser.Factory {
    override fun supportsFormat(format: androidx.media3.common.Format): Boolean = false

    override fun getCueReplacementBehavior(format: androidx.media3.common.Format): Int = 1

    override fun create(format: androidx.media3.common.Format): SubtitleParser =
        throw IllegalArgumentException("No subtitle parser on iOS: the engine parses subtitles")
}
