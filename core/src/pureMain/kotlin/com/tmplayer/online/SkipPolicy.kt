package com.tmplayer.online

import com.tmplayer.player.Chapter

/**
 * The four things a viewer can choose a behaviour for. The opening of an anime and the intro of a
 * show are one choice, as are credits and an ending: [SkipKind] tells them apart only for the
 * button's words.
 */
enum class SkipGroup {
    Recap, Intro, Credits, Preview;

    companion object {
        fun of(kind: SkipKind): SkipGroup = when (kind) {
            SkipKind.Recap -> Recap
            SkipKind.Intro, SkipKind.Opening -> Intro
            SkipKind.Credits, SkipKind.Ending -> Credits
            SkipKind.Preview -> Preview
        }
    }
}

/** What happens when the playhead enters a stretch: a button to press, a countdown, or nothing. */
enum class SkipMode { Ask, Auto, Off }

/**
 * One [SkipMode] per [SkipGroup], [SkipMode.Ask] where nothing was chosen. Kept as one line of
 * text in the settings file (`Recap=Off,Intro=Auto`), which [decode] reads without trusting it.
 */
data class SkipModes(val chosen: Map<SkipGroup, SkipMode> = emptyMap()) {
    fun of(group: SkipGroup): SkipMode = chosen[group] ?: SkipMode.Ask

    fun of(kind: SkipKind): SkipMode = of(SkipGroup.of(kind))

    fun with(group: SkipGroup, mode: SkipMode): SkipModes =
        SkipModes(if (mode == SkipMode.Ask) chosen - group else chosen + (group to mode))

    /** Only what differs from Ask, so the default writes nothing. */
    fun encode(): String = chosen.entries.sortedBy { it.key.ordinal }.joinToString(",") { "${it.key.name}=${it.value.name}" }

    companion object {
        fun decode(text: String?): SkipModes {
            if (text.isNullOrBlank() || text.length > 200) return SkipModes()
            val found = HashMap<SkipGroup, SkipMode>()
            for (part in text.split(',')) {
                val (name, value) = part.split('=', limit = 2).takeIf { it.size == 2 } ?: continue
                val group = SkipGroup.entries.firstOrNull { it.name == name.trim() } ?: continue
                val mode = SkipMode.entries.firstOrNull { it.name == value.trim() } ?: continue
                if (mode != SkipMode.Ask) found[group] = mode
            }
            return SkipModes(found)
        }
    }
}

/**
 * Puts the stretches from every source for one video into the one list the player works from:
 * the viewer's own mark on this video first, then the file's chapters, then the online
 * databases, then a mark carried over from another episode as a guess.
 *
 * A stretch is dropped when it cannot be right: it starts before zero or past the end of the
 * file, ends before it starts or more than two seconds past the end, or is longer than three
 * fifths of the file. One that overlaps a stretch kept from a more trusted source is dropped, and
 * so is one of the same group from a less trusted source (chapters say where the intro is: a
 * database's idea of a second intro is not wanted beside it). Within one source, stretches that
 * do not overlap all stay (two credits, a recap and an intro).
 */
object SkipMerge {
    const val OVERSHOOT_MS = 2_000L

    fun merge(all: List<SkipSegment>, durationMs: Long): List<SkipSegment> {
        val fitted = all.mapNotNull { fit(it, durationMs) }
        val kept = ArrayList<SkipSegment>()
        for (candidate in fitted.sortedWith(compareBy({ it.source.ordinal }, { it.startMs }))) {
            val group = SkipGroup.of(candidate.kind)
            val clash = kept.any { other ->
                overlaps(candidate, other, durationMs) ||
                    (other.source != candidate.source && SkipGroup.of(other.kind) == group)
            }
            if (!clash) kept += candidate
        }
        return kept.sortedBy { it.startMs }
    }

    private fun fit(segment: SkipSegment, durationMs: Long): SkipSegment? {
        if (segment.startMs < 0) return null
        var end = segment.endMs
        if (end != null && end <= segment.startMs) return null
        if (durationMs > 0) {
            if (segment.startMs >= durationMs - SkipSegment.END_MARGIN_MS) return null
            if (end != null) {
                if (end > durationMs + OVERSHOOT_MS) return null
                end = minOf(end, durationMs)
            }
            if ((end ?: durationMs) - segment.startMs > durationMs * 3 / 5) return null
        }
        return if (end == segment.endMs) segment else segment.copy(endMs = end)
    }

    private fun overlaps(a: SkipSegment, b: SkipSegment, durationMs: Long): Boolean {
        val aEnd = a.endMs ?: if (durationMs > 0) durationMs else Long.MAX_VALUE
        val bEnd = b.endMs ?: if (durationMs > 0) durationMs else Long.MAX_VALUE
        return a.startMs < bEnd && b.startMs < aEnd
    }
}

/** The modes the player works to: the viewer's, or Off for every group while "Skip intro buttons" is switched off. */
val MetaSettings.skipModesInForce: SkipModes
    get() = if (skipTimes) skipModes else SkipModes(SkipGroup.entries.associateWith { SkipMode.Off })

/** What the pill should be doing for the playhead's place. */
sealed interface SkipOffer {
    val segment: SkipSegment

    /** A button to press. */
    data class Ask(override val segment: SkipSegment) : SkipOffer

    /** "Skipping in 3", with Cancel: [secondsLeft] counts 3, 2, 1. */
    data class Counting(override val segment: SkipSegment, val secondsLeft: Int) : SkipOffer

    /** The countdown ran out: jump now. */
    data class Fire(override val segment: SkipSegment) : SkipOffer
}

/**
 * Everything one playing video knows about where to skip, for every platform's pill: the file's
 * chapters, what the databases said, the viewer's marks, and the viewer's per kind choice.
 * A platform feeds it ([chapters], [online], [marks], [modes]) as each arrives and asks [offer]
 * on every tick; it keeps no clock of its own, so it is tested by calling it.
 *
 * A countdown runs only while the video plays, so a pause in the middle of an intro neither
 * skips it nor wastes the seconds; Cancel ([cancel]) turns that one stretch back into a plain
 * button. Not thread safe: one UI thread uses it.
 */
class SkipSession(var modes: SkipModes = SkipModes()) {
    private var chapterList: List<Chapter> = emptyList()
    private var onlineList: List<SkipSegment> = emptyList()
    private var markList: List<SkipSegment> = emptyList()
    private var known = 0L
    private var merged: List<SkipSegment> = emptyList()

    private var counting: Long? = null
    private var elapsedMs = 0L
    private var lastMs = 0L
    private val cancelled = HashSet<Long>()

    fun chapters(list: List<Chapter>) { chapterList = list; merged = emptyList(); known = -1 }

    fun online(list: List<SkipSegment>) { onlineList = list; merged = emptyList(); known = -1 }

    fun marks(list: List<SkipSegment>) { markList = list; merged = emptyList(); known = -1 }

    /** The merged list for a file [durationMs] long (see [SkipMerge]). */
    fun segments(durationMs: Long): List<SkipSegment> {
        if (known != durationMs) {
            merged = SkipMerge.merge(ChapterSkips.segments(chapterList, durationMs) + onlineList + markList, durationMs)
            known = durationMs
        }
        return merged
    }

    /**
     * What to show at [positionMs] of a file [durationMs] long, or null for nothing. [skipped] is
     * the starts of stretches already skipped or dismissed; [nowMs] is any clock that only goes
     * forward; [playing] false holds a countdown.
     */
    fun offer(positionMs: Long, durationMs: Long, playing: Boolean, nowMs: Long, skipped: Set<Long> = emptySet()): SkipOffer? {
        if (durationMs <= 0) return null
        val allowed = segments(durationMs).filter { modes.of(it.kind) != SkipMode.Off }
        val segment = SkipSegments.active(allowed, positionMs, durationMs, skipped)
        if (segment == null) {
            counting = null
            return null
        }
        if (modes.of(segment.kind) != SkipMode.Auto || segment.startMs in cancelled) return SkipOffer.Ask(segment)
        if (counting != segment.startMs) {
            counting = segment.startMs
            elapsedMs = 0
            lastMs = nowMs
        }
        if (playing) elapsedMs += (nowMs - lastMs).coerceIn(0, TICK_CAP_MS)
        lastMs = nowMs
        if (elapsedMs >= AUTO_MS) return SkipOffer.Fire(segment)
        return SkipOffer.Counting(segment, ((AUTO_MS - elapsedMs + 999) / 1_000).toInt().coerceIn(1, 3))
    }

    /** Cancel on the countdown: this stretch stays a plain button for the rest of the video. */
    fun cancel(segment: SkipSegment) {
        cancelled += segment.startMs
        counting = null
    }

    companion object {
        const val AUTO_MS = 3_000L

        /** A gap longer than this between two ticks (a stall, a suspended app) does not count as time passed. */
        private const val TICK_CAP_MS = 1_500L
    }
}
