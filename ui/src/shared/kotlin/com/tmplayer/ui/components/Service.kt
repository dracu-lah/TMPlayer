package com.tmplayer.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The outside services TMPlayer names on a screen (HX93), in one list, so a logo or an initial is
 * decided once and every platform shows the same. [label] is the name as the service writes it.
 *
 * A service has a bundled [logo] only where its own press or brand page offers one to download
 * and its rules allow an app like this to use it: today that is TMDB alone (docs/brand-assets.md
 * says why each of the others has none). Every other service wears an initial in its
 * [com.tmplayer.data.NameColour], never a mark copied or redrawn from memory. The web app's twin
 * is `webapp/src/ui/services.ts`; keep the two lists alike.
 */
enum class Service(val label: String) {
    OpenSubtitles("OpenSubtitles"),
    SubDl("SubDL"),
    MSone("MSone"),
    SubMaker("SubMaker"),
    Tmdb("TMDB"),
    TvMaze("TVmaze"),
    AniList("AniList"),
    Omdb("OMDb"),
    Wikidata("Wikidata"),
    YouTube("YouTube"),
    Telegram("Telegram"),
    Sentry("Sentry"),
    TheIntroDb("TheIntroDB"),
    AniSkip("AniSkip"),
    AnimeSkip("Anime-Skip"),
    IdMapping("arm.haglund.dev"),
    ;

    /** The bundled official mark, or null where the badge is an initial. Built once, read many times. */
    val logo: ImageVector? get() = when (this) {
        Tmdb -> ServiceLogos.TmdbSquare
        else -> null
    }

    companion object {
        /** The service a link's or a provider's name belongs to, ignoring case and a trailing ".com". */
        fun named(name: String?): Service? {
            val key = name?.trim()?.lowercase()?.removeSuffix(".com").orEmpty()
            return entries.firstOrNull { it.label.lowercase() == key }
        }
    }
}

/**
 * Official marks as vectors, so there is no bitmap to decode per row and nothing to fetch: one
 * [ImageVector] per service, built on first use.
 */
internal object ServiceLogos {

    /**
     * TMDB's "blue square 1" logo (the two lines of letters), the path and the three gradient stops
     * unchanged from the SVG at https://www.themoviedb.org/about/logos-attribution, fetched
     * 2026-11-20 (the file is kept as webapp/src/ui/brand/tmdb-blue-square-1.svg). Never recoloured,
     * stretched or turned, as TMDB's terms require.
     */
    val TmdbSquare: ImageVector by lazy {
        ImageVector.Builder(
            name = "TmdbSquare",
            defaultWidth = 190.24.dp,
            defaultHeight = 81.52.dp,
            viewportWidth = 190.24f,
            viewportHeight = 81.52f,
        ).addPath(
            pathData = addPathNodes(TMDB_SQUARE_PATH),
            fill = Brush.linearGradient(
                0f to Color(0xFF90CEA1),
                0.56f to Color(0xFF3CBEC9),
                1f to Color(0xFF00B3E5),
                start = Offset(0f, 40.76f),
                end = Offset(190.24f, 40.76f),
            ),
        ).build()
    }

    /** TMDB's logo is 190.24 by 81.52: the tile fits it by width and never changes that. */
    const val TMDB_SQUARE_ASPECT = 190.24f / 81.52f
}

private const val TMDB_SQUARE_PATH =
        "M105.67,36.06h66.9A17.67,17.67,0,0,0,190.24,18.4h0A17.67,17.67,0,0,0,172.57.73h-66.9A17.67,17.67,0,0,0,88,18.4h0A17.67,17.67,0,0,0,105.67,36.06Z" +
        "m-88,45h76.9A17.67,17.67,0,0,0,112.24,63.4h0A17.67,17.67,0,0,0,94.57,45.73H17.67A17.67,17.67,0,0,0,0,63.4H0A17.67,17.67,0,0,0,17.67,81.06Z" +
        "M10.41,35.42h7.8V6.92h10.1V0H.31v6.9h10.1Z" +
        "m28.1,0h7.8V8.25h.1l9,27.15h6l9.3-27.15h.1V35.4h7.8V0H66.76l-8.2,23.1h-.1L50.31,0H38.51Z" +
        "M152.43,55.67a15.07,15.07,0,0,0-4.52-5.52,18.57,18.57,0,0,0-6.68-3.08,33.54,33.54,0,0,0-8.07-1h-11.7v35.4h12.75a24.58,24.58,0,0,0,7.55-1.15A19.34,19.34,0,0,0,148.11,77a16.27,16.27,0,0,0,4.37-5.5,16.91,16.91,0,0,0,1.63-7.58A18.5,18.5,0,0,0,152.43,55.67Z" +
        "M145,68.6A8.8,8.8,0,0,1,142.36,72a10.7,10.7,0,0,1-4,1.82,21.57,21.57,0,0,1-5,.55h-4.05v-21h4.6a17,17,0,0,1,4.67.63,11.66,11.66,0,0,1,3.88,1.87A9.14,9.14,0,0,1,145,59a9.87,9.87,0,0,1,1,4.52A11.89,11.89,0,0,1,145,68.6Z" +
        "m44.63-.13a8,8,0,0,0-1.58-2.62A8.38,8.38,0,0,0,185.63,64a10.31,10.31,0,0,0-3.17-1v-.1a9.22,9.22,0,0,0,4.42-2.82,7.43,7.43,0,0,0,1.68-5,8.42,8.42,0,0,0-1.15-4.65,8.09,8.09,0,0,0-3-2.72,12.56,12.56,0,0,0-4.18-1.3,32.84,32.84,0,0,0-4.62-.33h-13.2v35.4h14.5a22.41,22.41,0,0,0,4.72-.5,13.53,13.53,0,0,0,4.28-1.65,9.42,9.42,0,0,0,3.1-3,8.52,8.52,0,0,0,1.2-4.68A9.39,9.39,0,0,0,189.66,68.47Z" +
        "M170.21,52.72h5.3a10,10,0,0,1,1.85.18,6.18,6.18,0,0,1,1.7.57,3.39,3.39,0,0,1,1.22,1.13,3.22,3.22,0,0,1,.48,1.82,3.63,3.63,0,0,1-.43,1.8,3.4,3.4,0,0,1-1.12,1.2,4.92,4.92,0,0,1-1.58.65,7.51,7.51,0,0,1-1.77.2h-5.65Z" +
        "m11.72,20a3.9,3.9,0,0,1-1.22,1.3,4.64,4.64,0,0,1-1.68.7,8.18,8.18,0,0,1-1.82.2h-7v-8h5.9a15.35,15.35,0,0,1,2,.15,8.47,8.47,0,0,1,2.05.55,4,4,0,0,1,1.57,1.18,3.11,3.11,0,0,1,.63,2A3.71,3.71,0,0,1,181.93,72.72Z"
