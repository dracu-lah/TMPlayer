package com.tmplayer.platform

import kotlin.concurrent.Volatile

/**
 * Two files on a platform's disk that hold the app's log (reliability plan L4): a current one and
 * the one before it. The pure [RotatingLog] decides when to rotate; a platform says how to touch
 * the files (see `PlainLogFiles` for the JVM, which Android and the desktop share).
 *
 * Every method may throw (a full disk, a revoked folder): [RotatingLog] catches it.
 */
interface LogFiles {

    /** Runs [block] with every other writer, reader and rotation of these files held off. */
    fun <T> exclusive(block: () -> T): T

    /** Bytes in the current file, 0 when there is none. */
    fun currentSize(): Long

    /** Adds [text] to the end of the current file, creating it. */
    fun append(text: String)

    /** The current file becomes the previous one (replacing it) and the current file starts empty. */
    fun rotate()

    /** The previous file's text, "" when there is none. */
    fun readPrevious(): String

    /** The current file's text, "" when there is none. */
    fun readCurrent(): String

    /** Removes both files (sign-out, "Delete your data"). */
    fun deleteAll()
}

/**
 * A bounded log on disk: about [maxBytesPerFile] in each of two files, so the newest
 * `2 x maxBytesPerFile` of lines survive a restart.
 *
 * - **Never throws.** A full disk, a missing folder or any other failure drops the line and counts
 *   it; after a failure the next [BACKOFF_LINES] lines are not even tried, then it tries again, so
 *   a full disk costs almost nothing and a freed one recovers by itself.
 * - **Concurrent writers.** Every write, rotation and read runs inside [LogFiles.exclusive], so
 *   two threads never interleave a line or rotate twice, and an export never reads a file that
 *   is being rotated under it.
 * - **Bounded lines.** A line over [MAX_LINE_CHARS] is cut; a line break inside one is kept as a
 *   break, so each stored line starts with its own stamp or is a continuation (a stack trace).
 *
 * The log holds names and is scrubbed on the way out ([com.tmplayer.data.LogExport]), never on the
 * way in.
 */
class RotatingLog(
    private val files: LogFiles,
    val maxBytesPerFile: Long = DEFAULT_MAX_BYTES,
) {

    @Volatile
    private var skip = 0

    @Volatile
    var failures = 0
        private set

    /** Writes [line] (a newline is added). Safe from any thread, never throws. */
    fun write(line: String) {
        if (skip > 0) {
            skip--
            return
        }
        try {
            val text = (if (line.length > MAX_LINE_CHARS) line.take(MAX_LINE_CHARS) + "..." else line) + "\n"
            val size = text.encodeToByteArray().size
            files.exclusive {
                if (files.currentSize() + size > maxBytesPerFile) files.rotate()
                files.append(text)
            }
        } catch (e: Exception) {
            failures++
            skip = BACKOFF_LINES
        }
    }

    /**
     * Every stored line, the previous file's first, so they read in the order they were written.
     * A file that cannot be read gives no lines rather than an error.
     */
    fun readLines(): List<String> = try {
        files.exclusive {
            val previous = try { files.readPrevious() } catch (e: Exception) { "" }
            val current = try { files.readCurrent() } catch (e: Exception) { "" }
            (previous + current).split('\n').let { if (it.lastOrNull() == "") it.dropLast(1) else it }
        }
    } catch (e: Exception) {
        emptyList()
    }

    /** Removes the stored log. Never throws. */
    fun clear() {
        try {
            files.exclusive { files.deleteAll() }
        } catch (e: Exception) {
            failures++
        }
    }

    companion object {
        /** About 2 MB a file, 4 MB in all. */
        const val DEFAULT_MAX_BYTES = 2L * 1024 * 1024
        const val MAX_LINE_CHARS = 4000

        /** After a failed write this many lines are dropped without trying. */
        const val BACKOFF_LINES = 200
    }
}

/**
 * A [LogSink] that also writes every line to a [RotatingLog], with the time and the same
 * `I/tag: message` shape the standard error sink prints. Install it over what is there:
 * `Logger.sink = FileLogSink(log, Logger.sink)`; the [next] sink (logcat on Android) still gets
 * every call, so nothing about the public [Logger] changes.
 */
class FileLogSink(
    private val log: RotatingLog,
    private val next: LogSink? = null,
    private val clock: () -> Long,
) : LogSink {

    override fun i(tag: String, message: String) {
        next?.let { runCatching { it.i(tag, message) } }
        log.write("${stamp(clock())} I/$tag: $message")
    }

    override fun w(tag: String, message: String, error: Throwable?) {
        next?.let { runCatching { it.w(tag, message, error) } }
        val trace = error?.let { runCatching { "\n" + it.stackTraceToString().lines().take(TRACE_LINES).joinToString("\n") }.getOrDefault("") }.orEmpty()
        log.write("${stamp(clock())} W/$tag: $message$trace")
    }

    companion object {
        const val TRACE_LINES = 40

        /** `2026-11-15 10:11:12.123Z`, UTC, from epoch milliseconds; no time zone database needed. */
        fun stamp(epochMs: Long): String {
            val ms = epochMs.coerceIn(0L, 253_402_300_799_999L)
            val days = ms / 86_400_000L
            val rest = (ms % 86_400_000L).toInt()
            // Howard Hinnant's civil-from-days.
            val z = days + 719_468
            val era = z / 146_097
            val doe = (z - era * 146_097).toInt()
            val yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365
            val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
            val mp = (5 * doy + 2) / 153
            val day = doy - (153 * mp + 2) / 5 + 1
            val month = if (mp < 10) mp + 3 else mp - 9
            val year = (yoe + era * 400 + if (month <= 2) 1 else 0).toInt()
            fun two(n: Int) = n.toString().padStart(2, '0')
            return "${year.toString().padStart(4, '0')}-${two(month)}-${two(day)} " +
                "${two(rest / 3_600_000)}:${two(rest / 60_000 % 60)}:${two(rest / 1_000 % 60)}." +
                "${(rest % 1_000).toString().padStart(3, '0')}Z"
        }
    }
}
