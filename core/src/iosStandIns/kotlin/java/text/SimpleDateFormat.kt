package java.text

import java.util.Date
import java.util.Locale
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSLocale
import platform.Foundation.NSTimeZone
import platform.Foundation.currentLocale
import platform.Foundation.localTimeZone
import platform.Foundation.timeIntervalSince1970

/** java.text.SimpleDateFormat over NSDateFormatter: the same pattern letters for what core uses. */
class SimpleDateFormat(private val pattern: String, private val locale: Locale? = null) {
    var timeZone: java.util.TimeZone = java.util.TimeZone.getDefault()

    private fun formatter(): NSDateFormatter = NSDateFormatter().apply {
        this.locale = this@SimpleDateFormat.locale?.nsLocale ?: NSLocale.currentLocale
        dateFormat = pattern
        this.timeZone = this@SimpleDateFormat.timeZone.zone
    }

    fun format(date: Date): String = formatter().stringFromDate(java.time.dateOf(date.time))

    fun parse(text: String): Date? = formatter().dateFromString(text)?.let { Date((it.timeIntervalSince1970 * 1000.0).toLong()) }
        ?: throw ParseException("Unparseable date: \"$text\"", 0)
}

class ParseException(message: String, val errorOffset: Int) : Exception(message)
