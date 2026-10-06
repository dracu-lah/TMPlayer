package com.tmplayer.ui.browse

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tmplayer.data.ChatRepository
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.HomeRow
import com.tmplayer.data.HomeRows
import com.tmplayer.data.MediaItem
import com.tmplayer.data.ResumeRecord
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.SizeFilter
import com.tmplayer.data.Td
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Where Home's videos come from: Telegram in the app, a fixture in the promo build and the tests. */
interface HomeSource {
    /** A starred chat's newest videos, newest first. */
    suspend fun chatMedia(chatId: Long): List<MediaItem>

    /** The newest videos across every chat. */
    suspend fun recentMedia(): List<MediaItem>

    /** The video behind a Continue watching record, for its picture; null when Telegram has no answer. */
    suspend fun mediaItem(chatId: Long, messageId: Long): MediaItem?
}

/** The signed in account, through TDLib. */
object TdHomeSource : HomeSource {
    private suspend fun repository() = ChatRepository(Td.awaitAuthorizedSession().client)

    override suspend fun chatMedia(chatId: Long): List<MediaItem> =
        repository().mediaPage(chatId, pageSize = CHAT_PAGE).items

    override suspend fun recentMedia(): List<MediaItem> = repository().recentMedia()

    override suspend fun mediaItem(chatId: Long, messageId: Long): MediaItem? =
        repository().mediaItem(chatId, messageId)

    /**
     * Per search, three searches a chat: room for a row of ten after the size limits and a show
     * folding several episodes into one tile, and a fraction of a full grid page.
     */
    private const val CHAT_PAGE = 20
}

/**
 * Home's videos, fetched a row at a time as the rows come on screen.
 *
 * Nothing is asked for up front: a row asks with [request] when the lazy list first composes it,
 * so somebody with thirty starred chats pays for the three on screen, and a 1 GB television is
 * never holding thirty pages of thumbnails at once. At most [PARALLEL] fetches run together.
 *
 * What arrives is kept for the life of the screen, so scrolling back up does not ask again;
 * [refresh] starts over.
 */
class HomeViewModel(
    private val source: HomeSource = TdHomeSource,
    /** The size limits from Settings, applied as in a chat's grid; read per fetch so a change lands on refresh. */
    private val sizeLimits: suspend () -> Pair<Long, Long> = { SizeFilter.DEFAULT_MIN to SizeFilter.DEFAULT_MAX },
) : ViewModel() {

    private val _loaded = MutableStateFlow<Map<Long, List<MediaItem>>>(emptyMap())

    /** Each starred chat's videos, once fetched. A chat missing here has not answered yet. */
    val loaded: StateFlow<Map<Long, List<MediaItem>>> = _loaded.asStateFlow()

    private val _recent = MutableStateFlow<List<MediaItem>?>(null)

    /** The newest videos across every chat, or null until they arrive. */
    val recent: StateFlow<List<MediaItem>?> = _recent.asStateFlow()

    private val _art = MutableStateFlow<Map<String, MediaItem>>(emptyMap())

    /** Continue watching videos as Telegram describes them, by progress key, for their pictures. */
    val art: StateFlow<Map<String, MediaItem>> = _art.asStateFlow()

    private val gate = Semaphore(PARALLEL)
    private val asked = HashSet<String>()
    private val jobs = mutableListOf<Job>()

    /** Fetches a starred chat's row, once. */
    fun request(chatId: Long) = once("chat:$chatId") {
        val items = runCatching { source.chatMedia(chatId) }.getOrElse { failure ->
            if (failure is CancellationException) throw failure
            emptyList()
        }
        val kept = withinLimits(items)
        _loaded.update { it + (chatId to kept) }
    }

    /** Fetches "Recently added across chats", once. */
    fun requestRecent() = once("recent") {
        val items = runCatching { source.recentMedia() }.getOrElse { failure ->
            if (failure is CancellationException) throw failure
            emptyList()
        }
        _recent.value = withinLimits(items)
    }

    /** Looks up a Continue watching tile's picture, once. A miss leaves the tile its neutral art. */
    fun requestArt(record: ResumeRecord) {
        val key = SettingsStore.progressKey(record.chatId, record.messageId)
        once("art:$key") {
            val item = runCatching { source.mediaItem(record.chatId, record.messageId) }.getOrElse { failure ->
                if (failure is CancellationException) throw failure
                null
            } ?: return@once
            _art.update { it + (key to item) }
        }
    }

    /** Forgets everything fetched, so every row on screen asks again. */
    fun refresh() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        asked.clear()
        _loaded.value = emptyMap()
        _recent.value = null
    }

    private fun once(key: String, fetch: suspend () -> Unit) {
        if (!asked.add(key)) return
        jobs += viewModelScope.launch { gate.withPermit { fetch() } }
        jobs.removeAll { it.isCompleted }
    }

    private suspend fun withinLimits(items: List<MediaItem>): List<MediaItem> {
        val (min, max) = runCatching { sizeLimits() }.getOrDefault(SizeFilter.DEFAULT_MIN to SizeFilter.DEFAULT_MAX)
        return items.filter { SizeFilter.matches(it.sizeBytes, min, max) }
    }

    companion object {
        /** Rows fetched at once: enough to fill a screen quickly, few enough to stay polite to Telegram. */
        const val PARALLEL = 3
    }
}

/**
 * Home's rows for what is on screen now, rebuilt only when one of the inputs changes.
 *
 * @param chats the chat list, which decides the starred rows' order.
 */
@Composable
fun rememberHomeRows(
    model: HomeViewModel,
    chats: List<ChatSummary>,
    favourites: Set<Long>,
    continueWatching: List<ResumeRecord>,
    seriesView: Boolean,
): List<HomeRow> {
    val loaded by model.loaded.collectAsState()
    val recent by model.recent.collectAsState()
    val starred = remember(chats, favourites) { HomeRows.favouriteChats(chats, favourites) }
    return remember(continueWatching, starred, loaded, recent, seriesView) {
        HomeRows.build(continueWatching, starred, loaded, recent, seriesView)
    }
}
