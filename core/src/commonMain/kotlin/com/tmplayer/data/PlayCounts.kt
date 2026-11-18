package com.tmplayer.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Plays started on this device, by ABI, decoder and engine, for the last [DAYS] days: the
 * denominator a stutter rate needs (Sentry triage, 2026-11-18), so "50 reports from OMX.MS" can
 * be read against how often OMX.MS plays at all.
 *
 * There is no weekly upload in the app to carry it (S3's "weekly list" is a query over the
 * stutter events in Sentry), so the counts ride on the stutter event only, as two coarse buckets
 * ([StutterReport.bucketOf]): plays with the same ABI, decoder and engine as the stutter, and all
 * plays. The limit that follows: a device that never stutters sends no counts, so the rate is
 * over devices that reported once, not over every device.
 *
 * Kept only while crash reporting is on ([StutterReporter.played]), in a small private file:
 * the UTC day and, for each day, at most [MAX_KEYS] keys with a count capped at [MAX_COUNT].
 * A key is three short words ([keyOf]), never a title, file or chat. Deleted when reporting is
 * turned off ([clear]).
 */
class PlayCounts(
    private val file: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** Counts one play of [key] today. */
    @Synchronized
    fun note(key: String) {
        val today = clock() / DAY_MILLIS
        val days = read(today)
        val counts = days.getOrPut(today) { LinkedHashMap() }
        if (key !in counts && counts.size >= MAX_KEYS) return
        counts[key] = ((counts[key] ?: 0) + 1).coerceAtMost(MAX_COUNT)
        write(days)
    }

    /** Plays of [key] and of every key in the last [DAYS] days, today included. */
    @Synchronized
    fun week(key: String): Pair<Int, Int> {
        val days = read(clock() / DAY_MILLIS)
        val same = days.values.sumOf { it[key] ?: 0 }
        val all = days.values.sumOf { day -> day.values.sum() }
        return same to all
    }

    /** Forgets every count: reporting turned off. */
    @Synchronized
    fun clear() {
        runCatching { file.delete() }
    }

    /** The days still in the window, each with its counts; anything unreadable is left out. */
    private fun read(today: Long): MutableMap<Long, MutableMap<String, Int>> {
        val out = LinkedHashMap<Long, MutableMap<String, Int>>()
        runCatching {
            if (!file.isFile || file.length() > MAX_FILE_BYTES) return out
            val days = JSONObject(file.readText()).optJSONArray("days") ?: return out
            for (i in 0 until minOf(days.length(), DAYS)) {
                val day = days.optJSONObject(i) ?: continue
                val number = day.optLong("day", -1)
                if (number !in (today - DAYS + 1)..today) continue
                val counts = day.optJSONObject("counts") ?: continue
                val kept = LinkedHashMap<String, Int>()
                for (name in counts.keys()) {
                    if (kept.size >= MAX_KEYS) break
                    if (name.length > MAX_KEY_LENGTH) continue
                    val n = counts.optInt(name, -1)
                    if (n > 0) kept[name] = n.coerceAtMost(MAX_COUNT)
                }
                out[number] = kept
            }
        }
        return out
    }

    private fun write(days: Map<Long, Map<String, Int>>) {
        runCatching {
            file.parentFile?.mkdirs()
            val array = JSONArray()
            for ((day, counts) in days.entries.sortedByDescending { it.key }.take(DAYS)) {
                array.put(JSONObject().put("day", day).put("counts", JSONObject(counts)))
            }
            val partial = File(file.path + ".part")
            partial.writeText(JSONObject().put("days", array).toString())
            partial.renameTo(file)
        }
    }

    companion object {
        const val DAYS = 7
        /** The longest text a report carries. */
        const val MAX_TEXT = 64
        const val MAX_KEYS = 24
        const val MAX_COUNT = 10_000
        private const val MAX_KEY_LENGTH = 3 * MAX_TEXT + 2
        private const val MAX_FILE_BYTES = 64 * 1024L
        private const val DAY_MILLIS = 24L * 60 * 60 * 1000

        /** One key: the ABI, the decoder's name and the engine, each cut as a report's text is. */
        fun keyOf(abi: String, decoder: String, engine: String): String =
            listOf(abi, decoder, engine).joinToString("|") { clean(it).ifEmpty { "unknown" } }

        /**
         * Letters, digits and `. - _ + :` and spaces, at most [MAX_TEXT] characters: no path survives a
         * cut like this. Here, not in StutterReport, which Sentry and org.json keep off iOS, where this
         * class is compiled; [StutterReport.clean] is this.
         */
        fun clean(text: String): String = text.filter { it.isLetterOrDigit() || it in ".-_+: " }.trim().take(MAX_TEXT)
    }
}
