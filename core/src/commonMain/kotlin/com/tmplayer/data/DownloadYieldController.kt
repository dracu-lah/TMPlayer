package com.tmplayer.data

import com.tmplayer.platform.Logger
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Feeds [DownloadYield] with what is playing and what is downloading, and hands its answer to the
 * platform's runner. The rule is in [DownloadYield]; this is the loop around it, shared by the
 * Android service and the desktop runner so the two cannot drift apart.
 *
 * Nothing is stored. The rows it holds are [OfflineDownloads.Stage.Yielding] in memory only, so a
 * crash comes back through [OfflineDownloads.restore] with every download paused and a Resume on
 * the Downloads screen, as it always did, and nothing this class did can strand one.
 *
 * @param hold stop these downloads and mark them [OfflineDownloads.Stage.Yielding]. Idempotent.
 * @param release put these back on the queue (or in the wait they were in before). Idempotent.
 * @param isStreaming whether a played file still comes over the network. A failed answer counts as
 * no, so a TDLib that cannot be asked never leaves downloads waiting for nothing.
 */
class DownloadYieldController(
    private val scope: CoroutineScope,
    private val enabled: Flow<Boolean>,
    private val hold: (List<Int>) -> Unit,
    private val release: (List<Int>) -> Unit,
    private val isStreaming: suspend (Int) -> Boolean = {
        Td.localFileAvailability(it) != LocalFileAvailability.Complete
    },
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val tickMs: Long = TICK_MS,
) {
    private val exempt = ConcurrentHashMap.newKeySet<Int>()

    /**
     * The viewer started [fileId] by hand while it was held, so it carries on until the video in
     * its way is over. Without this, pressing Pause and then Resume on a waiting download would be
     * undone a second later.
     */
    fun exempt(fileId: Int) {
        exempt.add(fileId)
    }

    private data class Inputs(
        val enabled: Boolean,
        val plays: Map<Int, StreamingPlays.Entry>,
        val queue: List<Triple<Int, OfflineDownloads.Stage, Boolean>>,
    )

    fun start(): Job = scope.launch {
        combine(
            enabled.distinctUntilChanged(),
            StreamingPlays.state,
            // What the rule depends on, and not the byte counts that change every second.
            OfflineDownloads.active.map { rows ->
                rows.values.sortedBy { it.order }.map { Triple(it.fileId, it.stage, DownloadYield.nearlyDone(it.asDownload())) }
            }.distinctUntilChanged(),
        ) { on, plays, queue -> Inputs(on, plays, queue) }
            .collectLatest { inputs ->
                // Again and again while something is playing, because a pause turns into "long"
                // with the clock rather than with an event, and a stream can finish downloading.
                do {
                    val watching = evaluate(inputs.enabled)
                    if (watching) delay(tickMs)
                } while (watching)
            }
    }

    /** One pass. Returns whether another is needed later without any input changing. */
    private suspend fun evaluate(on: Boolean): Boolean {
        val entries = StreamingPlays.state.value
        val now = nowMs()
        val plays = entries.map { (id, entry) ->
            DownloadYield.Play(
                fileIds = setOf(id),
                streaming = on && try {
                    isStreaming(id)
                } catch (e: CancellationException) {
                    // A newer input cancelled this pass: end it, do not decide on a guess.
                    throw e
                } catch (_: Exception) {
                    false
                },
                pausedForMs = entry.pausedSinceMs?.let { (now - it).coerceAtLeast(0) } ?: 0L,
            )
        }
        val downloads = OfflineDownloads.active.value.values.map { row ->
            row.asDownload().copy(
                state = when (row.stage) {
                    OfflineDownloads.Stage.Running, OfflineDownloads.Stage.Queued -> DownloadYield.State.Active
                    OfflineDownloads.Stage.Yielding -> DownloadYield.State.Held
                    else -> DownloadYield.State.Other
                },
            )
        }
        val playing = DownloadYield.streamingNow(plays)
        if (!playing) exempt.clear()
        val plan = DownloadYield.decide(on, plays, downloads, exempt.toSet())
        if (plan.hold.isNotEmpty()) {
            Logger.i(TAG, "Holding ${plan.hold.size} download(s) while a video plays")
            runCatching { hold(plan.hold) }.onFailure { Logger.w(TAG, "Could not hold downloads", it) }
        }
        if (plan.release.isNotEmpty()) {
            Logger.i(TAG, "Putting back ${plan.release.size} download(s)")
            runCatching { release(plan.release) }.onFailure { Logger.w(TAG, "Could not put downloads back", it) }
        }
        // Only the clock needs watching: a play that has not yet been paused long, or a stream.
        return on && entries.isNotEmpty()
    }

    private fun OfflineDownloads.Progress.asDownload() = DownloadYield.Download(
        fileId = fileId,
        state = DownloadYield.State.Other,
        totalBytes = totalBytes,
        downloadedBytes = downloadedBytes,
    )

    companion object {
        private const val TAG = "DownloadYield"

        /** How often the clock and a stream's progress are looked at while a video is open. */
        const val TICK_MS = 5_000L
    }
}
