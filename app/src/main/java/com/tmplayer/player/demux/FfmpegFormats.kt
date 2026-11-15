package com.tmplayer.player.demux

import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/**
 * The Media3 [Format] of a stream libavformat read. A codec a device may decode in hardware or
 * pass to an AV receiver keeps Media3's own MIME type (MPEG-2 video, H.264 and HEVC in Annex B,
 * AC-3, E-AC-3, DTS, TrueHD, MP3, MPEG audio layers 1 and 2), so the renderer order of
 * docs/ARCHITECTURE.md ("Decoder fallback") still applies: the device first, the FFmpeg extension
 * behind it. Every other one is named by the FFmpeg decoder that reads it,
 * `video/x-ffmpeg-<decoder>` or `audio/x-ffmpeg-<decoder>`, with the initialization data laid out
 * as the extension's FfmpegLibrary.ffmpegMimeType documents it (tm3): [0] the container's extra
 * data as it is, [1] eight bytes, two big-endian int32s (audio: block size and bits per coded
 * sample; video: bits per coded sample and the FourCC).
 */
@OptIn(UnstableApi::class)
object FfmpegFormats {
    const val VIDEO_PREFIX = "video/x-ffmpeg-"
    const val AUDIO_PREFIX = "audio/x-ffmpeg-"

    /** The container MIME type of a track this reader made, `video/x-ffmpeg-container-<demuxer>`. */
    const val CONTAINER_PREFIX = "video/x-ffmpeg-container-"

    fun sampleMimeType(stream: StreamInfo): String? {
        val video = stream.type == StreamInfo.TYPE_VIDEO
        if (!video && stream.type != StreamInfo.TYPE_AUDIO) return null
        val annexB = annexB(stream.extraData)
        val standard = when (stream.codec) {
            "mpeg2video" -> MimeTypes.VIDEO_MPEG2
            "h264" -> MimeTypes.VIDEO_H264.takeIf { annexB }
            "hevc" -> MimeTypes.VIDEO_H265.takeIf { annexB }
            "ac3" -> MimeTypes.AUDIO_AC3
            "eac3" -> MimeTypes.AUDIO_E_AC3
            "dts" -> MimeTypes.AUDIO_DTS
            "truehd" -> MimeTypes.AUDIO_TRUEHD
            "mp3" -> MimeTypes.AUDIO_MPEG
            "mp2" -> MimeTypes.AUDIO_MPEG_L2
            "mp1" -> MimeTypes.AUDIO_MPEG_L1
            else -> null
        }
        if (standard != null) return standard
        // A codec this build has no decoder for keeps its codec name: the track is listed, and the
        // renderers say they cannot play it.
        val name = stream.decoder ?: stream.codec ?: return null
        return (if (video) VIDEO_PREFIX else AUDIO_PREFIX) + name
    }

    /** H.264 or HEVC whose parameter sets travel in the packets with start codes (no avcC/hvcC). */
    private fun annexB(extra: ByteArray?): Boolean {
        if (extra == null || extra.isEmpty()) return true
        if (extra.size >= 4 && extra[0].toInt() == 0 && extra[1].toInt() == 0 &&
            (extra[2].toInt() == 1 || (extra[2].toInt() == 0 && extra[3].toInt() == 1))
        ) return true
        return false
    }

    fun format(stream: StreamInfo, container: String): Format? {
        val mime = sampleMimeType(stream) ?: return null
        if (stream.attachedPicture) return null
        val ffmpegNamed = mime.startsWith(VIDEO_PREFIX) || mime.startsWith(AUDIO_PREFIX)
        val builder = Format.Builder()
            .setId(stream.index.toString())
            .setSampleMimeType(mime)
            .setContainerMimeType(CONTAINER_PREFIX + container)
            .setLanguage(stream.language)
            .setAverageBitrate(if (stream.bitRate > 0) stream.bitRate else Format.NO_VALUE)
            .setSelectionFlags(if (stream.default) androidx.media3.common.C.SELECTION_FLAG_DEFAULT else 0)
        if (stream.type == StreamInfo.TYPE_VIDEO) {
            if (stream.width > 0) builder.setWidth(stream.width)
            if (stream.height > 0) builder.setHeight(stream.height)
            if (stream.frameRate > 0f) builder.setFrameRate(stream.frameRate)
            if (ffmpegNamed) builder.setInitializationData(initializationData(stream.extraData, stream.bitsPerCodedSample, stream.codecTag))
            // The device's MPEG-2 decoder gets the sequence header as its codec data too (the reader copies
            // it from the first packet, where a program stream carries it once), so a restart after a seek has it.
            else if (stream.codec == "mpeg2video" && stream.extraData != null && stream.extraData.isNotEmpty()) {
                builder.setInitializationData(listOf(stream.extraData))
            }
        } else {
            if (stream.sampleRate > 0) builder.setSampleRate(stream.sampleRate)
            if (stream.channels > 0) builder.setChannelCount(stream.channels)
            if (ffmpegNamed) builder.setInitializationData(initializationData(stream.extraData, stream.blockAlign, stream.bitsPerCodedSample))
        }
        return builder.build()
    }

    private fun initializationData(extra: ByteArray?, first: Int, second: Int): List<ByteArray> =
        listOf(extra ?: ByteArray(0), ByteBuffer.allocate(8).putInt(first).putInt(second).array())

    /** The demuxer a container MIME type of [CONTAINER_PREFIX] names, or null. */
    fun demuxerOf(containerMimeType: String?): String? =
        containerMimeType?.takeIf { it.startsWith(CONTAINER_PREFIX) }?.removePrefix(CONTAINER_PREFIX)?.takeIf(ContainerValues::isName)

    /** A container's name for the playback details: "ASF (Windows Media)", "RealMedia", "MPEG-PS". */
    fun containerLabel(demuxer: String): String = when (demuxer) {
        "asf" -> "ASF (Windows Media)"
        "rm" -> "RealMedia"
        "ivr" -> "RealMedia (IVR)"
        "xwma" -> "xWMA"
        "dv" -> "DV"
        "nut" -> "NUT"
        "mxf" -> "MXF"
        "nsv" -> "Nullsoft Video"
        "wtv" -> "Windows TV"
        "ape" -> "Monkey's Audio"
        "tta" -> "True Audio"
        "wv" -> "WavPack"
        "aiff" -> "AIFF"
        "au" -> "Sun AU"
        "caf" -> "Core Audio"
        "voc" -> "Creative Voice"
        "w64" -> "Wave64"
        "ogg" -> "Ogg"
        "mpeg" -> "MPEG-PS"
        "smjpeg" -> "SMJPEG"
        "vivo" -> "Vivo"
        else -> demuxer.uppercase()
    }

    /** A sample MIME type as the playback details show it: the decoder's name for an FFmpeg-named one. */
    fun codecLabel(mime: String): String = when {
        mime.startsWith(VIDEO_PREFIX) -> mime.removePrefix(VIDEO_PREFIX)
        mime.startsWith(AUDIO_PREFIX) -> mime.removePrefix(AUDIO_PREFIX)
        else -> mime.substringAfter('/')
    }
}
