package com.tmplayer

import android.content.Context
import com.tmplayer.data.MediaItem
import com.tmplayer.online.HttpRequest
import com.tmplayer.online.HttpResponse
import com.tmplayer.online.HttpTransport
import com.tmplayer.online.MetadataCache
import com.tmplayer.online.MetadataStore
import com.tmplayer.online.OnlineMetadata
import java.io.File
import java.net.URLDecoder

/**
 * Posters and overviews for the screenshot fixture, over a canned TMDB and AniList that never
 * touch the network. `--ez meta true` turns lookups on; `--es metakey none|refused|own` shows the
 * TMDB key row in each state; without `meta` the feature is installed but off, which is what a
 * fresh install looks like (the detail panel then offers to turn it on).
 *
 * Big Buck Bunny is the one real title: its poster and landscape are (c) Blender Foundation, CC BY
 * 3.0, scaled down from Wikimedia Commons. Harbour Notes and Sky Garden are the fixture's own
 * made-up shows, with posters cut from its own demo pictures. The overviews are written here.
 */
internal object PromoMeta {

    /** Whether lookups are on in this run, for the fixture's Home and detail variants. */
    var on = false
        private set

    /** The real film the fixture can show a poster for. */
    val bunny = MediaItem(
        chatId = 102,
        messageId = 900,
        fileId = 0,
        title = "Big.Buck.Bunny.2008.1080p.BluRay.x264.mkv",
        sizeBytes = 885L * 1024 * 1024,
        durationSec = 596,
        mimeType = "video/x-matroska",
        thumbnailFileId = 0,
        miniThumbnail = null,
        date = 9_100,
        fileName = "Big.Buck.Bunny.2008.1080p.BluRay.x264.mkv",
        width = 1920,
        height = 1080,
    )

    fun install(context: Context, enabled: Boolean, key: String?) {
        on = enabled
        val res = context.resources
        fun bytes(id: Int) = res.openRawResource(id).use { it.readBytes() }
        val images = mapOf(
            "promo-bbb.webp" to bytes(R.drawable.promo_bbb_poster),
            "promo-bbb-wide.webp" to bytes(R.drawable.promo_bbb_backdrop),
            "promo-harbour.webp" to bytes(R.drawable.promo_harbour_poster),
            "promo-garden.webp" to bytes(R.drawable.promo_garden_poster),
        )
        val dir = File(context.cacheDir, "promo-metadata").apply { deleteRecursively() }
        val store = MetadataStore(File(dir, "metadata.properties"))
        store.update { it.copy(enabled = enabled || key == "refused", ownKey = if (key == "own") "promo-own-key" else "") }
        val online = OnlineMetadata(
            buildKey = if (key == "none") "" else "promo-key",
            store = store,
            cache = MetadataCache(File(dir, "cache")),
            appVersion = BuildConfig.VERSION_NAME,
            http = Canned(images, refuse = key == "refused"),
        )
        OnlineMetadata.current = online
        // A refused key is learnt from one answer, as it would be in the app.
        if (key == "refused") {
            kotlinx.coroutines.runBlocking { online.lookup(com.tmplayer.online.MetaQuery("Refused", 2000)) }
            online.setEnabled(enabled)
        }
    }

    /** TMDB and AniList as far as the fixture's titles go; anything else is "nothing found". */
    private class Canned(private val images: Map<String, ByteArray>, private val refuse: Boolean) : HttpTransport {
        override suspend fun send(request: HttpRequest): HttpResponse {
            val url = URLDecoder.decode(request.url, "UTF-8")
            images.entries.firstOrNull { url.endsWith("/" + it.key) }?.let { return HttpResponse(200, body = it.value) }
            if (refuse && "themoviedb" in url) return json(401, """{"status_code":7}""")
            val body = when {
                "search/movie" in url && "Big Buck Bunny" in url -> """{"results":[$BUNNY]}"""
                "search/tv" in url && "Harbour Notes" in url -> """{"results":[$HARBOUR]}"""
                "/movie/10378?" in url -> BUNNY_EXTRAS
                "/tv/7001?" in url -> HARBOUR_EXTRAS
                "/tv/7001/season/" in url -> episode(url)
                "graphql.anilist.co" in url && request.body?.contains("Sky Garden") == true -> GARDEN
                "graphql.anilist.co" in url -> """{"data":{"Page":{"media":[]}}}"""
                "api.tvmaze.com" in url -> return json(404, "{}")
                else -> """{"results":[]}"""
            }
            return json(200, body)
        }

        private fun json(code: Int, body: String) = HttpResponse(code, mapOf("Content-Type" to "application/json"), body.toByteArray())

        private fun episode(url: String): String {
            val (season, number) = Regex("""season/(\d+)/episode/(\d+)""").find(url)!!.destructured
            val n = number.toInt()
            val name = EPISODES.getOrElse((season.toInt() - 1) * 6 + n - 1) { "Episode $n" }
            return """{"name":"$name","overview":"${EPISODE_OVERVIEWS[n % EPISODE_OVERVIEWS.size]}","still_path":null}"""
        }
    }

    /** The detail page's extras (facts, cast, trailer) for the two titles, as TMDB appends them. Made up but for Bunny's facts. */
    private const val BUNNY_EXTRAS = """{"id":10378,"genres":[{"name":"Animation"},{"name":"Comedy"},{"name":"Family"}],"runtime":10,"vote_average":6.5,"vote_count":200,
        "credits":{"cast":[],"crew":[{"name":"Sacha Goedegebure","job":"Director"}]},
        "videos":{"results":[{"site":"YouTube","key":"YE7VzlLtp-4","type":"Trailer","official":true,"iso_639_1":"en","published_at":"2008-05-20"}]},
        "release_dates":{"results":[{"iso_3166_1":"US","release_dates":[{"certification":"G","type":3}]}]},
        "recommendations":{"results":[]},"similar":{"results":[]}}"""

    private const val HARBOUR_EXTRAS = """{"id":7001,"genres":[{"name":"Drama"},{"name":"Documentary"}],"episode_run_time":[42],"number_of_seasons":2,"vote_average":7.9,"vote_count":340,
        "created_by":[{"name":"Mara Lindqvist"}],
        "aggregate_credits":{"cast":[
          {"name":"Mara Lindqvist","total_episode_count":12,"profile_path":null,"roles":[{"character":"Herself","episode_count":12}]},
          {"name":"Tomas Berg","total_episode_count":10,"profile_path":null,"roles":[{"character":"Harbour master","episode_count":10}]},
          {"name":"Ines Varga","total_episode_count":8,"profile_path":null,"roles":[{"character":"Net mender","episode_count":8}]},
          {"name":"Ole Strand","total_episode_count":6,"profile_path":null,"roles":[{"character":"Lighthouse keeper","episode_count":6}]}]},
        "content_ratings":{"results":[{"iso_3166_1":"US","rating":"TV-PG"}]},
        "videos":{"results":[{"site":"YouTube","key":"promoharbour","type":"Trailer","official":true,"iso_639_1":"en","published_at":"2024-02-01"}]},
        "recommendations":{"results":[]},"similar":{"results":[]}}"""

    private const val BUNNY = """{"id":10378,"title":"Big Buck Bunny","original_title":"Big Buck Bunny","release_date":"2008-04-10",
        "overview":"A gentle giant of a rabbit wakes to a perfect spring morning, until three bored rodents start picking on the forest's smallest creatures. Patience runs out, and the bunny plans a comeback worthy of a cartoon.",
        "poster_path":"/promo-bbb.webp","backdrop_path":"/promo-bbb-wide.webp"}"""

    private const val HARBOUR = """{"id":7001,"name":"Harbour Notes","original_name":"Harbour Notes","first_air_date":"2024-03-01",
        "overview":"A filmmaker returns to the fishing town she grew up in and starts recording everything: the boats, the storms, and the neighbours who would rather not be on camera.",
        "poster_path":"/promo-harbour.webp","backdrop_path":null}"""

    private const val GARDEN = """{"data":{"Page":{"media":[{"id":8001,"format":"TV","seasonYear":2025,
        "title":{"romaji":"Sky Garden","english":"Sky Garden","native":""},"synonyms":[],
        "description":"Two students tend a rooftop garden that only blooms at night, and keep finding notes left in the soil by somebody who tended it before them.",
        "coverImage":{"large":"https://s4.anilist.co/promo-garden.webp"},"bannerImage":null}]}}}"""

    private val EPISODES = listOf("Low Tide", "The Storm", "Nets", "Lighthouse", "Off Season", "Homecoming", "Spring Catch", "The Regatta", "Fog", "Open Water")

    private val EPISODE_OVERVIEWS = listOf(
        "The boats stay in while the town argues about the new sea wall.",
        "A storm cuts the power, and the camera keeps rolling by candlelight.",
        "An old fisherman agrees to one interview, on his terms.",
    )
}
