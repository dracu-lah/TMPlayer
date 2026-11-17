package com.tmplayer.player

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import com.tmplayer.data.DecoderReport

// The decoder list on iOS, in place of upstream's DecoderLister.kt and DecoderFacts.kt: those ask
// Android's MediaCodecList for the decoders a device has and read a codec's own output format.
// iOS has no MediaCodec: the app's video engine (AVPlayer or mpv) decodes, through VideoToolbox
// or in software, and does not list its decoders. So the list is empty, a decoder's claims are its
// name alone, and the codec output is passed on unread. The names are upstream's, so the playback
// recorder and the log report compile unchanged.

object DecoderLister {

    /** The codecs a report lists when the last video's is not known. */
    val COMMON = listOf("video/avc", "video/hevc", "video/av01", "video/x-vnd.on2.vp9", "video/dolby-vision")

    /** No decoders are listed on iOS, whatever [mimes] asks for. */
    @Suppress("UNUSED_PARAMETER")
    fun read(mimes: List<String>): List<DecoderReport.Decoder> = emptyList()
}

object DecoderFacts {

    /** What is known of the decoder named [name] on iOS: its name, nothing else is listed. */
    @Suppress("UNUSED_PARAMETER")
    fun claims(name: String, mime: String, width: Int, height: Int, fps: Double): Map<String, String> =
        mapOf("dec.name" to name)
}

/** Upstream's wrapper that reads each video codec's output format; no codec runs on iOS, so it hands every adapter on untouched. */
@UnstableApi
internal class OutputFactsCodecAdapterFactory(
    private val delegate: MediaCodecAdapter.Factory,
    @Suppress("UNUSED_PARAMETER") onOutput: (Map<String, String>) -> Unit,
) : MediaCodecAdapter.Factory {

    override fun createAdapter(configuration: MediaCodecAdapter.Configuration): MediaCodecAdapter =
        delegate.createAdapter(configuration)
}
