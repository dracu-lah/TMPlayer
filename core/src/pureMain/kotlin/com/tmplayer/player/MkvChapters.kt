package com.tmplayer.player

import com.tmplayer.i18n.TrackCodes

/**
 * The chapter marks of a Matroska file (.mkv, .webm), read from its bytes.
 *
 * mpv reads them on the desktop. Media3's Matroska extractor skips the Chapters element entirely,
 * so on Android the player had a chaptered episode's marks on disk and nowhere to show them. This
 * reads them from the parts of the file already downloaded, the same way the subtitle hash does:
 * asking TDLib for bytes it has not got would move the download window the player is streaming
 * from, so a reader that answers null for a part not yet there is all this is ever given.
 *
 * Where they are: the file starts with the EBML header and then the Segment, whose first children
 * are a SeekHead (the table of where the big elements sit), Info and Tracks. mkvmerge and ffmpeg
 * both write Chapters before the first Cluster, so the head of the file, which the player reads
 * first anyway, nearly always holds them or at least the SeekHead entry that says where they are.
 *
 * Only what a timebar needs is kept: each chapter's start and a display name. Hidden or disabled
 * chapters, and chapters that point into another file (ordered chapters with a segment link), are
 * left out, and of several editions the default one is read.
 */
object MkvChapters {

    /** How much of the start of the file is read first. */
    const val HEAD_BYTES = 256 * 1024

    /** More chapters than anyone writes; a bigger element is not read. */
    const val MAX_ELEMENT_BYTES = 1024 * 1024

    /** The most chapters kept, the first by start, as an MP4's chapter track is held to ([Mp4Chapters]). */
    const val MAX_CHAPTERS = 1000

    /** What the head of the file says about chapters. */
    sealed interface Head {
        data object NotMatroska : Head
        data object None : Head
        data class Found(val chapters: List<Chapter>) : Head

        /** The Chapters element starts at [offset] in the file, past what was read. */
        data class At(val offset: Long) : Head
    }

    /**
     * The chapters of a file, given [read], which returns the [length] bytes at an offset (fewer at
     * the end of the file) or null when that part is not on the disk. Empty for a file with none,
     * one that is not Matroska, or one whose chapters are not downloaded yet.
     */
    suspend fun read(language: String? = null, read: suspend (offset: Long, length: Int) -> ByteArray?): List<Chapter> {
        val head = read(0, HEAD_BYTES) ?: return emptyList()
        return when (val found = readHead(head, language)) {
            is Head.Found -> found.chapters
            Head.None, Head.NotMatroska -> emptyList()
            is Head.At -> {
                val start = read(found.offset, 16) ?: return emptyList()
                val total = elementLength(start) ?: return emptyList()
                if (total > MAX_ELEMENT_BYTES) return emptyList()
                val element = read(found.offset, total.toInt()) ?: return emptyList()
                parseElement(element, language) ?: emptyList()
            }
        }
    }

    /** Reads the start of a file: the chapters themselves when they are in it, or where they are. */
    fun readHead(head: ByteArray, language: String? = null): Head {
        val c = Cursor(head)
        val ebml = c.element() ?: return Head.NotMatroska
        if (ebml.id != EBML || ebml.size < 0) return Head.NotMatroska
        if (ebml.dataStart + ebml.size >= head.size) return Head.None
        c.pos = (ebml.dataStart + ebml.size).toInt()
        val segment = c.element() ?: return Head.None
        if (segment.id != SEGMENT) return Head.NotMatroska
        val segmentStart = segment.dataStart
        var pointed: Long? = null
        while (c.pos < head.size) {
            val at = c.pos
            val child = c.element() ?: break
            when (child.id) {
                CHAPTERS -> {
                    val end = child.dataStart + child.size
                    if (child.size < 0 || end > head.size) return Head.At(at.toLong())
                    val chapters = parseChapters(head, child.dataStart, end.toInt(), language)
                    return if (chapters.isEmpty()) Head.None else Head.Found(chapters)
                }
                SEEK_HEAD -> if (child.size >= 0 && child.dataStart + child.size <= head.size) {
                    seekTo(head, child.dataStart, (child.dataStart + child.size).toInt(), CHAPTERS)
                        ?.let { pointed = segmentStart + it }
                }
                // The media has started: whatever was going to come before it has.
                CLUSTER -> break
            }
            if (child.size < 0) break
            c.pos = (child.dataStart + child.size).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
        val offset = pointed ?: return Head.None
        // Pointed at a place inside what was read, but not where an element was found: read it there.
        if (offset < head.size) {
            val inside = runCatching { head.copyOfRange(offset.toInt(), head.size) }.getOrNull()
            val chapters = inside?.let { parseElement(it, language) }
            if (chapters != null) return if (chapters.isEmpty()) Head.None else Head.Found(chapters)
        }
        return Head.At(offset)
    }

    /** The whole length of the element starting at [bytes]' first byte, header included, or null. */
    fun elementLength(bytes: ByteArray): Long? {
        val e = Cursor(bytes).element() ?: return null
        if (e.size < 0) return null
        return e.dataStart + e.size
    }

    /**
     * The chapters in a Chapters element that starts at [bytes]' first byte: null when it is not
     * one or is cut short, empty when it holds none worth showing.
     */
    fun parseElement(bytes: ByteArray, language: String? = null): List<Chapter>? {
        val e = Cursor(bytes).element() ?: return null
        if (e.id != CHAPTERS || e.size < 0) return null
        val end = e.dataStart + e.size
        if (end > bytes.size) return null
        return parseChapters(bytes, e.dataStart, end.toInt(), language)
    }

    private fun seekTo(bytes: ByteArray, from: Int, to: Int, target: Long): Long? {
        for (seek in children(bytes, from, to)) {
            if (seek.id != SEEK) continue
            var id: Long? = null
            var position: Long? = null
            for (field in children(bytes, seek.dataStart, seek.end)) {
                when (field.id) {
                    SEEK_ID -> id = uint(bytes, field)
                    SEEK_POSITION -> position = uint(bytes, field)
                }
            }
            if (id == target && position != null) return position
        }
        return null
    }

    private class Edition(val default: Boolean, val hidden: Boolean, val chapters: List<Chapter>)

    private fun parseChapters(bytes: ByteArray, from: Int, to: Int, language: String?): List<Chapter> {
        val editions = children(bytes, from, to).filter { it.id == EDITION }.map { edition ->
            var default = false
            var hidden = false
            val chapters = mutableListOf<Chapter>()
            for (child in children(bytes, edition.dataStart, edition.end)) {
                when (child.id) {
                    EDITION_DEFAULT -> default = uint(bytes, child) == 1L
                    EDITION_HIDDEN -> hidden = uint(bytes, child) == 1L
                    ATOM -> atom(bytes, child, language)?.let(chapters::add)
                }
            }
            Edition(default, hidden, chapters)
        }
        val chosen = editions.firstOrNull { it.default && !it.hidden }
            ?: editions.firstOrNull { !it.hidden }
            ?: editions.firstOrNull()
            ?: return emptyList()
        return chosen.chapters.sortedBy { it.startMs }.distinctBy { it.startMs }.take(MAX_CHAPTERS)
    }

    private fun atom(bytes: ByteArray, atom: Element, language: String?): Chapter? {
        var startNs: Long? = null
        var hidden = false
        var enabled = true
        var linked = false
        val names = mutableListOf<Pair<String?, String>>()
        for (child in children(bytes, atom.dataStart, atom.end)) {
            when (child.id) {
                TIME_START -> startNs = uint(bytes, child)
                FLAG_HIDDEN -> hidden = uint(bytes, child) == 1L
                FLAG_ENABLED -> enabled = uint(bytes, child) != 0L
                SEGMENT_UID -> linked = true
                DISPLAY -> {
                    var name: String? = null
                    var lang: String? = null
                    for (field in children(bytes, child.dataStart, child.end)) {
                        when (field.id) {
                            CHAP_STRING -> name = text(bytes, field)
                            CHAP_LANGUAGE, CHAP_LANGUAGE_BCP47 -> if (lang == null) lang = text(bytes, field)
                        }
                    }
                    if (!name.isNullOrBlank()) names += lang to name.trim()
                }
            }
        }
        val start = startNs ?: return null
        if (hidden || !enabled || linked) return null
        val wanted = language?.substringBefore('-')?.lowercase()
        val title = names.firstOrNull { (lang, _) -> wanted != null && lang != null && sameLanguage(lang, wanted) }?.second
            ?: names.firstOrNull()?.second
        return Chapter(startMs = start / 1_000_000, title = title)
    }

    /** "en" against "eng" or "en-US": Matroska's old tags are ISO 639-2, its new ones BCP 47. */
    private fun sameLanguage(tag: String, wanted: String): Boolean {
        val base = tag.substringBefore('-').lowercase()
        return base == wanted || (base.length == 3 && TrackCodes.toIso1(base) == wanted)
    }

    // ---- EBML --------------------------------------------------------------------------------

    private class Element(val id: Long, val dataStart: Int, val size: Long) {
        val end: Int get() = (dataStart + size).toInt()
    }

    /** The elements between [from] and [to], stopping at one that runs past [to] or is cut short. */
    private fun children(bytes: ByteArray, from: Int, to: Int): List<Element> {
        val out = mutableListOf<Element>()
        val c = Cursor(bytes, limit = to)
        c.pos = from
        while (c.pos < to) {
            val e = c.element() ?: break
            if (e.size < 0 || e.dataStart + e.size > to) break
            out += e
            c.pos = e.end
        }
        return out
    }

    private fun uint(bytes: ByteArray, e: Element): Long? {
        if (e.size !in 1..8) return if (e.size == 0L) 0L else null
        var v = 0L
        for (i in 0 until e.size.toInt()) v = (v shl 8) or (bytes[e.dataStart + i].toLong() and 0xFF)
        return v
    }

    private fun text(bytes: ByteArray, e: Element): String =
        bytes.decodeToString(e.dataStart, e.end).trimEnd('\u0000')

    private class Cursor(val bytes: ByteArray, val limit: Int = bytes.size) {
        var pos = 0

        /** The element header at [pos], moving past it; null when it is cut short or malformed. */
        fun element(): Element? {
            val id = vint(keepMarker = true, maxLength = 4) ?: return null
            val size = vint(keepMarker = false, maxLength = 8) ?: return null
            return Element(id, pos, size)
        }

        /** An EBML variable length integer; -1 for a size of all ones, which means unknown. */
        private fun vint(keepMarker: Boolean, maxLength: Int): Long? {
            if (pos >= limit) return null
            val first = bytes[pos].toInt() and 0xFF
            if (first == 0) return null
            val length = first.countLeadingZeroBits() - 23
            if (length > maxLength || pos + length > limit) return null
            var value = if (keepMarker) first.toLong() else (first and (0xFF shr length)).toLong()
            var allOnes = value == (0xFF shr length).toLong()
            for (i in 1 until length) {
                val b = bytes[pos + i].toInt() and 0xFF
                if (b != 0xFF) allOnes = false
                value = (value shl 8) or b.toLong()
            }
            pos += length
            return if (!keepMarker && allOnes) -1 else value
        }
    }

    private const val EBML = 0x1A45DFA3L
    private const val SEGMENT = 0x18538067L
    private const val SEEK_HEAD = 0x114D9B74L
    private const val SEEK = 0x4DBBL
    private const val SEEK_ID = 0x53ABL
    private const val SEEK_POSITION = 0x53ACL
    private const val CLUSTER = 0x1F43B675L
    private const val CHAPTERS = 0x1043A770L
    private const val EDITION = 0x45B9L
    private const val EDITION_HIDDEN = 0x45BDL
    private const val EDITION_DEFAULT = 0x45DBL
    private const val ATOM = 0xB6L
    private const val TIME_START = 0x91L
    private const val FLAG_HIDDEN = 0x98L
    private const val FLAG_ENABLED = 0x4598L
    private const val SEGMENT_UID = 0x6E67L
    private const val DISPLAY = 0x80L
    private const val CHAP_STRING = 0x85L
    private const val CHAP_LANGUAGE = 0x437CL
    private const val CHAP_LANGUAGE_BCP47 = 0x437DL
}
