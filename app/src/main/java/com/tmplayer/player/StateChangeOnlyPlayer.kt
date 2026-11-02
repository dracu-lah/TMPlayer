package com.tmplayer.player

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.FlagSet

/**
 * The player as a media session sees it, without the events that change nothing the system shows.
 *
 * Media3 answers every player event it hears with a synchronous binder call into the system
 * server (`MediaSession.setPlaybackState`). A stream from Telegram flips "is loading" on and off
 * with every chunk it reads, so the session made a call per chunk from the main thread; on a
 * loaded Mi TV one of them was slow enough to be an ANR (HX74, Sentry 157715772). Play, pause,
 * buffering, ready, ended, seeks, speed, the item and the commands still go through: those are
 * the state changes the notification and the lock screen draw.
 */
open class StateChangeOnlyPlayer(player: Player) : ForwardingPlayer(player) {
    private val wrapped = HashMap<Player.Listener, Player.Listener>()

    override fun addListener(listener: Player.Listener) {
        val filtering = wrapped.getOrPut(listener) { Filtering(listener) }
        super.addListener(filtering)
    }

    override fun removeListener(listener: Player.Listener) {
        super.removeListener(wrapped.remove(listener) ?: listener)
    }

    private class Filtering(private val to: Player.Listener) : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            val kept = withoutNoise(events) ?: return
            to.onEvents(player, kept)
        }

        // Everything else is passed on one by one, except the noise.
        override fun onIsLoadingChanged(isLoading: Boolean) = Unit
        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) = to.onTimelineChanged(timeline, reason)
        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) = to.onMediaItemTransition(mediaItem, reason)
        override fun onMediaMetadataChanged(mediaMetadata: androidx.media3.common.MediaMetadata) = to.onMediaMetadataChanged(mediaMetadata)
        override fun onPlaybackStateChanged(playbackState: Int) = to.onPlaybackStateChanged(playbackState)
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = to.onPlayWhenReadyChanged(playWhenReady, reason)
        override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) = to.onPlaybackSuppressionReasonChanged(playbackSuppressionReason)
        override fun onIsPlayingChanged(isPlaying: Boolean) = to.onIsPlayingChanged(isPlaying)
        override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) = to.onPlaybackParametersChanged(playbackParameters)
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) = to.onPlayerError(error)
        override fun onPlayerErrorChanged(error: androidx.media3.common.PlaybackException?) = to.onPlayerErrorChanged(error)
        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) = to.onPositionDiscontinuity(oldPosition, newPosition, reason)
        override fun onAvailableCommandsChanged(availableCommands: Player.Commands) = to.onAvailableCommandsChanged(availableCommands)
    }

    companion object {
        /** The events no session needs. */
        private val NOISE = intArrayOf(Player.EVENT_IS_LOADING_CHANGED)

        /** [events] without the noise, or null when nothing else is left in it. */
        fun withoutNoise(events: Player.Events): Player.Events? {
            if (NOISE.none { events.contains(it) }) return events
            val kept = FlagSet.Builder()
            for (i in 0 until events.size()) {
                val event = events.get(i)
                if (event !in NOISE) kept.add(event)
            }
            val flags = kept.build()
            return if (flags.size() == 0) null else Player.Events(flags)
        }
    }
}
