package androidx.media3.session

import android.content.Context
import androidx.media3.common.Player

/**
 * Media3's media session: what the system's controls (lock screen, headsets) drive. On iOS the
 * session is Now Playing and the remote commands (com.tmplayer.ios.media3.NowPlaying), from the
 * moment it is built until it is released.
 */
class MediaSession private constructor(player: Player, val id: String) {
    var player: Player = player
        set(value) {
            field = value
            com.tmplayer.ios.media3.NowPlaying.playerChanged(this)
        }

    /** Who controls a session, Media3's. */
    class ControllerInfo internal constructor(val packageName: String)

    fun setMediaButtonPreferences(buttons: List<CommandButton>) = Unit

    fun setSessionActivity(activity: android.app.PendingIntent?) = Unit

    fun release() = com.tmplayer.ios.media3.NowPlaying.hide(this)

    class Builder(@Suppress("UNUSED_PARAMETER") context: Context, private val player: Player) {
        private var id = ""

        fun setId(id: String): Builder = apply { this.id = id }

        fun setMediaButtonPreferences(buttons: List<CommandButton>): Builder = this

        fun setCustomLayout(buttons: List<CommandButton>): Builder = this

        fun setSessionActivity(activity: android.app.PendingIntent): Builder = this

        fun build(): MediaSession = MediaSession(player, id).also(com.tmplayer.ios.media3.NowPlaying::show)
    }
}
