package com.tmplayer.data

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * "Send logs" as one bundle (reliability plan L5): the facts a report needs in one text file, each
 * part scrubbed by [LogScrubber] the same way, and the whole kept under [MAX_BYTES] so it fits a
 * mail, a share sheet and the upload's cap (2 MB) alike.
 *
 * The parts, in the order they are written: the earlier run's crash, the device's facts, the
 * decoders the device lists for the video's codec ([DecoderReport]), the last playback sessions
 * and the moments marked as stuttering, the last Playback test, the app's own log and last the
 * logcat. When the bundle is over the cap the oldest playback sessions go first, then the oldest
 * log lines: what is newest is what the report is about.
 */
object LogBundle {

    /** Under the upload's 2 MB cap with room for the Worker's own envelope. */
    const val MAX_BYTES = 1_500_000

    enum class Kind { CRASH, DEVICE, DECODERS, SESSIONS, MARKS, PLAYBACK_TEST, APP_LOG, LOGCAT }

    /** What the app gathered, in plain lines, newest last. Nothing here is scrubbed yet. */
    class Parts(
        val crash: List<String> = emptyList(),
        val device: List<String> = emptyList(),
        val decoders: List<String> = emptyList(),
        /** One block of lines per playback session, oldest session first. */
        val sessions: List<List<String>> = emptyList(),
        val marks: List<String> = emptyList(),
        val playbackTest: List<String> = emptyList(),
        val appLog: List<String> = emptyList(),
        val logcat: List<String> = emptyList(),
    )

    /** One kind of content in a bundle and how many lines (sessions for [Kind.SESSIONS]) it holds. */
    data class Part(val kind: Kind, val count: Int)

    class Result(
        val text: String,
        val bytes: Int,
        /** What is in [text], for the screen that asks before it is sent. Empty kinds are left out. */
        val parts: List<Part>,
        val droppedSessions: Int,
        val droppedLogLines: Int,
    ) {
        val trimmed: Boolean get() = droppedSessions > 0 || droppedLogLines > 0
    }

    private val WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    /**
     * The bundle's text. [known] are names to take out word for word, see [LogExport.knownNames].
     * [maxBytes] is the cap on the text in UTF-8 and is only lowered by tests.
     */
    fun build(
        facts: Feedback.Facts,
        parts: Parts,
        known: Collection<String> = emptyList(),
        now: LocalDateTime = LocalDateTime.now(),
        maxBytes: Int = MAX_BYTES,
    ): Result {
        val scrubber = LogScrubber(known)
        fun scrub(lines: List<String>) = lines.map { scrubber.scrub(it) }
        val crash = scrub(parts.crash)
        val device = scrub(parts.device)
        val decoders = scrub(parts.decoders)
        val marks = scrub(parts.marks)
        val test = scrub(parts.playbackTest)
        var sessions = parts.sessions.map { scrub(it) }.filter { it.isNotEmpty() }
        var appLog = scrub(parts.appLog.takeLast(LogExport.MAX_LINES * 2))
        var logcat = scrub(parts.logcat.takeLast(LogExport.MAX_LINES))

        val header = buildList {
            add("TMPlayer logs")
            add("App version: ${facts.version}")
            add("Platform: ${facts.platform.option.ifEmpty { "other" }}")
            add("Device: ${facts.device}")
            add("App language: ${facts.language}")
            add("Saved: ${now.format(WHEN)}")
            add(
                "Names, long numbers, phone numbers, @usernames and file names are replaced " +
                    "with placeholders such as [name] and [number].",
            )
        }

        var droppedSessions = 0
        var droppedLog = 0
        fun size(lines: List<String>) = lines.sumOf { it.encodeToByteArray().size + 1 }
        val fixed = size(header) + size(crash) + size(device) + size(decoders) + size(marks) + size(test) + SECTION_ALLOWANCE
        // Sizes are kept running so a long list is measured once, not once per line dropped.
        var sessionBytes = sessions.sumOf { size(it) }
        var appBytes = size(appLog)
        var logcatBytes = size(logcat)
        while (fixed + sessionBytes + appBytes + logcatBytes > maxBytes) {
            when {
                sessions.isNotEmpty() -> {
                    sessionBytes -= size(sessions.first())
                    sessions = sessions.drop(1)
                    droppedSessions++
                }
                appLog.isNotEmpty() -> {
                    val cut = (appLog.size / 10).coerceAtLeast(1)
                    appBytes -= size(appLog.take(cut))
                    appLog = appLog.drop(cut)
                    droppedLog += cut
                }
                logcat.isNotEmpty() -> {
                    val cut = (logcat.size / 10).coerceAtLeast(1)
                    logcatBytes -= size(logcat.take(cut))
                    logcat = logcat.drop(cut)
                    droppedLog += cut
                }
                else -> break
            }
        }

        val included = buildList {
            if (crash.isNotEmpty()) add(Part(Kind.CRASH, crash.size))
            if (device.isNotEmpty()) add(Part(Kind.DEVICE, device.size))
            if (decoders.isNotEmpty()) add(Part(Kind.DECODERS, decoders.size))
            if (sessions.isNotEmpty()) add(Part(Kind.SESSIONS, sessions.size))
            if (marks.isNotEmpty()) add(Part(Kind.MARKS, marks.size))
            if (test.isNotEmpty()) add(Part(Kind.PLAYBACK_TEST, test.size))
            if (appLog.isNotEmpty()) add(Part(Kind.APP_LOG, appLog.size))
            if (logcat.isNotEmpty()) add(Part(Kind.LOGCAT, logcat.size))
        }

        val text = buildString {
            for (line in header) appendLine(line)
            if (droppedSessions > 0 || droppedLog > 0) {
                appendLine("To fit the size limit, $droppedSessions older playback session(s) and $droppedLog older log line(s) were left out.")
            }
            section("Earlier run", crash)
            section("Device", device)
            section("Decoders this device lists for the video's codec", decoders)
            if (sessions.isNotEmpty()) {
                appendLine()
                appendLine("== Playback sessions, oldest first ==")
                for (block in sessions) for (line in block) appendLine(line)
            }
            section("Moments marked as stuttering", marks)
            section("Playback test", test)
            section("App log, kept on this device", appLog)
            section("Logcat", logcat)
        }
        var out = text
        var bytes = out.encodeToByteArray().size
        if (bytes > maxBytes) {
            // The header and the fixed parts alone are over the cap: cut at a line, never mid word.
            val lines = out.lines()
            val kept = ArrayList<String>()
            var used = 0
            for (line in lines) {
                val n = line.encodeToByteArray().size + 1
                if (used + n > maxBytes) break
                kept += line
                used += n
            }
            out = kept.joinToString("\n") + "\n"
            bytes = out.encodeToByteArray().size
        }
        return Result(out, bytes, included, droppedSessions, droppedLog)
    }

    /** What the section titles and blank lines add. */
    private const val SECTION_ALLOWANCE = 1_000

    private fun StringBuilder.section(title: String, lines: List<String>) {
        if (lines.isEmpty()) return
        appendLine()
        appendLine("== $title ==")
        for (line in lines) appendLine(line)
    }
}

/**
 * The decoders a device lists, as lines for a report (L5). Read from `MediaCodecList` by the app
 * (read only) and formatted here, so the shape is the same everywhere and a test can pin it.
 */
object DecoderReport {

    /** One decoder as the device lists it for one codec. A size or rate of 0 means the device did not say. */
    data class Decoder(
        val name: String,
        val mime: String,
        /** True hardware, false software, null when the Android release cannot say (before 10). */
        val hardware: Boolean?,
        val maxWidth: Int = 0,
        val maxHeight: Int = 0,
        val maxFrameRate: Int = 0,
        val secureOnly: Boolean = false,
    )

    /**
     * Lines grouped by codec, [videoMime] first and marked, hardware before software, each with
     * its name, kind, the largest picture it takes and the highest rate. Empty list in: a line that
     * says the device lists none, since "no decoder" is the news.
     */
    fun lines(decoders: List<Decoder>, videoMime: String? = null): List<String> {
        if (decoders.isEmpty()) return listOf("No decoders listed for the video's codec.")
        val byMime = decoders.groupBy { it.mime.lowercase() }
        val order = byMime.keys.sortedWith(compareBy<String>({ it != videoMime?.lowercase() }, { it }))
        return buildList {
            for (mime in order) {
                add(mime + if (mime == videoMime?.lowercase()) " (the last video played)" else "")
                val list = byMime.getValue(mime).sortedWith(compareBy<Decoder>({ kindRank(it) }, { it.name }))
                for (d in list) {
                    add("  ${safeName(d.name)}, ${kind(d)}, ${size(d)}${if (d.secureOnly) ", secure only" else ""}")
                }
            }
        }
    }

    private fun kindRank(d: Decoder) = when (d.hardware) {
        true -> 0
        null -> 1
        false -> 2
    }

    private fun kind(d: Decoder) = when (d.hardware) {
        true -> "hardware"
        false -> "software"
        null -> "hardware or software, not told"
    }

    private fun size(d: Decoder): String {
        val picture = if (d.maxWidth > 0 && d.maxHeight > 0) "up to ${d.maxWidth}x${d.maxHeight}" else "size not told"
        val rate = if (d.maxFrameRate > 0) ", up to ${d.maxFrameRate} fps" else ""
        return picture + rate
    }

    /** A codec name is the device's own word; kept to a plain alphabet and a sane length. */
    private fun safeName(name: String): String = name.filter { it.isLetterOrDigit() || it in "._-" }.take(80).ifEmpty { "?" }
}
