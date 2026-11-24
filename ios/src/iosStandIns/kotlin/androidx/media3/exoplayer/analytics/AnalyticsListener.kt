package androidx.media3.exoplayer.analytics

import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.DecoderReuseEvaluation

/** What ExoPlayer reports for analysis, Media3's: every call has an empty default. */
interface AnalyticsListener {
    /** When something happened, Media3's. */
    class EventTime(
        val realtimeMs: Long,
        val windowIndex: Int,
        val eventPlaybackPositionMs: Long,
        val currentPlaybackPositionMs: Long,
        val totalBufferedDurationMs: Long,
    )

    /** The sound output opened (a new video, another track), Media3's: with the encoding it was opened for. */
    fun onAudioTrackInitialized(eventTime: EventTime, audioTrackConfig: androidx.media3.exoplayer.audio.AudioSink.AudioTrackConfig) {}

    fun onAudioUnderrun(eventTime: EventTime, bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) {}

    fun onVideoDecoderInitialized(eventTime: EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {}

    fun onAudioDecoderInitialized(eventTime: EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {}

    fun onVideoInputFormatChanged(eventTime: EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?) {}

    fun onAudioInputFormatChanged(eventTime: EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?) {}

    fun onDroppedVideoFrames(eventTime: EventTime, droppedFrames: Int, elapsedMs: Long) {}

    fun onVideoFrameProcessingOffset(eventTime: EventTime, totalProcessingOffsetUs: Long, frameCount: Int) {}

    fun onRenderedFirstFrame(eventTime: EventTime, output: Any, renderTimeMs: Long) {}

    fun onBandwidthEstimate(eventTime: EventTime, totalLoadTimeMs: Int, totalBytesLoaded: Long, bitrateEstimate: Long) {}

    fun onPlayerError(eventTime: EventTime, error: PlaybackException) {}

    fun onPlaybackStateChanged(eventTime: EventTime, state: Int) {}

    fun onPlayWhenReadyChanged(eventTime: EventTime, playWhenReady: Boolean, reason: Int) {}

    fun onVideoCodecError(eventTime: EventTime, videoCodecError: Exception) {}

    fun onAudioCodecError(eventTime: EventTime, audioCodecError: Exception) {}
}
