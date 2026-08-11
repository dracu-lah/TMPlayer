package com.tmplayer.data

import dev.g000sha256.tdl.TdlClient
import dev.g000sha256.tdl.TdlResult
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
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ChatSummary) return false
        return id == other.id &&
            title == other.title &&
            photoFileId == other.photoFileId &&
            kind == other.kind &&
            miniThumbnail.contentEquals(other.miniThumbnail)
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + title.hashCode()
        result = 31 * result + photoFileId
        result = 31 * result + kind.hashCode()
        result = 31 * result + (miniThumbnail?.contentHashCode() ?: 0)
        return result
    }
}

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
     */
    suspend fun syncChats(limit: Int = CHAT_LIMIT): ChatSync = withContext(Dispatchers.IO) {
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

        ChatSync(cachedChats(limit), failure)
    }

    /**
     * Reads the chat list already in TDLib's database without requiring a network connection.
     *
     * The three hundred `getChat` calls run together rather than one after another. Sequentially
     * they are three hundred round trips on the first-frame path, which on a stick is most of the
     * wait before anything appears; TDLib answers them out of its own database and is perfectly
     * happy to be asked in parallel.
     */
    suspend fun cachedChats(limit: Int = CHAT_LIMIT): List<ChatSummary> = withContext(Dispatchers.IO) {
        val ids = td.getChats(ChatListMain(), limit).value().chatIds
        coroutineScope {
            ids.map { id -> async { td.getChat(id).valueOrNull } }
                .awaitAll()
                .filterNotNull()
                .map { chat ->
                    ChatSummary(
                        id = chat.id,
                        title = chat.title.ifBlank { "Chat ${chat.id}" },
                        miniThumbnail = chat.photo?.minithumbnail?.data,
                        photoFileId = chat.photo?.small?.id ?: 0,
                        kind = kindOf(chat.type),
                    )
                }
        }
    }

    /**
     * The ids TDLib currently holds, in its own order, and nothing else.
     *
     * The order is the point: TDLib sorts the main list by position itself, so a caller that
     * already has the rows can put them in today's order for one round trip, without the
     * `getChat` fan-out that makes [cachedChats] the expensive half of a sync.
     */
    suspend fun chatOrder(limit: Int = CHAT_LIMIT): List<Long> = withContext(Dispatchers.IO) {
        td.getChats(ChatListMain(), limit).valueOrNull?.chatIds?.toList() ?: emptyList()
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
        private const val CHAT_PAGE = 100
    }
}
