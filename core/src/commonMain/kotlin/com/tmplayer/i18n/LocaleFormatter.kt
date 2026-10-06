package com.tmplayer.i18n

import com.tmplayer.data.SizeFilter
import com.tmplayer.player.StreamStats
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Numbers, sizes, durations, dates and track language names in one UI language. The unit words
 * come from the catalog (`format.*`), the digits and separators from the Java locale, so French
 * reads "1,5 Go" once its catalog says "{size} Go".
 */
class LocaleFormatter internal constructor(private val messages: Messages) {

    val locale: Locale get() = messages.locale

    // NumberFormat is not thread safe, so each call makes its own; they are cheap next to a frame.
    fun number(value: Number): String = NumberFormat.getInstance(locale).format(value)

    /** [value] with exactly [digits] fraction digits: `1.5`, `1,5`. */
    fun decimal(value: Double, digits: Int): String = NumberFormat.getInstance(locale).apply {
        minimumFractionDigits = digits
        maximumFractionDigits = digits
    }.format(value)

    /** `62%`, rounded down so something still arriving is never shown as complete. */
    fun percent(fraction: Double): String {
        val whole = (fraction * 100).toInt().coerceIn(0, 100)
        return NumberFormat.getPercentInstance(locale).format(whole / 100.0)
    }

    /** `1.5 GB`, `350 MB`, `12 KB` (binary units, as the rest of the app counts), or "" for nothing. */
    fun size(bytes: Long): String = when {
        bytes <= 0 -> ""
        bytes >= GB -> messages.formatSizeGb(decimal(bytes.toDouble() / GB, 1))
        bytes >= MB -> messages.formatSizeMb(decimal(bytes.toDouble() / MB, 0))
        else -> messages.formatSizeKb(decimal(bytes.toDouble() / KB, 0))
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

    /** A calendar date in the language's medium form: `6 Oct 2026`, `6 oct. 2026`. */
    fun date(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
            .format(Instant.ofEpochMilli(epochMillis).atZone(zone))

    /** A date and a time, both in the language's medium form. */
    fun dateTime(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale)
            .format(Instant.ofEpochMilli(epochMillis).atZone(zone))

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
        val name = Locale.forLanguageTag(iso1).getDisplayLanguage(locale)
        if (name.isBlank() || name.equals(iso1, ignoreCase = true)) return raw
        return name.replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
    }

    private fun twoDigits(value: Long): String = NumberFormat.getIntegerInstance(locale).apply {
        minimumIntegerDigits = 2
        isGroupingUsed = false
    }.format(value)

    private companion object {
        const val KB = 1024L
        const val MB = KB * 1024
        const val GB = MB * 1024
        val UNDETERMINED = setOf("und", "mis", "mul", "zxx", "qaa")
    }
}

/** ISO 639-2 and 639-3 codes to the two letter ones Java names languages by. */
internal object TrackCodes {
    private val bibliographic = mapOf(
        "alb" to "sq", "arm" to "hy", "baq" to "eu", "bur" to "my", "chi" to "zh", "cze" to "cs",
        "dut" to "nl", "fre" to "fr", "geo" to "ka", "ger" to "de", "gre" to "el", "ice" to "is",
        "mac" to "mk", "mao" to "mi", "may" to "ms", "per" to "fa", "rum" to "ro", "slo" to "sk",
        "tib" to "bo", "wel" to "cy",
    )

    private val threeToTwo: Map<String, String> by lazy {
        val out = HashMap<String, String>()
        for (two in Locale.getISOLanguages()) {
            val three = runCatching { Locale.forLanguageTag(two).isO3Language }.getOrNull()
            if (!three.isNullOrEmpty()) out[three] = two
        }
        out.putAll(bibliographic)
        out
    }

    fun toIso1(code: String): String? = when (code.length) {
        2 -> code.let { if (it == "in") "id" else if (it == "iw") "he" else it }
        3 -> threeToTwo[code] ?: code
        else -> null
    }
}
