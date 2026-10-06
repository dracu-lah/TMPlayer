package com.tmplayer.data

import com.tmplayer.i18n.L
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * When a video on the "Watched" list was finished, in the words a card can carry.
 *
 * Calendar days rather than 24 hour spans: something finished at eleven last night was watched
 * "Yesterday" this morning, not "Today". Anything older than a week is a date, since "41 days ago"
 * makes the viewer do the arithmetic the card should have done.
 */
object WatchedWhen {

    // The day and the month's short name in the UI language, in the order English writes them:
    // the locale's own medium date would turn "27 Sep" into "Sep 27, 2026" in English.
    private fun sameYear() = DateTimeFormatter.ofPattern("d MMM", L.messages.locale)
    private fun otherYear() = DateTimeFormatter.ofPattern("d MMM yyyy", L.messages.locale)

    /**
     * The same moment as part of a sentence, for the line under a card: "Watched yesterday",
     * "Watched 5 minutes ago", "Watched on 27 Sep".
     */
    fun phrase(watchedAt: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        if (isDate(watchedAt, now, zone)) return L.watchedOn(label(watchedAt, now, zone))
        val elapsed = now - watchedAt
        if (elapsed < MINUTE_MS) return L.watchedJustNowPhrase
        if (elapsed < HOUR_MS) return L.watchedMinutesAgoPhrase(elapsed / MINUTE_MS)
        val then = Instant.ofEpochMilli(watchedAt).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return when (val days = ChronoUnit.DAYS.between(then, today)) {
            in Long.MIN_VALUE..0L -> L.watchedTodayPhrase
            1L -> L.watchedYesterdayPhrase
            else -> L.watchedDaysAgoPhrase(days)
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
        if (elapsed < MINUTE_MS) return L.watchedJustNow
        if (elapsed < HOUR_MS) return L.watchedMinutesAgo(elapsed / MINUTE_MS)
        val then = Instant.ofEpochMilli(watchedAt).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val days = ChronoUnit.DAYS.between(then, today)
        return when {
            days <= 0L -> L.watchedToday
            days == 1L -> L.watchedYesterday
            days < WEEK_DAYS -> L.watchedDaysAgo(days)
            then.year == today.year -> then.format(sameYear())
            else -> then.format(otherYear())
        }
    }

    private const val MINUTE_MS = 60_000L
    private const val HOUR_MS = 60 * MINUTE_MS
    private const val WEEK_DAYS = 7L
}
