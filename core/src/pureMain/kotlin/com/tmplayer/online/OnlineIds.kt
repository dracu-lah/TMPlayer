package com.tmplayer.online

/** The host of an `https://` URL, as `URI(url).host`, or null. */
internal fun hostOf(url: String): String? {
    val rest = url.substringAfter("://", "").takeIf { it.isNotEmpty() } ?: return null
    val authority = rest.substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@')
    return authority.substringBefore(':').lowercase().takeIf { it.isNotEmpty() }
}

/** A video's ids on TMDB and IMDb, either unknown. */
data class VideoIds(val tmdb: String? = null, val imdb: String? = null)
