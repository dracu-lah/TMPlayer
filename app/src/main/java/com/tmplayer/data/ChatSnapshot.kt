package com.tmplayer.data

/**
 * The chat list, reduced to what it takes to paint one, so the first frame does not wait on TDLib.
 *
 * Cold start went: open TDLib, wait for its database, then ask it about three hundred chats before
 * anything at all could be drawn. On a 1 GB stick that is seconds of an empty screen every single
 * launch, for a list that is very nearly the same list it was yesterday. This is that list written
 * down at the end of a sync and read back at the start of the next one, so the app opens on
 * something and corrects itself a moment later.
 *
 * The blurred previews are deliberately not in it. They are a couple of hundred bytes each and
 * would turn a small preference write into a sixty kilobyte one on every sync; the art arrives
 * from TDLib's own cache a moment behind the names, which is the right way round.
 *
 * One chat per line, `id|kind|photoFileId|title`, with the title last because it is the only
 * field that can itself contain the separator.
 */
object ChatSnapshot {

    fun encode(chats: List<ChatSummary>): String =
        chats.take(MAX_ENTRIES).joinToString("\n") { chat ->
            listOf(
                chat.id.toString(),
                chat.kind.name,
                chat.photoFileId.toString(),
                // Newlines would end the record early. Nothing else in a title can hurt it.
                chat.title.replace('\n', ' '),
            ).joinToString("|")
        }

    /** Anything malformed is dropped rather than guessed at: it is a cache, not a source. */
    fun decode(encoded: String?): List<ChatSummary> {
        if (encoded.isNullOrBlank()) return emptyList()
        return encoded.lineSequence().mapNotNull { line ->
            val parts = line.split("|", limit = 4)
            if (parts.size < 4) return@mapNotNull null
            val id = parts[0].toLongOrNull() ?: return@mapNotNull null
            val kind = ChatKind.entries.firstOrNull { it.name == parts[1] } ?: return@mapNotNull null
            val photoFileId = parts[2].toIntOrNull() ?: return@mapNotNull null
            val title = parts[3].takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ChatSummary(
                id = id,
                title = title,
                miniThumbnail = null,
                photoFileId = photoFileId,
                kind = kind,
            )
        }.toList()
    }

    /**
     * Rather more than a screenful and rather less than the whole list.
     *
     * The snapshot exists to fill the first frame, and nobody scrolls three hundred chats before
     * the real list has arrived, so the rest would be bytes written on every sync and never read.
     */
    const val MAX_ENTRIES = 60
}
