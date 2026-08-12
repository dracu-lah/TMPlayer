package com.tmplayer.data

import dev.g000sha256.tdl.TdlClient
import dev.g000sha256.tdl.TdlResult
import dev.g000sha256.tdl.dto.Chat
import dev.g000sha256.tdl.dto.ChatListArchive
import dev.g000sha256.tdl.dto.ChatListFolder
import dev.g000sha256.tdl.dto.ChatListMain
import dev.g000sha256.tdl.dto.ChatTypeBasicGroup
import dev.g000sha256.tdl.dto.ChatTypeSupergroup
import dev.g000sha256.tdl.dto.SearchMessagesFilterAnimation
import dev.g000sha256.tdl.dto.SearchMessagesFilterDocument
import dev.g000sha256.tdl.dto.SearchMessagesFilterVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/** How a chat is grouped in the navigation rail. */
enum class ChatKind(val label: String) {
    /**
     * The chat with yourself, which Telegram gives every account and which every Telegram client
     * names rather than showing the account's own name on it.
     *
     * It is worth a kind of its own because it is the one chat people actually use as a video
     * library: a file forwarded to Saved Messages from a phone is the usual way something arrives
     * on this app in the first place. Left as [Direct] it sat somewhere in the middle of People
     * under the viewer's own name, which reads as a stranger who happens to share it.
     */
    Saved("Saved Messages"),
    Channel("Channels"),
    Group("Groups"),
    Direct("People"),
}

/**
 * One row of the chat list.
 *
 * Equality covers every field, including the bytes of the blurred preview. Identity-only equality
 * looked harmless (a chat is its id) but it made a refreshed list compare equal to the old one, so
 * the StateFlow conflated it away and a renamed chat, or one with a new picture, kept its old row
 * until the app was restarted. The array is compared by content rather than by reference for the
 * same reason: TDLib hands back a fresh array every read.
 *
 * [Immutable] is the promise Compose needs to skip a row whose chat has not changed; it is honest
 * here because nothing ever writes into that array after construction.
 */
@androidx.compose.runtime.Immutable
data class ChatSummary(
    val id: Long,
    val title: String,
    val miniThumbnail: ByteArray?,
    val photoFileId: Int,
    val kind: ChatKind,
    /**
     * Pinned in whichever list the chat lives in: the main one, or the archive for a chat that
     * has been put there. Telegram treats the two as separate pins, and a chat is only ever in
     * one of them, so one flag says it without having to name the list.
     */
    val isPinned: Boolean = false,
    /** Archived chats are kept out of every other tab, which is the whole point of archiving one. */
    val isArchived: Boolean = false,
    val unreadCount: Int = 0,
    /** Muted chats keep their unread count but say it quietly, as Telegram itself does. */
    val isMuted: Boolean = false,
    /**
     * The chat folders this chat belongs to, by folder id.
     *
     * Membership is Telegram's answer rather than this app's: a folder is a set of rules about
     * chat types, inclusions and exclusions, and evaluating those here would be a second, worse
     * implementation of something TDLib already keeps up to date. Filled in by asking about each
     * folder in turn, not read off the chat, for the reason [folderMembership] gives.
     */
    val folderIds: List<Int> = emptyList(),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ChatSummary) return false
        return id == other.id &&
            title == other.title &&
            photoFileId == other.photoFileId &&
            kind == other.kind &&
            isPinned == other.isPinned &&
            isArchived == other.isArchived &&
            unreadCount == other.unreadCount &&
            isMuted == other.isMuted &&
            folderIds == other.folderIds &&
            miniThumbnail.contentEquals(other.miniThumbnail)
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + title.hashCode()
        result = 31 * result + photoFileId
        result = 31 * result + kind.hashCode()
        result = 31 * result + isPinned.hashCode()
        result = 31 * result + isArchived.hashCode()
        result = 31 * result + unreadCount
        result = 31 * result + isMuted.hashCode()
        result = 31 * result + folderIds.hashCode()
        result = 31 * result + (miniThumbnail?.contentHashCode() ?: 0)
        return result
    }
}

/**
 * One of the viewer's Telegram folders, as much of it as a tab needs.
 *
 * The rules that decide what is in a folder are deliberately not here. TDLib evaluates them and
 * answers what is in each folder, so this carries only what has to be drawn on a rail item.
 */
@androidx.compose.runtime.Immutable
data class ChatFolderSummary(val id: Int, val title: String)

/**
 * Puts a chat list in the order Telegram itself would draw it.
 *
 * TDLib's own ordering is by position within a list and takes no view on pinning, so a pinned chat
 * that has been quiet for a month sorted below whatever arrived this morning: the exact opposite of
 * what pinning it was for. Saved Messages goes above even the pins, because it is the one chat that
 * is always wanted and never has anything to do with recency.
 *
 * [sortedWith] is stable, so within each of the three groups the order TDLib handed over survives
 * untouched, and nothing here has to know how that order was arrived at.
 */
fun arrangeChats(chats: List<ChatSummary>): List<ChatSummary> =
    chats.sortedWith(compareBy({ it.kind != ChatKind.Saved }, { !it.isPinned }))

/**
 * The result of a sync: the list TDLib now holds, and whatever stopped it being complete.
 *
 * [failure] is null both when every page arrived and when Telegram said there were no more, which
 * are the two ways of succeeding. It carries the raw TDLib message so the caller can decide
 * whether to show it, retry at the delay a flood wait names, or leave the cached list alone.
 */
data class ChatSync(val chats: List<ChatSummary>, val failure: String? = null) {
    val complete: Boolean get() = failure == null
}

/** One page of media, plus the cursors needed to ask for the next one. */
data class MediaPage(
    val items: List<MediaItem>,
    val cursors: MediaCursors,
    val endReached: Boolean,
)

/**
 * Telegram searches one media kind at a time, so a chat's videos are assembled from three
 * parallel searches and each keeps its own position.
 */
data class MediaCursors(
    val video: Long = 0,
    val document: Long = 0,
    val animation: Long = 0,
    val videoDone: Boolean = false,
    val documentDone: Boolean = false,
    val animationDone: Boolean = false,
) {
    val allDone: Boolean get() = videoDone && documentDone && animationDone
}

class ChatRepository(private val td: TdlClient) {

    /**
     * TDLib serves chats out of its local database, so the list has to be pulled from the
     * server first. [loadChats] returns 404 once everything is already local, which is the
     * documented "no more" answer, not a failure.
     *
     * @param known rows the caller already has, so this does not fetch them a second time. A cold
     *   start reads the local list and then syncs, and without this the second pass re-fetched
     *   every chat it had just been handed: eight hundred round trips and eight hundred whole
     *   TDLib chat objects on a device with 96 MB of heap, for a list that had not changed in the
     *   second it took. Anything genuinely new is still fetched, and every row on screen is kept
     *   current by the update collectors rather than by re-reading it.
     */
    suspend fun syncChats(
        limit: Int = CHAT_LIMIT,
        known: List<ChatSummary> = emptyList(),
    ): ChatSync = withContext(Dispatchers.IO) {
        var loaded = 0
        var failure: String? = null
        while (loaded < limit) {
            val result = td.loadChats(ChatListMain(), CHAT_PAGE)
            if (result is TdlResult.Failure) {
                // 404 is TDLib's documented "everything is already local". Everything else is a
                // real failure, and the two were indistinguishable here before: a dropped
                // connection produced a short list that the screen then presented as the whole
                // library, with nothing anywhere saying the sync had not worked.
                if (!result.message.contains("404")) failure = result.message
                break
            }
            loaded += CHAT_PAGE
        }

        // The archive is pulled too, and its failures are ignored on purpose. It is a secondary
        // tab, most accounts keep very little in it, and an archive that would not load is not a
        // reason to tell somebody their chat list is broken.
        var archived = 0
        while (archived < ARCHIVE_LIMIT) {
            if (td.loadChats(ChatListArchive(), CHAT_PAGE) is TdlResult.Failure) break
            archived += CHAT_PAGE
        }

        // Every folder at once rather than one after another. These are server round trips, not
        // database reads, so ten folders in a row put ten latencies between the viewer and a
        // refreshed list, and this runs again every couple of minutes.
        coroutineScope {
            Td.folders.value
                .map { folder -> async { td.loadChats(ChatListFolder(folder.id), CHAT_PAGE) } }
                .awaitAll()
        }

        ChatSync(cachedChats(limit, known), failure)
    }

    /**
     * Reads the chat list already in TDLib's database without requiring a network connection.
     *
     * The three hundred `getChat` calls run together rather than one after another. Sequentially
     * they are three hundred round trips on the first-frame path, which on a stick is most of the
     * wait before anything appears; TDLib answers them out of its own database and is perfectly
     * happy to be asked in parallel.
     */
    suspend fun cachedChats(
        limit: Int = CHAT_LIMIT,
        known: List<ChatSummary> = emptyList(),
    ): List<ChatSummary> = withContext(Dispatchers.IO) {
        val main = td.getChats(ChatListMain(), limit).value().chatIds.toList()
        // Archived chats are read as well, and a failure here is not allowed to take the main list
        // down with it: an account with nothing archived, or a TDLib that has not got round to the
        // archive yet, should still see every chat it has.
        val archived = td.getChats(ChatListArchive(), ARCHIVE_LIMIT).valueOrNull?.chatIds?.toList()
            .orEmpty()
        // Main first, so the archive can only ever add to the end. A chat cannot be in both lists,
        // but distinct() costs nothing and makes that TDLib's problem rather than a duplicate row.
        val ids = (main + archived).distinct()
        // The chat with yourself, which has no flag of its own: Telegram simply gives it the id of
        // the account, so the only way to recognise it is to know who is signed in. Cached by Td,
        // because this used to be a round trip on every pass of a path that runs several times a
        // launch, for an answer that cannot change while the client is alive.
        val myId = Td.myId()

        val summaries = coroutineScope {
            val membership = async { folderMembership() }
            val byId = known.associateBy { it.id }
            val fresh = ids.filterNot { it in byId }

            // Bounded, and reduced to a summary inside each chunk. Four hundred `getChat` calls in
            // flight at once meant four hundred whole TDLib chat objects held live until the last
            // of them landed, each carrying a last message with its own text and thumbnails, on a
            // stick with a 96 MB heap. In chunks the peak is [CHAT_CHUNK] of them and the rest is
            // the small row this screen actually draws. It is no slower: the limit is the stick,
            // not TDLib, which answers all of these out of its own database.
            val fetched = fresh.chunked(CHAT_CHUNK).flatMap { chunk ->
                chunk.map { id -> async { td.getChat(id).valueOrNull } }
                    .awaitAll()
                    .filterNotNull()
                    .map { chat -> summarise(chat, myId) }
            }

            val folders = membership.await()
            // Reused rows still have their folders reapplied. Membership is read per folder rather
            // than off each chat, so a row fetched before the folder lists were loaded is not left
            // permanently outside every folder it belongs to.
            val all = (known.filter { it.id in ids.toSet() } + fetched).map { chat ->
                val belongs = folders[chat.id].orEmpty()
                if (belongs == chat.folderIds) chat else chat.copy(folderIds = belongs)
            }
            // Back into the order TDLib gave, which the reuse above does not preserve on its own.
            val order = ids.withIndex().associate { (at, id) -> id to at }
            all.sortedBy { order[it.id] ?: Int.MAX_VALUE }
        }
        arrangeChats(summaries)
    }

    /**
     * Which chats are in which folder, asked of Telegram one folder at a time and in parallel.
     *
     * Read this way rather than off each chat's own list of the lists it belongs to, which is what
     * this did first. That field is only filled in once TDLib has been asked to load the folder in
     * question, so on a cold start, where the chats are read before the folders have arrived, every
     * folder came out empty and stayed empty until something else triggered a sync. These are local
     * database reads and there are as many of them as the viewer has folders, which is single
     * figures.
     */
    private suspend fun folderMembership(): Map<Long, List<Int>> = coroutineScope {
        val folders = Td.folders.value
        if (folders.isEmpty()) return@coroutineScope emptyMap()
        val byChat = mutableMapOf<Long, MutableList<Int>>()
        folders
            .map { folder ->
                async {
                    folder.id to td.getChats(ChatListFolder(folder.id), CHAT_LIMIT)
                        .valueOrNull?.chatIds?.toList().orEmpty()
                }
            }
            .awaitAll()
            .forEach { (folderId, chatIds) ->
                chatIds.forEach { byChat.getOrPut(it) { mutableListOf() }.add(folderId) }
            }
        byChat
    }

    private fun summarise(chat: Chat, myId: Long): ChatSummary {
        val saved = myId != 0L && chat.id == myId
        // Whichever list this chat is actually in. A chat has at most one of the two, and asking
        // about the wrong one is how a pinned archived chat loses its pin on the way here.
        val position = chat.positions.firstOrNull {
            it.list is ChatListMain || it.list is ChatListArchive
        }
        return ChatSummary(
            id = chat.id,
            title = if (saved) "Saved Messages" else chat.title.ifBlank { "Chat ${chat.id}" },
            miniThumbnail = chat.photo?.minithumbnail?.data,
            photoFileId = chat.photo?.small?.id ?: 0,
            kind = if (saved) ChatKind.Saved else kindOf(chat.type),
            isPinned = position?.isPinned == true,
            isArchived = chat.positions.any { it.list is ChatListArchive },
            unreadCount = chat.unreadCount,
            // muteFor is a count of seconds still to run, so anything above zero is muted now.
            // useDefaultMuteFor means the account-wide setting decides, and this app has no way to
            // change that setting, so a chat that has never been muted individually reads as unmuted.
            isMuted = chat.notificationSettings?.let {
                !it.useDefaultMuteFor && it.muteFor > 0
            } == true,
            // Folders are filled in by the caller, from Telegram's own per folder answer.
        )
    }

    /**
     * The ids TDLib currently holds, in its own order, and nothing else.
     *
     * The order is the point: TDLib sorts the main list by position itself, so a caller that
     * already has the rows can put them in today's order for one round trip, without the
     * `getChat` fan-out that makes [cachedChats] the expensive half of a sync.
     */
    suspend fun chatOrder(limit: Int = CHAT_LIMIT): List<Long> = withContext(Dispatchers.IO) {
        val main = td.getChats(ChatListMain(), limit).valueOrNull?.chatIds?.toList() ?: return@withContext emptyList()
        // The archive belongs in the answer even though nothing reorders within it. The caller
        // matches this against every row it holds and gives up if any of them is missing, so a
        // main-list-only answer would silently stop the whole screen from ever reordering.
        val archived = td.getChats(ChatListArchive(), ARCHIVE_LIMIT).valueOrNull?.chatIds?.toList()
            .orEmpty()
        (main + archived).distinct()
    }

    /**
     * Next page of playable media in [chatId], newest first.
     *
     * The three searches run concurrently and are merged by message id, which is monotonic
     * per chat, so a straight descending sort restores true chronological order.
     */
    suspend fun mediaPage(
        chatId: Long,
        cursors: MediaCursors = MediaCursors(),
        query: String = "",
    ): MediaPage =
        // Off Main for the whole page. The mapper below asks the filesystem twice per message to
        // decide whether the file is already on disk, and this runs three searches of forty
        // messages each inside a loop that will do it up to eight times: nearly two thousand
        // syscalls, and every one of them was landing on the thread drawing the grid.
        withContext(Dispatchers.IO) {
        coroutineScope {
            val videos = async {
                if (cursors.videoDone) null
                else search(chatId, cursors.video, SearchMessagesFilterVideo(), query)
            }
            val documents = async {
                if (cursors.documentDone) null
                else search(chatId, cursors.document, SearchMessagesFilterDocument(), query)
            }
            val animations = async {
                if (cursors.animationDone) null
                else search(chatId, cursors.animation, SearchMessagesFilterAnimation(), query)
            }

            val videoResult = videos.await()
            val documentResult = documents.await()
            val animationResult = animations.await()

            val items = buildList {
                videoResult?.let { addAll(it.items) }
                documentResult?.let { addAll(it.items) }
                animationResult?.let { addAll(it.items) }
            }.distinctBy { it.messageId }.sortedByDescending { it.messageId }

            val next = cursors.copy(
                video = videoResult?.next ?: cursors.video,
                document = documentResult?.next ?: cursors.document,
                animation = animationResult?.next ?: cursors.animation,
                videoDone = cursors.videoDone || videoResult?.done ?: true,
                documentDone = cursors.documentDone || documentResult?.done ?: true,
                animationDone = cursors.animationDone || animationResult?.done ?: true,
            )
            MediaPage(items, next, next.allDone)
        }
        }

    /**
     * Pins or unpins a chat, in whichever of the two lists it currently lives in.
     *
     * These next three write to the viewer's actual Telegram account, and every one of them shows
     * up on their phone a second later. They are here because the tabs invite them: a screen with
     * an Archived tab and pins at the top of every list is one somebody will immediately try to
     * pin something into, and having to reach for a phone to do it is the app admitting it is only
     * half a client. All three are reversible from the same menu, which is why these and not, say,
     * leaving a chat.
     */
    suspend fun setPinned(chat: ChatSummary, pinned: Boolean): TdlResult<dev.g000sha256.tdl.dto.Ok> =
        withContext(Dispatchers.IO) {
            val list = if (chat.isArchived) ChatListArchive() else ChatListMain()
            td.toggleChatIsPinned(list, chat.id, pinned)
        }

    suspend fun setArchived(chatId: Long, archived: Boolean): TdlResult<dev.g000sha256.tdl.dto.Ok> =
        withContext(Dispatchers.IO) {
            // Telegram has no "unarchive": a chat is moved from one list to the other, and the
            // main list is where it goes back to.
            td.addChatToList(chatId, if (archived) ChatListArchive() else ChatListMain())
        }

    /**
     * Clears a chat's unread count without opening it.
     *
     * `forceRead` is what makes this work from a list. Without it TDLib treats the call as a claim
     * that the messages were actually on screen, and a chat whose messages this app has never
     * fetched keeps its count.
     */
    suspend fun markRead(chatId: Long): TdlResult<dev.g000sha256.tdl.dto.Ok> =
        withContext(Dispatchers.IO) {
            td.viewMessages(
                chatId = chatId,
                messageIds = longArrayOf(),
                source = null,
                forceRead = true,
            )
        }

    /**
     * A supergroup is Telegram's one type for both broadcast channels and large groups; only the
     * `isChannel` flag separates them, and getting it wrong puts a video channel under "Groups".
     */
    private fun kindOf(type: dev.g000sha256.tdl.dto.ChatType): ChatKind = when (type) {
        is ChatTypeSupergroup -> if (type.isChannel) ChatKind.Channel else ChatKind.Group
        is ChatTypeBasicGroup -> ChatKind.Group
        else -> ChatKind.Direct
    }

    private class SearchResult(val items: List<MediaItem>, val next: Long, val done: Boolean)

    private suspend fun search(
        chatId: Long,
        fromMessageId: Long,
        filter: dev.g000sha256.tdl.dto.SearchMessagesFilter,
        query: String = "",
    ): SearchResult {
        val found = td.searchChatMessages(
            chatId = chatId,
            topicId = null,
            // Telegram matches a document search against its file name, which is exactly how
            // videos are named in these chats.
            query = query,
            senderId = null,
            fromMessageId = fromMessageId,
            offset = 0,
            limit = PAGE_SIZE,
            filter = filter,
        ).value()

        val items = found.messages.mapNotNull { MediaMapper.fromMessage(it) }
        // nextFromMessageId is 0 once the chat has no older matches left.
        val done = found.messages.isEmpty() || found.nextFromMessageId == 0L
        return SearchResult(items, found.nextFromMessageId, done)
    }

    companion object {
        const val PAGE_SIZE = 40
        const val CHAT_LIMIT = 300

        /**
         * Rather fewer than the main list gets.
         *
         * The archive is where chats go to be out of the way, so it is read to fill a tab nobody
         * opens often rather than to be browsed three hundred deep, and every id in it costs a
         * `getChat` on the cold-start path that the main list is already paying for.
         */
        const val ARCHIVE_LIMIT = 100
        private const val CHAT_PAGE = 100

        /**
         * How many chats are read from TDLib at once.
         *
         * Wide enough that the round trips overlap and the fan-out is still most of a second
         * faster than doing them one by one, narrow enough that the whole chat list is never live
         * in memory at the same moment.
         */
        private const val CHAT_CHUNK = 32
    }
}
