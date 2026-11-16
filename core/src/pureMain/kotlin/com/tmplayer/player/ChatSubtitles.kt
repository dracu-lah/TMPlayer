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
        // Each pass peels the last word; a long run of dots and tags costs a bounded number of passes.
        var passes = 0
        while (passes++ < MAX_TAGS) {
            val at = stem.lastIndexOfAny(charArrayOf('.', '_', ' '))
            val tag = stem.substring(at + 1).lowercase()
            if (at > 0) {
                val peeled = when {
                    tag == "forced" -> { forced = true; true }
                    tag in HEARING -> { hearing = true; true }
                    tag in NEUTRAL -> true
                    else -> false
                }
                if (peeled) {
                    stem = stem.substring(0, at)
                    continue
                }
            }
            // A language, written as a code, a full name ("Hindi"), a name with its place ("Spanish
            // (Latin America)", "Brazilian Portuguese"). The last one counts, and it may be the whole name.
            val found = (if (language == null) languageAtEnd(stem) else null) ?: break
            language = found.first
            stem = stem.substring(0, found.second).trimEnd('.', '_', ' ', '-')
        }
        return Tags(stem, language, forced, hearing)
    }

    private const val MAX_TAGS = 12

    /** Trailing "(Latin America)" or "[Simplified]". */
    private val TRAILING_GROUP = Regex("""\s*[(\[]([^()\[\]]{2,24})[)\]]\s*$""")

    /**
     * The language the end of [stem] names and where in [stem] its words start, or null: the last
     * word as a code or a name, two words that make one ("Brazilian Portuguese"), and a place or
     * script in brackets after it. A bracket after something that is no language is left alone, so
     * "Show (2020)" keeps its year.
     */
    private fun languageAtEnd(stem: String): Pair<String, Int>? {
        val group = TRAILING_GROUP.find(stem)
        val body = if (group != null) stem.substring(0, group.range.first) else stem
        if (body.isBlank()) return null
        val qualifier = group?.groupValues?.get(1)?.trim()?.lowercase()
        val lower = body.lowercase()
        val phrase = PHRASES.entries.firstOrNull { (words, _) -> lower.endsWith(words) && (lower.length == words.length || !lower[lower.length - words.length - 1].isLetterOrDigit()) }
        if (phrase != null) return phrase.value to (body.length - phrase.key.length)
        val start = body.lastIndexOfAny(charArrayOf('.', '_', ' ')) + 1
        val word = body.substring(start)
        val code = languageCode(word) ?: return null
        return (if (qualifier != null) withPlace(code, qualifier) else code) to start
    }

    /** [code] narrowed by the place or script a name puts in brackets ("Latin America", "Brazil", "Simplified"). */
    private fun withPlace(code: String, qualifier: String): String {
        val base = code.substringBefore('-')
        val place = qualifier.replace('_', ' ').replace('-', ' ')
        return when {
            base == "zh" && ("simplified" in place || place == "hans" || place == "cn" || "china" in place) -> "zh-hans"
            base == "zh" && ("traditional" in place || place == "hant" || "taiwan" in place || "hong kong" in place) -> "zh-hant"
            base == "pt" && ("brazil" in place || "brasil" in place || place == "br") -> "pt-br"
            base == "pt" && ("portugal" in place || "european" in place || place == "pt") -> "pt-pt"
            base == "es" && ("latin" in place || "latam" in place || "mexic" in place || place == "419") -> "es-419"
            base == "es" && ("spain" in place || "castil" in place || "europe" in place) -> "es-es"
            base == "fr" && "canad" in place -> "fr-ca"
            else -> code
        }
    }

    private val PHRASES = mapOf(
        "brazilian portuguese" to "pt-br",
        "european portuguese" to "pt-pt",
        "latin american spanish" to "es-419",
        "simplified chinese" to "zh-hans",
        "traditional chinese" to "zh-hant",
        "canadian french" to "fr-ca",
    )

    /** A language tag ("en", "eng", "pt-BR", "pob") as the two letter code, or null when it is none. */
    fun languageCode(tag: String): String? {
        val raw = tag.trim().lowercase().replace('_', '-')
        if (raw.isEmpty() || raw.length > 40) return null
        NAMES[raw]?.let { return it }
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
    private val NEUTRAL = setOf("default", "full", "sub", "subs", "subtitle", "subtitles", "srt", "text", "dub", "dubs", "dubbed", "dubbing")
    private val GENERIC = setOf("sub", "subs", "subtitle", "subtitles", "srt", "english", "forced", "sdh", "cc", "caption", "captions", "text", "full", "default")
    private val LANGUAGE_NAMES: Set<String> get() = NAMES.keys

    /**
     * A language by the name it goes by in English and by the one it calls itself, lower case, as
     * the two letter code. The names of the languages release groups write after a title (Hindi,
     * Tamil, Telugu) come with the ones every subtitle site lists.
     */
    private val NAMES: Map<String, String> = buildMap {
        fun add(code: String, vararg names: String) = names.forEach { put(it, code) }
        add("en", "english", "inglês", "ingles", "anglais", "englisch")
        add("hi", "hindi", "हिन्दी", "हिंदी")
        add("ta", "tamil", "தமிழ்")
        add("te", "telugu", "తెలుగు")
        add("kn", "kannada", "ಕನ್ನಡ")
        add("ml", "malayalam", "മലയാളം")
        add("bn", "bengali", "bangla", "বাংলা")
        add("mr", "marathi", "मराठी")
        add("pa", "punjabi", "panjabi", "ਪੰਜਾਬੀ")
        add("gu", "gujarati", "ગુજરાતી")
        add("ur", "urdu", "اردو")
        add("ne", "nepali", "नेपाली")
        add("si", "sinhala", "sinhalese")
        add("es", "spanish", "español", "espanol", "castellano", "espagnol", "spanisch")
        add("fr", "french", "français", "francais", "französisch")
        add("de", "german", "deutsch", "allemand")
        add("it", "italian", "italiano", "italien")
        add("pt", "portuguese", "português", "portugues", "portugais")
        add("ru", "russian", "русский", "russisch")
        add("ar", "arabic", "العربية", "arabe")
        add("ja", "japanese", "日本語", "japonais")
        add("ko", "korean", "한국어", "coréen")
        add("zh", "chinese", "mandarin", "cantonese", "中文", "chinois")
        add("zh-hans", "简体中文", "简体")
        add("zh-hant", "繁體中文", "繁体中文", "繁體")
        add("th", "thai", "ไทย")
        add("vi", "vietnamese", "tiếng việt", "tieng viet")
        add("id", "indonesian", "bahasa indonesia")
        add("ms", "malay", "bahasa melayu")
        add("tl", "filipino", "tagalog")
        add("tr", "turkish", "türkçe", "turkce")
        add("nl", "dutch", "nederlands", "flemish")
        add("pl", "polish", "polski")
        add("sv", "swedish", "svenska")
        add("no", "norwegian", "norsk", "bokmal", "bokmål")
        add("da", "danish", "dansk")
        add("fi", "finnish", "suomi")
        add("el", "greek", "ελληνικά")
        add("he", "hebrew", "עברית")
        add("fa", "persian", "farsi", "فارسی")
        add("uk", "ukrainian", "українська")
        add("ro", "romanian", "română", "romana")
        add("hu", "hungarian", "magyar")
        add("cs", "czech", "čeština", "cestina")
        add("sk", "slovak", "slovenčina")
        add("sl", "slovenian", "slovene")
        add("hr", "croatian", "hrvatski")
        add("sr", "serbian", "srpski")
        add("bs", "bosnian")
        add("bg", "bulgarian", "български")
        add("sq", "albanian")
        add("lt", "lithuanian")
        add("lv", "latvian")
        add("et", "estonian")
        add("is", "icelandic")
        add("ga", "irish")
        add("cy", "welsh")
        add("ca", "catalan", "català")
        add("gl", "galician")
        add("eu", "basque")
        add("mk", "macedonian")
        add("sw", "swahili", "kiswahili")
        add("af", "afrikaans")
        add("am", "amharic")
        add("as", "assamese")
        add("be", "belarusian")
        add("my", "burmese")
        add("km", "khmer")
        add("lo", "lao")
        add("mn", "mongolian")
        add("ka", "georgian")
        add("hy", "armenian")
        add("az", "azerbaijani")
        add("kk", "kazakh")
        add("uz", "uzbek")
        add("ps", "pashto")
        add("ku", "kurdish")
    }

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
