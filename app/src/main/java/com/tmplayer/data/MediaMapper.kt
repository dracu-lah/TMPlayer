package com.tmplayer.data

import dev.g000sha256.tdl.dto.Message
import dev.g000sha256.tdl.dto.MessageAnimation
import dev.g000sha256.tdl.dto.MessageDocument
import dev.g000sha256.tdl.dto.MessageVideo
import java.text.SimpleDateFormat
import java.io.File
import java.util.Date
import java.util.Locale

/**
 * One playable thing in a chat, flattened out of whatever message shape Telegram used.
 *
 * Equality is over every field, not the id alone, or a list whose badges have changed compares
 * equal to the one before it and the change never reaches the screen. It also lets Compose skip
 * the rows that genuinely did not move. The blurred preview is compared by content because TDLib
 * hands back a fresh array on each read.
 */
@androidx.compose.runtime.Immutable
data class MediaItem(
    val chatId: Long,
    val messageId: Long,
    val fileId: Int,
    val title: String,
    val sizeBytes: Long,
    val durationSec: Int,
    val mimeType: String,
    val thumbnailFileId: Int,
    val miniThumbnail: ByteArray?,
    val date: Int,
    val fileName: String = "",
    val onDevice: Boolean = false,
) {
    /**
     * "4K", "1080p": read off the file name, which is where releases state it.
     *
     * Computed once and kept rather than on every read: as a `get()` the regex would run on every
     * recomposition of a tile, several hundred times a second on a scrolling grid, for an answer
     * that cannot change.
     */
    val qualityTags: List<String> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        MediaMapper.qualityTags(fileName)
    }

    val id: String get() = "$chatId:$messageId"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MediaItem) return false
        return chatId == other.chatId &&
            messageId == other.messageId &&
            fileId == other.fileId &&
            title == other.title &&
            sizeBytes == other.sizeBytes &&
            durationSec == other.durationSec &&
            mimeType == other.mimeType &&
            thumbnailFileId == other.thumbnailFileId &&
            date == other.date &&
            fileName == other.fileName &&
            onDevice == other.onDevice &&
            miniThumbnail.contentEquals(other.miniThumbnail)
    }

    override fun hashCode(): Int {
        var result = chatId.hashCode()
        result = 31 * result + messageId.hashCode()
        result = 31 * result + fileId
        result = 31 * result + title.hashCode()
        result = 31 * result + sizeBytes.hashCode()
        result = 31 * result + durationSec
        result = 31 * result + mimeType.hashCode()
        result = 31 * result + thumbnailFileId
        result = 31 * result + date
        result = 31 * result + fileName.hashCode()
        result = 31 * result + onDevice.hashCode()
        result = 31 * result + (miniThumbnail?.contentHashCode() ?: 0)
        return result
    }
}

/**
 * Turns Telegram messages into playable entries, dropping everything that is not a video.
 *
 * Videos are posted both as videos and, very often because it preserves quality and
 * multi-track audio, as plain documents, so both shapes are accepted and documents are
 * screened by MIME type and file extension.
 */
object MediaMapper {

    /** Container formats Media3 can demux, plus the ones NextLib's decoders cover. */
    private val VIDEO_EXTENSIONS = setOf(
        "mkv", "mp4", "m4v", "avi", "mov", "webm", "ts", "m2ts", "mts",
        "flv", "wmv", "mpg", "mpeg", "3gp", "ogv", "divx", "vob", "asf", "rmvb",
    )

    fun fromMessage(message: Message): MediaItem? = when (val content = message.content) {
        is MessageVideo -> {
            val video = content.video
            MediaItem(
                chatId = message.chatId,
                messageId = message.id,
                fileId = video.video.id,
                title = displayTitle(video.fileName, content.caption?.text, "Video"),
                fileName = video.fileName,
                sizeBytes = fileSize(video.video.size, video.video.expectedSize),
                durationSec = video.duration,
                mimeType = video.mimeType.orEmpty(),
                thumbnailFileId = video.thumbnail?.file?.id ?: 0,
                miniThumbnail = video.minithumbnail?.data,
                date = message.date,
                onDevice = onDevice(video.video),
            )
        }

        is MessageDocument -> {
            val document = content.document
            val name = document.fileName.orEmpty()
            if (!looksLikeVideo(name, document.mimeType.orEmpty())) null
            else MediaItem(
                chatId = message.chatId,
                messageId = message.id,
                fileId = document.document.id,
                title = displayTitle(name, content.caption?.text, "File"),
                fileName = name,
                sizeBytes = fileSize(document.document.size, document.document.expectedSize),
                durationSec = 0,
                mimeType = document.mimeType.orEmpty(),
                thumbnailFileId = document.thumbnail?.file?.id ?: 0,
                miniThumbnail = document.minithumbnail?.data,
                date = message.date,
                onDevice = onDevice(document.document),
            )
        }

        // Silent MP4s, the way most short clips and trailers arrive.
        is MessageAnimation -> {
            val animation = content.animation
            MediaItem(
                chatId = message.chatId,
                messageId = message.id,
                fileId = animation.animation.id,
                title = displayTitle(animation.fileName, content.caption?.text, "Clip"),
                fileName = animation.fileName,
                sizeBytes = fileSize(animation.animation.size, animation.animation.expectedSize),
                durationSec = animation.duration,
                mimeType = animation.mimeType.orEmpty(),
                thumbnailFileId = animation.thumbnail?.file?.id ?: 0,
                miniThumbnail = animation.minithumbnail?.data,
                date = message.date,
                onDevice = onDevice(animation.animation),
            )
        }

        else -> null
    }

    fun looksLikeVideo(fileName: String, mimeType: String): Boolean {
        if (mimeType.startsWith("video/", ignoreCase = true)) return true
        // Plenty of uploads arrive as application/octet-stream; fall back to the extension.
        val extension = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return extension in VIDEO_EXTENSIONS
    }

    /**
     * The one marker worth a badge: how sharp the file is.
     *
     * Descriptive file names put this in the name and nowhere else; the container would have to be
     * opened to learn it otherwise, which is not worth a download per tile. Deliberately only
     * resolution: codec and dynamic range tell somebody choosing what to watch nothing they can
     * act on, and a tile wearing three badges is harder to read than one wearing the badge that
     * decides anything.
     */
    fun qualityTags(fileName: String?): List<String> {
        val name = fileName?.lowercase() ?: return emptyList()
        return when {
            RESOLUTION_4K.containsMatchIn(name) -> listOf("4K")
            name.contains("1080p") || name.contains("1080i") -> listOf("1080p")
            name.contains("720p") -> listOf("720p")
            name.contains("480p") || name.contains("360p") -> listOf("SD")
            else -> emptyList()
        }
    }

    private val RESOLUTION_4K = Regex("""\b(2160p|4k|uhd)\b""")

    /** A file name beats a caption, and a caption beats nothing. */
    fun displayTitle(fileName: String?, caption: String?, fallback: String): String {
        val name = fileName?.trim().orEmpty()
        if (name.isNotEmpty()) return name
        val firstCaptionLine = caption?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        if (firstCaptionLine.isNotEmpty()) return firstCaptionLine.take(120)
        return fallback
    }

    /** TDLib reports 0 for a file it has never touched, but usually knows the expected size. */
    private fun fileSize(size: Long, expectedSize: Long) = if (size > 0) size else expectedSize

    private fun onDevice(file: dev.g000sha256.tdl.dto.File): Boolean {
        val diskFile = file.local.path.takeIf { it.isNotBlank() }?.let(::File)
        return LocalFilePolicy.evaluate(
            downloadCompleted = file.local.isDownloadingCompleted,
            pathPresent = diskFile != null,
            regularFile = diskFile?.isFile == true,
            length = diskFile?.length() ?: 0,
            size = file.size,
            expectedSize = file.expectedSize,
        ) == LocalFileAvailability.Complete
    }

    fun formatSize(bytes: Long): String = when {
        bytes <= 0 -> ""
        bytes >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f GB", bytes / 1024.0 / 1024 / 1024)
        bytes >= 1024L * 1024 -> String.format(Locale.US, "%.0f MB", bytes / 1024.0 / 1024)
        else -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    }

    fun formatDuration(seconds: Int): String {
        if (seconds <= 0) return ""
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        return if (hours > 0) String.format(Locale.US, "%dh %02dm", hours, minutes)
        else String.format(Locale.US, "%dm", minutes.coerceAtLeast(1))
    }
}
