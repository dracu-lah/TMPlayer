package com.tmplayer.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import com.tmplayer.i18n.L
import com.tmplayer.player.demux.FfmpegFormats

/**
 * What the failure sheet says about the format that would not play (EG-F37): [FormatLine]'s line
 * from the Media3 formats in hand, and the device's decoder names for "Copy details". The words
 * themselves are core's; this reads the player.
 */
@UnstableApi
object FailedFormat {

    /** The picture and the sound the failure was about, with whether the FFmpeg decoder had it. */
    data class Of(val video: Format?, val audio: Format?, val softwareDecoder: Boolean)

    /**
     * Whether the sheet shows the line for [error]: a container or a decoder said no (the 3000s
     * parsing, 4000s decoding, 5000s audio output and 6000s DRM codes). A lost connection or a
     * full disk is not about the format.
     */
    fun relevant(error: PlaybackException?): Boolean = error != null && error.errorCode in 3000..6999

    /**
     * The picture and the sound of [player]: the failing renderer's own format first (what it was
     * handed), then the tracks the player lists, selected ones before the rest (a track no decoder
     * claims is listed all the same), then what was playing. Cover art is not a picture here.
     */
    fun of(player: ExoPlayer?, failing: Format?, softwareDecoder: Boolean): Of {
        var video: Format? = null
        var audio: Format? = null
        val type = failing?.sampleMimeType
        if (type != null && MimeTypes.isVideo(type)) video = failing
        if (type != null && MimeTypes.isAudio(type)) audio = failing
        val tracks = runCatching { player?.currentTracks }.getOrNull()
        fun listed(kind: Int): Format? = tracks?.groups.orEmpty()
            .filter { it.type == kind && it.length > 0 }
            .sortedByDescending { it.isSelected }
            .firstNotNullOfOrNull { group ->
                (0 until group.length).map(group::getTrackFormat).firstOrNull { it.sampleMimeType?.startsWith("image/") != true }
            }
        if (video == null) video = listed(C.TRACK_TYPE_VIDEO) ?: runCatching { player?.videoFormat }.getOrNull()
        if (audio == null) audio = listed(C.TRACK_TYPE_AUDIO) ?: runCatching { player?.audioFormat }.getOrNull()
        return Of(video, audio, softwareDecoder)
    }

    fun facts(of: Of): FormatLine.Facts {
        val video = of.video
        val audio = of.audio
        return FormatLine.Facts(
            videoMime = video?.sampleMimeType,
            videoCodecs = video?.codecs,
            width = video?.width?.coerceAtLeast(0) ?: 0,
            height = video?.height?.coerceAtLeast(0) ?: 0,
            frameRate = FrameRates.plausible(video?.frameRate ?: 0f),
            transfer = transferOf(video),
            audioMime = audio?.sampleMimeType,
            audioCodecs = audio?.codecs,
            channels = audio?.channelCount?.coerceAtLeast(0) ?: 0,
            containerMime = (video ?: audio)?.containerMimeType,
        )
    }

    private fun transferOf(video: Format?): FormatLine.Transfer = when (video?.colorInfo?.colorTransfer) {
        C.COLOR_TRANSFER_ST2084 -> FormatLine.Transfer.Hdr10
        C.COLOR_TRANSFER_HLG -> FormatLine.Transfer.Hlg
        C.COLOR_TRANSFER_SDR, C.COLOR_TRANSFER_GAMMA_2_2, C.COLOR_TRANSFER_SRGB -> FormatLine.Transfer.Sdr
        else -> FormatLine.Transfer.Unknown
    }

    /** "read by FFmpeg" when the container reader was ours, "software decoder" when the FFmpeg decoder had the track. */
    fun extras(of: Of, ffmpegWord: String = L.playerErrorViaFfmpeg, softwareWord: String = L.playerErrorViaSoftware): List<String> = buildList {
        val container = (of.video ?: of.audio)?.containerMimeType
        if (FfmpegFormats.demuxerOf(container) != null) add(ffmpegWord)
        if (of.softwareDecoder) add(softwareWord)
    }

    /** The one line, empty when nothing is known of the format. */
    fun line(of: Of, ffmpegWord: String = L.playerErrorViaFfmpeg, softwareWord: String = L.playerErrorViaSoftware): String =
        FormatLine.line(facts(of), extras(of, ffmpegWord, softwareWord))

    /** The device's decoders for the picture and the sound, for the clipboard; never throws. */
    fun decoders(of: Of): List<FormatLine.Decoders> = listOfNotNull(of.video, of.audio)
        .mapNotNull { it.sampleMimeType }
        .distinct()
        .map { mime ->
            // An FFmpeg named type is the app's own decoder: the device has no MediaCodec for it.
            val device = if (mime.startsWith(FfmpegFormats.VIDEO_PREFIX) || mime.startsWith(FfmpegFormats.AUDIO_PREFIX)) {
                emptyList()
            } else {
                runCatching { MediaCodecUtil.getDecoderInfos(mime, false, false).map { it.name } }.getOrDefault(emptyList())
            }
            FormatLine.Decoders(mime, device)
        }
}
