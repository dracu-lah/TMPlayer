package com.tmplayer.player.demux

import androidx.media3.extractor.ExtractorsFactory

// The FFmpeg demuxer on iOS: Android's player adds libavformat as the last extractor for the few
// containers Media3 has none for (ASF, RealMedia). iOS has no such extractor, the video engine
// (AVPlayer or mpv) reads the file itself, so no track is ever named by it. The names are upstream's,
// so the activity and the failure sheet compile unchanged.

/** The names an FFmpeg-read track and container carry, and how the details label them. */
object FfmpegFormats {
    const val VIDEO_PREFIX = "video/x-ffmpeg-"
    const val AUDIO_PREFIX = "audio/x-ffmpeg-"

    /** The container MIME type of a track the FFmpeg reader made, `video/x-ffmpeg-container-<demuxer>`. */
    const val CONTAINER_PREFIX = "video/x-ffmpeg-container-"

    /** The demuxer a container MIME type of [CONTAINER_PREFIX] names, or null. */
    fun demuxerOf(containerMimeType: String?): String? =
        containerMimeType?.takeIf { it.startsWith(CONTAINER_PREFIX) }?.removePrefix(CONTAINER_PREFIX)
            ?.takeIf { name -> name.isNotEmpty() && name.length <= 32 && name.all { it.isLetterOrDigit() || it == '_' } }

    /** A container's name for the playback details. */
    fun containerLabel(demuxer: String): String = demuxer.uppercase()

    /** A sample MIME type as the playback details show it: the decoder's name for an FFmpeg-named one. */
    fun codecLabel(mime: String): String = when {
        mime.startsWith(VIDEO_PREFIX) -> mime.removePrefix(VIDEO_PREFIX)
        mime.startsWith(AUDIO_PREFIX) -> mime.removePrefix(AUDIO_PREFIX)
        else -> mime.substringAfter('/')
    }
}

/** The extractors with libavformat added last: on iOS there is none to add, so they are the delegate's own. */
class FfmpegExtractors(private val delegate: ExtractorsFactory) : ExtractorsFactory by delegate
