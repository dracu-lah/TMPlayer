package com.tmplayer.data

/**
 * A phone number for a log line: its first two digits (the country code, or its start) and its
 * last two, the rest as dots, so a code sent to a wrong number shows as one ("+39 ••••••••34")
 * without the number itself.
 */
object PhoneMask {
    private const val KEPT = 2

    fun mask(number: String): String {
        val digits = number.filter { it in '0'..'9' }
        if (digits.length <= KEPT * 2) return "•".repeat(digits.length)
        return "+${digits.take(KEPT)} ${"•".repeat(digits.length - KEPT * 2)}${digits.takeLast(KEPT)}"
    }
}
