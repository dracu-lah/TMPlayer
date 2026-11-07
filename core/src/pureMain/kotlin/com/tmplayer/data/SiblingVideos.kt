package com.tmplayer.data

import kotlin.concurrent.Volatile

/**
 * The videos the chat grid on screen holds, so a screen that acts on one video (the detail page's
 * "Choose the right poster") can offer the same answer to the others of its title in that chat
 * ([VideoCorrections.sameTitleIn]) without being handed the whole list. Only the latest chat is
 * kept, and only a reference to the list its grid already holds.
 */
object SiblingVideos {

    private class Held(val chatId: Long, val items: List<MediaItem>)

    @Volatile
    private var held: Held? = null

    fun note(chatId: Long, items: List<MediaItem>) {
        held = Held(chatId, items)
    }

    /** The videos held for [item]'s chat, or none when another chat (or none) is held. */
    fun of(item: MediaItem): List<MediaItem> = held?.takeIf { it.chatId == item.chatId }?.items.orEmpty()

    /** The other files of [item]'s title in its chat, copies of the tile and extras left out. */
    fun sameTitle(item: MediaItem): List<MediaItem> = VideoCorrections.sameTitleIn(item, of(item))
}
