package com.tmplayer.data

/**
 * Whether background downloads step aside for a video that is playing from the network.
 *
 * A download and a stream share one line and one TDLib queue, so a stream that stutters while a
 * file is coming down in the background looks like a slow video when it is a busy connection.
 * While a video plays from the network the other downloads wait, and they carry on when the
 * playback ends or has been paused for long enough that nobody is watching ([PAUSED_LONG_MS]).
 *
 * Pure, so the rule is a table of cases rather than something reasoned about on a TV stick.
 * What this class never sees is why a download is held for another reason: no signal, Wi-Fi only
 * on mobile data, a pause the viewer pressed or a failure. Those rows arrive as [State.Other],
 * are left exactly as they are and are never started by this rule, so the three reasons for
 * waiting cannot undo each other. Low storage mode is the same: it bounds what the stream keeps on
 * disk and has no say in who gets the line.
 */
object DownloadYield {

    /** A paused video is not being watched after this long, and its stream no longer needs the line. */
    const val PAUSED_LONG_MS = 2 * 60 * 1000L

    /** Under this share of a download left, it is allowed to finish. */
    const val NEARLY_DONE_FRACTION = 0.05

    /** Or under this many bytes left, which is a few seconds on any line worth streaming over. */
    const val NEARLY_DONE_BYTES = 8L * 1024 * 1024

    enum class State {
        /** Being fetched, or next in line to be: the rows this rule may hold. */
        Active,

        /** Held by this rule: the rows it may put back. */
        Held,

        /** Anything else (paused, offline, Wi-Fi only, failed, moving): not this rule's to touch. */
        Other,
    }

    /** One entry of the queue, as far as this rule needs to see it. */
    data class Download(
        val fileId: Int,
        val state: State,
        val totalBytes: Long,
        val downloadedBytes: Long,
    ) {
        /** What is left, or null when the size is not known. */
        val remainingBytes: Long? get() = if (totalBytes > 0) (totalBytes - downloadedBytes).coerceAtLeast(0) else null
    }

    /**
     * One video being watched.
     *
     * @param fileIds the files it reads, which is several for a video cut into byte pieces.
     * @param streaming whether it is read from the network. A file already on the disk asks
     * nothing of the line, so it is a play that does not yield anything.
     * @param pausedForMs how long it has been paused, or 0 while it plays.
     */
    data class Play(val fileIds: Set<Int>, val streaming: Boolean, val pausedForMs: Long = 0)

    /** What to do now: ids to hold and ids to put back, each in the order given. */
    data class Plan(val hold: List<Int>, val release: List<Int>) {
        val isEmpty: Boolean get() = hold.isEmpty() && release.isEmpty()
    }

    /** Whether any of [plays] is a stream somebody is, or very recently was, watching. */
    fun streamingNow(plays: List<Play>): Boolean =
        plays.any { it.streaming && it.pausedForMs < PAUSED_LONG_MS }

    /** Whether a download so near its end that stopping it would cost more than letting it finish. */
    fun nearlyDone(download: Download): Boolean {
        val left = download.remainingBytes ?: return false
        if (left <= NEARLY_DONE_BYTES) return true
        return download.totalBytes > 0 && left.toDouble() / download.totalBytes < NEARLY_DONE_FRACTION
    }

    /**
     * @param enabled the setting "Pause downloads while playing". Off, everything held goes back.
     * @param exempt downloads the viewer started by hand while this rule held them: they carry
     * on until the playback that was in the way is over.
     */
    fun decide(
        enabled: Boolean,
        plays: List<Play>,
        downloads: List<Download>,
        exempt: Set<Int> = emptySet(),
    ): Plan {
        val held = downloads.filter { it.state == State.Held }
        if (!enabled || !streamingNow(plays)) {
            return Plan(hold = emptyList(), release = held.map { it.fileId })
        }
        // Every file any player has open, paused or not: the one being watched is never what gets
        // stopped, whatever its own download looks like.
        val played = plays.flatMapTo(HashSet()) { it.fileIds }
        fun spared(download: Download) =
            download.fileId in played || download.fileId in exempt || nearlyDone(download)
        return Plan(
            hold = downloads.filter { it.state == State.Active && !spared(it) }.map { it.fileId },
            // A held row that turned out to be the video now playing, or that the viewer pressed
            // Resume on, is let go: the rule gives way to a decision somebody made.
            release = held.filter { it.fileId in played || it.fileId in exempt }.map { it.fileId },
        )
    }
}
