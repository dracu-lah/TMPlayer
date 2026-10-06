package com.tmplayer.online

import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Posters and overviews kept on this device, so a title is asked for once and a poster fetched
 * once: the rate limits stay untouched while scrolling, and what was found keeps showing with no
 * connection at all.
 *
 * Answers are keyed by [MetaQuery.cacheKey] (the cleaned title, year, episode and language),
 * pictures by their address. Nothing is kept longer than [MAX_TTL_MS], six months, which is
 * TMDB's limit; a "nothing matched" is kept for [MISS_TTL_MS] only, so a show that is new to the
 * providers turns up within the week. [purge] empties it all, which Settings offers.
 */
class MetadataCache(
    private val dir: File,
    private val now: () -> Long = System::currentTimeMillis,
    private val foundTtlMs: Long = FOUND_TTL_MS,
    private val missTtlMs: Long = MISS_TTL_MS,
    private val imageTtlMs: Long = MAX_TTL_MS,
) {
    init {
        require(foundTtlMs in 1..MAX_TTL_MS && missTtlMs in 1..MAX_TTL_MS && imageTtlMs in 1..MAX_TTL_MS) {
            "a metadata cache TTL may not exceed six months"
        }
    }

    private val answers get() = File(dir, "answers")
    private val images get() = File(dir, "images")
    private val extras get() = File(dir, "extras")
    private val seasons get() = File(dir, "seasons")

    /** What was learnt the last time, if it is still fresh: a result, or null to ask again. */
    @Synchronized
    fun answer(key: String): MetaResult? {
        val file = File(answers, name(key) + ".json")
        if (!file.isFile) return null
        val json = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return null
        val info = json.optJSONObject("info")?.let { MetaInfo.fromJson(it) }
        val ttl = if (info != null) foundTtlMs else missTtlMs
        if (now() - json.optLong("saved") > ttl) {
            file.delete()
            return null
        }
        return if (info != null) MetaResult.Found(info) else MetaResult.NoMatch
    }

    @Synchronized
    fun putAnswer(key: String, result: MetaResult) {
        val info = when (result) {
            is MetaResult.Found -> result.info
            MetaResult.NoMatch -> null
            else -> return
        }
        answers.mkdirs()
        val json = JSONObject().put("key", key).put("saved", now())
        if (info != null) json.put("info", info.toJson())
        write(File(answers, name(key) + ".json"), json.toString().toByteArray())
    }

    /** A detail page's extras learnt within [foundTtlMs], by [OnlineMetadata]'s extras key. */
    @Synchronized
    fun extras(key: String): MetaExtras? {
        val file = File(extras, name(key) + ".json")
        if (!file.isFile) return null
        val json = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return null
        if (now() - json.optLong("saved") > foundTtlMs) {
            file.delete()
            return null
        }
        return json.optJSONObject("extras")?.let { MetaExtras.fromJson(it) }
    }

    @Synchronized
    fun putExtras(key: String, value: MetaExtras) {
        extras.mkdirs()
        val json = JSONObject().put("key", key).put("saved", now()).put("extras", value.toJson())
        write(File(extras, name(key) + ".json"), json.toString().toByteArray())
    }

    /**
     * A season's episode list learnt within [SEASON_TTL_MS]: a day, since a running show gains an
     * episode a week and the page should say so without waiting months.
     */
    @Synchronized
    fun season(key: String): List<MetaEpisode>? {
        val file = File(seasons, name(key) + ".json")
        if (!file.isFile) return null
        val json = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return null
        if (now() - json.optLong("saved") > SEASON_TTL_MS) {
            file.delete()
            return null
        }
        val list = json.optJSONArray("episodes") ?: return null
        return (0 until list.length()).mapNotNull { list.optJSONObject(it) }.map {
            MetaEpisode(
                season = it.optInt("season"),
                number = it.optInt("number"),
                name = it.optString("name"),
                overview = it.optString("overview"),
                stillUrl = it.optString("still").ifBlank { null },
                airDate = it.optString("airDate").ifBlank { null },
                runtimeMin = it.optInt("runtime").takeIf { r -> r > 0 },
            )
        }
    }

    @Synchronized
    fun putSeason(key: String, episodes: List<MetaEpisode>) {
        seasons.mkdirs()
        val list = org.json.JSONArray(
            episodes.map {
                JSONObject().put("season", it.season).put("number", it.number).put("name", it.name).put("overview", it.overview)
                    .put("still", it.stillUrl.orEmpty()).put("airDate", it.airDate.orEmpty()).put("runtime", it.runtimeMin ?: 0)
            },
        )
        val json = JSONObject().put("key", key).put("saved", now()).put("episodes", list)
        write(File(seasons, name(key) + ".json"), json.toString().toByteArray())
    }

    /** A picture fetched within [imageTtlMs], by its address. */
    @Synchronized
    fun image(url: String): File? {
        val file = File(images, name(url))
        if (!file.isFile) return null
        if (now() - file.lastModified() > imageTtlMs) {
            file.delete()
            return null
        }
        return file
    }

    @Synchronized
    fun putImage(url: String, bytes: ByteArray): File {
        images.mkdirs()
        val file = File(images, name(url))
        write(file, bytes)
        file.setLastModified(now())
        return file
    }

    /** Every entry past its TTL, deleted. Run once at start, off the main thread. */
    @Synchronized
    fun prune() {
        answers.listFiles()?.forEach { f ->
            val json = runCatching { JSONObject(f.readText()) }.getOrNull()
            val ttl = if (json?.has("info") == true) foundTtlMs else missTtlMs
            if (json == null || now() - json.optLong("saved") > ttl) f.delete()
        }
        extras.listFiles()?.forEach { f ->
            val json = runCatching { JSONObject(f.readText()) }.getOrNull()
            if (json == null || now() - json.optLong("saved") > foundTtlMs) f.delete()
        }
        seasons.listFiles()?.forEach { f ->
            val json = runCatching { JSONObject(f.readText()) }.getOrNull()
            if (json == null || now() - json.optLong("saved") > SEASON_TTL_MS) f.delete()
        }
        images.listFiles()?.forEach { f -> if (now() - f.lastModified() > imageTtlMs) f.delete() }
    }

    /** What the cache holds on the disk, in bytes. */
    @Synchronized
    fun bytes(): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** Everything, answers and pictures. */
    @Synchronized
    fun purge() {
        dir.deleteRecursively()
    }

    private fun write(file: File, bytes: ByteArray) {
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeBytes(bytes)
        if (!temp.renameTo(file)) {
            file.delete()
            temp.renameTo(file)
        }
    }

    private fun name(key: String): String =
        MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        /** How long a season's episode list is trusted: see [season]. */
        const val SEASON_TTL_MS = 24L * 60 * 60 * 1000

        const val DAY_MS = 24L * 60 * 60 * 1000
        const val MAX_TTL_MS = 180 * DAY_MS
        const val FOUND_TTL_MS = 60 * DAY_MS
        const val MISS_TTL_MS = 7 * DAY_MS
    }
}
