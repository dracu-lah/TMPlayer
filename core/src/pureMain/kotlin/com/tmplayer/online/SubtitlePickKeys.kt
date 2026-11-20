package com.tmplayer.online

import com.tmplayer.data.MediaItem
import com.tmplayer.data.SeriesShelf

/**
 * The keys a video's remembered online subtitle is filed under (HX25), apart from the files
 * [SubtitlePicks] keeps: no JDK in it, so the web player (WebRules) files its picks under the same
 * keys, and a re-post or another copy of a video finds the pick there as it does in the apps.
 */
object SubtitlePickKeys {
    const val MAX_KEY = 400

    /**
     * The keys a video is remembered under, most exact first: this post, then the video itself
     * ([SeriesShelf.sameVideoKey]: a film's title and year, a show's episode), so another copy
     * or a re-post of it finds the pick. A video that names nothing has the post alone.
     */
    fun of(item: MediaItem): List<String> = listOfNotNull(
        "msg:${item.chatId}:${item.messageId}".takeIf { item.messageId != 0L },
        runCatching { SeriesShelf.sameVideoKey(item) }.getOrNull()?.takeIf { it.isNotBlank() }?.let { "video:$it".take(MAX_KEY) },
    )
}
