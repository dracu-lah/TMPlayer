package com.tmplayer.cast

import java.util.Locale

/** Yes, no, or "try it and see": what is known of a receiver's support for one thing. */
enum class Support { Yes, Maybe, No }

/**
 * What a receiver decodes, as far as can be known from the sender's side. Google's Default Media
 * Receiver runs in a Chrome build on the device, so the containers are the same everywhere (MP4,
 * WebM, MP3; Matroska only on the Google TV generation) and the codecs follow the hardware.
 * [forModel] reads the model name the device announces.
 */
data class ReceiverCaps(
    val hevc: Support = Support.Maybe,
    val vp9: Support = Support.Maybe,
    val av1: Support = Support.Maybe,
    /** 10 bit and HDR pictures in a codec it otherwise plays. */
    val tenBit: Support = Support.Maybe,
    /** A Matroska file played as it is, which the Google TV models' Chrome takes. */
    val matroska: Support = Support.Maybe,
    /** Dolby Digital and Dolby Digital Plus handed to the TV or amplifier as they are. */
    val dolbyPassthrough: Support = Support.Yes,
    /**
     * A picture larger than 1080p (4K, or a 3840 wide scope picture). A 1080p stick turns it away
     * whatever the codec: its decoder will not open at that size.
     */
    val uhd: Support = Support.Maybe,
) {
    companion object {
        /** The first three Chromecasts: H.264 and VP8 only, up to 1080p. */
        val LEGACY = ReceiverCaps(hevc = Support.No, vp9 = Support.No, av1 = Support.No, tenBit = Support.No, matroska = Support.No, uhd = Support.No)
        val ULTRA = ReceiverCaps(hevc = Support.Yes, vp9 = Support.Yes, av1 = Support.No, tenBit = Support.Yes, matroska = Support.No, uhd = Support.Yes)
        val GOOGLE_TV = ReceiverCaps(hevc = Support.Yes, vp9 = Support.Yes, av1 = Support.Maybe, tenBit = Support.Yes, matroska = Support.Maybe, uhd = Support.Yes)
        val STREAMER = ReceiverCaps(hevc = Support.Yes, vp9 = Support.Yes, av1 = Support.Yes, tenBit = Support.Yes, matroska = Support.Maybe, uhd = Support.Yes)
        val UNKNOWN = ReceiverCaps()

        /**
         * A 1080p Android TV box or stick: what a Google TV plays, at 1080p. The Google TV HD and
         * the Mi TV Stick (MiTV-AESP0, the device test's stick, whose HEVC decoder would not open
         * a 3832x1600 picture) are of this kind.
         */
        val FULL_HD_STICK = GOOGLE_TV.copy(uhd = Support.No)

        /** Model names of 1080p sticks and boxes, as they announce themselves. */
        private val FULL_HD_MODELS = setOf("chromecast hd", "google tv hd", "mitv-aesp0", "mi tv stick")

        /**
         * From the model name in the device's announcement ("md" in its mDNS record, the Cast
         * framework's model name on Android). A television or box with Cast built in is an
         * Android TV, so it plays what a Google TV does.
         */
        fun forModel(model: String?): ReceiverCaps {
            val m = model?.lowercase(Locale.ROOT)?.trim().orEmpty()
            return when {
                m.isEmpty() -> UNKNOWN
                "ultra" in m -> ULTRA
                "streamer" in m -> STREAMER
                m in FULL_HD_MODELS -> FULL_HD_STICK
                "google tv" in m || "shield" in m || "bravia" in m -> GOOGLE_TV
                // Another television or box with Cast built in: a Google TV's codecs, but nothing
                // in the name says whether its panel and decoder go past 1080p.
                "android tv" in m -> GOOGLE_TV.copy(uhd = Support.Maybe)
                m == "chromecast" -> UNKNOWN
                "audio" in m || "home" in m || "nest" in m && "hub" !in m -> ReceiverCaps(hevc = Support.No, vp9 = Support.No, av1 = Support.No, matroska = Support.No)
                "hub" in m -> LEGACY
                else -> GOOGLE_TV.copy(uhd = Support.Maybe)
            }
        }
    }
}

/** How one cast is carried out. */
sealed interface CastRoute {

    /** The file as it is, by its own bytes and with ranges. */
    data class Direct(val mime: String) : CastRoute

    /** Matroska rewrapped as fragmented MP4 on the fly, with the one [audioTrack] passed through. */
    data class Remux(val audioTrack: Int?) : CastRoute

    /** Rewrapped, with the sound of [audioTrack] turned into AAC on this device. */
    data class ConvertAudio(val audioTrack: Int) : CastRoute

    /**
     * Matroska served as HLS ([MkvHls]): the picture rewrapped into fMP4 segments, each of [audio]
     * a soundtrack rendition (converted where it says), [audioTrack] the one it starts on, and the
     * text subtitles inside the file as WebVTT renditions.
     */
    data class Hls(val audioTrack: Int?, val audio: List<HlsAudio>) : CastRoute

    /**
     * Made again on this device ([TranscodePlanner]): the picture as H.264 (or passed through where
     * [TranscodeTarget.copyVideo] says only the sound or the wrapper is the trouble), the sound of
     * [audioTrack] as the target says. The last resort, said so on screen, with its progress.
     */
    data class Transcode(val audioTrack: Int?, val reason: TranscodeReason, val target: TranscodeTarget) : CastRoute
}

/** Why a video cannot be cast at all. [Resolution]: a picture past 1080p on a 1080p receiver. */
enum class CastRefusal { VideoCodec, Resolution, AudioCodec, Container, Pictures }

/**
 * The plan for one video: the routes to try in order (a Maybe goes first and falls back on a
 * receiver error), the soundtrack chosen, and why nothing would work when nothing would.
 */
data class CastPlan(
    val routes: List<CastRoute>,
    val audioTrack: Int?,
    val refusal: CastRefusal? = null,
    /** The chosen soundtrack will not play on the receiver as sent: the picture goes alone. */
    val silent: Boolean = false,
) {
    val castable: Boolean get() = routes.isNotEmpty()
}

/**
 * The codec decision table: which videos a Default Media Receiver plays as they are, which only
 * need a new wrapper or new sound, and which need the picture converted.
 *
 * Containers. MP4 and WebM play directly. Matroska plays directly when it is WebM in all but name
 * (VP8, VP9 or AV1 with Opus or Vorbis) or when the receiver takes Matroska; otherwise it is
 * rewrapped into fragmented MP4 as it is sent ([Remux]). MPEG-TS, AVI and the rest have no rewrap
 * here and need the full conversion.
 *
 * Video. H.264 and VP8 everywhere; HEVC and VP9 from the Ultra and the Google TV generation on;
 * AV1 on the newest; 10 bit only where HEVC is. MPEG-4 Part 2, MPEG-2 and VC-1 need converting.
 *
 * Audio, per the Cast documentation. AAC, MP3, Opus, Vorbis and FLAC decode on the receiver; AC-3
 * and E-AC-3 pass through to the TV; DTS, TrueHD, PCM in Matroska and the rest are turned into
 * AAC. The track the viewer was listening to is kept where it can be, then the file's default,
 * then the first that needs no conversion.
 */
object CastFormats {

    val RECEIVER_AUDIO = setOf(Codecs.AAC, Codecs.MP3, Codecs.OPUS, Codecs.VORBIS, Codecs.FLAC)
    val PASSTHROUGH_AUDIO = setOf(Codecs.AC3, Codecs.EAC3)

    /** Audio the remuxer can carry into MP4 as it is. */
    val MP4_AUDIO = setOf(Codecs.AAC, Codecs.MP3, Codecs.OPUS, Codecs.FLAC, Codecs.AC3, Codecs.EAC3)

    /** Video the remuxer can carry into MP4 as it is. */
    val MP4_VIDEO = setOf(Codecs.H264, Codecs.HEVC, Codecs.AV1)

    fun videoSupport(video: VideoTrack, caps: ReceiverCaps): Support {
        val codec = when (video.codec) {
            Codecs.H264, Codecs.VP8 -> Support.Yes
            Codecs.HEVC -> caps.hevc
            Codecs.VP9 -> caps.vp9
            Codecs.AV1 -> caps.av1
            else -> Support.No
        }
        if (codec == Support.No) return codec
        val sized = if (aboveFullHd(video)) worse(codec, caps.uhd) else codec
        return if (video.tenBit) worse(sized, caps.tenBit) else sized
    }

    /** Larger than 1080p either way (a scope 4K picture is 3840 by about 1600). */
    fun aboveFullHd(video: VideoTrack): Boolean = video.width > 1920 || video.height > 1088

    /** The picture is past 1080p and [caps] says the receiver may not open it. */
    fun mayBeTooLarge(video: VideoTrack?, caps: ReceiverCaps): Boolean =
        video != null && aboveFullHd(video) && caps.uhd != Support.Yes

    fun audioSupport(codec: String, caps: ReceiverCaps): Support = when (codec) {
        in RECEIVER_AUDIO -> Support.Yes
        in PASSTHROUGH_AUDIO -> caps.dolbyPassthrough
        else -> Support.No
    }

    /**
     * The plan for [probe] on a receiver with [caps].
     *
     * @param preferredAudio the track number the viewer has on, or null for the file's choice.
     * @param canConvertAudio this device can turn sound into AAC (Android, with the FFmpeg decoders).
     * @param transcoder what this device can make again ([TranscodeAbility]), or null where it
     *   cannot transcode (the TV build, no encoder, a computer without ffmpeg). A transcode is
     *   planned only where nothing else makes the receiver play the file with its sound.
     * @param linkKbps the measured link to the receiver, for the transcode's bitrate; null when not known.
     * @param receiverContainers the wrappers a DLNA TV lists, for the transcode's; null for a Cast receiver.
     */
    fun plan(
        probe: MediaProbe,
        caps: ReceiverCaps,
        preferredAudio: Int? = null,
        canConvertAudio: Boolean = false,
        transcoder: TranscodeAbility? = null,
        linkKbps: Int? = null,
        receiverContainers: Set<Container>? = null,
    ): CastPlan {
        val audio = chooseAudio(probe.audio, caps, preferredAudio, canConvertAudio)
        val audioTrack = audio?.track
        val video = probe.video
        val videoOk = video?.let { videoSupport(it, caps) } ?: if (probe.container == Container.Other) Support.Maybe else Support.Yes
        val audioOk = audio?.support ?: Support.Yes
        val routes = ArrayList<CastRoute>()

        fun transcode(reason: TranscodeReason, copyVideo: Boolean): CastRoute.Transcode? {
            val ability = transcoder ?: return null
            // Passed through only where this device's muxer carries the picture; else made again.
            val copy = copyVideo && video != null && ability.passesVideo(video.codec)
            if (!copy && video != null && !ability.decodesVideo(video.codec)) return null
            val target = TranscodePlanner.target(probe, caps, ability, audioTrack, copy, linkKbps, receiverContainers)
            // Made again for its sound, it must come out with sound.
            if (reason == TranscodeReason.AudioCodec && target.audio != TranscodeAudio.Aac && target.audio != TranscodeAudio.Ac3) return null
            return CastRoute.Transcode(audioTrack?.number?.takeIf { target.audio != TranscodeAudio.None }, reason, target)
        }

        // The picture cannot be shown as it is: conversion or nothing.
        if (videoOk == Support.No) {
            val sizeOnly = video != null && aboveFullHd(video) && caps.uhd == Support.No && videoSupport(video, caps.copy(uhd = Support.Yes)) != Support.No
            val bitsOnly = video != null && video.tenBit && caps.tenBit == Support.No && videoSupport(video, caps.copy(tenBit = Support.Yes)) != Support.No
            val why = if (sizeOnly) CastRefusal.Resolution else CastRefusal.VideoCodec
            val reason = when {
                sizeOnly -> TranscodeReason.Resolution
                bitsOnly -> TranscodeReason.TenBit
                else -> TranscodeReason.VideoCodec
            }
            val t = transcode(reason, copyVideo = false)
            return if (t != null) CastPlan(listOf(t), audioTrack?.number, silent = t.audioTrack == null && probe.audio.isNotEmpty())
            else CastPlan(emptyList(), audioTrack?.number, why)
        }

        val convert = audioTrack?.takeIf { canConvertAudio }?.let { CastRoute.ConvertAudio(it.number) }
        when (probe.container) {
            // An MP4 is played by its own bytes: the remuxer reads Matroska. A soundtrack the
            // receiver cannot play still leaves the picture worth sending, said so on screen.
            Container.Mp4 -> routes += CastRoute.Direct("video/mp4")
            Container.WebM -> routes += CastRoute.Direct("video/webm")
            Container.Matroska -> {
                val webmLike = video?.codec in setOf(Codecs.VP8, Codecs.VP9, Codecs.AV1) &&
                    probe.audio.all { it.codec == Codecs.OPUS || it.codec == Codecs.VORBIS } &&
                    (audioTrack == null || audioTrack.codec == Codecs.OPUS || audioTrack.codec == Codecs.VORBIS)
                val remuxable = (video == null || video.codec in MP4_VIDEO)
                if (webmLike) routes += CastRoute.Direct("video/webm")
                // HLS first: the receiver knows the length, seeks itself and switches tracks. The
                // one rewrapped stream stays behind it for a receiver that refuses the playlist
                // (or a file without cues).
                if (remuxable && !webmLike && video != null) {
                    val renditions = hlsAudio(probe.audio, caps, canConvertAudio)
                    val default = audioTrack?.number?.takeIf { n -> renditions.any { it.number == n } } ?: renditions.firstOrNull()?.number
                    routes += CastRoute.Hls(default, renditions)
                }
                val directMkv = caps.matroska != Support.No && audioOk != Support.No && !webmLike
                if (directMkv && caps.matroska == Support.Yes) routes += CastRoute.Direct("video/x-matroska")
                // Vorbis plays on the receiver but has no place in MP4: converted like DTS.
                val audioRewraps = audioTrack == null || audioTrack.codec in MP4_AUDIO
                if (remuxable && audioRewraps && audioOk != Support.No) routes += CastRoute.Remux(audioTrack?.number)
                if (directMkv && caps.matroska == Support.Maybe) routes += CastRoute.Direct("video/x-matroska")
                if (remuxable && convert != null && (audioOk == Support.No || !audioRewraps)) routes += convert
                // A soundtrack nothing here can play or convert: the picture goes alone, said so.
                if (remuxable && routes.all { it is CastRoute.Hls }) routes += CastRoute.Remux(null)
            }
            // No rewrap reads these: made again, the picture passed through where it plays as it is.
            Container.MpegTs, Container.Avi -> transcode(TranscodeReason.Container, copyVideo = video?.codec == Codecs.H264 && videoOk == Support.Yes)?.let { routes += it }
            Container.Other -> routes += CastRoute.Direct("video/mp4")
        }
        // Every way so far goes without the sound: made again with it, the picture passed through
        // where it may play (a Maybe picture is still tried; the full conversion is behind it).
        if (routes.firstOrNull()?.let { silentRoute(it, audioOk, probe) } == true && audioOk == Support.No) {
            transcode(TranscodeReason.AudioCodec, copyVideo = true)?.let { routes.add(0, it) }
        }
        // A Maybe picture (HEVC on an unnamed Chromecast) or an unknown file falls back to conversion.
        if (videoOk == Support.Maybe || routes.isEmpty() || probe.container == Container.Other) {
            val reason = when {
                probe.container == Container.Other && video == null -> TranscodeReason.Container
                video != null && mayBeTooLarge(video, caps) && videoSupport(video, caps.copy(uhd = Support.Yes)) == Support.Yes -> TranscodeReason.Resolution
                video != null && video.tenBit && caps.tenBit != Support.Yes -> TranscodeReason.TenBit
                video == null -> TranscodeReason.Container
                else -> TranscodeReason.VideoCodec
            }
            transcode(reason, copyVideo = false)?.let { routes += it }
        }
        if (routes.isEmpty()) {
            val refusal = when {
                probe.container == Container.MpegTs || probe.container == Container.Avi -> CastRefusal.Container
                audioOk == Support.No -> CastRefusal.AudioCodec
                else -> CastRefusal.Container
            }
            return CastPlan(emptyList(), audioTrack?.number, refusal)
        }
        val first = routes.first()
        return CastPlan(routes.distinct(), audioTrack?.number, silent = silentRoute(first, audioOk, probe))
    }

    /** [route] plays the picture without the sound the viewer chose. */
    private fun silentRoute(route: CastRoute, audioOk: Support, probe: MediaProbe): Boolean = when (route) {
        is CastRoute.Direct -> audioOk == Support.No
        is CastRoute.Remux -> route.audioTrack == null && probe.audio.isNotEmpty()
        is CastRoute.Hls -> route.audio.isEmpty() && probe.audio.isNotEmpty()
        is CastRoute.Transcode -> route.audioTrack == null && probe.audio.isNotEmpty()
        is CastRoute.ConvertAudio -> false
    }

    /**
     * Rewrapping (or sending as it is) is enough for this receiver: the picture plays as it is, the
     * wrapper has a rewrap here, and the sound plays or is converted. False means only a transcode
     * would make it play with its sound.
     */
    fun remuxEnough(probe: MediaProbe, caps: ReceiverCaps, preferredAudio: Int? = null, canConvertAudio: Boolean = false): Boolean {
        val p = plan(probe, caps, preferredAudio, canConvertAudio)
        return p.castable && !p.silent
    }

    /**
     * The soundtracks an HLS cast offers, in the file's order: each one MP4 carries that the
     * receiver plays or passes through as it is, and, where this device converts, every other one
     * as AAC. One the receiver cannot take and nothing here can turn into AAC is left out.
     *
     * Dolby (AC-3, E-AC-3) goes twice where this device converts: as it is, and as AAC beside it.
     * Whether the receiver passes Dolby on is up to the TV or amplifier behind it, which no sender
     * can see: the receiver's Shaka drops a variant whose codec its MSE refuses (the Mi TV Stick of
     * the device test dropped E-AC-3 that way and played only the AAC track), so the AAC copy is
     * what still plays there, and where Dolby is taken the sender asks for it by its track id.
     */
    fun hlsAudio(tracks: List<AudioTrack>, caps: ReceiverCaps, canConvert: Boolean): List<HlsAudio> = tracks.flatMap { t ->
        when {
            t.codec in MP4_AUDIO && audioSupport(t.codec, caps) != Support.No ->
                if (t.codec in PASSTHROUGH_AUDIO && canConvert) listOf(HlsAudio(t.number, convert = false), HlsAudio(t.number, convert = true))
                else listOf(HlsAudio(t.number, convert = false))
            canConvert -> listOf(HlsAudio(t.number, convert = true))
            else -> emptyList()
        }
    }

    /** The plan's chosen soundtrack and how well the receiver takes it. */
    data class AudioChoice(val track: AudioTrack, val support: Support)

    fun chooseAudio(tracks: List<AudioTrack>, caps: ReceiverCaps, preferred: Int?, canConvert: Boolean): AudioChoice? {
        if (tracks.isEmpty()) return null
        fun of(t: AudioTrack) = AudioChoice(t, audioSupport(t.codec, caps))
        tracks.firstOrNull { it.number == preferred }?.let { t ->
            val c = of(t)
            // The viewer's own track is kept if it plays, or if it can be converted.
            if (c.support != Support.No || canConvert) return c
            // Else the same language in a form that plays, before the file's default.
            tracks.firstOrNull { it.language == t.language && audioSupport(it.codec, caps) != Support.No }?.let { return of(it) }
        }
        val playable = tracks.filter { audioSupport(it.codec, caps) != Support.No }
        playable.firstOrNull { it.isDefault }?.let { return of(it) }
        if (canConvert) tracks.firstOrNull { it.isDefault }?.let { return of(it) }
        playable.firstOrNull()?.let { return of(it) }
        return of(tracks.firstOrNull { it.isDefault } ?: tracks.first())
    }

    private fun worse(a: Support, b: Support): Support = when {
        a == Support.No || b == Support.No -> Support.No
        a == Support.Maybe || b == Support.Maybe -> Support.Maybe
        else -> Support.Yes
    }

    /** The receiver will play the picture and the sound of [route] as given: no note needed. */
    fun needsNote(route: CastRoute): Boolean = route is CastRoute.Transcode
}
