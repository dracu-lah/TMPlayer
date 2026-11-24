package com.tmplayer.player

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegAudioRenderer
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegLibrary
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegVideoRenderer

/**
 * "Try the other decoder" on a decoding error: the device's own decoder (MediaCodec) and the
 * app's FFmpeg decoder are two renderers for the same track, and this turns off the one that
 * failed so the other takes the track when the player is prepared again, from the same position.
 *
 * Only offered when the other side has a decoder for the format at all, so the button never leads
 * to the same failure. The choice lasts for this player, which is this video (and an episode that
 * follows in the same player): the Decoder setting is where a lasting choice is made.
 */
@UnstableApi
object DecoderSwitch {

    /** Which way a switch goes: the track type and whether it turns to the app's FFmpeg decoder. */
    data class Plan(val trackType: Int, val toFfmpeg: Boolean)

    private val DECODER_ERRORS = setOf(
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED,
        // A Dolby or DTS track sent to the receiver as it is (passthrough) whose AudioTrack the
        // device would not open: the failure names the compressed format, and the FFmpeg decoder
        // turns it into plain sound the same AudioTrack takes. A failure over sound that was
        // already decoded names raw PCM, which neither side decodes, so it offers nothing.
        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
    )

    fun isFfmpeg(renderer: Renderer): Boolean = renderer is FfmpegVideoRenderer || renderer is FfmpegAudioRenderer

    /** The switch that might get past [error], or null when it is not a decoder's failure or nothing else can decode it. */
    fun planFor(player: ExoPlayer?, error: PlaybackException): Plan? {
        player ?: return null
        if (error.errorCode !in DECODER_ERRORS) return null
        val renderer = error as? ExoPlaybackException ?: return null
        if (renderer.type != ExoPlaybackException.TYPE_RENDERER) return null
        val mime = renderer.rendererFormat?.sampleMimeType ?: return null
        val trackType = when {
            MimeTypes.isVideo(mime) -> C.TRACK_TYPE_VIDEO
            MimeTypes.isAudio(mime) -> C.TRACK_TYPE_AUDIO
            else -> return null
        }
        val index = renderer.rendererIndex
        if (index !in 0 until player.rendererCount) return null
        val failedOnFfmpeg = isFfmpeg(player.getRenderer(index))
        val possible = if (failedOnFfmpeg) hasMediaCodec(mime) else ffmpegHas(mime)
        return if (possible) Plan(trackType, toFfmpeg = !failedOnFfmpeg) else null
    }

    /**
     * The switch the player's menu offers for a picture that plays wrong without an error (WP-25,
     * green or choppy frames): from the decoder showing the picture now ([softwareVideoChosen]) to
     * the other one, only when the other side has a decoder for the picture's format at all, so
     * the line never leads to a picture that cannot play. Null with no picture yet (sound alone,
     * before the tracks are known) or nothing else to decode it with.
     */
    fun planForPicture(player: ExoPlayer?): Plan? {
        player ?: return null
        val mime = player.videoFormat?.sampleMimeType ?: return null
        if (!MimeTypes.isVideo(mime)) return null
        val onSoftware = softwareVideoChosen(player)
        val possible = if (onSoftware) hasMediaCodec(mime) else ffmpegHas(mime)
        return if (possible) Plan(C.TRACK_TYPE_VIDEO, toFfmpeg = !onSoftware) else null
    }

    /**
     * Turns the other renderers of [plan]'s track type off, so the chosen side takes the track at
     * the next prepare. Every other track type is left as it is.
     */
    fun apply(player: ExoPlayer, plan: Plan) {
        val selector = player.trackSelector as? DefaultTrackSelector ?: return
        val parameters = selector.buildUponParameters()
        for (index in 0 until player.rendererCount) {
            if (player.getRendererType(index) != plan.trackType) continue
            parameters.setRendererDisabled(index, isFfmpeg(player.getRenderer(index)) != plan.toFfmpeg)
        }
        selector.setParameters(parameters)
    }

    /**
     * Whether the picture of the current item went to the app's FFmpeg decoder, read from the
     * track selector's mapping (each track group is mapped to exactly one renderer): a picture
     * group mapped to an FFmpeg video renderer that is not turned off. Cover art is not counted.
     */
    fun softwareVideoChosen(player: ExoPlayer?): Boolean {
        player ?: return false
        val selector = player.trackSelector as? DefaultTrackSelector ?: return false
        val mapped = selector.currentMappedTrackInfo ?: return false
        val parameters = selector.parameters
        for (index in 0 until minOf(mapped.rendererCount, player.rendererCount)) {
            if (mapped.getRendererType(index) != C.TRACK_TYPE_VIDEO) continue
            if (!isFfmpeg(player.getRenderer(index)) || parameters.getRendererDisabled(index)) continue
            val groups = mapped.getTrackGroups(index)
            for (group in 0 until groups.length) {
                val trackGroup = groups.get(group)
                val picture = (0 until trackGroup.length).any {
                    trackGroup.getFormat(it).sampleMimeType?.startsWith("image/") != true &&
                        mapped.getTrackSupport(index, group, it) == C.FORMAT_HANDLED
                }
                if (picture) return true
            }
        }
        return false
    }

    private fun ffmpegHas(mime: String): Boolean = runCatching {
        FfmpegLibrary.isAvailable() && FfmpegLibrary.supportsFormat(mime)
    }.getOrDefault(false)

    private fun hasMediaCodec(mime: String): Boolean = runCatching {
        MediaCodecUtil.getDecoderInfos(mime, false, false).isNotEmpty()
    }.getOrDefault(false)
}
