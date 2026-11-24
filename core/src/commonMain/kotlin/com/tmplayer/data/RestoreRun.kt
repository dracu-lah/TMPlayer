package com.tmplayer.data

import kotlinx.coroutines.CancellationException
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
 */
object RestoreRun {
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
            Outcome.Failed
        } finally {
            state.value = false
        }
    }
}
