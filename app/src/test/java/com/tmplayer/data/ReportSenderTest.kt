package com.tmplayer.data

import com.tmplayer.data.ReportSender.State
import com.tmplayer.data.ReportSender.Why
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Send to developer's steps (L5): asks first, one send at a time, a cancel leaves nothing, a failure keeps the file. */
class ReportSenderTest {

    private val facts = Feedback.Facts("1.30.0", Feedback.Platform.Phone, "Phone", "en")
    private val report = LogBundle.build(facts, LogBundle.Parts(appLog = listOf("W/Td: x")))

    private class Calls {
        var built = 0
        var uploads = 0
        var kept = mutableListOf<String>()
    }

    private fun sender(
        calls: Calls,
        outcome: suspend () -> LogUpload.Outcome = { LogUpload.Outcome.Sent("TMP-7K3Q") },
        place: String? = "Download/tmplayer-logs.txt",
        build: suspend () -> LogBundle.Result = { calls.built++; report },
    ) = ReportSender(
        build = build,
        upload = { calls.uploads++; outcome() },
        keep = { calls.kept += it; place },
    )

    private suspend fun ReportSender.await(done: (State) -> Boolean): State = withTimeout(5_000) {
        var s = state.value
        while (!done(s)) {
            kotlinx.coroutines.yield()
            s = state.value
        }
        s
    }

    @Test
    fun `nothing is built or sent until it is opened, and nothing is sent until Send is pressed`() = runBlocking {
        val calls = Calls()
        val s = sender(calls)
        assertEquals(State.Idle, s.state.value)
        s.send(this)
        assertEquals(0, calls.uploads)
        s.open(this)
        val confirm = s.await { it is State.Confirm } as State.Confirm
        assertEquals(report, confirm.report)
        assertEquals(0, calls.uploads)
    }

    @Test
    fun `Send uploads once and shows the code, a second press does nothing`() = runBlocking {
        val calls = Calls()
        val gate = CompletableDeferred<Unit>()
        val s = sender(calls, outcome = { gate.await(); LogUpload.Outcome.Sent("TMP-7K3Q") })
        s.open(this)
        s.await { it is State.Confirm }
        s.send(this)
        s.send(this)
        s.send(this)
        gate.complete(Unit)
        assertEquals(State.Sent("TMP-7K3Q"), s.await { it is State.Sent })
        assertEquals(1, calls.uploads)
        s.send(this)
        assertEquals(1, calls.uploads)
    }

    @Test
    fun `opening twice builds once`() = runBlocking {
        val calls = Calls()
        val s = sender(calls)
        s.open(this)
        s.open(this)
        s.await { it is State.Confirm }
        s.open(this)
        assertEquals(1, calls.built)
    }

    @Test
    fun `no connection keeps the file and says where`() = runBlocking {
        val calls = Calls()
        val s = sender(calls, outcome = { LogUpload.Outcome.NoConnection })
        s.open(this)
        s.await { it is State.Confirm }
        s.send(this)
        val kept = s.await { it is State.Kept } as State.Kept
        assertEquals(Why.NO_CONNECTION, kept.why)
        assertEquals("Download/tmplayer-logs.txt", kept.place)
        assertEquals(listOf(report.text), calls.kept)
    }

    @Test
    fun `a limit keeps the file and carries the wait, an error is a refusal`() = runBlocking {
        val limited = sender(Calls(), outcome = { LogUpload.Outcome.SlowDown(45) })
        limited.open(this)
        limited.await { it is State.Confirm }
        limited.send(this)
        assertEquals(State.Kept(Why.SLOW_DOWN, "Download/tmplayer-logs.txt", 45), limited.await { it is State.Kept })

        val refused = sender(Calls(), outcome = { LogUpload.Outcome.Failed(500) })
        refused.open(this)
        refused.await { it is State.Confirm }
        refused.send(this)
        assertEquals(Why.REFUSED, (refused.await { it is State.Kept } as State.Kept).why)
    }

    @Test
    fun `when even saving fails the state says there is no place`() = runBlocking {
        val s = sender(Calls(), outcome = { LogUpload.Outcome.NoConnection }, place = null)
        s.open(this)
        s.await { it is State.Confirm }
        s.send(this)
        assertNull((s.await { it is State.Kept } as State.Kept).place)
    }

    @Test
    fun `a keep that throws is no place, not a crash`() = runBlocking {
        val s = ReportSender(build = { report }, upload = { LogUpload.Outcome.NoConnection }, keep = { throw java.io.IOException("No space left on device") })
        s.open(this)
        s.await { it is State.Confirm }
        s.send(this)
        assertNull((s.await { it is State.Kept } as State.Kept).place)
    }

    @Test
    fun `an upload that throws keeps the file`() = runBlocking {
        val calls = Calls()
        val s = sender(calls, outcome = { throw IllegalStateException("boom") })
        s.open(this)
        s.await { it is State.Confirm }
        s.send(this)
        assertEquals(Why.NO_CONNECTION, (s.await { it is State.Kept } as State.Kept).why)
        assertEquals(1, calls.kept.size)
    }

    @Test
    fun `cancel during the send goes back to the report and keeps nothing`() = runBlocking {
        val calls = Calls()
        val gate = CompletableDeferred<Unit>()
        val s = sender(calls, outcome = { gate.await(); LogUpload.Outcome.Sent("TMP-AAAA") })
        s.open(this)
        s.await { it is State.Confirm }
        s.send(this)
        s.await { it is State.Sending }
        s.cancel()
        assertTrue(s.state.value is State.Confirm)
        assertEquals(0, calls.kept.size)
        // And it can be sent again from there.
        gate.complete(Unit)
        s.send(this)
        assertEquals(State.Sent("TMP-AAAA"), s.await { it is State.Sent })
    }

    @Test
    fun `close ends any step and cancels a send`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val s = sender(Calls(), outcome = { gate.await(); LogUpload.Outcome.Sent("TMP-AAAA") })
        s.open(this)
        s.await { it is State.Confirm }
        s.send(this)
        s.close()
        assertEquals(State.Idle, s.state.value)
        gate.complete(Unit)
        kotlinx.coroutines.yield()
        assertEquals(State.Idle, s.state.value)
        s.open(this)
        s.await { it is State.Confirm }
        s.close()
        assertEquals(State.Idle, s.state.value)
    }

    @Test
    fun `a report that cannot be built is a failed step with a way out`() = runBlocking {
        val s = sender(Calls(), build = { throw java.io.IOException("logcat") })
        s.open(this)
        assertTrue(s.await { it is State.Failed } is State.Failed)
        s.close()
        assertEquals(State.Idle, s.state.value)
    }
}
