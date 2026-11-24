package com.tmplayer.data

import com.tmplayer.platform.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Restoring a backup, one at a time, on every platform (WP-97): [run] starts the work only when no
 * restore is under way, so a second press of the confirm (a double click, a held OK) is ignored
 * rather than merging the same backup twice, and [running] is what the Restore row's spinner and
 * its words read. Held here, not in a screen, so a rotation or a close of Settings does not start a
 * second run beside the first.
 *
 * A cut part way (the process killed) leaves what the stores had already written: each store takes
 * the backup in one edit and nothing is ever removed ([BackupService.restore]), so running the same
 * backup again finishes the job and changes nothing twice.
 *
 * A restore belongs to the profile open when its confirm was pressed ([start], SEC2-04): a switch
 * part way cancels it, and the stores drop whatever write of it is still under way, so the rest of
 * the backup never lands in the next profile (from a PIN profile into one without a PIN, say).
 */
object RestoreRun {
    private const val TAG = "RestoreRun"

    private val state = MutableStateFlow(false)

    /** True while a restore is under way. */
    val running: StateFlow<Boolean> = state.asStateFlow()

    /** What [run] did. */
    enum class Outcome { Done, Failed, AlreadyRunning }

    /**
     * Runs [work] unless a restore is under way: [Outcome.AlreadyRunning] then, [Outcome.Failed]
     * when it threw (cancellation is passed on, not counted as a failure), else [Outcome.Done].
     */
    suspend fun run(work: suspend () -> Unit): Outcome {
        if (!state.compareAndSet(false, true)) return Outcome.AlreadyRunning
        return try {
            work()
            Outcome.Done
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The kind and its words, so Send logs after "could not be restored" says why (SEC2-13).
            Logger.w(TAG, "restore failed: ${e::class.simpleName}: ${e.message.orEmpty().take(200)}")
            Outcome.Failed
        } finally {
            state.value = false
        }
    }

    /**
     * Starts [work] for the profile open now: in [Accounts.profileScope], which a switch cancels and
     * which carries the profile's [AccountOwner], so a store refuses a write of it once another
     * profile is open. [tell] hears the outcome only while that profile is still open: no "Restored"
     * shown in another one. A restore cut by a switch is finished by running it again there.
     */
    fun start(work: suspend () -> Unit, tell: (Outcome) -> Unit): Job =
        start(Accounts.profileScope, { Accounts.stamp }, work, tell)

    internal fun start(scope: CoroutineScope, stamp: () -> AccountStamp, work: suspend () -> Unit, tell: (Outcome) -> Unit): Job {
        val owner = stamp()
        return scope.launch {
            val outcome = run(work)
            if (stamp() == owner) runCatching { tell(outcome) }
        }
    }
}
