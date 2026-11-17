package androidx.media3.common

import androidx.media3.common.text.CueGroup

// Media3's Player on iOS, in Java's shape: getters are functions, as Android's player overrides
// them; its reads in Kotlin's property style (player.currentPosition) come from the extension
// properties generated beside the player's code (generate-getters.py). The one implementation
// that plays is com.tmplayer.ios.media3.EngineExoPlayer, over a video engine.

interface Player {
    fun addListener(listener: Listener)

    fun removeListener(listener: Listener)

    fun setMediaItem(mediaItem: MediaItem)

    fun setMediaItem(mediaItem: MediaItem, startPositionMs: Long)

    fun setMediaItem(mediaItem: MediaItem, resetPosition: Boolean)

    fun setMediaItems(mediaItems: List<MediaItem>)

    fun setMediaItems(mediaItems: List<MediaItem>, resetPosition: Boolean)

    fun setMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long)

    fun addMediaItem(mediaItem: MediaItem)

    fun replaceMediaItem(index: Int, mediaItem: MediaItem)

    fun clearMediaItems()

    fun getMediaItemCount(): Int

    fun getMediaItemAt(index: Int): MediaItem

    fun getCurrentMediaItem(): MediaItem?

    fun getCurrentMediaItemIndex(): Int

    fun isCurrentMediaItemSeekable(): Boolean

    fun isCurrentMediaItemLive(): Boolean

    fun isCurrentMediaItemDynamic(): Boolean

    fun getCurrentTimeline(): Timeline

    fun prepare()

    fun play()

    fun pause()

    fun stop()

    fun release()

    fun getPlaybackState(): Int

    fun getPlaybackSuppressionReason(): Int

    fun isPlaying(): Boolean

    fun isLoading(): Boolean

    fun getPlayerError(): PlaybackException?

    fun setPlayWhenReady(playWhenReady: Boolean)

    fun getPlayWhenReady(): Boolean

    fun setRepeatMode(repeatMode: Int)

    fun getRepeatMode(): Int

    fun setShuffleModeEnabled(shuffleModeEnabled: Boolean)

    fun getShuffleModeEnabled(): Boolean

    fun seekTo(positionMs: Long)

    fun seekTo(mediaItemIndex: Int, positionMs: Long)

    fun seekToDefaultPosition()

    fun seekBack()

    fun seekForward()

    fun getSeekBackIncrement(): Long

    fun getSeekForwardIncrement(): Long

    fun hasNextMediaItem(): Boolean

    fun hasPreviousMediaItem(): Boolean

    fun seekToNextMediaItem()

    fun seekToPreviousMediaItem()

    fun seekToNext()

    fun seekToPrevious()

    fun setPlaybackParameters(playbackParameters: PlaybackParameters)

    fun getPlaybackParameters(): PlaybackParameters

    fun setPlaybackSpeed(speed: Float)

    fun getDuration(): Long

    fun getCurrentPosition(): Long

    fun getBufferedPosition(): Long

    fun getBufferedPercentage(): Int

    fun getTotalBufferedDuration(): Long

    fun getContentDuration(): Long

    fun getContentPosition(): Long

    fun getContentBufferedPosition(): Long

    fun getCurrentTracks(): Tracks

    fun getTrackSelectionParameters(): TrackSelectionParameters

    fun setTrackSelectionParameters(parameters: TrackSelectionParameters)

    fun getMediaMetadata(): MediaMetadata

    fun getVideoSize(): VideoSize

    fun getVolume(): Float

    fun setVolume(volume: Float)

    fun getAudioAttributes(): AudioAttributes

    fun getCurrentCues(): CueGroup

    fun getAvailableCommands(): Commands

    fun isCommandAvailable(command: Int): Boolean

    /**
     * Media3 draws onto a surface; on iOS the app's video engine draws into its own view, so a
     * player ignores these two unless it has a surface to give.
     */
    fun setVideoSurfaceView(surfaceView: android.view.SurfaceView?) {}

    fun clearVideoSurface() {}

    /** What happens to a player, each callback a no-op until overridden, as in Media3. */
    interface Listener {
        fun onEvents(player: Player, events: Events) {}

        fun onTimelineChanged(timeline: Timeline, reason: Int) {}

        fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {}

        fun onTracksChanged(tracks: Tracks) {}

        fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {}

        fun onIsLoadingChanged(isLoading: Boolean) {}

        fun onAvailableCommandsChanged(availableCommands: Commands) {}

        fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) {}

        fun onPlaybackStateChanged(playbackState: Int) {}

        fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {}

        fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {}

        fun onIsPlayingChanged(isPlaying: Boolean) {}

        fun onRepeatModeChanged(repeatMode: Int) {}

        fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {}

        fun onPlayerError(error: PlaybackException) {}

        fun onPlayerErrorChanged(error: PlaybackException?) {}

        fun onPositionDiscontinuity(oldPosition: PositionInfo, newPosition: PositionInfo, reason: Int) {}

        fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {}

        fun onSeekBackIncrementChanged(seekBackIncrementMs: Long) {}

        fun onSeekForwardIncrementChanged(seekForwardIncrementMs: Long) {}

        fun onAudioAttributesChanged(audioAttributes: AudioAttributes) {}

        fun onVolumeChanged(volume: Float) {}

        fun onSkipSilenceEnabledChanged(skipSilenceEnabled: Boolean) {}

        fun onVideoSizeChanged(videoSize: VideoSize) {}

        fun onSurfaceSizeChanged(width: Int, height: Int) {}

        fun onRenderedFirstFrame() {}

        fun onCues(cueGroup: CueGroup) {}
    }

    /** The events of one round of callbacks, as Media3's. */
    class Events(private val flags: FlagSet) {
        fun contains(event: Int): Boolean = flags.contains(event)

        fun containsAny(vararg events: Int): Boolean = flags.containsAny(*events)

        fun size(): Int = flags.size()

        fun get(index: Int): Int = flags.get(index)

        override fun equals(other: Any?): Boolean = other is Events && other.flags == flags

        override fun hashCode(): Int = flags.hashCode()
    }

    /** The commands a player offers, as Media3's. */
    class Commands private constructor(private val flags: FlagSet) {
        fun contains(command: Int): Boolean = flags.contains(command)

        fun containsAny(vararg commands: Int): Boolean = flags.containsAny(*commands)

        fun size(): Int = flags.size()

        fun get(index: Int): Int = flags.get(index)

        fun buildUpon(): Builder = Builder().addAll(this)

        override fun equals(other: Any?): Boolean = other is Commands && other.flags == flags

        override fun hashCode(): Int = flags.hashCode()

        class Builder {
            private val flags = FlagSet.Builder()

            fun add(command: Int): Builder = apply { flags.add(command) }

            fun addIf(command: Int, condition: Boolean): Builder = apply { flags.addIf(command, condition) }

            fun addAll(vararg commands: Int): Builder = apply { flags.addAll(*commands) }

            fun addAll(commands: Commands): Builder = apply { flags.addAll(commands.flags) }

            fun addAllCommands(): Builder = apply { flags.addAll(*ALL_COMMANDS) }

            fun remove(command: Int): Builder = apply { flags.remove(command) }

            fun removeAll(vararg commands: Int): Builder = apply { flags.removeAll(*commands) }

            fun removeIf(command: Int, condition: Boolean): Builder = apply { if (condition) flags.remove(command) }

            fun build(): Commands = Commands(flags.build())
        }

        companion object {
            val EMPTY: Commands = Builder().build()
        }
    }

    /** Where playback was or is, for a position discontinuity. */
    class PositionInfo(
        val windowUid: Any?,
        val mediaItemIndex: Int,
        val mediaItem: MediaItem?,
        val periodUid: Any?,
        val periodIndex: Int,
        val positionMs: Long,
        val contentPositionMs: Long,
        val adGroupIndex: Int,
        val adIndexInAdGroup: Int,
    )

    companion object {
        const val STATE_IDLE = 1
        const val STATE_BUFFERING = 2
        const val STATE_READY = 3
        const val STATE_ENDED = 4

        const val PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST = 1
        const val PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS = 2
        const val PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY = 3
        const val PLAY_WHEN_READY_CHANGE_REASON_REMOTE = 4
        const val PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM = 5
        const val PLAY_WHEN_READY_CHANGE_REASON_SUPPRESSED_TOO_LONG = 6

        const val PLAYBACK_SUPPRESSION_REASON_NONE = 0
        const val PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS = 1
        const val PLAYBACK_SUPPRESSION_REASON_UNSUITABLE_AUDIO_OUTPUT = 3

        const val REPEAT_MODE_OFF = 0
        const val REPEAT_MODE_ONE = 1
        const val REPEAT_MODE_ALL = 2

        const val DISCONTINUITY_REASON_AUTO_TRANSITION = 0
        const val DISCONTINUITY_REASON_SEEK = 1
        const val DISCONTINUITY_REASON_SEEK_ADJUSTMENT = 2
        const val DISCONTINUITY_REASON_SKIP = 3
        const val DISCONTINUITY_REASON_REMOVE = 4
        const val DISCONTINUITY_REASON_INTERNAL = 5
        const val DISCONTINUITY_REASON_SILENCE_SKIP = 6

        const val TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED = 0
        const val TIMELINE_CHANGE_REASON_SOURCE_UPDATE = 1

        const val MEDIA_ITEM_TRANSITION_REASON_REPEAT = 0
        const val MEDIA_ITEM_TRANSITION_REASON_AUTO = 1
        const val MEDIA_ITEM_TRANSITION_REASON_SEEK = 2
        const val MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED = 3

        const val EVENT_TIMELINE_CHANGED = 0
        const val EVENT_MEDIA_ITEM_TRANSITION = 1
        const val EVENT_TRACKS_CHANGED = 2
        const val EVENT_IS_LOADING_CHANGED = 3
        const val EVENT_PLAYBACK_STATE_CHANGED = 4
        const val EVENT_PLAY_WHEN_READY_CHANGED = 5
        const val EVENT_PLAYBACK_SUPPRESSION_REASON_CHANGED = 6
        const val EVENT_IS_PLAYING_CHANGED = 7
        const val EVENT_REPEAT_MODE_CHANGED = 8
        const val EVENT_SHUFFLE_MODE_ENABLED_CHANGED = 9
        const val EVENT_PLAYER_ERROR = 10
        const val EVENT_POSITION_DISCONTINUITY = 11
        const val EVENT_PLAYBACK_PARAMETERS_CHANGED = 12
        const val EVENT_AVAILABLE_COMMANDS_CHANGED = 13
        const val EVENT_MEDIA_METADATA_CHANGED = 14
        const val EVENT_PLAYLIST_METADATA_CHANGED = 15
        const val EVENT_SEEK_BACK_INCREMENT_CHANGED = 16
        const val EVENT_SEEK_FORWARD_INCREMENT_CHANGED = 17
        const val EVENT_MAX_SEEK_TO_PREVIOUS_POSITION_CHANGED = 18
        const val EVENT_TRACK_SELECTION_PARAMETERS_CHANGED = 19
        const val EVENT_AUDIO_ATTRIBUTES_CHANGED = 20
        const val EVENT_AUDIO_SESSION_ID = 21
        const val EVENT_VOLUME_CHANGED = 22
        const val EVENT_SKIP_SILENCE_ENABLED_CHANGED = 23
        const val EVENT_SURFACE_SIZE_CHANGED = 24
        const val EVENT_VIDEO_SIZE_CHANGED = 25
        const val EVENT_RENDERED_FIRST_FRAME = 26
        const val EVENT_CUES = 27
        const val EVENT_METADATA = 28
        const val EVENT_DEVICE_INFO_CHANGED = 29
        const val EVENT_DEVICE_VOLUME_CHANGED = 30

        const val COMMAND_INVALID = -1
        const val COMMAND_PLAY_PAUSE = 1
        const val COMMAND_PREPARE = 2
        const val COMMAND_STOP = 3
        const val COMMAND_SEEK_TO_DEFAULT_POSITION = 4
        const val COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM = 5
        const val COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM = 6
        const val COMMAND_SEEK_TO_PREVIOUS = 7
        const val COMMAND_SEEK_TO_NEXT_MEDIA_ITEM = 8
        const val COMMAND_SEEK_TO_NEXT = 9
        const val COMMAND_SEEK_TO_MEDIA_ITEM = 10
        const val COMMAND_SEEK_BACK = 11
        const val COMMAND_SEEK_FORWARD = 12
        const val COMMAND_SET_SPEED_AND_PITCH = 13
        const val COMMAND_SET_SHUFFLE_MODE = 14
        const val COMMAND_SET_REPEAT_MODE = 15
        const val COMMAND_GET_CURRENT_MEDIA_ITEM = 16
        const val COMMAND_GET_TIMELINE = 17
        const val COMMAND_GET_METADATA = 18
        const val COMMAND_SET_PLAYLIST_METADATA = 19
        const val COMMAND_CHANGE_MEDIA_ITEMS = 20
        const val COMMAND_GET_AUDIO_ATTRIBUTES = 21
        const val COMMAND_GET_VOLUME = 22
        const val COMMAND_GET_DEVICE_VOLUME = 23
        const val COMMAND_SET_VOLUME = 24
        const val COMMAND_SET_DEVICE_VOLUME = 25
        const val COMMAND_ADJUST_DEVICE_VOLUME = 26
        const val COMMAND_SET_VIDEO_SURFACE = 27
        const val COMMAND_GET_TEXT = 28
        const val COMMAND_SET_TRACK_SELECTION_PARAMETERS = 29
        const val COMMAND_GET_TRACKS = 30
        const val COMMAND_SET_MEDIA_ITEM = 31
        const val COMMAND_RELEASE = 32

        /** Every command above, for Commands.Builder.addAllCommands. */
        internal val ALL_COMMANDS = (1..32).toList().toIntArray()
    }
}
