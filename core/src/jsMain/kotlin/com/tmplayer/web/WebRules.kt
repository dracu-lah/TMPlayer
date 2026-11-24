@file:OptIn(ExperimentalJsExport::class)
@file:JsExport

package com.tmplayer.web

import com.tmplayer.data.AlbumPhoto
import com.tmplayer.data.AlbumPosts
import com.tmplayer.data.ArchiveFile
import com.tmplayer.data.ArchiveKind
import com.tmplayer.data.Archives
import com.tmplayer.data.ChatKind
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.ContentProtection
import com.tmplayer.data.DeviceForm
import com.tmplayer.data.EpisodeNeighbours
import com.tmplayer.data.EpisodeTag
import com.tmplayer.data.FormatFacts
import com.tmplayer.data.Fuzzy
import com.tmplayer.data.GlobalSearch
import com.tmplayer.data.HomeLayout
import com.tmplayer.data.HomeOrder
import com.tmplayer.data.HomeRow
import com.tmplayer.data.HomeRows
import com.tmplayer.data.LessonSteps
import com.tmplayer.data.MediaFacts
import com.tmplayer.data.MediaItem
import com.tmplayer.data.MediaName
import com.tmplayer.data.MediaText
import com.tmplayer.data.PosterPick
import com.tmplayer.data.QualityHabit
import com.tmplayer.data.QualityRead
import com.tmplayer.data.RandomPick
import com.tmplayer.data.ResumeRecord
import com.tmplayer.data.ResumeRewind
import com.tmplayer.data.Series
import com.tmplayer.data.EpisodeGuide
import com.tmplayer.data.SeriesShelf
import com.tmplayer.data.SettingsGroup
import com.tmplayer.data.SettingsMap
import com.tmplayer.data.SettingsSearch
import com.tmplayer.data.ReleaseHighlights
import com.tmplayer.data.copyQuality
import com.tmplayer.data.ForumTopicSummary
import com.tmplayer.data.ShelfEntry
import com.tmplayer.data.SizeFilter
import com.tmplayer.data.SplitPieces
import com.tmplayer.data.StoredKeys
import com.tmplayer.data.TgLink
import com.tmplayer.data.TopicHit
import com.tmplayer.data.TopicSearch
import com.tmplayer.data.VideoCopies
import com.tmplayer.data.VideoCorrection
import com.tmplayer.data.VideoCorrections
import com.tmplayer.data.VideoFacets
import com.tmplayer.data.VideoFaceting
import com.tmplayer.data.VideoFilter
import com.tmplayer.data.VideoFiltering
import com.tmplayer.data.VideoSort
import com.tmplayer.data.VideoSorting
import com.tmplayer.i18n.L
import com.tmplayer.i18n.Languages
import com.tmplayer.i18n.Translator
import com.tmplayer.online.ChatLanguageHints
import com.tmplayer.online.MetaKind
import com.tmplayer.online.SubtitlePickKeys
import com.tmplayer.player.PlaybackWatch
import com.tmplayer.player.PlayerKeyMap
import com.tmplayer.player.SpeedMemory
import kotlin.math.roundToInt

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

/**
 * A photo of an album as the worker read it (a photo among a page's messages, or a search hit on
 * one): the album's caption and picture for [AlbumPosts].
 */
interface RawPhoto {
    val chatId: Double
    val messageId: Double
    /** The album (grouped_id) as a decimal string, or null for a photo on its own. */
    val albumId: String?
    /** The photo's caption as Telegram sent it, untrimmed. */
    val caption: String?
    /** Whether the worker can draw the photo (it has a thumbnail to ask for). */
    val hasPicture: Boolean
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
    /** True when the album's photo is this video's picture (the worker draws it from the photo's message). */
    val albumPhoto: Boolean
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
    /** How many versions the card says (HX38, [VideoCopies.versionCount]): the same file posted twice counts once; 1 for a show. */
    val versions: Int
    val parts: Array<String>
    val pieces: Array<String>
    /**
     * What the numbers of [pieces] say (SplitPieces.report): how many there were, the first one
     * missing counted from 1 (0 when none is), and how many play from the first without a gap.
     * All 0 for a tile that is not a set of pieces.
     */
    val piecesTotal: Int
    val piecesFirstMissing: Int
    val piecesPlayable: Int
    val extra: String?
    val seasons: Array<WebSeason>

    /** The whole-season files of a show ("Season 2 Complete"), listed under their season; empty for a file. */
    val packs: Array<WebPack>
}

/** A season, or a span of seasons, posted as one file ([com.tmplayer.data.SeriesPack]). [seasonEnd] is 0 for one season. */
interface WebPack {
    val season: Int
    val seasonEnd: Int
    val video: Video
}

interface WebSeason {
    val number: Int
    val episodes: Array<WebEpisode>
    /** Episode numbers a longer file holds ("S01E09E10" holds 10), so the show's page does not list them as missing. */
    val covered: Array<Int>
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
    /** An IMDb id the post's own words give ("tt0111161"), or null; resolved by the worker's TMDB `find`. */
    val imdbId: String?
    val series: Boolean
    /** The name reads like fansub anime ("[Group] Show - 12", "Show - 12"): AniList is asked first (MetaQuery.anime). */
    val anime: Boolean
    /** ISO 639-1 codes, a hint for which of two titles of one name is meant. */
    val languages: Array<String>
    /**
     * The languages the chat's own title names (HX24, [noteChat] first), ISO 639-1: a weaker hint
     * than [languages], for the same case, never a reason to accept a title. Empty for most chats.
     */
    val hints: Array<String>
    /**
     * "S01E02" when the video is one episode of a show (not an extra), else null: a lone episode
     * on Home's carousel stands for its show and its Play names the episode (HomeFeatured.loneEpisode).
     */
    val episode: String?
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
    val defaultMin: Double
    val defaultMax: Double
    /** What "Hide small clips" hides below ([SizeFilter.CLIP_MIN]). */
    val clipMin: Double
    /** Home's own floor ([SizeFilter.HOME_MIN]). */
    val homeMin: Double
    /** The stored minimum the old default wrote (400 MB), which reads as "not set". */
    val oldDefaultMin: Double
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
    /** False for Recently added kept for the carousel alone, its row turned off in Settings: not drawn. */
    val asRow: Boolean
    /** Recently added reads chosen chats and none is chosen or left: a line in the row's place leads to Settings. */
    val noneChosen: Boolean
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
fun screen(raws: Array<RawVideo>): Screened = screenAlbums(raws, emptyArray())

/**
 * [screen] with the photos of the page's albums ([RawPhoto], found among its messages or by a
 * search): an album's photo gives its videos a caption where they have none and, where the
 * worker can draw it, their picture (`albumPhoto`). Same rules as the apps' [AlbumPosts].
 */
fun screenAlbums(raws: Array<RawVideo>, photos: Array<RawPhoto>): Screened {
    val screened = ContentProtection.screen(raws.toList(), { it.selfDestructs }, ::mapped)
    val picked = AlbumPosts.pick(photos.mapNotNull(::albumPhotoOf))
    val items = MediaText.shareAlbumCaptions(AlbumPosts.fold(screened.items, picked))
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
    val sorted = sortedEntries(split.kept, sort, watched, started, emptyArray(), emptyArray())
    return plain("tiles" to sorted.map(::tile).toTypedArray(), "hiddenBySize" to split.hidden)
}

/** [items] arranged into shows, copies and pieces, in [sort]'s order, with where the viewer is with each video. */
private fun sortedEntries(
    items: List<MediaItem>,
    sort: String,
    watched: Array<String>,
    started: Array<String>,
    touched: Array<String>,
    touchedAt: Array<Double>,
): List<ShelfEntry> {
    val done = watched.toHashSet()
    val going = started.toHashSet()
    val last = HashMap<String, Long>()
    for (i in 0 until minOf(touched.size, touchedAt.size)) last[touched[i]] = touchedAt[i].toLong()
    return VideoSorting.sort(SeriesShelf.arrange(items), VideoSort.decode(sort), progress = { item ->
        when (item.id) {
            in done -> VideoSorting.Progress.Watched
            in going -> VideoSorting.Progress.InProgress
            else -> VideoSorting.Progress.Untouched
        }
    }, locale = L.messages.locale, lastPlayed = { last[it.id] })
}

/**
 * A chat's listing with the Sort menu's whole Show and filter side (U6, [VideoFilter] and
 * [VideoFacets], the one pipeline the apps' grids run): size limits, then arranged, sorted
 * ([sort] also takes "LastPlayed", from [touched] and [touchedAt], when each video was last
 * watched), then screened by [filter] ("All", "Unwatched", "InProgress", "Watched", "OnDevice",
 * "Shows", "Singles", "HighDefinition"; anything else is "All") and by [facets], the encoded line
 * ([facetsWith]) the page keeps per chat. Like every order but Newest it works on the videos
 * loaded so far.
 */
fun shelfView(
    videos: Array<Video>,
    sort: String,
    filter: String,
    facets: String?,
    minBytes: Double,
    maxBytes: Double,
    watched: Array<String>,
    started: Array<String>,
    touched: Array<String>,
    touchedAt: Array<Double>,
    nowSec: Double,
): ShelfView {
    val split = SizeFilter.split(videos.map(::toItem), minBytes.toLong(), maxBytes.toLong()) { it.sizeBytes }
    val led = sortedEntries(split.kept, sort, watched, started, touched, touchedAt)
    val done = watched.toHashSet()
    val going = started.toHashSet()
    val progress: (MediaItem) -> VideoSorting.Progress = { item ->
        when (item.id) {
            in done -> VideoSorting.Progress.Watched
            in going -> VideoSorting.Progress.InProgress
            else -> VideoSorting.Progress.Untouched
        }
    }
    val parsed = VideoFacets.decode(facets)
    val faceted = VideoFaceting.screen(VideoFiltering.filter(led, VideoFilter.decode(filter), progress), parsed, nowSec.toLong())
    return plain(
        "tiles" to faceted.shown.map(::tile).toTypedArray(),
        "hiddenBySize" to split.hidden,
        "total" to led.size,
        "hiddenByFilter" to (led.size - faceted.shown.size),
        "unknown" to faceted.unknown,
        "languages" to VideoFaceting.languagesIn(led, keep = parsed.language).toTypedArray(),
    )
}

/** A chat's listing after [shelfView]: the tiles shown, and what the filters left out or could not judge. */
interface ShelfView {
    val tiles: Array<ShelfTile>
    val hiddenBySize: Int
    /** Entries before the filters, so "N hidden by your filter" is [hiddenByFilter]. */
    val total: Int
    val hiddenByFilter: Int
    /** Entries listed after the matches because nothing states their length or picture ([VideoFaceting.Screened.unknown]). */
    val unknown: Int
    /** The audio languages the loaded names state, most common first, for the Language group; the picked one is always in. */
    val languages: Array<String>
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
        "imdbId" to query.imdbId,
        "series" to item.parsedName.isSeries,
        "anime" to query.anime,
        "languages" to query.languages.toTypedArray(),
        "hints" to query.hints.toTypedArray(),
        "episode" to SeriesShelf.episodeOf(item).takeIf { it.isEpisode && it.extra == null && it.title.isNotBlank() }?.let { name ->
            name.episode?.let { "S${(name.season ?: 1).toString().padStart(2, '0')}E${it.toString().padStart(2, '0')}" }
        },
    )
}

/**
 * What a video's page says of it when nothing online does (MediaText.overviewOf): its caption with
 * the file's facts (File name, Size, Quality...) and lines repeating its name taken off, or null
 * when nothing is left.
 */
fun overviewOf(video: Video): String? = MediaText.overviewOf(video.caption, video.fileName, video.title)

/** The title a tile shows for [video], as the apps' cards do. */
fun tileTitle(video: Video): String = SeriesShelf.tileTitle(toItem(video))

/**
 * The name a Continue watching card writes under its picture (the apps' continueCardTitle): "S1 E3  ·  Harbour
 * Notes" for an episode, "Part 4  ·  Small shelf" for a lesson, the film's or the video's name otherwise.
 */
fun continueTitle(video: Video): String {
    val item = toItem(video)
    val parsed = SeriesShelf.episodeOf(item)
    // The half of a split episode keeps its letter ("S8 E6b"), so the two halves read apart.
    val half = parsed.episodePart?.let { ('a' + it - 1).toString() }.orEmpty()
    val episode = parsed.episode?.let { number -> (parsed.season?.let { L.seriesEpisodeShort(it, number) } ?: L.seriesEpisode(number)) + half }
    val (label, title) = if (episode != null) {
        val name = parsed.title.ifBlank { SeriesShelf.tileTitle(item) }
        // A file named by its episode alone ("Episode 1") is not given the label twice.
        if (name.isBlank() || episode.startsWith(name, ignoreCase = true)) null to episode else episode to name
    } else {
        val lesson = LessonSteps.lessonOf(item)
        if (lesson != null) {
            lesson.code to readable(lesson.course).ifBlank { SeriesShelf.tileTitle(item) }
        } else {
            null to if (SeriesShelf.isFilm(item)) SeriesShelf.posterTitle(item) else SeriesShelf.tileTitle(item)
        }
    }
    return listOfNotNull(label, title.ifBlank { null }).joinToString("  \u00B7  ")
}

/**
 * What a copy is called in a list of copies (the apps' copyHeading): its quality ("1080p", "4K", else
 * its size), then what its name says of the picture: "1080p", "H.264", "Blu-ray". Facts the name does not state are left out.
 */
fun copyFacts(video: Video): Array<String> {
    val item = toItem(video)
    val facts = MediaFacts.read(item.fileName.ifBlank { item.title }, item.width, item.height)
    val quality = VideoCopies.heightOf(item)?.let { item.copyQuality() }
    return listOfNotNull(quality, facts.videoCodec, facts.dynamicRange, facts.source).toTypedArray()
}

/**
 * The one pill a poster tile wears (FormatFacts.tileTag): "720p", "1080p", "1440p", "4K" or "8K" from
 * the size Telegram states, else a resolution word in the name; null for SD, an unknown size or a
 * name that says nothing, so no empty pill is drawn.
 */
fun tileTag(video: Video): String? = FormatFacts.tileTag(toItem(video))?.text

/** The quality a video is badged with on its page ("1080p", "4K"), or null when neither its name nor its size says (VideoCopies.qualityLabel). */
fun qualityLabel(video: Video): String? = VideoCopies.qualityLabel(toItem(video))

/** A course's name as a file name writes it ("small-shelf"), read as words ("Small shelf"). */
private fun readable(name: String): String {
    val words = name.replace(Regex("[._-]+"), " ").trim().replace(Regex("\\s+"), " ")
    return if (words == words.lowercase()) words.replaceFirstChar { it.titlecase() } else words
}

/** Indexes of [texts] that answer [query], best first; every index for a blank query. */
fun rank(texts: Array<String>, query: String): Array<Int> =
    Fuzzy.rank(texts.indices.toList(), query) { texts[it] }.toTypedArray()

/**
 * What to ask Telegram for a search inside one chat, in order ([com.tmplayer.data.ChatSearch.spellings]):
 * the words as typed, as words, joined by underscores and by dots as release names are, then the
 * longest words alone.
 */
fun chatSearchSpellings(query: String): Array<String> = com.tmplayer.data.ChatSearch.spellings(query).toTypedArray()

/** Whether [text] answers [query] with every word a prefix of one of its words, in any order. */
fun chatSearchMatches(text: String, query: String): Boolean = com.tmplayer.data.ChatSearch.matches(text, query)

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
    "defaultMin" to SizeFilter.DEFAULT_MIN.toDouble(),
    "defaultMax" to SizeFilter.DEFAULT_MAX.toDouble(),
    "clipMin" to SizeFilter.CLIP_MIN.toDouble(),
    "homeMin" to SizeFilter.HOME_MIN.toDouble(),
    "oldDefaultMin" to SizeFilter.OLD_DEFAULT_MIN.toDouble(),
)

/** A stored minimum read as the apps read it: nothing, zero and the old 400 MB default are 0. */
fun sizeEffectiveMin(stored: Double?): Double = SizeFilter.effectiveMin(stored?.toLong()).toDouble()

/** Home's floor for a viewer's minimum: its own 50 MB, or a higher custom floor. */
fun sizeHomeMin(minBytes: Double): Double = SizeFilter.homeMin(minBytes.toLong()).toDouble()

/** Whether a file of [bytes] passes; an unknown size (0) always does, the top means no limit. */
fun sizeMatches(bytes: Double, minBytes: Double, maxBytes: Double): Boolean =
    SizeFilter.matches(bytes.toLong(), minBytes.toLong(), maxBytes.toLong())

/** "Any size", "50 MB and up" and the like, in the provided catalog's words. */
fun sizeRange(minBytes: Double, maxBytes: Double): String = SizeFilter.describe(minBytes.toLong(), maxBytes.toLong())

/** "50 MB", "1.5 GB", as the apps label a size limit. */
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
    layout: WebHomeLayout,
    recentFrom: Array<Double>?,
    noneChosen: Boolean,
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
        // As both apps build it (MainActivity, desktop Home): one chat is enough for the row.
        recentMinStarred = 1,
        autoFill = true,
        chatsPending = chatsPending,
        layout = homeLayout(layout),
        recentFrom = recentFrom?.map { it.toLong() },
        noneChosen = noneChosen,
    )
    return rows.mapNotNull { row ->
        val base = arrayOf<Pair<String, Any?>>(
            "key" to row.key,
            "total" to row.total,
            "totalAtLeast" to row.totalAtLeast,
            "hasMore" to row.hasMore,
            "asRow" to ((row as? HomeRow.Recent)?.asRow ?: true),
            "noneChosen" to ((row as? HomeRow.Recent)?.noneChosen ?: false),
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
            // Not built where Home fills itself; the page builds its own lists' rows (lists.ts).
            is HomeRow.WatchLater, is HomeRow.NewEpisodes, is HomeRow.Listed -> null
        }
    }.toTypedArray()
}

/** Settings > Home as the page keeps it: each row's switch. */
interface WebHomeLayout {
    val carousel: Boolean
    val continueWatching: Boolean
    val recentlyAdded: Boolean
    val starredRows: Boolean
    /** The order of the rows as stored (HomeOrder): row keys, comma separated; missing or garbage is Home's own. */
    val order: String?
}

/** Which of Home's chats are read, and where Recently added comes from, as HomeViewModel picks them. */
interface HomePlan {
    /** The chats Home reads, the ones Recently added waits on first. */
    val picked: Array<Double>
    /** The chats Recently added reads alone (the added chats), or null for Home's own. */
    val recentFrom: Array<Double>?
    /** Added chats, and none added or none of them left: a line stands in the row's place. */
    val noneChosen: Boolean
    /** Recently added's search across chats is wanted at all (its row or the carousel is on). */
    val needsRecent: Boolean
    /** Rows of chats Home picks for itself (Automatic): the list is still on its way. */
    val autoPick: Boolean
}

private fun homeLayout(l: WebHomeLayout) = HomeLayout(
    carousel = l.carousel,
    continueWatching = l.continueWatching,
    recentlyAdded = l.recentlyAdded,
    starredRows = l.starredRows,
    order = HomeOrder.parse(l.order),
)

/**
 * Home's chats as HomeViewModel picks them (2026-11-01): [added] is the viewer's Add chats choice
 * (HX27), null when nothing was picked. Picked, Recently added reads the added chats alone, the
 * first [HomeLayout.CHOSEN_MAX] in [chats]' order; otherwise Home picks its own chats as before. A
 * starred chat is read for a row of its own only while that switch is on. [listWhole] says the
 * chat list is in, so an added chat missing from it is gone rather than still on its way.
 */
fun homePlan(chats: Array<WebChat>, starred: Array<Double>, recent: Array<Video>?, layout: WebHomeLayout, added: Array<Double>?, listWhole: Boolean): HomePlan {
    val l = homeLayout(layout)
    val addedIds = added?.map { it.toLong() }?.filterTo(LinkedHashSet()) { it != 0L }
    val list = chats.map(::summary)
    val favourites = starred.map { it.toLong() }.toSet()
    val autoPick = l.needsRecent && addedIds == null
    val chosen = l.needsRecent && addedIds != null
    val rowChats = if (l.starredRows || (l.needsRecent && !chosen)) HomeRows.favouriteChats(list, favourites) else emptyList()
    val chosenChats = if (chosen && addedIds != null) HomeLayout.chosenChats(list, addedIds) else emptyList()
    val picked = when {
        autoPick -> HomeRows.homeChats(list, favourites, recent?.map(::toItem))
        chosen -> (chosenChats + rowChats).distinctBy { it.id }
        else -> rowChats
    }
    val recentFrom = if (chosen) chosenChats.map { it.id.toDouble() }.toTypedArray() else null
    return plain(
        "picked" to picked.map { it.id.toDouble() }.toTypedArray(),
        "recentFrom" to recentFrom,
        "noneChosen" to (chosen && (addedIds.isNullOrEmpty() || (listWhole && recentFrom.isNullOrEmpty()))),
        "needsRecent" to l.needsRecent,
        "autoPick" to autoPick,
    )
}

/** The added chats Home reads, ids only, at most [HomeLayout.CHOSEN_MAX]: for the worker to look up any the chat list has not reached. */
fun homeChosenMax(): Int = HomeLayout.CHOSEN_MAX

private fun summary(chat: WebChat) = ChatSummary(
    id = chat.id.toLong(),
    title = chat.title,
    miniThumbnail = null,
    photoFileId = 0,
    kind = if (chat.saved) ChatKind.Saved else ChatKind.Group,
    isPinned = chat.pinned,
)

/**
 * Whether a document is a video kept in a ZIP (one release-named .zip as large as a video, or the
 * pieces of a split one), which the chat lists as a video and plays out of the archive, and not as
 * an archive for the listing's foot. MediaText.looksLikeVideoArchive.
 */
fun looksLikeVideoArchive(fileName: String, size: Double): Boolean =
    MediaText.looksLikeVideoArchive(fileName, size.toLong())

/** Whether [fileName] (or [mimeType]) is an archive, and what kind, as a chat lists it; null when not. */
fun archive(fileName: String, mimeType: String?): Archive? {
    val kind: ArchiveKind = Archives.kindOf(fileName, mimeType.orEmpty()) ?: return null
    val volume = Archives.isVolume(fileName)
    return plain(
        "kind" to kind.label,
        "volume" to volume,
        "reason" to Archives.reason(
            ArchiveFile(0, 0, 0, fileName, 0, 0, kind, volume = volume, locked = Archives.looksLocked(fileName)),
        ),
    )
}

/** MediaMapper's rules, for a message the worker read instead of TDLib. */
private fun mapped(raw: RawVideo): MediaItem? {
    if (raw.withheld) return null
    val name = raw.fileName.orEmpty()
    val mime = raw.mimeType.orEmpty()
    val round = raw.kind == "round"
    if (raw.kind == "document" && !MediaText.looksLikeVideo(name, mime) && !MediaText.looksLikeVideoArchive(name, raw.size.toLong())) return null
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

/** The apps' [AlbumPhoto] for a photo the worker read: its file is only known to the worker, so any picture is file 1. */
private fun albumPhotoOf(raw: RawPhoto): AlbumPhoto? {
    val album = raw.albumId?.toLongOrNull() ?: return null
    if (album == 0L) return null
    return AlbumPhoto(
        chatId = raw.chatId.toLong(),
        albumId = album,
        messageId = raw.messageId.toLong(),
        fileId = if (raw.hasPicture) 1 else 0,
        mini = null,
        caption = MediaText.captionOf(raw.caption),
    )
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
    albumPhotoFileId = if (video.albumPhoto) 1 else 0,
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
    "albumPhoto" to (item.albumPhotoFileId != 0),
    "canBeSaved" to item.canBeSaved,
    "round" to MediaText.isRound(item),
)

private fun ids(items: List<MediaItem>): Array<String> = items.map { it.id }.toTypedArray()

private fun tile(entry: ShelfEntry): ShelfTile = when (entry) {
    is ShelfEntry.Show -> show(entry.key, entry.series)
    is ShelfEntry.File -> {
        val item = entry.item
        val report = SplitPieces.report(item)
        plain(
            "key" to entry.key,
            "kind" to "file",
            "title" to SeriesShelf.tileTitle(item),
            "video" to toWeb(item),
            "copies" to ids(item.versions),
            "versions" to VideoCopies.versionCount(item, null),
            "parts" to ids(item.parts),
            "pieces" to ids(item.pieces),
            "piecesTotal" to (report?.total ?: 0),
            "piecesFirstMissing" to (report?.firstMissing ?: 0),
            "piecesPlayable" to (report?.playable ?: 0),
            "extra" to item.extra?.name,
            "seasons" to emptyArray<WebSeason>(),
            "packs" to emptyArray<WebPack>(),
        )
    }
}

private fun show(key: String, series: Series): ShelfTile = plain(
    "key" to key,
    "kind" to "show",
    "title" to series.title,
    "video" to toWeb(series.cover),
    "copies" to emptyArray<String>(),
    "versions" to 1,
    "parts" to emptyArray<String>(),
    "pieces" to emptyArray<String>(),
    "piecesTotal" to 0,
    "piecesFirstMissing" to 0,
    "piecesPlayable" to 0,
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
            "covered" to EpisodeGuide.covered(season).toTypedArray(),
        )
    }.toTypedArray(),
    "packs" to series.packs.map { pack ->
        plain<WebPack>("season" to pack.season, "seasonEnd" to (pack.seasonEnd ?: 0), "video" to toWeb(pack.item))
    }.toTypedArray(),
)

private fun label(tag: EpisodeTag): EpisodeLabel = plain(
    "code" to tag.code,
    "name" to tag.name,
    "show" to tag.show,
    "season" to tag.season,
    "episode" to tag.episode,
)

// ---- subtitle files posted in the chat (EG-F48) ---------------------------------------------

/** A document of the chat as the worker read it, for [chatSubtitles]. Ids are the browser client's, not TDLib's. */
interface ChatDoc {
    val messageId: Double
    val fileName: String?
    val size: Double
    /** Seconds since the epoch. */
    val date: Double
    /** The album (grouped_id) as a decimal string, or null. */
    val albumId: String?
    /** The message this one replies to, or 0. */
    val replyToMessageId: Double
}

/** One file of the list: [index] is its place in the documents handed in. */
interface ChatSubtitleHit {
    val index: Int
    /** "Album", "Reply", "Neighbour" or "Name". */
    val relation: String
    /** The two letter code its name gives, or null. */
    val language: String?
    val forced: Boolean
    val hearing: Boolean
    /** A zip, whose entries are read when it is chosen. */
    val archive: Boolean
}

/**
 * ChatSubtitles.rank for the web player: which of [docs] (the chat's documents around the video,
 * from one search) belong to the video, best first, at most ten. The browser client's message ids
 * are Telegram's own; they are shifted the way TDLib's are so the neighbour rule reads the same.
 */
fun chatSubtitles(
    videoName: String,
    caption: String?,
    messageId: Double,
    date: Double,
    albumId: String?,
    replyToMessageId: Double,
    docs: Array<ChatDoc>,
    preferred: Array<String>,
): Array<ChatSubtitleHit> {
    fun tdlib(id: Double): Long = id.toLong() shl 20
    val video = com.tmplayer.player.ChatSubtitles.Video(
        messageId = tdlib(messageId),
        fileName = videoName,
        caption = caption.orEmpty(),
        date = date.toInt(),
        albumId = albumId?.toLongOrNull() ?: 0L,
        replyToMessageId = if (replyToMessageId > 0) tdlib(replyToMessageId) else 0L,
    )
    val list = docs.mapIndexed { index, d ->
        com.tmplayer.player.ChatSubtitles.Doc(
            messageId = tdlib(d.messageId),
            fileId = index,
            fileName = d.fileName.orEmpty(),
            size = d.size.toLong(),
            date = d.date.toInt(),
            albumId = d.albumId?.toLongOrNull() ?: 0L,
            replyToMessageId = if (d.replyToMessageId > 0) tdlib(d.replyToMessageId) else 0L,
        )
    }
    return com.tmplayer.player.ChatSubtitles.rank(video, list, preferred.toList()).map { hit ->
        plain<ChatSubtitleHit>(
            "index" to hit.doc.fileId,
            "relation" to hit.relation.name,
            "language" to hit.language,
            "forced" to hit.forced,
            "hearing" to hit.hearing,
            "archive" to hit.archive,
        )
    }.toTypedArray()
}

// ---- one quality read, recommended copy first, versions (HX38) ----------------------------------

/** What picture a copy is, read one way for the badge, the list and the order ([QualityRead]). */
interface WebQuality {
    /** "4K", "1080p", "720p", "SD", or null when neither the stated size nor the name says. */
    val label: String?
    /** The picture's height as the copies list reads it (2160 for 4K), or null. */
    val height: Int?
    /** The name says one picture over a file stated as another: the size is right, the flag says the name was not. */
    val mismatch: Boolean
    /** A cam, a telesync or a pre-DVD rip by its name: never recommended where a better copy exists. */
    val lowGrade: Boolean
}

fun qualityRead(video: Video): WebQuality {
    val item = toItem(video)
    return plain(
        "label" to QualityRead.of(item),
        "height" to VideoCopies.heightOf(item),
        "mismatch" to QualityRead.mismatch(item),
        "lowGrade" to QualityRead.lowGrade(item),
    )
}

/**
 * The kind of device the copies are recommended for: "phone" (a screen that shows 1080p in full,
 * so a 4K copy is offered but does not lead), "tv" or "computer". Anything else is left as it was.
 */
fun setCopyDevice(device: String) {
    if (device == "phone" || device == "tv" || device == "computer") VideoCopies.deviceWord = device
}

/**
 * [copies] in the order a list of copies shows them, recommended first ([GlobalSearch.copyOrder]),
 * as ids: not a cam, one this browser plays smoothly (the ids of [slow] are the copies it cannot
 * play natively, which the page knows and the rules do not), the viewer's audio [language]
 * ("ta"), the picture the device suits, the newest.
 */
fun copyOrder(copies: Array<Video>, language: String?, slow: Array<String>): Array<String> {
    val tooSlow = slow.toHashSet()
    return GlobalSearch.copyOrder(copies.map(::toItem), language, { it.id in tooSlow }).map { it.id }.toTypedArray()
}

/** The copy nothing else settles (no pick, nothing started, no habit): [VideoCopies.bestForDevice], its id or null when no copy states its picture. */
fun bestCopy(copies: Array<Video>, slow: Array<String>): String? {
    val tooSlow = slow.toHashSet()
    return VideoCopies.bestForDevice(copies.map(::toItem), { it.id in tooSlow })?.id
}

/**
 * The copy Play opens ([VideoCopies.pick]): the one the viewer [chosen] for this video, else the
 * one they [started], else the audio [language], else the picture nearest what they usually play
 * ([habit], from [habitNoted]), else the best for the device. [slow] as for [copyOrder]; the
 * viewer's own pick stands whatever it is.
 */
fun pickCopy(
    copies: Array<Video>,
    chosen: String?,
    started: Array<String>,
    habit: String?,
    language: String?,
    slow: Array<String>,
): String? {
    val going = started.toHashSet()
    val tooSlow = slow.toHashSet()
    return VideoCopies.pick(
        copies.map(::toItem),
        chosen = chosen,
        started = { it.id in going },
        habit = QualityHabit.decode(habit),
        language = language,
        tooSlow = { it.id in tooSlow },
    )?.id
}

/** The habit line ("1080:12,720:3") with one more play at [height] noted; what the page stores as the usual quality. */
fun habitNoted(habit: String?, height: Int?): String = QualityHabit.decode(habit).noted(height).encode()

/**
 * How many versions a card says for [copies] of one video: the distinct files, 1 for one copy
 * ([VideoCopies.versionCount]). The null gate is on purpose: GlobalSearch.fileCount and
 * VideoCopies.oneOfEachFile use the default ChatGate.browsing and must not be exported to JS, or
 * ChatGate and its coroutines return to the worker (+100 KB gzip).
 */
fun versionCount(copies: Array<Video>): Int =
    if (copies.size < 2) 1 else VideoCopies.distinctFiles(copies.map(::toItem), null).size.coerceAtLeast(1)

// ---- the chat's language as a hint for the poster lookup (HX24) -----------------------------------

/** Notes [title] as chat [chatId]'s name: [posterAsk] then carries its language words as `hints`. A rename replaces the note. */
fun noteChat(chatId: Double, title: String) = ChatLanguageHints.note(chatId.toLong(), title)

/** The language words in a chat's title ("Malayalam Movies HD" gives "ml"), ISO 639-1. */
fun chatLanguages(title: String): Array<String> = MediaName.languageHints(title).toTypedArray()

/** Forgets every chat's title (signing out). */
fun forgetChats() = ChatLanguageHints.clear()

// ---- what the viewer corrected by hand: not the same video, a rename, a poster pick -----------------

/** One video's correction as [correctionRead] reads the stored lines. */
interface WebCorrection {
    /** "Not the same video": never folded with another file. */
    val separate: Boolean
    /** "Wrong match": no poster or overview from the providers. */
    val wrong: Boolean
    /** The viewer's own title, or null. */
    val title: String?
    /** The picked TMDB title as "film:123" or "show:5", or null. */
    val pick: String?
}

/** A correction's stored lines (`separate`, `wrong`, `tmdb=film:123`, `title=...`) read; garbage reads as no correction. */
fun correctionRead(text: String?): WebCorrection {
    val c = VideoCorrection.decode(text)
    return plain(
        "separate" to c.separate,
        "wrong" to c.wrongMatch,
        "title" to c.title,
        "pick" to c.pick?.let { (if (it.show) "show" else "film") + ":" + it.tmdbId },
    )
}

private val PICK_LINE = Regex("""^(film|show):(\d{1,9})$""")

/**
 * [text]'s correction with one [field] changed, as the stored lines ("" when nothing is left to
 * keep): "separate" and "wrong" on for [value] "1", off otherwise; "title" to [value] (blank or
 * null removes it, cut to [VideoCorrections.TITLE_MAX]); "pick" to "film:123" or "show:5" (anything
 * else removes it; a pick lifts "wrong", turning "wrong" on drops the pick); "resetMatch" drops
 * both. Other fields change nothing.
 */
fun correctionSet(text: String?, field: String, value: String?): String {
    val c = VideoCorrection.decode(text)
    val next = when (field) {
        "separate" -> c.copy(separate = value == "1")
        "wrong" -> c.withWrongMatch(value == "1")
        "title" -> c.copy(title = value?.trim()?.take(VideoCorrections.TITLE_MAX)?.trim()?.ifEmpty { null })
        "pick" -> c.withPick(
            PICK_LINE.matchEntire(value.orEmpty().trim())?.let { found ->
                val id = found.groupValues[2].toIntOrNull()?.takeIf { it > 0 } ?: return@let null
                PosterPick(id, show = found.groupValues[1] == "show")
            },
        )
        "resetMatch" -> c.resetMatch()
        else -> c
    }
    return next.encode()
}

/**
 * Hands the corrections on this device to the grouping: [ids] are "chatId:messageId", [texts] the
 * stored lines. [shelf], [shelfView] and the rest group by them from here on ("Not the same
 * video" splits a wrong group, a rename names it). Replaces what was set before.
 */
fun setCorrections(ids: Array<String>, texts: Array<String>) {
    val all = HashMap<String, VideoCorrection>()
    for (i in 0 until minOf(ids.size, texts.size)) {
        val correction = VideoCorrection.decode(texts[i])
        if (!correction.isEmpty) all[ids[i]] = correction
    }
    VideoCorrections.set(all)
}

// ---- the Sort menu's filters, "Play random" (U6, U16) -------------------------------------------------

/** The Sort menu's filters by what a video is ([VideoFacets]), as the page draws their chips. */
interface WebFacets {
    /** "any", "short" (under 20 minutes) or "long" (an hour or more). */
    val length: String
    /** "any", "4k", "1080" or "720": at least that sharp. */
    val picture: String
    val hdr: Boolean
    val subtitles: Boolean
    /** An ISO 639-1 code, or null for any language. */
    val language: String?
    /** "any", "week", "month" or "year". */
    val added: String
    /** How many filters are on, for the Sort button's badge. */
    val count: Int
}

/** The filters in a chat's stored line ("len=short;pic=1080;hdr=1"); each unreadable part is that filter off, never the whole line lost. */
fun facetsRead(line: String?): WebFacets {
    val f = VideoFacets.decode(line)
    return plain(
        "length" to f.length.code,
        "picture" to f.picture.code,
        "hdr" to f.hdr,
        "subtitles" to f.subtitles,
        "language" to f.language,
        "added" to f.added.code,
        "count" to f.count,
    )
}

/**
 * [line] with one filter changed, as the stored line: [field] "length", "picture", "added" take a
 * code of [facetsRead] (an unknown one is off), "hdr" and "subtitles" take "1" for on, "language"
 * takes a code or null for any. "clear" drops every filter.
 */
fun facetsWith(line: String?, field: String, value: String?): String {
    val f = VideoFacets.decode(line)
    val next = when (field) {
        "length" -> f.copy(length = VideoFacets.Length.entries.firstOrNull { it.code == value } ?: VideoFacets.Length.Any)
        "picture" -> f.copy(picture = VideoFacets.Picture.entries.firstOrNull { it.code == value } ?: VideoFacets.Picture.Any)
        "added" -> f.copy(added = VideoFacets.Added.entries.firstOrNull { it.code == value } ?: VideoFacets.Added.Any)
        "hdr" -> f.copy(hdr = value == "1")
        "subtitles" -> f.copy(subtitles = value == "1")
        "language" -> f.copy(language = value?.lowercase()?.takeIf { it.length in 2..3 && it.all { c -> c in 'a'..'z' } })
        "clear" -> VideoFacets.NONE
        else -> f
    }
    return next.encode()
}

/** The Show filters in the order the menu lists them; "HighDefinition" only where it is already picked ([VideoFilter]). */
fun videoFilters(): Array<String> = VideoFilter.entries.map { it.name }.toTypedArray()

/** The orders in the order the menu lists them ([VideoSort]). */
fun videoSorts(): Array<String> = VideoSort.entries.map { it.name }.toTypedArray()

/**
 * Which of [videos] "Play random" plays (U16, [RandomPick]): unwatched while any is left ([watched]
 * are ids), never [last] again while anything else is, null for an empty list. Returns an id.
 */
fun playRandom(videos: Array<Video>, watched: Array<String>, last: String?): String? {
    val done = watched.toHashSet()
    return RandomPick.pick(videos.toList(), { it.id }, { it.id in done }, last)?.id
}

// ---- speed, per series, chat and topic (CP48) ------------------------------------------------------

/** Where one video's speed is remembered ([SpeedMemory.Scopes]): [series] for a show or a course, [chat] for the chat or topic. */
interface WebSpeedScopes {
    val series: String?
    val chat: String?
    /** Where a newly picked speed is written: the narrowest place the video belongs to. */
    val target: String?
    /** Every scope the video reads from, narrowest first, which choosing 1x forgets. */
    val all: Array<String>
}

fun speedScopes(video: Video): WebSpeedScopes {
    val item = toItem(video)
    val key = MediaName.parse(item.title).title.ifBlank { item.title }
    val scopes = SpeedMemory.scopesOf(item, key)
    return plain(
        "series" to scopes.series,
        "chat" to scopes.chat,
        "target" to scopes.target,
        "all" to scopes.all.toTypedArray(),
    )
}

/** The name a scope's speed is stored under (the apps' `speed_...`). */
fun speedStoreKey(scope: String): String = StoredKeys.speed(scope)

/** The speed to open at: [file], else [series], else [chat], else 1 ([SpeedMemory.resolve]); a value that cannot be a speed is passed over. */
fun speedResolve(file: Double?, series: Double?, chat: Double?): Double =
    twoDecimals(SpeedMemory.resolve(file?.toFloat(), series?.toFloat(), chat?.toFloat()))

/** What to store for a picked [value]: the speed kept to two decimals within what a player can do, or null for 1x (written as nothing). */
fun speedKeep(value: Double): Double? = SpeedMemory.toKeep(value.toFloat())?.let(::twoDecimals)

/** A speed the rules hold as a Float, as the exact two decimal number the page compares and stores (1.1f would widen to 1.100000023841858). */
private fun twoDecimals(speed: Float): Double = (speed.toDouble() * 100).roundToInt() / 100.0

// ---- Settings' map and its search (U20) --------------------------------------------------------------

/** One row of Settings as the map lists it, in the provided catalog's words. [id] is the same on every form. */
interface WebSettingsRow {
    val id: String
    /** The page it lives on ([SettingsGroup]'s name: "Playback", "Subtitles", "Storage", ...). */
    val group: String
    val groupTitle: String
    val groupSummary: String
    val title: String
    /** The lines it can show under it. */
    val details: Array<String>
    /** What it needs before it is drawn ([com.tmplayer.data.SettingsNeed]'s name), or null. */
    val need: String?
}

private fun formOf(name: String): DeviceForm = when (name) {
    "phone" -> DeviceForm.Phone
    "tv" -> DeviceForm.Tv
    else -> DeviceForm.Desktop
}

private fun settingsText(form: DeviceForm): (String) -> String = { key ->
    val word = form.name.lowercase()
    L.messages.format(key, mapOf("device" to word, "form" to word))
}

/** The rows [form] ("phone", "tv" or "desktop") has, by page in the order the list shows them, in the provided catalog's words. */
fun settingsRows(form: String): Array<WebSettingsRow> {
    val on = formOf(form)
    val text = settingsText(on)
    return SettingsMap.on(on).map { row ->
        plain<WebSettingsRow>(
            "id" to row.id,
            "group" to row.group.name,
            "groupTitle" to runCatching { text(row.group.title) }.getOrDefault(""),
            "groupSummary" to runCatching { text(row.group.summary) }.getOrDefault(""),
            "title" to runCatching { text(row.titleOn(on)) }.getOrDefault(""),
            "details" to row.details.mapNotNull { runCatching { text(it) }.getOrNull()?.takeIf(String::isNotBlank) }.toTypedArray(),
            "need" to row.need?.name,
        )
    }.toTypedArray()
}

/**
 * The ids of the rows [form] has that answer [query], best first, at most [SettingsSearch.MAX_HITS]
 * (every word typed found in a row's title, its lines, its page or the other words people type for
 * it; nothing for a blank query). [only] limits the search to the rows a page actually draws.
 */
fun settingsSearch(query: String, form: String, only: Array<String>?): Array<String> {
    val on = formOf(form)
    val allowed = only?.toHashSet()
    val rows = SettingsMap.on(on).filter { allowed == null || it.id in allowed }
    return SettingsSearch.match(query.take(SETTINGS_QUERY_MAX), rows, on, settingsText(on)).map { it.row.id }.toTypedArray()
}

private const val SETTINGS_QUERY_MAX = 80

/** One heading of the "What's new" sheet and the lines under it. */
interface WebWhatsNewSection {
    val title: String
    val lines: Array<String>
}

/**
 * What the apps' "What's new" sheet says: the release its highlights describe, the full changelog's
 * address and the lines in the language the rules speak, for the web's own form (no remote keys, no
 * desktop window): grouped under headings in [sections], flat in [highlights].
 */
interface WebWhatsNew {
    val version: String
    val changelog: String
    val highlights: Array<String>
    val sections: Array<WebWhatsNewSection>
}

fun whatsNew(): WebWhatsNew = plain(
    "version" to ReleaseHighlights.VERSION,
    "changelog" to ReleaseHighlights.CHANGELOG,
    "highlights" to ReleaseHighlights.highlightsFor(ReleaseHighlights.Form.Web).toTypedArray(),
    "sections" to ReleaseHighlights.sectionsFor(ReleaseHighlights.Form.Web).map {
        plain<WebWhatsNewSection>("title" to it.title, "lines" to it.lines.toTypedArray())
    }.toTypedArray(),
)

// ---- Search by topic name (U14) ---------------------------------------------------------------------

/** What one search by topic name may ask of Telegram ([TopicSearch]'s budget), so the worker holds to the apps' numbers. */
interface WebTopicBudget {
    /** Groups with topics asked per search, at most. */
    val chats: Double
    /** Of those, asked at the same time. */
    val atOnce: Double
    /** Topics read per group. */
    val topicsPerChat: Double
    /** Hits listed at most. */
    val maxHits: Double
}

fun topicBudget(): WebTopicBudget = plain(
    "chats" to TopicSearch.CHATS_PER_SEARCH.toDouble(),
    "atOnce" to TopicSearch.AT_ONCE.toDouble(),
    "topicsPerChat" to TopicSearch.TOPICS_PER_CHAT.toDouble(),
    "maxHits" to TopicSearch.MAX_HITS.toDouble(),
)

/** A group with topics turned on, as the chat list knows it. */
interface WebTopicChat {
    val id: Double
    val title: String
    val archived: Boolean
}

/**
 * The groups worth asking for [query] ([TopicSearch.candidates]), best first, at most the budget's:
 * only [chats] (the page passes the groups with topics), only the [added] ones (null: every chat),
 * those whose own title answers the words first. Archived ones only with [includeArchived].
 */
fun topicCandidates(chats: Array<WebTopicChat>, query: String, added: Array<Double>?, includeArchived: Boolean): Array<Double> {
    val allowed = added?.map { it.toLong() }?.toHashSet()
    val summaries = chats.map {
        ChatSummary(id = it.id.toLong(), title = it.title, miniThumbnail = null, photoFileId = 0, kind = ChatKind.Group, isArchived = it.archived)
    }
    return TopicSearch.candidates(summaries, query, { allowed == null || it in allowed }, { true }, includeArchived)
        .take(TopicSearch.CHATS_PER_SEARCH).map { it.id.toDouble() }.toTypedArray()
}

/** One topic of a group, as Telegram listed it. */
interface WebTopic {
    val id: Double
    val name: String
    val pinned: Boolean
    val closed: Boolean
    val hasVideos: Boolean
}

/** A topic that answers the words, listed as "Chat > Topic". */
interface WebTopicHit {
    val chatId: Double
    val chatTitle: String
    val topicId: Double
    val name: String
    val pinned: Boolean
    val closed: Boolean
    val hasVideos: Boolean
    val score: Double
}

private fun hitOf(h: TopicHit): WebTopicHit = plain(
    "chatId" to h.chatId.toDouble(),
    "chatTitle" to h.chatTitle,
    "topicId" to h.topic.id.toDouble(),
    "name" to h.topic.name,
    "pinned" to h.topic.isPinned,
    "closed" to h.topic.isClosed,
    "hasVideos" to h.topic.hasVideos,
    "score" to h.score.toDouble(),
)

private fun hitFrom(h: WebTopicHit) = TopicHit(
    chatId = h.chatId.toLong(),
    chatTitle = h.chatTitle,
    topic = ForumTopicSummary(id = h.topicId.toInt(), name = h.name, isPinned = h.pinned, isClosed = h.closed, hasVideos = h.hasVideos),
    score = h.score.toInt(),
)

/** How well a topic [name] answers [query] (every word must land, a typo forgiven), or 0. */
fun topicScore(name: String, query: String): Double = TopicSearch.score(name, query).toDouble()

/** The [topics] of one group that answer [query] ([TopicSearch.hits]); [fromTelegram] keeps one our folding misses at the lowest score. */
fun topicHits(chatId: Double, chatTitle: String, topics: Array<WebTopic>, query: String, fromTelegram: Boolean): Array<WebTopicHit> =
    TopicSearch.hits(
        chatId.toLong(),
        chatTitle,
        topics.filter { it.id.isFinite() }.map { ForumTopicSummary(id = it.id.toInt(), name = it.name, isPinned = it.pinned, isClosed = it.closed, hasVideos = it.hasVideos) },
        query,
        fromTelegram,
    ).map(::hitOf).toTypedArray()

/** [hits] best first, one per chat and topic, at most the budget's ([TopicSearch.rank]); [chatOrder] is the chat list's order, by id. */
fun topicRank(hits: Array<WebTopicHit>, chatOrder: Array<Double>): Array<WebTopicHit> {
    val order = HashMap<Long, Int>()
    chatOrder.forEachIndexed { i, id -> order.getOrPut(id.toLong()) { i } }
    return TopicSearch.rank(hits.map(::hitFrom), order).map(::hitOf).toTypedArray()
}

// ---- Telegram links (U9) -----------------------------------------------------------------------------

/**
 * The link to a post as Telegram's apps copy it ([TgLink.Post]): `t.me/<username>/<id>` for a
 * public chat, `t.me/c/<id>/<id>` for a private channel or supergroup (its Bot API id, -100...,
 * with the prefix off), with a forum [topicId] between and `?t=` for [startSec] when it is above 0.
 * Null for a chat that has neither (a person, a small group), or an id that cannot be one.
 */
fun tgPostLink(username: String?, chatId: Double, topicId: Double, messageId: Double, startSec: Double): String? {
    val message = messageId.takeIf { it.isFinite() && it >= 1 }?.toLong() ?: return null
    val topic = topicId.takeIf { it.isFinite() && it >= 1 }?.toLong() ?: 0L
    val start = startSec.takeIf { it.isFinite() && it >= 1 }?.toInt() ?: 0
    val name = username?.trim()?.removePrefix("@")?.takeIf { it.isNotEmpty() }
    if (name != null) return TgLink.Post(name, null, message, topic, start).url
    val chat = chatId.takeIf { it.isFinite() }?.toLong() ?: return null
    if (chat >= CHANNEL_ID_BASE) return null
    return TgLink.Post(null, CHANNEL_ID_BASE - chat, message, topic, start).url
}

/** A Bot API id of -100 and the channel's own number: the base to take it from. */
private const val CHANNEL_ID_BASE = -1_000_000_000_000L

// ---- the rest the later tasks read ------------------------------------------------------------------------

/** The keys a video's remembered online subtitle is filed under, most exact first (HX25, [SubtitlePickKeys]). */
fun subtitlePickKeys(video: Video): Array<String> = SubtitlePickKeys.of(toItem(video)).toTypedArray()

/**
 * The one definition of a real stutter ([PlaybackWatch.symptomOf]): "slow", "dropped", "underruns",
 * "late", or null when nothing the viewer would see happened. [speed] is the position's pace against
 * the speed asked for (1 is on time). Garbage in reads as no evidence.
 */
fun stutterSymptom(speed: Double, dropped: Double, frames: Double, underruns: Double, late: Boolean): String? =
    PlaybackWatch.symptomOf(speed, intOf(dropped), intOf(frames), intOf(underruns), late)

/** How long after a start, seek or interruption playback is not read (the decoder is warming up), in milliseconds. */
fun stutterSettleMs(): Double = PlaybackWatch.SETTLE_MS.toDouble()

/** How long a stutter watch counts playing time before it answers, in milliseconds. */
fun stutterWindowMs(): Double = PlaybackWatch.WINDOW_MS.toDouble()

private fun intOf(value: Double): Int = if (value.isFinite()) value.coerceIn(0.0, 2_000_000_000.0).toInt() else 0

/** One language the apps ship a catalog for. */
interface WebLanguage {
    val tag: String
    /** The language's own name ("Deutsch"). */
    val name: String
    val rtl: Boolean
}

/** The languages the apps ship, English first. */
fun languageList(): Array<WebLanguage> =
    Languages.all.map { plain<WebLanguage>("tag" to it.tag, "name" to it.name, "rtl" to it.rtl) }.toTypedArray()

/** The catalog to use for the viewer's [saved] choice ("" or null follows [system], the browser's languages): a shipped tag, English as the last resort. */
fun languageResolve(saved: String?, system: Array<String>): String = Languages.resolve(saved, system.toList())

/** Makes [tag], a shipped catalog [provideCatalog] was given, the language the rules speak (plurals, numbers, names). */
fun useLanguage(tag: String) = Translator.use(tag)

// ---- the player's keyboard (WP-28, WP-61) ------------------------------------------------------------

/** What a key does in the player ([PlayerKeyMap.Action]): [kind] is [PlayerKeyMap.Kind.id], [value] and [flag] as the kind says. */
interface WebKeyAction {
    val kind: String
    val value: Int
    val flag: Boolean
}

/**
 * [PlayerKeyMap.actionFor] for a browser key: [code] is `KeyboardEvent.code`, [key] its `key`.
 * Null for a key the player leaves to the page and the browser. One table for the web, the tablets
 * and the Chromebooks, so no page ports it by hand.
 */
fun playerKeyAction(
    code: String,
    key: String,
    shift: Boolean,
    ctrl: Boolean,
    alt: Boolean,
    meta: Boolean,
    mac: Boolean,
    chapters: Boolean,
    paused: Boolean,
    speedNormal: Boolean,
    fullscreen: Boolean,
    skipShown: Boolean,
): WebKeyAction? {
    val action = PlayerKeyMap.actionFor(
        PlayerKeyMap.Press(code, key, shift, ctrl, alt, meta),
        PlayerKeyMap.Context(mac, chapters, paused, speedNormal, fullscreen, skipShown),
    ) ?: return null
    return plain("kind" to action.kind.id, "value" to action.value, "flag" to action.flag)
}

/** A plain JS object of [fields], typed as the exported interface it stands for. */
private fun <T> plain(vararg fields: Pair<String, Any?>): T {
    val o: dynamic = js("({})")
    for ((name, value) in fields) o[name] = value
    return o.unsafeCast<T>()
}
