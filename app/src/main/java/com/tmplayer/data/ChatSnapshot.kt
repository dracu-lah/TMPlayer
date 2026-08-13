package com.tmplayer.data

/**
 * The chat list, reduced to what it takes to paint one, so the first frame does not wait on TDLib.
 *
 * Without it a cold start has to open TDLib, wait for its database and ask about three hundred
 * chats before anything can be drawn, which on a 1 GB stick is seconds of empty screen for a list
 * that is very nearly yesterday's. This is that list written down at the end of a sync and read
 * back at the start of the next one, so the app opens on something and corrects itself a moment
 * later.
 *
 * The blurred previews are deliberately not in it. They are a couple of hundred bytes each and
 * would turn a small preference write into a sixty kilobyte one on every sync; the art arrives
 * from TDLib's own cache a moment behind the names, which is the right way round.
 *
 * One chat per line, `id|kind|photoFileId|flags|unread|folders|title`, with the title last because
 * it is the only field that can itself contain the separator.
 *
 * A line with four fields rather than seven is read as an older record, the extra fields defaulting
 * to no pin, no archive and no folders. Dropping those lines instead would make the first launch
 * after an update open on the empty screen this file exists to prevent.
 */
object ChatSnapshot {

    private const val PINNED = 1
    private const val ARCHIVED = 2
    private const val MUTED = 4

    fun encode(chats: List<ChatSummary>): String =
        chats.take(MAX_ENTRIES).joinToString("\n") { chat ->
            val flags = (if (chat.isPinned) PINNED else 0) or
                (if (chat.isArchived) ARCHIVED else 0) or
                (if (chat.isMuted) MUTED else 0)
            listOf(
                chat.id.toString(),
                chat.kind.name,
                chat.photoFileId.toString(),
                flags.toString(),
                chat.unreadCount.toString(),
                chat.folderIds.joinToString(","),
                // Newlines would end the record early. Nothing else in a title can hurt it.
                chat.title.replace('\n', ' '),
            ).joinToString("|")
        }

    /** Anything malformed is dropped rather than guessed at: it is a cache, not a source. */
    fun decode(encoded: String?): List<ChatSummary> {
        if (encoded.isNullOrBlank()) return emptyList()
        return encoded.lineSequence().mapNotNull { line ->
            val wide = line.split("|", limit = FIELDS)
            // A title may itself contain the separator, so the shape of the line is decided by
            // whether the two numeric fields a current record has are actually numbers, not by
            // counting separators. Counting separators would tear an old title containing a pipe
            // into fields and throw the whole row away.
            val legacy = wide.size != FIELDS ||
                wide[3].toIntOrNull() == null ||
                wide[4].toIntOrNull() == null
            val parts = if (legacy) line.split("|", limit = LEGACY_FIELDS) else wide
            if (parts.size != if (legacy) LEGACY_FIELDS else FIELDS) return@mapNotNull null
            val id = parts[0].toLongOrNull() ?: return@mapNotNull null
            val kind = ChatKind.entries.firstOrNull { it.name == parts[1] } ?: return@mapNotNull null
            val photoFileId = parts[2].toIntOrNull() ?: return@mapNotNull null
            val flags = if (legacy) 0 else parts[3].toIntOrNull() ?: return@mapNotNull null
            val unread = if (legacy) 0 else parts[4].toIntOrNull() ?: return@mapNotNull null
            val folders = if (legacy) {
                emptyList()
            } else {
                parts[5].split(",").mapNotNull { it.toIntOrNull() }
            }
            val title = parts.last().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ChatSummary(
                id = id,
                title = title,
                miniThumbnail = null,
                photoFileId = photoFileId,
                kind = kind,
                isPinned = flags and PINNED != 0,
                isArchived = flags and ARCHIVED != 0,
                unreadCount = unread,
                isMuted = flags and MUTED != 0,
                folderIds = folders,
            )
        }.toList()
    }

    private const val FIELDS = 7
    private const val LEGACY_FIELDS = 4

    /**
     * Rather more than a screenful and rather less than the whole list.
     *
     * The snapshot exists to fill the first frame, and nobody scrolls three hundred chats before
     * the real list has arrived, so the rest would be bytes written on every sync and never read.
     */
    const val MAX_ENTRIES = 60
}
