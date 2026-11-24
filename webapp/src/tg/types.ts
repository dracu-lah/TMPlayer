// What the Telegram worker hands the page: plain data only. File locations, access hashes and file
// references stay in the worker; the page names a file by its key.
import type { PosterAsk } from '../rules'

export interface Me {
    id: number
    /** Null until Telegram has been asked. */
    name: string | null
    /** The public @username, without the @; null where there is none or Telegram has not been asked. */
    username?: string | null
}

/** A chat's info page: what the apps' ChatInfoSheet shows. Asked for only when the page opens. */
export interface ChatInfo {
    title: string
    kind: 'saved' | 'channel' | 'group' | 'user' | 'bot'
    /** Key of the large picture, for `thumb`; null where there is none. */
    photo: string | null
    /** Members or subscribers; null where Telegram does not say. */
    members: number | null
    username: string | null
    /** A person's bio, or a group's or channel's description. */
    about: string | null
    /** t.me/<username> for a public chat. */
    link: string | null
    /** The details could not be read (the name, kind and picture are still the list's). */
    failed: boolean
}

/** A file the page can ask the worker for, by key. */
export interface FileRef {
    key: string
    size: number
}

/** One of Telegram's own re-encoded MP4 copies of a video (H.264 or AV1, 720p and 480p). */
export interface VideoCopy extends FileRef {
    width: number
    height: number
    /** `avc1`, `av01`... as Telegram names it, or null when it does not say. */
    codec: string | null
}

/** Telegram's scrub preview: a JPEG sprite and a map of `time,x,y` per frame. */
export interface Storyboard {
    sprite: FileRef
    map: FileRef
}

/**
 * A video cut into raw byte pieces (Name.mkv.001, Name.part001) or kept in a ZIP split the same
 * way (Name.1080p.zip.001): the pieces that play as one stream, in order, from the first up to the
 * first one missing. `size` of the item is every piece present added up (what a card shows).
 */
export interface PieceSet {
    files: FileRef[]
    /** How many pieces the numbers say there are. */
    total: number
    /** The first piece missing, counted from 1; 0 when every piece is here. */
    firstMissing: number
}

export interface VideoItem extends FileRef {
    /** The Telegram datacenter (1 to 5) the file is kept in, for Playback details; absent in items kept before this existed. */
    dc?: number
    chatId: number
    messageId: number
    duration: number
    title: string
    /** The name Continue watching writes under a card (the apps' continueCardTitle): "S1 E3  ·  Harbour Notes", else the title. */
    name: string
    chat: string
    /** Milliseconds since 1970. */
    date: number
    /** Key of the thumbnail, for `thumb`. */
    thumb: string | null
    /**
     * A hash of Telegram's inline preview of the cover (the apps' miniThumbnail), the same for the
     * same picture: lets the page tell an uploader's advert, one cover on many videos, from a
     * video's own. Null where Telegram sent none; absent in items kept before this existed.
     */
    cover?: string | null
    /** The document's file name; absent in items kept before this existed. */
    fileName?: string
    /** A ZIP (one file, or a piece of a split one): the video inside it plays out of the archive when it is stored uncompressed. */
    zip?: boolean
    /** Set when the video is one cut into pieces (the lead piece's item stands for them all); see PieceSet. */
    joined?: PieceSet
    /** Telegram's own lighter MP4 copies, best first, when the video was posted as a video. */
    copies: VideoCopy[]
    storyboard: Storyboard | null
    /** False under "restrict saving content" (the chat's or the message's): it streams, nothing is kept. */
    canBeSaved: boolean
    /**
     * The one pill a poster tile wears ("720p", "1080p", "4K"...), from the size Telegram states
     * else a resolution word in the name, as the apps' FormatFacts.tileTag says; absent for SD or
     * when nothing says (no empty pill), and in items kept before this existed.
     */
    tile?: string
    /** What the poster lookup asks about this video (the apps' MetaQuery), or null when its name holds nothing to ask. */
    poster: PosterAsk | null
}

/** A subtitle file posted in the video's chat (EG-F48), best first in the list the worker answers. */
export interface ChatSubtitle {
    /** The worker's file key: `chunk` reads its bytes. */
    key: string
    /** The file's name as it was posted. */
    name: string
    size: number
    /** How it belongs to the video: the same album, a reply, next to it, or by its name. */
    relation: 'Album' | 'Reply' | 'Neighbour' | 'Name'
    /** The two letter code its name gives, or null. */
    language: string | null
    forced: boolean
    hearing: boolean
    /** A zip, whose subtitle entry is read when it is chosen. */
    archive: boolean
    /** False under "restrict saving content": the file is used here and never offered for download. */
    canBeSaved: boolean
}

export interface ChatItem {
    id: number
    title: string
    /** Key of the chat's small picture, for `thumb`. */
    photo: string | null
    pinned: boolean
    kind: 'user' | 'bot' | 'group' | 'channel'
    /** A group with topics: its videos are listed across all of them. */
    forum: boolean
    /** Saved Messages, which the page names in its own words. */
    self: boolean
    /** In Telegram's archive: listed by `archivedChats`, never by `chats`. */
    archived?: boolean
}

/** A chat list loaded part way: what has come so far, sent as `chats` runs. */
export interface ChatsEvent {
    chats: ChatItem[]
}

/** Video sort orders, as the app's VideoSort names them. */
export type Sort = 'Newest' | 'Oldest' | 'Name' | 'Year' | 'Size' | 'Longest' | 'LastPlayed' | 'Unwatched'

/** How a chat's grid is asked for: the app's order, size limits and progress, and words to look for. */
export interface ChatView {
    sort: Sort
    minBytes: number
    maxBytes: number
    /** Video ids ("chatId:messageId") finished, and started. */
    watched: string[]
    started: string[]
    query: string
    /**
     * The chat's Show filter and the line of its other filters (ui/videoFilter.ts), as the worker's
     * rules read them. Absent or "All" and "": every video shows.
     */
    filter?: string
    facets?: string
    /** For the Last played order: the version of the times the worker holds (sent once with `setTouches`); absent or unknown, the order has no times. */
    touches?: number
    /**
     * After a video that is not an episode, the chat's own next one in this order (the app's
     * ChatOrder): the watch page's Next and autoplay follow the grid's sort. Off or absent: it stops.
     */
    chatOrder?: boolean
    /** A forum chat held to one topic (the `?topic=` of a "Chat > Topic" row in Search): only its videos are listed, read through the topic itself. */
    topic?: number
}

/**
 * One card of a chat's grid, as the app's shelf groups it. A show plays `item`, its episode up
 * next; a file plays `item` itself.
 */
export interface Tile {
    key: string
    kind: 'show' | 'file'
    title: string
    item: VideoItem
    seasons: number
    episodes: number
    /** Other copies of the same video, parts it was cut into, and raw pieces that play as one. */
    copies: number
    /** How many versions the card says (VideoCopies.versionCount: the same file posted twice counts once); 1 for a show or a single file. */
    versions: number
    parts: number
    pieces: number
    /** "Trailer", "Sample"... for an extra. */
    extra: string | null
}

export interface ChatPage {
    title: string
    self: boolean
    /** Key of the chat's small picture, for `thumb`. */
    photo: string | null
    tiles: Tile[]
    /** Videos the size limits left out of `tiles`. */
    hiddenBySize: number
    /** Cards before the chat's filters, of the videos loaded so far; `hiddenByFilter` of them are screened out. */
    total: number
    hiddenByFilter: number
    /** Cards kept after the matches because nothing states their length or picture size. */
    unknown: number
    /** The audio languages the loaded names state, most common first (the Language chips). */
    languages: string[]
    /** Videos loaded so far, before the limits. */
    loaded: number
    /** Every video in the chat is loaded. */
    done: boolean
    /** Names of compressed files posted in the chat, which cannot be played. */
    archives: string[]
    selfDestructing: number
    /** The chat restricts saving content: its videos stream only. */
    protected: boolean
    /** Telegram's sponsored messages for a channel, placed among the cards every `sponsoredBetween` (0: one after them all). */
    sponsored: SponsoredItem[]
    sponsoredBetween: number
    /** The topic the grid is held to (`ChatView.topic`) with its name, when it was found. */
    topic?: { id: number; name: string }
}

/** A topic found by its name (U14), listed as "Chat > Topic"; a tap opens the chat on that topic. */
export interface TopicHit {
    chatId: number
    chatTitle: string
    topicId: number
    /** The topic's name; empty for one with no title (the page says "Untitled topic"). */
    name: string
    pinned: boolean
    closed: boolean
    /** False when the topic holds no video or file: listed dimmed. */
    hasVideos: boolean
    score: number
}

/** The answer of a search by topic name, whole or so far. */
export interface TopicsFound {
    hits: TopicHit[]
    /** Groups with topics asked, and how many of those Telegram refused. */
    asked: number
    refused: number
    /** More groups could have topics than one search asks: the heading says so. */
    partial: boolean
    /** The search is still asking (an event before the last answer). */
    searching: boolean
}

/** A sponsored message Telegram shows in a channel, as the apps' SponsoredItem. Never playable. */
export interface SponsoredItem {
    /** The worker's name for it; its random id stays there. */
    id: string
    recommended: boolean
    title: string
    text: string
    buttonText: string
    sponsorInfo: string
    additionalInfo: string
    url: string
    canReport: boolean
    /** Key of its picture, for `thumb`. */
    thumb: string | null
}

/** Telegram's answer to a report: reasons to pick from, or how it ended. */
export type SponsoredReport =
    | { kind: 'options'; title: string; options: { text: string; option: number[] }[] }
    | { kind: 'reported' | 'hidden' | 'premium' | 'unavailable' }

/** How the lists across chats (Search, Home) are asked for: the size limits and progress. */
export interface ListView {
    minBytes: number
    maxBytes: number
    /** Video ids ("chatId:messageId") finished, and started. */
    watched: string[]
    started: string[]
    /**
     * The chats the viewer added (HX27), or null for every chat. The worker cannot read the page's
     * storage, so the choice travels with the view, and every list the worker builds is held to it.
     * Null is "nobody has answered, or they chose to keep all", which is the web app as it was.
     */
    added?: number[] | null
}

/** A search's cards, grouped as a chat's are, within the size limits, and how many the limits hid. */
export interface Found {
    tiles: Tile[]
    hiddenBySize: number
    /** Videos the cards stand for (a show by its episodes) and the chats they are in, for the count line. */
    videos: number
    chats: number
    /** The search read the viewer's added chats (HX62); false when it read every chat because none was added. */
    addedOnly: boolean
}

/**
 * Recently added's See all: the newest videos across Home's chats, read on a page at a time, and
 * grouped as a chat's are. `done` once Telegram has no more, or the page holds as many as it keeps.
 */
export interface RecentPage {
    tiles: Tile[]
    hiddenBySize: number
    done: boolean
    /** It reads starred chats only, which its heading says, as the row's does. */
    starredOnly: boolean
    /** It stopped reading on at the most it keeps, with more still in Telegram. */
    capped: boolean
}

/** Where a video was left, as the page keeps it (store.ts Resume). */
export interface ResumeMark {
    chatId: number
    messageId: number
    position: number
    length: number
    time: number
}

export interface HomeView extends ListView {
    /** Continue watching's videos, the latest first. */
    resumes: ResumeMark[]
    starred: number[]
    /** Settings > Home: which rows are built (the apps' HomeLayout). Recently added reads the added chats (`added`) once there are any. */
    layout: {
        carousel: boolean
        continueWatching: boolean
        recentlyAdded: boolean
        starredRows: boolean
        /** The order of the rows, as stored (the apps' HomeOrder): row keys, comma separated; empty is Home's own. */
        order: string
    }
}

/** One row of Home, as the app builds it (WebRules' home). A row not `loaded` is still waiting for Telegram. */
export interface HomeRow {
    key: string
    /** 'list': one of the viewer's named lists (HX43), built in the page from the lists it keeps. */
    kind: 'continue' | 'recent' | 'chat' | 'list'
    /** A named list's row: the list's id. */
    listId?: string
    /** A starred chat's row: the chat, and its name. */
    chatId: number | null
    title: string | null
    loaded: boolean
    /** Recently added draws from starred chats only, which its heading says. */
    starredOnly: boolean
    tiles: Tile[]
    /** The chat has more than the row shows. */
    hasMore: boolean
    /** False for Recently added kept for the carousel alone (its row is off in Settings): not drawn. */
    asRow: boolean
    /** Recently added reads the added chats and none is added or left: a line in its place leads to Add chats. */
    noneChosen: boolean
}

/** Home as far as it has come; sent as `HomeEvent`s while the chats answer. */
export interface HomePage {
    rows: HomeRow[]
    /** Videos part way whose message is gone from its chat: the page forgets them. */
    gone: { chatId: number; messageId: number }[]
    /** Saved Messages, for the first run's button. */
    selfId: number | null
    /** Everything has answered. */
    settled: boolean
    /** Chats whose row could not be read (an error or no answer in time): their rows left; the page says so with Try again. */
    failed?: number
}

/** A country for the sign in's number, from Telegram's own list (help.getCountriesList). */
export interface Country {
    /** ISO 3166-1 alpha-2, as Telegram spells it: "IN", "GB". */
    iso: string
    name: string
    /** Every calling code that reaches it, most common first. */
    codes: DialCode[]
}

export interface DialCode {
    /** Digits, no "+". */
    code: string
    /** Area codes that mean this country where the calling code is shared ("204" for Canada under "1"). */
    prefixes: string[]
    /** How a national number is grouped, "XXX XXX XXXX"; a digit in it must match the number. */
    patterns: string[]
}

/**
 * A code Telegram sent, as mtcute's SentCode names its delivery: `app`, `sms`, `call`,
 * `flash_call`, `missed_call`, `email`, `email_required`, `fragment`, `firebase`, `sms_word`,
 * `sms_phrase`; `email_setup` for the code sent to an address given at sign in.
 */
export interface SentCode {
    hash: string
    type: string
    /** What a resend would use, or `none`. */
    next: string
    /** Seconds before a resend is allowed; 0 when Telegram does not say. */
    timeout: number
    /** 0 when unknown. */
    length: number
    /** The masked address for an email code. */
    email: string | null
    /** The start of a word or phrase sent by text. */
    beginning: string | null
}

/** Events the QR sign in sends while it runs. */
export type QrEvent =
    | { kind: 'url'; url: string; expires: number }
    | { kind: 'scanned' }
    | { kind: 'password'; hint: string | null }
    | { kind: 'badPassword' }

/** A Telegram or worker error, as it crosses to the page. */
export interface RpcError {
    message: string
    text?: string
    code?: number
    seconds?: number
}

/** An episode as the watch page lists it: its code ("S01E03"), its own name when the file gives one, and what plays. */
export interface EpisodeRef {
    code: string
    name: string | null
    item: VideoItem
}

/** What the watch page shows round the player, from the chat's videos by the app's rules (WebRules' steps). */
export interface WatchInfo {
    /** The name a card would give it: the show's for an episode. */
    title: string
    /** Set for an episode of a show. */
    episode: { code: string; name: string | null; show: string } | null
    previous: VideoItem | null
    previousCode: string | null
    next: VideoItem | null
    nextCode: string | null
    /** The show's seasons, when it is an episode; empty otherwise. */
    seasons: { number: number; episodes: EpisodeRef[]; covered: number[] }[]
    /** Whole-season files of the show ("Season 2 Complete"), listed under their season; `seasonEnd` is set for a span of seasons. */
    packs: { season: number; seasonEnd: number | null; item: VideoItem }[]
    /** Other cards of the chat, newest first, when it is not an episode. */
    more: Tile[]
    fileName: string
    width: number
    height: number
    chatTitle: string
    /** The chat restricts saving content. */
    protected: boolean
    /** What the file is badged with ("1080p", "4K"), from its stated size else its name (QualityRead); null when neither says. */
    quality: string | null
    /** The picture's height that badge reads (2160 for 4K), for the quality the viewer usually plays; null when nothing says. */
    qualityHeight?: number | null
    /**
     * Every copy of this video in the chat when there is more than one file of it, this one among
     * them, in the order the list shows them: recommended first (HX38). Its length is "N versions".
     */
    copies: CopyRef[]
    /** Every file of that group, the same file posted twice included (`copies` shows such a pair once): what a rename is written to. */
    group: { chatId: number; messageId: number }[]
    /** The caption as an overview, its file facts and lines repeating the name taken off (MediaText.overviewOf); null when nothing is left. */
    overview: string | null
}

/** One copy of a video, for the copies list on its page: what its name says of the picture, and where it was posted. */
export interface CopyRef {
    item: VideoItem
    /** "1080p", "H.264", "Blu-ray": the quality first, then what the name states. */
    facts: string[]
    fileName: string
    /** Seconds since 1970. */
    date: number
    chat: string
    /** "Best for this device" (VideoCopies.bestForDevice), only where the copies differ in picture. */
    best?: boolean
    /** "May stutter on this computer" for a copy this browser cannot play natively; "Smooth" beside one that may. */
    fit?: 'smooth' | 'stutter' | null
    /** The viewer started this copy. */
    started?: boolean
    /** Its name says another picture than the size Telegram states (the size heads the row). */
    mismatch?: boolean
}
