package com.tmplayer.desktop.os

import com.tmplayer.data.AccountList
import com.tmplayer.data.Accounts
import com.tmplayer.platform.TransferNotifier.OpenTarget

/**
 * What a press on a posted result does (HX88), apart from D-Bus so it can be tested on any OS.
 *
 * A result kept for a profile ([KeyedResults.result]) is [gated]: it opens its file or folder only
 * when its profile is still the open, shown one. A gated entry whose gate is gone (evicted, or
 * never stored) refuses the press, since the answer to "may this open" is unknown; a gate that
 * throws refuses too. An entry that was never gated (a plain completion) opens as before.
 */
internal class PressEntry(
    val target: OpenTarget?,
    private val gate: (() -> Boolean)?,
    private val gated: Boolean = gate != null,
) {
    /** The target to open for a press now, or null when there is none or the press is refused. */
    fun targetToOpen(): OpenTarget? {
        val target = target ?: return null
        if (!gated) return target
        val allowed = gate?.let { runCatching(it).getOrDefault(false) } ?: false
        return if (allowed) target else null
    }
}

/**
 * The server ids posted under each key ([KeyedResults]), bounded: [maxKeys] keys, the oldest out,
 * and [maxPerKey] ids a key. A key used again keeps the earlier ids, so [take] still closes every
 * one of them and none stays on the screen with nothing able to close it. Worker thread only.
 */
internal class KeyedServerIds(private val maxKeys: Int = MAX_KEYS, private val maxPerKey: Int = MAX_PER_KEY) {
    private val ids = LinkedHashMap<Long, ArrayDeque<Long>>()

    val size: Int get() = ids.size

    fun add(key: Long, id: Long) {
        val list = ids.remove(key) ?: ArrayDeque()
        list.addLast(id)
        while (list.size > maxPerKey) list.removeFirst()
        ids[key] = list
        while (ids.size > maxKeys) ids.remove(ids.keys.first())
    }

    /** Every id kept under [key], oldest first, forgotten now. */
    fun take(key: Long): List<Long> = ids.remove(key)?.toList().orEmpty()

    companion object {
        const val MAX_KEYS = 512
        const val MAX_PER_KEY = 16
    }
}

/**
 * Whether a press on a result must be asked about its profile first (HX88). Only with two or more
 * profiles or a PIN can a result outlive the profile that made it; with one profile and no PIN the
 * result keeps its plain press, which still works after the app has quit (an in-memory link would
 * open nothing then).
 */
internal object PressGate {
    fun needed(list: AccountList?): Boolean = list != null && (list.profiles.size >= 2 || list.profiles.any { it.pin != null })

    fun neededNow(): Boolean = runCatching { needed(Accounts.book?.list?.value) }.getOrDefault(true)
}
