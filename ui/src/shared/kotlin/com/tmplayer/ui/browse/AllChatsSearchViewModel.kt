package com.tmplayer.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tmplayer.data.AllChatsCursor
import com.tmplayer.data.AllChatsPage
import com.tmplayer.data.AllChatsSearch
import com.tmplayer.data.ChatRepository
import com.tmplayer.data.Failures
import com.tmplayer.data.MediaItem
import com.tmplayer.data.Td
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where "Videos in all chats" gets its pages: Telegram in the app, a fixture in the promo build. */
fun interface VideoSearchSource {
    suspend fun page(query: String, cursor: AllChatsCursor): AllChatsPage

    companion object {
        /** The signed in account, through TDLib's `searchMessages`. */
        val Telegram = VideoSearchSource { query, cursor ->
            ChatRepository(Td.awaitAuthorizedSession().client).searchAllChats(query, cursor)
        }
    }
}

/** The videos half of an all-chats search. The chats half is the local list, matched on screen. */
data class VideoSearchState(
    val query: String = "",
    val videos: List<MediaItem> = emptyList(),
    /** The first page is on its way. */
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val endReached: Boolean = false,
    /** The search failed, in words; null when it did not. */
    val error: String? = null,
)

/**
 * "Videos in all chats": the query, sent to Telegram once the typing stops, paged on demand.
 *
 * A search that finds nothing for the whole of a query of several words tries again with the
 * longest of them and keeps only what answers the whole query, the same retry a chat's own search
 * makes (see [AllChatsSearch.fallbackQuery]).
 */
class AllChatsSearchViewModel(
    private val source: VideoSearchSource = VideoSearchSource.Telegram,
    /** Told about a search that found something, for the recent-search chips. */
    private val onSearched: (suspend (String) -> Unit)? = null,
    private val settleMs: Long = SETTLE_MS,
) : ViewModel() {

    private val _state = MutableStateFlow(VideoSearchState())
    val state: StateFlow<VideoSearchState> = _state.asStateFlow()

    private var job: Job? = null
    private var cursor = AllChatsCursor()

    /** What Telegram is being asked, which is the fallback word once the whole query found nothing. */
    private var serverQuery = ""

    /** Re-runs the search for [text], after the typing has settled. Blank clears it. */
    fun search(text: String) {
        val query = text.trim()
        if (query == _state.value.query && (job?.isActive == true || _state.value.videos.isNotEmpty() || _state.value.endReached)) return
        job?.cancel()
        cursor = AllChatsCursor()
        if (query.isEmpty()) {
            _state.value = VideoSearchState()
            return
        }
        _state.value = VideoSearchState(query = query, loading = true)
        job = viewModelScope.launch {
            delay(settleMs)
            runCatching { firstPage(query) }
                .onSuccess { (page, fallback) ->
                    cursor = page.cursor
                    val kept = AllChatsSearch.keep(page.items, query, fallback)
                    _state.value = VideoSearchState(
                        query = query,
                        videos = AllChatsSearch.merge(emptyList(), kept),
                        endReached = page.cursor.done,
                    )
                    if (kept.isNotEmpty()) runCatching { onSearched?.invoke(query) }
                }
                .onFailure {
                    if (it is CancellationException) throw it
                    _state.value = VideoSearchState(query = query, error = Failures.humanise(it))
                }
        }
    }

    /** The next page, when the list nears its end. */
    fun loadMore() {
        val now = _state.value
        if (now.query.isEmpty() || now.loading || now.loadingMore || now.endReached || now.error != null) return
        if (job?.isActive == true) return
        _state.update { it.copy(loadingMore = true) }
        val query = now.query
        val fallback = serverQuery != query
        job = viewModelScope.launch {
            runCatching { source.page(serverQuery, cursor) }
                .onSuccess { page ->
                    cursor = page.cursor
                    val kept = AllChatsSearch.keep(page.items, query, fallback)
                    _state.update {
                        it.copy(
                            videos = AllChatsSearch.merge(it.videos, kept),
                            loadingMore = false,
                            endReached = page.cursor.done,
                        )
                    }
                }
                .onFailure {
                    if (it is CancellationException) throw it
                    // A page that failed half way down keeps what is on screen; the next scroll
                    // to the end asks again.
                    _state.update { s -> s.copy(loadingMore = false) }
                }
        }
    }

    /** After a failure: the same query again, without the wait. */
    fun retry() {
        val query = _state.value.query
        _state.value = VideoSearchState()
        if (query.isNotEmpty()) search(query)
    }

    private suspend fun firstPage(query: String): Pair<AllChatsPage, Boolean> {
        serverQuery = query
        val whole = source.page(query, AllChatsCursor())
        if (whole.items.isNotEmpty()) return whole to false
        val word = AllChatsSearch.fallbackQuery(query) ?: return whole to false
        val retry = source.page(word, AllChatsCursor())
        if (retry.items.isEmpty()) return whole to false
        serverQuery = word
        return retry to true
    }

    companion object {
        /** Long enough that each letter typed on a phone is not a round trip to Telegram. */
        const val SETTLE_MS = 450L
    }
}
