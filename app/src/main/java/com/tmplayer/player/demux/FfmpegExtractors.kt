package com.tmplayer.player.demux

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.text.SubtitleParser

/**
 * [delegate]'s extractors, then [FfmpegExtractor] as the last resort: Media3's own extractors keep
 * every file they read (their seeking, their Dolby Vision and HDR handling), and a file none of them
 * takes (ASF/WMV, RealMedia, an MPEG-1 system stream, an Ogg with Theora) goes to libavformat
 * instead of ending in "unrecognized format". Without the tm4 FFmpeg extension ([available] false:
 * nextlib's Maven Central AAR) the list is [delegate]'s alone.
 */
@OptIn(UnstableApi::class)
class FfmpegExtractors(
    private val delegate: ExtractorsFactory,
    private val available: () -> Boolean = FfmpegDemuxNative::available,
    private val create: () -> Extractor = { FfmpegExtractor() },
) : ExtractorsFactory {

    override fun createExtractors(): Array<Extractor> = withLast(delegate.createExtractors())

    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        withLast(delegate.createExtractors(uri, responseHeaders))

    private fun withLast(extractors: Array<Extractor>): Array<Extractor> =
        if (available()) extractors + create() else extractors

    // What DefaultMediaSourceFactory sets on its extractors, passed on, as CuelessMatroskaSeek does.
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
}
