package com.tmplayer.data

import dev.g000sha256.tdl.TdlClient
import dev.g000sha256.tdl.TdlResult
import dev.g000sha256.tdl.dto.ChatListMain
import dev.g000sha256.tdl.dto.SearchMessagesFilterAnimation
import dev.g000sha256.tdl.dto.SearchMessagesFilterDocument
import dev.g000sha256.tdl.dto.SearchMessagesFilterVideo
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

data class ChatSummary(
    val id: Long,
    val title: String,
    val miniThumbnail: ByteArray?,
    val photoFileId: Int,
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

class ChatRepository(private val td: TdlClient = Td.client) {

    /**
     * TDLib serves chats out of its local database, so the list has to be pulled from the
     * server first. [loadChats] returns 404 once everything is already local — that is the
     * documented "no more" answer, not a failure.
     */
    suspend fun chats(limit: Int = CHAT_LIMIT): List<ChatSummary> {
        var loaded = 0
        while (loaded < limit) {
            val result = td.loadChats(ChatListMain(), CHAT_PAGE)
            if (result is TdlResult.Failure) break
            loaded += CHAT_PAGE
        }

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
                    ),
                )
            }
        }
    }

    /**
     * Next page of playable media in [chatId], newest first.
     *
     * The three searches run concurrently and are merged by message id, which is monotonic
     * per chat — so a straight descending sort restores true chronological order.
     */
    suspend fun mediaPage(chatId: Long, cursors: MediaCursors = MediaCursors()): MediaPage =
        coroutineScope {
            val videos = async {
                if (cursors.videoDone) null
                else search(chatId, cursors.video, SearchMessagesFilterVideo())
            }
            val documents = async {
                if (cursors.documentDone) null
                else search(chatId, cursors.document, SearchMessagesFilterDocument())
            }
            val animations = async {
                if (cursors.animationDone) null
                else search(chatId, cursors.animation, SearchMessagesFilterAnimation())
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

    private class SearchResult(val items: List<MediaItem>, val next: Long, val done: Boolean)

    private suspend fun search(
        chatId: Long,
        fromMessageId: Long,
        filter: dev.g000sha256.tdl.dto.SearchMessagesFilter,
    ): SearchResult {
        val found = td.searchChatMessages(
            chatId = chatId,
            topicId = null,
            query = "",
            senderId = null,
            fromMessageId = fromMessageId,
            offset = 0,
            limit = PAGE_SIZE,
            filter = filter,
        ).valueOrNull ?: return SearchResult(emptyList(), fromMessageId, done = true)

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
