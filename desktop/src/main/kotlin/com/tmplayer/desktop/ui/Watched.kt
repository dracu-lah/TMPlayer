package com.tmplayer.desktop.ui

import com.tmplayer.ui.browse.HistoryTab
import com.tmplayer.ui.browse.rememberRecordItem
import com.tmplayer.ui.online.OnlineArt
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tmplayer.data.HomeRows
import com.tmplayer.data.MediaMapper
import com.tmplayer.data.WatchLaterRecord
import com.tmplayer.data.WatchedRecord
import com.tmplayer.data.WatchedWhen
import com.tmplayer.data.DayGroup
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.material3.MaterialTheme
import com.tmplayer.desktop.WatchedWords
import com.tmplayer.desktop.setWatched
import com.tmplayer.ui.browse.BrowseTab
import com.tmplayer.ui.components.ScrollToTop
import com.tmplayer.ui.components.FastScroller
import com.tmplayer.ui.components.rememberToast
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * History: Continue watching, Watch later and Previously watched as tabs of one page, since all
 * three are lists of single videos kept on this device. The tab is [ShellState.historyTab], the same choice the phone and the
 * TV remember.
 */
@Composable
fun HistoryPage(state: ShellState) {
    val tabs: @Composable () -> Unit = {
        val s = LocalStrings.current
        val sort = state.historySort
        ChoiceChips(
            // The lists' order first, as the Chats page has its own: Recent or A to Z.
            listOf(Choice("sort", sort.label, sort.icon, selected = false, description = s.browseSortBy(sort.label)) { state.historySort = sort.toggled }) +
                HistoryTab.entries.map { tab ->
                    Choice(tab.name, tab.label, tab.icon, selected = tab == state.historyTab) { state.historyTab = tab }
                },
        )
    }
    when (state.historyTab) {
        HistoryTab.Continue -> ContinuePage(state, tabs)
        HistoryTab.WatchLater -> WatchLaterPage(state, tabs)
        HistoryTab.Watched -> WatchedPage(state, tabs)
    }
}

/**
 * History's Watched tab: every video played to the end or marked by hand, most recently
 * finished first, as posters. Like Continue watching, the records keep no artwork, so each poster
 * is the play mark with the watched tick on it.
 */
@Composable
fun WatchedPage(state: ShellState, tabs: @Composable () -> Unit = {}) {
    val s = LocalStrings.current
    val all by state.watched.history.collectAsState(initial = null)
    // One card for the copies of one video, as Continue watching has.
    val records = remember(all, state.historySort) { all?.let(HomeRows::foldWatched)?.let { historyOrder(state, it) { record -> record.toMediaItem() } } }
    val progress by state.settings.watchProgress.collectAsState(initial = emptyMap())
    // The page's, not a tile's: unmarking takes the tile away, and with it any scope of its own.
    val scope = rememberCoroutineScope()
    // Remove from history, one card, the ticked ones or all of them: at once, with Undo.
    val toast = rememberToast()
    val undoToast = com.tmplayer.ui.components.rememberActionToast()
    val removeCards: (List<WatchedRecord>) -> Unit = { cards ->
        runHistoryEdit(scope, state, undoToast, toast, { s.browseRemovedFromHistory(it) }, s.browseUndo) { st, w ->
            com.tmplayer.data.HistoryEdits.removeWatchedCards(st, w, cards)
        }
    }
    val clearAll: () -> Unit = {
        runHistoryEdit(scope, state, undoToast, toast, { s.browseHistoryCleared }, s.browseUndo) { st, w -> com.tmplayer.data.HistoryEdits.clear(st, w) }
    }
    val gridOrder = remember(records) { records?.let { list -> daySections(list, System.currentTimeMillis()) { it.watchedAt }.sections.flatMap { it.second } }.orEmpty() }
    val ids = remember(gridOrder) { gridOrder.map { "${it.chatId}:${it.messageId}" } }
    val currentIds by rememberUpdatedState(ids)
    val selection = remember { GridSelection(history = true) { currentIds.getOrNull(it) } }
    LaunchedEffect(ids) { if (ids.isEmpty()) selection.clear() else selection.keepOnly(ids.toSet()) }
    com.tmplayer.ui.nav.BackHandler(enabled = selection.active) { selection.clear() }
    val removeTicked: () -> Unit = {
        val cards = gridOrder.filter { selection.isSelected("${it.chatId}:${it.messageId}") }
        selection.clear()
        if (cards.isNotEmpty()) removeCards(cards)
    }
    // "Watched 5 minutes ago" moves on while the page is open.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(CLOCK_TICK_MS)
            now = System.currentTimeMillis()
        }
    }
    Column(Modifier.fillMaxSize().historyKeys(selection, { currentIds }, removeTicked)) {
        PageHeader(
            BrowseTab.History.heading,
            HistoryTab.Watched.blurb,
            actions = {
                if (!records.isNullOrEmpty()) {
                    if (!selection.active) OutlinedButton(onClick = { selection.start() }) { Text(s.browseSelect) }
                    OutlinedButton(onClick = clearAll) { Text(s.browseClearHistory) }
                }
            },
        )
        tabs()
        val list = records
        when {
            list == null -> VideoGridSkeleton(state.posterWidth, posters = false)
            list.isEmpty() -> EmptyNote(HistoryTab.Watched.emptyIcon, s.watchedEmpty, s.browseGoToChats) { state.showChats(com.tmplayer.ui.browse.BrowseSection.of(com.tmplayer.ui.browse.BrowseTab.Chats)) }
            else -> {
                val grid = com.tmplayer.ui.components.rememberAheadGridState()
                val current by rememberUpdatedState(list)
                // Today, Yesterday and Earlier over the tiles, by when each was finished.
                val days = remember(list) { daySections(list, System.currentTimeMillis()) { it.watchedAt } }
                val currentDays by rememberUpdatedState(days)
                val nav = rememberKeyboardNav(remember(grid) { SectionedGridSurface(grid) { currentDays.gridIndex } })
                Box(Modifier.fillMaxSize()) {
                  androidx.compose.runtime.CompositionLocalProvider(LocalGridSelection provides selection) {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(state.posterWidth),
                        state = grid,
                        contentPadding = PaddingValues(start = pageMargin(), end = pageMargin() + 8.dp, top = 8.dp, bottom = 24.dp + com.tmplayer.ui.components.LocalBottomChrome.current),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                        modifier = Modifier.fillMaxSize().navKeys(nav) { current.size },
                    ) {
                        daySectionItems(days, key = { it.key }) { index, record, day ->
                            LaunchedEffect(record) { state.noteChatTitle(record.chatId, record.chatTitle) }
                            WatchedTile(state, record, progress[record.key]?.fraction, now, nav, index, day) {
                                // The card leaving this page is the answer, with Undo.
                                removeCards(listOf(record))
                            }
                        }
                    }
                  }
                    if (selection.active) {
                        HistorySelectionBar(selection, ids, removeTicked, Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp))
                    } else {
                        ScrollToTop(grid)
                    }
                    // The hold to scroll handle on a long list, the scrollbar on a short one.
                    FastScroller(grid, label = days.scrollLabels({ it.key }, { it.title }, state.historySort == com.tmplayer.ui.browse.ChatSort.Name)) {
                        VerticalScrollbar(rememberScrollbarAdapter(grid), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                    }
                }
            }
        }
    }
}

@Composable
private fun WatchedTile(
    state: ShellState,
    record: WatchedRecord,
    fraction: Float?,
    now: Long,
    nav: KeyboardNav?,
    index: Int,
    day: DayGroup?,
    onUnwatch: () -> Unit,
) {
    val s = LocalStrings.current
    // The video as Telegram describes it once it answers, for its thumbnail, as on Home.
    val item = rememberRecordItem(record.chatId, record.messageId, remember(record) { record.toMediaItem() })
    Poster(
        state = state,
        nav = nav,
        index = index,
        item = item,
        chatTitle = record.chatTitle,
        progress = WatchedWords.posterProgress(fraction, finished = true),
        art = { OnHome { OnlineArt(item, Modifier.fillMaxSize()) { RecordArt(item) } } },
        subtitle = listOf(record.chatTitle, WatchedWhen.phrase(record.watchedAt, now).let { phrase ->
            // Under a day heading the line does not say the heading's word again.
            val repeats = day == DayGroup.Yesterday || (day == DayGroup.Today && phrase == s.watchedTodayPhrase)
            if (repeats) "" else phrase
        })
            .filter { it.isNotBlank() }
            .joinToString("  ·  "),
        finished = true,
        markToggle = false,
        fileMenu = false,
        extraMenu = { close ->
            DropdownMenuItem(text = { Text(s.watchedPlayAgain) }, onClick = {
                close()
                state.openPlayer(item, startFromBeginning = true)
            })
            // On the Watched list the tick coming off is the card leaving History: one name for it.
            DropdownMenuItem(text = { Text(s.browseRemoveFromHistory) }, onClick = {
                close()
                onUnwatch()
            })
        },
    )
}

/**
 * History's Watch later tab: single videos put aside without starring their chats, most recently
 * added first. Finishing one, or marking it watched, takes it off (see [WatchedStore.markWatched]).
 */
@Composable
fun WatchLaterPage(state: ShellState, tabs: @Composable () -> Unit = {}) {
    val s = LocalStrings.current
    val saved by state.watched.watchLater.items.collectAsState(initial = null)
    val records = remember(saved, state.historySort) { saved?.let { historyOrder(state, it) { record -> record.toMediaItem() } } }
    val progress by state.settings.watchProgress.collectAsState(initial = emptyMap())
    // The page's, not a tile's: removing takes the tile away, and with it any scope of its own.
    val scope = rememberCoroutineScope()
    // Remove from Watch later, one card, the ticked ones or all of them: at once, with Undo.
    val toast = rememberToast()
    val undoToast = com.tmplayer.ui.components.rememberActionToast()
    val removeCards: (List<WatchLaterRecord>) -> Unit = { cards ->
        runHistoryEdit(scope, state, undoToast, toast, { s.browseRemovedFromWatchLater(it) }, s.browseUndo) { _, w ->
            com.tmplayer.data.HistoryEdits.removeLater(w, cards.map { it.key }, cards.size)
        }
    }
    val clearAll: () -> Unit = {
        runHistoryEdit(scope, state, undoToast, toast, { s.browseWatchLaterCleared }, s.browseUndo) { _, w -> com.tmplayer.data.HistoryEdits.clearLater(w) }
    }
    val gridOrder = records.orEmpty()
    val ids = remember(gridOrder) { gridOrder.map { "${it.chatId}:${it.messageId}" } }
    val currentIds by rememberUpdatedState(ids)
    val selection = remember { GridSelection(history = true) { currentIds.getOrNull(it) } }
    LaunchedEffect(ids) { if (ids.isEmpty()) selection.clear() else selection.keepOnly(ids.toSet()) }
    com.tmplayer.ui.nav.BackHandler(enabled = selection.active) { selection.clear() }
    val removeTicked: () -> Unit = {
        val cards = gridOrder.filter { selection.isSelected("${it.chatId}:${it.messageId}") }
        selection.clear()
        if (cards.isNotEmpty()) removeCards(cards)
    }
    Column(Modifier.fillMaxSize().historyKeys(selection, { currentIds }, removeTicked)) {
        PageHeader(
            BrowseTab.History.heading,
            HistoryTab.WatchLater.blurb,
            actions = {
                if (!records.isNullOrEmpty()) {
                    if (!selection.active) OutlinedButton(onClick = { selection.start() }) { Text(s.browseSelect) }
                    OutlinedButton(onClick = clearAll) { Text(s.browseClearWatchLater) }
                }
            },
        )
        tabs()
        val list = records
        when {
            list == null -> VideoGridSkeleton(state.posterWidth, posters = false)
            list.isEmpty() -> EmptyNote(HistoryTab.WatchLater.emptyIcon, s.browseEmptyWatchLater, s.browseGoToChats) { state.showChats(com.tmplayer.ui.browse.BrowseSection.of(com.tmplayer.ui.browse.BrowseTab.Chats)) }
            else -> {
                val grid = com.tmplayer.ui.components.rememberAheadGridState()
                val current by rememberUpdatedState(list)
                val nav = rememberKeyboardNav(remember(grid) { GridSurface(grid, { 0 }, { current.size }) })
                Box(Modifier.fillMaxSize()) {
                  androidx.compose.runtime.CompositionLocalProvider(LocalGridSelection provides selection) {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(state.posterWidth),
                        state = grid,
                        contentPadding = PaddingValues(start = pageMargin(), end = pageMargin() + 8.dp, top = 8.dp, bottom = 24.dp + com.tmplayer.ui.components.LocalBottomChrome.current),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                        modifier = Modifier.fillMaxSize().navKeys(nav) { current.size },
                    ) {
                        itemsIndexed(list, key = { _, it -> it.key }) { index, record ->
                            LaunchedEffect(record) { state.noteChatTitle(record.chatId, record.chatTitle) }
                            WatchLaterTile(state, record, progress[record.key]?.fraction, nav, index) {
                                // The card leaving this page is the answer, with Undo.
                                removeCards(listOf(record))
                            }
                        }
                    }
                  }
                    if (selection.active) {
                        HistorySelectionBar(selection, ids, removeTicked, Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp))
                    } else {
                        ScrollToTop(grid)
                    }
                    // The hold to scroll handle on a long list, the scrollbar on a short one.
                    val byKey = remember(list) { list.associateBy { it.key } }
                    val byName = state.historySort == com.tmplayer.ui.browse.ChatSort.Name
                    FastScroller(grid, label = { key -> if (byName) byKey[key]?.let { com.tmplayer.ui.browse.ScrollLabels.letter(it.title) } else null }) {
                        VerticalScrollbar(rememberScrollbarAdapter(grid), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                    }
                }
            }
        }
    }
}

@Composable
private fun WatchLaterTile(
    state: ShellState,
    record: WatchLaterRecord,
    fraction: Float?,
    nav: KeyboardNav?,
    index: Int,
    onRemove: () -> Unit,
) {
    val s = LocalStrings.current
    // The video as Telegram describes it once it answers, for its thumbnail, as on Home.
    val item = rememberRecordItem(record.chatId, record.messageId, remember(record) { record.toMediaItem() })
    Poster(
        state = state,
        nav = nav,
        index = index,
        item = item,
        chatTitle = record.chatTitle,
        progress = fraction,
        art = { OnHome { OnlineArt(item, Modifier.fillMaxSize()) { RecordArt(item) } } },
        subtitle = listOfNotNull(
            record.chatTitle.ifBlank { null },
            record.durationSec.takeIf { it > 0 }?.let(MediaMapper::formatDuration),
            record.sizeBytes.takeIf { it > 0 }?.let(MediaMapper::formatSize),
        ).joinToString("  ·  "),
        markToggle = false,
        // The record does not know whether its chat restricts saving, so no download line is offered.
        fileMenu = false,
        extraMenu = { close ->
            DropdownMenuItem(text = { Text(s.playerPlay) }, onClick = {
                close()
                state.openPlayer(item, startFromBeginning = false)
            })
            DropdownMenuItem(text = { Text(s.detailWatchLaterRemove) }, onClick = {
                close()
                onRemove()
            })
        },
    )
}

private const val CLOCK_TICK_MS = 60_000L

/** History's records under day headings, with the grid item that draws each cell. */
internal class DaySections<T>(val sections: List<Pair<DayGroup?, List<T>>>) {
    val gridIndex: IntArray = run {
        val out = ArrayList<Int>()
        var at = 0
        sections.forEach { (day, members) ->
            if (day != null) at++
            members.forEach { _ -> out += at++ }
        }
        out.toIntArray()
    }
}

/**
 * One of History's lists in the order picked over it: as kept (most recent first), or by title.
 * By title the day headings stand aside on their own ([DayGroup.headings] wants newest first).
 */
internal fun <T> historyOrder(state: ShellState, records: List<T>, item: (T) -> com.tmplayer.data.MediaItem): List<T> =
    if (state.historySort == com.tmplayer.ui.browse.ChatSort.Name) {
        com.tmplayer.data.VideoSorting.byName(records, { com.tmplayer.data.SeriesShelf.tileTitle(item(it)) })
    } else {
        records
    }

/** [records] under Today, Yesterday and Earlier, or in one run when headings would not help. */
internal fun <T> daySections(records: List<T>, now: Long, at: (T) -> Long): DaySections<T> =
    DaySections(DayGroup.headings(records, at, now) ?: listOf(null to records))

/**
 * What the fast scroller's bubble says over these sections: the letter when the list is by name,
 * otherwise the day heading the item on screen sits under (Today, Yesterday, Earlier).
 */
@Composable
internal fun <T> DaySections<T>.scrollLabels(key: (T) -> Any, title: (T) -> String, byName: Boolean): (Any) -> String? {
    val s = LocalStrings.current
    val labels = remember(this, byName, s) {
        buildMap<Any, String> {
            sections.forEach { (day, members) ->
                members.forEach { record ->
                    val said = when {
                        byName -> com.tmplayer.ui.browse.ScrollLabels.letter(title(record))
                        day == DayGroup.Today -> s.watchedToday
                        day == DayGroup.Yesterday -> s.watchedYesterday
                        day == DayGroup.Earlier -> s.watchedEarlier
                        else -> null
                    }
                    if (said != null) put(key(record), said)
                }
            }
        }
    }
    return { labels[it] }
}

/** The headings and the tiles under them, each tile told its place among the cells and its day. */
internal fun <T> LazyGridScope.daySectionItems(
    days: DaySections<T>,
    key: (T) -> Any,
    tile: @Composable (index: Int, record: T, day: DayGroup?) -> Unit,
) {
    var cell = 0
    days.sections.forEach { (day, members) ->
        if (day != null) {
            item(key = "day-$day", span = { GridItemSpan(maxLineSpan) }) { DayHeading(day) }
        }
        val base = cell
        cell += members.size
        itemsIndexed(members, key = { _, it -> key(it) }) { i, record -> tile(base + i, record, day) }
    }
}

@Composable
private fun DayHeading(day: DayGroup) {
    val s = LocalStrings.current
    Text(
        when (day) {
            DayGroup.Today -> s.watchedToday
            DayGroup.Yesterday -> s.watchedYesterday
            DayGroup.Earlier -> s.watchedEarlier
        },
        style = MaterialTheme.typography.titleSmall,
        color = Tone.muted,
        modifier = Modifier.padding(top = 8.dp),
    )
}
