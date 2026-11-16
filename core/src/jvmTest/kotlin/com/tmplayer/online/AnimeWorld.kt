package com.tmplayer.online

/**
 * A fake network that answers like AniList, TMDB, Kitsu and Jikan for one long anime ([AnimeFixtures]),
 * and records what it was asked. Each provider can be switched off (it then answers 404) to test
 * the order the guide falls back in; [offline] drops every connection.
 */
internal class AnimeWorld : HttpTransport {
    val requests = mutableListOf<HttpRequest>()

    var tmdb = true
    var tmdbAbsoluteGroup = false
    var kitsu = true
    var jikan = true
    var offline = false
    var streaming: IntRange = IntRange.EMPTY
    var mal: Int? = AnimeFixtures.MAL
    var entryEpisodes: Int? = AnimeFixtures.TOTAL
    var next: Pair<Int, Long>? = null
    var tmdbSizes: List<Int> = AnimeFixtures.seasonSizes
    var tmdbName = "Naruto Shippuden"
    var aniListSearch: String = AnimeFixtures.aniListSearch()
    var kitsuTotal = AnimeFixtures.TOTAL
    var jikanTotal = AnimeFixtures.TOTAL

    fun count(part: String) = requests.count { part in it.url || it.body?.contains(part) == true }

    override suspend fun send(request: HttpRequest): HttpResponse {
        requests += request
        if (offline) throw com.tmplayer.platform.IOException("offline")
        val url = request.url
        val body = request.body.orEmpty()
        fun ok(text: String) = HttpResponse(200, emptyMap(), text.toByteArray())
        val missing = HttpResponse(404, emptyMap(), "{}".toByteArray())
        return when {
            "graphql.anilist.co" in url -> when {
                "streamingEpisodes" in body -> ok(AnimeFixtures.aniListEpisodes(entryEpisodes, next, streaming, mal))
                "characters" in body -> ok(AnimeFixtures.aniListExtras(mal))
                else -> ok(aniListSearch)
            }
            "api.themoviedb.org" in url -> when {
                !tmdb -> missing
                "search/tv" in url -> ok(AnimeFixtures.tmdbSearch(name = tmdbName))
                "tv/episode_group/" in url -> ok(AnimeFixtures.tmdbGroup(tmdbSizes))
                "append_to_response=episode_groups" in url -> ok(AnimeFixtures.tmdbShow(tmdbSizes, tmdbAbsoluteGroup))
                "append_to_response=season" in url -> {
                    val wanted = Regex("""season/(\d+)""").findAll(url.substringAfter("append_to_response=")).map { it.groupValues[1].toInt() }.toList()
                    ok(AnimeFixtures.tmdbSeasons(tmdbSizes, wanted.min(), wanted.max()))
                }
                "tv/${AnimeFixtures.TMDB}/season/" in url && "/episode/" in url -> missing
                "tv/${AnimeFixtures.TMDB}/season/" in url -> {
                    val season = Regex("""season/(\d+)""").find(url)!!.groupValues[1].toInt()
                    ok(AnimeFixtures.tmdbSeasons(tmdbSizes, season, season))
                }
                "tv/${AnimeFixtures.TMDB}" in url -> ok(AnimeFixtures.tmdbShow(tmdbSizes, tmdbAbsoluteGroup))
                else -> missing
            }
            "kitsu.io/api/edge/mappings" in url -> if (kitsu) ok(AnimeFixtures.kitsuMapping()) else missing
            "kitsu.io/api/edge/anime/" in url -> {
                if (!kitsu) return missing
                val offset = Regex("""offset%5D=(\d+)""").find(url)?.groupValues?.get(1)?.toInt() ?: 0
                ok(AnimeFixtures.kitsuPage(offset, kitsuTotal))
            }
            "api.jikan.moe" in url -> {
                if (!jikan) return missing
                val page = Regex("""page=(\d+)""").find(url)?.groupValues?.get(1)?.toInt() ?: 1
                ok(AnimeFixtures.jikanPage(page, jikanTotal))
            }
            else -> missing
        }
    }
}
