package com.tmplayer.data

import com.tmplayer.i18n.L

/**
 * What a piece of background work on a screen is doing, for the one status line every screen
 * shares (WP-91): a refresh of content already shown, a list that is still growing, a restore.
 * A first full page load with nothing to show keeps the page's own skeleton and error
 * (`StateScaffold`); this is everything else.
 *
 * Derived from a view model's flows each time and never saved: after process death the next load
 * starts [Idle]. [WorkStatusWords] turns it into one sentence, [merge] makes one line out of two
 * sources (Home's rows and the chat list).
 */
sealed interface WorkStatus {

    /** Nothing runs: nothing is drawn and no space is taken. */
    data object Idle : WorkStatus

    /** A first load with no content, under a skeleton. [what] is the skeleton's label. */
    data class Loading(val what: String = "") : WorkStatus

    /**
     * A refresh, a revalidate or a list that grows while content is on screen. [what] is the
     * caller's own words ("Loading your chats: 312 so far"), blank for "Updating…". [total] is
     * set only when it is really known, never guessed, and only then is a bar drawn.
     */
    data class Syncing(val what: String = "", val done: Int? = null, val total: Int? = null) : WorkStatus

    /** The refresh ended well, at [at]. Shown for 2 s, and only after a refresh the viewer asked for. */
    data class Updated(val at: Long) : WorkStatus

    /** Some sources answered and some did not; [what] is the caller's words for it. */
    data class Partial(val okCount: Int, val failedCount: Int, val what: String = "") : WorkStatus

    /**
     * The work failed. [keptAt] is when the content still on screen was last fresh, null when
     * there is none. [message] is the failure in the viewer's words ([Failures.humanise]).
     */
    data class Failed(val message: String, val keptAt: Long? = null) : WorkStatus

    /** Settled offline, with what is on screen fresh as of [keptAt]. The offline banner says it. */
    data class Offline(val keptAt: Long? = null) : WorkStatus

    /** Telegram asked for a wait (a flood wait) that ends at [untilMs], a wall-clock stamp. */
    data class RateLimited(val untilMs: Long) : WorkStatus

    companion object {

        /** A flood wait this long or shorter is waited out quietly: a line would only flash. */
        const val QUIET_WAIT_SECONDS = 10

        /** No wait is believed past a week: a larger number is a garbled answer, not Telegram's. */
        private const val LONGEST_WAIT_SECONDS = 7L * 86_400

        /**
         * Telegram's wait in seconds (from `Td.floodWaitRemainingSeconds()`) as a status, or null
         * when it is short enough to wait out quietly, zero, negative or absurd.
         */
        fun rateLimited(seconds: Int, now: Long): RateLimited? {
            if (seconds <= QUIET_WAIT_SECONDS || seconds > LONGEST_WAIT_SECONDS) return null
            if (now > Long.MAX_VALUE - seconds * 1000L) return null
            return RateLimited(now + seconds * 1000L)
        }

        /**
         * What a failed call's [raw] error text (from TDLib or the network) comes to: a wait when
         * it names one over [QUIET_WAIT_SECONDS]; [Offline] when it is a failure of the line
         * ([Failures.isNetwork]) and the device is settled offline ([network], so the offline
         * banner is up to say it); otherwise a failure in the viewer's words (a timeout while
         * online says to check the connection, with Try again), with the content kept from
         * [keptAt]. Garbage text is a plain failure, never a wait, and raw text is never shown.
         */
        fun ofFailure(
            raw: String?,
            now: Long,
            keptAt: Long? = null,
            network: NetworkStatus = NetworkStatus.Unknown,
        ): WorkStatus {
            val seconds = Failures.floodWaitSeconds(raw)
            if (seconds != null) rateLimited(seconds, now)?.let { return it }
            if (network == NetworkStatus.Offline && Failures.isNetwork(raw)) return Offline(keptAt)
            return Failed(Failures.humanise(raw), keptAt)
        }

        /**
         * [ofFailure] for a thrown failure: an I/O failure (no route, a reset socket) is a failure
         * of the line whatever its text, so it is [Offline] when the device is settled offline.
         */
        fun ofFailure(
            error: Throwable?,
            now: Long,
            keptAt: Long? = null,
            network: NetworkStatus = NetworkStatus.Unknown,
        ): WorkStatus {
            if (error is com.tmplayer.platform.IOException && network == NetworkStatus.Offline) return Offline(keptAt)
            // The offline sentence, never the exception's own text.
            if (error is com.tmplayer.platform.IOException) return Failed(Failures.OFFLINE, keptAt)
            return ofFailure(error?.message, now, keptAt, network)
        }

        /**
         * One status for a page fed by two sources. The one the viewer must act on or wait for
         * wins: Failed, then RateLimited, Offline, Partial, Loading, Syncing, Updated and Idle.
         * Two of a kind are joined: their counts added, the oldest kept time, the longest wait.
         */
        fun merge(a: WorkStatus, b: WorkStatus): WorkStatus {
            val ra = rank(a)
            val rb = rank(b)
            if (ra != rb) return if (ra > rb) a else b
            return when (a) {
                is Failed -> {
                    val other = b as Failed
                    // A source with nothing kept wins the words: the page cannot claim to show
                    // what was there when part of it shows nothing.
                    val words = if (a.keptAt == null || other.keptAt != null) a.message else other.message
                    Failed(words, keptAt = oldestOfBoth(a.keptAt, other.keptAt))
                }
                is RateLimited -> RateLimited(maxOf(a.untilMs, (b as RateLimited).untilMs))
                is Offline -> Offline(oldestOfBoth(a.keptAt, (b as Offline).keptAt))
                is Partial -> {
                    val other = b as Partial
                    Partial(
                        okCount = sum(a.okCount, other.okCount),
                        failedCount = sum(a.failedCount, other.failedCount),
                        what = a.what.ifBlank { other.what },
                    )
                }
                is Loading -> a.copy(what = a.what.ifBlank { (b as Loading).what })
                is Syncing -> {
                    val other = b as Syncing
                    val total = if (a.total != null && other.total != null) sum(a.total, other.total) else null
                    val done = if (a.done != null || other.done != null) sum(a.done ?: 0, other.done ?: 0) else null
                    Syncing(what = a.what.ifBlank { other.what }, done = done, total = total)
                }
                is Updated -> Updated(maxOf(a.at, (b as Updated).at))
                Idle -> Idle
            }
        }

        /** [merge] over any number of sources; none is [Idle]. */
        fun merge(all: Iterable<WorkStatus>): WorkStatus = all.fold<WorkStatus, WorkStatus>(Idle, ::merge)

        private fun rank(status: WorkStatus): Int = when (status) {
            Idle -> 0
            is Updated -> 1
            is Syncing -> 2
            is Loading -> 3
            is Partial -> 4
            is Offline -> 5
            is RateLimited -> 6
            is Failed -> 7
        }

        /** The older kept time when both sources kept content; null when either kept none. */
        private fun oldestOfBoth(a: Long?, b: Long?): Long? = if (a == null || b == null) null else minOf(a, b)

        private fun sum(a: Int, b: Int): Int = (a.toLong() + b.toLong()).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    }
}

/** Seconds left of a [WorkStatus.RateLimited] wait at [now], rounded up and never below zero. */
fun WorkStatus.RateLimited.secondsLeft(now: Long): Int {
    if (untilMs <= now) return 0
    val left = untilMs - now
    // A difference past Long's range wraps below zero: that is a wait longer than anything.
    if (left < 0) return Int.MAX_VALUE
    val seconds = left / 1000 + if (left % 1000 > 0) 1 else 0
    return seconds.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

/** The status line's words for a [WorkStatus], shared by every platform (as [SyncWords] is for Settings). */
object WorkStatusWords {

    /** Past this a wait is said in minutes or hours ("about 5 minutes"), not ticked by the second. */
    private const val SAID_IN_SECONDS_UNTIL = 120

    /**
     * One sentence for [status] at [now]; empty for [WorkStatus.Idle]. A kept time in the future
     * (a clock that moved back) is read as now; a kept time of zero or less has no time to name.
     */
    fun line(status: WorkStatus, now: Long): String {
        val f = L.messages.formatter
        return when (status) {
            WorkStatus.Idle -> ""
            is WorkStatus.Loading -> status.what.ifBlank { L.commonLoading }
            is WorkStatus.Syncing -> {
                val what = status.what.ifBlank { L.stateUpdating }
                val total = status.total
                val done = status.done
                if (total != null && total > 0 && done != null && done >= 0) {
                    what + " " + L.commonOfTotal(done = f.number(done.coerceAtMost(total)), total = f.number(total))
                } else {
                    what
                }
            }
            is WorkStatus.Updated -> L.stateUpdated
            is WorkStatus.Partial -> status.what.ifBlank { L.chatsSomeNotLoaded }
            is WorkStatus.Failed -> {
                val kept = status.keptAt
                when {
                    kept != null && kept > 0 -> L.stateFailedKept(time = f.dateTime(minOf(kept, now)))
                    else -> status.message.ifBlank { Failures.DEFAULT }
                }
            }
            is WorkStatus.Offline -> L.stateOffline
            is WorkStatus.RateLimited -> rateLimited(status.secondsLeft(now))
        }
    }

    /**
     * The wait's sentence: by the second while it is short, in the largest unit past two minutes.
     * A wait already over reads as one second left, since the line goes when the status does.
     */
    fun rateLimited(seconds: Int): String {
        val left = seconds.coerceAtLeast(1)
        return if (left < SAID_IN_SECONDS_UNTIL) {
            L.stateRateLimited(seconds = left)
        } else {
            L.errorsFloodWait(wait = Failures.waitPhrase(left))
        }
    }

    /**
     * What is spoken once when the status starts, which for a wait is the sentence at its start
     * and is not repeated as the seconds tick down; empty when nothing is said.
     */
    fun spoken(status: WorkStatus, now: Long): String = when (status) {
        WorkStatus.Idle, is WorkStatus.Offline -> ""
        else -> line(status, now)
    }
}
