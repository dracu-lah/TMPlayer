package com.tmplayer.online

/**
 * Canned provider answers shaped like a long anime's (Naruto Shippuden: about 500 episodes named
 * by absolute number, 21 TMDB seasons, one AniList entry), a show split in cours, and a wrong
 * match. Nothing here reaches the internet; every answer is a string a provider could send.
 */
internal object AnimeFixtures {

    const val MAL = 1735
    const val ANILIST = 1735
    const val TMDB = 31910
    const val KITSU = 1555
    const val TOTAL = 500

    /** TMDB's 21 seasons: 20 of ordinary length and a last one that makes the total 500. */
    val seasonSizes: List<Int> = run {
        val sizes = mutableListOf(32, 21, 18, 32, 20, 20, 28, 18, 25, 22, 22, 24, 23, 25, 24, 25, 24, 21, 25, 19)
        sizes += TOTAL - sizes.sum()
        sizes
    }

    private val FIRST = listOf("Homecoming", "Rescue", "Silent", "Broken", "Hidden", "Final", "Distant", "Burning", "Frozen", "Golden", "Crimson", "Lonely", "Wandering", "Secret", "Rising", "Fallen", "Gentle", "Fierce", "Ancient", "Hollow")
    private val SECOND = listOf("Bridge", "Promise", "Storm", "Village", "Mask", "Shadow", "Blade", "River", "Tower", "Garden", "Memory", "Contract", "Ember", "Harbor", "Winter", "Gate", "Oath", "Lantern", "Border", "Echo", "Pilgrim", "Orchard", "Ledger", "Beacon", "Canyon", "Cipher", "Tide", "Summit", "Relic", "Verdict")

    /** A name no placeholder check mistakes for "Episode N", unique to the absolute number. */
    fun title(absolute: Int): String = "${FIRST[absolute % FIRST.size]} ${SECOND[absolute / FIRST.size % SECOND.size]}"

    /** The Kitsu or Jikan wording of the same title, so a test can tell which source gave a name. */
    fun kitsuTitle(absolute: Int): String = "K: ${title(absolute)}"
    fun jikanTitle(absolute: Int): String = "J: ${title(absolute)}"
    fun streamTitle(absolute: Int): String = "S: ${title(absolute)}"

    /** Where absolute episode [n] sits in TMDB's seasons: (season, episode in it). */
    fun place(n: Int): Pair<Int, Int> {
        var left = n
        seasonSizes.forEachIndexed { i, size ->
            if (left <= size) return (i + 1) to left
            left -= size
        }
        error("episode $n is past the last season")
    }

    /** An air date for absolute episode [n], a week apart from 2007-02-15. */
    fun date(n: Int): String = java.time.LocalDate.of(2007, 2, 15).plusWeeks(n - 1L).toString()

    // ---- AniList ----------------------------------------------------------------------------

    fun aniListSearch(id: Int = ANILIST, english: String? = null, romaji: String = "Naruto: Shippuuden", synonyms: List<String> = listOf("Naruto Shippuden", "NARUTO Shippuden")): String =
        """{"data":{"Page":{"media":[{"id":$id,"format":"TV","seasonYear":2007,"startDate":{"year":2007},
            "title":{"romaji":"$romaji","english":${english?.let { "\"$it\"" } ?: "null"},"native":"ナルト- 疾風伝"},
            "synonyms":[${synonyms.joinToString(",") { "\"$it\"" }}],
            "description":"Naruto returns.<br>He is older now.","coverImage":{"large":"https://s4.anilist.co/cover.jpg"},"bannerImage":"https://s4.anilist.co/banner.jpg"}]}}}"""

    fun aniListExtras(mal: Int? = MAL): String =
        """{"data":{"Media":{"idMal":${mal ?: "null"},"genres":["Action"],"averageScore":82,"duration":23,"trailer":null,
            "characters":{"edges":[]},"recommendations":{"nodes":[]}}}}"""

    /** AniList's episode facts: its one entry's count, the next airing, and the streaming list ("Episode 12 - Title"). */
    fun aniListEpisodes(
        episodes: Int? = TOTAL,
        next: Pair<Int, Long>? = null,
        streaming: IntRange = IntRange.EMPTY,
        mal: Int? = MAL,
    ): String {
        val list = streaming.joinToString(",") {
            """{"title":"Episode $it - ${streamTitle(it)}","thumbnail":"https://img1.ak.crunchyroll.com/i/$it.jpg","url":"https://x.test/$it","site":"Crunchyroll"}"""
        }
        val nextJson = next?.let { """{"episode":${it.first},"airingAt":${it.second}}""" } ?: "null"
        return """{"data":{"Media":{"idMal":${mal ?: "null"},"episodes":${episodes ?: "null"},"status":"FINISHED","duration":23,
            "startDate":{"year":2007,"month":2,"day":15},"nextAiringEpisode":$nextJson,"streamingEpisodes":[$list]}}}"""
    }

    // ---- TMDB -------------------------------------------------------------------------------

    fun tmdbSearch(id: Int = TMDB, name: String = "Naruto Shippuden", year: Int = 2007): String =
        """{"results":[{"id":$id,"name":"$name","original_name":"ナルト- 疾風伝","first_air_date":"$year-02-15","overview":"x","poster_path":"/p.jpg","backdrop_path":"/b.jpg","original_language":"ja"}]}"""

    /** `tv/{id}` with the episode group list appended. [absolute] says whether TMDB has an "Absolute order" group. */
    fun tmdbShow(sizes: List<Int> = seasonSizes, absolute: Boolean = false, groupId: String = "5acf93d40e0a26"): String {
        val seasons = sizes.mapIndexed { i, n -> """{"season_number":${i + 1},"episode_count":$n,"air_date":"${2007 + i}-02-15"}""" }
        val groups = buildList {
            add("""{"id":"dvd1","name":"DVD order","type":3,"episode_count":${sizes.sum()},"group_count":${sizes.size}}""")
            if (absolute) add("""{"id":"$groupId","name":"Absolute Order","type":2,"episode_count":${sizes.sum()},"group_count":1}""")
        }
        return """{"id":$TMDB,"name":"Naruto Shippuden","seasons":[{"season_number":0,"episode_count":9,"air_date":"2008-01-01"},${seasons.joinToString(",")}],
            "episode_groups":{"results":[${groups.joinToString(",")}]}}"""
    }

    /** The absolute order group: one group, every episode in order, each with its own season and episode. */
    fun tmdbGroup(sizes: List<Int> = seasonSizes, titles: (Int) -> String = ::title): String {
        var n = 0
        val episodes = buildList {
            sizes.forEachIndexed { s, size ->
                for (e in 1..size) {
                    n++
                    add(
                        """{"order":${n - 1},"episode_number":$e,"season_number":${s + 1},"name":"${titles(n)}","overview":"About ${titles(n)}.",
                        "still_path":"/still$n.jpg","air_date":"${date(n)}","runtime":23}""",
                    )
                }
            }
        }
        return """{"id":"g","groups":[{"id":"all","name":"Absolute Order","order":1,"episodes":[${episodes.joinToString(",")}]}]}"""
    }

    /** `tv/{id}` with seasons [from]..[to] appended: each its own key, as TMDB's append does. */
    fun tmdbSeasons(sizes: List<Int> = seasonSizes, from: Int, to: Int, titles: (Int) -> String = ::title): String {
        val parts = (from..minOf(to, sizes.size)).joinToString(",") { s ->
            val offset = sizes.take(s - 1).sum()
            val episodes = (1..sizes[s - 1]).joinToString(",") { e ->
                val n = offset + e
                """{"episode_number":$e,"season_number":$s,"name":"${titles(n)}","overview":"About ${titles(n)}.","still_path":"/still$n.jpg","air_date":"${date(n)}","runtime":23}"""
            }
            """"season/$s":{"season_number":$s,"episodes":[$episodes]}"""
        }
        return """{"id":$TMDB,$parts}"""
    }

    // ---- Kitsu ------------------------------------------------------------------------------

    fun kitsuMapping(kitsuId: Int = KITSU): String =
        """{"data":[{"id":"9","type":"mappings","relationships":{"item":{"data":{"type":"anime","id":"$kitsuId"}}}}],"included":[{"id":"$kitsuId","type":"anime"}]}"""

    /** One page of up to 20 episodes starting after [offset]. */
    fun kitsuPage(offset: Int, total: Int = TOTAL, titles: (Int) -> String = ::kitsuTitle): String {
        val items = ((offset + 1)..minOf(offset + 20, total)).joinToString(",") { n ->
            """{"id":"${100000 + n}","type":"episodes","attributes":{"number":$n,"canonicalTitle":"${titles(n)}","titles":{"en_us":"${titles(n)}"},
                "synopsis":"Kitsu words for $n.","airdate":"${date(n)}","length":23,"thumbnail":{"original":"https://media.kitsu.app/episodes/$n.jpg"}}}"""
        }
        val next = if (offset + 20 < total) ",\"next\":\"https://kitsu.io/api/edge/anime/$KITSU/episodes?page%5Boffset%5D=${offset + 20}\"" else ""
        return """{"data":[$items],"meta":{"count":$total},"links":{"first":"x"$next}}"""
    }

    // ---- Jikan ------------------------------------------------------------------------------

    fun jikanPage(page: Int, total: Int = TOTAL, titles: (Int) -> String = ::jikanTitle): String {
        val first = (page - 1) * 100 + 1
        val items = (first..minOf(first + 99, total)).joinToString(",") { n ->
            """{"mal_id":$n,"title":"${titles(n)}","title_japanese":"x","aired":"${date(n)}T00:00:00+00:00","filler":false,"recap":false}"""
        }
        return """{"pagination":{"last_visible_page":${(total + 99) / 100},"has_next_page":${first + 99 < total}},"data":[$items]}"""
    }
}
