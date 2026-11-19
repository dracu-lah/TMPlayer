package com.tmplayer.data

/**
 * How a per series setting is named in storage, apart from the store that holds it: no JDK in it,
 * so the web player (WebRules) files a series' tracks and speed under the same names as the apps,
 * and a backup or the sync file reads either's.
 */
object StoredKeys {

    /**
     * One series, as a key: lower-cased and stripped of everything that is not a letter or a digit,
     * so "Kerala Crime Files" and "Kerala_Crime_Files" are the same show. Two shows would have to
     * differ only in punctuation to collide, and the cost of that is subtitles coming on early.
     */
    fun series(series: String): String = series.lowercase().filter { it.isLetterOrDigit() }.take(SERIES_MAX)

    /**
     * A speed remembered for a show, a course, a chat or a topic ([com.tmplayer.player.SpeedMemory]):
     * the scope keeps its colons and a group's minus sign, so chat 12 topic 34 and chat 123 topic 4
     * never share a key.
     */
    fun speed(scope: String): String =
        "speed_" + scope.lowercase().filter { it.isLetterOrDigit() || it == ':' || it == '-' }.take(SPEED_MAX)

    private const val SERIES_MAX = 48
    private const val SPEED_MAX = 80
}
