package androidx.media3.exoplayer.mediacodec

import android.content.Context
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreMedia.kCMVideoCodecType_AV1
import platform.CoreMedia.kCMVideoCodecType_DolbyVisionHEVC
import platform.CoreMedia.kCMVideoCodecType_H264
import platform.CoreMedia.kCMVideoCodecType_HEVC
import platform.CoreMedia.kCMVideoCodecType_VP9
import platform.VideoToolbox.VTIsHardwareDecodeSupported

// Android's decoders on iOS: what Media3 would ask MediaCodec, answered by VideoToolbox for the
// pictures it decodes in hardware on this device, and by the engine's software decoders (FFmpeg)
// for the rest. Android's player reads this to tell hardware from software (HardwareDecoders),
// which picks between Telegram's copies of a video, so the answer is the device's own.

/** One decoder, Media3's. */
class MediaCodecInfo(
    val name: String,
    val mimeType: String,
    val codecMimeType: String,
    val hardwareAccelerated: Boolean,
    val softwareOnly: Boolean,
    val vendor: Boolean,
) {
    fun isFormatSupported(format: Format): Boolean = format.sampleMimeType == mimeType

    fun isFormatSupported(@Suppress("UNUSED_PARAMETER") context: Context, format: Format): Boolean = isFormatSupported(format)

    override fun toString(): String = name
}

/** Media3's lookup of decoders, from VideoToolbox and the engine. */
object MediaCodecUtil {
    /** Media3's failure to list decoders. */
    class DecoderQueryException(cause: Throwable?) : Exception(cause)

    @OptIn(ExperimentalForeignApi::class)
    fun getDecoderInfos(mimeType: String, secure: Boolean, tunneling: Boolean): List<MediaCodecInfo> {
        if (secure || tunneling) return emptyList()
        val software = MediaCodecInfo("FFmpeg", mimeType, mimeType, hardwareAccelerated = false, softwareOnly = true, vendor = false)
        val codec = when (mimeType) {
            MimeTypes.VIDEO_H264 -> kCMVideoCodecType_H264
            MimeTypes.VIDEO_H265 -> kCMVideoCodecType_HEVC
            MimeTypes.VIDEO_DOLBY_VISION -> kCMVideoCodecType_DolbyVisionHEVC
            MimeTypes.VIDEO_AV1 -> kCMVideoCodecType_AV1
            MimeTypes.VIDEO_VP9 -> kCMVideoCodecType_VP9
            else -> return listOf(software)
        }
        val hardware = runCatching { VTIsHardwareDecodeSupported(codec) }.getOrDefault(false)
        val videoToolbox = MediaCodecInfo("VideoToolbox", mimeType, mimeType, hardwareAccelerated = true, softwareOnly = false, vendor = true)
        return if (hardware) listOf(videoToolbox, software) else listOf(software)
    }

    fun getDecoderInfo(mimeType: String, secure: Boolean, tunneling: Boolean): MediaCodecInfo? =
        getDecoderInfos(mimeType, secure, tunneling).firstOrNull()
}

/** Which decoders to try for a format, Media3's; on iOS the device's own (MediaCodecUtil). */
fun interface MediaCodecSelector {
    fun getDecoderInfos(mimeType: String, requiresSecureDecoder: Boolean, requiresTunnelingDecoder: Boolean): List<MediaCodecInfo>

    companion object {
        val DEFAULT: MediaCodecSelector = MediaCodecSelector { mime, secure, tunneling -> MediaCodecUtil.getDecoderInfos(mime, secure, tunneling) }
    }
}
