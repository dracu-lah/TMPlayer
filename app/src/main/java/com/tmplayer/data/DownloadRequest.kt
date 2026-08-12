package com.tmplayer.data

import android.content.Context
import android.content.Intent

/**
 * One video the viewer asked to keep, in the only form that survives everything it has to survive.
 *
 * A download outlives the screen that started it, the activity that screen was in, and, once it is
 * queued behind another, the moment it was asked for. It therefore cannot be a [MediaItem] held in
 * a composable: it has to cross an intent to reach the service, and it has to cross a write to disk
 * to reach the next launch after the process is killed. Both encodings are here, next to each other,
 * so a field added to one is a field the other refuses to compile without.
 */
data class DownloadRequest(
    val fileId: Int,
    val title: String,
    val sizeBytes: Long,
    val chatId: Long,
    val messageId: Long,
    val chatTitle: String,
    val durationSec: Int,
    val mimeType: String,
    val fileName: String,
) {
    /** Enough of a [MediaItem] for the Downloads list, which reads five of its fields. */
    fun item(): MediaItem = MediaItem(
        chatId = chatId,
        messageId = messageId,
        fileId = fileId,
        title = title,
        sizeBytes = sizeBytes,
        durationSec = durationSec,
        mimeType = mimeType,
        thumbnailFileId = 0,
        miniThumbnail = null,
        date = 0,
        fileName = fileName,
    )

    fun intent(context: Context, action: String): Intent =
        Intent(context, DownloadService::class.java).apply {
            this.action = action
            putExtra(DownloadService.EXTRA_FILE_ID, fileId)
            putExtra(DownloadService.EXTRA_TITLE, title)
            putExtra(DownloadService.EXTRA_SIZE, sizeBytes)
            putExtra(DownloadService.EXTRA_CHAT_ID, chatId)
            putExtra(DownloadService.EXTRA_MESSAGE_ID, messageId)
            putExtra(DownloadService.EXTRA_CHAT_TITLE, chatTitle)
            putExtra(DownloadService.EXTRA_DURATION, durationSec)
            putExtra(DownloadService.EXTRA_MIME, mimeType)
            putExtra(DownloadService.EXTRA_FILE_NAME, fileName)
        }

    /** One line of the stored queue. See [decode] for why it is not JSON. */
    fun encode(): String = listOf(
        fileId.toString(),
        title.clean(),
        sizeBytes.toString(),
        chatId.toString(),
        messageId.toString(),
        chatTitle.clean(),
        durationSec.toString(),
        mimeType.clean(),
        fileName.clean(),
    ).joinToString(SEP.toString())

    private fun String.clean(): String = replace(SEP, ' ').replace(ROW, ' ')

    companion object {
        fun from(item: MediaItem, chatTitle: String): DownloadRequest = DownloadRequest(
            fileId = item.fileId,
            title = item.title,
            sizeBytes = item.sizeBytes,
            chatId = item.chatId,
            messageId = item.messageId,
            chatTitle = chatTitle,
            durationSec = item.durationSec,
            mimeType = item.mimeType,
            fileName = item.fileName,
        )

        fun from(intent: Intent): DownloadRequest? {
            val fileId = intent.getIntExtra(DownloadService.EXTRA_FILE_ID, 0)
            if (fileId <= 0) return null
            return DownloadRequest(
                fileId = fileId,
                title = intent.getStringExtra(DownloadService.EXTRA_TITLE).orEmpty(),
                sizeBytes = intent.getLongExtra(DownloadService.EXTRA_SIZE, 0),
                chatId = intent.getLongExtra(DownloadService.EXTRA_CHAT_ID, 0),
                messageId = intent.getLongExtra(DownloadService.EXTRA_MESSAGE_ID, 0),
                chatTitle = intent.getStringExtra(DownloadService.EXTRA_CHAT_TITLE).orEmpty(),
                durationSec = intent.getIntExtra(DownloadService.EXTRA_DURATION, 0),
                mimeType = intent.getStringExtra(DownloadService.EXTRA_MIME).orEmpty(),
                fileName = intent.getStringExtra(DownloadService.EXTRA_FILE_NAME).orEmpty(),
            )
        }

        /**
         * Rebuilds a request from its stored line, or null when the line cannot be trusted.
         *
         * Separator-delimited rather than JSON for the same reason [ResumeRecord] is: the store is
         * already full of lines in this shape, it costs no dependency, and a line written by an
         * older build is dropped here rather than thrown at whatever reads the queue. Titles are
         * release names full of punctuation, so the delimiters are the unprintable ones.
         */
        fun decode(line: String): DownloadRequest? {
            val parts = line.split(SEP)
            if (parts.size != FIELDS) return null
            val fileId = parts[0].toIntOrNull() ?: return null
            if (fileId <= 0) return null
            return DownloadRequest(
                fileId = fileId,
                title = parts[1],
                sizeBytes = parts[2].toLongOrNull() ?: 0L,
                chatId = parts[3].toLongOrNull() ?: return null,
                messageId = parts[4].toLongOrNull() ?: return null,
                chatTitle = parts[5],
                durationSec = parts[6].toIntOrNull() ?: 0,
                mimeType = parts[7],
                fileName = parts[8],
            )
        }

        /** The whole queue as one preference value, since it is only ever read and written whole. */
        fun encodeAll(requests: List<DownloadRequest>): String =
            requests.joinToString(ROW.toString()) { it.encode() }

        fun decodeAll(stored: String?): List<DownloadRequest> {
            if (stored.isNullOrEmpty()) return emptyList()
            return stored.split(ROW).mapNotNull(::decode).distinctBy { it.fileId }
        }

        /** Unit separator, between the fields of one request. */
        private const val SEP = ''

        /** Record separator, between requests. */
        private const val ROW = ''

        private const val FIELDS = 9
    }
}
