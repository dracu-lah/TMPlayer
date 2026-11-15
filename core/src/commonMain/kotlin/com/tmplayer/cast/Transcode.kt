package com.tmplayer.cast

/** Why a cast has to be made again on this device rather than sent or rewrapped. */
enum class TranscodeReason {
    /** A picture codec the receiver does not decode (MPEG-4 Part 2, MPEG-2, VC-1, HEVC on an old Chromecast). */
    VideoCodec,

    /** 10 bit or HDR on a receiver that shows 8 bit only. */
    TenBit,

    /** Past 1080p on a 1080p receiver. */
    Resolution,

    /** A wrapper with no rewrap here (MPEG-TS, AVI, other). */
    Container,

    /** Sound the receiver cannot play and nothing else here converts. */
    AudioCodec,
}

/** What the sound becomes in a transcode. */
enum class TranscodeAudio { Copy, Aac, Ac3, None }

/** The wrapper a transcode writes: fragmented MP4 for Cast and most DLNA TVs, MPEG-TS for a TV that lists TS alone. */
enum class TranscodeContainer(val mime: String, val extension: String) {
    Fmp4("video/mp4", "mp4"),
    MpegTs("video/mp2t", "ts"),
}

/**
 * The output of one transcode: H.264 at [width] by [height] (0 for the source's size, which
 * [copyVideo] keeps as it is), [videoKbps] and at most [maxFps]; the sound as [audio] at
 * [audioKbps] and [audioChannels]; in [container]. [toneMap] turns HDR into SDR for an 8 bit receiver.
 */
data class TranscodeTarget(
    val copyVideo: Boolean,
    val width: Int,
    val height: Int,
    val videoKbps: Int,
    val maxFps: Int,
    val audio: TranscodeAudio,
    val audioKbps: Int,
    val audioChannels: Int,
    val container: TranscodeContainer = TranscodeContainer.Fmp4,
    val toneMap: Boolean = false,
) {
    val mime: String get() = container.mime
}

/**
 * What this device can make, or null where it cannot transcode at all (the TV build, a phone
 * without an H.264 encoder, a computer without ffmpeg).
 */
data class TranscodeAbility(
    /** The largest picture the H.264 encoder takes; the planner never asks for more than 1080p anyway. */
    val maxWidth: Int = 1920,
    val maxHeight: Int = 1080,
    /** The encoder's own top bitrate, in kb/s. */
    val maxVideoKbps: Int = 20_000,
    /** The encoder is hardware (fast and cool); a software one is held to 30 fps. */
    val hardware: Boolean = true,
    /** Picture codecs the device decodes for the transcode; null for anything (ffmpeg). */
    val videoDecoders: Set<String>? = null,
    /** Sound codecs the device decodes for the transcode; null for anything (ffmpeg). */
    val audioDecoders: Set<String>? = null,
    /** An AC-3 encoder is there (ffmpeg's); Android's MediaCodec has none. */
    val ac3: Boolean = false,
    val containers: Set<TranscodeContainer> = setOf(TranscodeContainer.Fmp4),
    /** Picture codecs its muxer carries as they are; null for every one MP4 takes (ffmpeg). */
    val passVideo: Set<String>? = null,
    /** Sound codecs its muxer carries as they are; null for every one MP4 takes (ffmpeg). Media3's fMP4 muxer: AAC and Opus. */
    val passAudio: Set<String>? = null,
) {
    fun decodesVideo(codec: String?): Boolean = videoDecoders == null || (codec != null && codec in videoDecoders)
    fun decodesAudio(codec: String?): Boolean = audioDecoders == null || (codec != null && codec in audioDecoders)
    fun passesVideo(codec: String?): Boolean = codec != null && codec in CastFormats.MP4_VIDEO + Codecs.VP9 && (passVideo == null || codec in passVideo)
    fun passesAudio(codec: String?): Boolean = codec != null && codec in CastFormats.MP4_AUDIO && (passAudio == null || codec in passAudio)
}

/**
 * The pure half of transcoding for a cast: what to make, from what the file is ([MediaProbe]), what
 * the receiver plays ([ReceiverCaps]), what this device can make ([TranscodeAbility]) and how fast
 * the link to the receiver is. Nothing here reads a file or starts a codec.
 */
object TranscodePlanner {

    /** One step of the ladder: a picture height and the H.264 bitrate that looks clean at it. */
    data class Rung(val height: Int, val kbps: Int)

    val LADDER = listOf(Rung(1080, 8_000), Rung(720, 4_500), Rung(540, 2_500), Rung(480, 1_800), Rung(360, 1_000))

    /** The share of the measured link a transcode may use, the rest left for the link's ups and downs. */
    const val LINK_SHARE = 0.6

    const val AAC_KBPS = 192
    const val AC3_KBPS = 640

    /** Past this, a stated frame rate is nonsense (a 1 ns frame) and is not believed. */
    private const val MAX_BELIEVED_FPS = 240

    /**
     * The target for [probe] on a receiver with [caps], with [audio] the soundtrack sent (null for
     * none or a file with no tracks known). [copyVideo] passes the picture as it is (only the sound
     * or the wrapper is the trouble). [linkKbps] is the measured link, or null when not known.
     */
    fun target(
        probe: MediaProbe,
        caps: ReceiverCaps,
        ability: TranscodeAbility,
        audio: AudioTrack?,
        copyVideo: Boolean,
        linkKbps: Int? = null,
        receiverContainers: Set<Container>? = null,
    ): TranscodeTarget {
        val video = probe.video
        val (w, h) = if (copyVideo) 0 to 0 else size(video, ability, linkKbps)
        // By the picture's area, so a wide 1920 by 800 gets the 1080p rate and not 720p's.
        val effective = kotlin.math.sqrt(w.toDouble() * h * 9 / 16)
        val rung = LADDER.lastOrNull { it.height >= effective } ?: LADDER.first()
        val kbps = if (copyVideo) 0 else bitrate(rung, ability, linkKbps)
        val sourceFps = video?.defaultDurationNs?.takeIf { it > 0 }?.let { ((1_000_000_000L + it / 2) / it).toInt() }?.takeIf { it in 1..MAX_BELIEVED_FPS }
        val cap = if (!ability.hardware || h > 1080) 30 else 60
        val fps = sourceFps?.coerceAtMost(cap) ?: cap
        val sound = audioFor(audio, caps, ability)
        val channels = when (sound) {
            TranscodeAudio.Ac3 -> 6
            TranscodeAudio.Aac -> 2
            TranscodeAudio.Copy -> audio?.channels?.takeIf { it in 1..8 } ?: 2
            TranscodeAudio.None -> 0
        }
        val audioKbps = when (sound) {
            TranscodeAudio.Ac3 -> AC3_KBPS
            TranscodeAudio.Aac -> AAC_KBPS
            else -> 0
        }
        val container = if (
            receiverContainers != null && Container.Mp4 !in receiverContainers && Container.MpegTs in receiverContainers &&
            TranscodeContainer.MpegTs in ability.containers
        ) TranscodeContainer.MpegTs else TranscodeContainer.Fmp4
        return TranscodeTarget(
            copyVideo = copyVideo,
            width = w,
            height = h,
            videoKbps = kbps,
            maxFps = fps,
            audio = sound,
            audioKbps = audioKbps,
            audioChannels = channels,
            container = container,
            toneMap = !copyVideo && video?.tenBit == true && caps.tenBit != Support.Yes,
        )
    }

    /**
     * [route] made again with the link and the receiver's wrappers as they are now: a stream that
     * starts later (a seek, a reload, another soundtrack) plans from what has been learned since
     * the cast was planned. The soundtrack and the copy-or-convert choice stay as they were.
     */
    fun retarget(
        route: CastRoute.Transcode,
        probe: MediaProbe,
        caps: ReceiverCaps,
        ability: TranscodeAbility,
        linkKbps: Int?,
        receiverContainers: Set<Container>?,
    ): CastRoute.Transcode {
        val track = route.audioTrack?.let { n -> probe.audio.firstOrNull { it.number == n } }
        return route.copy(target = target(probe, caps, ability, track, route.target.copyVideo, linkKbps, receiverContainers))
    }

    /**
     * The output size: the source's, scaled down to fit 1080p, the encoder's limit and the rung the
     * link allows, sides even and the shape kept. A source of unknown size gets the rung's 16:9.
     */
    fun size(video: VideoTrack?, ability: TranscodeAbility, linkKbps: Int?): Pair<Int, Int> {
        val linkRung = linkKbps?.takeIf { it > 0 }?.let { k -> LADDER.firstOrNull { it.kbps <= k * LINK_SHARE } ?: LADDER.last() }
        val maxH = even(minOf(1080, ability.maxHeight.coerceAtLeast(144), linkRung?.height ?: 1080))
        val maxW = minOf(1920, ability.maxWidth.coerceAtLeast(256), maxH * 16 / 9 + 16).let { even(it) }
        val sw = video?.width?.takeIf { it in 16..16_384 }
        val sh = video?.height?.takeIf { it in 16..16_384 }
        if (sw == null || sh == null) return even(maxH * 16 / 9).coerceAtMost(maxW) to even(maxH)
        val scale = minOf(1.0, maxW.toDouble() / sw, maxH.toDouble() / sh)
        return even(Math.round(sw * scale).toInt()).coerceIn(16, maxW) to even(Math.round(sh * scale).toInt()).coerceIn(16, maxH)
    }

    private fun bitrate(rung: Rung, ability: TranscodeAbility, linkKbps: Int?): Int {
        val link = linkKbps?.takeIf { it > 0 }?.let { (it * LINK_SHARE).toInt() } ?: Int.MAX_VALUE
        return minOf(rung.kbps, ability.maxVideoKbps.coerceAtLeast(500), link).coerceAtLeast(500)
    }

    /**
     * Sound as it is when the receiver plays it and MP4 carries it; AC-3 5.1 for a receiver that
     * takes Dolby, a source with more than two channels and an encoder that makes AC-3; else AAC
     * stereo; nothing when this device cannot decode the source's sound.
     */
    fun audioFor(audio: AudioTrack?, caps: ReceiverCaps, ability: TranscodeAbility): TranscodeAudio = when {
        audio == null -> TranscodeAudio.None
        ability.passesAudio(audio.codec) && CastFormats.audioSupport(audio.codec, caps) == Support.Yes -> TranscodeAudio.Copy
        !ability.decodesAudio(audio.codec) -> TranscodeAudio.None
        ability.ac3 && caps.dolbyPassthrough == Support.Yes && audio.channels > 2 -> TranscodeAudio.Ac3
        else -> TranscodeAudio.Aac
    }

    private fun even(v: Int): Int = v and 1.inv()
}

/** What is known of the device's state, for [TranscodeGuard]; null where the platform cannot say. */
data class DeviceHealth(
    val batteryPercent: Int? = null,
    val charging: Boolean? = null,
    val thermal: Thermal = Thermal.Unknown,
) {
    enum class Thermal { Unknown, None, Light, Moderate, Severe, Critical, Emergency, Shutdown }
}

/** Why a transcode was stopped before its end. */
enum class TranscodeStop { Cancelled, TooHot, BatteryLow, ReceiverGone, Failed, NoEncoder }

/** The battery and heat limits a transcode keeps to: past them it stops, said so. */
object TranscodeGuard {
    const val BATTERY_FLOOR = 15
    const val CHECK_EVERY_MS = 5_000L

    /** Slower than real time for this long and the screen says the TV will pause to catch up. */
    const val SLOW_AFTER_MS = 20_000L

    fun check(health: DeviceHealth): TranscodeStop? = when {
        health.thermal >= DeviceHealth.Thermal.Severe -> TranscodeStop.TooHot
        health.charging != true && health.batteryPercent != null && health.batteryPercent in 0 until BATTERY_FLOOR -> TranscodeStop.BatteryLow
        else -> null
    }
}

/** Where a running transcode is: [positionMs] of the video made so far, at [speed] times real time. */
data class TranscodeProgress(
    val startMs: Long,
    val positionMs: Long,
    val durationMs: Long,
    val speed: Float,
    /** Slower than real time for [TranscodeGuard.SLOW_AFTER_MS]: the TV will pause to catch up. */
    val slow: Boolean = false,
    val done: Boolean = false,
    val stop: TranscodeStop? = null,
    val message: String? = null,
) {
    /** How much of the whole video is made, 0 to 1, or null when its length is not known. */
    val fraction: Float? get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else null
}
