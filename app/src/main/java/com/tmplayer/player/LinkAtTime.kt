package com.tmplayer.player

import com.tmplayer.data.TelegramLinks
import com.tmplayer.data.TgLink

/**
 * "Copy link at this time" (U9): the video's own Telegram link, as TDLib gives it, with `?t=<seconds>`
 * added so TMPlayer (which already opens a time from a link, [TgLink.Post.startSec]) starts there.
 *
 * Built through the link parser rather than by appending text, so a link that already has a query
 * (`?thread=`, `?comment=`) keeps it and the time joins it, and an existing `t=` is replaced, not
 * doubled. Whatever is not a post link gives null: the caller says there is no link.
 */
object LinkAtTime {

    /** A public channel's `t.me/name/123`, a private chat's `t.me/c/...` or a topic's, at [positionMs]. */
    fun of(link: String?, positionMs: Long): String? {
        val post = TelegramLinks.parse(link.orEmpty()) as? TgLink.Post ?: return null
        val seconds = (positionMs.coerceIn(0L, MAX_MS) / 1_000L).toInt()
        return post.copy(startSec = seconds).url
    }

    /** Whether the menu line is offered: a video from a message that may be shared, and not one built from pieces. */
    fun offered(hasMessage: Boolean, mayShare: Boolean, fromPieces: Boolean): Boolean =
        hasMessage && mayShare && !fromPieces

    /** A day and a half: past any real video, so a bad position cannot make a silly number. */
    private const val MAX_MS = 129_600_000L
}
