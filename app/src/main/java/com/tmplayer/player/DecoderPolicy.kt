package com.tmplayer.player

import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

/**
 * How the next ExoPlayer is built for the remedy ladder (RL5, research 4.1): the path
 * [RemedyLadder] chose ([RemedyPath]) turned into Media3's settings, one place, read once per
 * build of the player and logged there.
 *
 * - [excluded] decoder names are left out of the codec list ([ExcludingCodecSelector], R1c), so a
 *   decoder that failed for this class is never offered again; the next listed one, or the app's
 *   FFmpeg decoder behind it, takes the picture.
 * - [async] MediaCodec queueing (R1a): Media3 queues asynchronously from API 31 by itself; [Async.On]
 *   asks for it below, [Async.Off] (a known device that is worse with it) keeps it off above.
 * - [softwareVideo] puts the app's FFmpeg video decoder ahead of the device's (R2), the same order
 *   "Prefer software video" builds; [softwareAudio] does the same for the sound (A1, [AudioPolicy]).
 * - [tunnelingOff] keeps tunneled playback off whatever Auto would pick (R1b).
 * - [passthroughOff] keeps every sound track plain PCM (A2, [PcmOnlyOutputProvider]): a Dolby or
 *   DTS bitstream the output says it takes is decoded here instead, by the device's decoder or
 *   FFmpeg's. The ladder's hop sets it, and so does Settings' Audio passthrough turned off.
 *
 * Decoder fallback stays on in every policy: Media3 then tries the next decoder when one fails to
 * start, which is the first rung of all and costs nothing.
 */
@UnstableApi
data class DecoderPolicy(
    val excluded: Set<String> = emptySet(),
    val async: Async = Async.Default,
    val softwareVideo: Boolean = false,
    val softwareAudio: Boolean = false,
    val tunnelingOff: Boolean = false,
    val passthroughOff: Boolean = false,
) {
    enum class Async { Default, On, Off }

    /**
     * This policy under Settings' Audio passthrough: [allowed] (on, the default) leaves it as it
     * is, off keeps the sound PCM whatever the ladder's path says. A path that turned it off stays off.
     */
    fun allowingPassthrough(allowed: Boolean): DecoderPolicy = if (allowed || passthroughOff) this else copy(passthroughOff = true)

    /** The codec list Media3 asks: [base] as it is, or without [excluded] for the picture. */
    fun selector(base: MediaCodecSelector = MediaCodecSelector.DEFAULT): MediaCodecSelector =
        if (excluded.isEmpty()) base else ExcludingCodecSelector(base, excluded)

    /** Sets [factory] up for this policy: the codec list, the queueing mode and decoder fallback on. */
    fun <T : DefaultRenderersFactory> applyTo(factory: T): T {
        factory.setMediaCodecSelector(selector())
        when (async) {
            Async.On -> factory.forceEnableMediaCodecAsynchronousQueueing()
            Async.Off -> factory.forceDisableMediaCodecAsynchronousQueueing()
            Async.Default -> Unit
        }
        factory.setEnableDecoderFallback(true)
        return factory
    }

    /** The extension mode for the whole factory: FFmpeg's video first when the viewer or this policy asks. */
    fun extensionMode(preferSoftwareVideo: Boolean): Int =
        if (preferSoftwareVideo || softwareVideo) {
            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
        } else {
            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
        }

    /** The line logged as the player is built, so a report shows the path every video played on. */
    fun describe(): String = buildString {
        append("Decoder policy: fallback on, async ").append(async.name.lowercase())
        append(", picture ").append(if (softwareVideo) "software first" else "device first")
        append(", sound ").append(if (softwareAudio) "software first" else "device first")
        if (tunnelingOff) append(", tunneling off")
        if (passthroughOff) append(", passthrough off")
        if (excluded.isNotEmpty()) append(", left out ").append(excluded.sorted().joinToString(","))
    }

    /**
     * [describe] as a short class for the stutter report (`async_default+pic_hw+snd_hw+tun_auto+out_0`):
     * the same facts in fixed words, the left out decoders as a count only.
     */
    fun shortClass(): String = buildString {
        append("async_").append(async.name.lowercase())
        append("+pic_").append(if (softwareVideo) "sw" else "hw")
        append("+snd_").append(if (softwareAudio) "sw" else "hw")
        append("+tun_").append(if (tunnelingOff) "off" else "auto")
        append("+out_").append(excluded.size.coerceAtMost(9))
        // Only when off, so every report written before A2 keeps its class.
        if (passthroughOff) append("+pt_off")
    }

    companion object {
        val Default = DecoderPolicy()

        /**
         * The policy for [path] on a device with API [sdk], where the known device list says
         * [knownAsync] about async queueing (null: nothing).
         */
        fun of(path: RemedyPath, knownAsync: Boolean?, sdk: Int): DecoderPolicy {
            val async = when {
                path.asyncFlipped -> Async.On
                knownAsync == true && sdk in 1 until RemedyLadder.ASYNC_DEFAULT_SDK -> Async.On
                knownAsync == false && sdk >= RemedyLadder.ASYNC_DEFAULT_SDK -> Async.Off
                else -> Async.Default
            }
            return DecoderPolicy(
                excluded = path.excluded,
                async = async,
                softwareVideo = path.software,
                softwareAudio = path.audioSoftware,
                tunnelingOff = path.tunnelingOff,
                passthroughOff = path.passthroughOff,
            )
        }
    }
}

/**
 * [base]'s decoders without the names in [excluded] (any case), for a picture's MIME type only:
 * the sound's decoders are never touched. When every decoder for a picture is left out the list is
 * kept whole, so an exclusion alone never makes a picture the device can show into none at all.
 */
@UnstableApi
class ExcludingCodecSelector(
    private val base: MediaCodecSelector,
    excluded: Collection<String>,
) : MediaCodecSelector {

    private val names = excluded.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()

    override fun getDecoderInfos(
        mimeType: String,
        requiresSecureDecoder: Boolean,
        requiresTunnelingDecoder: Boolean,
    ): List<MediaCodecInfo> {
        val all = base.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
        if (names.isEmpty() || !MimeTypes.isVideo(mimeType)) return all
        val kept = all.filter { it.name.lowercase() !in names }
        return if (kept.isEmpty()) all else kept
    }
}
