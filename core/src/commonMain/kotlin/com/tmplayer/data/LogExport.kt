package com.tmplayer.data

import com.tmplayer.online.MetaSettings
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * "Save logs" (CP59): the app's recent log lines in a text file the viewer can attach to a bug
 * report, with what would say who they are or what they watch taken out first.
 *
 * Each platform gathers its own lines (an Android phone or TV its own logcat, the desktop its log
 * file) and hands them here, so the scrubbing and the header are the same everywhere.
 */
object LogExport {

    /** About as much as a bug needs, and a file small enough to attach anywhere. */
    const val MAX_LINES = 2000

    private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")
    private val WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    /** `tmplayer-logs-20261013-1405.txt`, so two saves a minute apart do not collide. */
    fun fileName(now: LocalDateTime = LocalDateTime.now()): String = "tmplayer-logs-${now.format(STAMP)}.txt"

    /**
     * The file's text: what the device is, in the same facts a bug report carries, then the last
     * [MAX_LINES] of [lines], each one scrubbed. [known] are names the app has seen (chat titles,
     * video titles) to take out word for word; see [LogScrubber].
     *
     * The header is for the maintainer, who reads reports in English, so it is not in the catalog.
     */
    fun report(
        facts: Feedback.Facts,
        lines: List<String>,
        known: Collection<String> = emptyList(),
        now: LocalDateTime = LocalDateTime.now(),
        /** What the lines cannot hold, written before them: an earlier run's crash, whether this run is a safe one. */
        extra: List<String> = emptyList(),
    ): String {
        val scrubber = LogScrubber(known)
        return buildString {
            appendLine("TMPlayer logs")
            appendLine("App version: ${facts.version}")
            appendLine("Platform: ${facts.platform.option.ifEmpty { "other" }}")
            appendLine("Device: ${facts.device}")
            appendLine("App language: ${facts.language}")
            appendLine("Saved: ${now.format(WHEN)}")
            appendLine(
                "Names, long numbers, phone numbers, @usernames and file names are replaced " +
                    "with placeholders such as [name] and [number].",
            )
            appendLine()
            for (line in extra) appendLine(scrubber.scrub(line))
            for (line in lines.takeLast(MAX_LINES)) appendLine(scrubber.scrub(line))
        }
    }

    /**
     * The titles this device has seen, from Continue watching, the watched list, Watch later,
     * downloads and the cache: chat names, video names and file names, which a log line may quote.
     * Read from the stores' raw entries rather than their lists, which the kids lock filters: a
     * chat the lock hides is still a name to take out. Short ones are left out, since "TV" or "Up"
     * taken out everywhere would leave the log unreadable.
     */
    suspend fun knownNames(settings: SettingsStore, watched: WatchedStore?): Set<String> {
        val names = HashSet<String>()
        runCatching {
            val raw = Backups.collect(
                settings = settings.rawEntries(),
                watched = watched?.rawEntries().orEmpty(),
                metadata = MetaSettings(),
                createdAt = 0L,
                appVersion = "",
            )
            for (r in raw.resume) {
                names += r.title
                names += r.chatTitle
            }
            for (r in raw.watched) {
                names += r.title
                names += r.chatTitle
            }
            for (r in raw.watchLater) {
                names += r.title
                names += r.chatTitle
                names += r.fileName
            }
        }
        runCatching {
            for (r in settings.downloadsNow() + settings.cachedVideosNow()) {
                names += r.title
                names += r.chatTitle
            }
        }
        return names.map { it.trim() }.filter { it.length >= LogScrubber.MIN_KNOWN }.toSet()
    }
}

/**
 * Takes what is personal out of a log line, keeping what a bug needs: the tag, the time, the
 * message's own words and the stack trace.
 *
 * In this order: names the app knows ([known], chat and video titles, longest first so a title
 * that contains another goes whole), e-mail addresses, file names and the last part of a path to
 * a media or subtitle file, phone numbers written with a plus, `@usernames`, then any run of six
 * or more digits, which is how Telegram's chat, user, message and file ids look. Shorter numbers
 * stay: they are line numbers, sizes, timings and error codes.
 */
class LogScrubber(known: Collection<String> = emptyList()) {

    private val names: List<Regex> = known
        .map { it.trim() }
        .filter { it.length >= MIN_KNOWN }
        .distinct()
        .sortedByDescending { it.length }
        .map { Regex(Regex.escape(it), RegexOption.IGNORE_CASE) }

    fun scrub(line: String): String {
        var out = line
        for (name in names) out = name.replace(out, NAME)
        out = EMAIL.replace(out, "[email]")
        out = PATH_FILE.replace(out) { "${it.groupValues[1]}[file]" }
        out = BARE_FILE.replace(out, "[file]")
        out = PHONE.replace(out, "[phone]")
        out = USERNAME.replace(out, "@[user]")
        out = DIGITS.replace(out, "[number]")
        return out
    }

    companion object {
        /** A known name shorter than this is not taken out; see [LogExport.knownNames]. */
        const val MIN_KNOWN = 4

        private const val NAME = "[name]"

        private const val EXTENSIONS =
            "mkv|mp4|m4v|avi|webm|mov|wmv|flv|ts|m2ts|mpg|mpeg|3gp|mp3|m4a|aac|flac|ogg|opus|wav|" +
                "srt|ass|ssa|vtt|sub|idx|sup|zip|rar|7z|pdf|jpg|jpeg|png|webp|gif|epub|apk"

        private val EMAIL = Regex("""[\w.+-]+@[\w-]+(\.[\w-]+)+""")

        /** The last part of a path, spaces and all, when it ends in a media, subtitle or document extension. */
        private val PATH_FILE = Regex("""([/\\])[^/\\\r\n]+?\.(?:$EXTENSIONS)\b""", RegexOption.IGNORE_CASE)

        /** A file name on its own, with no spaces in it: `Kerala.Crime.Files.S01E02.mkv`. */
        private val BARE_FILE = Regex("""[^\s/\\:'"()\[\]]+\.(?:$EXTENSIONS)\b""", RegexOption.IGNORE_CASE)

        /** A plus, then at least seven digits, which may be spaced or hyphenated as people write them. */
        private val PHONE = Regex("""\+\d[\d \-()]{5,}\d""")

        /**
         * A Telegram username: an @ not glued to a word (so `Object@1a2b3c` and an e-mail stay out
         * of it), then five or more of what a username may hold.
         */
        private val USERNAME = Regex("""(?<![\w.@])@[A-Za-z][A-Za-z0-9_]{4,}""")

        private val DIGITS = Regex("""\d{6,}""")
    }
}
