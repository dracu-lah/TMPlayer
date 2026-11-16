package com.tmplayer.player

import com.tmplayer.json.JSONObject

/**
 * One reading of how a playback session is going (reliability plan L1): what the player, the
 * decoder, the data source and the device said over the last [intervalMs]. A run of these is what
 * tells a slow line from a slow decoder ([StutterVerdict]).
 *
 * Numbers only, plus two short machine names (decoder, engine). There is deliberately no field for
 * a title, a file name or a chat, so a trace can be sent as it is. [clamped] is what the recorder
 * and the reader both apply, so a bad value from a driver or a hand-edited file cannot reach the
 * arithmetic.
 */
data class TraceSample(
    /** Wall-clock time of the reading, epoch milliseconds. */
    val atMs: Long,
    /** Length of the window this reading covers, milliseconds. */
    val intervalMs: Long = 0L,
    /** Frames dropped since the last reading. */
    val droppedFrames: Int = 0,
    /** Times playback ran dry (a rebuffer) since the last reading. */
    val underruns: Int = 0,
    /** Media buffered ahead of the position, milliseconds. */
    val bufferAheadMs: Long = 0L,
    /** The video's bitrate, kilobits a second, or 0 when not known. */
    val bitrateKbps: Int = 0,
    /** The decoder's codec name, such as `c2.qti.avc.decoder`; limited to [TraceLimits.NAME_CHARS] of a safe alphabet. */
    val decoder: String = "",
    val hardware: Boolean = true,
    /** Milliseconds the data source spent blocked waiting for TDLib's bytes since the last reading. */
    val readWaitMs: Long = 0L,
    /** Data source reopens and seeks since the last reading. */
    val reopens: Int = 0,
    /** TDLib's download speed, bytes a second. */
    val tdSpeedBps: Long = 0L,
    /** The app's CPU use as a share of the device, 0..100, or [UNKNOWN] when it could not be read. */
    val cpuPercent: Int = UNKNOWN,
    /** The last `onTrimMemory` level seen (0 when none), see [TraceLimits.MEMORY_PRESSURE_LEVEL]. */
    val memoryLevel: Int = 0,
    /** Android's thermal status 0..6 (API 29+), or [UNKNOWN]. */
    val thermal: Int = UNKNOWN,
    /** `exoplayer`, `vlc`, `mpv` and so on. */
    val engine: String = "",
) {

    /** This sample with every value in its sane range and the two names reduced to a safe alphabet. */
    fun clamped(): TraceSample = TraceSample(
        atMs = atMs.coerceIn(0L, TraceLimits.MAX_TIME_MS),
        intervalMs = intervalMs.coerceIn(0L, TraceLimits.MAX_INTERVAL_MS),
        droppedFrames = droppedFrames.coerceIn(0, TraceLimits.MAX_COUNT),
        underruns = underruns.coerceIn(0, TraceLimits.MAX_COUNT),
        bufferAheadMs = bufferAheadMs.coerceIn(0L, TraceLimits.MAX_BUFFER_MS),
        bitrateKbps = bitrateKbps.coerceIn(0, TraceLimits.MAX_BITRATE_KBPS),
        decoder = TraceLimits.safeName(decoder),
        hardware = hardware,
        readWaitMs = readWaitMs.coerceIn(0L, TraceLimits.MAX_INTERVAL_MS),
        reopens = reopens.coerceIn(0, TraceLimits.MAX_COUNT),
        tdSpeedBps = tdSpeedBps.coerceIn(0L, TraceLimits.MAX_SPEED_BPS),
        cpuPercent = if (cpuPercent < 0) UNKNOWN else cpuPercent.coerceAtMost(100),
        memoryLevel = memoryLevel.coerceIn(0, TraceLimits.MAX_MEMORY_LEVEL),
        thermal = if (thermal < 0) UNKNOWN else thermal.coerceAtMost(TraceLimits.MAX_THERMAL),
        engine = TraceLimits.safeName(engine),
    )

    companion object {
        /** "Not measured", for the readings a device may not give. */
        const val UNKNOWN = -1
    }
}

/** The ranges a [TraceSample] is held to, and the bounds of the ring and the reader. */
object TraceLimits {
    /** Year 2100, in epoch milliseconds. */
    const val MAX_TIME_MS = 4_102_444_800_000L
    const val MAX_INTERVAL_MS = 3_600_000L
    const val MAX_COUNT = 1_000_000
    const val MAX_BUFFER_MS = 86_400_000L
    const val MAX_BITRATE_KBPS = 2_000_000
    const val MAX_SPEED_BPS = 10_000_000_000L
    const val MAX_MEMORY_LEVEL = 100
    const val MAX_THERMAL = 6
    const val NAME_CHARS = 64

    /** Android's `TRIM_MEMORY_RUNNING_LOW`: the device is short of memory while this app is in front. */
    const val MEMORY_PRESSURE_LEVEL = 10

    /** Android's `THERMAL_STATUS_SEVERE`: the system is throttling. */
    const val THERMAL_SEVERE = 3

    /** A sample every 5 s, an hour of playback is 720 of them. */
    const val MAX_SAMPLES_PER_SESSION = 720
    const val MAX_SESSIONS = 5

    /** What the ring keeps, in characters of JSON lines. */
    const val MAX_BYTES = 512 * 1024

    /** A line longer than this is not one of ours; the reader skips it unparsed. */
    const val MAX_LINE_CHARS = 1024

    /** Only a codec name or an engine id fits: letters, digits, dot, dash, underscore. */
    fun safeName(raw: String): String {
        val out = StringBuilder()
        for (c in raw) {
            if (out.length >= NAME_CHARS) break
            out.append(if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '.' || c == '-' || c == '_') c else '_')
        }
        return out.toString()
    }
}

/**
 * One playback: when it began, its readings (oldest first), the [facts] the readings cannot say
 * ([TraceFacts]) and the ladder's [hops]. Both are empty for a session an older app wrote.
 */
data class TraceSession(
    val startedAtMs: Long,
    val samples: List<TraceSample>,
    val facts: Map<String, String> = emptyMap(),
    val hops: List<TraceHop> = emptyList(),
)

/**
 * The JSON-lines form of a trace: a `session` header line, an `f` line of facts and `h` lines of
 * ladder hops when there are any, then one `s` line per sample.
 *
 * ```
 * {"t":"session","v":2,"at":1763200000000}
 * {"t":"f","at":1763200000500,"k":{"dec.name":"c2.qti.avc.decoder","class":"..."}}
 * {"t":"h","at":1763200003000,"ev":"Frozen","from":"default","to":"ex","res":"pending"}
 * {"t":"s","at":1763200005000,"iv":5000,"df":0,"ur":0,"ba":8200,...}
 * ```
 *
 * Version 2 added the `f` and `h` lines. The reader takes both versions (and a future one: it
 * reads the lines and fields it knows and skips the rest), so a file an older app left and a file
 * with new kinds of line in it both read.
 *
 * A recorder appends one line at a time, so a process killed mid-write leaves a file whose only
 * damage is a cut last line, which [read] skips.
 */
object PlaybackTrace {

    const val VERSION = 2

    fun sessionLine(startedAtMs: Long): String =
        JSONObject().put("t", "session").put("v", VERSION).put("at", startedAtMs.coerceIn(0L, TraceLimits.MAX_TIME_MS)).toString()

    fun sampleLine(sample: TraceSample): String {
        val s = sample.clamped()
        return JSONObject()
            .put("t", "s")
            .put("at", s.atMs)
            .put("iv", s.intervalMs)
            .put("df", s.droppedFrames)
            .put("ur", s.underruns)
            .put("ba", s.bufferAheadMs)
            .put("br", s.bitrateKbps)
            .put("dc", s.decoder)
            .put("hw", s.hardware)
            .put("rw", s.readWaitMs)
            .put("ro", s.reopens)
            .put("sp", s.tdSpeedBps)
            .put("cp", s.cpuPercent)
            .put("mm", s.memoryLevel)
            .put("th", s.thermal)
            .put("en", s.engine)
            .toString()
    }

    /** The facts lines of a session: [facts] cleaned and cut into lines of [TraceFacts.PER_LINE], at [atMs]. */
    fun factsLines(atMs: Long, facts: Map<String, String>): List<String> =
        TraceFacts.clean(facts).entries.chunked(TraceFacts.PER_LINE).map { chunk ->
            val keys = JSONObject()
            for ((k, v) in chunk) keys.put(k, v)
            JSONObject().put("t", "f").put("at", atMs.coerceIn(0L, TraceLimits.MAX_TIME_MS)).put("k", keys).toString()
        }

    fun hopLine(hop: TraceHop): String {
        val h = hop.clamped()
        return JSONObject().put("t", "h").put("at", h.atMs).put("ev", h.evidence).put("from", h.from).put("to", h.to).put("res", h.result).toString()
    }

    /** The whole file's text for [sessions], one line each, the newest session last. */
    fun encode(sessions: List<TraceSession>): String = buildString {
        for (session in sessions) {
            append(sessionLine(session.startedAtMs)).append('\n')
            for (line in factsLines(session.startedAtMs, session.facts)) append(line).append('\n')
            for (hop in session.hops.takeLast(TraceFacts.MAX_HOPS)) append(hopLine(hop)).append('\n')
            for (sample in session.samples) append(sampleLine(sample)).append('\n')
        }
    }

    /**
     * Reads a trace file back. Never throws: a line that is cut, not JSON, the wrong type, too
     * long or from the future (a time after 2100) is skipped and the rest is kept. Samples before any header (the head
     * of the file was cut off) form a session of their own. Times that run backwards are held at
     * the last good time, so a session stays in order. The result is held to the ring's caps, the
     * newest kept.
     */
    fun read(
        text: String,
        maxSessions: Int = TraceLimits.MAX_SESSIONS,
        maxSamples: Int = TraceLimits.MAX_SAMPLES_PER_SESSION,
    ): List<TraceSession> {
        if (maxSessions <= 0 || maxSamples <= 0) return emptyList()
        val sessions = ArrayDeque<Reading>()
        var last = 0L
        // A huge file: look only at the end, which is where the newest readings are.
        var from = if (text.length > MAX_READ_CHARS) text.length - MAX_READ_CHARS else 0
        if (from > 0) {
            val nl = text.indexOf('\n', from)
            from = if (nl < 0) text.length else nl + 1
        }
        var pos = from
        while (pos < text.length) {
            var end = text.indexOf('\n', pos)
            if (end < 0) end = text.length
            val lineEnd = if (end > pos && text[end - 1] == '\r') end - 1 else end
            if (lineEnd - pos in 2..TraceLimits.MAX_LINE_CHARS) {
                val obj = try {
                    JSONObject(text.substring(pos, lineEnd))
                } catch (e: Exception) {
                    null
                }
                if (obj != null) {
                    when (obj.optString("t")) {
                        "session" -> if (timeOk(obj)) {
                            val at = obj.optLong("at", 0L).coerceAtLeast(last)
                            last = at
                            sessions.addLast(Reading(at))
                            while (sessions.size > maxSessions) sessions.removeFirst()
                        }
                        "s" -> if (timeOk(obj)) {
                            val sample = sampleOf(obj).clamped()
                            val at = sample.atMs.coerceAtLeast(last)
                            last = at
                            if (sessions.isEmpty()) sessions.addLast(Reading(at))
                            val samples = sessions.last().samples
                            samples.addLast(sample.copy(atMs = at))
                            if (samples.size > maxSamples) samples.removeFirst()
                        }
                        // Version 2's lines. One with no session before it (the head was cut off) is skipped.
                        "f" -> if (timeOk(obj)) sessions.lastOrNull()?.facts?.putAll(factsOf(obj))
                        "h" -> if (timeOk(obj)) sessions.lastOrNull()?.let {
                            // A hop said again (its result came later) replaces the line it updates.
                            val hop = hopOf(obj)
                            val same = it.hops.indexOfFirst { h -> h.sameStep(hop) }
                            if (same >= 0) it.hops[same] = hop else it.hops.addLast(hop)
                            if (it.hops.size > TraceFacts.MAX_HOPS) it.hops.removeFirst()
                        }
                    }
                }
            }
            pos = end + 1
        }
        return sessions.map { TraceSession(it.startedAtMs, it.samples.toList(), TraceFacts.clean(it.facts), it.hops.toList()) }
    }

    /** A session being read: its readings, facts and hops so far. */
    private class Reading(val startedAtMs: Long) {
        val samples = ArrayDeque<TraceSample>()
        val facts = LinkedHashMap<String, String>()
        val hops = ArrayDeque<TraceHop>()
    }

    /** The `k` object of a facts line: string values of known keys only; anything else is skipped. */
    private fun factsOf(o: JSONObject): Map<String, String> {
        val k = o.optJSONObject("k") ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (key in k.keys()) {
            if (key !in TraceFacts.KEYS) continue
            // Text, a number or a flag; an object or an array is not a fact.
            val value = when (val v = k.get(key)) {
                is String -> v
                is Number, is Boolean -> v.toString()
                else -> ""
            }
            if (value.isNotEmpty()) out[key] = value
        }
        return TraceFacts.clean(out)
    }

    private fun hopOf(o: JSONObject) = TraceHop(
        atMs = o.optLong("at", 0L),
        evidence = o.optString("ev", ""),
        from = o.optString("from", "default"),
        to = o.optString("to", ""),
        result = o.optString("res", TraceHop.PENDING),
    ).clamped()

    /** A line with no time, or one before 1970 or after 2100, is not from this app: skipped, so it cannot drag the whole session's clock along. */
    private fun timeOk(o: JSONObject): Boolean = o.has("at") && o.optLong("at", -1L) in 0L..TraceLimits.MAX_TIME_MS

    /** Longer than any trace the ring writes; anything before the last this much is ignored. */
    private const val MAX_READ_CHARS = 4 * 1024 * 1024

    private fun sampleOf(o: JSONObject) = TraceSample(
        atMs = o.optLong("at", 0L),
        intervalMs = o.optLong("iv", 0L),
        droppedFrames = o.optInt("df", 0),
        underruns = o.optInt("ur", 0),
        bufferAheadMs = o.optLong("ba", 0L),
        bitrateKbps = o.optInt("br", 0),
        decoder = o.optString("dc", ""),
        hardware = o.optBoolean("hw", true),
        readWaitMs = o.optLong("rw", 0L),
        reopens = o.optInt("ro", 0),
        tdSpeedBps = o.optLong("sp", 0L),
        cpuPercent = o.optInt("cp", TraceSample.UNKNOWN),
        memoryLevel = o.optInt("mm", 0),
        thermal = o.optInt("th", TraceSample.UNKNOWN),
        engine = o.optString("en", ""),
    )
}

/**
 * The last few sessions in memory, bounded three ways: [maxSamples] a session (the oldest reading
 * goes first), [maxSessions] (the oldest session goes first) and [maxBytes] of JSON lines in all
 * (the oldest readings go first, never the newest reading of the session being recorded). A
 * recorder owns one ring and calls it from one thread; [PlaybackTrace.encode] of [sessions]
 * is what goes to disk.
 */
class PlaybackTraceRing(
    private val maxSessions: Int = TraceLimits.MAX_SESSIONS,
    private val maxSamples: Int = TraceLimits.MAX_SAMPLES_PER_SESSION,
    private val maxBytes: Int = TraceLimits.MAX_BYTES,
) {

    private class Entry(val sample: TraceSample, val bytes: Int)

    private class Open(val startedAtMs: Long) {
        val entries = ArrayDeque<Entry>()
        var facts: Map<String, String> = emptyMap()
        var factsBytes = 0
        val hops = ArrayDeque<TraceHop>()
        var hopBytes = 0
    }

    private val open = ArrayDeque<Open>()
    private var bytes = 0
    private var lastAtMs = 0L

    /** Characters of JSON lines [PlaybackTrace.encode] would write now. */
    val size: Int get() = bytes

    /** Starts a session. A clock that went backwards is held at the last time seen. */
    fun startSession(atMs: Long) {
        val at = atMs.coerceIn(0L, TraceLimits.MAX_TIME_MS).coerceAtLeast(lastAtMs)
        lastAtMs = at
        open.addLast(Open(at))
        bytes += PlaybackTrace.sessionLine(at).length + 1
        while (open.size > maxSessions.coerceAtLeast(1)) dropOldestSession()
        trim()
    }

    /** Adds [facts] to the current session (a key said again takes its new value), starting one when there is none. */
    fun addFacts(facts: Map<String, String>) {
        val clean = TraceFacts.clean(facts)
        if (clean.isEmpty()) return
        if (open.isEmpty()) startSession(lastAtMs)
        val current = open.last()
        current.facts = current.facts + clean
        val now = PlaybackTrace.factsLines(current.startedAtMs, current.facts).sumOf { it.length + 1 }
        bytes += now - current.factsBytes
        current.factsBytes = now
        trim()
    }

    /** Adds a ladder hop to the current session; only the last [TraceFacts.MAX_HOPS] are kept. */
    fun addHop(hop: TraceHop) {
        if (open.isEmpty()) startSession(lastAtMs)
        val current = open.last()
        val clean = hop.clamped()
        val same = current.hops.indexOfFirst { it.sameStep(clean) }
        if (same >= 0) {
            val old = PlaybackTrace.hopLine(current.hops[same]).length + 1
            current.hops[same] = clean
            val delta = PlaybackTrace.hopLine(clean).length + 1 - old
            current.hopBytes += delta
            bytes += delta
            trim()
            return
        }
        current.hops.addLast(clean)
        current.hopBytes += PlaybackTrace.hopLine(clean).length + 1
        while (current.hops.size > TraceFacts.MAX_HOPS) {
            val gone = current.hops.removeFirst()
            current.hopBytes -= PlaybackTrace.hopLine(gone).length + 1
            bytes -= PlaybackTrace.hopLine(gone).length + 1
        }
        bytes += PlaybackTrace.hopLine(clean).length + 1
        trim()
    }

    /** The facts of the current session so far. */
    fun currentFacts(): Map<String, String> = open.lastOrNull()?.facts.orEmpty()

    /** The hops of the current session so far, oldest first. */
    fun currentHops(): List<TraceHop> = open.lastOrNull()?.hops?.toList().orEmpty()

    /** Adds a reading to the current session, starting one at its time when there is none. */
    fun add(sample: TraceSample) {
        val clean = sample.clamped()
        val at = clean.atMs.coerceAtLeast(lastAtMs)
        lastAtMs = at
        if (open.isEmpty()) startSession(at)
        val fixed = clean.copy(atMs = at)
        val current = open.last()
        val entry = Entry(fixed, PlaybackTrace.sampleLine(fixed).length + 1)
        current.entries.addLast(entry)
        bytes += entry.bytes
        while (current.entries.size > maxSamples.coerceAtLeast(1)) bytes -= current.entries.removeFirst().bytes
        trim()
    }

    /** What is kept, oldest session first. */
    fun sessions(): List<TraceSession> = open.map { s -> TraceSession(s.startedAtMs, s.entries.map { it.sample }, s.facts, s.hops.toList()) }

    /** The samples of the current session, for the verdict and for "the last 60 s". */
    fun currentSamples(): List<TraceSample> = open.lastOrNull()?.entries?.map { it.sample }.orEmpty()

    fun clear() {
        open.clear()
        bytes = 0
    }

    private fun dropOldestSession() {
        val gone = open.removeFirst()
        bytes -= PlaybackTrace.sessionLine(gone.startedAtMs).length + 1 + gone.entries.sumOf { it.bytes } + gone.factsBytes + gone.hopBytes
    }

    /** Over the byte cap: the oldest session's oldest readings first; the session being recorded keeps at least its newest reading. */
    private fun trim() {
        while (bytes > maxBytes) {
            val oldest = open.firstOrNull() ?: return
            when {
                open.size > 1 && oldest.entries.isEmpty() -> dropOldestSession()
                oldest.entries.size > 1 || open.size > 1 -> bytes -= oldest.entries.removeFirst().bytes
                else -> return
            }
        }
    }

    companion object {
        /** A ring holding what [PlaybackTrace.read] found in a file, for a recorder that continues it. */
        fun from(text: String): PlaybackTraceRing {
            val ring = PlaybackTraceRing()
            for (session in PlaybackTrace.read(text)) {
                ring.startSession(session.startedAtMs)
                ring.addFacts(session.facts)
                for (hop in session.hops) ring.addHop(hop)
                for (sample in session.samples) ring.add(sample)
            }
            return ring
        }
    }
}
