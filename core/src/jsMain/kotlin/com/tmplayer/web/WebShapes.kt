@file:OptIn(ExperimentalJsExport::class)
@file:JsExport

package com.tmplayer.web

import com.tmplayer.data.AlbumPosts
import com.tmplayer.data.VideoCopies
import com.tmplayer.data.VideoFaceting

// The plain-object shapes the web worker hands in and gets back (see WebRules.kt for the rules).

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
