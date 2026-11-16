package com.tmplayer.player

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import com.tmplayer.data.DecoderReport

/**
 * Reads the decoders the device lists, for Send logs (L5). Read only: it asks `MediaCodecList`
 * for names and limits and opens no codec, so it costs a few milliseconds and changes nothing.
 * Formatting is [DecoderReport]'s, so a test can pin it.
 */
object DecoderLister {

    /** The codecs a report lists when the last video's is not known. */
    val COMMON = listOf("video/avc", "video/hevc", "video/av01", "video/x-vnd.on2.vp9", "video/dolby-vision")

    /** Decoders for [mimes], never throwing: a device whose list cannot be read gives an empty list. */
    fun read(mimes: List<String>): List<DecoderReport.Decoder> = runCatching {
        val wanted = mimes.map { it.lowercase() }.toSet()
        val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        buildList {
            for (info in infos) {
                if (info.isEncoder) continue
                for (type in info.supportedTypes.orEmpty()) {
                    if (type.lowercase() !in wanted) continue
                    add(describe(info, type))
                }
            }
        }
    }.getOrDefault(emptyList())

    /** The device's own entry for the decoder named [name] (RL12's [DecoderFacts]), or null when it is not listed or the list cannot be read. */
    fun infoOf(name: String): MediaCodecInfo? = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { !it.isEncoder && it.name == name }
    }.getOrNull()

    private fun describe(info: MediaCodecInfo, type: String): DecoderReport.Decoder {
        val hardware = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> runCatching { info.isHardwareAccelerated && !info.isSoftwareOnly }.getOrNull()
            else -> !DecoderNames.isSoftware(info.name)
        }
        var width = 0
        var height = 0
        var rate = 0
        var secure = false
        runCatching {
            val caps = info.getCapabilitiesForType(type)
            caps.videoCapabilities?.let { video ->
                width = video.supportedWidths.upper
                height = video.supportedHeights.upper
                // The rate depends on the size asked about; the largest size is the limit that matters.
                rate = runCatching { video.getSupportedFrameRatesFor(width, height).upper.toInt() }
                    .getOrElse { runCatching { video.supportedFrameRates.upper.toInt() }.getOrDefault(0) }
            }
            secure = caps.isFeatureRequired(MediaCodecInfo.CodecCapabilities.FEATURE_SecurePlayback)
        }
        return DecoderReport.Decoder(info.name, type, hardware, width, height, rate, secure)
    }
}
