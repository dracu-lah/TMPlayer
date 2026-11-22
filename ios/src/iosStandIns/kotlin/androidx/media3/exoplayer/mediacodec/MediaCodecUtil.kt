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

/** Android's `MediaCodecInfo.CodecProfileLevel`: a profile and the highest level it decodes. */
class CodecProfileLevel(val profile: Int, val level: Int)

/** What a decoder says it takes, Android's `CodecCapabilities`: only the sound's channel limit is named. */
class DecoderCapabilities(val audioCapabilities: DecoderAudioCapabilities? = null)

/** Android's `AudioCapabilities`: the most channels a sound decoder takes. */
class DecoderAudioCapabilities(val maxInputChannelCount: Int)

/** One decoder, Media3's. */
class MediaCodecInfo(
    val name: String,
    val mimeType: String,
    val codecMimeType: String,
    val hardwareAccelerated: Boolean,
    val softwareOnly: Boolean,
    val vendor: Boolean,
    /** Whether it takes tunnelled playback; VideoToolbox does not on iOS, so none of the above does. */
    val tunneling: Boolean = false,
    /** The profiles and levels the decoder lists; VideoToolbox's are not read, so none. */
    val profileLevels: Array<CodecProfileLevel> = emptyArray(),
    /** What the decoder says it takes; VideoToolbox and the engine's decoders say nothing, so none. */
    val capabilities: DecoderCapabilities? = null,
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

    /** Media3's other type a decoder may take a format as (E-AC-3 JOC as E-AC-3); no decoder is listed that way here, so none. */
    @Suppress("UNUSED_PARAMETER")
    fun getAlternativeCodecMimeType(format: Format): String? = null

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
