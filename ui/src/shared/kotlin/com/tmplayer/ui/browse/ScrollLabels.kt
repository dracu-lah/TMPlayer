package com.tmplayer.ui.browse

import com.tmplayer.data.NameInitial
import com.tmplayer.data.ShelfEntry
import com.tmplayer.data.VideoSort
import com.tmplayer.data.VideoSorting
import com.tmplayer.i18n.Messages

/**
 * What the fast scroller's bubble says while its handle is dragged ([com.tmplayer.ui.components.FastScroller]):
 * where the list has got to, in the terms of its own order. A chat's videos by date show the date
 * at the top of the screen, by name the letter, by year the year, by size the size and by length
 * the length; an order that groups by something with no name (unwatched first) shows no bubble.
 */
object ScrollLabels {

    /** The bubble for [entry] in a chat's grid listed in [sort], or null for none. */
    fun video(entry: ShelfEntry, sort: VideoSort, s: Messages): String? =
        when (val value = VideoSorting.placedBy(entry, sort)) {
            null -> null
            is String -> letter(value)
            is Int -> when (sort) {
                VideoSort.Newest, VideoSort.Oldest -> value.takeIf { it > 0 }?.let { s.formatter.date(it * 1000L) }
                VideoSort.Year -> value.toString()
                else -> null
            }
            is Long -> when (sort) {
                VideoSort.Size -> value.takeIf { it > 0 }?.let(s.formatter::size)
                VideoSort.Longest -> value.takeIf { it > 0 }?.let(s.formatter::duration)
                else -> null
            }
            else -> null
        }

    /**
     * The letter a name is filed under: its first letter or digit, upper case, with any digit
     * filed as "#" so a run of "1917", "2012" and "300" is one stretch of the list.
     */
    fun letter(name: String): String? {
        val first = NameInitial.of(name) ?: return null
        return if (first.first().isDigit()) "#" else first
    }
}
