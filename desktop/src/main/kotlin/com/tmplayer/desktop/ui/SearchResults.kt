package com.tmplayer.desktop.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.tmplayer.data.AllChatsSearch
import com.tmplayer.data.ChatSummary
import com.tmplayer.ui.browse.VideoSearchState
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.theme.Tone

/**
 * "Videos in all chats", answered in two parts: the chats whose names match, as rows that open
 * them, then the videos Telegram found across every chat, as posters. A poster plays on a click
 * and opens the detail pane on a right click, the context menu key or I, as in a chat's grid.
 *
 * Split from [ChatsPage] and handed the search's state, so the render test can draw it with a
 * made-up search.
 */
@Composable
internal fun AllChatsResults(
    state: ShellState,
    query: String,
    chats: List<ChatSummary>,
    favourites: Set<Long>,
    videos: VideoSearchState,
    onStar: (ChatSummary) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
) {
    val s = LocalStrings.current
    val matching = remember(chats, query) { AllChatsSearch.matchingChats(chats, query) }
    val grid = rememberLazyGridState()
    LoadMoreNearEnd(grid, enabled = videos.videos.isNotEmpty() && !videos.endReached && !videos.loadingMore) { onLoadMore() }
    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = androidx.compose.foundation.lazy.grid.GridCells.Adaptive(state.posterWidth),
            state = grid,
            contentPadding = PaddingValues(start = 24.dp, end = 32.dp, top = 8.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (matching.isNotEmpty()) {
                item(key = "chats-heading", span = { GridItemSpan(maxLineSpan) }) { SectionHeading(s.browseSearchResultsChats) }
                items(matching, key = { "chat-${it.id}" }, span = { GridItemSpan(maxLineSpan) }) { chat ->
                    ChatRow(chat = chat, favourite = chat.id in favourites, onOpen = { state.openChat(chat) }, onStar = { onStar(chat) })
                }
            }
            item(key = "videos-heading", span = { GridItemSpan(maxLineSpan) }) { SectionHeading(s.browseSearchResultsVideos) }
            when {
                videos.loading -> item(key = "searching", span = { GridItemSpan(maxLineSpan) }) {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.size(24.dp))
                        Text(s.browseSearchVideosSearching, color = Tone.muted)
                    }
                }
                videos.error != null -> item(key = "failed", span = { GridItemSpan(maxLineSpan) }) {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(s.browseSearchVideosFailed(videos.error.orEmpty()), color = Tone.muted)
                        OutlinedButton(onClick = onRetry) { Text(s.commonRetry) }
                    }
                }
                videos.videos.isEmpty() && videos.query.isNotEmpty() -> item(key = "none", span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        s.browseSearchVideosNone(query),
                        color = Tone.muted,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    )
                }
                else -> items(videos.videos, key = { it.id }) { item ->
                    MediaTile(state, item, state.chatTitleOf(item.chatId), outsideChat = true)
                }
            }
            if (videos.loadingMore) {
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

@Composable
private fun SectionHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 8.dp).semantics { heading() },
    )
}
