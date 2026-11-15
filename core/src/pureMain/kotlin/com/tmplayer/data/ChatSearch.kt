package com.tmplayer.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

/**
 * The search inside one chat, the pure half: what to ask Telegram, what to match here, and how the
 * answers are put together. [ChatSearchRun] is one search through it.
 *
 * Why it is more than one `searchChatMessages`: Telegram matches the words of a file name as it
 * splits them, and a release name such as `Resident.Evil.Welcome.To.Raccoon.City.2021.1080p.mkv`
 * or `Harbour_Notes_2024.mkv` is not split where a viewer types spaces. The search across every
 * chat answered that long ago (spellings with dots and underscores, and a local half over the
 * videos the app has seen, [GlobalSearch]); the chat's own search box had neither, so it said
 * "nothing matches" for a video the search across chats found in that very chat. Now it has:
 * - a local first answer from what is loaded (the chat's grid, Home, Continue watching), matched
 *   here with every word as a prefix in any order, shown at once;
 * - Telegram asked with the query as typed, as words, and in the release name spellings;
 * - when Telegram cannot be asked (offline, a long flood wait, a failure) or finds nothing, the
 *   chat's history read backwards a bounded number of pages and matched here, with "Search deeper"
 *   to go on from where it stopped.
 */
object ChatSearch {

    /** Messages asked of Telegram per filter and page. */
    const val PAGE = 50

    /** Messages per `getChatHistory` call while reading the chat back; Telegram's own cap is 100. */
    const val SCAN_PAGE = 100

    /** History pages read per round: the newest 500 messages, then 500 more per Search deeper. */
    const val SCAN_PAGES = 5

    /** The longest FLOOD_WAIT a search sits out, once; past it the history is read instead. */
    const val MAX_FLOOD_WAIT_S = 30

    /** Words shorter than this are not asked of Telegram alone: "the" and "s02" match half a chat. */
    const val MIN_WORD = 3

    /** Single words tried after the whole query, longest first. */
    const val MAX_WORDS = 3

    /** The query tidied as the search across chats tidies it: trimmed, single spaced, at most 80 characters. */
    fun clean(raw: String): String = GlobalSearch.clean(raw)

    /**
     * What to ask Telegram, in order: the query as typed, its words with the punctuation taken out,
     * joined by underscores and by dots (release names), then its longest words alone. Each once.
     */
    fun spellings(raw: String): List<String> {
        val typed = clean(raw)
        if (typed.isEmpty()) return emptyList()
        val words = Fuzzy.tokens(typed)
        // Telegram's search ignores case, so two spellings that differ only by it are one question.
        val out = LinkedHashMap<String, String>()
        fun ask(spelling: String) {
            val key = spelling.lowercase()
            if (spelling.isNotBlank() && key !in out) out[key] = spelling
        }
        ask(typed)
        ask(words.joinToString(" "))
        if (words.size >= 2) {
            ask(words.joinToString("_"))
            ask(words.joinToString("."))
            words.filter { it.length >= MIN_WORD }.distinct().sortedByDescending { it.length }.take(MAX_WORDS).forEach(::ask)
        }
        return out.values.toList()
    }

    /**
     * Whether [text] answers [raw] the strict way, for the matches found here: every word of the
     * query starts a word of the text, in any order. Case, accents, dots, underscores, dashes and
     * plus signs do not count ([Fuzzy.normalize]).
     */
    fun matches(text: String, raw: String): Boolean {
        val words = Fuzzy.tokens(raw)
        if (words.isEmpty()) return false
        val have = Fuzzy.tokens(text)
        if (have.isEmpty()) return false
        return words.all { word -> have.any { it.startsWith(word) } }
    }

    /** [matches] over everything a search inside a chat is matched against ([MediaItem.searchText]). */
    fun matches(item: MediaItem, raw: String): Boolean = matches(item.searchText, raw)

    /**
     * The videos of [chatId] (and [topicId], 0 for all) among [known] that answer [raw], best first,
     * each once by message id. What is already loaded, so it is shown before Telegram answers.
     */
    fun local(known: List<MediaItem>, chatId: Long, raw: String, topicId: Int = 0): List<MediaItem> {
        if (Fuzzy.tokens(raw).isEmpty()) return emptyList()
        val mine = known.asSequence()
            .filter { it.chatId == chatId && (topicId == 0 || it.topicId == topicId) }
            .distinctBy { it.messageId }
            .filter { matches(it, raw) }
            .toList()
        return rank(mine, raw)
    }

    /** Telegram's answer ranked against what was typed, dropping what does not answer it at all. */
    fun rank(items: List<MediaItem>, raw: String): List<MediaItem> = Fuzzy.rank(items, raw) { it.searchText }

    /**
     * [page] added under [shown]: what is listed stays where it is (no card moves under the
     * viewer's finger), and what is new goes after it, in its own order, each once by message id.
     */
    fun merge(shown: List<MediaItem>, page: List<MediaItem>): List<MediaItem> {
        if (page.isEmpty()) return shown
        val seen = shown.mapTo(HashSet()) { it.messageId }
        val fresh = page.filter { seen.add(it.messageId) }
        return if (fresh.isEmpty()) shown else shown + fresh
    }
}

/** How a chat search found what it lists. */
enum class ChatSearchHow {
    /** Only what was loaded already: Telegram has not answered yet. */
    Local,

    /** Telegram's search, which can page on. */
    Server,

    /** The chat's history read back and matched here: Telegram's search found nothing or failed. */
    Scanned,

    /** No connection: what TDLib keeps on this device, read back and matched here. */
    Offline,
}

/** What a chat search lists now. */
data class ChatSearchAnswer(
    val items: List<MediaItem>,
    val how: ChatSearchHow,
    /** No more to ask for by scrolling: Telegram's pages ran out, or the history was read to the start. */
    val endReached: Boolean,
    /** Messages of the history read so far ([ChatSearchHow.Scanned] and [ChatSearchHow.Offline]). */
    val scanned: Int = 0,
    /** The history read stopped at its bound before the chat's start: Search deeper goes on. */
    val canGoDeeper: Boolean = false,
    /** The last thing asked failed outright (nothing at all could be read): Try again. */
    val failed: Throwable? = null,
)

/** One page of a chat's history, read back from [ChatHistoryPage.next]. */
data class ChatHistoryPage(
    /** The videos among the messages read. */
    val items: List<MediaItem>,
    /** Messages read, videos or not. */
    val read: Int,
    /** Where the next page starts (the oldest message read), 0 once the chat's start is reached. */
    val next: Long,
    /** The photos of albums among the messages read, so an album split across two pages is still put together. */
    val photos: List<AlbumPhoto> = emptyList(),
)

/** What a chat search asks TDLib for. Each call throws when it fails. */
interface ChatSearchSource {
    /** `searchChatMessages` for [query] with the video, document and animation filters, from [cursors]. */
    suspend fun search(query: String, cursors: MediaCursors, limit: Int): MediaPage

    /** `getChatHistory` back from [fromMessageId] (0 for the newest), at most [limit] messages; only from disk when [onlyLocal]. */
    suspend fun history(fromMessageId: Long, limit: Int, onlyLocal: Boolean): ChatHistoryPage

    /** False when Telegram cannot be reached now. */
    suspend fun online(): Boolean
}

/**
 * One search of one chat for [typed], through [source]. Not shared between threads: the view model
 * runs one at a time and drops it when the words change, so a stale answer never lands.
 *
 * TDLib calls are bounded: [first] makes at most [ChatSearch.spellings] searches (four to seven,
 * of three filters each) and, beside them, one round of history pages (500 messages, seven calls
 * at most), [more] one page, [deeper] one round. A flood wait is waited out once, by this search alone; another one, or a
 * longer one, reads the history instead, so retries stop by themselves.
 */
class ChatSearchRun(
    private val source: ChatSearchSource,
    typed: String,
    known: List<MediaItem>,
    private val chatId: Long,
    private val topicId: Int = 0,
    /** Waits out a flood wait; the tests pass virtual time. */
    private val wait: suspend (Long) -> Unit = { delay(it) },
) {
    val query: String = ChatSearch.clean(typed)

    private var items: List<MediaItem> = ChatSearch.local(known, chatId, query, topicId)
    private var how = ChatSearchHow.Local
    private var spelling = ""
    private var cursors = MediaCursors()
    private var serverDone = false
    private var scanFrom = 0L
    private var scanned = 0
    private var scanDone = false
    private var floodWaited = false

    /** The albums' photos met while reading the history, and the albumed videos still waiting for theirs. */
    private val albumPhotos = HashMap<Long, AlbumPhoto>()
    private var pendingAlbumVideos: List<MediaItem> = emptyList()

    /** Whether this search is reading the chat's history rather than paging Telegram's answer. */
    val readingHistory: Boolean get() = how == ChatSearchHow.Scanned || how == ChatSearchHow.Offline

    /** What is listed before anything is asked: the matches among what is loaded. */
    fun local(): ChatSearchAnswer = answer()

    /**
     * The first answer, added under the local one: Telegram's search and, beside it, the newest
     * part of the history read and matched here, which finds the release names Telegram's word
     * search cannot see. When Telegram finds nothing or fails, the history is the answer, and
     * Search deeper reads on; offline it is what TDLib keeps on this device.
     */
    suspend fun first(): ChatSearchAnswer {
        if (query.isEmpty()) return answer().copy(endReached = true)
        if (!source.online()) {
            how = ChatSearchHow.Offline
            val (read, failure) = round(onlyLocal = true)
            items = ChatSearch.merge(items, read)
            return answer().copy(failed = failure)
        }
        return coroutineScope {
            val history = async { round(onlyLocal = false) }
            val told = askTelegram()
            val (read, failure) = history.await()
            if (!told.isNullOrEmpty()) {
                how = ChatSearchHow.Server
                items = ChatSearch.merge(ChatSearch.merge(items, told), read)
                answer()
            } else {
                how = ChatSearchHow.Scanned
                items = ChatSearch.merge(items, read)
                answer().copy(failed = failure)
            }
        }
    }

    /**
     * Each spelling in turn until one finds something that answers the query: those, ranked, with
     * the cursors kept for [more]. Empty when none did, null when Telegram could not be asked.
     */
    private suspend fun askTelegram(): List<MediaItem>? {
        for (word in ChatSearch.spellings(query)) {
            val page = ask(word, MediaCursors()) ?: return null
            val found = ChatSearch.rank(page.items, query)
            if (found.isEmpty()) continue
            spelling = word
            cursors = page.cursors
            serverDone = page.endReached
            return found
        }
        return emptyList()
    }

    /** The next page, as the viewer scrolls: Telegram's next one. The history goes on only by [deeper]. */
    suspend fun more(): ChatSearchAnswer {
        if (how != ChatSearchHow.Server || serverDone) return answer()
        val page = ask(spelling, cursors) ?: return answer().copy(failed = lastFailure ?: IllegalStateException("search failed"))
        cursors = page.cursors
        serverDone = page.endReached
        items = ChatSearch.merge(items, ChatSearch.rank(page.items, query))
        return answer()
    }

    /** Search deeper: another round of the history, from where the last one stopped. */
    suspend fun deeper(): ChatSearchAnswer {
        // Only a search that is reading the history has a deeper to go to.
        if (scanDone || !readingHistory) return answer()
        val (read, failure) = round(onlyLocal = how == ChatSearchHow.Offline)
        items = ChatSearch.merge(items, read)
        return answer().copy(failed = failure)
    }

    private var lastFailure: Throwable? = null

    /** One search, a flood wait waited out once; null when it failed. */
    private suspend fun ask(word: String, from: MediaCursors): MediaPage? {
        while (true) {
            try {
                return source.search(word, from, ChatSearch.PAGE)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastFailure = e
                val seconds = Failures.floodWaitSeconds(e.message)
                if (seconds != null && seconds in 1..ChatSearch.MAX_FLOOD_WAIT_S && !floodWaited) {
                    floodWaited = true
                    wait(seconds * 1_000L)
                    continue
                }
                return null
            }
        }
    }

    /**
     * A round of the history, newest first from where the last round stopped: the matches, and
     * the failure when not one page could be read (said with Try again).
     */
    private suspend fun round(onlyLocal: Boolean): Pair<List<MediaItem>, Throwable?> {
        var failure: Throwable? = null
        var pages = 0
        var read = 0
        var found: List<MediaItem> = emptyList()
        // Bounded by messages and by calls: TDLib's first page of a chat it has not opened is often
        // a single message, so a round counts what it read, with a couple of calls to spare.
        while (read < ChatSearch.SCAN_PAGES * ChatSearch.SCAN_PAGE && pages < ChatSearch.SCAN_PAGES + 2 && !scanDone) {
            val page = try {
                source.history(scanFrom, ChatSearch.SCAN_PAGE, onlyLocal)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = e
                break
            }
            pages++
            read += page.read.coerceAtLeast(0)
            scanned += page.read.coerceAtLeast(0)
            // An album's photo may sit on the page before or after its videos: the videos of earlier
            // pages that waited for theirs are folded again with every page's photos.
            if (page.photos.isNotEmpty()) albumPhotos.putAll(AlbumPosts.pick(page.photos))
            val folded = AlbumPosts.fold(page.items + pendingAlbumVideos, albumPhotos)
            pendingAlbumVideos = folded.filter { it.albumId != 0L && it.albumPhotoFileId == 0 && !it.hasCover }
                .sortedByDescending { it.messageId }.take(PENDING_ALBUM_VIDEOS)
            found = ChatSearch.merge(found, ChatSearch.local(folded, chatId, query, topicId))
            // An empty page, or one that does not move back, is the chat's start: never asked again.
            if (page.read <= 0 || page.next <= 0L || (scanFrom != 0L && page.next >= scanFrom)) {
                scanDone = true
            } else {
                scanFrom = page.next
            }
        }
        return found to (if (pages == 0) failure else null)
    }

    private companion object {
        /** Albumed videos kept to meet their photo on a page read later; the oldest are let go. */
        const val PENDING_ALBUM_VIDEOS = 200
    }

    private fun answer(): ChatSearchAnswer = when (how) {
        ChatSearchHow.Server -> ChatSearchAnswer(items, how, endReached = serverDone)
        ChatSearchHow.Local -> ChatSearchAnswer(items, how, endReached = false)
        else -> ChatSearchAnswer(items, how, endReached = true, scanned = scanned, canGoDeeper = !scanDone)
    }
}
