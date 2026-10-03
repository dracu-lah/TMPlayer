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
import com.tmplayer.data.Td
import com.tmplayer.data.valueOrNull
import com.tmplayer.data.WatchPoint
import com.tmplayer.player.StreamStats
import com.tmplayer.ui.browse.MediaListViewModel
import com.tmplayer.ui.components.MediaArt
import com.tmplayer.ui.components.TmIcons
import com.tmplayer.ui.components.UiState
import com.tmplayer.ui.components.rememberToast
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import com.tmplayer.desktop.os.OpenExternal
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
        val model = rememberViewModel(Triple(chat.id, min, max)) { MediaListViewModel(chat.id, min, max) }
        val ui by model.state.collectAsState()
        var query by remember(chat.id) { mutableStateOf("") }
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
                SearchField(query, { query = it }, "Search this chat", state.searchFocus, Modifier.width(300.dp))
                PosterSizeStep(state)
                IconButton(onClick = { model.load() }) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") }
            },
        )
        StateBox(ui, onRetry = { model.load() }) { content ->
            val grid = rememberLazyGridState()
            LoadMoreNearEnd(grid, enabled = !content.endReached && !content.loadingMore) { model.loadMore() }
            Box(Modifier.fillMaxSize()) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(state.posterWidth),
                    state = grid,
                    contentPadding = PaddingValues(start = 24.dp, end = 32.dp, top = 8.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    content.sponsored?.messages?.firstOrNull()?.let { ad ->
                        item(key = "sponsored", span = { GridItemSpan(maxLineSpan) }) {
                            SponsoredCard(ad, model)
                        }
                    }
                    items(content.items, key = { it.id }) { item ->
                        MediaTile(state, item, chat.title)
                    }
                    if (content.loadingMore) {
                        item(key = "more", span = { GridItemSpan(maxLineSpan) }) {
                            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(28.dp))
                            }
                        }
                    }
                }
                VerticalScrollbar(
                    rememberScrollbarAdapter(grid),
                    Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 4.dp),
                )
            }
        }
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
    Column(Modifier.fillMaxSize()) {
        PageHeader("Continue", "Pick up where you left off", actions = { PosterSizeStep(state) })
        val list = records
        when {
            list == null -> Centred { CircularProgressIndicator() }
            list.isEmpty() -> Centred { Text("Nothing part-watched yet. Videos you stop half way wait here.", color = Tone.muted) }
            else -> {
                val grid = rememberLazyGridState()
                Box(Modifier.fillMaxSize()) {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(state.posterWidth),
                        state = grid,
                        contentPadding = PaddingValues(start = 24.dp, end = 32.dp, top = 8.dp, bottom = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(list, key = { "${it.chatId}:${it.messageId}" }) { record ->
                            LaunchedEffect(record) { state.noteChatTitle(record.chatId, record.chatTitle) }
                            ContinueTile(state, record) {
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
private fun ContinueTile(state: ShellState, record: ResumeRecord, onForget: () -> Unit) {
    val item = remember(record) { record.toMediaItem() }
    Poster(
        state = state,
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
            DropdownMenuItem(text = { Text("Remove from Continue watching") }, onClick = { close(); onForget() })
        },
    )
}

/** A video in a chat's grid. */
@Composable
internal fun MediaTile(state: ShellState, item: MediaItem, chatTitle: String) {
    val progress by state.settings.watchProgress.collectAsState(initial = emptyMap())
    val point: WatchPoint? = progress[SettingsStore.progressKey(item.chatId, item.messageId)]
    Poster(
        state = state,
        item = item,
        chatTitle = chatTitle,
        progress = point?.fraction,
        art = {
            MediaArt(item.miniThumbnail, item.thumbnailFileId, Modifier.fillMaxSize()) {
                Text(item.title.take(1).uppercase(), style = MaterialTheme.typography.headlineSmall, color = Tone.muted)
            }
        },
        subtitle = buildString {
            if (item.durationSec > 0) append(MediaMapper.formatDuration(item.durationSec)).append("  ·  ")
            append(MediaMapper.formatSize(item.sizeBytes))
            item.qualityTags.firstOrNull()?.let { append("  ·  ").append(it) }
        },
    )
}

/**
 * The poster every grid draws: art at 16:9, a progress line, a title under it. Hovering for a
 * moment lifts it and shows Play and the overflow; a right click (Ctrl+click on macOS) opens the
 * same overflow where the pointer is.
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
private fun Poster(
    state: ShellState,
    item: MediaItem,
    chatTitle: String,
    progress: Float?,
    art: @Composable () -> Unit,
    subtitle: String,
    extraMenu: (@Composable (close: () -> Unit) -> Unit)? = null,
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
    val scale by animateFloatAsState(if (lifted) 1.05f else 1f)
    var menu by remember { mutableStateOf(false) }
    val downloads by OfflineDownloads.active.collectAsState()
    val download = downloads[item.fileId]

    Column(
        Modifier
            .hoverable(interaction)
            .onPointerEvent(PointerEventType.Press) { event ->
                val macContext = IS_MAC && event.keyboardModifiers.isCtrlPressed && event.buttons.isPrimaryPressed
                if (event.buttons.isSecondaryPressed || macContext) menu = true
            }
            .clickable { state.openPlayer(item, startFromBeginning = false) },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .clip(MaterialTheme.shapes.medium),
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
            when {
                item.onDevice -> Badge(Icons.Filled.CheckCircle, "On this computer", Modifier.align(Alignment.TopStart))
                download != null -> Badge(TmIcons.Download, "Downloading", Modifier.align(Alignment.TopStart))
            }
            if (lifted || menu) {
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
                TileMenu(state, item, chatTitle, expanded = menu, onDismiss = { menu = false }, extra = extraMenu)
            }
        }
        Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Tone.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Badge(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier) {
    Box(
        modifier.padding(6.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)).padding(4.dp),
    ) {
        Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(16.dp))
    }
}

/** Play, Play from start, Download or Remove download, Copy link: the poster's overflow. */
@Composable
private fun TileMenu(
    state: ShellState,
    item: MediaItem,
    chatTitle: String,
    expanded: Boolean,
    onDismiss: () -> Unit,
    extra: (@Composable (close: () -> Unit) -> Unit)?,
) {
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    val downloads by OfflineDownloads.active.collectAsState()
    val history by state.settings.downloadHistory.collectAsState(initial = emptyList())
    val kept = item.onDevice || history.any { it.chatId == item.chatId && it.messageId == item.messageId }
    val queued = downloads.containsKey(item.fileId)

    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text("Play") }, onClick = { onDismiss(); state.openPlayer(item, startFromBeginning = false) })
        DropdownMenuItem(text = { Text("Play from start") }, onClick = { onDismiss(); state.openPlayer(item, startFromBeginning = true) })
        if (!kept && !queued) {
            DropdownMenuItem(text = { Text("Download") }, onClick = {
                onDismiss()
                OfflineDownloads.start(state.downloads, item, chatTitle.ifBlank { state.chatTitleOf(item.chatId) })
                toast("Downloading ${item.title}")
            })
        }
        if (kept || queued) {
            DropdownMenuItem(text = { Text("Remove download") }, onClick = {
                onDismiss()
                scope.removeDownload(state, item, queued)
                toast("${item.title} removed from this computer")
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

private fun CoroutineScope.removeDownload(state: ShellState, item: MediaItem, queued: Boolean) {
    if (queued) OfflineDownloads.cancel(state.downloads, item.fileId)
    launch {
        runCatching { Td.deleteFile(item.fileId) }
        state.settings.forgetDownload(item.chatId, item.messageId)
    }
}

/** Telegram's sponsored message for a channel, shown above its videos as the phone shows it. */
@Composable
private fun SponsoredCard(ad: SponsoredItem, model: MediaListViewModel) {
    val toast = rememberToast()
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
        }
    }
}

private val IS_MAC = System.getProperty("os.name").orEmpty().lowercase().contains("mac")
private const val HOVER_INTENT_MS = 300L
private const val SEARCH_SETTLE_MS = 300L
private const val LOAD_AHEAD = 8
