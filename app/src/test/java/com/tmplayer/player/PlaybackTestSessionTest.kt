package com.tmplayer.player

import com.tmplayer.player.PlaybackTestSession.Candidate
import com.tmplayer.player.PlaybackTestSession.State
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Playback test's steps (L6): refused while a video plays, space shown before a fetch, cancel leaves nothing, double start does nothing. */
class PlaybackTestSessionTest {

    private val mb = 1024L * 1024L
    private val downloaded = Candidate("1:2", "A downloaded video", 800 * mb, downloaded = true)
    private val remote = Candidate("3:4", "A watched video", 800 * mb, downloaded = false)

    private class FakeEnv(var video: Candidate?) : PlaybackTestSession.Env {
        var playing = false
        var free = 5_000L * 1024 * 1024
        var prefix = 0L
        var fetchOk = true
        var fetchGate: CompletableDeferred<Unit>? = null
        var playGate: CompletableDeferred<Unit>? = null
        var fetches = 0
        var plays = 0
        var discarded = 0
        var kept: List<String>? = null
        var keepOk = true
        var result = PlaybackTest.Result(PlaybackTest.Verdict.CLEAN, 2, 0, 30_000, "c2.qti.avc.decoder", true, "video/avc")

        override fun anotherVideoPlaying() = playing
        override suspend fun pick() = video
        override suspend fun freeBytes() = free
        override suspend fun onDiskPrefix(c: Candidate) = prefix
        override suspend fun fetch(c: Candidate, progress: (Long) -> Unit): Boolean {
            fetches++
            progress(10L * 1024 * 1024)
            fetchGate?.await()
            return fetchOk
        }
        override suspend fun discardFetched(c: Candidate) { discarded++ }
        override suspend fun play(c: Candidate, progress: (Long) -> Unit): PlaybackTest.Result {
            plays++
            progress(5_000)
            playGate?.await()
            return result
        }
        override suspend fun keepForReport(lines: List<String>): Boolean {
            kept = lines
            return keepOk
        }
    }

    private suspend fun PlaybackTestSession.await(done: (State) -> Boolean): State = withTimeout(5_000) {
        while (!done(state.value)) yield()
        state.value
    }

    @Test
    fun `refused while another video plays, and nothing is looked for`() = runBlocking {
        val env = FakeEnv(downloaded).apply { playing = true }
        val s = PlaybackTestSession(env)
        s.begin(this)
        s.await { it is State.Refused }
        s.start(this)
        assertEquals(0, env.plays)
        s.close()
        assertEquals(State.Idle, s.state.value)
    }

    @Test
    fun `a video that starts between the screen and the press still refuses`() = runBlocking {
        val env = FakeEnv(downloaded)
        val s = PlaybackTestSession(env)
        s.begin(this)
        s.await { it is State.Ready }
        env.playing = true
        s.start(this)
        s.await { it is State.Refused }
        assertEquals(0, env.plays)
    }

    @Test
    fun `no download and nothing watched says so`() = runBlocking {
        val s = PlaybackTestSession(FakeEnv(null))
        s.begin(this)
        s.await { it is State.NoVideo }
        Unit
    }

    @Test
    fun `a download plays as it is with no fetch, and the result is clean`() = runBlocking {
        val env = FakeEnv(downloaded)
        val s = PlaybackTestSession(env)
        s.begin(this)
        assertEquals(State.Ready(downloaded, null), s.await { it is State.Ready })
        s.start(this)
        val done = s.await { it is State.Done } as State.Done
        assertEquals(PlaybackTest.Verdict.CLEAN, done.result.verdict)
        assertFalse(done.fetched)
        assertEquals(0, env.fetches)
        assertEquals(0, env.discarded)
    }

    @Test
    fun `a video not on the disk is offered with the space it needs, and nothing is fetched until Start`() = runBlocking {
        val env = FakeEnv(remote)
        val s = PlaybackTestSession(env)
        s.begin(this)
        val ready = s.await { it is State.Ready } as State.Ready
        assertEquals(PlaybackTest.Fetch.Needed(30 * mb, env.free), ready.fetch)
        assertEquals(0, env.fetches)
        s.start(this)
        val done = s.await { it is State.Done } as State.Done
        assertTrue(done.fetched)
        assertEquals(1, env.fetches)
        // What the test brought is deleted again once it is over.
        assertEquals(1, env.discarded)
    }

    @Test
    fun `not enough space shows both numbers and fetches nothing`() = runBlocking {
        val env = FakeEnv(remote).apply { free = 100 * mb }
        val s = PlaybackTestSession(env)
        s.begin(this)
        val no = s.await { it is State.NoSpace } as State.NoSpace
        assertEquals(30 * mb, no.needed)
        assertEquals(100 * mb, no.free)
        s.start(this)
        assertEquals(0, env.fetches)
    }

    @Test
    fun `a first window already on the disk is not fetched again`() = runBlocking {
        val env = FakeEnv(remote).apply { prefix = 40 * mb; free = 0 }
        val s = PlaybackTestSession(env)
        s.begin(this)
        assertEquals(State.Ready(remote, null), s.await { it is State.Ready })
        s.start(this)
        val done = s.await { it is State.Done } as State.Done
        assertFalse(done.fetched)
        assertEquals(0, env.fetches)
    }

    @Test
    fun `a failed fetch leaves nothing and says it was the fetch`() = runBlocking {
        val env = FakeEnv(remote).apply { fetchOk = false }
        val s = PlaybackTestSession(env)
        s.begin(this)
        s.await { it is State.Ready }
        s.start(this)
        val failed = s.await { it is State.Failed } as State.Failed
        assertTrue(failed.fetching)
        assertEquals(1, env.discarded)
        assertEquals(0, env.plays)
    }

    @Test
    fun `cancel in the middle of the fetch deletes the partial and closes`() = runBlocking {
        val env = FakeEnv(remote).apply { fetchGate = CompletableDeferred() }
        val s = PlaybackTestSession(env)
        s.begin(this)
        s.await { it is State.Ready }
        s.start(this)
        val fetching = s.await { it is State.Fetching } as State.Fetching
        assertEquals(10 * mb, fetching.bytes)
        s.cancel()
        assertEquals(State.Idle, s.state.value)
        withTimeout(5_000) { while (env.discarded == 0) yield() }
        assertEquals(1, env.discarded)
        assertEquals(0, env.plays)
    }

    @Test
    fun `cancel in the middle of the test deletes what the fetch brought`() = runBlocking {
        val env = FakeEnv(remote).apply { playGate = CompletableDeferred() }
        val s = PlaybackTestSession(env)
        s.begin(this)
        s.await { it is State.Ready }
        s.start(this)
        val testing = s.await { it is State.Testing && it.playedMs > 0 } as State.Testing
        assertEquals(5_000, testing.playedMs)
        s.cancel()
        withTimeout(5_000) { while (env.discarded == 0) yield() }
        assertEquals(State.Idle, s.state.value)
        assertEquals(1, env.discarded)
    }

    @Test
    fun `a double press on Start runs one test`() = runBlocking {
        val env = FakeEnv(downloaded).apply { playGate = CompletableDeferred() }
        val s = PlaybackTestSession(env)
        s.begin(this)
        s.await { it is State.Ready }
        s.start(this)
        s.start(this)
        s.start(this)
        s.await { it is State.Testing && it.playedMs > 0 }
        env.playGate!!.complete(Unit)
        s.await { it is State.Done }
        assertEquals(1, env.plays)
    }

    @Test
    fun `the result goes to Send logs once and without a title`() = runBlocking {
        val env = FakeEnv(downloaded)
        val s = PlaybackTestSession(env)
        s.begin(this)
        s.await { it is State.Ready }
        s.start(this)
        s.await { it is State.Done }
        s.addToReport(this)
        val done = s.await { it is State.Done && it.added } as State.Done
        assertTrue(done.added)
        assertTrue(env.kept!!.first().startsWith("Playback test:"))
        assertFalse(env.kept!!.any { "downloaded video" in it })
        env.kept = null
        s.addToReport(this)
        yield()
        assertEquals(null, env.kept)
    }

    @Test
    fun `a result that could not be kept says so and can be tried again`() = runBlocking {
        val env = FakeEnv(downloaded).apply { keepOk = false }
        val s = PlaybackTestSession(env)
        s.begin(this)
        s.await { it is State.Ready }
        s.start(this)
        s.await { it is State.Done }
        s.addToReport(this)
        assertTrue((s.await { it is State.Done && it.addFailed } as State.Done).addFailed)
        env.keepOk = true
        s.addToReport(this)
        assertTrue((s.await { it is State.Done && it.added } as State.Done).added)
    }

    @Test
    fun `a player that throws is a failed step with a way out`() = runBlocking {
        val env = object : PlaybackTestSession.Env by FakeEnv(downloaded) {
            override suspend fun play(c: Candidate, progress: (Long) -> Unit): PlaybackTest.Result = throw IllegalStateException("codec")
        }
        val s = PlaybackTestSession(env)
        s.begin(this)
        s.await { it is State.Ready }
        s.start(this)
        assertFalse((s.await { it is State.Failed } as State.Failed).fetching)
        s.close()
        assertEquals(State.Idle, s.state.value)
    }
}
