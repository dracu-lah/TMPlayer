package java.time

import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarIdentifierGregorian
import platform.Foundation.NSCalendarUnitDay
import platform.Foundation.NSCalendarUnitHour
import platform.Foundation.NSCalendarUnitMinute
import platform.Foundation.NSCalendarUnitMonth
import platform.Foundation.NSCalendarUnitNanosecond
import platform.Foundation.NSCalendarUnitSecond
import platform.Foundation.NSCalendarUnitYear
import platform.Foundation.NSDate
import platform.Foundation.NSDateComponents
import platform.Foundation.NSISO8601DateFormatter
import platform.Foundation.NSTimeZone
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.localTimeZone
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.timeZoneForSecondsFromGMT
import platform.Foundation.timeZoneWithName

// The part of java.time core's JVM half uses: instants, zones, dates and times in the ISO
// (proleptic Gregorian) calendar, over Foundation's Gregorian NSCalendar.

open class DateTimeException(message: String?) : RuntimeException(message)

class ZoneId private constructor(val id: String, internal val zone: NSTimeZone) {
    fun getId(): String = id
    override fun equals(other: Any?): Boolean = other is ZoneId && other.id == id
    override fun hashCode(): Int = id.hashCode()
    override fun toString(): String = id

    companion object {
        fun systemDefault(): ZoneId = NSTimeZone.localTimeZone.let { ZoneId(it.name, it) }

        fun of(id: String): ZoneId {
            if (id == "Z" || id == "UTC" || id == "GMT") return ZoneId(id, NSTimeZone.timeZoneForSecondsFromGMT(0))
            val zone = NSTimeZone.timeZoneWithName(id) ?: throw DateTimeException("Unknown time-zone ID: $id")
            return ZoneId(id, zone)
        }
    }
}

object ZoneOffset {
    val UTC: ZoneId = ZoneId.of("UTC")
}

internal fun gregorian(zone: NSTimeZone): NSCalendar =
    NSCalendar(calendarIdentifier = NSCalendarIdentifierGregorian).also { it.timeZone = zone }

internal fun dateOf(epochMillis: Long): NSDate = NSDate.dateWithTimeIntervalSince1970(epochMillis / 1000.0)

class Instant private constructor(private val millis: Long) : Comparable<Instant> {
    fun toEpochMilli(): Long = millis
    val epochSecond: Long get() = millis.floorDiv(1000)
    fun getEpochSecond(): Long = epochSecond
    fun atZone(zone: ZoneId): ZonedDateTime = ZonedDateTime(millis, zone)
    fun plusMillis(amount: Long): Instant = Instant(millis + amount)
    fun plusSeconds(amount: Long): Instant = Instant(millis + amount * 1000)
    fun minusMillis(amount: Long): Instant = Instant(millis - amount)
    fun isBefore(other: Instant): Boolean = millis < other.millis
    fun isAfter(other: Instant): Boolean = millis > other.millis
    override fun compareTo(other: Instant): Int = millis.compareTo(other.millis)
    override fun equals(other: Any?): Boolean = other is Instant && other.millis == millis
    override fun hashCode(): Int = millis.hashCode()

    /** ISO 8601 in UTC with the milliseconds when there are any, as Instant.toString. */
    override fun toString(): String = NSISO8601DateFormatter().apply {
        formatOptions = platform.Foundation.NSISO8601DateFormatWithInternetDateTime or
            (if (millis % 1000 != 0L) platform.Foundation.NSISO8601DateFormatWithFractionalSeconds else 0uL)
    }.stringFromDate(dateOf(millis))

    companion object {
        val EPOCH = Instant(0)
        fun now(): Instant = Instant(java.lang.System.currentTimeMillis())
        fun ofEpochMilli(millis: Long): Instant = Instant(millis)
        fun ofEpochSecond(seconds: Long): Instant = Instant(seconds * 1000)

        /** ISO 8601 with a zone or offset ("2025-10-03T12:00:00Z"), fractions optional. */
        fun parse(text: CharSequence): Instant {
            val value = text.toString()
            for (options in listOf(
                platform.Foundation.NSISO8601DateFormatWithInternetDateTime or platform.Foundation.NSISO8601DateFormatWithFractionalSeconds,
                platform.Foundation.NSISO8601DateFormatWithInternetDateTime,
            )) {
                NSISO8601DateFormatter().apply { formatOptions = options }.dateFromString(value)
                    ?.let { return Instant((it.timeIntervalSince1970 * 1000.0).toLong()) }
            }
            throw DateTimeParseException("Text '$value' could not be parsed", value, 0)
        }
    }
}

class ZonedDateTime internal constructor(internal val millis: Long, val zone: ZoneId) {
    private val parts: NSDateComponents by lazy {
        gregorian(zone.zone).components(
            NSCalendarUnitYear or NSCalendarUnitMonth or NSCalendarUnitDay or NSCalendarUnitHour or
                NSCalendarUnitMinute or NSCalendarUnitSecond or NSCalendarUnitNanosecond,
            fromDate = dateOf(millis),
        )
    }

    val year: Int get() = parts.year.toInt()
    val monthValue: Int get() = parts.month.toInt()
    val dayOfMonth: Int get() = parts.day.toInt()
    val hour: Int get() = parts.hour.toInt()
    val minute: Int get() = parts.minute.toInt()
    val second: Int get() = parts.second.toInt()
    fun getYear(): Int = year
    fun toLocalDate(): LocalDate = LocalDate.of(year, monthValue, dayOfMonth)
    fun toLocalDateTime(): LocalDateTime = LocalDateTime(year, monthValue, dayOfMonth, hour, minute, second, (millis.mod(1000L) * 1_000_000).toInt())
    fun toInstant(): Instant = Instant.ofEpochMilli(millis)
    fun toEpochSecond(): Long = millis.floorDiv(1000)
    fun format(formatter: java.time.format.DateTimeFormatter): String = formatter.format(this)
    override fun toString(): String = "${toLocalDateTime()}[${zone.id}]"
}

class LocalDate private constructor(val year: Int, val monthValue: Int, val dayOfMonth: Int) : Comparable<LocalDate> {
    fun getYear(): Int = year
    fun toEpochDay(): Long = epochDayOf(year.toLong(), monthValue.toLong(), dayOfMonth.toLong())
    fun plusDays(days: Long): LocalDate = ofEpochDay(toEpochDay() + days)
    fun minusDays(days: Long): LocalDate = ofEpochDay(toEpochDay() - days)
    fun isBefore(other: LocalDate): Boolean = this < other
    fun isAfter(other: LocalDate): Boolean = this > other
    fun format(formatter: java.time.format.DateTimeFormatter): String = formatter.format(this)

    /** Midnight of this date in [zone], or the first moment of it where a change of clocks skips midnight. */
    fun atStartOfDay(zone: ZoneId): ZonedDateTime {
        val parts = platform.Foundation.NSDateComponents().apply {
            year = this@LocalDate.year.toLong()
            month = monthValue.toLong()
            day = dayOfMonth.toLong()
        }
        val date = gregorian(zone.zone).dateFromComponents(parts) ?: dateOf(toEpochDay() * 86_400_000)
        return ZonedDateTime((date.timeIntervalSince1970 * 1000.0).toLong(), zone)
    }

    override fun compareTo(other: LocalDate): Int = toEpochDay().compareTo(other.toEpochDay())
    override fun equals(other: Any?): Boolean = other is LocalDate && other.year == year && other.monthValue == monthValue && other.dayOfMonth == dayOfMonth
    override fun hashCode(): Int = (year shl 11) + (monthValue shl 6) + dayOfMonth
    override fun toString(): String = "${year.toString().padStart(4, '0')}-${monthValue.toString().padStart(2, '0')}-${dayOfMonth.toString().padStart(2, '0')}"

    companion object {
        fun of(year: Int, month: Int, dayOfMonth: Int): LocalDate {
            if (month !in 1..12 || dayOfMonth !in 1..31) throw DateTimeException("Invalid date $year-$month-$dayOfMonth")
            return LocalDate(year, month, dayOfMonth)
        }

        fun ofEpochDay(epochDay: Long): LocalDate {
            // Howard Hinnant's civil_from_days, the inverse of epochDayOf.
            val z = epochDay + 719_468
            val era = (if (z >= 0) z else z - 146_096) / 146_097
            val dayOfEra = z - era * 146_097
            val yearOfEra = (dayOfEra - dayOfEra / 1460 + dayOfEra / 36_524 - dayOfEra / 146_096) / 365
            val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
            val mp = (5 * dayOfYear + 2) / 153
            val day = dayOfYear - (153 * mp + 2) / 5 + 1
            val month = if (mp < 10) mp + 3 else mp - 9
            val year = yearOfEra + era * 400 + if (month <= 2) 1 else 0
            return LocalDate(year.toInt(), month.toInt(), day.toInt())
        }

        fun now(): LocalDate = Instant.now().atZone(ZoneId.systemDefault()).toLocalDate()
        fun now(zone: ZoneId): LocalDate = Instant.now().atZone(zone).toLocalDate()
        fun parse(text: CharSequence): LocalDate {
            val parts = text.toString().split('-')
            return of(parts.getOrNull(0)?.toIntOrNull() ?: 0, parts.getOrNull(1)?.toIntOrNull() ?: 0, parts.getOrNull(2)?.toIntOrNull() ?: 0)
        }
    }
}

internal fun epochDayOf(year: Long, month: Long, day: Long): Long {
    val y = if (month <= 2) year - 1 else year
    val era = (if (y >= 0) y else y - 399) / 400
    val yearOfEra = y - era * 400
    val monthFromMarch = (month + 9) % 12
    val dayOfYear = (153 * monthFromMarch + 2) / 5 + day - 1
    val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
    return era * 146_097 + dayOfEra - 719_468
}

class LocalDateTime internal constructor(
    val year: Int,
    val monthValue: Int,
    val dayOfMonth: Int,
    val hour: Int,
    val minute: Int,
    val second: Int,
    val nano: Int = 0,
) {
    fun toLocalDate(): LocalDate = LocalDate.of(year, monthValue, dayOfMonth)
    fun format(formatter: java.time.format.DateTimeFormatter): String = formatter.format(this)

    /** The epoch milliseconds of these fields read as UTC: how a formatter lays them out zone free. */
    internal fun utcMillis(): Long =
        (epochDayOf(year.toLong(), monthValue.toLong(), dayOfMonth.toLong()) * 86_400 + hour * 3600L + minute * 60L + second) * 1000 + nano / 1_000_000

    override fun toString(): String =
        "${toLocalDate()}T${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}:${second.toString().padStart(2, '0')}"

    override fun equals(other: Any?): Boolean = other is LocalDateTime && other.toString() == toString() && other.nano == nano
    override fun hashCode(): Int = toString().hashCode() + nano

    companion object {
        fun now(): LocalDateTime = Instant.now().atZone(ZoneId.systemDefault()).toLocalDateTime()
        fun now(zone: ZoneId): LocalDateTime = Instant.now().atZone(zone).toLocalDateTime()
        fun ofInstant(instant: Instant, zone: ZoneId): LocalDateTime = instant.atZone(zone).toLocalDateTime()
    }
}

class DateTimeParseException(message: String, val parsedString: CharSequence, val errorIndex: Int) : DateTimeException(message)
