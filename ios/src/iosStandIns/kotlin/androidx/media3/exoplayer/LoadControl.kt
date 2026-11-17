package androidx.media3.exoplayer

import androidx.media3.common.Timeline
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator

/**
 * Media3's buffering policy, in the shape Android's player wraps it (AheadFirstLoadControl). The
 * engine buffers by itself on iOS and reads ahead by what [DefaultLoadControl] was built with, so
 * nothing here is called by a player loop; the answers are the plain ones for a control that has
 * no allocator of its own.
 */
interface LoadControl {
    /** What a decision is about, Media3's. */
    class Parameters(
        val playerId: PlayerId,
        val timeline: Timeline,
        val mediaPeriodId: MediaSource.MediaPeriodId,
        val playbackPositionUs: Long,
        val bufferedDurationUs: Long,
        val playbackSpeed: Float,
        val playWhenReady: Boolean,
        val rebuffering: Boolean,
        val targetLiveOffsetUs: Long,
        val lastRebufferRealtimeMs: Long,
    )

    fun onPrepared(playerId: PlayerId) {}

    fun onTracksSelected(parameters: Parameters, trackGroups: TrackGroupArray, trackSelections: Array<out ExoTrackSelection?>) {}

    fun onStopped(playerId: PlayerId) {}

    fun onReleased(playerId: PlayerId) {}

    fun getAllocator(playerId: PlayerId): Allocator

    fun getBackBufferDurationUs(playerId: PlayerId): Long = 0L

    fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean = false

    fun shouldContinueLoading(parameters: Parameters): Boolean

    fun shouldContinuePreloading(playerId: PlayerId, timeline: Timeline, mediaPeriodId: MediaSource.MediaPeriodId, bufferedDurationUs: Long): Boolean = false

    fun shouldStartPlayback(parameters: Parameters): Boolean
}
