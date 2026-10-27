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
import com.tmplayer.player.SpeedMemory
import com.tmplayer.player.SubtitleStyle
import com.tmplayer.player.SyncDelays
import com.tmplayer.player.TouchPrefs
import com.tmplayer.player.TrackPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import okio.Path.Companion.toPath
import java.io.File

private val FAVORITES = stringSetPreferencesKey("favorite_chats")
private val HIDDEN_CHATS = stringPreferencesKey("hidden_chats") // HiddenChats.KEY
private val PLAYER_HINT_SEEN = booleanPreferencesKey("player_hint_seen")
private val FIRST_VIDEO_TIP_DISMISSED = booleanPreferencesKey("first_video_tip_dismissed")
private val TV_BROWSE_HINT_SEEN = booleanPreferencesKey("tv_browse_hint_seen")
private val ONLINE_META_NOTICE_DISMISSED = booleanPreferencesKey("online_meta_notice_dismissed")
private val ONLINE_META_INVITE_SEEN = booleanPreferencesKey("online_meta_invite_seen")
private val OPEN_LAST_CHAT = booleanPreferencesKey("open_last_chat")
private val DOWNLOAD_FIRST = booleanPreferencesKey("download_before_playing")
private val AUTOPLAY_NEXT = booleanPreferencesKey("autoplay_next")
private val DETAIL_FIRST = booleanPreferencesKey("detail_first")
private val COMPACT_NAV = booleanPreferencesKey("compact_nav")
private val DOWNMIX_STEREO = booleanPreferencesKey("downmix_stereo")
private val VOLUME_BOOST = booleanPreferencesKey("volume_boost")
private val NIGHT_MODE = booleanPreferencesKey("night_mode")
private val EVEN_VOLUME = booleanPreferencesKey("even_volume")

/** Defaults the platform sets at start, where one device differs from another. */
object SettingsDefaults {
    /**
     * Even out volume until the viewer chooses: off on a television, where processing every
     * sample of the sound costs a weak stick's cores while it decodes the picture.
     */
    @Volatile
    var evenVolume: Boolean = true
}
private val RESUME_SYNC = booleanPreferencesKey("resume_sync")
private val RESUME_SYNC_MESSAGE = longPreferencesKey("resume_sync_message")
private val RESUME_SYNC_KEYS = stringPreferencesKey("resume_sync_keys")
private val SKIP_SILENCE = booleanPreferencesKey("skip_silence")
private val BACKGROUND_AUDIO = booleanPreferencesKey("background_audio")
private val PIP_ON_LEAVE = booleanPreferencesKey("pip_on_leave")
// Not "resume_...": that prefix is a resume point's, and the sweeps over resume points (the
// broken-history prune at launch, Clear history) would take this setting with them.
private val RESUME_REWIND = longPreferencesKey("rewind_on_resume_ms")
private val WIFI_ONLY = booleanPreferencesKey("wifi_only_downloads")
private val SAVE_MOBILE_DATA = booleanPreferencesKey("save_mobile_data")
private val REMOVE_AFTER_WATCHING = booleanPreferencesKey("remove_after_watching")
private val LOW_STORAGE_MODE = booleanPreferencesKey("low_storage_mode")
private val LIGHTER_PICTURES = booleanPreferencesKey("lighter_pictures")
private val REDUCE_MOTION = booleanPreferencesKey("reduce_motion")
private val DEVICE_CARD_DISMISSED = booleanPreferencesKey("device_card_dismissed")
private val DEVICE_HINT_SHOWN = booleanPreferencesKey("device_hint_shown")
private val TRICKPLAY = booleanPreferencesKey("trickplay")
private val WATCH_NEXT = booleanPreferencesKey("watch_next")
private val TV_CHANNELS = booleanPreferencesKey("tv_channels")
private val HOME_CAROUSEL = booleanPreferencesKey("home_carousel")
private val HOME_CONTINUE = booleanPreferencesKey("home_continue")
private val HOME_RECENT = booleanPreferencesKey("home_recent")
private val HOME_STARRED_ROWS = booleanPreferencesKey("home_starred_rows")
private val HOME_RECENT_SOURCE = stringPreferencesKey("home_recent_source")
private val HOME_RECENT_CHATS = stringPreferencesKey("home_recent_chats")
private val CRASH_REPORTS = booleanPreferencesKey("crash_reports")
private val LAST_CHAT = longPreferencesKey("last_chat")
private val MIN_SIZE = longPreferencesKey("min_size_bytes")
private val MAX_SIZE = longPreferencesKey("max_size_bytes")
private val CHAT_FILTER = stringPreferencesKey("chat_filter")
private val CHAT_SORT = stringPreferencesKey("chat_sort")
/** "Only my folders", retired: read once by [SettingsStore.takeOnlyFolders] and removed. */
private val HIDE_DEFAULT_GROUPS = booleanPreferencesKey("hide_default_groups")

/** The qualities played, counted: see [QualityHabit]. */
private val QUALITY_HABIT = stringPreferencesKey("quality_habit")

/** What this device remembers of pictures it decoded in software: see [DecodeMemory]. */
private val DECODE_MEMORY = stringPreferencesKey("decode_memory")

/** The kinds of picture ([DecodeMemory.keyOf]) whose "may stutter" question the viewer turned off. */
private val HEAVY_ASK_SKIP = stringPreferencesKey("heavy_ask_skip")

/**
 * The copy the viewer chose for one video, by [MediaItem.id], keyed on the video
 * ([VideoCopies.keyOf]) so it holds whichever copy the video is opened from.
 */
private fun copyPickKey(videoKey: String) = stringPreferencesKey("copy_pick_" + videoKey.take(160))

// The app lock and the kids lock (CP57). See [PinHash], [PinAttempts] and [KidsLock]. All of them
// belong to the account: signing out clears them with everything else, which is the only way
// back from a forgotten PIN.
private val APP_PIN = stringPreferencesKey("app_pin")
private val PIN_FAILURES = longPreferencesKey("app_pin_failures")
// The wait left after wrong PINs, and the [PinClock] time it was written at: see [PinAttempts].
private val PIN_WAIT_LEFT = longPreferencesKey("app_pin_wait_left_ms")
private val PIN_WAIT_STAMP = longPreferencesKey("app_pin_wait_stamp")
private val LOCK_ON_OPEN = booleanPreferencesKey("app_lock_on_open")
private val KIDS_LOCK = booleanPreferencesKey("kids_lock")
private val KIDS_CHATS = stringSetPreferencesKey("kids_chats")

private val SETUP = stringPreferencesKey("setup")
private val TOUR = stringPreferencesKey("tour")
private val HISTORY_TAB = stringPreferencesKey("history_tab")
private val HISTORY_SORT = stringPreferencesKey("history_sort")
private val DOWNLOADS_SORT = stringPreferencesKey("downloads_sort")
private val CHAT_SNAPSHOT = stringPreferencesKey("chat_snapshot")
private val ACCOUNT_SNAPSHOT = stringPreferencesKey("account_snapshot")
private val THEME_CHOICE = stringPreferencesKey("theme_choice")

/** The Pure black switch under the theme: dark on true black, whenever the app is dark. */
private val PURE_BLACK = booleanPreferencesKey("pure_black")

/** The accent colour, an [Accent] by name. About the device's look, so it outlives a sign out. */
private val ACCENT = stringPreferencesKey("accent")

/** The viewer's own accent as ARGB, used while [ACCENT] says Custom. The device's look, like [ACCENT]. */
private val ACCENT_CUSTOM = longPreferencesKey("accent_custom")

/** The typeface, an [AppTypeface] by name. */
private val APP_TYPEFACE = stringPreferencesKey("app_typeface")

/** Second lines and edges brought forward. */
private val HIGH_CONTRAST = booleanPreferencesKey("high_contrast")

private val LANGUAGE = stringPreferencesKey("language")

/** The language the "Now in ..." card last announced. See [com.tmplayer.i18n.LanguageNotice]. */
private val LANGUAGE_ANNOUNCED = stringPreferencesKey("language_announced")

/** The version whose "What's new" has been dealt with. See [WhatsNew]. */
private val LAST_SEEN_VERSION = stringPreferencesKey("last_seen_version")
private val DYNAMIC_COLOUR = booleanPreferencesKey("dynamic_colour")

/** The interface size (CP59), a [UiScale] by name. About the screen, so it outlives a sign out. */
private val UI_SCALE = stringPreferencesKey("ui_scale")
private val VIDEO_SCALE = stringPreferencesKey("video_scale")

/** The last few searches inside a chat, one per line, newest first. See [RecentSearches]. */
private val RECENT_SEARCHES = stringPreferencesKey("recent_searches")

/** "Remember searches" in Settings: on unless switched off, and switching it off forgets them. */
private val REMEMBER_SEARCHES = booleanPreferencesKey("remember_searches")

// The update check's memory, shared by the phone, the TV and the desktop. See [UpdateScheduler].
private val UPDATE_NOTIFY = booleanPreferencesKey("update_notify")
private val UPDATE_LAST_CHECK = longPreferencesKey("update_last_check")
private val UPDATE_SKIPPED = stringPreferencesKey("update_skipped_version")
private val UPDATE_SNOOZED_UNTIL = longPreferencesKey("update_snoozed_until")
private val UPDATE_POPUP_SHOWN = stringPreferencesKey("update_popup_shown")
private val UPDATE_TEST_VERSIONS = booleanPreferencesKey("update_test_versions")
// Android's own install of an update (HX5): "Install updates by itself", the version it last tried
// and when, the Android release that refused to update without asking, and the install under way
// for the notice after the restart. See [AutoInstall] and [UpdateRestart].
private val UPDATE_AUTO_INSTALL = booleanPreferencesKey("update_auto_install")
private val UPDATE_AUTO_TRIED = stringPreferencesKey("update_auto_tried")
private val UPDATE_SILENT_REFUSED = longPreferencesKey("update_silent_refused")
private val UPDATE_INSTALLING = stringPreferencesKey("update_installing")

// The support card's memory. See [SupportReminder]. About this device's viewer rather than the
// Telegram account, so signing out keeps them: "I already support" must not come undone.
private val SUPPORT_FIRST_SEEN = longPreferencesKey("support_first_seen")
private val SUPPORT_SNOOZED_UNTIL = longPreferencesKey("support_snoozed_until")
// Written by the window's "Don't show again", as it was by the old "Don't ask again".
private val SUPPORT_NEVER = booleanPreferencesKey("support_never")
private val SUPPORT_WATCHES = longPreferencesKey("support_watches")
private val SUPPORT_WATCH_MS = longPreferencesKey("support_watch_ms")
private val SUPPORT_ASKED = longPreferencesKey("support_asked")
private val SUPPORT_SUPPORTER = booleanPreferencesKey("support_supporter")
private val SUPPORT_RECENT = stringPreferencesKey("support_recent")
// The Buy Me a Coffee window and the notification that replaced the ladder (2026-10-18).
private val SUPPORT_ASKED_AT = longPreferencesKey("support_asked_at")
private val SUPPORT_WINDOWS = longPreferencesKey("support_windows")
private val SUPPORT_NUDGED_AT = longPreferencesKey("support_nudged_at")
// The notification's star rung (CP76): when it asked for a star, and whether Star was pressed.
private val SUPPORT_STAR_ASKED_AT = longPreferencesKey("support_star_asked_at")
private val SUPPORT_STARRED = booleanPreferencesKey("support_starred")
// "New videos in starred chats" (phone and desktop): the switch, whether the offer after a star
// was made, and the newest video message each starred chat had at the last check. The account's,
// so signing out clears them with everything else.
private val NEW_VIDEO_ALERTS = booleanPreferencesKey("new_video_alerts")
private val NEW_VIDEO_OFFERED = booleanPreferencesKey("new_video_offered")
private val NEW_VIDEO_SEEN = stringPreferencesKey("new_video_seen")
// "Sponsor reminders", the notification's switch. The device's, like the counters.
private val SPONSOR_REMINDERS = booleanPreferencesKey("sponsor_reminders")
private val SUPPORT_KEYS = listOf(
    SUPPORT_FIRST_SEEN, SUPPORT_SNOOZED_UNTIL, SUPPORT_WATCHES, SUPPORT_WATCH_MS, SUPPORT_ASKED,
    SUPPORT_ASKED_AT, SUPPORT_WINDOWS, SUPPORT_NUDGED_AT, SUPPORT_STAR_ASKED_AT,
)

// The notifications that help (CP76), one switch each beside the two above, and what each one
// remembers so it never says the same thing twice. The account's, so signing out clears them.
private val NOTIFY_DOWNLOADS = booleanPreferencesKey("notify_downloads")
private val NOTIFY_EPISODES = booleanPreferencesKey("notify_episodes")
private val NOTIFY_LOW_STORAGE = booleanPreferencesKey("notify_low_storage")
private val NOTIFY_PIECES = booleanPreferencesKey("notify_pieces")
private val NOTIFY_UPDATE = booleanPreferencesKey("notify_update")
private val EPISODE_SEEN = stringPreferencesKey("episode_alert_seen")
private val EPISODE_TOLD = stringPreferencesKey("episode_alert_told")
private val LOW_STORAGE_ALERT = stringPreferencesKey("low_storage_alert")
private val PIECE_GAPS = stringPreferencesKey("piece_gaps")
private val UPDATE_ALERTED = stringPreferencesKey("update_alerted_version")

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
private val SUBTITLE_BOX_LEVEL = stringPreferencesKey("subtitle_box_level")
private val SUBTITLE_COLOUR = stringPreferencesKey("subtitle_colour")
private val SUBTITLE_EDGE = stringPreferencesKey("subtitle_edge")
private val SUBTITLE_FACE = stringPreferencesKey("subtitle_face")
private val SUBTITLE_BOLD = booleanPreferencesKey("subtitle_bold")

// Android's video decoder choice. See [VideoDecoder].
private val VIDEO_DECODER = stringPreferencesKey("video_decoder")

// The app-wide languages, under the series memory. See [TrackPreferences].
private val PREFERRED_AUDIO = stringPreferencesKey("preferred_audio_language")
private val PREFERRED_SUBTITLE = stringPreferencesKey("preferred_subtitle_language")
private val SUBTITLE_MODE = stringPreferencesKey("subtitle_mode")

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
private fun seriesNameKey(series: String) = stringPreferencesKey("series_name_$series")

private fun kidsChatsOf(prefs: Preferences): Set<Long> =
    prefs[KIDS_CHATS].orEmpty().mapNotNull { it.toLongOrNull() }.toSet()

/** The kids lock's allowed chats as stored, or null while it is not in force. */
private fun allowedChatsOf(prefs: Preferences): Set<Long>? =
    if (KidsLock.inForce(prefs[KIDS_LOCK] == true, prefs[APP_PIN] != null)) kidsChatsOf(prefs) else null

private fun attemptsOf(prefs: Preferences, now: Long): PinAttempts = PinAttempts.restored(
    failures = (prefs[PIN_FAILURES] ?: 0L).toInt(),
    waitLeftMs = prefs[PIN_WAIT_LEFT] ?: 0L,
    stampedAt = prefs[PIN_WAIT_STAMP] ?: 0L,
    now = now,
)

/**
 * A speed remembered for a show, a course, a chat or a topic: see [SpeedMemory]. The scope keeps its
 * colons and a group's minus sign, so chat 12 topic 34 and chat 123 topic 4 never share a key.
 */
private fun speedKey(scope: String) = floatPreferencesKey(
    "speed_" + scope.lowercase(java.util.Locale.ROOT).filter { it.isLetterOrDigit() || it == ':' || it == '-' }.take(80),
)

/** Where playback stopped, so the next launch can offer to continue. */
private fun resumeKey(chatId: Long, messageId: Long) =
    longPreferencesKey("resume_${chatId}_$messageId")

private fun durationKey(chatId: Long, messageId: Long) =
    longPreferencesKey("duration_${chatId}_$messageId")

/** Title, chat and file id, so a half-watched video can be reopened without its chat loaded. */
private fun metaKey(chatId: Long, messageId: Long) =
    stringPreferencesKey("meta_${chatId}_$messageId")

/** The forum topic last browsed in one group, so it opens where the viewer left it. 0 is all. */
private fun topicKey(chatId: Long) = longPreferencesKey("topic_$chatId")

private val DEVELOPER_OPTIONS = booleanPreferencesKey("developer_options")

/** What a chat's search turns the developer options on with, on a phone or a television. */
const val DEVELOPER_PHRASE = "/namecheck"

/** The order a chat's videos are listed in, per chat. See [VideoSort]. */
private fun videoSortKey(chatId: Long) = stringPreferencesKey("vsort_$chatId")

/** Which of a chat's videos are listed, per chat. See [VideoFilter]. */
private fun videoFilterKey(chatId: Long) = stringPreferencesKey("vfilter_$chatId")

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
private const val SETUP_PENDING = "pending"
private const val SETUP_DONE = "done"
private const val TOUR_PENDING = "pending"
private const val TOUR_DONE = "done"

private fun cachedKey(chatId: Long, messageId: Long) =
    stringPreferencesKey("$CACHED_PREFIX${chatId}_$messageId")

/**
 * Every setting and every small record the app keeps, over one preferences DataStore.
 *
 * The store is handed in rather than built here, because DataStore allows exactly one live
 * instance per file in a process: each app opens it once, with [openDataStore], and every
 * `SettingsStore` shares that instance.
 */
class SettingsStore(private val prefs: DataStore<Preferences>) : AllowedChatsSource, CorrectionsSource, HiddenChatsSource {

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

    // ---- hidden chats -----------------------------------------------------------------------

    /** The chats hidden from TMPlayer's lists, in the order they were hidden. See [HiddenChats]. */
    override val hiddenChats: Flow<List<HiddenChat>> = read { HiddenChats.decode(it[HIDDEN_CHATS]) }

    suspend fun hideChat(chatId: Long, title: String) {
        prefs.edit { it[HIDDEN_CHATS] = HiddenChats.encode(HiddenChats.with(HiddenChats.decode(it[HIDDEN_CHATS]), HiddenChat(chatId, title))) }
    }

    /** Lists [chatId] again, wherever it was left out. */
    suspend fun showChat(chatId: Long) {
        prefs.edit { prefs ->
            val left = HiddenChats.decode(prefs[HIDDEN_CHATS]).filter { it.id != chatId }
            if (left.isEmpty()) prefs.remove(HIDDEN_CHATS) else prefs[HIDDEN_CHATS] = HiddenChats.encode(left)
        }
    }

    suspend fun showAllChats() {
        prefs.edit { it.remove(HIDDEN_CHATS) }
    }

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
            val pureBlack = prefs[PURE_BLACK]
            val accent = prefs[ACCENT]
            val dynamic = prefs[DYNAMIC_COLOUR]
            val uiScale = prefs[UI_SCALE]
            // How light the device needs the app to be is the device's, not the account's, and so
            // is how sparingly it may use the mobile data it pays for.
            val device = listOf(LIGHTER_PICTURES, REDUCE_MOTION, DEVICE_CARD_DISMISSED, DEVICE_HINT_SHOWN, SAVE_MOBILE_DATA)
                .mapNotNull { key -> prefs[key]?.let { Pair(key, it) } }
            val customAccent = prefs[ACCENT_CUSTOM]
            val typeface = prefs[APP_TYPEFACE]
            val highContrast = prefs[HIGH_CONTRAST]
            val migrated = prefs[DOWNLOADS_MIGRATED]
            val support = SUPPORT_KEYS.mapNotNull { key -> prefs[key]?.let { Pair(key, it) } }
            val supportNever = prefs[SUPPORT_NEVER]
            val supporter = prefs[SUPPORT_SUPPORTER]
            val starred = prefs[SUPPORT_STARRED]
            val reminders = prefs[SPONSOR_REMINDERS]
            val recent = prefs[SUPPORT_RECENT]
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
            pureBlack?.let { prefs[PURE_BLACK] = it }
            accent?.let { prefs[ACCENT] = it }
            dynamic?.let { prefs[DYNAMIC_COLOUR] = it }
            uiScale?.let { prefs[UI_SCALE] = it }
            customAccent?.let { prefs[ACCENT_CUSTOM] = it }
            typeface?.let { prefs[APP_TYPEFACE] = it }
            highContrast?.let { prefs[HIGH_CONTRAST] = it }
            for ((key, value) in device) prefs[key] = value
            for ((key, value) in support) prefs[key] = value
            supportNever?.let { prefs[SUPPORT_NEVER] = it }
            supporter?.let { prefs[SUPPORT_SUPPORTER] = it }
            starred?.let { prefs[SUPPORT_STARRED] = it }
            reminders?.let { prefs[SPONSOR_REMINDERS] = it }
            recent?.let { prefs[SUPPORT_RECENT] = it }
            if (keepDownloads) {
                for ((key, value) in kept) prefs[key] = value
                migrated?.let { prefs[DOWNLOADS_MIGRATED] = it }
            }
        }
    }

    /**
     * Unstars every chat at once, which is the only way back from a tab full of them. Under the
     * kids lock ([allowed] not null) only the favourites it shows go; the hidden ones stay.
     */
    suspend fun clearFavorites(allowed: Set<Long>? = null) {
        prefs.edit { prefs ->
            if (allowed == null) {
                prefs.remove(FAVORITES)
            } else {
                prefs[FAVORITES] = prefs[FAVORITES].orEmpty().filterTo(HashSet()) { it.toLongOrNull() !in allowed }
            }
        }
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

    /**
     * Whether the television's side bar and the desktop's floating bar show their icons alone,
     * without the names. Off by default; a phone's bar is not affected.
     */
    val compactNav: Flow<Boolean> = read { it[COMPACT_NAV] ?: false }

    suspend fun setCompactNav(value: Boolean) {
        prefs.edit { it[COMPACT_NAV] = value }
    }

    /** The chat opened most recently, or zero when there has not been one yet. */
    val lastChatId: Flow<Long> = read { it[LAST_CHAT] ?: 0L }

    suspend fun rememberChatOpened(chatId: Long) {
        prefs.edit { it[LAST_CHAT] = chatId }
    }

    /** The forum topic last picked in [chatId], or 0 for all of them. */
    suspend fun chosenTopic(chatId: Long): Int = (prefs.data.first()[topicKey(chatId)] ?: 0L).toInt()

    suspend fun rememberTopic(chatId: Long, topicId: Int) {
        prefs.edit { if (topicId == 0) it.remove(topicKey(chatId)) else it[topicKey(chatId)] = topicId.toLong() }
    }

    suspend fun forgetLastChat() {
        prefs.edit { it.remove(LAST_CHAT) }
    }

    // ---- a chat's own order, and the viewer's corrections ------------------------------------

    /** How [chatId]'s videos are ordered: [VideoSort.Newest] until the viewer picks another. */
    fun videoSort(chatId: Long): Flow<VideoSort> = read { VideoSort.decode(it[videoSortKey(chatId)]) }

    suspend fun setVideoSort(chatId: Long, sort: VideoSort) {
        prefs.edit { if (sort == VideoSort.DEFAULT) it.remove(videoSortKey(chatId)) else it[videoSortKey(chatId)] = sort.name }
    }

    /** Which of [chatId]'s videos are listed: [VideoFilter.All] until the viewer picks another. */
    fun videoFilter(chatId: Long): Flow<VideoFilter> = read { VideoFilter.decode(it[videoFilterKey(chatId)]) }

    suspend fun setVideoFilter(chatId: Long, filter: VideoFilter) {
        prefs.edit { if (filter == VideoFilter.DEFAULT) it.remove(videoFilterKey(chatId)) else it[videoFilterKey(chatId)] = filter.name }
    }

    /**
     * The hidden developer options (the name check, [NameReport]): off until the viewer turns them
     * on by the hidden gesture each platform has (searching a chat for [DEVELOPER_PHRASE] on a
     * phone or a television, seven clicks on the version on a computer). Device only: not backed up.
     */
    val developerOptions: Flow<Boolean> = read { it[DEVELOPER_OPTIONS] ?: false }

    suspend fun setDeveloperOptions(value: Boolean) {
        prefs.edit { if (value) it[DEVELOPER_OPTIONS] = true else it.remove(DEVELOPER_OPTIONS) }
    }

    /** Every [VideoCorrection], by [MediaItem.id]. See [VideoCorrections]. */
    override val corrections: Flow<Map<String, VideoCorrection>> = read { prefs ->
        prefs.asMap().mapNotNull { (key, value) ->
            val id = VideoCorrections.idOf(key.name) ?: return@mapNotNull null
            val correction = VideoCorrection.decode(value as? String)
            if (correction.isEmpty) null else id to correction
        }.toMap()
    }

    /**
     * Replaces the correction for each of [items] with what [change] makes of it; an empty one is
     * removed. Every copy of a folded tile is passed together, so a rename or a wrong match holds
     * whichever copy the tile is showing.
     */
    suspend fun correct(items: List<MediaItem>, change: (VideoCorrection) -> VideoCorrection) {
        val now = VideoCorrections.current
        val updated = items.distinctBy { it.id }.map { it to change(now[it.id] ?: VideoCorrection()) }
        prefs.edit { prefs ->
            for ((item, correction) in updated) {
                val key = stringPreferencesKey(VideoCorrections.keyName(item.chatId, item.messageId))
                if (correction.isEmpty) prefs.remove(key) else prefs[key] = correction.encode()
            }
        }
        updated.forEach { (item, correction) -> VideoCorrections.put(item.id, correction) }
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
     * "Low storage mode": a streamed video keeps only a rolling window of itself on the disk, so a
     * film larger than the free space can still be watched. Off by default, because seeking back
     * past the window fetches those bytes again. Never applies to a video the viewer downloaded.
     */
    val lowStorageMode: Flow<Boolean> = read { it[LOW_STORAGE_MODE] ?: false }

    suspend fun setLowStorageMode(value: Boolean) {
        prefs.edit { it[LOW_STORAGE_MODE] = value }
    }

    /** Read once at the start of playback, where a flow that has not emitted yet would lie. */
    suspend fun lowStorageModeNow(): Boolean = prefs.data.first()[LOW_STORAGE_MODE] ?: false

    // ---- low-end devices (CP68) -------------------------------------------------------------

    /**
     * "Lighter pictures": the picture memory takes half its usual share of the heap, posters,
     * stills and thumbnails are decoded smaller, and art is not blurred behind itself. Off by
     * default; recommended on a device short of memory ([DeviceFitRules]).
     */
    val lighterPictures: Flow<Boolean> = read { it[LIGHTER_PICTURES] ?: false }

    suspend fun setLighterPictures(value: Boolean) {
        prefs.edit { it[LIGHTER_PICTURES] = value }
    }

    /** "Reduce motion": pictures and panels appear at once and the loading shimmer stands still. */
    val reduceMotion: Flow<Boolean> = read { it[REDUCE_MOTION] ?: false }

    suspend fun setReduceMotion(value: Boolean) {
        prefs.edit { it[REDUCE_MOTION] = value }
    }

    /** The settings a [Recommendation] is about, as they are now. */
    val lightSettings: Flow<LightSettings> = read {
        LightSettings(
            lowStorageMode = it[LOW_STORAGE_MODE] ?: false,
            seekThumbnails = it[TRICKPLAY] ?: true,
            lighterPictures = it[LIGHTER_PICTURES] ?: false,
            reduceMotion = it[REDUCE_MOTION] ?: false,
        )
    }

    /** Applies [recommendations] in one write; each stays its own setting, undone in its own row. */
    suspend fun applyRecommendations(recommendations: Collection<Recommendation>) {
        if (recommendations.isEmpty()) return
        prefs.edit {
            for (recommendation in recommendations) {
                when (recommendation) {
                    Recommendation.LowStorageMode -> it[LOW_STORAGE_MODE] = true
                    Recommendation.NoSeekThumbnails -> it[TRICKPLAY] = false
                    Recommendation.LighterPictures -> it[LIGHTER_PICTURES] = true
                    Recommendation.ReduceMotion -> it[REDUCE_MOTION] = true
                }
            }
        }
    }

    /** Settings' "Recommended for this device" card, closed by the viewer: it does not come back. */
    val deviceCardDismissed: Flow<Boolean> = read { it[DEVICE_CARD_DISMISSED] ?: false }

    suspend fun dismissDeviceCard() {
        prefs.edit { it[DEVICE_CARD_DISMISSED] = true }
    }

    /** The one-time hint after a stutter or a memory warning: once per install, sign outs included. */
    val deviceHintShown: Flow<Boolean> = read { it[DEVICE_HINT_SHOWN] ?: false }

    suspend fun markDeviceHintShown() {
        prefs.edit { it[DEVICE_HINT_SHOWN] = true }
    }

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

    /**
     * Night mode for sound: the loud and the quiet brought close together, so dialogue is heard at
     * a low volume and an explosion does not wake the house. Unlike [volumeBoost] it makes the
     * loudest moments quieter rather than everything louder. Off until turned on, then kept.
     */
    val nightMode: Flow<Boolean> = read { it[NIGHT_MODE] ?: false }

    suspend fun setNightMode(value: Boolean) {
        prefs.edit { it[NIGHT_MODE] = value }
    }

    /** Read before the player is built, like [volumeBoostNow]. */
    suspend fun nightModeNow(): Boolean = prefs.data.first()[NIGHT_MODE] ?: false

    /**
     * Even out volume: a track mastered quietly (a film made for a cinema, most surround tracks)
     * lifted towards the level of everything else, never past a cap and never clipped. On until
     * turned off, because a film 10 dB quieter than the video before it is the common case.
     */
    val evenVolume: Flow<Boolean> = read { it[EVEN_VOLUME] ?: SettingsDefaults.evenVolume }

    suspend fun setEvenVolume(value: Boolean) {
        prefs.edit { it[EVEN_VOLUME] = value }
    }

    /** Read before the player is built, like [volumeBoostNow]. */
    suspend fun evenVolumeNow(): Boolean = prefs.data.first()[EVEN_VOLUME] ?: SettingsDefaults.evenVolume

    /**
     * Resume across devices ([ResumeSync]): Continue watching kept in a message in the viewer's
     * own Saved Messages, edited in place, so another device signed in to the same account picks
     * up where this one stopped. Off until turned on in Settings.
     */
    val resumeSync: Flow<Boolean> = read { it[RESUME_SYNC] ?: false }

    suspend fun setResumeSync(value: Boolean) {
        prefs.edit {
            it[RESUME_SYNC] = value
            // Off forgets which message it was and what it last said, so turning it on again
            // starts by reading the message afresh.
            if (!value) {
                it.remove(RESUME_SYNC_MESSAGE)
                it.remove(RESUME_SYNC_KEYS)
            }
        }
    }

    suspend fun resumeSyncNow(): Boolean = prefs.data.first()[RESUME_SYNC] ?: false

    /** The Saved Messages message the places are kept in, once found or sent; 0 before. */
    suspend fun resumeSyncMessageNow(): Long = prefs.data.first()[RESUME_SYNC_MESSAGE] ?: 0L

    suspend fun setResumeSyncMessage(id: Long) {
        prefs.edit { it[RESUME_SYNC_MESSAGE] = id }
    }

    /** The videos ("chat_message") this device last wrote to the message, for telling what it removed since. */
    suspend fun resumeSyncKeysNow(): Set<String> =
        prefs.data.first()[RESUME_SYNC_KEYS].orEmpty().split(',').filter { it.isNotBlank() }.toSet()

    suspend fun setResumeSyncKeys(keys: Set<String>) {
        prefs.edit { it[RESUME_SYNC_KEYS] = keys.joinToString(",") }
    }

    /**
     * A place another device saved ([ResumeSync]), written as this device's own would be, when it
     * is newer than whatever this device has for the same video. [fileId] is this device's id for
     * the file, read from the message here: TDLib's file ids differ from one device to the next.
     */
    suspend fun importResume(entry: ResumeSyncDoc.Entry, fileId: Int): Boolean {
        var wrote = false
        prefs.edit { prefs ->
            val local = ResumeRecord.updatedAtOf(prefs[metaKey(entry.chatId, entry.messageId)]) ?: Long.MIN_VALUE
            if (local >= entry.updatedAt && prefs[resumeKey(entry.chatId, entry.messageId)] != null) return@edit
            prefs[resumeKey(entry.chatId, entry.messageId)] = entry.positionMs
            if (entry.durationMs > 0) prefs[durationKey(entry.chatId, entry.messageId)] = entry.durationMs
            prefs[metaKey(entry.chatId, entry.messageId)] = ResumeRecord.encode(
                fileId = fileId,
                title = entry.title,
                chatTitle = entry.chatTitle,
                sizeBytes = entry.sizeBytes,
                durationSec = entry.durationSec,
                updatedAt = entry.updatedAt,
                fileName = entry.fileName,
            )
            evictOldestHistory(prefs)
            wrote = true
        }
        return wrote
    }

    /** A place another device removed after this one last saved it ([ResumeSync]): gone here too. */
    suspend fun dropSyncedResume(tomb: ResumeSyncDoc.Tomb) {
        prefs.edit { prefs ->
            val local = ResumeRecord.updatedAtOf(prefs[metaKey(tomb.chatId, tomb.messageId)]) ?: return@edit
            if (local >= tomb.at) return@edit
            prefs.remove(resumeKey(tomb.chatId, tomb.messageId))
            prefs.remove(durationKey(tomb.chatId, tomb.messageId))
            prefs.remove(metaKey(tomb.chatId, tomb.messageId))
        }
    }

    /**
     * Skip silence, from the speed picker: the quiet stretches of a lecture or a talk played
     * through at once. Off until turned on, then kept for every video.
     *
     * Android only. FFmpeg's silenceremove filter in mpv's audio chain drops samples without the
     * picture following, so sound and picture drift apart; the mpv scripts that skip silence speed
     * playback up through it instead, which is a feature of its own rather than a filter line.
     */
    val skipSilence: Flow<Boolean> = read { it[SKIP_SILENCE] ?: false }

    suspend fun setSkipSilence(value: Boolean) {
        prefs.edit { it[SKIP_SILENCE] = value }
    }

    /** Read before the player is built, so a lecture opens already skipping. */
    suspend fun skipSilenceNow(): Boolean = prefs.data.first()[SKIP_SILENCE] ?: false

    /**
     * The phone keeps the sound of a video playing with the screen off or in another app, from a
     * media notification with play, pause and seeking. Off until turned on: leaving the player
     * pauses it, as it always has. Picture in picture still comes first where it can. Phone only.
     */
    val backgroundAudio: Flow<Boolean> = read { it[BACKGROUND_AUDIO] ?: false }

    suspend fun setBackgroundAudio(value: Boolean) {
        prefs.edit { it[BACKGROUND_AUDIO] = value }
    }

    suspend fun backgroundAudioNow(): Boolean = prefs.data.first()[BACKGROUND_AUDIO] ?: false

    /**
     * Whether going Home or to another app while a video plays shrinks it into a picture in
     * picture window (HX9). On by default, as it always was; off, leaving pauses the video (or
     * keeps the sound going, with [backgroundAudio]). The Picture in picture buttons work either
     * way, because the viewer asked for those. Android phones only; nothing else enters by itself.
     */
    val pictureInPictureOnLeave: Flow<Boolean> = read { it[PIP_ON_LEAVE] ?: true }

    suspend fun setPictureInPictureOnLeave(value: Boolean) {
        prefs.edit { it[PIP_ON_LEAVE] = value }
    }

    /**
     * How far before the saved position a resumed video starts, one of [ResumeRewind.CHOICES_MS]
     * (zero is off). A stored value off the list reads as the default.
     */
    val resumeRewindMs: Flow<Long> = read { ResumeRewind.sanitise(it[RESUME_REWIND]) }

    suspend fun setResumeRewindMs(value: Long) {
        prefs.edit { it[RESUME_REWIND] = ResumeRewind.sanitise(value) }
    }

    suspend fun resumeRewindNow(): Long = ResumeRewind.sanitise(prefs.data.first()[RESUME_REWIND])

    /**
     * Where a video with a saved position opens: that position, a few seconds earlier as the
     * viewer has set ([ResumeRewind]), or zero for one without. Continue watching and the history
     * keep the saved position itself; only the player starts from this.
     */
    suspend fun resumeStart(chatId: Long, messageId: Long): Long =
        ResumeRewind.start(resumePosition(chatId, messageId), resumeRewindNow())

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
     * "Save mobile data": on a metered connection a streamed video is fetched only a bounded
     * stretch ahead of where it is playing, and the stretch is extended as playback nears its
     * end, so a film stopped after twenty minutes has not cost most of its size. Nothing already
     * fetched is deleted, so seeking back costs nothing. Off by default, because many people
     * watch on mobile data with data to spare, and a stream fetched to the end rides out a
     * patchy signal better. Never applies to a video the viewer is downloading. See
     * [com.tmplayer.player.SaveDataStreams].
     */
    val saveMobileData: Flow<Boolean> = read { it[SAVE_MOBILE_DATA] ?: false }

    suspend fun setSaveMobileData(value: Boolean) {
        prefs.edit { it[SAVE_MOBILE_DATA] = value }
    }

    /** Read once at the start of playback, where a flow that has not emitted yet would lie. */
    suspend fun saveMobileDataNow(): Boolean = prefs.data.first()[SAVE_MOBILE_DATA] ?: false

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

    /**
     * TMPlayer's own channels on the Android TV home screen. See [TvChannels].
     *
     * On unless the viewer turns it off, unlike "Play next": the channels show what is new in the
     * chats they starred, not what they watched, and the launcher shows an app's channels only
     * once the viewer has the app on the home screen.
     */
    val tvChannels: Flow<Boolean> = read { it[TV_CHANNELS] ?: true }

    suspend fun tvChannelsNow(): Boolean = prefs.data.first()[TV_CHANNELS] ?: true

    suspend fun setTvChannels(value: Boolean) {
        prefs.edit { it[TV_CHANNELS] = value }
    }

    /**
     * The large picture turning over at the top of Home (user, 2026-10-23: an option to turn it
     * off). Off, Home opens on its rows; Recently added stays a row either way.
     */
    val homeCarousel: Flow<Boolean> = read { it[HOME_CAROUSEL] ?: true }

    suspend fun setHomeCarousel(value: Boolean) {
        prefs.edit { it[HOME_CAROUSEL] = value }
    }

    /** Which rows Home shows and where Recently added reads from ([HomeLayout]); every row on by default. */
    val homeLayout: Flow<HomeLayout> = read {
        HomeLayout.of(
            carousel = it[HOME_CAROUSEL],
            continueWatching = it[HOME_CONTINUE],
            recentlyAdded = it[HOME_RECENT],
            starredRows = it[HOME_STARRED_ROWS],
            source = it[HOME_RECENT_SOURCE],
            chats = it[HOME_RECENT_CHATS],
        )
    }

    suspend fun setHomeContinue(value: Boolean) {
        prefs.edit { it[HOME_CONTINUE] = value }
    }

    suspend fun setHomeRecent(value: Boolean) {
        prefs.edit { it[HOME_RECENT] = value }
    }

    suspend fun setHomeStarredRows(value: Boolean) {
        prefs.edit { it[HOME_STARRED_ROWS] = value }
    }

    suspend fun setHomeRecentSource(value: RecentSource) {
        prefs.edit { it[HOME_RECENT_SOURCE] = value.name }
    }

    /** The chats ticked for Recently added; an empty set clears the setting. */
    suspend fun setHomeRecentChats(ids: Set<Long>) {
        prefs.edit { if (ids.isEmpty()) it.remove(HOME_RECENT_CHATS) else it[HOME_RECENT_CHATS] = HomeLayout.encodeChats(ids) }
    }

    // ---- the support window ----------------------------------------------------------------

    /** What [SupportReminder] decides from. */
    val supportCounters: Flow<SupportReminder.Counters> = read(::supportCountersOf)

    suspend fun supportCountersNow(): SupportReminder.Counters = supportCountersOf(prefs.data.first())

    private fun supportCountersOf(prefs: Preferences) = SupportReminder.Counters(
        firstSeenAt = prefs[SUPPORT_FIRST_SEEN] ?: 0L,
        completedWatches = (prefs[SUPPORT_WATCHES] ?: 0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        watchTimeMs = prefs[SUPPORT_WATCH_MS] ?: 0L,
        asked = (prefs[SUPPORT_ASKED] ?: 0L).coerceIn(0L, SupportReminder.LADDER_ASKS.toLong()).toInt(),
        snoozedUntil = prefs[SUPPORT_SNOOZED_UNTIL] ?: 0L,
        neverAgain = prefs[SUPPORT_NEVER] ?: false,
        supporter = prefs[SUPPORT_SUPPORTER] ?: false,
        askedAt = prefs[SUPPORT_ASKED_AT] ?: 0L,
        windows = (prefs[SUPPORT_WINDOWS] ?: 0L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
        nudgedAt = prefs[SUPPORT_NUDGED_AT] ?: 0L,
        starAskedAt = prefs[SUPPORT_STAR_ASKED_AT] ?: 0L,
        starred = prefs[SUPPORT_STARRED] ?: false,
    )

    /**
     * The day the counting starts. Called on every launch; only the first call writes. An install
     * that predates the card starts counting from its first launch with it.
     */
    suspend fun noteSupportFirstSeen(now: Long) {
        prefs.edit { if ((it[SUPPORT_FIRST_SEEN] ?: 0L) <= 0L) it[SUPPORT_FIRST_SEEN] = now }
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
     * The window was shown at [now], whatever the viewer did with it: the next one waits its gap
     * (see [SupportReminder.snoozedUntil]).
     */
    suspend fun recordSupportAsk(now: Long) {
        prefs.edit {
            it[SUPPORT_ASKED_AT] = now
            it[SUPPORT_WINDOWS] = (it[SUPPORT_WINDOWS] ?: 0L) + 1
            it[SUPPORT_SNOOZED_UNTIL] = SupportReminder.snoozedUntil(now)
        }
    }

    /**
     * The notification went out at [now]: the "Don't forget to sponsor" one, or with [star] the
     * rung before it that asks only for a star.
     */
    suspend fun recordSupportNudge(now: Long, star: Boolean = false) {
        prefs.edit {
            it[SUPPORT_NUDGED_AT] = now
            if (star) it[SUPPORT_STAR_ASKED_AT] = now
        }
    }

    /** Star on GitHub was pressed, on the notification or the window: no star is asked for again. */
    suspend fun markSupportStarred() {
        prefs.edit { it[SUPPORT_STARRED] = true }
    }

    /** The old "I already support", or the last card's "Done": no more asks, and About says thank you. */
    suspend fun markSupporter() {
        prefs.edit { it[SUPPORT_SUPPORTER] = true }
    }

    /** "Don't show again" on the window: no more windows or notifications, and no thank you. */
    suspend fun markSupportNever() {
        prefs.edit { it[SUPPORT_NEVER] = true }
    }

    /**
     * "Sponsor reminders": the occasional notification. Off until the viewer turns it on, as
     * every notification but the finished downloads is (CP76).
     */
    val sponsorReminders: Flow<Boolean> = read { it[SPONSOR_REMINDERS] ?: Notice.Support.defaultOn }

    suspend fun sponsorRemindersNow(): Boolean = prefs.data.first()[SPONSOR_REMINDERS] ?: Notice.Support.defaultOn

    suspend fun setSponsorReminders(value: Boolean) {
        prefs.edit { it[SPONSOR_REMINDERS] = value }
    }

    // ---- new videos in starred chats --------------------------------------------------------

    /** "New videos in starred chats": off until the viewer turns it on. */
    val newVideoAlerts: Flow<Boolean> = read { it[NEW_VIDEO_ALERTS] ?: false }

    suspend fun newVideoAlertsNow(): Boolean = prefs.data.first()[NEW_VIDEO_ALERTS] ?: false

    /** Turning it on starts from what each chat has now: what was posted before is not news. */
    suspend fun setNewVideoAlerts(value: Boolean) {
        prefs.edit {
            it[NEW_VIDEO_ALERTS] = value
            it[NEW_VIDEO_OFFERED] = true
            if (value) it.remove(NEW_VIDEO_SEEN)
        }
    }

    /** Whether the one offer after a star has been made (or the switch was touched). */
    suspend fun newVideoOfferedNow(): Boolean = prefs.data.first()[NEW_VIDEO_OFFERED] ?: false

    suspend fun markNewVideoOffered() {
        prefs.edit { it[NEW_VIDEO_OFFERED] = true }
    }

    /** The newest video message id of each starred chat at the last check. See [NewVideoAlerts]. */
    suspend fun newVideoSeenNow(): Map<Long, Long> = NewVideoAlerts.decode(prefs.data.first()[NEW_VIDEO_SEEN])

    suspend fun setNewVideoSeen(seen: Map<Long, Long>) {
        prefs.edit { it[NEW_VIDEO_SEEN] = NewVideoAlerts.encode(seen) }
    }

    // ---- the notifications that help (CP76) -------------------------------------------------

    private fun noticeKey(notice: Notice): Preferences.Key<Boolean> = when (notice) {
        Notice.Downloads -> NOTIFY_DOWNLOADS
        Notice.NewEpisodes -> NOTIFY_EPISODES
        Notice.NewVideos -> NEW_VIDEO_ALERTS
        Notice.LowStorage -> NOTIFY_LOW_STORAGE
        Notice.MissingPieces -> NOTIFY_PIECES
        Notice.UpdateReady -> NOTIFY_UPDATE
        Notice.Support -> SPONSOR_REMINDERS
    }

    /** Whether [notice] may be posted: its own switch, at [Notice.defaultOn] until it is touched. */
    fun noticeOn(notice: Notice): Flow<Boolean> = read { it[noticeKey(notice)] ?: notice.defaultOn }

    suspend fun noticeOnNow(notice: Notice): Boolean = prefs.data.first()[noticeKey(notice)] ?: notice.defaultOn

    /**
     * Turns [notice] on or off. Turning one on that watches the chats starts from what they hold
     * now, so what was posted before is never news.
     */
    suspend fun setNoticeOn(notice: Notice, value: Boolean) {
        if (notice == Notice.NewVideos) return setNewVideoAlerts(value)
        prefs.edit {
            it[noticeKey(notice)] = value
            if (value && notice == Notice.NewEpisodes) it.remove(EPISODE_SEEN)
        }
    }

    /** The newest video message id of each chat the episode check read last time. */
    suspend fun episodeSeenNow(): Map<Long, Long> = NewVideoAlerts.decode(prefs.data.first()[EPISODE_SEEN])

    suspend fun setEpisodeSeen(seen: Map<Long, Long>) {
        prefs.edit { it[EPISODE_SEEN] = NewVideoAlerts.encode(seen) }
    }

    /** The episodes already announced ([EpisodeAlerts.toldKey]), newest first. */
    suspend fun episodeToldNow(): List<String> =
        prefs.data.first()[EPISODE_TOLD].orEmpty().split(';').filter { it.isNotBlank() }

    suspend fun setEpisodeTold(told: List<String>) {
        prefs.edit { it[EPISODE_TOLD] = told.joinToString(";") }
    }

    suspend fun lowStorageAlertNow(): StorageAlerts.State = StorageAlerts.State.decode(prefs.data.first()[LOW_STORAGE_ALERT])

    suspend fun setLowStorageAlert(state: StorageAlerts.State) {
        prefs.edit { it[LOW_STORAGE_ALERT] = state.encode() }
    }

    /** The cut videos the player found a piece missing from, for [PieceAlerts]. */
    suspend fun pieceGapsNow(): List<PieceAlerts.Gap> = PieceAlerts.decode(prefs.data.first()[PIECE_GAPS])

    suspend fun setPieceGaps(gaps: List<PieceAlerts.Gap>) {
        prefs.edit { it[PIECE_GAPS] = PieceAlerts.encode(gaps) }
    }

    /** The version the "update ready" notification last went out for, or blank. */
    suspend fun updateAlertedNow(): String = prefs.data.first()[UPDATE_ALERTED].orEmpty()

    suspend fun setUpdateAlerted(version: String) {
        prefs.edit { it[UPDATE_ALERTED] = version }
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

    /**
     * "Get test versions": the update check also offers GitHub pre-releases (vX.Y.Z-beta.N), the
     * builds that go out before each release for testing. Off by default; switched on only after
     * its warning.
     */
    val updateTestVersions: Flow<Boolean> = read { it[UPDATE_TEST_VERSIONS] ?: false }

    suspend fun setUpdateTestVersions(value: Boolean) {
        prefs.edit { it[UPDATE_TEST_VERSIONS] = value }
    }

    /**
     * "Install updates by itself" (HX5, Android 12 and later): a new version is fetched and
     * installed while TMPlayer is closed and idle, without Android's question. Off by default, and
     * about this device, so a restore never brings it along.
     */
    val updateAutoInstall: Flow<Boolean> = read { it[UPDATE_AUTO_INSTALL] ?: false }

    suspend fun updateAutoInstallNow(): Boolean = prefs.data.first()[UPDATE_AUTO_INSTALL] ?: false

    suspend fun setUpdateAutoInstall(value: Boolean) {
        prefs.edit { it[UPDATE_AUTO_INSTALL] = value }
    }

    /** The automatic install's last try, as [AutoInstall.Tried.encode] writes it; blank for none. */
    suspend fun updateAutoTriedNow(): String = prefs.data.first()[UPDATE_AUTO_TRIED].orEmpty()

    suspend fun setUpdateAutoTried(value: String) {
        prefs.edit { it[UPDATE_AUTO_TRIED] = value }
    }

    /**
     * The Android release (its API level) on which this device refused to update the app without
     * asking, so later updates ask straight away; 0 for never. A new Android release tries again.
     */
    suspend fun updateSilentRefusedNow(): Long = prefs.data.first()[UPDATE_SILENT_REFUSED] ?: 0L

    suspend fun setUpdateSilentRefused(apiLevel: Long) {
        prefs.edit { it[UPDATE_SILENT_REFUSED] = apiLevel }
    }

    /** The install handed to Android, as [UpdateRestart.Pending.encode] writes it; blank for none. */
    suspend fun updateInstallingNow(): String = prefs.data.first()[UPDATE_INSTALLING].orEmpty()

    suspend fun setUpdateInstalling(value: String) {
        prefs.edit { if (value.isEmpty()) it.remove(UPDATE_INSTALLING) else it[UPDATE_INSTALLING] = value }
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
        override suspend fun testVersions() = prefs.data.first()[UPDATE_TEST_VERSIONS] ?: false
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
     * How the picture was last fitted to the screen.
     *
     * Remembered for the same reason the speed is ([openingSpeed]): a viewer whose television overscans, or who
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
     * Whether the grid's one-time note on online metadata (posters and overviews are looked up by
     * each video's cleaned title) was put away, by Keep or by Turn off. A hint for this device, so
     * it is not in [Backups.SETTINGS] and a restore never brings it back.
     */
    val onlineMetaNoticeDismissed: Flow<Boolean> = read { it[ONLINE_META_NOTICE_DISMISSED] ?: false }

    suspend fun setOnlineMetaNoticeDismissed(dismissed: Boolean = true) {
        prefs.edit { it[ONLINE_META_NOTICE_DISMISSED] = dismissed }
    }

    /**
     * Whether the one-time invite to turn online metadata on was answered (see
     * [com.tmplayer.online.MetaNotice.shouldInvite]). A hint for this device, out of backups.
     */
    val onlineMetaInviteSeen: Flow<Boolean> = read { it[ONLINE_META_INVITE_SEEN] ?: false }

    /** Turn on or Not now: either way it is not asked again, and Turn on also retires the note. */
    suspend fun markOnlineMetaInviteSeen(turnedOn: Boolean) {
        prefs.edit {
            it[ONLINE_META_INVITE_SEEN] = true
            if (turnedOn) it[ONLINE_META_NOTICE_DISMISSED] = true
        }
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
            it[SUBTITLE_BOX_LEVEL] = style.boxLevel.name
            it[SUBTITLE_POSITION] = style.position.name
            it[SUBTITLE_COLOUR] = style.colour.name
            it[SUBTITLE_EDGE] = style.edge.name
            it[SUBTITLE_FACE] = style.face.name
            it[SUBTITLE_BOLD] = style.bold
        }
    }

    private fun subtitleStyleOf(prefs: Preferences) = SubtitleStyle.from(
        size = prefs[SUBTITLE_SIZE],
        box = prefs[SUBTITLE_BOX],
        boxLevel = prefs[SUBTITLE_BOX_LEVEL],
        position = prefs[SUBTITLE_POSITION],
        colour = prefs[SUBTITLE_COLOUR],
        edge = prefs[SUBTITLE_EDGE],
        face = prefs[SUBTITLE_FACE],
        bold = prefs[SUBTITLE_BOLD],
    )

    // ---- video decoder (Android) --------------------------------------------------------------

    val videoDecoder: Flow<VideoDecoder> = read { VideoDecoder.from(it[VIDEO_DECODER]) }

    suspend fun videoDecoderNow(): VideoDecoder = VideoDecoder.from(prefs.data.first()[VIDEO_DECODER])

    suspend fun setVideoDecoder(decoder: VideoDecoder) {
        prefs.edit { it[VIDEO_DECODER] = decoder.name }
    }

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
            // The name as the player had it, so Settings can list the series by it. Not for a
            // memory that says nothing: that one is not listed.
            if (choice.empty) prefs.remove(seriesNameKey(key)) else prefs[seriesNameKey(key)] = series.trim().take(120)
        }
    }

    /** Every series with remembered sound or subtitles, by name. See [LearntLanguages]. */
    val learntLanguages: Flow<List<LearntSeries>> = read { prefs ->
        LearntLanguages.from(prefs.asMap().mapKeys { it.key.name })
    }

    /** Forgets what one series was watched with; its next episode opens with the usual languages. */
    suspend fun forgetLearntLanguages(key: String) {
        prefs.edit { prefs ->
            LearntLanguages.keysOf(key).forEach { name ->
                prefs.asMap().keys.firstOrNull { it.name == name }?.let { prefs.remove(it) }
            }
        }
    }

    /** [forgetLearntLanguages] for every series at once. */
    suspend fun forgetAllLearntLanguages() {
        prefs.edit { prefs ->
            val prefixes = listOf(LearntLanguages.AUDIO, LearntLanguages.TEXT, LearntLanguages.SUBS, LearntLanguages.NAME)
            prefs.asMap().keys.filter { key -> prefixes.any { key.name.startsWith(it) } }.forEach { prefs.remove(it) }
        }
    }

    // ---- speed, per series, chat and topic ---------------------------------------------------

    /**
     * The speed a video opens at: [fileSpeed], its own from its resume point, else the one kept for
     * its series or course, else for its chat or topic, else 1x. See [SpeedMemory].
     */
    suspend fun openingSpeed(fileSpeed: Float?, scopes: SpeedMemory.Scopes): Float {
        val prefs = prefs.data.first()
        return SpeedMemory.resolve(
            file = fileSpeed,
            series = scopes.series?.let { prefs[speedKey(it)] },
            chat = scopes.chat?.let { prefs[speedKey(it)] },
        )
    }

    /**
     * Remembers [value] for the narrowest place the video belongs to. 1x forgets it there and in the
     * chat or topic underneath, so the next video of the same kind opens at 1x as well.
     */
    suspend fun rememberSpeed(scopes: SpeedMemory.Scopes, value: Float) {
        val keep = SpeedMemory.toKeep(value)
        prefs.edit { prefs ->
            if (keep == null) {
                scopes.all.forEach { prefs.remove(speedKey(it)) }
            } else {
                scopes.target?.let { prefs[speedKey(it)] = keep }
            }
        }
    }

    // ---- tracks, app-wide -------------------------------------------------------------------

    /**
     * The languages a video opens with when neither its own saved tracks nor its series say
     * otherwise. Read once when a video opens, so a change applies from the next one.
     */
    val trackPreferences: Flow<TrackPreferences> = read(::trackPreferencesOf)

    suspend fun trackPreferencesNow(): TrackPreferences = trackPreferencesOf(prefs.data.first())

    suspend fun setTrackPreferences(value: TrackPreferences) {
        prefs.edit {
            value.audioLanguage?.let { code -> it[PREFERRED_AUDIO] = code } ?: it.remove(PREFERRED_AUDIO)
            value.subtitleKey.ifEmpty { null }?.let { key -> it[PREFERRED_SUBTITLE] = key } ?: it.remove(PREFERRED_SUBTITLE)
            it[SUBTITLE_MODE] = value.mode.key
        }
    }

    private fun trackPreferencesOf(prefs: Preferences) =
        TrackPreferences.from(prefs[PREFERRED_AUDIO], prefs[PREFERRED_SUBTITLE], prefs[SUBTITLE_MODE])

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
     * What was searched for lately, newest first, at most [RecentSearches.LIMIT]. One list for
     * every search box: a chat's own and the search across every chat. A viewer looking for a show
     * looks for it wherever it was posted.
     */
    val recentSearches: Flow<List<String>> = read { RecentSearches.decode(it[RECENT_SEARCHES]) }

    /** Whether searches are kept at all. */
    val rememberSearches: Flow<Boolean> = read { it[REMEMBER_SEARCHES] ?: true }

    /** Turning it off forgets what is kept, which is what somebody switching it off wants. */
    suspend fun setRememberSearches(value: Boolean) {
        prefs.edit {
            it[REMEMBER_SEARCHES] = value
            if (!value) it.remove(RECENT_SEARCHES)
        }
    }

    suspend fun addRecentSearch(query: String) {
        prefs.edit { prefs ->
            if (prefs[REMEMBER_SEARCHES] == false) return@edit
            val before = RecentSearches.decode(prefs[RECENT_SEARCHES])
            val after = RecentSearches.add(before, query)
            if (after != before) prefs[RECENT_SEARCHES] = RecentSearches.encode(after)
        }
    }

    suspend fun removeRecentSearch(query: String) {
        prefs.edit { prefs ->
            val after = RecentSearches.remove(RecentSearches.decode(prefs[RECENT_SEARCHES]), query)
            if (after.isEmpty()) prefs.remove(RECENT_SEARCHES) else prefs[RECENT_SEARCHES] = RecentSearches.encode(after)
        }
    }

    /** Puts back a list Clear all took, for its Undo. */
    suspend fun restoreRecentSearches(searches: List<String>) {
        prefs.edit { it[RECENT_SEARCHES] = RecentSearches.encode(searches.take(RecentSearches.LIMIT)) }
    }

    suspend fun clearRecentSearches() {
        prefs.edit { it.remove(RECENT_SEARCHES) }
    }

    // ---- card layout ------------------------------------------------------------------------

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

    // ---- "Only my folders", retired ----------------------------------------------------------

    /**
     * Whether "Only my folders" was on, read and forgotten in one write. The option hid Chats and
     * Saved Messages so the sidebar listed the account's Telegram folders; the Chats page now
     * carries Telegram's own folder tabs as chips, so somebody who had it on is shown their first
     * folder's chip once, and nothing is hidden. Called once the folders are known.
     */
    suspend fun takeOnlyFolders(): Boolean {
        var was = false
        prefs.edit { prefs ->
            was = prefs[HIDE_DEFAULT_GROUPS] == true
            prefs.remove(HIDE_DEFAULT_GROUPS)
        }
        return was
    }

    /** The retired option as an older version stored it, for the migration's test. */
    internal suspend fun storeOnlyFoldersForTest(on: Boolean) {
        prefs.edit { it[HIDE_DEFAULT_GROUPS] = on }
    }

    // ---- the app lock and the kids lock (CP57) ------------------------------------------------

    /** Everything the lock rows in Settings show, in one read. */
    val appLock: Flow<AppLockSettings> = read { prefs ->
        val pinSet = prefs[APP_PIN] != null
        AppLockSettings(
            pinSet = pinSet,
            onOpen = pinSet && prefs[LOCK_ON_OPEN] == true,
            kids = KidsLock.inForce(prefs[KIDS_LOCK] == true, pinSet),
            kidsChats = kidsChatsOf(prefs),
        )
    }

    /**
     * The chats the kids lock allows, or null while it is off: what [ChatGate] follows and every
     * list of chats is filtered by.
     */
    override val kidsAllowedChats: Flow<Set<Long>?> = read(::allowedChatsOf)

    /** Whether a PIN is set, now. */
    suspend fun pinSetNow(): Boolean = prefs.data.first()[APP_PIN] != null

    /** The wrong guesses so far and any wait they earned, now. */
    suspend fun pinAttemptsNow(now: Long = PinClock.now()): PinAttempts = attemptsOf(prefs.data.first(), now)

    /** Sets (or changes) the PIN, and forgets any wrong guesses made against the old one. */
    suspend fun setPin(pin: String) {
        // Twenty thousand rounds: off the caller, which is often the UI thread.
        val line = kotlinx.coroutines.withContext(Dispatchers.Default) { PinHash.hash(pin) }
        prefs.edit {
            it[APP_PIN] = line
            it.remove(PIN_FAILURES)
            it.remove(PIN_WAIT_LEFT)
            it.remove(PIN_WAIT_STAMP)
        }
    }

    /**
     * Removes the PIN, and with it everything the PIN guards: the lock on opening and the kids
     * lock. The chosen chats are kept, so setting a PIN and the lock again starts from them.
     */
    suspend fun removePin() {
        prefs.edit {
            it.remove(APP_PIN)
            it.remove(PIN_FAILURES)
            it.remove(PIN_WAIT_LEFT)
            it.remove(PIN_WAIT_STAMP)
            it.remove(LOCK_ON_OPEN)
            it.remove(KIDS_LOCK)
        }
    }

    /**
     * Checks [pin] against the stored one at [PinClock] time [now], counting a wrong one and refusing
     * to look at all while an earlier run of wrong ones is still waiting out its [PinBackoff].
     * The verdict and the count are written in one edit, so two pads cannot both get a free try.
     */
    suspend fun checkPin(pin: String, now: Long = PinClock.now()): PinCheck {
        var verdict: PinCheck = PinCheck.NoPin
        prefs.edit { prefs ->
            val stored = prefs[APP_PIN] ?: return@edit
            val attempts = attemptsOf(prefs, now)
            val wait = attempts.waitMs(now)
            verdict = when {
                wait > 0 -> PinCheck.Waiting(wait)
                PinHash.verify(pin, stored) -> {
                    prefs.remove(PIN_FAILURES)
                    prefs.remove(PIN_WAIT_LEFT)
                    prefs.remove(PIN_WAIT_STAMP)
                    PinCheck.Right
                }
                else -> {
                    val next = attempts.failed(now)
                    prefs[PIN_FAILURES] = next.failures.toLong()
                    prefs[PIN_WAIT_LEFT] = next.waitMs(now)
                    prefs[PIN_WAIT_STAMP] = now
                    PinCheck.Wrong(next)
                }
            }
        }
        return verdict
    }

    /** "Ask when the app opens". Ignored without a PIN. */
    suspend fun setLockOnOpen(on: Boolean) {
        prefs.edit { it[LOCK_ON_OPEN] = on }
    }

    /** The kids lock's switch. Ignored without a PIN ([KidsLock.inForce]). */
    suspend fun setKidsLock(on: Boolean) {
        prefs.edit { it[KIDS_LOCK] = on }
    }

    /** The chats the kids lock allows, replacing the last choice whole. */
    suspend fun setKidsChats(chats: Set<Long>) {
        prefs.edit { it[KIDS_CHATS] = chats.map(Long::toString).toSet() }
    }

    /**
     * Forgets the PIN and both locks, chosen chats included. Called when a sign in screen is up:
     * an account signed out from another device (Telegram's Devices list, the way back from a
     * forgotten PIN) never passes through this app's own sign out, which clears them anyway.
     */
    suspend fun clearAppLock() {
        prefs.edit {
            it.remove(APP_PIN)
            it.remove(PIN_FAILURES)
            it.remove(PIN_WAIT_LEFT)
            it.remove(PIN_WAIT_STAMP)
            it.remove(LOCK_ON_OPEN)
            it.remove(KIDS_LOCK)
            it.remove(KIDS_CHATS)
        }
    }

    /**
     * The short setup after signing in (CP67): library, posters and storage, one choice a step.
     * Armed by a sign in screen ([armSetup]), so an install that was already signed in when this
     * arrived, and had gone through the old tour, is never walked through it; a fresh sign in,
     * including one after signing out, is. True while it waits to be shown or is on screen.
     */
    val setupPending: Flow<Boolean> = read { it[SETUP] == SETUP_PENDING }

    /** A sign in screen is up: the setup comes once the account is in. */
    suspend fun armSetup() {
        prefs.edit { if (it[SETUP] != SETUP_PENDING) it[SETUP] = SETUP_PENDING }
    }

    /**
     * Finished, or skipped: not shown again until the next sign in or [replaySetup]. The quick
     * tour comes at the end of the setup, so it is done with it, and never offered on its own.
     */
    suspend fun markSetupDone() {
        prefs.edit {
            it[SETUP] = SETUP_DONE
            it[TOUR] = TOUR_DONE
        }
    }

    /** Settings' "Run the setup again". */
    suspend fun replaySetup() {
        prefs.edit { it[SETUP] = SETUP_PENDING }
    }

    /**
     * The quick tour (CP69): [TourState.Never] until it has been shown or offered, [TourState.Pending]
     * while it waits to be shown on its own, [TourState.Done] after. A fresh sign in shows it after
     * the setup and marks it done with [markSetupDone]; an install updated from a version without
     * it is offered it once, which is what [TourState.Never] is there to tell.
     */
    val tourState: Flow<TourState> = read {
        when (it[TOUR]) {
            TOUR_PENDING -> TourState.Pending
            TOUR_DONE -> TourState.Done
            else -> TourState.Never
        }
    }

    /** The tour, on its own: taken from the offer, or opened from Settings. */
    suspend fun openTour() {
        prefs.edit { it[TOUR] = TOUR_PENDING }
    }

    /** Finished, skipped, or the offer turned down: not shown again unless Settings opens it. */
    suspend fun markTourDone() {
        prefs.edit { it[TOUR] = TOUR_DONE }
    }

    enum class TourState { Never, Pending, Done }

    /** Which tab of History was last open, `HistoryTab` by name. */
    val historyTab: Flow<String?> = read { it[HISTORY_TAB] }

    suspend fun setHistoryTab(name: String) {
        prefs.edit { it[HISTORY_TAB] = name }
    }

    /** The order of History's lists (Continue watching, Watch later, Watched), `ChatSort` by name: most recent first or by title. */
    val historySort: Flow<String?> = read { it[HISTORY_SORT] }

    suspend fun setHistorySort(name: String) {
        prefs.edit { it[HISTORY_SORT] = name }
    }

    /** The Downloads page's order, once for the page. See [DownloadShelf.Sort]. */
    val downloadsSort: Flow<DownloadShelf.Sort> = read { DownloadShelf.Sort.decode(it[DOWNLOADS_SORT]) }

    suspend fun setDownloadsSort(sort: DownloadShelf.Sort) {
        prefs.edit { it[DOWNLOADS_SORT] = sort.name }
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
        prefs.edit {
            settleLegacyBlack(it)
            it[THEME_CHOICE] = value.name
        }
    }

    /**
     * The Pure black switch: dark on true black, for an OLED panel, wherever the app is dark (Dark,
     * or System on a dark device; see [pureBlackGround]). Off by default.
     *
     * Black was a fourth theme until 2026-10-19, stored as [ThemeChoice.LEGACY_BLACK]. That name
     * still reads as Dark with this switch on, whether it was kept on the device or arrives in an
     * old backup, and the first write to either setting rewrites it as the two settings it now is.
     */
    val pureBlack: Flow<Boolean> =
        read { it[THEME_CHOICE] == ThemeChoice.LEGACY_BLACK || it[PURE_BLACK] == true }

    suspend fun setPureBlack(value: Boolean) {
        prefs.edit {
            settleLegacyBlack(it)
            it[PURE_BLACK] = value
        }
    }

    /** The accent colour; see [Accent]. Telegram blue until one is picked. */
    val accent: Flow<Accent> = read { Accent.from(it[ACCENT]) }

    suspend fun setAccent(value: Accent) {
        prefs.edit { it[ACCENT] = value.name }
    }

    /** The viewer's own accent colour as ARGB, or null before one was picked; see [Accent.Custom]. */
    val customAccent: Flow<Int?> = read { it[ACCENT_CUSTOM]?.toInt() }

    /** Picks [argb] as the accent, and makes it the one in use. */
    suspend fun setCustomAccent(argb: Int) {
        prefs.edit {
            it[ACCENT_CUSTOM] = argb.toLong() and 0xFFFFFFFFL
            it[ACCENT] = Accent.Custom.name
        }
    }

    /** The typeface every screen is set in; see [AppTypeface]. */
    val typeface: Flow<AppTypeface> = read { AppTypeface.from(it[APP_TYPEFACE]) }

    suspend fun setTypeface(value: AppTypeface) {
        prefs.edit { it[APP_TYPEFACE] = value.name }
    }

    /** Second lines and edges brought forward. */
    val highContrast: Flow<Boolean> = read { it[HIGH_CONTRAST] ?: false }

    suspend fun setHighContrast(value: Boolean) {
        prefs.edit { it[HIGH_CONTRAST] = value }
    }

    /**
     * "Back to the defaults" on the Appearance page: the theme, Pure black, the accent and the
     * viewer's own colour, the wallpaper's colours, the interface size, the typeface and the
     * contrast as a fresh install has them. Hands back what they were, for Undo.
     */
    suspend fun resetAppearance(): AppearanceSnapshot {
        var before: AppearanceSnapshot? = null
        prefs.edit { p ->
            before = AppearanceSnapshot(
                theme = p[THEME_CHOICE], pureBlack = p[PURE_BLACK], accent = p[ACCENT], customAccent = p[ACCENT_CUSTOM]?.toInt(),
                dynamic = p[DYNAMIC_COLOUR], uiScale = p[UI_SCALE], typeface = p[APP_TYPEFACE], highContrast = p[HIGH_CONTRAST],
            )
            listOf(THEME_CHOICE, ACCENT, UI_SCALE, APP_TYPEFACE).forEach { p.remove(it) }
            listOf(PURE_BLACK, DYNAMIC_COLOUR, HIGH_CONTRAST).forEach { p.remove(it) }
            p.remove(ACCENT_CUSTOM)
        }
        return before!!
    }

    /** Puts back what [resetAppearance] took away. */
    suspend fun restoreAppearance(snapshot: AppearanceSnapshot) {
        prefs.edit { p ->
            fun <T> put(key: Preferences.Key<T>, value: T?) { if (value == null) p.remove(key) else p[key] = value }
            put(THEME_CHOICE, snapshot.theme)
            put(PURE_BLACK, snapshot.pureBlack)
            put(ACCENT, snapshot.accent)
            put(ACCENT_CUSTOM, snapshot.customAccent?.let { it.toLong() and 0xFFFFFFFFL })
            put(DYNAMIC_COLOUR, snapshot.dynamic)
            put(UI_SCALE, snapshot.uiScale)
            put(APP_TYPEFACE, snapshot.typeface)
            put(HIGH_CONTRAST, snapshot.highContrast)
        }
    }

    /** A stored Black, rewritten as Dark with the Pure black switch on. */
    private fun settleLegacyBlack(prefs: MutablePreferences) {
        if (prefs[THEME_CHOICE] == ThemeChoice.LEGACY_BLACK) {
            prefs[THEME_CHOICE] = ThemeChoice.Dark.name
            prefs[PURE_BLACK] = true
        }
    }

    /**
     * The interface size for a viewer who never chose one: [UiScale.Default] on a phone and a
     * television. The desktop sets it from the screen before its first window ([UiScale.forScreen]),
     * since a maximized window at the phone's size is mostly empty space.
     */
    @Volatile
    var unchosenUiScale: UiScale = UiScale.Default

    /** How large the interface is drawn, on every device; see [UiScale]. */
    val uiScale: Flow<UiScale> = read { UiScale.from(it[UI_SCALE], unchosenUiScale) }

    /**
     * Keeps [value] as the viewer's own choice. Default is only left unwritten where it is also
     * what an unchosen size is, so choosing Default on the desktop sticks.
     */
    suspend fun setUiScale(value: UiScale) {
        prefs.edit { if (value == UiScale.Default && unchosenUiScale == UiScale.Default) it.remove(UI_SCALE) else it[UI_SCALE] = value.name }
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

    // ---- resume -----------------------------------------------------------------------------

    suspend fun resumePosition(chatId: Long, messageId: Long): Long =
        prefs.data.first()[resumeKey(chatId, messageId)] ?: 0L

    /**
     * Where [item] was left, whichever copy of it was playing: its own record, or the newest of its
     * copies' ([VideoCopies.resumeOf]). The record keeps the ids of the copy it was written for, so
     * a caller can tell a place carried over from another file from one of its own.
     */
    suspend fun resumeRecordFor(item: MediaItem): ResumeRecord? =
        VideoCopies.resumeOf(item, continueWatching.first())
            ?: resumeRecord(item.chatId, item.messageId)

    /** [resumeStart] for a video whose place may have been written on another copy of it. */
    suspend fun resumeStartFor(item: MediaItem): Long =
        ResumeRewind.start(resumeRecordFor(item)?.positionMs ?: resumePosition(item.chatId, item.messageId), resumeRewindNow())

    // ---- copies -----------------------------------------------------------------------------

    /** The qualities the viewer has played, for choosing a copy: see [VideoCopies.pick]. */
    val qualityHabit: Flow<QualityHabit> = read { QualityHabit.decode(it[QUALITY_HABIT]) }

    suspend fun qualityHabitNow(): QualityHabit = QualityHabit.decode(prefs.data.first()[QUALITY_HABIT])

    /** Counts one play of [item]'s quality towards the habit. */
    suspend fun notePlayedQuality(item: MediaItem) {
        val height = VideoCopies.heightOf(item) ?: return
        prefs.edit { it[QUALITY_HABIT] = QualityHabit.decode(it[QUALITY_HABIT]).noted(height).encode() }
    }

    /** The copy chosen for [item]'s video, by [MediaItem.id], or null. */
    suspend fun copyPickFor(item: MediaItem): String? {
        val key = VideoCopies.keyOf(item) ?: return null
        return prefs.data.first()[copyPickKey(key)]
    }

    /** Every chosen copy at once, video key to [MediaItem.id], for pages that list many. */
    val copyPicks: Flow<Map<String, String>> = read { prefs ->
        buildMap {
            for ((key, value) in prefs.asMap()) {
                val name = key.name.takeIf { it.startsWith("copy_pick_") } ?: continue
                put(name.removePrefix("copy_pick_"), value as? String ?: continue)
            }
        }
    }

    /**
     * The copy of a film posted more than once to play without asking: [VideoCopies.pick] over
     * [item]'s versions with this install's pick, places and habit. [item] itself when it has no
     * other copies. The answer carries the same versions, so the page and the player can still
     * list them.
     */
    suspend fun copyToPlay(item: MediaItem): MediaItem {
        // Never a copy in a chat the kids lock hides.
        val copies = VideoCopies.visible(item.versions).takeIf { it.size > 1 } ?: return item
        val started = continueWatching.first().mapTo(HashSet()) { progressKey(it.chatId, it.messageId) }
        val picked = VideoCopies.pick(
            copies,
            chosen = copyPickFor(item),
            started = { progressKey(it.chatId, it.messageId) in started },
            habit = qualityHabitNow(),
            language = com.tmplayer.player.TrackLanguages.normalise(trackPreferencesNow().audioLanguage),
            tooSlow = VideoCopies.tooSlowHere,
        ) ?: return item
        return picked.asVersion(item)
    }

    /**
     * The copy [copyToPlay] passed over because this device would decode it too slowly, when it
     * did: what the player names in its "Playing the 720p copy" line. Null otherwise.
     */
    suspend fun copyPassedOver(item: MediaItem): MediaItem? {
        val copies = VideoCopies.visible(item.versions).takeIf { it.size > 1 } ?: return null
        val started = continueWatching.first().mapTo(HashSet()) { progressKey(it.chatId, it.messageId) }
        return VideoCopies.passedOver(
            copies,
            chosen = copyPickFor(item),
            started = { progressKey(it.chatId, it.messageId) in started },
            habit = qualityHabitNow(),
            language = com.tmplayer.player.TrackLanguages.normalise(trackPreferencesNow().audioLanguage),
            tooSlow = VideoCopies.tooSlowHere,
        )
    }

    /** What this device remembers of the pictures it played in software ([DecodeMemory]). */
    suspend fun decodeMemoryNow(): Map<String, Boolean> = DecodeMemory.decode(prefs.data.first()[DECODE_MEMORY])

    /** The kinds of picture the "may stutter" question is no longer asked for. */
    suspend fun heavyAskSkipsNow(): Set<String> =
        prefs.data.first()[HEAVY_ASK_SKIP]?.split(';')?.filter { it.isNotBlank() }?.toSet().orEmpty()

    /** "Don't ask again for this kind of video": [kind] is a [DecodeMemory.keyOf]. */
    suspend fun skipHeavyAsk(kind: String) {
        prefs.edit {
            val now = it[HEAVY_ASK_SKIP]?.split(';')?.filter { k -> k.isNotBlank() }.orEmpty()
            it[HEAVY_ASK_SKIP] = (now - kind + kind).takeLast(DecodeMemory.MAX).joinToString(";")
        }
    }

    /** Notes whether a picture of [picture]'s kind kept up here. */
    suspend fun noteDecode(picture: DecodeJudge.Picture, keptUp: Boolean) {
        prefs.edit { it[DECODE_MEMORY] = DecodeMemory.encode(DecodeMemory.noted(DecodeMemory.decode(it[DECODE_MEMORY]), picture, keptUp)) }
    }

    /**
     * Remembers [copy] as the one to play for its video, from the copies list or the player's
     * quality button, so the next open plays it and the next episode is chosen like it.
     */
    suspend fun setCopyPick(copy: MediaItem) {
        val key = VideoCopies.keyOf(copy) ?: return
        prefs.edit { it[copyPickKey(key)] = copy.id }
    }

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
        // The kids lock reads its chats off the same map, so the row can never be drawn for a
        // moment with the lock and the records out of step.
        val allowed = allowedChatsOf(prefs)
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
                if (KidsLock.allows(allowed, record.chatId)) add(record)
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
            // Still inside the few seconds the resume rewound: the place saved before stands, or
            // opening and closing a video would walk it back by the rewind every time.
            val positionMs = ResumeRewind.kept(prefs[key], positionMs, ResumeRewind.sanitise(prefs[RESUME_REWIND]))
            // In the first three minutes there is nothing new worth resuming, but a place saved
            // before stands: reopening a film and closing it again, or a beat that lands before
            // the resume seek, must not wipe it. Starting over clears it on purpose instead.
            if (positionMs < ResumeRules.MIN_POSITION_MS) return@edit
            // In the credits: there is nothing worth resuming.
            if (!ResumeRules.keeps(positionMs, durationMs)) {
                val played = recordOf(prefs, chatId, messageId, description)?.toMediaItem()
                prefs.remove(key)
                prefs.remove(durationKey(chatId, messageId))
                prefs.remove(metaKey(chatId, messageId))
                // Finished in this copy is finished in all of them: an older place in another copy
                // would otherwise come back to Continue watching in its stead.
                if (played != null) forgetCopies(prefs, played)
            } else {
                // Only this copy's own line is written. The other copies keep theirs, and the
                // newest of them is the one read (VideoCopies.resumeOf, HomeRows.foldCopies).
                prefs[key] = positionMs
                if (durationMs > 0) prefs[durationKey(chatId, messageId)] = durationMs
                if (description != null) prefs[metaKey(chatId, messageId)] = description
                evictOldestHistory(prefs)
            }
        }
    }

    /** The record [description] describes for the ids, else the one stored for them, or null. */
    private fun recordOf(prefs: Preferences, chatId: Long, messageId: Long, description: String?): ResumeRecord? {
        val ids = progressKey(chatId, messageId)
        return description?.let { ResumeRecord.decode(ids, it, 0L, 0L) }
            ?: ResumeRecord.decode(ids, prefs[metaKey(chatId, messageId)], 0L, 0L)
    }

    /**
     * Removes the places of every other copy of [played], in this chat or any other: whatever
     * [VideoCopies.sameVideo] calls the same video. The copy's own line is the caller's to remove.
     */
    private fun forgetCopies(prefs: MutablePreferences, played: MediaItem) {
        val copies = prefs.asMap().keys.mapNotNull { key ->
            val ids = key.name.removePrefixOrNull("meta_") ?: return@mapNotNull null
            ResumeRecord.decode(ids, prefs[stringPreferencesKey(key.name)], 0L, 0L)
        }.filter { it.messageId != played.messageId || it.chatId != played.chatId }
            .filter { VideoCopies.sameVideo(played, it) }
        for (copy in copies) {
            prefs.remove(resumeKey(copy.chatId, copy.messageId))
            prefs.remove(durationKey(copy.chatId, copy.messageId))
            prefs.remove(metaKey(copy.chatId, copy.messageId))
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

    // ---- backup -----------------------------------------------------------------------------

    /** Every stored value under its stored name, for [Backups.collect] and [Backups.plan]. */
    suspend fun rawEntries(): Map<String, Any> = prefs.data.first().asMap().mapKeys { it.key.name }

    /**
     * Writes what [writes] returns for the store as it is at that moment, in one edit, so nothing
     * written between reading the file and confirming the restore is overwritten by a stale merge.
     * The history cap is applied after, as after any other resume point.
     */
    suspend fun restoreEntries(writes: (Map<String, Any>) -> Map<String, Any>) {
        prefs.edit { prefs ->
            val now = prefs.asMap().mapKeys { it.key.name }
            for ((name, value) in writes(now)) prefs.putRaw(name, value)
            evictOldestHistory(prefs)
        }
    }

    /**
     * Forgets where one video was left: this copy's place, and the place of any other copy of the
     * same video ([VideoCopies.sameVideo]), so Start over or Mark as watched on one copy does not
     * leave another's older place to resume from. [item] names the video when this copy has no
     * place of its own to read it from (Start over on a copy never played).
     */
    suspend fun clearResumePosition(chatId: Long, messageId: Long, item: MediaItem? = null) {
        prefs.edit {
            val played = item ?: recordOf(it, chatId, messageId, null)?.toMediaItem()
            it.remove(resumeKey(chatId, messageId))
            it.remove(durationKey(chatId, messageId))
            it.remove(metaKey(chatId, messageId))
            if (played != null) forgetCopies(it, played)
        }
    }

    /**
     * "Remove from Continue watching" for a card that may stand for several copies of one video
     * ([HomeRows.foldCopies]): every copy goes, in one edit, so none comes up in its place.
     */
    suspend fun clearResumeCopies(record: ResumeRecord) {
        val copies = HomeRows.copiesOf(record, continueWatching.first())
        prefs.edit {
            for (copy in copies) {
                it.remove(resumeKey(copy.chatId, copy.messageId))
                it.remove(durationKey(copy.chatId, copy.messageId))
                it.remove(metaKey(copy.chatId, copy.messageId))
            }
        }
    }

    /**
     * Remove from history, for the places of [ids] ([progressKey]s): every line of each (the
     * place, the length, the description and the sync delays) taken out in one edit and handed
     * back as stored, so Undo ([putBackResume]) writes exactly them again. An id with nothing
     * stored is skipped, so removing the same card twice takes nothing the second time.
     */
    suspend fun takeResume(ids: Collection<String>): Map<String, Any> {
        if (ids.isEmpty()) return emptyMap()
        val wanted = ids.toHashSet()
        val taken = LinkedHashMap<String, Any>()
        prefs.edit { prefs ->
            // Collected before anything is removed: the map cannot be walked while it is written.
            val doomed = prefs.asMap().entries.filter { (key, _) ->
                val ids = RESUME_LINES.firstNotNullOfOrNull { key.name.removePrefixOrNull(it) }
                ids != null && ids in wanted
            }
            for ((key, value) in doomed) {
                taken[key.name] = value
                prefs.remove(key)
            }
        }
        return taken
    }

    /**
     * Undo for [takeResume]: the lines put back as they were, the card's place and its spot in
     * the order included. A video played again since keeps its newer place: an older one written
     * over it would move the viewer back. The history cap applies after, as after any place.
     */
    suspend fun putBackResume(taken: Map<String, Any>) {
        if (taken.isEmpty()) return
        prefs.edit { prefs ->
            val byIds = taken.entries.groupBy { (name, _) -> RESUME_LINES.firstNotNullOfOrNull { name.removePrefixOrNull(it) }.orEmpty() }
            for ((ids, lines) in byIds) {
                if (ids.isEmpty()) continue
                val then = ResumeRecord.updatedAtOf(taken["meta_$ids"] as? String) ?: Long.MIN_VALUE
                val now = prefs[stringPreferencesKey("meta_$ids")]?.let(ResumeRecord::updatedAtOf)
                if (prefs[longPreferencesKey("resume_$ids")] != null && now != null && now > then) continue
                for ((name, value) in lines) prefs.putRaw(name, value)
            }
            evictOldestHistory(prefs)
        }
    }

    companion object {
        /** What one video's place is kept in, each followed by its [progressKey]. */
        private val RESUME_LINES = listOf("resume_", "duration_", "meta_", "delay_")

        /**
         * The file name Android's `preferencesDataStore("tmplayer")` delegate has always used, in
         * `filesDir/datastore/`. Keeping it is what keeps an upgraded install's settings.
         */
        const val FILE_NAME = "tmplayer.preferences_pb"

        /** Opens the store at [file]. Call once per process and share the result. */
        fun openDataStore(file: File): DataStore<Preferences> = openSafeDataStore(file)

        /** Whether launch should skip the chat list, given what is remembered and the setting. */
        fun autoOpenChatId(lastChatId: Long, enabled: Boolean): Long? =
            if (enabled && lastChatId != 0L) lastChatId else null

        /**
         * How many half-watched videos are kept before the oldest start dropping off.
         *
         * The resume history only. Downloads are not capped: see [noteDownload].
         */
        const val MAX_HISTORY = 200

        fun progressKey(chatId: Long, messageId: Long) = ProgressKeys.of(chatId, messageId)
    }
}

