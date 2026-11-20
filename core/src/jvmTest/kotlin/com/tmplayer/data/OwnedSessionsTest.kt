package com.tmplayer.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * A Telegram session bound to its profile (HX88), with fake sessions, a fake switch and a virtual
 * clock: a run of A that waits while A's client closes slowly never gets B's client, so nothing it
 * sends lands in B's account; the hooks before a switch run together under one deadline; a run a
 * switch cancelled does not start the sync's pause.
 */
class OwnedSessionsTest {

    private class FakeSession(val owner: AccountStamp)

    private val a = AccountStamp(1L, null)
    private val b = AccountStamp(2L, "pbbbbb")

    @Test
    fun `A's run waiting through a slow close never gets B's client, nothing is sent to B`() = runTest {
        var open = a
        var switching = false
        val live = MutableStateFlow<FakeSession?>(FakeSession(a))
        val sent = mutableListOf<AccountStamp>()
        // A's client starts closing: no session for a while, and a switch under way.
        live.value = null
        // Undispatched: the run passes its first check while A is still open, as a real one does.
        val run = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            // The run passed its first checks before the switch was asked for.
            val session = OwnedSessions.await(
                owner = a,
                mayAct = { OwnedSessions.mayAct(a, current = open, switching = switching) },
                ownerOf = { it.owner },
                timeoutMs = 30_000L,
            ) { live.filterNotNull().first() }
            if (session != null) sent += session.owner
            session
        }
        switching = true
        delay(3_000L)
        // The close took 3 s; the flip, then B's client.
        open = b
        switching = false
        live.value = FakeSession(b)
        assertNull("A's run gets no session at all", run.await())
        assertTrue("nothing went to B's account", sent.isEmpty())
    }

    @Test
    fun `a client that came up for B is refused even when nothing else says so`() = runTest {
        // Every other check passes (the switch flag already cleared, the stamp not yet read):
        // only the session's own profile tells it is B's.
        val live = MutableStateFlow<FakeSession?>(null)
        val run = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            OwnedSessions.await(a, mayAct = { true }, ownerOf = FakeSession::owner, timeoutMs = 30_000L) { live.filterNotNull().first() }
        }
        delay(3_000L)
        live.value = FakeSession(b)
        assertNull("B's client is never A's", run.await())
    }

    @Test
    fun `the owner's own live client is handed out`() = runTest {
        val session = OwnedSessions.await(a, mayAct = { true }, ownerOf = FakeSession::owner, timeoutMs = 1_000L) { FakeSession(a) }
        assertEquals(a, session?.owner)
    }

    @Test
    fun `no session while a switch is under way, nor for a profile no longer open`() {
        assertTrue(OwnedSessions.mayAct(a, current = a, switching = false))
        assertTrue(!OwnedSessions.mayAct(a, current = a, switching = true))
        assertTrue(!OwnedSessions.mayAct(a, current = b, switching = false))
    }

    @Test
    fun `a write of a run whose profile is gone stops before it is made`() = runBlocking {
        val failed = runCatching { withContext(AccountOwner(AccountStamp(Long.MAX_VALUE, "pgone1"))) { OwnedSessions.ensure() } }
        assertTrue(failed.exceptionOrNull() is OwnedSessions.ProfileGone)
    }

    @Test
    fun `the hooks before a switch run together under one deadline and say what they wait for`() = runBlocking {
        val heard = mutableListOf<List<String>>()
        val slowSync: suspend () -> Unit = { delay(10_000L) }
        val slowMove: suspend () -> Unit = { delay(10_000L) }
        BeforeSwitch.add(slowSync) { "a sync" }
        BeforeSwitch.add(slowMove) { "moving downloads" }
        try {
            val started = System.currentTimeMillis()
            BeforeSwitch.runAll(deadlineMs = 300L) { heard += it }
            val took = System.currentTimeMillis() - started
            assertTrue("one deadline for all, not one each in turn ($took ms)", took < 3_000L)
            assertEquals(listOf("a sync", "moving downloads"), heard.first())
        } finally {
            BeforeSwitch.remove(slowSync)
            BeforeSwitch.remove(slowMove)
        }
    }

    @Test
    fun `a run that a switch cancelled does not start the pause before the next`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val ran = AtomicInteger(0)
        val scheduler = SyncScheduler(
            scope = scope,
            clock = { 1_000L },
            foreground = { true },
            lowEnd = false,
            lastRunAt = { 0L },
            job = { throw kotlinx.coroutines.CancellationException("a switch") },
            onRan = { ran.incrementAndGet() },
        )
        scheduler.ask(SyncScheduler.Why.Manual)
        delay(300L)
        scope.cancel()
        assertEquals("a cancelled run is not a run", 0, ran.get())
    }
}
