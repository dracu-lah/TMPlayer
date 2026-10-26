package com.tmplayer.i18n

import com.tmplayer.data.SizeFilter
import com.tmplayer.player.StreamStats

/**
 * Numbers, sizes, durations, dates and track language names in one UI language. The unit words
 * come from the catalog (`format.*`), the digits and separators from the platform's locale data
 * ([PlatformText]: the JDK on Android and the desktop, `Intl` in the browser), so French
 * reads "1,5 Go" once its catalog says "{size} Go".
 */
class LocaleFormatter internal constructor(private val messages: Messages) {

    val locale: TmLocale get() = messages.locale

    /** A whole number grouped (`1,234`), anything else with up to three fraction digits. */
    fun number(value: Number): String = when (value) {
        is Int, is Long, is Short, is Byte -> PlatformText.integer(locale, value.toLong(), grouping = true, minDigits = 1)
        is Decimal -> if (value.scale <= 0) {
            PlatformText.integer(locale, value.toLong(), grouping = true, minDigits = 1)
        } else {
            PlatformText.decimal(locale, value.toDouble(), 0, 3)
        }
        else -> PlatformText.decimal(locale, value.toDouble(), 0, 3)
    }

    /** [value] with exactly [digits] fraction digits: `1.5`, `1,5`. */
    fun decimal(value: Double, digits: Int): String = PlatformText.decimal(locale, value, digits, digits)

    /** `62%`, rounded down so something still arriving is never shown as complete. */
    fun percent(fraction: Double): String {
        val whole = (fraction * 100).toInt().coerceIn(0, 100)
        return PlatformText.percent(locale, whole / 100.0)
    }

    /** `1.5 GB`, `350 MB`, `12 KB` (binary units, as the rest of the app counts), or "" for nothing. */
    fun size(bytes: Long): String = when {
        bytes <= 0 -> ""
        bytes >= GB -> messages.formatSizeGb(decimal(bytes.toDouble() / GB, 1))
        bytes >= MB -> messages.formatSizeMb(decimal(bytes.toDouble() / MB, 0))
        else -> messages.formatSizeKb(decimal(bytes.toDouble() / KB, 0))
    }

    /**
     * The sizes of several copies, smallest to largest: `1.4 GB to 2.2 GB`, or one size when both
     * ends come out as the same text (`2.2 GB`, never `2.2 GB to 2.2 GB`), and "" when neither is
     * known. The ends may come in either order.
     */
    fun sizeSpan(minBytes: Long, maxBytes: Long): String {
        val low = size(minOf(minBytes, maxBytes).takeIf { it > 0 } ?: maxOf(minBytes, maxBytes))
        val high = size(maxOf(minBytes, maxBytes))
        return if (low == high) high else messages.searchSizeRange(low, high)
    }

    /**
     * A transfer's running total: `0 MB`, `640 KB`, `12.5 MB`, `1.42 GB`. Finer than [size], since
     * it is watched while it counts up.
     */
    fun bytes(bytes: Long): String = when {
        bytes <= 0 -> messages.formatSizeMb(number(0))
        bytes < MB -> messages.formatSizeKb(number(bytes / KB))
        bytes < GB -> messages.formatSizeMb(decimal(bytes.toDouble() / MB, 1))
        else -> messages.formatSizeGb(decimal(bytes.toDouble() / GB, 2))
    }

    /** `640 KB/s`, `2.4 MB/s`, or `…` while the speed says nothing yet. */
    fun speed(bytesPerSec: Long): String = when {
        bytesPerSec < StreamStats.MIN_MEANINGFUL_SPEED -> "…"
        bytesPerSec < MB -> messages.formatSpeedKb(number(bytesPerSec / KB))
        else -> messages.formatSpeedMb(decimal(bytesPerSec.toDouble() / MB, 1))
    }

    /** "about 3m 20s left", or "" when there is no estimate yet. */
    fun eta(seconds: Long?): String = when {
        seconds == null -> ""
        seconds <= 1 -> messages.formatEtaAlmost
        seconds < 60 -> messages.formatEtaSeconds(seconds)
        seconds < 3600 -> messages.formatEtaMinutes(seconds / 60, seconds % 60)
        else -> messages.formatEtaHours(seconds / 3600, (seconds % 3600) / 60)
    }

    /**
     * One end of the size limits: `50 MB`, `2 GB`, `2.5 GB`, or the open ends "No minimum" and
     * "No limit", which pair with each other rather than reading as the whole range.
     */
    fun sizeLimit(bytes: Long): String = when {
        bytes <= SizeFilter.FLOOR -> messages.sizeNoMinimum
        bytes >= SizeFilter.CEILING -> messages.sizeNoLimit
        bytes < GB -> messages.formatSizeMb(number(bytes / MB))
        bytes % GB == 0L -> messages.formatSizeGb(number(bytes / GB))
        else -> messages.formatSizeGb(decimal(bytes.toDouble() / GB, 1))
    }

    /** The size limits as a sentence, four cases, since "between No minimum and 2 GB" is not one. */
    fun sizeRange(minBytes: Long, maxBytes: Long): String {
        val hasMin = minBytes > SizeFilter.FLOOR
        val hasMax = maxBytes < SizeFilter.CEILING
        return when {
            hasMin && hasMax -> messages.sizeRangeBetween(sizeLimit(minBytes), sizeLimit(maxBytes))
            hasMin -> messages.sizeRangeFrom(sizeLimit(minBytes))
            hasMax -> messages.sizeRangeUpTo(sizeLimit(maxBytes))
            else -> messages.sizeRangeAll
        }
    }

    /** A video's length: `1h 05m`, `42m`, never less than a minute, or "" when unknown. */
    fun duration(seconds: Long): String {
        if (seconds <= 0) return ""
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        return if (hours > 0) {
            messages.formatHoursMinutes(number(hours), twoDigits(minutes))
        } else {
            messages.formatMinutes(number(minutes.coerceAtLeast(1)))
        }
    }

    /** `1:23:45` or `4:07`, the form a seek bar reads in every language we ship. */
    fun clock(millis: Long): String {
        val total = (millis / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "${number(h)}:${twoDigits(m)}:${twoDigits(s)}" else "${number(m)}:${twoDigits(s)}"
    }

    /** A calendar date in the language's medium form, in the device's time zone: `6 Oct 2026`, `6 oct. 2026`. */
    fun date(epochMillis: Long): String = PlatformText.date(locale, epochMillis)

    /** A date and a time, both in the language's medium form, in the device's time zone. */
    fun dateTime(epochMillis: Long): String = PlatformText.dateTime(locale, epochMillis)

    /** "6 Oct", or "6 Oct 2025" with [withYear]: the short form "watched on" uses. */
    fun dayMonth(epochMillis: Long, withYear: Boolean): String = PlatformText.dayMonth(locale, epochMillis, withYear)

    /**
     * An audio or subtitle track's language, named in the UI language: `ger` and `de` both read
     * "Deutsch" in German and "German" in English. Accepts ISO 639-1, 639-2 (both the terminology
     * and the bibliographic codes Matroska uses) and BCP 47 tags. Null for "undetermined" and
     * blanks; an unknown code comes back as it was.
     */
    fun trackLanguage(code: String?): String? {
        val raw = code?.trim()?.replace('_', '-')?.takeIf { it.isNotEmpty() } ?: return null
        val language = raw.substringBefore('-').lowercase()
        if (language in UNDETERMINED) return null
        val iso1 = TrackCodes.toIso1(language) ?: return raw
        val name = PlatformText.languageName(iso1, locale)
        if (name.isBlank() || name.equals(iso1, ignoreCase = true)) return raw
        return PlatformText.capitalize(name, locale)
    }

    /**
     * What a BCP 47 tag says beyond the language, named in the UI language: the script and the
     * region, so `zh-Hans` and `zh-Hant` read "Simplified" and "Traditional" and `pt-BR` reads
     * "Brazil". [trackLanguage] names only the language, and two tracks that differ here would
     * otherwise read the same. Null when the tag carries neither.
     */
    fun trackLanguageVariant(code: String?): String? {
        val raw = code?.trim()?.replace('_', '-')?.takeIf { '-' in it } ?: return null
        val script = PlatformText.scriptName(raw, locale)
        val region = PlatformText.regionName(raw, locale)
        return listOfNotNull(script, region).filter { it.isNotBlank() }.joinToString(", ").ifBlank { null }
    }

    /**
     * Formats one of each kind once and throws the text away, so the platform's locale data for
     * this language (ICU's numbers, calendar and month names) is loaded by whoever calls this, off
     * the main thread, rather than by the first frame that shows a size, a length or a date. On a
     * 1 GB television that first load allocates enough to land in a garbage collection, and a
     * frame stuck there was reported as an ANR (Sentry 156073936, HX2).
     */
    fun warmUp() {
        number(WARM_NUMBER)
        decimal(WARM_DECIMAL, 1)
        percent(WARM_FRACTION)
        clock(WARM_CLOCK_MS)
        date(WARM_EPOCH_MS)
        dayMonth(WARM_EPOCH_MS, withYear = true)
    }

    private fun twoDigits(value: Long): String = PlatformText.integer(locale, value, grouping = false, minDigits = 2)

    private companion object {
        const val KB = 1024L
        const val MB = KB * 1024
        const val GB = MB * 1024
        val UNDETERMINED = setOf("und", "mis", "mul", "zxx", "qaa")
        const val WARM_NUMBER = 1234
        const val WARM_DECIMAL = 1.5
        const val WARM_FRACTION = 0.5
        const val WARM_CLOCK_MS = 3_723_000L
        const val WARM_EPOCH_MS = 1_760_000_000_000L
    }
}

/** ISO 639-2 and 639-3 codes to the two letter ones the platform names languages by. */
internal object TrackCodes {
    private val bibliographic = mapOf(
        "alb" to "sq", "arm" to "hy", "baq" to "eu", "bur" to "my", "chi" to "zh", "cze" to "cs",
        "dut" to "nl", "fre" to "fr", "geo" to "ka", "ger" to "de", "gre" to "el", "ice" to "is",
        "mac" to "mk", "mao" to "mi", "may" to "ms", "per" to "fa", "rum" to "ro", "slo" to "sk",
        "tib" to "bo", "wel" to "cy",
    )

    private val threeToTwo: Map<String, String> by lazy {
        val out = HashMap(PlatformText.threeLetterCodes())
        out.putAll(bibliographic)
        out
    }

    /** The bibliographic code a two letter one also goes by (`de` to `ger`), or null for most. */
    fun bibliographicOf(iso1: String): String? =
        bibliographic.entries.firstOrNull { it.value == iso1 }?.key

    fun toIso1(code: String): String? = when (code.length) {
        2 -> code.let { if (it == "in") "id" else if (it == "iw") "he" else it }
        3 -> threeToTwo[code] ?: code
        else -> null
    }
}
