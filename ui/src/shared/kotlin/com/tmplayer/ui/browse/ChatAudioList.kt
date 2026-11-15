package com.tmplayer.ui.browse

import com.tmplayer.data.ChatAudio
import com.tmplayer.data.MediaItem
import com.tmplayer.data.PlayQueue
import com.tmplayer.data.QueueFeed
import com.tmplayer.data.Td
import com.tmplayer.data.Failures
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
 * A chat's audio files and voice messages as a list to play from ([ChatAudio]), loaded a page at a
 * time, newest first as Telegram lists them. Playing one sets [PlayQueue] to the whole list in
 * the order it was posted, so a course of lectures or an album plays on from there.
 */
class ChatAudioList(private val chatId: Long, private val scope: CoroutineScope) {

    data class State(
        val items: List<MediaItem> = emptyList(),
        val loading: Boolean = true,
        val done: Boolean = false,
        /** The last page failed, with why; [ChatAudioList.loadMore] asks again. */
        val failure: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var last: ChatAudio.Page? = null
    private var job: Job? = null

    init {
        loadMore()
    }

    fun loadMore() {
        if (job?.isActive == true || _state.value.done) return
        _state.update { it.copy(loading = true, failure = null) }
        job = scope.launch {
            try {
                val page = withTimeoutOrNull(PAGE_TIMEOUT_MS) {
                    val session = Td.awaitConnectedSessionOrNull() ?: return@withTimeoutOrNull null
                    ChatAudio.page(session.client, chatId, last)
                } ?: run {
                    _state.update { it.copy(loading = false, failure = com.tmplayer.ui.components.SlowAnswer.MESSAGE) }
                    return@launch
                }
                last = page
                _state.update { state ->
                    State(
                        items = (state.items + page.items).distinctBy { it.messageId }.sortedByDescending { it.messageId },
                        loading = false,
                        done = page.done,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, failure = Failures.humanise(e)) }
            }
        }
    }

    /** Plays [item] with the list behind it, oldest first (or shuffled, as chosen), so the next one follows on. */
    fun queueFrom(item: MediaItem): MediaItem {
        PlayQueue.set(_state.value.items, current = item, more = feed())
        return item
    }

    /**
     * Play all: the oldest file with the list behind it, or, with shuffle on, any file with the
     * rest following in a random order. In the order posted the pages are read first (up to
     * [ALL_MAX] files), since the oldest is the start of a course and Telegram lists the newest
     * first; shuffled, it starts with what is loaded and the queue reads the rest while it plays.
     */
    suspend fun playAll(): MediaItem? {
        job?.join()
        while (!PlayQueue.shuffle && !_state.value.done && _state.value.failure == null && _state.value.items.size < ALL_MAX) {
            loadMore()
            job?.join()
        }
        PlayQueue.set(_state.value.items, current = null, more = feed())
        return PlayQueue.items.firstOrNull()
    }

    /**
     * The rest of the chat for a queue that outlives this list (the sheet closes when a file
     * plays): its own reading of the pages after the ones read here, not tied to the sheet's scope.
     */
    private fun feed(): QueueFeed? {
        if (_state.value.done) return null
        val chat = chatId
        var after = last
        return QueueFeed {
            val session = Td.awaitConnectedSessionOrNull() ?: return@QueueFeed null
            val read = after?.takeUnless { it.done } ?: return@QueueFeed null
            val page = withTimeoutOrNull(PAGE_TIMEOUT_MS) { ChatAudio.page(session.client, chat, read) } ?: return@QueueFeed null
            after = page
            if (page.items.isEmpty() && page.done) null else page.items
        }
    }

    private companion object {
        const val PAGE_TIMEOUT_MS = 20_000L
        const val ALL_MAX = 2000
    }
}
