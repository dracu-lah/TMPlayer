package com.tmplayer.online

/**
 * A title the poster's own words name: an IMDb or TMDB link, or a bare `tt0111161`, in a file's
 * name or its caption (TS). A channel that writes the link has said which title the video is,
 * which is a better answer than any guess from the file name.
 *
 * Never trusted blindly: two different ids of one kind (a post that links its neighbours too) say
 * nothing, a quoted line (one starting with `>`) is somebody else's, and only the start of a long
 * text is read, so a garbage caption costs the same as an ordinary one. The viewer's own pick
 * and a `{tmdb-123}` still win over a link ([MetaQuery.of]), and a link that finds nothing leaves
 * the title guess to run.
 */
object MetaLinks {

    /** What a text names: a TMDB id with its kind, or an IMDb id; either may be null. */
    data class Found(val tmdbId: Int? = null, val show: Boolean = false, val imdbId: String? = null) {
        val any: Boolean get() = tmdbId != null || imdbId != null
    }

    /** More than this of a text is never read: a caption is a few lines, a file name a few hundred characters. */
    const val MAX_READ = 4_000

    private val IMDB_LINK = Regex("""imdb\.com/(?:[a-z]{2}(?:-[a-z]{2})?/)?title/(tt\d{7,9})(?!\d)""", RegexOption.IGNORE_CASE)
    private val IMDB_WORD = Regex("""(?<![A-Za-z0-9])(tt\d{7,9})(?![A-Za-z0-9])""", RegexOption.IGNORE_CASE)
    private val TMDB_LINK = Regex("""themoviedb\.org/(movie|tv)/(\d{1,9})(?!\d)""", RegexOption.IGNORE_CASE)

    /**
     * The title [texts] (the file name, then the caption) name, or an empty [Found]. A TMDB link
     * wins over an IMDb id: it names the film or show outright.
     */
    fun read(vararg texts: String?): Found {
        val imdb = LinkedHashSet<String>()
        val tmdb = LinkedHashSet<Pair<Int, Boolean>>()
        for (text in texts) {
            if (text.isNullOrEmpty()) continue
            for (line in text.take(MAX_READ).lineSequence()) {
                if (line.trimStart().startsWith(">")) continue
                IMDB_LINK.findAll(line).forEach { imdb += it.groupValues[1].lowercase() }
                IMDB_WORD.findAll(line).forEach { imdb += it.groupValues[1].lowercase() }
                TMDB_LINK.findAll(line).forEach { match ->
                    val id = match.groupValues[2].toIntOrNull()?.takeIf { it > 0 } ?: return@forEach
                    tmdb += id to (match.groupValues[1].lowercase() == "tv")
                }
            }
        }
        val tmdbOne = tmdb.singleOrNull()
        val imdbOne = imdb.singleOrNull()
        return Found(tmdbId = tmdbOne?.first, show = tmdbOne?.second ?: false, imdbId = imdbOne)
    }
}
