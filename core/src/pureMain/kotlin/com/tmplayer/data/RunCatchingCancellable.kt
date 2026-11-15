package com.tmplayer.data

import kotlin.coroutines.cancellation.CancellationException

/**
 * [runCatching] for code that suspends: a cancelled coroutine is rethrown instead of being caught
 * as a failure. A plain `runCatching` around a suspend call turns the cancellation of the screen
 * that asked into a null or a default, and the loop around it carries on asking for a screen that
 * is gone (the episode lookup kept paging a chat after the player closed).
 */
suspend inline fun <T> runCatchingCancellable(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (e: Throwable) {
        Result.failure(e)
    }
