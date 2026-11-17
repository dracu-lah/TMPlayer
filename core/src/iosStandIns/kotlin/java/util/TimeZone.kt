package java.util

import platform.Foundation.NSTimeZone
import platform.Foundation.localTimeZone
import platform.Foundation.timeZoneForSecondsFromGMT
import platform.Foundation.timeZoneWithName

/** java.util.TimeZone, for SimpleDateFormat. */
class TimeZone private constructor(internal val zone: NSTimeZone) {
    val id: String get() = zone.name
    fun getID(): String = id

    companion object {
        fun getDefault(): TimeZone = TimeZone(NSTimeZone.localTimeZone)
        fun getTimeZone(id: String): TimeZone = TimeZone(NSTimeZone.timeZoneWithName(id) ?: NSTimeZone.timeZoneForSecondsFromGMT(0))
    }
}
