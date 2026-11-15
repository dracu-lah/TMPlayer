package com.tmplayer.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

/**
 * The photo of a Telegram album, as far as its videos need it: the picture and the caption. A
 * channel that posts "a cover photo with the title under it, then the video" sends them as one
 * album, so to a viewer it is one post and the video should read as the title and wear the photo.
 */
class AlbumPhoto(
    val chatId: Long,
    val albumId: Long,
    /** The photo's own message, the lowest one of the album's photos that carries [caption] or a picture. */
    val messageId: Long,
    /** The photo's file at a card's size, or 0 when Telegram listed no size for it. */
    val fileId: Int,
    /** The photo's inline preview, or null. */
    val mini: ByteArray?,
    /** The caption of the album as the photo carries it, trimmed and cleaned like any caption, or empty. */
    val caption: String,
)

/** What one read of an album's neighbourhood found: its videos (the photo already folded in) and its photo. */
class AlbumWindow(val items: List<MediaItem>, val photo: AlbumPhoto?)

/** Reads the messages around one message and answers the album it belongs to. Throws when TDLib fails. */
fun interface AlbumReader {
    suspend fun read(chatId: Long, anchorMessageId: Long): AlbumWindow
}

/**
 * Albums read as one post (EchoGram B11, B16): the album's photo gives its videos a caption and a
 * picture, and a search that hits the photo leads to the album's videos.
 *
 * The picture ladder, one for every screen: the uploader's own cover (`MessageVideo.cover`) first,
 * then the album's photo, then the frame Telegram took of the video. All three are the item's
 * [MediaItem.thumbnailFileId], so every card, page and player cover draws them the same way. The
 * online poster (TMDB, TVmaze, AniList) stays above all of them where posters are on, as it is
 * for every video, because the poster card draws a provider's poster before any Telegram
 * picture; a title the providers do not know shows the album's photo.
 *
 * The caption ladder: the video's own caption (and so the existing name parsing) first, then the
 * caption of the earliest captioned member of its album, photo or video.
 *
 * Cost: a listing never reads for albums. Only a search hit does ([enrich]): one `getChatHistory`
 * window per album, at most [ALBUMS_PER_PAGE] a page, [READS_AT_ONCE] at a time.
 */
object AlbumPosts {

    /** Telegram's albums hold ten messages. */
    const val MAX_MEMBERS = 10

    /** Albums read per page of search results: the rest are read on the next page or not at all. */
    const val ALBUMS_PER_PAGE = 6

    /** Reads in flight together. */
    const val READS_AT_ONCE = 3

    /** The longest FLOOD_WAIT a read sits out, once; past it the album stays as the hit showed it. */
    const val MAX_FLOOD_WAIT_S = 30

    /** Messages read around a hit: the whole of an album ten long, wherever the hit falls in it. */
    const val WINDOW = MAX_MEMBERS * 2 + 1

    /**
     * One photo per album from the [photos] a page holds: the album's first picture with the album's
     * first caption. An album with no photo at all is not in the answer; one whose photo has no
     * picture size still carries its caption.
     */
    fun pick(photos: Iterable<AlbumPhoto>): Map<Long, AlbumPhoto> {
        val out = HashMap<Pair<Long, Long>, MutableList<AlbumPhoto>>()
        for (photo in photos) {
            if (photo.albumId == 0L) continue
            out.getOrPut(photo.chatId to photo.albumId) { ArrayList() } += photo
        }
        val picked = HashMap<Long, AlbumPhoto>(out.size)
        for ((key, members) in out) {
            val sorted = members.sortedBy { it.messageId }
            val captioned = sorted.firstOrNull { it.caption.isNotBlank() }
            val pictured = sorted.firstOrNull { it.fileId != 0 }
            if (captioned == null && pictured == null) continue
            picked[key.second] = AlbumPhoto(
                chatId = key.first,
                albumId = key.second,
                messageId = (captioned ?: pictured)!!.messageId,
                fileId = pictured?.fileId ?: 0,
                mini = pictured?.mini,
                caption = captioned?.caption.orEmpty(),
            )
        }
        return picked
    }

    /**
     * [items] with each album's photo folded into the videos that album holds. A video with a
     * caption of its own keeps it (and so its name's reading), one without takes the caption of
     * the album's earliest captioned member; a video with no cover of its own takes the photo as
     * its picture. [photos] is [pick]'s answer, by album id.
     *
     * An album whose videos are named for different shows (one post of two unrelated titles) is
     * left alone: its photo and its caption belong to one of them, and which is not known.
     * Nothing is read from the network, and a list with no album in it comes back as it is.
     */
    fun fold(items: List<MediaItem>, photos: Map<Long, AlbumPhoto>): List<MediaItem> {
        if (photos.isEmpty() || items.none { it.albumId != 0L && it.albumId in photos }) return items
        val byAlbum = items.filter { it.albumId != 0L && it.albumId in photos }.groupBy { it.albumId }
        val mixed = byAlbum.filterValues(::isMixed).keys
        // The earliest captioned member decides, a photo among them: Telegram shows one caption per album.
        val captions = HashMap<Long, String>()
        for ((album, members) in byAlbum) {
            if (album in mixed) continue
            val photo = photos.getValue(album)
            var bestId = Long.MAX_VALUE
            var best = ""
            if (photo.caption.isNotBlank()) { bestId = photo.messageId; best = photo.caption }
            for (m in members) if (m.caption.isNotBlank() && m.messageId < bestId) { bestId = m.messageId; best = m.caption }
            if (best.isNotEmpty()) captions[album] = best
        }
        return items.map { item ->
            val album = item.albumId
            if (album == 0L || album !in photos || album in mixed) return@map item
            val photo = photos.getValue(album)
            var next = item
            val shared = captions[album]
            if (shared != null && item.caption.isBlank()) {
                next = next.copy(caption = shared, title = MediaText.displayTitle(item.fileName, shared, item.title))
            }
            if (!item.hasCover && item.albumPhotoFileId == 0 && photo.fileId != 0 && !MediaText.isRound(item)) {
                next = next.copy(
                    thumbnailFileId = photo.fileId,
                    miniThumbnail = photo.mini ?: item.miniThumbnail,
                    albumPhotoFileId = photo.fileId,
                )
            }
            next
        }
    }

    /** Two videos of one album that name two different shows. A video named by a camera or by nothing names none. */
    internal fun isMixed(members: List<MediaItem>): Boolean {
        if (members.size < 2) return false
        val shows = HashSet<String>()
        for (m in members) {
            if (m.fileName.isBlank() || MediaText.isGenericName(m.fileName)) continue
            val title = Fuzzy.tokens(m.parsedNameAlone.title).joinToString(" ")
            if (title.isNotEmpty()) shows += title
        }
        return shows.size > 1
    }

    /**
     * The photos among [hits] that belong to an album, as a search hit: what a search with the photo
     * filter found. Albumless photos and ones the photo cannot lead anywhere from are dropped.
     */
    fun hitsOf(hits: List<AlbumPhoto>): List<AlbumPhoto> = hits.filter { it.albumId != 0L && it.messageId != 0L }

    /**
     * One page of search results made to read as albums: the page's videos folded with what their
     * albums' photos say, and, for a photo among [hits], the videos of its album added (each one
     * that took the photo's caption, so it answers the words the photo was found by).
     *
     * One [reader] call per album, never per item: the albums of the hits first, then those of the
     * page's videos that still lack a photo, [ALBUMS_PER_PAGE] in all and [READS_AT_ONCE] at a time.
     * A read that fails leaves its album as the hit showed it. A flood wait is waited out by that
     * read alone, once, and only up to [MAX_FLOOD_WAIT_S].
     */
    suspend fun enrich(
        items: List<MediaItem>,
        hits: List<AlbumPhoto>,
        reader: AlbumReader,
        wait: suspend (Long) -> Unit = { delay(it) },
    ): List<MediaItem> {
        val wanted = LinkedHashMap<Pair<Long, Long>, Pair<Long, AlbumPhoto?>>()
        for (hit in hitsOf(hits).sortedByDescending { it.messageId }) {
            wanted.getOrPut(hit.chatId to hit.albumId) { hit.messageId to hit }
        }
        for (item in items.sortedByDescending { it.messageId }) {
            if (item.albumId == 0L || item.albumPhotoFileId != 0) continue
            if (item.caption.isNotBlank() && item.hasCover) continue
            wanted.getOrPut(item.chatId to item.albumId) { item.messageId to null }
        }
        if (wanted.isEmpty()) return items
        val reads = wanted.entries.take(ALBUMS_PER_PAGE)
        val windows = ArrayList<Triple<Pair<Long, Long>, AlbumPhoto?, AlbumWindow>>()
        for (chunk in reads.chunked(READS_AT_ONCE)) {
            val answered = coroutineScope {
                chunk.map { (key, anchor) ->
                    async { readOnce(reader, key.first, anchor.first, wait)?.let { Triple(key, anchor.second, it) } }
                }.map { it.await() }
            }
            windows += answered.filterNotNull()
        }
        if (windows.isEmpty()) return items
        val photos = HashMap<Long, AlbumPhoto>()
        for ((_, _, window) in windows) window.photo?.let { photos[it.albumId] = it }
        val merged = fold(items, photos)
        val have = merged.mapTo(HashSet()) { it.chatId to it.messageId }
        val extra = ArrayList<MediaItem>()
        for ((key, hit, window) in windows) {
            if (hit == null) continue
            // The photo was found by its caption: its videos are the answer, those that took it.
            val answer = window.items.filter {
                it.albumId == key.second && it.chatId == key.first && (it.chatId to it.messageId) !in have &&
                    hit.caption.isNotBlank() && it.caption == hit.caption
            }.take(MAX_MEMBERS)
            for (member in answer) if (have.add(member.chatId to member.messageId)) extra += member
        }
        return if (extra.isEmpty()) merged else merged + extra
    }

    /** One read, its flood wait waited out once; null when it failed. */
    private suspend fun readOnce(reader: AlbumReader, chatId: Long, anchor: Long, wait: suspend (Long) -> Unit): AlbumWindow? {
        var waited = false
        while (true) {
            try {
                return reader.read(chatId, anchor)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val seconds = Failures.floodWaitSeconds(e.message)
                if (seconds != null && seconds in 1..MAX_FLOOD_WAIT_S && !waited) {
                    waited = true
                    wait(seconds * 1_000L + 250L)
                    continue
                }
                return null
            }
        }
    }
}
