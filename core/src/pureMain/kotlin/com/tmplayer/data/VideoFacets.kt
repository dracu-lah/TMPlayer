package com.tmplayer.data

/**
 * What a video is, as far as its names and Telegram's stated facts say, read once per item
 * ([MediaItem.traits]) so a chat's filters screen thousands of tiles without parsing a name twice.
 *
 * Nothing here opens a file: the picture's size is Telegram's (sent as a video) or the name's
 * ("1080p"), HDR, subtitles and the languages are the name's alone ([MediaFacts]). A fact the
 * names do not state is unknown, never guessed.
 */
data class VideoTraits(
    /** [GlobalSearch.qualityRank] of the picture: 4 for 4K, 3 for 1080p, 2 for 720p, 1 for SD, 0 unknown. */
    val pictureRank: Int,
    /** The name says HDR, HDR10, HDR10+ or Dolby Vision. */
    val hdr: Boolean,
    /** The name says it carries subtitles ("ESub", "MSubs", "Subbed"). */
    val subtitles: Boolean,
    /** The audio languages the name states, ISO 639-1 codes, in the order written ([MediaName.languages]). */
    val languages: List<String>,
) {
    companion object {
        fun of(item: MediaItem): VideoTraits {
            // A video sent as a video often has no file name: its title (from the caption) is the name then.
            val name = item.fileName.ifBlank { item.title }
            val facts = MediaFacts.read(name, item.width, item.height)
            val languages = (facts.languages + if (item.fileName.isNotBlank() && item.title != item.fileName) MediaName.languages(item.title) else emptyList()).distinct()
            return VideoTraits(
                pictureRank = facts.resolution?.let(GlobalSearch::qualityRank) ?: 0,
                hdr = facts.dynamicRange != null,
                subtitles = MediaFacts.Track.Subtitles in facts.tracks,
                languages = languages,
            )
        }
    }
}

/**
 * The filters of a chat's Sort menu beyond Show ([VideoFilter]), all of which may be on at once
 * (U6): length, picture, HDR, subtitles, language and when it was added. Remembered per chat
 * ([SettingsStore.videoFacets]) as one line ([encode]), backed up with the chat's order.
 */
data class VideoFacets(
    val length: Length = Length.Any,
    val picture: Picture = Picture.Any,
    val hdr: Boolean = false,
    val subtitles: Boolean = false,
    /** An ISO 639-1 code ([MediaName.languages]), or null for any language. */
    val language: String? = null,
    val added: Added = Added.Any,
) {
    enum class Length(val code: String) { Any("any"), Short("short"), Long("long") }

    /** At least this sharp: 1080p and up includes 4K. */
    enum class Picture(val code: String, val rank: Int) { Any("any", 0), Uhd("4k", 4), FullHd("1080", 3), Hd("720", 2) }

    /** Posted within the last [days]. */
    enum class Added(val code: String, val days: Int) { Any("any", 0), Week("week", 7), Month("month", 31), Year("year", 366) }

    val isDefault: Boolean get() = this == NONE

    /** How many filters are on, for the Sort button's badge and the note's wording. */
    val count: Int get() = listOf(length != Length.Any, picture != Picture.Any, hdr, subtitles, language != null, added != Added.Any).count { it }

    /** One line for the store: "len=short;pic=1080;hdr=1;sub=1;lang=ml;add=month", the defaults left out. */
    fun encode(): String = buildList {
        if (length != Length.Any) add("len=${length.code}")
        if (picture != Picture.Any) add("pic=${picture.code}")
        if (hdr) add("hdr=1")
        if (subtitles) add("sub=1")
        language?.let { add("lang=$it") }
        if (added != Added.Any) add("add=${added.code}")
    }.joinToString(";")

    companion object {
        val NONE = VideoFacets()

        /** Shorter than this is short: an episode of a sitcom, a clip, a lesson. */
        const val SHORT_MAX_SEC = 20 * 60

        /** At least this is long: a film, a long talk. */
        const val LONG_MIN_SEC = 60 * 60

        /**
         * [raw] back from [encode]. Each part is read on its own: an unknown key, an unknown value
         * or a malformed part is that filter off, never the whole line lost (a backup from a later
         * version, a hand-edited file).
         */
        fun decode(raw: String?): VideoFacets {
            if (raw.isNullOrBlank()) return NONE
            var facets = NONE
            for (part in raw.split(';')) {
                val eq = part.indexOf('=')
                if (eq <= 0) continue
                val key = part.substring(0, eq).trim()
                val value = part.substring(eq + 1).trim()
                facets = when (key) {
                    "len" -> facets.copy(length = Length.entries.firstOrNull { it.code == value } ?: Length.Any)
                    "pic" -> facets.copy(picture = Picture.entries.firstOrNull { it.code == value } ?: Picture.Any)
                    "hdr" -> facets.copy(hdr = value == "1")
                    "sub" -> facets.copy(subtitles = value == "1")
                    "lang" -> facets.copy(language = value.lowercase().takeIf { it.length in 2..3 && it.all { c -> c in 'a'..'z' } })
                    "add" -> facets.copy(added = Added.entries.firstOrNull { it.code == value } ?: Added.Any)
                    else -> facets
                }
            }
            return facets
        }
    }
}

/**
 * Screens a chat's arranged entries by [VideoFacets]. Pure, order-keeping and read from the
 * items' kept [MediaItem.traits], so it runs over every page loaded so far without a call or a
 * second parse; the pages still load as the viewer scrolls, each screened as it arrives.
 *
 * - **Copies and episodes.** An entry passes if any of its files does (a 1080p copy among 720p
 *   ones, a show with one episode in the last week).
 * - **Unknowns are shown.** A file whose length or picture size nothing states is never dropped by
 *   the length or picture filter: it is kept, listed after the ones that match, and counted
 *   ([Screened.unknown]) so the grid can say so. HDR, subtitles and a language are only known when
 *   the name says them, and a file that does not say is screened out, counted in [Screened.hidden].
 */
object VideoFaceting {
    enum class Verdict { Pass, Unknown, Fail }

    /** [shown] in order, the matches first and then the unknowns; [hidden] screened out. */
    data class Screened(val shown: List<ShelfEntry>, val hidden: Int, val unknown: Int)

    fun verdict(item: MediaItem, facets: VideoFacets, nowSec: Long): Verdict {
        if (facets.isDefault) return Verdict.Pass
        val traits = item.traits
        if (facets.hdr && !traits.hdr) return Verdict.Fail
        if (facets.subtitles && !traits.subtitles) return Verdict.Fail
        if (facets.language != null && facets.language !in traits.languages) return Verdict.Fail
        if (facets.added != VideoFacets.Added.Any && item.date.toLong() < nowSec - facets.added.days * 86_400L) return Verdict.Fail
        var unknown = false
        val seconds = item.parts.ifEmpty { listOf(item) }.sumOf { it.durationSec.coerceAtLeast(0) }
        when (facets.length) {
            VideoFacets.Length.Any -> Unit
            VideoFacets.Length.Short -> if (seconds <= 0) unknown = true else if (seconds >= VideoFacets.SHORT_MAX_SEC) return Verdict.Fail
            VideoFacets.Length.Long -> if (seconds <= 0) unknown = true else if (seconds < VideoFacets.LONG_MIN_SEC) return Verdict.Fail
        }
        if (facets.picture != VideoFacets.Picture.Any) {
            if (traits.pictureRank == 0) unknown = true else if (traits.pictureRank < facets.picture.rank) return Verdict.Fail
        }
        return if (unknown) Verdict.Unknown else Verdict.Pass
    }

    /** The best of [entry]'s files: one that passes makes it pass. */
    fun verdict(entry: ShelfEntry, facets: VideoFacets, nowSec: Long): Verdict {
        if (facets.isDefault) return Verdict.Pass
        var best = Verdict.Fail
        for (item in VideoSorting.items(entry)) {
            when (verdict(item, facets, nowSec)) {
                Verdict.Pass -> return Verdict.Pass
                Verdict.Unknown -> best = Verdict.Unknown
                Verdict.Fail -> Unit
            }
        }
        return best
    }

    fun screen(entries: List<ShelfEntry>, facets: VideoFacets, nowSec: Long): Screened {
        if (facets.isDefault) return Screened(entries, 0, 0)
        val passed = ArrayList<ShelfEntry>(entries.size)
        val unknown = ArrayList<ShelfEntry>()
        var hidden = 0
        for (entry in entries) {
            when (verdict(entry, facets, nowSec)) {
                Verdict.Pass -> passed += entry
                Verdict.Unknown -> unknown += entry
                Verdict.Fail -> hidden++
            }
        }
        return Screened(passed + unknown, hidden, unknown.size)
    }

    /**
     * The languages [entries]' names state, the most common first, at most [limit]: what the
     * Language group offers. [keep] (the one picked) is always offered, so it can be turned off
     * even when no loaded video names it any more.
     */
    fun languagesIn(entries: List<ShelfEntry>, keep: String? = null, limit: Int = LANGUAGES_SHOWN): List<String> {
        val counts = HashMap<String, Int>()
        for (entry in entries) {
            VideoSorting.items(entry).flatMapTo(HashSet()) { it.traits.languages }.forEach { counts[it] = (counts[it] ?: 0) + 1 }
        }
        val top = counts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).take(limit).map { it.key }
        return if (keep == null || keep in top) top else listOf(keep) + top.take(limit - 1)
    }

    const val LANGUAGES_SHOWN = 8

    /**
     * Pages a grid asks for by itself while its filters leave it too short to scroll ([mayAutoLoad]).
     * A filter that matches one video in a thousand would otherwise read a chat of 20,000 to its
     * start with nobody touching it; past this the grid offers Keep looking instead.
     */
    const val AUTO_PAGES = 3

    /**
     * Whether a grid near its end may ask for the next page by itself: always without a filter,
     * and with one while it lists at least a [screenful] (the viewer is scrolling), else for the
     * first [AUTO_PAGES] pages only ([autoLoaded] counts them).
     */
    fun mayAutoLoad(filtersOn: Boolean, shown: Int, screenful: Int, autoLoaded: Int): Boolean =
        !filtersOn || shown >= screenful || autoLoaded < AUTO_PAGES
}
