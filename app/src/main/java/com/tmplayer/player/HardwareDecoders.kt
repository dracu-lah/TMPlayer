package com.tmplayer.player

import android.content.Context
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import com.tmplayer.data.TelegramQualities
import com.tmplayer.data.TelegramQuality

/**
 * Whether this device has a hardware decoder for a picture, as Media3's codec list tells hardware
 * from software (by the codec's own flag from Android 10, by its name before that: Google's
 * `OMX.google.` and `c2.android.` decoders are software). The format's profile, level and size
 * are checked too, so HEVC 10-bit on a chip that decodes only 8-bit, or VP9 profile 2, counts as
 * software. Media3 has already asked the same questions to pick a renderer, so the answers come
 * from its cache. See [com.tmplayer.data.SoftwareDecode].
 */
@UnstableApi
object HardwareDecoders {

    fun has(context: Context, format: Format): Boolean = fitOf(context, format) != CodecLevel.Fit.NONE

    /**
     * How the device's best hardware decoder fits [format], asked as the player's renderer asks
     * ([LenientCapabilities]): a 1080p file stamped level 5.1 plays on a level 4.1 chip, and 4K at
     * 60 frames on a chip rated for 30 there plays on the chip, so neither is software here.
     * [CodecLevel.Fit.NONE] when no hardware decoder takes it or the list cannot be read.
     */
    fun fitOf(context: Context, format: Format): CodecLevel.Fit = runCatching {
        val mime = format.sampleMimeType ?: return CodecLevel.Fit.NONE
        val hardware = MediaCodecUtil.getDecoderInfos(mime, false, false).filter { it.hardwareAccelerated && !it.softwareOnly }
        if (hardware.isEmpty()) return CodecLevel.Fit.NONE
        CodecLevel.fit(format) { asked -> hardware.any { it.isFormatSupported(context, asked) } }
    }.getOrDefault(CodecLevel.Fit.NONE)

    /** [has] for one of Telegram's copies, from its codec's name and its size. */
    fun has(context: Context, copy: TelegramQuality): Boolean {
        val mime = TelegramQualities.mimeOf(copy.codec) ?: return false
        val format = Format.Builder()
            .setSampleMimeType(mime)
            .apply { if (copy.width > 0 && copy.height > 0) setWidth(copy.width).setHeight(copy.height) }
            .build()
        return has(context, format)
    }

    /**
     * The hardware decoders listed for [mime], each with its highest profile and that profile's
     * highest level, for the stutter report ([com.tmplayer.data.StutterReport.hwDecodersOf]).
     * Media3's cached codec list, so cheap after the player asked; empty when it cannot be read.
     */
    fun listed(mime: String?): String = runCatching {
        if (mime.isNullOrBlank()) return ""
        val hardware = MediaCodecUtil.getDecoderInfos(mime, false, false).filter { it.hardwareAccelerated && !it.softwareOnly }
        com.tmplayer.data.StutterReport.hwDecodersOf(
            hardware.map { info ->
                val top = info.profileLevels.maxWithOrNull(compareBy({ it.profile }, { it.level }))
                info.name to top?.let { it.profile to it.level }
            },
        )
    }.getOrDefault("")
}
