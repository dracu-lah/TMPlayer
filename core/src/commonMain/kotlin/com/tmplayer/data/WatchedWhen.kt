package com.tmplayer.data

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * When a video on the "Watched" list was finished, in the words a card can carry.
 *
 * Calendar days rather than 24 hour spans: something finished at eleven last night was watched
 * "Yesterday" this morning, not "Today". Anything older than a week is a date, since "41 days ago"
 * makes the viewer do the arithmetic the card should have done.
 */
object WatchedWhen {

    private val SAME_YEAR = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
    private val OTHER_YEAR = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

    /**
     * The same moment as part of a sentence, for the line under a card: "Watched yesterday",
     * "Watched 5 minutes ago", "Watched on 27 Sep".
     */
    fun phrase(watchedAt: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val label = label(watchedAt, now, zone)
        return when {
            isDate(watchedAt, now, zone) -> "Watched on $label"
            else -> "Watched " + label.replaceFirstChar { it.lowercase() }
        }
    }

    /** Whether [label] gives a date here rather than a word or a count. */
    private fun isDate(watchedAt: Long, now: Long, zone: ZoneId): Boolean {
        if (now - watchedAt < HOUR_MS) return false
        val then = Instant.ofEpochMilli(watchedAt).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return ChronoUnit.DAYS.between(then, today) >= WEEK_DAYS
    }

    fun label(watchedAt: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val elapsed = now - watchedAt
        // A clock moved backwards, or a record from a device whose clock ran ahead, is still
        // something that has just been watched rather than something from the future.
        if (elapsed < MINUTE_MS) return "Just now"
        if (elapsed < HOUR_MS) {
            val minutes = elapsed / MINUTE_MS
            return if (minutes == 1L) "1 minute ago" else "$minutes minutes ago"
        }
        val then = Instant.ofEpochMilli(watchedAt).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val days = ChronoUnit.DAYS.between(then, today)
        return when {
            days <= 0L -> "Today"
            days == 1L -> "Yesterday"
            days < WEEK_DAYS -> "$days days ago"
            then.year == today.year -> then.format(SAME_YEAR)
            else -> then.format(OTHER_YEAR)
        }
    }

    private const val MINUTE_MS = 60_000L
    private const val HOUR_MS = 60 * MINUTE_MS
    private const val WEEK_DAYS = 7L
}
