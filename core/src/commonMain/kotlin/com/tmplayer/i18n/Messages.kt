package com.tmplayer.i18n

import java.math.BigDecimal
import java.util.Locale

/**
 * One UI language's text: its own catalog over the English one, key by key. A key the language
 * has not translated yet, or translated into something that does not parse or names arguments the
 * English does not have, reads in English instead.
 *
 * It is the generated [Strings], so `messages.commonRetry` and `messages.browseVideosCount(3)`
 * are typed; [text] and [format] are the untyped way in, for the formatter and the tests.
 */
class Messages internal constructor(
    /** The shipped tag (`es-419`), or [Languages.PSEUDO]. */
    val tag: String,
    private val own: Map<String, Icu.Pattern>,
    private val english: Map<String, Icu.Pattern>,
    private val pseudo: Boolean = false,
) : Strings {

    override val messages: Messages get() = this

    /** The Java locale numbers, dates and language names are written in. */
    val locale: Locale = Locale.forLanguageTag(if (pseudo) Languages.ENGLISH else tag)

    /** True for a right to left language (Arabic), so a screen can mirror its layout. */
    val rtl: Boolean = Languages.isRtl(tag)

    /** Sizes, durations, dates, numbers and track language names in this language. */
    val formatter: LocaleFormatter = LocaleFormatter(this)

    /** Whether this language has its own text for [key], rather than falling back to English. */
    fun translates(key: String): Boolean = key in own

    fun text(key: String): String = format(key, emptyMap())

    fun format(key: String, vararg args: Pair<String, Any?>): String = format(key, args.toMap())

    fun format(key: String, args: Map<String, Any?>): String {
        val pattern = own[key] ?: english[key] ?: return key
        val out = StringBuilder()
        render(pattern.parts, args, null, out)
        return if (pseudo) Pseudo.wrap(out.toString()) else out.toString()
    }

    private fun render(parts: List<Icu.Part>, args: Map<String, Any?>, pound: Number?, out: StringBuilder) {
        for (part in parts) when (part) {
            is Icu.Text -> out.append(part.text)
            is Icu.Value -> when (val value = args[part.name]) {
                null -> out.append('{').append(part.name).append('}')
                is Number -> out.append(formatter.number(value))
                else -> out.append(value.toString())
            }
            Icu.Pound -> out.append(pound?.let(formatter::number) ?: "#")
            is Icu.Plural -> {
                val value = number(args[part.name])
                val shifted: Number = if (part.offset == 0) value else BigDecimal(value.toString()).subtract(BigDecimal(part.offset))
                val operands = PluralRules.Operands(value)
                val exact = operands.whole?.let { "=$it" }
                val case = exact?.let(part.cases::get)
                    ?: part.cases[PluralRules.select(tag, shifted)]
                    ?: part.cases.getValue("other")
                render(case, args, shifted, out)
            }
            is Icu.Select -> {
                val case = part.cases[args[part.name]?.toString()] ?: part.cases.getValue("other")
                render(case, args, pound, out)
            }
        }
    }

    private fun number(value: Any?): Number = when (value) {
        is Number -> value
        null -> 0
        else -> value.toString().toBigDecimalOrNull() ?: 0
    }

    override fun toString(): String = "Messages($tag, ${own.size} of ${english.size} keys)" // i18n-ok: debug toString
}

/**
 * The `en-XA` pseudo-locale: every letter of the English accented, each message bracketed and
 * stretched by about a third, so text that is not going through the catalog, text that is cut
 * off, and layouts that cannot take a longer language all show at a glance.
 */
object Pseudo {
    private val accents = mapOf(
        'a' to 'á', 'b' to 'ƀ', 'c' to 'ç', 'd' to 'ð', 'e' to 'é', 'f' to 'ƒ', 'g' to 'ĝ', 'h' to 'ĥ',
        'i' to 'î', 'j' to 'ĵ', 'k' to 'ķ', 'l' to 'ļ', 'm' to 'ɱ', 'n' to 'ñ', 'o' to 'ö', 'p' to 'þ',
        'q' to 'ǫ', 'r' to 'ŕ', 's' to 'š', 't' to 'ţ', 'u' to 'û', 'v' to 'ṽ', 'w' to 'ŵ', 'x' to 'ẋ',
        'y' to 'ý', 'z' to 'ž',
        'A' to 'Å', 'B' to 'Ɓ', 'C' to 'Ç', 'D' to 'Ð', 'E' to 'É', 'F' to 'Ƒ', 'G' to 'Ĝ', 'H' to 'Ĥ',
        'I' to 'Î', 'J' to 'Ĵ', 'K' to 'Ķ', 'L' to 'Ļ', 'M' to 'Ṁ', 'N' to 'Ñ', 'O' to 'Ö', 'P' to 'Þ',
        'Q' to 'Ǫ', 'R' to 'Ŕ', 'S' to 'Š', 'T' to 'Ţ', 'U' to 'Û', 'V' to 'Ṽ', 'W' to 'Ŵ', 'X' to 'Ẋ',
        'Y' to 'Ý', 'Z' to 'Ž',
    )

    fun accent(text: String): String = buildString(text.length) { text.forEach { append(accents[it] ?: it) } }

    /** `[Ŕéţŕý ···]`: brackets for clipping, dots for about 30 per cent more width. */
    fun wrap(text: String): String {
        val extra = (text.length * 3 + 9) / 10
        return if (extra == 0) "[]" else "[$text ${"·".repeat(extra)}]"
    }
}
