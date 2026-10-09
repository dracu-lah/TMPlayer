package com.tmplayer.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.tmplayer.player.PlaybackSpeed
import com.tmplayer.player.SubtitleStyle
import com.tmplayer.player.SyncDelays
import com.tmplayer.player.TouchPrefs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import okio.Path.Companion.toPath
import java.io.File

private val FAVORITES = stringSetPreferencesKey("favorite_chats")
private val OVERVIEW_SEEN = booleanPreferencesKey("overview_seen")
private val PLAYER_HINT_SEEN = booleanPreferencesKey("player_hint_seen")
private val FIRST_VIDEO_TIP_DISMISSED = booleanPreferencesKey("first_video_tip_dismissed")
private val TV_BROWSE_HINT_SEEN = booleanPreferencesKey("tv_browse_hint_seen")
private val OPEN_LAST_CHAT = booleanPreferencesKey("open_last_chat")
private val DOWNLOAD_FIRST = booleanPreferencesKey("download_before_playing")
private val AUTOPLAY_NEXT = booleanPreferencesKey("autoplay_next")
private val DETAIL_FIRST = booleanPreferencesKey("detail_first")
private val DOWNMIX_STEREO = booleanPreferencesKey("downmix_stereo")
private val VOLUME_BOOST = booleanPreferencesKey("volume_boost")
private val WIFI_ONLY = booleanPreferencesKey("wifi_only_downloads")
private val REMOVE_AFTER_WATCHING = booleanPreferencesKey("remove_after_watching")
private val TRICKPLAY = booleanPreferencesKey("trickplay")
private val WATCH_NEXT = booleanPreferencesKey("watch_next")
private val CRASH_REPORTS = booleanPreferencesKey("crash_reports")
private val PLAYBACK_SPEED = floatPreferencesKey("playback_speed")
private val LAST_CHAT = longPreferencesKey("last_chat")
private val MIN_SIZE = longPreferencesKey("min_size_bytes")
private val MAX_SIZE = longPreferencesKey("max_size_bytes")
private val CHAT_LAYOUT = stringPreferencesKey("chat_layout")
private val CHAT_FILTER = stringPreferencesKey("chat_filter")
private val CHAT_SORT = stringPreferencesKey("chat_sort")
private val HIDE_DEFAULT_GROUPS = booleanPreferencesKey("hide_default_groups")
private val FIRST_SIGN_IN_CARD = stringPreferencesKey("first_sign_in_card")
private val HISTORY_TAB = stringPreferencesKey("history_tab")
private val MEDIA_LAYOUT = stringPreferencesKey("media_layout")
private val SERIES_VIEW = booleanPreferencesKey("series_view")
private val CHAT_SNAPSHOT = stringPreferencesKey("chat_snapshot")
private val ACCOUNT_SNAPSHOT = stringPreferencesKey("account_snapshot")
private val THEME_CHOICE = stringPreferencesKey("theme_choice")
private val LANGUAGE = stringPreferencesKey("language")

/** The language the "Now in ..." card last announced. See [com.tmplayer.i18n.LanguageNotice]. */
private val LANGUAGE_ANNOUNCED = stringPreferencesKey("language_announced")

/** The version whose "What's new" has been dealt with. See [WhatsNew]. */
private val LAST_SEEN_VERSION = stringPreferencesKey("last_seen_version")
private val DYNAMIC_COLOUR = booleanPreferencesKey("dynamic_colour")
private val VIDEO_SCALE = stringPreferencesKey("video_scale")

/** Which of the sidebar's folding groups the viewer left open. Absent until the first fold. */

/** The last few searches inside a chat, one per line, newest first. See [RecentSearches]. */
private val RECENT_SEARCHES = stringPreferencesKey("recent_searches")

// The update check's memory, shared by the phone, the TV and the desktop. See [UpdateScheduler].
private val UPDATE_NOTIFY = booleanPreferencesKey("update_notify")
private val UPDATE_LAST_CHECK = longPreferencesKey("update_last_check")
private val UPDATE_SKIPPED = stringPreferencesKey("update_skipped_version")
private val UPDATE_SNOOZED_UNTIL = longPreferencesKey("update_snoozed_until")
private val UPDATE_POPUP_SHOWN = stringPreferencesKey("update_popup_shown")

// The support card's memory. See [SupportReminder]. About this device's viewer rather than the
// Telegram account, so signing out keeps them: "Don't ask again" must not come undone.
private val SUPPORT_FIRST_SEEN = longPreferencesKey("support_first_seen")
private val SUPPORT_PLAYS = longPreferencesKey("support_plays")
private val SUPPORT_SNOOZED_UNTIL = longPreferencesKey("support_snoozed_until")
private val SUPPORT_NEVER = booleanPreferencesKey("support_never")
private val SUPPORT_WATCHES = longPreferencesKey("support_watches")
private val SUPPORT_WATCH_MS = longPreferencesKey("support_watch_ms")
private val SUPPORT_ASKED = longPreferencesKey("support_asked")
private val SUPPORT_SUPPORTER = booleanPreferencesKey("support_supporter")
private val SUPPORT_RECENT = stringPreferencesKey("support_recent")
private val SUPPORT_KEYS = listOf(
    SUPPORT_FIRST_SEEN, SUPPORT_PLAYS, SUPPORT_SNOOZED_UNTIL, SUPPORT_WATCHES, SUPPORT_WATCH_MS, SUPPORT_ASKED,
)

/**
 * The one video the watch cache is holding: which message it came from, and what it is called.
 *
 * Two keys rather than one, because the ids belong to the row and the rest belongs to the record,
 * and [ResumeRecord] already knows how to write the second half. Kept apart from the `dl_` rows on
 * purpose: those are downloads the viewer asked for, this is what pressing Play left behind, and the
 * storage accounting rests on the two never being confused.
 */
private val CACHED_VIDEO_IDS = stringPreferencesKey("cached_video_ids")
private val CACHED_VIDEO = stringPreferencesKey("cached_video")

/**
 * The downloads that have not finished, so a killed process comes back to its queue.
 *
 * One key holding every unfinished request, because it is only ever read whole at launch and
 * rewritten whole as the queue moves. A key per download would leave orphans behind whenever a
 * write was interrupted.
 */
private val DOWNLOAD_QUEUE = stringPreferencesKey("download_queue")

/** Set once the downloads recorded before they had a folder of their own have been moved into it. */
private val DOWNLOADS_MIGRATED = booleanPreferencesKey("downloads_migrated")
private val SCREEN_ORIENTATION = stringPreferencesKey("screen_orientation")

// The phone player's touch settings. See [TouchPrefs].
private val TAP_PLAYS_PAUSES = booleanPreferencesKey("player_tap_plays_pauses")
private val DOUBLE_TAP_MS = longPreferencesKey("double_tap_ms")
private val HOLD_SPEED = floatPreferencesKey("hold_speed")
private val CONTROLS_TIMEOUT_MS = longPreferencesKey("controls_timeout_ms")
private val GESTURE_SEEK = booleanPreferencesKey("gesture_seek")
private val GESTURE_BRIGHTNESS = booleanPreferencesKey("gesture_brightness")
private val GESTURE_VOLUME = booleanPreferencesKey("gesture_volume")
private val PLAYER_HAPTICS = booleanPreferencesKey("player_haptics")
private val SHOW_REMAINING = booleanPreferencesKey("show_remaining_time")

// How subtitles look, on every platform. See [SubtitleStyle].
private val SUBTITLE_SIZE = stringPreferencesKey("subtitle_size")
private val SUBTITLE_BOX = booleanPreferencesKey("subtitle_box")
private val SUBTITLE_POSITION = stringPreferencesKey("subtitle_position")

/**
 * One series, as a key.
 *
 * Lower-cased and stripped of everything that is not a letter or a digit, so "Kerala Crime Files"
 * and "Kerala_Crime_Files" are the same show. Two shows would have to differ only in punctuation
 * to collide, and the cost of that is subtitles coming on a video early.
 */
private fun seriesKey(series: String): String =
    series.lowercase(java.util.Locale.ROOT).filter { it.isLetterOrDigit() }.take(48)

private fun audioKey(series: String) = stringPreferencesKey("audio_$series")
private fun textKey(series: String) = stringPreferencesKey("text_$series")
private fun subtitlesKey(series: String) = booleanPreferencesKey("subs_$series")

/** Where playback stopped, so the next launch can offer to continue. */
private fun resumeKey(chatId: Long, messageId: Long) =
    longPreferencesKey("resume_${chatId}_$messageId")

private fun durationKey(chatId: Long, messageId: Long) =
    longPreferencesKey("duration_${chatId}_$messageId")

/** Title, chat and file id, so a half-watched video can be reopened without its chat loaded. */
private fun metaKey(chatId: Long, messageId: Long) =
    stringPreferencesKey("meta_${chatId}_$messageId")

/** The subtitle and audio offsets the viewer set for one file. See [SyncDelays]. */
private fun delayKey(chatId: Long, messageId: Long) =
    stringPreferencesKey("delay_${chatId}_$messageId")

/**
 * The same line again, for the Downloads screen.
 *
 * It cannot read the resume history instead: a video is written there only once three minutes of it
 * have been watched (see [ResumeRules]), and a download the viewer started and walked away from is precisely the one taking
 * up the space they are looking for. This is written the moment playback is allowed to begin.
 */
private fun downloadKey(chatId: Long, messageId: Long) =
    stringPreferencesKey("dl_${chatId}_$messageId")

/** The same line again for a video nobody asked to keep, which playing one leaves behind. */
private const val CACHED_PREFIX = "wc_"
private const val CARD_PENDING = "pending"
private const val CARD_DONE = "done"

private fun cachedKey(chatId: Long, messageId: Long) =
    stringPreferencesKey("$CACHED_PREFIX${chatId}_$messageId")

/**
 * Every setting and every small record the app keeps, over one preferences DataStore.
 *
 * The store is handed in rather than built here, because DataStore allows exactly one live
 * instance per file in a process: each app opens it once, with [openDataStore], and every
 * `SettingsStore` shares that instance.
 */
class SettingsStore(private val prefs: DataStore<Preferences>) {

    /**
     * One preference, read the way every preference here is read.
     *
     * DataStore re-emits the entire preference map to every collector on every write, and a freshly
     * built Set or Map compares by identity under Compose's strong skipping, so [distinctUntilChanged]
     * is what stops a write about one thing from redrawing everything else. The mapping runs off the
     * main thread because some of these walk every key in the store and decode as they go.
     */
    private fun <T> read(transform: (Preferences) -> T): Flow<T> = prefs.data
        .map(transform)
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)

    // ---- favourites -------------------------------------------------------------------------

    val favorites: Flow<Set<Long>> = read { prefs ->
        prefs[FAVORITES].orEmpty().mapNotNull { it.toLongOrNull() }.toSet()
    }

    suspend fun toggleFavorite(chatId: Long): Boolean {
        var nowFavorite = false
        prefs.edit { prefs ->
            val current = prefs[FAVORITES].orEmpty().toMutableSet()
            val key = chatId.toString()
            nowFavorite = if (current.remove(key)) false else current.add(key)
            prefs[FAVORITES] = current
        }
        return nowFavorite
    }

    /**
     * Wipes every preference: favourites, watch history, size limits, the lot.
     *
     * Signing out has to leave nothing of the previous account behind. Two things survive, and
     * neither is about the account: the light or dark choice, and whether colours come from the
     * wallpaper. Those describe the phone, not the Telegram account signed into it, and flipping
     * the app to dark halfway through signing out reads as a fault rather than as privacy.
     *
     * With [keepDownloads], the records of downloads in the Downloads folder survive too, with the
     * flag saying the old ones were moved there: those files are the viewer's and outlive the
     * account, and a file with no record is one nothing in the app can play or delete. A download
     * still in TDLib's cache has no path and goes, since signing out empties that cache anyway.
     */
    suspend fun clearEverything(keepDownloads: Boolean = false) {
        prefs.edit { prefs ->
            val theme = prefs[THEME_CHOICE]
            val dynamic = prefs[DYNAMIC_COLOUR]
            val migrated = prefs[DOWNLOADS_MIGRATED]
            val support = SUPPORT_KEYS.mapNotNull { key -> prefs[key]?.let { Pair(key, it) } }
            val supportNever = prefs[SUPPORT_NEVER]
            val supporter = prefs[SUPPORT_SUPPORTER]
            val recent = prefs[SUPPORT_RECENT]
            // The first sign in card is asked once per install, not once per account.
            val card = prefs[FIRST_SIGN_IN_CARD]?.takeIf { it == CARD_DONE }
            val kept = if (keepDownloads) {
                prefs.asMap().mapNotNull { (key, value) ->
                    val ids = key.name.removePrefixOrNull("dl_") ?: return@mapNotNull null
                    val record = decodeDownload(prefs, ids, value as? String) ?: return@mapNotNull null
                    if (record.localPath == null) null else Pair(stringPreferencesKey(key.name), value as String)
                }
            } else {
                emptyList()
            }
            prefs.clear()
            theme?.let { prefs[THEME_CHOICE] = it }
            dynamic?.let { prefs[DYNAMIC_COLOUR] = it }
            for ((key, value) in support) prefs[key] = value
            supportNever?.let { prefs[SUPPORT_NEVER] = it }
            supporter?.let { prefs[SUPPORT_SUPPORTER] = it }
            recent?.let { prefs[SUPPORT_RECENT] = it }
            card?.let { prefs[FIRST_SIGN_IN_CARD] = it }
            if (keepDownloads) {
                for ((key, value) in kept) prefs[key] = value
                migrated?.let { prefs[DOWNLOADS_MIGRATED] = it }
            }
        }
    }

    /** Unstars every chat at once, which is the only way back from a tab full of them. */
    suspend fun clearFavorites() {
        prefs.edit { it.remove(FAVORITES) }
    }

    // ---- what opens on launch ---------------------------------------------------------------

    /**
     * Skip the chat list and reopen whichever chat was last watched.
     *
     * Off by default: opening straight into a chat suits somebody who watches one channel every
     * evening and nobody else, since the way back to the listing has to be discovered first.
     */
    val openLastChat: Flow<Boolean> = read { it[OPEN_LAST_CHAT] ?: false }

    suspend fun setOpenLastChat(value: Boolean) {
        prefs.edit { it[OPEN_LAST_CHAT] = value }
    }

    /**
     * Whether the next episode starts on its own when one finishes.
     *
     * On by default, because it is what a series is for and what every other player does. The
     * countdown before it starts is the way out for anybody who meant to stop.
     */
    val autoplayNext: Flow<Boolean> = read { it[AUTOPLAY_NEXT] ?: true }

    suspend fun setAutoplayNext(value: Boolean) {
        prefs.edit { it[AUTOPLAY_NEXT] = value }
    }

    /** Read from disk at the end of a video, where a flow's placeholder would be a wrong answer. */
    suspend fun autoplayNextNow(): Boolean = prefs.data.first()[AUTOPLAY_NEXT] ?: true

    /**
     * Whether a tap on a video opens its page (poster, cast, Play) before playing. On by default;
     * off plays at once, and the page is still a hold or the info key away.
     */
    val detailFirst: Flow<Boolean> = read { it[DETAIL_FIRST] ?: true }

    suspend fun setDetailFirst(value: Boolean) {
        prefs.edit { it[DETAIL_FIRST] = value }
    }

    /** The chat opened most recently, or zero when there has not been one yet. */
    val lastChatId: Flow<Long> = read { it[LAST_CHAT] ?: 0L }

    suspend fun rememberChatOpened(chatId: Long) {
        prefs.edit { it[LAST_CHAT] = chatId }
    }

    suspend fun forgetLastChat() {
        prefs.edit { it.remove(LAST_CHAT) }
    }

    /**
     * The chat to open immediately, or null to show the chat list.
     *
     * Read straight from disk rather than from a collected flow: this runs once, the moment the
     * chats arrive, and a flow that has not emitted yet would still be reporting its placeholder.
     */
    suspend fun autoOpenTarget(): Long? {
        val prefs = prefs.data.first()
        // Must stay the same default as [openLastChat], which is the switch the viewer reads.
        return autoOpenChatId(prefs[LAST_CHAT] ?: 0L, prefs[OPEN_LAST_CHAT] ?: false)
    }

    // ---- playback ---------------------------------------------------------------------------

    /**
     * Wait for the whole video to arrive before starting it.
     *
     * Off by default, because streaming while it downloads is the point of the app. It is here for
     * a connection too slow or too unsteady to keep up with playback, where waiting once beats
     * being stopped every few minutes.
     */
    val downloadBeforePlaying: Flow<Boolean> = read { it[DOWNLOAD_FIRST] ?: false }

    suspend fun setDownloadBeforePlaying(value: Boolean) {
        prefs.edit { it[DOWNLOAD_FIRST] = value }
    }

    /** Read once at the start of playback, where a flow that has not emitted yet would lie. */
    suspend fun downloadBeforePlayingNow(): Boolean =
        prefs.data.first()[DOWNLOAD_FIRST] ?: false

    /**
     * Whether the soundtrack is folded down to stereo, as the viewer set it, or null while they
     * have not said. Null is an answer of its own: the default differs by device, on for a phone
     * and off for a television, and [com.tmplayer.player.AudioDownmix.wanted] settles it.
     */
    val downmixChoice: Flow<Boolean?> = read { it[DOWNMIX_STEREO] }

    suspend fun setDownmix(value: Boolean) {
        prefs.edit { it[DOWNMIX_STEREO] = value }
    }

    /** Read before the player is built, because the fold is fixed into its audio sink. */
    suspend fun downmixChoiceNow(): Boolean? = prefs.data.first()[DOWNMIX_STEREO]

    /**
     * Volume boost, the player menu's night mode: quiet dialogue lifted and loud scenes held back.
     * Off until the viewer turns it on, and then kept for every video until they turn it off.
     */
    val volumeBoost: Flow<Boolean> = read { it[VOLUME_BOOST] ?: false }

    suspend fun setVolumeBoost(value: Boolean) {
        prefs.edit { it[VOLUME_BOOST] = value }
    }

    /** Read before the player is built, so the first second of sound is already at its level. */
    suspend fun volumeBoostNow(): Boolean = prefs.data.first()[VOLUME_BOOST] ?: false

    // ---- the watch cache ---------------------------------------------------------------------

    /**
     * Every video playback has left behind, newest first.
     *
     * A list, written the same way downloads are, under `wc_`: stepping between episodes inside the
     * player can leave more than one file behind, and anything not listed here is a video nothing in
     * the app can see, name or delete. [WatchCache] keeps it honest by claiming the video being
     * played and giving up everything else at the same moment, so the list is normally one entry.
     */
    val cachedVideos: Flow<List<ResumeRecord>> = read { prefs -> decodeCachedList(prefs) }

    /** Read once, at the moment a video is asked for, where a flow yet to emit would lie. */
    suspend fun cachedVideosNow(): List<ResumeRecord> = decodeCachedList(prefs.data.first())

    private fun decodeCachedList(prefs: Preferences): List<ResumeRecord> = buildList {
        for ((key, value) in prefs.asMap()) {
            val name = key.name
            if (!name.startsWith(CACHED_PREFIX)) continue
            val ids = name.removePrefix(CACHED_PREFIX)
            val record = ResumeRecord.decode(
                key = ids,
                encoded = value as? String,
                positionMs = prefs[longPreferencesKey("resume_$ids")] ?: 0L,
                durationMs = prefs[longPreferencesKey("duration_$ids")] ?: 0L,
            ) ?: continue
            add(record)
        }
        // The single record older installs wrote, carried across so the first launch after an
        // update knows what it is holding rather than starting from an empty list beside a full disk.
        val legacy = prefs[CACHED_VIDEO_IDS]
        if (legacy != null && none { progressKey(it.chatId, it.messageId) == legacy }) {
            ResumeRecord.decode(
                key = legacy,
                encoded = prefs[CACHED_VIDEO],
                positionMs = prefs[longPreferencesKey("resume_$legacy")] ?: 0L,
                durationMs = prefs[longPreferencesKey("duration_$legacy")] ?: 0L,
            )?.let(::add)
        }
    }.sortedByDescending { it.updatedAt }

    /**
     * Records another video as one playback put on the disk.
     *
     * Adding rather than replacing. Deleting the file is [WatchCache]'s job, since this store knows
     * only preferences and TDLib owns the bytes. Never drop a record before its file has gone, or
     * the disk keeps something nothing can name.
     */
    suspend fun rememberCachedVideo(item: MediaItem, chatTitle: String) {
        prefs.edit { prefs ->
            prefs[cachedKey(item.chatId, item.messageId)] = ResumeRecord.encode(
                fileId = item.fileId,
                title = item.title,
                chatTitle = chatTitle,
                sizeBytes = item.sizeBytes,
                durationSec = item.durationSec,
                updatedAt = System.currentTimeMillis(),
            )
        }
    }

    /** Forgets one cached video, once its file has actually gone. */
    suspend fun forgetCachedVideo(chatId: Long, messageId: Long) {
        prefs.edit { prefs ->
            prefs.remove(cachedKey(chatId, messageId))
            if (prefs[CACHED_VIDEO_IDS] == progressKey(chatId, messageId)) {
                prefs.remove(CACHED_VIDEO_IDS)
                prefs.remove(CACHED_VIDEO)
            }
        }
    }

    /** Forgets the lot, for the button that deletes every file behind them. */
    suspend fun forgetCachedVideo() {
        prefs.edit { prefs ->
            val doomed = prefs.asMap().keys.filter { it.name.startsWith(CACHED_PREFIX) }
            for (key in doomed) prefs.remove(key)
            prefs.remove(CACHED_VIDEO_IDS)
            prefs.remove(CACHED_VIDEO)
        }
    }

    /** Whether this video is one the viewer downloaded on purpose, and so is never evicted. */
    suspend fun isKeptDownload(chatId: Long, messageId: Long): Boolean =
        prefs.data.first()[downloadKey(chatId, messageId)] != null

    /**
     * Refuse to fetch video over a connection the viewer pays for by the byte.
     *
     * Off by default, because many people watch on mobile data by choice. On, it is absolute: a
     * video that is not already downloaded will not open until there is Wi-Fi.
     */
    val wifiOnlyDownloads: Flow<Boolean> = read { it[WIFI_ONLY] ?: false }

    suspend fun setWifiOnlyDownloads(value: Boolean) {
        prefs.edit { it[WIFI_ONLY] = value }
    }

    /** Read at the start of playback, where the flow's first emission has not arrived yet. */
    suspend fun wifiOnlyDownloadsNow(): Boolean = prefs.data.first()[WIFI_ONLY] ?: false

    /**
     * "Remove after watching": a download is deleted once it is marked watched. Off by default,
     * because a download is something the viewer asked to keep. See [RemoveAfterWatching].
     */
    val removeAfterWatching: Flow<Boolean> = read { it[REMOVE_AFTER_WATCHING] ?: false }

    suspend fun setRemoveAfterWatching(value: Boolean) {
        prefs.edit { it[REMOVE_AFTER_WATCHING] = value }
    }

    // ---- trickplay and Watch Next ------------------------------------------------------------

    /**
     * Thumbnails over the scrub bar, from the part of the video already on the disk. See [Trickplay].
     *
     * On unless the viewer turns it off; a device under [Trickplay.MIN_TOTAL_MEMORY_BYTES] never
     * draws them whatever this says.
     */
    val trickplay: Flow<Boolean> = read { it[TRICKPLAY] ?: true }

    suspend fun trickplayNow(): Boolean = prefs.data.first()[TRICKPLAY] ?: true

    suspend fun setTrickplay(value: Boolean) {
        prefs.edit { it[TRICKPLAY] = value }
    }

    /**
     * Entries in the Android TV home screen's "Play next" row. See [WatchNext].
     *
     * Off until the viewer turns it on: it puts the names of what they watch on the home screen,
     * where anybody else in the room sees them.
     */
    val watchNext: Flow<Boolean> = read { it[WATCH_NEXT] ?: false }

    suspend fun watchNextNow(): Boolean = prefs.data.first()[WATCH_NEXT] ?: false

    suspend fun setWatchNext(value: Boolean) {
        prefs.edit { it[WATCH_NEXT] = value }
    }

    // ---- the support card -------------------------------------------------------------------

    /** What [SupportReminder] decides from. */
    val supportCounters: Flow<SupportReminder.Counters> = read(::supportCountersOf)

    suspend fun supportCountersNow(): SupportReminder.Counters = supportCountersOf(prefs.data.first())

    private fun supportCountersOf(prefs: Preferences) = SupportReminder.Counters(
        firstSeenAt = prefs[SUPPORT_FIRST_SEEN] ?: 0L,
        completedWatches = (prefs[SUPPORT_WATCHES] ?: 0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        watchTimeMs = prefs[SUPPORT_WATCH_MS] ?: 0L,
        asked = (prefs[SUPPORT_ASKED] ?: 0L).coerceIn(0L, SupportReminder.MAX_ASKS.toLong()).toInt(),
        snoozedUntil = prefs[SUPPORT_SNOOZED_UNTIL] ?: 0L,
        neverAgain = prefs[SUPPORT_NEVER] ?: false,
        supporter = prefs[SUPPORT_SUPPORTER] ?: false,
    )

    /**
     * The day the counting starts. Called on every launch; only the first call writes. An install
     * that predates the card starts counting from its first launch with it.
     */
    suspend fun noteSupportFirstSeen(now: Long) {
        prefs.edit { if ((it[SUPPORT_FIRST_SEEN] ?: 0L) <= 0L) it[SUPPORT_FIRST_SEEN] = now }
    }

    /** One more video started. Only informational now: the ladder counts finished videos. */
    suspend fun noteSupportPlay(now: Long) {
        prefs.edit {
            if ((it[SUPPORT_FIRST_SEEN] ?: 0L) <= 0L) it[SUPPORT_FIRST_SEEN] = now
            it[SUPPORT_PLAYS] = (it[SUPPORT_PLAYS] ?: 0L) + 1
        }
    }

    /**
     * A video watched to the end ([SupportReminder.Finished]). Counts once per hour for the same
     * video, however often it is opened again.
     */
    suspend fun noteSupportCompleted(key: String, now: Long) {
        prefs.edit {
            val (recent, counts) = SupportReminder.stepCompleted(SupportReminder.decodeRecent(it[SUPPORT_RECENT]), key, now)
            it[SUPPORT_RECENT] = SupportReminder.encodeRecent(recent)
            if (counts) it[SUPPORT_WATCHES] = (it[SUPPORT_WATCHES] ?: 0L) + 1
        }
    }

    /** Adds the time watched since the last progress report of [key] to the running total. */
    private fun noteSupportProgress(prefs: MutablePreferences, key: String, positionMs: Long, now: Long) {
        val (recent, added) = SupportReminder.stepTime(SupportReminder.decodeRecent(prefs[SUPPORT_RECENT]), key, positionMs, now)
        prefs[SUPPORT_RECENT] = SupportReminder.encodeRecent(recent)
        if (added > 0L) prefs[SUPPORT_WATCH_MS] = (prefs[SUPPORT_WATCH_MS] ?: 0L) + added
    }

    /**
     * A card of [rung] was shown, whatever the viewer did with it: the ladder moves up one step and
     * the next card waits its gap (see [SupportReminder.snoozedUntil]).
     */
    suspend fun recordSupportAsk(now: Long, rung: Int) {
        prefs.edit {
            it[SUPPORT_ASKED] = rung.toLong().coerceIn(1L, SupportReminder.MAX_ASKS.toLong())
            it[SUPPORT_SNOOZED_UNTIL] = SupportReminder.snoozedUntil(now, rung)
        }
    }

    /** "I already support", or the last ask's "Done": no more cards, and About says thank you. */
    suspend fun markSupporter() {
        prefs.edit { it[SUPPORT_SUPPORTER] = true }
    }

    /** The old "Don't ask again". The Settings row and About keep the links. */
    suspend fun neverAskSupport() {
        prefs.edit { it[SUPPORT_NEVER] = true }
    }

    // ---- updates ----------------------------------------------------------------------------

    /**
     * "Tell me when a new version is out": the scheduled checks. On by default, because a
     * sideloaded app has nothing else to tell anybody; Settings' own button works either way.
     */
    val updateNotify: Flow<Boolean> = read { it[UPDATE_NOTIFY] ?: true }

    suspend fun setUpdateNotify(value: Boolean) {
        prefs.edit { it[UPDATE_NOTIFY] = value }
    }

    /** The update check's own memory, read and written by [UpdateScheduler] alone. */
    val updatePrefs: UpdatePrefs = object : UpdatePrefs {
        override suspend fun notify() = prefs.data.first()[UPDATE_NOTIFY] ?: true
        override suspend fun lastCheck() = prefs.data.first()[UPDATE_LAST_CHECK] ?: 0L
        override suspend fun setLastCheck(at: Long) {
            prefs.edit { it[UPDATE_LAST_CHECK] = at }
        }
        override suspend fun skippedVersion() = prefs.data.first()[UPDATE_SKIPPED].orEmpty()
        override suspend fun setSkippedVersion(version: String) {
            prefs.edit { it[UPDATE_SKIPPED] = version }
        }
        override suspend fun snoozedUntil() = prefs.data.first()[UPDATE_SNOOZED_UNTIL] ?: 0L
        override suspend fun setSnoozedUntil(at: Long) {
            prefs.edit { it[UPDATE_SNOOZED_UNTIL] = at }
        }
        override suspend fun popupShownFor() = prefs.data.first()[UPDATE_POPUP_SHOWN].orEmpty()
        override suspend fun setPopupShownFor(version: String) {
            prefs.edit { it[UPDATE_POPUP_SHOWN] = version }
        }
    }

    /**
     * Whether a crash may be reported to the project, and the one thing in this app that ever
     * sends anything anywhere except Telegram and GitHub.
     *
     * Off, on a fresh install and after signing out. Nothing is initialised until this is true: see
     * [com.tmplayer.data.CrashReports] for what a report does and does not carry.
     */
    val crashReports: Flow<Boolean> = read { it[CRASH_REPORTS] ?: false }

    suspend fun setCrashReports(value: Boolean) {
        prefs.edit { it[CRASH_REPORTS] = value }
    }

    /** Read once during startup, before anything has had a chance to crash. */
    suspend fun crashReportsNow(): Boolean = prefs.data.first()[CRASH_REPORTS] ?: false

    /**
     * The speed the last video was left at, applied to the next one.
     *
     * Somebody who watches everything at 1.25x should say so once rather than every episode, and
     * on a television there is no gear menu to say it in twice.
     */
    val playbackSpeed: Flow<Float> = read {
        PlaybackSpeed.sanitise(it[PLAYBACK_SPEED] ?: PlaybackSpeed.DEFAULT)
    }

    suspend fun setPlaybackSpeed(value: Float) {
        prefs.edit { it[PLAYBACK_SPEED] = PlaybackSpeed.sanitise(value) }
    }

    suspend fun playbackSpeedNow(): Float =
        PlaybackSpeed.sanitise(prefs.data.first()[PLAYBACK_SPEED] ?: PlaybackSpeed.DEFAULT)

    /**
     * How the picture was last fitted to the screen.
     *
     * Remembered for the same reason the speed is: a viewer whose television overscans, or who
     * cannot stand black bars, should not have to choose Crop again every episode.
     */
    suspend fun videoScaleNow(): String? = prefs.data.first()[VIDEO_SCALE]

    suspend fun setVideoScale(name: String) {
        prefs.edit { it[VIDEO_SCALE] = name }
    }

    /**
     * Whether the player's one-time hint has been shown: that a double tap jumps on a phone, that
     * the arrows jump and More lists every key on a television. The controls that used to say so
     * are gone from the screen, so it is said once, the first time.
     */
    suspend fun playerHintSeenNow(): Boolean = prefs.data.first()[PLAYER_HINT_SEEN] ?: false

    suspend fun markPlayerHintSeen() {
        prefs.edit { it[PLAYER_HINT_SEEN] = true }
    }

    /**
     * Whether the chat list's first-run tip (forward a video to Saved Messages) was put away. It
     * also goes by itself once anything has been played; this is for somebody who closed it first.
     */
    val firstVideoTipDismissed: Flow<Boolean> = read { it[FIRST_VIDEO_TIP_DISMISSED] ?: false }

    suspend fun setFirstVideoTipDismissed(dismissed: Boolean = true) {
        prefs.edit { it[FIRST_VIDEO_TIP_DISMISSED] = dismissed }
    }

    /**
     * Whether the television's one-time browse hint (OK opens, hold OK for favourites, Back goes up)
     * has been shown. Marked as it appears, so it is said once even if the app is left mid-way.
     */
    suspend fun tvBrowseHintSeenNow(): Boolean = prefs.data.first()[TV_BROWSE_HINT_SEEN] ?: false

    suspend fun markTvBrowseHintSeen() {
        prefs.edit { it[TV_BROWSE_HINT_SEEN] = true }
    }

    /**
     * Which way up the player opens, on a phone.
     *
     * Kept beside the scale: both describe how this viewer likes to watch rather than the video in
     * front of them. Read as a plain name so the player owns its meaning and this file stays a store.
     */
    suspend fun screenOrientationNow(): String? = prefs.data.first()[SCREEN_ORIENTATION]

    suspend fun setScreenOrientation(name: String) {
        prefs.edit { it[SCREEN_ORIENTATION] = name }
    }

    // ---- the phone player's touch settings -------------------------------------------------

    /**
     * How the phone's player answers a thumb: the tap, the double tap, the hold, the swipes, how
     * long the controls stay up, and whether the total reads as time left. One value rather than
     * nine flows, because the player reads all of it at once and Settings edits it as a set.
     */
    val touchPrefs: Flow<TouchPrefs> = read(::touchPrefsOf)

    suspend fun touchPrefsNow(): TouchPrefs = touchPrefsOf(prefs.data.first())

    suspend fun updateTouchPrefs(change: (TouchPrefs) -> TouchPrefs) {
        prefs.edit { prefs ->
            val next = change(touchPrefsOf(prefs))
            prefs[TAP_PLAYS_PAUSES] = next.tapPlaysPauses
            prefs[DOUBLE_TAP_MS] = TouchPrefs.sanitiseDoubleTap(next.doubleTapMs)
            prefs[HOLD_SPEED] = TouchPrefs.sanitiseHold(next.holdSpeed)
            prefs[CONTROLS_TIMEOUT_MS] = TouchPrefs.sanitiseTimeout(next.controlsTimeoutMs)
            prefs[GESTURE_SEEK] = next.seekGesture
            prefs[GESTURE_BRIGHTNESS] = next.brightnessGesture
            prefs[GESTURE_VOLUME] = next.volumeGesture
            prefs[PLAYER_HAPTICS] = next.haptics
            prefs[SHOW_REMAINING] = next.showRemaining
        }
    }

    private fun touchPrefsOf(prefs: Preferences) = TouchPrefs(
        tapPlaysPauses = prefs[TAP_PLAYS_PAUSES] ?: false,
        doubleTapMs = TouchPrefs.sanitiseDoubleTap(prefs[DOUBLE_TAP_MS]),
        holdSpeed = TouchPrefs.sanitiseHold(prefs[HOLD_SPEED]),
        controlsTimeoutMs = TouchPrefs.sanitiseTimeout(prefs[CONTROLS_TIMEOUT_MS]),
        seekGesture = prefs[GESTURE_SEEK] ?: true,
        brightnessGesture = prefs[GESTURE_BRIGHTNESS] ?: true,
        volumeGesture = prefs[GESTURE_VOLUME] ?: true,
        haptics = prefs[PLAYER_HAPTICS] ?: true,
        showRemaining = prefs[SHOW_REMAINING] ?: false,
    )

    // ---- subtitle look and per file sync ----------------------------------------------------

    val subtitleStyle: Flow<SubtitleStyle> = read(::subtitleStyleOf)

    suspend fun subtitleStyleNow(): SubtitleStyle = subtitleStyleOf(prefs.data.first())

    suspend fun setSubtitleStyle(style: SubtitleStyle) {
        prefs.edit {
            it[SUBTITLE_SIZE] = style.size.name
            it[SUBTITLE_BOX] = style.box
            it[SUBTITLE_POSITION] = style.position.name
        }
    }

    private fun subtitleStyleOf(prefs: Preferences) =
        SubtitleStyle.from(prefs[SUBTITLE_SIZE], prefs[SUBTITLE_BOX], prefs[SUBTITLE_POSITION])

    /**
     * The offsets set for this file, kept beside its resume position.
     *
     * A key of its own rather than a field of the resume line: an offset is worth keeping for a video
     * watched less than a minute (that is exactly when a viewer fixes one), and the resume line is not
     * written until then.
     */
    suspend fun syncDelays(chatId: Long, messageId: Long): SyncDelays =
        SyncDelays.decode(prefs.data.first()[delayKey(chatId, messageId)])

    suspend fun setSyncDelays(chatId: Long, messageId: Long, delays: SyncDelays) {
        prefs.edit { prefs ->
            delays.encode()?.let { prefs[delayKey(chatId, messageId)] = it }
                ?: prefs.remove(delayKey(chatId, messageId))
        }
    }

    // ---- tracks, per series -----------------------------------------------------------------

    /**
     * The audio track and the subtitles this series was last watched with.
     *
     * Per series rather than per file, because the choice belongs to the show and the file id
     * changes with every episode. Per series rather than app-wide, because the answer differs
     * between shows: subtitles on for one, off for another, and neither should overwrite the other.
     *
     * A video that is not part of a series is keyed by its own name, so this is a no-op for it.
     */
    suspend fun trackChoice(series: String): TrackChoice {
        val prefs = prefs.data.first()
        val key = seriesKey(series)
        return TrackChoice(
            audioLanguage = prefs[audioKey(key)]?.takeIf { it.isNotBlank() },
            textLanguage = prefs[textKey(key)]?.takeIf { it.isNotBlank() },
            subtitlesOn = prefs[subtitlesKey(key)] ?: false,
        )
    }

    suspend fun setTrackChoice(series: String, choice: TrackChoice) {
        val key = seriesKey(series)
        prefs.edit { prefs ->
            choice.audioLanguage?.let { prefs[audioKey(key)] = it } ?: prefs.remove(audioKey(key))
            choice.textLanguage?.let { prefs[textKey(key)] = it } ?: prefs.remove(textKey(key))
            prefs[subtitlesKey(key)] = choice.subtitlesOn
        }
    }

    // ---- size filter ------------------------------------------------------------------------

    val minSizeBytes: Flow<Long> =
        read { it[MIN_SIZE] ?: SizeFilter.DEFAULT_MIN }

    val maxSizeBytes: Flow<Long> =
        read { it[MAX_SIZE] ?: SizeFilter.DEFAULT_MAX }

    suspend fun setMinSizeBytes(value: Long) {
        prefs.edit { prefs ->
            val max = prefs[MAX_SIZE] ?: SizeFilter.DEFAULT_MAX
            prefs[MIN_SIZE] = SizeFilter.clampMin(value, max)
        }
    }

    suspend fun setMaxSizeBytes(value: Long) {
        prefs.edit { prefs ->
            val min = prefs[MIN_SIZE] ?: SizeFilter.DEFAULT_MIN
            prefs[MAX_SIZE] = SizeFilter.clampMax(value, min)
        }
    }

    // ---- cold start -------------------------------------------------------------------------

    /**
     * The chat list as it was at the end of the last sync, for the first frame of the next launch.
     *
     * Read straight off disk rather than as a flow: it is wanted once, before anything else has
     * happened, and a flow that has not emitted yet would hand back an empty list.
     */
    suspend fun cachedChatSnapshot(): List<ChatSummary> =
        ChatSnapshot.decode(prefs.data.first()[CHAT_SNAPSHOT])

    suspend fun saveChatSnapshot(chats: List<ChatSummary>) {
        val encoded = ChatSnapshot.encode(chats)
        prefs.edit { prefs ->
            // Written only when it has actually changed: this runs after every sync, and the whole
            // preference file is rewritten and fsynced per edit.
            if (prefs[CHAT_SNAPSHOT] != encoded) prefs[CHAT_SNAPSHOT] = encoded
        }
    }

    /**
     * The account header as it was last seen, so a cold start does not open on "Your account"
     * and a grey circle while TDLib gets its database up.
     *
     * The photo file id is deliberately not written down: a TDLib file id belongs to the session
     * that issued it, and a remembered one would point at nothing after a restart. The blurred
     * mini thumbnail is a couple of hundred bytes and carries the face until the real picture
     * arrives from TDLib's cache, which is the same bargain the chat snapshot makes.
     *
     * Encoded as `username|base64(mini)|name`, the name last because it is the only field that
     * can contain the separator.
     */
    suspend fun cachedAccountSnapshot(): Account? {
        val encoded = prefs.data.first()[ACCOUNT_SNAPSHOT] ?: return null
        val parts = encoded.split("|", limit = 3)
        if (parts.size != 3 || parts[2].isBlank()) return null
        val mini = parts[1].takeIf { it.isNotBlank() }?.let {
            runCatching { java.util.Base64.getDecoder().decode(it) }.getOrNull()
        }
        return Account(
            name = parts[2],
            username = parts[0],
            miniThumbnail = mini,
            photoFileId = 0,
        )
    }

    suspend fun saveAccountSnapshot(account: Account) {
        val mini = account.miniThumbnail
            ?.let { java.util.Base64.getEncoder().encodeToString(it) }
            .orEmpty()
        val encoded = "${account.username}|$mini|${account.name.replace('|', ' ')}"
        prefs.edit { prefs ->
            if (prefs[ACCOUNT_SNAPSHOT] != encoded) prefs[ACCOUNT_SNAPSHOT] = encoded
        }
    }

    /** For sign-out: the next person to open the app must not be greeted as the last one. */
    suspend fun clearColdStartSnapshots() {
        prefs.edit { prefs ->
            prefs.remove(CHAT_SNAPSHOT)
            prefs.remove(ACCOUNT_SNAPSHOT)
        }
    }

    // ---- recent searches --------------------------------------------------------------------

    /**
     * What was searched for inside a chat lately, newest first, at most [RecentSearches.LIMIT].
     * One list for every chat: a viewer looking for a show looks for it wherever it was posted.
     */
    val recentSearches: Flow<List<String>> = read { RecentSearches.decode(it[RECENT_SEARCHES]) }

    suspend fun addRecentSearch(query: String) {
        prefs.edit { prefs ->
            val before = RecentSearches.decode(prefs[RECENT_SEARCHES])
            val after = RecentSearches.add(before, query)
            if (after != before) prefs[RECENT_SEARCHES] = RecentSearches.encode(after)
        }
    }

    suspend fun clearRecentSearches() {
        prefs.edit { it.remove(RECENT_SEARCHES) }
    }

    // ---- card layout ------------------------------------------------------------------------

    /**
     * How the chat sections are arranged.
     *
     * Rows by default: a chat is a name and a picture, and a name is what the viewer is reading.
     */
    val chatLayout: Flow<CardLayout> =
        read { CardLayout.decode(it[CHAT_LAYOUT], CardLayout.List) }

    suspend fun setChatLayout(value: CardLayout) {
        prefs.edit { it[CHAT_LAYOUT] = value.name }
    }

    // ---- the chips over Chats and History -----------------------------------------------------

    /**
     * The chip chosen over the Chats list (All, Unread, Channels and so on), by name; null until
     * one is picked. Held as text because the chips themselves live in :ui (`ChatFilter`), which
     * reads it back and falls back to All for a name it does not know.
     */
    val chatFilter: Flow<String?> = read { it[CHAT_FILTER] }

    suspend fun setChatFilter(name: String) {
        prefs.edit { it[CHAT_FILTER] = name }
    }

    /** The Chats list's order, `ChatSort` by name: Telegram's recency or by title. */
    val chatSort: Flow<String?> = read { it[CHAT_SORT] }

    suspend fun setChatSort(name: String) {
        prefs.edit { it[CHAT_SORT] = name }
    }

    // ---- Telegram's default groups ------------------------------------------------------------

    /**
     * Whether the sidebar leaves out Telegram's default groups (Chats and Saved Messages) and lists
     * only the account's own folders beside Home, History, Favourites and Downloads. Off by default.
     * The chip over Chats ([chatFilter], [chatSort]) is left as it was while this is on, so turning
     * it off again comes back to the same chip.
     */
    val hideDefaultGroups: Flow<Boolean> = read { it[HIDE_DEFAULT_GROUPS] ?: false }

    suspend fun hideDefaultGroupsNow(): Boolean = prefs.data.first()[HIDE_DEFAULT_GROUPS] ?: false

    /**
     * Turns the option on or off. Turning it on also unstars [unstar] in the same write, the
     * favourites hiding the groups would leave out of reach (`DefaultGroups.unreachableFavorites`
     * in :ui), so the setting and the favourites can never be seen half changed. Turning it off
     * brings nothing back.
     */
    suspend fun setHideDefaultGroups(hide: Boolean, unstar: Set<Long> = emptySet()) {
        prefs.edit { prefs ->
            prefs[HIDE_DEFAULT_GROUPS] = hide
            if (hide && unstar.isNotEmpty()) {
                val keep = prefs[FAVORITES].orEmpty() - unstar.map(Long::toString).toSet()
                prefs[FAVORITES] = keep
            }
        }
    }

    /**
     * The one card after the first sign in (CP42), which asks "Show everything" or "Only my
     * folders". Armed when a sign in screen is shown and the card has never been dealt with, so a
     * viewer who was signed in before this existed is never asked; true while it waits for the
     * shell and the account's folders.
     */
    val firstSignInCardPending: Flow<Boolean> = read { it[FIRST_SIGN_IN_CARD] == CARD_PENDING }

    /** A sign in screen is up: arm the card, unless it has been armed or answered before. */
    suspend fun armFirstSignInCard() {
        prefs.edit { if (it[FIRST_SIGN_IN_CARD] == null) it[FIRST_SIGN_IN_CARD] = CARD_PENDING }
    }

    /** Answered, or skipped for an account with no folders: never asked again on this install. */
    suspend fun markFirstSignInCardDone() {
        prefs.edit { it[FIRST_SIGN_IN_CARD] = CARD_DONE }
    }

    /** Which tab of History was last open, `HistoryTab` by name. */
    val historyTab: Flow<String?> = read { it[HISTORY_TAB] }

    suspend fun setHistoryTab(name: String) {
        prefs.edit { it[HISTORY_TAB] = name }
    }

    /**
     * How a chat's videos are arranged.
     *
     * Tiles by default: previews make individual uploads easy to identify from across the room.
     */
    val mediaLayout: Flow<CardLayout> =
        read { CardLayout.decode(it[MEDIA_LAYOUT], CardLayout.Grid) }

    suspend fun setMediaLayout(value: CardLayout) {
        prefs.edit { it[MEDIA_LAYOUT] = value.name }
    }

    /**
     * Whether a chat folds its episodes into one tile per show ("Series") or lists every file
     * ("All files"). On by default: a chat of forty episodes reads better as the three shows it is.
     */
    val seriesView: Flow<Boolean> = read { it[SERIES_VIEW] ?: true }

    suspend fun setSeriesView(value: Boolean) {
        prefs.edit { it[SERIES_VIEW] = value }
    }

    // ---- appearance -------------------------------------------------------------------------

    /**
     * Light, dark, or whatever the phone is set to.
     *
     * A television has no such setting and never reads this: a panel in a dark room is dark, and
     * Android TV has no system-wide light mode to follow.
     */
    val themeChoice: Flow<ThemeChoice> =
        read { ThemeChoice.from(it[THEME_CHOICE]) }

    suspend fun setThemeChoice(value: ThemeChoice) {
        prefs.edit { it[THEME_CHOICE] = value.name }
    }

    /**
     * The UI language the viewer picked, as a tag from `Languages.tags`, or "" to follow the
     * system's own list. `Translator.select` turns this and the system list into the language.
     */
    val language: Flow<String> = read { it[LANGUAGE] ?: "" }

    suspend fun setLanguage(tag: String) {
        prefs.edit { if (tag.isBlank()) it.remove(LANGUAGE) else it[LANGUAGE] = tag }
    }

    /**
     * The language the one-time "Now in Español" card was shown for, or "" before any. Kept so the
     * card comes once per language the system hands the app, not once per launch.
     */
    val languageAnnounced: Flow<String> = read { it[LANGUAGE_ANNOUNCED] ?: "" }

    suspend fun setLanguageAnnounced(tag: String) {
        prefs.edit { it[LANGUAGE_ANNOUNCED] = tag }
    }

    /** The newest version this install has opened, for "What's new". "" before the first. */
    val lastSeenVersion: Flow<String> = read { it[LAST_SEEN_VERSION] ?: "" }

    suspend fun setLastSeenVersion(version: String) {
        prefs.edit { it[LAST_SEEN_VERSION] = version }
    }

    /**
     * Whether to take the palette from the phone's wallpaper, where Android offers one.
     *
     * Off by default. TMPlayer has a palette of its own and should look like itself on first run;
     * a wallpaper can take the app somewhere it was never designed for. Turning it on hands the
     * colours over to the phone, and on a phone older than Android 12 there is no palette to take.
     */
    val dynamicColour: Flow<Boolean> = read { it[DYNAMIC_COLOUR] ?: false }

    suspend fun setDynamicColour(value: Boolean) {
        prefs.edit { it[DYNAMIC_COLOUR] = value }
    }

    // ---- prompts ----------------------------------------------------------------------------

    /**
     * Whether the tour (Welcome, then How it works) has been seen. It comes before sign in on the
     * first run, and Settings asks for it again with [replayOverview]. The two page tour kept the
     * key the six page one used, so an install that saw the old tour is not shown the new one.
     * Installs from before the shared tour may still carry an old "intro_seen" key; nothing reads it.
     */
    val overviewSeen: Flow<Boolean> = read { it[OVERVIEW_SEEN] ?: false }

    suspend fun markOverviewSeen() {
        prefs.edit { it[OVERVIEW_SEEN] = true }
    }

    suspend fun replayOverview() {
        prefs.edit { it[OVERVIEW_SEEN] = false }
    }

    // ---- resume -----------------------------------------------------------------------------

    suspend fun resumePosition(chatId: Long, messageId: Long): Long =
        prefs.data.first()[resumeKey(chatId, messageId)] ?: 0L

    /**
     * The whole resume point for one video, with the [ResumeState] it was playing with, or null
     * when there is none (or its description is missing).
     */
    suspend fun resumeRecord(chatId: Long, messageId: Long): ResumeRecord? {
        val prefs = prefs.data.first()
        val positionMs = prefs[resumeKey(chatId, messageId)] ?: return null
        return ResumeRecord.decode(
            key = SettingsStore.progressKey(chatId, messageId),
            encoded = prefs[metaKey(chatId, messageId)],
            positionMs = positionMs,
            durationMs = prefs[durationKey(chatId, messageId)] ?: 0L,
        )
    }

    /**
     * Every stored resume point, keyed by `chatId:messageId`, read in one pass.
     *
     * The grid needs a progress bar on every preview at once; asking DataStore per card would be
     * one disk read per item on a device with very little to spare.
     */
    val watchProgress: Flow<Map<String, WatchPoint>> = read { prefs ->
        buildMap {
            for ((key, value) in prefs.asMap()) {
                val name = key.name
                if (!name.startsWith("resume_")) continue
                val ids = name.removePrefix("resume_")
                val positionMs = value as? Long ?: continue
                val durationMs = prefs[longPreferencesKey("duration_$ids")] ?: 0L
                put(ids, WatchPoint(positionMs, durationMs))
            }
        }
    }

    /**
     * Everything half-watched, most recent first: the "Continue watching" row.
     *
     * Entries without a stored description are skipped rather than guessed at: there is no title to
     * put on the card.
     */
    val continueWatching: Flow<List<ResumeRecord>> = read { prefs ->
        buildList {
            for ((key, value) in prefs.asMap()) {
                val name = key.name
                if (!name.startsWith("resume_")) continue
                val ids = name.removePrefix("resume_")
                val positionMs = value as? Long ?: continue
                val record = ResumeRecord.decode(
                    key = ids,
                    encoded = prefs[stringPreferencesKey("meta_$ids")],
                    positionMs = positionMs,
                    durationMs = prefs[longPreferencesKey("duration_$ids")] ?: 0L,
                ) ?: continue
                add(record)
            }
        }.sortedByDescending { it.updatedAt }
    }

    /**
     * Every video this install has ever asked TDLib for, newest first.
     *
     * A record with a [ResumeRecord.localPath] is a download in TMPlayer's own Downloads folder,
     * and the path is where its file is. One without is a download from before downloads had a
     * folder, whose file is wherever TDLib's cache put it: whether that is still on disk is TDLib's
     * answer, not this one, and the screen asks it per row.
     */
    val downloadHistory: Flow<List<ResumeRecord>> = read { prefs -> decodeDownloads(prefs) }

    /** The same list read once, for work that must not wait on a flow's first emission. */
    suspend fun downloadsNow(): List<ResumeRecord> = decodeDownloads(prefs.data.first())

    /** The record for one message, or null when it was never downloaded or has been removed. */
    suspend fun downloadRecord(chatId: Long, messageId: Long): ResumeRecord? {
        val prefs = prefs.data.first()
        val ids = progressKey(chatId, messageId)
        return decodeDownload(prefs, ids, prefs[downloadKey(chatId, messageId)])
    }

    /**
     * Points one download at its file, or at none with a null [localPath].
     *
     * Written per file as each move lands, so an interrupted move of many leaves a list in which
     * every record is right about where its file is. Does nothing for a message with no record:
     * a path on its own, with no title or size, is not a row anything could draw.
     *
     * @return whether there was a record to update.
     */
    suspend fun setDownloadPath(chatId: Long, messageId: Long, localPath: String?): Boolean {
        var updated = false
        prefs.edit { prefs ->
            val key = downloadKey(chatId, messageId)
            val ids = progressKey(chatId, messageId)
            val record = decodeDownload(prefs, ids, prefs[key]) ?: return@edit
            prefs[key] = ResumeRecord.encode(
                fileId = record.fileId,
                title = record.title,
                chatTitle = record.chatTitle,
                sizeBytes = record.sizeBytes,
                durationSec = record.durationSec,
                updatedAt = record.updatedAt,
                localPath = localPath,
            )
            updated = true
        }
        return updated
    }

    /**
     * Whether the downloads recorded before they had a folder of their own have been moved into it.
     *
     * Set once, by whichever platform runs that migration, so it is not attempted on every launch.
     */
    suspend fun downloadsMigratedNow(): Boolean = prefs.data.first()[DOWNLOADS_MIGRATED] ?: false

    suspend fun markDownloadsMigrated() {
        prefs.edit { it[DOWNLOADS_MIGRATED] = true }
    }

    private fun decodeDownloads(prefs: Preferences): List<ResumeRecord> = buildList {
        for ((key, value) in prefs.asMap()) {
            val ids = key.name.removePrefixOrNull("dl_") ?: continue
            add(decodeDownload(prefs, ids, value as? String) ?: continue)
        }
    }.sortedByDescending { it.updatedAt }

    private fun decodeDownload(prefs: Preferences, ids: String, encoded: String?): ResumeRecord? =
        ResumeRecord.decode(
            key = ids,
            encoded = encoded,
            positionMs = prefs[longPreferencesKey("resume_$ids")] ?: 0L,
            durationMs = prefs[longPreferencesKey("duration_$ids")] ?: 0L,
        )

    /**
     * The unfinished downloads, as the last write of the queue left them.
     *
     * Read once at launch by [OfflineDownloads.restore]. Not a flow: nothing watches this, because
     * the live queue is the one in memory and this is only its footprint on disk.
     */
    suspend fun downloadQueueNow(): List<DownloadRequest> =
        DownloadRequest.decodeAll(prefs.data.first()[DOWNLOAD_QUEUE])

    /**
     * Records the queue as it now stands. An empty list clears the key rather than storing "".
     *
     * Written on every change to the queue, which is a handful of writes per download rather than
     * one per second: progress is deliberately not stored, since a resumed download asks TDLib how
     * many bytes are actually on disk rather than trusting a number the crash may have caught mid
     * write.
     */
    suspend fun saveDownloadQueue(requests: List<DownloadRequest>) {
        prefs.edit { prefs ->
            if (requests.isEmpty()) {
                prefs.remove(DOWNLOAD_QUEUE)
            } else {
                prefs[DOWNLOAD_QUEUE] = DownloadRequest.encodeAll(requests)
            }
        }
    }

    /**
     * Remembers a video as one that has been fetched, so Downloads can name it later.
     *
     * [localPath] is where the file now is, once it has been moved into TMPlayer's Downloads
     * folder; null records a download whose file is still in TDLib's cache.
     *
     * Not capped, unlike the resume history: this list is the only record of which files on the
     * disk are the viewer's, and a download whose record fell off the end would become a file
     * nothing can name, play or delete.
     */
    suspend fun noteDownload(item: MediaItem, chatTitle: String, localPath: String? = null) {
        prefs.edit { prefs ->
            prefs[downloadKey(item.chatId, item.messageId)] = ResumeRecord.encode(
                fileId = item.fileId,
                title = item.title,
                chatTitle = chatTitle,
                sizeBytes = item.sizeBytes,
                durationSec = item.durationSec,
                updatedAt = System.currentTimeMillis(),
                localPath = localPath,
            )
        }
    }

    /** Drops one row from Downloads, once its file has actually been deleted. */
    suspend fun forgetDownload(chatId: Long, messageId: Long) {
        prefs.edit { it.remove(downloadKey(chatId, messageId)) }
    }

    suspend fun saveResumePosition(
        chatId: Long,
        messageId: Long,
        positionMs: Long,
        durationMs: Long = 0L,
        description: String? = null,
    ) {
        prefs.edit { prefs ->
            val key = resumeKey(chatId, messageId)
            // Watch time for the support ladder, read off the same heartbeat.
            noteSupportProgress(prefs, progressKey(chatId, messageId), positionMs, System.currentTimeMillis())
            // In the first three minutes, or in the credits: there is nothing worth resuming.
            if (!ResumeRules.keeps(positionMs, durationMs)) {
                prefs.remove(key)
                prefs.remove(durationKey(chatId, messageId))
                prefs.remove(metaKey(chatId, messageId))
            } else {
                prefs[key] = positionMs
                if (durationMs > 0) prefs[durationKey(chatId, messageId)] = durationMs
                if (description != null) prefs[metaKey(chatId, messageId)] = description
                evictOldestHistory(prefs)
            }
        }
    }

    /**
     * Keeps the history to [MAX_HISTORY] entries, oldest out first.
     *
     * Unbounded, every key would be walked on every read of Continue watching and rewritten on every
     * ten-second heartbeat. The cap is generous: nobody scrolls past two hundred half-watched videos.
     */
    private fun evictOldestHistory(prefs: MutablePreferences) {
        val stamps = prefs.asMap().keys
            .mapNotNull { it.name.removePrefixOrNull("meta_") }
            .mapNotNull { ids ->
                val meta = prefs[stringPreferencesKey("meta_$ids")] ?: return@mapNotNull null
                ids to (ResumeRecord.updatedAtOf(meta) ?: 0L)
            }
        if (stamps.size <= MAX_HISTORY) return
        stamps.sortedBy { it.second }
            .take(stamps.size - MAX_HISTORY)
            .forEach { (ids, _) ->
                prefs.remove(longPreferencesKey("resume_$ids"))
                prefs.remove(longPreferencesKey("duration_$ids"))
                prefs.remove(stringPreferencesKey("meta_$ids"))
                prefs.remove(stringPreferencesKey("delay_$ids"))
            }
    }

    private fun String.removePrefixOrNull(prefix: String): String? =
        if (startsWith(prefix)) removePrefix(prefix) else null

    /**
     * Forgets every half-watched video in one pass, emptying Continue watching.
     *
     * The keys are collected before anything is removed: [MutablePreferences] is being written to
     * while its own map is walked otherwise.
     */
    suspend fun clearWatchHistory() {
        prefs.edit { prefs ->
            val doomed = prefs.asMap().keys.filter { key ->
                val name = key.name
                name.startsWith("resume_") || name.startsWith("duration_") || name.startsWith("meta_") ||
                    name.startsWith("delay_")
            }
            for (key in doomed) prefs.remove(key)
        }
    }

    /**
     * Drops half-watched entries that can no longer become a card, and says how many went.
     *
     * A resume position is written the moment playback starts, but the description beside it can be
     * missing: an interrupted write, or a file id since revoked, leaves a position that
     * [continueWatching] can only skip, forever and invisibly. This sweep uses the same decoder as
     * the tab, so anything it deletes is exactly what the tab could not show.
     */
    suspend fun pruneBrokenHistory(): Int {
        var removed = 0
        prefs.edit { prefs ->
            // Collected before anything is removed: [MutablePreferences] is being written to while
            // its own map is walked otherwise.
            val doomed = prefs.asMap().keys
                .map { it.name }
                .filter { it.startsWith("resume_") }
                .mapNotNull { name ->
                    val ids = name.removePrefix("resume_")
                    val positionMs = prefs[longPreferencesKey(name)] ?: return@mapNotNull ids
                    val record = ResumeRecord.decode(
                        key = ids,
                        encoded = prefs[stringPreferencesKey("meta_$ids")],
                        positionMs = positionMs,
                        durationMs = prefs[longPreferencesKey("duration_$ids")] ?: 0L,
                    )
                    if (record == null) ids else null
                }

            for (ids in doomed) {
                prefs.remove(longPreferencesKey("resume_$ids"))
                prefs.remove(longPreferencesKey("duration_$ids"))
                prefs.remove(stringPreferencesKey("meta_$ids"))
                removed++
            }
        }
        return removed
    }

    suspend fun clearResumePosition(chatId: Long, messageId: Long) {
        prefs.edit {
            it.remove(resumeKey(chatId, messageId))
            it.remove(durationKey(chatId, messageId))
            it.remove(metaKey(chatId, messageId))
        }
    }

    companion object {
        /**
         * The file name Android's `preferencesDataStore("tmplayer")` delegate has always used, in
         * `filesDir/datastore/`. Keeping it is what keeps an upgraded install's settings.
         */
        const val FILE_NAME = "tmplayer.preferences_pb"

        /** Opens the store at [file]. Call once per process and share the result. */
        fun openDataStore(file: File): DataStore<Preferences> =
            PreferenceDataStoreFactory.createWithPath(produceFile = { file.absolutePath.toPath() })

        /** Whether launch should skip the chat list, given what is remembered and the setting. */
        fun autoOpenChatId(lastChatId: Long, enabled: Boolean): Long? =
            if (enabled && lastChatId != 0L) lastChatId else null

        /**
         * How many half-watched videos are kept before the oldest start dropping off.
         *
         * The resume history only. Downloads are not capped: see [noteDownload].
         */
        const val MAX_HISTORY = 200

        fun progressKey(chatId: Long, messageId: Long) = "${chatId}_$messageId"
    }
}

/**
 * The soundtrack and the subtitles a series is watched with.
 *
 * [subtitlesOn] is separate from [textLanguage] having a value, because "off" is a real choice and
 * has to survive: without it, a viewer who turned subtitles off would get them back the moment the
 * next episode's default selection picked a track.
 */
data class TrackChoice(
    val audioLanguage: String? = null,
    val textLanguage: String? = null,
    val subtitlesOn: Boolean = false,
) {
    /** Nothing has been chosen yet, so the player's own defaults are the honest answer. */
    val empty: Boolean get() = audioLanguage == null && textLanguage == null && !subtitlesOn
}

/** How far into a video the viewer got, and how long it runs. */
data class WatchPoint(val positionMs: Long, val durationMs: Long) {
    val fraction: Float
        get() = if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
}
