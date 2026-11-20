package com.tmplayer.online

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Online subtitles kept on this device, so that the same search or the same file is never asked
 * for twice: a second download of a file costs nothing from the viewer's daily quota, and a second
 * search costs nothing from the rate limit.
 *
 * Searches are keyed by the file hash or by the parsed name, plus the languages and the machine
 * translation switch; files by provider and file id. Nothing is kept longer than [MAX_TTL_MS], six
 * months, the longest any provider's terms allow; searches go stale sooner, since new uploads
 * arrive. [purge] empties it all, which Settings offers.
 */
class SubtitleCache(
    private val dir: File,
    private val now: () -> Long = System::currentTimeMillis,
    private val searchTtlMs: Long = SEARCH_TTL_MS,
    private val fileTtlMs: Long = MAX_TTL_MS,
    /** The most the cache keeps on the disk; once over it the oldest files go, searches first. */
    private val maxBytes: Long = MAX_BYTES,
) {
    init {
        require(searchTtlMs in 1..MAX_TTL_MS && fileTtlMs in 1..MAX_TTL_MS) { "a cache TTL may not exceed six months" }
    }

    private val searches get() = File(dir, "search")
    private val files get() = File(dir, "files")

    /** A search's hits, if this exact search was made within [searchTtlMs]. */
    @Synchronized
    fun search(key: String): List<SubtitleHit>? {
        val file = File(searches, name(key) + ".json")
        if (!file.isFile) return null
        val json = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return null
        if (now() - json.optLong("saved") > searchTtlMs) {
            file.delete()
            return null
        }
        return SubtitleHit.listFromJson(json.optJSONArray("hits") ?: JSONArray())
    }

    /** Forgets one kept search, so the next one asks the service again. */
    @Synchronized
    fun removeSearch(key: String) {
        File(searches, name(key) + ".json").delete()
    }

    @Synchronized
    fun putSearch(key: String, hits: List<SubtitleHit>) {
        searches.mkdirs()
        val json = JSONObject().put("key", key).put("saved", now()).put("hits", SubtitleHit.listToJson(hits))
        // A search not kept (a full disk) is only asked again next time (HX10).
        runCatching { write(File(searches, name(key) + ".json"), json.toString()) }
            .onFailure { com.tmplayer.platform.Logger.w("SubtitleCache", "search not cached: ${it.message}") }
    }

    /** A downloaded subtitle, if it was fetched within [fileTtlMs]. */
    @Synchronized
    fun file(provider: SubtitleProvider, id: String): File? {
        val found = files.listFiles { f -> f.name.startsWith(fileStem(provider, id) + ".") }?.firstOrNull() ?: return null
        if (now() - found.lastModified() > fileTtlMs) {
            found.delete()
            return null
        }
        return found
    }

    @Synchronized
    fun putFile(provider: SubtitleProvider, id: String, text: SubtitleText.Text): File {
        files.mkdirs()
        files.listFiles { f -> f.name.startsWith(fileStem(provider, id) + ".") }?.forEach { it.delete() }
        val file = File(files, fileStem(provider, id) + "." + text.extension)
        write(file, text.text)
        file.setLastModified(now())
        trim(keep = file)
        return file
    }

    /** Deletes one kept file, such as the half of a translation once the whole one is saved. */
    @Synchronized
    fun removeFile(provider: SubtitleProvider, id: String) {
        files.listFiles { f -> f.name.startsWith(fileStem(provider, id) + ".") }?.forEach { it.delete() }
    }

    /**
     * Brings the cache under [maxBytes]: the oldest searches go first (they are asked again for
     * nothing), then the oldest files, never [keep], the one just written. A subtitle is a few tens of
     * kilobytes, so this seldom does anything; it is the bound the cache promises on a full disk.
     */
    @Synchronized
    fun trim(keep: File? = null) {
        var total = bytes()
        if (total <= maxBytes) return
        val order = (searches.listFiles().orEmpty().sortedBy { it.lastModified() }) +
            (files.listFiles().orEmpty().filter { it != keep }.sortedBy { it.lastModified() })
        for (f in order) {
            if (total <= maxBytes) break
            val size = f.length()
            if (f.delete()) total -= size
        }
    }

    /** Every entry older than its TTL, deleted. Cheap: a cache of subtitles is a few hundred files. */
    @Synchronized
    fun prune() {
        searches.listFiles()?.forEach { f ->
            val saved = runCatching { JSONObject(f.readText()).optLong("saved") }.getOrDefault(0L)
            if (now() - saved > searchTtlMs) f.delete()
        }
        files.listFiles()?.forEach { f -> if (now() - f.lastModified() > fileTtlMs) f.delete() }
        trim()
    }

    /** What the cache holds on the disk, in bytes. */
    @Synchronized
    fun bytes(): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** Every file downloaded from [provider]; its searches age out on their own. */
    @Synchronized
    fun purgeFiles(provider: SubtitleProvider) {
        files.listFiles { f -> f.name.startsWith(provider.name.lowercase() + "-") }?.forEach { it.delete() }
    }

    /** Everything, searches and files. */
    @Synchronized
    fun purge() {
        dir.deleteRecursively()
    }

    private fun write(file: File, text: String) {
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(text)
        if (!temp.renameTo(file)) {
            file.delete()
            temp.renameTo(file)
        }
    }

    private fun fileStem(provider: SubtitleProvider, id: String): String =
        provider.name.lowercase() + "-" + name(id).take(24)

    private fun name(key: String): String =
        MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
        const val MAX_TTL_MS = 180 * DAY_MS
        const val SEARCH_TTL_MS = 30 * DAY_MS

        /** 20 MB: some thousands of subtitle files and their searches. */
        const val MAX_BYTES = 20L * 1024 * 1024
    }
}
