package com.tmplayer.ui.browse

import com.tmplayer.data.HomeRow
import com.tmplayer.i18n.Messages

/**
 * A Home row's heading, the same on the phone, the television and the desktop: a starred chat's
 * row is headed by the chat's name.
 */
fun homeRowTitle(row: HomeRow, s: Messages): String = when (row) {
    is HomeRow.Continue -> if (row.onPhone) s.homeRowOnPhone else s.homeRowContinue
    is HomeRow.WatchLater -> s.homeRowWatchLater
    is HomeRow.Listed -> row.name
    is HomeRow.NewEpisodes -> s.homeRowNewEpisodes
    is HomeRow.Chat -> row.title
    is HomeRow.Recent -> if (row.starredOnly) s.homeRowRecent else s.homeRowRecentAll
}

/** Whether [row] has its tiles yet; a row that has not is drawn as a placeholder strip of the same height. */
val HomeRow.isLoaded: Boolean
    get() = when (this) {
        is HomeRow.Continue, is HomeRow.WatchLater, is HomeRow.Listed, is HomeRow.NewEpisodes -> true
        is HomeRow.Chat -> loaded
        is HomeRow.Recent -> loaded
    }
