package com.tmplayer.data

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.tmplayer.online.MetaSettings
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

/**
 * What a viewer would miss on a new device, in one file: the settings, the favourites, what has been
 * watched and what is saved for later, where each half-watched video stopped, and the small memories the app keeps per series,
 * per file and per group.
 *
 * Never in it: the Telegram session (TDLib keeps that in its own database, which nothing here reads),
 * API keys of any kind, tokens, and anything that names a place on this device's disk, such as a
 * download's path. A backup is a file the viewer will mail to themselves or leave in a cloud folder,
 * so it carries nothing that would let anybody into their account.
 *
 * Settings and memories travel as the raw preference values they are stored as, under their stored
 * names, so a setting added later needs only its name in [Backups.SETTINGS] to come along. The two
 * lists travel as plain fields rather than as the encoded lines the stores keep, so the file says
 * what it means to anybody who opens it, and an encoding change in a store does not break old files.
 */
data class Backup(
    val createdAt: Long,
    /** The TMPlayer version that wrote it, for the viewer's information only. */
    val appVersion: String,
    /** Preference name to value, only names in [Backups.SETTINGS] and of the kind listed there. */
    val settings: Map<String, Any>,
    /** The poster and overview switches. Never the viewer's own TMDB key. */
    val metadata: Map<String, Boolean>,
    val favourites: Set<Long>,
    val recentSearches: List<String>,
    /** Per series tracks, per file offsets, per group topics: names matching [Backups.MEMORY]. */
    val memory: Map<String, Any>,
    /** Resume points, without a download's path. */
    val resume: List<ResumeRecord>,
    val watched: List<WatchedRecord>,
    /** Watch later, which lives in the watched store's file under a prefix of its own. */
    val watchLater: List<WatchLaterRecord> = emptyList(),
    /** Every video's bookmarks, from [BookmarkStore]. */
    val bookmarks: List<Bookmark> = emptyList(),
    val version: Int = Backups.VERSION,
)

/** What reading a file came to. */
sealed interface BackupRead {
    data class Ok(val backup: Backup) : BackupRead

    /**
     * Written by a newer TMPlayer in a format this one does not know. Refused rather than half read:
     * a field this build skipped would be lost from the viewer's lists without a word.
     */
    data class TooNew(val version: Int) : BackupRead

    /** Not JSON, or JSON that is not a TMPlayer backup. */
    data object NotABackup : BackupRead
}

/**
 * What restoring [backup] onto this device would change, counted before anything is written, so the
 * confirm prompt can say it in numbers.
 */
data class RestorePlan(
    val backup: Backup,
    /** Settings, the poster switches among them, whose value on this device differs from the file's. */
    val settingsChanged: Int,
    val favouritesAdded: Int,
    val watchedAdded: Int,
    /** Videos saved for later in the backup that this device's list lacks. */
    val watchLaterAdded: Int = 0,
    /** Bookmarks in the backup at a moment this device has none at, in the same video. */
    val bookmarksAdded: Int = 0,
    /** Resume points this device does not have, or has an older one of. */
    val resumeAdded: Int,
    val memoryAdded: Int,
    val searchesAdded: Int,
) {
    val nothing: Boolean
        get() = settingsChanged + favouritesAdded + watchedAdded + watchLaterAdded + bookmarksAdded + resumeAdded + memoryAdded +
            searchesAdded == 0
}

/**
 * The backup format: what goes in, how it is written and read, and how a file is merged into what a
 * device already has. Pure functions over the stores' raw maps, so every rule is tested without a
 * store; [BackupService] is what reads and writes the stores.
 *
 * The merge never removes anything. Lists are joined. Of two resume points for one video the newer
 * is kept, and of two watched marks the newer time, with a mark made by hand staying one (as
 * [WatchedStore.markWatched] keeps it); of two watch later entries, the later added. Memories this device already has keep its own value, since a
 * choice made here is the more recent in nearly every case. Settings are the one thing replaced,
 * which is why the restore asks first.
 */
object Backups {

    /** Raised whenever a file this build writes could not be read by an older one. */
    const val VERSION = 1

    /** The first field of every file, so a random JSON file is told apart from a backup. */
    const val FORMAT = "tmplayer-backup"

    /** How a stored preference value is typed, so a file cannot write a text where a switch was. */
    enum class Kind { BOOL, LONG, FLOAT, TEXT }

    /**
     * The settings that travel, by stored name. Choices about how the app looks and plays, which
     * the viewer would set again by hand on a new device.
     *
     * Left out on purpose: crash reports (a consent is given on the device that sends them), the
     * hints and walkthroughs already seen, the last chat opened, cold start snapshots, the update
     * check's and the support card's bookkeeping, and every record of a cached or downloaded file,
     * which points at a file on this device's disk. The app lock and the kids lock stay behind too:
     * a PIN's hash has no place in a file that gets mailed around, and a restore must never be a
     * way round a lock somebody set on this device.
     *
     * A setting one device does not show still travels: a backup moved from a phone to a TV and on
     * to another phone keeps the phone's choice.
     */
    val SETTINGS: Map<String, Kind> = mapOf(
        "accent" to Kind.TEXT,
        "accent_custom" to Kind.LONG,
        "app_typeface" to Kind.TEXT,
        "autoplay_next" to Kind.BOOL,
        "background_audio" to Kind.BOOL,
        "pip_on_leave" to Kind.BOOL,
        "chat_filter" to Kind.TEXT,
        "chat_layout" to Kind.TEXT,
        "chat_sort" to Kind.TEXT,
        "controls_timeout_ms" to Kind.LONG,
        "detail_first" to Kind.BOOL,
        "compact_nav" to Kind.BOOL,
        "double_tap_ms" to Kind.LONG,
        "download_before_playing" to Kind.BOOL,
        "downmix_stereo" to Kind.BOOL,
        "dynamic_colour" to Kind.BOOL,
        "even_volume" to Kind.BOOL,
        "gesture_brightness" to Kind.BOOL,
        "gesture_seek" to Kind.BOOL,
        "gesture_volume" to Kind.BOOL,
        "high_contrast" to Kind.BOOL,
        "history_tab" to Kind.TEXT,
        // The chats hidden from the lists ([HiddenChats]), one text setting.
        "hidden_chats" to Kind.TEXT,
        "history_sort" to Kind.TEXT,
        "downloads_sort" to Kind.TEXT,
        "hold_speed" to Kind.FLOAT,
        "home_carousel" to Kind.BOOL,
        "home_continue" to Kind.BOOL,
        "home_recent" to Kind.BOOL,
        // The chats ticked for Recently added, by id, as one text setting ([HomeLayout.encodeChats]).
        "home_recent_chats" to Kind.TEXT,
        "home_recent_source" to Kind.TEXT,
        "home_starred_rows" to Kind.BOOL,
        "language" to Kind.TEXT,
        "lighter_pictures" to Kind.BOOL,
        "low_storage_mode" to Kind.BOOL,
        "max_size_bytes" to Kind.LONG,
        "media_layout" to Kind.TEXT,
        "min_size_bytes" to Kind.LONG,
        "new_video_alerts" to Kind.BOOL,
        "night_mode" to Kind.BOOL,
        "even_volume" to Kind.BOOL,
        "notify_downloads" to Kind.BOOL,
        "notify_episodes" to Kind.BOOL,
        "notify_low_storage" to Kind.BOOL,
        "notify_pieces" to Kind.BOOL,
        "notify_update" to Kind.BOOL,
        "open_last_chat" to Kind.BOOL,
        "preferred_audio_language" to Kind.TEXT,
        "preferred_subtitle_language" to Kind.TEXT,
        "player_haptics" to Kind.BOOL,
        "player_tap_plays_pauses" to Kind.BOOL,
        "pure_black" to Kind.BOOL,
        "quality_habit" to Kind.TEXT,
        "reduce_motion" to Kind.BOOL,
        "remember_searches" to Kind.BOOL,
        "remove_after_watching" to Kind.BOOL,
        "resume_sync" to Kind.BOOL,
        "rewind_on_resume_ms" to Kind.LONG,
        "save_mobile_data" to Kind.BOOL,
        "screen_orientation" to Kind.TEXT,
        "show_remaining_time" to Kind.BOOL,
        "skip_silence" to Kind.BOOL,
        "sponsor_reminders" to Kind.BOOL,
        "subtitle_bold" to Kind.BOOL,
        "subtitle_box" to Kind.BOOL,
        "subtitle_box_level" to Kind.TEXT,
        "subtitle_colour" to Kind.TEXT,
        "subtitle_edge" to Kind.TEXT,
        "subtitle_face" to Kind.TEXT,
        "subtitle_mode" to Kind.TEXT,
        "subtitle_position" to Kind.TEXT,
        "subtitle_size" to Kind.TEXT,
        "theme_choice" to Kind.TEXT,
        "trickplay" to Kind.BOOL,
        "tv_channels" to Kind.BOOL,
        "ui_scale" to Kind.TEXT,
        "update_notify" to Kind.BOOL,
        "video_decoder" to Kind.TEXT,
        "video_scale" to Kind.TEXT,
        "volume_boost" to Kind.BOOL,
        "watch_next" to Kind.BOOL,
        "wifi_only_downloads" to Kind.BOOL,
    )

    /**
     * The memories that travel, by the prefix of their stored names: the audio and subtitle choice
     * per series, the speed per series, chat or topic, the subtitle and audio offsets per file, and
     * the forum topic last opened per group and the copy chosen per video. A new memory joins by
     * adding its prefix here.
     */
    val MEMORY: Map<String, Kind> = mapOf(
        "audio_" to Kind.TEXT,
        "text_" to Kind.TEXT,
        "subs_" to Kind.BOOL,
        // The series' name as the player saw it, for Settings' list of what is remembered.
        "series_name_" to Kind.TEXT,
        "speed_" to Kind.FLOAT,
        "delay_" to Kind.TEXT,
        "topic_" to Kind.LONG,
        // A chat's own order ([VideoSort]) and filter ([VideoFilter]), and the viewer's corrections to names ([VideoCorrection]).
        "vsort_" to Kind.TEXT,
        "vfilter_" to Kind.TEXT,
        VideoCorrections.PREFIX to Kind.TEXT,
        "copy_pick_" to Kind.TEXT,
    )

    private const val FAVOURITES_KEY = "favorite_chats"
    private const val RECENT_SEARCHES = "recent_searches"
    private const val WATCHED_PREFIX = "w_"
    private const val WATCH_LATER_PREFIX = "l_"

    /** "tmplayer-backup-2026-10-13.json", dated in the viewer's own time zone. */
    fun fileName(now: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        "tmplayer-backup-${Instant.ofEpochMilli(now).atZone(zone).toLocalDate()}.json"

    // ---- collecting -------------------------------------------------------------------------

    /**
     * A backup of a device, from the raw contents of its settings store and its watched store and
     * its poster switches.
     */
    fun collect(
        settings: Map<String, Any>,
        watched: Map<String, Any>,
        metadata: MetaSettings,
        createdAt: Long,
        appVersion: String,
        bookmarks: List<Bookmark> = emptyList(),
    ): Backup = Backup(
        createdAt = createdAt,
        appVersion = appVersion,
        settings = settings.filter { (name, value) -> SETTINGS[name]?.let { fits(value, it) } == true },
        metadata = mapOf("enabled" to metadata.enabled, "skip_times" to metadata.skipTimes),
        favourites = favouritesOf(settings),
        recentSearches = RecentSearches.decode(settings[RECENT_SEARCHES] as? String),
        memory = settings.filter { (name, value) -> memoryKind(name)?.let { fits(value, it) } == true },
        resume = resumeOf(settings),
        watched = watchedOf(watched),
        watchLater = watchLaterOf(watched),
        bookmarks = bookmarks.sortedWith(compareBy({ it.chatId }, { it.messageId }, { it.positionMs })),
    )

    private fun favouritesOf(settings: Map<String, Any>): Set<Long> =
        (settings[FAVOURITES_KEY] as? Set<*>).orEmpty().mapNotNull { it.toString().toLongOrNull() }.toSet()

    private fun resumeOf(settings: Map<String, Any>): List<ResumeRecord> = settings.mapNotNull { (name, value) ->
        if (!name.startsWith("resume_")) return@mapNotNull null
        val ids = name.removePrefix("resume_")
        ResumeRecord.decode(
            key = ids,
            encoded = settings["meta_$ids"] as? String,
            positionMs = value as? Long ?: return@mapNotNull null,
            durationMs = settings["duration_$ids"] as? Long ?: 0L,
        )?.copy(localPath = null)
    }.sortedByDescending { it.updatedAt }

    private fun watchedOf(watched: Map<String, Any>): List<WatchedRecord> = watched.mapNotNull { (name, value) ->
        if (!name.startsWith(WATCHED_PREFIX)) return@mapNotNull null
        WatchedRecord.decode(name.removePrefix(WATCHED_PREFIX), value as? String)
    }.sortedByDescending { it.watchedAt }

    private fun watchLaterOf(watched: Map<String, Any>): List<WatchLaterRecord> = watched.mapNotNull { (name, value) ->
        if (!name.startsWith(WATCH_LATER_PREFIX)) return@mapNotNull null
        WatchLaterRecord.decode(name.removePrefix(WATCH_LATER_PREFIX), value as? String)
    }.sortedByDescending { it.addedAt }

    private fun memoryKind(name: String): Kind? =
        MEMORY.entries.firstOrNull { (prefix, _) -> name.startsWith(prefix) && name.length > prefix.length }?.value

    private fun fits(value: Any, kind: Kind): Boolean = when (kind) {
        Kind.BOOL -> value is Boolean
        Kind.LONG -> value is Long
        Kind.FLOAT -> value is Float && !value.isNaN()
        Kind.TEXT -> value is String
    }

    // ---- writing ----------------------------------------------------------------------------

    /** The file's text: JSON, indented, so it can be read and checked by eye. */
    fun encode(backup: Backup): String {
        val root = JSONObject()
        root.put("format", FORMAT)
        root.put("version", backup.version)
        root.put("createdAt", backup.createdAt)
        root.put("app", backup.appVersion)
        root.put("settings", valuesJson(backup.settings))
        root.put("metadata", JSONObject().apply { backup.metadata.forEach { (k, v) -> put(k, v) } })
        root.put("favourites", JSONArray(backup.favourites.sorted()))
        root.put("recentSearches", JSONArray(backup.recentSearches))
        root.put("memory", valuesJson(backup.memory))
        root.put("resume", JSONArray(backup.resume.map(::resumeJson)))
        root.put("watched", JSONArray(backup.watched.map(::watchedJson)))
        root.put("watchLater", JSONArray(backup.watchLater.map(::watchLaterJson)))
        root.put("bookmarks", JSONArray(backup.bookmarks.map(::bookmarkJson)))
        return root.toString(2)
    }

    /** Each value under the name of its kind, so reading it back needs no guessing. */
    private fun valuesJson(values: Map<String, Any>) = JSONObject().apply {
        for ((name, value) in values.toSortedMap()) {
            val typed = when (value) {
                is Boolean -> JSONObject().put("bool", value)
                is Long -> JSONObject().put("long", value)
                is Float -> JSONObject().put("float", value.toDouble())
                is String -> JSONObject().put("text", value)
                else -> continue
            }
            put(name, typed)
        }
    }

    private fun resumeJson(r: ResumeRecord) = JSONObject().apply {
        put("chat", r.chatId)
        put("message", r.messageId)
        put("file", r.fileId)
        put("title", r.title)
        put("chatTitle", r.chatTitle)
        put("size", r.sizeBytes)
        put("durationSec", r.durationSec)
        put("positionMs", r.positionMs)
        put("durationMs", r.durationMs)
        put("updatedAt", r.updatedAt)
        r.state?.let { put("state", it.encode()) }
    }

    private fun watchLaterJson(w: WatchLaterRecord) = JSONObject().apply {
        put("chat", w.chatId)
        put("message", w.messageId)
        put("file", w.fileId)
        put("title", w.title)
        put("fileName", w.fileName)
        put("chatTitle", w.chatTitle)
        put("size", w.sizeBytes)
        put("durationSec", w.durationSec)
        put("mime", w.mimeType)
        put("addedAt", w.addedAt)
    }

    private fun bookmarkJson(b: Bookmark) = JSONObject().apply {
        put("chat", b.chatId)
        put("message", b.messageId)
        put("id", b.id)
        put("positionMs", b.positionMs)
        put("note", b.note)
    }

    private fun watchedJson(w: WatchedRecord) = JSONObject().apply {
        put("chat", w.chatId)
        put("message", w.messageId)
        put("file", w.fileId)
        put("title", w.title)
        put("chatTitle", w.chatTitle)
        put("size", w.sizeBytes)
        put("durationSec", w.durationSec)
        put("watchedAt", w.watchedAt)
        put("manual", w.manual)
    }

    // ---- reading ----------------------------------------------------------------------------

    /**
     * Reads a file. Anything malformed inside a well formed backup (an entry missing its ids, a
     * setting of the wrong kind) is skipped on its own; the rest of the file still restores.
     */
    fun decode(text: String): BackupRead {
        val root = try {
            JSONObject(text)
        } catch (_: JSONException) {
            return BackupRead.NotABackup
        }
        if (root.optString("format") != FORMAT) return BackupRead.NotABackup
        val version = root.optInt("version", 0)
        if (version < 1) return BackupRead.NotABackup
        if (version > VERSION) return BackupRead.TooNew(version)
        val settings = valuesOf(root.optJSONObject("settings")) { name -> SETTINGS[name] }
        val metaJson = root.optJSONObject("metadata")
        val metadata = buildMap {
            for (name in listOf("enabled", "skip_times")) {
                (metaJson?.opt(name) as? Boolean)?.let { put(name, it) }
            }
        }
        return BackupRead.Ok(
            Backup(
                createdAt = root.optLong("createdAt", 0L),
                appVersion = root.optString("app"),
                settings = settings,
                metadata = metadata,
                favourites = longs(root.optJSONArray("favourites")).toSet(),
                recentSearches = RecentSearches.decode(
                    RecentSearches.encode(strings(root.optJSONArray("recentSearches"))),
                ),
                memory = valuesOf(root.optJSONObject("memory"), ::memoryKind),
                resume = objects(root.optJSONArray("resume")).mapNotNull(::resumeOf),
                watched = objects(root.optJSONArray("watched")).mapNotNull(::watchedOf),
                watchLater = objects(root.optJSONArray("watchLater")).mapNotNull(::watchLaterOf),
                bookmarks = objects(root.optJSONArray("bookmarks")).mapNotNull(::bookmarkOf),
                version = version,
            ),
        )
    }

    private fun valuesOf(json: JSONObject?, kindOf: (String) -> Kind?): Map<String, Any> = buildMap {
        if (json == null) return@buildMap
        for (name in json.keys()) {
            val kind = kindOf(name) ?: continue
            val typed = json.optJSONObject(name) ?: continue
            val value: Any = when (kind) {
                Kind.BOOL -> typed.opt("bool") as? Boolean
                Kind.LONG -> (typed.opt("long") as? Number)?.toLong()
                Kind.FLOAT -> (typed.opt("float") as? Number)?.toFloat()?.takeIf { !it.isNaN() }
                Kind.TEXT -> typed.opt("text") as? String
            } ?: continue
            put(name, value)
        }
    }

    private fun objects(array: JSONArray?): List<JSONObject> =
        if (array == null) emptyList() else (0 until array.length()).mapNotNull { array.optJSONObject(it) }

    private fun strings(array: JSONArray?): List<String> =
        if (array == null) emptyList() else (0 until array.length()).mapNotNull { array.opt(it) as? String }

    private fun longs(array: JSONArray?): List<Long> =
        if (array == null) emptyList() else (0 until array.length()).mapNotNull { (array.opt(it) as? Number)?.toLong() }

    private fun JSONObject.longOrNull(name: String): Long? = (opt(name) as? Number)?.toLong()

    /** Through [ResumeRecord.decode]'s own checks, so the file can hold nothing the store would not. */
    private fun resumeOf(json: JSONObject): ResumeRecord? {
        val chatId = json.longOrNull("chat") ?: return null
        val messageId = json.longOrNull("message") ?: return null
        val line = ResumeRecord.encode(
            fileId = json.longOrNull("file")?.toInt() ?: return null,
            title = json.optString("title"),
            chatTitle = json.optString("chatTitle"),
            sizeBytes = json.longOrNull("size") ?: 0L,
            durationSec = json.longOrNull("durationSec")?.toInt() ?: 0,
            updatedAt = json.longOrNull("updatedAt") ?: 0L,
            state = json.optString("state").takeIf { it.isNotEmpty() }?.let(ResumeState::decode),
        )
        return ResumeRecord.decode(
            key = SettingsStore.progressKey(chatId, messageId),
            encoded = line,
            positionMs = json.longOrNull("positionMs") ?: return null,
            durationMs = json.longOrNull("durationMs") ?: 0L,
        )
    }

    private fun watchLaterOf(json: JSONObject): WatchLaterRecord? = WatchLaterRecord(
        chatId = json.longOrNull("chat") ?: return null,
        messageId = json.longOrNull("message") ?: return null,
        fileId = json.longOrNull("file")?.toInt() ?: 0,
        title = json.optString("title"),
        fileName = json.optString("fileName"),
        chatTitle = json.optString("chatTitle"),
        sizeBytes = json.longOrNull("size") ?: 0L,
        durationSec = json.longOrNull("durationSec")?.toInt() ?: 0,
        mimeType = json.optString("mime"),
        addedAt = json.longOrNull("addedAt") ?: return null,
    )

    private fun bookmarkOf(json: JSONObject): Bookmark? = Bookmark(
        chatId = json.longOrNull("chat") ?: return null,
        messageId = json.longOrNull("message") ?: return null,
        id = json.longOrNull("id") ?: 0L,
        positionMs = json.longOrNull("positionMs")?.takeIf { it >= 0 } ?: return null,
        note = BookmarkStore.cleanNote(json.optString("note")),
    )

    private fun watchedOf(json: JSONObject): WatchedRecord? = WatchedRecord(
        chatId = json.longOrNull("chat") ?: return null,
        messageId = json.longOrNull("message") ?: return null,
        fileId = json.longOrNull("file")?.toInt() ?: 0,
        title = json.optString("title"),
        chatTitle = json.optString("chatTitle"),
        sizeBytes = json.longOrNull("size") ?: 0L,
        durationSec = json.longOrNull("durationSec")?.toInt() ?: 0,
        watchedAt = json.longOrNull("watchedAt") ?: return null,
        manual = json.optBoolean("manual", false),
    )

    // ---- merging ----------------------------------------------------------------------------

    /** What [settingsWrites], [watchedWrites] and [metadataAfter] would change, counted. */
    fun plan(
        backup: Backup,
        settings: Map<String, Any>,
        watched: Map<String, Any>,
        metadata: MetaSettings,
        bookmarks: List<Bookmark> = emptyList(),
    ): RestorePlan {
        val writes = settingsWrites(settings, backup)
        val favouritesBefore = favouritesOf(settings)
        val searchesBefore = RecentSearches.decode(settings[RECENT_SEARCHES] as? String)
        val searchesAfter = (writes[RECENT_SEARCHES] as? String)?.let(RecentSearches::decode) ?: searchesBefore
        val metaChanged = listOf(
            backup.metadata["enabled"]?.let { it != metadata.enabled },
            backup.metadata["skip_times"]?.let { it != metadata.skipTimes },
        ).count { it == true }
        return RestorePlan(
            backup = backup,
            settingsChanged = writes.keys.count { it in SETTINGS } + metaChanged,
            favouritesAdded = (backup.favourites - favouritesBefore).size,
            watchedAdded = backup.watched.count { "$WATCHED_PREFIX${it.key}" !in watched },
            watchLaterAdded = backup.watchLater.count { "$WATCH_LATER_PREFIX${it.key}" !in watched },
            bookmarksAdded = bookmarksToAdd(bookmarks, backup).size,
            resumeAdded = writes.keys.count { it.startsWith("resume_") },
            memoryAdded = writes.keys.count { memoryKind(it) != null },
            searchesAdded = searchesAfter.count { found -> searchesBefore.none { it.equals(found, ignoreCase = true) } },
        )
    }

    /**
     * The values to write into the settings store for [backup], given what is in it now: every
     * setting that differs, the favourites joined, the recent searches joined (this device's first),
     * memories this device lacks, and resume points it lacks or holds an older one of.
     */
    fun settingsWrites(device: Map<String, Any>, backup: Backup): Map<String, Any> = buildMap {
        for ((name, value) in backup.settings) {
            if (SETTINGS[name]?.let { fits(value, it) } == true && device[name] != value) put(name, value)
        }

        val favourites = favouritesOf(device)
        if (!favourites.containsAll(backup.favourites)) {
            put(FAVOURITES_KEY, (favourites + backup.favourites).map(Long::toString).toSet())
        }

        val searches = RecentSearches.decode(device[RECENT_SEARCHES] as? String)
        val joined = RecentSearches.decode(RecentSearches.encode(searches + backup.recentSearches))
        if (joined != searches) put(RECENT_SEARCHES, RecentSearches.encode(joined))

        for ((name, value) in backup.memory) {
            if (name !in device && memoryKind(name)?.let { fits(value, it) } == true) put(name, value)
        }

        for (record in backup.resume) {
            val ids = SettingsStore.progressKey(record.chatId, record.messageId)
            val mine = (device["meta_$ids"] as? String)?.let(ResumeRecord::updatedAtOf)
            if ("resume_$ids" in device && mine != null && mine >= record.updatedAt) continue
            put("resume_$ids", record.positionMs)
            if (record.durationMs > 0) put("duration_$ids", record.durationMs)
            put(
                "meta_$ids",
                ResumeRecord.encode(
                    fileId = record.fileId,
                    title = record.title,
                    chatTitle = record.chatTitle,
                    sizeBytes = record.sizeBytes,
                    durationSec = record.durationSec,
                    updatedAt = record.updatedAt,
                    state = record.state,
                ),
            )
        }
    }

    /**
     * The values to write into the watched store: marks it lacks, and newer times for ones it has;
     * watch later entries it lacks, and later added ones for those it has.
     */
    fun watchedWrites(device: Map<String, Any>, backup: Backup): Map<String, Any> = buildMap {
        for (record in backup.watched) {
            val name = WATCHED_PREFIX + record.key
            val mine = WatchedRecord.decode(record.key, device[name] as? String)
            val kept = when {
                mine == null -> record
                record.watchedAt > mine.watchedAt -> record.copy(manual = record.manual || mine.manual)
                record.manual && !mine.manual -> mine.copy(manual = true)
                else -> continue
            }
            put(name, kept.encode())
        }
        for (record in backup.watchLater) {
            val name = WATCH_LATER_PREFIX + record.key
            val mine = WatchLaterRecord.decode(record.key, device[name] as? String)
            if (mine == null || record.addedAt > mine.addedAt) put(name, record.encode())
        }
    }

    /**
     * The backup's bookmarks this device lacks: one per video and moment, a moment being what
     * [BookmarkStore.add] calls one (within [BookmarkStore.SAME_MOMENT_MS]). A bookmark here at the
     * same moment keeps its own note, as memories already here do.
     */
    fun bookmarksToAdd(device: List<Bookmark>, backup: Backup): List<Bookmark> {
        val kept = device.toMutableList()
        return buildList {
            for (mark in backup.bookmarks) {
                val near = kept.any {
                    it.key == mark.key && kotlin.math.abs(it.positionMs - mark.positionMs) < BookmarkStore.SAME_MOMENT_MS
                }
                if (near) continue
                add(mark)
                kept += mark
            }
        }
    }

    /** The poster switches after a restore; the TMDB key and anything it refused stay this device's. */
    fun metadataAfter(current: MetaSettings, backup: Backup): MetaSettings = current.copy(
        enabled = backup.metadata["enabled"] ?: current.enabled,
        skipTimes = backup.metadata["skip_times"] ?: current.skipTimes,
        // Off in a backup was chosen on the device that wrote it, so a restore does not ask again.
        offByChoice = current.offByChoice || backup.metadata["enabled"] == false,
    )
}

/** Writes a value [Backups] produced under its own name, with the key type its value has. */
internal fun MutablePreferences.putRaw(name: String, value: Any) {
    when (value) {
        is Boolean -> this[booleanPreferencesKey(name)] = value
        is Long -> this[longPreferencesKey(name)] = value
        is Float -> this[floatPreferencesKey(name)] = value
        is String -> this[stringPreferencesKey(name)] = value
        is Set<*> -> this[stringSetPreferencesKey(name)] = value.mapTo(HashSet()) { it.toString() }
    }
}
