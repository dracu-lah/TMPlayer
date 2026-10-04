package com.tmplayer.desktop.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.border
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed as keyCtrl
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.MediaItem
import com.tmplayer.data.MediaMapper
import com.tmplayer.data.OfflineDownloads
import com.tmplayer.data.ResumeRecord
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.SponsoredItem
import com.tmplayer.data.SponsoredReportOption
import com.tmplayer.data.SponsoredReportOutcome
import com.tmplayer.data.Td
import com.tmplayer.data.valueOrNull
import com.tmplayer.data.WatchPoint
import com.tmplayer.desktop.WatchedWords
import com.tmplayer.desktop.setWatched
import com.tmplayer.ui.components.WatchedBadge
import com.tmplayer.player.StreamStats
import com.tmplayer.ui.browse.MediaListViewModel
import com.tmplayer.ui.components.MediaArt
import com.tmplayer.ui.components.TmIcons
import com.tmplayer.ui.components.UiState
import com.tmplayer.ui.components.rememberToast
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import com.tmplayer.desktop.os.OpenExternal
import com.tmplayer.desktop.DownloadIndex
import com.tmplayer.ui.nav.BackHandler
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import java.io.File
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

/**
 * One chat's videos as a grid of posters: the shared [MediaListViewModel] (paging, search, size
 * limits, sponsored messages), [GridCells.Adaptive] on the poster width the toolbar steps, a
 * scrollbar, and the next page fetched as the end comes into view.
 */
@Composable
fun MediaGridPage(state: ShellState, chat: ChatSummary) {
    val minSize by state.settings.minSizeBytes.collectAsState(initial = null)
    val maxSize by state.settings.maxSizeBytes.collectAsState(initial = null)
    val min = minSize
    val max = maxSize
    Column(Modifier.fillMaxSize()) {
        if (min == null || max == null) {
            Centred { CircularProgressIndicator() }
            return@Column
        }
        val model = rememberViewModel(Triple(chat.id, min, max)) { MediaListViewModel(chat.id, min, max) { DownloadIndex.presentIds(state.settings) } }
        val ui by model.state.collectAsState()
        var query by remember(chat.id) { mutableStateOf("") }
        // The grid's keyboard focus, once the grid exists; the search field's Down arrow enters it.
        var gridNav by remember(chat.id) { mutableStateOf<KeyboardNav?>(null) }
        LaunchedEffect(model, query) {
            delay(SEARCH_SETTLE_MS)
            model.search(query.trim())
        }
        LaunchedEffect(chat.id) { state.settings.rememberChatOpened(chat.id) }

        PageHeader(
            title = chat.title,
            subtitle = (ui as? UiState.Content)?.value?.items?.size?.let { if (it == 1) "1 video" else "$it videos" },
            leading = {
                IconButton(onClick = { state.closeChat() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to chats")
                }
                ChatAvatar(chat.miniThumbnail, chat.photoFileId, chat.title, 40.dp)
            },
            actions = {
                SearchField(query, { query = it }, "Search this chat", state.searchFocus, Modifier.width(300.dp), onDown = { gridNav?.focus(0) })
                PosterSizeStep(state)
                IconButton(onClick = { model.load() }) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") }
            },
        )
        StateBox(ui, onRetry = { model.load() }) { content ->
            val grid = rememberLazyGridState()
            LoadMoreNearEnd(grid, enabled = !content.endReached && !content.loadingMore) { model.loadMore() }
            val ad = content.sponsored?.messages?.firstOrNull()
            VideoGrid(
                state = state,
                items = content.items,
                chatTitle = chat.title,
                grid = grid,
                onNav = { gridNav = it },
                header = ad?.let { { SponsoredCard(it, model) } },
                loadingMore = content.loadingMore,
                // Search ignores the size limits, so the note would be wrong while one is typed.
                hiddenBySize = if (query.isBlank()) content.hiddenBySize else 0,
            )
        }
    }
}

/**
 * A chat's posters in an adaptive grid with a scrollbar, the arrow keys of [KeyboardNav] over
 * them, an optional full width [header] (the sponsored message) and a spinner row while the next
 * page loads. Split from [MediaGridPage] so the UI tests can drive it without TDLib.
 */
@Composable
internal fun VideoGrid(
    state: ShellState,
    items: List<MediaItem>,
    chatTitle: String,
    grid: LazyGridState = rememberLazyGridState(),
    onNav: (KeyboardNav) -> Unit = {},
    header: (@Composable () -> Unit)? = null,
    loadingMore: Boolean = false,
    /** Videos the size limits kept out; above zero, a quiet line over the posters says so. */
    hiddenBySize: Int = 0,
) {
    val cells by rememberUpdatedState(items)
    val note = WatchedWords.sizeLimitNote(hiddenBySize)
    val headerItems by rememberUpdatedState((if (header != null) 1 else 0) + (if (note != null) 1 else 0))
    val nav = rememberKeyboardNav(remember(grid) { GridSurface(grid, { headerItems }, { cells.size }) })
    LaunchedEffect(nav) { onNav(nav) }
    val history by state.settings.downloadHistory.collectAsState(initial = emptyList())
    val index = remember(history) { history.associateBy { "${it.chatId}:${it.messageId}" } }
    val selection = remember(grid) { GridSelection { cells.getOrNull(it)?.id } }
    BackHandler(enabled = selection.active) { selection.clear() }
    Box(Modifier.fillMaxSize()) {
      CompositionLocalProvider(LocalGridSelection provides selection, LocalDownloadIndex provides index) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(state.posterWidth),
            state = grid,
            contentPadding = PaddingValues(start = 24.dp, end = 32.dp, top = 8.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.fillMaxSize().navKeys(nav) { cells.size },
        ) {
            if (header != null) {
                item(key = "sponsored", span = { GridItemSpan(maxLineSpan) }) { header() }
            }
            if (note != null) {
                item(key = "hidden-by-size", span = { GridItemSpan(maxLineSpan) }) {
                    SizeLimitNote(note, onChange = { state.openSizeLimits() })
                }
            }
            itemsIndexed(items, key = { _, it -> it.id }) { index, item ->
                MediaTile(state, item, chatTitle, nav, index)
            }
            if (loadingMore) {
                item(key = "more", span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(28.dp))
                    }
                }
            }
        }
      }
        VerticalScrollbar(
            rememberScrollbarAdapter(grid),
            Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 4.dp),
        )
        if (selection.active) {
            SelectionBar(state, selection, items, chatTitle, index, Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp))
        }
    }
}

/**
 * The line over a chat's posters when the size limits kept some of its videos out, so a missing
 * episode is explained rather than looking lost. Quiet on purpose: muted text and a text button.
 */
@Composable
private fun SizeLimitNote(text: String, onChange: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = Tone.muted)
        TextButton(onClick = onChange) { Text("Change", style = MaterialTheme.typography.bodySmall) }
    }
}

/** Calls [loadMore] once the last few posters are on screen. */
@Composable
private fun LoadMoreNearEnd(grid: LazyGridState, enabled: Boolean, loadMore: () -> Unit) {
    LaunchedEffect(grid, enabled) {
        if (!enabled) return@LaunchedEffect
        snapshotFlow {
            val info = grid.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - LOAD_AHEAD
        }.distinctUntilChanged().collect { near -> if (near) loadMore() }
    }
}

/** The toolbar's poster size step: smaller and larger, through [POSTER_STEPS]. */
@Composable
fun PosterSizeStep(state: ShellState) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { state.stepPoster(-1) }, enabled = state.posterWidth != POSTER_STEPS.first()) {
            Icon(TmIcons.Grid, contentDescription = "Smaller posters")
        }
        IconButton(onClick = { state.stepPoster(1) }, enabled = state.posterWidth != POSTER_STEPS.last()) {
            Icon(Icons.Filled.Add, contentDescription = "Larger posters")
        }
    }
}

/**
 * The page Continue watching: what the viewer was part way through, newest first, as posters.
 * The records keep no artwork (see the phone's ContinueArt), so each poster is the play mark.
 */
@Composable
fun ContinuePage(state: ShellState) {
    val records by state.settings.continueWatching.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    Column(Modifier.fillMaxSize()) {
        PageHeader("Continue", "Pick up where you left off", actions = { PosterSizeStep(state) })
        val list = records
        when {
            list == null -> Centred { CircularProgressIndicator() }
            list.isEmpty() -> Centred { Text("Nothing part-watched yet. Videos you stop half way wait here.", color = Tone.muted) }
            else -> {
                val grid = rememberLazyGridState()
                val records by rememberUpdatedState(list)
                val nav = rememberKeyboardNav(remember(grid) { GridSurface(grid, { 0 }, { records.size }) })
                Box(Modifier.fillMaxSize()) {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(state.posterWidth),
                        state = grid,
                        contentPadding = PaddingValues(start = 24.dp, end = 32.dp, top = 8.dp, bottom = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                        modifier = Modifier.fillMaxSize().navKeys(nav) { records.size },
                    ) {
                        itemsIndexed(list, key = { _, it -> "${it.chatId}:${it.messageId}" }) { index, record ->
                            LaunchedEffect(record) { state.noteChatTitle(record.chatId, record.chatTitle) }
                            ContinueTile(
                                state, record, nav, index,
                                onMarkWatched = {
                                    // On the page's scope: marking takes the card off this page.
                                    val item = record.toMediaItem()
                                    toast("${item.title} marked as watched")
                                    scope.launch {
                                        runCatching { setWatched(state.watched, state.settings, item, record.chatTitle, watched = true) }
                                    }
                                },
                            ) {
                                scope.launch { state.settings.clearResumePosition(record.chatId, record.messageId) }
                            }
                        }
                    }
                    VerticalScrollbar(rememberScrollbarAdapter(grid), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                }
            }
        }
    }
}

@Composable
private fun ContinueTile(
    state: ShellState,
    record: ResumeRecord,
    nav: KeyboardNav?,
    index: Int,
    onMarkWatched: () -> Unit,
    onForget: () -> Unit,
) {
    val item = remember(record) { record.toMediaItem() }
    Poster(
        state = state,
        nav = nav,
        index = index,
        item = item,
        chatTitle = record.chatTitle,
        progress = record.fraction,
        art = {
            Box(Modifier.fillMaxSize().background(Tone.surfaceHigh), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Tone.accent, modifier = Modifier.size(40.dp))
            }
        },
        subtitle = "${record.chatTitle}  ·  ${MediaMapper.formatDuration((record.remainingMs / 1000).toInt())} left",
        extraMenu = { close ->
            // Marking also forgets the position, so the card leaves this page with it.
            DropdownMenuItem(text = { Text(WatchedWords.markLabel(onList = false)) }, onClick = {
                close()
                onMarkWatched()
            })
            DropdownMenuItem(text = { Text("Remove from Continue watching") }, onClick = { close(); onForget() })
        },
        markToggle = false,
    )
}

/** A video in a chat's grid. */
@Composable
internal fun MediaTile(state: ShellState, item: MediaItem, chatTitle: String, nav: KeyboardNav? = null, index: Int = 0) {
    val progress by state.settings.watchProgress.collectAsState(initial = emptyMap())
    val key = SettingsStore.progressKey(item.chatId, item.messageId)
    val point: WatchPoint? = progress[key]
    val watched by state.watched.watched.collectAsState(initial = emptyMap())
    val finished = key in watched
    Poster(
        state = state,
        nav = nav,
        index = index,
        item = item,
        chatTitle = chatTitle,
        progress = WatchedWords.posterProgress(point?.fraction, finished),
        finished = finished,
        art = {
            MediaArt(item.miniThumbnail, item.thumbnailFileId, Modifier.fillMaxSize()) {
                Text(item.title.take(1).uppercase(), style = MaterialTheme.typography.headlineSmall, color = Tone.muted)
            }
        },
        subtitle = buildString {
            if (item.durationSec > 0) append(MediaMapper.formatDuration(item.durationSec)).append("  ·  ")
            append(MediaMapper.formatSize(item.sizeBytes))
            item.qualityTags.firstOrNull()?.let { append("  ·  ").append(it) }
            if (WatchedWords.showsWatched(point?.fraction, finished)) append("  ·  Watched")
        },
    )
}

/**
 * The poster every grid draws: art at 16:9, a progress line, a title under it. Hovering for a
 * moment lifts it and shows Play and the overflow; a right click (Ctrl+click on macOS) opens the
 * same overflow where the pointer is.
 *
 * From the keyboard ([nav], B2.1): focus draws a ring and lifts it like a hover, the arrows move
 * between posters, Enter, Space or P plays, and the context menu key or Shift+F10 opens the
 * overflow.
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
internal fun Poster(
    state: ShellState,
    nav: KeyboardNav?,
    index: Int,
    item: MediaItem,
    chatTitle: String,
    progress: Float?,
    art: @Composable () -> Unit,
    subtitle: String,
    extraMenu: (@Composable (close: () -> Unit) -> Unit)? = null,
    /** On the Watched list: a tick in the bottom corner of the art. */
    finished: Boolean = false,
    /** Whether the menu offers "Mark as watched" or "Mark as unwatched" by [finished]. */
    markToggle: Boolean = true,
    /** The menu's play, download and link lines; off for a page whose [extraMenu] is the menu. */
    fileMenu: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var lifted by remember { mutableStateOf(false) }
    // Hover intent: a pointer passing over on its way somewhere else does not lift every poster.
    LaunchedEffect(hovered) {
        if (hovered) {
            delay(HOVER_INTENT_MS)
            lifted = true
        } else {
            lifted = false
        }
    }
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (lifted || focused) 1.05f else 1f)
    var menu by remember { mutableStateOf(false) }
    val requester = remember { FocusRequester() }
    val selection = LocalGridSelection.current
    // Set by a Ctrl or Shift press just before the click it belongs to, so that click picks the
    // poster instead of playing it.
    var picking by remember { mutableStateOf(false) }
    val play = {
        if (picking && selection != null) {
            picking = false
        } else {
            state.openPlayer(item, startFromBeginning = false)
        }
    }
    val downloads by OfflineDownloads.active.collectAsState()
    val download = downloads[item.fileId]
    val record = LocalDownloadIndex.current[item.id]
    val selected = selection?.isSelected(item.id) == true

    Column(
        Modifier
            .hoverable(interaction)
            .onPointerEvent(PointerEventType.Press) { event ->
                val macContext = IS_MAC && event.keyboardModifiers.isCtrlPressed && event.buttons.isPrimaryPressed
                if (event.buttons.isSecondaryPressed || macContext) menu = true
            }
            .onPointerEvent(PointerEventType.Press, PointerEventPass.Initial) { event ->
                if (selection == null || !event.buttons.isPrimaryPressed) return@onPointerEvent
                val mods = event.keyboardModifiers
                val toggle = if (IS_MAC) mods.isMetaPressed else mods.isCtrlPressed
                when {
                    mods.isShiftPressed -> {
                        picking = true
                        selection.extend(index)
                    }
                    toggle -> {
                        picking = true
                        selection.toggle(index)
                    }
                    else -> picking = false
                }
            }
            .then(if (nav != null) Modifier.navCell(nav, index, requester) else Modifier.focusRequester(requester))
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event ->
                when {
                    GridNav.isMenuKey(event) -> {
                        menu = true
                        true
                    }
                    event.type == KeyEventType.KeyDown && event.key in PLAY_KEYS && !event.keyCtrl &&
                        !event.isAltPressed && !event.isMetaPressed && !event.isShiftPressed -> {
                        play()
                        true
                    }
                    else -> false
                }
            }
            .clickable(onClick = play),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .clip(MaterialTheme.shapes.medium)
                .then(if (focused || selected) Modifier.border(3.dp, Tone.accent, MaterialTheme.shapes.medium) else Modifier),
        ) {
            art()
            if (progress != null && progress > 0f) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp),
                    color = Tone.accent,
                    trackColor = Color.Black.copy(alpha = 0.4f),
                )
            }
            if (finished) {
                // The one corner no other badge uses, clear of the bar along the bottom edge.
                WatchedBadge(Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 9.dp), size = 24.dp)
            }
            when {
                selected -> Badge(Icons.Filled.CheckCircle, "Selected", Modifier.align(Alignment.TopStart))
                record != null -> Badge(Icons.Filled.CheckCircle, "Downloaded", Modifier.align(Alignment.TopStart))
                download != null && download.busy -> Badge(TmIcons.Download, "Downloading", Modifier.align(Alignment.TopStart))
                item.onDevice -> Badge(TmIcons.Download, "Cached", Modifier.align(Alignment.TopStart))
            }
            if (lifted || menu || focused) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f)))
                Surface(
                    shape = CircleShape,
                    color = Tone.accent,
                    modifier = Modifier.align(Alignment.Center).size(48.dp)
                        .clickable { state.openPlayer(item, startFromBeginning = false) },
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "Play", tint = Tone.onAccent, modifier = Modifier.padding(10.dp))
                }
                Box(Modifier.align(Alignment.TopEnd)) {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More", tint = Color.White)
                    }
                }
            }
            Box(Modifier.align(Alignment.TopEnd)) {
                TileMenu(state, item, chatTitle, expanded = menu, onDismiss = {
                    menu = false
                    // Back to the poster, so the arrows carry on from where the menu was opened.
                    if (focused || nav?.current == index) runCatching { requester.requestFocus() }
                }, extra = extraMenu, watchedToggle = if (markToggle) finished else null, fileMenu = fileMenu)
            }
        }
        Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Tone.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A word on the poster's corner: Downloaded, Cached, Downloading or Selected. */
@Composable
private fun Badge(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier) {
    Row(
        modifier.padding(6.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.6f))
            .padding(start = 4.dp, end = 8.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.White)
    }
}

/**
 * Play, Play from start, the one download entry for where the video is (Download when it is on
 * Telegram only, Save to Downloads when it is cached, Downloading while it is queued, In Downloads
 * and Remove from Downloads once it is kept), Open in another app for a whole file, Copy link.
 */
@Composable
private fun TileMenu(
    state: ShellState,
    item: MediaItem,
    chatTitle: String,
    expanded: Boolean,
    onDismiss: () -> Unit,
    extra: (@Composable (close: () -> Unit) -> Unit)?,
    /** Null leaves the mark line out; otherwise whether the video is on the Watched list. */
    watchedToggle: Boolean? = null,
    fileMenu: Boolean = true,
) {
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    val downloads by OfflineDownloads.active.collectAsState()
    val record = LocalDownloadIndex.current[item.id]
    val row = downloads[item.fileId]
    val title = chatTitle.ifBlank { state.chatTitleOf(item.chatId) }

    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
      if (!fileMenu) {
        extra?.invoke(onDismiss)
      } else {
        DropdownMenuItem(text = { Text("Play") }, onClick = { onDismiss(); state.openPlayer(item, startFromBeginning = false) })
        DropdownMenuItem(text = { Text("Play from start") }, onClick = { onDismiss(); state.openPlayer(item, startFromBeginning = true) })
        // Beside the play lines, because it is about watching rather than about the file. Marking
        // also forgets the saved position, so the video leaves Continue watching with it.
        if (watchedToggle != null) {
            DropdownMenuItem(text = { Text(WatchedWords.markLabel(watchedToggle)) }, onClick = {
                onDismiss()
                val mark = !watchedToggle
                scope.launch {
                    runCatching { setWatched(state.watched, state.settings, item, title, mark) }
                    toast(if (mark) "${item.title} marked as watched" else "${item.title} marked as unwatched")
                }
            })
        }
        when {
            record != null -> {
                DropdownMenuItem(text = { Text("In Downloads") }, onClick = { onDismiss(); state.go(Destination.Downloads) })
                DropdownMenuItem(text = { Text("Remove from Downloads") }, onClick = {
                    onDismiss()
                    scope.launch {
                        if (DownloadIndex.delete(state.settings, record)) {
                            toast("${item.title} removed from Downloads")
                        } else {
                            toast("The file is in use. Close whatever has it open and try again")
                        }
                    }
                })
            }
            row != null && row.busy -> {
                DropdownMenuItem(text = { Text("Downloading…") }, onClick = { onDismiss(); state.go(Destination.Downloads) })
                DropdownMenuItem(text = { Text("Cancel download") }, onClick = {
                    onDismiss()
                    OfflineDownloads.cancel(state.downloads, item.fileId)
                })
            }
            item.onDevice -> DropdownMenuItem(text = { Text("Save to Downloads") }, onClick = {
                onDismiss()
                OfflineDownloads.start(state.downloads, item, title)
                toast("Saving ${item.title} to Downloads")
            })
            else -> DropdownMenuItem(text = { Text("Download") }, onClick = {
                onDismiss()
                OfflineDownloads.start(state.downloads, item, title)
                toast("Downloading ${item.title}")
            })
        }
        if (record != null || item.onDevice) {
            DropdownMenuItem(text = { Text("Open in another app") }, onClick = {
                onDismiss()
                scope.launch {
                    val file = record?.localPath?.let(::File)?.takeIf { it.isFile }
                        ?: runCatching { Td.localFilePath(Td.currentFileId(item.chatId, item.messageId, item.fileId)) }
                            .getOrNull()?.let(::File)
                    if (file == null) toast("The file is not here any more") else OpenExternal.open(file)
                }
            })
        }
        DropdownMenuItem(text = { Text("Copy link") }, onClick = {
            onDismiss()
            scope.launch {
                val link = runCatching {
                    Td.client.getMessageLink(item.chatId, item.messageId, 0, 0, "", false, false).valueOrNull?.link
                }.getOrNull()
                if (link.isNullOrBlank()) {
                    toast("This chat has no links to its messages")
                } else {
                    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(link), null)
                    toast("Link copied")
                }
            }
        })
        extra?.invoke(onDismiss)
      }
    }
}

/** Telegram's sponsored message for a channel, shown above its videos as the phone shows it. */
@Composable
private fun SponsoredCard(ad: SponsoredItem, model: MediaListViewModel) {
    val toast = rememberToast()
    var reportOptions by remember(ad.messageId) { mutableStateOf<Pair<String, List<SponsoredReportOption>>?>(null) }

    fun report(item: SponsoredItem, optionId: ByteArray) {
        model.reportSponsored(
            item = item,
            optionId = optionId,
            onResult = { outcome ->
                when (outcome) {
                    is SponsoredReportOutcome.Options -> reportOptions = outcome.title to outcome.options
                    SponsoredReportOutcome.Reported -> toast("Sponsored message reported.")
                    SponsoredReportOutcome.AdsHidden -> toast("Sponsored messages hidden by Telegram.")
                    SponsoredReportOutcome.PremiumRequired -> toast("Telegram Premium is required to hide sponsored messages.")
                    SponsoredReportOutcome.Unavailable -> toast("This sponsored message can no longer be reported.")
                }
            },
            onFailure = toast,
        )
    }

    LaunchedEffect(ad.messageId) { model.markSponsoredViewed(ad.messageId) }
    Surface(shape = MaterialTheme.shapes.large, color = Tone.surface, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (ad.miniThumbnail != null || ad.thumbnailFileId > 0) {
                MediaArt(ad.miniThumbnail, ad.thumbnailFileId, Modifier.size(56.dp).clip(MaterialTheme.shapes.small)) {}
            }
            Column(Modifier.weight(1f)) {
                Text(ad.label.ifBlank { "Sponsored" }, style = MaterialTheme.typography.labelMedium, color = Tone.muted)
                Text(ad.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(ad.text, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (ad.sponsorUrl.isNotBlank()) {
                OutlinedButton(onClick = {
                    model.clickSponsored(ad, media = false, onSuccess = {
                        OpenExternal.browse(ad.sponsorUrl)
                    }, onFailure = toast)
                }) { Text(ad.buttonText.ifBlank { "Open" }) }
            }
            if (ad.canBeReported) {
                TextButton(onClick = { report(ad, byteArrayOf()) }) { Text("Report", color = Tone.muted) }
            }
        }
    }

    // Telegram answers a first report with the reasons it accepts; the chosen one is sent back
    // the same way, and may itself be answered with a narrower list.
    reportOptions?.let { (title, options) ->
        AlertDialog(
            onDismissRequest = { reportOptions = null },
            title = { Text(title.ifBlank { "Why are you reporting this?" }) },
            text = {
                Column {
                    options.forEach { option ->
                        TextButton(
                            onClick = {
                                reportOptions = null
                                report(ad, option.id)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(option.text, modifier = Modifier.fillMaxWidth()) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { reportOptions = null }) { Text("Cancel") } },
        )
    }
}

private val PLAY_KEYS = setOf(Key.Enter, Key.NumPadEnter, Key.Spacebar, Key.P)
private val IS_MAC = System.getProperty("os.name").orEmpty().lowercase().contains("mac")
private const val HOVER_INTENT_MS = 300L
private const val SEARCH_SETTLE_MS = 300L
private const val LOAD_AHEAD = 8
