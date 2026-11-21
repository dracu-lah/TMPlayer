package com.tmplayer.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * The profile rule for notifications on iOS (HX88, invariant 6), in place of Android's
 * ProfileNotifications (Android's Notification and its channels have no iOS counterpart). The one
 * notice the iOS app posts is the player's "Playback stopped, tap to resume", a fixed line that
 * names nothing of the profile; it is the profile's all the same (a tap resumes its video), so it
 * comes down on every switch and with a sign out. Downloads and alerts post nothing on iOS today.
 */
object ProfileNotifications {
    /** For the process: what the profile left had posted comes down on each switch. */
    fun follow(context: Context, scope: CoroutineScope) {
        scope.launch { Accounts.activeFolder.drop(1).collect { clearProfile(context) } }
    }

    /** The open profile's notices off Notification Center: a sign out, a switch. */
    fun clearProfile(context: Context) {
        runCatching { com.tmplayer.player.PlaybackService.clearStopped(context) }
    }
}
