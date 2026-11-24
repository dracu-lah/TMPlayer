package com.tmplayer.data

import com.tmplayer.player.Remedy
import com.tmplayer.player.RemedyAction
import com.tmplayer.player.RemedyPlan

/** A remedy that failed for a class: on [count] videos, the last one [video], in app [version]. */
data class RemedyFail(val count: Int, val video: String, val version: String)

/**
 * What one class ([RemedyClassKey]) learned: the remedies that worked ([working], one per level),
 * the decoder names that failed ([excluded]), the remedies that failed ([fails]), hops, stutters
 * and native crashes counted, and the day it was last seen (days since 1970).
 */
data class RemedyRecord(
    val working: Set<Remedy> = emptySet(),
    val excluded: List<String> = emptyList(),
    val fails: Map<Remedy, RemedyFail> = emptyMap(),
    val hops: Int = 0,
    val stutters: Int = 0,
    val crashes: Int = 0,
    val day: Int = 0,
)

/**
 * The remedy ladder's memory per class (RL2, research 4.3), so the next video of a class starts on
 * the path that worked, with no wait and no stutter. Immutable: every change returns a new memory.
 *
 * - A remedy that played 60 s clean ([worked]) is the class's start, and its failures are forgotten.
 * - A remedy is skipped after it failed on two different videos; in a new app version it is tried
 *   once more (one probe per class after an update), and a failure there skips it again.
 * - One native crash in a class starts its next video on the software decoder, two on the other engine
 *   (as [com.tmplayer.player.EngineSelector.afterFailure] allows); a clean play clears them.
 * - Bounded: [MAX_ENTRIES] classes (the least recently seen dropped), keys of [RemedyClassKey.MAX_LENGTH],
 *   counts capped at [MAX_COUNT], entries unseen for [STALE_DAYS] dropped when read.
 *
 * Stored like [com.tmplayer.player.EngineHistory.encode], one line:
 * `key=working|excluded|hops|stutters|crashes|day|fails;key=...`, with working `sw,au`, excluded
 * `OMX.a,c2.b` and fails `ex~2~ab12~1.30.0+sw~1~cd34~1.30.0`.
 */
class RemedyMemory private constructor(val records: Map<String, RemedyRecord>) {

    /** Where a video of [key] starts in app [version]. */
    fun planFor(key: String, version: String): RemedyPlan {
        val record = records[key] ?: return RemedyPlan()
        val v = cleanVersion(version)
        val skip = record.fails.filter { (_, fail) -> fail.count >= DEMOTE_AFTER && fail.version == v }.keys
        val start = when {
            record.crashes >= 2 -> setOf(Remedy.OtherEngine)
            record.crashes == 1 -> setOf(Remedy.Software)
            else -> record.working
        } - skip
        return RemedyPlan(start, skip, record.excluded.toSet(), record.stutters > 0)
    }

    /**
     * [hop] was taken for [key] on [video] (a short hashed tag, never a title): the hop counted,
     * the remedy it replaced failed, a decoder name left out is kept.
     */
    fun hopped(key: String, hop: RemedyAction.Hop, video: String, version: String, day: Int): RemedyMemory {
        val updated = touch(key, day) { record ->
            val excluded = if (hop.remedy == Remedy.ExcludeDecoder) {
                (record.excluded + hop.path.excluded.map(::cleanName)).filter { it.isNotEmpty() }.distinct().takeLast(MAX_EXCLUDED)
            } else record.excluded
            record.copy(hops = bump(record.hops), excluded = excluded)
        }
        return if (hop.replaced == null) updated else updated.failed(key, hop.replaced, video, version, day)
    }

    /** [remedy] failed for [key] on [video]; the same video failing twice counts once. Null (the defaults) only touches the day. */
    fun failed(key: String, remedy: Remedy?, video: String, version: String, day: Int): RemedyMemory = touch(key, day) { record ->
        if (remedy == null) return@touch record
        val tag = cleanVideo(video)
        val v = cleanVersion(version)
        val old = record.fails[remedy]
        if (old != null && old.video == tag && old.version == v) return@touch record
        val fail = RemedyFail(bump(old?.count ?: 0), tag, v)
        val demoted = fail.count >= DEMOTE_AFTER
        record.copy(fails = record.fails + (remedy to fail), working = if (demoted) record.working - remedy else record.working)
    }

    /** A video of [key] played [com.tmplayer.player.RemedyLadder.WORKED_MS] clean with [taken] in effect. */
    fun worked(key: String, taken: Collection<Remedy>, day: Int): RemedyMemory = touch(key, day) { record ->
        val kept = taken.filter { it != Remedy.LighterCopy }
        val byLevel = kept.groupBy { it.level }.mapValues { (_, list) -> list.last() }
        val working = record.working.filter { it.level !in byLevel }.toSet() + byLevel.values
        record.copy(working = working, fails = record.fails - kept.toSet(), crashes = 0)
    }

    /**
     * [remedy] is known to be the way for [key] without waiting for a clean play, because the
     * failure itself said so (the sound output refused a bitstream, so PCM it is: RL8): it is the
     * class's start at its level and its failures are forgotten. Crash counts are left as they are.
     */
    fun learned(key: String, remedy: Remedy, day: Int): RemedyMemory = touch(key, day) { record ->
        if (remedy == Remedy.LighterCopy) return@touch record
        record.copy(working = record.working.filter { it.level != remedy.level }.toSet() + remedy, fails = record.fails - remedy)
    }

    /**
     * The viewer moved a video of [key] off [remedy] from the player's menu (WP-25, the decoder line
     * back to the device's decoder): it is no longer the class's start. Fails and crashes stay.
     */
    fun unlearned(key: String, remedy: Remedy, day: Int): RemedyMemory = touch(key, day) { record ->
        record.copy(working = record.working - remedy)
    }

    /** A video of [key] got a stutter verdict: its next stutter steps by itself. */
    fun stuttered(key: String, day: Int): RemedyMemory = touch(key, day) { it.copy(stutters = bump(it.stutters)) }

    /** The app died in native code while a video of [key] played (the crash marker, read on the next start). */
    fun crashed(key: String, day: Int): RemedyMemory = touch(key, day) { it.copy(crashes = bump(it.crashes)) }

    fun encode(): String = records.entries.joinToString(";") { (key, r) ->
        listOf(
            r.working.joinToString(",") { it.code },
            r.excluded.joinToString(","),
            r.hops, r.stutters, r.crashes, r.day,
            r.fails.entries.joinToString("+") { (remedy, f) -> "${remedy.code}~${f.count}~${f.video}~${f.version}" },
        ).joinToString("|", prefix = "$key=")
    }

    private fun touch(key: String, day: Int, change: (RemedyRecord) -> RemedyRecord): RemedyMemory {
        val k = cleanKey(key).ifEmpty { return this }
        val out = LinkedHashMap(records)
        val old = out.remove(k) ?: RemedyRecord()
        out[k] = change(old).copy(day = day.coerceAtLeast(0))
        while (out.size > MAX_ENTRIES) out.remove(out.keys.first())
        return RemedyMemory(out)
    }

    companion object {
        val Empty = RemedyMemory(emptyMap())

        const val MAX_ENTRIES = 200
        const val MAX_COUNT = 1_000
        const val STALE_DAYS = 180

        /** Failures on different videos before a remedy is skipped for a class. */
        const val DEMOTE_AFTER = 2

        private const val MAX_EXCLUDED = 3
        private const val MAX_TEXT = MAX_ENTRIES * 400
        private const val NAME_MAX = 48
        private const val VIDEO_MAX = 16
        private const val VERSION_MAX = 24

        /**
         * The stored memory, entries unseen for [STALE_DAYS] before [today] dropped. Anything missing,
         * too long or unreadable is [Empty]; a bad entry or field is skipped and the rest kept.
         */
        fun decode(stored: String?, today: Int): RemedyMemory {
            if (stored.isNullOrBlank() || stored.length > MAX_TEXT) return Empty
            val out = LinkedHashMap<String, RemedyRecord>()
            for (entry in stored.split(';')) {
                val key = cleanKey(entry.substringBefore('=', ""))
                if (key.isEmpty()) continue
                val record = recordOf(entry.substringAfter('=', "").split('|')) ?: continue
                if (record.day < today - STALE_DAYS) continue
                out.remove(key)
                out[key] = record.copy(day = record.day.coerceAtMost(today.coerceAtLeast(0)))
            }
            while (out.size > MAX_ENTRIES) out.remove(out.keys.first())
            return RemedyMemory(out)
        }

        private fun recordOf(fields: List<String>): RemedyRecord? {
            if (fields.size < 6) return null
            fun count(i: Int) = fields.getOrNull(i)?.trim()?.toIntOrNull()?.coerceIn(0, MAX_COUNT) ?: 0
            val day = fields.getOrNull(5)?.trim()?.toIntOrNull()?.takeIf { it >= 0 } ?: return null
            val fails = LinkedHashMap<Remedy, RemedyFail>()
            for (item in fields.getOrNull(6).orEmpty().split('+')) {
                val parts = item.split('~')
                val remedy = Remedy.ofCode(parts.getOrNull(0)?.trim()) ?: continue
                val n = parts.getOrNull(1)?.trim()?.toIntOrNull()?.coerceIn(1, MAX_COUNT) ?: continue
                fails[remedy] = RemedyFail(n, cleanVideo(parts.getOrNull(2).orEmpty()), cleanVersion(parts.getOrNull(3).orEmpty()))
            }
            return RemedyRecord(
                working = fields[0].split(',').mapNotNull { Remedy.ofCode(it.trim()) }
                    .filter { it != Remedy.LighterCopy }.distinctBy { it.level }.toSet(),
                excluded = fields[1].split(',').map(::cleanName).filter { it.isNotEmpty() }.distinct().take(MAX_EXCLUDED),
                fails = fails,
                hops = count(2),
                stutters = count(3),
                crashes = count(4),
                day = day,
            )
        }

        private fun bump(n: Int) = (n + 1).coerceAtMost(MAX_COUNT)

        private fun cleanKey(text: String): String =
            text.trim().lowercase().filter { it in 'a'..'z' || it in '0'..'9' || it in ".-_:/" }.take(RemedyClassKey.MAX_LENGTH)

        private fun cleanName(text: String): String =
            text.trim().filter { it.isLetterOrDigit() && it.code < 128 || it in "._-" }.take(NAME_MAX)

        private fun cleanVideo(text: String): String =
            text.trim().filter { it.isLetterOrDigit() && it.code < 128 }.take(VIDEO_MAX)

        private fun cleanVersion(text: String): String =
            text.trim().filter { it.isLetterOrDigit() && it.code < 128 || it in ".-" }.take(VERSION_MAX)
    }
}
