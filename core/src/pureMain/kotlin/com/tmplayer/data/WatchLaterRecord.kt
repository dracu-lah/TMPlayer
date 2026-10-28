package com.tmplayer.data

import kotlin.concurrent.Volatile

/**
 * A video the viewer put aside to watch later, without starring the chat it came from, or a whole
 * show ([series] set, HX20).
 *
 * Carries what a tile needs to be drawn and played without that chat being loaded, as a
 * [WatchedRecord] does. The picture is not kept: a thumbnail's file id belongs to the TDLib
 * instance that handed it out, so Home asks Telegram for the video again (see
 * [ChatRepository.mediaItem]), as it does for Continue watching.
 *
 * A show is kept by its chat and its grouping key ([Series.key]), so it stays one line whatever is
 * posted next, and finishing one episode does not cross the show off as it does a video. Its
 * [messageId] and the file fields are those of one episode, the anchor: the tile asks Telegram for
 * that video's picture, and the show's page is built from it again ([stubSeries], then
 * WholeSeries searches the chat for the rest).
 */
data class WatchLaterRecord(
    val chatId: Long,
    val messageId: Long,
    val fileId: Int,
    val title: String,
    /** The file's own name, which can say more than a title taken from the caption. */
    val fileName: String,
    val chatTitle: String,
    val sizeBytes: Long,
    val durationSec: Int,
    val mimeType: String,
    val addedAt: Long,
    /** The show's grouping key ([Series.key]) for a show saved whole; empty for one video. */
    val series: String = "",
) {
    /** The video this line draws (the anchor episode's, for a show): art and tiles are looked up by it. */
    val key: String get() = ProgressKeys.of(chatId, messageId)

    /** A whole show rather than one video. */
    val isShow: Boolean get() = series.isNotEmpty()

    /**
     * The line's own name in the store, which Remove, Undo and a backup go by: [key] for a video,
     * the chat and the show's key for a show ([showId]).
     */
    val id: String get() = if (isShow) showId(chatId, series) else key

    /**
     * The show this line stands for, as far as the line alone can say: its anchor episode under
     * the show's name, for a page that then finds the rest in the chat. Null for a video, or for an
     * anchor whose name no longer reads as an episode.
     */
    fun stubSeries(): Series? {
        if (!isShow) return null
        val item = toMediaItem()
        val parsed = SeriesShelf.episodeOf(item)
        val episode = parsed.episode ?: return null
        val season = parsed.season ?: 1
        return Series(series, title, listOf(SeriesSeason(season, listOf(SeriesEpisode(item, season, episode)))))
    }

    /**
     * The video this record stands for, made once and kept: what is read off its name (the copy
     * and film keys Continue watching folds by) is kept on that item, so it is read once too.
     */
    fun toMediaItem(): MediaItem = item ?: makeItem().also { item = it }

    @Volatile private var item: MediaItem? = null

    private fun makeItem(): MediaItem = MediaItem(
        chatId = chatId,
        messageId = messageId,
        fileId = fileId,
        // For a show, the show's name; its anchor's file name (below) is what reads as an episode.
        title = title,
        sizeBytes = sizeBytes,
        durationSec = durationSec,
        mimeType = mimeType,
        thumbnailFileId = 0,
        miniThumbnail = null,
        date = 0,
        fileName = fileName.ifEmpty { title },
    )

    internal fun encode(): String = listOfNotNull(
        addedAt.toString(),
        fileId.toString(),
        title.replace(SEP, ' '),
        fileName.replace(SEP, ' '),
        chatTitle.replace(SEP, ' '),
        sizeBytes.toString(),
        durationSec.toString(),
        mimeType.replace(SEP, ' '),
        // A show's anchor: the line's name says the show, not the message.
        if (isShow) messageId.toString() else null,
    ).joinToString(SEP.toString())

    companion object {
        /** Unit separator, for the reason [ResumeRecord] uses it: titles contain everything else. */
        private const val SEP = '\u001F'
        private const val FIELDS = 8

        /** What starts the part of a show's line name after the chat. */
        private const val SHOW_MARK = "s:"

        /** In a show's line name ([showId]) and never in a video's, which is two numbers. */
        const val SHOW_SEP = "_$SHOW_MARK"

        fun of(item: MediaItem, chatTitle: String, addedAt: Long) = WatchLaterRecord(
            chatId = item.chatId,
            messageId = item.messageId,
            fileId = item.fileId,
            title = item.title,
            fileName = item.fileName,
            chatTitle = chatTitle,
            sizeBytes = item.sizeBytes,
            durationSec = item.durationSec,
            mimeType = item.mimeType,
            addedAt = addedAt,
        )

        /**
         * A whole show, kept by [anchor] (one of its episodes, the newest upload unless said) for
         * its picture and for finding it again.
         */
        fun ofShow(series: Series, chatTitle: String, addedAt: Long, anchor: MediaItem = series.cover) = WatchLaterRecord(
            chatId = anchor.chatId,
            messageId = anchor.messageId,
            fileId = anchor.fileId,
            title = series.title,
            fileName = anchor.fileName.ifEmpty { anchor.title },
            chatTitle = chatTitle,
            sizeBytes = 0L,
            durationSec = 0,
            mimeType = anchor.mimeType,
            addedAt = addedAt,
            series = series.key,
        )

        /**
         * A show's line name: the chat, then "s:" and the show's key. An older build reads what
         * follows the chat as a message id, fails, and leaves the line alone.
         */
        fun showId(chatId: Long, seriesKey: String): String = "${chatId}_$SHOW_MARK$seriesKey"

        /** Rebuilds a record, or null for a line this build cannot trust. */
        internal fun decode(ids: String, encoded: String?): WatchLaterRecord? {
            if (encoded.isNullOrEmpty()) return null
            val at = ids.indexOf('_')
            if (at <= 0) return null
            val chatId = ids.substring(0, at).toLongOrNull() ?: return null
            val rest = ids.substring(at + 1)
            val parts = encoded.split(SEP)
            // A later build may append fields; everything up to here still reads the same.
            if (parts.size < FIELDS) return null
            val series = if (rest.startsWith(SHOW_MARK)) rest.removePrefix(SHOW_MARK).takeIf { it.isNotBlank() } ?: return null else ""
            val messageId = if (series.isNotEmpty()) {
                parts.getOrNull(FIELDS)?.toLongOrNull() ?: return null
            } else {
                rest.toLongOrNull() ?: return null
            }
            return WatchLaterRecord(
                chatId = chatId,
                messageId = messageId,
                addedAt = parts[0].toLongOrNull() ?: return null,
                fileId = parts[1].toIntOrNull() ?: 0,
                title = parts[2],
                fileName = parts[3],
                chatTitle = parts[4],
                sizeBytes = parts[5].toLongOrNull() ?: 0L,
                durationSec = parts[6].toIntOrNull() ?: 0,
                mimeType = parts[7],
                series = series,
            )
        }
    }
}
