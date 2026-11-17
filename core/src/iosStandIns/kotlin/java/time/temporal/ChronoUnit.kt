package java.time.temporal

import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime

/** The units core measures between dates and instants. */
enum class ChronoUnit(private val millis: Long) {
    MILLIS(1), SECONDS(1_000), MINUTES(60_000), HOURS(3_600_000), DAYS(86_400_000), WEEKS(604_800_000);

    /** Whole units from [start] to [end], negative when [end] is earlier, as the JDK counts them. */
    fun between(start: Any, end: Any): Long = when {
        start is LocalDate && end is LocalDate -> (end.toEpochDay() - start.toEpochDay()) * 86_400_000 / millis
        start is Instant && end is Instant -> (end.toEpochMilli() - start.toEpochMilli()) / millis
        start is ZonedDateTime && end is ZonedDateTime -> (end.toInstant().toEpochMilli() - start.toInstant().toEpochMilli()) / millis
        else -> throw IllegalArgumentException("Cannot measure between ${start::class.simpleName} and ${end::class.simpleName}")
    }
}
