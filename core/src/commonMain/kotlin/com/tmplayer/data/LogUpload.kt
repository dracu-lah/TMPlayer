package com.tmplayer.data

/**
 * The rules of "Send to developer" (reliability plan L5), apart from the network so a test can pin
 * them: whether a build may offer it, how the log inbox's answer is read, and what each answer
 * means for the screen. The inbox is `workers/log-inbox`; it answers `{"code":"TMP-7K3Q"}`.
 *
 * Everything here reads text from outside, so none of it throws: a reply that is cut off, the wrong
 * type or huge is simply "no code".
 */
object LogUpload {

    /** The inbox's cap; the bundle's own cap ([LogBundle.MAX_BYTES]) is under it. */
    const val MAX_BYTES = 2 * 1024 * 1024

    /** Only this much of an answer is looked at. */
    private const val MAX_REPLY_CHARS = 4_096

    private val CODE = Regex("""TMP-[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{4}""")
    private val CODE_FIELD = Regex(""""code"\s*:\s*"(TMP-[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{4})"""")

    /**
     * Whether a build with this address offers the button: empty means none (forks and CI builds),
     * and only a secure address counts, so a report never travels in the clear.
     */
    fun available(url: String?): Boolean {
        val u = url?.trim().orEmpty()
        return u.startsWith("https://") && u.length > "https://".length && u.none { it.isWhitespace() }
    }

    /**
     * The inbox's address for title reports (N1), made from the build's one address
     * (`https://host/v1/report` gives `https://host/v1/titles`), or null where there is no inbox.
     * Same host, same rules: a build with no address offers no title reports either.
     */
    fun titlesUrl(url: String?): String? {
        if (!available(url)) return null
        val u = url!!.trim().substringBefore('?').substringBefore('#').trimEnd('/')
        val base = if (u.endsWith("/v1/report")) u.removeSuffix("/v1/report") else u
        return "$base/v1/titles"
    }

    /**
     * The inbox's address for feedback (HX64): `https://host/v1/report` gives
     * `https://host/v1/feedback`, or null where there is no inbox.
     */
    fun feedbackUrl(url: String?): String? = titlesUrl(url)?.removeSuffix("/v1/titles")?.let { "$it/v1/feedback" }

    /** The code in the inbox's answer, or null when there is not exactly a well formed one. */
    fun parseCode(body: String?): String? {
        if (body == null) return null
        return CODE_FIELD.find(body.take(MAX_REPLY_CHARS))?.groupValues?.get(1)?.takeIf { CODE.matches(it) }
    }

    /** What an upload came to, for the screen. */
    sealed interface Outcome {
        /** Stored: say [code] large. */
        data class Sent(val code: String) : Outcome

        /** The inbox's limit: try again in about [retryAfterSeconds]. */
        data class SlowDown(val retryAfterSeconds: Int) : Outcome

        /** No answer at all (offline, a timeout, cut off part way): the file is kept instead. */
        data object NoConnection : Outcome

        /** An answer that is not a code (an error status, or a body that is not the inbox's): the file is kept instead. */
        data class Failed(val status: Int) : Outcome
    }

    /** The answer to a POST as an [Outcome]. [retryAfter] is the `Retry-After` header, in seconds. */
    fun outcome(status: Int, body: String?, retryAfter: String? = null): Outcome {
        if (status == 201 || status == 200) parseCode(body)?.let { return Outcome.Sent(it) }
        if (status == 429) {
            val wait = retryAfter?.trim()?.toIntOrNull()?.coerceIn(1, 3_600) ?: 60
            return Outcome.SlowDown(wait)
        }
        return Outcome.Failed(status)
    }

    /**
     * The upload is refused before it starts when the text is over the inbox's cap: that is the
     * bundle's own cap failing, never a reason to send half a report.
     */
    fun fits(text: String): Boolean = text.encodeToByteArray().size <= MAX_BYTES
}
