package com.tmplayer.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

/**
 * The device's video renderer, trusting the picture's size over the level and frame rate its file
 * claims, so the app's FFmpeg decoder gets a picture only when the device's chip truly cannot.
 *
 * Encoders often stamp a 1080p HEVC or H.264 file with a level far above what its size needs
 * (`hvc1.2.4.L153`, level 5.1, on a plain 1920x1080 film). A TV chip that declares level 4.1, as
 * the Amlogic decoder in the 1 GB Mi TV does, is then reported unable to take it, Media3 hands the
 * picture to the FFmpeg renderer instead, and four small cores decode 1080p 10-bit HEVC at about
 * four frames a second (2026-10-23). The chip plays such files without trouble: the level is a
 * ceiling on the stream, and the stream is well under it. Kodi and Jellyfin ignore the claimed
 * level for the same reason.
 *
 * The same happened to 4K-wide 10-bit HEVC at 60 frames on phones (HX71, vivo V2270A and realme
 * RMX3710, 2026-11-01): the phone's hardware decoder takes Main 10 at that size but is rated for
 * fewer frames there, Media3 then called the picture beyond it, and FFmpeg dropped 596 of 678
 * frames. A hardware decoder past its rated frame rate still shows far more frames than eight
 * phone cores decoding 4K 10-bit in software.
 *
 * So a format the device's decoder turns down only as exceeding its capabilities is asked again:
 * first with the level the picture's size actually needs ([CodecLevel.relaxed]), then without its
 * frame rate and with the level its size needs at 30 frames ([CodecLevel.rateFree]), which only a
 * hardware decoder may answer. When the decoder takes one, the device's decoder is what the track
 * selector sees as fit. The renderer itself still gets the real format. Profiles and sizes are
 * left as they are: a chip without 10-bit HEVC, or one whose largest picture is 1080p, still
 * leaves a 4K 10-bit picture to FFmpeg.
 */
@UnstableApi
class LevelLenientVideoRenderer(renderer: Renderer) : ForwardingRenderer(renderer) {

    private val lenient: RendererCapabilities by lazy { LenientCapabilities(super.getCapabilities()) }

    override fun getCapabilities(): RendererCapabilities = lenient
}

/** [inner]'s answers, with a format it turns down as too much asked again. See [LevelLenientVideoRenderer]. */
@UnstableApi
class LenientCapabilities(private val inner: RendererCapabilities) : RendererCapabilities by inner {

    override fun supportsFormat(format: Format): Int {
        val support = inner.supportsFormat(format)
        if (RendererCapabilities.getFormatSupport(support) != C.FORMAT_EXCEEDS_CAPABILITIES) return support
        val relaxed = CodecLevel.relaxed(format)
        if (relaxed != null) {
            val again = inner.supportsFormat(relaxed)
            if (RendererCapabilities.getFormatSupport(again) == C.FORMAT_HANDLED) return again
        }
        val rateFree = CodecLevel.rateFree(format) ?: return support
        val again = inner.supportsFormat(rateFree)
        // Past its rated frame rate only a hardware decoder beats FFmpeg: the system's own
        // software decoder is slower than FFmpeg's threads, so it never takes the picture this way.
        val hardware = RendererCapabilities.getHardwareAccelerationSupport(again) == RendererCapabilities.HARDWARE_ACCELERATION_SUPPORTED
        return if (RendererCapabilities.getFormatSupport(again) == C.FORMAT_HANDLED && hardware) again else support
    }
}

/**
 * [base]'s decoders for a picture with the hardware ones ahead of the software ones, each group in
 * its own order; the sound's lists are left as they are. Media3 opens the first decoder that takes
 * the real format, and when none does (the picture [LenientCapabilities] let through past a chip's
 * rated frame rate) the first listed: without this, a device that lists the system's software
 * decoder first would open that one although the chip is what said yes.
 */
@UnstableApi
class HardwareFirstCodecSelector(private val base: MediaCodecSelector) : MediaCodecSelector {

    override fun getDecoderInfos(
        mimeType: String,
        requiresSecureDecoder: Boolean,
        requiresTunnelingDecoder: Boolean,
    ): List<MediaCodecInfo> {
        val all = base.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
        if (!MimeTypes.isVideo(mimeType)) return all
        val (hardware, software) = all.partition { it.hardwareAccelerated && !it.softwareOnly }
        return hardware + software
    }
}

/** The codec level a picture's size needs, written back into its codecs string. See [LevelLenientVideoRenderer]. */
object CodecLevel {

    /**
     * How a hardware decoder fits a picture, for the stutter report: as the file asks
     * ([RATED]), only once its claimed level is lowered to its size's ([LEVEL]), only past its
     * rated frame rate ([RATE]), or not at all ([NONE]: FFmpeg's picture).
     */
    enum class Fit { RATED, LEVEL, RATE, NONE }

    /**
     * How a hardware decoder that answers [takes] fits [format]: asked as it is, then as
     * [relaxed], then as [rateFree], the order [LenientCapabilities] asks in.
     */
    fun fit(format: Format, takes: (Format) -> Boolean): Fit = when {
        takes(format) -> Fit.RATED
        relaxed(format)?.let(takes) == true -> Fit.LEVEL
        rateFree(format)?.let(takes) == true -> Fit.RATE
        else -> Fit.NONE
    }

    /**
     * [format] with its claimed level lowered to 4.1 when the picture is no larger than 1080p and
     * the claim is higher; null when nothing would change (no size, larger than 1080p, a codec
     * other than HEVC or H.264, an unreadable string, or a claim already at 4.1 or under).
     */
    fun relaxed(format: Format): Format? {
        if (!fullHd(format)) return null
        val lowered = lowered(format, HEVC_LEVEL_41, AVC_LEVEL_41) ?: return null
        return format.buildUpon().setCodecs(lowered).build()
    }

    /**
     * [format] without its frame rate, so a decoder is asked about the size alone, and with a
     * claimed level above what that size needs at 30 frames lowered to it: 4.1 up to 1080p, 5.0
     * for HEVC and 5.1 for H.264 up to 4096x2304. Null for a picture without a size, one larger
     * than 4096x2304, one that is not video, or when nothing would change.
     */
    fun rateFree(format: Format): Format? {
        if (format.width <= 0 || format.height <= 0) return null
        if (!MimeTypes.isVideo(format.sampleMimeType)) return null
        val lowered = when {
            fullHd(format) -> lowered(format, HEVC_LEVEL_41, AVC_LEVEL_41)
            maxOf(format.width, format.height) <= UHD_LONG && minOf(format.width, format.height) <= UHD_SHORT ->
                lowered(format, HEVC_LEVEL_5, AVC_LEVEL_51)
            else -> return null
        }
        if (lowered == null && format.frameRate == Format.NO_VALUE.toFloat()) return null
        return format.buildUpon()
            .setFrameRate(Format.NO_VALUE.toFloat())
            .apply { if (lowered != null) setCodecs(lowered) }
            .build()
    }

    /** `hvc1.2.4.L153.90` to `hvc1.2.4.L123.90`; high tier (`H153`) to main tier at 4.1 as well. */
    internal fun hevc(codecs: String): String? = hevc(codecs, HEVC_LEVEL_41)

    /** `avc1.640033` (High at 5.1) to `avc1.640029` (High at 4.1). */
    internal fun avc(codecs: String): String? = avc(codecs, AVC_LEVEL_41)

    /** [hevc] down to [target] (30 times the level); null when the claim is main tier at or under it. */
    internal fun hevc(codecs: String, target: Int): String? {
        val parts = codecs.split('.').toMutableList()
        if (parts.size < 4 || (parts[0] != "hvc1" && parts[0] != "hev1")) return null
        val tier = parts[3]
        if (tier.length < 2 || (tier[0] != 'L' && tier[0] != 'H')) return null
        val level = tier.substring(1).toIntOrNull() ?: return null
        if (level <= target && tier[0] == 'L') return null
        parts[3] = "L$target"
        return parts.joinToString(".")
    }

    /** [avc] down to [target] (ten times the level, in hex); null when the claim is at or under it. */
    internal fun avc(codecs: String, target: Int): String? {
        val parts = codecs.split('.')
        if (parts.size < 2 || (parts[0] != "avc1" && parts[0] != "avc3")) return null
        val profile = parts[1]
        if (profile.length != 6) return null
        val level = profile.substring(4).toIntOrNull(16) ?: return null
        if (level <= target) return null
        return parts[0] + "." + profile.substring(0, 4) + "%02X".format(target) +
            parts.drop(2).joinToString("") { ".$it" }
    }

    private fun fullHd(format: Format): Boolean =
        format.width > 0 && format.height > 0 &&
            maxOf(format.width, format.height) <= FULL_HD_LONG && minOf(format.width, format.height) <= FULL_HD_SHORT

    private fun lowered(format: Format, hevcTarget: Int, avcTarget: Int): String? {
        val codecs = format.codecs ?: return null
        return when (format.sampleMimeType) {
            MimeTypes.VIDEO_H265 -> hevc(codecs, hevcTarget)
            MimeTypes.VIDEO_H264 -> avc(codecs, avcTarget)
            else -> null
        }
    }

    /** HEVC's level 4.1 is written as 30 times the level; it covers 1080p at 60 frames. */
    private const val HEVC_LEVEL_41 = 123

    /** HEVC's level 5.0: 4096x2176 at 30 frames. */
    private const val HEVC_LEVEL_5 = 150

    /** H.264's level 4.1, written as ten times the level in hex. */
    private const val AVC_LEVEL_41 = 0x29

    /** H.264's level 5.1: 3840x2160 at 30 frames. */
    private const val AVC_LEVEL_51 = 0x33

    /** 1080p either way round, with the 1088 lines some encoders pad to. */
    private const val FULL_HD_LONG = 1920
    private const val FULL_HD_SHORT = 1088

    /** 4K either way round, the cinema width included. */
    private const val UHD_LONG = 4096
    private const val UHD_SHORT = 2304
}
