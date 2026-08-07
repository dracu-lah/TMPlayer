package com.tmplayer.ui.browse

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tmplayer.data.MediaItem
import com.tmplayer.data.MediaMapper
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.WatchPoint
import com.tmplayer.ui.components.MediaGridSkeleton
import com.tmplayer.ui.components.Poster
import com.tmplayer.ui.components.StateScaffold
import com.tmplayer.ui.components.Spinner
import com.tmplayer.ui.components.TmIcons
import com.tmplayer.ui.components.TvSearchField
import com.tmplayer.ui.components.rememberVoiceSearch
import com.tmplayer.ui.theme.Accent
import com.tmplayer.ui.theme.SurfaceDark
import com.tmplayer.ui.theme.SurfaceRaised
import com.tmplayer.ui.theme.TextMuted
import com.tmplayer.ui.theme.TextPrimary
import com.tmplayer.ui.theme.Tv

@Suppress("UNCHECKED_CAST")
private class MediaListViewModelFactory(
    private val chatId: Long,
    private val minSize: Long,
    private val maxSize: Long,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        MediaListViewModel(chatId, minSize, maxSize) as T
}

@Composable
fun MediaGridScreen(
    chatId: Long,
    chatTitle: String,
    isFavorite: Boolean,
    minSizeBytes: Long,
    maxSizeBytes: Long,
    watchProgress: Map<String, WatchPoint>,
    onToggleFavorite: () -> Unit,
    onPlay: (MediaItem) -> Unit,
    onPlayFromStart: (MediaItem) -> Unit,
) {
    val context = LocalContext.current
    // The limits are part of the key: changing them in Settings has to rebuild the listing,
    // not leave a stale one filtered by the old bounds.
    val viewModel: MediaListViewModel = viewModel(
        key = "media-$chatId-$minSizeBytes-$maxSizeBytes",
        factory = MediaListViewModelFactory(chatId, minSizeBytes, maxSizeBytes),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var details by remember { mutableStateOf<MediaItem?>(null) }

    // Whether anything on this device can play a YouTube link. Checked once rather than per
    // film, and used to hide the trailer button entirely rather than have it open nothing.
    val trailersAvailable = remember { canOpenYouTube(context) }

    Column(Modifier.fillMaxSize()) {
        Header(
            chatTitle = chatTitle,
            isFavorite = isFavorite,
            query = query,
            onQuery = { query = it },
            onSubmit = { viewModel.search(query) },
            onToggleFavorite = onToggleFavorite,
            onRefresh = viewModel::load,
        )

        StateScaffold(
            state,
            onRetry = viewModel::load,
            loading = { MediaGridSkeleton() },
        ) { list ->
            val gridState = rememberLazyGridState()
            val firstItem = remember { FocusRequester() }

            Box(Modifier.fillMaxSize()) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(COLUMNS),
                    state = gridState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = Tv.SafeH,
                        end = Tv.SafeH,
                        // A television crops its outermost few percent, so the last row needs
                        // clearance or its titles are cut off the bottom of the panel.
                        bottom = Tv.SafeV + 16.dp,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(list.items, key = { it.id }) { item ->
                        MediaCard(
                            item = item,
                            watched = watchProgress[
                                SettingsStore.progressKey(item.chatId, item.messageId),
                            ],
                            onClick = { details = item },
                            modifier = if (item === list.items.firstOrNull()) {
                                Modifier.focusRequester(firstItem)
                            } else {
                                Modifier
                            },
                        )
                    }
                }

                // Floated over the grid; a reserved band cost a whole row on a 540dp panel.
                if (list.loadingMore) {
                    Row(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = Tv.SafeV)
                            .clip(RoundedCornerShape(20.dp))
                            .background(SurfaceRaised)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Spinner(size = 18.dp, strokeWidth = 2.dp)
                        Text(
                            "Loading more…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextMuted,
                        )
                    }
                }
            }

            LaunchedEffect(list.items.firstOrNull()?.id) {
                runCatching { firstItem.requestFocus() }
            }

            // Fetch the next page well before the user reaches the bottom row.
            val nearEnd by remember(list.items.size) {
                derivedStateOf {
                    val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                    last >= list.items.size - COLUMNS * 2
                }
            }
            LaunchedEffect(gridState, list.items.size) {
                snapshotFlow { nearEnd }.collect { if (it) viewModel.loadMore() }
            }
        }
    }

    details?.let { item ->
        val point = watchProgress[SettingsStore.progressKey(item.chatId, item.messageId)]
        FilmDetailsPanel(
            item = item,
            resumeMs = point?.positionMs ?: 0L,
            trailersAvailable = trailersAvailable,
            onPlay = { details = null; onPlay(item) },
            onPlayFromStart = { details = null; onPlayFromStart(item) },
            onWatchTrailer = { key -> openYouTube(context, key) },
            onDismiss = { details = null },
        )
    }
}

/**
 * Trailers open in YouTube's own app rather than inside TMPlayer.
 *
 * Embedding their player requires their SDK and a WebView pointed at an embed URL breaks their
 * terms, and every Android TV device ships the app.
 */
private fun youTubeIntent(key: String) =
    Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$key"))

private fun canOpenYouTube(context: Context): Boolean =
    youTubeIntent("test").resolveActivity(context.packageManager) != null

private fun openYouTube(context: Context, key: String) {
    runCatching { context.startActivity(youTubeIntent(key)) }
}

@Composable
private fun Header(
    chatTitle: String,
    isFavorite: Boolean,
    query: String,
    onQuery: (String) -> Unit,
    onSubmit: () -> Unit,
    onToggleFavorite: () -> Unit,
    onRefresh: () -> Unit,
) {
    val startVoice = rememberVoiceSearch("Say a film name") {
        onQuery(it)
        onSubmit()
    }

    Column(Modifier.padding(start = Tv.SafeH, end = Tv.SafeH, top = Tv.SafeV, bottom = 12.dp)) {
        Text(
            chatTitle,
            style = MaterialTheme.typography.headlineLarge,
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "Films and videos from this chat",
            style = MaterialTheme.typography.bodyMedium,
            color = TextMuted,
        )
        Spacer(Modifier.height(12.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val searchField = remember { FocusRequester() }

            TvSearchField(
                value = query,
                onValueChange = onQuery,
                placeholder = "Search this chat",
                onSubmit = onSubmit,
                modifier = Modifier.weight(1f).focusRequester(searchField),
            )
            if (startVoice != null) {
                Pill("Voice search", TmIcons.Mic, onClick = startVoice)
            }
            if (query.isNotBlank()) {
                Pill("Clear", Icons.Filled.Close) {
                    // Focus has to leave before the state change, because clearing the query is
                    // what removes this pill from the layout. Letting it vanish while focused
                    // strands the remote: the next press goes nowhere or jumps to the rail.
                    runCatching { searchField.requestFocus() }
                    onQuery("")
                    onSubmit()
                }
            }
            Pill(
                label = if (isFavorite) "Remove favourite" else "Add favourite",
                icon = if (isFavorite) Icons.Filled.Star else TmIcons.StarOutline,
                tintWhenIdle = if (isFavorite) Accent else TextPrimary,
                onClick = onToggleFavorite,
            )
            // Telegram pushes new messages into TDLib's database, but this grid was built from a
            // search that ran when it opened, so a film posted since then needs a fresh search.
            Pill("Refresh", Icons.Filled.Refresh, onClick = onRefresh)
        }
    }
}

@Composable
private fun Pill(
    label: String,
    icon: ImageVector,
    tintWhenIdle: Color = TextPrimary,
    onClick: () -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val background by animateColorAsState(
        targetValue = if (focused) Accent else SurfaceRaised,
        animationSpec = tween(140),
        label = "pill",
    )
    val foreground = if (focused) Color.White else tintWhenIdle

    Row(
        Modifier
            .height(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(background)
            .clickable(interactionSource = interactions, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = null, tint = foreground, modifier = Modifier.size(22.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = foreground, maxLines = 1)
    }
}

/**
 * A poster tile that marks focus with a border rather than by growing.
 *
 * TV Material's card scales up when focused, and a card in the outermost grid column visibly
 * runs off the screen edge when it does.
 */
@Composable
private fun MediaCard(
    item: MediaItem,
    watched: WatchPoint?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val border by animateColorAsState(
        targetValue = if (focused) Accent else Color.Transparent,
        animationSpec = tween(140),
        label = "cardBorder",
    )

    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (focused) SurfaceRaised else SurfaceDark)
            .border(3.dp, border, RoundedCornerShape(14.dp))
            .clickable(interactionSource = interactions, indication = null, onClick = onClick),
    ) {
        Box {
            Poster(
                miniThumbnail = item.miniThumbnail,
                thumbnailFileId = item.thumbnailFileId,
                fallbackLabel = item.title,
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            )
            val tags = item.qualityTags
            if (tags.isNotEmpty()) {
                Row(
                    Modifier.align(Alignment.TopEnd).padding(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    tags.forEach { tag ->
                        Text(
                            tag,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White,
                            maxLines = 1,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color.Black.copy(alpha = 0.72f))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            if (watched != null && watched.fraction > 0f) {
                // A thin bar along the bottom of the art, the one place a viewer already looks
                // to see whether they have started something.
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(6.dp)
                        .background(Color.Black.copy(alpha = 0.55f)),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(watched.fraction)
                            .fillMaxHeight()
                            .background(Accent),
                    )
                }
            }
        }
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                item.title,
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                // One line, not two. A release file name is long enough to fill both, and the
                // second line cost a row of the grid on a 540dp panel; the full title is one
                // press away on the details panel now.
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            val duration = MediaMapper.formatDuration(item.durationSec)
            val size = MediaMapper.formatSize(item.sizeBytes)
            val resume = watched
                ?.takeIf { it.positionMs > 0 }
                ?.let { "Stopped at ${com.tmplayer.player.StreamStats.formatClock(it.positionMs)}" }
            Text(
                listOfNotNull(duration.ifEmpty { null }, size.ifEmpty { null }, resume)
                    .joinToString("  ·  "),
                style = MaterialTheme.typography.bodyMedium,
                color = if (resume != null) Accent else TextMuted,
                maxLines = 1,
            )
        }
    }
}

private const val COLUMNS = 4
