package com.tmplayer.ui.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tmplayer.data.MediaItem
import com.tmplayer.data.MediaMapper
import com.tmplayer.ui.components.BigLoader
import com.tmplayer.ui.components.Poster
import com.tmplayer.ui.components.StateScaffold

@Suppress("UNCHECKED_CAST")
private class MediaListViewModelFactory(private val chatId: Long) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T = MediaListViewModel(chatId) as T
}

@Composable
fun MediaGridScreen(
    chatId: Long,
    chatTitle: String,
    onPlay: (MediaItem) -> Unit,
) {
    val viewModel: MediaListViewModel = viewModel(
        key = "media-$chatId",
        factory = MediaListViewModelFactory(chatId),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 56.dp, end = 56.dp, top = 40.dp, bottom = 16.dp)) {
            Text(
                chatTitle,
                style = MaterialTheme.typography.headlineLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text("Videos and movie files only", style = MaterialTheme.typography.bodyMedium)
        }

        StateScaffold(state, onRetry = viewModel::load) { list ->
            val gridState = rememberLazyGridState()
            val firstItem = remember { FocusRequester() }

            LazyVerticalGrid(
                columns = GridCells.Fixed(COLUMNS),
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 56.dp, end = 56.dp, bottom = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                items(list.items, key = { it.id }) { item ->
                    MediaCard(
                        item = item,
                        onClick = { onPlay(item) },
                        modifier = if (item === list.items.firstOrNull()) {
                            Modifier.focusRequester(firstItem)
                        } else {
                            Modifier
                        },
                    )
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

            if (list.loadingMore) {
                Box(Modifier.fillMaxWidth().height(96.dp)) { BigLoader(null) }
            }
        }
    }
}

@Composable
private fun MediaCard(item: MediaItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = CardDefaults.shape(RoundedCornerShape(14.dp)),
    ) {
        Column {
            Poster(
                miniThumbnail = item.miniThumbnail,
                thumbnailFileId = item.thumbnailFileId,
                fallbackLabel = item.title,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)),
            )
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val duration = MediaMapper.formatDuration(item.durationSec)
                    val size = MediaMapper.formatSize(item.sizeBytes)
                    Text(
                        listOf(duration, size).filter { it.isNotEmpty() }.joinToString("  ·  "),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

private const val COLUMNS = 4
