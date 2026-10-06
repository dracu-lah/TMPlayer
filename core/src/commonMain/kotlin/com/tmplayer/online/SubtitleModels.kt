package com.tmplayer.online

import org.json.JSONArray
import org.json.JSONObject

/** Where an online subtitle comes from. [label] is the provider's own name, never translated. */
enum class SubtitleProvider(val label: String) {
    OpenSubtitles("OpenSubtitles"),
    Subdl("SubDL"),
}

/**
 * What a search is for: the video's name (and caption) to parse, its size, the OpenSubtitles hash
 * when both ends of the file were on the disk to compute it, and the languages wanted, best first.
 */
data class SubtitleTarget(
    val fileName: String,
    val sizeBytes: Long,
    val hash: String? = null,
    val caption: String? = null,
    val languages: List<String> = listOf("en"),
)

/** One subtitle a provider offers. [id] is OpenSubtitles' file id, or SubDL's download path. */
data class SubtitleHit(
    val provider: SubtitleProvider,
    val id: String,
    val language: String,
    val release: String,
    val fileName: String = "",
    val downloads: Int = 0,
    val hashMatch: Boolean = false,
    val hearingImpaired: Boolean = false,
    /** Machine or AI translated, which a search leaves out unless the viewer has asked for them. */
    val machine: Boolean = false,
) {
    internal fun toJson(): JSONObject = JSONObject()
        .put("provider", provider.name)
        .put("id", id)
        .put("language", language)
        .put("release", release)
        .put("file", fileName)
        .put("downloads", downloads)
        .put("hash", hashMatch)
        .put("hi", hearingImpaired)
        .put("machine", machine)

    internal companion object {
        fun fromJson(o: JSONObject): SubtitleHit? = runCatching {
            SubtitleHit(
                provider = SubtitleProvider.valueOf(o.getString("provider")),
                id = o.getString("id"),
                language = o.optString("language"),
                release = o.optString("release"),
                fileName = o.optString("file"),
                downloads = o.optInt("downloads"),
                hashMatch = o.optBoolean("hash"),
                hearingImpaired = o.optBoolean("hi"),
                machine = o.optBoolean("machine"),
            )
        }.getOrNull()

        fun listToJson(hits: List<SubtitleHit>): JSONArray = JSONArray().apply { hits.forEach { put(it.toJson()) } }

        fun listFromJson(array: JSONArray): List<SubtitleHit> =
            (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(::fromJson) }
    }
}

/**
 * Why a search or a download did not give everything it might have, said once in words every
 * screen shows the same way. A [SearchResult] can carry one beside its hits: a list found by name
 * is still worth showing when downloading is what is blocked.
 */
sealed interface SubtitleNotice {
    /** Not signed in: searching works, downloading needs the viewer's own free account. */
    data object SignInToDownload : SubtitleNotice

    /** The day's downloads are spent; [resetAt] is when more arrive, if OpenSubtitles said. */
    data class QuotaUsed(val resetAt: Long?) : SubtitleNotice

    /** The viewer's sign in ran out, and has to be done again. */
    data object SignInExpired : SubtitleNotice

    /** The app's key was refused: the provider is off until an update brings a new one. */
    data object Unavailable : SubtitleNotice

    /** Too many requests a moment ago; the next try a minute later will go through. */
    data object Busy : SubtitleNotice

    /** No answer from the provider at all. */
    data object Offline : SubtitleNotice

    /** The SubDL key the viewer typed in was refused. */
    data object SubdlKeyRefused : SubtitleNotice

    /** The file arrived but held no subtitle in a format the players read. */
    data object Unreadable : SubtitleNotice
}

/** A search's answer: the hits, best first, and why there may be fewer than hoped. */
data class SearchResult(
    val hits: List<SubtitleHit>,
    val notice: SubtitleNotice? = null,
    val fromCache: Boolean = false,
)

/** A download's answer: the subtitle written as a UTF-8 file ready for the player, or why not. */
sealed interface DownloadResult {
    data class Done(val file: java.io.File, val label: String, val fromCache: Boolean) : DownloadResult
    data class Failed(val notice: SubtitleNotice) : DownloadResult
}
