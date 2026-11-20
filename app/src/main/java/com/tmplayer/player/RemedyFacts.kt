package com.tmplayer.player

import android.os.Build
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import com.tmplayer.data.DecodeJudge
import com.tmplayer.data.RemedyClassKey
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegLibrary

/**
 * What [RemedyRunner] reads off ExoPlayer and the device (RL6), kept out of the player screen:
 * the sample for the watches, the ladder's facts, the class key and the renderer behind an error.
 * Every read is guarded: a codec list that throws, a player released, a format missing all give
 * the quiet answer (no other decoder, no software, no key), never a crash.
 */
@UnstableApi
object RemedyFacts {

    /** One look at [exo] for the watches; [hidden] when the picture is off on purpose. */
    fun sample(exo: ExoPlayer, hidden: Boolean, nowMs: Long): RemedyRunner.Sample {
        val counters = exo.videoDecoderCounters?.also { it.ensureUpdated() }
        val tracks = exo.currentTracks
        val picture = selected(exo, C.TRACK_TYPE_VIDEO)
        val playing = exo.isPlaying
        return RemedyRunner.Sample(
            nowMs = nowMs,
            ready = exo.playbackState == Player.STATE_READY,
            buffering = exo.playbackState == Player.STATE_BUFFERING,
            playing = playing,
            positionMs = exo.currentPosition,
            speed = exo.playbackParameters.speed,
            rendered = counters?.renderedOutputBufferCount?.toLong() ?: 0L,
            dropped = counters?.droppedBufferCount?.toLong() ?: 0L,
            skipped = counters?.skippedOutputBufferCount?.toLong() ?: 0L,
            audioPlaying = playing && tracks.isTypeSelected(C.TRACK_TYPE_AUDIO),
            pictureExpected = picture != null && picture.sampleMimeType?.startsWith("image/") != true,
            hidden = hidden,
            software = DecoderSwitch.softwareVideoChosen(exo),
        )
    }

    /**
     * The ladder's facts for [exo] now: [decoderName] is the video decoder Media3 last started,
     * [excluded] the names already left out, the rest the viewer's settings. [passthrough] is
     * whether the player was built able to pass a bitstream through (Settings allows it and no
     * hop turned it off); the runner narrows it to a refused bitstream when the sound fails.
     */
    fun ladder(
        exo: ExoPlayer?,
        decoderName: String?,
        excluded: Set<String>,
        decoderPicked: Boolean,
        tunneling: Boolean,
        tunnelingPicked: Boolean,
        passthrough: Boolean = false,
    ): LadderFacts {
        val video = exo?.let { selected(it, C.TRACK_TYPE_VIDEO) }
        val audio = exo?.let { selected(it, C.TRACK_TYPE_AUDIO) }
        val software = exo != null && DecoderSwitch.softwareVideoChosen(exo)
        // FFmpeg's name is no device decoder to leave out.
        val name = decoderName?.trim()?.takeIf { it.isNotEmpty() && !software }
        val mime = video?.sampleMimeType
        return LadderFacts(
            sdk = Build.VERSION.SDK_INT,
            decoderName = name,
            otherDecoder = name != null && mime != null && otherDecoder(mime, name, excluded),
            softwareTakes = !software && mime != null && ffmpegTakes(mime),
            softwareVerdict = video?.let { runCatching { DeviceDecode.verdictOf(it) }.getOrNull() } ?: DecodeJudge.Verdict.UNKNOWN,
            tunneling = tunneling,
            decoderPicked = decoderPicked,
            tunnelingPicked = tunnelingPicked,
            audioSoftwareTakes = AudioPolicy.softwareTakes(audio?.sampleMimeType),
            passthrough = passthrough,
            autoSwitch = true,
        )
    }

    /** The class of what [exo] plays, from its selected formats, or null while no picture format is known. */
    fun classKey(exo: ExoPlayer?, container: String?): String? {
        exo ?: return null
        val video = selected(exo, C.TRACK_TYPE_VIDEO) ?: return null
        if (video.sampleMimeType?.startsWith("image/") == true) return null
        val picture = DeviceDecode.pictureOf(video) ?: return null
        return RemedyClassKey.of(EngineDevice.facts(), picture, video.codecs, container, selected(exo, C.TRACK_TYPE_AUDIO)?.sampleMimeType)
    }

    /** The renderer [error] came from in [exo], or null when it is not a renderer's. */
    fun failedRenderer(exo: ExoPlayer?, error: PlaybackException): RemedyRunner.FailedRenderer? {
        exo ?: return null
        val renderer = error as? ExoPlaybackException ?: return null
        if (renderer.type != ExoPlaybackException.TYPE_RENDERER) return null
        val index = renderer.rendererIndex
        if (index !in 0 until exo.rendererCount) return null
        return RemedyRunner.FailedRenderer(exo.getRendererType(index), DecoderSwitch.isFfmpeg(exo.getRenderer(index)))
    }

    /** Days since 1970. */
    fun today(): Int = (System.currentTimeMillis() / DAY_MS).toInt()

    /** A short tag for one video ([com.tmplayer.data.RemedyMemory]): a hash of its ids, never a name. */
    fun videoTag(chatId: Long, messageId: Long, fileId: Int): String =
        java.lang.Long.toHexString((chatId * 31 + messageId) * 31 + fileId).takeLast(TAG_LENGTH)

    /** The format playing for [type]: the selected track's, or the renderer's input format. */
    private fun selected(exo: ExoPlayer, type: Int): Format? {
        for (group in exo.currentTracks.groups) {
            if (group.type != type) continue
            for (index in 0 until group.length) if (group.isTrackSelected(index)) return group.getTrackFormat(index)
        }
        return if (type == C.TRACK_TYPE_VIDEO) exo.videoFormat else exo.audioFormat
    }

    private fun otherDecoder(mime: String, current: String, excluded: Set<String>): Boolean = runCatching {
        val gone = (excluded + current).map { it.lowercase() }.toSet()
        MediaCodecUtil.getDecoderInfos(mime, false, false).any { it.name.lowercase() !in gone }
    }.getOrDefault(false)

    private fun ffmpegTakes(mime: String): Boolean =
        runCatching { FfmpegLibrary.isAvailable() && FfmpegLibrary.supportsFormat(mime) }.getOrDefault(false)

    private const val DAY_MS = 86_400_000L
    private const val TAG_LENGTH = 12
}
