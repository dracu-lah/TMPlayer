package com.tmplayer.ui.browse

import com.tmplayer.data.ChatRepository
import com.tmplayer.data.ForumTopicSummary
import com.tmplayer.data.PageQueries
import com.tmplayer.data.QueryKey
import com.tmplayer.data.Td
import com.tmplayer.data.TopicHit
import com.tmplayer.i18n.Messages

/**
 * Where the search by topic name (U14) asks: Telegram in the app, a fixture in the tests. Only
 * [search] reaches the network; the other two read what this device already holds.
 */
interface TopicSource {
    /** Whether [chatId] is a group with topics turned on, from TDLib's memory; null when not known. */
    suspend fun isForum(chatId: Long): Boolean?

    /** The topics [chatId]'s tabs listed when it was opened, kept by [PageQueries]; null when not kept. Never a call. */
    fun known(chatId: Long): List<ForumTopicSummary>? = null

    /** Telegram's topics of [chatId] whose names answer [query], at most [limit]. Throws when refused. */
    suspend fun search(chatId: Long, query: String, limit: Int): List<ForumTopicSummary>

    companion object {
        val Telegram: TopicSource = object : TopicSource {
            override suspend fun isForum(chatId: Long): Boolean? {
                val session = Td.awaitConnectedSessionOrNull() ?: return null
                return ChatRepository(session.client).isForum(chatId)
            }

            override fun known(chatId: Long): List<ForumTopicSummary>? =
                PageQueries.cache.peek<List<ForumTopicSummary>>(QueryKey.Topics(chatId), PageQueries.CHAT_STALE_MS)?.data?.takeIf { it.isNotEmpty() }

            override suspend fun search(chatId: Long, query: String, limit: Int): List<ForumTopicSummary> {
                val session = Td.awaitConnectedSessionOrNull() ?: throw VideoSearchSource.Offline()
                return ChatRepository(session.client).searchForumTopics(chatId, query, limit)
            }
        }
    }
}

/** A topic hit as its row reads, on every device: "Chat > Topic". */
fun topicHitLabel(hit: TopicHit, s: Messages): String = s.searchTopicRow(chat = hit.chatTitle, topic = hit.topic.name)

/**
 * Opens [chatId] on [topicId]: the topic is noted for this run ([PageQueries.noteTopic]) and
 * remembered as the group's last pick ([com.tmplayer.data.SettingsStore.rememberTopic]), which is
 * what a group's listing opens on ([MediaListViewModel]). The caller then opens the chat as it
 * always does, so the way in is the same as a tap on the chat.
 */
suspend fun openOnTopic(memory: MediaListViewModel.TopicMemory, chatId: Long, topicId: Int) {
    PageQueries.noteTopic(chatId, topicId)
    runCatching { memory.remember(chatId, topicId) }
}
