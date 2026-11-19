package com.tmplayer.dev

import com.tmplayer.data.MediaItem
import com.tmplayer.data.ShelfEntry
import org.json.JSONObject

/**
 * The pure half of the names dump (debug and preview builds only, see [NameDumpReceiver]): where
 * [com.tmplayer.data.SeriesShelf.arrange] put each video, one JSON line per video, and the
 * leftovers a reader should look at. Nothing here touches TDLib or the disk.
 */
object NameDump {

    /** Where one video ended up on its chat's shelf. */
    data class Placement(
        /** "show:<key>", "film-copy-group", "file", or "unlisted" for one the shelf left out. */
        val shelf: String,
        val season: Int? = null,
        val episode: Int? = null,
        /** True for a whole-season file listed under a show rather than as an episode. */
        val pack: Boolean = false,
    ) {
        val isFile: Boolean get() = shelf == FILE
    }

    const val FILE = "file"
    const val FILM_COPY_GROUP = "film-copy-group"
    const val UNLISTED = "unlisted"

    /**
     * Each of [items] by id, as [entries] (the shelf arranged over them) placed it. A video folded
     * into another (a copy, a piece, a part) gets the placement of what it was folded into.
     */
    fun placements(items: List<MediaItem>, entries: List<ShelfEntry>): Map<String, Placement> {
        val out = HashMap<String, Placement>(items.size)
        fun put(item: MediaItem, placement: Placement) {
            out.putIfAbsent(item.id, placement)
            item.pieces.forEach { out.putIfAbsent(it.id, placement) }
            item.parts.forEach { part ->
                out.putIfAbsent(part.id, placement)
                part.pieces.forEach { out.putIfAbsent(it.id, placement) }
            }
        }
        for (entry in entries) when (entry) {
            is ShelfEntry.Show -> {
                val shelf = "show:${entry.series.key}"
                for (episode in entry.series.episodes) {
                    val placement = Placement(shelf, episode.season, episode.episode)
                    episode.copies.forEach { put(it, placement) }
                    episode.alsoIn.forEach { put(it, placement) }
                }
                for (pack in entry.series.packs) put(pack.item, Placement(shelf, pack.season, null, pack = true))
            }
            is ShelfEntry.File -> {
                val item = entry.item
                if (item.versions.size > 1) {
                    val placement = Placement(FILM_COPY_GROUP)
                    put(item, placement)
                    item.versions.forEach { put(it, placement) }
                } else {
                    put(item, Placement(FILE))
                }
            }
        }
        return out
    }

    /** The first non-blank line of [caption], at most [CAPTION_MAX] characters. */
    fun captionLine(caption: String): String =
        caption.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty().take(CAPTION_MAX)

    /** One video as a line of names.jsonl. */
    fun jsonLine(chat: String, chatKind: String, item: MediaItem, placement: Placement): String {
        val name = item.parsedName
        val parsed = JSONObject()
            .put("title", name.title)
            .put("year", name.year ?: JSONObject.NULL)
            .put("season", name.season ?: JSONObject.NULL)
            .put("episode", name.episode ?: JSONObject.NULL)
            .put("lastEpisode", name.lastEpisode ?: JSONObject.NULL)
            .put("extra", name.extra?.name ?: JSONObject.NULL)
            .put("airDate", name.airDate ?: JSONObject.NULL)
        val line = JSONObject()
            .put("chat", chat)
            .put("chatKind", chatKind)
            .put("fileName", item.fileName)
            .put("caption", captionLine(item.caption))
            .put("sizeBytes", item.sizeBytes)
            .put("durationSec", item.durationSec)
            .put("date", item.date)
            .put("parsed", parsed)
            .put("shelf", placement.shelf)
        if (placement.season != null) line.put("showSeason", placement.season)
        if (placement.episode != null) line.put("showEpisode", placement.episode)
        if (placement.pack) line.put("pack", true)
        return line.toString()
    }

    /** What a leftover is judged by: its file name, else its caption's first line. */
    fun nameOf(item: MediaItem): String = item.fileName.ifBlank { captionLine(item.caption) }

    /**
     * Whether [name] holds a number of one to three digits that is not a resolution, a year or a
     * codec: a name that may be an episode the shelf failed to group.
     */
    fun mayBeEpisode(name: String): Boolean {
        var text = name.lowercase()
        for (noise in NOISE) text = noise.replace(text, " ")
        return TOKEN.split(text).any { token ->
            token.length in 1..3 && token.all { it in '0'..'9' } && token !in NOT_EPISODES
        }
    }

    /** The lines of the LEFTOVERS section for one chat: files whose name [mayBeEpisode]. */
    fun leftovers(chat: String, items: List<MediaItem>, placed: Map<String, Placement>): List<String> =
        items.filter { placed[it.id]?.isFile == true }
            .map(::nameOf)
            // A name with a year is a film: its stray numbers are audio layouts and sizes.
            .filter { !YEAR.containsMatchIn(it) && mayBeEpisode(it) }
            .map { "$chat\t$it" }

    const val CAPTION_MAX = 200

    private val TOKEN = Regex("[^a-z0-9]+")
    private val YEAR = Regex("(?<!\\d)(?:19|20)\\d{2}(?!\\d)")

    /** Taken out before the numbers are read: resolutions, codecs, bit depths, audio channels, sizes. */
    private val NOISE = listOf(
        Regex("""\b\d{3,4}\s?[pi]\b"""),
        Regex("""\b\d{3,4}x\d{3,4}\b"""),
        Regex("""\b[xh]\.?26[45]\b"""),
        Regex("""\b\d{1,2}\s?bits?\b"""),
        Regex("""\b(?:aac|ac3|eac3|ddp?|dts|atmos|truehd|opus|flac)\s?\d(?:\.\d)?\b"""),
        Regex("""\b\d\.\d\b"""),
        Regex("""\b\d+(?:\.\d+)?\s?(?:mb|gb|kb)\b"""),
    )

    /** Bare numbers that are a resolution or a codec even when written alone. */
    private val NOT_EPISODES = setOf("144", "240", "360", "480", "540", "576", "720", "264", "265")
}
