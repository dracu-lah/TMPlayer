package com.tmplayer.player

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil

/**
 * Whether this device has a hardware video decoder that offers tunneled playback for a video
 * format ([TunnelingPolicy.Input.codecCanTunnel]), read from the codec list the device reports
 * (`FEATURE_TunneledPlayback`, which Media3 exposes as [androidx.media3.exoplayer.mediacodec.MediaCodecInfo.tunneling]).
 */
@OptIn(UnstableApi::class)
object TunnelingSupport {

    /**
     * True when a hardware decoder for [mimeType] can tunnel. With no format known yet (a blank type)
     * the two common ones, H.264 and H.265, stand in: a device that can tunnel neither cannot be
     * asked to. Any failure of the codec query is "cannot": tunneling is only ever an extra.
     */
    fun canTunnel(mimeType: String?): Boolean {
        val types = mimeType?.trim()?.lowercase()?.takeIf { it.startsWith("video/") }?.let { listOf(it) }
            ?: listOf("video/avc", "video/hevc")
        return types.any { type ->
            runCatching {
                MediaCodecUtil.getDecoderInfos(type, /* requiresSecureDecoder = */ false, /* requiresTunnelingDecoder = */ true)
                    .any { it.tunneling && it.hardwareAccelerated && !it.softwareOnly }
            }.getOrDefault(false)
        }
    }
}
