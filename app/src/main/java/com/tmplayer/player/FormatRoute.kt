package com.tmplayer.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import com.tmplayer.data.FormatFacts

/**
 * Which decoder path a format is given before anything has failed. Pure, so the rules are tested
 * without a device; [DolbyVisionBaseLayer] and [PlayerActivity] ask the device and act on them.
 *
 * Dolby Vision first, the format that most often played as sound over a black screen. A Dolby
 * Vision track is a plain HEVC (or AVC) picture, the base layer, with Dolby's metadata beside it,
 * and for every profile but 5 that base layer is a complete HDR10, HLG or SDR picture of its own.
 * Media3 falls back to it only for profiles 4, 8 and 9 and only on MediaCodec, and the FFmpeg
 * decoder does not know the Dolby Vision type at all, so profile 7 (the Blu-ray remuxes, the
 * enhancement layer interleaved as NAL types an HEVC decoder skips) found no decoder on a device
 * or screen without Dolby Vision, and a profile 8 film too large for the hardware had no software
 * path. Where the device would not show it as Dolby Vision anyway, the track is relabelled as its
 * base layer, the way Kodi, Jellyfin and Just Player do, and then both decoders can take it.
 */
@UnstableApi
object FormatRoute {

    /**
     * The Dolby Vision profile a codecs string names ("dvhe.07.06", "dvh1.08.06", "dvav.09.05",
     * "dav1.10.09"), or null when it is not a Dolby Vision string.
     */
    fun dolbyVisionProfile(codecs: String?): Int? = FormatFacts.dolbyVisionProfile(codecs)

    /**
     * The codec of the base layer a decoder without Dolby Vision shows correctly: HEVC for
     * profiles 4, 7 and 8, AVC for 9. Null for profile 5, whose picture is in Dolby's own colour
     * space and comes out green and purple without the Dolby Vision processing, and for anything
     * not known to have a compatible base layer (AV1 profile 10 is left to Media3, which checks
     * the colour signalling it needs).
     */
    fun dolbyVisionBaseMime(codecs: String?): String? = when (dolbyVisionProfile(codecs)) {
        4, 7, 8 -> MimeTypes.VIDEO_H265
        9 -> MimeTypes.VIDEO_H264
        else -> null
    }

    /** Whether [format] is Dolby Vision with no picture a plain decoder can show (profile 5). */
    fun dolbyVisionOnly(format: Format): Boolean =
        format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION && dolbyVisionBaseMime(format.codecs) == null

    /**
     * [format] as the player should see it. A Dolby Vision track with a compatible base layer
     * becomes that base layer when [playsAsDolbyVision] says this device would not show it as
     * Dolby Vision; [baseCodecs] reads the base layer's own codecs string from the track's
     * parameter sets, and when it cannot, a string is made up that still says what the decoder
     * must handle (the HEVC base layer of every profile here is Main 10).
     */
    fun forDevice(
        format: Format,
        playsAsDolbyVision: (Format) -> Boolean,
        baseCodecs: (Format) -> String?,
    ): Format {
        if (format.sampleMimeType != MimeTypes.VIDEO_DOLBY_VISION) return format
        val base = dolbyVisionBaseMime(format.codecs) ?: return format
        if (playsAsDolbyVision(format)) return format
        val codecs = baseCodecs(format)?.takeIf { it.isNotBlank() }
            ?: if (base == MimeTypes.VIDEO_H265) hevcMain10Codecs(format.height) else null
        return format.buildUpon()
            .setSampleMimeType(base)
            .setCodecs(codecs)
            .build()
    }

    /**
     * An RFC 6381 string for HEVC Main 10 at a level that fits [height]: 4.1 up to 1080p, 5.1
     * above. What a decoder is asked is "10-bit, this large", so an 8-bit-only chip is not handed
     * a picture it cannot show.
     */
    fun hevcMain10Codecs(height: Int): String =
        if (height in 1..FULL_HD_HEIGHT) "hvc1.2.4.L123.B0" else "hvc1.2.4.L153.B0"

    /**
     * Whether a decoder failure is switched to the other decoder at once, before the failure
     * sheet: once per track type per player ([alreadySwitched]), so a file that fails on both
     * decoders still reaches the sheet, and never to software for a picture this device would
     * decode too slowly to watch ([softwareTooSlow]), where the sheet's sentence is the honest
     * answer. A picture that already played ([pictureStarted]) and then failed is more likely a
     * damaged stretch of the file than a decoder that cannot do the format, so it gets the sheet
     * and its Retry; a soundtrack switches whenever it fails, since changing to a Dolby or DTS
     * track part way is when its passthrough is first tried.
     */
    fun switchesByItself(
        trackType: Int,
        toFfmpeg: Boolean,
        alreadySwitched: Set<Int>,
        softwareTooSlow: Boolean,
        pictureStarted: Boolean,
    ): Boolean {
        if (trackType in alreadySwitched) return false
        if (trackType == C.TRACK_TYPE_VIDEO && (pictureStarted || (toFfmpeg && softwareTooSlow))) return false
        return trackType == C.TRACK_TYPE_VIDEO || trackType == C.TRACK_TYPE_AUDIO
    }

    private const val FULL_HD_HEIGHT = 1088
}
