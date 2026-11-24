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
import kotlinx.coroutines.withContext
import okio.Path.Companion.toPath
import java.io.File

private val FAVORITES = stringSetPreferencesKey("favorite_chats")
// The retired hidden chats (AddedChats.HIDDEN_KEY): read once to fold them into the added chats, then deleted.
private val HIDDEN_CHATS = stringPreferencesKey("hidden_chats")
private val ADDED_CHATS = stringPreferencesKey("added_chats") // AddedChats.KEY
private val ADDED_CHATS_MODE = stringPreferencesKey("added_chats_mode") // AddedChats.MODE_KEY
private val PLAYER_HINT_SEEN = booleanPreferencesKey("player_hint_seen")
private val FIRST_VIDEO_TIP_DISMISSED = booleanPreferencesKey("first_video_tip_dismissed")
private val TV_BROWSE_HINT_SEEN = booleanPreferencesKey("tv_browse_hint_seen")
private val ONLINE_META_NOTICE_DISMISSED = booleanPreferencesKey("online_meta_notice_dismissed")
private val ONLINE_META_INVITE_SEEN = booleanPreferencesKey("online_meta_invite_seen")
private val OPEN_LAST_CHAT = booleanPreferencesKey("open_last_chat")
private val DOWNLOAD_FIRST = booleanPreferencesKey("download_before_playing")
private val AUTOPLAY_NEXT = booleanPreferencesKey("autoplay_next")
private val DETAIL_FIRST = booleanPreferencesKey("detail_first")
private val SERIES_ONLY_MINE = booleanPreferencesKey("series_only_mine")
private val COMPACT_NAV = booleanPreferencesKey("compact_nav")
private val DOWNMIX_STEREO = booleanPreferencesKey("downmix_stereo")
private val VOLUME_BOOST = booleanPreferencesKey("volume_boost")
private val NIGHT_MODE = booleanPreferencesKey("night_mode")
private val EVEN_VOLUME = booleanPreferencesKey("even_volume")
private val PLAYER_VOLUME = longPreferencesKey("player_volume")

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
// Resume on other devices' file (U7): which message it is, what this device held after its last
// sync, whose account that was, when a new file was last sent, and what the last try came to.
private val RESUME_SYNC_FILE = longPreferencesKey("resume_sync_file")
private val RESUME_SYNC_FAVOURITES = stringPreferencesKey("resume_sync_favourites")
private val RESUME_SYNC_AT = longPreferencesKey("resume_sync_at")
private val RESUME_SYNC_ACCOUNT = longPreferencesKey("resume_sync_account")
private val RESUME_SYNC_SENT_AT = longPreferencesKey("resume_sync_sent_at")
private val RESUME_SYNC_STATUS = stringPreferencesKey("resume_sync_status")
// HX87, where the sync file and the backups are kept: the private channel the viewer chose (0 is
// Saved Messages), its title, whose account chose it, why it is not used right now, which chat the
// remembered file message and the last new file belong to, and where an older copy was left.
private val SYNC_CHANNEL = longPreferencesKey("sync_channel")
private val SYNC_CHANNEL_TITLE = stringPreferencesKey("sync_channel_title")
private val SYNC_CHANNEL_ACCOUNT = longPreferencesKey("sync_channel_account")
private val SYNC_CHANNEL_BLOCK = stringPreferencesKey("sync_channel_block")
private val SYNC_FILE_CHAT = longPreferencesKey("sync_file_chat")
private val SYNC_SENT_CHAT = longPreferencesKey("sync_sent_chat")
private val SYNC_OLD_COPY = stringPreferencesKey("sync_old_copy")
// "Keep my place on all my devices": the Home card that asks once (answered or left), and when this
// device last wrote the file, so a restart does not start the few minutes' pause over.
private val KEEP_PLACE_CARD_SEEN = booleanPreferencesKey("keep_place_card_seen")
private val KEEP_PLACE_WROTE_AT = longPreferencesKey("keep_place_wrote_at")
private val SKIP_SILENCE = booleanPreferencesKey("skip_silence")
private val BACKGROUND_AUDIO = booleanPreferencesKey("background_audio")
private val PIP_ON_LEAVE = booleanPreferencesKey("pip_on_leave")
/** The phone's in-app mini player switch, retired (T12): never read, removed by [SettingsStore.dropRetiredMiniPlayer]. */
private val MINI_PLAYER = booleanPreferencesKey("mini_player")
// Not "resume_...": that prefix is a resume point's, and the sweeps over resume points (the
// broken-history prune at launch, Clear history) would take this setting with them.
private val RESUME_REWIND = longPreferencesKey("rewind_on_resume_ms")
private val WIFI_ONLY = booleanPreferencesKey("wifi_only_downloads")
// No longer read (always on, R2): the name stays so a backup that carries it still restores and the coverage test knows it.
@Suppress("unused")
private val PAUSE_DOWNLOADS_WHILE_PLAYING = booleanPreferencesKey("pause_downloads_while_playing")
private val SAVE_MOBILE_DATA = booleanPreferencesKey("save_mobile_data")
// "On mobile data: Ask first, Never, Allow" (R2): one word that replaced the two switches above for
// anyone who has chosen; before that [MobileData.resolve] reads the two booleans.
private val MOBILE_DATA = stringPreferencesKey("mobile_data")
private val REMOVE_AFTER_WATCHING = booleanPreferencesKey("remove_after_watching")
// Smart downloads (WP-83): the shows that keep their next episodes, `chatId|showKey` a line.
private val KEEP_NEXT_SHOWS = stringPreferencesKey("keep_next_shows")
private val LOW_STORAGE_MODE = booleanPreferencesKey("low_storage_mode")
// Android's chosen place for downloads (HX26): an absolute path to a volume's app-specific
// folder, or blank for the default, internal storage. Left out of backups; see
// BackupCoverageTest for why a path to this device's disk cannot travel to another one.
private val DOWNLOAD_ROOT = stringPreferencesKey("download_root")
private val DOWNLOAD_ROOT_PENDING = stringPreferencesKey("download_root_pending")
private val LIGHTER_PICTURES = booleanPreferencesKey("lighter_pictures")
private val REDUCE_MOTION = booleanPreferencesKey("reduce_motion")
// T8: the television's focus ring thicker still with a contrasting outer line (HIGH_CONTRAST_FOCUS).
private val HIGH_CONTRAST_FOCUS = booleanPreferencesKey("high_contrast_focus")
private val DEVICE_CARD_DISMISSED = booleanPreferencesKey("device_card_dismissed")
private val DEVICE_HINT_SHOWN = booleanPreferencesKey("device_hint_shown")
private val TRICKPLAY = booleanPreferencesKey("trickplay")
private val WATCH_NEXT = booleanPreferencesKey("watch_next")
private val TV_CHANNELS = booleanPreferencesKey("tv_channels")
private val HOME_CAROUSEL = booleanPreferencesKey("home_carousel")
private val HOME_CONTINUE = booleanPreferencesKey("home_continue")
private val HOME_RECENT = booleanPreferencesKey("home_recent")
private val HOME_STARRED_ROWS = booleanPreferencesKey("home_starred_rows")
private val HOME_ORDER = stringPreferencesKey("home_order") // HomeOrder: row keys, comma separated
private val CRASH_REPORTS = booleanPreferencesKey("crash_reports")
private val DETAILED_LOGS = booleanPreferencesKey("detailed_logs")

// Whether the crash report question was put to this install (S1, [CrashAsk]). Not in a backup:
// like the answer, it is per device.
private val CRASH_ASKED = booleanPreferencesKey("crash_ask_done")
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

/** Title reports sent, asked about and waiting for a connection (N1): see [TitleLedger]. Device only, not backed up. */
private val TITLE_LEDGER = stringPreferencesKey("title_ledger")
private val FEEDBACK_DRAFT = stringPreferencesKey("feedback_draft")

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
private val LOCK_BIOMETRIC = booleanPreferencesKey("app_lock_biometric")
private val KIDS_LOCK = booleanPreferencesKey("kids_lock")
private val KIDS_CHATS = stringSetPreferencesKey("kids_chats")

private val SETUP = stringPreferencesKey("setup")
private val TOUR = stringPreferencesKey("tour")
// The notifications and support steps after the setup's question: see [OnboardingSteps].
private val ONBOARDING_STEPS = stringPreferencesKey("onboarding_steps")
// The one-time hint registry (WP-68): "id=count,id=count", the device's. See HintRegistry.
private val HINTS_SEEN = stringPreferencesKey("hints_seen")
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
private val UI_SCALE_TENTATIVE = stringPreferencesKey("ui_scale_tentative")
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

/** The TDLib database the stored queue's file ids belong to ([DatabaseMarker], HX88). */
private val DOWNLOAD_QUEUE_DB = stringPreferencesKey("download_queue_db")

/** [SettingsStore.saveDownloadQueue]'s default: the marker of the store's own profile's database. */
const val DATABASE_OF_STORE = "@store"

/** Set once the downloads recorded before they had a folder of their own have been moved into it. */
private val DOWNLOADS_MIGRATED = booleanPreferencesKey("downloads_migrated")

/** Set on every sign in, wiped by Sign out with the rest ([SettingsStore.wasSignedIn], HX23). */
private val SIGNED_IN = booleanPreferencesKey("signed_in")
private val SCREEN_ORIENTATION = stringPreferencesKey("screen_orientation")

// The phone player's touch settings. See [TouchPrefs].
private val TAP_PLAYS_PAUSES = booleanPreferencesKey("player_tap_plays_pauses")
private val DOUBLE_TAP_MS = longPreferencesKey("double_tap_ms")
private val HOLD_SPEED = floatPreferencesKey("hold_speed")
private val CONTROLS_TIMEOUT_MS = longPreferencesKey("controls_timeout_ms")
private val GESTURE_SEEK = booleanPreferencesKey("gesture_seek")
private val GESTURE_BRIGHTNESS = booleanPreferencesKey("gesture_brightness")
private val REMEMBER_BRIGHTNESS = booleanPreferencesKey("remember_brightness")
private val PLAYER_BRIGHTNESS = longPreferencesKey("player_brightness")
private val GESTURE_VOLUME = booleanPreferencesKey("gesture_volume")
private val PLAYER_HAPTICS = booleanPreferencesKey("player_haptics")
private val SHOW_REMAINING = booleanPreferencesKey("show_remaining_time")

// How subtitles look, on every platform. See [SubtitleStyle].
private val SUBTITLE_SIZE = stringPreferencesKey("subtitle_size")
private val SUBTITLE_SIZE_PERCENT = longPreferencesKey("subtitle_size_percent")
private val SUBTITLE_POSITION_PERCENT = longPreferencesKey("subtitle_position_percent")
private val SUBTITLE_COLOUR_RGB = longPreferencesKey("subtitle_colour_rgb")
private val SUBTITLE_BOX = booleanPreferencesKey("subtitle_box")
private val SUBTITLE_POSITION = stringPreferencesKey("subtitle_position")
private val SUBTITLE_BOX_LEVEL = stringPreferencesKey("subtitle_box_level")
private val SUBTITLE_COLOUR = stringPreferencesKey("subtitle_colour")
private val SUBTITLE_EDGE = stringPreferencesKey("subtitle_edge")
private val SUBTITLE_FACE = stringPreferencesKey("subtitle_face")
private val SUBTITLE_BOLD = booleanPreferencesKey("subtitle_bold")

// Android's video decoder choice. See [VideoDecoder].
private val VIDEO_DECODER = stringPreferencesKey("video_decoder")

// Which player opens a video (PR4, [com.tmplayer.player.PlayerChoice]) and the engine and
// playback switches of Settings, Playback (PR5). Every value is read back through an OrNull form.
private val DEFAULT_PLAYER = stringPreferencesKey("default_player")
private val EXTERNAL_HINT_SEEN = booleanPreferencesKey("external_hint_seen")
private val PLAYER_ENGINE = stringPreferencesKey("player_engine")
private val ENGINE_SWITCH_ON_ERROR = booleanPreferencesKey("engine_switch_on_error")
private val SUGGEST_VLC = booleanPreferencesKey("suggest_vlc")
// How each engine has done on this device (PR2, [com.tmplayer.player.EngineHistory]): this device's
// decoders, so it stays behind in a backup.
private val ENGINE_HISTORY = stringPreferencesKey("engine_history")
// What the remedy ladder learned per class of video on this device (RL6, [RemedyMemory]): its decoders, so it stays behind.
private val REMEDY_MEMORY = stringPreferencesKey("remedy_memory")
private val FRAME_RATE_MATCHING = booleanPreferencesKey("frame_rate_matching")
private val SCAN_THEN_JUMP = booleanPreferencesKey("scan_then_jump")
private val TUNNELED_PLAYBACK = stringPreferencesKey("tunneled_playback")
// Dolby and DTS to the television or receiver as they are (A2); off decodes them to plain sound here.
private val AUDIO_PASSTHROUGH = booleanPreferencesKey("audio_passthrough")
private val PLAYBACK_STATS = booleanPreferencesKey("playback_stats")

// Play the chat in order, and Repeat this video (EG-F30, EG-F52): see [ChatOrder] and [RepeatVideo].
private val PLAY_CHAT_ORDER = booleanPreferencesKey("play_chat_order")
private val PLAY_ALL_SKIP_WATCHED = booleanPreferencesKey("play_all_skip_watched")
private val KEEP_REPEAT = booleanPreferencesKey("keep_repeat")

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
private fun seriesKey(series: String): String = StoredKeys.series(series)

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
private fun speedKey(scope: String) = floatPreferencesKey(StoredKeys.speed(scope))

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
private fun videoFacetsKey(chatId: Long) = stringPreferencesKey("vfacets_$chatId")

/** The subtitle and audio offsets the viewer set for one file. See [SyncDelays]. */
private fun delayKey(chatId: Long, messageId: Long) =
    stringPreferencesKey("delay_${chatId}_$messageId")

/** One video's own subtitle look ("This video only", WP-29): this device, per video, never in a backup. */
private fun videoSubtitleKey(chatId: Long, messageId: Long) =
    stringPreferencesKey("substyle_${chatId}_$messageId")

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
private const val SYNC_TITLE_MAX = 120
private const val CACHED_PREFIX = "wc_"
private const val SETUP_PENDING = "pending"
private const val SETUP_DONE = "done"
private const val SETUP_REPLAY = "replay"
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
class SettingsStore(private val prefs: DataStore<Preferences>) : AllowedChatsSource, CorrectionsSource, AddedChatsSource {

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

    /**
     * [block] as the work of [owner]'s profile (HX88): its writes are dropped by the store once
     * another profile is open ([AccountScopedDataStore]). A player or a download passes the stamp it
     * was made under, so a save that starts after a switch never lands in the next profile.
     */
    private suspend fun owned(owner: AccountStamp?, block: suspend () -> Unit) {
        if (owner == null) block() else withContext(AccountOwner(owner)) { block() }
    }

    // ---- added chats ------------------------------------------------------------------------

    /** The chats the viewer added, with the mode beside them. See [AddedChats]. */
    override val addedChats: Flow<AddedChoice> = read { prefs ->
        AddedChoice(AddedChats.modeOf(prefs[ADDED_CHATS_MODE]), AddedChats.decode(prefs[ADDED_CHATS]))
    }

    /**
     * Stores the whole answer the picker came back with, which is the only way the set changes as
     * a whole: Done always writes [AddedMode.Picked], even for an empty choice, because an empty
     * answer is an answer. The key is NOT removed when the list empties, since an absent key
     * means "never asked".
     */
    suspend fun setAddedChats(chats: List<AddedChat>) = setAddedChoice(AddedChoice(AddedMode.Picked, chats))

    /**
     * Stores the picker's whole answer in one edit (HX86): under [AddedMode.All], the "All chats"
     * switch is on and the ticked chats are kept beside it, untouched, so switching it off brings
     * them back. The switch flipped on and off before Done writes once, the last state.
     */
    suspend fun setAddedChoice(choice: AddedChoice) {
        prefs.edit { prefs ->
            prefs[ADDED_CHATS] = AddedChats.encode(choice.chats)
            prefs[ADDED_CHATS_MODE] = if (choice.mode == AddedMode.All) AddedChats.MODE_ALL else AddedChats.MODE_PICKED
        }
    }

    /**
     * Every chat, as an answer rather than as silence (HX28's "Keep all my chats"). No screen
     * writes this any more, since choosing chats became mandatory; kept so stored [AddedMode.All]
     * answers, older backups and the store parity tests stay valid.
     */
    suspend fun keepAllChats() {
        prefs.edit { prefs ->
            prefs.remove(ADDED_CHATS)
            prefs[ADDED_CHATS_MODE] = AddedChats.MODE_ALL
        }
    }

    /** Adds one chat to the choice, for the star-and-add row outside the picker. */
    suspend fun addChat(chatId: Long, title: String) {
        prefs.edit { prefs ->
            val mode = AddedChats.modeOf(prefs[ADDED_CHATS_MODE])
            // Adding one chat while every chat is shown would narrow the app to that chat alone,
            // which nobody asked for, so the choice is only touched once the viewer has made one.
            if (mode != AddedMode.Picked) return@edit
            prefs[ADDED_CHATS] = AddedChats.encode(AddedChats.with(AddedChats.decode(prefs[ADDED_CHATS]), AddedChat(chatId, title)))
        }
    }

    /**
     * Takes one chat out of the choice. Nothing in Telegram changes, and downloads and history stay.
     *
     * Under "every chat" (unanswered or Keep all), [everything] is the chat list the viewer sees:
     * the choice becomes those chats but this one ([AddedChats.removing]), which is what the chat
     * menu's "Remove from TMPlayer" does. Without it, only a picked choice changes.
     */
    suspend fun removeChat(chatId: Long, everything: List<AddedChat> = emptyList()) {
        prefs.edit { prefs ->
            val now = AddedChoice(AddedChats.modeOf(prefs[ADDED_CHATS_MODE]), AddedChats.decode(prefs[ADDED_CHATS]))
            val next = AddedChats.removing(now, chatId, everything)
            if (next == now) return@edit
            prefs[ADDED_CHATS] = AddedChats.encode(next.chats)
            prefs[ADDED_CHATS_MODE] = AddedChats.MODE_PICKED
        }
    }

    /**
     * Turns "All chats" off from a chat's menu (HX86), where Remove is not offered while it is on:
     * the kept picks come back ([AddedChats.turningOffAll]). Nothing to do when the switch is off, so
     * a second press, or the picker having turned it off meanwhile, writes nothing.
     */
    suspend fun turnOffAllChats(everything: List<ChatSummary>, listComplete: Boolean) {
        prefs.edit { prefs ->
            val now = AddedChoice(AddedChats.modeOf(prefs[ADDED_CHATS_MODE]), AddedChats.decode(prefs[ADDED_CHATS]))
            val next = AddedChats.turningOffAll(now, everything, listComplete)
            if (next == now) return@edit
            prefs[ADDED_CHATS] = AddedChats.encode(next.chats)
            prefs[ADDED_CHATS_MODE] = AddedChats.MODE_PICKED
        }
    }

    /**
     * The one-time fold of the retired hidden chats into the added chats ([AddedChats.withoutHidden]),
     * then the old setting is deleted. Waits, with the setting kept, while the fold needs a chat
     * list and [everything] is empty. Text that does not parse counts as nothing hidden.
     */
    override suspend fun migrateHiddenChats(everything: List<AddedChat>) {
        prefs.edit { prefs ->
            val raw = prefs[HIDDEN_CHATS] ?: return@edit
            val now = AddedChoice(AddedChats.modeOf(prefs[ADDED_CHATS_MODE]), AddedChats.decode(prefs[ADDED_CHATS]))
            val next = AddedChats.withoutHidden(now, AddedChats.hiddenIds(raw), everything) ?: return@edit
            if (next != now && next.mode == AddedMode.Picked) {
                prefs[ADDED_CHATS] = AddedChats.encode(next.chats)
                prefs[ADDED_CHATS_MODE] = AddedChats.MODE_PICKED
            }
            prefs.remove(HIDDEN_CHATS)
        }
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
            val keptMobileData = prefs[MOBILE_DATA]
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
            // How often the tour was skipped is the device's, so a sign out and the tour offered
            // again do not start the count over: two skips on this device silence the hints (WP-68).
            val tourSkips = hintStateOf(prefs).count(HintIds.TOUR_SKIPPED)
            prefs.clear()
            if (tourSkips > 0) prefs[HINTS_SEEN] = HintRegistry.encode(HintRegistry.raise(HintState(), HintIds.TOUR_SKIPPED, tourSkips))
            theme?.let { prefs[THEME_CHOICE] = it }
            pureBlack?.let { prefs[PURE_BLACK] = it }
            accent?.let { prefs[ACCENT] = it }
            dynamic?.let { prefs[DYNAMIC_COLOUR] = it }
            uiScale?.let { prefs[UI_SCALE] = it }
            customAccent?.let { prefs[ACCENT_CUSTOM] = it }
            typeface?.let { prefs[APP_TYPEFACE] = it }
            highContrast?.let { prefs[HIGH_CONTRAST] = it }
            for ((key, value) in device) prefs[key] = value
            keptMobileData?.let { prefs[MOBILE_DATA] = it }
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
     * Signing out of one profile while others stay on the device (HX88): only the account's keys
     * go ([AccountKeys]), so the theme, the player, the app lock and the kids lock switch stay as
     * every profile has them. With [keepDownloads] the records of downloads in the Downloads
     * folder stay, as [clearEverything] keeps them. [clearEverything] is still the sign out of the
     * last profile.
     */
    suspend fun clearAccountData(keepDownloads: Boolean = false) {
        prefs.edit { prefs ->
            for ((key, value) in prefs.asMap().entries.toList()) {
                val name = key.name
                if (!AccountKeys.isAccount(name)) continue
                if (keepDownloads) {
                    if (name == DOWNLOADS_MIGRATED.name) continue
                    val ids = name.removePrefixOrNull("dl_")
                    if (ids != null && decodeDownload(prefs, ids, value as? String)?.localPath != null) continue
                }
                prefs.remove(key)
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

    // ---- play the chat in order, repeat this video (EG-F30, EG-F52) ---------------------------

    /**
     * What follows a video that is not an episode: off stops at its end (today's way), on starts
     * the next video of the chat in the order its grid is sorted in ([ChatOrder]), through the
     * same countdown and "Still watching?" as the next episode.
     */
    val playChatOrder: Flow<Boolean> = read { it[PLAY_CHAT_ORDER] ?: false }

    suspend fun setPlayChatOrder(value: Boolean) {
        prefs.edit { it[PLAY_CHAT_ORDER] = value }
    }

    suspend fun playChatOrderNow(): Boolean = prefs.data.first()[PLAY_CHAT_ORDER] ?: false

    /**
     * Whether Play all and Shuffle go past videos already watched (WP-32): off by default, so a press plays
     * what the grid shows; on, the next video is the next one not yet watched. Only the way forward skips:
     * Previous goes back to what was just played.
     */
    val playAllSkipWatched: Flow<Boolean> = read { it[PLAY_ALL_SKIP_WATCHED] ?: false }

    suspend fun setPlayAllSkipWatched(value: Boolean) {
        prefs.edit { it[PLAY_ALL_SKIP_WATCHED] = value }
    }

    suspend fun playAllSkipWatchedNow(): Boolean = prefs.data.first()[PLAY_ALL_SKIP_WATCHED] ?: false

    /** Whether Repeat this video stays on when the next video starts; off by default (decision D9). */
    val keepRepeat: Flow<Boolean> = read { it[KEEP_REPEAT] ?: false }

    suspend fun setKeepRepeat(value: Boolean) {
        prefs.edit { it[KEEP_REPEAT] = value }
    }

    suspend fun keepRepeatNow(): Boolean = prefs.data.first()[KEEP_REPEAT] ?: false

    /**
     * Whether a tap on a video opens its page (poster, cast, Play) before playing. On by default;
     * off plays at once, and the page is still a hold or the info key away.
     */
    val detailFirst: Flow<Boolean> = read { it[DETAIL_FIRST] ?: true }

    suspend fun setDetailFirst(value: Boolean) {
        prefs.edit { it[DETAIL_FIRST] = value }
    }

    /**
     * "Only show what I have" on a show's page (T9): on lists just the seasons and episodes the
     * chats hold; off (the default) lists every one the metadata knows, the missing ones quiet.
     */
    val seriesOnlyMine: Flow<Boolean> = read { it[SERIES_ONLY_MINE] ?: false }

    suspend fun setSeriesOnlyMine(value: Boolean) {
        prefs.edit { it[SERIES_ONLY_MINE] = value }
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
     * [chatId]'s filters by what a video is ([VideoFacets], U6): none until the viewer picks one.
     * A stored line from another version reads part by part, an unknown part as that filter off.
     */
    fun videoFacets(chatId: Long): Flow<VideoFacets> = read { VideoFacets.decode(it[videoFacetsKey(chatId)]) }

    suspend fun setVideoFacets(chatId: Long, facets: VideoFacets) {
        prefs.edit { if (facets.isDefault) it.remove(videoFacetsKey(chatId)) else it[videoFacetsKey(chatId)] = facets.encode() }
    }

    /** Show, and every filter by what a video is, back to none for [chatId]: the empty state's Clear filters. */
    suspend fun clearVideoFilters(chatId: Long) {
        prefs.edit {
            it.remove(videoFilterKey(chatId))
            it.remove(videoFacetsKey(chatId))
        }
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
        val at = System.currentTimeMillis()
        prefs.edit { prefs ->
            for ((item, correction) in updated) {
                val key = stringPreferencesKey(VideoCorrections.keyName(item.chatId, item.messageId))
                val name = ProgressKeys.of(item.chatId, item.messageId)
                val stamp = longPreferencesKey(SyncDocuments.CORRECTION_AT_PREFIX + name)
                val gone = longPreferencesKey(SyncDocuments.CORRECTION_REMOVAL_PREFIX + name)
                // When it changed, and when it was cleared, so another device with sync on follows.
                if (correction.isEmpty) {
                    val existed = prefs[key] != null
                    prefs.remove(key)
                    prefs.remove(stamp)
                    if (existed) prefs[gone] = at
                } else {
                    prefs.remove(gone)
                    prefs[key] = correction.encode()
                    prefs[stamp] = at
                }
            }
        }
        updated.forEach { (item, correction) -> VideoCorrections.put(item.id, correction) }
    }

    /**
     * Corrections made on another device ([ResumeSync]), in one edit, each checked again against
     * what is here now: a correction changed here after it was made there stays, and a clear
     * only clears a correction that was not changed here since. Returns how many changed.
     */
    suspend fun applySyncedCorrections(add: List<SyncCorrection>, remove: Map<String, Long>): Int {
        if (add.isEmpty() && remove.isEmpty()) return 0
        var changed = 0
        prefs.edit { prefs ->
            for (incoming in add) {
                val name = incoming.key
                val key = stringPreferencesKey(VideoCorrections.PREFIX + name)
                val stamp = longPreferencesKey(SyncDocuments.CORRECTION_AT_PREFIX + name)
                val gone = longPreferencesKey(SyncDocuments.CORRECTION_REMOVAL_PREFIX + name)
                val mineAt = if (prefs[key] != null) (prefs[stamp] ?: 1L) else 0L
                if ((prefs[gone] ?: 0L) > incoming.at || mineAt >= incoming.at) continue
                prefs[key] = incoming.text
                prefs[stamp] = incoming.at
                prefs.remove(gone)
                changed++
            }
            for ((name, at) in remove) {
                val key = stringPreferencesKey(VideoCorrections.PREFIX + name)
                val stamp = longPreferencesKey(SyncDocuments.CORRECTION_AT_PREFIX + name)
                val gone = longPreferencesKey(SyncDocuments.CORRECTION_REMOVAL_PREFIX + name)
                if (prefs[key] == null || (prefs[stamp] ?: 1L) >= at) continue
                prefs.remove(key)
                prefs.remove(stamp)
                prefs[gone] = at
                changed++
            }
        }
        return changed
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
     * Off until chosen, except that a fresh install's first sign in screen turns it on ([armSetup]):
     * an install updated from before keeps off, so an update changes nothing for it.
     */
    val lowStorageMode: Flow<Boolean> = read { it[LOW_STORAGE_MODE] ?: false }

    suspend fun setLowStorageMode(value: Boolean) {
        prefs.edit { it[LOW_STORAGE_MODE] = value }
    }

    /** Read once at the start of playback, where a flow that has not emitted yet would lie. */
    suspend fun lowStorageModeNow(): Boolean = prefs.data.first()[LOW_STORAGE_MODE] ?: false

    /**
     * Where Android moves a finished download to, blank for the default (the app's own internal
     * storage, as it always was). An SD card or USB drive chosen in Storage settings (HX26,
     * [com.tmplayer.data.AndroidStorage]); the watch cache and TDLib's database are never here,
     * and never move with it.
     */
    val downloadRoot: Flow<String> = read { it[DOWNLOAD_ROOT] ?: "" }

    suspend fun setDownloadRoot(path: String) {
        prefs.edit { it[DOWNLOAD_ROOT] = path }
    }

    /** Read once where a flow that has not emitted yet would answer the wrong folder. */
    suspend fun downloadRootNow(): String = prefs.data.first()[DOWNLOAD_ROOT] ?: ""

    /**
     * A move of the downloads folder begun and not finished, written before the first file goes
     * so a kill or a crash can pick up where it left off on the next launch (decoded with
     * [StorageRelocationPlan.Pending]; Android leaves `oldCache` blank, since its cache never
     * moves). Blank when nothing is pending.
     */
    val downloadRootPending: Flow<String> = read { it[DOWNLOAD_ROOT_PENDING] ?: "" }

    suspend fun setDownloadRootPending(value: String) {
        prefs.edit { it[DOWNLOAD_ROOT_PENDING] = value }
    }

    suspend fun downloadRootPendingNow(): String = prefs.data.first()[DOWNLOAD_ROOT_PENDING] ?: ""

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

    /**
     * "High contrast focus" (T8, television only): the focus ring thicker still, with a white (dark
     * themes) or black (light theme) line round it, so it shows on pale artwork. Off by default.
     */
    val highContrastFocus: Flow<Boolean> = read { it[HIGH_CONTRAST_FOCUS] ?: false }

    suspend fun setHighContrastFocus(value: Boolean) {
        prefs.edit { it[HIGH_CONTRAST_FOCUS] = value }
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
     * Player volume ([com.tmplayer.player.PlayerVolume]), 0 to 100 per cent of the file's own
     * level, under the device's volume. Full until turned down, then kept for every video. A
     * stored value out of range reads as full, never as silence.
     */
    suspend fun playerVolumeNow(): Int = com.tmplayer.player.PlayerVolume.sanitise(prefs.data.first()[PLAYER_VOLUME])

    /**
     * The brightness the player was left at, in per cent, or [TouchPrefs.BRIGHTNESS_SYSTEM] to follow the system's.
     * Only read when "Remember brightness" is on, and only stored while it is (WP-27).
     */
    suspend fun playerBrightnessNow(): Int = TouchPrefs.sanitiseBrightness(prefs.data.first()[PLAYER_BRIGHTNESS])

    suspend fun setPlayerBrightness(percent: Int) {
        prefs.edit { p ->
            if (p[REMEMBER_BRIGHTNESS] != true || percent < TouchPrefs.BRIGHTNESS_MIN) {
                p.remove(PLAYER_BRIGHTNESS)
            } else {
                p[PLAYER_BRIGHTNESS] = percent.coerceIn(TouchPrefs.BRIGHTNESS_MIN, 100).toLong()
            }
        }
    }

    suspend fun setPlayerVolume(percent: Int) {
        val value = percent.coerceIn(com.tmplayer.player.PlayerVolume.MIN, com.tmplayer.player.PlayerVolume.FULL)
        prefs.edit { it[PLAYER_VOLUME] = value.toLong() }
    }

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
                it.remove(RESUME_SYNC_FILE)
                it.remove(RESUME_SYNC_FAVOURITES)
                it.remove(RESUME_SYNC_AT)
                it.remove(RESUME_SYNC_ACCOUNT)
                it.remove(RESUME_SYNC_STATUS)
                it.remove(KEEP_PLACE_WROTE_AT)
                it.remove(SYNC_CHANNEL_BLOCK)
                it.remove(SYNC_FILE_CHAT)
                it.remove(SYNC_SENT_CHAT)
            }
        }
    }

    suspend fun resumeSyncNow(): Boolean = prefs.data.first()[RESUME_SYNC] ?: false

    /**
     * Whether the one question about keeping the viewer's place on all their devices still waits
     * to be asked on Home: not once it was shown, answered or sync was turned on in Settings.
     */
    val keepPlaceCardPending: Flow<Boolean> = read { !(it[KEEP_PLACE_CARD_SEEN] ?: false) && !(it[RESUME_SYNC] ?: false) }

    /** The card was shown (or answered): it is not shown again, whatever came of it. */
    suspend fun setKeepPlaceCardSeen() {
        prefs.edit { it[KEEP_PLACE_CARD_SEEN] = true }
    }

    /** When this device last wrote the sync file; 0 before. */
    suspend fun resumeSyncWroteAtNow(): Long = prefs.data.first()[KEEP_PLACE_WROTE_AT] ?: 0L

    suspend fun setResumeSyncWroteAt(at: Long) {
        prefs.edit { it[KEEP_PLACE_WROTE_AT] = at }
    }

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

    /** The sync file's message in Saved Messages, once found or confirmed sent; 0 before. */
    suspend fun resumeSyncFileNow(): Long = prefs.data.first()[RESUME_SYNC_FILE] ?: 0L

    suspend fun setResumeSyncFile(id: Long) {
        prefs.edit { it[RESUME_SYNC_FILE] = id }
    }

    /** What this device held after its last sync ([SyncBase]), for telling what it removed since. */
    suspend fun resumeSyncBaseNow(): SyncBase {
        val p = prefs.data.first()
        return SyncBase(
            resumeKeys = p[RESUME_SYNC_KEYS].orEmpty().split(',').filter { it.isNotBlank() }.toSet(),
            favourites = p[RESUME_SYNC_FAVOURITES].orEmpty().split(',').mapNotNull { it.trim().toLongOrNull() }.toSet(),
            at = p[RESUME_SYNC_AT] ?: 0L,
        )
    }

    suspend fun setResumeSyncBase(base: SyncBase) {
        prefs.edit {
            it[RESUME_SYNC_KEYS] = base.resumeKeys.joinToString(",")
            it[RESUME_SYNC_FAVOURITES] = base.favourites.joinToString(",")
            it[RESUME_SYNC_AT] = base.at
        }
    }

    /** The account the last sync was for; 0 before the first. Another account stops the sync. */
    suspend fun resumeSyncAccountNow(): Long = prefs.data.first()[RESUME_SYNC_ACCOUNT] ?: 0L

    suspend fun setResumeSyncAccount(id: Long) {
        prefs.edit { it[RESUME_SYNC_ACCOUNT] = id }
    }

    /**
     * When this device last sent a new sync file. Kept across restarts, so a file Telegram has not
     * confirmed yet is never sent a second time by the next start.
     */
    suspend fun resumeSyncSentAtNow(): Long = prefs.data.first()[RESUME_SYNC_SENT_AT] ?: 0L

    suspend fun setResumeSyncSentAt(at: Long) {
        prefs.edit { it[RESUME_SYNC_SENT_AT] = at }
    }

    /** The private channel chosen for the sync file and backups (HX87); null is Saved Messages. */
    val syncChannel: Flow<SyncChannelChoice?> = read { decodeSyncChannel(it) }

    suspend fun syncChannelNow(): SyncChannelChoice? = decodeSyncChannel(prefs.data.first())

    private fun decodeSyncChannel(p: Preferences): SyncChannelChoice? {
        val chat = p[SYNC_CHANNEL] ?: return null
        if (chat == 0L) return null
        return SyncChannelChoice(chat, p[SYNC_CHANNEL_TITLE].orEmpty(), p[SYNC_CHANNEL_ACCOUNT] ?: 0L)
    }

    /**
     * Chooses the channel, or Saved Messages for null, in one edit. The block that was shown for
     * the old choice goes with it.
     */
    suspend fun setSyncChannel(choice: SyncChannelChoice?) {
        prefs.edit {
            if (choice == null) {
                it.remove(SYNC_CHANNEL)
                it.remove(SYNC_CHANNEL_TITLE)
                it.remove(SYNC_CHANNEL_ACCOUNT)
            } else {
                it[SYNC_CHANNEL] = choice.chatId
                it[SYNC_CHANNEL_TITLE] = choice.title.take(SYNC_TITLE_MAX)
                it[SYNC_CHANNEL_ACCOUNT] = choice.account
            }
            it.remove(SYNC_CHANNEL_BLOCK)
        }
    }

    /** Why the chosen channel is not used now; null when it is, or none is chosen. */
    val syncChannelBlock: Flow<SyncChannelBlock?> = read { SyncChannelBlock.decode(it[SYNC_CHANNEL_BLOCK]) }

    suspend fun setSyncChannelBlock(block: SyncChannelBlock?) {
        prefs.edit { if (block == null) it.remove(SYNC_CHANNEL_BLOCK) else it[SYNC_CHANNEL_BLOCK] = block.encode() }
    }

    /** The chat the remembered sync file message is in; 0 before it is known (Saved Messages, as before HX87). */
    suspend fun syncFileChatNow(): Long = prefs.data.first()[SYNC_FILE_CHAT] ?: 0L

    suspend fun setSyncFileChat(chatId: Long) {
        prefs.edit { it[SYNC_FILE_CHAT] = chatId }
    }

    /** The chat the last new sync file was sent to, so the pause after sending holds for that chat only. */
    suspend fun syncSentChatNow(): Long = prefs.data.first()[SYNC_SENT_CHAT] ?: 0L

    suspend fun setSyncSentChat(chatId: Long) {
        prefs.edit { it[SYNC_SENT_CHAT] = chatId }
    }

    /** Where an older copy of the sync file was left after the viewer moved it ("" for none). */
    val syncOldCopy: Flow<String> = read { it[SYNC_OLD_COPY].orEmpty() }

    suspend fun setSyncOldCopy(place: String) {
        prefs.edit { if (place.isBlank()) it.remove(SYNC_OLD_COPY) else it[SYNC_OLD_COPY] = place.take(SYNC_TITLE_MAX) }
    }

    /** What the last sync came to, for the Settings row; null before the first or on a line it cannot read. */
    val resumeSyncStatus: Flow<SyncStatus?> = read { SyncStatus.decode(it[RESUME_SYNC_STATUS]) }

    /** When the last sync that went through was; 0 before the first. */
    val resumeSyncAt: Flow<Long> = read { it[RESUME_SYNC_AT] ?: 0L }

    suspend fun setResumeSyncStatus(status: SyncStatus) {
        prefs.edit { it[RESUME_SYNC_STATUS] = status.encode() }
    }

    /**
     * A place another device saved, from the sync file ([ResumeSync]), written as this device's own
     * would be when it is newer than whatever is here. [fileId] and [title] are read from the
     * message on this device: TDLib's file ids differ from one device to the next.
     */
    suspend fun importSyncedResume(record: ResumeRecord, fileId: Int, title: String, chatTitle: String): Boolean {
        if (fileId <= 0) return false
        var wrote = false
        prefs.edit { prefs ->
            val local = ResumeRecord.updatedAtOf(prefs[metaKey(record.chatId, record.messageId)]) ?: Long.MIN_VALUE
            if (local >= record.updatedAt && prefs[resumeKey(record.chatId, record.messageId)] != null) return@edit
            prefs[resumeKey(record.chatId, record.messageId)] = record.positionMs
            if (record.durationMs > 0) prefs[durationKey(record.chatId, record.messageId)] = record.durationMs
            prefs[metaKey(record.chatId, record.messageId)] = ResumeRecord.encode(
                fileId = fileId,
                title = title.ifBlank { record.title },
                chatTitle = chatTitle,
                sizeBytes = record.sizeBytes,
                durationSec = record.durationSec,
                updatedAt = record.updatedAt,
                state = record.state,
            )
            evictOldestHistory(prefs)
            wrote = true
        }
        return wrote
    }

    /** A place another device removed at [at], after this one last saved it: gone here too. */
    suspend fun dropSyncedResume(key: String, at: Long) {
        val ids = key.split('_')
        val chatId = ids.getOrNull(0)?.toLongOrNull() ?: return
        val messageId = ids.getOrNull(1)?.toLongOrNull() ?: return
        prefs.edit { prefs ->
            val local = ResumeRecord.updatedAtOf(prefs[metaKey(chatId, messageId)]) ?: return@edit
            if (local >= at) return@edit
            prefs.remove(resumeKey(chatId, messageId))
            prefs.remove(durationKey(chatId, messageId))
            prefs.remove(metaKey(chatId, messageId))
        }
    }

    /** Favourite chats starred or unstarred on another device, in one edit. */
    suspend fun applySyncedFavourites(add: Set<Long>, remove: Set<Long>) {
        if (add.isEmpty() && remove.isEmpty()) return
        prefs.edit { prefs ->
            val current = prefs[FAVORITES].orEmpty().toMutableSet()
            current.removeAll(remove.map(Long::toString).toSet())
            current.addAll(add.map(Long::toString))
            prefs[FAVORITES] = current
        }
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
     * The retired "Keep playing in a mini player" switch (T12): Back from the player now always
     * stops and goes back. An old stored value is never read; this removes it, once, and is safe to
     * call on every start.
     */
    suspend fun dropRetiredMiniPlayer() {
        if (prefs.data.first().contains(MINI_PLAYER)) prefs.edit { it.remove(MINI_PLAYER) }
    }

    /** The retired switch as an older version stored it, for the removal's test. */
    internal suspend fun storeMiniPlayerForTest(on: Boolean) {
        prefs.edit { it[MINI_PLAYER] = on }
    }

    internal suspend fun hasMiniPlayerForTest(): Boolean = prefs.data.first().contains(MINI_PLAYER)

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

    private fun mobileDataOf(prefs: Preferences): MobileData =
        MobileData.resolve(prefs[MOBILE_DATA], prefs[WIFI_ONLY] ?: false, prefs[SAVE_MOBILE_DATA] ?: false)

    /**
     * "On mobile data": Ask first (the default), Never or Allow (R2). Read from the one stored word
     * when the viewer has chosen, else from the two switches it replaced ([MobileData.resolve]).
     */
    val mobileData: Flow<MobileData> = read { mobileDataOf(it) }

    /** Read at the start of playback and of a download, where the flow's first emission has not arrived yet. */
    suspend fun mobileDataNow(): MobileData = mobileDataOf(prefs.data.first())

    /**
     * Picks the answer and keeps the old Wi-Fi only switch in step with it (Never is on, the others
     * off), so a backup restored on a build before R2 holds back exactly what this one does.
     *
     * Also ends the old "Save mobile data" streaming bound: its row is gone, so a stored true could
     * never be turned off again, and Allow would still have limited every stream. A pick is the
     * viewer's one answer about mobile data, so the old key is removed (it maps once, here).
     */
    suspend fun setMobileData(value: MobileData) {
        prefs.edit {
            it[MOBILE_DATA] = value.stored
            it[WIFI_ONLY] = value == MobileData.Never
            it.remove(SAVE_MOBILE_DATA)
        }
    }

    /**
     * Refuse to fetch video over a connection the viewer pays for by the byte: "On mobile data:
     * Never". Derived from [mobileData], so the readers of this switch (the download service, the
     * player, the update download) follow the one control without a change.
     */
    val wifiOnlyDownloads: Flow<Boolean> = read { mobileDataOf(it).blocks }

    suspend fun setWifiOnlyDownloads(value: Boolean) {
        prefs.edit {
            it[WIFI_ONLY] = value
            // An answer already chosen moves with the switch: Never on, and off leaves Never.
            val chosen = MobileData.parse(it[MOBILE_DATA])
            if (value) it[MOBILE_DATA] = MobileData.Never.stored
            else if (chosen == MobileData.Never) it[MOBILE_DATA] = MobileData.Ask.stored
        }
    }

    /**
     * Always on since R2 (the switch is gone): the other downloads wait while a video plays from
     * the network, so they do not share its line, and carry on when it ends or has been paused for
     * long ([DownloadYield]). A stream that stutters beside a download looks like a slow video, and
     * nobody has to find a switch to be spared it. The stored value stays in old backups and is not read.
     */
    val pauseDownloadsWhilePlaying: Flow<Boolean> = read { true }

    /** Read at the start of playback, where the flow's first emission has not arrived yet. */
    suspend fun wifiOnlyDownloadsNow(): Boolean = mobileDataOf(prefs.data.first()).blocks

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

    /** The shows with "Keep the next episodes" on (WP-83), this account's. See [KeepNextShows]. */
    val keepNextShows: Flow<Set<KeepNextShow>> = read { KeepNextShows.decode(it[KEEP_NEXT_SHOWS]) }

    suspend fun keepNextShowsNow(): Set<KeepNextShow> = KeepNextShows.decode(prefs.data.first()[KEEP_NEXT_SHOWS])

    /** Turns smart downloads on or off for [show]; read and written in one edit, so two quick presses both land. */
    suspend fun setKeepNext(show: KeepNextShow, on: Boolean) {
        prefs.edit { it[KEEP_NEXT_SHOWS] = KeepNextShows.encode(KeepNextShows.with(KeepNextShows.decode(it[KEEP_NEXT_SHOWS]), show, on)) }
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

    /** Which rows Home shows ([HomeLayout]); every row on by default. */
    val homeLayout: Flow<HomeLayout> = read {
        HomeLayout.of(
            carousel = it[HOME_CAROUSEL],
            continueWatching = it[HOME_CONTINUE],
            recentlyAdded = it[HOME_RECENT],
            starredRows = it[HOME_STARRED_ROWS],
            order = it[HOME_ORDER],
        )
    }

    /**
     * Settings, Home: the rows in the order the viewer gave them ([HomeOrder.encode]). Empty puts
     * the rows back in Home's own order. Carried in a backup, not in the sync file: settings
     * never travel by it (SyncDocument).
     */
    suspend fun setHomeOrder(order: List<String>) {
        prefs.edit {
            val text = HomeOrder.encode(order)
            if (text.isEmpty()) it.remove(HOME_ORDER) else it[HOME_ORDER] = text
        }
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
    fun noticeOn(notice: Notice): Flow<Boolean> = read { noticeValue(it, notice) }

    suspend fun noticeOnNow(notice: Notice): Boolean = noticeValue(prefs.data.first(), notice)

    /**
     * [Notice.LowStorage] is always on since R2 (its switch left Settings: a phone about to run out
     * of room says so, and the system's own notification settings can still silence the channel);
     * the stored value stays in old backups and is not read.
     */
    private fun noticeValue(prefs: Preferences, notice: Notice): Boolean =
        if (notice == Notice.LowStorage) true else prefs[noticeKey(notice)] ?: notice.defaultOn

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
    val crashReports: Flow<Boolean> = read { it[CRASH_REPORTS] ?: CrashAsk.OPT_OUT_DEFAULT_ON }

    /** Writes the answer, from wherever it was given, and with it that the question was put (S1). */
    suspend fun setCrashReports(value: Boolean) {
        prefs.edit {
            it[CRASH_REPORTS] = value
            it[CRASH_ASKED] = true
        }
    }

    /**
     * "Keep detailed logs" (reliability plan L4): what the viewer chose, or null when they never
     * chose, so the build decides (on in a beta, preview or debug build, off in a stable one, see
     * `AppLogPolicy`). Kept as a choice, not a value, so a stable build that later turns into a beta
     * does not carry a default that was never anyone's decision.
     */
    val detailedLogs: Flow<Boolean?> = read { it[DETAILED_LOGS] }

    suspend fun setDetailedLogs(value: Boolean) {
        prefs.edit { it[DETAILED_LOGS] = value }
    }

    /** Read once during startup, before anything has had a chance to crash. */
    suspend fun crashReportsNow(): Boolean = prefs.data.first()[CRASH_REPORTS] ?: CrashAsk.OPT_OUT_DEFAULT_ON

    /**
     * Whether the crash report question was ever put to this install (S1, [CrashAsk]): the
     * first-run card or the setup's step shown, or an answer stored either way (Settings' switch,
     * the setup, the question after a crash). An install updated from before the flag existed
     * counts as asked once it has any answer stored.
     */
    val crashReportsAsked: Flow<Boolean> = read { it[CRASH_ASKED] == true || it[CRASH_REPORTS] != null }

    /** Writes down that the question was shown, answered or not. */
    suspend fun markCrashReportsAsked() {
        prefs.edit { it[CRASH_ASKED] = true }
    }

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
    suspend fun playerHintSeenNow(): Boolean = hintSeenNow(HintIds.PLAYER)

    suspend fun markPlayerHintSeen() = markHintSeen(HintIds.PLAYER)

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
    suspend fun tvBrowseHintSeenNow(): Boolean = hintSeenNow(HintIds.TV_BROWSE)

    suspend fun markTvBrowseHintSeen() = markHintSeen(HintIds.TV_BROWSE)

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
            val before = touchPrefsOf(prefs)
            val next = change(before)
            prefs[TAP_PLAYS_PAUSES] = next.tapPlaysPauses
            prefs[DOUBLE_TAP_MS] = TouchPrefs.sanitiseDoubleTap(next.doubleTapMs)
            prefs[HOLD_SPEED] = TouchPrefs.sanitiseHold(next.holdSpeed)
            // Only a value the viewer chose is stored, so a television's own default (5 s) is not
            // frozen at 3.5 s by changing some other touch setting. A changed time is a choice, so
            // a caller that only copies controlsTimeoutMs is stored too (it used to be dropped
            // while nothing was stored yet, the desktop's Hide the controls after step included).
            if (next.controlsTimeoutStored || next.controlsTimeoutMs != before.controlsTimeoutMs) prefs[CONTROLS_TIMEOUT_MS] = TouchPrefs.sanitiseTimeout(next.controlsTimeoutMs)
            prefs[GESTURE_SEEK] = next.seekGesture
            prefs[GESTURE_BRIGHTNESS] = next.brightnessGesture
            prefs[GESTURE_VOLUME] = next.volumeGesture
            prefs[PLAYER_HAPTICS] = next.haptics
            prefs[SHOW_REMAINING] = next.showRemaining
            prefs[REMEMBER_BRIGHTNESS] = next.rememberBrightness
            // Switching it off forgets the level: the next time it is on starts from the system's.
            if (!next.rememberBrightness) prefs.remove(PLAYER_BRIGHTNESS)
        }
    }

    private fun touchPrefsOf(prefs: Preferences) = TouchPrefs(
        tapPlaysPauses = prefs[TAP_PLAYS_PAUSES] ?: false,
        doubleTapMs = TouchPrefs.sanitiseDoubleTap(prefs[DOUBLE_TAP_MS]),
        holdSpeed = TouchPrefs.sanitiseHold(prefs[HOLD_SPEED]),
        controlsTimeoutMs = TouchPrefs.sanitiseTimeout(prefs[CONTROLS_TIMEOUT_MS]),
        controlsTimeoutStored = prefs[CONTROLS_TIMEOUT_MS] != null,
        seekGesture = prefs[GESTURE_SEEK] ?: true,
        brightnessGesture = prefs[GESTURE_BRIGHTNESS] ?: true,
        volumeGesture = prefs[GESTURE_VOLUME] ?: true,
        haptics = prefs[PLAYER_HAPTICS] ?: true,
        showRemaining = prefs[SHOW_REMAINING] ?: false,
        rememberBrightness = prefs[REMEMBER_BRIGHTNESS] ?: false,
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
            // The fine values (WP-29): stored only while they are set, so the preset names above stay the whole story otherwise.
            if (style.sizePercent > 0) it[SUBTITLE_SIZE_PERCENT] = style.sizePercent.toLong() else it.remove(SUBTITLE_SIZE_PERCENT)
            if (style.positionPercent >= 0) it[SUBTITLE_POSITION_PERCENT] = style.positionPercent.toLong() else it.remove(SUBTITLE_POSITION_PERCENT)
            if (style.customColour != 0) it[SUBTITLE_COLOUR_RGB] = SubtitleStyle.storedColour(style.customColour) else it.remove(SUBTITLE_COLOUR_RGB)
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
        sizePercent = prefs[SUBTITLE_SIZE_PERCENT],
        positionPercent = prefs[SUBTITLE_POSITION_PERCENT],
        customColour = prefs[SUBTITLE_COLOUR_RGB],
    )

    // ---- video decoder (Android) --------------------------------------------------------------

    val videoDecoder: Flow<VideoDecoder> = read { VideoDecoder.from(it[VIDEO_DECODER]) }

    suspend fun videoDecoderNow(): VideoDecoder = VideoDecoder.from(prefs.data.first()[VIDEO_DECODER])

    suspend fun setVideoDecoder(decoder: VideoDecoder) {
        prefs.edit { it[VIDEO_DECODER] = decoder.name }
    }

    // ---- default player and engine (Android, PR4 and PR5) -------------------------------------

    /** The player a press on a video opens: TMPlayer's own unless the viewer chose otherwise. */
    val defaultPlayer: Flow<com.tmplayer.player.PlayerChoice> = read { com.tmplayer.player.PlayerChoice.parse(it[DEFAULT_PLAYER]) }

    suspend fun defaultPlayerNow(): com.tmplayer.player.PlayerChoice =
        com.tmplayer.player.PlayerChoice.parse(prefs.data.first()[DEFAULT_PLAYER])

    suspend fun setDefaultPlayer(choice: com.tmplayer.player.PlayerChoice) {
        prefs.edit {
            if (choice == com.tmplayer.player.PlayerChoice.TmPlayer) it.remove(DEFAULT_PLAYER) else it[DEFAULT_PLAYER] = choice.encode()
        }
    }

    /** Whether the one line that says where "Play in TMPlayer" is has been shown (this device only). */
    suspend fun externalHintSeenNow(): Boolean = hintSeenNow(HintIds.EXTERNAL)

    suspend fun markExternalHintSeen() = markHintSeen(HintIds.EXTERNAL)

    /**
     * The one-time hint registry (WP-68, this device only): every hint and device fact the hints need,
     * by id with a count. The three older flags (`player_hint_seen`, `tv_browse_hint_seen`,
     * `external_hint_seen`) still count as seen when set, so nobody meets them twice. See [HintRegistry].
     */
    val hintState: Flow<HintState> = read { hintStateOf(it) }

    /** The ids seen at least once. */
    val hintsSeen: Flow<Set<String>> = hintState.map { it.seenIds }.distinctUntilChanged()

    /** Whether every hint is off: the tour was skipped twice. */
    val hintsOff: Flow<Boolean> = hintState.map { it.hintsOff }.distinctUntilChanged()

    suspend fun hintSeenNow(id: String): Boolean = hintCount(id) >= 1

    suspend fun hintCount(id: String): Int = hintStateOf(prefs.data.first()).count(id)

    /** Marked as the hint appears, so it is said once even if the app is left mid-way. */
    suspend fun markHintSeen(id: String) {
        prefs.edit { it[HINTS_SEEN] = HintRegistry.encode(HintRegistry.raise(hintStateOf(it), id)) }
    }

    /** Counts [id] once more and returns the new count (a hint "the first 5 times"). */
    suspend fun bumpHint(id: String): Int {
        var count = 0
        prefs.edit {
            val next = HintRegistry.bump(hintStateOf(it), id)
            count = next.count(id)
            it[HINTS_SEEN] = HintRegistry.encode(next)
        }
        return count
    }

    /** The quick tour was turned down or skipped: counted, so two skips silence every hint, then done as [markTourDone]. */
    suspend fun markTourSkipped() {
        prefs.edit {
            it[HINTS_SEEN] = HintRegistry.encode(HintRegistry.bump(hintStateOf(it), HintIds.TOUR_SKIPPED))
            it[TOUR] = TOUR_DONE
        }
    }

    private fun hintStateOf(p: Preferences): HintState {
        val legacy = buildSet {
            if (p[PLAYER_HINT_SEEN] == true) add(HintIds.PLAYER)
            if (p[TV_BROWSE_HINT_SEEN] == true) add(HintIds.TV_BROWSE)
            if (p[EXTERNAL_HINT_SEEN] == true) add(HintIds.EXTERNAL)
        }
        return HintRegistry.parse(p[HINTS_SEEN], legacy)
    }

    /** Automatic, ExoPlayer or VLC. Stored whatever this build can run; [com.tmplayer.player.EngineSelector] decides what it can honour. */
    val engineChoice: Flow<com.tmplayer.player.EngineChoice> = read { com.tmplayer.player.EngineChoice.from(it[PLAYER_ENGINE]) }

    suspend fun engineChoiceNow(): com.tmplayer.player.EngineChoice = com.tmplayer.player.EngineChoice.from(prefs.data.first()[PLAYER_ENGINE])

    suspend fun setEngineChoice(choice: com.tmplayer.player.EngineChoice) {
        prefs.edit {
            // The failure counts are since the viewer last chose ([com.tmplayer.player.EngineHistory]).
            if (com.tmplayer.player.EngineChoice.from(it[PLAYER_ENGINE]) != choice) it.remove(ENGINE_HISTORY)
            it[PLAYER_ENGINE] = choice.key
        }
    }

    /** Decoding failures of each engine on this device since the engine setting last changed. */
    suspend fun engineHistoryNow(): com.tmplayer.player.EngineHistory =
        com.tmplayer.player.EngineHistory.decode(prefs.data.first()[ENGINE_HISTORY])

    /** Counts one decoding failure of [engine] on this device. */
    suspend fun noteEngineFailure(engine: PlayerEngine) {
        prefs.edit { it[ENGINE_HISTORY] = com.tmplayer.player.EngineHistory.decode(it[ENGINE_HISTORY]).failed(engine).encode() }
    }

    /** The remedy ladder's memory per class ([RemedyMemory.decode]: anything unreadable is empty, stale entries dropped). */
    suspend fun remedyMemoryNow(today: Int): RemedyMemory = RemedyMemory.decode(prefs.data.first()[REMEDY_MEMORY], today)

    suspend fun setRemedyMemory(memory: RemedyMemory) {
        prefs.edit { it[REMEDY_MEMORY] = memory.encode() }
    }

    /** Switch to the other engine by itself when a decoder gives up (on), or only suggest it (off). */
    val switchEngineOnError: Flow<Boolean> = read { it[ENGINE_SWITCH_ON_ERROR] ?: true }

    suspend fun switchEngineOnErrorNow(): Boolean = prefs.data.first()[ENGINE_SWITCH_ON_ERROR] ?: true

    suspend fun setSwitchEngineOnError(value: Boolean) {
        prefs.edit { it[ENGINE_SWITCH_ON_ERROR] = value }
    }

    /**
     * "Suggest VLC when playback cannot keep up" (PR5): its switch was taken out of Settings by
     * U20, since nothing acted on it, and nothing writes it now. The name stays, read here, so a
     * backup that carries it restores cleanly and a player that comes to offer VLC can honour an
     * answer already given.
     */
    suspend fun suggestVlcNow(): Boolean = prefs.data.first()[SUGGEST_VLC] ?: true

    /** Frame-rate matching on a television (on by default; it still follows the TV's own setting). */
    val frameRateMatching: Flow<Boolean> = read { it[FRAME_RATE_MATCHING] ?: true }

    suspend fun frameRateMatchingNow(): Boolean = prefs.data.first()[FRAME_RATE_MATCHING] ?: true

    /**
     * Scan while holding, jump once when let go (WP-79): a held Left or Right over the bare picture
     * moves a scrub position and seeks once on release, instead of a live seek a few times a second.
     * Off unless chosen: nobody's habit changes, and the live ramp stays the default.
     */
    val scanThenJump: Flow<Boolean> = read { it[SCAN_THEN_JUMP] ?: false }

    /** Read before the player is built, like [frameRateMatchingNow]. */
    suspend fun scanThenJumpNow(): Boolean = prefs.data.first()[SCAN_THEN_JUMP] ?: false

    suspend fun setScanThenJump(value: Boolean) {
        prefs.edit { it[SCAN_THEN_JUMP] = value }
    }

    suspend fun setFrameRateMatching(value: Boolean) {
        prefs.edit { it[FRAME_RATE_MATCHING] = value }
    }

    val tunneledMode: Flow<com.tmplayer.player.TunneledMode> = read { com.tmplayer.player.TunneledMode.from(it[TUNNELED_PLAYBACK]) }

    suspend fun tunneledModeNow(): com.tmplayer.player.TunneledMode = com.tmplayer.player.TunneledMode.from(prefs.data.first()[TUNNELED_PLAYBACK])

    suspend fun setTunneledMode(mode: com.tmplayer.player.TunneledMode) {
        prefs.edit { it[TUNNELED_PLAYBACK] = mode.key }
    }

    /**
     * Audio passthrough (on by default, which is how every build before it played): a Dolby or DTS
     * track goes to the television or receiver as it is where the output says it takes it. Off
     * keeps every track plain PCM, decoded here. The player reads it as a video opens.
     */
    val audioPassthrough: Flow<Boolean> = read { it[AUDIO_PASSTHROUGH] ?: true }

    suspend fun audioPassthroughNow(): Boolean = prefs.data.first()[AUDIO_PASSTHROUGH] ?: true

    suspend fun setAudioPassthrough(value: Boolean) {
        prefs.edit { it[AUDIO_PASSTHROUGH] = value }
    }

    /** The playback stats overlay (off). Stored now; the overlay itself comes later. */
    val playbackStats: Flow<Boolean> = read { it[PLAYBACK_STATS] ?: false }

    suspend fun playbackStatsNow(): Boolean = prefs.data.first()[PLAYBACK_STATS] ?: false

    suspend fun setPlaybackStats(value: Boolean) {
        prefs.edit { it[PLAYBACK_STATS] = value }
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

    /**
     * The subtitle look this one video has of its own (WP-29 "This video only"), or null when it follows
     * the look of every video. This device only: a look chosen for one video on one screen is not worth
     * a sync line or a backup.
     */
    suspend fun videoSubtitleStyle(chatId: Long, messageId: Long): SubtitleStyle? =
        SubtitleStyle.decode(prefs.data.first()[videoSubtitleKey(chatId, messageId)])

    /** Keeps [style] for this video alone, or forgets it when [style] is null (back to the look of every video). */
    suspend fun setVideoSubtitleStyle(chatId: Long, messageId: Long, style: SubtitleStyle?) {
        prefs.edit {
            if (style == null) it.remove(videoSubtitleKey(chatId, messageId))
            else it[videoSubtitleKey(chatId, messageId)] = SubtitleStyle.encode(style)
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
            // The ordered lists are the codes joined with a comma; a list of one is the single code it always was.
            value.audioStored.ifEmpty { null }?.let { text -> it[PREFERRED_AUDIO] = text } ?: it.remove(PREFERRED_AUDIO)
            value.subtitleStored.ifEmpty { null }?.let { text -> it[PREFERRED_SUBTITLE] = text } ?: it.remove(PREFERRED_SUBTITLE)
            it[SUBTITLE_MODE] = value.mode.key
        }
    }

    private fun trackPreferencesOf(prefs: Preferences) =
        TrackPreferences.from(prefs[PREFERRED_AUDIO], prefs[PREFERRED_SUBTITLE], prefs[SUBTITLE_MODE])

    // ---- size filter ------------------------------------------------------------------------

    /**
     * The smallest video a chat lists ([SizeFilter.effectiveMin]): 0 unless the viewer turned on
     * "Hide small clips" (50 MB) or chose a floor before the sliders went. A stored 400 MB, the old
     * default, reads as 0.
     */
    val minSizeBytes: Flow<Long> =
        read { SizeFilter.effectiveMin(it[MIN_SIZE]) }

    val maxSizeBytes: Flow<Long> =
        read { SizeFilter.effectiveMax(it[MAX_SIZE]) }

    /** The Sort sheet's "Hide small clips" switch: on stores [SizeFilter.CLIP_MIN] (a custom floor is kept), off stores nothing. */
    suspend fun setHideSmallClips(on: Boolean) {
        prefs.edit { prefs ->
            val floor = SizeFilter.clipSwitchFloor(prefs[MIN_SIZE], on)
            if (floor != null) prefs[MIN_SIZE] = floor else prefs.remove(MIN_SIZE)
        }
    }

    /** Reset on the Sort sheet's custom limits line: both stored bounds go, every video is listed. */
    suspend fun resetSizeLimits() {
        prefs.edit { prefs ->
            prefs.remove(MIN_SIZE)
            prefs.remove(MAX_SIZE)
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

    /** Puts back a list Clear all took, for its Undo, under any search made since. */
    suspend fun restoreRecentSearches(searches: List<String>) {
        prefs.edit { it[RECENT_SEARCHES] = RecentSearches.encode(RecentSearches.restore(searches, RecentSearches.decode(it[RECENT_SEARCHES]))) }
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
     * Whether a fingerprint or face may open the app lock's pad (WP-41), off until it is switched on.
     * True only with a PIN set and the lock asking when the app opens: without either there is no
     * pad for it to open. It never reaches Settings, signing out, a profile's PIN or the kids lock.
     */
    val appLockBiometric: Flow<Boolean> = read { prefs ->
        prefs[APP_PIN] != null && prefs[LOCK_ON_OPEN] == true && prefs[LOCK_BIOMETRIC] == true
    }

    /** The biometric switch (this device's: the sensor is, and a backup never carries it). */
    suspend fun setAppLockBiometric(on: Boolean) {
        prefs.edit { it[LOCK_BIOMETRIC] = on }
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
            it.remove(LOCK_BIOMETRIC)
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
        prefs.edit {
            it[LOCK_ON_OPEN] = on
            // A fingerprint only opens the pad that asks when the app opens: with that off it goes too.
            if (!on) it.remove(LOCK_BIOMETRIC)
        }
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
            it.remove(LOCK_BIOMETRIC)
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
    val setupPending: Flow<Boolean> = read { it[SETUP] == SETUP_PENDING || it[SETUP] == SETUP_REPLAY }

    /**
     * The setup is on screen because Settings ran it again ([replaySetup]) rather than because of
     * a sign in: the one question is asked again as the ordinary picker, with the chosen chats
     * ticked and a Back, and ends when it is closed.
     */
    val setupReplay: Flow<Boolean> = read { it[SETUP] == SETUP_REPLAY }

    /**
     * An account is signed in on this device. Kept until Sign out clears the preferences, so a
     * sign in screen that finds it set follows a session that ended elsewhere (HX23).
     */
    suspend fun markSignedIn() {
        if (prefs.data.first()[SIGNED_IN] == true) return
        prefs.edit { it[SIGNED_IN] = true }
    }

    /** Whether [markSignedIn] was set and not yet cleared by Sign out or [forgetSignedIn]. */
    suspend fun wasSignedIn(): Boolean = prefs.data.first()[SIGNED_IN] == true

    /** The session that ended elsewhere has been dealt with. */
    suspend fun forgetSignedIn() {
        prefs.edit { it.remove(SIGNED_IN) }
    }

    /**
     * A sign in screen is up: the setup comes once the account is in. The first sign in screen of
     * a fresh install (nothing about setup, signing in or Low storage mode stored yet) also turns
     * [lowStorageMode] on, so a new install starts with it on, and marks the phone's "your account
     * moved" hint seen (it never had the gear); an install that is updated never reaches this, and a
     * stored answer either way is left alone.
     *
     * [tvDarkByDefault] (a television's caller, WP-73): the same fresh install also starts in
     * [ThemeChoice.Dark], as every major television app does, when no theme is stored. Only here:
     * this is the one place that knows the install is new, so an install that is updated, a sign in
     * again and a replay of the setup never change a look somebody has, even one that was never
     * written (it keeps the system's). A backup restored later writes its own theme over it.
     */
    suspend fun armSetup(tvDarkByDefault: Boolean = false) {
        prefs.edit {
            val fresh = it[SETUP] == null && it[SIGNED_IN] != true && it[LOW_STORAGE_MODE] == null
            if (fresh) it[LOW_STORAGE_MODE] = true
            if (fresh && tvDarkByDefault && it[THEME_CHOICE] == null) it[THEME_CHOICE] = ThemeChoice.Dark.name
            // A fresh install never had the gear: the hint that says where it went is not for it (WP-103).
            if (fresh) it[HINTS_SEEN] = HintRegistry.encode(HintRegistry.raise(hintStateOf(it), HintIds.NAV_ACCOUNT_MOVED))
            if (it[SETUP] != SETUP_PENDING) it[SETUP] = SETUP_PENDING
        }
    }

    /**
     * Finished, or skipped: not shown again until the next sign in or [replaySetup]. The quick
     * tour is no longer part of the setup: unless it was already done, it is left (or put back)
     * at [TourState.Never], so the shell offers it once on its own after the setup and the
     * mandatory chat choice. A tour already taken or turned down stays done.
     */
    suspend fun markSetupDone() {
        prefs.edit {
            // A first run (not a replay from Settings) arms the notifications and support steps, once:
            // an install that was updated never had them armed, so never sees them.
            if (it[SETUP] == SETUP_PENDING && it[ONBOARDING_STEPS] == null) {
                it[ONBOARDING_STEPS] = OnboardingSteps.encode(OnboardingSteps.State(armed = true)) ?: "armed"
            }
            it[SETUP] = SETUP_DONE
            if (it[TOUR] != TOUR_DONE) it.remove(TOUR)
        }
    }

    /** Settings' "Run the setup again": the one question, asked again as the ordinary picker. */
    suspend fun replaySetup() {
        prefs.edit { it[SETUP] = SETUP_REPLAY }
    }

    /**
     * The quick tour (CP69): [TourState.Never] until it has been shown or offered, [TourState.Pending]
     * while it waits to be shown on its own, [TourState.Done] after. A fresh sign in is offered it
     * once after the setup ([markSetupDone] leaves it at [TourState.Never]), as is an install
     * updated from a version without it.
     */
    val tourState: Flow<TourState> = read {
        when (it[TOUR]) {
            TOUR_PENDING -> TourState.Pending
            TOUR_DONE -> TourState.Done
            else -> TourState.Never
        }
    }

    /** The notifications and support steps: armed by the first run's [markSetupDone], each with its outcome. */
    val onboardingSteps: Flow<OnboardingSteps.State> = read { OnboardingSteps.decode(it[ONBOARDING_STEPS]) }

    /** Records how [step] ended, so it is not shown again (a Denied notifications step stays, in its settings form). */
    suspend fun markOnboardingStep(step: OnboardingSteps.Step, outcome: OnboardingSteps.Outcome) {
        prefs.edit {
            val state = OnboardingSteps.decode(it[ONBOARDING_STEPS])
            // Never arms by itself: a step recorded on an install that was not armed changes nothing.
            if (!state.armed) return@edit
            it[ONBOARDING_STEPS] = OnboardingSteps.encode(state.with(step, outcome)) ?: return@edit
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
            listOf(THEME_CHOICE, ACCENT, UI_SCALE, UI_SCALE_TENTATIVE, APP_TYPEFACE).forEach { p.remove(it) }
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
        prefs.edit { putUiScale(it, value) }
    }

    private fun putUiScale(prefs: MutablePreferences, value: UiScale) {
        if (value == UiScale.Default && unchosenUiScale == UiScale.Default) prefs.remove(UI_SCALE) else prefs[UI_SCALE] = value.name
    }

    /**
     * Interface size tried (WP-10): [next] applies at once and the size before it, [previous], is
     * kept marked until [keepUiScale]. If the app dies before an answer, [revertUiScale] on the next
     * start puts [previous] back, so a stray press can never leave a screen nobody can read. A trial
     * already marked keeps its first old size: that is the one to go back to.
     */
    suspend fun tryUiScale(next: UiScale, previous: UiScale) {
        prefs.edit {
            if (it[UI_SCALE_TENTATIVE] == null) it[UI_SCALE_TENTATIVE] = previous.name
            putUiScale(it, next)
        }
    }

    /** The size tried is the viewer's now: the mark goes, the size stays. */
    suspend fun keepUiScale() {
        prefs.edit { it.remove(UI_SCALE_TENTATIVE) }
    }

    /**
     * Puts back the size before a trial that was never answered (Undo, the countdown, or a start
     * after the app died with the question open), and clears the mark. The size put back, or null
     * when no trial was marked.
     */
    suspend fun revertUiScale(): UiScale? {
        var back: UiScale? = null
        prefs.edit {
            val old = it[UI_SCALE_TENTATIVE] ?: return@edit
            back = UiScale.from(old, unchosenUiScale)
            putUiScale(it, back!!)
            it.remove(UI_SCALE_TENTATIVE)
        }
        return back
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

    /** The title reports this device sent, asked about and holds for later (N1), kept on this device only. */
    val titleLedger: TitleLedgerStore = object : TitleLedgerStore {
        override suspend fun read(): TitleLedger = TitleLedger.decode(prefs.data.first()[TITLE_LEDGER])

        // Read and written inside one edit, so a send finishing while a queue is added to loses neither.
        override suspend fun update(change: (TitleLedger) -> TitleLedger) {
            prefs.edit { it[TITLE_LEDGER] = change(TitleLedger.decode(it[TITLE_LEDGER])).encode() }
        }
    }

    /** The "Report a problem" or "Request a feature" being written or waiting to go (HX64), on this device only. */
    val feedbackDraft: FeedbackDraftStore = object : FeedbackDraftStore {
        override suspend fun read(): FeedbackDraft? = FeedbackDraft.decode(prefs.data.first()[FEEDBACK_DRAFT])

        override suspend fun write(draft: FeedbackDraft?) {
            prefs.edit { if (draft == null) it.remove(FEEDBACK_DRAFT) else it[FEEDBACK_DRAFT] = draft.encode() }
        }
    }

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

    /**
     * Where every profile's downloads are (HX88), not only the open one's: a folder grant is the
     * device's, and one profile's change must not let go of a folder another profile's downloads
     * are in.
     */
    suspend fun downloadPathsOfEveryProfileNow(): List<String> {
        val views = (prefs as? AccountScopedDataStore)?.everyProfileNow()?.values ?: listOf(prefs.data.first())
        return views.flatMap { view -> decodeDownloads(view).mapNotNull { it.localPath } }
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
     * [rowsGeneration] (from [OfflineDownloads.persistSnapshot]) makes it write only while the rows
     * are still that set.
     *
     * Written on every change to the queue, which is a handful of writes per download rather than
     * one per second: progress is deliberately not stored, since a resumed download asks TDLib how
     * many bytes are actually on disk rather than trusting a number the crash may have caught mid
     * write.
     */
    suspend fun saveDownloadQueue(
        requests: List<DownloadRequest>,
        owner: AccountStamp? = null,
        database: String? = DATABASE_OF_STORE,
        rowsGeneration: Long? = null,
    ) = owned(owner) {
        // The database of the profile this store writes for (its owner, else the open one), read
        // from that profile's own folder (HX88): never from the folder a switch points at first.
        val marker = if (database == DATABASE_OF_STORE) {
            val folder = (owner ?: kotlinx.coroutines.currentCoroutineContext()[AccountOwner]?.stamp)?.folder ?: Accounts.activeFolder.value
            Td.databaseMarkerFor(folder)
        } else {
            database
        }
        prefs.edit { prefs ->
            // The rows these were read from went meanwhile (a sign out or an ended session, which
            // drops them before it renews the marker): they are the old database's, and the marker
            // read above may already be the new one's. Checked inside the edit, which is one at a
            // time, so a write that was waiting never lands after the next database's restore.
            if (rowsGeneration != null && OfflineDownloads.rowsGeneration != rowsGeneration) return@edit
            if (requests.isEmpty()) {
                prefs.remove(DOWNLOAD_QUEUE)
                prefs.remove(DOWNLOAD_QUEUE_DB)
            } else {
                prefs[DOWNLOAD_QUEUE] = DownloadRequest.encodeAll(requests)
                // Its file ids are this database's: a queue of another one is never resumed. A
                // marker that could not be read keeps the tag there was: a tagged queue is never
                // taken for one from before markers.
                if (marker != null) prefs[DOWNLOAD_QUEUE_DB] = marker
            }
        }
    }

    /** The database the stored queue was written for, or null for one from before markers. */
    suspend fun downloadQueueDatabaseNow(): String? = prefs.data.first()[DOWNLOAD_QUEUE_DB]

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
    suspend fun noteDownload(item: MediaItem, chatTitle: String, localPath: String? = null, owner: AccountStamp? = null) = owned(owner) {
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
        owner: AccountStamp? = null,
    ) = owned(owner) {
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
            val written = writes(now)
            for ((name, value) in written) prefs.putRaw(name, value)
            // A correction brought back by a restore is the viewer's choice: a removal noted for it
            // (here or in a synced file) must not undo it, so it is stamped as changed now.
            val at = System.currentTimeMillis()
            for (name in written.keys) {
                if (VideoCorrections.idOf(name) == null) continue
                val id = name.removePrefix(VideoCorrections.PREFIX)
                val gone = longPreferencesKey(SyncDocuments.CORRECTION_REMOVAL_PREFIX + id)
                if (prefs[gone] != null) {
                    prefs.remove(gone)
                    prefs[longPreferencesKey(SyncDocuments.CORRECTION_AT_PREFIX + id)] = at
                }
            }
            evictOldestHistory(prefs)
        }
    }

    /**
     * Forgets where one video was left: this copy's place, and the place of any other copy of the
     * same video ([VideoCopies.sameVideo]), so Start over or Mark as watched on one copy does not
     * leave another's older place to resume from. [item] names the video when this copy has no
     * place of its own to read it from (Start over on a copy never played).
     */
    suspend fun clearResumePosition(chatId: Long, messageId: Long, item: MediaItem? = null, owner: AccountStamp? = null) = owned(owner) {
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

        /** Whether the Tips card may show on Home: the tour was never taken or turned down. */
        fun tipsDue(state: TourState): Boolean = state == TourState.Never

        /**
         * Opens the store at [file]. Call once per process and share the result. The account's
         * keys are the open profile's ([AccountScopedDataStore], HX88).
         */
        fun openDataStore(file: File): DataStore<Preferences> = AccountScopedDataStore.opened(openSafeDataStore(file))

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

