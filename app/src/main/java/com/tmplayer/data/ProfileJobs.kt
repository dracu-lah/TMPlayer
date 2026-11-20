package com.tmplayer.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The long jobs of one profile (HX88): Save to folder, moving downloads, an external player's
 * stream, a cast. Each takes its owner ([Accounts.stamp]) when it starts, posts its notifications
 * for that owner ([ProfileNotifications.post]), and stops at the first switch, sign out or remove
 * ([stopOnSwitch]), its foreground notification taken down, so nothing of it lands in the next
 * profile's shade.
 */
object ProfileJobs {
    /** Runs [stop] once, at the next change of the open profile. */
    fun stopOnSwitch(scope: CoroutineScope, stop: () -> Unit): Job = scope.launch {
        Accounts.activeFolder.drop(1).first()
        runCatching(stop)
    }
}
