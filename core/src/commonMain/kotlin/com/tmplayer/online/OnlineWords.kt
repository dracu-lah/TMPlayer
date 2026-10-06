package com.tmplayer.online

import com.tmplayer.i18n.L
import com.tmplayer.i18n.Translator
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** The sentences the online subtitle states read as, the same on every screen. */
object OnlineWords {

    fun notice(notice: SubtitleNotice): String = when (notice) {
        SubtitleNotice.SignInToDownload -> L.onlineNoticeSignIn
        is SubtitleNotice.QuotaUsed -> notice.resetAt?.let { L.onlineNoticeQuotaUntil(time(it)) } ?: L.onlineNoticeQuota
        SubtitleNotice.SignInExpired -> L.onlineNoticeExpired
        SubtitleNotice.Unavailable -> L.onlineNoticeUnavailable
        SubtitleNotice.Busy -> L.onlineNoticeBusy
        SubtitleNotice.Offline -> L.onlineNoticeOffline
        SubtitleNotice.SubdlKeyRefused -> L.onlineNoticeSubdlRefused
        SubtitleNotice.Unreadable -> L.onlineNoticeUnreadable
    }

    /** The line under the account row in Settings. */
    fun status(status: OnlineStatus): String = when (status) {
        OnlineStatus.NotInBuild -> L.onlineStatusNotInBuild
        OnlineStatus.Unavailable -> L.onlineNoticeUnavailable
        OnlineStatus.SignedOut -> L.onlineStatusSignedOut
        is OnlineStatus.Expired -> L.onlineStatusExpired(status.username)
        is OnlineStatus.SignedIn -> if (status.remaining >= 0 && status.allowed > 0) {
            L.onlineStatusSignedIn(status.username, L.onlineDownloadsLeft(status.remaining, status.allowed.toString()))
        } else {
            L.onlineStatusSignedInPlain(status.username)
        }
        is OnlineStatus.QuotaUsed -> L.onlineStatusSignedIn(
            status.username,
            status.resetAt?.let { L.onlineNoticeQuotaUntil(time(it)) } ?: L.onlineNoticeQuota,
        )
    }

    /** Today's downloads alone, for a screen that names the account on a line of its own. */
    fun quota(status: OnlineStatus): String = when (status) {
        is OnlineStatus.SignedIn -> if (status.remaining >= 0 && status.allowed > 0) {
            L.onlineDownloadsLeft(status.remaining, status.allowed.toString())
        } else {
            ""
        }
        is OnlineStatus.QuotaUsed -> status.resetAt?.let { L.onlineNoticeQuotaUntil(time(it)) } ?: L.onlineNoticeQuota
        else -> status(status)
    }

    /** One line under a result: language, release, and the marks that tell results apart. */
    fun hitDetail(hit: SubtitleHit): String = buildList {
        add(OnlineSubtitles.languageName(hit.language))
        if (hit.hashMatch) add(L.onlineHashMatch)
        if (hit.hearingImpaired) add(L.onlineHearingImpaired)
        if (hit.machine) add(L.onlineMachine)
        if (hit.downloads > 0) add(L.onlineDownloadCount(hit.downloads))
        add(hit.provider.label)
    }.joinToString("  ·  ")

    /** A reset time as the clock reads it, or the date and time when it is not today. */
    fun time(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val at = Instant.ofEpochMilli(epochMillis).atZone(zone)
        val today = Instant.now().atZone(zone).toLocalDate()
        val locale = Translator.messages.locale
        return if (at.toLocalDate() == today) {
            DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(at)
        } else {
            Translator.messages.formatter.dateTime(epochMillis, zone)
        }
    }
}
