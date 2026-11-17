package android.media

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri

/**
 * Bytes a retriever reads from wherever the caller keeps them, Android's: the player hands it the
 * part of a video TDLib has written so far.
 */
abstract class MediaDataSource : AutoCloseable {
    abstract fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int

    abstract fun getSize(): Long
}

/**
 * Android's frame and metadata reader. Its one reader upstream is `TrickplayFrames`, the scrub
 * pictures, which upstream hides on every device (`Trickplay.PICTURES_SHOWN` false), so nothing
 * on iOS reaches it. Opening a source fails the way Android's does on a file it cannot read
 * (IllegalArgumentException). Should upstream show the pictures again, the reader belongs here:
 * FFmpeg's, in MPVKit already, for any container (docs/IOS.md, layer 3 progress).
 */
open class MediaMetadataRetriever : AutoCloseable {
    fun setDataSource(path: String) {
        throw IllegalArgumentException("No frame reader on iOS yet: $path")
    }

    fun setDataSource(context: Context, uri: Uri) {
        throw IllegalArgumentException("No frame reader on iOS yet: $uri")
    }

    fun setDataSource(source: MediaDataSource) {
        throw IllegalArgumentException("No frame reader on iOS yet")
    }

    fun getFrameAtTime(timeUs: Long, option: Int): Bitmap? = null

    fun getFrameAtTime(timeUs: Long): Bitmap? = null

    fun getFrameAtTime(): Bitmap? = null

    fun getScaledFrameAtTime(timeUs: Long, option: Int, dstWidth: Int, dstHeight: Int): Bitmap? = null

    fun extractMetadata(keyCode: Int): String? = null

    fun release() {}

    override fun close() = release()

    companion object {
        const val OPTION_PREVIOUS_SYNC = 0
        const val OPTION_NEXT_SYNC = 1
        const val OPTION_CLOSEST_SYNC = 2
        const val OPTION_CLOSEST = 3

        const val METADATA_KEY_DURATION = 9
        const val METADATA_KEY_HAS_VIDEO = 17
        const val METADATA_KEY_VIDEO_WIDTH = 18
        const val METADATA_KEY_VIDEO_HEIGHT = 19
        const val METADATA_KEY_VIDEO_ROTATION = 24
    }
}
