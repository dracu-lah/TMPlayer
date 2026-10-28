package com.tmplayer.data

import com.tmplayer.i18n.L
import dev.g000sha256.tdl.TdlResult

/** A TDLib request that came back as an error. */
class TdlError(val code: Int, override val message: String) : Exception("TDLib $code: $message")

/**
 * A request refused because the session is no longer signed in ([SessionCheck.isAuthError]):
 * the sign in comes next, so a screen that sees it gives way rather than offering Try again
 * (HX23). Its message is the line the viewer reads.
 */
class SessionLostException : java.io.IOException(L.errorsSignedOut)

val <T> TdlResult<T>.valueOrNull: T?
    get() = when (this) {
        is TdlResult.Success -> result
        is TdlResult.Failure -> {
            noted()
            null
        }
    }

fun <T> TdlResult<T>.value(): T = when (this) {
    is TdlResult.Success -> result
    is TdlResult.Failure -> {
        noted()
        throw TdlError(code, message)
    }
}

val <T> TdlResult<T>.errorMessage: String?
    get() = (this as? TdlResult.Failure)?.also { it.noted() }?.message

/**
 * Every refusal read through these goes past [Td.noteRequestFailure], which looks only for a
 * session that is no longer signed in (HX23) and costs a string match otherwise.
 */
private fun TdlResult.Failure.noted() {
    runCatching { Td.noteRequestFailure(code, message) }
}
