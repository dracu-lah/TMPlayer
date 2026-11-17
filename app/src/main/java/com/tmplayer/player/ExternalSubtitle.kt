package com.tmplayer.player

import java.util.Locale

/**
 * A subtitle file from somewhere on the phone, chosen through the system's document picker and
 * laid over the video playing now.
 *
 * The desktop takes the same files by drop (`SubtitleDrop` there) and hands them to mpv, which
 * reads all five formats itself. Media3 reads SubRip, SubStation Alpha and WebVTT, but not `.sub`,
 * so a MicroDVD `.sub` (frame numbers in braces, by far the common kind) is rewritten as SubRip
 * here before the player sees it. The other `.sub`, VobSub, is a picture format that needs its
 * `.idx` beside it, and is refused with a sentence that says so.
 *
 * Pure, so the format rules can be tested without a device.
 */
object ExternalSubtitle {

    /** What the picker accepts, by file name. Case does not matter. */
    val EXTENSIONS = setOf("srt", "ass", "ssa", "vtt", "sub")

    /** The largest file worth reading: a feature film's subtitles are tens of kilobytes. */
    const val MAX_BYTES = 4L * 1024 * 1024

    /** Frames a second assumed for a MicroDVD file that does not say, the film rate. */
    const val DEFAULT_FPS = 23.976

    /** The file's extension, lower case, or empty when it has none. */
    fun extensionOf(name: String): String =
        name.substringAfterLast('.', "").lowercase(Locale.ROOT)

    /** Whether a file of this name is one the picker should have offered. */
    fun accepts(name: String): Boolean = extensionOf(name) in EXTENSIONS

    /**
     * The MIME type Media3 is told the file is, for a file it can read as it stands; null for
     * `.sub`, which has to go through [microDvdToSrt] first, and for anything else.
     */
    fun mimeTypeOf(extension: String): String? = when (extension.lowercase(Locale.ROOT)) {
        "srt" -> MIME_SUBRIP
        "ass", "ssa" -> MIME_SSA
        "vtt" -> MIME_VTT
        else -> null
    }

    /**
     * The language a file's name gives just before its extension, the way players name side
     * files ("Film.2019.ar.srt", "Film.pt-BR.srt", "Film.ara.srt"); null when it names none.
     */
    fun languageOf(name: String): String? {
        val parts = name.split('.')
        if (parts.size < 3) return null
        val tag = parts[parts.size - 2]
        return tag.takeIf { LANGUAGE_TAG.matches(it) }?.lowercase(Locale.ROOT)
    }

    /**
     * Whether a track's format id is the side file [id] ("external-3"). Media3 may put a prefix
     * of its own in front, so the id is looked for inside it, but not as the start of a longer
     * number: "external-1" is not "external-12".
     */
    fun isTrackId(formatId: String?, id: String): Boolean {
        formatId ?: return false
        var at = formatId.indexOf(id)
        while (at >= 0) {
            val next = formatId.getOrNull(at + id.length)
            if (next == null || !next.isDigit()) return true
            at = formatId.indexOf(id, at + 1)
        }
        return false
    }

    /** True when [text] reads as MicroDVD: its first cue line opens with two frame numbers. */
    fun isMicroDvd(text: String): Boolean =
        text.lineSequence().map { it.trim().trimStart('\uFEFF') }.firstOrNull { it.isNotEmpty() }
            ?.let { CUE.matchEntire(it) != null } == true

    /**
     * MicroDVD rewritten as SubRip.
     *
     * Each line is `{start}{end}text`, counted in frames, with `|` for a line break and style
     * codes such as `{y:i}` in braces. A first cue of `{1}{1}23.976` is the file stating its own
     * frame rate rather than a line of dialogue, and wins over [fps]. Style codes are dropped:
     * SubRip has no equivalent for most of them, and a stray brace on screen is worse than plain
     * text. Lines that are not cues are skipped.
     */
    fun microDvdToSrt(text: String, fps: Double = DEFAULT_FPS): String {
        val cues = text.lineSequence()
            .map { it.trim().trimStart('\uFEFF') }
            .mapNotNull { CUE.matchEntire(it) }
            .toList()
        var rate = fps.takeIf { it > 0 } ?: DEFAULT_FPS
        val out = StringBuilder()
        var number = 0
        cues.forEachIndexed { index, match ->
            val (startText, endText, body) = match.destructured
            // A frame number too long for a Long (outside data) drops its cue, not the file.
            val start = startText.toLongOrNull() ?: return@forEachIndexed
            val end = endText.toLongOrNull() ?: return@forEachIndexed
            if (index == 0 && start == end && start <= 1) {
                body.trim().toDoubleOrNull()?.takeIf { it > 0 }?.let {
                    rate = it
                    return@forEachIndexed
                }
            }
            val words = body.replace(STYLE, "").split('|').joinToString("\n") { it.trim() }.trim()
            if (words.isEmpty()) return@forEachIndexed
            number++
            out.append(number).append('\n')
            out.append(clock(start, rate)).append(" --> ").append(clock(end.coerceAtLeast(start), rate)).append('\n')
            out.append(words).append("\n\n")
        }
        return out.toString()
    }

    /** A frame number as a SubRip time, `HH:MM:SS,mmm`. */
    fun clock(frame: Long, fps: Double): String {
        val ms = (frame * 1000.0 / fps).toLong().coerceAtLeast(0)
        return String.format(
            Locale.ROOT,
            "%02d:%02d:%02d,%03d",
            ms / 3_600_000,
            ms / 60_000 % 60,
            ms / 1000 % 60,
            ms % 1000,
        )
    }

    // Media3's MimeTypes values, spelt out so this file needs nothing from Android.
    const val MIME_SUBRIP = "application/x-subrip"
    const val MIME_SSA = "text/x-ssa"
    const val MIME_VTT = "text/vtt"

    private val CUE = Regex("""^\{([0-9]+)\}\{([0-9]+)\}(.*)$""")
    private val STYLE = Regex("""\{[^}]*\}""")

    /** Two or three letters, with a region or script after a hyphen or underscore if any. */
    private val LANGUAGE_TAG = Regex("""[A-Za-z]{2,3}([-_][A-Za-z0-9]{2,4})?""")
}
