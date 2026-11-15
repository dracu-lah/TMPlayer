package com.tmplayer.player

import com.tmplayer.data.MediaName
import com.tmplayer.data.ParsedName
import com.tmplayer.data.SeriesShelf

/**
 * Subtitle files posted in the chat the video was posted in (EG-F48): which of the chat's
 * documents belong to the video playing, and in what order the Subtitles list offers them under
 * "In this chat". Pure, so the same rules run on the phone, the television, the desktop and the
 * web page, and are tested on real name shapes without a device or a chat.
 *
 * Only documents from one search around the video are weighed ([ChatSubtitleFinder] in core does
 * the asking), so a chat of two thousand subtitle files is never read whole: the ten best are
 * kept. Nothing here reads a file's contents; that is [ChatSubtitleText].
 */
object ChatSubtitles {

    /** How many matches the list shows, however many files the chat holds. */
    const val TOP = 10

    /** The extensions a subtitle file goes by, as the picker's. A zip is looked inside of. */
    val EXTENSIONS = setOf("srt", "ass", "ssa", "vtt", "sub")

    /** The largest subtitle worth reading: a feature film's are tens of kilobytes. */
    const val MAX_BYTES = 4L * 1024 * 1024

    /** The largest zip worth fetching. A season of subtitles is a few megabytes; a video archive is never one. */
    const val MAX_ZIP_BYTES = 8L * 1024 * 1024

    /** Message steps (`id shr 20`) either side of the video that count as "next to it". */
    const val NEIGHBOUR_STEPS = 6

    /** Seconds either side of the video's date that count as "posted with it". */
    const val NEIGHBOUR_SECONDS = 180

    /** How the file is related to the video, best first. */
    enum class Relation { Album, Reply, Neighbour, Name }

    /** How well the file's name says it is this video's: the same episode or file name, the same title, nothing. */
    enum class NameMatch { Exact, Title, None }

    /** One document of the chat, as TDLib (or the browser's client) gave it. */
    data class Doc(
        val messageId: Long,
        val fileId: Int,
        val fileName: String,
        /** The size Telegram states, 0 when it states none. */
        val size: Long,
        val date: Int = 0,
        val albumId: Long = 0,
        /** The message this one replies to, 0 for none. */
        val replyToMessageId: Long = 0,
        /** False when the chat or the message withholds saving: the file is used in the app and never handed on. */
        val canBeSaved: Boolean = true,
    )

    /** The video the files are for. */
    data class Video(
        val messageId: Long,
        val fileName: String,
        val caption: String = "",
        val date: Int = 0,
        val albumId: Long = 0,
        val replyToMessageId: Long = 0,
    )

    /** One file of the list, with why it is there. */
    data class Hit(
        val doc: Doc,
        val relation: Relation,
        val nameMatch: NameMatch,
        /** The language the name gives, as the two letter code, or null. */
        val language: String?,
        val forced: Boolean,
        val hearing: Boolean,
        /** A zip, shown as "Compressed": its entries are read when it is chosen. */
        val archive: Boolean,
    )

    /** What a file name says about itself apart from its title: the language and the flags before the extension. */
    data class Tags(val base: String, val language: String?, val forced: Boolean, val hearing: Boolean)

    /** The extension of [name], lower case, or empty. */
    fun extensionOf(name: String): String = name.substringAfterLast('.', "").lowercase()

    /** Whether a file of this name could be a subtitle file or a zip of them. */
    fun isCandidateName(name: String): Boolean = extensionOf(name).let { it in EXTENSIONS || it == "zip" }

    /**
     * Splits "Show.S01E02.eng.forced.srt" into the base ("Show.S01E02"), the language ("en") and the
     * flags. Tags are peeled from the end, a language only when it is a real language code, so a
     * title that ends in a short word ("Do.It") loses nothing unless that word is one.
     */
    fun tagsOf(fileName: String): Tags {
        var stem = fileName.substringBeforeLast('.', fileName)
        if (stem.isEmpty()) stem = fileName
        var language: String? = null
        var forced = false
        var hearing = false
        while (true) {
            val at = stem.lastIndexOfAny(charArrayOf('.', '_', ' '))
            if (at <= 0) break
            val tag = stem.substring(at + 1).lowercase()
            when {
                tag == "forced" -> forced = true
                tag in HEARING -> hearing = true
                tag in NEUTRAL -> {}
                language == null && languageCode(tag) != null -> language = languageCode(tag)
                else -> break
            }
            stem = stem.substring(0, at)
        }
        return Tags(stem, language, forced, hearing)
    }

    /** A language tag ("en", "eng", "pt-BR", "pob") as the two letter code, or null when it is none. */
    fun languageCode(tag: String): String? {
        val raw = tag.trim().lowercase().replace('_', '-')
        if (raw.isEmpty()) return null
        if (raw == "pob" || raw == "pt-br") return "pt-br"
        val base = raw.substringBefore('-')
        val region = raw.substringAfter('-', "")
        if (region.isNotEmpty() && region.length !in 2..4) return null
        return when (base.length) {
            2 -> base.takeIf { it in TWO }
            3 -> THREE[base]
            else -> null
        }?.let { if (region.isNotEmpty() && it == "pt") "pt-$region" else it }
    }

    /**
     * The files of [docs] that belong to [video], best first, at most [top] of them. [preferred] is
     * the languages the viewer wants (two letter codes, best first): among equals a file in one of
     * them comes first, then one with no language named, then the rest; forced and hearing impaired
     * versions come after a full one.
     */
    fun rank(video: Video, docs: List<Doc>, preferred: List<String> = emptyList(), top: Int = TOP): List<Hit> {
        if (top <= 0) return emptyList()
        val wanted = preferred.mapNotNull { languageCode(it) ?: it.lowercase().takeIf { c -> c.length == 2 } }
        val own = parseOf(video.fileName, video.caption)
        val ownKey = baseKey(tagsOf(video.fileName).base)
        val hits = ArrayList<Hit>()
        for (doc in docs) {
            if (doc.messageId == video.messageId || doc.messageId <= 0L) continue
            val name = doc.fileName
            val extension = extensionOf(name)
            val archive = extension == "zip"
            if (extension !in EXTENSIONS && !archive) continue
            // A file of no size is empty; one past the limit is not a subtitle, whatever it is called.
            if (doc.size <= 0L || doc.size > if (archive) MAX_ZIP_BYTES else MAX_BYTES) continue
            val tags = tagsOf(name)
            val parsedTheirs = MediaName.parse(name)
            // "Show.S01.subs": a season with no episode is a pack, which the name parser leaves unread.
            val theirs = if (parsedTheirs.season == null && parsedTheirs.episode == null) parsedTheirs.copy(season = seasonOf(tags.base)) else parsedTheirs
            val name1 = nameMatch(own, ownKey, theirs, tags.base, archive)
            val relation = relationOf(video, doc, name1)
            val conflict = conflicts(own, ownKey, theirs, tags.base, archive)
            // An album and a reply are the poster's own statement; a neighbour or a name is only a guess,
            // and a file that numbers a different episode, or names a different film, is not this one's.
            if (relation == null) continue
            if (conflict != Conflict.None && relation != Relation.Reply) {
                if (conflict == Conflict.Episode || relation != Relation.Album) continue
            }
            hits += Hit(doc, relation, name1, tags.language, tags.forced, tags.hearing, archive)
        }
        val ranked = hits.sortedWith(
            compareBy<Hit> { it.relation.ordinal }
                .thenBy { it.nameMatch.ordinal }
                .thenBy { languageRank(it.language, wanted) }
                .thenBy { it.forced }
                .thenBy { it.hearing }
                .thenBy { it.archive }
                .thenBy { kotlin.math.abs(it.doc.date - video.date) }
                .thenBy { it.doc.fileName },
        )
        // The same file posted twice (a forward) is listed once: the nearest copy, which sorted first.
        val seen = HashSet<String>()
        return ranked.filter { seen.add(it.doc.fileName.lowercase() + "|" + it.doc.size) }.take(top)
    }

    /** The doc's relation to the video, or null when it has none and its name says nothing either. */
    private fun relationOf(video: Video, doc: Doc, name: NameMatch): Relation? {
        if (video.albumId != 0L && doc.albumId == video.albumId) return Relation.Album
        if (doc.replyToMessageId == video.messageId || (video.replyToMessageId != 0L && video.replyToMessageId == doc.messageId)) {
            return Relation.Reply
        }
        val steps = kotlin.math.abs((doc.messageId shr 20) - (video.messageId shr 20))
        val seconds = if (doc.date > 0 && video.date > 0) kotlin.math.abs(doc.date - video.date) else Int.MAX_VALUE
        if (steps <= NEIGHBOUR_STEPS || seconds <= NEIGHBOUR_SECONDS) return Relation.Neighbour
        return if (name != NameMatch.None) Relation.Name else null
    }

    /** The season a name numbers on its own ("Show.S01.subs", "Show Season 2"), or null. */
    private fun seasonOf(base: String): Int? =
        SEASON.find(base)?.groupValues?.get(1)?.toIntOrNull()

    private val SEASON = Regex("""(?:^|[^a-zA-Z0-9])(?:season[ ._-]?|s)(\d{1,2})(?![0-9a-zA-Z])""", RegexOption.IGNORE_CASE)

    private enum class Conflict { None, Episode, Title }

    private fun parseOf(fileName: String, caption: String): ParsedName = MediaName.parse(fileName, caption)

    private fun nameMatch(own: ParsedName, ownKey: String, theirs: ParsedName, theirBase: String, archive: Boolean): NameMatch {
        val titleOwn = SeriesShelf.keyOf(own.title)
        val titleTheirs = SeriesShelf.keyOf(theirs.title)
        val sameTitle = titleOwn.isNotEmpty() && (titleOwn == titleTheirs)
        if (own.season != null && own.episode != null) {
            if (!archive && theirs.episode == own.episode && (theirs.season == null || theirs.season == own.season) &&
                (sameTitle || titleTheirs.isEmpty() || generic(titleTheirs))
            ) {
                return NameMatch.Exact
            }
            // A season pack: the right season, no single episode, the right show.
            if (archive && theirs.episode == null && theirs.season == own.season && sameTitle) return NameMatch.Exact
            if (sameTitle && (theirs.episode == null || theirs.episode == own.episode)) return NameMatch.Title
            return NameMatch.None
        }
        // A film or a file with no episode: the whole base name, or the same title and year.
        if (ownKey.isNotEmpty() && baseKey(theirBase) == ownKey) return NameMatch.Exact
        if (sameTitle && theirs.episode == null && (own.year == null || theirs.year == null || own.year == theirs.year)) {
            return if (own.year != null && own.year == theirs.year) NameMatch.Exact else NameMatch.Title
        }
        return NameMatch.None
    }

    private fun conflicts(own: ParsedName, ownKey: String, theirs: ParsedName, theirBase: String, archive: Boolean): Conflict {
        if (own.season != null && own.episode != null && theirs.episode != null) {
            if (theirs.episode != own.episode || (theirs.season != null && theirs.season != own.season)) return Conflict.Episode
        }
        if (own.season != null && own.episode != null && theirs.season != null && theirs.episode == null && theirs.season != own.season) {
            return Conflict.Episode
        }
        val titleOwn = SeriesShelf.keyOf(own.title)
        val titleTheirs = SeriesShelf.keyOf(theirs.title)
        if (titleOwn.isEmpty() || titleTheirs.isEmpty() || generic(titleTheirs) || generic(titleOwn)) return Conflict.None
        if (titleOwn == titleTheirs || titleOwn.contains(titleTheirs) || titleTheirs.contains(titleOwn)) return Conflict.None
        if (!archive && ownKey.isNotEmpty() && baseKey(theirBase) == ownKey) return Conflict.None
        return Conflict.Title
    }

    /** A name that says what the file is, not whose it is: "english", "subs", "subtitle". */
    private fun generic(titleKey: String): Boolean {
        val words = titleKey.split(' ').filter { it.isNotEmpty() }
        return words.isEmpty() || words.all { it in GENERIC || languageCode(it) != null || it in LANGUAGE_NAMES }
    }

    private fun languageRank(language: String?, wanted: List<String>): Int {
        if (language == null) return wanted.size
        val base = language.substringBefore('-')
        val at = wanted.indexOfFirst { it == language || it.substringBefore('-') == base }
        return if (at >= 0) at else wanted.size + 1
    }

    /** A base name reduced to letters and digits, so "Show.S01E02" and "show s01e02" are one. */
    internal fun baseKey(base: String): String =
        base.lowercase().filter { it.isLetterOrDigit() }

    private val HEARING = setOf("sdh", "cc", "hi", "hearing", "hoh")
    private val NEUTRAL = setOf("default", "full", "sub", "subs", "subtitle", "subtitles", "srt", "text")
    private val GENERIC = setOf("sub", "subs", "subtitle", "subtitles", "srt", "english", "forced", "sdh", "cc", "caption", "captions", "text", "full", "default")
    private val LANGUAGE_NAMES = setOf(
        "english", "spanish", "french", "german", "italian", "portuguese", "russian", "arabic", "chinese", "japanese",
        "korean", "hindi", "turkish", "polish", "dutch", "swedish", "norwegian", "danish", "finnish", "greek", "hebrew",
        "thai", "vietnamese", "indonesian", "ukrainian", "czech", "hungarian", "romanian", "bulgarian", "persian",
        "malayalam", "tamil", "telugu", "bengali", "urdu",
    )

    private val TWO = setOf(
        "aa", "af", "ak", "am", "ar", "as", "az", "be", "bg", "bn", "bo", "bs", "ca", "cs", "cy", "da", "de", "el", "en",
        "es", "et", "eu", "fa", "fi", "fr", "ga", "gl", "gu", "he", "hi", "hr", "hu", "hy", "id", "is", "it", "ja", "ka",
        "kk", "km", "kn", "ko", "ku", "ky", "lo", "lt", "lv", "mk", "ml", "mn", "mr", "ms", "my", "nb", "ne", "nl", "no",
        "pa", "pl", "ps", "pt", "ro", "ru", "si", "sk", "sl", "sq", "sr", "sv", "sw", "ta", "te", "th", "tl", "tr", "uk",
        "ur", "uz", "vi", "zh",
    )

    private val THREE = mapOf(
        "eng" to "en", "spa" to "es", "fre" to "fr", "fra" to "fr", "ger" to "de", "deu" to "de", "ita" to "it",
        "por" to "pt", "rus" to "ru", "ara" to "ar", "chi" to "zh", "zho" to "zh", "jpn" to "ja", "kor" to "ko",
        "hin" to "hi", "tur" to "tr", "pol" to "pl", "dut" to "nl", "nld" to "nl", "swe" to "sv", "nor" to "no",
        "dan" to "da", "fin" to "fi", "gre" to "el", "ell" to "el", "heb" to "he", "tha" to "th", "vie" to "vi",
        "ind" to "id", "ukr" to "uk", "cze" to "cs", "ces" to "cs", "hun" to "hu", "rum" to "ro", "ron" to "ro",
        "bul" to "bg", "per" to "fa", "fas" to "fa", "mal" to "ml", "tam" to "ta", "tel" to "te", "ben" to "bn",
        "urd" to "ur", "hrv" to "hr", "srp" to "sr", "slv" to "sl", "slk" to "sk", "slo" to "sk", "lit" to "lt",
        "lav" to "lv", "est" to "et", "cat" to "ca", "glg" to "gl", "baq" to "eu", "eus" to "eu", "ice" to "is",
        "isl" to "is", "alb" to "sq", "sqi" to "sq", "mac" to "mk", "mkd" to "mk", "may" to "ms", "msa" to "ms",
        "fil" to "tl", "tgl" to "tl", "bos" to "bs", "kur" to "ku", "pus" to "ps", "sin" to "si",
    )
}
