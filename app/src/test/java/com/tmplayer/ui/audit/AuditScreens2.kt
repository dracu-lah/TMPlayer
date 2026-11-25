package com.tmplayer.ui.audit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tmplayer.data.ChatGrid
import com.tmplayer.data.DetailAction
import com.tmplayer.data.DownloadRequest
import com.tmplayer.data.LocalDownloads
import com.tmplayer.data.MediaCursors
import com.tmplayer.data.MediaItem
import com.tmplayer.data.OfflineDownloads
import com.tmplayer.data.PageQueries
import com.tmplayer.data.QueryKey
import com.tmplayer.data.WatchPoint
import com.tmplayer.ui.browse.AccountMenuContent
import com.tmplayer.ui.browse.DetailRow
import com.tmplayer.ui.browse.LocalSeriesWatch
import com.tmplayer.ui.browse.MediaDetailScreen
import com.tmplayer.ui.browse.MediaGridScreen
import com.tmplayer.ui.browse.SeriesWatch
import com.tmplayer.ui.components.ConnectionNotice
import com.tmplayer.ui.downloads.ActiveDownloadCard
import com.tmplayer.ui.downloads.DownloadCard
import com.tmplayer.ui.downloads.DownloadRow
import com.tmplayer.ui.downloads.DownloadsScreen
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.FloatingTone
import com.tmplayer.ui.theme.Tone

internal const val AUDIT_CHAT = -7_101L

/** Screens that need a seeded cache or a bigger fake: a chat's videos, a video's page, Downloads, the account card. */
internal fun auditScreens2(): List<AuditScreen> = buildList {
    val videos = (0 until 24).map { i ->
        MediaItem(
            chatId = AUDIT_CHAT, messageId = (100 - i).toLong() * 16, fileId = i + 1,
            title = listOf("Harbour", "Night market walk", "Mountain railway", "Lecture 8, graphs", "Dawn over the bay", "A very long video title that keeps on going past the edge of the tile")[i % 6] + " " + i,
            sizeBytes = (200L + i * 37) * 1024 * 1024, durationSec = 600 + i * 311, mimeType = "video/mp4",
            thumbnailFileId = 0, miniThumbnail = null, date = 1_700_000_000 - i * 86_400, fileName = "clip$i.mp4",
        )
    }
    add(AuditScreen("chat-videos") {
        remember { PageQueries.cache.put(QueryKey.ChatMedia(AUDIT_CHAT), ChatGrid(items = videos, cursors = MediaCursors(), endReached = true)) }
        MediaGridScreen(
            chatId = AUDIT_CHAT, chatTitle = "Harbour Lights fans", chatPhotoFileId = 0, chatMiniThumbnail = null, isFavorite = false,
            minSizeBytes = 0L, maxSizeBytes = Long.MAX_VALUE, watchProgress = emptyMap(), onToggleFavorite = {}, onPlay = {},
            telegramConnected = true, offline = false, onOfflineAction = {}, connectionNotice = ConnectionNotice.Hidden,
        )
    })
    add(AuditScreen("chat-empty") {
        remember { PageQueries.cache.put(QueryKey.ChatMedia(AUDIT_CHAT - 1), ChatGrid(items = emptyList(), cursors = MediaCursors(), endReached = true)) }
        MediaGridScreen(
            chatId = AUDIT_CHAT - 1, chatTitle = "Weekend Club", chatPhotoFileId = 0, chatMiniThumbnail = null, isFavorite = true,
            minSizeBytes = 0L, maxSizeBytes = Long.MAX_VALUE, watchProgress = emptyMap(), onToggleFavorite = {}, onPlay = {},
            telegramConnected = true, offline = false, onOfflineAction = {}, connectionNotice = ConnectionNotice.Hidden,
        )
    })

    val clip = MediaItem(
        chatId = 2, messageId = 500, fileId = 0, title = "Clip.2020.1080p.WEB-DL.x264.mkv", sizeBytes = 1_400L * 1024 * 1024,
        durationSec = 6_120, mimeType = "video/x-matroska", thumbnailFileId = 0, miniThumbnail = null, date = 1_790_000_000,
        fileName = "Clip.2020.1080p.WEB-DL.x264.mkv", width = 1920, height = 1080,
    )
    fun row(action: DetailAction, label: String, detail: String? = null) = DetailRow(action, label, TmIcons.Check, detail, onSelect = {})
    add(AuditScreen("detail-video") {
        CompositionLocalProvider(LocalSeriesWatch provides SeriesWatch.None) {
            MediaDetailScreen(
                clip, "Harbour Lights fans", WatchPoint(1_800_000, 6_120_000), false,
                listOf(
                    row(DetailAction.Resume, "Resume", "from 30:00"), row(DetailAction.StartOver, "Start over"),
                    row(DetailAction.MarkWatched, "Mark as watched"), row(DetailAction.AddWatchLater, "Watch later"),
                    row(DetailAction.AddToList, "Add to a list"), row(DetailAction.Download, "Download"),
                    row(DetailAction.Share, "Share"), row(DetailAction.OpenChat, "Open chat"),
                ),
                onClose = {},
            )
        }
    })

    add(AuditScreen("downloads-empty") {
        DownloadsScreen(onPlay = {}, onBack = {}, onBrowse = {}, onDownloadSeason = {})
    })
    add(AuditScreen("downloads-cards") {
        fun record(id: Long, title: String, mb: Long) = com.tmplayer.data.ResumeRecord(
            chatId = 2, messageId = id, fileId = id.toInt(), title = title, chatTitle = "Weekend series",
            sizeBytes = mb * 1024 * 1024, durationSec = 3_000, positionMs = 0, durationMs = 0, updatedAt = id,
        )
        fun dl(id: Long, title: String, mb: Long, cached: Boolean = false, complete: Boolean = true, partOf: Long = 0) = DownloadRow(
            key = (if (cached) "cached_2_$id" else "kept_2_$id"), title = title,
            bytes = if (complete) mb * 1024 * 1024 else partOf * 1024 * 1024, totalBytes = mb * 1024 * 1024, complete = complete,
            record = record(id, title, mb), chatTitle = "Weekend series", durationSec = 3_000,
            state = LocalDownloads.FileState.Present, cached = cached,
        )
        Column(Modifier.fillMaxSize().background(Tone.background).verticalScroll(rememberScrollState()).padding(top = 40.dp)) {
            DownloadCard(row = dl(1, "Harbour Lights S01E01", 812), picking = false, checked = false, onPlay = {}, onShare = {}, onDelete = {}, onToggle = {}, onHold = null, onSaveToFolder = {})
            DownloadCard(row = dl(2, "Night market walk 1080p with a long title that wraps", 900, cached = true), picking = false, checked = false, onPlay = {}, onShare = {}, onDelete = {}, onToggle = {}, onHold = null, onKeep = {})
            DownloadCard(row = dl(3, "Lecture 8, graphs", 380, cached = true, complete = false, partOf = 120), picking = true, checked = true, onPlay = {}, onShare = {}, onDelete = {}, onToggle = {}, onHold = null, onKeep = {})
            ActiveDownloadCard(
                progress = OfflineDownloads.Progress(
                    request = DownloadRequest.from(
                        MediaItem(chatId = 2, messageId = 9, fileId = 9, title = "Mountain railway", sizeBytes = 1_500L * 1024 * 1024, durationSec = 0, mimeType = "video/mp4", thumbnailFileId = 0, miniThumbnail = null, date = 0),
                        "Travel clips",
                    ),
                    downloadedBytes = 600L * 1024 * 1024, totalBytes = 1_500L * 1024 * 1024, stage = OfflineDownloads.Stage.Paused,
                ),
                place = -1, onPause = {}, onResume = {}, onCancel = {},
            )
        }
    })

    add(AuditScreen("account-card", systemBars = false) {
        Box(Modifier.fillMaxSize().background(FloatingTone.sheet).verticalScroll(rememberScrollState())) {
            AccountMenuContent(
                account = AuditData.account, profile = null, name = "Asha Rao", first = androidx.compose.ui.focus.FocusRequester(),
                onRefresh = {}, favoriteCount = 7, watchingCount = 3, watchedCount = 12,
                onFavourites = {}, onHistory = {}, onWatchLater = {}, onSettings = {}, onUpdate = {}, onAbout = {},
                onSwitchProfile = {}, onSwitchOrAdd = {},
            )
        }
    })
}
