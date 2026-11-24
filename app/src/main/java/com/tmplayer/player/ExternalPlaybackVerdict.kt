package com.tmplayer.player

/**
 * The player's "may this video leave Telegram's hands" answer ([savable]: null until known), and the
 * same answer told to the screen's other outlets each time it changes (SEC2-01). On iOS the engine's
 * AirPlay (Control Center's and the lock screen's route button, not only the More panel's row) is
 * allowed only while this is true: a chat that restricts saving, or one not yet asked, never plays on
 * another screen. Android has nothing to tell and answers with a no-op.
 */
internal class ExternalPlaybackVerdict(private val tell: (Boolean) -> Unit) {
    var savable: Boolean? = null
        set(value) {
            field = value
            tell(value == true)
        }
}
