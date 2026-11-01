package com.tmplayer.ui.browse

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import com.tmplayer.data.ChatGate
import com.tmplayer.data.ChatVideoNotes
import com.tmplayer.data.Failures
import com.tmplayer.data.KidsLock
import com.tmplayer.data.PageQueries
import com.tmplayer.data.QueryKey
import com.tmplayer.data.LinkRefusal
import com.tmplayer.data.MediaItem
import com.tmplayer.data.PostComments
import com.tmplayer.data.Td
import com.tmplayer.data.VideoPage
import com.tmplayer.i18n.L
import com.tmplayer.ui.components.SlowAnswer
import dev.g000sha256.tdl.TdlClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Videos read a page at a time from somewhere other than a chat's own video search (CP81): a
 * chat's round videos, the comments under a post. A failed or slow page says so, and
 * [loadMore] asks again.
 */
class PagedVideos(
    private val scope: CoroutineScope,
    /**
     * What the pages read are kept under in [PageQueries.cache], so opening the list again shows
     * them at once (through the kids lock as it is now), asked again from the start only once
     * they are [PageQueries.CHAT_STALE_MS] old. Null keeps nothing.
     */
    private val key: QueryKey? = null,
    private val fetch: suspend (td: TdlClient, from: Long) -> VideoPage,
) {
    data class State(
        val items: List<MediaItem> = emptyList(),
        val loading: Boolean = true,
        val done: Boolean = false,
        val failure: String? = null,
    )

    /** The pages read so far, as the cache keeps them. */
    private data class Kept(val items: List<MediaItem>, val next: Long, val done: Boolean)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var next = 0L
    private var job: Job? = null

    init {
        val kept = key?.let { PageQueries.cache.peek<Kept>(it, PageQueries.CHAT_STALE_MS) }
        if (kept != null) {
            val allowed = ChatGate.allowed.value
            next = kept.data.next
            _state.value = State(
                items = kept.data.items.filter { KidsLock.allows(allowed, it.chatId) },
                loading = false,
                done = kept.data.done,
            )
            // Stale: read again from the first page, the kept pages staying up until it lands.
            if (kept.stale) {
                next = 0L
                job = launchPage(replace = true)
            }
        } else {
            loadMore()
        }
    }

    fun loadMore() {
        if (job?.isActive == true || _state.value.done) return
        _state.update { it.copy(loading = true, failure = null) }
        job = launchPage(replace = false)
    }

    /**
     * Reads the page from [next] and adds it to what is listed, or, to [replace] a stale kept
     * listing, puts it in its place once it lands (a failure then leaves the kept pages up).
     */
    private fun launchPage(replace: Boolean): Job = scope.launch {
        try {
            val page = withTimeoutOrNull(PAGE_TIMEOUT_MS) {
                val session = Td.awaitConnectedSessionOrNull() ?: return@withTimeoutOrNull null
                fetch(session.client, next)
            } ?: run {
                if (!replace) _state.update { it.copy(loading = false, failure = SlowAnswer.MESSAGE) }
                return@launch
            }
            next = page.next
            _state.update { state ->
                State(
                    items = ((if (replace) emptyList() else state.items) + page.items).distinctBy { it.id }.sortedByDescending { it.messageId },
                    loading = false,
                    done = page.done,
                )
            }
            key?.let { PageQueries.cache.put(it, Kept(_state.value.items, next, _state.value.done)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: LinkRefusal) {
            key?.let { PageQueries.cache.remove { k -> k == it } }
            _state.update { it.copy(items = emptyList(), loading = false, done = true, failure = e.message) }
        } catch (e: Exception) {
            if (!replace) _state.update { it.copy(loading = false, failure = Failures.humanise(e)) }
        }
    }

    companion object {
        private const val PAGE_TIMEOUT_MS = 25_000L

        /** A chat's round video messages. */
        fun roundVideos(scope: CoroutineScope, chatId: Long) = PagedVideos(scope, QueryKey.Paged("rounds", chatId)) { td, from ->
            ChatVideoNotes.page(td, chatId, from)
        }

        /**
         * The videos in the comments under a channel post. The comments' group is a chat of its
         * own, so the kids lock has to allow it too, or nothing is listed and that is said.
         */
        fun comments(scope: CoroutineScope, chatId: Long, messageId: Long) = PagedVideos(scope, QueryKey.Paged("comments", chatId, messageId)) { td, from ->
            val thread = PostComments.thread(td, chatId, messageId) ?: throw LinkRefusal(L.detailCommentsNone)
            if (!PostComments.allowed(ChatGate.awaitReady(), chatId, thread.chatId)) throw LinkRefusal(L.linkKidsLock)
            PostComments.page(td, chatId, messageId, thread.chatId, from)
        }
    }
}

/** How many comments [item]'s post has, for the detail page's "Videos in the comments"; 0 until known. */
@Composable
fun rememberCommentCount(item: MediaItem): Int {
    val key = QueryKey.CommentCount(item.chatId, item.messageId)
    // The count from the last visit, at once; asked again only once it is stale.
    val count by produceState(initialValue = PageQueries.cache.data<Int>(key) ?: 0, item.chatId, item.messageId) {
        val kept = PageQueries.cache.peek<Int>(key, PageQueries.CHAT_STALE_MS)
        if (kept != null) {
            value = kept.data
            if (!kept.stale) return@produceState
        }
        val session = Td.awaitConnectedSessionOrNull() ?: return@produceState
        value = runCatching {
            PageQueries.cache.fetch(key) { PostComments.count(session.client, item.chatId, item.messageId) }
        }.getOrDefault(kept?.data ?: 0)
    }
    return count
}

