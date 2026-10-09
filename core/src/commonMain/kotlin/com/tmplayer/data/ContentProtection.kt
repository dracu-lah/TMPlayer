package com.tmplayer.data

import com.tmplayer.i18n.L

/**
 * What a chat's owner allows done with its videos, read the way Telegram's own clients read it.
 *
 * Telegram's API terms ask third-party clients to honour two things, and TMPlayer's legal page
 * promises it does. A chat or a message with "restrict saving content" turned on may be watched but
 * not kept: no download, no copy into Downloads, no share, no handing the file to another app.
 * Self-destructing media is stricter still: it is meant to be seen once in Telegram itself, so
 * TMPlayer does not list it at all.
 */
object ContentProtection {

    enum class Verdict {
        /** Watch, download, share: everything. */
        Full,

        /** Streams like any other video, and nothing else. */
        StreamOnly,

        /** Not listed and not playable. */
        Hidden,
    }

    /**
     * [canBeSaved] is TDLib's `message.can_be_saved`, which already folds in the chat's setting;
     * [chatProtected] is `chat.has_protected_content`, read as well so a message seen before the
     * chat's setting changed cannot outvote it.
     */
    fun verdict(canBeSaved: Boolean, chatProtected: Boolean, selfDestructs: Boolean): Verdict = when {
        selfDestructs -> Verdict.Hidden
        !canBeSaved || chatProtected -> Verdict.StreamOnly
        else -> Verdict.Full
    }

    /** A page of messages after the screen: the videos to list, and how many were left out. */
    data class Screened(val items: List<MediaItem>, val selfDestructing: Int)

    /**
     * Maps [entries] to videos, leaving out the self-destructing ones and counting them.
     *
     * Only entries that would have been videos are counted, so a self-destructing photo in the
     * same chat does not count as a missing video.
     */
    fun <T> screen(
        entries: List<T>,
        selfDestructs: (T) -> Boolean,
        map: (T) -> MediaItem?,
    ): Screened {
        var hidden = 0
        val items = entries.mapNotNull { entry ->
            val item = map(entry) ?: return@mapNotNull null
            if (selfDestructs(entry)) {
                hidden++
                null
            } else {
                item
            }
        }
        return Screened(items, hidden)
    }

    /** Said when a download is refused, or a finished one is left in the cache, for this reason. */
    val NOT_SAVABLE: String get() = L.downloadsNotSavable
}
