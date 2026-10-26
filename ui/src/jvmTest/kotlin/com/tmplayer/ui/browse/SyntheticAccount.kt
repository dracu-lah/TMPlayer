package com.tmplayer.ui.browse

import com.tmplayer.data.ChatKind
import com.tmplayer.data.ChatListCalls
import com.tmplayer.data.ChatListRef
import com.tmplayer.data.ChatLoad
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.FeedLane
import com.tmplayer.data.MediaCursors
import com.tmplayer.data.MediaItem
import com.tmplayer.data.MediaPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A large Telegram account and a model of TDLib in front of it, for measuring how Home, the chat
 * list and the other lists behave at scale (HX8): reuse it for any list or feed (AGENTS.md, Scale).
 *
 * The account: [mainChats] chats in the main list and [archivedChats] in the archive, [folders]
 * folders over them, [videoChats] chats with videos (100,000 in all, a few with 20,000 each),
 * [starred] starred chats, Saved Messages and five pinned chats. Some chats post a video every
 * [postEveryMs] while the test runs.
 *
 * The TDLib model, in virtual time:
 * - every server call takes a latency, and runs on [scope] as TDLib runs a request: a caller that
 *   stops waiting does not take it back;
 * - Telegram answers bursts with FLOOD_WAIT, per method, from a token bucket: [Flood];
 * - TDLib waits a flood wait out itself and asks again while the waits add up to at most
 *   [TDLIB_WAIT_LIMIT_S], then answers "Too Many Requests: retry after N"; the chat list's own
 *   loading (`getChats`, `loadChats`) is always waited out;
 * - a folder's list is built from the main list and the archive, as TDLib builds it: asking for
 *   more of a folder than it holds reads both lists to their end;
 * - while the connection is down ([offline]) server calls wait for it to come back.
 */
class SyntheticAccount(
    parent: CoroutineScope,
    private val now: () -> Long,
    val mainChats: Int = 3_000,
    val archivedChats: Int = 1_500,
    val videoChats: Int = 400,
    val starredCount: Int = 40,
    val postEveryMs: Long = 90_000,
    /** True for a TDLib database that has the chat list already (a warm start). */
    val warm: Boolean = false,
) {
    /** Where TDLib's requests run: one that fails does not take the others, or the test, with it. */
    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))

    /** Per method: [capacity] calls at once, then one more every [refillMs], else FLOOD_WAIT of [waitS] seconds, longer each time it is hit again soon. */
    class Flood(val capacity: Int, val refillMs: Long, val waitS: Int) {
        var tokens = capacity.toDouble()
        var at = 0L
        var strikes = 0
        var lastStrike = -1_000_000L
    }

    private val floods = mapOf(
        "search" to Flood(capacity = 30, refillMs = 1_000, waitS = 15),
        "global" to Flood(capacity = 6, refillMs = 10_000, waitS = 30),
        "dialogs" to Flood(capacity = 10, refillMs = 3_000, waitS = 30),
    )

    val calls = LinkedHashMap<String, Int>()
    var floodHits = 0
    var floodWaitedMs = 0L
    var tooManyRequests = 0
    var inFlight = 0
    var maxInFlight = 0

    /** The connection is down while this is true. */
    val offline = MutableStateFlow(false)

    private fun count(type: String) {
        calls[type] = (calls[type] ?: 0) + 1
    }

    fun total(): Int = calls.values.sum()

    /** A server call of [method]: latency, the connection, FLOOD_WAIT and TDLib's own waiting. */
    private suspend fun <T> server(type: String, method: String, latencyMs: Long, internalWait: Boolean = false, answer: () -> T): T =
        scope.async { attempt(type, method, latencyMs, internalWait, answer) }.await()

    private suspend fun <T> attempt(type: String, method: String, latencyMs: Long, internalWait: Boolean, answer: () -> T): T {
        inFlight++
        maxInFlight = maxOf(maxInFlight, inFlight)
        try {
            var waited = 0
            while (true) {
                if (offline.value) offline.first { !it }
                count(type)
                delay(latencyMs)
                val wait = floodWait(method)
                if (wait == 0) return answer()
                floodHits++
                if (!internalWait && waited + wait > TDLIB_WAIT_LIMIT_S) {
                    tooManyRequests++
                    throw IllegalStateException("Too Many Requests: retry after $wait")
                }
                waited += wait
                floodWaitedMs += wait * 1000L
                delay(wait * 1000L)
            }
        } finally {
            inFlight--
        }
    }

    private fun floodWait(method: String): Int {
        val f = floods[method] ?: return 0
        val t = now()
        f.tokens = minOf(f.capacity.toDouble(), f.tokens + (t - f.at).toDouble() / f.refillMs)
        f.at = t
        if (f.tokens >= 1.0) {
            f.tokens -= 1.0
            return 0
        }
        f.strikes = if (t - f.lastStrike < 60_000) f.strikes + 1 else 0
        f.lastStrike = t
        return minOf(f.waitS * (1 + f.strikes), 300)
    }

    // ---- the account --------------------------------------------------------------------------

    val savedId = 10L
    val pinned = (11L..15L).toSet()
    val starred: Set<Long> = (1..starredCount).map { 20L + it * ((videoChats - 30) / starredCount) }.toSet()

    private fun archivedId(i: Int) = 100_000L + i
    private fun isChannel(id: Long) = id % 5 == 0L || id % 5 == 1L

    fun summary(id: Long): ChatSummary = ChatSummary(
        id = id,
        title = if (id == savedId) "Saved Messages" else "Chat $id",
        miniThumbnail = null,
        photoFileId = 0,
        kind = when {
            id == savedId -> ChatKind.Saved
            isChannel(id) -> ChatKind.Channel
            else -> ChatKind.Group
        },
        isPinned = id in pinned,
        isArchived = id >= 100_000L,
    )

    /** The whole main list as the app would end up holding it, in Telegram's order. */
    fun mainList(): List<ChatSummary> = (1L..mainChats).map(::summary)

    /** Videos in [chatId]: a few huge chats, the rest a hundred or so, none past [videoChats]. */
    fun videoCount(chatId: Long): Int = when {
        chatId > videoChats || chatId >= 100_000L -> 0
        chatId <= 3 -> 20_000
        else -> 40_000 / (videoChats - 3)
    } + (posted[chatId] ?: 0)

    val totalVideos: Int get() = (1L..videoChats).sumOf { videoCount(it) }

    private val posted = HashMap<Long, Int>()
    private val lastPost = HashMap<Long, Long>()

    /** Chats that post a video every [postEveryMs]: the busiest video channels. */
    val posting: Set<Long> = (1L..60L).toSet()

    private val changes = MutableSharedFlow<Set<Long>>(extraBufferCapacity = 64)

    /** Starts the posting chats posting; changes come in windows of three seconds, as [TdHomeSource.changedChats] batches them. */
    fun startPosting(followed: () -> Set<Long>) {
        scope.launch {
            while (true) {
                delay(3_000)
                val t = now()
                val window = HashSet<Long>()
                posting.forEach { chat ->
                    val due = (lastPost[chat] ?: (chat * 1_500L)) + postEveryMs
                    if (t >= due) {
                        lastPost[chat] = t
                        posted[chat] = (posted[chat] ?: 0) + 1
                        if (chat in followed()) window += chat
                    }
                }
                if (window.isNotEmpty()) changes.tryEmit(window)
            }
        }
    }

    fun changedChats(): Flow<Set<Long>> = changes

    private fun dateOf(chatId: Long, index: Int): Int {
        val newest = 2_000_000_000 - (if (chatId in posting) 0 else chatId.toInt() * 50)
        return newest - index * 600 - (chatId % 97).toInt()
    }

    /** The video at [index] of [chatId], newest first; documents every third and fourth of ten. */
    private fun item(chatId: Long, index: Int): MediaItem {
        val count = videoCount(chatId)
        val messageId = (count - index).toLong() * 16
        return MediaItem(
            chatId = chatId, messageId = messageId, fileId = (chatId * 100_000 + messageId).toInt(),
            title = "Show $chatId S01E${index % 20 + 1}", sizeBytes = 400L * 1024 * 1024, durationSec = 1_400,
            mimeType = "video/mp4", thumbnailFileId = 0, miniThumbnail = null, date = dateOf(chatId, index),
            fileName = "Show.$chatId.S01E${(index % 20 + 1).toString().padStart(2, '0')}.mkv",
        )
    }

    private fun isDocument(index: Int) = index % 10 >= 7

    /** A chat full of files that are not videos: three of every four documents are screened out. */
    private fun fileChat(chatId: Long) = chatId > 300

    /** `searchChatMessages` with one filter, from [from] (0 for the newest), at most [limit]. */
    suspend fun searchChat(chatId: Long, filter: String, from: Long, limit: Int): Pair<List<MediaItem>, Long> =
        server("searchChatMessages", "search", latencyMs = 250) {
            val count = videoCount(chatId)
            if (filter == "animation" || count == 0) return@server emptyList<MediaItem>() to 0L
            val start = if (from == 0L) 0 else (count - (from / 16).toInt())
            val out = ArrayList<MediaItem>()
            var i = start
            var matched = 0
            while (i < count && matched < limit) {
                val doc = isDocument(i)
                if ((filter == "document") == doc) {
                    matched++
                    // A document that is not a video counts as a match Telegram sent, and is screened out here.
                    if (!(doc && fileChat(chatId) && i % 4 != 0)) out += item(chatId, i)
                }
                i++
            }
            out to (if (i >= count) 0L else item(chatId, i).messageId)
        }

    /** One page of [chatId] as [com.tmplayer.data.ChatRepository.mediaPage] reads it: three searches at once. */
    suspend fun mediaPage(chatId: Long, cursors: MediaCursors, pageSize: Int): MediaPage = coroutineScope {
        val v = async { if (cursors.videoDone) null else searchChat(chatId, "video", cursors.video, pageSize) }
        val d = async { if (cursors.documentDone) null else searchChat(chatId, "document", cursors.document, pageSize) }
        val a = async { if (cursors.animationDone) null else searchChat(chatId, "animation", cursors.animation, pageSize) }
        val (vr, dr, ar) = listOf(v, d, a).awaitAll()
        val items = (vr?.first.orEmpty() + dr?.first.orEmpty()).distinctBy { it.messageId }.sortedByDescending { it.messageId }
        val next = cursors.copy(
            video = vr?.second ?: cursors.video, document = dr?.second ?: cursors.document,
            videoDone = cursors.videoDone || vr?.second == 0L, documentDone = cursors.documentDone || dr?.second == 0L,
            animationDone = cursors.animationDone || ar != null,
        )
        MediaPage(items, next, next.allDone)
    }

    /** `searchMessages` across every chat with one filter: the newest [limit] across the account. */
    suspend fun searchAll(filter: String, limit: Int): List<MediaItem> = server("searchMessages", "global", latencyMs = 400) {
        val heads = (1L..videoChats).flatMap { chat -> (0 until 8).map { chat to it } }
            .filter { (chat, i) -> isDocument(i) == (filter == "document") && i < videoCount(chat) }
            .sortedByDescending { (chat, i) -> dateOf(chat, i) }
            .take(limit)
        heads.map { (chat, i) -> item(chat, i) }
    }

    /** `getMessage`, which TDLib answers from its database for anything seen. */
    suspend fun message(chatId: Long, messageId: Long): MediaItem? {
        count("getMessage")
        delay(2)
        val count = videoCount(chatId)
        val index = count - (messageId / 16).toInt()
        return if (index in 0 until count) item(chatId, index) else null
    }

    /** Home's source over this account, reading a chat as [TdHomeSource] does ([HomeReads]). */
    /** The chats Home holds open for their new posts. */
    val followed = HashSet<Long>()

    val homeSource = object : HomeSource {
        override suspend fun chatMedia(chatId: Long) = chatMediaRead(chatId).first
        override suspend fun chatMediaRead(chatId: Long): Pair<List<MediaItem>, FeedLane?> =
            HomeReads.read(chatId) { cursors -> mediaPage(chatId, cursors, HomeReads.CHAT_PAGE) }
        override suspend fun chatMediaFrom(chatId: Long, cursors: MediaCursors) = mediaPage(chatId, cursors, 40)
        override suspend fun recentMedia(): List<MediaItem> = coroutineScope {
            listOf(async { searchAll("video", 40) }, async { searchAll("document", 40) }).awaitAll().flatten()
                .sortedByDescending { it.date }
        }
        override suspend fun mediaItem(chatId: Long, messageId: Long) = message(chatId, messageId)
        override fun changedChats(): Flow<Set<Long>> = changes
        override suspend fun follow(chatId: Long): AutoCloseable {
            count("openChat")
            followed += chatId
            return AutoCloseable { followed -= chatId }
        }
        override suspend fun chatSummaries(ids: Collection<Long>): List<ChatSummary> = ids.mapNotNull { id ->
            count("getChat")
            delay(1)
            if (id <= mainChats || id == savedId) summary(id) else null
        }
    }

    // ---- the chat list, as TDLib loads it -----------------------------------------------------

    private var mainLoaded = if (warm) mainChats else 0
    private var archiveLoaded = if (warm) archivedChats else 0
    private val listLoader = Mutex()

    /** The folders: every channel, and three of chats picked by hand. */
    val folders: Map<Int, (Long) -> Boolean> = mapOf(
        1 to { id: Long -> id < 100_000L && isChannel(id) },
        2 to { id: Long -> id in 20L..49L },
        3 to { id: Long -> id in 60L..64L },
        4 to { id: Long -> id in setOf(archivedId(3), 70L, 71L, 72L) },
    )

    /** One `getDialogs` page of the main list or the archive, as TDLib loads it. */
    private suspend fun loadPage(archive: Boolean) {
        listLoader.withLock {
            if (archive && archiveLoaded >= archivedChats) return
            if (!archive && mainLoaded >= mainChats) return
            server("getDialogs", "dialogs", latencyMs = 400, internalWait = true) {
                if (archive) archiveLoaded = minOf(archivedChats, archiveLoaded + 100) else mainLoaded = minOf(mainChats, mainLoaded + 100)
            }
        }
    }

    private fun mainIds(n: Int) = (1L..minOf(n, mainLoaded).toLong()).toList()
    private fun archiveIds(n: Int) = (1..minOf(n, archiveLoaded)).map(::archivedId)
    private fun folderIds(id: Int) = (mainIds(mainLoaded) + archiveIds(archiveLoaded)).filter(folders.getValue(id))

    val listCalls = object : ChatListCalls {
        override suspend fun loadChats(list: ChatListRef, limit: Int): ChatLoad {
            count("loadChats")
            when (list) {
                ChatListRef.Main -> if (mainLoaded >= mainChats) return ChatLoad.AllLoaded else loadPage(false)
                ChatListRef.Archive -> if (archiveLoaded >= archivedChats) return ChatLoad.AllLoaded else loadPage(true)
                is ChatListRef.Folder -> when {
                    mainLoaded < mainChats -> loadPage(false)
                    archiveLoaded < archivedChats -> loadPage(true)
                    else -> return ChatLoad.AllLoaded
                }
            }
            return ChatLoad.Loaded
        }

        override suspend fun chatIds(list: ChatListRef, limit: Int): List<Long> {
            count("getChats")
            when (list) {
                ChatListRef.Main -> {
                    while (mainLoaded < minOf(limit, mainChats)) loadPage(false)
                    return mainIds(limit)
                }
                ChatListRef.Archive -> {
                    while (archiveLoaded < minOf(limit, archivedChats)) loadPage(true)
                    return archiveIds(limit)
                }
                is ChatListRef.Folder -> {
                    // The chats picked by hand first (getPeerDialogs), then both lists until the folder has enough.
                    server("getPeerDialogs", "dialogs", latencyMs = 400, internalWait = true) { }
                    while (folderIds(list.id).size < limit && (mainLoaded < mainChats || archiveLoaded < archivedChats)) {
                        loadPage(archive = mainLoaded >= mainChats)
                    }
                    return folderIds(list.id).take(limit)
                }
            }
        }

        override suspend fun summary(chatId: Long, myId: Long): ChatSummary? {
            count("getChat")
            delay(1)
            return if (chatId >= 100_000L) summary(chatId).copy(isArchived = true) else summary(chatId)
        }

        override suspend fun myId(): Long = savedId

        override fun folders(): List<Int> = folders.keys.toList()
    }

    companion object {
        /** How long TDLib waits flood waits out for one request before handing back a 429. */
        const val TDLIB_WAIT_LIMIT_S = 60
    }
}
