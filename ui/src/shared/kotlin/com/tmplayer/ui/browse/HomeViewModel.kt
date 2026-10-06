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
import com.tmplayer.data.LocalFileAvailability
import com.tmplayer.data.MediaItem
import com.tmplayer.data.ResumeRecord
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.SizeFilter
import com.tmplayer.data.Td
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.Flow
import com.tmplayer.data.batched
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

    /**
     * [chatMedia], and whether that reached the chat's oldest video. A source that cannot tell says
     * it did, so a row only offers "See all" for what it can show is there.
     */
    suspend fun chatMediaPage(chatId: Long): Pair<List<MediaItem>, Boolean> = chatMedia(chatId) to true

    /** The newest videos across every chat. */
    suspend fun recentMedia(): List<MediaItem>

    /** The video behind a Continue watching record, for its picture; null when Telegram has no answer. */
    suspend fun mediaItem(chatId: Long, messageId: Long): MediaItem?

    /** Whether [item]'s file is whole on this device now, rather than when it was fetched. */
    suspend fun wholeOnDevice(item: MediaItem): Boolean = item.onDevice

    /** The chats whose videos changed while Home is open, a window at a time. None by default. */
    fun changedChats(): Flow<Set<Long>> = emptyFlow()

    /** Asks for [chatId]'s new posts as they happen until the handle is closed; null where nothing follows them. */
    suspend fun follow(chatId: Long): AutoCloseable? = null
}

/** The signed in account, through TDLib. */
object TdHomeSource : HomeSource {
    private suspend fun repository() = ChatRepository(Td.awaitAuthorizedSession().client)

    override suspend fun chatMedia(chatId: Long): List<MediaItem> =
        repository().mediaPage(chatId, pageSize = CHAT_PAGE).items

    override suspend fun chatMediaPage(chatId: Long): Pair<List<MediaItem>, Boolean> =
        repository().mediaPage(chatId, pageSize = CHAT_PAGE).let { it.items to it.endReached }

    override suspend fun recentMedia(): List<MediaItem> = repository().recentMedia()

    override suspend fun mediaItem(chatId: Long, messageId: Long): MediaItem? =
        repository().mediaItem(chatId, messageId)

    override suspend fun wholeOnDevice(item: MediaItem): Boolean =
        Td.localFileAvailability(item.fileId) == LocalFileAvailability.Complete

    override fun changedChats(): Flow<Set<Long>> =
        Td.videoChanges.batched(LIVE_WINDOW_MS).map { batches -> batches.mapTo(HashSet()) { it.chatId } }

    override suspend fun follow(chatId: Long): AutoCloseable? {
        Td.awaitAuthorizedSession()
        return Td.watchChat(chatId)
    }

    /** Longer than a grid's: a row fetched again is a search, and Home has several rows to keep. */
    private const val LIVE_WINDOW_MS = 3_000L

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

    private val _partial = MutableStateFlow<Set<Long>>(emptySet())

    /** The starred chats whose fetch stopped short of their oldest video: they have more than Home holds. */
    val partial: StateFlow<Set<Long>> = _partial.asStateFlow()

    private val _recent = MutableStateFlow<List<MediaItem>?>(null)

    /** The newest videos across every chat, or null until they arrive. */
    val recent: StateFlow<List<MediaItem>?> = _recent.asStateFlow()

    private val _art = MutableStateFlow<Map<String, MediaItem>>(emptyMap())

    /** Continue watching videos as Telegram describes them, by progress key, for their pictures. */
    val art: StateFlow<Map<String, MediaItem>> = _art.asStateFlow()

    private val gate = Semaphore(PARALLEL)
    private val asked = HashSet<String>()
    private val jobs = mutableListOf<Job>()

    /** The starred chats held open in TDLib for their new posts, as their rows were fetched. */
    private val following = LinkedHashMap<Long, AutoCloseable>()

    init {
        // A post in a starred chat refreshes its row, and the recent row that draws from those
        // chats, in place: the old tiles stay up until the new ones arrive.
        viewModelScope.launch {
            source.changedChats().collect { chats ->
                val rows = chats.filter { it in _loaded.value }
                if (rows.isEmpty()) return@collect
                rows.forEach { chatId ->
                    asked -= "chat:$chatId"
                    request(chatId)
                }
                if (_recent.value != null) {
                    asked -= "recent"
                    requestRecent()
                }
            }
        }
    }

    override fun onCleared() {
        following.values.forEach { runCatching { it.close() } }
        following.clear()
    }

    /** Follows [chatId]'s new posts while Home is open, for as many rows as [FOLLOW_MAX] allows. */
    private fun follow(chatId: Long) {
        if (chatId in following || following.size >= FOLLOW_MAX) return
        viewModelScope.launch {
            val handle = runCatching { source.follow(chatId) }.getOrNull() ?: return@launch
            if (chatId in following || following.size >= FOLLOW_MAX) handle.close() else following[chatId] = handle
        }
    }

    /** Fetches a starred chat's row, once (again after a post in it). */
    fun request(chatId: Long) = once("chat:$chatId") {
        follow(chatId)
        val (items, ended) = runCatching { source.chatMediaPage(chatId) }.getOrElse { failure ->
            if (failure is CancellationException) throw failure
            emptyList<MediaItem>() to true
        }
        val kept = withinLimits(items)
        _partial.update { if (ended) it - chatId else it + chatId }
        _loaded.update { it + (chatId to kept) }
    }

    /** Fetches the newest videos across every chat, once, for "Recently added in starred chats" (see [HomeRows]). */
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
        _partial.value = emptySet()
        _recent.value = null
    }

    /**
     * Reads every tile's Cached badge off the disk again, without fetching anything.
     *
     * What arrives is kept for the life of the screen, badges included, and the watch cache holds
     * one video: playing another took the last one's copy away while its tile went on saying
     * Cached. Called when the screen comes back from the player and when the cache record changes.
     */
    fun refreshLocalAvailability() {
        availabilityJob?.cancel()
        availabilityJob = viewModelScope.launch {
            val answers = HashMap<Int, Boolean>()
            suspend fun recheckOne(item: MediaItem): MediaItem {
                val whole = answers.getOrPut(item.fileId) {
                    runCatching { source.wholeOnDevice(item) }.getOrElse { failure ->
                        if (failure is CancellationException) throw failure
                        item.onDevice
                    }
                }
                return item.recheckedCache(whole)
            }
            suspend fun recheckAll(items: List<MediaItem>): List<MediaItem> {
                val updated = items.map { recheckOne(it) }
                return if (updated.indices.all { updated[it] === items[it] }) items else updated
            }

            val loadedBefore = _loaded.value
            val recentBefore = _recent.value
            val artBefore = _art.value
            val loaded = loadedBefore.mapValues { (_, items) -> recheckAll(items) }
            val recent = recentBefore?.let { recheckAll(it) }
            val art = artBefore.mapValues { (_, item) -> recheckOne(item) }
            // Written only over what is still the list that was checked: a row fetched meanwhile
            // came after the change and is right as it stands.
            _loaded.update { now ->
                now.mapValues { (chatId, items) ->
                    if (items === loadedBefore[chatId]) loaded.getValue(chatId) else items
                }
            }
            _recent.update { now -> if (now != null && now === recentBefore) recent else now }
            _art.update { now ->
                now.mapValues { (key, item) -> if (item === artBefore[key]) art.getValue(key) else item }
            }
        }
    }

    private var availabilityJob: Job? = null

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

        /**
         * Starred chats held open for their posts at once. An open channel is one TDLib keeps in
         * step with the server, which is not free on a 1 GB stick; the first rows are the ones seen.
         */
        const val FOLLOW_MAX = 12
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
    val partial by model.partial.collectAsState()
    val starred = remember(chats, favourites) { HomeRows.favouriteChats(chats, favourites) }
    return remember(continueWatching, starred, loaded, recent, seriesView, partial) {
        HomeRows.build(continueWatching, starred, loaded, recent, seriesView, partial)
    }
}
