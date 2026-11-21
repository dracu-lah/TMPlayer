package com.tmplayer.ios.media3

import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSNumber
import platform.Foundation.dataWithBytes
import platform.MediaPlayer.MPChangePlaybackPositionCommandEvent
import platform.MediaPlayer.MPMediaItemArtwork
import platform.MediaPlayer.MPMediaItemPropertyArtist
import platform.MediaPlayer.MPMediaItemPropertyArtwork
import platform.MediaPlayer.MPMediaItemPropertyPlaybackDuration
import platform.MediaPlayer.MPMediaItemPropertyTitle
import platform.MediaPlayer.MPNowPlayingInfoCenter
import platform.MediaPlayer.MPNowPlayingInfoMediaTypeVideo
import platform.MediaPlayer.MPNowPlayingInfoPropertyDefaultPlaybackRate
import platform.MediaPlayer.MPNowPlayingInfoPropertyElapsedPlaybackTime
import platform.MediaPlayer.MPNowPlayingInfoPropertyMediaType
import platform.MediaPlayer.MPNowPlayingInfoPropertyPlaybackRate
import platform.MediaPlayer.MPRemoteCommand
import platform.MediaPlayer.MPRemoteCommandCenter
import platform.MediaPlayer.MPRemoteCommandHandlerStatusCommandFailed
import platform.MediaPlayer.MPRemoteCommandHandlerStatusSuccess
import kotlinx.coroutines.launch
import platform.UIKit.UIImage

/** What the system's screens name while a PIN profile is hidden. */
private const val APP_NAME = "TMPlayer" // i18n-ok: the app's name


/**
 * Android's media session as iOS's system controls (docs/IOS.md, layer 4 step 7): what
 * the session's player plays is iOS's Now Playing (the lock screen, Control Center, CarPlay), and
 * the remote commands (those screens, a headset's buttons, a keyboard's media keys, Siri) drive
 * the session's player as Android's system controls drive a Media3 session.
 *
 * As on Android 13 and later: a seek bar, play and pause, and the previous and next episode where
 * the session's player offers them (upstream's EpisodeStepping); the skip back and forward by the
 * player's own amounts for a headset and the keys, which iOS's screens show where there is no
 * episode to step to. The name, detail and thumbnail are the session item's metadata, which
 * upstream fills and, with the app lock on, empties to the app's name.
 *
 * One session holds Now Playing at a time, the latest made, as the system shows the active one;
 * releasing it clears Now Playing and the commands.
 */
internal object NowPlaying {
    private var session: MediaSession? = null
    private var watched: Player? = null
    private val targets = ArrayList<Pair<MPRemoteCommand, Any>>()
    private var artwork: Pair<ByteArray, MPMediaItemArtwork>? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refresh()
    }

    /** [session] takes the system's controls. */
    fun show(session: MediaSession) {
        this.session = session
        watch(session.player)
        if (targets.isEmpty()) listen()
        followProfile()
        refresh()
    }

    /**
     * Drawn again each time a PIN profile hides or shows (HX88, invariant 6): while hidden (the
     * app away, or locked) the lock screen and Control Center show the app's name only.
     */
    private var profileWatch: kotlinx.coroutines.Job? = null

    private fun followProfile() {
        if (profileWatch != null) return
        profileWatch = kotlinx.coroutines.MainScope().launch {
            com.tmplayer.data.ProfileUnlock.hidden().collect { refresh() }
        }
    }

    /** [session]'s player changed (the next episode played in place). */
    fun playerChanged(session: MediaSession) {
        if (this.session !== session) return
        watch(session.player)
        refresh()
    }

    /** [session] has gone; Now Playing and the commands go with it, unless another holds them. */
    fun hide(session: MediaSession) {
        if (this.session !== session) return
        this.session = null
        watch(null)
        profileWatch?.cancel()
        profileWatch = null
        val center = MPRemoteCommandCenter.sharedCommandCenter()
        for ((command, target) in targets) command.removeTarget(target)
        targets.clear()
        center.nextTrackCommand.enabled = false
        center.previousTrackCommand.enabled = false
        artwork = null
        MPNowPlayingInfoCenter.defaultCenter().nowPlayingInfo = null
    }

    private fun watch(player: Player?) {
        if (watched === player) return
        watched?.removeListener(listener)
        watched = player
        player?.addListener(listener)
    }

    private fun player(): Player? = session?.player

    private fun listen() {
        val center = MPRemoteCommandCenter.sharedCommandCenter()
        fun on(command: MPRemoteCommand, action: (Player, Any?) -> Boolean) {
            command.enabled = true
            val target = command.addTargetWithHandler { event ->
                val player = player()
                if (player != null && runCatching { action(player, event) }.getOrDefault(false)) {
                    MPRemoteCommandHandlerStatusSuccess
                } else {
                    MPRemoteCommandHandlerStatusCommandFailed
                }
            }
            targets += command to target
        }
        on(center.playCommand) { player, _ -> play(player) }
        on(center.pauseCommand) { player, _ -> player.pause().let { true } }
        on(center.togglePlayPauseCommand) { player, _ -> if (showsPause(player)) player.pause().let { true } else play(player) }
        on(center.changePlaybackPositionCommand) { player, event ->
            val at = (event as? MPChangePlaybackPositionCommandEvent)?.positionTime ?: return@on false
            player.seekTo((at * 1000).toLong().coerceAtLeast(0))
            true
        }
        on(center.skipForwardCommand) { player, _ -> player.seekForward().let { true } }
        on(center.skipBackwardCommand) { player, _ -> player.seekBack().let { true } }
        on(center.nextTrackCommand) { player, _ ->
            player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT).also { if (it) player.seekToNext() }
        }
        on(center.previousTrackCommand) { player, _ ->
            player.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS).also { if (it) player.seekToPrevious() }
        }
    }

    // Media3's Util.handlePlayButtonAction: a stopped player prepared, an ended one from the start.
    private fun play(player: Player): Boolean {
        when (player.getPlaybackState()) {
            Player.STATE_IDLE -> player.prepare()
            Player.STATE_ENDED -> player.seekToDefaultPosition()
        }
        player.play()
        return true
    }

    // Media3's Util.shouldShowPlayButton, inverted.
    private fun showsPause(player: Player): Boolean =
        player.getPlayWhenReady() && player.getPlaybackState() != Player.STATE_IDLE && player.getPlaybackState() != Player.STATE_ENDED

    @OptIn(ExperimentalForeignApi::class)
    private fun refresh() {
        val player = player() ?: return
        val center = MPRemoteCommandCenter.sharedCommandCenter()
        center.nextTrackCommand.enabled = player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT)
        center.previousTrackCommand.enabled = player.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS)
        center.skipForwardCommand.preferredIntervals = listOf(NSNumber(double = player.getSeekForwardIncrement() / 1000.0))
        center.skipBackwardCommand.preferredIntervals = listOf(NSNumber(double = player.getSeekBackIncrement() / 1000.0))
        val metadata = player.getMediaMetadata()
        val info = HashMap<Any?, Any?>()
        // Upstream's player already empties its metadata to the app's name while the profile is
        // hidden; this holds the same rule at the last step before the system's screens (HX88).
        val hidden = runCatching { com.tmplayer.data.ProfileUnlock.hiddenNow() }.getOrDefault(true)
        info[MPMediaItemPropertyTitle] = if (hidden) APP_NAME else (metadata.displayTitle ?: metadata.title)?.toString().orEmpty()
        info[MPMediaItemPropertyArtist] = if (hidden) "" else (metadata.artist ?: metadata.subtitle)?.toString().orEmpty()
        info[MPNowPlayingInfoPropertyMediaType] = NSNumber(unsignedLong = MPNowPlayingInfoMediaTypeVideo)
        val duration = player.getDuration()
        if (duration > 0) info[MPMediaItemPropertyPlaybackDuration] = NSNumber(double = duration / 1000.0)
        info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = NSNumber(double = player.getCurrentPosition().coerceAtLeast(0) / 1000.0)
        val speed = player.getPlaybackParameters().speed.toDouble()
        info[MPNowPlayingInfoPropertyPlaybackRate] = NSNumber(double = if (player.isPlaying()) speed else 0.0)
        info[MPNowPlayingInfoPropertyDefaultPlaybackRate] = NSNumber(double = 1.0)
        if (!hidden) artworkOf(metadata.artworkData)?.let { info[MPMediaItemPropertyArtwork] = it }
        MPNowPlayingInfoCenter.defaultCenter().nowPlayingInfo = info
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun artworkOf(bytes: ByteArray?): MPMediaItemArtwork? {
        if (bytes == null || bytes.isEmpty()) return null
        artwork?.let { (made, art) -> if (made.contentEquals(bytes)) return art }
        val data = bytes.usePinned { NSData.dataWithBytes(it.addressOf(0), bytes.size.toULong()) }
        val image = UIImage.imageWithData(data) ?: return null
        val art = MPMediaItemArtwork(boundsSize = image.size) { _ -> image }
        artwork = bytes to art
        return art
    }
}
