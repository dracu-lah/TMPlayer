package com.tmplayer.desktop.ui

import com.tmplayer.ui.browse.HistoryTab
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
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
import com.tmplayer.data.WatchedRecord
import com.tmplayer.data.WatchedWhen
import com.tmplayer.desktop.WatchedWords
import com.tmplayer.desktop.setWatched
import com.tmplayer.ui.browse.BrowseTab
import com.tmplayer.ui.components.rememberToast
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * History: Continue watching and Previously watched as two tabs of one page, since both are lists
 * of videos already played. The tab is [ShellState.historyTab], the same choice the phone and the
 * TV remember.
 */
@Composable
fun HistoryPage(state: ShellState) {
    val tabs: @Composable () -> Unit = {
        ChoiceChips(
            HistoryTab.entries.map { tab ->
                Choice(tab.name, tab.label, tab.icon, selected = tab == state.historyTab) { state.historyTab = tab }
            },
        )
    }
    when (state.historyTab) {
        HistoryTab.Continue -> ContinuePage(state, tabs)
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
    val records by state.watched.history.collectAsState(initial = null)
    val progress by state.settings.watchProgress.collectAsState(initial = emptyMap())
    var confirmClear by remember { mutableStateOf(false) }
    // The page's, not a tile's: unmarking takes the tile away, and with it any scope of its own.
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    // "Watched 5 minutes ago" moves on while the page is open.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(CLOCK_TICK_MS)
            now = System.currentTimeMillis()
        }
    }
    Column(Modifier.fillMaxSize()) {
        PageHeader(
            BrowseTab.History.heading,
            HistoryTab.Watched.blurb,
            actions = {
                if (!records.isNullOrEmpty()) {
                    OutlinedButton(onClick = { confirmClear = true }) { Text(s.watchedClear) }
                }
                PosterSizeStep(state)
            },
        )
        tabs()
        val list = records
        when {
            list == null -> Centred { CircularProgressIndicator() }
            list.isEmpty() -> Centred {
                Text(s.watchedEmpty, color = Tone.muted)
            }
            else -> {
                val grid = rememberLazyGridState()
                val current by rememberUpdatedState(list)
                val nav = rememberKeyboardNav(remember(grid) { GridSurface(grid, { 0 }, { current.size }) })
                Box(Modifier.fillMaxSize()) {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(state.posterWidth),
                        state = grid,
                        contentPadding = PaddingValues(start = 24.dp, end = 32.dp, top = 8.dp, bottom = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                        modifier = Modifier.fillMaxSize().navKeys(nav) { current.size },
                    ) {
                        itemsIndexed(list, key = { _, it -> it.key }) { index, record ->
                            LaunchedEffect(record) { state.noteChatTitle(record.chatId, record.chatTitle) }
                            WatchedTile(state, record, progress[record.key]?.fraction, now, nav, index) {
                                val item = record.toMediaItem()
                                toast(s.watchedMarkedUnwatched(item.title))
                                scope.launch {
                                    runCatching { setWatched(state.watched, state.settings, item, record.chatTitle, watched = false) }
                                }
                            }
                        }
                    }
                    VerticalScrollbar(rememberScrollbarAdapter(grid), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                }
            }
        }
    }
    if (confirmClear) {
        ClearWatchedDialog(state, records?.size ?: 0, scope, onDismiss = { confirmClear = false })
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
    onUnwatch: () -> Unit,
) {
    val s = LocalStrings.current
    val item = remember(record) { record.toMediaItem() }
    Poster(
        state = state,
        nav = nav,
        index = index,
        item = item,
        chatTitle = record.chatTitle,
        progress = WatchedWords.posterProgress(fraction, finished = true),
        art = {
            Box(Modifier.fillMaxSize().background(Tone.surfaceHigh), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Tone.accent, modifier = Modifier.size(40.dp))
            }
        },
        subtitle = listOf(record.chatTitle, WatchedWhen.phrase(record.watchedAt, now))
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
            DropdownMenuItem(text = { Text(WatchedWords.markLabel(onList = true)) }, onClick = {
                close()
                onUnwatch()
            })
        },
    )
}

/** "Clear the watched list?", from the page and from Settings alike. */
@Composable
internal fun ClearWatchedDialog(state: ShellState, count: Int, scope: CoroutineScope, onDismiss: () -> Unit) {
    val s = LocalStrings.current
    val toast = rememberToast()
    ConfirmDialog(
        title = s.watchedClearTitle,
        message = s.watchedClearMessage,
        detail = s.watchedClearDetail,
        confirmLabel = s.commonClear,
        onConfirm = {
            onDismiss()
            scope.launch {
                runCatching { state.watched.clear() }
                toast(s.watchedCleared(count))
            }
        },
        onDismiss = onDismiss,
    )
}

private const val CLOCK_TICK_MS = 60_000L
