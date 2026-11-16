package com.tmplayer.data

import com.tmplayer.i18n.L
import com.tmplayer.platform.Logger
import dev.g000sha256.tdl.TdlClient
import kotlinx.coroutines.flow.first

/**
 * "New videos in starred chats": a notification when a chat the viewer starred has posted videos
 * since the last look, on a phone (a background job every few hours) and on the desktop (every
 * 45 minutes while the app runs). A TV has none: Home's rows already show what is new.
 *
 * What counts as new is per chat: the newest video message id seen at the last check, stored in
 * the settings ([SettingsStore.newVideoSeenNow]). A chat seen for the first time (just starred, or
 * the switch just turned on) only sets its mark, since what it posted before is not news. The size
 * limits and the kids lock apply as they do to the chat's own grid, and with the app lock on
 * ("Ask when the app opens") the notification names no chat and no video.
 */
object NewVideoAlerts {

    /** The most starred chats one check looks at, newest star order aside. */
    const val MAX_CHATS = 24

    /** Messages asked for per chat and kind of post: a check wants the newest few, no more. */
    const val PAGE = 10

    /** The video titles quoted under one chat's notification. */
    private const val MAX_TITLES = 3

    private const val TAG = "NewVideoAlerts"

    /** What one chat has posted since the last check. [titles] are the newest first. */
    data class ChatNews(val chatId: Long, val chat: String, val count: Int, val titles: List<String>)

    /** The news, and the marks to store for the next check. */
    data class Diff(val news: List<ChatNews>, val seen: Map<Long, Long>)

    /** One notification: its title, the line under it, and the chat a press opens (null: the list). */
    data class Alert(val title: String, val body: String?, val chatId: Long?)

    /**
     * Compares each starred chat's newest videos ([latest], by chat, any order) with [seen].
     *
     * A chat missing from [latest] (its fetch failed) keeps its old mark and reports nothing. A chat
     * no longer [starred] loses its mark, so starring it again starts afresh. [keep] is the size
     * limits: a video they hide is not news, but still moves the mark, so it never counts later.
     */
    fun diff(
        starred: Collection<Long>,
        seen: Map<Long, Long>,
        latest: Map<Long, List<MediaItem>>,
        titles: Map<Long, String>,
        keep: (MediaItem) -> Boolean = { true },
    ): Diff {
        val news = mutableListOf<ChatNews>()
        val next = HashMap<Long, Long>()
        for (chatId in starred) {
            val before = seen[chatId]
            val items = latest[chatId]
            if (items == null) {
                before?.let { next[chatId] = it }
                continue
            }
            val newest = items.maxOfOrNull { it.messageId }
            next[chatId] = maxOf(before ?: 0L, newest ?: 0L)
            // First look at this chat: only the mark.
            if (before == null) continue
            val fresh = items.filter { it.messageId > before && keep(it) }
                .distinctBy { it.messageId }
                .sortedByDescending { it.messageId }
            if (fresh.isNotEmpty()) {
                news += ChatNews(
                    chatId = chatId,
                    chat = titles[chatId].orEmpty(),
                    count = fresh.size,
                    titles = fresh.map { it.title }.filter { it.isNotBlank() }.take(MAX_TITLES),
                )
            }
        }
        return Diff(news, next)
    }

    /**
     * The notification for [news], or null when there is none. One chat: "3 new videos in Video
     * Club" over the newest titles, and a press opens that chat. Several: the total over the chats'
     * names, and a press opens the list. [private] (the app lock is on) says only that there are
     * new videos in the starred chats: no names, no titles, no count per chat.
     */
    fun alert(news: List<ChatNews>, private: Boolean): Alert? {
        if (news.isEmpty()) return null
        if (private) return Alert(L.notifyNewVideosPrivate, null, null)
        val single = news.singleOrNull()
        if (single != null) {
            return Alert(
                L.notifyNewVideosInChat(count = single.count, chat = single.chat),
                single.titles.joinToString(", ").ifBlank { null },
                single.chatId,
            )
        }
        return Alert(
            L.notifyNewVideosInChats(count = news.sumOf { it.count }),
            news.map { it.chat }.filter { it.isNotBlank() }.joinToString(", ").ifBlank { null },
            null,
        )
    }

    /**
     * One check: reads the newest videos of each starred chat the kids lock lets through, stores the
     * new marks and returns the notification to post, if any. Off with the switch, it does nothing.
     * Every failure is a quiet no: a chat that cannot be read now is read again next time.
     */
    suspend fun check(td: TdlClient, settings: SettingsStore, allowed: Set<Long>?): Alert? {
        if (!settings.newVideoAlertsNow()) return null
        val starred = KidsLock.favourites(settings.favorites.first(), allowed).take(MAX_CHATS)
        val seen = settings.newVideoSeenNow()
        if (starred.isEmpty()) {
            if (seen.isNotEmpty()) settings.setNewVideoSeen(emptyMap())
            return null
        }
        val repository = ChatRepository(td)
        val latest = HashMap<Long, List<MediaItem>>()
        val titles = HashMap<Long, String>()
        // One chat at a time: a small device has the memory for one page, not two dozen at once.
        for (chatId in starred) {
            runCatching {
                titles[chatId] = td.getChat(chatId).valueOrNull?.title.orEmpty()
                latest[chatId] = repository.mediaPage(chatId, pageSize = PAGE).items
            }.onFailure { Logger.w(TAG, "chat not checked: ${it.message}") }
        }
        // A clip does not ping: the alerts keep a floor of their own (SizeFilter.NOTIFY_MIN).
        val min = SizeFilter.notifyMin(settings.minSizeBytes.first())
        val max = settings.maxSizeBytes.first()
        val diff = diff(starred, seen, latest, titles) { SizeFilter.matches(it.sizeBytes, min, max) }
        settings.setNewVideoSeen(diff.seen)
        val lock = settings.appLock.first()
        return alert(diff.news, private = lock.pinSet && lock.onOpen)
    }

    /** `chat:message;chat:message`, the stored form of the marks. */
    fun encode(seen: Map<Long, Long>): String =
        seen.entries.sortedBy { it.key }.joinToString(";") { "${it.key}:${it.value}" }

    fun decode(text: String?): Map<Long, Long> =
        text.orEmpty().split(';').mapNotNull { part ->
            val (chat, message) = part.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
            (chat.toLongOrNull() ?: return@mapNotNull null) to (message.toLongOrNull() ?: return@mapNotNull null)
        }.toMap()
}
