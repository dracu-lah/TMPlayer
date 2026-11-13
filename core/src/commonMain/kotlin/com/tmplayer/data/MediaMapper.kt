package com.tmplayer.data

import com.tmplayer.i18n.L
import dev.g000sha256.tdl.dto.Message
import dev.g000sha256.tdl.dto.MessageAnimation
import dev.g000sha256.tdl.dto.MessageDocument
import dev.g000sha256.tdl.dto.MessageVideo
import java.text.SimpleDateFormat
import java.io.File
import java.util.Date
import java.util.Locale

/**
 * Turns Telegram messages into playable entries, dropping everything that is not a video.
 *
 * Videos are posted both as videos and, very often because it preserves quality and
 * multi-track audio, as plain documents, so both shapes are accepted and documents are
 * screened by MIME type and file extension.
 */
object MediaMapper {

    /** Container formats Media3 can demux, plus the ones NextLib's decoders cover. See [MediaText]. */
    internal val VIDEO_EXTENSIONS: Set<String> get() = MediaText.VIDEO_EXTENSIONS

    /**
     * The video in [message], or null when there is none or it is self-destructing.
     *
     * [chatProtected] is the chat's "restrict saving content", for a caller that has the chat to
     * hand; the message's own flag usually says the same, see [ContentProtection.verdict].
     */
    fun fromMessage(message: Message, chatProtected: Boolean = false): MediaItem? =
        if (selfDestructs(message)) null else mapped(message, chatProtected)

    /** A page of search results, with the self-destructing videos left out and counted. */
    fun screen(messages: List<Message>, chatProtected: Boolean): ContentProtection.Screened =
        ContentProtection.screen(messages, ::selfDestructs) { mapped(it, chatProtected) }

    /** Media Telegram means to be seen once, in Telegram: a timer, or "view once". */
    fun selfDestructs(message: Message): Boolean = message.selfDestructType != null

    /**
     * Whether [content] is something [fromMessage] would list: a video, a silent clip, or a file
     * that looks like a video. For an edit, where TDLib sends the new content without its message.
     */
    fun isVideoContent(content: dev.g000sha256.tdl.dto.MessageContent): Boolean = when (content) {
        is MessageVideo, is MessageAnimation -> true
        is dev.g000sha256.tdl.dto.MessagePaidMedia -> content.media.any { it is dev.g000sha256.tdl.dto.PaidMediaVideo }
        is MessageDocument -> content.document.fileName.orEmpty().let { name ->
            looksLikeVideo(name, content.document.mimeType.orEmpty()) ||
                MediaText.looksLikeVideoArchive(name, content.document.document.size)
        }
        else -> false
    }

    private fun fromVideo(
        message: Message,
        video: dev.g000sha256.tdl.dto.Video,
        caption: String?,
        post: MessageVideo? = null,
    ): MediaItem {
        val size = fileSize(video.video.size, video.video.expectedSize)
        // The uploader's own cover, where they chose one, is the card's picture in place of the
        // frame Telegram took (CP80).
        val cover = post?.cover?.let(::coverSize)
        val base = fromVideoFile(message, video, caption, size)
        if (post == null) return base
        return base.copy(
            thumbnailFileId = cover?.photo?.id ?: base.thumbnailFileId,
            miniThumbnail = post.cover?.minithumbnail?.data ?: base.miniThumbnail,
            qualities = TelegramQualities.fromTdlib(post.alternativeVideos.orEmpty().toList(), video.height, size),
            startSec = post.startTimestamp.coerceAtLeast(0),
            storyboard = storyboardOf(post.storyboards.orEmpty().toList()),
        )
    }

    /** The size of a cover photo to draw on a card: the largest no wider than a television's card. */
    internal fun coverSize(photo: dev.g000sha256.tdl.dto.Photo): dev.g000sha256.tdl.dto.PhotoSize? {
        val sizes = photo.sizes.orEmpty().filter { it.photo.id != 0 }
        return sizes.filter { it.width <= COVER_MAX_WIDTH }.maxByOrNull { it.width } ?: sizes.minByOrNull { it.width }
    }

    private const val COVER_MAX_WIDTH = 800

    /** The storyboard whose tiles come closest to a scrub preview's width without passing it. */
    private fun storyboardOf(boards: List<dev.g000sha256.tdl.dto.VideoStoryboard>): StoryboardFiles? {
        val usable = boards.filter { it.storyboardFile.id != 0 && it.mapFile.id != 0 && it.width > 0 && it.height > 0 }
        val board = usable.filter { it.width <= StoryboardMaps.PREFERRED_TILE_WIDTH }.maxByOrNull { it.width }
            ?: usable.minByOrNull { it.width }
            ?: return null
        return StoryboardFiles(board.storyboardFile.id, board.mapFile.id, board.width, board.height)
    }

    private fun fromVideoFile(message: Message, video: dev.g000sha256.tdl.dto.Video, caption: String?, size: Long) = MediaItem(
        chatId = message.chatId,
        messageId = message.id,
        fileId = video.video.id,
        title = displayTitle(video.fileName, caption, L.mediaUntitledVideo),
        fileName = video.fileName,
        sizeBytes = size,
        durationSec = video.duration,
        mimeType = video.mimeType.orEmpty(),
        thumbnailFileId = video.thumbnail?.file?.id ?: 0,
        miniThumbnail = video.minithumbnail?.data,
        date = message.date,
        onDevice = onDevice(video.video),
        caption = captionOf(caption),
        width = video.width,
        height = video.height,
    )

    /** The video in [message] with its saving rule applied, whatever its timer says. */
    private fun mapped(message: Message, chatProtected: Boolean): MediaItem? {
        // Not a post yet: a scheduled one has a stand-in id until it goes out, and one still
        // sending may yet fail. Either would be a tile pointing at a message that does not exist.
        if (message.schedulingState != null || message.sendingState != null) return null
        // Withheld by Telegram on this platform or in this country. Listed, it opens to an error.
        if (!message.restrictionInfo?.restrictionReason.isNullOrEmpty()) return null
        val topic = (message.topicId as? dev.g000sha256.tdl.dto.MessageTopicForum)?.forumTopicId ?: 0
        val item = video(message)
            ?.let { if (topic != 0) it.copy(topicId = topic) else it }
            ?.let { if (message.mediaAlbumId != 0L) it.copy(albumId = message.mediaAlbumId) else it }
            ?: return null
        return when (ContentProtection.verdict(message.canBeSaved, chatProtected, selfDestructs = false)) {
            ContentProtection.Verdict.Full -> item
            else -> item.copy(canBeSaved = false)
        }
    }

    private fun video(message: Message): MediaItem? = when (val content = message.content) {
        is MessageVideo -> fromVideo(message, content.video, content.caption?.text, content)
        // A post sold for Stars, or one the viewer has bought: the video is there once unlocked.
        // Still locked it is a preview with no file, and nothing to play. A post of several takes
        // the first: a tile is one message, and the rest are a tap away in Telegram.
        is dev.g000sha256.tdl.dto.MessagePaidMedia -> content.media
            .firstNotNullOfOrNull { it as? dev.g000sha256.tdl.dto.PaidMediaVideo }
            ?.let { fromVideo(message, it.video, content.caption?.text) }

        is MessageDocument -> {
            val document = content.document
            val name = document.fileName.orEmpty()
            if (!looksLikeVideo(name, document.mimeType.orEmpty()) &&
                !MediaText.looksLikeVideoArchive(name, fileSize(document.document.size, document.document.expectedSize))
            ) null
            else MediaItem(
                chatId = message.chatId,
                messageId = message.id,
                fileId = document.document.id,
                title = displayTitle(name, content.caption?.text, L.mediaUntitledFile),
                fileName = name,
                sizeBytes = fileSize(document.document.size, document.document.expectedSize),
                durationSec = 0,
                mimeType = document.mimeType.orEmpty(),
                thumbnailFileId = document.thumbnail?.file?.id ?: 0,
                miniThumbnail = document.minithumbnail?.data,
                date = message.date,
                onDevice = onDevice(document.document),
                caption = captionOf(content.caption?.text),
            )
        }

        // Silent MP4s, the way most short clips and trailers arrive.
        is MessageAnimation -> {
            val animation = content.animation
            MediaItem(
                chatId = message.chatId,
                messageId = message.id,
                fileId = animation.animation.id,
                title = displayTitle(animation.fileName, content.caption?.text, L.mediaUntitledClip),
                fileName = animation.fileName,
                sizeBytes = fileSize(animation.animation.size, animation.animation.expectedSize),
                durationSec = animation.duration,
                mimeType = animation.mimeType.orEmpty(),
                thumbnailFileId = animation.thumbnail?.file?.id ?: 0,
                miniThumbnail = animation.minithumbnail?.data,
                date = message.date,
                onDevice = onDevice(animation.animation),
                caption = captionOf(content.caption?.text),
                width = animation.width,
                height = animation.height,
            )
        }

        // A round video message (CP81): square, named for what it is.
        is dev.g000sha256.tdl.dto.MessageVideoNote -> roundVideo(message, content.videoNote, null)

        // A link whose preview Telegram filled with a video file of its own (CP81). A preview of
        // somebody else's site (an embedded player) has no file here, and stays a link.
        is dev.g000sha256.tdl.dto.MessageText -> linkPreviewVideo(message, content)

        else -> null
    }

    private fun roundVideo(message: Message, note: dev.g000sha256.tdl.dto.VideoNote, title: String?): MediaItem? {
        if (note.video.id == 0) return null
        return MediaItem(
            chatId = message.chatId,
            messageId = message.id,
            fileId = note.video.id,
            title = title?.takeIf { it.isNotBlank() } ?: L.mediaRoundVideo,
            sizeBytes = fileSize(note.video.size, note.video.expectedSize),
            durationSec = note.duration,
            mimeType = "video/mp4",
            thumbnailFileId = note.thumbnail?.file?.id ?: 0,
            miniThumbnail = note.minithumbnail?.data,
            date = message.date,
            onDevice = onDevice(note.video),
            width = note.length,
            height = note.length,
        )
    }

    /** The video inside [content]'s link preview, where Telegram carries the file. */
    private fun linkPreviewVideo(message: Message, content: dev.g000sha256.tdl.dto.MessageText): MediaItem? {
        val preview = content.linkPreview ?: return null
        return when (val type = preview.type) {
            is dev.g000sha256.tdl.dto.LinkPreviewTypeVideo -> {
                val video = type.video
                if (video.video.id == 0 || fileSize(video.video.size, video.video.expectedSize) <= 0L) return null
                val named = preview.title.ifBlank { content.text.text }
                fromVideo(message, video, named)
            }
            is dev.g000sha256.tdl.dto.LinkPreviewTypeVideoNote -> roundVideo(message, type.videoNote, preview.title)
            else -> null
        }
    }

    /** Whether [item] is a round video message, which lists draw in a circle. */
    fun isRound(item: MediaItem): Boolean = MediaText.isRound(item)

    /** See [MediaText.shareAlbumCaptions]. */
    fun shareAlbumCaptions(items: List<MediaItem>): List<MediaItem> = MediaText.shareAlbumCaptions(items)

    fun looksLikeVideo(fileName: String, mimeType: String): Boolean = MediaText.looksLikeVideo(fileName, mimeType)

    /** See [MediaText.qualityTags]. */
    fun qualityTags(fileName: String?): List<String> = MediaText.qualityTags(fileName)

    /** See [MediaText.displayTitle]. */
    fun displayTitle(fileName: String?, caption: String?, fallback: String): String =
        MediaText.displayTitle(fileName, caption, fallback)

    /** See [MediaText.isGenericName]. */
    fun isGenericName(fileName: String): Boolean = MediaText.isGenericName(fileName)

    /** See [MediaText.captionLine]. */
    fun captionLine(item: MediaItem, repeated: Set<String> = emptySet()): String? = MediaText.captionLine(item, repeated)

    /** See [MediaText.repeatedCaptionLines]. */
    fun repeatedCaptionLines(items: List<MediaItem>): Set<String> = MediaText.repeatedCaptionLines(items)

    internal fun captionOf(text: String?): String = MediaText.captionOf(text)

    /** TDLib reports 0 for a file it has never touched, but usually knows the expected size. */
    internal fun fileSize(size: Long, expectedSize: Long) = MediaText.fileSize(size, expectedSize)

    internal fun onDevice(file: dev.g000sha256.tdl.dto.File): Boolean {
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

    fun formatSize(bytes: Long): String = MediaText.formatSize(bytes)

    fun formatDuration(seconds: Int): String = MediaText.formatDuration(seconds)
}
