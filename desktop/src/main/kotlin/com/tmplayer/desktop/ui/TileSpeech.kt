package com.tmplayer.desktop.ui

/**
 * What a screen reader adds after a video card's title and line (WP-50): how far it was watched and
 * what is true of it, joined with a comma. Blank parts are left out; nothing to say is "".
 */
internal fun tileSpokenState(percentWatched: String?, state: String?): String =
    listOf(percentWatched, state).filter { !it.isNullOrBlank() }.joinToString(", ")

/**
 * The whole percent of a video watched, for "40 percent watched": null for none, or all but the
 * last of it (a finished video says Watched), so the words are never "0 percent" or "100 percent".
 */
internal fun watchedPercentOf(progress: Float?): Int? {
    val whole = ((progress ?: return null) * 100f).toInt()
    return whole.takeIf { it in 1..99 }
}
