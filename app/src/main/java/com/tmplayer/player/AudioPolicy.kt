package com.tmplayer.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Tracks
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
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
 * A2 is passthrough off ([PcmOnlyOutputProvider]): the output refuses Dolby and DTS, so the device's
 * own decoder decodes the track. It is the next step only where FFmpeg has no decoder for the format
 * and the device has one ([deviceDecodes]); with neither, the engine hop is next, never a silent track.
 */
@UnstableApi
object AudioPolicy {

    /** The linear PCM encodings an AudioTrack can be opened with: everything else is a bitstream (WP-24). */
    private val PCM_ENCODINGS = setOf(
        C.ENCODING_PCM_8BIT, C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN, C.ENCODING_PCM_24BIT,
        C.ENCODING_PCM_24BIT_BIG_ENDIAN, C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN, C.ENCODING_PCM_FLOAT,
    )

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

    /**
     * Whether an AudioTrack opened with [encoding] carries a bitstream (Dolby, DTS) rather than PCM
     * (WP-24). Volume boost and Night mode shape PCM only: on a bitstream the sound goes to the
     * speakers or the receiver untouched, so the lines about them say "Not applied" ([boostReaches]).
     */
    fun bitstreamEncoding(encoding: Int): Boolean =
        encoding != C.ENCODING_INVALID && encoding !in PCM_ENCODINGS

    /** Whether Volume boost and Night mode change the sound now: not while it plays as a bitstream. */
    fun boostReaches(bitstream: Boolean): Boolean = !bitstream

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

    /**
     * Whether [format] is Dolby's or DTS's (AC-3, E-AC-3, AC-4, TrueHD, DTS and its kinds): what
     * Audio passthrough is about. AAC or MP3 a receiver takes as it is are left alone.
     */
    fun dolbyOrDts(format: Format?): Boolean = format?.sampleMimeType in DOLBY_DTS

    /**
     * The most channels any of the device's own decoders for [mime] takes, or null with no decoder
     * for it. A decoder that does not say (no audio capabilities) counts as taking any number.
     */
    fun deviceDecoderChannels(mime: String): Int? = runCatching {
        val infos = MediaCodecUtil.getDecoderInfos(mime, false, false)
        if (infos.isEmpty()) null else infos.maxOf { it.capabilities?.audioCapabilities?.maxInputChannelCount ?: Int.MAX_VALUE }
    }.getOrNull()

    /**
     * Whether one of this device's own decoders takes [format] (TV sticks often have none for TrueHD
     * or DTS-HD), looked up the way Media3's audio renderer looks: the format's own type, then its
     * alternative ([MediaCodecUtil.getAlternativeCodecMimeType]: E-AC-3 JOC, Atmos, falls back to an
     * E-AC-3 decoder; DTS-HD and DTS:X to a DTS decoder, which plays their core). A decoder that
     * takes fewer channels than the track has does not count, as far as it says ([channels] is
     * [deviceDecoderChannels] outside tests); a decoder that says nothing is trusted.
     */
    fun deviceDecodes(format: Format?, channels: (String) -> Int? = ::deviceDecoderChannels): Boolean {
        val mime = format?.sampleMimeType
        if (mime.isNullOrBlank() || !MimeTypes.isAudio(mime)) return false
        val alternative = runCatching { MediaCodecUtil.getAlternativeCodecMimeType(format) }.getOrNull()
        val needed = format.channelCount
        return listOfNotNull(mime, alternative).distinct().any { type ->
            val most = runCatching { channels(type) }.getOrNull() ?: return@any false
            needed <= 0 || most >= needed
        }
    }

    /** Whether a decoder here, the device's ([deviceDecodes]) or FFmpeg's ([softwareTakes]), turns [format] into PCM. */
    fun decodable(
        format: Format?,
        channels: (String) -> Int? = ::deviceDecoderChannels,
        ffmpeg: (String?) -> Boolean = ::softwareTakes,
    ): Boolean = deviceDecodes(format, channels) || ffmpeg(format?.sampleMimeType)

    /**
     * Settings' Audio passthrough as the player applies it: the row is the television's, so on a
     * phone or tablet (an HDMI, USB or Bluetooth output among them) a value restored from a TV's
     * backup is not followed, and passthrough stays allowed there.
     */
    fun passthroughAllowed(stored: Boolean, television: Boolean): Boolean = stored || !television

    /**
     * True when [tracks] hold sound and none of it can play here: no track selected and none
     * supported (a format with no decoder on the device and none in FFmpeg). Media3 raises no
     * error for it; it plays the picture in silence, so the player says why.
     */
    fun soundUnplayable(tracks: Tracks): Boolean {
        val sound = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        if (sound.isEmpty() || sound.any { it.isSelected }) return false
        return sound.none { group -> (0 until group.length).any { group.isTrackSupported(it, true) } }
    }

    /** Whether the app's FFmpeg decoder takes sound of [mime] on this device. */
    fun softwareTakes(mime: String?): Boolean {
        if (mime.isNullOrBlank() || !MimeTypes.isAudio(mime)) return false
        return runCatching { FfmpegLibrary.isAvailable() && FfmpegLibrary.supportsFormat(mime) }.getOrDefault(false)
    }

    private const val MAX_CAUSES = 6

    private val DOLBY_DTS = setOf(
        MimeTypes.AUDIO_AC3, MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC, MimeTypes.AUDIO_AC4, MimeTypes.AUDIO_TRUEHD,
        MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_DTS_EXPRESS, MimeTypes.AUDIO_DTS_X,
    )
}

/**
 * The once per video "no sound here" line ([AudioPolicy.soundUnplayable]): said the first time the
 * tracks show sound that cannot play, never again for that video, and not while another engine
 * plays. Tracks whose sound plays do not use it up, so a rebuild that loses the sound still says so.
 */
internal class SoundNotice {
    private var said = false

    /** True when the line is to be said now for [tracks]. */
    fun shouldSay(tracks: Tracks, otherEngine: Boolean): Boolean {
        if (said || otherEngine || !AudioPolicy.soundUnplayable(tracks)) return false
        said = true
        return true
    }

    /** A new video: it may be said again. */
    fun newVideo() {
        said = false
    }
}
