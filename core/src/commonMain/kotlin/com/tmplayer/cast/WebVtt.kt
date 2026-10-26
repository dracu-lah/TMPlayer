package com.tmplayer.cast

import com.tmplayer.online.SubtitleText
import java.util.Locale

/**
 * Subtitles as a Cast receiver takes them: WebVTT, sent beside the video as a side track.
 *
 * SubRip becomes WebVTT with its timings rewritten and its basic tags kept (WebVTT has the same
 * b, i and u). A styled ASS or SSA file is sent as plain text: its override blocks, drawing
 * commands and comments go, its line breaks stay, and each event becomes a cue. WebVTT passes
 * through with its header made sure of.
 */
object WebVtt {

    const val MIME = "text/vtt"

    /** An HLS subtitle segment's tie to the media clock: cue time zero is media time zero. */
    const val TIMESTAMP_MAP = "X-TIMESTAMP-MAP=MPEGTS:0,LOCAL:00:00:00.000"

    /** The most hours a cue's time is read with; a longer number is a broken file. */
    private const val MAX_HOURS = 99_999L

    data class Cue(val startMs: Long, val endMs: Long, val text: String)

    /** [bytes] in the format [extension] names (srt, ass, ssa, vtt) as one WebVTT text. */
    fun convert(bytes: ByteArray, extension: String): String = convert(SubtitleText.decode(bytes), extension)

    fun convert(text: String, extension: String): String = when (extension.lowercase(Locale.ROOT)) {
        "vtt" -> fromVtt(text)
        "ass", "ssa" -> write(parseAss(text))
        else -> if (looksLikeAss(text)) write(parseAss(text)) else write(parseSrt(text))
    }

    fun looksLikeAss(text: String): Boolean = text.trimStart().startsWith("[Script Info]", ignoreCase = true)

    fun write(cues: List<Cue>): String {
        val sb = StringBuilder("WEBVTT\n\n")
        cues.sortedBy { it.startMs }.forEach { cue ->
            if (cue.text.isBlank() || cue.endMs <= cue.startMs) return@forEach
            sb.append(stamp(cue.startMs)).append(" --> ").append(stamp(cue.endMs)).append('\n')
            // A blank line inside a cue would end it; WebVTT also forbids "-->" in the text.
            sb.append(cue.text.replace("-->", "->").lines().filter { it.isNotBlank() }.joinToString("\n"))
            sb.append("\n\n")
        }
        return sb.toString()
    }

    fun stamp(ms: Long): String {
        val t = ms.coerceAtLeast(0)
        val h = t / 3_600_000
        val m = t / 60_000 % 60
        val s = t / 1000 % 60
        val f = t % 1000
        return String.format(Locale.ROOT, "%02d:%02d:%02d.%03d", h, m, s, f)
    }

    private fun fromVtt(text: String): String {
        val body = text.replace("\r\n", "\n").replace('\r', '\n')
        return if (body.trimStart().startsWith("WEBVTT")) body.trimStart() else "WEBVTT\n\n$body"
    }

    private val srtTiming = Regex("""(\d+):(\d{1,2}):(\d{1,2})[,.](\d{1,3})\s*-->\s*(\d+):(\d{1,2}):(\d{1,2})[,.](\d{1,3})""")

    fun parseSrt(text: String): List<Cue> {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val cues = ArrayList<Cue>()
        var i = 0
        while (i < lines.size) {
            val match = srtTiming.find(lines[i])
            if (match == null) {
                i++
                continue
            }
            val g = match.groupValues
            val start = ms(g[1], g[2], g[3], g[4])
            val end = ms(g[5], g[6], g[7], g[8])
            i++
            val body = StringBuilder()
            while (i < lines.size && lines[i].isNotBlank()) {
                // A timing line straight after, with no blank line between, starts the next cue.
                if (srtTiming.containsMatchIn(lines[i])) break
                if (body.isNotEmpty()) body.append('\n')
                body.append(lines[i].trim())
                i++
            }
            cues += Cue(start, end, cleanSrt(body.toString()))
        }
        return cues
    }

    private fun ms(h: String, m: String, s: String, frac: String): Long {
        val f = frac.padEnd(3, '0').take(3).toLong()
        // The hours are as many digits as the file has: too many is a nonsense cue, read as 0 (HX10).
        return (h.toLongOrNull()?.coerceAtMost(MAX_HOURS) ?: 0L) * 3_600_000 + m.toLong() * 60_000 + s.toLong() * 1000 + f
    }

    /** SubRip's tags WebVTT also has stay; font colours and the ASS-style braces in some files go. */
    private fun cleanSrt(text: String): String =
        text.replace(Regex("""\{\\[^}]*\}"""), "")
            .replace(Regex("""</?font[^>]*>""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""<(/?)([biu])>""", RegexOption.IGNORE_CASE)) { "<${it.groupValues[1]}${it.groupValues[2].lowercase()}>" }
            .replace(Regex("""<(?!/?[biu]>)[^>]*>"""), "")
            .replace(Regex("""&(?![A-Za-z]+;|#[0-9]+;)"""), "&amp;")

    fun parseAss(text: String): List<Cue> {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        var inEvents = false
        var format: List<String> = listOf("layer", "start", "end", "style", "name", "marginl", "marginr", "marginv", "effect", "text")
        val cues = ArrayList<Cue>()
        for (raw in lines) {
            val line = raw.trim()
            if (line.startsWith("[")) {
                inEvents = line.equals("[Events]", ignoreCase = true)
                continue
            }
            if (!inEvents) continue
            when {
                line.startsWith("Format:", ignoreCase = true) ->
                    format = line.substringAfter(':').split(',').map { it.trim().lowercase(Locale.ROOT) }
                line.startsWith("Dialogue:", ignoreCase = true) -> {
                    val fields = line.substringAfter(':').split(',', limit = format.size)
                    if (fields.size < format.size) continue
                    val start = assTime(fields[format.indexOf("start")].trim()) ?: continue
                    val end = assTime(fields[format.indexOf("end")].trim()) ?: continue
                    val textAt = format.indexOf("text").takeIf { it >= 0 } ?: (format.size - 1)
                    val body = fields[textAt]
                    // A drawing (\p1 and on) is a shape, not words.
                    if (Regex("""\\p[1-9]""").containsMatchIn(body)) continue
                    val plain = body
                        .replace(Regex("""\{[^}]*\}"""), "")
                        .replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " ")
                        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                        .trim()
                    if (plain.isNotEmpty()) cues += Cue(start, end, plain)
                }
            }
        }
        // Several events at one moment (a sign and a line of dialogue) are one cue on the receiver,
        // or it stacks them out of order.
        return cues.groupBy { it.startMs to it.endMs }.map { (span, group) ->
            Cue(span.first, span.second, group.joinToString("\n") { it.text })
        }
    }

    /** The cues of a subtitle file in the format [extension] names, for the HLS subtitle renditions. */
    fun cues(bytes: ByteArray, extension: String): List<Cue> {
        val text = SubtitleText.decode(bytes)
        return when (extension.lowercase(Locale.ROOT)) {
            "vtt" -> parseVtt(text)
            "ass", "ssa" -> parseAss(text)
            else -> if (looksLikeAss(text)) parseAss(text) else parseSrt(text)
        }
    }

    private val vttTiming = Regex("""(?:(\d+):)?(\d{1,2}):(\d{1,2})\.(\d{1,3})\s*-->\s*(?:(\d+):)?(\d{1,2}):(\d{1,2})\.(\d{1,3})""")

    /** A WebVTT file's cues, their text as it is (WebVTT markup is what the receiver reads). */
    fun parseVtt(text: String): List<Cue> {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val cues = ArrayList<Cue>()
        var i = 0
        while (i < lines.size) {
            val match = vttTiming.find(lines[i])
            if (match == null) {
                i++
                continue
            }
            val g = match.groupValues
            val start = ms(g[1].ifEmpty { "0" }, g[2], g[3], g[4])
            val end = ms(g[5].ifEmpty { "0" }, g[6], g[7], g[8])
            i++
            val body = StringBuilder()
            while (i < lines.size && lines[i].isNotBlank()) {
                if (body.isNotEmpty()) body.append('\n')
                body.append(lines[i].trim())
                i++
            }
            cues += Cue(start, end, body.toString())
        }
        return cues
    }

    /**
     * One subtitle block out of a Matroska file as a cue: SubRip's text with its tags cleaned, an
     * ASS event's text (the fields before it dropped, its styling taken out), WebVTT as it is.
     * Null for an empty event or a drawing.
     */
    fun fromMatroska(codec: String, data: ByteArray, startMs: Long, endMs: Long): Cue? {
        val raw = String(data, Charsets.UTF_8).trimEnd('\u0000')
        val text = when (codec) {
            Codecs.ASS, Codecs.SSA -> {
                // ReadOrder, Layer, Style, Name, MarginL, MarginR, MarginV, Effect, Text.
                val body = raw.split(',', limit = 9).let { if (it.size == 9) it[8] else raw }
                if (Regex("""\\p[1-9]""").containsMatchIn(body)) return null
                body.replace(Regex("""\{[^}]*\}"""), "")
                    .replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " ")
                    .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                    .trim()
            }
            Codecs.VTT -> raw.trim()
            else -> cleanSrt(raw.replace("\r\n", "\n").trim())
        }
        if (text.isBlank() || endMs <= startMs) return null
        return Cue(startMs, endMs, text)
    }

    /**
     * One HLS subtitle segment: the cues that start in it. The timestamp map ties the cue times to
     * the media's own clock, which here starts at zero with the video, as the fMP4 segments do.
     * Written as RFC 8216 has it, with colons (MPEGTS:0,LOCAL:...): Shaka Player, which the Default
     * Media Receiver plays HLS with, finds the header, cannot read a map written with equals signs,
     * and fails the whole video with INVALID_TEXT_HEADER, which is what the device test met.
     */
    fun segment(cues: List<Cue>): String {
        val sb = StringBuilder("WEBVTT\n").append(TIMESTAMP_MAP).append("\n\n")
        cues.sortedBy { it.startMs }.forEach { cue ->
            if (cue.text.isBlank() || cue.endMs <= cue.startMs) return@forEach
            sb.append(stamp(cue.startMs)).append(" --> ").append(stamp(cue.endMs)).append('\n')
            sb.append(cue.text.replace("-->", "->").lines().filter { it.isNotBlank() }.joinToString("\n"))
            sb.append("\n\n")
        }
        return sb.toString()
    }

    private fun assTime(t: String): Long? {
        val parts = t.split(':')
        if (parts.size != 3) return null
        val h = parts[0].toLongOrNull() ?: return null
        val m = parts[1].toLongOrNull() ?: return null
        val sec = parts[2].split('.')
        val s = sec[0].toLongOrNull() ?: return null
        val cs = sec.getOrNull(1)?.padEnd(2, '0')?.take(3)?.let { if (it.length == 2) it.toLongOrNull()?.times(10) else it.toLongOrNull() } ?: 0
        return h * 3_600_000 + m * 60_000 + s * 1000 + cs
    }
}
