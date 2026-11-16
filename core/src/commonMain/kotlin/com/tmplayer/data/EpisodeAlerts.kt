package com.tmplayer.data

import com.tmplayer.i18n.L
import com.tmplayer.platform.Logger
import dev.g000sha256.tdl.TdlClient
import kotlinx.coroutines.flow.first

/**
 * "New episodes of shows you watch" (CP76): "Episode 5 of Harbour Notes is in Video Club", with
 * Play, when a show the viewer is watching posts an episode they have not seen. Narrower than
 * [NewVideoAlerts], which says every new video in a starred chat: this one says only the next
 * episodes of shows already under way, in whichever chat posts them.
 *
 * A show is under way when Continue watching holds one of its episodes, or one is marked watched
 * ([shows]). The chats read are those the shows were watched in and the starred ones, at most
 * [MAX_CHATS], each a page of its newest videos, as [NewVideoAlerts] reads them. What is new is
 * per chat ([Diff.seen]): a chat seen for the first time only sets its mark. An episode counts
 * when it is past the furthest one watched in its season, or in a later season, and has not been
 * announced before ([toldKey]), so the same episode posted in two chats is said once. The kids lock
 * leaves its hidden chats unread, and with the app lock on ("Ask when the app opens") the
 * notification names no show, no chat and offers no Play.
 */
object EpisodeAlerts {

    const val MAX_CHATS = 24
    const val PAGE = 10

    /** How many announced episodes are remembered. */
    const val TOLD_LIMIT = 96

    private const val TAG = "EpisodeAlerts"

    /** A video the viewer has started or watched, as the records keep it. */
    data class Watching(val chatId: Long, val fileName: String?, val title: String, val caption: String?)

    /** A show under way: the furthest episode watched in each season, and the chats it was in. */
    data class Show(val key: String, val title: String, val furthest: Map<Int, Int>, val chats: Set<Long>) {
        val lastSeason: Int get() = furthest.keys.maxOrNull() ?: 0

        /** Whether season [season], episode [episode] is past everything watched. */
        fun isNext(season: Int, episode: Int): Boolean {
            val reached = furthest[season]
            return if (reached != null) episode > reached else season > lastSeason
        }
    }

    /** One new episode found. */
    data class Found(val item: MediaItem, val chat: String, val show: Show, val season: Int, val episode: Int)

    data class Diff(val found: List<Found>, val seen: Map<Long, Long>)

    /** The notification: its words, the episode Play opens (null: none), the chat a press opens. */
    data class Alert(val title: String, val body: String?, val play: MediaItem?, val chatTitle: String, val chatId: Long?)

    private fun parse(fileName: String?, title: String, caption: String?): ParsedName =
        MediaName.parse(fileName?.takeIf { it.isNotBlank() } ?: title, caption ?: title.takeIf { it != fileName })

    /** The shows under way among [watching], by show key. */
    fun shows(watching: List<Watching>): Map<String, Show> {
        val out = LinkedHashMap<String, Show>()
        for (w in watching) {
            val name = parse(w.fileName, w.title, w.caption)
            val episode = name.episode ?: continue
            val key = SeriesShelf.keyOf(name.title).ifEmpty { continue }
            val season = name.season ?: 1
            val last = name.lastEpisode ?: episode
            val before = out[key]
            val furthest = (before?.furthest ?: emptyMap()).toMutableMap()
            furthest[season] = maxOf(furthest[season] ?: 0, last)
            out[key] = Show(
                key = key,
                title = before?.title ?: name.title,
                furthest = furthest,
                chats = (before?.chats ?: emptySet()) + w.chatId,
            )
        }
        return out
    }

    /** "harbour notes|2|5": one episode, wherever it is posted. */
    fun toldKey(show: String, season: Int, episode: Int): String = "$show|$season|$episode"

    /**
     * Compares each chat's newest videos ([latest]) with [seen], as [NewVideoAlerts.diff] does: a
     * chat missing from [latest] keeps its mark, one seen for the first time only gets one. [keep]
     * is the size limits. [told] is what was announced before.
     */
    fun diff(
        chats: Collection<Long>,
        shows: Map<String, Show>,
        seen: Map<Long, Long>,
        latest: Map<Long, List<MediaItem>>,
        titles: Map<Long, String>,
        told: Set<String>,
        keep: (MediaItem) -> Boolean = { true },
    ): Diff {
        val found = mutableListOf<Found>()
        val next = HashMap<Long, Long>()
        val said = told.toMutableSet()
        for (chatId in chats) {
            val before = seen[chatId]
            val items = latest[chatId]
            if (items == null) {
                before?.let { next[chatId] = it }
                continue
            }
            next[chatId] = maxOf(before ?: 0L, items.maxOfOrNull { it.messageId } ?: 0L)
            if (before == null) continue
            for (item in items.filter { it.messageId > before && keep(it) }.distinctBy { it.messageId }.sortedBy { it.messageId }) {
                if (item.splitPiece?.certain == true) continue
                val name = item.parsedName
                val episode = name.episode ?: continue
                val show = shows[SeriesShelf.keyOf(name.title)] ?: continue
                val season = name.season ?: 1
                if (!show.isNext(season, episode)) continue
                if (!said.add(toldKey(show.key, season, episode))) continue
                found += Found(item, titles[chatId].orEmpty(), show, season, episode)
            }
        }
        return Diff(found, next)
    }

    /** The notification for [found], or null for none. */
    fun alert(found: List<Found>, private: Boolean): Alert? {
        if (found.isEmpty()) return null
        if (private) return Alert(L.notifyEpisodesPrivate, null, null, "", null)
        val single = found.singleOrNull()
        if (single != null) {
            return Alert(
                title = L.notifyEpisodeNew(episode = single.episode, show = single.show.title, chat = single.chat),
                body = single.item.title.takeIf { it.isNotBlank() && it != single.show.title },
                play = single.item,
                chatTitle = single.chat,
                chatId = single.item.chatId,
            )
        }
        return Alert(
            title = L.notifyEpisodesNew(count = found.size),
            body = found.map { it.show.title }.distinct().joinToString(", ").ifBlank { null },
            play = null,
            chatTitle = "",
            chatId = found.map { it.item.chatId }.distinct().singleOrNull(),
        )
    }

    /** What the records say the viewer is watching: Continue watching and the watched list. */
    suspend fun watching(settings: SettingsStore, watched: WatchedStore?): List<Watching> {
        val resume = runCatching { settings.continueWatching.first() }.getOrDefault(emptyList())
            .map { Watching(it.chatId, it.fileName, it.title, it.caption) }
        val seen = runCatching { watched?.history?.first().orEmpty() }.getOrDefault(emptyList())
            .map { Watching(it.chatId, it.fileName, it.title, it.caption) }
        return resume + seen
    }

    /**
     * One check: reads the chats the shows were watched in and the starred ones the kids lock lets
     * through, stores the marks and the announced episodes, and returns the notification to post,
     * if any. Off with the switch, it does nothing; every failure is a quiet no.
     */
    suspend fun check(td: TdlClient, settings: SettingsStore, watched: WatchedStore?, allowed: Set<Long>?): Alert? {
        if (!settings.noticeOnNow(Notice.NewEpisodes)) return null
        val all = watching(settings, watched).filter { KidsLock.allows(allowed, it.chatId) }
        val shows = shows(all)
        if (shows.isEmpty()) return null
        val starred = KidsLock.favourites(settings.favorites.first(), allowed)
        val chats = (shows.values.flatMap { it.chats } + starred).distinct()
            .filter { KidsLock.allows(allowed, it) }
            .take(MAX_CHATS)
        val repository = ChatRepository(td)
        val latest = HashMap<Long, List<MediaItem>>()
        val titles = HashMap<Long, String>()
        for (chatId in chats) {
            runCatching {
                titles[chatId] = td.getChat(chatId).valueOrNull?.title.orEmpty()
                latest[chatId] = repository.mediaPage(chatId, pageSize = PAGE).items
            }.onFailure { Logger.w(TAG, "chat not checked: ${it.message}") }
        }
        // A clip does not ping: the alerts keep a floor of their own (SizeFilter.NOTIFY_MIN).
        val min = SizeFilter.notifyMin(settings.minSizeBytes.first())
        val max = settings.maxSizeBytes.first()
        val told = settings.episodeToldNow()
        val diff = diff(chats, shows, settings.episodeSeenNow(), latest, titles, told.toSet()) {
            SizeFilter.matches(it.sizeBytes, min, max)
        }
        // Only the chats read this time keep marks: one no longer watched or starred starts afresh.
        settings.setEpisodeSeen(diff.seen)
        if (diff.found.isNotEmpty()) {
            val fresh = diff.found.map { toldKey(it.show.key, it.season, it.episode) }
            settings.setEpisodeTold((fresh + told).distinct().take(TOLD_LIMIT))
        }
        val lock = settings.appLock.first()
        return alert(diff.found, private = lock.pinSet && lock.onOpen)
    }
}
