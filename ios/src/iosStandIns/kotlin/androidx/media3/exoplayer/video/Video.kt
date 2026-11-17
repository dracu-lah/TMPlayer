package androidx.media3.exoplayer.video

import androidx.media3.common.Format

/** What a video renderer reports, Media3's: every call has an empty default. */
interface VideoRendererEventListener {
    fun onVideoEnabled(counters: androidx.media3.exoplayer.DecoderCounters) {}

    fun onVideoDecoderInitialized(decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {}

    fun onDroppedFrames(count: Int, elapsedMs: Long) {}
}

/** The time and format of each frame about to be shown, Media3's. */
fun interface VideoFrameMetadataListener {
    fun onVideoFrameAboutToBeRendered(presentationTimeUs: Long, releaseTimeNs: Long, format: Format, mediaFormat: Any?)
}

/** Media3's MediaCodec video renderer; none is made on iOS, where the engine decodes. */
abstract class MediaCodecVideoRenderer : androidx.media3.exoplayer.Renderer
