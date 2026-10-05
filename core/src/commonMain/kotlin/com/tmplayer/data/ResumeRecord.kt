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
    /**
     * Where the file is, for a download that has been moved into TMPlayer's own Downloads folder.
     *
     * Null for everything else: a resume or cached record, and a download recorded before
     * downloads had a folder of their own, whose file is still wherever TDLib's cache put it.
     */
    val localPath: String? = null,
    /** The tracks and speed it was playing with, on a line written since resume kept them. */
    val state: ResumeState? = null,
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

        /** The same line with a path on the end, written for a download that has a file of its own. */
        private const val FIELDS_WITH_PATH = 7

        /** And a [ResumeState] after the path, which is left empty when there is none. */
        private const val FIELDS_WITH_STATE = 8

        fun encode(
            fileId: Int,
            title: String,
            chatTitle: String,
            sizeBytes: Long,
            durationSec: Int,
            updatedAt: Long,
            localPath: String? = null,
            state: ResumeState? = null,
        ): String = buildList {
            add(fileId.toString())
            add(title.replace(SEP, ' '))
            add(chatTitle.replace(SEP, ' '))
            add(sizeBytes.toString())
            add(durationSec.toString())
            add(updatedAt.toString())
            // Appended rather than slotted in, so a line without either is exactly what older
            // builds wrote and read, and a line with them is still read field for field up to here.
            val path = localPath?.takeIf { it.isNotBlank() }?.replace(SEP, ' ')
            if (path != null || state != null) add(path.orEmpty())
            if (state != null) add(state.encode())
        }.joinToString(SEP.toString())

        /**
         * Just the timestamp out of an encoded line, for the history cap.
         *
         * Reading the whole record would be a full decode per entry on every write of a resume
         * position, and the cap only ever needs to know which entries are the oldest.
         */
        fun updatedAtOf(encoded: String?): Long? =
            encoded?.split(SEP)?.getOrNull(5)?.toLongOrNull()

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
            if (parts.size != FIELDS && parts.size != FIELDS_WITH_PATH && parts.size != FIELDS_WITH_STATE) return null
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
                localPath = parts.getOrNull(6)?.takeIf { it.isNotBlank() },
                state = parts.getOrNull(7)?.let(ResumeState::decode),
            )
        }
    }
}

/**
 * How a video was being played when its position was written, so a resume comes back the same way:
 * the soundtrack, the subtitles and the speed, as Kodi keeps them with its bookmark.
 *
 * Tracks are counted by their place among the file's tracks of that kind, from 0. The series
 * memory ([TrackChoice]) goes by language, which is right for the next episode but cannot tell
 * two English tracks apart, the film and its commentary; for the same file the place can. The
 * language is kept beside it, so a file whose track list came out differently (a subtitle file
 * added since, a player that lists them in another order) is not handed the wrong one.
 *
 * The per file offsets are not here: [com.tmplayer.player.SyncDelays] has a key of its own per
 * message, written on every step, and pruned with this history.
 */
data class ResumeState(
    /** The soundtrack's place among the audio tracks, or null when it was not known. */
    val audioTrack: Int? = null,
    val audioLanguage: String? = null,
    /** The subtitle track's place, [SUBTITLES_OFF] for none, or null when it was not known. */
    val subtitleTrack: Int? = null,
    val subtitleLanguage: String? = null,
    /** The speed, or null when it was not known. */
    val speed: Float? = null,
) {
    val subtitlesOff: Boolean get() = subtitleTrack == SUBTITLES_OFF

    /** Five fields with a comma between; languages are short codes and never carry one. */
    fun encode(): String = listOf(
        audioTrack?.toString().orEmpty(),
        audioLanguage.orEmpty().replace(',', ' ').trim(),
        subtitleTrack?.toString().orEmpty(),
        subtitleLanguage.orEmpty().replace(',', ' ').trim(),
        speed?.toString().orEmpty(),
    ).joinToString(",")

    companion object {
        const val SUBTITLES_OFF = -1

        /**
         * Whether the track found at the stored place is still the one that was playing: the
         * languages match, or one side has none to compare.
         */
        fun sameTrack(storedLanguage: String?, foundLanguage: String?): Boolean =
            storedLanguage.isNullOrBlank() || foundLanguage.isNullOrBlank() ||
                storedLanguage.equals(foundLanguage, ignoreCase = true)

        /** Null for a field that cannot be read, rather than a state that plays the wrong track. */
        fun decode(text: String): ResumeState? {
            val parts = text.split(',')
            if (parts.size != 5) return null
            val state = ResumeState(
                audioTrack = parts[0].toIntOrNull()?.takeIf { it >= 0 },
                audioLanguage = parts[1].ifBlank { null },
                subtitleTrack = parts[2].toIntOrNull()?.takeIf { it >= SUBTITLES_OFF },
                subtitleLanguage = parts[3].ifBlank { null },
                speed = parts[4].toFloatOrNull()?.takeIf { !it.isNaN() && it > 0f },
            )
            return state.takeIf { it.audioTrack != null || it.subtitleTrack != null || it.speed != null }
        }
    }
}

/**
 * When a position is worth keeping, and when a video counts as watched: Kodi's rules, the same on
 * every platform.
 *
 * - Nothing is kept in the first [MIN_POSITION_MS]: that is somebody sampling a video, and
 *   "Continue watching" filling up with short visits is noise.
 * - Nothing is kept in the last [END_FRACTION] either: that is the credits, and resuming into
 *   them is resuming into nothing.
 * - From [WATCHED_FRACTION] the video is watched: it goes on the Watched list and its position is
 *   forgotten, whether or not the credits were sat through.
 *
 * Without a length (a stream that has not said yet) only the first rule can be judged.
 */
object ResumeRules {
    const val MIN_POSITION_MS = 180_000L
    const val END_FRACTION = 0.08
    const val WATCHED_FRACTION = 0.90

    /** Whether [positionMs] is a resume point worth writing down. */
    fun keeps(positionMs: Long, durationMs: Long): Boolean {
        if (positionMs < MIN_POSITION_MS) return false
        if (durationMs <= 0) return true
        return positionMs < durationMs * (1 - END_FRACTION)
    }

    /** Whether playback at [positionMs] has seen enough of the video to call it watched. */
    fun watched(positionMs: Long, durationMs: Long): Boolean =
        durationMs > 0 && positionMs >= durationMs * WATCHED_FRACTION
}
