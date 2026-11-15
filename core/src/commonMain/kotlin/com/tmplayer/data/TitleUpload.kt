package com.tmplayer.data

/**
 * How the log inbox's answer to a title report (`POST /v1/titles`, N1) is read, apart from the
 * network so a test can pin it. The inbox answers `{"code":"NAME-7K3Q","duplicate":false}`.
 *
 * It reads text from outside, so none of it throws: an answer that is cut off, the wrong type or
 * huge is simply "no code".
 */
object TitleUpload {

    /** The inbox's cap for one title report ([TitleReport.MAX_JSON]); a bigger text never leaves. */
    const val MAX_BYTES = TitleReport.MAX_JSON

    private const val MAX_REPLY_CHARS = 4_096

    private val CODE_FIELD = Regex(""""code"\s*:\s*"(NAME-[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{4})"""")
    private val DUPLICATE = Regex(""""duplicate"\s*:\s*true""")

    sealed interface Outcome {
        /** The inbox holds the pair: say [code]. [duplicate] when it had it already (another viewer sent the same). */
        data class Sent(val code: String, val duplicate: Boolean = false) : Outcome

        /** The inbox's limit: try again in about [retryAfterSeconds]. */
        data class SlowDown(val retryAfterSeconds: Int) : Outcome

        /** No answer at all (offline, a timeout, cut off part way). */
        data object NoConnection : Outcome

        /** An answer that is not a code: an error status, or a body that is not the inbox's. */
        data class Failed(val status: Int) : Outcome
    }

    /** The answer to a POST as an [Outcome]. [retryAfter] is the `Retry-After` header, in seconds. */
    fun outcome(status: Int, body: String?, retryAfter: String? = null): Outcome {
        if (status == 201 || status == 200) {
            val text = body?.take(MAX_REPLY_CHARS)
            val code = text?.let { CODE_FIELD.find(it)?.groupValues?.get(1) }
            if (code != null) return Outcome.Sent(code, duplicate = DUPLICATE.containsMatchIn(text))
        }
        if (status == 429) return Outcome.SlowDown(retryAfter?.trim()?.toIntOrNull()?.coerceIn(1, 3_600) ?: 60)
        return Outcome.Failed(status)
    }

    /**
     * Whether an outcome is worth trying again later (the pair stays queued): no connection, the
     * limit, or the inbox itself failing. A refusal of the pair (any other status) never changes.
     */
    fun worthRetrying(outcome: Outcome): Boolean = when (outcome) {
        is Outcome.Sent -> false
        is Outcome.SlowDown, Outcome.NoConnection -> true
        is Outcome.Failed -> outcome.status in 500..599
    }
}
