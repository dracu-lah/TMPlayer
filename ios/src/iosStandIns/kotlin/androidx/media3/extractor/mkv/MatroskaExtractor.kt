package androidx.media3.extractor.mkv

import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.text.SubtitleParser

/** Media3's Matroska extractor, by name: the engine reads Matroska itself on iOS. */
class MatroskaExtractor(
    @Suppress("UNUSED_PARAMETER") subtitleParserFactory: SubtitleParser.Factory?,
    @Suppress("UNUSED_PARAMETER") flags: Int,
) : Extractor {
    constructor() : this(null, 0)

    override fun sniff(input: ExtractorInput): Boolean = false

    override fun init(output: ExtractorOutput) {}

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int = Extractor.RESULT_END_OF_INPUT

    override fun seek(position: Long, timeUs: Long) {}

    override fun release() {}

    companion object {
        const val FLAG_DISABLE_SEEK_FOR_CUES = 1
        const val FLAG_EMIT_RAW_SUBTITLE_DATA = 1 shl 1
    }
}
