package com.tmplayer.data

import com.tmplayer.player.OpeningProgress
import com.tmplayer.player.StreamStats

/**
 * Why a video that is opening has not started, in one place for the phone, the television and the
 * desktop (FQ1), so the loader says the same thing on each and a table test can check it.
 *
 * Five verdicts, in the order they win:
 * - [State.Waiting]: nothing to say yet, or bytes are flowing well (the loader shows its speed).
 * - [State.Offline]: the device itself has no network; the offline notice says it, the loader stays.
 * - [State.Stalled]: no new byte for [OpeningStall.STALL_MS]; the sheet (see [OpeningStall.reason]
 *   for whether the line to Telegram is down or up) offers Try again, Download first and Back.
 * - [State.Quota]: bytes arrive past the slow point while Telegram is capping this account; said, and
 *   nothing is offered, since the cap slows a download and never stops it.
 * - [State.Slow]: bytes arrive past the slow point, but too slowly to measure; a quiet line.
 */
object StartVerdict {

    enum class State { Waiting, Slow, Stalled, Quota, Offline }

    /** What is known about the wait right now. Times are in milliseconds. */
    data class Facts(
        /** How long the opening has been going. */
        val waitedMs: Long,
        /** How long since the last new byte arrived (or since the wait began, when none has). */
        val quietMs: Long,
        /** The whole file is on this device: Telegram has nothing to do with the wait. */
        val local: Boolean,
        /** The device has no network at all. */
        val deviceOffline: Boolean,
        /** The rate bytes are arriving at, in bytes a second. */
        val bytesPerSec: Long,
        /** Telegram is capping this account's download speed. */
        val speedLimited: Boolean,
    )

    fun of(
        facts: Facts,
        slowAfterMs: Long = OpeningProgress.SPEED_NOTE_AFTER_MS,
        stallMs: Long = OpeningStall.STALL_MS,
    ): State = when {
        facts.local -> State.Waiting
        facts.deviceOffline -> State.Offline
        facts.quietMs >= stallMs -> State.Stalled
        facts.waitedMs <= slowAfterMs -> State.Waiting
        facts.speedLimited -> State.Quota
        facts.bytesPerSec < StreamStats.MIN_MEANINGFUL_SPEED -> State.Slow
        else -> State.Waiting
    }
}
