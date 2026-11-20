package com.tmplayer.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The long jobs of one profile (HX88): Save to folder, moving downloads, an external player's
 * stream, a cast. Each takes its owner ([Accounts.stamp]) when it starts and uses it for every
 * write and post; a switch first stops it and waits for it ([beforeSwitch], between two files, so
 * the library is left as it was or as it is meant to be), and a job whose owner is no longer open
 * ([stopOnSwitch]) stops at once, its foreground notification taken down.
 */
object ProfileJobs {

    /**
     * Runs [stop] at the next change of the open profile ([switches]), or at once when [owner] is
     * already not the open profile's turn (a service that started after the switch).
     */
    fun stopOnSwitch(
        scope: CoroutineScope,
        owner: AccountStamp = Accounts.stamp,
        current: () -> AccountStamp = { Accounts.stamp },
        switches: Flow<Any?> = Accounts.activeFolder.drop(1),
        stop: () -> Unit,
    ): Job = scope.launch {
        if (current() != owner) {
            runCatching(stop)
            return@launch
        }
        switches.first()
        runCatching(stop)
    }

    /**
     * A [BeforeSwitch] hook for a running job: asks it to stop ([cancel]) and waits until it has
     * ([join]), at most [waitMs], so nothing of the job runs once the next profile is open.
     */
    fun beforeSwitch(cancel: () -> Unit, join: suspend () -> Unit, waitMs: Long = JOIN_WAIT_MS): suspend () -> Unit = {
        cancel()
        withTimeoutOrNull(waitMs) { join() }
    }

    /** Whether a job made for [owner] may still write now. */
    fun mayWrite(
        owner: AccountStamp,
        current: AccountStamp = Accounts.stamp,
        switching: Boolean = Accounts.book?.list?.value?.switchingTo != null,
    ): Boolean = OwnedSessions.mayAct(owner, current, switching)

    /** Long enough for the file in hand to stop (a copy stops between buffers), never for ever. */
    const val JOIN_WAIT_MS = 60_000L
}
