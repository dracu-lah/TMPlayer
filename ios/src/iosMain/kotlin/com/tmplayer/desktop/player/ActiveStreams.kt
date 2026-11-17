package com.tmplayer.desktop.player

import java.util.concurrent.ConcurrentHashMap

/**
 * Which files the player has open, as the desktop's player keeps it: a finished download is not
 * moved out from under a video that is playing it. The iOS player's streams report here.
 */
object ActiveStreams {
    private val open = ConcurrentHashMap<Int, Int>()
    fun opened(fileId: Int) {
        open.merge(fileId, 1, Int::plus)
    }
    fun closed(fileId: Int) {
        open.computeIfPresent(fileId) { _, n -> (n - 1).takeIf { it > 0 } }
    }
    fun isOpen(fileId: Int): Boolean = open.containsKey(fileId)
    fun openIds(): Set<Int> = open.keys.toSet()
}
