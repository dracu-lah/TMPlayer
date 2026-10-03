package com.tmplayer.data

import dev.g000sha256.tdl.TdlResult

/** A TDLib request that came back as an error. */
class TdlError(val code: Int, override val message: String) : Exception("TDLib $code: $message")

val <T> TdlResult<T>.valueOrNull: T?
    get() = (this as? TdlResult.Success)?.result

fun <T> TdlResult<T>.value(): T = when (this) {
    is TdlResult.Success -> result
    is TdlResult.Failure -> throw TdlError(code, message)
}

val <T> TdlResult<T>.errorMessage: String?
    get() = (this as? TdlResult.Failure)?.message
