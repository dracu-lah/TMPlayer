// Worker only: the chat list, and each opened chat's videos paged in from Telegram and arranged
// by the app's own rules (WebRules' screen and shelf) once per change, the answer kept until the
// chat, the order, the size limits or the words change. The page asks again on every scroll and
// gets the kept answer.
import type { Message, Peer, TelegramClient } from '@mtcute/web'
import { rules, type Shelf, type ShelfTile, type Video } from '../rules'
import { chatProtects, itemOf, photosAmong, rawOf, thumbnails, videoId } from './media'
import { forgetSponsored, loadSponsored } from './sponsored'
import { tally } from '../ui/searchTally'
import type { ChatItem, CopyRef, ChatPage, ChatView, Found, ListView, RecentPage, SponsoredItem, Tile, VideoItem, WatchInfo } from './types'

const PAGE = 100
/** Messages asked per filter of Telegram's search inside a chat. */
const SEARCH_PAGE = 50
const VIDEO_FILTER = { _: 'inputMessagesFilterVideo' } as const
const DOC_FILTER = { _: 'inputMessagesFilterDocument' } as const
const PHOTO_FILTER = { _: 'inputMessagesFilterPhotos' } as const
/** An album read back for a search hit (AlbumPosts in core): six albums a page, three at once, one history window each. */
const ALBUM_READS = 6
const ALBUM_AT_ONCE = 3
const ALBUM_MEMBERS = 10
/** The longest FLOOD_WAIT one album read sits out, once. */
const ALBUM_FLOOD_MAX_S = 30
/** Chats kept arranged at once; the oldest opened goes first. */
const KEEP_CHATS = 6

/**
 * The chats a list may draw from, as the page's view carries it (HX27, `ListView.added`): the
 * worker holds no settings of its own, so every cross-chat list reads this out of its own call's
 * view rather than asking the page again.
 */
const addedSetOf = (view: Pick<ListView, 'added'>): Set<number> | null => (view.added ? new Set(view.added) : null)

interface Feed {
    filter: typeof VIDEO_FILTER | typeof DOC_FILTER
    next: number | undefined
    done: boolean
}

/** One query's search of a chat: the spelling Telegram answered (null until one did) and where its pages go on. */
interface ChatSearch {
    spelling: string | null
    feeds: Feed[]
    /** Every spelling was asked and none answered, or the pages ran out. */
    done: boolean
    loading: Promise<void> | null
}

interface ChatState {
    id: number
    title: string
    self: boolean
    photo: string | null
    protected: boolean
    /** A channel, which carries Telegram's sponsored messages. */
    channel: boolean
    feeds: Feed[]
    videos: Map<string, Video>
    items: Map<string, VideoItem>
    /**
     * Videos Telegram's search of the chat found that its pages have not reached yet, listed only
     * under words (a search inside the chat is no longer a filter over the pages loaded so far).
     */
    hits: Map<string, Video>
    /** Telegram's search of the chat, per query (lower case): the spelling that answered and its pages. */
    searches: Map<string, ChatSearch>
    archives: Map<number, string>
    /** The first photo message of each album met so far (by album id), drawn as its videos' picture; bounded. */
    albumPhotos: Map<string, Message>
    selfDestructing: number
    /** Bumped each time videos come in, so a kept arrangement knows it is stale. */
    version: number
    loading: Promise<void> | null
    kept: { version: number; view: string; page: ChatPage } | null
    sponsored: SponsoredItem[]
    sponsoredBetween: number
    /** When it was read from its newest message, for Home's reuse. */
    time: number
}

const chats = new Map<number, ChatState>()

export function forgetAll() {
    chats.clear()
    dialogs = null
    dialogsLoading = null
    dialogsSoFar = []
    dialogsAt = 0
    headLoading = null
    archived = null
    archivedLoading = null
    epoch++
    homeStates.clear()
    searches.clear()
    watchKept.clear()
    recentState = null
    homeChatsKnown = null
    videoCountCache.clear()
    forgetSponsored()
}

async function openState(tg: TelegramClient, chatId: number): Promise<ChatState> {
    const peer = await tg.getPeer(chatId)
    const small = peer.photo?.small ?? null
    if (small) thumbnails.set(small.uniqueFileId, small)
    return {
        photo: small?.uniqueFileId ?? null,
        id: chatId,
        title: peer.displayName,
        self: peer.type === 'user' && peer.isSelf,
        protected: !!(peer.raw as { noforwards?: boolean }).noforwards,
        feeds: [VIDEO_FILTER, DOC_FILTER].map((filter) => ({ filter, next: undefined, done: false })),
        videos: new Map(),
        items: new Map(),
        hits: new Map(),
        searches: new Map(),
        archives: new Map(),
        albumPhotos: new Map(),
        selfDestructing: 0,
        version: 0,
        loading: null,
        kept: null,
        sponsored: [],
        sponsoredBetween: 0,
        time: Date.now(),
        channel: peer.type === 'chat' && peer.chatType === 'channel',
    }
}

/** The next hundred of each kind (videos, files) the chat has not sent yet, through the rules. */
async function loadPage(tg: TelegramClient, s: ChatState): Promise<void> {
    const r = await rules()
    const pages = await Promise.all(
        s.feeds
            .filter((f) => !f.done)
            .map(async (f) => {
                const res = await tg.searchMessages({ chatId: s.id, filter: f.filter, limit: PAGE, offset: f.next })
                f.next = res.next
                f.done = res.next === undefined || res.length === 0
                return res as Message[]
            }),
    )
    absorb(r, s, pages.flat(), s.videos)
    s.version++
}

/**
 * Messages Telegram sent for the chat, through the rules, into `into` (and their items into the chat's). Returns the videos added.
 * Photos among them (an album's, from a search hit or a window read) give the album's videos a caption and a picture.
 */
function absorb(r: Awaited<ReturnType<typeof rules>>, s: ChatState, messages: Message[], into: Map<string, Video>): Video[] {
    const byId = new Map<number, Message>()
    for (const msg of messages) byId.set(msg.id, msg)
    const { photos, byAlbum } = photosAmong(messages)
    for (const [album, msg] of byAlbum) {
        s.albumPhotos.delete(album)
        s.albumPhotos.set(album, msg)
    }
    while (s.albumPhotos.size > KEEP_ALBUM_PHOTOS) s.albumPhotos.delete(s.albumPhotos.keys().next().value!)
    const raws = []
    for (const msg of byId.values()) {
        const raw = rawOf(msg, s.protected)
        if (!raw) continue
        if (raw.kind === 'document' && raw.fileName && !raw.withheld) {
            // A release-named zip (or one piece of a split one) is a video kept in a box: listed, and played out of the archive.
            const archive = r.looksLikeVideoArchive(raw.fileName, raw.size) ? null : r.archive(raw.fileName, raw.mimeType)
            if (archive) {
                s.archives.set(msg.id, raw.fileName)
                continue
            }
        }
        raws.push(raw)
    }
    const screened = r.screenAlbums(raws, photos)
    if (into === s.videos) s.selfDestructing += screened.selfDestructing
    const added: Video[] = []
    for (const v of screened.videos) {
        const msg = byId.get(v.messageId)
        const item = msg && itemOf(msg, v.title, r.posterAsk(v), r.continueTitle(v), v.albumPhoto && v.albumId ? s.albumPhotos.get(v.albumId) : null, r.tileTag(v))
        if (!item) continue
        into.set(v.id, v)
        s.items.set(v.id, item)
        added.push(v)
    }
    return added
}

/** Albums' photos kept per chat. */
const KEEP_ALBUM_PHOTOS = 300

/**
 * The messages around the albums that `hits` belong to: one history window per album (a photo hit
 * first, then an album's video hit), at most six a page and three at once, never one per item. A read
 * that fails leaves its album as the hit showed it, and a flood wait is waited out by that read alone.
 */
export async function albumNeighbours(tg: TelegramClient, photoHits: Message[], hits: Message[]): Promise<Message[]> {
    const anchors = new Map<string, Message>()
    for (const m of [...photoHits, ...hits]) {
        if (!m.groupedId || !m.chat) continue
        const key = `${m.chat.id}:${m.groupedId.toString()}`
        if (!anchors.has(key)) anchors.set(key, m)
    }
    const reads = [...anchors.values()].slice(0, ALBUM_READS)
    const out: Message[] = []
    for (let i = 0; i < reads.length; i += ALBUM_AT_ONCE) {
        const chunk = await Promise.all(reads.slice(i, i + ALBUM_AT_ONCE).map((m) => albumWindow(tg, m)))
        out.push(...chunk.flat())
    }
    return out
}

/** One album's members around `anchor`: the ten before and after it, kept to its own album. */
async function albumWindow(tg: TelegramClient, anchor: Message): Promise<Message[]> {
    for (let attempt = 0; attempt < 2; attempt++) {
        try {
            const around = await tg.getHistory(anchor.chat.id, {
                offset: { id: anchor.id, date: Math.floor(anchor.date.getTime() / 1000) },
                addOffset: -ALBUM_MEMBERS,
                limit: ALBUM_MEMBERS * 2 + 1,
            })
            const album = anchor.groupedId!.toString()
            return around.filter((m) => m.groupedId?.toString() === album).slice(0, ALBUM_MEMBERS)
        } catch (e) {
            const seconds = floodSeconds(e)
            if (seconds === null || seconds > ALBUM_FLOOD_MAX_S || attempt > 0) return []
            await new Promise((resolve) => setTimeout(resolve, seconds * 1000 + 250))
        }
    }
    return []
}

/** The seconds a FLOOD_WAIT asks for, or null for any other failure. */
function floodSeconds(e: unknown): number | null {
    const seconds = (e as { seconds?: unknown })?.seconds
    if (typeof seconds === 'number' && Number.isFinite(seconds)) return seconds
    const m = /FLOOD_WAIT_(\d+)/.exec(String((e as { message?: unknown })?.message ?? e))
    return m ? Number(m[1]) : null
}

/**
 * Telegram's own search of the chat for `query`, a page at a time. Telegram matches the words of a
 * file name as it parts them, and `Resident.Evil.2021.mkv` is one word to it, so the first page
 * tries each spelling the rules give (as typed, as words, with underscores, with dots, the longest
 * words) until one finds a video that answers the words. A failure (a flood wait, the connection)
 * leaves the search to be asked again on the next view, with the loaded pages still matched.
 */
async function searchChat(tg: TelegramClient, s: ChatState, query: string): Promise<void> {
    const key = query.toLowerCase()
    let state = s.searches.get(key)
    if (!state) {
        state = { spelling: null, feeds: [], done: false, loading: null }
        s.searches.set(key, state)
        // Each chat keeps a handful of queries' searches; the oldest goes first.
        while (s.searches.size > KEEP_SEARCHES) s.searches.delete(s.searches.keys().next().value!)
    }
    if (state.done) return
    const st = state
    st.loading ??= (async () => {
        const r = await rules()
        const pageOf = async (spelling: string, feeds: Feed[]) => {
            const pages = await Promise.all(
                feeds
                    .filter((f) => !f.done)
                    .map(async (f) => {
                        const res = await tg.searchMessages({ chatId: s.id, query: spelling, filter: f.filter, limit: SEARCH_PAGE, offset: f.next })
                        f.next = res.next
                        f.done = res.next === undefined || res.length === 0
                        return res as Message[]
                    }),
            )
            const hits = pages.flat()
            // A photo whose caption names the title leads to its album's videos: asked beside the
            // others (its first page only, a failure leaves the rest standing), then one read of
            // each hit's album.
            const photoHits = await tg
                .searchMessages({ chatId: s.id, query: spelling, filter: PHOTO_FILTER, limit: ALBUM_READS * 2 })
                .then((res) => res as Message[])
                .catch(() => [] as Message[])
            const neighbours = await albumNeighbours(tg, photoHits, hits)
            return absorb(r, s, [...hits, ...photoHits, ...neighbours], s.hits)
        }
        if (st.spelling === null) {
            for (const spelling of r.chatSearchSpellings(query)) {
                const feeds: Feed[] = [VIDEO_FILTER, DOC_FILTER].map((filter) => ({ filter, next: undefined, done: false }))
                const added = await pageOf(spelling, feeds)
                if (!r.rank(added.map((v) => `${v.title} ${v.fileName} ${v.caption}`), query).length) continue
                st.spelling = spelling
                st.feeds = feeds
                st.done = feeds.every((f) => f.done)
                return
            }
            st.done = true
            return
        }
        await pageOf(st.spelling, st.feeds)
        st.done = st.feeds.every((f) => f.done)
    })()
        .catch(() => undefined)
        .finally(() => {
            st.loading = null
            s.version++
        })
    await st.loading
}

/** Runs one load at a time per chat: a second Refresh or scroll while one runs waits for it. */
function load(s: ChatState, run: () => Promise<void>): Promise<void> {
    s.loading ??= run().finally(() => (s.loading = null))
    return s.loading
}

const done = (s: ChatState) => s.feeds.every((f) => f.done)

/** Queries whose search a chat keeps. */
const KEEP_SEARCHES = 8

/** Under words: the chat's pages are read to the end and Telegram's search has no more. */
const searchDone = (s: ChatState, query: string) => done(s) && (s.searches.get(query.toLowerCase())?.done ?? false)

/** The episode a show's card plays: the one started, else the first not watched, else the first. */
function upNext(tile: ShelfTile, watched: Set<string>, started: Set<string>): Video | null {
    const episodes = tile.seasons.flatMap((season) => season.episodes.map((e) => e.video))
    return episodes.find((v) => started.has(v.id)) ?? episodes.find((v) => !watched.has(v.id)) ?? episodes[0] ?? tile.video ?? null
}

/**
 * The item a tile plays. A set of pieces (a video cut up, or a ZIP split up) plays as one stream of
 * every piece from the first up to the first gap, its size the pieces added up; anything else
 * plays the item as it is.
 */
export function joinedItem(t: Pick<ShelfTile, 'pieces' | 'piecesTotal' | 'piecesFirstMissing' | 'piecesPlayable'>, item: VideoItem, items: Map<string, VideoItem>): VideoItem {
    if (!t.pieces.length) return item
    const found: VideoItem[] = []
    for (const id of t.pieces) {
        const piece = items.get(id)
        if (piece) found.push(piece)
    }
    // A piece the page does not know (it left the list part way): the lead plays as it is, never half a set.
    if (found.length !== t.pieces.length) return item
    const playable = found.slice(0, Math.max(0, t.piecesPlayable || found.length))
    return {
        ...item,
        size: found.reduce((n, p) => n + Math.max(0, p.size), 0),
        zip: item.zip || found.some((p) => p.zip) || undefined,
        joined: { files: playable.map((p) => ({ key: p.key, size: p.size })), total: t.piecesTotal || found.length, firstMissing: t.piecesFirstMissing },
    }
}

/** The page's cards for the rules' tiles, each playing an item `items` holds; a show plays its episode up next. */
export function tilesOf(shelf: Pick<Shelf, 'tiles'>, items: Map<string, VideoItem>, view: Pick<ListView, 'watched' | 'started'>): Tile[] {
    const watched = new Set(view.watched)
    const started = new Set(view.started)
    const tiles: Tile[] = []
    for (const t of shelf.tiles) {
        const video = t.kind === 'show' ? upNext(t, watched, started) : t.video
        const lead = video && items.get(video.id)
        if (!lead) continue
        const item = t.kind === 'show' ? lead : joinedItem(t, lead, items)
        tiles.push({
            key: t.key,
            kind: t.kind === 'show' ? 'show' : 'file',
            title: t.title,
            item,
            seasons: t.seasons.length,
            episodes: t.seasons.reduce((n, season) => n + season.episodes.length, 0),
            copies: Math.max(0, t.copies.length - 1),
            parts: t.parts.length,
            pieces: t.pieces.length,
            extra: t.extra ?? null,
        })
    }
    return tiles
}

function arrange(s: ChatState, view: ChatView): ChatPage {
    const key = JSON.stringify(view)
    if (s.kept && s.kept.version === s.version && s.kept.view === key) return s.kept.page
    const r = rulesNow!
    const query = view.query.trim()
    // Under words, what Telegram's search found beyond the loaded pages is listed with them.
    const videos = [...s.videos.values()]
    if (query) for (const [id, v] of s.hits) if (!s.videos.has(id)) videos.push(v)
    const shelf = r.shelf(videos, view.sort, view.minBytes, view.maxBytes, view.watched, view.started)
    let tiles = tilesOf(shelf, s.items, view)
    if (query) {
        const texts = tiles.map((t) => {
            if (t.kind === 'show') return t.title
            const v = s.videos.get(videoId(t.item)) ?? s.hits.get(videoId(t.item))
            return `${t.title} ${v?.fileName ?? ''} ${v?.caption ?? ''}`
        })
        const order = r.rank(texts, query)
        tiles = order.map((i) => tiles[i])
    }
    const page: ChatPage = {
        title: s.title,
        self: s.self,
        photo: s.photo,
        tiles,
        hiddenBySize: shelf.hiddenBySize,
        loaded: s.videos.size,
        done: query ? searchDone(s, query) : done(s),
        archives: [...s.archives.entries()].sort((a, b) => b[0] - a[0]).map(([, name]) => name),
        selfDestructing: s.selfDestructing,
        protected: s.protected,
        sponsored: s.sponsored,
        sponsoredBetween: s.sponsoredBetween,
    }
    s.kept = { version: s.version, view: key, page }
    return page
}

const opening = new Map<number, Promise<ChatState>>()

/** A chat read again from its newest message; a second open or Refresh while one runs waits for it. */
function fresh(tg: TelegramClient, chatId: number): Promise<ChatState> {
    let p = opening.get(chatId)
    if (!p) {
        p = (async () => {
            const s = await openState(tg, chatId)
            const [, ads] = await Promise.all([loadPage(tg, s), s.channel ? loadSponsored(tg, chatId) : null])
            if (ads) {
                s.sponsored = ads.items
                s.sponsoredBetween = ads.between
            }
            return s
        })().finally(() => opening.delete(chatId))
        opening.set(chatId, p)
    }
    return p
}

let rulesNow: Awaited<ReturnType<typeof rules>> | null = null

/**
 * A chat's grid. `open` loads its first page if it has none yet, `more` the next one, `refresh`
 * starts over from Telegram, `view` only arranges again (another order, size limits or words).
 */
export async function chatPage(tg: TelegramClient, chatId: number, view: ChatView, mode: 'open' | 'more' | 'refresh' | 'view'): Promise<ChatPage> {
    rulesNow = await rules()
    let s = mode === 'refresh' ? undefined : chats.get(chatId)
    const opened = !s
    if (!s) s = await fresh(tg, chatId)
    const query = view.query.trim()
    const st = s
    if (query && mode !== 'more') {
        // Words: Telegram's search of the whole chat, not only the pages loaded so far.
        await searchChat(tg, st, query)
    } else if (query && mode === 'more' && !(st.searches.get(query.toLowerCase())?.done ?? false)) {
        await searchChat(tg, st, query)
    } else if (mode === 'more' && !opened && !done(st)) {
        // Past Telegram's answer the chat's own pages go on, matched here: the release names its search cannot see.
        await load(st, () => loadPage(tg, st))
    } else if (st.loading) await st.loading
    // Most recently used last, so the oldest is the one let go.
    chats.delete(chatId)
    chats.set(chatId, s)
    while (chats.size > KEEP_CHATS) chats.delete(chats.keys().next().value!)
    return arrange(s, view)
}

/** Home's chats, read from their newest message and kept a few minutes; an opened chat's own state is reused. */
const homeStates = new Map<number, ChatState>()
const HOME_KEEP_MS = 3 * 60_000

/** A chat's newest videos for Home: what its first page brought, and whether that was all of it. */
export async function newestOf(tg: TelegramClient, chatId: number): Promise<{ videos: Video[]; items: Map<string, VideoItem>; complete: boolean }> {
    let s = chats.get(chatId) ?? homeStates.get(chatId)
    if (!s || Date.now() - s.time > HOME_KEEP_MS) {
        s = await fresh(tg, chatId)
        homeStates.set(chatId, s)
        for (const [id, held] of homeStates) if (Date.now() - held.time > HOME_KEEP_MS) homeStates.delete(id)
    } else if (s.loading) await s.loading
    return { videos: [...s.videos.values()], items: s.items, complete: done(s) }
}

// The chat list

let dialogs: ChatItem[] | null = null
/** The chat list as far as it has come while it loads. */
let dialogsSoFar: ChatItem[] = []
let dialogsLoading: Promise<ChatItem[]> | null = null
const dialogListeners = new Set<(chats: ChatItem[]) => void>()

export function chatItem(peer: Peer, pinned: boolean): ChatItem {
    const small = peer.photo?.small ?? null
    if (small) thumbnails.set(small.uniqueFileId, small)
    const self = peer.type === 'user' && peer.isSelf
    return {
        id: peer.id,
        title: peer.displayName,
        photo: small && !self ? small.uniqueFileId : null,
        pinned,
        kind: peer.type === 'user' ? (peer.isBot ? 'bot' : 'user') : peer.chatType === 'channel' ? 'channel' : 'group',
        forum: peer.type === 'chat' && peer.isForum,
        self,
    }
}

/** When the whole list was read, or its head last read again. */
let dialogsAt = 0
/** A kept list older than this has its head read again before it is handed out (HX44). */
const FRESH_MS = 60_000
let headLoading: Promise<ChatItem[]> | null = null
/** Bumped by a sign out, so an answer that lands after it is not kept for the next account. */
let epoch = 0

/**
 * Every chat outside the archive, pinned first, in Telegram's order. The list arrives in pages
 * of a hundred, each sent on to `onBatch` as it comes; it is kept for the session, and
 * `refresh` asks again. A kept list more than a minute old is handed to `onBatch` at once and
 * its first page then read again (one call), so a chat joined since, or a new private chat,
 * shows up by itself, as the apps' list does without a Refresh button (HX44).
 */
export async function chatList(tg: TelegramClient, refresh: boolean, onBatch: (chats: ChatItem[]) => void): Promise<ChatItem[]> {
    if (dialogs && !refresh) {
        if (Date.now() - dialogsAt < FRESH_MS) return dialogs
        onBatch(dialogs)
        headLoading ??= freshenHead(tg).finally(() => (headLoading = null))
        return await headLoading
    }
    dialogListeners.add(onBatch)
    try {
        dialogsLoading ??= (async () => {
            const mine = epoch
            const out: ChatItem[] = []
            const seen = new Set<number>()
            for await (const d of tg.iterDialogs({ archived: 'exclude', pinned: 'include' })) {
                if (seen.has(d.peer.id)) continue
                seen.add(d.peer.id)
                out.push(chatItem(d.peer, d.isPinned))
                if (out.length % PAGE === 0) {
                    if (mine !== epoch) break
                    dialogsSoFar = out.slice()
                    for (const fn of dialogListeners) fn(dialogsSoFar)
                }
            }
            if (mine === epoch) {
                dialogs = out
                dialogsAt = Date.now()
            }
            return out
        })().finally(() => (dialogsLoading = null))
        return await dialogsLoading
    } finally {
        dialogListeners.delete(onBatch)
    }
}

/**
 * The list's first page read again: Telegram's head in its own order, then the rest as kept, so
 * a chat moved up or newly joined comes first and nothing further down is asked for again. A
 * failure keeps the list as it was and waits another minute before trying.
 */
async function freshenHead(tg: TelegramClient): Promise<ChatItem[]> {
    const mine = epoch
    const kept = dialogs ?? []
    try {
        const head: ChatItem[] = []
        const seen = new Set<number>()
        for await (const d of tg.iterDialogs({ archived: 'exclude', pinned: 'include', limit: PAGE })) {
            if (seen.has(d.peer.id)) continue
            seen.add(d.peer.id)
            head.push(chatItem(d.peer, d.isPinned))
        }
        if (mine !== epoch) return kept
        // Pinned chats all come in the head, so one further down is pinned no longer.
        const rest = kept.filter((c) => !seen.has(c.id)).map((c) => (c.pinned ? { ...c, pinned: false } : c))
        dialogs = [...head, ...rest]
        dialogsAt = Date.now()
        return dialogs
    } catch {
        if (mine === epoch) dialogsAt = Date.now()
        return kept
    }
}

let archived: ChatItem[] | null = null
let archivedLoading: Promise<ChatItem[]> | null = null
const archivedListeners = new Set<(chats: ChatItem[]) => void>()

/**
 * Every chat in Telegram's archive, read whole (HX32) in pages of a hundred and kept for the
 * session. Only the Add chats picker and a Chats list narrowed to archived chats the viewer
 * added ask for it: a channel archived to keep Telegram tidy is still a channel of videos.
 */
export async function archivedList(tg: TelegramClient, onBatch: (chats: ChatItem[]) => void): Promise<ChatItem[]> {
    if (archived) return archived
    archivedListeners.add(onBatch)
    try {
        archivedLoading ??= (async () => {
            const mine = epoch
            const out: ChatItem[] = []
            const seen = new Set<number>()
            for await (const d of tg.iterDialogs({ archived: 'only', pinned: 'include' })) {
                if (seen.has(d.peer.id)) continue
                seen.add(d.peer.id)
                out.push({ ...chatItem(d.peer, false), archived: true })
                if (out.length % PAGE === 0) {
                    if (mine !== epoch) break
                    const soFar = out.slice()
                    for (const fn of archivedListeners) fn(soFar)
                }
            }
            if (mine === epoch) archived = out
            return out
        })().finally(() => (archivedLoading = null))
        return await archivedLoading
    } finally {
        archivedListeners.delete(onBatch)
    }
}

/** The chat list: whole once it has loaded (`done`), else as far as it has come. */
export function knownChats(): { chats: ChatItem[]; done: boolean } {
    return dialogs ? { chats: dialogs, done: true } : { chats: dialogsSoFar, done: false }
}

/** Ids of the listed chats whose names answer `query`, best first, by the app's fuzzy search. */
export async function rankChats(query: string): Promise<number[]> {
    const r = await rules()
    const list = dialogs ?? []
    return r.rank(list.map((c) => c.title), query).map((i) => list[i].id)
}

// Search across chats, within the size limits

/** Kept for two minutes, so a change of size limits arranges again without asking Telegram. */
const searches = new Map<string, { time: number; messages: Promise<Message[]> }>()

/** Added chats searched side by side (two calls each, a video and a document search), as the apps' CHATS_AT_ONCE. */
const ADDED_AT_ONCE = 6
/** The most added chats one search reads: one answer is all the page has, so a longer list is cut here. */
const ADDED_MAX = 60

/**
 * The videos and files of the viewer's added chats (HX62) that answer `query`: each chat searched on
 * its own, a few at a time, because Telegram's search across the account cannot be told "these chats
 * only" and, post-filtered, would show almost nothing for twelve chats of three thousand. A chat that
 * refuses (left, flooded past what mtcute waits out) is skipped and the rest are listed.
 */
async function inAddedChats(tg: TelegramClient, query: string, added: Set<number>): Promise<Message[]> {
    const limit = query ? 40 : 60
    const chats = [...added].slice(0, ADDED_MAX)
    const out: Message[] = []
    for (let i = 0; i < chats.length; i += ADDED_AT_ONCE) {
        const batch = chats.slice(i, i + ADDED_AT_ONCE)
        const answers = await Promise.all(
            batch.map(async (chatId) => {
                try {
                    const [videos, docs] = await Promise.all([
                        tg.searchMessages({ chatId, query, filter: VIDEO_FILTER, limit }),
                        tg.searchMessages({ chatId, query, filter: DOC_FILTER, limit }),
                    ])
                    return [...videos, ...docs] as Message[]
                } catch {
                    return []
                }
            }),
        )
        for (const found of answers) out.push(...found)
    }
    return out
}

/**
 * The newest videos across every chat for [query] (all of them for a blank one), through the
 * rules, with their items. `added` leaves out chats not in the viewer's choice (HX27), null for
 * every chat.
 */
export async function across(
    tg: TelegramClient,
    query: string,
    added: Set<number> | null = null,
    everyChat = false,
): Promise<{ videos: Video[]; items: Map<string, VideoItem> }> {
    const r = await rules()
    // The viewer's added chats are read one by one; every chat (nothing added, or the one-off "Search my other chats") in one search across the account.
    const own = added && !everyChat ? added : null
    const key = `${own ? [...own].join() : '*'}\n${query}`
    let hit = searches.get(key)
    if (!hit || Date.now() - hit.time > 120_000) {
        const limit = query ? 60 : 100
        hit = {
            time: Date.now(),
            messages: Promise.all([
                own ? inAddedChats(tg, query, own) : tg.searchGlobal({ query, filter: VIDEO_FILTER, limit }),
                own ? Promise.resolve([]) : tg.searchGlobal({ query, filter: DOC_FILTER, limit }),
                // A photo whose caption names the title leads to its album's videos; beside a query
                // only, and a failure of this search leaves the others' answer standing.
                query ? tg.searchGlobal({ query, filter: PHOTO_FILTER, limit: ALBUM_READS * 2 }).catch(() => []) : Promise.resolve([]),
            ]).then(async ([a, b, c]) => {
                const found = [...a, ...b] as Message[]
                const photoHits = [...c] as Message[]
                // One read of each hit's album, six a page: the search is kept two minutes, so it is read once.
                const neighbours = query ? await albumNeighbours(tg, photoHits, found) : []
                // Of a video hit's album only the photo is wanted (its caption and picture); the other
                // videos are listed when a photo hit named the album, as the answer to the words.
                const named = new Set(photoHits.filter((m) => m.groupedId).map((m) => `${m.chat?.id}:${m.groupedId!.toString()}`))
                const kept = neighbours.filter((m) => m.media?.type === 'photo' || named.has(`${m.chat?.id}:${m.groupedId?.toString()}`))
                return [...found, ...photoHits, ...kept]
            }),
        }
        hit.messages.catch(() => searches.delete(key))
        searches.set(key, hit)
        for (const [k, v] of searches) if (Date.now() - v.time > 120_000) searches.delete(k)
    }
    const messages = await hit.messages
    const byId = new Map<string, Message>()
    const raws = []
    const inChats = messages.filter((msg) => !own || own.has(msg.chat?.id))
    const { photos, byAlbum } = photosAmong(inChats)
    for (const msg of inChats) {
        const raw = rawOf(msg, chatProtects(msg))
        if (!raw) continue
        byId.set(videoId(raw), msg)
        raws.push(raw)
    }
    const videos: Video[] = []
    const items = new Map<string, VideoItem>()
    for (const v of r.screenAlbums(raws, photos).videos) {
        const item = itemOf(byId.get(v.id)!, v.title, r.posterAsk(v), r.continueTitle(v), v.albumPhoto && v.albumId ? byAlbum.get(v.albumId) : null, r.tileTag(v))
        if (!item || items.has(v.id)) continue
        items.set(v.id, item)
        videos.push(v)
    }
    return { videos, items }
}

/**
 * A named list's cards (HX43), from the videos the page keeps (nothing is asked of Telegram):
 * grouped into shows, copies and pieces as a chat's grid is, a show saved whole as one card, each
 * card where its first line stands, in the order the lines were added.
 */
export async function listTiles(lines: { item: VideoItem; show?: string }[]): Promise<Tile[]> {
    const r = await rules()
    const videos: Video[] = []
    const items = new Map<string, VideoItem>()
    const order = new Map<string, number>()
    const out: { at: number; tile: Tile }[] = []
    lines.slice(0, 20_000).forEach((line, at) => {
        const item = line.item
        if (!item || typeof item.chatId !== 'number' || typeof item.messageId !== 'number') return
        if (line.show) {
            out.push({ at, tile: { key: `show-${item.chatId}-${line.show}`, kind: 'show', title: item.title, item, seasons: 0, episodes: 0, copies: 0, parts: 0, pieces: 0, extra: null } })
            return
        }
        const v = r.video({
            chatId: item.chatId, messageId: item.messageId, kind: 'video', fileName: item.fileName ?? item.title, mimeType: null, caption: null,
            size: item.size ?? 0, duration: item.duration ?? 0, width: 0, height: 0, date: Math.floor((item.date ?? 0) / 1000), topicId: 0, albumId: null,
            canBeSaved: item.canBeSaved !== false, selfDestructs: false, withheld: false,
        })
        if (!v || items.has(v.id)) return
        items.set(v.id, item)
        order.set(v.id, at)
        videos.push(v)
    })
    const shelf = r.shelf(videos, 'Newest', 0, r.sizeLimits().ceiling, [], [])
    for (const tile of tilesOf(shelf, items, { watched: [], started: [] })) {
        out.push({ at: order.get(`${tile.item.chatId}:${tile.item.messageId}`) ?? Number.MAX_SAFE_INTEGER, tile })
    }
    return out.sort((a, b) => a.at - b.at).map((o) => o.tile)
}

/** Videos across every chat within the size limits, grouped into shows, copies and pieces as a chat's are, newest first. */
export async function searchAll(tg: TelegramClient, query: string, view: ListView, everyChat = false): Promise<Found> {
    const r = await rules()
    const added = addedSetOf(view)
    const { videos, items } = await across(tg, query, added, everyChat)
    const shelf = r.shelf(videos, 'Newest', view.minBytes, view.maxBytes, view.watched, view.started)
    const tiles = tilesOf(shelf, items, view)
    return { tiles, hiddenBySize: shelf.hiddenBySize, ...tally(tiles), addedOnly: !!added }
}

// The chats the viewer added (HX27): a rough video count, without reading or screening any of
// them, three chats at a time so the picker never floods Telegram; kept for a while and never for
// a failure, as the apps' ChatVideoCount does.
const VIDEO_COUNT_BATCH = 3
const VIDEO_COUNT_CACHE_MS = 10 * 60_000
const videoCountCache = new Map<number, { count: number; time: number }>()

/** A chat's rough video count: the two filters' totals Telegram's search already carries, summed; -1 when it could not be read, never cached. */
async function oneVideoCount(tg: TelegramClient, chatId: number): Promise<number> {
    const [videos, docs] = await Promise.all([
        tg.searchMessages({ chatId, filter: VIDEO_FILTER, limit: 1 }),
        tg.searchMessages({ chatId, filter: DOC_FILTER, limit: 1 }),
    ])
    return videos.total + docs.total
}

/** `chatIds`' rough video counts, by id; -1 for one Telegram could not answer. */
export async function videoCounts(tg: TelegramClient, chatIds: readonly number[]): Promise<Record<number, number>> {
    const now = Date.now()
    const out: Record<number, number> = {}
    const need: number[] = []
    for (const id of chatIds) {
        const hit = videoCountCache.get(id)
        if (hit && now - hit.time < VIDEO_COUNT_CACHE_MS) out[id] = hit.count
        else need.push(id)
    }
    for (let i = 0; i < need.length; i += VIDEO_COUNT_BATCH) {
        const batch = need.slice(i, i + VIDEO_COUNT_BATCH)
        await Promise.all(
            batch.map(async (chatId) => {
                try {
                    const count = await oneVideoCount(tg, chatId)
                    videoCountCache.set(chatId, { count, time: now })
                    out[chatId] = count
                } catch {
                    out[chatId] = -1
                }
            }),
        )
    }
    return out
}

// Recently added's See all

/** Its videos kept this long, so leaving the page and coming back carries on where it was. */
const RECENT_KEEP_MS = 3 * 60_000
/**
 * The most videos the page holds: past this it stops reading on, so an account with a hundred
 * thousand videos never fills the worker's memory or the page's grid.
 */
const RECENT_MAX = 2_000
/** Pages of Telegram's search (a hundred each) one ask for more may read, at most. */
const RECENT_ROUNDS = 4
/** New videos one ask for more is content with. */
const RECENT_ENOUGH = 30

/** Where Telegram's search across chats goes on from (mtcute's SearchGlobalOffset, not exported by @mtcute/web). */
type SearchGlobalOffset = NonNullable<NonNullable<Parameters<TelegramClient['searchGlobal']>[0]>['offset']>

interface GlobalFeed {
    filter: typeof VIDEO_FILTER | typeof DOC_FILTER
    next: SearchGlobalOffset | undefined
    done: boolean
    /** Date (seconds) of the oldest message read so far; Infinity before the first page. */
    oldest: number
}

interface RecentState {
    key: string
    /** Home's chats it keeps to; null for every chat (Home not built in this tab yet). */
    chats: Set<number> | null
    starredOnly: boolean
    feeds: GlobalFeed[]
    videos: Map<string, Video>
    items: Map<string, VideoItem>
    loading: Promise<void> | null
    time: number
}

let recentState: RecentState | null = null
/** Home's chats as its last build picked them, and whether they are all starred. */
let homeChatsKnown: { ids: number[]; starredOnly: boolean } | null = null

/** Home's chats as built, for Recently added's See all to read on through. */
export function rememberHomeChats(ids: number[], starredOnly: boolean) {
    homeChatsKnown = { ids: [...ids], starredOnly }
}

const recentDone = (s: RecentState) => s.videos.size >= RECENT_MAX || s.feeds.every((f) => f.done)

/**
 * Videos older than this may still have newer ones unread in the other feed (videos and files
 * page apart), so they wait until it catches up: cards are only ever added at the grid's end.
 */
function horizon(s: RecentState): number {
    if (recentDone(s)) return -Infinity
    return Math.max(...s.feeds.filter((f) => !f.done).map((f) => f.oldest))
}

/**
 * Reads on until enough new videos came, the rounds are spent, Telegram has no more or the page
 * holds its most. `gate` is the viewer's own added chats (HX27), null for every chat; it narrows
 * `s.chats` (Home's own chats for this run) further still, since a chat Home picked may since
 * have been taken out of the choice.
 */
async function loadRecent(tg: TelegramClient, s: RecentState, signal: AbortSignal, gate: Set<number> | null): Promise<void> {
    const r = await rules()
    let added = 0
    for (let round = 0; round < RECENT_ROUNDS && added < RECENT_ENOUGH && !recentDone(s) && !signal.aborted; round++) {
        const open = s.feeds.filter((f) => !f.done)
        // The feed read least far back holds the others' cards back: it is the one asked.
        const behind = Math.max(...open.map((f) => f.oldest))
        const ask = open.filter((f) => f.oldest === behind)
        const pages = await Promise.all(
            ask.map(async (f) => {
                const res = await tg.searchGlobal({ query: '', filter: f.filter, limit: PAGE, offset: f.next })
                const msgs = res as Message[]
                const before = f.next
                f.next = res.next
                // No next page, an empty one, or the same offset again (a loop on a bad answer): this feed is over.
                f.done = !res.next || !msgs.length || (before?.id === res.next.id && before?.rate === res.next.rate)
                for (const m of msgs) {
                    const at = m.date instanceof Date ? Math.floor(m.date.getTime() / 1000) : NaN
                    if (Number.isFinite(at)) f.oldest = Math.min(f.oldest, at)
                }
                // A page whose dates could not be read still moves the feed on, never back.
                if (!Number.isFinite(f.oldest)) f.oldest = -Infinity
                return msgs
            }),
        )
        const byId = new Map<string, Message>()
        const raws = []
        for (const msg of pages.flat()) {
            if (s.chats && !s.chats.has(msg.chat?.id)) continue
            if (gate && !gate.has(msg.chat?.id)) continue
            const raw = rawOf(msg, chatProtects(msg))
            if (!raw) continue
            const id = videoId(raw)
            if (s.videos.has(id) || byId.has(id)) continue
            byId.set(id, msg)
            raws.push(raw)
        }
        for (const v of r.screen(raws).videos) {
            if (s.videos.size >= RECENT_MAX) break
            const msg = byId.get(v.id)
            const item = msg && itemOf(msg, v.title, r.posterAsk(v), r.continueTitle(v), undefined, r.tileTag(v))
            if (!item || s.videos.has(v.id)) continue
            s.videos.set(v.id, v)
            s.items.set(v.id, item)
            added++
        }
    }
}

/**
 * Recently added's See all: the newest videos across Home's chats, grouped as a chat's are.
 * `open` reads its first page unless it was read a moment ago, `more` the next one (one read at
 * a time: a second ask while one runs waits for it), `view` only arranges again.
 */
export async function recentPage(tg: TelegramClient, view: ListView, mode: 'open' | 'more' | 'view', signal: AbortSignal): Promise<RecentPage> {
    const r = await rules()
    const gate = addedSetOf(view)
    const known = homeChatsKnown?.ids.length ? homeChatsKnown : null
    const key = known ? known.ids.join() : '*'
    let s = recentState
    if (!s || s.key !== key || (mode === 'open' && !s.loading && Date.now() - s.time > RECENT_KEEP_MS)) {
        s = recentState = {
            key,
            chats: known && new Set(known.ids),
            starredOnly: known?.starredOnly ?? false,
            feeds: [VIDEO_FILTER, DOC_FILTER].map((filter) => ({ filter, next: undefined, done: false, oldest: Infinity })),
            videos: new Map(),
            items: new Map(),
            loading: null,
            time: Date.now(),
        }
        mode = 'more'
    }
    const st = s
    // Left before its first page came: opened again, it reads that page now.
    if (mode === 'open' && !st.loading && !st.videos.size) mode = 'more'
    if (mode === 'more' && !recentDone(st)) {
        st.loading ??= loadRecent(tg, st, signal, gate).finally(() => (st.loading = null))
        await st.loading
    } else if (st.loading) await st.loading
    st.time = Date.now()
    const edge = horizon(st)
    // The shelf keeps the order it is given for Newest (a chat's own), so the two feeds are merged by date here.
    // A chat taken out of the choice since this was read leaves its videos out here too, without losing what was already kept.
    const shown = [...st.videos.values()]
        .filter((v) => v.date >= edge && (!gate || gate.has(v.chatId)))
        .sort((a, b) => b.date - a.date || b.messageId - a.messageId)
    // The same floor as Home's row (SizeFilter.HOME_MIN), said on the page.
    const shelf = r.shelf(shown, 'Newest', r.sizeHomeMin(view.minBytes), view.maxBytes, view.watched, view.started)
    return {
        tiles: tilesOf(shelf, st.items, view),
        hiddenBySize: shelf.hiddenBySize,
        done: recentDone(st),
        starredOnly: st.starredOnly,
        capped: st.videos.size >= RECENT_MAX && !st.feeds.every((f) => f.done),
    }
}

// The watch page

/** A chat's state for the watch page: the opened chat's, Home's, else its first page read now. */
async function stateFor(tg: TelegramClient, chatId: number): Promise<ChatState> {
    let s = chats.get(chatId) ?? homeStates.get(chatId)
    if (!s) {
        s = await fresh(tg, chatId)
        chats.set(chatId, s)
        while (chats.size > KEEP_CHATS) chats.delete(chats.keys().next().value!)
    } else if (s.loading) await s.loading
    return s
}

/** A message beyond the pages read so far (opened by its address), added through the rules; null when it is gone or not a video. */
async function addMessage(tg: TelegramClient, s: ChatState, messageId: number): Promise<Video | null> {
    const r = await rules()
    const [msg] = (await tg.getMessages(s.id, messageId)) as (Message | null)[]
    const raw = msg && rawOf(msg, s.protected)
    if (!msg || !raw) return null
    const [v] = r.screen([raw]).videos
    const item = v && itemOf(msg, v.title, r.posterAsk(v), r.continueTitle(v), undefined, r.tileTag(v))
    if (!v || !item) return null
    s.videos.set(v.id, v)
    s.items.set(v.id, item)
    s.version++
    return v
}

/** Pages of a chat read further to find the rest of a set of pieces that has a gap, before it counts as missing. */
const MORE_PAGES = 4

/**
 * The item for one message of a video cut into pieces (or a split ZIP), with every piece of it in
 * the chat: for a player opened on one message (Continue watching, an address) that was not handed
 * the set. A gap in the numbers reads a few more pages of the chat first, as the apps' lookUp
 * does. Null when the message is not one piece of a set.
 */
export async function joinedFor(tg: TelegramClient, chatId: number, messageId: number): Promise<VideoItem | null> {
    const r = await rules()
    rulesNow = r
    const s = await stateFor(tg, chatId)
    const id = `${chatId}:${messageId}`
    if (!s.videos.get(id) && !(await addMessage(tg, s, messageId))) return null
    for (let round = 0; ; round++) {
        const shelf = r.shelf([...s.videos.values()], 'Newest', 0, r.sizeLimits().ceiling, [], [])
        const tile = shelf.tiles.find((t) => t.kind === 'file' && t.pieces.includes(id))
        const own = s.items.get(id)
        if (!tile || !own) return null
        if (!tile.piecesFirstMissing || done(s) || round >= MORE_PAGES) return joinedItem(tile, own, s.items)
        await load(s, () => loadPage(tg, s))
    }
}

/**
 * The cards either side of a video in the chat's grid, in the order the grid is sorted in (the apps'
 * ChatOrder, here the grid itself): size limits skip a card as they hide it, and when the video is the
 * last card loaded and the chat has more pages, the next page is read (up to MORE_PAGES) before saying
 * there is no next one. Null for a video that is no card of its own (another copy of one).
 */
async function chatOrderAround(tg: TelegramClient, s: ChatState, view: ChatView, id: string): Promise<{ previous: VideoItem | null; next: VideoItem | null } | null> {
    for (let round = 0; ; round++) {
        const tiles = arrange(s, { ...view, query: '', chatOrder: undefined }).tiles
        const at = tiles.findIndex((tile) => videoId(tile.item) === id)
        if (at < 0) return null
        if (at < tiles.length - 1 || done(s) || round >= MORE_PAGES) return { previous: tiles[at - 1]?.item ?? null, next: tiles[at + 1]?.item ?? null }
        try {
            await load(s, () => loadPage(tg, s))
        } catch {
            // The chat could not be asked: what is loaded is the answer.
            return { previous: tiles[at - 1]?.item ?? null, next: null }
        }
    }
}

const watchKept = new Map<string, { version: number; view: string; info: WatchInfo }>()

/**
 * The watch page's parts for one video: its episodes and the ones either side (the app's
 * EpisodeNeighbours, through WebRules' `steps`), or the chat's other cards when it is not an
 * episode. `steps` arranges the whole chat, so the answer is kept until the chat or the view changes.
 */
export async function watchInfo(tg: TelegramClient, chatId: number, messageId: number, view: ChatView): Promise<WatchInfo | null> {
    const r = await rules()
    rulesNow = r
    const s = await stateFor(tg, chatId)
    const id = `${chatId}:${messageId}`
    const current = s.videos.get(id) ?? (await addMessage(tg, s, messageId))
    if (!current) return null
    const viewKey = JSON.stringify(view)
    const kept = watchKept.get(id)
    if (kept && kept.version === s.version && kept.view === viewKey) return kept.info
    const steps = r.steps(current, [...s.videos.values()])
    const item = (v: Video | null | undefined) => (v ? (s.items.get(v.id) ?? null) : null)
    const seasons = (steps.series?.seasons ?? []).map((season) => ({
        number: season.number,
        covered: Array.from(season.covered ?? []),
        episodes: season.episodes.flatMap((e) => {
            const it = s.items.get(e.video.id)
            return it ? [{ code: e.code, name: e.video.id === id ? (steps.current?.name ?? null) : null, item: it }] : []
        }),
    }))
    // A season, or a span, posted as one file; a season the chat holds only so is a season of the page too.
    const packs = (steps.series?.packs ?? []).flatMap((p) => {
        const it = s.items.get(p.video.id)
        return it ? [{ season: p.season, seasonEnd: p.seasonEnd > p.season ? p.seasonEnd : null, item: it }] : []
    })
    for (const n of new Set(packs.map((p) => p.season))) {
        if (!seasons.some((season) => season.number === n)) seasons.push({ number: n, covered: [], episodes: [] })
    }
    if (packs.length) seasons.sort((a, b) => a.number - b.number)
    let more: Tile[] = []
    if (!seasons.length) {
        more = arrange(s, { ...view, sort: 'Newest', query: '' })
            .tiles.filter((t) => videoId(t.item) !== id)
            .slice(0, 12)
    }
    // Every copy of it in the chat, found by the shelf's own grouping (the size limits left open, as the apps' copy list ignores them).
    const wholeShelf = r.shelf([...s.videos.values()], 'Newest', 0, r.sizeLimits().ceiling, [], [])
    const copyIds = new Set<string>()
    for (const t of wholeShelf.tiles) {
        if (t.kind === 'show') {
            for (const season of t.seasons) for (const e of season.episodes) if (e.video.id === id || e.copies.includes(id)) e.copies.forEach((c) => copyIds.add(c))
        } else if (t.video?.id === id || t.copies.includes(id)) t.copies.forEach((c) => copyIds.add(c))
    }
    // The same file posted twice (forwarded, or sent again: the same name and size) is one choice, the post this page is about standing for it.
    const seenFile = new Map<string, string>()
    for (const cid of copyIds) {
        const v = s.videos.get(cid)
        const key = v && v.fileName && v.size > 0 ? `${v.fileName}|${v.size}` : cid
        if (!seenFile.has(key) || cid === id) seenFile.set(key, cid)
    }
    const distinct = new Set(seenFile.values())
    const copies: CopyRef[] =
        distinct.size > 1
            ? [...distinct]
                  .flatMap((cid) => {
                      const v = s.videos.get(cid)
                      const it = s.items.get(cid)
                      if (!v || !it) return []
                      const facts = r.copyFacts(v)
                      // The quality heads the facts ("4K", "1080p"); a copy whose name states none (the size heads it) comes last, as the apps' picker has it.
                      const height = facts[0] === '4K' ? 2160 : /^(\d+)p$/.test(facts[0] ?? '') ? parseInt(facts[0]) : 0
                      return [{ item: it, facts, fileName: v.fileName, date: v.date, chat: s.title, height, size: v.size }]
                  })
                  .sort((a, b) => b.height - a.height || b.size - a.size)
                  .map(({ height: _h, size: _s, ...copy }) => copy)
            : []
    // After a video that is not an episode (the apps' ChatOrder): the chat's own list goes on, in the grid's sort.
    const around = view.chatOrder && !steps.next && !steps.previous && !seasons.length ? await chatOrderAround(tg, s, view, id) : null
    const info: WatchInfo = {
        title: steps.current?.show ?? r.tileTitle(current),
        episode: steps.current ? { code: steps.current.code, name: steps.current.name ?? null, show: steps.current.show } : null,
        previous: item(steps.previous) ?? around?.previous ?? null,
        previousCode: steps.previousLabel?.code ?? null,
        next: item(steps.next) ?? around?.next ?? null,
        nextCode: steps.nextLabel?.code ?? null,
        seasons,
        packs,
        more,
        fileName: current.fileName,
        width: current.width,
        height: current.height,
        chatTitle: s.title,
        protected: s.protected,
        quality: r.qualityLabel(current) ?? null,
        copies,
        overview: r.overviewOf(current) ?? null,
    }
    watchKept.set(id, { version: s.version, view: viewKey, info })
    while (watchKept.size > 20) watchKept.delete(watchKept.keys().next().value!)
    return info
}
