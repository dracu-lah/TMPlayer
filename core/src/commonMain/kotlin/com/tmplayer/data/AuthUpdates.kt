package com.tmplayer.data

import com.tmplayer.platform.Logger
import dev.g000sha256.tdl.dto.AuthorizationState
import dev.g000sha256.tdl.dto.AuthorizationStateClosed
import kotlinx.coroutines.flow.Flow

/**
 * A client's authorization states, with its Closed always heard.
 *
 * tdl-coroutines ends every update flow of a client once one of them has passed on Closed: a shared
 * flag is set by the first collector to get there, and each other collector then stops on the
 * Closed itself without handing it on. Which collector is first is a race between the client's
 * flows (Td has eight), so on a slow TV the states' collector lost it: TDLib had closed in a second,
 * the app never heard Closed, never started the next client, and Add account or a switch waited
 * for ever on "Still closing the last profile" (Mi TV, 2026-11-25).
 *
 * The flow ending is itself the news: a client's update flows end only after its Closed. So when
 * [states] ends without [handle] having been given a Closed, it is given one.
 */
internal object AuthUpdates {
    suspend fun follow(states: Flow<AuthorizationState>, handle: suspend (AuthorizationState) -> Unit) {
        var closed = false
        states.collect { state ->
            if (state is AuthorizationStateClosed) closed = true
            handle(state)
        }
        if (!closed) {
            Logger.i(TAG, "the client's updates ended before its Closed was heard; taken as closed")
            handle(AuthorizationStateClosed())
        }
    }

    private const val TAG = "Td"
}
