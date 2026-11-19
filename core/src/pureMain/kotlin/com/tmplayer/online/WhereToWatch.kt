package com.tmplayer.online

import com.tmplayer.json.JSONArray
import com.tmplayer.json.JSONObject

/**
 * Where a title can be watched legally in one region, as TMDB lists it (data from JustWatch):
 * the services' names only, never a link, so nothing here points at a source the viewer has not
 * chosen (HX41). Each list is in TMDB's own display order, at most [WatchOfferRules.PER_KIND].
 */
data class WatchOffers(
    /** ISO 3166-1 region the lists are for ("GB"). */
    val region: String,
    val stream: List<String> = emptyList(),
    val free: List<String> = emptyList(),
    val rent: List<String> = emptyList(),
    val buy: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = stream.isEmpty() && free.isEmpty() && rent.isEmpty() && buy.isEmpty()
}

/** What asking where to watch a title gave, so the sheet can say it plainly. */
sealed interface WhereToWatch {
    /** Something is listed for [WatchOffers.region]. */
    data class Found(val offers: WatchOffers) : WhereToWatch

    /** TMDB answered and lists nothing for [region]: said as it is, never as an error. */
    data class NoneHere(val region: String) : WhereToWatch

    /** Lookups are switched off in Settings. */
    data object Off : WhereToWatch

    /** No TMDB key to ask with, or TMDB turned the key down. */
    data object NoKey : WhereToWatch

    /** Offline, throttled or a TMDB outage: Try again may work later. */
    data object Unavailable : WhereToWatch
}

/** Reads TMDB's `watch/providers` answer. Pure and total: any shape of answer gives a list, never an error. */
object WatchOfferRules {

    /** Services kept per kind: a sheet a remote can read, however long TMDB's list is. */
    const val PER_KIND = 12

    /** The longest service name kept. */
    const val NAME_MAX = 60

    /**
     * The offers for [region] in [json] (`{"results": {"GB": {"flatrate": [...], ...}}}`). A region
     * that is not a two letter code, a missing region, wrong types and blank names all leave lists
     * empty. TMDB's `ads` (free with adverts) is listed with `free`.
     */
    fun read(json: JSONObject, region: String): WatchOffers {
        val code = region.trim().uppercase().takeIf { it.length == 2 && it.all { c -> c in 'A'..'Z' } } ?: "US"
        val here = json.optJSONObject("results")?.optJSONObject(code) ?: return WatchOffers(code)
        return WatchOffers(
            region = code,
            stream = names(here.optJSONArray("flatrate")),
            free = (names(here.optJSONArray("free")) + names(here.optJSONArray("ads"))).distinct().take(PER_KIND),
            rent = names(here.optJSONArray("rent")),
            buy = names(here.optJSONArray("buy")),
        )
    }

    private fun names(list: JSONArray?): List<String> {
        if (list == null) return emptyList()
        val entries = (0 until minOf(list.length(), PER_KIND * 4)).mapNotNull { list.optJSONObject(it) }
        return entries
            .mapNotNull { entry ->
                val name = entry.optString("provider_name").trim().replace(SPACES, " ").take(NAME_MAX)
                if (name.isEmpty()) null else name to entry.optInt("display_priority", Int.MAX_VALUE)
            }
            .sortedBy { it.second }
            .map { it.first }
            .distinct()
            .take(PER_KIND)
    }

    private val SPACES = Regex("""\s+""")
}
