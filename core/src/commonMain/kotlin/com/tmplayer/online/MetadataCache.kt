package com.tmplayer.online

import com.tmplayer.json.JSONObject
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
    /** The most the pictures may take on the disk; the ones fetched longest ago go first past it. */
    private val imageCapBytes: Long = IMAGE_CAP_BYTES,
) : MetaCacheStore {
    init {
        require(foundTtlMs in 1..MAX_TTL_MS && missTtlMs in 1..MAX_TTL_MS && imageTtlMs in 1..MAX_TTL_MS) {
            "a metadata cache TTL may not exceed six months"
        }
    }

    private val answers get() = File(dir, "answers")
    private val images get() = File(dir, "images")
    private val extras get() = File(dir, "extras")
    private val seasons get() = File(dir, "seasons")
    private val skips get() = File(dir, "skips")
    private val logos get() = File(dir, "logos")

    /** The pictures' bytes on the disk, counted once and then kept up to date, or -1 before the count. */
    private var imageTotal = -1L

    /** A title's logo as learnt within [foundTtlMs] (or [missTtlMs] for a title with none), or null to ask. */
    @Synchronized
    override fun logo(key: String): LogoAnswer? {
        val file = File(logos, name(key) + ".json")
        if (!file.isFile) return null
        val json = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return null
        val url = json.optString("url").ifBlank { null }
        if (now() - json.optLong("saved") > if (url != null) foundTtlMs else missTtlMs) {
            file.delete()
            return null
        }
        return LogoAnswer(url)
    }

    @Synchronized
    override fun logos(limit: Int): List<Pair<String, LogoAnswer>> {
        val files = logos.listFiles { file -> file.isFile && file.name.endsWith(".json") } ?: return emptyList()
        val at = now()
        return files
            .sortedByDescending { it.lastModified() }
            .asSequence()
            .mapNotNull { file ->
                val json = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return@mapNotNull null
                val key = json.optString("key").ifBlank { null } ?: return@mapNotNull null
                val url = json.optString("url").ifBlank { null }
                // A stale one is left for [logo] to drop when it is asked for.
                if (at - json.optLong("saved") > if (url != null) foundTtlMs else missTtlMs) return@mapNotNull null
                key to LogoAnswer(url)
            }
            .take(limit)
            .toList()
    }

    @Synchronized
    override fun putLogo(key: String, answer: LogoAnswer) {
        logos.mkdirs()
        val json = JSONObject().put("key", key).put("saved", now()).put("url", answer.url.orEmpty())
        write(File(logos, name(key) + ".json"), json.toString().toByteArray())
    }

    /**
     * An episode's skip times learnt within [SKIP_FOUND_TTL_MS], or an empty list for one nobody
     * had timed, asked again after [SKIP_MISS_TTL_MS]: the databases are community-run and grow.
     * Null when it has to be asked.
     */
    @Synchronized
    override fun skipTimes(key: String): List<SkipSegment>? {
        val file = File(skips, name(key) + ".json")
        if (!file.isFile) return null
        val json = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return null
        val list = SkipSegment.listFromJson(json.optJSONArray("segments"))
        val ttl = if (list.isEmpty()) SKIP_MISS_TTL_MS else SKIP_FOUND_TTL_MS
        if (now() - json.optLong("saved") > ttl) {
            file.delete()
            return null
        }
        return list
    }

    @Synchronized
    override fun putSkipTimes(key: String, segments: List<SkipSegment>) {
        skips.mkdirs()
        val json = JSONObject().put("key", key).put("saved", now()).put("segments", SkipSegment.listToJson(segments))
        write(File(skips, name(key) + ".json"), json.toString().toByteArray())
    }

    /** What was learnt the last time, if it is still fresh: a result, or null to ask again. */
    @Synchronized
    override fun answer(key: String): MetaResult? {
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
    override fun putAnswer(key: String, result: MetaResult) {
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
    override fun extras(key: String): MetaExtras? {
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
    override fun putExtras(key: String, value: MetaExtras) {
        extras.mkdirs()
        val json = JSONObject().put("key", key).put("saved", now()).put("extras", value.toJson())
        write(File(extras, name(key) + ".json"), json.toString().toByteArray())
    }

    /**
     * A season's episode list learnt within [SEASON_TTL_MS]: a day, since a running show gains an
     * episode a week and the page should say so without waiting months.
     */
    @Synchronized
    override fun season(key: String): List<MetaEpisode>? {
        val file = File(seasons, name(key) + ".json")
        if (!file.isFile) return null
        val json = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return null
        if (now() - json.optLong("saved") > SEASON_TTL_MS) {
            file.delete()
            return null
        }
        val list = json.optJSONArray("episodes") ?: return null
        return MetaSeasonJson.fromJson(list)
    }

    @Synchronized
    override fun putSeason(key: String, episodes: List<MetaEpisode>) {
        seasons.mkdirs()
        val list = MetaSeasonJson.toJson(episodes)
        val json = JSONObject().put("key", key).put("saved", now()).put("episodes", list)
        write(File(seasons, name(key) + ".json"), json.toString().toByteArray())
    }

    /** A picture fetched within [imageTtlMs], by its address. */
    @Synchronized
    override fun image(url: String): File? {
        val file = File(images, name(url))
        if (!file.isFile) return null
        if (now() - file.lastModified() > imageTtlMs) {
            file.delete()
            return null
        }
        return file
    }

    @Synchronized
    override fun putImage(url: String, bytes: ByteArray): File {
        images.mkdirs()
        val file = File(images, name(url))
        // The picture is read back from this file, so here a failed write is the caller's to know.
        if (!write(file, bytes)) throw java.io.IOException("The picture could not be cached")
        file.setLastModified(now())
        trimImages(keep = file)
        return file
    }

    /**
     * The pictures back under [imageCapBytes], oldest fetched first. Without a cap a library of
     * a few hundred titles in the wide and original sizes a television asks for came to gigabytes
     * that no storage figure in the app accounted for (user, 2026-10-18).
     */
    private fun trimImages(keep: File) {
        // The folder is listed once, then the running total kept: listing and measuring a few
        // hundred files on every poster written held every other poster's read behind it.
        if (imageTotal >= 0) {
            imageTotal += keep.length()
            if (imageTotal <= imageCapBytes) return
        }
        val all = images.listFiles()?.filter { it.isFile } ?: return
        var total = all.sumOf { it.length() }
        imageTotal = total
        if (total <= imageCapBytes) return
        for (f in all.sortedBy { it.lastModified() }) {
            if (total <= imageCapBytes) break
            if (f == keep) continue
            val size = f.length()
            if (f.delete()) total -= size
        }
        imageTotal = total
    }

    /** Every entry past its TTL, deleted. Run once at start, off the main thread. */
    @Synchronized
    override fun prune() {
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
        images.listFiles()?.maxByOrNull { it.lastModified() }?.let { trimImages(keep = it) }
    }

    /** What the cache holds on the disk, in bytes. */
    @Synchronized
    override fun bytes(): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** What the pictures alone take on the disk, in bytes: the posters and backdrops. */
    @Synchronized
    override fun imageBytes(): Long = images.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L

    /**
     * The pictures only. The answers stay, so every title keeps its match and its overview and a
     * poster is fetched again only when it is next shown (Free up space, user 2026-10-18).
     */
    @Synchronized
    override fun purgeImages() {
        imageTotal = -1
        images.deleteRecursively()
    }

    /** Everything, answers and pictures. */
    @Synchronized
    override fun purge() {
        imageTotal = -1
        dir.deleteRecursively()
    }

    /**
     * Writes through a temporary file. A full disk or a folder that went away means "not cached"
     * (HX10), never an exception out of a lookup that a tile or a page is waiting on; false then.
     */
    private fun write(file: File, bytes: ByteArray): Boolean {
        val temp = File(file.parentFile, file.name + ".tmp")
        return runCatching {
            temp.writeBytes(bytes)
            if (!temp.renameTo(file)) {
                file.delete()
                temp.renameTo(file)
            }
            file.isFile
        }.getOrElse {
            com.tmplayer.platform.Logger.w("MetadataCache", "not cached: ${it.message}")
            temp.delete()
            false
        }
    }

    private fun name(key: String): String =
        MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        /** How long a season's episode list is trusted: see [season]. */
        const val SEASON_TTL_MS = MetaKeep.SEASON_TTL_MS

        const val DAY_MS = MetaKeep.DAY_MS
        const val MAX_TTL_MS = MetaKeep.MAX_TTL_MS
        const val FOUND_TTL_MS = MetaKeep.FOUND_TTL_MS
        const val MISS_TTL_MS = MetaKeep.MISS_TTL_MS
        const val SKIP_FOUND_TTL_MS = MetaKeep.SKIP_FOUND_TTL_MS
        const val SKIP_MISS_TTL_MS = MetaKeep.SKIP_MISS_TTL_MS

        /** [imageCapBytes] unless told otherwise: posters and backdrops for some hundreds of titles. */
        const val IMAGE_CAP_BYTES = 150L * 1024 * 1024
    }
}
