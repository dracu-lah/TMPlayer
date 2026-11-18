package io.github.anilbeesetti.nextlib.media3ext.ffdecoder

import androidx.media3.exoplayer.Renderer

// NextLib's FFmpeg renderers, which Android's player can switch to when a hardware decoder fails.
// On iOS the video engine decodes everything itself, so the library answers as it does on Android
// when its native part is not loaded: not available, no format supported, and no renderer of its
// own in the player (docs/IOS.md, layer 4).

object FfmpegLibrary {
    fun isAvailable(): Boolean = false

    fun supportsFormat(mimeType: String?): Boolean = false

    fun getVersion(): String? = null
}

abstract class FfmpegVideoRenderer : Renderer

/**
 * NextLib's constructor with the number of decoder threads. The player builds one only where a
 * renderer of this class is already in its list, which on iOS none is ([FfmpegLibrary.isAvailable]).
 */
@Suppress("UNUSED_PARAMETER", "FunctionName")
fun FfmpegVideoRenderer(
    allowedJoiningTimeMs: Long,
    eventHandler: android.os.Handler?,
    eventListener: androidx.media3.exoplayer.video.VideoRendererEventListener?,
    maxDroppedFramesToNotify: Int,
    threads: Int,
    numInputBuffers: Int,
    numOutputBuffers: Int,
): FfmpegVideoRenderer = error("FFmpeg's video renderer is never built on iOS")

abstract class FfmpegAudioRenderer : Renderer

/** NextLib's renderers factory, Media3's default with FFmpeg's renderers added on Android. */
open class NextRenderersFactory(context: android.content.Context) : androidx.media3.exoplayer.DefaultRenderersFactory(context)
