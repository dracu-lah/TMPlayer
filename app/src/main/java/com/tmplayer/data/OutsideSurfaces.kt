package com.tmplayer.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * What shows the open profile outside the app (HX88, docs/agents/multi-account.md): cleared each
 * time the open profile is hidden ([ProfileUnlock.hidden]), its first value included, so a cold
 * start with a PIN locked profile takes down what an earlier run left on the launcher, and so does
 * a lock that happens while the app is away.
 */
object OutsideSurfaces {
    /**
     * Also on every switch ([switches]): the launcher's rows are the profile that was open, PIN or
     * not, and the next profile's Home writes its own. A sign out or a remove of the open profile
     * is a switch too.
     */
    fun follow(
        scope: CoroutineScope,
        hidden: Flow<Boolean> = ProfileUnlock.hidden(),
        switches: Flow<Any?> = Accounts.activeFolder.drop(1),
        clear: suspend () -> Unit,
    ): Job = scope.launch {
        launch { hidden.collect { now -> if (now) runCatching { clear() } } }
        launch { switches.collect { runCatching { clear() } } }
    }
}
