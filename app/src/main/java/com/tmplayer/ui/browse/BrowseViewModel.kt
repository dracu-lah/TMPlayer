package com.tmplayer.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tmplayer.data.Account
import com.tmplayer.data.ChatRepository
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.Failures
import com.tmplayer.data.MediaCursors
import com.tmplayer.data.MediaItem
import com.tmplayer.data.LocalFileAvailability
import com.tmplayer.data.SizeFilter
import com.tmplayer.data.Td
import com.tmplayer.ui.components.UiState
import kotlinx.coroutines.CancellationException
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

    private val _state = MutableStateFlow<UiState<BrowseData>>(UiState.Loading("Loading your chats…"))
    val state: StateFlow<UiState<BrowseData>> = _state.asStateFlow()

    init {
        load()
    }

    private var loadJob: Job? = null
    private var account: Account? = null
    private var chats: List<ChatSummary>? = null

    fun load() {
        loadJob?.cancel()
        if (chats == null) _state.value = UiState.Loading("Loading your chats…")
        loadJob = viewModelScope.launch {
            val session = Td.awaitAuthorizedSession()
            val repository = ChatRepository(session.client)
            try {
                // Either local read may be briefly unavailable on the first Ready update after
                // QR sign-in. Neither is treated as the final answer; the connected pass below
                // retries both without asking the viewer to press Refresh.
                chats = try {
                    repository.cachedChats()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    null
                }
                account = try {
                    Td.me(session)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    null
                }
                if (!session.isCurrent()) return@launch
                if (chats != null) publish()

                // The local database is the first answer. Telegram is then synchronized when its
                // socket is ready, without holding the saved library behind a connection wait.
                val connected = Td.awaitConnectedSession()
                if (connected.generation != session.generation) return@launch
                if (account == null) account = runCatching { Td.me(session) }.getOrNull()
                chats = repository.syncChats()
                if (session.isCurrent()) publish()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (session.isCurrent() && chats == null) {
                    _state.value = UiState.Error(Failures.humanise(error))
                }
            }
        }
    }

    /** Removes one account's data immediately when authorization is lost. */
    fun reset() {
        loadJob?.cancel()
        account = null
        chats = null
        _state.value = UiState.Loading("Loading your chats…")
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
    val availabilityRevision: Int = 0,
)

class MediaListViewModel(
    private val chatId: Long,
    private val minSizeBytes: Long,
    private val maxSizeBytes: Long,
) : ViewModel() {

    private var cursors = MediaCursors()
    private var pageJob: Job? = null
    private var availabilityJob: Job? = null
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
        load(preserveContent = false)
    }

    fun load() = load(preserveContent = true)

    private fun load(preserveContent: Boolean) {
        val previous = if (preserveContent) (_state.value as? UiState.Content)?.value else null
        val previousCursors = cursors
        cursors = MediaCursors()
        val searching = query.isNotBlank()
        if (previous == null) {
            _state.value = UiState.Loading(if (searching) "Searching…" else "Finding films…")
        }
        pageJob?.cancel()
        pageJob = viewModelScope.launch {
            val session = Td.awaitAuthorizedSession()
            val repository = ChatRepository(session.client)
            runCatching { repository.mediaPage(chatId, cursors, query) }
                .onSuccess { rawPage ->
                    if (!session.isCurrent()) return@onSuccess
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
                .onFailure {
                    if (it is CancellationException) throw it
                    if (session.isCurrent()) {
                        if (previous != null) {
                            cursors = previousCursors
                            _state.value = UiState.Content(previous)
                        } else {
                            _state.value = UiState.Error(Failures.humanise(it))
                        }
                    }
                }
        }
    }

    private val isFiltering: Boolean
        get() = minSizeBytes > SizeFilter.FLOOR || maxSizeBytes < SizeFilter.CEILING

    private fun withinSizeLimits(item: MediaItem) =
        SizeFilter.matches(item.sizeBytes, minSizeBytes, maxSizeBytes)

    /** Revalidates badges after playback or a cache clear without rebuilding the whole grid. */
    fun refreshLocalAvailability() {
        val current = (_state.value as? UiState.Content)?.value ?: return
        availabilityJob?.cancel()
        availabilityJob = viewModelScope.launch {
            val session = Td.awaitAuthorizedSession()
            var changed = false
            val updated = current.items.map { item ->
                val onDevice = Td.localFileAvailability(item.fileId) == LocalFileAvailability.Complete
                if (onDevice == item.onDevice) {
                    item
                } else {
                    changed = true
                    item.copy(onDevice = onDevice)
                }
            }
            if (session.isCurrent() && changed) {
                _state.value = UiState.Content(
                    current.copy(
                        items = updated,
                        availabilityRevision = current.availabilityRevision + 1,
                    ),
                )
            }
        }
    }

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
            val session = Td.awaitAuthorizedSession()
            val repository = ChatRepository(session.client)
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
                    .onFailure {
                        if (it is CancellationException) throw it
                        failed = true
                    }
            }

            if (session.isCurrent()) _state.value = UiState.Content(
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
