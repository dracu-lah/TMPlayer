@file:OptIn(ExperimentalJsExport::class)
@file:JsExport

package com.tmplayer.web

import com.tmplayer.data.ArchiveKind
import com.tmplayer.data.Archives
import com.tmplayer.data.ChatKind
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.ContentProtection
import com.tmplayer.data.EpisodeNeighbours
import com.tmplayer.data.EpisodeTag
import com.tmplayer.data.Fuzzy
import com.tmplayer.data.HomeRow
import com.tmplayer.data.HomeRows
import com.tmplayer.data.MediaItem
import com.tmplayer.data.MediaName
import com.tmplayer.data.MediaText
import com.tmplayer.data.ResumeRecord
import com.tmplayer.data.ResumeRewind
import com.tmplayer.data.Series
import com.tmplayer.data.SeriesShelf
import com.tmplayer.data.ShelfEntry
import com.tmplayer.data.SizeFilter
import com.tmplayer.data.VideoSort
import com.tmplayer.data.VideoSorting
import com.tmplayer.i18n.L
import com.tmplayer.i18n.Translator
import com.tmplayer.online.MetaKind

// The app's own rules for the web player (webapp/, CP104), exported as one ES module that runs in
// the page's Telegram worker: what counts as a video and what it is called, how a chat's videos
// group into shows, copies and pieces, their orders, the size limits, the episodes either side,
// fuzzy search, saving rules and the rewind on resume. The worker hands videos in as plain
// objects and gets plain objects back, which cross postMessage as they are.
//
// Numbers cross as JS numbers: chat and message ids fit in a double; an album id (a 64 bit
// grouped_id) does not, so it crosses as its decimal string.

/** A video as the worker read it off a message, before the app's rules. */
interface RawVideo {
    val chatId: Double
    val messageId: Double
    /** "video", "animation", "document" or "round". */
    val kind: String
    val fileName: String?
    val mimeType: String?
    /** The post's caption as Telegram sent it, untrimmed. */
    val caption: String?
    val size: Double
    val duration: Double
    val width: Double
    val height: Double
    /** Seconds since the epoch, as Telegram dates a message. */
    val date: Double
    /** The forum topic, or 0. */
    val topicId: Double
    /** The album (grouped_id) as a decimal string, or null for a post on its own. */
    val albumId: String?
    /** The message's own "can be saved" (false under the chat's "restrict saving content"). */
    val canBeSaved: Boolean
    /** A timer or "view once": never listed. */
    val selfDestructs: Boolean
    /** Withheld by Telegram here (a restriction reason), scheduled or still sending: never listed. */
    val withheld: Boolean
}

/** A video after the app's rules: what the lists, the player and the rest of this module take. */
interface Video {
    /** "chatId:messageId", the app's own key for a video. */
    val id: String
    val chatId: Double
    val messageId: Double
    val title: String
    val fileName: String
    val caption: String
    val mimeType: String
    val size: Double
    val duration: Double
    val width: Double
    val height: Double
    val date: Double
    val topicId: Double
    val albumId: String?
    /** False: stream only, nothing kept (ContentProtection.StreamOnly). */
    val canBeSaved: Boolean
    val round: Boolean
}

/** A page of messages after the rules: the videos, and how many self-destructing ones were left out. */
interface Screened {
    val videos: Array<Video>
    val selfDestructing: Int
}

/**
 * One tile of a chat's listing. [kind] "show": [title] and [seasons]. [kind] "file": [video],
 * with [copies] (every copy of one film, this one first), [parts] (a film cut into "CD1", "CD2")
 * and [pieces] (raw byte pieces that play as one) as ids, and [extra] for a trailer or sample.
 */
interface ShelfTile {
    val key: String
    val kind: String
    val title: String
    val video: Video?
    val copies: Array<String>
    val parts: Array<String>
    val pieces: Array<String>
    val extra: String?
    val seasons: Array<WebSeason>
}

interface WebSeason {
    val number: Int
    val episodes: Array<WebEpisode>
}

interface WebEpisode {
    val season: Int
    val episode: Int
    /** "S01E02". */
    val code: String
    /** The copy listed for the episode. */
    val video: Video
    /** Every copy's id, the listed one first. */
    val copies: Array<String>
}

interface Shelf {
    val tiles: Array<ShelfTile>
    /** Videos the size limits left out, for "N hidden by size". */
    val hiddenBySize: Int
}

interface EpisodeLabel {
    /** "S01E05". */
    val code: String
    val name: String?
    val show: String
    val season: Int
    val episode: Int
}

interface Steps {
    val previous: Video?
    val next: Video?
    val current: EpisodeLabel?
    val previousLabel: EpisodeLabel?
    val nextLabel: EpisodeLabel?
    /** The whole show, for the episode list; null for a film. */
    val series: ShelfTile?
}

interface Parsed {
    val title: String
    val year: Int?
    val season: Int?
    val episode: Int?
    val lastEpisode: Int?
    val part: Int?
    val extra: String?
    val airDate: String?
    /** "S01E02", or null for a film. */
    val code: String?
}

/**
 * What the poster lookup asks about a video (MetaQuery.of, the question every app puts to TMDB):
 * the cleaned title, the year, whether it is a film or a show, a `{tmdb-123}` the name forces, and
 * the audio languages the name lists. [series] is whether the name says an episode, which is what
 * lets a show's title stand without a year (MetaConfidence).
 */
interface PosterAsk {
    val title: String
    val year: Int?
    /** "film" or "show". */
    val kind: String
    val tmdbId: Int?
    val series: Boolean
    /** ISO 639-1 codes, a hint for which of two titles of one name is meant. */
    val languages: Array<String>
}

/** A compressed file in a chat, listed as one that cannot be played. */
interface Archive {
    /** "ZIP", "RAR", "7z" or "archive". */
    val kind: String
    /** One volume of several, which needs its fellows. */
    val volume: Boolean
    /** Why it is not played, in the provided catalog's words. */
    val reason: String
}

interface SizeLimits {
    val floor: Double
    val ceiling: Double
    val step: Double
    val defaultMin: Double
    val defaultMax: Double
}

/** A video left part way, as the page keeps its place, with the video read again off its message. */
interface WebResume {
    val video: Video
    val chatTitle: String
    val positionMs: Double
    /** 0 when unknown. */
    val durationMs: Double
    /** When it was left, milliseconds since the epoch. */
    val updatedAt: Double
}

/** A chat as Home picks from them, in the chat list's order. */
interface WebChat {
    val id: Double
    val title: String
    /** Saved Messages. */
    val saved: Boolean
    val pinned: Boolean
}

/** One of Home's chats' newest videos, within the size limits; [complete] false when the chat has older ones. */
interface ChatVideos {
    val chatId: Double
    val videos: Array<Video>
    val complete: Boolean
}

/**
 * One row of Home (HomeRows.build). [kind] "continue": [resumes] are the videos part way, as ids,
 * copies folded. "recent": the newest across Home's chats, [starredOnly] when every one of them
 * is starred. "chat": a starred chat's newest, [chatId] and [title]. [loaded] false is a row still
 * waiting for Telegram, drawn as a placeholder of the same height.
 */
interface WebHomeRow {
    val key: String
    val kind: String
    val chatId: Double?
    val title: String?
    val loaded: Boolean
    val starredOnly: Boolean
    val resumes: Array<String>
    val tiles: Array<ShelfTile>
    /** For "See all": how many videos, at least that many when [totalAtLeast], and whether the row leaves any out. */
    val total: Int
    val totalAtLeast: Boolean
    val hasMore: Boolean
}

/**
 * The UI catalog for [tag] ("en" first: i18n/en.json as text). A few rules name things in the
 * app's words (an untitled file, a round video), so the worker provides English before it maps.
 */
fun provideCatalog(tag: String, json: String) = Translator.provide(tag, json)

/** One message's video after the rules, or null when it is not listed (not a video, withheld, self-destructing). */
fun video(raw: RawVideo): Video? = if (raw.selfDestructs) null else mapped(raw)?.let(::toWeb)

/**
 * A page of messages: the videos to list, an album's caption shared with its captionless
 * videos as the apps do, and the self-destructing ones counted.
 */
fun screen(raws: Array<RawVideo>): Screened {
    val screened = ContentProtection.screen(raws.toList(), { it.selfDestructs }, ::mapped)
    val items = MediaText.shareAlbumCaptions(screened.items)
    return plain("videos" to items.map(::toWeb).toTypedArray(), "selfDestructing" to screened.selfDestructing)
}

/**
 * A chat's listing: [videos] through the size limits, grouped into shows, copies and pieces as
 * the apps group them, in [sort]'s order ("Newest", "Oldest", "Name", "Year", "Size",
 * "Longest", "Unwatched"). [watched] and [started] are video ids, for the Unwatched order.
 */
fun shelf(
    videos: Array<Video>,
    sort: String,
    minBytes: Double,
    maxBytes: Double,
    watched: Array<String>,
    started: Array<String>,
): Shelf {
    val split = SizeFilter.split(videos.map(::toItem), minBytes.toLong(), maxBytes.toLong()) { it.sizeBytes }
    val done = watched.toHashSet()
    val going = started.toHashSet()
    val sorted = VideoSorting.sort(SeriesShelf.arrange(split.kept), VideoSort.decode(sort), progress = { item ->
        when (item.id) {
            in done -> VideoSorting.Progress.Watched
            in going -> VideoSorting.Progress.InProgress
            else -> VideoSorting.Progress.Untouched
        }
    }, locale = L.messages.locale)
    return plain("tiles" to sorted.map(::tile).toTypedArray(), "hiddenBySize" to split.hidden)
}

/** The episodes either side of [current] among [videos], the chat's, as the player steps them. */
fun steps(current: Video, videos: Array<Video>): Steps {
    val steps = EpisodeNeighbours.around(toItem(current), videos.map(::toItem))
    return plain(
        "previous" to steps.previous?.let(::toWeb),
        "next" to steps.next?.let(::toWeb),
        "current" to steps.current?.let(::label),
        "previousLabel" to steps.previousTag?.let(::label),
        "nextLabel" to steps.nextTag?.let(::label),
        "series" to steps.series?.let { tile(ShelfEntry.Show(it)) },
    )
}

/** What a file name (and its caption) says: title, year, episode, part, extra. */
fun parse(fileName: String, caption: String?): Parsed {
    val name = MediaName.parse(fileName, caption)
    return plain(
        "title" to name.title,
        "year" to name.year,
        "season" to name.season,
        "episode" to name.episode,
        "lastEpisode" to name.lastEpisode,
        "part" to name.part,
        "extra" to name.extra?.name,
        "airDate" to name.airDate,
        "code" to name.episodeCode,
    )
}

/** The poster lookup's question for [video], or null when its name holds nothing worth asking (MetaQuery.of). */
fun posterAsk(video: Video): PosterAsk? {
    val item = toItem(video)
    val query = item.metaQuery ?: return null
    return plain(
        "title" to query.title,
        "year" to query.year,
        "kind" to if (query.kind == MetaKind.Show) "show" else "film",
        "tmdbId" to query.tmdbId,
        "series" to item.parsedName.isSeries,
        "languages" to query.languages.toTypedArray(),
    )
}

/** The title a tile shows for [video], as the apps' cards do. */
fun tileTitle(video: Video): String = SeriesShelf.tileTitle(toItem(video))

/** Indexes of [texts] that answer [query], best first; every index for a blank query. */
fun rank(texts: Array<String>, query: String): Array<Int> =
    Fuzzy.rank(texts.indices.toList(), query) { texts[it] }.toTypedArray()

/** "Full", "StreamOnly" or "Hidden" (ContentProtection.verdict). */
fun protection(canBeSaved: Boolean, chatProtected: Boolean, selfDestructs: Boolean): String =
    ContentProtection.verdict(canBeSaved, chatProtected, selfDestructs).name

/** Where a video saved at [savedMs] opens, with the rewind taken off. */
fun resumeStart(savedMs: Double, rewindMs: Double): Double = ResumeRewind.start(savedMs.toLong(), rewindMs.toLong()).toDouble()

/** What to save at [positionMs] for a video resumed from [savedMs] (null when it started fresh). */
fun resumeKept(savedMs: Double?, positionMs: Double, rewindMs: Double): Double =
    ResumeRewind.kept(savedMs?.toLong(), positionMs.toLong(), rewindMs.toLong()).toDouble()

/** A stored rewind setting, or the default when it is off the list. */
fun rewindSetting(ms: Double?): Double = ResumeRewind.sanitise(ms?.toLong()).toDouble()

/** The choices Settings offers for the rewind, in milliseconds; 0 is off. */
fun rewindChoices(): Array<Double> = ResumeRewind.CHOICES_MS.map { it.toDouble() }.toTypedArray()

fun sizeLimits(): SizeLimits = plain(
    "floor" to SizeFilter.FLOOR.toDouble(),
    "ceiling" to SizeFilter.CEILING.toDouble(),
    "step" to SizeFilter.STEP.toDouble(),
    "defaultMin" to SizeFilter.DEFAULT_MIN.toDouble(),
    "defaultMax" to SizeFilter.DEFAULT_MAX.toDouble(),
)

/** Whether a file of [bytes] passes; an unknown size (0) always does, the top means no limit. */
fun sizeMatches(bytes: Double, minBytes: Double, maxBytes: Double): Boolean =
    SizeFilter.matches(bytes.toLong(), minBytes.toLong(), maxBytes.toLong())

/** One press of the slider in [direction] (+1 or -1), on the 100 MB grid. */
fun sizeStep(bytes: Double, direction: Int): Double = SizeFilter.step(bytes.toLong(), direction).toDouble()

/** Where the slider's thumb sits for [bytes], 0 to 1. */
fun sizeFraction(bytes: Double): Double = SizeFilter.fraction(bytes.toLong()).toDouble()

/** The size at [fraction] of the track, snapped to the grid. */
fun sizeAt(fraction: Double): Double = SizeFilter.snap(SizeFilter.bytesAt(fraction.toFloat())).toDouble()

/** "Any size", "50 MB and up" and the like, in the provided catalog's words. */
fun sizeRange(minBytes: Double, maxBytes: Double): String = SizeFilter.describe(minBytes.toLong(), maxBytes.toLong())

/** "50 MB", "1.5 GB", or the slider's ends ("No minimum", "No limit"), as the apps label a size limit. */
fun sizeLabel(bytes: Double): String = SizeFilter.label(bytes.toLong())

/**
 * The chats Home reads (HomeRows.homeChats): the starred ones, Saved Messages, the first pinned
 * ones, then those [recent] (the newest videos across every chat, null while that search runs)
 * found the most videos in. Ids, in that order.
 */
fun homeChats(chats: Array<WebChat>, starred: Array<Double>, recent: Array<Video>?): Array<Double> =
    HomeRows.homeChats(chats.map(::summary), starred.map { it.toLong() }.toSet(), recent?.map(::toItem))
        .map { it.id.toDouble() }.toTypedArray()

/**
 * Home's rows as the apps build them where Home fills itself: Continue watching, Recently added
 * across [chats] (homeChats' answer), then a row for each starred one. [loaded] holds the chats
 * read so far; [recent] is null while the search across chats runs; [chatsPending] says the chat
 * list is not in yet.
 */
fun home(
    resumes: Array<WebResume>,
    chats: Array<WebChat>,
    starred: Array<Double>,
    loaded: Array<ChatVideos>,
    recent: Array<Video>?,
    chatsPending: Boolean,
): Array<WebHomeRow> {
    val records = resumes.map { r ->
        val v = r.video
        ResumeRecord(
            chatId = v.chatId.toLong(),
            messageId = v.messageId.toLong(),
            fileId = 0,
            title = v.title,
            chatTitle = r.chatTitle,
            sizeBytes = v.size.toLong(),
            durationSec = v.duration.toInt(),
            positionMs = r.positionMs.toLong(),
            durationMs = r.durationMs.toLong(),
            updatedAt = r.updatedAt.toLong(),
            fileName = v.fileName,
            caption = v.caption,
        )
    }
    val rows = HomeRows.build(
        continueWatching = records,
        favourites = chats.map(::summary),
        loaded = loaded.associate { c -> c.chatId.toLong() to c.videos.map(::toItem) },
        recent = recent?.map(::toItem),
        partial = loaded.filterNot { it.complete }.map { it.chatId.toLong() }.toSet(),
        starred = starred.map { it.toLong() }.toSet(),
        autoFill = true,
        chatsPending = chatsPending,
    )
    return rows.mapNotNull { row ->
        val base = arrayOf<Pair<String, Any?>>(
            "key" to row.key,
            "total" to row.total,
            "totalAtLeast" to row.totalAtLeast,
            "hasMore" to row.hasMore,
        )
        when (row) {
            is HomeRow.Continue -> plain<WebHomeRow>(
                *base, "kind" to "continue", "chatId" to null, "title" to null, "loaded" to true, "starredOnly" to false,
                "resumes" to row.records.map { it.toMediaItem().id }.toTypedArray(), "tiles" to emptyArray<ShelfTile>(),
            )
            is HomeRow.Recent -> plain<WebHomeRow>(
                *base, "kind" to "recent", "chatId" to null, "title" to null, "loaded" to row.loaded, "starredOnly" to row.starredOnly,
                "resumes" to emptyArray<String>(), "tiles" to row.entries.map(::tile).toTypedArray(),
            )
            is HomeRow.Chat -> plain<WebHomeRow>(
                *base, "kind" to "chat", "chatId" to row.chatId.toDouble(), "title" to row.title, "loaded" to row.loaded, "starredOnly" to true,
                "resumes" to emptyArray<String>(), "tiles" to row.entries.map(::tile).toTypedArray(),
            )
            // Not built where Home fills itself.
            is HomeRow.WatchLater, is HomeRow.NewEpisodes -> null
        }
    }.toTypedArray()
}

private fun summary(chat: WebChat) = ChatSummary(
    id = chat.id.toLong(),
    title = chat.title,
    miniThumbnail = null,
    photoFileId = 0,
    kind = if (chat.saved) ChatKind.Saved else ChatKind.Group,
    isPinned = chat.pinned,
)

/** Whether [fileName] (or [mimeType]) is an archive, and what kind, as a chat lists it; null when not. */
fun archive(fileName: String, mimeType: String?): Archive? {
    val kind: ArchiveKind = Archives.kindOf(fileName, mimeType.orEmpty()) ?: return null
    val volume = Archives.isVolume(fileName)
    return plain(
        "kind" to kind.label,
        "volume" to volume,
        "reason" to if (volume) L.gridArchiveVolumeReason(kind.label) else L.gridArchiveReason(kind.label),
    )
}

/** MediaMapper's rules, for a message the worker read instead of TDLib. */
private fun mapped(raw: RawVideo): MediaItem? {
    if (raw.withheld) return null
    val name = raw.fileName.orEmpty()
    val mime = raw.mimeType.orEmpty()
    val round = raw.kind == "round"
    if (raw.kind == "document" && !MediaText.looksLikeVideo(name, mime)) return null
    val fallback = when (raw.kind) {
        "document" -> L.mediaUntitledFile
        "animation" -> L.mediaUntitledClip
        else -> L.mediaUntitledVideo
    }
    val item = MediaItem(
        chatId = raw.chatId.toLong(),
        messageId = raw.messageId.toLong(),
        fileId = 0,
        title = if (round) L.mediaRoundVideo else MediaText.displayTitle(name, raw.caption, fallback),
        sizeBytes = raw.size.toLong(),
        durationSec = if (raw.kind == "document") 0 else raw.duration.toInt(),
        mimeType = if (round) "video/mp4" else mime,
        thumbnailFileId = 0,
        miniThumbnail = null,
        date = raw.date.toInt(),
        fileName = if (round) "" else name,
        caption = if (round) "" else MediaText.captionOf(raw.caption),
        width = raw.width.toInt(),
        height = raw.height.toInt(),
        topicId = raw.topicId.toInt(),
        albumId = raw.albumId?.toLongOrNull() ?: 0L,
    )
    return when (ContentProtection.verdict(raw.canBeSaved, chatProtected = false, selfDestructs = false)) {
        ContentProtection.Verdict.Full -> item
        else -> item.copy(canBeSaved = false)
    }
}

private fun toItem(video: Video): MediaItem = MediaItem(
    chatId = video.chatId.toLong(),
    messageId = video.messageId.toLong(),
    fileId = 0,
    title = video.title,
    sizeBytes = video.size.toLong(),
    durationSec = video.duration.toInt(),
    mimeType = video.mimeType,
    thumbnailFileId = 0,
    miniThumbnail = null,
    date = video.date.toInt(),
    fileName = video.fileName,
    canBeSaved = video.canBeSaved,
    caption = video.caption,
    width = video.width.toInt(),
    height = video.height.toInt(),
    topicId = video.topicId.toInt(),
    albumId = video.albumId?.toLongOrNull() ?: 0L,
)

private fun toWeb(item: MediaItem): Video = plain(
    "id" to item.id,
    "chatId" to item.chatId.toDouble(),
    "messageId" to item.messageId.toDouble(),
    "title" to item.title,
    "fileName" to item.fileName,
    "caption" to item.caption,
    "mimeType" to item.mimeType,
    "size" to item.sizeBytes.toDouble(),
    "duration" to item.durationSec.toDouble(),
    "width" to item.width.toDouble(),
    "height" to item.height.toDouble(),
    "date" to item.date.toDouble(),
    "topicId" to item.topicId.toDouble(),
    "albumId" to item.albumId.takeIf { it != 0L }?.toString(),
    "canBeSaved" to item.canBeSaved,
    "round" to MediaText.isRound(item),
)

private fun ids(items: List<MediaItem>): Array<String> = items.map { it.id }.toTypedArray()

private fun tile(entry: ShelfEntry): ShelfTile = when (entry) {
    is ShelfEntry.Show -> show(entry.key, entry.series)
    is ShelfEntry.File -> {
        val item = entry.item
        plain(
            "key" to entry.key,
            "kind" to "file",
            "title" to SeriesShelf.tileTitle(item),
            "video" to toWeb(item),
            "copies" to ids(item.versions),
            "parts" to ids(item.parts),
            "pieces" to ids(item.pieces),
            "extra" to item.extra?.name,
            "seasons" to emptyArray<WebSeason>(),
        )
    }
}

private fun show(key: String, series: Series): ShelfTile = plain(
    "key" to key,
    "kind" to "show",
    "title" to series.title,
    "video" to toWeb(series.cover),
    "copies" to emptyArray<String>(),
    "parts" to emptyArray<String>(),
    "pieces" to emptyArray<String>(),
    "extra" to null,
    "seasons" to series.seasons.map { season ->
        plain<WebSeason>(
            "number" to season.number,
            "episodes" to season.episodes.map { episode ->
                plain<WebEpisode>(
                    "season" to episode.season,
                    "episode" to episode.episode,
                    "code" to episode.code,
                    "video" to toWeb(episode.item),
                    "copies" to ids(episode.copies),
                )
            }.toTypedArray(),
        )
    }.toTypedArray(),
)

private fun label(tag: EpisodeTag): EpisodeLabel = plain(
    "code" to tag.code,
    "name" to tag.name,
    "show" to tag.show,
    "season" to tag.season,
    "episode" to tag.episode,
)

/** A plain JS object of [fields], typed as the exported interface it stands for. */
private fun <T> plain(vararg fields: Pair<String, Any?>): T {
    val o: dynamic = js("({})")
    for ((name, value) in fields) o[name] = value
    return o.unsafeCast<T>()
}
