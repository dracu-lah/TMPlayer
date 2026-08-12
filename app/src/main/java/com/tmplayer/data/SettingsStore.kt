package com.tmplayer.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.tmplayer.player.PlaybackSpeed
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.prefs by preferencesDataStore("tmplayer")

private val FAVORITES = stringSetPreferencesKey("favorite_chats")
private val ASK_BEFORE_CLEARING = booleanPreferencesKey("ask_before_clearing")
private val INTRO_SEEN = booleanPreferencesKey("intro_seen")
private val OVERVIEW_SEEN = booleanPreferencesKey("overview_seen")
private val OPEN_LAST_CHAT = booleanPreferencesKey("open_last_chat")
private val DOWNLOAD_FIRST = booleanPreferencesKey("download_before_playing")
private val AUTOPLAY_NEXT = booleanPreferencesKey("autoplay_next")
private val WIFI_ONLY = booleanPreferencesKey("wifi_only_downloads")
private val PLAYBACK_SPEED = floatPreferencesKey("playback_speed")
private val LAST_CHAT = longPreferencesKey("last_chat")
private val MIN_SIZE = longPreferencesKey("min_size_bytes")
private val MAX_SIZE = longPreferencesKey("max_size_bytes")
private val CHAT_LAYOUT = stringPreferencesKey("chat_layout")
private val MEDIA_LAYOUT = stringPreferencesKey("media_layout")
private val CHAT_SNAPSHOT = stringPreferencesKey("chat_snapshot")
private val THEME_CHOICE = stringPreferencesKey("theme_choice")
private val DYNAMIC_COLOUR = booleanPreferencesKey("dynamic_colour")
private val KEEP_VIDEOS = intPreferencesKey("keep_videos")
private val VIDEO_SCALE = stringPreferencesKey("video_scale")

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

/**
 * The same line again, for the Downloads screen.
 *
 * It cannot read the resume history instead: a video is written there only once a minute of it has
 * been watched, and a download the viewer started and walked away from is precisely the one taking
 * up the space they are looking for. This is written the moment playback is allowed to begin.
 */
private fun downloadKey(chatId: Long, messageId: Long) =
    stringPreferencesKey("dl_${chatId}_$messageId")

class SettingsStore(private val context: Context) {

    // ---- favourites -------------------------------------------------------------------------

    val favorites: Flow<Set<Long>> = context.prefs.data.map { prefs ->
        prefs[FAVORITES].orEmpty().mapNotNull { it.toLongOrNull() }.toSet()
    }

    suspend fun toggleFavorite(chatId: Long): Boolean {
        var nowFavorite = false
        context.prefs.edit { prefs ->
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
     * Signing out has to leave nothing of the previous account behind. TDLib clears its own
     * database on log out, but everything this app remembers about their chats and their videos
     * lives here, and none of it means anything to whoever signs in next.
     *
     * Two things survive, and neither is about the account: whether the app is drawn light or
     * dark, and whether it takes its colours from the wallpaper. Those describe the phone and the
     * person holding it, not the Telegram account signed into it, and having the app flip to dark
     * halfway through signing out reads as a fault rather than as privacy.
     */
    suspend fun clearEverything() {
        context.prefs.edit { prefs ->
            val theme = prefs[THEME_CHOICE]
            val dynamic = prefs[DYNAMIC_COLOUR]
            prefs.clear()
            theme?.let { prefs[THEME_CHOICE] = it }
            dynamic?.let { prefs[DYNAMIC_COLOUR] = it }
        }
    }

    /** Unstars every chat at once, which is the only way back from a tab full of them. */
    suspend fun clearFavorites() {
        context.prefs.edit { it.remove(FAVORITES) }
    }

    // ---- what opens on launch ---------------------------------------------------------------

    /**
     * Skip the chat list and reopen whichever chat was last watched.
     *
     * Off by default. Opening straight into a chat is the right thing for somebody who watches
     * one channel every evening, and the wrong thing for everybody else: the app opens somewhere
     * they did not ask to be, and the way back to the listing has to be discovered before the app
     * can be used at all. A shortcut is worth having, but not before its owner has said so.
     */
    val openLastChat: Flow<Boolean> = context.prefs.data.map { it[OPEN_LAST_CHAT] ?: false }

    suspend fun setOpenLastChat(value: Boolean) {
        context.prefs.edit { it[OPEN_LAST_CHAT] = value }
    }

    /**
     * Whether the next episode starts on its own when one finishes.
     *
     * On by default, because it is what a series is for and what every other player does. The
     * countdown before it starts is the way out for anybody who meant to stop.
     */
    val autoplayNext: Flow<Boolean> = context.prefs.data.map { it[AUTOPLAY_NEXT] ?: true }

    suspend fun setAutoplayNext(value: Boolean) {
        context.prefs.edit { it[AUTOPLAY_NEXT] = value }
    }

    /** Read from disk at the end of a video, where a flow's placeholder would be a wrong answer. */
    suspend fun autoplayNextNow(): Boolean = context.prefs.data.first()[AUTOPLAY_NEXT] ?: true

    /** The chat opened most recently, or zero when there has not been one yet. */
    val lastChatId: Flow<Long> = context.prefs.data.map { it[LAST_CHAT] ?: 0L }

    suspend fun rememberChatOpened(chatId: Long) {
        context.prefs.edit { it[LAST_CHAT] = chatId }
    }

    suspend fun forgetLastChat() {
        context.prefs.edit { it.remove(LAST_CHAT) }
    }

    /**
     * The chat to open immediately, or null to show the chat list.
     *
     * Read straight from disk rather than from a collected flow: this runs once, the moment the
     * chats arrive, and a flow that has not emitted yet would still be reporting its placeholder.
     */
    suspend fun autoOpenTarget(): Long? {
        val prefs = context.prefs.data.first()
        return autoOpenChatId(prefs[LAST_CHAT] ?: 0L, prefs[OPEN_LAST_CHAT] ?: true)
    }

    // ---- playback ---------------------------------------------------------------------------

    /**
     * Wait for the whole video to arrive before starting it.
     *
     * Off by default, because streaming while it downloads is the point of the app. It is here for
     * a connection too slow or too unsteady to keep up with playback, where waiting once beats
     * being stopped every few minutes.
     */
    val downloadBeforePlaying: Flow<Boolean> = context.prefs.data.map { it[DOWNLOAD_FIRST] ?: false }

    suspend fun setDownloadBeforePlaying(value: Boolean) {
        context.prefs.edit { it[DOWNLOAD_FIRST] = value }
    }

    /** Read once at the start of playback, where a flow that has not emitted yet would lie. */
    suspend fun downloadBeforePlayingNow(): Boolean =
        context.prefs.data.first()[DOWNLOAD_FIRST] ?: false

    /**
     * How many downloaded videos to keep before the oldest is given up, or
     * [CacheShelf.UNLIMITED] for as many as the disk will hold.
     *
     * The default is the device's, not a constant: a stick with 8 GB can hold about one video, and
     * a phone can comfortably hold three. Both are only a starting point, and the number is the
     * viewer's from the moment they touch it, which is why the stored value is never written on
     * their behalf.
     */
    val keepVideos: Flow<Int> = context.prefs.data.map { it[KEEP_VIDEOS] ?: defaultKeepVideos() }

    /** Read once, at the moment a video is asked for, for the same reason as the one above. */
    suspend fun keepVideosNow(): Int =
        context.prefs.data.first()[KEEP_VIDEOS] ?: defaultKeepVideos()

    suspend fun setKeepVideos(value: Int) {
        context.prefs.edit { it[KEEP_VIDEOS] = value }
    }

    private fun defaultKeepVideos(): Int =
        if (FormFactor.isTv(context)) TV_KEEP_VIDEOS else TOUCH_KEEP_VIDEOS

    /**
     * Refuse to fetch video over a connection the viewer pays for by the byte.
     *
     * Off by default, because a great many people watch on mobile data by choice and an app that
     * silently refuses to play is worse than one that asks. On, it is absolute: a video that is
     * not already downloaded will not open until there is Wi-Fi.
     */
    val wifiOnlyDownloads: Flow<Boolean> = context.prefs.data.map { it[WIFI_ONLY] ?: false }

    suspend fun setWifiOnlyDownloads(value: Boolean) {
        context.prefs.edit { it[WIFI_ONLY] = value }
    }

    /** Read at the start of playback, where the flow's first emission has not arrived yet. */
    suspend fun wifiOnlyDownloadsNow(): Boolean = context.prefs.data.first()[WIFI_ONLY] ?: false

    /**
     * The speed the last video was left at, applied to the next one.
     *
     * Somebody who watches everything at 1.25x should say so once rather than every episode, and
     * on a television there is no gear menu to say it in twice.
     */
    val playbackSpeed: Flow<Float> = context.prefs.data.map {
        PlaybackSpeed.sanitise(it[PLAYBACK_SPEED] ?: PlaybackSpeed.DEFAULT)
    }

    suspend fun setPlaybackSpeed(value: Float) {
        context.prefs.edit { it[PLAYBACK_SPEED] = PlaybackSpeed.sanitise(value) }
    }

    suspend fun playbackSpeedNow(): Float =
        PlaybackSpeed.sanitise(context.prefs.data.first()[PLAYBACK_SPEED] ?: PlaybackSpeed.DEFAULT)

    /**
     * How the picture was last fitted to the screen.
     *
     * Remembered for the same reason the speed is: a viewer whose television overscans, or who
     * cannot stand black bars, was choosing Crop again at the start of every single episode.
     */
    suspend fun videoScaleNow(): String? = context.prefs.data.first()[VIDEO_SCALE]

    suspend fun setVideoScale(name: String) {
        context.prefs.edit { it[VIDEO_SCALE] = name }
    }

    // ---- tracks, per series -----------------------------------------------------------------

    /**
     * The audio track and the subtitles this series was last watched with.
     *
     * Kept per series rather than per file, because the choice belongs to the show: a viewer who
     * turned English subtitles on for episode one wants them on for episode two, and the file id
     * changes with every episode. Kept per series rather than once for the whole app, because the
     * answer genuinely differs between one show and the next: subtitles on for the Malayalam
     * series, off for the English one, and neither preference should overwrite the other.
     *
     * A video that is not part of a series is keyed by its own name, which makes this a no-op for
     * it: nothing else will ever look that key up.
     */
    suspend fun trackChoice(series: String): TrackChoice {
        val prefs = context.prefs.data.first()
        val key = seriesKey(series)
        return TrackChoice(
            audioLanguage = prefs[audioKey(key)]?.takeIf { it.isNotBlank() },
            textLanguage = prefs[textKey(key)]?.takeIf { it.isNotBlank() },
            subtitlesOn = prefs[subtitlesKey(key)] ?: false,
        )
    }

    suspend fun setTrackChoice(series: String, choice: TrackChoice) {
        val key = seriesKey(series)
        context.prefs.edit { prefs ->
            choice.audioLanguage?.let { prefs[audioKey(key)] = it } ?: prefs.remove(audioKey(key))
            choice.textLanguage?.let { prefs[textKey(key)] = it } ?: prefs.remove(textKey(key))
            prefs[subtitlesKey(key)] = choice.subtitlesOn
        }
    }

    // ---- size filter ------------------------------------------------------------------------

    val minSizeBytes: Flow<Long> =
        context.prefs.data.map { it[MIN_SIZE] ?: SizeFilter.DEFAULT_MIN }

    val maxSizeBytes: Flow<Long> =
        context.prefs.data.map { it[MAX_SIZE] ?: SizeFilter.DEFAULT_MAX }

    suspend fun setMinSizeBytes(value: Long) {
        context.prefs.edit { prefs ->
            val max = prefs[MAX_SIZE] ?: SizeFilter.DEFAULT_MAX
            prefs[MIN_SIZE] = SizeFilter.clampMin(value, max)
        }
    }

    suspend fun setMaxSizeBytes(value: Long) {
        context.prefs.edit { prefs ->
            val min = prefs[MIN_SIZE] ?: SizeFilter.DEFAULT_MIN
            prefs[MAX_SIZE] = SizeFilter.clampMax(value, min)
        }
    }

    // ---- cold start -------------------------------------------------------------------------

    /**
     * The chat list as it was at the end of the last sync, for the first frame of the next launch.
     *
     * Read straight off disk rather than as a flow: it is wanted once, before anything else has
     * happened, and a flow that has not emitted yet would hand back an empty list at exactly the
     * moment the whole point is to have something to draw.
     */
    suspend fun cachedChatSnapshot(): List<ChatSummary> =
        ChatSnapshot.decode(context.prefs.data.first()[CHAT_SNAPSHOT])

    suspend fun saveChatSnapshot(chats: List<ChatSummary>) {
        val encoded = ChatSnapshot.encode(chats)
        context.prefs.edit { prefs ->
            // Written only when it has actually changed. This runs after every sync, and the
            // whole preference file is rewritten and fsynced per edit, so a list that has not
            // moved would cost a disk write every couple of minutes for nothing.
            if (prefs[CHAT_SNAPSHOT] != encoded) prefs[CHAT_SNAPSHOT] = encoded
        }
    }

    // ---- card layout ------------------------------------------------------------------------

    /**
     * How the chat sections are arranged.
     *
     * Rows by default: a chat is a name and a picture, and a name is what the viewer is reading.
     */
    val chatLayout: Flow<CardLayout> =
        context.prefs.data.map { CardLayout.decode(it[CHAT_LAYOUT], CardLayout.List) }

    suspend fun setChatLayout(value: CardLayout) {
        context.prefs.edit { it[CHAT_LAYOUT] = value.name }
    }

    /**
     * How a chat's videos are arranged.
     *
     * Tiles by default: previews make individual uploads easy to identify from across the room.
     */
    val mediaLayout: Flow<CardLayout> =
        context.prefs.data.map { CardLayout.decode(it[MEDIA_LAYOUT], CardLayout.Grid) }

    suspend fun setMediaLayout(value: CardLayout) {
        context.prefs.edit { it[MEDIA_LAYOUT] = value.name }
    }

    // ---- appearance -------------------------------------------------------------------------

    /**
     * Light, dark, or whatever the phone is set to.
     *
     * A television has no such setting and never reads this: a panel in a dark room is dark, and
     * Android TV has no system-wide light mode to follow.
     */
    val themeChoice: Flow<ThemeChoice> =
        context.prefs.data.map { ThemeChoice.from(it[THEME_CHOICE]) }

    suspend fun setThemeChoice(value: ThemeChoice) {
        context.prefs.edit { it[THEME_CHOICE] = value.name }
    }

    /**
     * Whether to take the palette from the phone's wallpaper, where Android offers one.
     *
     * Off by default. TMPlayer has a palette of its own and should look like itself on first run;
     * a wallpaper can take the app somewhere it was never designed for. Turning it on hands the
     * colours over to the phone, and on a phone older than Android 12 there is no palette to take.
     */
    val dynamicColour: Flow<Boolean> = context.prefs.data.map { it[DYNAMIC_COLOUR] ?: false }

    suspend fun setDynamicColour(value: Boolean) {
        context.prefs.edit { it[DYNAMIC_COLOUR] = value }
    }

    // ---- prompts ----------------------------------------------------------------------------

    /**
     * Whether to confirm before dropping the previous video to make room for a new one.
     *
     * Off by default. The stick holds one video at a time, so the answer is always yes, and being
     * asked it every time is a press between the viewer and what they came to watch. Anyone who
     * wants the question back can turn it on.
     */
    val askBeforeClearing: Flow<Boolean> = context.prefs.data.map { it[ASK_BEFORE_CLEARING] ?: false }

    suspend fun setAskBeforeClearing(value: Boolean) {
        context.prefs.edit { it[ASK_BEFORE_CLEARING] = value }
    }

    val introSeen: Flow<Boolean> = context.prefs.data.map { it[INTRO_SEEN] ?: false }

    suspend fun markIntroSeen() {
        context.prefs.edit { it[INTRO_SEEN] = true }
    }

    /**
     * Whether the walkthrough has been shown. Separate from [introSeen]: one is what the app is
     * allowed to do, the other is how to use it, and Settings can ask for the second one back.
     */
    val overviewSeen: Flow<Boolean> = context.prefs.data.map { it[OVERVIEW_SEEN] ?: false }

    suspend fun markOverviewSeen() {
        context.prefs.edit { it[OVERVIEW_SEEN] = true }
    }

    suspend fun replayOverview() {
        context.prefs.edit { it[OVERVIEW_SEEN] = false }
    }

    // ---- resume -----------------------------------------------------------------------------

    suspend fun resumePosition(chatId: Long, messageId: Long): Long =
        context.prefs.data.first()[resumeKey(chatId, messageId)] ?: 0L

    /**
     * Every stored resume point, keyed by `chatId:messageId`, read in one pass.
     *
     * The grid needs a progress bar on every preview at once; asking DataStore per card would be
     * one disk read per item on a device with very little to spare.
     */
    val watchProgress: Flow<Map<String, WatchPoint>> = context.prefs.data.map { prefs ->
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
     * Entries without a stored description are skipped rather than guessed at: they were written
     * by a build before the description existed, so there is no title to put on the card.
     */
    val continueWatching: Flow<List<ResumeRecord>> = context.prefs.data.map { prefs ->
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
     * Whether the file is still on disk is TDLib's answer, not this one: the screen asks it per
     * row. This is only the names, so that a row can say what it is rather than quoting a file id.
     */
    val downloadHistory: Flow<List<ResumeRecord>> = context.prefs.data.map { prefs ->
        buildList {
            for ((key, value) in prefs.asMap()) {
                val name = key.name
                if (!name.startsWith("dl_")) continue
                val ids = name.removePrefix("dl_")
                val record = ResumeRecord.decode(
                    key = ids,
                    encoded = value as? String,
                    positionMs = prefs[longPreferencesKey("resume_$ids")] ?: 0L,
                    durationMs = prefs[longPreferencesKey("duration_$ids")] ?: 0L,
                ) ?: continue
                add(record)
            }
        }.sortedByDescending { it.updatedAt }
    }

    /** Remembers a video as one that has been fetched, so Downloads can name it later. */
    suspend fun noteDownload(item: MediaItem, chatTitle: String) {
        context.prefs.edit { prefs ->
            prefs[downloadKey(item.chatId, item.messageId)] = ResumeRecord.encode(
                fileId = item.fileId,
                title = item.title,
                chatTitle = chatTitle,
                sizeBytes = item.sizeBytes,
                durationSec = item.durationSec,
                updatedAt = System.currentTimeMillis(),
            )
            evictOldestDownloads(prefs)
        }
    }

    /** Drops one row from Downloads, once its file has actually been deleted. */
    suspend fun forgetDownload(chatId: Long, messageId: Long) {
        context.prefs.edit { it.remove(downloadKey(chatId, messageId)) }
    }

    /** Empties the Downloads list, for the button that deletes every file behind it. */
    suspend fun forgetAllDownloads() {
        context.prefs.edit { prefs ->
            val doomed = prefs.asMap().keys.filter { it.name.startsWith("dl_") }
            for (key in doomed) prefs.remove(key)
        }
    }

    /** The same cap the resume history has, for the same reason: this list is not a log. */
    private fun evictOldestDownloads(prefs: MutablePreferences) {
        val stamps = prefs.asMap().keys
            .mapNotNull { it.name.removePrefixOrNull("dl_") }
            .mapNotNull { ids ->
                val meta = prefs[stringPreferencesKey("dl_$ids")] ?: return@mapNotNull null
                ids to (ResumeRecord.updatedAtOf(meta) ?: 0L)
            }
        if (stamps.size <= MAX_HISTORY) return
        stamps.sortedBy { it.second }
            .take(stamps.size - MAX_HISTORY)
            .forEach { (ids, _) -> prefs.remove(stringPreferencesKey("dl_$ids")) }
    }

    suspend fun saveResumePosition(
        chatId: Long,
        messageId: Long,
        positionMs: Long,
        durationMs: Long = 0L,
        description: String? = null,
    ) {
        context.prefs.edit { prefs ->
            val key = resumeKey(chatId, messageId)
            // Under a minute in, or basically finished: there is nothing worth resuming.
            if (positionMs < MIN_RESUME_MS) {
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
     * Nothing bounded it before, so a viewer who watches a video a day accumulates a key per video
     * forever, and every one of them is walked on every read of Continue watching and rewritten on
     * every ten-second heartbeat. The cap is generous: nobody scrolls past two hundred half-watched
     * videos, and past that the list is a cost rather than a feature.
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
        context.prefs.edit { prefs ->
            val doomed = prefs.asMap().keys.filter { key ->
                val name = key.name
                name.startsWith("resume_") || name.startsWith("duration_") || name.startsWith("meta_")
            }
            for (key in doomed) prefs.remove(key)
        }
    }

    /**
     * Drops half-watched entries that can no longer become a card, and says how many went.
     *
     * A resume position is written the moment playback starts, but the description beside it comes
     * from the video that was playing; a build that predates the description, a write interrupted by
     * the stick killing the app, or a file id that has since been revoked all leave a position on
     * disk that [continueWatching] can only skip. Skipping it every launch keeps a dead entry
     * forever, and it counts against nothing the viewer can see or clear. This is the sweep that
     * gets rid of them, and it uses the same decoder as the tab, so anything it deletes is exactly
     * what the tab could not show.
     */
    suspend fun pruneBrokenHistory(): Int {
        var removed = 0
        context.prefs.edit { prefs ->
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
        context.prefs.edit {
            it.remove(resumeKey(chatId, messageId))
            it.remove(durationKey(chatId, messageId))
            it.remove(metaKey(chatId, messageId))
        }
    }

    companion object {
        /** Whether launch should skip the chat list, given what is remembered and the setting. */
        fun autoOpenChatId(lastChatId: Long, enabled: Boolean): Long? =
            if (enabled && lastChatId != 0L) lastChatId else null

        const val MIN_RESUME_MS = 60_000L

        /** How many half-watched videos are kept before the oldest start dropping off. */
        const val MAX_HISTORY = 200

        /** One video at a time, which is about all an 8 GB stick can hold. */
        const val TV_KEEP_VIDEOS = 1

        /**
         * One on a phone as well.
         *
         * It used to be three, on the reasoning that a phone has the room. But the number is a
         * ceiling on what is kept without being asked for, and a viewer who wants three videos on
         * a train now says so by downloading three: a default that quietly holds two films nobody
         * chose is a default that eats a gigabyte of somebody's phone.
         */
        const val TOUCH_KEEP_VIDEOS = 1

        /** What the arrows step through, ending in "as many as fit". */
        val KEEP_VIDEO_CHOICES = listOf(1, 2, 3, 5, 10, CacheShelf.UNLIMITED)

        /**
         * The next choice up or down, stopping at the ends.
         *
         * Returning the value unchanged at either end is what tells the row to grey the arrow out,
         * so there is one definition of "there is nothing above this" rather than two.
         */
        fun stepKeepVideos(current: Int, direction: Int): Int {
            val at = KEEP_VIDEO_CHOICES.indexOf(current)
            // A number nobody offers any more, from an older build: treat it as the first step.
            if (at < 0) return KEEP_VIDEO_CHOICES.first()
            return KEEP_VIDEO_CHOICES.getOrElse(at + direction) { current }
        }

        /** How the count reads in a sentence, where zero means no limit at all. */
        fun keepVideosLabel(count: Int): String = when {
            count == CacheShelf.UNLIMITED -> "As many as fit"
            count == 1 -> "1 video"
            else -> "$count videos"
        }

        /** Anything within this of the end counts as watched. */
        const val END_MARGIN_MS = 30_000L

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
