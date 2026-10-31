package com.tmplayer.data

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The "name check": every file name a chat has loaded and how the app read it, as a text file the
 * viewer keeps on their own device to see where the guesses go wrong. Never uploaded; the viewer
 * chooses whether to pass it on. `scripts/name-report.py` sums one up.
 *
 * One line per file, tab separated, under a header line naming the columns, so a spreadsheet opens
 * it as it is. Tabs and line breaks inside a name or a caption are written as spaces.
 */
object NameReport {

    /**
     * The listing a chat's grid loaded last, for the report to read: kept in memory only, and
     * never for a chat the kids lock hides ([remember]).
     */
    @Volatile
    var lastListing: List<MediaItem> = emptyList()
        private set

    /** Keeps [items] as the last listing, unless the gate keeps [chatId] out of sight. */
    fun remember(chatId: Long, items: List<MediaItem>) {
        if (!KidsLock.allows(ChatGate.browsing.value, chatId)) return
        lastListing = items
    }

    val COLUMNS = listOf(
        "file_name", "caption", "title", "year", "season", "episode", "air_date", "part", "extra",
        "languages", "key", "group", "film", "tile_title", "duration_s", "size_bytes",
    )

    /** "tmplayer-names-2026-10-15-2130.txt", in the viewer's own time zone. */
    fun fileName(now: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        "tmplayer-names-" + DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm").format(Instant.ofEpochMilli(now).atZone(zone)) + ".txt"

    /**
     * The report for [items], read as the chat's grid reads them ([SeriesShelf.arrange]), with
     * [corrections] applied. "group" numbers the files the grid folds into one tile (a show, or
     * the copies of a film); a file on its own has none.
     */
    fun text(items: List<MediaItem>, corrections: Map<String, VideoCorrection> = VideoCorrections.current): String {
        val groupOf = HashMap<String, String>()
        val arranged = SeriesShelf.arrange(items, corrections)
        arranged.forEachIndexed { index, entry ->
            val members = when (entry) {
                is ShelfEntry.Show -> entry.series.episodes.flatMap { it.copies }
                is ShelfEntry.File -> entry.item.versions.ifEmpty { listOf(entry.item) }.flatMap { it.parts.ifEmpty { listOf(it) } }
            }
            if (members.size > 1) members.forEach { groupOf[it.id] = "g${index + 1}" }
        }
        val extras = arranged.mapNotNull { (it as? ShelfEntry.File)?.item?.takeIf { item -> item.extra != null } }
            .associateBy { it.id }
        return buildString {
            appendLine(COLUMNS.joinToString("\t"))
            for (item in items) {
                val name = SeriesShelf.episodeOf(item, corrections)
                val shown = extras[item.id] ?: item
                val row = listOf(
                    item.fileName,
                    item.caption.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty(),
                    name.title,
                    name.year?.toString().orEmpty(),
                    name.season?.toString().orEmpty(),
                    name.episode?.toString().orEmpty(),
                    name.airDate.orEmpty(),
                    name.part?.toString().orEmpty(),
                    (shown.extra ?: name.extra)?.name.orEmpty(),
                    MediaName.languages(item.nameForParsing).joinToString(","),
                    SeriesShelf.candidateKey(item, corrections, name).orEmpty(),
                    groupOf[item.id].orEmpty(),
                    if (SeriesShelf.isFilm(item)) "yes" else "no",
                    SeriesShelf.tileTitle(shown),
                    item.durationSec.toString(),
                    item.sizeBytes.toString(),
                )
                appendLine(row.joinToString("\t") { it.replace(BREAKS, " ") })
            }
        }
    }

    private val BREAKS = Regex("""[\t\r\n]+""")
}
