package androidx.media3.common

import androidx.media3.common.text.CueGroup

/**
 * Media3's ForwardingPlayer: every call to the wrapped player, and its listeners told about this
 * player instead, as Android's player wraps one (StateChangeOnlyPlayer, EpisodeStepping).
 */
open class ForwardingPlayer(private val player: Player) : Player {
    fun getWrappedPlayer(): Player = player

    override fun addListener(listener: Player.Listener) = player.addListener(Forwarding(this, listener))

    override fun removeListener(listener: Player.Listener) = player.removeListener(Forwarding(this, listener))

    override fun setMediaItem(mediaItem: MediaItem) = player.setMediaItem(mediaItem)

    override fun setMediaItem(mediaItem: MediaItem, startPositionMs: Long) = player.setMediaItem(mediaItem, startPositionMs)

    override fun setMediaItem(mediaItem: MediaItem, resetPosition: Boolean) = player.setMediaItem(mediaItem, resetPosition)

    override fun setMediaItems(mediaItems: List<MediaItem>) = player.setMediaItems(mediaItems)

    override fun setMediaItems(mediaItems: List<MediaItem>, resetPosition: Boolean) = player.setMediaItems(mediaItems, resetPosition)

    override fun setMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long) =
        player.setMediaItems(mediaItems, startIndex, startPositionMs)

    override fun addMediaItem(mediaItem: MediaItem) = player.addMediaItem(mediaItem)

    override fun replaceMediaItem(index: Int, mediaItem: MediaItem) = player.replaceMediaItem(index, mediaItem)

    override fun clearMediaItems() = player.clearMediaItems()

    override fun getMediaItemCount(): Int = player.getMediaItemCount()

    override fun getMediaItemAt(index: Int): MediaItem = player.getMediaItemAt(index)

    override fun getCurrentMediaItem(): MediaItem? = player.getCurrentMediaItem()

    override fun getCurrentMediaItemIndex(): Int = player.getCurrentMediaItemIndex()

    override fun isCurrentMediaItemSeekable(): Boolean = player.isCurrentMediaItemSeekable()

    override fun isCurrentMediaItemLive(): Boolean = player.isCurrentMediaItemLive()

    override fun isCurrentMediaItemDynamic(): Boolean = player.isCurrentMediaItemDynamic()

    override fun getCurrentTimeline(): Timeline = player.getCurrentTimeline()

    override fun prepare() = player.prepare()

    override fun play() = player.play()

    override fun pause() = player.pause()

    override fun stop() = player.stop()

    override fun release() = player.release()

    override fun getPlaybackState(): Int = player.getPlaybackState()

    override fun getPlaybackSuppressionReason(): Int = player.getPlaybackSuppressionReason()

    override fun isPlaying(): Boolean = player.isPlaying()

    override fun isLoading(): Boolean = player.isLoading()

    override fun getPlayerError(): PlaybackException? = player.getPlayerError()

    override fun setPlayWhenReady(playWhenReady: Boolean) = player.setPlayWhenReady(playWhenReady)

    override fun getPlayWhenReady(): Boolean = player.getPlayWhenReady()

    override fun setRepeatMode(repeatMode: Int) = player.setRepeatMode(repeatMode)

    override fun getRepeatMode(): Int = player.getRepeatMode()

    override fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) = player.setShuffleModeEnabled(shuffleModeEnabled)

    override fun getShuffleModeEnabled(): Boolean = player.getShuffleModeEnabled()

    override fun seekTo(positionMs: Long) = player.seekTo(positionMs)

    override fun seekTo(mediaItemIndex: Int, positionMs: Long) = player.seekTo(mediaItemIndex, positionMs)

    override fun seekToDefaultPosition() = player.seekToDefaultPosition()

    override fun seekBack() = player.seekBack()

    override fun seekForward() = player.seekForward()

    override fun getSeekBackIncrement(): Long = player.getSeekBackIncrement()

    override fun getSeekForwardIncrement(): Long = player.getSeekForwardIncrement()

    override fun hasNextMediaItem(): Boolean = player.hasNextMediaItem()

    override fun hasPreviousMediaItem(): Boolean = player.hasPreviousMediaItem()

    override fun seekToNextMediaItem() = player.seekToNextMediaItem()

    override fun seekToPreviousMediaItem() = player.seekToPreviousMediaItem()

    override fun seekToNext() = player.seekToNext()

    override fun seekToPrevious() = player.seekToPrevious()

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) = player.setPlaybackParameters(playbackParameters)

    override fun getPlaybackParameters(): PlaybackParameters = player.getPlaybackParameters()

    override fun setPlaybackSpeed(speed: Float) = player.setPlaybackSpeed(speed)

    override fun getDuration(): Long = player.getDuration()

    override fun getCurrentPosition(): Long = player.getCurrentPosition()

    override fun getBufferedPosition(): Long = player.getBufferedPosition()

    override fun getBufferedPercentage(): Int = player.getBufferedPercentage()

    override fun getTotalBufferedDuration(): Long = player.getTotalBufferedDuration()

    override fun getContentDuration(): Long = player.getContentDuration()

    override fun getContentPosition(): Long = player.getContentPosition()

    override fun getContentBufferedPosition(): Long = player.getContentBufferedPosition()

    override fun getCurrentTracks(): Tracks = player.getCurrentTracks()

    override fun getTrackSelectionParameters(): TrackSelectionParameters = player.getTrackSelectionParameters()

    override fun setTrackSelectionParameters(parameters: TrackSelectionParameters) = player.setTrackSelectionParameters(parameters)

    override fun getMediaMetadata(): MediaMetadata = player.getMediaMetadata()

    override fun getVideoSize(): VideoSize = player.getVideoSize()

    override fun getVolume(): Float = player.getVolume()

    override fun setVolume(volume: Float) = player.setVolume(volume)

    override fun getAudioAttributes(): AudioAttributes = player.getAudioAttributes()

    override fun getCurrentCues(): CueGroup = player.getCurrentCues()

    override fun getAvailableCommands(): Player.Commands = player.getAvailableCommands()

    override fun isCommandAvailable(command: Int): Boolean = getAvailableCommands().contains(command)

    override fun setVideoSurfaceView(surfaceView: android.view.SurfaceView?) = player.setVideoSurfaceView(surfaceView)

    override fun clearVideoSurface() = player.clearVideoSurface()

    /** A listener of the wrapped player that tells its own listener about this player; equal by that listener. */
    private class Forwarding(private val forwarding: Player, private val listener: Player.Listener) : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = listener.onEvents(forwarding, events)

        override fun onTimelineChanged(timeline: Timeline, reason: Int) = listener.onTimelineChanged(timeline, reason)

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = listener.onMediaItemTransition(mediaItem, reason)

        override fun onTracksChanged(tracks: Tracks) = listener.onTracksChanged(tracks)

        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) = listener.onMediaMetadataChanged(mediaMetadata)

        override fun onIsLoadingChanged(isLoading: Boolean) = listener.onIsLoadingChanged(isLoading)

        override fun onAvailableCommandsChanged(availableCommands: Player.Commands) = listener.onAvailableCommandsChanged(availableCommands)

        override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) = listener.onTrackSelectionParametersChanged(parameters)

        override fun onPlaybackStateChanged(playbackState: Int) = listener.onPlaybackStateChanged(playbackState)

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = listener.onPlayWhenReadyChanged(playWhenReady, reason)

        override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) =
            listener.onPlaybackSuppressionReasonChanged(playbackSuppressionReason)

        override fun onIsPlayingChanged(isPlaying: Boolean) = listener.onIsPlayingChanged(isPlaying)

        override fun onRepeatModeChanged(repeatMode: Int) = listener.onRepeatModeChanged(repeatMode)

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = listener.onShuffleModeEnabledChanged(shuffleModeEnabled)

        override fun onPlayerError(error: PlaybackException) = listener.onPlayerError(error)

        override fun onPlayerErrorChanged(error: PlaybackException?) = listener.onPlayerErrorChanged(error)

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) =
            listener.onPositionDiscontinuity(oldPosition, newPosition, reason)

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) = listener.onPlaybackParametersChanged(playbackParameters)

        override fun onSeekBackIncrementChanged(seekBackIncrementMs: Long) = listener.onSeekBackIncrementChanged(seekBackIncrementMs)

        override fun onSeekForwardIncrementChanged(seekForwardIncrementMs: Long) = listener.onSeekForwardIncrementChanged(seekForwardIncrementMs)

        override fun onAudioAttributesChanged(audioAttributes: AudioAttributes) = listener.onAudioAttributesChanged(audioAttributes)

        override fun onVolumeChanged(volume: Float) = listener.onVolumeChanged(volume)

        override fun onSkipSilenceEnabledChanged(skipSilenceEnabled: Boolean) = listener.onSkipSilenceEnabledChanged(skipSilenceEnabled)

        override fun onVideoSizeChanged(videoSize: VideoSize) = listener.onVideoSizeChanged(videoSize)

        override fun onSurfaceSizeChanged(width: Int, height: Int) = listener.onSurfaceSizeChanged(width, height)

        override fun onRenderedFirstFrame() = listener.onRenderedFirstFrame()

        override fun onCues(cueGroup: CueGroup) = listener.onCues(cueGroup)

        override fun equals(other: Any?): Boolean = other is Forwarding && other.forwarding === forwarding && other.listener == listener

        override fun hashCode(): Int = 31 * forwarding.hashCode() + listener.hashCode()
    }
}
