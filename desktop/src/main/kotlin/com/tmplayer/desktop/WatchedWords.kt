package com.tmplayer.desktop

import com.tmplayer.data.MediaItem
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.WatchedRecord
import com.tmplayer.data.WatchedStore

/**
 * The small decisions behind the desktop's Watched list, kept apart from Compose so they can be
 * tested: which way a menu's mark line reads, how full a poster's bar is, and the note under a
 * chat's grid when the size limits, or a video's own timer, kept videos out.
 */
object WatchedWords {

    fun markLabel(onList: Boolean): String = if (onList) "Mark as unwatched" else "Mark as watched"

    /**
     * How much of a poster's bar is filled. A video part way through (a second viewing included)
     * shows where it is; a finished one with no saved position runs full, since finishing it is
     * exactly what clears the position and one marked by hand may never have been opened here.
     * Null draws no bar.
     */
    fun posterProgress(fraction: Float?, finished: Boolean): Float? = when {
        fraction != null && fraction > 0f -> fraction
        finished -> 1f
        else -> null
    }

    /** "Watched" under a finished poster's title, unless a second viewing is part way through. */
    fun showsWatched(fraction: Float?, finished: Boolean): Boolean = finished && (fraction == null || fraction <= 0f)

    /** The quiet line at the end of a chat's grid, or null when nothing was kept out. */
    fun sizeLimitNote(hidden: Int): String? = when {
        hidden <= 0 -> null
        hidden == 1 -> "1 video hidden by the size limits"
        else -> "$hidden videos hidden by the size limits"
    }

    /**
     * The same line with the self-destructing videos added. Those are left out of every listing,
     * because they are meant to be seen once in Telegram itself, so the line says where to go.
     */
    fun hiddenNote(bySize: Int, selfDestructing: Int): String? {
        val destructing = when {
            selfDestructing <= 0 -> null
            selfDestructing == 1 -> "1 self-destructing video not shown: open it in Telegram"
            else -> "$selfDestructing self-destructing videos not shown: open them in Telegram"
        }
        return listOfNotNull(sizeLimitNote(bySize), destructing).joinToString(". ").ifEmpty { null }
    }
}

/**
 * Marks [item] watched by hand, or takes the mark off. Marking also forgets the saved position,
 * which is what takes the video out of Continue watching, as on the phone.
 */
suspend fun setWatched(
    store: WatchedStore,
    settings: SettingsStore,
    item: MediaItem,
    chatTitle: String,
    watched: Boolean,
    now: Long = System.currentTimeMillis(),
) {
    if (watched) {
        store.markWatched(WatchedRecord.of(item, chatTitle, now, manual = true))
        settings.clearResumePosition(item.chatId, item.messageId)
    } else {
        store.markUnwatched(item.chatId, item.messageId)
    }
}
