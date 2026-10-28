package com.tmplayer.data

/**
 * What goes into TMPlayer's own channels on the Android TV home screen, decided away from the
 * platform, so the rules can be tested without a television.
 *
 * A channel is a row of cards the launcher draws under the app's name; the app writes the cards
 * through the TV provider and the launcher reads them. The cards come from Home's rows as
 * [HomeRows] already built them, nothing is fetched for them alone:
 * - [Channel.Recent], the default channel: the first few of Home's Recently added ([RECENT_LIMIT]),
 *   drawn from the chats Home picks for itself ([HomeRows.homeChats]), so it fills with nothing
 *   starred (user, 2026-10-23: it stayed empty without favourites). With no Recently added it is
 *   empty, and a launcher hides an empty channel.
 * - [Channel.WatchLater] and [Channel.NewEpisodes], which the viewer can add from the launcher's
 *   own channel list: Home's rows of the same names.
 *
 * Rules shared with the "Play next" row ([WatchNext]): nothing from a chat the kids lock leaves
 * out, and nothing from a chat that restricts saving, whose names stay off the home screen too.
 */
object TvChannels {

    enum class Channel(val key: String) {
        Recent("recent"),
        WatchLater("watch_later"),
        NewEpisodes("new_episodes"),
        ;

        companion object {
            fun ofKey(key: String?): Channel? = entries.firstOrNull { it.key == key }
        }
    }

    /**
     * Where a card's picture comes from, worked out on the device. Every card is landscape, so a
     * channel reads as one even row: the film's or show's wide picture when the online lookups are
     * on and one is known, the video's own thumbnail otherwise. Never a 2:3 poster beside them.
     */
    data class Art(
        /** The thumbnail's TDLib file; 0 for a video known only from a record, which has none. */
        val thumbnailFileId: Int,
        /** True for a film or a show, as opposed to a plain video. */
        val titled: Boolean,
        /** The name the picture is looked up by, with the caption beside it. */
        val name: String,
        val caption: String,
    ) {
        // "l": cards since every one is landscape, so the posters written before are redrawn once.
        val key: String get() = "t$thumbnailFileId|${if (titled) "p" else "-"}|l|$name|$caption"
    }

    /** What a card does when it is picked. */
    sealed interface Target {
        /** Opens the player, as a "Play next" entry does. */
        data class Play(val video: WatchNext.Video) : Target

        /** Opens a folded show's page on Home. */
        data class Show(val chatId: Long, val seriesKey: String, val title: String) : Target

        val key: String
            get() = when (this) {
                is Play -> "play|${video.chatId}|${video.messageId}|${video.fileId}|${video.title}|${video.chatTitle}|${video.sizeBytes}|${video.durationSec}"
                is Show -> "show|$chatId|$seriesKey|$title"
            }
    }

    data class Program(
        val channel: Channel,
        /** What the provider row is keyed by: the channel and the video or show, so each card has one row. */
        val internalId: String,
        val chatId: Long,
        val title: String,
        val description: String,
        val durationMs: Long,
        /** Where the viewer stopped, or 0. */
        val positionMs: Long,
        val art: Art,
        val target: Target,
        /** Higher first: the launcher orders a channel by it. */
        val weight: Int,
        /** A show rather than a single video, which the launcher labels differently. */
        val isShow: Boolean = false,
    ) {
        /**
         * Everything the card shows and does, so a card that has not changed is not written again:
         * every write is a provider round trip and wakes the launcher to redraw the row.
         */
        val fingerprint: String
            get() = listOf(
                title,
                description,
                durationMs.toString(),
                positionMs.toString(),
                art.key,
                target.key,
                weight.toString(),
                isShow.toString(),
            ).joinToString(SEPARATOR)
    }

    /** Cards per channel: Home's own row length. */
    const val LIMIT = HomeRows.LIMIT

    /** Cards in the default channel: a few of the newest, not a whole row (user, 2026-10-23). */
    const val RECENT_LIMIT = 6

    private const val SEPARATOR = "\u001f"

    /**
     * The cards every channel should hold, or null while Home's rows are still loading: a channel
     * is not emptied by rows that have not answered yet, only by rows that answered with nothing.
     *
     * Recently added settling is what says Home has loaded: it comes from a search, so by then the
     * stores Watch later and New episodes are drawn from have long been read.
     *
     * @param allowed the kids lock's chats, or null while it is off.
     * @param chatTitle a chat's name, for the line under a card.
     * @param progress where the viewer stopped in a video, when they did.
     * @param art Watch later's videos as Telegram describes them, by progress key, for their thumbnails.
     */
    fun plan(
        rows: List<HomeRow>,
        allowed: Set<Long>?,
        chatTitle: (Long) -> String,
        progress: (chatId: Long, messageId: Long) -> WatchPoint? = { _, _ -> null },
        art: Map<String, MediaItem> = emptyMap(),
    ): List<Program>? {
        val recentEntries = recentEntries(rows) ?: return null
        return buildList {
            addAll(entries(Channel.Recent, recentEntries, chatTitle, progress, RECENT_LIMIT))
            rows.filterIsInstance<HomeRow.WatchLater>().firstOrNull()?.let { row ->
                addAll(watchLater(row.records, art, progress))
            }
            rows.filterIsInstance<HomeRow.NewEpisodes>().firstOrNull()?.let { row ->
                addAll(newEpisodes(row.episodes, chatTitle, progress))
            }
        }.filter { KidsLock.allows(allowed, it.chatId) }
    }

    /** Recently added's tiles, or null while they are loading. */
    private fun recentEntries(rows: List<HomeRow>): List<ShelfEntry>? {
        // Built as a placeholder while the search runs, and left out once it found nothing.
        val row = rows.filterIsInstance<HomeRow.Recent>().firstOrNull() ?: return emptyList()
        return if (row.loaded) row.entries else null
    }

    private fun entries(
        channel: Channel,
        entries: List<ShelfEntry>,
        chatTitle: (Long) -> String,
        progress: (Long, Long) -> WatchPoint?,
        limit: Int = LIMIT,
    ): List<Program> {
        val saveable = entries.filter { entry ->
            when (entry) {
                is ShelfEntry.File -> entry.item.canBeSaved
                is ShelfEntry.Show -> entry.series.episodes.all { it.item.canBeSaved }
            }
        }
        // Films and shows only, as on Home (user, 2026-10-22: random videos on the home screen's
        // row); the other videos only when the chats hold no film or show at all.
        val titled = saveable.filter { entry ->
            when (entry) {
                is ShelfEntry.File -> SeriesShelf.isFilm(entry.item)
                is ShelfEntry.Show -> true
            }
        }
        val kept = titled.ifEmpty { saveable }.take(limit)
        return kept.mapIndexed { index, entry ->
            val weight = kept.size - index
            when (entry) {
                is ShelfEntry.File -> fileProgram(channel, entry.item, chatTitle(entry.item.chatId), progress, weight)
                is ShelfEntry.Show -> {
                    val cover = entry.series.cover
                    Program(
                        channel = channel,
                        internalId = "${channel.key}:show:${cover.chatId}:${entry.series.key}",
                        chatId = cover.chatId,
                        title = entry.series.title,
                        description = chatTitle(cover.chatId),
                        durationMs = 0,
                        positionMs = 0,
                        art = Art(cover.thumbnailFileId, titled = true, cover.nameForParsing, cover.caption),
                        target = Target.Show(cover.chatId, entry.series.key, entry.series.title),
                        weight = weight,
                        isShow = true,
                    )
                }
            }
        }
    }

    private fun fileProgram(
        channel: Channel,
        item: MediaItem,
        chatTitle: String,
        progress: (Long, Long) -> WatchPoint?,
        weight: Int,
        thumbnail: MediaItem = item,
    ): Program {
        val film = SeriesShelf.isFilm(item)
        val point = progress(item.chatId, item.messageId)
        val durationMs = item.durationSec.toLong() * 1_000
        return Program(
            channel = channel,
            internalId = "${channel.key}:${item.chatId}_${item.messageId}",
            chatId = item.chatId,
            title = if (film) SeriesShelf.posterTitle(item) else SeriesShelf.tileTitle(item),
            description = chatTitle,
            durationMs = durationMs.takeIf { it > 0 } ?: point?.durationMs ?: 0,
            positionMs = point?.positionMs?.takeIf { it > 0 } ?: 0,
            art = Art(thumbnail.thumbnailFileId, titled = film, item.nameForParsing, item.caption),
            target = Target.Play(WatchNext.Video.of(item, chatTitle)),
            weight = weight,
        )
    }

    private fun watchLater(
        records: List<WatchLaterRecord>,
        art: Map<String, MediaItem>,
        progress: (Long, Long) -> WatchPoint?,
    ): List<Program> {
        // A record keeps no word of the chat's saving rules; the video as Telegram described it
        // for the tile's picture does, and one restricted since it was saved stays off.
        // A show (HX20) is left out: the launcher plays what a card names, and a show is a page to open.
        val kept = records.filter { !it.isShow && art[it.key]?.canBeSaved != false }.take(LIMIT)
        return kept.mapIndexed { index, record ->
            val item = record.toMediaItem()
            fileProgram(Channel.WatchLater, item, record.chatTitle, progress, kept.size - index, thumbnail = art[record.key] ?: item)
        }
    }

    private fun newEpisodes(
        episodes: List<NewEpisode>,
        chatTitle: (Long) -> String,
        progress: (Long, Long) -> WatchPoint?,
    ): List<Program> {
        val kept = episodes.filter { it.item.canBeSaved }.take(LIMIT)
        return kept.mapIndexed { index, episode ->
            fileProgram(Channel.NewEpisodes, episode.item, chatTitle(episode.item.chatId), progress, kept.size - index).copy(
                title = episode.show,
                description = listOf(episode.code, chatTitle(episode.item.chatId)).filter { it.isNotBlank() }.joinToString(" · "),
                // An episode is a frame of the show, not its backdrop: two new episodes would wear one picture.
                art = Art(episode.item.thumbnailFileId, titled = false, episode.item.nameForParsing, episode.item.caption),
            )
        }
    }

    /** A card already in the provider, as [diff] needs it. */
    data class Existing(
        val rowId: Long,
        /** What [Program.fingerprint] was when the row was written; null for a row without one. */
        val fingerprint: String?,
        /** False once the viewer took the card off the home screen. */
        val browsable: Boolean = true,
    )

    data class Diff(
        val insert: List<Program>,
        /** Rows to write again, by provider id. */
        val update: List<Pair<Long, Program>>,
        /** Provider ids to take out. */
        val delete: List<Long>,
    ) {
        val isEmpty: Boolean get() = insert.isEmpty() && update.isEmpty() && delete.isEmpty()
    }

    /**
     * The writes that turn [existing] (by internal id) into [desired]: new cards inserted, changed
     * ones written again, gone ones deleted, and the rest left alone.
     *
     * A card the viewer removed on the home screen is not written again while it stays wanted:
     * that is an answer, as it is for "Play next".
     */
    fun diff(existing: Map<String, Existing>, desired: List<Program>): Diff {
        val wanted = desired.distinctBy { it.internalId }
        val ids = wanted.mapTo(HashSet()) { it.internalId }
        val insert = mutableListOf<Program>()
        val update = mutableListOf<Pair<Long, Program>>()
        wanted.forEach { program ->
            val row = existing[program.internalId]
            when {
                row == null -> insert += program
                !row.browsable -> Unit
                row.fingerprint != program.fingerprint -> update += row.rowId to program
            }
        }
        val delete = existing.filterKeys { it !in ids }.values.map { it.rowId }
        return Diff(insert, update, delete)
    }
}
