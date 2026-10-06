package com.tmplayer.data

/**
 * What goes into the Android TV home screen's "Play next" row, decided away from the platform.
 *
 * The row is the launcher's, not the app's: entries are written through the TV provider and the
 * launcher draws them. This only says which entries should exist, so the rules are the resume
 * rules ([ResumeRules]) and can be tested without a television. Off unless the viewer turns it on,
 * and never on a device whose home screen has no such row ([RemoteQuirks.watchNextSupported]).
 */
object WatchNext {

    enum class Kind {
        /** Part way through: the launcher draws the progress and opens it where it stopped. */
        Continue,

        /** The episode after one just finished. */
        Next,
    }

    /** Everything the launcher needs to draw an entry and to start it again from a tap. */
    data class Video(
        val chatId: Long,
        val messageId: Long,
        val fileId: Int,
        val title: String,
        val chatTitle: String,
        val sizeBytes: Long,
        val durationSec: Int,
    ) {
        /** What the provider row is keyed by, so a later write replaces it rather than adding one. */
        val internalId: String get() = "${chatId}_$messageId"

        /** What the player is opened with when the entry is picked on the home screen. */
        fun toMediaItem(): MediaItem = MediaItem(
            chatId = chatId,
            messageId = messageId,
            fileId = fileId,
            title = title,
            sizeBytes = sizeBytes,
            durationSec = durationSec,
            mimeType = "",
            thumbnailFileId = 0,
            miniThumbnail = null,
            date = 0,
            fileName = title,
        )

        companion object {
            fun of(item: MediaItem, chatTitle: String): Video = Video(
                chatId = item.chatId,
                messageId = item.messageId,
                fileId = item.fileId,
                title = item.title,
                chatTitle = chatTitle,
                sizeBytes = item.sizeBytes,
                durationSec = item.durationSec,
            )
        }
    }

    data class Entry(
        val kind: Kind,
        val video: Video,
        val positionMs: Long,
        val durationMs: Long,
        /** When the viewer last touched it; the launcher sorts the row by this. */
        val lastEngagementMs: Long,
    ) {
        val internalId: String get() = video.internalId
    }

    sealed interface Change {
        data class Upsert(val entry: Entry) : Change
        data class Remove(val internalId: String) : Change
    }

    /**
     * The changes a stop at [positionMs] makes to the row.
     *
     * - Under three minutes in: nothing. A glance at a video is not a place to come back to, and
     *   a "Play next" entry somebody opened by mistake stays where it was.
     * - Past the watched line (90 per cent): this video leaves the row, and [next], when the chat
     *   has one, takes its place as a Next entry from the start.
     * - Between the two, short of the last 8 per cent: a Continue entry at [positionMs].
     * - In the gap between the resume line and the watched line: this video leaves the row, the
     *   same moment its resume point is dropped.
     */
    fun changesFor(
        video: Video,
        positionMs: Long,
        durationMs: Long,
        nowMs: Long,
        next: Video?,
        enabled: Boolean,
        supported: Boolean,
    ): List<Change> {
        if (!enabled || !supported) return emptyList()
        if (video.chatId == 0L || video.messageId == 0L) return emptyList()
        if (ResumeRules.watched(positionMs, durationMs)) {
            return buildList {
                add(Change.Remove(video.internalId))
                if (next != null && next.internalId != video.internalId) {
                    val nextDuration = next.durationSec.toLong() * 1_000
                    add(Change.Upsert(Entry(Kind.Next, next, 0L, nextDuration, nowMs)))
                }
            }
        }
        if (positionMs < ResumeRules.MIN_POSITION_MS) return emptyList()
        if (!ResumeRules.keeps(positionMs, durationMs)) return listOf(Change.Remove(video.internalId))
        return listOf(Change.Upsert(Entry(Kind.Continue, video, positionMs, durationMs, nowMs)))
    }
}
