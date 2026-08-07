package com.tmplayer.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tmplayer.data.Account
import com.tmplayer.data.ChatRepository
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.Failures
import com.tmplayer.data.MediaCursors
import com.tmplayer.data.MediaItem
import com.tmplayer.data.SizeFilter
import com.tmplayer.data.Td
import com.tmplayer.ui.components.UiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The chat list plus who is signed in; both are needed before the browser can draw. */
data class BrowseData(
    val chats: List<ChatSummary>,
    val account: Account? = null,
)

class ChatListViewModel : ViewModel() {

    private val repository by lazy { ChatRepository() }
    private val _state = MutableStateFlow<UiState<BrowseData>>(UiState.Loading("Loading your chats…"))
    val state: StateFlow<UiState<BrowseData>> = _state.asStateFlow()

    init {
        load()
    }

    private var account: Account? = null
    private var chats: List<ChatSummary>? = null
    fun load() {
        _state.value = UiState.Loading("Loading your chats…")
        viewModelScope.launch {
            // Nothing is worth asking for until there is a line to Telegram. Coming back from
            // standby this is the whole wait, and without it the list arrives empty.
            Td.awaitConnected()
            runCatching { repository.chats() }
                .onSuccess {
                    chats = it
                    publish()
                }
                .onFailure { _state.value = UiState.Error(Failures.humanise(it)) }
        }
        // The avatar is decoration: it must never hold the chat list up, and its failure is not
        // an error the viewer needs to see. Whichever of the two lands second does the publishing,
        // so neither can quietly drop the other's result.
        viewModelScope.launch {
            account = runCatching { Td.me() }.getOrNull() ?: return@launch
            publish()
        }
    }

    private fun publish() {
        val loaded = chats ?: return
        _state.value = if (loaded.isEmpty()) {
            UiState.Empty("No chats yet. Open Telegram on your phone, then come back.")
        } else {
            UiState.Content(BrowseData(loaded, account))
        }
    }
}

data class MediaListState(
    val items: List<MediaItem> = emptyList(),
    val loadingMore: Boolean = false,
    val endReached: Boolean = false,
)

class MediaListViewModel(
    private val chatId: Long,
    private val minSizeBytes: Long,
    private val maxSizeBytes: Long,
) : ViewModel() {

    private val repository by lazy { ChatRepository() }
    private var cursors = MediaCursors()
    private var pageJob: Job? = null
    private var query = ""

    private val _state = MutableStateFlow<UiState<MediaListState>>(UiState.Loading("Finding films…"))
    val state: StateFlow<UiState<MediaListState>> = _state.asStateFlow()

    init {
        load()
    }

    /** Re-runs the listing against [text]; blank means "everything in this chat". */
    fun search(text: String) {
        if (text == query) return
        query = text
        load()
    }

    fun load() {
        cursors = MediaCursors()
        val searching = query.isNotBlank()
        _state.value = UiState.Loading(if (searching) "Searching…" else "Finding films…")
        pageJob?.cancel()
        pageJob = viewModelScope.launch {
            Td.awaitConnected()
            runCatching { repository.mediaPage(chatId, cursors, query) }
                .onSuccess { rawPage ->
                    val page = rawPage.copy(items = rawPage.items.filter(::withinSizeLimits))
                    cursors = page.cursors
                    _state.value = if (page.items.isEmpty() && page.endReached) {
                        val message = when {
                            searching -> "Nothing in this chat matches “$query”."
                            // Say which knob is hiding things, rather than claiming the chat is
                            // empty when it is the filter doing the work.
                            isFiltering -> "No videos here between " +
                                "${SizeFilter.label(minSizeBytes)} and " +
                                "${SizeFilter.label(maxSizeBytes)}.\n\n" +
                                "Change the video size limits in Settings to see more."
                            else -> "No films or videos in this chat."
                        }
                        UiState.Empty(message)
                    } else {
                        UiState.Content(MediaListState(page.items, endReached = page.endReached))
                    }
                    // A first page can come back empty while older pages still hold films.
                    if (page.items.isEmpty() && !page.endReached) loadMore()
                }
                .onFailure { _state.value = UiState.Error(Failures.humanise(it)) }
        }
    }

    private val isFiltering: Boolean
        get() = minSizeBytes > SizeFilter.FLOOR || maxSizeBytes < SizeFilter.CEILING

    private fun withinSizeLimits(item: MediaItem) =
        SizeFilter.matches(item.sizeBytes, minSizeBytes, maxSizeBytes)

    /**
     * Called when focus nears the end of the listing.
     *
     * Scrolling never stops at a page boundary: pages keep being pulled until this one actually
     * grew, or the chat ran out. Without that, a page whose films are all outside the size limits
     * adds nothing, the item count does not change, and the screen the viewer is scrolling has no
     * reason left to ask for more, so the listing stops short of the end of the chat.
     */
    fun loadMore() {
        val current = _state.value as? UiState.Content ?: return
        if (current.value.loadingMore || current.value.endReached) return
        if (pageJob?.isActive == true) return

        _state.value = UiState.Content(current.value.copy(loadingMore = true))
        pageJob = viewModelScope.launch {
            var items = current.value.items
            var endReached = false
            var failed = false
            // A chat can hold thousands of files that the size limits all reject, and the viewer
            // is watching a "Loading more…" chip the whole time. After this many empty-handed
            // pages it gives the screen back; the next scroll picks up where the cursors are.
            var attempts = 0

            while (
                items.size == current.value.items.size &&
                !endReached &&
                !failed &&
                attempts++ < MAX_EMPTY_PAGES
            ) {
                runCatching { repository.mediaPage(chatId, cursors, query) }
                    .onSuccess { rawPage ->
                        val page = rawPage.copy(items = rawPage.items.filter(::withinSizeLimits))
                        cursors = page.cursors
                        items = (items + page.items).distinctBy { it.messageId }
                        endReached = page.endReached
                    }
                    .onFailure { failed = true }
            }

            _state.value = UiState.Content(
                MediaListState(
                    items = items,
                    loadingMore = false,
                    // A page that failed is not the end of the chat, only the end of this attempt.
                    // Leaving the listing open means scrolling on retries it, rather than a single
                    // dropped connection cutting the chat short for as long as it stays open.
                    endReached = endReached,
                ),
            )
        }
    }

    private companion object {
        const val MAX_EMPTY_PAGES = 8
    }
}
