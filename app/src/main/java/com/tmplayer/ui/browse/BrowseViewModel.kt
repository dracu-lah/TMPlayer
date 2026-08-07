package com.tmplayer.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tmplayer.data.ChatRepository
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.MediaCursors
import com.tmplayer.data.MediaItem
import com.tmplayer.ui.components.UiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ChatListViewModel : ViewModel() {

    private val repository by lazy { ChatRepository() }
    private val _state = MutableStateFlow<UiState<List<ChatSummary>>>(UiState.Loading("Loading your chats…"))
    val state: StateFlow<UiState<List<ChatSummary>>> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.value = UiState.Loading("Loading your chats…")
        viewModelScope.launch {
            runCatching { repository.chats() }
                .onSuccess { chats ->
                    _state.value = if (chats.isEmpty()) {
                        UiState.Error("No chats yet. Open Telegram on your phone, then come back.")
                    } else {
                        UiState.Content(chats)
                    }
                }
                .onFailure { _state.value = UiState.Error(it.message ?: "Couldn't reach Telegram") }
        }
    }
}

data class MediaListState(
    val items: List<MediaItem> = emptyList(),
    val loadingMore: Boolean = false,
    val endReached: Boolean = false,
)

class MediaListViewModel(private val chatId: Long) : ViewModel() {

    private val repository by lazy { ChatRepository() }
    private var cursors = MediaCursors()
    private var pageJob: Job? = null

    private val _state = MutableStateFlow<UiState<MediaListState>>(UiState.Loading("Finding movies…"))
    val state: StateFlow<UiState<MediaListState>> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        cursors = MediaCursors()
        _state.value = UiState.Loading("Finding movies…")
        pageJob?.cancel()
        pageJob = viewModelScope.launch {
            runCatching { repository.mediaPage(chatId, cursors) }
                .onSuccess { page ->
                    cursors = page.cursors
                    _state.value = if (page.items.isEmpty() && page.endReached) {
                        UiState.Error("Nothing playable in this chat.")
                    } else {
                        UiState.Content(MediaListState(page.items, endReached = page.endReached))
                    }
                    // A first page can come back empty while older pages still hold films.
                    if (page.items.isEmpty() && !page.endReached) loadMore()
                }
                .onFailure { _state.value = UiState.Error(it.message ?: "Couldn't load this chat") }
        }
    }

    /** Called when focus nears the end of the grid. */
    fun loadMore() {
        val current = _state.value as? UiState.Content ?: return
        if (current.value.loadingMore || current.value.endReached) return
        if (pageJob?.isActive == true) return

        _state.value = UiState.Content(current.value.copy(loadingMore = true))
        pageJob = viewModelScope.launch {
            runCatching { repository.mediaPage(chatId, cursors) }
                .onSuccess { page ->
                    cursors = page.cursors
                    val merged = (current.value.items + page.items).distinctBy { it.messageId }
                    _state.value = UiState.Content(
                        MediaListState(merged, loadingMore = false, endReached = page.endReached),
                    )
                }
                .onFailure {
                    _state.value = UiState.Content(current.value.copy(loadingMore = false, endReached = true))
                }
        }
    }
}
