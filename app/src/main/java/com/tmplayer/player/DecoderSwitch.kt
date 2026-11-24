package com.tmplayer.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererCapabilities
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
 * Turning a side off is done through [DecoderGate], not the track selector's disabled renderers
 * (PB2-01): Media3 maps each track group to one renderer by its format support alone
 * (`MappingTrackSelector.findRenderer`, the first renderer winning a tie) and only afterwards drops
 * the selection of a disabled renderer, so a group both decoders take stayed mapped to the side
 * turned off and played with no picture (or no sound). The gate makes the side turned off answer
 * that it cannot take the format at all, so the mapping itself moves the group to the other side.
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

    fun isFfmpeg(renderer: Renderer): Boolean = when (renderer) {
        is GatedRenderer -> renderer.ffmpeg
        else -> renderer is FfmpegVideoRenderer || renderer is FfmpegAudioRenderer
    }

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
     * Turns the other renderers of [plan]'s track type off ([DecoderGate]), so the chosen side takes
     * the track at the next prepare. Every other track type is left as it is. False when the player
     * was not built with gated renderers, so nothing could be switched.
     */
    fun apply(player: ExoPlayer, plan: Plan): Boolean = apply((0 until player.rendererCount).map { player.getRenderer(it) }, plan)

    /** [apply] over the renderers themselves: the gate they share takes the choice. */
    fun apply(renderers: List<Renderer>, plan: Plan): Boolean {
        val gate = renderers.firstNotNullOfOrNull { (it as? GatedRenderer)?.gate } ?: return false
        gate.choose(plan.trackType, toFfmpeg = plan.toFfmpeg)
        return true
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
            val renderer = player.getRenderer(index)
            if (!isFfmpeg(renderer) || parameters.getRendererDisabled(index)) continue
            if ((renderer as? GatedRenderer)?.open() == false) continue
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

/**
 * Which decoder side of each track type is turned off for one player, shared by its
 * [GatedRenderer]s ([DecoderSwitch.apply]). Set on the main thread, read on the playback thread
 * whenever tracks are selected. Nothing is off until a switch: a new player starts with both on.
 */
class DecoderGate {

    /** Track type to whether its FFmpeg side is the one turned off (false: the device's side). */
    @Volatile
    private var off: Map<Int, Boolean> = emptyMap()

    /** [trackType] goes to FFmpeg's decoder ([toFfmpeg]) or the device's: the other side is turned off. */
    fun choose(trackType: Int, toFfmpeg: Boolean) {
        off = off + (trackType to !toFfmpeg)
    }

    /** Whether the [ffmpeg] side (or the device's) of [trackType] may take a track. */
    fun allows(trackType: Int, ffmpeg: Boolean): Boolean = off[trackType] != ffmpeg
}

/**
 * A video or sound renderer behind a [DecoderGate]: while the gate has its side turned off, its
 * capabilities answer that it cannot take any format, so the track selector maps the track to the
 * other side (PB2-01). Every other call goes to the renderer inside as it is.
 */
@UnstableApi
class GatedRenderer(renderer: Renderer, val gate: DecoderGate) : ForwardingRenderer(renderer) {

    /** Whether the renderer inside is the app's FFmpeg decoder, read once as it is built. */
    val ffmpeg: Boolean = DecoderSwitch.isFfmpeg(renderer)

    private val type: Int = renderer.getTrackType()

    private val gated: RendererCapabilities by lazy { GatedCapabilities(super.getCapabilities(), gate, ffmpeg) }

    override fun getCapabilities(): RendererCapabilities = gated

    /** Whether the gate lets this renderer take a track now. */
    fun open(): Boolean = gate.allows(type, ffmpeg)
}

/** [inner]'s answers while the [gate] lets its side take a track, none otherwise. See [GatedRenderer]. */
@UnstableApi
class GatedCapabilities(
    private val inner: RendererCapabilities,
    private val gate: DecoderGate,
    private val ffmpeg: Boolean,
) : RendererCapabilities by inner {

    override fun supportsFormat(format: Format): Int =
        if (gate.allows(inner.getTrackType(), ffmpeg)) {
            inner.supportsFormat(format)
        } else {
            RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE)
        }
}
