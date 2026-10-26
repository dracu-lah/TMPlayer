package com.tmplayer.player

import com.tmplayer.data.MediaItem

/**
 * A copy played in place of one too heavy for this device (HX2), handed from whoever chose it (the
 * Play press's copy choice, or the player moving to a smooth copy as a video starts) to the player
 * that opens it, which says so once as the picture lands: "Playing the 720p copy. The 1080p copy
 * is too heavy for this TV." with Change. One at a time; taken by the player of that copy only.
 */
object SmootherPick {

    private class Pick(val chatId: Long, val messageId: Long, val heavy: String)

    @Volatile
    private var pending: Pick? = null

    /** [copy] is about to play instead of the copy named [heavy] ("1080p"). */
    fun offer(copy: MediaItem, heavy: String) {
        pending = Pick(copy.chatId, copy.messageId, heavy)
    }

    /** The name of the copy passed over for this video, once; null when none was. */
    fun take(chatId: Long, messageId: Long): String? {
        val pick = pending ?: return null
        if (pick.chatId != chatId || pick.messageId != messageId) return null
        pending = null
        return pick.heavy
    }
}
