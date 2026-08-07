package com.tmplayer.ui.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tmplayer.data.ChatSummary
import com.tmplayer.ui.components.Poster
import com.tmplayer.ui.components.StateScaffold

@Composable
fun ChatListScreen(
    onOpenChat: (ChatSummary) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val viewModel: ChatListViewModel = viewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 56.dp, end = 56.dp, top = 40.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Your chats", style = MaterialTheme.typography.headlineLarge)
                Text("Pick a chat to see everything playable in it", style = MaterialTheme.typography.bodyMedium)
            }
            Button(onClick = onOpenSettings) { Text("Settings") }
        }

        StateScaffold(state, onRetry = viewModel::load) { chats ->
            val listState = rememberLazyListState()
            val firstItem = remember { FocusRequester() }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 56.dp, end = 56.dp, bottom = 56.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(chats, key = { it.id }) { chat ->
                    ChatRow(
                        chat = chat,
                        onClick = { onOpenChat(chat) },
                        modifier = if (chat === chats.first()) {
                            Modifier.focusRequester(firstItem)
                        } else {
                            Modifier
                        },
                    )
                }
            }
            // The remote has nowhere to go until something is focused.
            LaunchedEffect(chats.firstOrNull()?.id) {
                runCatching { firstItem.requestFocus() }
            }
        }
    }
}

@Composable
private fun ChatRow(chat: ChatSummary, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(112.dp),
        shape = CardDefaults.shape(RoundedCornerShape(16.dp)),
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Poster(
                miniThumbnail = chat.miniThumbnail,
                thumbnailFileId = chat.photoFileId,
                fallbackLabel = chat.title,
                modifier = Modifier.size(72.dp).clip(RoundedCornerShape(36.dp)),
            )
            Spacer(Modifier.width(24.dp))
            Text(
                chat.title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun EmptyBox(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.titleLarge)
    }
}
