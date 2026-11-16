package com.tmplayer.player

/**
 * One step of the remedy ladder as the trace keeps it (reliability research section 6, "Ladder"):
 * when it was taken, what evidence caused it, the remedy code it left ([from], `default` for none)
 * and took ([to]), and how it ended ([result], `pending` until 12 s of play say otherwise).
 */
data class TraceHop(
    val atMs: Long,
    val evidence: String,
    val from: String = "default",
    val to: String = "",
    val result: String = PENDING,
) {

    /** Whether [other] is the same step (same time, evidence and remedy) with a later result. */
    fun sameStep(other: TraceHop): Boolean = atMs == other.atMs && evidence == other.evidence && to == other.to

    /** This hop with every text reduced to [TraceFacts.safeValue] and the time in range. */
    fun clamped(): TraceHop = TraceHop(
        atMs = atMs.coerceIn(0L, TraceLimits.MAX_TIME_MS),
        evidence = TraceFacts.safeValue(evidence, SHORT),
        from = TraceFacts.safeValue(from, SHORT),
        to = TraceFacts.safeValue(to, SHORT),
        result = TraceFacts.safeValue(result, SHORT),
    )

    companion object {
        const val PENDING = "pending"
        const val WORKED = "worked"
        const val FAILED = "failed"
        private const val SHORT = 24
    }
}

/**
 * The facts of one playback session that the numbers of a [TraceSample] cannot say (research
 * section 6): which path the ladder is on, what the decoder claims, what the codec outputs, the
 * counters, the format, the device and the display. A fixed list of keys, [GROUPS], each with a
 * short plain value: there is no key for a title, a file name or a chat, so a trace can be sent as
 * it is. A key not in the list is dropped, so a hand-edited file or a newer app's line cannot add
 * free text to a bundle.
 *
 * Collected so far (the rest of the keys are declared, read, written and bundled, and stay empty
 * until their collector exists): Ladder, Decoder, Codec output, Counters, Format, Device, Display,
 * Crash. Dropped on purpose: `dev.vendorApi`, which an app cannot read (the key stays so a trace
 * from a tool that has it reads). Not collected yet: Timings, Source and Audio, whose numbers live inside the data source
 * and the audio sink and need their own hooks.
 */
object TraceFacts {

    /** Groups in the order a bundle prints them; the keys of each, as a collector writes them. */
    val GROUPS: List<Pair<String, List<String>>> = listOf(
        "Ladder" to listOf("class", "rung", "visited", "hops"),
        "Decoder" to listOf(
            "dec.name", "dec.hw", "dec.sw", "dec.vendor", "dec.alias", "dec.instances", "dec.levels",
            "dec.perf", "dec.fits", "dec.fps",
        ),
        "Codec output" to listOf("out.standard", "out.transfer", "out.range", "out.size", "out.crop", "out.fps", "out.pixfmt"),
        "Counters" to listOf(
            "cnt.rendered", "cnt.dropped", "cnt.skipped", "cnt.dropRun", "cnt.toKeyframe", "cnt.offsetUs", "cnt.inits", "cnt.releases",
        ),
        "Timings" to listOf("t.open", "t.firstFrame", "t.seek", "t.seekFrame", "t.decoderInit"),
        "Format" to listOf(
            "fmt.container", "fmt.codecs", "fmt.size", "fmt.fps", "fmt.bitrate", "fmt.depth", "fmt.color", "fmt.dv",
            "fmt.audio", "fmt.channels", "fmt.passthrough",
        ),
        "Source" to listOf("src.streaming", "src.moov", "src.warm", "src.prefix", "src.restarts", "src.priority", "src.others"),
        "Device" to listOf(
            "dev.soc", "dev.hardware", "dev.board", "dev.model", "dev.device", "dev.sdk", "dev.vendorApi", "dev.bits",
            "dev.lowRam", "dev.memClass", "dev.trim", "dev.thermal", "dev.saver",
        ),
        "Display" to listOf("dsp.modes", "dsp.hdr", "dsp.dv", "dsp.mode", "dsp.match", "dsp.surface"),
        "Audio" to listOf("aud.encodings", "aud.sink", "aud.buffer", "aud.underruns", "aud.bluetooth"),
        "Crash" to listOf("crash.reason", "crash.marker"),
    )

    /** Every key a fact may have. */
    val KEYS: Set<String> = GROUPS.flatMap { it.second }.toSet()

    /** The longest a fact's value is kept. */
    const val MAX_VALUE_CHARS = 96

    /** Facts per line: a line over [TraceLimits.MAX_LINE_CHARS] is skipped by the reader, so a long set is cut into several. */
    const val PER_LINE = 5

    /** A session holds at most this many facts and hops: the keys are fixed, the cap is for a garbage file. */
    const val MAX_HOPS = 8

    /**
     * [raw] reduced to letters, digits and `. , : ; + / = _ - ( )` and spaces, cut at [max]
     * characters. Quotes, newlines, `@`, backslashes and the like never reach a line.
     */
    fun safeValue(raw: String, max: Int = MAX_VALUE_CHARS): String {
        val out = StringBuilder()
        for (c in raw) {
            if (out.length >= max) break
            out.append(if (c.isLetterOrDigit() && c.code < 128 || c in ".,:;+/=_-() ") c else '_')
        }
        return out.toString().trim()
    }

    /** [facts] with unknown keys dropped and every value made safe; an empty value is dropped too. */
    fun clean(facts: Map<String, String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for ((key, value) in facts) {
            if (key !in KEYS) continue
            val v = safeValue(value)
            if (v.isNotEmpty()) out[key] = v
        }
        return out
    }

    /**
     * A bundle's lines for one session: the facts grouped under their titles, then the hops, oldest
     * first. Empty groups are left out; nothing at all gives no lines.
     */
    fun lines(facts: Map<String, String>, hops: List<TraceHop>): List<String> = buildList {
        for ((title, keys) in GROUPS) {
            val present = keys.filter { facts[it]?.isNotEmpty() == true }
            if (present.isEmpty()) continue
            add("  $title: " + present.joinToString(", ") { "$it=${facts.getValue(it)}" })
        }
        val first = hops.firstOrNull()?.atMs ?: return@buildList
        for (hop in hops) {
            val at = ((hop.atMs - first).coerceAtLeast(0L) / 1000)
            add("  Hop +${at}s ${hop.evidence}: ${hop.from} -> ${hop.to.ifEmpty { "?" }}, ${hop.result}")
        }
    }
}
