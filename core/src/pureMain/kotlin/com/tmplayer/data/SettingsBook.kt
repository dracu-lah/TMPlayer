package com.tmplayer.data

import com.tmplayer.platform.nowMillis
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The settings the browsing screens read, over any [KeyValueStore], with SettingsStore's own key
 * names and encodings, so a file (or a backup) written by one is read by the other: favourites,
 * the theme, the accent and the language, the kids lock, the card layouts and the chips, the sort
 * and the size limits, recent searches, and the resume points that make Continue watching.
 *
 * SettingsStore on the JVM keeps the rest (downloads, the cache, updates, the player's details),
 * which the browser build either has no use for or keeps in the page. SettingsBookTest holds the
 * two to the same answers over one DataStore file.
 */
class SettingsBook(private val kv: KeyValueStore) : AllowedChatsSource, CorrectionsSource, HiddenChatsSource {

    private fun <T> read(transform: (Map<String, Any>) -> T): Flow<T> = kv.data.map(transform).distinctUntilChanged()

    private fun Map<String, Any>.string(name: String): String? = this[name] as? String
    private fun Map<String, Any>.flag(name: String): Boolean? = this[name] as? Boolean
    private fun Map<String, Any>.long(name: String): Long? = this[name] as? Long

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any>.strings(name: String): Set<String> = (this[name] as? Set<String>).orEmpty()

    // ---- favourites ---------------------------------------------------------------------------

    val favorites: Flow<Set<Long>> = read { e -> e.strings(FAVORITES).mapNotNull { it.toLongOrNull() }.toSet() }

    /** Stars or unstars [chatId]; true when it is now a favourite. */
    suspend fun toggleFavorite(chatId: Long): Boolean {
        var now = false
        kv.edit { e ->
            val current = e.strings(FAVORITES).toMutableSet()
            val key = chatId.toString()
            now = if (current.remove(key)) false else current.add(key)
            e[FAVORITES] = current
        }
        return now
    }

    // ---- appearance and language ----------------------------------------------------------------

    val themeChoice: Flow<ThemeChoice> = read { ThemeChoice.from(it.string(THEME_CHOICE)) }

    suspend fun setThemeChoice(value: ThemeChoice) = kv.edit { e ->
        settleLegacyBlack(e)
        e[THEME_CHOICE] = value.name
    }

    val pureBlack: Flow<Boolean> = read { it.string(THEME_CHOICE) == ThemeChoice.LEGACY_BLACK || it.flag(PURE_BLACK) == true }

    suspend fun setPureBlack(value: Boolean) = kv.edit { e ->
        settleLegacyBlack(e)
        e[PURE_BLACK] = value
    }

    val accent: Flow<Accent> = read { Accent.from(it.string(ACCENT)) }

    suspend fun setAccent(value: Accent) = kv.edit { it[ACCENT] = value.name }

    /** The UI language as picked, "" or null to follow the device. */
    val language: Flow<String?> = read { it.string(LANGUAGE) }

    suspend fun setLanguage(tag: String) = kv.edit { e -> if (tag.isBlank()) e.remove(LANGUAGE) else e[LANGUAGE] = tag }

    val reduceMotion: Flow<Boolean> = read { it.flag(REDUCE_MOTION) ?: false }

    suspend fun setReduceMotion(value: Boolean) = kv.edit { it[REDUCE_MOTION] = value }

    val uiScale: Flow<UiScale> = read { UiScale.from(it.string(UI_SCALE)) }

    suspend fun setUiScale(value: UiScale) = kv.edit { it[UI_SCALE] = value.name }

    private fun settleLegacyBlack(e: MutableMap<String, Any>) {
        if (e.string(THEME_CHOICE) == ThemeChoice.LEGACY_BLACK) {
            e[THEME_CHOICE] = ThemeChoice.Dark.name
            e[PURE_BLACK] = true
        }
    }

    // ---- the kids lock -------------------------------------------------------------------------

    /** The lock rows of Settings. The PIN itself is checked by the platform (AppLock on the JVM). */
    val appLock: Flow<AppLockSettings> = read { e ->
        val pinSet = e.string(APP_PIN) != null
        AppLockSettings(
            pinSet = pinSet,
            onOpen = pinSet && e.flag(LOCK_ON_OPEN) == true,
            kids = KidsLock.inForce(e.flag(KIDS_LOCK) == true, pinSet),
            kidsChats = kidsChatsOf(e),
        )
    }

    override val kidsAllowedChats: Flow<Set<Long>?> = read(::allowedChatsOf)

    /** The chats hidden from the lists, under SettingsStore's own key ([HiddenChats.KEY]). */
    override val hiddenChats: Flow<List<HiddenChat>> = read { HiddenChats.decode(it[HiddenChats.KEY] as? String) }

    suspend fun setHiddenChats(chats: List<HiddenChat>) {
        kv.edit { it[HiddenChats.KEY] = HiddenChats.encode(chats) }
    }

    /** The stored PIN line (salt and hash, as AppLock writes it), or null when none is set. */
    suspend fun pinLine(): String? = kv.snapshot().string(APP_PIN)

    /** Sets (or changes) the PIN, as SettingsStore.setPin does, forgetting the wrong guesses. */
    suspend fun setPin(pin: String) {
        val line = PinHash.hash(pin)
        kv.edit { e ->
            e[APP_PIN] = line
            e.remove(PIN_FAILURES)
            e.remove(PIN_WAIT_LEFT)
            e.remove(PIN_WAIT_STAMP)
        }
    }

    /** Removes the PIN and what it guards (the lock on opening, the kids lock); the chats stay. */
    suspend fun removePin() = kv.edit { e ->
        listOf(APP_PIN, PIN_FAILURES, PIN_WAIT_LEFT, PIN_WAIT_STAMP, LOCK_ON_OPEN, KIDS_LOCK).forEach(e::remove)
    }

    /** As SettingsStore.checkPin: a wrong PIN counts, and a wait earned is waited out first. */
    suspend fun checkPin(pin: String, now: Long = PinClock.now()): PinCheck {
        var verdict: PinCheck = PinCheck.NoPin
        kv.edit { e ->
            val stored = e.string(APP_PIN) ?: return@edit
            val attempts = PinAttempts.restored(
                failures = (e.long(PIN_FAILURES) ?: 0L).toInt(),
                waitLeftMs = e.long(PIN_WAIT_LEFT) ?: 0L,
                stampedAt = e.long(PIN_WAIT_STAMP) ?: 0L,
                now = now,
            )
            val wait = attempts.waitMs(now)
            verdict = when {
                wait > 0 -> PinCheck.Waiting(wait)
                PinHash.verify(pin, stored) -> {
                    e.remove(PIN_FAILURES)
                    e.remove(PIN_WAIT_LEFT)
                    e.remove(PIN_WAIT_STAMP)
                    PinCheck.Right
                }
                else -> {
                    val next = attempts.failed(now)
                    e[PIN_FAILURES] = next.failures.toLong()
                    e[PIN_WAIT_LEFT] = next.waitMs(now)
                    e[PIN_WAIT_STAMP] = now
                    PinCheck.Wrong(next)
                }
            }
        }
        return verdict
    }

    suspend fun setLockOnOpen(on: Boolean) = kv.edit { it[LOCK_ON_OPEN] = on }

    suspend fun setKidsLock(on: Boolean) = kv.edit { it[KIDS_LOCK] = on }

    suspend fun setKidsChats(chats: Set<Long>) = kv.edit { it[KIDS_CHATS] = chats.map(Long::toString).toSet() }

    private fun kidsChatsOf(e: Map<String, Any>): Set<Long> = e.strings(KIDS_CHATS).mapNotNull { it.toLongOrNull() }.toSet()

    private fun allowedChatsOf(e: Map<String, Any>): Set<Long>? =
        if (KidsLock.inForce(e.flag(KIDS_LOCK) == true, e.string(APP_PIN) != null)) kidsChatsOf(e) else null

    // ---- corrections ---------------------------------------------------------------------------

    /** Renames and fixed matches, by [MediaItem.id], as SettingsStore keeps them. */
    override val corrections: Flow<Map<String, VideoCorrection>> = read { e ->
        e.mapNotNull { (name, value) ->
            val id = VideoCorrections.idOf(name) ?: return@mapNotNull null
            val correction = VideoCorrection.decode(value as? String)
            if (correction.isEmpty) null else id to correction
        }.toMap()
    }

    // ---- layouts, chips and sorting --------------------------------------------------------------

    val chatFilter: Flow<String?> = read { it.string(CHAT_FILTER) }

    suspend fun setChatFilter(name: String) = kv.edit { it[CHAT_FILTER] = name }

    val chatSort: Flow<String?> = read { it.string(CHAT_SORT) }

    suspend fun setChatSort(name: String) = kv.edit { it[CHAT_SORT] = name }

    val historyTab: Flow<String?> = read { it.string(HISTORY_TAB) }

    suspend fun setHistoryTab(name: String) = kv.edit { it[HISTORY_TAB] = name }

    fun videoSort(chatId: Long): Flow<VideoSort> = read { VideoSort.decode(it.string(videoSortKey(chatId))) }

    suspend fun setVideoSort(chatId: Long, sort: VideoSort) = kv.edit { e ->
        if (sort == VideoSort.DEFAULT) e.remove(videoSortKey(chatId)) else e[videoSortKey(chatId)] = sort.name
    }

    val minSizeBytes: Flow<Long> = read { it.long(MIN_SIZE) ?: SizeFilter.DEFAULT_MIN }
    val maxSizeBytes: Flow<Long> = read { it.long(MAX_SIZE) ?: SizeFilter.DEFAULT_MAX }

    suspend fun setMinSizeBytes(value: Long) = kv.edit { e ->
        e[MIN_SIZE] = SizeFilter.clampMin(value, e.long(MAX_SIZE) ?: SizeFilter.DEFAULT_MAX)
    }

    suspend fun setMaxSizeBytes(value: Long) = kv.edit { e ->
        e[MAX_SIZE] = SizeFilter.clampMax(value, e.long(MIN_SIZE) ?: SizeFilter.DEFAULT_MIN)
    }

    val autoplayNext: Flow<Boolean> = read { it.flag(AUTOPLAY_NEXT) ?: true }

    suspend fun setAutoplayNext(value: Boolean) = kv.edit { it[AUTOPLAY_NEXT] = value }

    val lastChatId: Flow<Long> = read { it.long(LAST_CHAT) ?: 0L }

    suspend fun setLastChat(chatId: Long) = kv.edit { it[LAST_CHAT] = chatId }

    // ---- searches ------------------------------------------------------------------------------

    val recentSearches: Flow<List<String>> = read { RecentSearches.decode(it.string(RECENT_SEARCHES)) }
    val rememberSearches: Flow<Boolean> = read { it.flag(REMEMBER_SEARCHES) ?: true }

    suspend fun setRememberSearches(value: Boolean) = kv.edit { e ->
        e[REMEMBER_SEARCHES] = value
        if (!value) e.remove(RECENT_SEARCHES)
    }

    suspend fun addRecentSearch(query: String) = kv.edit { e ->
        if (e.flag(REMEMBER_SEARCHES) == false) return@edit
        val before = RecentSearches.decode(e.string(RECENT_SEARCHES))
        val after = RecentSearches.add(before, query)
        if (after != before) e[RECENT_SEARCHES] = RecentSearches.encode(after)
    }

    suspend fun removeRecentSearch(query: String) = kv.edit { e ->
        val after = RecentSearches.remove(RecentSearches.decode(e.string(RECENT_SEARCHES)), query)
        if (after.isEmpty()) e.remove(RECENT_SEARCHES) else e[RECENT_SEARCHES] = RecentSearches.encode(after)
    }

    suspend fun clearRecentSearches() = kv.edit { it.remove(RECENT_SEARCHES) }

    // ---- resume points ---------------------------------------------------------------------------

    /** Every resume point by `<chat>_<message>`, for the progress bars on a grid. */
    val watchProgress: Flow<Map<String, WatchPoint>> = read { e ->
        buildMap {
            for ((name, value) in e) {
                if (!name.startsWith(RESUME)) continue
                val ids = name.removePrefix(RESUME)
                val positionMs = value as? Long ?: continue
                put(ids, WatchPoint(positionMs, e.long(DURATION + ids) ?: 0L))
            }
        }
    }

    /** Everything half watched, most recent first, without the chats the kids lock hides. */
    val continueWatching: Flow<List<ResumeRecord>> = read { e ->
        val allowed = allowedChatsOf(e)
        buildList {
            for ((name, value) in e) {
                if (!name.startsWith(RESUME)) continue
                val ids = name.removePrefix(RESUME)
                val positionMs = value as? Long ?: continue
                val record = ResumeRecord.decode(ids, e.string(META + ids), positionMs, e.long(DURATION + ids) ?: 0L) ?: continue
                if (KidsLock.allows(allowed, record.chatId)) add(record)
            }
        }.sortedByDescending { it.updatedAt }
    }

    suspend fun resumePosition(chatId: Long, messageId: Long): Long = kv.snapshot().long(RESUME + ids(chatId, messageId)) ?: 0L

    suspend fun resumeRecord(chatId: Long, messageId: Long): ResumeRecord? {
        val e = kv.snapshot()
        val ids = ids(chatId, messageId)
        val positionMs = e.long(RESUME + ids) ?: return null
        return ResumeRecord.decode(ids, e.string(META + ids), positionMs, e.long(DURATION + ids) ?: 0L)
    }

    /** Where a video starts: its place less the rewind on resume. */
    suspend fun resumeStart(chatId: Long, messageId: Long): Long {
        val e = kv.snapshot()
        return ResumeRewind.start(e.long(RESUME + ids(chatId, messageId)) ?: 0L, ResumeRewind.sanitise(e.long(RESUME_REWIND)))
    }

    /**
     * The player's heartbeat, by SettingsStore.saveResumePosition's rules: nothing in the first
     * three minutes (a place saved before stands), the place forgotten in the credits (with every
     * other copy's), the history capped at [MAX_HISTORY].
     */
    suspend fun saveResumePosition(chatId: Long, messageId: Long, positionMs: Long, durationMs: Long = 0L, description: String? = null) {
        kv.edit { e ->
            val ids = ids(chatId, messageId)
            val kept = ResumeRewind.kept(e.long(RESUME + ids), positionMs, ResumeRewind.sanitise(e.long(RESUME_REWIND)))
            if (kept < ResumeRules.MIN_POSITION_MS) return@edit
            if (!ResumeRules.keeps(kept, durationMs)) {
                val played = (description?.let { ResumeRecord.decode(ids, it, 0L, 0L) } ?: ResumeRecord.decode(ids, e.string(META + ids), 0L, 0L))?.toMediaItem()
                forget(e, ids)
                if (played != null) forgetCopies(e, played)
            } else {
                e[RESUME + ids] = kept
                if (durationMs > 0) e[DURATION + ids] = durationMs
                if (description != null) e[META + ids] = description
                evictOldestHistory(e)
            }
        }
    }

    /** Forgets this video's place and every other copy's. */
    suspend fun clearResumePosition(chatId: Long, messageId: Long, item: MediaItem? = null) = kv.edit { e ->
        val ids = ids(chatId, messageId)
        val played = item ?: ResumeRecord.decode(ids, e.string(META + ids), 0L, 0L)?.toMediaItem()
        forget(e, ids)
        if (played != null) forgetCopies(e, played)
    }

    /** "Remove from Continue watching" for a card that stands for several copies. */
    suspend fun clearResumeCopies(record: ResumeRecord) {
        val copies = HomeRows.copiesOf(record, continueWatching.first())
        kv.edit { e -> for (copy in copies) forget(e, ids(copy.chatId, copy.messageId)) }
    }

    /** Empties Continue watching. */
    suspend fun clearWatchHistory() = kv.edit { e ->
        e.keys.filter { it.startsWith(RESUME) || it.startsWith(DURATION) || it.startsWith(META) || it.startsWith(DELAY) }
            .forEach(e::remove)
    }

    private fun forget(e: MutableMap<String, Any>, ids: String) {
        e.remove(RESUME + ids)
        e.remove(DURATION + ids)
        e.remove(META + ids)
    }

    private fun forgetCopies(e: MutableMap<String, Any>, played: MediaItem) {
        val copies = e.keys.filter { it.startsWith(META) }.mapNotNull { name ->
            val ids = name.removePrefix(META)
            ResumeRecord.decode(ids, e.string(name), 0L, 0L)
        }.filter { it.messageId != played.messageId || it.chatId != played.chatId }
            .filter { VideoCopies.sameVideo(played, it) }
        for (copy in copies) forget(e, ids(copy.chatId, copy.messageId))
    }

    private fun evictOldestHistory(e: MutableMap<String, Any>) {
        val stamps = e.keys.filter { it.startsWith(META) }.map { name ->
            name.removePrefix(META) to (ResumeRecord.updatedAtOf(e.string(name)) ?: 0L)
        }
        if (stamps.size <= MAX_HISTORY) return
        stamps.sortedBy { it.second }.take(stamps.size - MAX_HISTORY).forEach { (ids, _) ->
            forget(e, ids)
            e.remove(DELAY + ids)
        }
    }

    /** A description line for [item]'s resume point, as the player writes one. */
    fun describe(item: MediaItem, chatTitle: String, state: ResumeState? = null): String = ResumeRecord.encode(
        fileId = item.fileId,
        title = item.title,
        chatTitle = chatTitle,
        sizeBytes = item.sizeBytes,
        durationSec = item.durationSec,
        updatedAt = nowMillis(),
        state = state,
        fileName = item.fileName,
        caption = item.caption,
    )

    companion object {
        /** As SettingsStore.MAX_HISTORY. */
        const val MAX_HISTORY = 200

        const val FAVORITES = "favorite_chats"
        const val THEME_CHOICE = "theme_choice"
        const val PURE_BLACK = "pure_black"
        const val ACCENT = "accent"
        const val LANGUAGE = "language"
        const val REDUCE_MOTION = "reduce_motion"
        const val UI_SCALE = "ui_scale"
        const val APP_PIN = "app_pin"
        const val PIN_FAILURES = "app_pin_failures"
        const val PIN_WAIT_LEFT = "app_pin_wait_left_ms"
        const val PIN_WAIT_STAMP = "app_pin_wait_stamp"
        const val LOCK_ON_OPEN = "app_lock_on_open"
        const val KIDS_LOCK = "kids_lock"
        const val KIDS_CHATS = "kids_chats"
        const val CHAT_FILTER = "chat_filter"
        const val CHAT_SORT = "chat_sort"
        const val HISTORY_TAB = "history_tab"
        const val MIN_SIZE = "min_size_bytes"
        const val MAX_SIZE = "max_size_bytes"
        const val AUTOPLAY_NEXT = "autoplay_next"
        const val LAST_CHAT = "last_chat"
        const val RECENT_SEARCHES = "recent_searches"
        const val REMEMBER_SEARCHES = "remember_searches"
        const val RESUME_REWIND = "rewind_on_resume_ms"
        private const val RESUME = "resume_"
        private const val DURATION = "duration_"
        private const val META = "meta_"
        private const val DELAY = "delay_"

        private fun ids(chatId: Long, messageId: Long) = ProgressKeys.of(chatId, messageId)

        fun videoSortKey(chatId: Long): String = "vsort_$chatId"
    }
}
