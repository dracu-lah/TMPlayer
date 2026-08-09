package com.tmplayer.data

/**
 * A video the viewer stopped part way through, with enough detail to start it again.
 *
 * The watch position alone is not enough to build a "Continue watching" row: the chat it came
 * from may not be loaded, or may not even be in the list any more. Everything needed to open the
 * player is therefore written alongside the position.
 */
data class ResumeRecord(
    val chatId: Long,
    val messageId: Long,
    val fileId: Int,
    val title: String,
    val chatTitle: String,
    val sizeBytes: Long,
    val durationSec: Int,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
) {
    val fraction: Float
        get() = if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)

    /** Milliseconds still to watch, or 0 when the length is unknown. */
    val remainingMs: Long
        get() = if (durationMs <= 0) 0L else (durationMs - positionMs).coerceAtLeast(0L)

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
        /**
         * Unit separator. Titles are release names full of dots, dashes and brackets, and any
         * printable delimiter would eventually appear inside one.
         */
        private const val SEP = ''
        private const val FIELDS = 6

        fun encode(
            fileId: Int,
            title: String,
            chatTitle: String,
            sizeBytes: Long,
            durationSec: Int,
            updatedAt: Long,
        ): String = listOf(
            fileId.toString(),
            title.replace(SEP, ' '),
            chatTitle.replace(SEP, ' '),
            sizeBytes.toString(),
            durationSec.toString(),
            updatedAt.toString(),
        ).joinToString(SEP.toString())

        /**
         * Rebuilds a record, or returns null when the stored line cannot be trusted.
         *
         * Preferences outlive app versions, so a line written by an older build, or a half
         * written one, has to be dropped rather than crash the browse screen.
         */
        fun decode(
            key: String,
            encoded: String?,
            positionMs: Long,
            durationMs: Long,
        ): ResumeRecord? {
            if (encoded.isNullOrEmpty()) return null
            val ids = key.split('_')
            if (ids.size != 2) return null
            val chatId = ids[0].toLongOrNull() ?: return null
            val messageId = ids[1].toLongOrNull() ?: return null

            val parts = encoded.split(SEP)
            if (parts.size != FIELDS) return null
            val fileId = parts[0].toIntOrNull() ?: return null
            if (fileId <= 0) return null
            val title = parts[1]
            if (title.isBlank()) return null

            return ResumeRecord(
                chatId = chatId,
                messageId = messageId,
                fileId = fileId,
                title = title,
                chatTitle = parts[2],
                sizeBytes = parts[3].toLongOrNull() ?: 0L,
                durationSec = parts[4].toIntOrNull() ?: 0,
                positionMs = positionMs,
                durationMs = durationMs,
                updatedAt = parts[5].toLongOrNull() ?: 0L,
            )
        }
    }
}
