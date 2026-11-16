package com.tmplayer.player

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * The playback trace on disk (reliability plan L1): one JSON-lines file, the last
 * [TraceLimits.MAX_SESSIONS] sessions, never more than about [TraceLimits.MAX_BYTES].
 *
 * A reading is appended as one line, so a process killed mid-session leaves a file that is whole
 * but for a cut last line, which [PlaybackTrace.read] skips. A new session rewrites the file
 * through the bounded ring (the oldest session goes first) and so does every [COMPACT_EVERY]
 * readings, so a session of many hours cannot grow it.
 *
 * Every method is safe from any thread and never throws: a full disk or a missing folder drops the
 * reading, and after [MAX_FAILURES] failures in a row the file gives up for this session rather
 * than trying a full disk every five seconds.
 */
class TraceFile(private val file: File) {

    private var appended = 0
    private var failures = 0

    /** Whether writing was given up on: see the class text. */
    val givenUp: Boolean @Synchronized get() = failures >= MAX_FAILURES

    /** Begins a session: the older ones (at most four) are kept, this one starts empty. */
    @Synchronized
    fun startSession(atMs: Long) {
        failures = 0
        attempt {
            val ring = ringOf(readText(), keep = TraceLimits.MAX_SESSIONS - 1)
            ring.startSession(atMs)
            rewrite(ring)
            appended = 0
        }
    }

    /** Adds a reading to the file. */
    @Synchronized
    fun append(sample: TraceSample) {
        if (givenUp) return
        attempt {
            file.parentFile?.mkdirs()
            val line = PlaybackTrace.sampleLine(sample) + "\n"
            FileOutputStream(file, true).use { it.write(line.toByteArray(Charsets.UTF_8)) }
            if (++appended >= COMPACT_EVERY) {
                rewrite(ringOf(readText(), keep = TraceLimits.MAX_SESSIONS))
                appended = 0
            }
        }
    }

    /** Adds facts to the current session (version 2's `f` lines): a key said again takes the later line's value on read. */
    @Synchronized
    fun appendFacts(atMs: Long, facts: Map<String, String>) {
        if (givenUp) return
        val lines = PlaybackTrace.factsLines(atMs, facts)
        if (lines.isEmpty()) return
        attempt {
            file.parentFile?.mkdirs()
            FileOutputStream(file, true).use { out -> lines.forEach { out.write((it + "\n").toByteArray(Charsets.UTF_8)) } }
        }
    }

    /** Adds a ladder hop to the current session. */
    @Synchronized
    fun appendHop(hop: TraceHop) {
        if (givenUp) return
        attempt {
            file.parentFile?.mkdirs()
            FileOutputStream(file, true).use { it.write((PlaybackTrace.hopLine(hop) + "\n").toByteArray(Charsets.UTF_8)) }
        }
    }

    /** Every session kept, oldest first; empty when there is no file or it cannot be read. */
    @Synchronized
    fun read(): List<TraceSession> = try {
        PlaybackTrace.read(readText())
    } catch (e: Exception) {
        emptyList()
    }

    /** Removes the file (sign-out, "Delete your data"). */
    @Synchronized
    fun delete() {
        runCatching { file.delete() }
        appended = 0
    }

    private fun readText(): String = if (file.isFile) file.readText(Charsets.UTF_8) else ""

    /** A ring that holds the newest [keep] sessions of [text] and has room for one more. */
    private fun ringOf(text: String, keep: Int): PlaybackTraceRing {
        val ring = PlaybackTraceRing(maxSessions = TraceLimits.MAX_SESSIONS)
        for (session in PlaybackTrace.read(text, maxSessions = keep)) {
            ring.startSession(session.startedAtMs)
            ring.addFacts(session.facts)
            for (hop in session.hops) ring.addHop(hop)
            for (sample in session.samples) ring.add(sample)
        }
        return ring
    }

    /** Written beside the file and renamed over it, so a kill in the middle leaves the old whole file. */
    private fun rewrite(ring: PlaybackTraceRing) {
        file.parentFile?.mkdirs()
        val partial = File(file.path + ".part")
        partial.writeText(PlaybackTrace.encode(ring.sessions()), Charsets.UTF_8)
        if (!partial.renameTo(file)) {
            file.delete()
            if (!partial.renameTo(file)) throw IOException("trace file could not be replaced")
        }
    }

    private inline fun attempt(block: () -> Unit) {
        try {
            block()
            failures = 0
        } catch (e: Exception) {
            failures++
        }
    }

    companion object {
        /** A session of hours is cut back to its cap this often. */
        const val COMPACT_EVERY = TraceLimits.MAX_SAMPLES_PER_SESSION

        /** In a row; then writing stops until the next session. */
        const val MAX_FAILURES = 3

        const val NAME = "playback-trace.jsonl"
    }
}
