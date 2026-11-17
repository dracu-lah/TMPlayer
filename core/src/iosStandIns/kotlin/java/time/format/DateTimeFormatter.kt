package java.time.format

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.dateOf
import java.time.gregorian
import java.util.Locale
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSDateFormatterFullStyle
import platform.Foundation.NSDateFormatterLongStyle
import platform.Foundation.NSDateFormatterMediumStyle
import platform.Foundation.NSDateFormatterNoStyle
import platform.Foundation.NSDateFormatterShortStyle
import platform.Foundation.NSLocale
import platform.Foundation.currentLocale
import platform.Foundation.NSTimeZone
import platform.Foundation.localeWithLocaleIdentifier
import platform.Foundation.timeZoneForSecondsFromGMT

enum class FormatStyle(internal val style: ULong) {
    FULL(NSDateFormatterFullStyle), LONG(NSDateFormatterLongStyle), MEDIUM(NSDateFormatterMediumStyle), SHORT(NSDateFormatterShortStyle)
}

/**
 * java.time.format.DateTimeFormatter for patterns and localized styles, in the Gregorian calendar
 * whatever the device uses. Patterns use the same letters as the JDK's for what core formats
 * (y M d H m s E a), and ASCII digits unless a locale is given, as ofPattern without one does.
 */
class DateTimeFormatter private constructor(
    private val pattern: String?,
    private val dateStyle: FormatStyle?,
    private val timeStyle: FormatStyle?,
    private val locale: Locale?,
) {
    fun withLocale(locale: Locale): DateTimeFormatter = DateTimeFormatter(pattern, dateStyle, timeStyle, locale)

    private fun formatter(zone: NSTimeZone): NSDateFormatter = NSDateFormatter().apply {
        this.locale = this@DateTimeFormatter.locale?.nsLocale
            ?: if (pattern != null) NSLocale.localeWithLocaleIdentifier("en_US_POSIX") else NSLocale.currentLocale
        calendar = gregorian(zone)
        timeZone = zone
        if (pattern != null) {
            dateFormat = pattern
        } else {
            this.dateStyle = this@DateTimeFormatter.dateStyle?.style ?: NSDateFormatterNoStyle
            this.timeStyle = this@DateTimeFormatter.timeStyle?.style ?: NSDateFormatterNoStyle
        }
    }

    fun format(temporal: Any): String = when (temporal) {
        is ZonedDateTime -> formatter(temporal.zone.zone).stringFromDate(dateOf(temporal.millis))
        is LocalDateTime -> formatter(utc).stringFromDate(dateOf(temporal.utcMillis()))
        is LocalDate -> formatter(utc).stringFromDate(dateOf(LocalDateTime(temporal.year, temporal.monthValue, temporal.dayOfMonth, 0, 0, 0).utcMillis()))
        else -> throw IllegalArgumentException("Cannot format ${temporal::class.simpleName}")
    }

    companion object {
        private val utc = NSTimeZone.timeZoneForSecondsFromGMT(0)

        fun ofPattern(pattern: String): DateTimeFormatter = DateTimeFormatter(pattern, null, null, null)
        fun ofPattern(pattern: String, locale: Locale): DateTimeFormatter = DateTimeFormatter(pattern, null, null, locale)
        fun ofLocalizedDate(style: FormatStyle): DateTimeFormatter = DateTimeFormatter(null, style, null, null)
        fun ofLocalizedTime(style: FormatStyle): DateTimeFormatter = DateTimeFormatter(null, null, style, null)
        fun ofLocalizedDateTime(style: FormatStyle): DateTimeFormatter = DateTimeFormatter(null, style, style, null)
        fun ofLocalizedDateTime(dateStyle: FormatStyle, timeStyle: FormatStyle): DateTimeFormatter =
            DateTimeFormatter(null, dateStyle, timeStyle, null)

        val ISO_LOCAL_DATE: DateTimeFormatter = ofPattern("yyyy-MM-dd")
        val ISO_LOCAL_DATE_TIME: DateTimeFormatter = ofPattern("yyyy-MM-dd'T'HH:mm:ss")
    }
}
