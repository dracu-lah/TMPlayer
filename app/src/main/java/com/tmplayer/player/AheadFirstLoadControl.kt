package com.tmplayer.player

import androidx.media3.common.Timeline
import androidx.media3.common.util.StuckPlayerException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator

/**
 * The player's byte budget, kept from starving the playback it is there to protect.
 *
 * [DefaultLoadControl][androidx.media3.exoplayer.DefaultLoadControl] stops loading once the bytes
 * it holds reach its target, however little of them can play, and it lets playback start on that
 * target alone. Two ways that left a video stuck:
 *
 * - A soundtrack that starts late in the file. A 2160p MKV kept in a ZIP had its chosen Hindi
 *   track begin 13.9 s in, 141 MB into an 80 Mbps file: 64 MB of picture filled the budget with
 *   nothing to play it against, loading stopped, and Media3 failed it four seconds later as
 *   "stuck buffering and not loading", every time.
 * - The back buffer. Ten seconds behind the position of a high rate stream is more than a 64 MB
 *   budget, so what had already played left a second or two ahead, and a start on the byte target
 *   alone handed the sound a fraction of a second, which never got going.
 *
 * So while less than [aheadFloorUs] is buffered ahead, loading goes on past the target up to
 * [ceilingBytes], and playback starts on time buffered (one second, four after a stall), not on
 * the target, while there is still room to load it. With enough ahead, or at the ceiling, the
 * delegate decides as before; a video stuck at the ceiling is one this device cannot hold enough
 * of to start ([isStuckAtCeiling]).
 *
 * The ceiling is on the allocator alone, not on the heap as the runtime reports it: Media3's
 * allocator keeps what it frees for the next load, so its high mark is the most the buffer ever
 * takes, while the runtime counts garbage not yet collected as used. A heap check refused a seek
 * back to the start while the allocator was only reusing what it already held, and the player
 * stuck there.
 */
@UnstableApi
class AheadFirstLoadControl(
    private val delegate: LoadControl,
    private val playbackUs: Long,
    private val rebufferUs: Long,
    private val ceilingBytes: Long,
    private val aheadFloorUs: Long = rebufferUs,
) : LoadControl {

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
        if (delegate.shouldContinueLoading(parameters)) return true
        return parameters.bufferedDurationUs < aheadFloorUs && roomFor(parameters.playerId)
    }

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean {
        if (!delegate.shouldStartPlayback(parameters)) return false
        val needed = if (parameters.rebuffering) rebufferUs else playbackUs
        // Ready on the byte target alone: wait for time buffered while it can still be loaded.
        return parameters.bufferedDurationUs >= needed || !roomFor(parameters.playerId)
    }

    private fun roomFor(playerId: PlayerId): Boolean =
        delegate.getAllocator(playerId).totalBytesAllocated < ceilingBytes

    override fun onPrepared(playerId: PlayerId) = delegate.onPrepared(playerId)

    override fun onTracksSelected(
        parameters: LoadControl.Parameters,
        trackGroups: TrackGroupArray,
        trackSelections: Array<out ExoTrackSelection?>,
    ) = delegate.onTracksSelected(parameters, trackGroups, trackSelections)

    override fun onStopped(playerId: PlayerId) = delegate.onStopped(playerId)

    override fun onReleased(playerId: PlayerId) = delegate.onReleased(playerId)

    override fun getAllocator(playerId: PlayerId): Allocator = delegate.getAllocator(playerId)

    override fun getBackBufferDurationUs(playerId: PlayerId): Long = delegate.getBackBufferDurationUs(playerId)

    override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean = delegate.retainBackBufferFromKeyframe(playerId)

    override fun shouldContinuePreloading(
        playerId: PlayerId,
        timeline: Timeline,
        mediaPeriodId: MediaSource.MediaPeriodId,
        bufferedDurationUs: Long,
    ): Boolean = delegate.shouldContinuePreloading(playerId, timeline, mediaPeriodId, bufferedDurationUs)

    companion object {
        /**
         * How far past its byte target the player may load to reach the time it needs: twice the
         * target on a low memory device, where a big buffer is what gets the app killed, else three
         * fifths of the heap, never under the target itself.
         */
        fun ceilingFor(targetBytes: Int, lowMemory: Boolean, maxHeapBytes: Long = Runtime.getRuntime().maxMemory()): Long =
            if (lowMemory) 2L * targetBytes else maxOf(targetBytes.toLong(), maxHeapBytes / 5 * 3)

        /**
         * Whether a failure is Media3 giving up on a player that waited with nothing to play and
         * would not load more: with this control in place, one that reached its ceiling.
         */
        fun isStuckAtCeiling(error: Throwable?): Boolean =
            generateSequence(error) { it.cause }.take(MAX_CAUSE_HOPS)
                .any { it is StuckPlayerException && it.stuckType == StuckPlayerException.STUCK_BUFFERING_NOT_LOADING }

        private const val MAX_CAUSE_HOPS = 8
    }
}
