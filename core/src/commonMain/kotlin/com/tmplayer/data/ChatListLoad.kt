package com.tmplayer.data

import com.tmplayer.platform.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

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
 * Which chats are in which folder, and which folders could not be read (HX32).
 *
 * A folder that failed is in [failed], never an empty folder: the screen says it could not be
 * read and offers Try again, instead of "Nothing in this folder yet".
 */
data class FolderMembership(val byChat: Map<Long, List<Int>> = emptyMap(), val failed: Set<Int> = emptySet())

/**
 * Reading the chat list: what [ChatRepository.syncChats] and [ChatRepository.cachedChats] do,
 * over [ChatListCalls].
 */
object ChatListLoad {

    /** Pages asked of `loadChats`: Telegram's own cap. */
    const val PAGE = 100

    /** How many chats are read from TDLib at once (see [cached]). */
    const val CHUNK = 32

    /**
     * The longest wait for a retry in [cached], which sits on the first frame's path: a flood wait
     * longer than this is reported, not waited out.
     */
    const val FIRST_WAIT_CAP_MS = 3_000L

    /** The longest wait for a retry in [sync], which runs behind a list already on screen. */
    const val SYNC_WAIT_CAP_MS = 60_000L

    /** The pause before the one retry of a failure that names no flood wait. */
    const val RETRY_PAUSE_MS = 1_000L

    private const val TAG = "ChatListLoad"
    private const val UNREADABLE = "The chat list could not be read" // i18n-ok: log text, never shown

    /**
     * Loads the main list to its end (or [limit]) and the archive to its end (or [archiveLimit], a
     * hard ceiling), then reads what TDLib holds ([cached]). The archive is paged like the main
     * list, until TDLib says all of it is local (HX32): one page of it left the archived movie
     * channels of a large account out of the app.
     */
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
        // A main list that ended on a flood wait is not followed by more asking: that only
        // extends the wait. The failure is reported, and the archive read from what is local.
        val waiting = failure != null && Failures.floodWaitSeconds(failure) != null
        val archiveFailure = if (waiting) null else loadArchive(calls, archiveLimit)
        // The folders are not loaded here (HX8): TDLib builds a folder's list from the main list
        // and the archive, so loading one reads both to their end, every chat of the account, page
        // by page and through Telegram's flood waits. The folders' members come from [membership],
        // apart, once the list is on screen.
        val read = cached(calls, limit, known, archiveLimit, SYNC_WAIT_CAP_MS)
        return ChatSync(read.chats, failure, archiveFailure ?: read.archiveFailure, read.dropped)
    }

    /**
     * Pages the archive in, [PAGE] at a time, up to [ceiling] chats. A page that fails is asked
     * once more (after the flood wait its message names), and a second failure ends the loop and
     * is returned; null when the archive is loaded, or the ceiling reached.
     */
    private suspend fun loadArchive(calls: ChatListCalls, ceiling: Int): String? {
        var archived = 0
        var retried = false
        while (archived < ceiling) {
            when (val result = calls.loadChats(ChatListRef.Archive, PAGE)) {
                ChatLoad.Loaded -> {
                    archived += PAGE
                    retried = false
                }
                ChatLoad.AllLoaded -> return null
                is ChatLoad.Failed -> {
                    if (retried || !waitToRetry(result.message, SYNC_WAIT_CAP_MS)) {
                        Logger.w(TAG, "The archive stopped loading after $archived chats: ${result.message}")
                        return result.message
                    }
                    retried = true
                }
            }
        }
        return null
    }

    /**
     * Waits the flood wait [message] names (or a short pause when it names none) before a retry,
     * and says whether to retry: false when the wait is longer than [capMs], where asking again
     * would only extend it.
     */
    private suspend fun waitToRetry(message: String?, capMs: Long): Boolean {
        val seconds = Failures.floodWaitSeconds(message)
        val waitMs = if (seconds != null) seconds * 1000L + 250L else RETRY_PAUSE_MS
        if (waitMs > capMs) return false
        delay(waitMs)
        return true
    }

    private suspend fun <T> tryRead(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        Result.failure(error)
    }

    /**
     * Reads the chat list already in TDLib's database. The `getChat` calls run together, in
     * chunks of [CHUNK]: sequentially they were three hundred round trips on the first frame's
     * path, and all at once hundreds of whole TDLib chat objects live on a stick with a 96 MB
     * heap. Rows in [known] are not read again, and keep the folders they had: which folders a
     * chat is in is read apart ([membership], [withFolders]), since that can mean reading the
     * whole account, and the list waited for it before showing anything (HX8).
     *
     * A failed archive read does not take the main list down, and is not hidden either (HX32): it
     * is retried once, after the flood wait it names if that is within [waitCapMs], and then
     * returned in [ChatSync.archiveFailure], with the archived rows of [known] kept. Chats TDLib
     * listed but would not describe are counted in [ChatSync.dropped] and logged.
     */
    suspend fun cached(
        calls: ChatListCalls,
        limit: Int,
        known: List<ChatSummary>,
        archiveLimit: Int,
        waitCapMs: Long = FIRST_WAIT_CAP_MS,
    ): ChatSync {
        val main = calls.chatIds(ChatListRef.Main, limit)
        var archiveFailure: String? = null
        var archived: List<Long>? = null
        var attempts = 0
        while (archived == null) {
            val read = tryRead { calls.chatIds(ChatListRef.Archive, archiveLimit) }
            archived = read.getOrNull()
            if (archived != null) break
            val message = read.exceptionOrNull()?.message
            if (attempts++ == 0 && waitToRetry(message, waitCapMs)) continue
            archiveFailure = message?.takeIf { it.isNotBlank() } ?: UNREADABLE
            Logger.w(TAG, "The archive could not be read: $archiveFailure", read.exceptionOrNull())
            // What the screen already holds of the archive stays, so a failure never empties it.
            archived = known.filter { it.isArchived }.map { it.id }
        }
        // Main first, so the archive can only ever add to the end.
        val ids: List<Long> = (main + archived.orEmpty()).distinct()
        val myId = calls.myId()
        var dropped = 0
        val summaries = coroutineScope {
            val byId = known.associateBy { it.id }
            val fresh = ids.filterNot { it in byId }
            val fetched = fresh.chunked(CHUNK).flatMap { chunk ->
                val got = chunk.map { id -> async { tryRead { calls.summary(id, myId) }.getOrNull() } }.awaitAll()
                dropped += got.count { it == null }
                got.filterNotNull()
            }
            val wanted = ids.toHashSet()
            val all = known.filter { it.id in wanted } + fetched
            // Back into the order TDLib gave, which the reuse above does not preserve on its own.
            val order = ids.withIndex().associate { (at, id) -> id to at }
            all.sortedBy { order[it.id] ?: Int.MAX_VALUE }
        }
        if (dropped > 0) Logger.w(TAG, "$dropped chats could not be read from TDLib and are left out of the list")
        return ChatSync(arrangeChats(summaries), null, archiveFailure, dropped)
    }

    /**
     * [chats] with the folders [membership] found for each. A folder that could not be read
     * ([FolderMembership.failed]) keeps the chats a row already said it held, rather than losing
     * them.
     */
    fun withFolders(chats: List<ChatSummary>, membership: FolderMembership): List<ChatSummary> = chats.map { chat ->
        val found = membership.byChat[chat.id].orEmpty()
        val belongs = if (membership.failed.isEmpty()) found else (found + chat.folderIds.filter { it in membership.failed }).distinct()
        if (belongs == chat.folderIds) chat else chat.copy(folderIds = belongs)
    }

    /**
     * Which chats are in which folder, asked one folder at a time and in parallel: read this way
     * rather than off each chat, whose own list of folders is only filled in once TDLib has
     * loaded the folder. On a database TDLib has not filled yet this reads the whole account, so
     * it runs behind the list, never ahead of it. A folder whose read fails is in
     * [FolderMembership.failed], not an empty folder (HX32).
     */
    suspend fun membership(calls: ChatListCalls, limit: Int): FolderMembership = coroutineScope {
        val folders = calls.folders()
        if (folders.isEmpty()) return@coroutineScope FolderMembership()
        val byChat = mutableMapOf<Long, MutableList<Int>>()
        val failed = linkedSetOf<Int>()
        folders
            .map { id -> async { id to tryRead<List<Long>> { calls.chatIds(ChatListRef.Folder(id), limit) } } }
            .awaitAll()
            .forEach { (folderId, read) ->
                val chatIds: List<Long>? = read.getOrNull()
                if (chatIds == null) {
                    failed += folderId
                    Logger.w(TAG, "Folder $folderId could not be read", read.exceptionOrNull())
                } else {
                    chatIds.forEach { byChat.getOrPut(it) { mutableListOf() }.add(folderId) }
                }
            }
        FolderMembership(byChat, failed)
    }
}
