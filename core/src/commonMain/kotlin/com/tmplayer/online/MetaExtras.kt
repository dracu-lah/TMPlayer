package com.tmplayer.online

import com.tmplayer.data.MediaItem
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** One name in the cast: the actor (or, for anime, the character) and who they play or voice. */
data class MetaPerson(
    val name: String,
    val role: String = "",
    val photoUrl: String? = null,
)

/** A trailer on YouTube, the only site it is opened from. [key] is YouTube's video id. */
data class MetaTrailer(val key: String) {
    /** Opens in the YouTube app where one is installed. */
    val appUri: String get() = "vnd.youtube:$key"

    /** The same video in a browser, and in the QR code a television shows when nothing can open it. */
    val watchUrl: String get() = "https://www.youtube.com/watch?v=$key"
}

/** One season of a show as the provider lists it: its number, how many episodes, when it began. */
data class MetaSeason(val number: Int, val episodeCount: Int, val airDate: String? = null)

/** The next episode of a show still running, and the day it airs (`2026-10-14`). */
data class MetaAiring(val season: Int, val episode: Int, val airDate: String, val name: String = "")

/** A title the provider recommends, by the provider's own id. Only shown when the viewer has it. */
data class MetaRef(
    val provider: MetaProvider,
    val kind: MetaKind,
    val id: String,
    val title: String,
) {
    /** What it is matched on. AniList's ids are one series, so its kind is left out of the match. */
    val matchKey: String get() = matchKey(provider, kind, id)

    companion object {
        fun matchKey(provider: MetaProvider, kind: MetaKind, id: String): String =
            if (provider == MetaProvider.AniList) "AniList||$id" else "${provider.name}|${kind.name}|$id"
    }
}

/**
 * The detail page's second helping, asked for only once a detail page opens: the facts line
 * (genres, length, seasons, rating, age rating, director or creators), the cast, a trailer, and
 * what the provider recommends. Every field may be empty; the page shows what there is.
 */
data class MetaExtras(
    val genres: List<String> = emptyList(),
    /** A film's length, or a show's usual episode length, in minutes. */
    val runtimeMin: Int? = null,
    val seasons: Int? = null,
    /** Out of ten, and only once enough people have voted: see [MetaRules.rating]. */
    val rating: Double? = null,
    /** The age rating in the viewer's region, else the US one. */
    val certification: String? = null,
    val directors: List<String> = emptyList(),
    val creators: List<String> = emptyList(),
    val cast: List<MetaPerson> = emptyList(),
    val trailer: MetaTrailer? = null,
    val similar: List<MetaRef> = emptyList(),
    /** A show's seasons, specials left out, so the page can say which the chat lacks. */
    val seasonList: List<MetaSeason> = emptyList(),
    /** The next episode to air, where the provider knows one. */
    val nextEpisode: MetaAiring? = null,
    /** The show has ended or been cancelled: no new season to wait for. */
    val ended: Boolean = false,
) {
    val isEmpty: Boolean
        get() = genres.isEmpty() && runtimeMin == null && seasons == null && rating == null && certification == null &&
            directors.isEmpty() && creators.isEmpty() && cast.isEmpty() && trailer == null && similar.isEmpty() &&
            seasonList.isEmpty() && nextEpisode == null

    fun toJson(): JSONObject = JSONObject()
        .put("genres", JSONArray(genres))
        .put("runtime", runtimeMin ?: 0)
        .put("seasons", seasons ?: 0)
        .put("rating", rating ?: 0.0)
        .put("certification", certification.orEmpty())
        .put("directors", JSONArray(directors))
        .put("creators", JSONArray(creators))
        .put("cast", JSONArray(cast.map { JSONObject().put("name", it.name).put("role", it.role).put("photo", it.photoUrl.orEmpty()) }))
        .put("trailer", trailer?.key.orEmpty())
        .put(
            "similar",
            JSONArray(similar.map { JSONObject().put("provider", it.provider.name).put("kind", it.kind.name).put("id", it.id).put("title", it.title) }),
        )
        .put("seasonList", JSONArray(seasonList.map { JSONObject().put("number", it.number).put("episodes", it.episodeCount).put("airDate", it.airDate.orEmpty()) }))
        .apply {
            nextEpisode?.let {
                put("next", JSONObject().put("season", it.season).put("episode", it.episode).put("airDate", it.airDate).put("name", it.name))
            }
        }
        .put("ended", ended)

    companion object {
        fun fromJson(json: JSONObject): MetaExtras? = runCatching {
            fun strings(name: String): List<String> {
                val list = json.optJSONArray(name) ?: return emptyList()
                return (0 until list.length()).map { list.optString(it) }.filter { it.isNotBlank() }
            }
            fun objects(name: String): List<JSONObject> {
                val list = json.optJSONArray(name) ?: return emptyList()
                return (0 until list.length()).mapNotNull { list.optJSONObject(it) }
            }
            MetaExtras(
                genres = strings("genres"),
                runtimeMin = json.optInt("runtime").takeIf { it > 0 },
                seasons = json.optInt("seasons").takeIf { it > 0 },
                rating = json.optDouble("rating", 0.0).takeIf { it > 0.0 },
                certification = json.optString("certification").ifBlank { null },
                directors = strings("directors"),
                creators = strings("creators"),
                cast = objects("cast").map { MetaPerson(it.optString("name"), it.optString("role"), it.optString("photo").ifBlank { null }) },
                trailer = json.optString("trailer").ifBlank { null }?.let { MetaTrailer(it) },
                similar = objects("similar").mapNotNull {
                    runCatching {
                        MetaRef(MetaProvider.valueOf(it.getString("provider")), MetaKind.valueOf(it.getString("kind")), it.getString("id"), it.optString("title"))
                    }.getOrNull()
                },
                seasonList = objects("seasonList").map { MetaSeason(it.optInt("number"), it.optInt("episodes"), it.optString("airDate").ifBlank { null }) }
                    .filter { it.number > 0 },
                nextEpisode = json.optJSONObject("next")?.let {
                    MetaAiring(it.optInt("season"), it.optInt("episode"), it.optString("airDate"), it.optString("name"))
                }?.takeIf { it.airDate.isNotBlank() && it.season > 0 },
                ended = json.optBoolean("ended"),
            )
        }.getOrNull()
    }
}

/** A video the viewer has, and the provider's title it was matched to: one "More like this" tile. */
data class KnownTitle(val item: MediaItem, val info: MetaInfo)

/** One video in a provider's list of videos for a title, before [MetaRules.trailer] picks one. */
data class MetaVideo(
    val site: String,
    val key: String,
    val type: String,
    val official: Boolean = false,
    val language: String = "",
    val published: String = "",
)

/** The choices behind [MetaExtras], kept apart from the parsing so each one is tested on its own. */
object MetaRules {

    /** Fewer votes than this and the average says nothing: one vote of 10 is not a 10.0 film. */
    const val MIN_VOTES = 25

    /** At most this many people in the cast row, and recommendations kept per title. */
    const val CAST_LIMIT = 10
    const val SIMILAR_LIMIT = 20

    /** The rating to show, rounded to one decimal, or null when too few voted or nobody did. */
    fun rating(average: Double, votes: Int): Double? {
        if (votes < MIN_VOTES || average <= 0.0 || average.isNaN()) return null
        return Math.round(average.coerceAtMost(10.0) * 10) / 10.0
    }

    /** The viewer's region: the device's country, or the US where it has none. */
    fun region(locale: Locale = Locale.getDefault()): String = locale.country.uppercase(Locale.ROOT).takeIf { it.length == 2 } ?: "US"

    /** The age rating for [region], else the US one, else none. Blank ratings are none. */
    fun certification(byRegion: Map<String, String>, region: String): String? {
        val clean = byRegion.filterValues { it.isNotBlank() }.mapKeys { it.key.uppercase(Locale.ROOT) }
        return clean[region.uppercase(Locale.ROOT)] ?: clean["US"]
    }

    /**
     * A film's age rating per region from TMDB's release dates: the theatrical release's where it
     * has one, then digital, physical, television, limited and premiere.
     */
    fun filmCertifications(byRegion: Map<String, List<Pair<String, Int>>>): Map<String, String> =
        byRegion.mapValues { (_, releases) ->
            releases.filter { it.first.isNotBlank() }
                .minByOrNull { RELEASE_ORDER.indexOf(it.second).let { at -> if (at < 0) RELEASE_ORDER.size else at } }
                ?.first.orEmpty()
        }

    private val RELEASE_ORDER = listOf(3, 4, 5, 6, 2, 1)

    /**
     * The trailer to offer: YouTube only; a trailer before a teaser; an official one first; then
     * one in the UI language, then English, then any; the newest of what is left.
     */
    fun trailer(videos: List<MetaVideo>, uiLanguage: String): MetaTrailer? {
        val ui = uiLanguage.substringBefore('-').lowercase(Locale.ROOT)
        fun languageRank(v: MetaVideo): Int {
            val lang = v.language.lowercase(Locale.ROOT)
            return when {
                lang.isNotEmpty() && lang == ui -> 0
                lang == "en" -> 1
                else -> 2
            }
        }
        return videos
            .filter { it.site.equals("YouTube", ignoreCase = true) && it.key.isNotBlank() }
            .filter { it.type.equals("Trailer", ignoreCase = true) || it.type.equals("Teaser", ignoreCase = true) }
            .sortedWith(
                compareBy<MetaVideo>({ if (it.type.equals("Trailer", ignoreCase = true)) 0 else 1 }, { if (it.official) 0 else 1 }, { languageRank(it) })
                    .thenByDescending { it.published },
            )
            .firstOrNull()
            ?.let { MetaTrailer(it.key) }
    }

    /**
     * "More like this", narrowed to what the viewer can play: each of [similar] that one of
     * [known] was matched to, in the provider's order, once each, never [self], at most [limit].
     */
    fun moreLikeThis(similar: List<MetaRef>, self: MetaInfo, known: List<KnownTitle>, limit: Int = 10): List<KnownTitle> {
        if (similar.isEmpty() || known.isEmpty()) return emptyList()
        val selfKey = MetaRef.matchKey(self.provider, self.kind, self.id)
        val byKey = LinkedHashMap<String, KnownTitle>()
        for (k in known) byKey.putIfAbsent(MetaRef.matchKey(k.info.provider, k.info.kind, k.info.id), k)
        return similar.asSequence()
            .map { it.matchKey }
            .filter { it != selfKey }
            .distinct()
            .mapNotNull { byKey[it] }
            .take(limit)
            .toList()
    }
}

/**
 * The videos this app has listed lately (Home's rows, a chat's grid, a search), so "More like
 * this" can offer only what the viewer has. Filled where the pages are loaded; bounded, oldest
 * first out, so a long session on a 1 GB television does not keep every video it ever showed.
 */
object KnownMedia {
    private const val LIMIT = 1_500

    private val items = object : LinkedHashMap<String, MediaItem>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MediaItem>?) = size > LIMIT
    }

    private val changes = kotlinx.coroutines.flow.MutableStateFlow(0)

    /**
     * Goes up each time videos are noted, so a page built from [all] (the detail page's episode
     * list) redraws when a chat's listing is loaded or refreshed and a new episode turns up.
     */
    val version: kotlinx.coroutines.flow.StateFlow<Int> get() = changes

    fun note(list: Collection<MediaItem>) {
        if (list.isEmpty()) return
        synchronized(items) { list.forEach { items[it.id] = it } }
        changes.value = changes.value + 1
    }

    /** Most recently listed last. */
    fun all(): List<MediaItem> = synchronized(items) { items.values.toList() }

    fun clear() {
        synchronized(items) { items.clear() }
        changes.value = changes.value + 1
    }
}
