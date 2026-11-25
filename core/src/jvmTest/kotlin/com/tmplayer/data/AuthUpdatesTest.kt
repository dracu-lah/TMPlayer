package com.tmplayer.data

import dev.g000sha256.tdl.dto.AuthorizationState
import dev.g000sha256.tdl.dto.AuthorizationStateClosed
import dev.g000sha256.tdl.dto.AuthorizationStateClosing
import dev.g000sha256.tdl.dto.AuthorizationStateReady
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A client's Closed is heard however its update flows race (Mi TV, 2026-11-25): Add account sat on
 * "Still closing the last profile" for ever while TDLib had closed in a second.
 */
class AuthUpdatesTest {

    /** Stands for tdl-coroutines' end of stream marker. */
    private object Stop

    /**
     * What tdl-coroutines' TdlRepository does with each collector of a client's updates: hands the
     * update on, then a Stop after a Closed; and every collector stops at the first update it meets
     * once any collector has passed a Stop (one flag for the client).
     */
    private class Repository {
        val engine = MutableSharedFlow<AuthorizationState>(extraBufferCapacity = 16)
        private val stopped = AtomicBoolean(false)

        fun updates(): Flow<AuthorizationState> = flow {
            engine.collect { update ->
                emit(update as Any)
                if (update is AuthorizationStateClosed) emit(Stop)
            }
        }.takeWhile { item ->
            val now = stopped.get() || item === Stop
            stopped.set(now)
            !now
        }.let { all -> flow { all.collect { if (it is AuthorizationState) emit(it) } } }
    }

    @Test
    fun `the states collector that loses the race to another collector still hears Closed`() = runBlocking {
        val client = Repository()
        val heard = mutableListOf<String>()
        val plain = mutableListOf<String>()
        val otherDone = CompletableDeferred<Unit>()
        // Another of the client's flows (Td has eight) that gets to the Closed first.
        val other = launch { client.updates().collect { if (it is AuthorizationStateClosed) otherDone.complete(Unit) } }
        // How Td collected before: the states' collector is slow (it takes a lock and talks to
        // TDLib), so the other collector passes the Closed and its Stop first.
        val before = launch {
            client.updates().collect { state ->
                plain += state::class.simpleName.orEmpty()
                if (state is AuthorizationStateClosing) otherDone.await()
            }
        }
        val states = launch {
            AuthUpdates.follow(client.updates()) { state ->
                heard += state::class.simpleName.orEmpty()
                if (state is AuthorizationStateClosing) otherDone.await()
            }
        }
        while (client.engine.subscriptionCount.value < 3) kotlinx.coroutines.yield()
        client.engine.emit(AuthorizationStateReady())
        client.engine.emit(AuthorizationStateClosing())
        client.engine.emit(AuthorizationStateClosed())
        withTimeout(5_000) { states.join() }
        before.join()
        other.join()
        kotlin.test.assertFalse("AuthorizationStateClosed" in plain, "the race as it was: the slow collector never hears Closed")
        assertEquals("AuthorizationStateClosed", heard.lastOrNull())
        assertEquals(1, heard.count { it == "AuthorizationStateClosed" })
    }

    @Test
    fun `a Closed heard in the flow is handled once`() = runBlocking {
        val heard = mutableListOf<AuthorizationState>()
        AuthUpdates.follow(kotlinx.coroutines.flow.flowOf(AuthorizationStateClosing(), AuthorizationStateClosed())) { heard += it }
        assertEquals(2, heard.size)
        assertEquals(1, heard.count { it is AuthorizationStateClosed })
    }
}
