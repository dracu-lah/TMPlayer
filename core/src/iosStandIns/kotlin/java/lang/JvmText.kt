package java.lang

import platform.Foundation.NSLocale
import platform.Foundation.NSNumber
import platform.Foundation.NSNumberFormatter
import platform.Foundation.NSNumberFormatterDecimalStyle
import platform.Foundation.currentLocale
import platform.Foundation.decimalSeparator
import platform.Foundation.groupingSeparator
import platform.Foundation.numberWithDouble

/**
 * The JVM's text helpers core's JVM half calls without an import: `String.format` and
 * `lowercase(Locale)`. The format covers the conversions upstream uses (d, s, f, x, X, c, b, %, n)
 * with flags, width and precision, and rounds %f half up as java.util.Formatter does.
 */
object JvmText {
    fun lowercase(text: String, locale: java.util.Locale?): String = when (locale?.language) {
        // The only languages whose case mapping differs from the root's for Latin letters.
        "tr", "az" -> text.replace("I", "ı").replace("İ", "i").lowercase()
        else -> text.lowercase()
    }

    fun uppercase(text: String, locale: java.util.Locale?): String = when (locale?.language) {
        "tr", "az" -> text.replace("i", "İ").uppercase()
        else -> text.uppercase()
    }

    private val spec = Regex("%(\\d+\\$)?([-#+ 0,(<]*)?(\\d+)?(\\.\\d+)?([a-zA-Z%])")

    fun format(locale: java.util.Locale?, format: String, args: Array<out Any?>): String {
        val ns = locale?.nsLocale ?: NSLocale.currentLocale
        val decimal = ns.decimalSeparator
        val grouping = ns.groupingSeparator
        var next = 0
        return spec.replace(format) { match ->
            val (indexText, flags, widthText, precisionText, conversion) = match.destructured
            if (conversion == "%") return@replace "%"
            if (conversion == "n") return@replace "\n"
            val index = indexText.dropLast(1).toIntOrNull()?.minus(1) ?: next++
            val arg = args.getOrNull(index)
            val precision = precisionText.drop(1).toIntOrNull()
            val body = when (conversion) {
                "d" -> signed(integerText(arg), flags, grouping)
                "x", "X" -> hex(arg).let { if (conversion == "X") it.uppercase() else it }
                "o" -> octal(arg)
                "f" -> signed(fixed(arg, precision ?: 6, decimal), flags, grouping)
                "e", "E" -> scientific(arg, precision ?: 6).let { if (conversion == "E") it.uppercase() else it }
                "s", "S" -> (arg?.toString() ?: "null").let { if (precision != null) it.take(precision) else it }
                    .let { if (conversion == "S") it.uppercase() else it }
                "c" -> when (arg) {
                    is Char -> arg.toString()
                    is Int -> Character.toChars(arg).concatToString()
                    else -> arg.toString()
                }
                "b", "B" -> (if (arg is Boolean) arg else arg != null).toString()
                else -> throw IllegalArgumentException("Unknown format conversion '$conversion'")
            }
            pad(body, widthText.toIntOrNull() ?: 0, flags)
        }
    }

    private fun integerText(arg: Any?): String = when (arg) {
        is Int, is kotlin.Long, is Short, is Byte -> arg.toString()
        null -> "null"
        else -> throw IllegalArgumentException("%d needs a whole number, not ${arg::class.simpleName}")
    }

    private fun hex(arg: Any?): String = when (arg) {
        is Int -> arg.toUInt().toString(16)
        is kotlin.Long -> arg.toULong().toString(16)
        is Short -> arg.toUShort().toString(16)
        is Byte -> arg.toUByte().toString(16)
        else -> throw IllegalArgumentException("%x needs a whole number")
    }

    private fun octal(arg: Any?): String = when (arg) {
        is Int -> arg.toUInt().toString(8)
        is kotlin.Long -> arg.toULong().toString(8)
        else -> throw IllegalArgumentException("%o needs a whole number")
    }

    private fun fixed(arg: Any?, precision: Int, decimal: String): String {
        val value = when (arg) {
            is Double -> arg
            is Float -> arg.toDouble()
            is Number -> arg.toDouble()
            else -> throw IllegalArgumentException("%f needs a number")
        }
        if (value.isNaN()) return "NaN"
        if (value.isInfinite()) return if (value > 0) "Infinity" else "-Infinity"
        // Half up, as the JVM's Formatter rounds; NSNumberFormatter keeps the digits exact.
        val formatter = NSNumberFormatter().apply {
            numberStyle = NSNumberFormatterDecimalStyle
            usesGroupingSeparator = false
            minimumFractionDigits = precision.toULong()
            maximumFractionDigits = precision.toULong()
            roundingMode = platform.Foundation.NSNumberFormatterRoundHalfUp
            decimalSeparator = decimal
            minusSign = "-"
        }
        return formatter.stringFromNumber(NSNumber.numberWithDouble(value)) ?: value.toString()
    }

    private fun scientific(arg: Any?, precision: Int): String {
        val value = (arg as? Number)?.toDouble() ?: throw IllegalArgumentException("%e needs a number")
        if (value == 0.0) return "0." + "0".repeat(precision) + "e+00"
        var exponent = kotlin.math.floor(kotlin.math.log10(kotlin.math.abs(value))).toInt()
        var mantissa = value / powerOfTen(exponent)
        val factor = powerOfTen(precision)
        mantissa = kotlin.math.floor(kotlin.math.abs(mantissa) * factor + 0.5) / factor * kotlin.math.sign(mantissa)
        if (kotlin.math.abs(mantissa) >= 10.0) {
            mantissa /= 10.0
            exponent++
        }
        val digits = fixed(mantissa, precision, ".")
        val sign = if (exponent < 0) "-" else "+"
        return "${digits}e$sign${kotlin.math.abs(exponent).toString().padStart(2, '0')}"
    }

    private fun powerOfTen(n: Int): Double = kotlin.math.exp(n * kotlin.math.ln(10.0))

    private fun signed(text: String, flags: String, grouping: String): String {
        var body = text
        val negative = body.startsWith("-")
        if (negative) body = body.drop(1)
        if (',' in flags) {
            val whole = body.substringBefore('.').let { w -> if (w.all(Char::isDigit)) w else body.takeWhile(Char::isDigit) }
            val rest = body.removePrefix(whole)
            body = whole.reversed().chunked(3).joinToString(grouping).reversed() + rest
        }
        val sign = when {
            negative -> if ('(' in flags) "(" else "-"
            '+' in flags -> "+"
            ' ' in flags -> " "
            else -> ""
        }
        return sign + body + if (negative && '(' in flags) ")" else ""
    }

    private fun pad(body: String, width: Int, flags: String): String = when {
        body.length >= width -> body
        '-' in flags -> body.padEnd(width)
        '0' in flags && (body.firstOrNull()?.let { it.isDigit() || it in "+- (" } == true) -> {
            val signLength = if (body.firstOrNull() in listOf('-', '+', ' ', '(')) 1 else 0
            body.take(signLength) + body.drop(signLength).padStart(width - signLength, '0')
        }
        else -> body.padStart(width)
    }
}
