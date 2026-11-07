package com.tmplayer.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.tmplayer.data.MediaItem
import com.tmplayer.data.PromoCovers

/**
 * The cover pictures found to be an uploader's advert ([PromoCovers]) in any list this run has
 * read, for every tile to ask before it draws a Telegram thumbnail.
 *
 * A list is worked out once, where it lands ([observe], on a background thread, from the chat's
 * view model and from Home's rows), never per tile: a tile only hashes its own ~100 byte preview,
 * and only when anything has been flagged at all. The same picture is the same advert wherever it
 * shows, so what one list found holds for the others; it is kept for the run, at most [MAX_KEYS]
 * pictures (the oldest go first), and a changed set recomposes the tiles that read it.
 */
object PromoCoverBook {
    private var keys by mutableStateOf<Set<Long>>(emptySet())
    private val lock = Any()
    private val held = LinkedHashSet<Long>()

    /** Reads what [items] show about adverts and keeps any new picture found. Safe from any thread. */
    fun observe(items: Collection<MediaItem>) {
        if (items.size < PromoCovers.MIN_FILES) return
        val found = PromoCovers.of(items).flagged
        if (found.isEmpty()) return
        synchronized(lock) {
            if (held.containsAll(found)) return
            for (key in found) {
                // Newest last, so a picture met again outlives one not seen since.
                held.remove(key)
                held.add(key)
            }
            while (held.size > MAX_KEYS) held.remove(held.first())
            keys = held.toSet()
        }
    }

    /** True when a preview with these bytes is a flagged advert. Reads state, so a composition follows it. */
    fun isPromo(bytes: ByteArray?): Boolean {
        val flagged = keys
        if (flagged.isEmpty() || bytes == null || bytes.isEmpty()) return false
        return PromoCovers.keyOf(bytes) in flagged
    }

    /** Forgets everything: for tests, and for the end of a session. */
    fun clear() {
        synchronized(lock) {
            held.clear()
            keys = emptySet()
        }
    }

    const val MAX_KEYS = 2048
}

/**
 * Whether the tile whose inline preview is [mini] wears an advert. False the whole time nothing
 * has been flagged, with no hashing; the answer is kept per preview, and changes (once) when a
 * list that finds the picture to be an advert is read.
 */
@Composable
fun rememberIsPromoCover(mini: ByteArray?): Boolean {
    if (mini == null || mini.isEmpty() || !PromoCoverBook.isPromo(mini)) return false
    return remember(mini) { true }
}
