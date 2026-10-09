package com.tmplayer.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.HomeRow
import com.tmplayer.data.MediaItem
import com.tmplayer.data.MediaMapper
import com.tmplayer.data.ResumeRecord
import com.tmplayer.data.Series
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.ShelfEntry
import com.tmplayer.ui.browse.BrowseSection
import com.tmplayer.ui.browse.BrowseTab
import com.tmplayer.ui.browse.HistoryTab
import com.tmplayer.ui.browse.ChatListViewModel
import com.tmplayer.ui.browse.HomeViewModel
import com.tmplayer.ui.browse.rememberHomeRows
import com.tmplayer.ui.components.MediaArt
import com.tmplayer.ui.components.TmIcons
import com.tmplayer.ui.components.UiState
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.flow.first

/**
 * Home on the desktop: the same rows as the phone and the television (see
 * [com.tmplayer.data.HomeRows]), each a horizontal strip of posters that loads when it scrolls
 * into view.
 */
@Composable
fun HomePage(state: ShellState, chats: ChatListViewModel) {
    val model = rememberViewModel(Unit) {
        HomeViewModel(sizeLimits = { state.settings.minSizeBytes.first() to state.settings.maxSizeBytes.first() })
    }
    val chatState by chats.state.collectAsState()
    val list = (chatState as? UiState.Content)?.value?.chats.orEmpty()
    val favourites by state.settings.favorites.collectAsState(initial = emptySet())
    val history by state.settings.continueWatching.collectAsState(initial = emptyList())
    val seriesView by state.settings.seriesView.collectAsState(initial = true)
    val art by model.art.collectAsState()
    HomeRowsView(
        state = state,
        rows = rememberHomeRows(model, list, favourites, history, seriesView),
        chats = list,
        art = art,
        onRowShown = { row ->
            when (row) {
                is HomeRow.Chat -> model.request(row.chatId)
                is HomeRow.Recent -> model.requestRecent()
                is HomeRow.Continue -> Unit
            }
        },
        onArtWanted = model::requestArt,
        onRefresh = model::refresh,
    )
}

/** Home's rows as drawn, given everything they are built from; the render test calls this directly. */
@Composable
internal fun HomeRowsView(
    state: ShellState,
    rows: List<HomeRow>,
    chats: List<ChatSummary>,
    art: Map<String, MediaItem>,
    onRowShown: (HomeRow) -> Unit,
    onArtWanted: (ResumeRecord) -> Unit,
    onRefresh: () -> Unit,
) {
    val s = LocalStrings.current
    var openSeries by remember { mutableStateOf<Series?>(null) }
    val shown = openSeries
    if (shown != null) {
        SeriesPage(state, shown, rememberSeriesWatch(state), onClose = { openSeries = null })
        return
    }
    val titles = remember(chats) { chats.associate { it.id to it.title } }
    LaunchedEffect(titles) { state.noteChatTitles(chats) }
    Column(Modifier.fillMaxSize()) {
        PageHeader(
            BrowseTab.Home.heading,
            BrowseTab.Home.blurb,
            actions = {
                IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, contentDescription = s.commonRefresh) }
                PosterSizeStep(state)
            },
        )
        if (rows.isEmpty()) {
            // Nothing played and nothing starred: a first run, so say how to get a first video in:
            // through Saved Messages, or with the default groups hidden, through a folder.
            val folders by com.tmplayer.data.Td.folders.collectAsState()
            val step = remember(state.hideDefaultGroups, folders) {
                com.tmplayer.ui.browse.DefaultGroups.firstStep(state.hideDefaultGroups, folders)
            }
            Centred {
                Column(
                    Modifier,
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(TmIcons.Bookmark, contentDescription = null, tint = Tone.accent, modifier = Modifier.size(48.dp))
                    Text(s.homeFirstTitle, style = MaterialTheme.typography.titleLarge, color = Tone.text, textAlign = TextAlign.Center)
                    Text(
                        step.body,
                        style = MaterialTheme.typography.bodyLarge,
                        color = Tone.muted,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.widthIn(max = 560.dp),
                    )
                    val target = step.target
                    val button = step.button
                    if (button != null && target != null) {
                        Button(onClick = { state.showChats(target) }) { Text(button) }
                    }
                    Text(s.homeFirstMore, style = MaterialTheme.typography.bodyMedium, color = Tone.muted, textAlign = TextAlign.Center)
                }
            }
            return@Column
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            items(rows, key = { it.key }, contentType = { it::class }) { row ->
                val loaded = when (row) {
                    is HomeRow.Continue -> true
                    is HomeRow.Chat -> row.loaded
                    is HomeRow.Recent -> row.loaded
                }
                LaunchedEffect(row.key, loaded) { if (!loaded) onRowShown(row) }
                val heading: Pair<String, (() -> Unit)?> = when (row) {
                    is HomeRow.Continue -> s.homeRowContinue to { state.showHistory(HistoryTab.Continue) }
                    is HomeRow.Chat -> row.title to { chats.firstOrNull { it.id == row.chatId }?.let(state::openChat); Unit }
                    is HomeRow.Recent -> s.homeRowRecent to null
                }
                // Only a row that leaves something out offers the rest of it.
                val title = heading.first
                val seeAll = heading.second?.takeIf { row.hasMore }
                Column {
                    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        if (seeAll != null) {
                            TextButton(onClick = seeAll) {
                                Text(s.homeSeeAll)
                                Text(
                                    "  ·  " + if (row.totalAtLeast) s.homeSeeAllCountAtLeast(row.total) else s.homeSeeAllCount(row.total),
                                    color = Tone.muted,
                                )
                            }
                        }
                    }
                    if (!loaded) {
                        Row(Modifier.padding(start = 24.dp, top = 8.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            // A poster's art and its caption in the same type with nothing in it, so the
                            // rows below stay put when this one fills.
                            repeat(4) {
                                Column(Modifier.width(state.posterWidth), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Box(
                                        Modifier.fillMaxWidth()
                                            .aspectRatio(16f / 9f)
                                            .background(Tone.surface, MaterialTheme.shapes.medium),
                                    )
                                    Text(" ", style = MaterialTheme.typography.titleSmall, minLines = 2, maxLines = 2)
                                    Text(" ", style = MaterialTheme.typography.bodySmall, maxLines = 1)
                                }
                            }
                        }
                        return@Column
                    }
                    LazyRow(
                        contentPadding = PaddingValues(start = 24.dp, end = 32.dp, top = 8.dp, bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        when (row) {
                            is HomeRow.Continue -> items(row.records, key = { "r-${it.chatId}_${it.messageId}" }) { record ->
                                val key = SettingsStore.progressKey(record.chatId, record.messageId)
                                LaunchedEffect(key) { onArtWanted(record) }
                                LaunchedEffect(key) { state.noteChatTitle(record.chatId, record.chatTitle) }
                                val item = art[key] ?: remember(record) { record.toMediaItem() }
                                Box(Modifier.width(state.posterWidth)) {
                                    Poster(
                                        state = state,
                                        nav = null,
                                        index = 0,
                                        item = item,
                                        chatTitle = record.chatTitle,
                                        progress = record.fraction,
                                        art = {
                                            OnHome { com.tmplayer.ui.online.OnlineArt(item, Modifier.fillMaxSize()) {
                                                MediaArt(item.miniThumbnail, item.thumbnailFileId, Modifier.fillMaxSize()) {
                                                    Text(item.title.take(1).uppercase(), style = MaterialTheme.typography.headlineSmall, color = Tone.muted)
                                                }
                                            } }
                                        },
                                        subtitle = listOf(
                                            record.chatTitle,
                                            s.browseTimeLeft(MediaMapper.formatDuration((record.remainingMs / 1000).toInt())),
                                        ).filter { it.isNotBlank() }.joinToString("  ·  "),
                                    )
                                }
                            }
                            is HomeRow.Chat -> shelf(state, row.entries, { row.title }) { openSeries = it }
                            is HomeRow.Recent -> shelf(state, row.entries, { titles[it].orEmpty() }) { openSeries = it }
                        }
                    }
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.shelf(
    state: ShellState,
    entries: List<ShelfEntry>,
    chatTitle: (Long) -> String,
    onOpenSeries: (Series) -> Unit,
) {
    items(entries, key = { it.key }, contentType = { it::class }) { entry ->
        Box(Modifier.width(state.posterWidth)) {
            when (entry) {
                is ShelfEntry.File -> OnHome { MediaTile(state, entry.item, chatTitle(entry.item.chatId), outsideChat = true) }
                is ShelfEntry.Show -> SeriesPoster(entry.series, rememberSeriesWatch(state), onOpen = { onOpenSeries(entry.series) })
            }
        }
    }
}

/** Home's tiles may wear the show's or the film's picture (see LocalOnlineArt); a chat's grid keeps frames. */
@Composable
private fun OnHome(content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(com.tmplayer.ui.online.LocalOnlineArt provides true, content = content)
}
