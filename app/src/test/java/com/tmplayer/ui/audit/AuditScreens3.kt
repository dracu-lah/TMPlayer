package com.tmplayer.ui.audit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.tmplayer.data.ChatKind
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.WatchLaterRecord
import com.tmplayer.data.WatchedRecord
import com.tmplayer.ui.browse.BrowseData
import com.tmplayer.ui.browse.BrowseScreen
import com.tmplayer.ui.browse.BrowseSection
import com.tmplayer.ui.browse.BrowseTab
import com.tmplayer.ui.browse.HistoryTab
import com.tmplayer.ui.browse.SeriesWatch
import com.tmplayer.ui.browse.TouchDestination
import com.tmplayer.ui.components.UiState

private val bigChats = listOf(
    ChatSummary(1, "Harbour Lights fans", null, 0, ChatKind.Group, unreadCount = 12, isPinned = true),
    ChatSummary(2, "Weekend Club", null, 0, ChatKind.Channel, unreadCount = 0),
    ChatSummary(3, "Harbour photos and a very long channel name that should not fit on one line", null, 0, ChatKind.Channel, isMuted = true, unreadCount = 1432),
    ChatSummary(4, "Asha Rao", null, 0, ChatKind.Direct),
    ChatSummary(5, "Saved Messages", null, 0, ChatKind.Saved),
    ChatSummary(6, "Mountain railway", null, 0, ChatKind.Group, isArchived = true),
    ChatSummary(7, "Lectures 2026", null, 0, ChatKind.Channel, unreadCount = 3),
    ChatSummary(8, "Travel clips", null, 0, ChatKind.Channel),
)

private val watched = (1..6).map {
    WatchedRecord(
        chatId = 1, messageId = it.toLong(), fileId = it, title = "Harbour Lights S01E0$it", chatTitle = "Harbour Lights fans",
        sizeBytes = 800_000_000, durationSec = 2700, watchedAt = 1_790_000_000_000L - it * 86_400_000L, manual = it % 2 == 0,
    )
}

private val later = (1..5).map {
    WatchLaterRecord(
        chatId = 2, messageId = it.toLong(), fileId = it, title = "Night market walk $it", fileName = "night$it.mp4",
        chatTitle = "Weekend Club", sizeBytes = 500_000_000, durationSec = 1800, mimeType = "video/mp4", addedAt = 1_790_000_000_000L - it * 3_600_000L,
    )
}

@Composable
internal fun Browse(
    tab: BrowseTab,
    destination: TouchDestination?,
    chats: List<ChatSummary> = bigChats,
    history: HistoryTab? = null,
    favourites: Set<Long> = setOf(1L, 2L),
    continueWatching: List<com.tmplayer.data.ResumeRecord> = AuditData.homeRows.filterIsInstance<com.tmplayer.data.HomeRow.Continue>().flatMap { it.records },
    watchedHistory: List<WatchedRecord> = watched,
    watchLater: List<WatchLaterRecord> = later,
    homeRows: List<com.tmplayer.data.HomeRow> = AuditData.homeRows,
    homeRowsReady: Boolean = true,
    homeStatus: com.tmplayer.data.WorkStatus = com.tmplayer.data.WorkStatus.Idle,
    homeStalled: Boolean = false,
) {
    val context = LocalContext.current
    LaunchedEffect(history) {
        if (history != null) SettingsStore(context).setHistoryTab(history.name)
    }
    Shell(destination ?: TouchDestination.Home, "browse-${tab.name}") {
        BrowseScreen(
            state = UiState.Content(BrowseData(chats)),
            account = AuditData.account,
            favorites = favourites,
            continueWatching = continueWatching,
            onRetry = {},
            onOpenChat = {},
            onResumeMedia = {},
            onOpenSettings = {},
            picked = BrowseSection.of(tab),
            watchedHistory = watchedHistory,
            watchLater = watchLater,
            homeRows = homeRows,
            homeRowsReady = homeRowsReady,
            homeStatus = homeStatus,
            homeStalled = homeStalled,
            homeWatch = SeriesWatch.None,
        )
    }
}

internal fun allAuditScreens(): List<AuditScreen> = auditScreens() + auditScreens2() + auditScreens3() + auditScreens4()

internal fun auditScreens3(): List<AuditScreen> = buildList {
    add(AuditScreen("browse-chats") { Browse(BrowseTab.Chats, TouchDestination.Chats) })
    add(AuditScreen("browse-chats-empty") { Browse(BrowseTab.Chats, TouchDestination.Chats, chats = emptyList()) })
    add(AuditScreen("browse-favourites") { Browse(BrowseTab.Favorites, null) })
    add(AuditScreen("browse-favourites-empty") { Browse(BrowseTab.Favorites, null, favourites = emptySet()) })
    add(AuditScreen("history-continue") { Browse(BrowseTab.History, null, history = HistoryTab.Continue) })
    add(AuditScreen("history-continue-empty") { Browse(BrowseTab.History, null, history = HistoryTab.Continue, continueWatching = emptyList()) })
    add(AuditScreen("history-later") { Browse(BrowseTab.History, null, history = HistoryTab.WatchLater) })
    add(AuditScreen("history-later-empty") { Browse(BrowseTab.History, null, history = HistoryTab.WatchLater, watchLater = emptyList()) })
    add(AuditScreen("history-watched") { Browse(BrowseTab.History, null, history = HistoryTab.Watched) })
    add(AuditScreen("history-watched-empty") { Browse(BrowseTab.History, null, history = HistoryTab.Watched, watchedHistory = emptyList()) })
}
