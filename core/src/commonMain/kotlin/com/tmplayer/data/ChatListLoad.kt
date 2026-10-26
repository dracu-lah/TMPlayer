package com.tmplayer.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** One of the chat lists TDLib keeps: the main list, the archive, or a folder. */
sealed class ChatListRef {
    data object Main : ChatListRef()
    data object Archive : ChatListRef()
    data class Folder(val id: Int) : ChatListRef()
}

/** How one `loadChats` went. */
sealed class ChatLoad {
    /** A page came in; there may be more. */
    data object Loaded : ChatLoad()

    /** TDLib's 404: every chat of that list is already local, the documented "no more". */
    data object AllLoaded : ChatLoad()

    /** Anything else, with TDLib's own message (a flood wait carries its seconds in it). */
    data class Failed(val message: String) : ChatLoad()
}

/**
 * The few TDLib calls the chat list is read with ([ChatListLoad]), so that reading can be run
 * against a synthetic account in the tests as well as against TDLib ([ChatRepository]).
 */
interface ChatListCalls {
    suspend fun loadChats(list: ChatListRef, limit: Int): ChatLoad

    /** `getChats`: the first [limit] ids of [list], in TDLib's order. Throws when TDLib fails. */
    suspend fun chatIds(list: ChatListRef, limit: Int): List<Long>

    /** One chat as the list draws it, read from TDLib's memory; null when TDLib has no answer. */
    suspend fun summary(chatId: Long, myId: Long): ChatSummary?

    /** The account's own id, which is Saved Messages' chat id; 0 when not known. */
    suspend fun myId(): Long

    /** The ids of the account's folders. */
    fun folders(): List<Int>
}

/**
 * Reading the chat list: what [ChatRepository.syncChats] and [ChatRepository.cachedChats] do,
 * over [ChatListCalls].
 */
object ChatListLoad {

    /** Pages asked of `loadChats`: Telegram's own cap. */
    const val PAGE = 100

    /** How many chats are read from TDLib at once (see [cached]). */
    const val CHUNK = 32

    suspend fun sync(
        calls: ChatListCalls,
        limit: Int,
        known: List<ChatSummary>,
        archiveLimit: Int,
    ): ChatSync {
        var loaded = 0
        var failure: String? = null
        while (loaded < limit) {
            val result = calls.loadChats(ChatListRef.Main, PAGE)
            if (result is ChatLoad.Failed) {
                failure = result.message
                break
            }
            if (result is ChatLoad.AllLoaded) break
            loaded += PAGE
        }
        var archived = 0
        while (archived < archiveLimit) {
            if (calls.loadChats(ChatListRef.Archive, PAGE) !is ChatLoad.Loaded) break
            archived += PAGE
        }
        // The folders are not loaded here (HX8): TDLib builds a folder's list from the main list
        // and the archive, so loading one reads both to their end, every chat of the account, page
        // by page and through Telegram's flood waits. The folders' members come from [membership],
        // apart, once the list is on screen.
        return ChatSync(cached(calls, limit, known, archiveLimit), failure)
    }

    /**
     * Reads the chat list already in TDLib's database. The `getChat` calls run together, in
     * chunks of [CHUNK]: sequentially they were three hundred round trips on the first frame's
     * path, and all at once hundreds of whole TDLib chat objects live on a stick with a 96 MB
     * heap. Rows in [known] are not read again, and keep the folders they had: which folders a
     * chat is in is read apart ([membership], [withFolders]), since that can mean reading the
     * whole account, and the list waited for it before showing anything (HX8).
     */
    suspend fun cached(
        calls: ChatListCalls,
        limit: Int,
        known: List<ChatSummary>,
        archiveLimit: Int,
    ): List<ChatSummary> {
        val main = calls.chatIds(ChatListRef.Main, limit)
        // A failure here is not allowed to take the main list down with it.
        val archived = runCatching { calls.chatIds(ChatListRef.Archive, archiveLimit) }.getOrNull().orEmpty()
        // Main first, so the archive can only ever add to the end.
        val ids = (main + archived).distinct()
        val myId = calls.myId()
        val summaries = coroutineScope {
            val byId = known.associateBy { it.id }
            val fresh = ids.filterNot { it in byId }
            val fetched = fresh.chunked(CHUNK).flatMap { chunk ->
                chunk.map { id -> async { calls.summary(id, myId) } }.awaitAll().filterNotNull()
            }
            val wanted = ids.toHashSet()
            val all = known.filter { it.id in wanted } + fetched
            // Back into the order TDLib gave, which the reuse above does not preserve on its own.
            val order = ids.withIndex().associate { (at, id) -> id to at }
            all.sortedBy { order[it.id] ?: Int.MAX_VALUE }
        }
        return arrangeChats(summaries)
    }

    /** [chats] with the folders [membership] found for each. */
    fun withFolders(chats: List<ChatSummary>, membership: Map<Long, List<Int>>): List<ChatSummary> = chats.map { chat ->
        val belongs = membership[chat.id].orEmpty()
        if (belongs == chat.folderIds) chat else chat.copy(folderIds = belongs)
    }

    /**
     * Which chats are in which folder, asked one folder at a time and in parallel: read this way
     * rather than off each chat, whose own list of folders is only filled in once TDLib has
     * loaded the folder. On a database TDLib has not filled yet this reads the whole account, so
     * it runs behind the list, never ahead of it.
     */
    suspend fun membership(calls: ChatListCalls, limit: Int): Map<Long, List<Int>> = coroutineScope {
        val folders = calls.folders()
        if (folders.isEmpty()) return@coroutineScope emptyMap()
        val byChat = mutableMapOf<Long, MutableList<Int>>()
        folders
            .map { id -> async { id to runCatching { calls.chatIds(ChatListRef.Folder(id), limit) }.getOrNull().orEmpty() } }
            .awaitAll()
            .forEach { (folderId, chatIds) -> chatIds.forEach { byChat.getOrPut(it) { mutableListOf() }.add(folderId) } }
        byChat
    }
}
