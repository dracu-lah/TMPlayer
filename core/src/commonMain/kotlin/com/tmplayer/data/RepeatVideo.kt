package com.tmplayer.data

/**
 * Repeat this video (EG-F52): the video that was playing when it was switched on plays again when
 * it ends, or every video after it with "Keep repeat on" set. Without that setting it lapses by
 * itself when another video starts, with nothing to remember to switch off. Lives with the process,
 * since each video is a new player screen on a phone.
 */
object RepeatVideo {

    @Volatile
    private var on = false

    @Volatile
    private var of: String? = null

    /** Whether [item] plays again when it ends: switched on for this very video, or for an earlier one with [keepRepeat]. */
    fun repeats(item: MediaItem, keepRepeat: Boolean = false): Boolean =
        on && (keepRepeat || of == keyOf(item))

    /**
     * Whether the player starts [item] again when it ends. [keepRepeat] is the "Keep repeat on"
     * setting and [sleepAtTheEnd] a sleep timer set for the end of this video, which wins over
     * Repeat: the video ends and the player asks "Still watching?".
     */
    fun replaysAtEnd(item: MediaItem, keepRepeat: Boolean = false, sleepAtTheEnd: Boolean = false): Boolean =
        !sleepAtTheEnd && repeats(item, keepRepeat)

    /** Repeat this video on for [item], or off; the A-B repeat is cleared by whoever turns this on. */
    fun set(on: Boolean, item: MediaItem? = null) {
        this.on = on && item != null
        of = if (this.on) item?.let(::keyOf) else null
    }

    /** Off, for a sign out or a test. */
    fun reset() = set(false)

    private fun keyOf(item: MediaItem): String = "${item.chatId}:${item.messageId}:${item.fileName}"
}
