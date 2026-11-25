package com.tmplayer.ui.audit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.tmplayer.data.Account
import com.tmplayer.data.ChatKind
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.GlobalSearch
import com.tmplayer.data.HomeRow
import com.tmplayer.data.MediaItem
import com.tmplayer.data.PickerModel
import com.tmplayer.data.ProfileTile
import com.tmplayer.data.ResultGroup
import com.tmplayer.data.ResumeRecord
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.ShelfEntry
import com.tmplayer.data.TileStatus
import com.tmplayer.data.WatchLaterRecord
import com.tmplayer.ui.browse.BrowseSection
import com.tmplayer.ui.browse.BrowseTab
import com.tmplayer.ui.browse.GlobalSearchState
import com.tmplayer.ui.browse.HomePane
import com.tmplayer.ui.browse.LocalTouchPageKey
import com.tmplayer.ui.browse.LocalUnderStatusBar
import com.tmplayer.ui.browse.PhonePageFrame
import com.tmplayer.ui.browse.PhoneSearchScreen
import com.tmplayer.ui.browse.TouchChrome
import com.tmplayer.ui.browse.TouchDestination
import com.tmplayer.ui.browse.TouchNav
import com.tmplayer.ui.browse.TouchNavHost
import com.tmplayer.ui.browse.TouchPage
import com.tmplayer.ui.browse.TouchPageSpec
import com.tmplayer.ui.browse.PhoneHomeCorner
import com.tmplayer.ui.components.BigEmpty
import com.tmplayer.ui.components.BigError
import com.tmplayer.ui.components.BigLoader
import com.tmplayer.ui.lock.PinPad
import com.tmplayer.ui.lock.PinPurpose
import com.tmplayer.ui.lock.WhoIsWatching
import com.tmplayer.ui.settings.AboutScreen
import com.tmplayer.ui.settings.SettingsPage
import com.tmplayer.ui.settings.SettingsScreen
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.browse.SeriesWatch

/** Fake data the audit screens share. */
internal object AuditData {
    val chats = listOf(
        ChatSummary(1, "Harbour Lights fans", null, 0, ChatKind.Group),
        ChatSummary(2, "Weekend Club", null, 0, ChatKind.Channel),
        ChatSummary(3, "Harbour photos", null, 0, ChatKind.Channel),
    )

    fun video(id: Long, name: String, chat: Long = 1) = MediaItem(
        chatId = chat, messageId = id, fileId = id.toInt(), title = name, sizeBytes = 900_000_000, durationSec = 5_400,
        mimeType = "video/mp4", thumbnailFileId = 0, miniThumbnail = null, date = (1000 - id).toInt(), fileName = name,
    )

    fun record(id: Long, title: String, frac: Float = 0.4f) = ResumeRecord(
        chatId = 1, messageId = id, fileId = id.toInt(), title = title, chatTitle = "Harbour Lights fans",
        sizeBytes = 900_000_000, durationSec = 5400, positionMs = (5_400_000 * frac).toLong(), durationMs = 5_400_000, updatedAt = 1000 - id,
    )

    val groups = listOf(
        ResultGroup("a", listOf(video(1, "Harbour Lights S02E03 1080p.mkv"), video(11, "Harbour Lights S02E03 1080p.mkv", 3))),
        ResultGroup("b", listOf(video(2, "Harbour Lights S02E04 720p.mkv"))),
        ResultGroup("c", listOf(video(3, "Night Harbour 2021 1080p.mkv", 2))),
        ResultGroup("d", listOf(video(4, "Harbour at Dawn 2019 2160p.mkv", 2))),
    )

    val account = Account(name = "Asha Rao", username = "asha", miniThumbnail = null, photoFileId = 0)

    fun entries(chat: Long, n: Int) = (1..n).map { ShelfEntry.File(video(chat * 100 + it, "Harbour Lights S0${it}E0${it} 1080p", chat)) }

    val homeRows: List<HomeRow> = listOf(
        HomeRow.Continue(listOf(record(1, "Harbour Lights S02E03"), record(2, "Night Harbour 2021", 0.8f), record(3, "Harbour at Dawn", 0.1f))),
        HomeRow.Chat(1, "Harbour Lights fans", entries(1, 6), loaded = true, fetched = 40, complete = false, more = emptyList()),
        HomeRow.Chat(2, "Weekend Club with a very long chat name that runs on and on", entries(2, 5), loaded = true, fetched = 5),
        HomeRow.Recent(entries(3, 6), loaded = true),
    )

    fun searchState(q: String, groups: List<ResultGroup> = AuditData.groups, extra: GlobalSearchState.() -> GlobalSearchState = { this }) =
        GlobalSearchState(
            query = GlobalSearch.parse(q),
            groups = groups,
            chats = chats.associate { it.id to com.tmplayer.data.ChatBrief.of(it) },
            endReached = true,
        ).extra()
}

/** The shell: the real floating bar and account picture over [content], as the phone draws a bar page. */
@Composable
internal fun Shell(destination: TouchDestination, key: String, content: @Composable () -> Unit) {
    val nav = remember {
        TouchNav(
            account = AuditData.account,
            favoriteCount = 3, downloadCount = 1, watchingCount = 2, watchedCount = 5, updateVersion = null,
            onHome = {}, onChats = {}, onSearch = {}, onSettings = {},
            onFavourites = {}, onDownloads = {}, onHistory = {}, onUpdate = {},
        )
    }
    val chrome = remember { TouchChrome() }
    Box(Modifier.fillMaxSize().background(Tone.background)) {
        TouchNavHost(nav, chrome, key) {
            CompositionLocalProvider(LocalTouchPageKey provides key) {
                TouchPage(TouchPageSpec(current = destination, refresh = {}))
                content()
            }
        }
    }
}

@Composable
private fun SearchScreenOf(state: GlobalSearchState, text: String) {
    var field by remember { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }
    PhoneSearchScreen(
        state = state.copy(query = GlobalSearch.parse(field.text)),
        text = field,
        onText = { field = it },
        onSubmit = {},
        onClose = {},
        fieldModifier = Modifier,
        chats = AuditData.chats,
        favourites = setOf(2L),
        recentSearches = listOf("harbour lights", "night harbour", "weekend club"),
        continueWatching = emptyList(),
        continueArt = { it.toMediaItem() },
        progress = { null },
        onOpenChat = {},
        onOpenGroup = {},
        onResume = {},
        onFilters = {},
        onSort = {},
        onLoadMore = {},
        onRetry = {},
        onRemoveRecent = {},
        onClearRecent = { emptyList() },
        onRestoreRecent = {},
        onVoice = {},
        listState = rememberLazyListState(),
    )
}

/** Home as the real browser draws it (rows, bar, account picture), with the bottom room the lists keep for the bar. */
@Composable
private fun HomeOf(
    rows: List<HomeRow>,
    ready: Boolean = true,
    noFavourites: Boolean = false,
    work: com.tmplayer.data.WorkStatus = com.tmplayer.data.WorkStatus.Idle,
    stalled: Boolean = false,
) {
    Browse(
        tab = BrowseTab.Home, destination = TouchDestination.Home,
        favourites = if (noFavourites) emptySet() else setOf(1L, 2L),
        homeRows = rows, homeRowsReady = ready, homeStatus = work, homeStalled = stalled,
    )
}

/** Every screen the first batch covers: Home, Settings and its pages, About, Search, states, lock. */
internal fun auditScreens(): List<AuditScreen> = buildList {
    add(AuditScreen("home-rows") { HomeOf(AuditData.homeRows) })
    add(AuditScreen("home-empty") { HomeOf(emptyList()) })
    add(AuditScreen("home-loading") { HomeOf(emptyList(), ready = false) })
    add(AuditScreen("home-no-favourites") { HomeOf(emptyList(), noFavourites = true) })
    add(AuditScreen("home-rows-partial") {
        HomeOf(AuditData.homeRows, work = com.tmplayer.data.WorkStatus.Partial(okCount = 3, failedCount = 2, what = "2 chats could not be read for now."))
    })
    add(AuditScreen("home-rows-stalled") { HomeOf(AuditData.homeRows, stalled = true) })

    add(AuditScreen("settings-main") {
        SettingsScreen(chats = emptyList(), onLoggedOut = {}, onBack = {}, initialPage = null, accountFolders = emptyList())
    })
    for (page in SettingsPage.entries) {
        add(AuditScreen("settings-${page.name.lowercase()}") {
            SettingsScreen(chats = emptyList(), onLoggedOut = {}, onBack = {}, initialPage = page, accountFolders = emptyList())
        })
    }
    add(AuditScreen("about") { AboutScreen(onBack = {}) })

    add(AuditScreen("search-empty") { SearchScreenOf(GlobalSearchState(), "") })
    add(AuditScreen("search-typing") { SearchScreenOf(GlobalSearchState(settling = true), "harbour") })
    add(AuditScreen("search-results") { SearchScreenOf(AuditData.searchState("harbour"), "harbour") })
    add(AuditScreen("search-none") { SearchScreenOf(AuditData.searchState("zzzz qqqq", emptyList()), "zzzz qqqq") })
    add(AuditScreen("search-searching") { SearchScreenOf(GlobalSearchState(searching = true), "harbour") })
    add(AuditScreen("search-offline") { SearchScreenOf(AuditData.searchState("harbour") { copy(offline = true) }, "harbour") })
    add(AuditScreen("search-error") {
        SearchScreenOf(GlobalSearchState(error = "Telegram did not answer. Check the connection and try again."), "harbour")
    })
    add(AuditScreen("search-other-chats") { SearchScreenOf(AuditData.searchState("harbour") { copy(addedOnly = true) }, "harbour") })

    add(AuditScreen("state-empty") { BigEmpty("Nothing watched yet. Videos you finish, or mark as watched, appear here.", icon = TmIcons.Clock) })
    add(AuditScreen("state-empty-actions") {
        BigEmpty(
            "No downloads yet. Open a chat and pick a video to keep on this phone.",
            icon = TmIcons.Download,
            actionLabel = "Go to Chats", onAction = {},
            secondLabel = "Download a season", onSecondAction = {},
            linkLabel = "Open folder", onLink = {},
        )
    })
    add(AuditScreen("state-error") { BigError("Telegram did not answer.", onRetry = {}) })
    add(AuditScreen("state-loading") { BigLoader(label = "Loading your videos") })

    add(AuditScreen("pin-check", systemBars = true) {
        val context = LocalContext.current
        PinPad(settings = remember { SettingsStore(context) }, purpose = PinPurpose.Check, title = "Enter the PIN", onDone = {}, onCancel = null, brand = true)
    })
    add(AuditScreen("pin-choose") {
        val context = LocalContext.current
        PinPad(settings = remember { SettingsStore(context) }, purpose = PinPurpose.Choose, title = "Choose a PIN", onDone = {}, onCancel = {})
    })
    add(AuditScreen("who-is-watching") {
        WhoIsWatching(
            model = PickerModel(
                tiles = listOf(
                    ProfileTile("main", "Asha Raman", 1, null, locked = false, status = TileStatus.Ready, active = true),
                    ProfileTile("p1", "Benedict with a very long name indeed", 2, null, locked = true, status = TileStatus.Ready, active = false),
                    ProfileTile("p2", "", 3, null, locked = false, status = TileStatus.NeedsAttention, active = false),
                ),
                canAdd = true,
                atLimit = false,
            ),
            onOpen = {}, onAdd = {}, onOptions = {},
        )
    })
}
