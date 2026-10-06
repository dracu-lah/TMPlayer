package com.tmplayer

import android.content.Context
import com.tmplayer.online.HttpResponse
import com.tmplayer.online.HttpTransport
import com.tmplayer.online.OnlineSubtitles
import com.tmplayer.online.OnlineSubtitlesStore
import com.tmplayer.online.SubtitleCache
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException

/**
 * Online subtitles for the screenshot fixtures, answered by a canned OpenSubtitles that never
 * touches the network, in whichever state the shot needs:
 *
 *     --es online signed_out|signed_in|quota|expired|unavailable|empty|offline|subdl|none
 *
 * `none` is a build without a key: the feature is not drawn at all. Every state starts from a
 * fresh account file and an empty cache, so one shot cannot leak into the next.
 */
object PromoOnline {

    fun install(context: Context, state: String?) {
        if (state == null) return
        val dir = File(context.filesDir, "promo-online").apply { deleteRecursively(); mkdirs() }
        val store = OnlineSubtitlesStore(File(dir, "account.properties"))
        val now = System.currentTimeMillis()
        when (state) {
            "signed_in", "unavailable" -> store.update { it.copy(username = "filmfan", token = "promo", allowed = 20, remaining = 17) }
            "quota" -> store.update { it.copy(username = "filmfan", token = "promo", allowed = 20, remaining = 0, resetAt = now + 5 * 3_600_000L) }
            "expired" -> store.update { it.copy(username = "filmfan", expired = true, allowed = 20) }
            "subdl" -> store.update { it.copy(subdlKey = "promo-subdl-key") }
        }
        val transport = HttpTransport { request ->
            when {
                state == "offline" -> throw IOException("offline fixture")
                state == "unavailable" -> HttpResponse(403, body = "{}".toByteArray())
                state == "empty" || (state == "subdl" && "opensubtitles" in request.url) -> HttpResponse(200, body = EMPTY.toByteArray())
                "api.subdl.com" in request.url -> HttpResponse(200, body = SUBDL.toByteArray())
                "/subtitles" in request.url -> HttpResponse(200, body = RESULTS.toByteArray())
                "/download" in request.url -> HttpResponse(
                    200,
                    body = """{"link":"https://promo.invalid/coast.srt","file_name":"coast.srt","remaining":16}""".toByteArray(),
                )
                "promo.invalid" in request.url -> HttpResponse(200, body = "1\n00:00:01,000 --> 00:00:04,000\nThe tide is turning.\n".toByteArray())
                else -> HttpResponse(404)
            }
        }
        val online = OnlineSubtitles(
            apiKey = if (state == "none") "" else "promo-key",
            store = store,
            cache = SubtitleCache(File(dir, "cache")),
            appVersion = BuildConfig.VERSION_NAME,
            http = transport,
        )
        // The refused key, as Settings shows it after a search was turned away.
        if (state == "unavailable") runBlocking { online.search(com.tmplayer.online.SubtitleTarget("The.Coast.S01E04.mkv", 0)) }
        OnlineSubtitles.current = online
    }

    private const val EMPTY = """{"total_count":0,"data":[]}"""

    private fun hit(id: Int, lang: String, release: String, downloads: Int, hash: Boolean = false, hi: Boolean = false) =
        """{"id":"$id","attributes":{"language":"$lang","release":"$release","download_count":$downloads,"hearing_impaired":$hi,
        "moviehash_match":$hash,"ai_translated":false,"machine_translated":false,"files":[{"file_id":$id,"file_name":"$release.srt"}]}}"""

    private val RESULTS = """{"total_count":6,"data":[
        ${hit(1, "en", "The.Coast.S01E04.1080p.WEB.H264", 18_240, hash = true)},
        ${hit(2, "en", "The.Coast.S01E04.720p.HDTV.x264", 9_812)},
        ${hit(3, "en", "The.Coast.S01E04.1080p.WEB.H264.SDH", 4_310, hi = true)},
        ${hit(4, "es", "The.Coast.S01E04.1080p.WEB.H264", 2_077)},
        ${hit(5, "ml", "The.Coast.S01E04.WEB", 312)},
        ${hit(6, "hi", "The.Coast.S01E04.WEBRip", 190)}
    ]}"""

    private const val SUBDL = """{"status":true,"subtitles":[
        {"release_name":"The.Coast.S01E04.WEB","name":"The.Coast.S01E04.zip","language":"ML","url":"/subtitle/1-1.zip"},
        {"release_name":"The.Coast.S01.Complete.WEB","name":"The.Coast.S01.zip","language":"EN","url":"/subtitle/1-2.zip"}
    ]}"""
}
