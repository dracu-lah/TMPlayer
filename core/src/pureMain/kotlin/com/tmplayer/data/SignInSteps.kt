package com.tmplayer.data

/**
 * What a login code field keeps of what was typed, pasted or autofilled into it.
 *
 * Shared by the phone, the TV and the desktop so the three agree on what counts as a code, and
 * kept here, away from any field, so a test can feed it a whole text message.
 */
object CodeInput {

    private val DIGIT_RUN = Regex("""\d+""")

    /**
     * The code in [raw]. For a code of digits a paste is often the whole message ("Telegram code
     * 12345. Do not give this code to anyone"), so a run of exactly [length] digits is taken when
     * there is one; otherwise every digit in it, up to [length]. A word or a phrase is kept as
     * typed, since its letters and spaces are the code.
     */
    fun clean(raw: String, digits: Boolean, length: Int): String {
        if (!digits) return raw
        if (length > 0) {
            // A run of exactly [length] digits: the same as (?<!\d)\d{n}(?!\d), without building a pattern per call.
            DIGIT_RUN.findAll(raw).firstOrNull { it.value.length == length }?.let { return it.value }
        }
        val kept = raw.filter(Char::isDigit)
        return if (length > 0) kept.take(length) else kept
    }

    /**
     * True when [code] is the whole answer and has not been sent yet. A word or a phrase has no
     * last box to notice and waits for the button. [submitted] is the last code sent, so a wrong
     * code left in the field does not send itself again on every redraw.
     */
    fun shouldAutoSubmit(code: String, length: Int, digits: Boolean, submitted: String?): Boolean =
        digits && length > 0 && code.length == length && code.all(Char::isDigit) && code != submitted
}

/** What a QR code on screen is doing, by how long it has been up. */
enum class QrPhase {
    /** Freshly drawn. */
    Fresh,

    /** In the last part of its life: TDLib is about to send the next one. */
    ExpiringSoon,

    /** Past its lifetime with no new link: TDLib is late, most often for want of a line. */
    Renewing,

    /** So late that TDLib could not renew it: the code no longer signs anybody in. */
    Expired,
}

/**
 * The clock behind the QR pane's "expires soon" cue.
 *
 * TDLib does not say how long a login link lasts. Telegram issues them for about
 * [DEFAULT_LIFETIME_MS] and TDLib asks for the next one before the last runs out, so once two
 * links have arrived the time between them is the lifetime that matters here, and until then the
 * usual figure stands in for it.
 */
object QrClock {

    const val DEFAULT_LIFETIME_MS = 30_000L

    /** From this much of the lifetime left the cue says the code is about to change. */
    const val SOON_MS = 8_000L

    /** As [com.tmplayer.ui.auth.rememberQrExpired]: well past any renewal TDLib would make. */
    const val STALE_MS = 45_000L

    /** The lifetime to draw: the last observed gap between two links, within sane bounds. */
    fun lifetime(observedGapMs: Long?): Long =
        observedGapMs?.takeIf { it in MIN_GAP_MS..STALE_MS } ?: DEFAULT_LIFETIME_MS

    fun phase(ageMs: Long, lifetimeMs: Long): QrPhase = when {
        ageMs >= STALE_MS -> QrPhase.Expired
        ageMs >= lifetimeMs -> QrPhase.Renewing
        lifetimeMs - ageMs <= SOON_MS -> QrPhase.ExpiringSoon
        else -> QrPhase.Fresh
    }

    /** How much of the code's life is left, 1 when drawn and 0 at its end. */
    fun remaining(ageMs: Long, lifetimeMs: Long): Float =
        (1f - ageMs.toFloat() / lifetimeMs.coerceAtLeast(1)).coerceIn(0f, 1f)

    /** A gap shorter than this is TDLib repeating itself, not a renewal. */
    private const val MIN_GAP_MS = 5_000L
}

/** The waits Telegram puts on sign in steps, read out of its errors. */
object SignInWaits {

    /** Seconds left until a moment given as unix seconds, never below zero. */
    fun secondsUntil(unixSeconds: Int, nowMs: Long): Int =
        (unixSeconds - nowMs / 1000).coerceAtLeast(0).toInt()
}
