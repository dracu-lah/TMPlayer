package com.tmplayer.data

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** The loop around [DownloadYield]: the registry, the queue and a runner's hold and release. */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadYieldControllerTest {

    private val ids = listOf(7101, 7102, 7103)

    /** Large enough that nothing here is nearly done. */
    private val BIG = 10L * 1024 * 1024 * 1024

    private fun request(id: Int) = DownloadRequest(
        fileId = id, title = "Video $id", sizeBytes = BIG, chatId = 1, messageId = id.toLong(),
        chatTitle = "Chat", durationSec = 60, mimeType = "video/mp4", fileName = "v$id.mp4",
    )

    private fun row(id: Int, stage: OfflineDownloads.Stage, done: Long = 100) = OfflineDownloads.note(
        OfflineDownloads.Progress(request(id), done, BIG, stage, order = id.toLong()),
    )

    private fun stageOf(id: Int) = OfflineDownloads.active.value[id]?.stage

    @BeforeTest
    fun setUp() {
        StreamingPlays.clear()
    }

    @AfterTest
    fun cleanUp() {
        StreamingPlays.clear()
        ids.forEach(OfflineDownloads::forget)
    }

    /** A runner in miniature: hold marks Yielding, release puts back on the queue. */
    private class Recorder {
        val held = mutableListOf<Int>()
        val released = mutableListOf<Int>()
        fun hold(ids: List<Int>) {
            held += ids
            ids.forEach { OfflineDownloads.stage(it, OfflineDownloads.Stage.Yielding) }
        }
        fun release(ids: List<Int>) {
            released += ids
            ids.forEach { OfflineDownloads.stage(it, OfflineDownloads.Stage.Queued) }
        }
    }

    private fun TestScope.controller(
        recorder: Recorder,
        enabled: MutableStateFlow<Boolean> = MutableStateFlow(true),
        streaming: suspend (Int) -> Boolean = { true },
    ): DownloadYieldController =
        DownloadYieldController(
            scope = backgroundScope,
            enabled = enabled,
            hold = recorder::hold,
            release = recorder::release,
            isStreaming = streaming,
            nowMs = { currentTime },
            tickMs = 1_000L,
        ).also { it.start() }

    @Test
    fun `a stream holds the others and its end puts them back`() = runTest {
        row(7101, OfflineDownloads.Stage.Running)
        row(7102, OfflineDownloads.Stage.Queued)
        val recorder = Recorder()
        controller(recorder)
        runCurrent()
        assertTrue(recorder.held.isEmpty())

        StreamingPlays.begin(9001)
        runCurrent()
        assertEquals(listOf(7101, 7102), recorder.held)
        assertEquals(OfflineDownloads.Stage.Yielding, stageOf(7101))

        StreamingPlays.end(9001)
        runCurrent()
        assertEquals(listOf(7101, 7102), recorder.released)
        assertEquals(OfflineDownloads.Stage.Queued, stageOf(7101))
    }

    @Test
    fun `a long pause lets them go by the clock alone`() = runTest {
        row(7101, OfflineDownloads.Stage.Running)
        val recorder = Recorder()
        controller(recorder)
        StreamingPlays.begin(9001)
        runCurrent()
        StreamingPlays.setPlaying(listOf(9001), playing = false, nowMs = currentTime)
        runCurrent()
        assertTrue(recorder.released.isEmpty())

        advanceTimeBy(DownloadYield.PAUSED_LONG_MS - 2_000)
        assertTrue(recorder.released.isEmpty())
        advanceTimeBy(4_000)
        assertEquals(listOf(7101), recorder.released)

        // Playing again holds again.
        StreamingPlays.setPlaying(listOf(9001), playing = true, nowMs = currentTime)
        runCurrent()
        assertEquals(listOf(7101, 7101), recorder.held)
    }

    @Test
    fun `turning the setting off puts them back at once, and on holds them again`() = runTest {
        row(7101, OfflineDownloads.Stage.Running)
        val recorder = Recorder()
        val enabled = MutableStateFlow(true)
        controller(recorder, enabled)
        StreamingPlays.begin(9001)
        runCurrent()
        assertEquals(listOf(7101), recorder.held)
        enabled.value = false
        runCurrent()
        assertEquals(listOf(7101), recorder.released)
        enabled.value = true
        runCurrent()
        assertEquals(listOf(7101, 7101), recorder.held)
    }

    @Test
    fun `the file being played is left alone`() = runTest {
        row(7101, OfflineDownloads.Stage.Running)
        row(7102, OfflineDownloads.Stage.Running)
        val recorder = Recorder()
        controller(recorder)
        StreamingPlays.begin(7101)
        runCurrent()
        assertEquals(listOf(7102), recorder.held)
    }

    @Test
    fun `a stream that finished downloading is no longer a stream`() = runTest {
        row(7101, OfflineDownloads.Stage.Running)
        val recorder = Recorder()
        var streaming = true
        controller(recorder, streaming = { streaming })
        StreamingPlays.begin(9001)
        runCurrent()
        assertEquals(listOf(7101), recorder.held)
        streaming = false
        advanceTimeBy(2_000)
        assertEquals(listOf(7101), recorder.released)
    }

    @Test
    fun `a question TDLib cannot answer never leaves downloads waiting`() = runTest {
        row(7101, OfflineDownloads.Stage.Running)
        val recorder = Recorder()
        controller(recorder, streaming = { error("no client") })
        StreamingPlays.begin(9001)
        runCurrent()
        assertTrue(recorder.held.isEmpty())
    }

    @Test
    fun `a download started by hand while held carries on until the video is over`() = runTest {
        row(7101, OfflineDownloads.Stage.Running)
        val recorder = Recorder()
        val controller = controller(recorder)
        StreamingPlays.begin(9001)
        runCurrent()
        assertEquals(listOf(7101), recorder.held)

        // Pause, then Resume: the runner marks it exempt and starts it.
        controller.exempt(7101)
        OfflineDownloads.stage(7101, OfflineDownloads.Stage.Running)
        advanceTimeBy(2_000)
        assertEquals(listOf(7101), recorder.held)
        assertEquals(OfflineDownloads.Stage.Running, stageOf(7101))

        // And it is held again for the next video: the exemption ended with the last one.
        StreamingPlays.end(9001)
        runCurrent()
        StreamingPlays.begin(9002)
        runCurrent()
        assertEquals(listOf(7101, 7101), recorder.held)
    }

    @Test
    fun `rows the network or the viewer holds are left alone`() = runTest {
        row(7101, OfflineDownloads.Stage.Paused)
        row(7102, OfflineDownloads.Stage.NoWifi)
        row(7103, OfflineDownloads.Stage.Offline)
        val recorder = Recorder()
        controller(recorder)
        StreamingPlays.begin(9001)
        runCurrent()
        StreamingPlays.end(9001)
        runCurrent()
        assertTrue(recorder.held.isEmpty())
        assertTrue(recorder.released.isEmpty())
    }

    @Test
    fun `two players on one file are one entry until both let go`() {
        StreamingPlays.begin(9001)
        StreamingPlays.begin(9001)
        StreamingPlays.end(9001)
        assertEquals(1, StreamingPlays.state.value[9001]?.players)
        StreamingPlays.end(9001)
        assertTrue(StreamingPlays.state.value.isEmpty())
        // A late end, or a file never begun, is nothing.
        StreamingPlays.end(9001)
        StreamingPlays.begin(0)
        assertTrue(StreamingPlays.state.value.isEmpty())
    }

    @Test
    fun `pausing remembers its first moment and playing clears it`() {
        StreamingPlays.begin(9001)
        StreamingPlays.setPlaying(listOf(9001), false, nowMs = 100)
        StreamingPlays.setPlaying(listOf(9001), false, nowMs = 900)
        assertEquals(100L, StreamingPlays.state.value[9001]?.pausedSinceMs)
        StreamingPlays.setPlaying(listOf(9001, 5), true, nowMs = 1000)
        assertNull(StreamingPlays.state.value[9001]?.pausedSinceMs)
    }
}
