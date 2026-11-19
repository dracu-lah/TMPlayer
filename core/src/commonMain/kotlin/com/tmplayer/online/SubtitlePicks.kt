package com.tmplayer.online

import com.tmplayer.data.MediaItem
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Which online subtitle the viewer picked for which video (HX25), so reopening the video lays the
 * same one over it without opening the picker, and "Off" is remembered the same way.
 *
 * A pick is kept under every key the video has ([keysOf]): this very post, and the video's own
 * identity (the film's title and year, the show's episode), so a re-post or another copy of the
 * same video finds it. Each pick holds the hit (provider and file id, enough to fetch it again)
 * and a copy of the file itself in [keptDir], outside the cache the system may clear, so a
 * picked subtitle (a few tens of kilobytes) survives low storage and "Free up space"; the copy
 * goes with its pick. At most [maxEntries] picks are kept, the oldest used first out, and a file
 * that is missing, cut short or not what it should be is read as no picks at all, never as an
 * error. It is a small state file, not part of the subtitle cache: purging that cache keeps it.
 */
class SubtitlePicks(
    private val file: File,
    private val keptDir: File = File(file.absoluteFile.parentFile ?: File("."), "picked-subtitles"),
    private val now: () -> Long = System::currentTimeMillis,
    private val maxEntries: Int = MAX_ENTRIES,
) {
    /** What is remembered for a video: [hit] null is "Off"; [kept] is the file's own copy when it is still there. */
    class Pick(val hit: SubtitleHit?, val kept: File?, val at: Long) {
        val off: Boolean get() = hit == null
    }

    private class Entry(val key: String, var at: Long, val hit: SubtitleHit?, val kept: String?)

    private var entries: LinkedHashMap<String, Entry>? = null

    /** The pick for the first of [keys] that has one (the most exact key first), or null. */
    @Synchronized
    fun recall(keys: List<String>): Pick? {
        val all = load()
        for (key in keys) {
            val entry = all[key] ?: continue
            val kept = entry.kept?.let { File(keptDir, it) }?.takeIf { runCatching { it.isFile && it.length() > 0 }.getOrDefault(false) }
            // Used now: the last to go when the list is full (kept in memory, written with the next change).
            all.remove(key)
            entry.at = now()
            all[key] = entry
            return Pick(entry.hit, kept, entry.at)
        }
        return null
    }

    /** Remembers [hit] for the video under [keys], with a copy of [downloaded] when it can be read. */
    @Synchronized
    fun remember(keys: List<String>, hit: SubtitleHit, downloaded: File?) {
        if (keys.isEmpty()) return
        val all = load()
        val copy = downloaded?.let { keep(hit, it) }
        for (key in keys) put(all, Entry(key, now(), hit, copy))
        save(all)
    }

    /** Remembers that the viewer turned subtitles off for the video, replacing any pick. */
    @Synchronized
    fun rememberOff(keys: List<String>) {
        if (keys.isEmpty()) return
        val all = load()
        for (key in keys) put(all, Entry(key, now(), null, null))
        save(all)
    }

    /** Drops an "Off" the viewer has since turned back on by choosing a track; a pick stays. */
    @Synchronized
    fun clearOff(keys: List<String>) {
        val all = load()
        var changed = false
        for (key in keys) if (all[key]?.hit == null && all.containsKey(key)) { all.remove(key); changed = true }
        if (changed) save(all)
    }

    /** Forgets whatever is remembered for [keys]. */
    @Synchronized
    fun forget(keys: List<String>) {
        val all = load()
        var changed = false
        for (key in keys) if (all.remove(key) != null) changed = true
        if (changed) save(all)
    }

    /** Every pick, and the copies kept with them. */
    @Synchronized
    fun clear() {
        entries = LinkedHashMap()
        runCatching { file.delete() }
        runCatching { keptDir.deleteRecursively() }
    }

    /** How many picks are kept. */
    @Synchronized
    fun size(): Int = load().size

    private fun put(all: LinkedHashMap<String, Entry>, entry: Entry) {
        all.remove(entry.key)
        all[entry.key] = entry
        while (all.size > maxEntries) {
            val oldest = all.keys.firstOrNull() ?: break
            all.remove(oldest)
        }
    }

    /** A copy of the downloaded file in [keptDir], named by the hit; null when it cannot be copied. */
    private fun keep(hit: SubtitleHit, downloaded: File): String? = runCatching {
        if (!downloaded.isFile || downloaded.length() !in 1..MAX_FILE_BYTES) return@runCatching null
        keptDir.mkdirs()
        val name = hit.provider.name.lowercase() + "-" + sha(hit.id).take(24) + "." + downloaded.extension.lowercase().ifEmpty { "srt" }
        val target = File(keptDir, name)
        if (target.absolutePath != downloaded.absolutePath) {
            val temp = File(keptDir, "$name.tmp")
            downloaded.copyTo(temp, overwrite = true)
            if (!temp.renameTo(target)) {
                target.delete()
                temp.renameTo(target)
            }
        }
        name
    }.getOrNull()

    private fun load(): LinkedHashMap<String, Entry> {
        entries?.let { return it }
        val read = LinkedHashMap<String, Entry>()
        runCatching {
            if (file.isFile && file.length() <= MAX_STATE_BYTES) {
                val array = JSONObject(file.readText()).optJSONArray("picks") ?: JSONArray()
                for (i in 0 until minOf(array.length(), maxEntries * 2)) {
                    val o = array.optJSONObject(i) ?: continue
                    val key = o.optString("key").takeIf { it.isNotBlank() && it.length <= MAX_KEY } ?: continue
                    val off = o.optBoolean("off")
                    val hit = if (off) null else (o.optJSONObject("hit")?.let(SubtitleHit::fromJson) ?: continue)
                    val kept = o.optString("kept").takeIf { it.isNotBlank() && '/' !in it && '\\' !in it && ".." !in it }
                    read[key] = Entry(key, o.optLong("at"), hit, kept)
                }
            }
        }
        while (read.size > maxEntries) read.remove(read.keys.firstOrNull() ?: break)
        entries = read
        return read
    }

    private fun save(all: LinkedHashMap<String, Entry>) {
        val array = JSONArray()
        for (entry in all.values) {
            val o = JSONObject().put("key", entry.key).put("at", entry.at)
            if (entry.hit == null) o.put("off", true) else o.put("hit", entry.hit.toJson())
            entry.kept?.let { o.put("kept", it) }
            array.put(o)
        }
        // A state file not written (a full disk) only means the pick is asked for again (HX10).
        runCatching {
            file.absoluteFile.parentFile?.mkdirs()
            val temp = File(file.absolutePath + ".tmp")
            temp.writeText(JSONObject().put("v", 1).put("picks", array).toString())
            if (!temp.renameTo(file)) {
                file.delete()
                temp.renameTo(file)
            }
        }.onFailure { com.tmplayer.platform.Logger.w("SubtitlePicks", "picks not saved: ${it.message}") }
        // Copies nothing points to any more go.
        runCatching {
            val used = all.values.mapNotNullTo(HashSet()) { it.kept }
            keptDir.listFiles()?.forEach { if (it.name !in used) it.delete() }
        }
    }

    private fun sha(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        /**
         * Deletes the picks' state file and the copies kept beside it by path, for a sign out
         * where no [SubtitlePicks] is alive (a build without the feature, a failed start). The
         * copies' folder is the one a [SubtitlePicks] on [stateFile] uses by default.
         */
        fun wipe(stateFile: File) {
            runCatching { stateFile.delete() }
            runCatching { File(stateFile.absolutePath + ".tmp").delete() }
            runCatching { File(stateFile.absoluteFile.parentFile ?: return, "picked-subtitles").deleteRecursively() }
        }

        const val MAX_ENTRIES = 400
        private const val MAX_FILE_BYTES = 4L * 1024 * 1024
        private const val MAX_STATE_BYTES = 4L * 1024 * 1024
        private const val MAX_KEY = SubtitlePickKeys.MAX_KEY

        /** The keys a video is remembered under, most exact first: see [SubtitlePickKeys.of], which the web player files its picks by too. */
        fun keysOf(item: MediaItem): List<String> = SubtitlePickKeys.of(item)
    }
}
