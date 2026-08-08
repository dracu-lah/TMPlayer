package com.tmplayer.data

import dev.g000sha256.tdl.TdlClient
import dev.g000sha256.tdl.TdlResult
import dev.g000sha256.tdl.dto.ChatListMain
import dev.g000sha256.tdl.dto.ChatTypeBasicGroup
import dev.g000sha256.tdl.dto.ChatTypeSupergroup
import dev.g000sha256.tdl.dto.SearchMessagesFilterAnimation
import dev.g000sha256.tdl.dto.SearchMessagesFilterDocument
import dev.g000sha256.tdl.dto.SearchMessagesFilterVideo
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** How a chat is grouped in the navigation rail. */
enum class ChatKind(val label: String) {
    Channel("Channels"),
    Group("Groups"),
    Direct("People"),
}

data class ChatSummary(
    val id: Long,
    val title: String,
    val miniThumbnail: ByteArray?,
    val photoFileId: Int,
    val kind: ChatKind,
) {
    override fun equals(other: Any?) = other is ChatSummary && other.id == id
    override fun hashCode() = id.hashCode()
}

/** One page of media, plus the cursors needed to ask for the next one. */
data class MediaPage(
    val items: List<MediaItem>,
    val cursors: MediaCursors,
    val endReached: Boolean,
)

/**
 * Telegram searches one media kind at a time, so a chat's movies are assembled from three
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
    suspend fun syncChats(limit: Int = CHAT_LIMIT): List<ChatSummary> {
        var loaded = 0
        while (loaded < limit) {
            val result = td.loadChats(ChatListMain(), CHAT_PAGE)
            if (result is TdlResult.Failure) break
            loaded += CHAT_PAGE
        }

        return cachedChats(limit)
    }

    /** Reads the chat list already in TDLib's database without requiring a network connection. */
    suspend fun cachedChats(limit: Int = CHAT_LIMIT): List<ChatSummary> {
        val ids = td.getChats(ChatListMain(), limit).value().chatIds
        return buildList {
            for (id in ids) {
                val chat = td.getChat(id).valueOrNull ?: continue
                add(
                    ChatSummary(
                        id = chat.id,
                        title = chat.title.ifBlank { "Chat $id" },
                        miniThumbnail = chat.photo?.minithumbnail?.data,
                        photoFileId = chat.photo?.small?.id ?: 0,
                        kind = kindOf(chat.type),
                    ),
                )
            }
        }
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

    /**
     * A supergroup is Telegram's one type for both broadcast channels and large groups; only the
     * `isChannel` flag separates them, and getting it wrong puts a film channel under "Groups".
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
            // films are named in these chats.
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
