package com.tmplayer.data

import kotlin.concurrent.Volatile

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
    /**
     * Whether a whole copy of the video is on this device, whichever kind: true for both
     * [Locality.Cached] and [Locality.Downloaded]. Kept beside [locality] for the callers that only
     * ask "can this play without a connection".
     */
    val onDevice: Boolean = false,
    /**
     * Which kind of copy that is, which is what the tile badge and the menu name. Follows [onDevice]
     * unless told otherwise: a mapper that only knows TDLib's answer can say no more than cached.
     */
    val locality: Locality = if (onDevice) Locality.Cached else Locality.Remote,
    /**
     * False when the chat or the message has "restrict saving content" on. The video still streams,
     * but every way of keeping a copy is withheld: see [ContentProtection].
     */
    val canBeSaved: Boolean = true,
    /**
     * The post's caption, trimmed, or empty. A second source for the episode when the file name
     * does not carry one: see [MediaName.parse].
     */
    val caption: String = "",
    /**
     * The picture's size in pixels as Telegram states it, or 0 where it does not: a video sent as
     * a file carries no dimensions, and the detail panel then reads the resolution off its name.
     */
    val width: Int = 0,
    val height: Int = 0,
    /**
     * Every copy of this one film in the chat, this one among them, newest first, when the chat
     * posted it more than once (a 720p and a 1080p): see [SeriesShelf.arrange]. Empty for a file
     * that is the only copy, and for an episode, whose copies live on its [SeriesEpisode].
     */
    val versions: List<MediaItem> = emptyList(),
    /**
     * The forum topic the post is in, for a group that has topics turned on, or 0. Lets a topic's
     * listing tell its own live posts from the rest of the group's, and the player keep the next
     * episode inside the topic the viewer was browsing.
     */
    val topicId: Int = 0,
    /**
     * Every piece of a film cut into several files ("CD1", "CD2"), this one first, in order, when
     * this is the first piece: see [SeriesShelf.arrange]. Played one after another as one video.
     * Empty for a whole file. Like [versions], arranging's answer and not part of equality.
     */
    val parts: List<MediaItem> = emptyList(),
    /**
     * Set when this file is a trailer, a teaser or a sample posted beside a film rather than the
     * film: never folded into it, listed after it, and labelled. See [SeriesShelf.arrange].
     */
    val extra: ExtraKind? = null,
    /**
     * The Telegram album the post was sent in (several videos sent together), or 0 for a post on
     * its own. An album's videos are different videos, never copies of each other ([SameVideo]),
     * and one without a caption of its own takes the album's ([MediaMapper.shareAlbumCaptions]).
     */
    val albumId: Long = 0,
    /**
     * Every piece of a video cut into raw byte pieces ("Name.part001" to "Name.part007", or
     * "Name.mkv.001"), this one first, in order, when this is the video they make: see
     * [SplitPieces.join]. Unlike [parts], no piece plays alone; they play as one stream of their
     * bytes joined, and this item (the first piece's message) is the video's identity for its
     * place, its Watched mark and the cache. Its [sizeBytes] is then the pieces' added up. Empty
     * for any other file. Like [versions], arranging's answer and not part of equality beyond its size.
     */
    val pieces: List<MediaItem> = emptyList(),
    /**
     * Telegram's own lighter copies of this upload, made on its servers for a big video
     * (`MessageVideo.alternativeVideos`), best first, each lighter than the upload. Empty for a file
     * sent as a document and for a video Telegram made none of. See [TelegramQualities].
     */
    val qualities: List<TelegramQuality> = emptyList(),
    /**
     * The height of the Telegram copy this item plays in place of the upload ([TelegramQualities.withQuality]),
     * or 0 for the upload itself. The message, its place and its Watched mark stay the same.
     */
    val playingQuality: Int = 0,
    /** The upload's file id and size, kept while [playingQuality] stands in for it; 0 otherwise. */
    val uploadFileId: Int = 0,
    val uploadSizeBytes: Long = 0,
    /** Where the poster asked the video to start, in seconds (`MessageVideo.startTimestamp`), or 0. */
    val startSec: Int = 0,
    /** Telegram's seek thumbnails for the video, a sprite and its map, or null. See [StoryboardMaps]. */
    val storyboard: StoryboardFiles? = null,
) {
    /**
     * Which byte piece of a cut video this file is, read off its name ([SplitPieces.pieceOf]), or
     * null for a file that is not one.
     */
    val splitPiece: SplitPiece?
        get() = read(splitPieceRead) { SplitPieces.pieceOf(fileName).also { splitPieceRead = it ?: NotFound } }

    // What is read off the names, once per item and kept on it. Plain fields rather than `by lazy`:
    // a lazy is two more objects on every item made, and Telegram's answers, the Home cache and
    // every copy() make thousands of items, most of which are never asked for half of these. Two
    // threads may both work one out the first time; the answer is the same, so either may stay.
    // Volatile, so a list such as [qualityTags] is seen whole on another thread.
    @Volatile private var splitPieceRead: Any? = null
    @Volatile private var qualityTagsRead: List<String>? = null
    @Volatile private var nameForParsingRead: String? = null
    @Volatile private var parsedNameRead: ParsedName? = null
    @Volatile private var metaQueryRead: Any? = null
    @Volatile private var fileTileTitleRead: Any? = null
    @Volatile private var parsedNameAloneRead: ParsedName? = null
    @Volatile private var searchTextRead: String? = null
    @Volatile private var genericNameRead: Int = 0
    @Volatile private var filmKeyRead: Any? = null
    @Volatile private var copyKeyRead: Any? = null
    @Volatile private var alternateKeysRead: List<String>? = null
    @Volatile private var looseHintRead: Int = 0
    @Volatile private var progressKeyRead: String? = null
    @Volatile private var markersRead: Set<String>? = null
    @Volatile private var captionLinesRead: Set<String>? = null

    /**
     * This item, made by [from]'s copy(), with what was read off [from]'s names kept where the
     * copy has the same names: a folded film's lead with its versions, a file labelled as an
     * extra. Without it each such copy was read from scratch on every build of Home.
     */
    internal fun keptReads(from: MediaItem): MediaItem {
        if (this === from || fileName != from.fileName || title != from.title || caption != from.caption) return this
        splitPieceRead = from.splitPieceRead
        qualityTagsRead = from.qualityTagsRead
        nameForParsingRead = from.nameForParsingRead
        parsedNameRead = from.parsedNameRead
        metaQueryRead = from.metaQueryRead
        fileTileTitleRead = from.fileTileTitleRead
        parsedNameAloneRead = from.parsedNameAloneRead
        searchTextRead = from.searchTextRead
        genericNameRead = from.genericNameRead
        alternateKeysRead = from.alternateKeysRead
        looseHintRead = from.looseHintRead
        markersRead = from.markersRead
        captionLinesRead = from.captionLinesRead
        if (chatId == from.chatId && messageId == from.messageId) progressKeyRead = from.progressKeyRead
        // The keys also read whether it is an extra, and whether it stands for a cut video's pieces.
        if (extra == from.extra && pieces.isEmpty() == from.pieces.isEmpty()) {
            filmKeyRead = from.filmKeyRead
            copyKeyRead = from.copyKeyRead
        }
        return this
    }

    companion object {
        /**
         * [fresh], a new answer for a list already [held] (looked up by [id]), with each video the
         * list had kept as the instance already held, or, where only something past its names
         * changed (a download, a view count), given what was read off the held one's names.
         * Without it one new post in a chat had every name of the chat read again, about 0.1 ms a
         * name on a desktop and many times that on a TV (HX73).
         */
        fun carryReads(fresh: List<MediaItem>, held: (String) -> MediaItem?): List<MediaItem> = fresh.map { item ->
            val before = held(item.id)
            when {
                before == null -> item
                before == item -> before
                else -> item.keptReads(before)
            }
        }
    }

    /** [value] read before, else [compute]'s answer, which stores itself; [NotFound] stands for null. */
    @Suppress("UNCHECKED_CAST")
    private inline fun <T> read(value: Any?, compute: () -> T): T = when {
        value === NotFound -> null as T
        value != null -> value as T
        else -> compute()
    }

    /** Whether this item stands for a video cut into byte pieces, played joined. */
    val isJoined: Boolean get() = pieces.isNotEmpty()

    /**
     * The file whose Watched mark stands for this tile: a split film's last piece, since the film
     * is watched only once that is, and the file itself otherwise.
     */
    val watchedBy: MediaItem get() = parts.lastOrNull()?.takeIf { parts.size > 1 } ?: this

    /** This copy with the same [versions] list, for moving from one version to another. */
    fun asVersion(of: MediaItem): MediaItem = if (of.versions.isEmpty()) this else copy(versions = of.versions).keptReads(this)

    /**
     * Where a video's bytes are, as far as the viewer is concerned.
     *
     * Three answers rather than a yes or no, because the two kinds of copy behave differently: a
     * download is in TMPlayer's Downloads folder until the viewer deletes it, while a cached copy is
     * in TDLib's cache and the next video played may take it.
     */
    enum class Locality {
        /** Only on Telegram. Playing it streams it. */
        Remote,

        /** Whole in TDLib's cache, because it was played. Evictable. */
        Cached,

        /** In the Downloads folder, listed in the download index. Kept until deleted. */
        Downloaded,
    }

    /** This item with its copy described again, keeping [onDevice] and [locality] in step. */
    fun withLocality(locality: Locality): MediaItem =
        copy(onDevice = locality != Locality.Remote, locality = locality).keptReads(this)

    /**
     * This item with its cached state read again from the disk: [wholeOnDevice] is TDLib's answer
     * now, which may differ from the one it gave when the item was fetched.
     *
     * The watch cache holds one video, so playing another takes the last one's copy away, and a
     * tile fetched before that went on saying Cached. Downloaded is left alone, since only the
     * download index can say a download has gone. Returns this same instance when nothing moved,
     * so a caller can tell a list that needs publishing from one that does not.
     */
    fun recheckedCache(wholeOnDevice: Boolean): MediaItem = when {
        locality == Locality.Downloaded -> this
        wholeOnDevice == (locality == Locality.Cached) -> this
        wholeOnDevice -> withLocality(Locality.Cached)
        else -> withLocality(Locality.Remote)
    }

    /**
     * "4K", "1080p": read off the file name, which is where releases state it.
     *
     * Computed once and kept rather than on every read: as a `get()` the regex would run on every
     * recomposition of a tile, several hundred times a second on a scrolling grid, for an answer
     * that cannot change.
     */
    val qualityTags: List<String>
        get() = qualityTagsRead ?: MediaText.qualityTags(fileName).also { qualityTagsRead = it }

    /**
     * Kept rather than built on every read: Home's rows, the shelf and the search key, compare and
     * look up videos by it hundreds of times a build.
     */
    val id: String = "$chatId:$messageId"

    /** [ProgressKeys.of] for this video, the key its place and its Watched mark are stored under. */
    internal val progressKey: String
        get() = progressKeyRead ?: ProgressKeys.of(chatId, messageId).also { progressKeyRead = it }

    /**
     * Whether the file's name is one a camera or Telegram made up ([MediaText.isGenericName]),
     * blank included: asked for by the name to parse, the tile's title and the film check alike.
     */
    internal val genericFileName: Boolean
        get() = when (genericNameRead) {
            1 -> false
            2 -> true
            else -> MediaText.isGenericName(fileName).also { genericNameRead = if (it) 2 else 1 }
        }

    /**
     * The name to read a show, an episode or a year out of: the file's own, unless a camera or
     * Telegram made it up, in which case the title, which is then the caption's first line.
     */
    val nameForParsing: String
        get() = nameForParsingRead ?: (if (fileName.isBlank() || genericFileName) title else fileName).also { nameForParsingRead = it }

    /**
     * [nameForParsing] read by [MediaName.parse] with the caption as a second source, once per item:
     * grouping, keying and naming all ask for it, and a chat's listing asks for every file.
     */
    val parsedName: ParsedName
        get() = parsedNameRead ?: MediaName.parse(nameForParsing, caption).also { parsedNameRead = it }

    /**
     * The online lookup's question for this item, worked out once: Home's folding and search's
     * merging ask for it per item on every build.
     */
    val metaQuery: com.tmplayer.online.MetaQuery?
        get() = read(metaQueryRead) {
            com.tmplayer.online.MetaQuery.of(nameForParsing, caption).also { metaQueryRead = it ?: NotFound }
        }

    /** [MediaName.tileTitle] of the file's name, read once: search screens every item by it per letter. */
    val fileTileTitle: String?
        get() = read(fileTileTitleRead) { MediaName.tileTitle(fileName, caption).also { fileTileTitleRead = it ?: NotFound } }

    /** The same without the caption, for a caption the chat puts under every post. */
    val parsedNameAlone: ParsedName
        get() = parsedNameAloneRead
            ?: (if (caption.isBlank()) parsedName else MediaName.parse(nameForParsing)).also { parsedNameAloneRead = it }

    /**
     * [SameTitle.filmKey] as it reads with no correction of the viewer's on this video, which is
     * the case it is kept for: Home folds and keys every tile by it on every build.
     */
    internal val uncorrectedFilmKey: String?
        get() = read(filmKeyRead) { SameTitle.filmKeyOf(this, null).also { filmKeyRead = it ?: NotFound } }

    /** [SeriesShelf.sameVideoKey] with no correction of the viewer's on this video, kept the same way. */
    internal val uncorrectedCopyKey: String?
        get() = read(copyKeyRead) { SeriesShelf.uncorrectedCopyKey(this).also { copyKeyRead = it ?: NotFound } }

    /**
     * The show keys the file name alone and the caption's first line alone give, for a file with a
     * caption ([SeriesShelf]'s second look); empty without one. Before any is left out for being
     * the key the file was read under.
     */
    internal val alternateKeys: List<String>
        get() = alternateKeysRead ?: SeriesShelf.alternateKeysOf(this).also { alternateKeysRead = it }

    /** [EpisodeCopies.markers] of [nameForParsing]: whether the name says how its copy was made. */
    internal val nameMarkers: Set<String>
        get() = markersRead ?: EpisodeCopies.markers(nameForParsing).also { markersRead = it }

    /**
     * The caption's lines that say anything, trimmed and lower-cased, once each: what a chat's
     * listing counts to find the lines it puts under every post ([MediaText.repeatedCaptionLines]).
     */
    internal val captionLines: Set<String>
        get() = captionLinesRead
            ?: (if (caption.isEmpty()) emptySet() else caption.lineSequence().map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet())
                .also { captionLinesRead = it }

    /** Whether the name or the caption holds a bare "E05" that [SeriesShelf] could read loosely. */
    internal val mayNameLooseEpisode: Boolean
        get() = when (looseHintRead) {
            1 -> false
            2 -> true
            else -> SeriesShelf.mayBeLoose(this).also { looseHintRead = if (it) 2 else 1 }
        }

    /**
     * Everything a search inside a chat is matched against: Telegram finds a video by its caption
     * as well as by its file name, so ranking by the name alone threw away every video it found
     * through the words under it.
     */
    val searchText: String
        get() = searchTextRead
            ?: listOf(fileName, title, caption).filter { it.isNotBlank() }.distinct().joinToString("\n").also { searchTextRead = it }

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
            locality == other.locality &&
            canBeSaved == other.canBeSaved &&
            caption == other.caption &&
            width == other.width &&
            height == other.height &&
            topicId == other.topicId &&
            extra == other.extra &&
            albumId == other.albumId &&
            playingQuality == other.playingQuality &&
            startSec == other.startSec &&
            qualities == other.qualities &&
            storyboard == other.storyboard &&
            parts.size == other.parts.size &&
            pieces.size == other.pieces.size &&
            pieces.indices.all { pieces[it].id == other.pieces[it].id } &&
            // By id: a copy found since (a new upload of the film) has to reach the tile and the
            // page, and comparing whole copies would compare their copies' thumbnails over again.
            versions.size == other.versions.size &&
            versions.indices.all { versions[it].id == other.versions[it].id } &&
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
        result = 31 * result + locality.hashCode()
        result = 31 * result + canBeSaved.hashCode()
        result = 31 * result + caption.hashCode()
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + topicId
        result = 31 * result + (extra?.hashCode() ?: 0)
        result = 31 * result + playingQuality
        result = 31 * result + startSec
        result = 31 * result + qualities.size
        result = 31 * result + parts.size
        result = 31 * result + pieces.size
        result = 31 * result + (miniThumbnail?.contentHashCode() ?: 0)
        result = 31 * result + versions.fold(0) { acc, copy -> 31 * acc + copy.id.hashCode() }
        return result
    }
}

/** A null answer kept on a [MediaItem], told apart from one not worked out yet. */
private object NotFound
