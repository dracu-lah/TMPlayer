package com.tmplayer.data

/**
 * The one-time hints this device has met, and how often (WP-68). A hint has an id; the registry
 * keeps one count per id, stored as a single device key by [HintRegistry.encode]. A hint shown once
 * has count 1; a counted hint ("the first 5 times") goes on until its count reaches its limit. The
 * same table records a device fact the hints need, such as "a Page or Channel key arrived once".
 *
 * Hints are the device's, not the account's: one person's marks do not come back on a profile
 * switch, and a backup leaves them behind.
 */
class HintState(val counts: Map<String, Int> = emptyMap()) {
    fun count(id: String): Int = counts[id] ?: 0

    /** Shown (or the fact recorded) at least once. */
    fun seen(id: String): Boolean = count(id) >= 1

    /** The ids with a count, for the callers that only ask "seen". */
    val seenIds: Set<String> get() = counts.filterValues { it >= 1 }.keys

    /** Every hint is off once the tour has been skipped [HintRegistry.TOUR_SKIPS_TO_SILENCE] times. */
    val hintsOff: Boolean get() = count(HintIds.TOUR_SKIPPED) >= HintRegistry.TOUR_SKIPS_TO_SILENCE

    /** Nothing was ever shown: the first run, the only time a hint may appear during playback. */
    val firstRun: Boolean get() = counts.isEmpty()

    override fun equals(other: Any?): Boolean = other is HintState && other.counts == counts
    override fun hashCode(): Int = counts.hashCode()
}

/** The ids of the hints and device facts the app already knows. A new hint adds its id here. */
object HintIds {
    /** The player's first-run hint (double tap or arrows jump). Was `player_hint_seen`. */
    const val PLAYER = "player"

    /** The television's browse hint. Was `tv_browse_hint_seen`. */
    const val TV_BROWSE = "tv_browse"

    /** The one line that says where "Play in TMPlayer" is. Was `external_hint_seen`. */
    const val EXTERNAL = "external"

    /** The desktop and web player's first-run line: Space plays, Left and Right jump, ? lists every key (WP-47). */
    const val PLAYER_KEYS = "player_keys"

    /** Device fact, not a hint: how many times the quick tour was turned down or skipped. */
    const val TOUR_SKIPPED = "tour_skipped"

    /** The long list hint: Back goes to the top, or the Channel keys sentence (WP-12). */
    const val LONG_LIST = "long_list"

    /** Device fact, not a hint: a Page or Channel key arrived once on this remote (WP-12, WP-75). */
    const val PAGE_KEY_ARRIVED = "page_key_arrived"

    /** The television Settings' line that Page and Channel keys jump between sections, once such a key has arrived (WP-75). */
    const val SETTINGS_PAGE_KEYS = "settings_page_keys"

    /** The scrub card's "Let go to jump here", the first three scans of the television's scan option (WP-79). */
    const val SCAN_JUMP = "scan_jump"

    /** The first Info press on the television's player: Hold Info reports a stutter (WP-21). */
    const val INFO_HOLD = "info_hold"

    /** The tile's second line on the television: Hold OK or press Menu for options (WP-14). */
    const val TILE_OPTIONS = "tile_options"

    /**
     * The phone's "Settings, your lists and your account are behind your picture now" (WP-103): once,
     * for an install that had the gear; a fresh install has it marked seen by its first sign in screen.
     */
    const val NAV_ACCOUNT_MOVED = "nav_account_moved"

    /**
     * The "Moved to {page}" lines at the top of a Settings page whose rows moved in WP-08, once per
     * old page for one release: `moved_privacy`, `moved_about`, `moved_library` ([page] is the
     * old page's [SettingsGroup] name). Removed, with the aliases, in the release after the beta.
     */
    fun moved(page: String): String = "moved_" + page.lowercase()
}

object HintRegistry {
    /** The tour skipped this many times turns every hint off. */
    const val TOUR_SKIPS_TO_SILENCE = 2

    /** The most ids kept: past it the oldest go, so a garbage value cannot grow without bound. */
    const val MAX_IDS = 128

    /** The longest stored string read at all. */
    const val MAX_STORED_CHARS = 8_192

    /** The longest id; a count never passes [MAX_COUNT]. */
    const val MAX_ID = 40
    const val MAX_COUNT = 9_999

    private fun validId(id: String): Boolean =
        id.isNotEmpty() && id.length <= MAX_ID && id.all { it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '.' }

    /**
     * Reads the stored `id=count,id=count` string. A bad entry is skipped and the rest kept; unknown
     * ids are kept (a later version's hints survive); the oldest past [MAX_IDS] are dropped. The
     * three retired flags count as seen when [legacy] names them and nothing newer is stored.
     */
    fun parse(raw: String?, legacy: Set<String> = emptySet()): HintState {
        val map = LinkedHashMap<String, Int>()
        raw?.take(MAX_STORED_CHARS)?.split(',')?.forEach { entry ->
            val id = entry.substringBefore('=', "")
            val count = entry.substringAfter('=', "").toIntOrNull() ?: return@forEach
            if (!validId(id) || count < 1) return@forEach
            map.remove(id)
            map[id] = count.coerceAtMost(MAX_COUNT)
        }
        for (id in legacy) if (id !in map) map[id] = 1
        while (map.size > MAX_IDS) map.remove(map.keys.first())
        return HintState(map)
    }

    fun encode(state: HintState): String =
        state.counts.entries.filter { validId(it.key) && it.value >= 1 }.takeLast(MAX_IDS)
            .joinToString(",") { "${it.key}=${it.value.coerceAtMost(MAX_COUNT)}" }

    /** [state] with [id] raised to at least [to] (default: seen once). An invalid id changes nothing. */
    fun raise(state: HintState, id: String, to: Int = 1): HintState {
        if (!validId(id)) return state
        val map = LinkedHashMap(state.counts)
        val next = maxOf(map[id] ?: 0, to.coerceIn(1, MAX_COUNT))
        map.remove(id)
        map[id] = next
        while (map.size > MAX_IDS) map.remove(map.keys.first())
        return HintState(map)
    }

    /** [state] with [id] counted once more. */
    fun bump(state: HintState, id: String): HintState = raise(state, id, state.count(id) + 1)
}
