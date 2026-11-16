package com.tmplayer.online

import com.tmplayer.json.JSONArray
import com.tmplayer.json.JSONObject

/** Where the viewer said an intro starts and ends, in milliseconds. */
data class SkipMark(val startMs: Long, val endMs: Long) {
    /** Whether this can be an intro at all: ends after it starts, 3 seconds to 10 minutes long. */
    val valid: Boolean get() = startMs >= 0 && endMs - startMs in MIN_MS..MAX_MS

    companion object {
        const val MIN_MS = 3_000L
        const val MAX_MS = 10 * 60_000L
    }
}

/** The two taps of "Mark this as the intro", and what each one did. */
sealed interface MarkStep {
    /** The first tap: the intro starts at [startMs]. The next tap ends it. */
    data class Started(val startMs: Long) : MarkStep

    /** The second tap: a finished mark, to be saved. */
    data class Done(val mark: SkipMark) : MarkStep

    /** The second tap came too soon after the first (or before it): the start stays, tap again. */
    data object TooShort : MarkStep

    /** The second tap came more than ten minutes after the start: taken as a new start. */
    data class Restarted(val startMs: Long) : MarkStep
}

/** The state between the two taps. One per playing video; not stored. */
class IntroMarking {
    var startMs: Long? = null
        private set

    val started: Boolean get() = startMs != null

    fun tap(positionMs: Long): MarkStep {
        val start = startMs
        if (start == null || positionMs < start) {
            startMs = positionMs.coerceAtLeast(0)
            return MarkStep.Started(startMs!!)
        }
        val gap = positionMs - start
        if (gap < SkipMark.MIN_MS) return MarkStep.TooShort
        if (gap > SkipMark.MAX_MS) {
            startMs = positionMs
            return MarkStep.Restarted(positionMs)
        }
        startMs = null
        return MarkStep.Done(SkipMark(start, positionMs))
    }

    fun cancel() {
        startMs = null
    }
}

/**
 * The intros the viewer marked, kept on this device and sent nowhere. A mark is kept twice: for
 * the video itself ([segmentsFor] gives it back as the viewer's own word) and for its season of
 * its chat, where every other episode of the season gets it as a guess, so one mark is enough for
 * a season. A newer mark for the season replaces the older.
 *
 * Both lists are bounded and read without trust: a file that is cut short, the wrong shape or
 * absurd loses its bad entries and nothing else.
 */
object UserSkipMarks {

    /** Where the text lives: a file on the platforms that have one. */
    interface Persistence {
        fun read(): String?
        fun write(text: String)
    }

    const val OWN_LIMIT = 2_000
    const val SEASON_LIMIT = 500

    private var persistence: Persistence? = null
    private val own = LinkedHashMap<String, SkipMark>()
    private val season = LinkedHashMap<String, SkipMark>()
    private val lock = com.tmplayer.platform.PlainLock()

    /** Starts keeping marks in [where], reading what is there. */
    fun open(where: Persistence) = lock.locked {
        persistence = where
        own.clear()
        season.clear()
        parse(runCatching { where.read() }.getOrNull())
    }

    /** The key a season of a show in one chat shares, for [save] and [segmentsFor]. Null where nothing says which show. */
    fun seasonKey(chatId: Long, titleKey: String, season: Int?): String? =
        titleKey.takeIf { it.isNotBlank() }?.let { "$chatId|${it.take(80)}|${season ?: 0}" }

    /** [seasonKey] for a video: its chat, show and season, or null for a film, an extra or a video of no show. */
    fun seasonKeyOf(item: com.tmplayer.data.MediaItem): String? {
        val name = item.parsedName
        if (!name.isSeries || name.extra != null) return null
        return seasonKey(item.chatId, name.titleKey, name.season)
    }

    /** Keeps [mark] for [itemId] and, when [seasonKey] is given, as the guess for the rest of the season. */
    fun save(itemId: String, seasonKey: String?, mark: SkipMark): Boolean = lock.locked {
        if (!mark.valid || itemId.isBlank()) return@locked false
        own.remove(itemId)
        own[itemId] = mark
        while (own.size > OWN_LIMIT) own.remove(own.keys.first())
        if (seasonKey != null) {
            season.remove(seasonKey)
            season[seasonKey] = mark
            while (season.size > SEASON_LIMIT) season.remove(season.keys.first())
        }
        persist()
        true
    }

    /** Forgets the mark on [itemId], and the season's guess when it was this one. */
    fun forget(itemId: String, seasonKey: String?) = lock.locked {
        val gone = own.remove(itemId)
        if (seasonKey != null && gone != null && season[seasonKey] == gone) season.remove(seasonKey)
        if (gone != null) persist()
    }

    /** Whether [itemId] has a mark of its own, for the menu line that removes it. */
    fun hasOwn(itemId: String): Boolean = lock.locked { itemId in own }

    /**
     * The stretches the marks give [itemId]: its own mark as the viewer's word, else the season's
     * mark as a guess (never for a file the mark would run past the end of).
     */
    fun segmentsFor(itemId: String, seasonKey: String?, durationMs: Long): List<SkipSegment> = lock.locked {
        own[itemId]?.let { return@locked listOf(SkipSegment(SkipKind.Intro, it.startMs, it.endMs, SkipSource.Mark)) }
        val guess = seasonKey?.let { season[it] } ?: return@locked emptyList()
        if (durationMs > 0 && guess.endMs > durationMs) return@locked emptyList()
        listOf(SkipSegment(SkipKind.Intro, guess.startMs, guess.endMs, SkipSource.Guess))
    }

    /** For tests: nothing kept, nothing stored. */
    fun reset() = lock.locked {
        persistence = null
        own.clear()
        season.clear()
    }

    private fun persist() {
        val json = JSONObject()
            .put("own", write(own))
            .put("season", write(season))
        runCatching { persistence?.write(json.toString()) }
    }

    private fun write(map: Map<String, SkipMark>): JSONArray =
        JSONArray(map.map { (key, mark) -> JSONObject().put("key", key).put("start", mark.startMs).put("end", mark.endMs) })

    private fun parse(text: String?) {
        if (text.isNullOrBlank() || text.length > 4_000_000) return
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return
        read(json.optJSONArray("own"), own, OWN_LIMIT)
        read(json.optJSONArray("season"), season, SEASON_LIMIT)
    }

    private fun read(list: JSONArray?, into: MutableMap<String, SkipMark>, limit: Int) {
        if (list == null) return
        for (i in 0 until minOf(list.length(), limit * 2)) {
            val entry = list.optJSONObject(i) ?: continue
            val key = runCatching { entry.getString("key") }.getOrNull()?.takeIf { it.isNotBlank() && it.length <= 200 } ?: continue
            val mark = runCatching { SkipMark(entry.getLong("start"), entry.getLong("end")) }.getOrNull()?.takeIf { it.valid } ?: continue
            into.remove(key)
            into[key] = mark
        }
        while (into.size > limit) into.remove(into.keys.first())
    }
}

