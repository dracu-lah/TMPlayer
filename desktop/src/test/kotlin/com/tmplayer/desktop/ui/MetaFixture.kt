package com.tmplayer.desktop.ui

import com.tmplayer.online.HttpRequest
import com.tmplayer.online.HttpResponse
import com.tmplayer.online.HttpTransport
import com.tmplayer.online.MetaQuery
import com.tmplayer.online.MetadataCache
import com.tmplayer.online.MetadataStore
import com.tmplayer.online.OnlineMetadata
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.URLDecoder
import java.nio.file.Files

/**
 * Posters and overviews for the desktop render tests, over a canned TMDB that never touches the
 * network: Big Buck Bunny (its poster and landscape are the promo build's, (c) Blender Foundation,
 * CC BY 3.0) and the made-up Harbour Lights, whose poster is the promo build's Harbour Notes one.
 */
internal object MetaFixture {

    private val pictures = File("../app/src/promo/res/drawable-nodpi")

    /** [state] is `on`, `off`, `none` (no TMDB key in the build) or `refused`. */
    fun install(state: String): OnlineMetadata {
        val dir = Files.createTempDirectory("tm-meta-render").toFile()
        val store = MetadataStore(File(dir, "metadata.properties"))
        store.update { it.copy(enabled = state != "off") }
        val online = OnlineMetadata(
            buildKey = if (state == "none") "" else "render-key",
            store = store,
            cache = MetadataCache(File(dir, "cache")),
            appVersion = "render",
            http = Canned(refuse = state == "refused"),
        )
        OnlineMetadata.current = online
        if (state == "refused") runBlocking { online.lookup(MetaQuery("Refused", 2000)) }
        return online
    }

    private class Canned(private val refuse: Boolean) : HttpTransport {
        override suspend fun send(request: HttpRequest): HttpResponse {
            val url = URLDecoder.decode(request.url, "UTF-8")
            IMAGES.entries.firstOrNull { url.endsWith("/" + it.key) }?.let {
                return HttpResponse(200, body = File(pictures, it.value).readBytes())
            }
            if (refuse && "themoviedb" in url) return HttpResponse(401, body = "{}".toByteArray())
            if ("api.tvmaze.com" in url) return HttpResponse(404, body = "{}".toByteArray())
            val body = when {
                "search/movie" in url && "Big Buck Bunny" in url -> """{"results":[$BUNNY]}"""
                "search/tv" in url && "Harbour Lights" in url -> """{"results":[$HARBOUR]}"""
                "/tv/7002/season/" in url -> """{"name":"The Storm","overview":"The storm reaches the harbour, and Mara has to choose between the boat and the house.","still_path":null}"""
                else -> """{"results":[]}"""
            }
            return HttpResponse(200, body = body.toByteArray())
        }
    }

    private val IMAGES = mapOf(
        "render-bbb.webp" to "promo_bbb_poster.webp",
        "render-bbb-wide.webp" to "promo_bbb_backdrop.webp",
        "render-harbour.webp" to "promo_harbour_poster.webp",
    )

    private const val BUNNY = """{"id":10378,"title":"Big Buck Bunny","original_title":"Big Buck Bunny","release_date":"2008-04-10",
        "overview":"A gentle giant of a rabbit wakes to a perfect spring morning, until three bored rodents start picking on the forest's smallest creatures. Patience runs out, and the bunny plans a comeback worthy of a cartoon.",
        "poster_path":"/render-bbb.webp","backdrop_path":"/render-bbb-wide.webp"}"""

    private const val HARBOUR = """{"id":7002,"name":"Harbour Lights","original_name":"Harbour Lights","first_air_date":"2023-02-01",
        "overview":"A lighthouse keeper's daughter takes over the family boatyard and finds the town's past in its ledgers.",
        "poster_path":"/render-harbour.webp","backdrop_path":null}"""
}
