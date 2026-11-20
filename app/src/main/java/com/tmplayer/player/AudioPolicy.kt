package com.tmplayer.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.audio.AudioSink
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegLibrary

/**
 * The sound's own short ladder (RL8, research 4.1 and C7), beside the picture's so a bad audio path
 * never moves the picture: A1 is the app's FFmpeg audio decoder ahead of the device's, so the
 * sound reaches the output as plain PCM instead of a Dolby or DTS bitstream.
 *
 * The case it exists for: the device's output refuses to open for a bitstream (passthrough to a
 * receiver or a TV that said it takes the format and does not), Media3's
 * `ERROR_CODE_AUDIO_TRACK_INIT_FAILED`. The refused format rides on the error
 * ([AudioSink.InitializationException.format]), so a refused bitstream teaches the class
 * ([com.tmplayer.data.RemedyMemory.learned]) and the next video of it starts in PCM at once, while a
 * refused PCM output (nothing FFmpeg could change) teaches nothing.
 *
 * A2 is passthrough off ([PcmOnlyOutputProvider]): the output takes PCM only, so the device's own
 * decoder (or FFmpeg's) decodes the track. It is the next step where FFmpeg has no decoder for the
 * format, and what Settings' Audio passthrough turned off asks for on every video.
 */
@UnstableApi
object AudioPolicy {

    /**
     * The audio renderers' extension mode for a factory built with [extensionRendererMode]: FFmpeg's
     * decoder first when [softwareFirst] (A1), else the device's whatever the picture prefers ("Prefer
     * software video" is about the picture, and the device's decoder is what passes Dolby through).
     */
    fun rendererMode(extensionRendererMode: Int, softwareFirst: Boolean): Int = when {
        extensionRendererMode == DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF -> extensionRendererMode
        softwareFirst -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
        extensionRendererMode == DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
        else -> extensionRendererMode
    }

    /** The format the sound output refused to open for, or null for any other failure. */
    fun refusedFormat(error: PlaybackException): Format? {
        if (error.errorCode != PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED) return null
        val init = generateSequence(error.cause) { it.cause }.take(MAX_CAUSES).filterIsInstance<AudioSink.InitializationException>().firstOrNull()
        return init?.format ?: (error as? ExoPlaybackException)?.rendererFormat
    }

    /** Whether [format] went to the output as it is (passthrough): anything but PCM. */
    fun bitstream(format: Format?): Boolean {
        val mime = format?.sampleMimeType ?: return false
        return MimeTypes.isAudio(mime) && mime != MimeTypes.AUDIO_RAW
    }

    /**
     * Whether the app's decoder can help after [error], given [takes] (FFmpeg has a decoder for the
     * format): never when the output refused plain PCM, which is all FFmpeg would give it.
     */
    fun softwareHelps(error: PlaybackException, takes: Boolean): Boolean {
        val refused = refusedFormat(error) ?: return takes
        return takes && bitstream(refused)
    }

    /** Whether [error] teaches the class to start its sound on the app's decoder: a refused bitstream FFmpeg turns into PCM. */
    fun teachesSoftware(error: PlaybackException, takes: Boolean): Boolean = takes && bitstream(refusedFormat(error))

    /** Whether the app's FFmpeg decoder takes sound of [mime] on this device. */
    fun softwareTakes(mime: String?): Boolean {
        if (mime.isNullOrBlank() || !MimeTypes.isAudio(mime)) return false
        return runCatching { FfmpegLibrary.isAvailable() && FfmpegLibrary.supportsFormat(mime) }.getOrDefault(false)
    }

    private const val MAX_CAUSES = 6
}
