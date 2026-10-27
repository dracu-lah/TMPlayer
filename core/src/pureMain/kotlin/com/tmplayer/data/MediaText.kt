package com.tmplayer.data

import com.tmplayer.i18n.L

/**
 * What a file name, a MIME type and a caption say about a video, with no TDLib in it: which files
 * are videos, how sharp a name says one is, which names a camera made up, and the caption with a
 * channel's boilerplate taken off. [MediaMapper] reads TDLib's messages with these on Android and
 * the desktop; the web player runs the same rules as a compiled module.
 */
object MediaText {

    /** Container formats Media3 can demux, plus the ones NextLib's decoders cover. */
    val VIDEO_EXTENSIONS = setOf(
        "mkv", "mp4", "m4v", "avi", "mov", "webm", "ts", "m2ts", "mts",
        "flv", "wmv", "mpg", "mpeg", "3gp", "ogv", "divx", "vob", "asf", "rmvb",
    )

    /** Whether [item] is a round video message, which lists draw in a circle. */
    fun isRound(item: MediaItem): Boolean = item.width > 0 && item.width == item.height && item.fileName.isEmpty() && item.title == L.mediaRoundVideo

    /**
     * An album's caption is written under one of its videos, usually the first, and the others
     * arrive with none. Each video of [items] without a caption takes its album's, so a member
     * named only "video.mp4" still reads as the show the caption names.
     */
    fun shareAlbumCaptions(items: List<MediaItem>): List<MediaItem> {
        if (items.none { it.albumId != 0L && it.caption.isBlank() }) return items
        val captions = items.filter { it.albumId != 0L && it.caption.isNotBlank() }
            .groupBy { it.albumId }
            .mapValues { (_, members) -> members.minBy { it.messageId }.caption }
        return items.map { item ->
            val shared = captions[item.albumId]
            if (item.albumId != 0L && item.caption.isBlank() && shared != null) item.copy(caption = shared) else item
        }
    }

    fun looksLikeVideo(fileName: String, mimeType: String): Boolean {
        // An archive's volumes are never a video, whatever a sender's client called them.
        if (SplitPieces.isArchiveVolume(fileName)) return false
        if (mimeType.startsWith("video/", ignoreCase = true)) return true
        // Plenty of uploads arrive as application/octet-stream; fall back to the extension.
        val extension = fileName.substringAfterLast('.', "").lowercase()
        if (extension in VIDEO_EXTENSIONS) return true
        // A piece of a video cut into byte pieces, which carries no media extension of its own:
        // "Name.mkv.001", or "Name.part001" where the name says it is a release (2160p, HEVC).
        val piece = SplitPieces.pieceOf(fileName) ?: return false
        val inner = piece.base.substringAfterLast('.', "").lowercase()
        return inner in VIDEO_EXTENSIONS || EpisodeCopies.markers(piece.base).isNotEmpty() || qualityTags(piece.base).isNotEmpty()
    }

    /**
     * The one marker worth a badge: how sharp the file is.
     *
     * Descriptive file names put this in the name and nowhere else; the container would have to be
     * opened to learn it otherwise, which is not worth a download per tile. Deliberately only
     * resolution: codec and dynamic range tell somebody choosing what to watch nothing they can
     * act on, and a tile wearing three badges is harder to read than one wearing the badge that
     * decides anything.
     */
    fun qualityTags(fileName: String?): List<String> {
        val name = fileName?.lowercase() ?: return emptyList()
        return when {
            RESOLUTION_4K.containsMatchIn(name) -> listOf("4K")
            name.contains("1080p") || name.contains("1080i") || (BARE_1080.containsMatchIn(name) && RELEASE_WORDS.containsMatchIn(name)) -> listOf("1080p")
            name.contains("720p") || (BARE_720.containsMatchIn(name) && RELEASE_WORDS.containsMatchIn(name)) -> listOf("720p")
            name.contains("480p") || name.contains("360p") -> listOf("SD")
            else -> emptyList()
        }
    }

    private val RESOLUTION_4K = Regex("""\b(2160p|4k|uhd)\b""")

    /**
     * "HDRip - 1080.mkv": the height with its "p" left off, which some uploaders do. Only as a
     * word of its own, so a 1920x1080 or a bitrate with digits round it is not read as one.
     */
    private val BARE_1080 = Regex("""(?<![\w])1080(?![\w])""")
    private val BARE_720 = Regex("""(?<![\w])720(?![\w])""")

    /**
     * Only a release names its height bare, "HDRip - 1080". Without one of these beside it the
     * number is an episode: anime run to four figures, and "One Piece - 1080" is not a 1080p file.
     */
    private val RELEASE_WORDS = Regex("""(rip|web|blu-?ray|x26[45]|h\.?26[45]|hevc|avc|aac|hq)""")

    /** A name a person chose beats a caption, a caption beats a made-up name, and either beats nothing. */
    fun displayTitle(fileName: String?, caption: String?, fallback: String): String {
        val name = fileName?.trim().orEmpty()
        val firstCaptionLine = captionOf(caption).lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        // A name a phone or Telegram made up says nothing a viewer can choose by, and the caption
        // the poster wrote under it usually does: "Episode 5, The Storm" over VID_20241013_1200.
        if (name.isNotEmpty() && !(isGenericName(name) && firstCaptionLine.isNotEmpty())) return name
        if (firstCaptionLine.isNotEmpty()) return firstCaptionLine.take(TITLE_MAX)
        return fallback
    }

    /**
     * Whether [fileName] is one a camera, a messenger or a screen recorder chose rather than a
     * person: `VID_20241013_120000.mp4`, `video_2024-10-13_12-00-00.mp4`, `IMG_1234.MOV`,
     * `PXL_20240101_093000.mp4`, `VID-20240101-WA0001.mp4`, `video.mp4`, a bare number or a hash.
     * A release name such as `1917.2019.1080p.mkv` carries words or tags and is never one.
     */
    fun isGenericName(fileName: String): Boolean {
        val base = fileName.trim().substringBeforeLast('.').trim().lowercase()
        if (base.isEmpty()) return true
        return GENERIC_NAMES.any { it.matches(base) }
    }

    /**
     * The first line of the caption that is not the title already, for a second line under it.
     * Null when the caption has nothing more to say.
     */
    fun captionLine(item: MediaItem, repeated: Set<String> = emptySet()): String? =
        item.caption.lineSequence().map { it.trim() }
            .firstOrNull {
                it.isNotEmpty() && !it.equals(item.title.trim(), ignoreCase = true) && it.lowercase() !in repeated
            }

    /**
     * Caption lines a chat puts under most of its posts, lowercased: a channel's signature, a
     * "New every Friday", a quality note. Shown under every row they would read as part of each
     * video and tell none of them apart, so [captionLine] skips them. A line counts once it is on
     * at least three posts and on at least [REPEAT_SHARE] of the ones listed.
     */
    fun repeatedCaptionLines(items: List<MediaItem>): Set<String> {
        if (items.size < MIN_REPEATS) return emptySet()
        val counts = HashMap<String, Int>()
        for (item in items) {
            // Read once per item ([MediaItem.captionLines]): every listing and build of Home counts them.
            item.captionLines.forEach { counts[it] = (counts[it] ?: 0) + 1 }
        }
        val needed = maxOf(MIN_REPEATS, (items.size * REPEAT_SHARE).toInt())
        return counts.filterValues { it >= needed }.keys
    }

    private const val MIN_REPEATS = 3
    private const val REPEAT_SHARE = 0.4

    /**
     * The caption with the channel's boilerplate taken off and kept short: it is read for an
     * episode and shown as a synopsis, never as a whole post.
     *
     * A line that is nothing but links, @mentions and #hashtags, or one of those behind "Join" or
     * "Subscribe", is the channel advertising itself under every post. It says nothing about the
     * video, and as a synopsis or a title it would be the same line on every tile.
     */
    fun captionOf(text: String?): String {
        if (text.isNullOrBlank()) return ""
        return text.lineSequence()
            .map { it.trimEnd() }
            .filterNot { isBoilerplate(it) }
            .joinToString("\n")
            .replace(BLANK_RUN, "\n\n")
            .trim()
            .take(CAPTION_MAX)
    }

    /**
     * The caption as a detail page's overview, or null when it says nothing the page does not.
     *
     * Channels often post a file's own facts as its caption: "FILE NAME : Pulimada 1080P ...",
     * "Size: 1.4 GB", "Quality - 1080p". The page lists those facts in its details block, and the
     * same words a second time where an overview goes read as clutter. So a line that is one of
     * those facts ("key: value" with a key from [FACT_KEYS], an emoji or bullet in front allowed),
     * a line that is only the file name or the title again, and the channel's links and hashtags
     * ([captionOf]) are taken off. Anything else is the poster's own words and stays.
     */
    fun overviewOf(caption: String?, fileName: String?, title: String?): String? {
        val text = captionOf(caption)
        if (text.isEmpty()) return null
        val repeats = buildSet {
            fileName?.let { name ->
                add(plainWords(name))
                add(plainWords(name.substringBeforeLast('.', name)))
            }
            title?.let { add(plainWords(it)) }
        }.filterTo(HashSet()) { it.isNotEmpty() }
        return text.lineSequence()
            .filterNot { line -> isFactLine(line) || (line.isNotBlank() && plainWords(line) in repeats) }
            .joinToString("\n")
            .replace(BLANK_RUN, "\n\n")
            .trim()
            .takeIf { it.any(Char::isLetterOrDigit) }
    }

    /** Overload for a video as it is listed. */
    fun overviewOf(item: MediaItem): String? = overviewOf(item.caption, item.fileName, item.title)

    private fun isFactLine(line: String): Boolean {
        val match = FACT_LINE.find(line) ?: return false
        return match.groupValues[1].lowercase().replace(SPACES, " ").trim() in FACT_KEYS
    }

    /** Lowercase letters and digits with one space between runs, so "A.B_C" and "a b c" match. */
    private fun plainWords(text: String): String =
        text.lowercase().replace(NOT_A_LETTER_OR_SPACE, " ").replace(SPACES, " ").trim()

    /** A key at the start of a line (after any emoji, bullet or symbol), then ":", "=", "-", "|" or ">". */
    private val FACT_LINE = Regex("""^[^\p{L}\p{N}]*([\p{L}][\p{L} ]{0,24}?)\s*[:=|>\-]""")
    private val SPACES = Regex("""\s+""")
    private val NOT_A_LETTER_OR_SPACE = Regex("""[^\p{L}\p{N}]+""")

    /** Facts a detail page already shows from the file itself, as channels label them. */
    private val FACT_KEYS = setOf(
        "file name", "filename", "file", "name", "title",
        "size", "file size", "filesize", "total size",
        "quality", "resolution", "res", "video quality", "print",
        "codec", "video codec", "audio codec", "video", "audio", "audio track", "audio tracks", "format",
        "extension", "ext", "container", "type", "file type",
        "duration", "runtime", "run time", "length",
        "uploaded by", "upload by", "uploader", "encoded by", "encoder", "ripped by", "posted by", "shared by",
        "source",
    )

    private fun isBoilerplate(line: String): Boolean {
        if (line.isBlank() || !LINKISH.containsMatchIn(line)) return false
        val rest = line.replace(LINKISH, " ").replace(NOT_A_LETTER, " ").trim().lowercase()
        if (rest.isEmpty()) return true
        return rest.split(' ').filter { it.isNotEmpty() }.all { it in CALL_WORDS }
    }

    private const val TITLE_MAX = 120

    private val GENERIC_NAMES = listOf(
        // Camera rolls, Telegram's own saves and screen recorders: a prefix, then the date and time.
        Regex("""(vid|img|mov|pxl|mvimg|video|movie|clip|file|document|telegram|tg|record|recording|screen[ _-]?record(ing)?|screenrecorder|wa)[ _-]*\d[\d _.:-]*(wa\d+)?([ _-]?\(\d+\))?"""),
        // "video", "video (2)", "untitled".
        Regex("""(video|vid|movie|clip|file|untitled|new video)([ _-]*\(?\d+\)?)?"""),
        // A number and nothing else, or a date and time and nothing else.
        Regex("""[\d _.:-]+"""),
        // A hash or a UUID.
        Regex("""[0-9a-f]{16,}|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"""),
    )

    private val LINKISH = Regex("""(https?://\S+|www\.\S+|t\.me/\S+|telegram\.me/\S+|@[A-Za-z0-9_]{4,}|#\S+)""")
    private val NOT_A_LETTER = Regex("""[^\p{L}\p{N} ]+""")
    private val BLANK_RUN = Regex("""\n{3,}""")

    /** The words a channel puts in front of its own link; a line of only these and a link is an ad. */
    private val CALL_WORDS = setOf(
        "join", "us", "subscribe", "follow", "share", "channel", "our", "for", "more", "via", "by",
        "from", "on", "telegram", "main", "backup", "group", "updates", "official", "here", "click",
        "visit", "and", "to", "the", "now", "link", "links",
    )

    private const val CAPTION_MAX = 600

    /** TDLib reports 0 for a file it has never touched, but usually knows the expected size. */
    fun fileSize(size: Long, expectedSize: Long) = if (size > 0) size else expectedSize

    fun formatSize(bytes: Long): String = L.messages.formatter.size(bytes)

    fun formatDuration(seconds: Int): String = L.messages.formatter.duration(seconds.toLong())
}
