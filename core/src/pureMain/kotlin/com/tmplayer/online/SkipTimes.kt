package com.tmplayer.online

import com.tmplayer.json.JSONArray
import com.tmplayer.json.JSONObject

/**
 * What a stretch of an episode is. A show's intro and an anime's opening are told apart only for
 * the button's words ("Skip intro", "Skip opening"); they behave the same.
 */
enum class SkipKind { Recap, Intro, Opening, Credits, Ending, Preview }

/**
 * Where a [SkipSegment] came from, which is also how much it is trusted: the viewer's own mark on
 * this very video, then the file's chapters, then an online database, then the viewer's mark on
 * another episode of the season applied as a guess. See [SkipMerge].
 */
enum class SkipSource { Mark, Chapter, Online, Guess }

/**
 * One stretch a viewer may want to jump over, in milliseconds from the start of the file. A null
 * [endMs] runs to the end, which is how credits usually come. [source] is not stored with the
 * answers the databases are asked for (they are all [SkipSource.Online]).
 */
data class SkipSegment(val kind: SkipKind, val startMs: Long, val endMs: Long?, val source: SkipSource = SkipSource.Online) {
    /** Where a skip lands: the segment's end, or the file's for one that runs to it. */
    fun target(durationMs: Long): Long = endMs ?: durationMs

    /** Whether [positionMs] is inside, leaving the last second out so a skip never lands back in it. */
    fun contains(positionMs: Long, durationMs: Long): Boolean {
        val end = target(durationMs)
        return positionMs >= startMs && positionMs < end - END_MARGIN_MS
    }

    /** At the end of the file: credits that roll until it finishes, which a next-up card handles. */
    fun runsToEnd(durationMs: Long): Boolean = endMs == null || (durationMs > 0 && durationMs - endMs < END_MARGIN_MS * 5)

    fun toJson(): JSONObject = JSONObject().put("kind", kind.name).put("start", startMs).put("end", endMs ?: -1L)

    companion object {
        const val END_MARGIN_MS = 1_000L

        fun fromJson(json: JSONObject): SkipSegment? = runCatching {
            SkipSegment(SkipKind.valueOf(json.getString("kind")), json.getLong("start"), json.optLong("end", -1L).takeIf { it >= 0 })
        }.getOrNull()

        fun listToJson(segments: List<SkipSegment>): JSONArray = JSONArray(segments.map { it.toJson() })

        fun listFromJson(array: JSONArray?): List<SkipSegment> =
            if (array == null) emptyList() else (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(::fromJson) }
    }
}

/** The segments of one playing file, and which of them the playhead is in. */
object SkipSegments {
    /**
     * The segment at [positionMs] whose button should show, or null. A segment the viewer has
     * already skipped or dismissed ([skipped], by start) does not come back for this playback.
     */
    fun active(segments: List<SkipSegment>, positionMs: Long, durationMs: Long, skipped: Set<Long> = emptySet()): SkipSegment? =
        segments.firstOrNull { it.startMs !in skipped && it.contains(positionMs, durationMs) }

    /**
     * Where the closing credits begin, for showing the next episode's card then rather than in
     * the last seconds: the credits or ending that run to the end of the file, else null.
     */
    fun creditsStart(segments: List<SkipSegment>, durationMs: Long): Long? =
        segments.filter { (it.kind == SkipKind.Credits || it.kind == SkipKind.Ending) && it.runsToEnd(durationMs) }
            .minOfOrNull { it.startMs }
}
