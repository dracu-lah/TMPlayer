package com.tmplayer.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Base64

/**
 * Home as it was last built, written to the disk so the next cold start draws it at once, with no
 * placeholder strips, and then brings it up to date in the background (user, 2026-10-21: "cache
 * once added; it's cached until refreshed").
 *
 * What is kept is what Telegram answered, before Home's rules ([HomeRows]) are applied: each
 * chat's newest videos, the search across chats, the chats that had more, and the videos behind
 * Continue watching and Watch later. The rules run again over it on every frame, so a video since
 * watched, a size limit since changed or a chat the kids lock now hides is right from the cached
 * copy too. The videos keep their blurred previews, so the first frame has pictures, and the
 * online posters are on the disk already ([com.tmplayer.online.MetadataCache]).
 *
 * TDLib's file ids are kept as they are: with its file database on they hold across restarts,
 * and the player checks a file id against the message's size before trusting it
 * ([Td.fileIdFits]). Telegram's lighter copies and seek thumbnails are left out; the refresh a
 * moment later brings them back.
 *
 * One JSON file, written whole through a temporary file, read once per process. Forgotten on sign
 * out ([clear]), since it is one account's videos.
 */
object HomeCache {

    /** Where it lives; null (a test, a build that never set it) keeps nothing. Set once at start. */
    @Volatile
    var file: File? = null

    /** What Home had, ready to draw. */
    data class Snapshot(
        val loaded: Map<Long, List<MediaItem>>,
        val recent: List<MediaItem>?,
        val partial: Set<Long>,
        val art: Map<String, MediaItem>,
        /** When it was written, in milliseconds since the epoch. */
        val savedAt: Long,
        /**
         * Home's chats as they were picked, so Home draws its rows before the chat list has come
         * (HX8): an account with thousands of chats waited for its whole list first.
         */
        val chats: List<ChatSummary> = emptyList(),
    ) {
        val isEmpty: Boolean get() = loaded.isEmpty() && recent == null && art.isEmpty()
    }

    @Volatile
    private var memory: Snapshot? = null

    @Volatile
    private var read = false

    /**
     * The last snapshot, read off the disk the first time and from memory after. Cheap to call
     * from the main thread once [preload] has run; otherwise the first call reads the file.
     */
    fun load(): Snapshot? {
        if (read) return memory
        synchronized(this) {
            if (read) return memory
            memory = runCatching { file?.takeIf { it.isFile }?.readText()?.let(::decode) }.getOrNull()
            read = true
            return memory
        }
    }

    /** Reads the file now, off the main thread, so Home's first frame finds it in memory. */
    fun preload() {
        load()
    }

    /** Writes [snapshot] over the last one. Skipped when nothing changed. */
    fun save(snapshot: Snapshot) {
        val target = file ?: return
        val before = memory
        if (before != null && before.loaded == snapshot.loaded && before.recent == snapshot.recent &&
            before.partial == snapshot.partial && before.art == snapshot.art && before.chats == snapshot.chats
        ) {
            return
        }
        synchronized(this) {
            memory = snapshot
            read = true
            runCatching {
                target.parentFile?.mkdirs()
                val temp = File(target.parentFile, target.name + ".tmp")
                temp.writeText(encode(snapshot))
                if (!temp.renameTo(target)) {
                    target.delete()
                    temp.renameTo(target)
                }
            }
        }
    }

    /** Forgets it, on the disk and in memory: for sign out. */
    fun clear() {
        synchronized(this) {
            memory = null
            read = true
            runCatching { file?.delete() }
        }
    }

    // ---- the format ---------------------------------------------------------------------------

    private const val VERSION = 1

    fun encode(snapshot: Snapshot): String = JSONObject()
        .put("v", VERSION)
        .put("saved", snapshot.savedAt)
        .put("chats", JSONArray(snapshot.loaded.map { (chatId, items) -> JSONObject().put("id", chatId).put("items", items.toJson()) }))
        .put("recent", snapshot.recent?.toJson() ?: JSONObject.NULL)
        .put("partial", JSONArray(snapshot.partial.toList()))
        .put("art", JSONArray(snapshot.art.map { (key, item) -> JSONObject().put("key", key).put("item", item.toJson()) }))
        .put("homeChats", JSONArray(snapshot.chats.map { it.toJson() }))
        .toString()

    /** Anything unreadable is dropped: it is a cache, and Home builds itself from Telegram anyway. */
    fun decode(text: String): Snapshot? {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return null
        if (json.optInt("v") != VERSION) return null
        val loaded = LinkedHashMap<Long, List<MediaItem>>()
        objects(json.optJSONArray("chats")).forEach { chat ->
            loaded[chat.optLong("id")] = items(chat.optJSONArray("items"))
        }
        val recent = if (json.isNull("recent")) null else items(json.optJSONArray("recent"))
        val partial = json.optJSONArray("partial")?.let { list -> (0 until list.length()).mapTo(HashSet()) { list.optLong(it) } }.orEmpty()
        val art = LinkedHashMap<String, MediaItem>()
        objects(json.optJSONArray("art")).forEach { entry ->
            val item = entry.optJSONObject("item")?.let(::item) ?: return@forEach
            art[entry.optString("key")] = item
        }
        val chats = objects(json.optJSONArray("homeChats")).mapNotNull(::chat)
        return Snapshot(loaded, recent, partial, art, json.optLong("saved"), chats)
    }

    /** A chat as Home's rows need it: no picture, which the chat list brings once it is in. */
    private fun ChatSummary.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("t", title)
        .put("k", kind.name)
        .put("pin", isPinned)

    private fun chat(json: JSONObject): ChatSummary? {
        val kind = ChatKind.entries.firstOrNull { it.name == json.optString("k") } ?: return null
        return ChatSummary(
            id = json.optLong("id"),
            title = json.optString("t"),
            miniThumbnail = null,
            photoFileId = 0,
            kind = kind,
            isPinned = json.optBoolean("pin"),
        )
    }

    /** One video in this file's spelling, for the other caches on the disk ([ChatGridDisk]). */
    internal fun itemJson(item: MediaItem): JSONObject = item.toJson()

    /** One video read back from [itemJson], or null when it cannot be read. */
    internal fun itemOf(json: JSONObject): MediaItem? = item(json)

    private fun List<MediaItem>.toJson(): JSONArray = JSONArray(map { it.toJson() })

    private fun MediaItem.toJson(): JSONObject = JSONObject()
        .put("c", chatId)
        .put("m", messageId)
        .put("f", fileId)
        .put("t", title)
        .put("s", sizeBytes)
        .put("d", durationSec)
        .put("mt", mimeType)
        .put("th", thumbnailFileId)
        .put("mini", miniThumbnail?.let { Base64.getEncoder().encodeToString(it) } ?: "")
        .put("dt", date)
        .put("fn", fileName)
        .put("loc", locality.name)
        .put("save", canBeSaved)
        .put("cap", caption)
        .put("w", width)
        .put("h", height)
        .put("topic", topicId)
        .put("extra", extra?.name ?: "")
        .put("album", albumId)
        .put("start", startSec)
        .apply {
            if (versions.isNotEmpty()) put("versions", versions.toJson())
            if (parts.isNotEmpty()) put("parts", parts.toJson())
            if (pieces.isNotEmpty()) put("pieces", pieces.toJson())
        }

    private fun items(list: JSONArray?): List<MediaItem> = objects(list).mapNotNull(::item)

    private fun item(json: JSONObject): MediaItem? = runCatching {
        val locality = MediaItem.Locality.entries.firstOrNull { it.name == json.optString("loc") } ?: MediaItem.Locality.Remote
        MediaItem(
            chatId = json.getLong("c"),
            messageId = json.getLong("m"),
            fileId = json.getInt("f"),
            title = json.optString("t"),
            sizeBytes = json.optLong("s"),
            durationSec = json.optInt("d"),
            mimeType = json.optString("mt"),
            thumbnailFileId = json.optInt("th"),
            miniThumbnail = json.optString("mini").takeIf { it.isNotEmpty() }?.let { Base64.getDecoder().decode(it) },
            date = json.optInt("dt"),
            fileName = json.optString("fn"),
            onDevice = locality != MediaItem.Locality.Remote,
            locality = locality,
            canBeSaved = json.optBoolean("save", true),
            caption = json.optString("cap"),
            width = json.optInt("w"),
            height = json.optInt("h"),
            versions = items(json.optJSONArray("versions")),
            topicId = json.optInt("topic"),
            parts = items(json.optJSONArray("parts")),
            extra = ExtraKind.entries.firstOrNull { it.name == json.optString("extra") },
            albumId = json.optLong("album"),
            pieces = items(json.optJSONArray("pieces")),
            startSec = json.optInt("start"),
        )
    }.getOrNull()

    private fun objects(list: JSONArray?): List<JSONObject> =
        if (list == null) emptyList() else (0 until list.length()).mapNotNull { list.optJSONObject(it) }
}
