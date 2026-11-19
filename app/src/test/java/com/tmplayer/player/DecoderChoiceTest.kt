package com.tmplayer.player

import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaFormat
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Timeline
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.trackselection.TrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.MediaCodecInfoBuilder

/**
 * HX71: which decoder takes the picture, the device's or the app's FFmpeg, decided by Media3's own
 * renderer and track selector over fake codec lists. The 4K-wide 10-bit HEVC at 60 frames of the
 * vivo V2270A and realme RMX3710 reports went to FFmpeg although the phone's chip takes Main 10 at
 * that size: it was rated for fewer frames there. The device's decoder now takes it, and FFmpeg
 * still gets what the chip truly cannot do (no 10-bit, a picture larger than the chip's largest).
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DecoderChoiceTest {

    private val context = RuntimeEnvironment.getApplication()

    /**
     * A fake decoder: [profiles] each with its highest level (the system's constants), pictures
     * up to [maxWidth]x[maxHeight], and [maxBlocksPerSecond] 16x16 blocks a second, which is its
     * rated frame rate at each size.
     */
    private fun codec(
        name: String,
        mime: String,
        profiles: List<Pair<Int, Int>>,
        maxWidth: Int,
        maxHeight: Int,
        maxBlocksPerSecond: Long,
        hardware: Boolean = true,
    ): MediaCodecInfo {
        val blocks = ((maxWidth + 15) / 16) * ((maxHeight + 15) / 16)
        val format = MediaFormat().apply {
            setString(MediaFormat.KEY_MIME, mime)
            setString("size-range", "64x64-${maxWidth}x$maxHeight")
            setString("block-size", "16x16")
            setString("block-count-range", "1-$blocks")
            setString("blocks-per-second-range", "1-$maxBlocksPerSecond")
            setString("frame-rate-range", "1-960")
            setInteger("max-width", maxWidth)
            setInteger("max-height", maxHeight)
        }
        val caps = MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
            .setMediaFormat(format)
            .setIsEncoder(false)
            .setProfileLevels(profiles.map { (p, l) -> CodecProfileLevel().apply { profile = p; level = l } }.toTypedArray())
            .setColorFormats(intArrayOf(CodecCapabilities.COLOR_FormatYUV420Flexible))
            .build()
        return MediaCodecInfo.newInstance(name, mime, mime, caps, hardware, !hardware, hardware, false, false)
    }

    private class Device(private val list: List<MediaCodecInfo>) : MediaCodecSelector {
        override fun getDecoderInfos(mimeType: String, requiresSecureDecoder: Boolean, requiresTunnelingDecoder: Boolean): List<MediaCodecInfo> =
            if (requiresSecureDecoder || requiresTunnelingDecoder) emptyList() else list.filter { it.mimeType == mimeType }
    }

    /** NextLib's FFmpeg video renderer as the selector sees it: every picture handled, in software. */
    private val ffmpeg = object : RendererCapabilities {
        override fun getName() = "FfmpegVideoRenderer"
        override fun getTrackType() = C.TRACK_TYPE_VIDEO
        override fun supportsFormat(format: Format): Int =
            RendererCapabilities.create(C.FORMAT_HANDLED, RendererCapabilities.ADAPTIVE_SEAMLESS, RendererCapabilities.TUNNELING_NOT_SUPPORTED)
        override fun supportsMixedMimeTypeAdaptation() = RendererCapabilities.ADAPTIVE_NOT_SUPPORTED
    }

    private enum class Side { DEVICE, FFMPEG, NONE }

    /**
     * The side Media3's track selector gives [format] to, the device's renderer first as the app
     * builds them: [lenient] is the player as built now (hardware first list, lenient renderer),
     * otherwise Media3's plain renderer as before HX71.
     */
    private fun choice(device: Device, format: Format, lenient: Boolean = true): Side {
        val hardware = if (lenient) {
            LevelLenientVideoRenderer(MediaCodecVideoRenderer(context, HardwareFirstCodecSelector(device))).capabilities
        } else {
            MediaCodecVideoRenderer(context, device).capabilities
        }
        val selector = DefaultTrackSelector(context)
        selector.init(
            object : TrackSelector.InvalidationListener {
                override fun onTrackSelectionsInvalidated(parameters: TrackSelectionParameters?) = Unit
            },
            DefaultBandwidthMeter.Builder(context).build(),
        )
        val result = selector.selectTracks(
            arrayOf(hardware, ffmpeg),
            TrackGroupArray(TrackGroup(format)),
            MediaSource.MediaPeriodId(Any()),
            Timeline.EMPTY,
        )
        return when {
            result.selections[0] != null -> Side.DEVICE
            result.selections[1] != null -> Side.FFMPEG
            else -> Side.NONE
        }
    }

    private fun hevc(codecs: String, width: Int, height: Int, fps: Float): Format = Format.Builder()
        .setSampleMimeType(MimeTypes.VIDEO_H265)
        .setCodecs(codecs)
        .setWidth(width)
        .setHeight(height)
        .setFrameRate(fps)
        .build()

    /** 3840x2160 at 30 frames, in 16x16 blocks: what a mid-range phone chip is rated for. */
    private val uhd30 = 240L * 135 * 30

    private val main10 = CodecProfileLevel.HEVCProfileMain10
    private val main = CodecProfileLevel.HEVCProfileMain

    /** A MediaTek phone (vivo V2270A class): Main and Main 10 to level 5.1, 4K rated for 30 frames, and Google's software decoder. */
    private val phone = Device(
        listOf(
            codec("c2.mtk.hevc.decoder", MimeTypes.VIDEO_H265, listOf(main to CodecProfileLevel.HEVCMainTierLevel51, main10 to CodecProfileLevel.HEVCMainTierLevel51), 4096, 2176, uhd30),
            codec("c2.android.hevc.decoder", MimeTypes.VIDEO_H265, listOf(main to CodecProfileLevel.HEVCMainTierLevel51), 4096, 2304, uhd30 / 2, hardware = false),
        ),
    )

    /** The vivo V2270A report: `hvc1.2.4.L153.90`, 3840x1604, 60 frames. */
    private val vivo = hevc("hvc1.2.4.L153.90", 3840, 1604, 60f)

    @Test
    fun theOldChoiceGaveThe4k60PictureToFfmpeg() {
        assertEquals("what the reports showed, before the frame rate is forgiven", Side.FFMPEG, choice(phone, vivo, lenient = false))
    }

    @Test
    fun aChipRatedForFewerFramesStillTakes4k10Bit() {
        assertEquals(Side.DEVICE, choice(phone, vivo))
        // The realme RMX3710 report: level 5.0, 3840x1616, on a chip that lists Main 10 only to 5.0.
        val realme = Device(listOf(codec("c2.mtk.hevc.decoder", MimeTypes.VIDEO_H265, listOf(main10 to CodecProfileLevel.HEVCMainTierLevel5), 4096, 2176, uhd30)))
        assertEquals(Side.DEVICE, choice(realme, hevc("hvc1.2.4.L150.90", 3840, 1616, 60f)))
        // A 4K file stamped level 5.1 at 60 frames on that 5.0 chip: the size needs 5.0 at 30.
        assertEquals(Side.DEVICE, choice(realme, hevc("hvc1.2.4.L153.90", 3840, 2160, 60f)))
    }

    @Test
    fun aPictureTheChipIsRatedForIsTheChipsAsBefore() {
        assertEquals(Side.DEVICE, choice(phone, hevc("hvc1.2.4.L150.90", 3840, 2160, 24f)))
        assertEquals(Side.DEVICE, choice(phone, hevc("hvc1.1.6.L120.90", 1920, 1080, 60f)))
    }

    @Test
    fun theMiTvsLevelStamped1080pStaysOnTheChip() {
        val tv = Device(
            listOf(codec("OMX.amlogic.hevc.decoder.awesome2", MimeTypes.VIDEO_H265, listOf(main10 to CodecProfileLevel.HEVCMainTierLevel41), 4096, 2304, 245L * 136 * 60)),
        )
        assertEquals(Side.FFMPEG, choice(tv, hevc("hvc1.2.4.L153.90", 1920, 1080, 24f), lenient = false))
        assertEquals(Side.DEVICE, choice(tv, hevc("hvc1.2.4.L153.90", 1920, 1080, 24f)))
    }

    @Test
    fun whatTheChipTrulyCannotDoStaysWithFfmpeg() {
        // An 8-bit only chip and a 10-bit picture: the profile is never forgiven.
        val eightBit = Device(listOf(codec("c2.mtk.hevc.decoder", MimeTypes.VIDEO_H265, listOf(main to CodecProfileLevel.HEVCMainTierLevel51), 4096, 2176, uhd30)))
        assertEquals(Side.FFMPEG, choice(eightBit, vivo))
        // A chip whose largest picture is 1080p: the size is never forgiven.
        val fullHd = Device(listOf(codec("OMX.MTK.VIDEO.DECODER.HEVC", MimeTypes.VIDEO_H265, listOf(main10 to CodecProfileLevel.HEVCMainTierLevel41), 1920, 1088, 120L * 68 * 60)))
        assertEquals(Side.FFMPEG, choice(fullHd, vivo))
        // No hardware decoder for HEVC at all, only Google's software one: FFmpeg's threads beat it.
        val softwareOnly = Device(listOf(codec("c2.android.hevc.decoder", MimeTypes.VIDEO_H265, listOf(main10 to CodecProfileLevel.HEVCMainTierLevel51), 4096, 2304, uhd30 / 2, hardware = false)))
        assertEquals(Side.FFMPEG, choice(softwareOnly, vivo))
        // 8K is past any phone chip's rating, and no rate is forgiven there.
        assertEquals(Side.FFMPEG, choice(phone, hevc("hvc1.2.4.L183.90", 7680, 4320, 30f)))
    }

    /** Media3's renderer with the list it opens decoders from, in order, for [format]: the first is the one opened. */
    private class Opening(context: android.content.Context, private val selector: MediaCodecSelector) : MediaCodecVideoRenderer(context, selector) {
        fun order(format: Format): List<String> = getDecoderInfos(selector, format, false).map { it.name }
    }

    /** What the player builds: hardware decoders listed first ([HardwareFirstCodecSelector]). */
    private fun opened(device: Device, format: Format): String? = Opening(context, HardwareFirstCodecSelector(device)).order(format).firstOrNull()

    private fun hardwareAnswer(device: Device, format: Format): Int =
        RendererCapabilities.getHardwareAccelerationSupport(
            LevelLenientVideoRenderer(MediaCodecVideoRenderer(context, HardwareFirstCodecSelector(device))).capabilities.supportsFormat(format),
        )

    @Test
    fun aPictureLetThroughPastTheRatingOpensTheChip() {
        // A device that lists Google's software decoder first, rated below the picture as well.
        val softwareListedFirst = Device(
            listOf(
                codec("c2.android.hevc.decoder", MimeTypes.VIDEO_H265, listOf(main10 to CodecProfileLevel.HEVCMainTierLevel51), 4096, 2304, uhd30 / 2, hardware = false),
                codec("c2.mtk.hevc.decoder", MimeTypes.VIDEO_H265, listOf(main10 to CodecProfileLevel.HEVCMainTierLevel51), 4096, 2176, uhd30),
            ),
        )
        assertEquals(Side.DEVICE, choice(softwareListedFirst, vivo))
        // Before: the software decoder answered first, past its own rating too, so FFmpeg got it.
        assertEquals(Side.FFMPEG, choice(softwareListedFirst, vivo, lenient = false))
        assertEquals("the chip said yes, so the chip opens", "c2.mtk.hevc.decoder", opened(softwareListedFirst, vivo))
        assertEquals("without the hardware first list Media3 opened the software one", "c2.android.hevc.decoder", Opening(context, softwareListedFirst).order(vivo).firstOrNull())
        assertEquals(RendererCapabilities.HARDWARE_ACCELERATION_SUPPORTED, hardwareAnswer(softwareListedFirst, vivo))
        assertEquals("c2.mtk.hevc.decoder", opened(phone, vivo))
    }

    @Test
    fun eightBit4k60OnAChipRatedFor30() {
        val uhd8 = hevc("hvc1.1.6.L153.90", 3840, 2160, 60f)
        // Only the chip: it takes the picture past its rating, as before HX71 for nothing else.
        val hardwareOnly = Device(listOf(codec("c2.mtk.hevc.decoder", MimeTypes.VIDEO_H265, listOf(main to CodecProfileLevel.HEVCMainTierLevel51), 4096, 2176, uhd30)))
        assertEquals(Side.DEVICE, choice(hardwareOnly, uhd8))
        assertEquals("c2.mtk.hevc.decoder", opened(hardwareOnly, uhd8))
        // A system software decoder rated for 60 there takes the real format, so Media3's answer
        // to the selector comes from it (in software) and the rate-free ask is never made. Media3
        // then orders the list it opens from by profile and level alone, which both take, so the
        // chip, listed first, is what opens: playback never lands on the slower software decoder.
        val fasterSoftware = Device(
            listOf(
                codec("c2.mtk.hevc.decoder", MimeTypes.VIDEO_H265, listOf(main to CodecProfileLevel.HEVCMainTierLevel51), 4096, 2176, uhd30),
                codec("c2.android.hevc.decoder", MimeTypes.VIDEO_H265, listOf(main to CodecProfileLevel.HEVCMainTierLevel52), 4096, 2304, uhd30 * 3, hardware = false),
            ),
        )
        assertEquals(Side.DEVICE, choice(fasterSoftware, uhd8))
        assertEquals(Side.DEVICE, choice(fasterSoftware, uhd8, lenient = false))
        assertEquals(RendererCapabilities.HARDWARE_ACCELERATION_NOT_SUPPORTED, hardwareAnswer(fasterSoftware, uhd8))
        assertEquals("c2.mtk.hevc.decoder", opened(fasterSoftware, uhd8))
        // The same with the software decoder listed first by the device.
        val fasterSoftwareFirst = Device(fasterSoftware.getDecoderInfos(MimeTypes.VIDEO_H265, false, false).reversed())
        assertEquals("c2.android.hevc.decoder", Opening(context, fasterSoftwareFirst).order(uhd8).firstOrNull())
        assertEquals("c2.mtk.hevc.decoder", opened(fasterSoftwareFirst, uhd8))
    }

    @Test
    fun theFitTheStutterReportCarriesMatchesTheChoice() {
        fun fitOn(device: Device, format: Format): CodecLevel.Fit {
            val hw = device.getDecoderInfos(format.sampleMimeType ?: "", false, false).filter { it.hardwareAccelerated }
            return CodecLevel.fit(format) { asked -> hw.any { it.isFormatSupported(context, asked) } }
        }
        assertEquals(CodecLevel.Fit.RATE, fitOn(phone, vivo))
        assertEquals(CodecLevel.Fit.RATED, fitOn(phone, hevc("hvc1.2.4.L150.90", 3840, 2160, 24f)))
        val tv = Device(listOf(codec("OMX.amlogic.hevc.decoder.awesome2", MimeTypes.VIDEO_H265, listOf(main10 to CodecProfileLevel.HEVCMainTierLevel41), 4096, 2304, 245L * 136 * 60)))
        assertEquals(CodecLevel.Fit.LEVEL, fitOn(tv, hevc("hvc1.2.4.L153.90", 1920, 1080, 24f)))
        val eightBit = Device(listOf(codec("c2.mtk.hevc.decoder", MimeTypes.VIDEO_H265, listOf(main to CodecProfileLevel.HEVCMainTierLevel51), 4096, 2176, uhd30)))
        assertEquals(CodecLevel.Fit.NONE, fitOn(eightBit, vivo))
    }
}
