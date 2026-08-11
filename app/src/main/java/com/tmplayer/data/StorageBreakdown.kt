package com.tmplayer.data

/**
 * What TMPlayer's share of the disk is actually made of.
 *
 * The Settings card carried one figure, "TMPlayer has 3.1 GB of video saved", and that figure was
 * `filesSize` from TDLib's fast statistics: every file it holds, pictures and thumbnails included.
 * So the sentence was wrong in the one case where the number matters. A viewer wondering why the
 * stick is full deserves to know whether it is one remux or a year of chat avatars, not least
 * because the Delete button only takes the first of those.
 *
 * Three buckets, because three is what a viewer can act on: the video the player caches, the
 * pictures the browse grid draws, and everything else TDLib keeps. Kept pure, over plain type
 * names rather than TDLib DTOs, so the arithmetic is testable without a device.
 */
data class StorageBreakdown(
    val videoBytes: Long = 0,
    val pictureBytes: Long = 0,
    val otherBytes: Long = 0,
) {
    val totalBytes: Long get() = videoBytes + pictureBytes + otherBytes

    companion object {
        /** Empty rather than null, so a screen that could not read the statistics still draws. */
        val EMPTY = StorageBreakdown()

        /**
         * @param rows one entry per file type TDLib reported, as (type name, bytes). The name is
         *   the DTO's simple class name, `FileTypeVideo` and its neighbours.
         */
        fun of(rows: List<Pair<String, Long>>): StorageBreakdown {
            var video = 0L
            var picture = 0L
            var other = 0L
            for ((type, size) in rows) {
                if (size <= 0) continue
                when {
                    isVideo(type) -> video += size
                    isPicture(type) -> picture += size
                    else -> other += size
                }
            }
            return StorageBreakdown(video, picture, other)
        }

        /**
         * The three types the player itself downloads. A video posted as a file arrives as
         * `FileTypeDocument`, which is most of this app's library, so leaving it out would put
         * the largest thing on the disk under "other".
         */
        private fun isVideo(type: String): Boolean = type in VIDEO_TYPES

        /** Chat photos and the grid's thumbnails: small each, endless together. */
        private fun isPicture(type: String): Boolean = type in PICTURE_TYPES

        private val VIDEO_TYPES = setOf(
            "FileTypeVideo",
            "FileTypeDocument",
            "FileTypeAnimation",
        )

        private val PICTURE_TYPES = setOf(
            "FileTypePhoto",
            "FileTypeThumbnail",
            "FileTypeProfilePhoto",
        )
    }
}
