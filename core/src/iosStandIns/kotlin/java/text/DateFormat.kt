package java.text

import java.util.Date
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSDateFormatterFullStyle
import platform.Foundation.NSDateFormatterLongStyle
import platform.Foundation.NSDateFormatterMediumStyle
import platform.Foundation.NSDateFormatterNoStyle
import platform.Foundation.NSDateFormatterShortStyle
import platform.Foundation.NSDateFormatterStyle
import platform.Foundation.NSLocale
import platform.Foundation.currentLocale

/** java.text.DateFormat's styled formatters over NSDateFormatter, in the user's own locale. */
class DateFormat private constructor(private val date: NSDateFormatterStyle, private val time: NSDateFormatterStyle) {
    fun format(value: Date): String = NSDateFormatter().apply {
        locale = NSLocale.currentLocale
        dateStyle = date
        timeStyle = time
    }.stringFromDate(java.time.dateOf(value.time))

    companion object {
        const val FULL = 0
        const val LONG = 1
        const val MEDIUM = 2
        const val SHORT = 3
        const val DEFAULT = MEDIUM

        private fun style(value: Int): NSDateFormatterStyle = when (value) {
            FULL -> NSDateFormatterFullStyle
            LONG -> NSDateFormatterLongStyle
            SHORT -> NSDateFormatterShortStyle
            else -> NSDateFormatterMediumStyle
        }

        fun getDateInstance(style: Int = DEFAULT): DateFormat = DateFormat(style(style), NSDateFormatterNoStyle)
        fun getTimeInstance(style: Int = DEFAULT): DateFormat = DateFormat(NSDateFormatterNoStyle, style(style))
        fun getDateTimeInstance(dateStyle: Int = DEFAULT, timeStyle: Int = DEFAULT): DateFormat = DateFormat(style(dateStyle), style(timeStyle))
    }
}
