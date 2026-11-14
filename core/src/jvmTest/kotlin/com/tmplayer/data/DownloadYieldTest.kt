package com.tmplayer.data

import com.tmplayer.data.DownloadYield.Download
import com.tmplayer.data.DownloadYield.Play
import com.tmplayer.data.DownloadYield.State
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Who waits for a playing video, and who is let go again. */
class DownloadYieldTest {

    private val gb = 1024L * 1024 * 1024
    private val mb = 1024L * 1024

    private fun active(id: Int, done: Long = 0, total: Long = gb) = Download(id, State.Active, total, done)
    private fun held(id: Int, done: Long = 0, total: Long = gb) = Download(id, State.Held, total, done)
    private fun other(id: Int, done: Long = 0, total: Long = gb) = Download(id, State.Other, total, done)
    private fun stream(id: Int, pausedForMs: Long = 0) = Play(setOf(id), streaming = true, pausedForMs = pausedForMs)

    private fun decide(
        plays: List<Play>,
        downloads: List<Download>,
        enabled: Boolean = true,
        exempt: Set<Int> = emptySet(),
    ) = DownloadYield.decide(enabled, plays, downloads, exempt)

    @Test
    fun `nothing playing holds nothing`() {
        val plan = decide(emptyList(), listOf(active(1), active(2)))
        assertTrue(plan.isEmpty)
    }

    @Test
    fun `a stream holds every other download that is running or queued`() {
        val plan = decide(listOf(stream(9)), listOf(active(1, done = gb / 2), active(2), active(3)))
        assertEquals(listOf(1, 2, 3), plan.hold)
        assertTrue(plan.release.isEmpty())
    }

    @Test
    fun `the file being played is never held`() {
        val plan = decide(listOf(stream(1)), listOf(active(1), active(2)))
        assertEquals(listOf(2), plan.hold)
    }

    @Test
    fun `a video cut into pieces keeps every piece going`() {
        val plan = decide(
            listOf(Play(setOf(1, 2, 3), streaming = true)),
            listOf(active(1), active(2), active(3), active(4)),
        )
        assertEquals(listOf(4), plan.hold)
    }

    @Test
    fun `a play from the disk asks nothing of the line`() {
        val plan = decide(listOf(Play(setOf(9), streaming = false)), listOf(active(1)))
        assertTrue(plan.isEmpty)
    }

    @Test
    fun `a download with under five percent left finishes`() {
        val plan = decide(listOf(stream(9)), listOf(active(1, done = (gb * 0.97).toLong()), active(2, done = gb / 2)))
        assertEquals(listOf(2), plan.hold)
    }

    @Test
    fun `a download with a few megabytes left finishes whatever its size`() {
        val total = 100 * gb
        val plan = decide(
            listOf(stream(9)),
            listOf(active(1, done = total - 6 * mb, total = total), active(2, done = total - 10 * gb, total = total)),
        )
        assertEquals(listOf(2), plan.hold)
    }

    @Test
    fun `an unknown size is not nearly done`() {
        val plan = decide(listOf(stream(9)), listOf(active(1, done = 5 * mb, total = 0)))
        assertEquals(listOf(1), plan.hold)
    }

    @Test
    fun `a pause shorter than the limit still holds, and the limit lets them go`() {
        val downloads = listOf(held(1), held(2))
        assertTrue(decide(listOf(stream(9, DownloadYield.PAUSED_LONG_MS - 1)), downloads).release.isEmpty())
        assertEquals(listOf(1, 2), decide(listOf(stream(9, DownloadYield.PAUSED_LONG_MS)), downloads).release)
    }

    @Test
    fun `playback ending puts every held download back, in the order given`() {
        val plan = decide(emptyList(), listOf(held(3), held(1), active(2)))
        assertEquals(listOf(3, 1), plan.release)
        assertTrue(plan.hold.isEmpty())
    }

    @Test
    fun `turning the setting off puts them back even while a stream plays`() {
        val plan = decide(listOf(stream(9)), listOf(held(1), active(2)), enabled = false)
        assertEquals(listOf(1), plan.release)
        assertTrue(plan.hold.isEmpty())
    }

    @Test
    fun `two players, the phone paused long and a cast still playing, keep the hold`() {
        val phone = stream(8, pausedForMs = DownloadYield.PAUSED_LONG_MS * 3)
        val cast = stream(9)
        val plan = decide(listOf(phone, cast), listOf(held(1), active(2)))
        assertEquals(listOf(2), plan.hold)
        assertTrue(plan.release.isEmpty())
    }

    @Test
    fun `two players both paused long let everything go`() {
        val long = DownloadYield.PAUSED_LONG_MS
        val plan = decide(listOf(stream(8, long), stream(9, long * 2)), listOf(held(1), held(2)))
        assertEquals(listOf(1, 2), plan.release)
    }

    @Test
    fun `the file of a paused player is still spared while another one plays`() {
        val plan = decide(listOf(stream(8, DownloadYield.PAUSED_LONG_MS * 2), stream(9)), listOf(active(8), active(1)))
        assertEquals(listOf(1), plan.hold)
    }

    @Test
    fun `a held download that became the played file is let go`() {
        val plan = decide(listOf(stream(1)), listOf(held(1), held(2)))
        assertEquals(listOf(1), plan.release)
        assertTrue(plan.hold.isEmpty())
    }

    @Test
    fun `a download the viewer started by hand carries on beside the stream`() {
        val plan = decide(listOf(stream(9)), listOf(active(1), active(2), held(3)), exempt = setOf(1, 3))
        assertEquals(listOf(2), plan.hold)
        assertEquals(listOf(3), plan.release)
    }

    @Test
    fun `rows waiting for Wi-Fi, a signal, the viewer or a failure are left exactly as they are`() {
        // Wi-Fi only, offline, a pressed Pause, a failure for want of room, a move: all arrive as
        // Other. Neither a stream nor its end may start or stop them.
        val rows = listOf(other(1), other(2), other(3), other(4))
        assertTrue(decide(listOf(stream(9)), rows).isEmpty)
        assertTrue(decide(emptyList(), rows).isEmpty)
    }

    @Test
    fun `a held row that the network took over is not this rule's to put back`() {
        // The service moves a held row to Offline or NoWifi when the signal goes: it is Other then,
        // and the end of the stream must not start it on mobile data with Wi-Fi only on.
        val plan = decide(emptyList(), listOf(other(1), held(2)))
        assertEquals(listOf(2), plan.release)
    }

    @Test
    fun `low storage does not change who waits`() {
        // Low storage mode bounds what the stream keeps; it takes no part in the rule, so the same
        // inputs give the same answer. A download a full drive refused is Other (failed) and stays so.
        val plan = decide(listOf(stream(9)), listOf(active(1), other(2)))
        assertEquals(listOf(1), plan.hold)
    }

    @Test
    fun `a crash leaves nothing to put back, because nothing was stored`() {
        // Held rows exist only as an in-memory stage. After a restart OfflineDownloads.restore
        // brings every row back as Paused ("Other" here), and a rule that sees none of them held
        // has nothing to release and nothing to strand.
        val afterRestart = listOf(other(1), other(2))
        assertTrue(decide(emptyList(), afterRestart).isEmpty)
        assertTrue(decide(listOf(stream(9)), afterRestart).isEmpty)
    }

    @Test
    fun `nearly done is the looser of the byte and share rules`() {
        assertTrue(DownloadYield.nearlyDone(active(1, done = gb - 8 * mb)))
        assertFalse(DownloadYield.nearlyDone(active(1, done = gb - 9 * mb - gb / 10)))
        assertTrue(DownloadYield.nearlyDone(active(1, done = gb - gb / 25)))
        assertFalse(DownloadYield.nearlyDone(active(1, done = gb - gb / 10)))
    }

    @Test
    fun `a negative remaining reads as done`() {
        assertTrue(DownloadYield.nearlyDone(active(1, done = 2 * gb, total = gb)))
    }
}
