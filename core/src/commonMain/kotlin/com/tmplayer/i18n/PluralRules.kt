package com.tmplayer.i18n

import java.math.BigDecimal

/**
 * CLDR cardinal plural rules (CLDR 46) for the languages TMPlayer ships, written out by hand so
 * no ICU4J is needed. Each returns one of `zero`, `one`, `two`, `few`, `many`, `other`; a message
 * that has no case for the category it gets falls back to `other`, as ICU does.
 *
 * The operands are CLDR's: `n` the absolute value, `i` its integer digits, `v` how many fraction
 * digits are visible (so `1.0` is not "one" in English), `e` the compact exponent, always 0 here.
 */
object PluralRules {

    /** The operands of one number. A Double keeps its shortest form, so 1.5 has v = 1. */
    class Operands(number: Number) {
        val n: BigDecimal
        val i: Long
        val v: Int

        init {
            val exact = when (number) {
                is Int, is Long, is Short, is Byte -> BigDecimal.valueOf(number.toLong())
                is BigDecimal -> number
                else -> BigDecimal(number.toDouble().toString())
            }.abs()
            n = exact
            i = exact.toBigInteger().toLong()
            v = exact.scale().coerceAtLeast(0)
        }

        /** True when n is a whole number (it may still be written with fraction digits). */
        val integral: Boolean get() = n.signum() == 0 || n.stripTrailingZeros().scale() <= 0

        /** n as a whole number, or null when it has a fractional part. */
        val whole: Long? get() = if (integral) n.toLong() else null
    }

    /** The category of [number] in [language] (a bare language code such as `ru` or `pt`). */
    fun select(language: String, number: Number): String {
        val o = Operands(number)
        return rule(language)(o)
    }

    /** Every category [language] can produce, `other` last. */
    fun categories(language: String): List<String> = when (base(language)) {
        "ar" -> listOf("zero", "one", "two", "few", "many", "other")
        "ru", "uk" -> listOf("one", "few", "many", "other")
        "es", "pt", "fr", "it" -> listOf("one", "many", "other")
        "ja", "ko", "zh", "vi", "id" -> listOf("other")
        else -> listOf("one", "other")
    }

    private fun base(language: String): String = language.substringBefore('-').substringBefore('_').lowercase()
        .let { if (it == "in") "id" else it }

    private fun rule(language: String): (Operands) -> String = when (base(language)) {
        "en", "de" -> ::oneIsExactlyOneInteger
        "it" -> { o -> if (o.i == 1L && o.v == 0) "one" else if (millions(o)) "many" else "other" }
        "es" -> { o -> if (o.whole == 1L && o.n.compareTo(BigDecimal.ONE) == 0) "one" else if (millions(o)) "many" else "other" }
        // pt (Brazil, the CLDR root for Portuguese) and fr: 0 and 1, and anything from 0 up to 2.
        "pt", "fr" -> { o -> if (o.i == 0L || o.i == 1L) "one" else if (millions(o)) "many" else "other" }
        "ru", "uk" -> ::eastSlavic
        "tr", "ml" -> { o -> if (o.n.compareTo(BigDecimal.ONE) == 0) "one" else "other" }
        "hi" -> { o -> if (o.i == 0L || o.n.compareTo(BigDecimal.ONE) == 0) "one" else "other" }
        "ar" -> ::arabic
        "ja", "ko", "zh", "vi", "id" -> { _ -> "other" }
        else -> ::oneIsExactlyOneInteger
    }

    private fun oneIsExactlyOneInteger(o: Operands) = if (o.i == 1L && o.v == 0) "one" else "other"

    /** `e = 0 and i != 0 and i % 1000000 = 0 and v = 0`: "1 000 000 de vidéos". */
    private fun millions(o: Operands) = o.v == 0 && o.i != 0L && o.i % 1_000_000 == 0L

    private fun eastSlavic(o: Operands): String {
        if (o.v != 0) return "other"
        val mod10 = o.i % 10
        val mod100 = o.i % 100
        return when {
            mod10 == 1L && mod100 != 11L -> "one"
            mod10 in 2..4 && mod100 !in 12..14 -> "few"
            else -> "many"
        }
    }

    private fun arabic(o: Operands): String {
        val whole = o.whole ?: return "other"
        val mod100 = whole % 100
        return when {
            whole == 0L -> "zero"
            whole == 1L -> "one"
            whole == 2L -> "two"
            mod100 in 3..10 -> "few"
            mod100 in 11..99 -> "many"
            else -> "other"
        }
    }
}
