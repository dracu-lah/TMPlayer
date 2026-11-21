// Worker only: the chat list, and each opened chat's videos paged in from Telegram and arranged
// by the app's own rules (WebRules' screen and shelf) once per change, the answer kept until the
// chat, the order, the size limits or the words change. The page asks again on every scroll and
// gets the kept answer.
import type { Message, TelegramClient } from '@mtcute/web'
import { rules, type Rules, type Shelf, type ShelfTile, type Video } from '../rules'
import { chatProtects, itemOf, photosAmong, rawOf, thumbnails, videoId } from './media'
import { forgetChatTitles, noteChatTitle } from './hints'
import { forgetSponsored, loadSponsored } from './sponsored'
import { tally } from '../ui/searchTally'
import { cleanContext, correctionLines, NO_COPY_CONTEXT, type CopyContext } from './copyFit'
import { copyRows, groupRefs, playCopy } from './copyPick'
import { touchesFor } from './touchCache'
import { createVideoCounter } from './counts'
import { forgetDialogs } from './dialogs'
import { forgetRecent } from './recent'
import type { CopyRef, ChatPage, ChatView, Found, ListView, SponsoredItem, Tile, VideoItem, WatchInfo } from './types'

export const PAGE = 100
/** Messages asked per filter of Telegram's search inside a chat. */
const SEARCH_PAGE = 50
export const VIDEO_FILTER = { _: 'inputMessagesFilterVideo' } as const
export const DOC_FILTER = { _: 'inputMessagesFilterDocument' } as const
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
export const addedSetOf = (view: Pick<ListView, 'added'>): Set<number> | null => (view.added ? new Set(view.added) : null)

// What the page says of the viewer's copies (HX38, copyFit.ts): the device, the picture kinds the
// browser cannot play natively, the files kept apart, the copies chosen, the usual quality. A change
// bumps the epoch, so every kept arrangement and watch answer is built again with it.
let copyCtx: CopyContext = NO_COPY_CONTEXT
let copyEpoch = 0

/** The page's copy context (copies.ts), cleaned: the device the copies suit, "Not the same video" and the rest. */
export async function setCopyContext(raw: unknown): Promise<void> {
    const r = await rules()
    const next = cleanContext(raw)
    if (JSON.stringify(next) === JSON.stringify(copyCtx)) return
    copyCtx = next
    r.setCopyDevice(next.device)
    const lines = correctionLines(next, r.correctionSet)
    r.setCorrections(lines.ids, lines.texts)
    copyEpoch++
    watchKept.clear()
    // A file kept apart or grouped again: the cards remember their groups afresh.
    groups.clear()
}

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
    /** A forum chat read through one of its topics (`ChatView.topic`): its pages are the topic's own, by thread. */
    topic: { id: number; name: string } | null
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
/** Forum chats held to one topic, by "chat:topic": a few at a time, apart from the chat's own state. */
const topicChats = new Map<string, ChatState>()
const KEEP_TOPIC_CHATS = 3

export function forgetAll() {
    chats.clear()
    topicChats.clear()
    forgetDialogs()
    homeStates.clear()
    searches.clear()
    watchKept.clear()
    groups.clear()
    // The files kept apart and the copies chosen were the account's: the next one starts with none (the device stays).
    if (copyCtx.apart.length || copyCtx.chosen.length || copyCtx.habit || copyCtx.titles.length) {
        copyCtx = { ...copyCtx, apart: [], chosen: [], habit: null, titles: [] }
        copyEpoch++
        rulesNow?.setCorrections([], [])
    }
    forgetRecent()
    videoCounter.forget()
    forgetSponsored()
    forgetChatTitles()
}

/** A topic's name, or '' when it cannot be read (the grid still lists its videos). */
async function topicName(tg: TelegramClient, chatId: number, topic: number): Promise<string> {
    try {
        const found = await tg.getForumTopicsById(chatId, [topic])
        const t = found[0]
        return t && !t.titleMissing ? String(t.title ?? '').slice(0, 200) : ''
    } catch {
        return ''
    }
}

async function openState(tg: TelegramClient, chatId: number, topic = 0): Promise<ChatState> {
    const peer = await tg.getPeer(chatId)
    const small = peer.photo?.small ?? null
    if (small) thumbnails.set(small.uniqueFileId, small)
    noteChatTitle(chatId, peer.displayName)
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
        topic: topic ? { id: topic, name: await topicName(tg, chatId, topic) } : null,
        version: 0,
        loading: null,
        kept: null,
        sponsored: [],
        sponsoredBetween: 0,
        time: Date.now(),
        channel: peer.type === 'chat' && peer.chatType === 'channel',
    }
}

/** A topic's reads go through its thread: only its messages come. */
const threadOf = (s: ChatState) => (s.topic ? { threadId: s.topic.id } : {})

/** The next hundred of each kind (videos, files) the chat has not sent yet, through the rules. */
async function loadPage(tg: TelegramClient, s: ChatState): Promise<void> {
    const r = await rules()
    const pages = await Promise.all(
        s.feeds
            .filter((f) => !f.done)
            .map(async (f) => {
                const res = await tg.searchMessages({ chatId: s.id, filter: f.filter, limit: PAGE, offset: f.next, ...threadOf(s) })
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
                        const res = await tg.searchMessages({ chatId: s.id, query: spelling, filter: f.filter, limit: SEARCH_PAGE, offset: f.next, ...threadOf(s) })
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
                .searchMessages({ chatId: s.id, query: spelling, filter: PHOTO_FILTER, limit: ALBUM_READS * 2, ...threadOf(s) })
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
export function tilesOf(
    shelf: Pick<Shelf, 'tiles'>,
    items: Map<string, VideoItem>,
    view: Pick<ListView, 'watched' | 'started'>,
    /** The rules and the videos the copies are read from, for the copy each card plays (HX38). */
    copies?: { r: Rules; videoOf: (id: string) => Video | undefined },
): Tile[] {
    const watched = new Set(view.watched)
    const started = new Set(view.started)
    const tiles: Tile[] = []
    for (const t of shelf.tiles) {
        const video = t.kind === 'show' ? upNext(t, watched, started) : t.video
        const lead = video && items.get(video.id)
        if (!lead) continue
        // A video posted more than once plays, and wears the badge of, the copy its page recommends
        // (HX38): the viewer's choice, the one started, else the best this browser plays natively.
        const played = t.kind === 'file' && t.copies.length > 1 && copies ? pickedItem(t, items, started, copies.r, copies.videoOf) : null
        const item = t.kind === 'show' ? lead : (played ?? joinedItem(t, lead, items))
        tiles.push({
            key: t.key,
            kind: t.kind === 'show' ? 'show' : 'file',
            title: t.title,
            item,
            seasons: t.seasons.length,
            episodes: t.seasons.reduce((n, season) => n + season.episodes.length, 0),
            copies: Math.max(0, t.copies.length - 1),
            versions: t.kind === 'show' ? 1 : Math.max(1, t.versions),
            parts: t.parts.length,
            pieces: t.pieces.length,
            extra: t.extra ?? null,
        })
    }
    return tiles
}

/** `videos` by id, the map made only when a card with copies first asks. */
export function lookup(videos: Iterable<Video>): (id: string) => Video | undefined {
    let byId: Map<string, Video> | null = null
    return (id) => {
        if (!byId) {
            byId = new Map()
            for (const v of videos) byId.set(v.id, v)
        }
        return byId.get(id)
    }
}

/** The item of the copy `t` plays when it is not its lead; null to play the lead as it is. */
function pickedItem(t: ShelfTile, items: Map<string, VideoItem>, started: ReadonlySet<string>, r: Rules, videoOf: (id: string) => Video | undefined): VideoItem | null {
    const copies = t.copies.flatMap((id) => videoOf(id) ?? [])
    rememberGroup(copies, items)
    const id = playCopy(r, copies, copyCtx, started)
    return id && id !== t.video?.id ? (items.get(id) ?? null) : null
}

/**
 * The copies a card counted, by each copy's id, so its page lists the very same group (HX38): a
 * card in Search or Home's Recently added folds copies from several chats, which the page's own
 * chat cannot regroup. Bounded: the oldest groups go first.
 */
const groups = new Map<string, { videos: Video[]; items: Map<string, VideoItem> }>()
const GROUPS_MAX = 4_000

function rememberGroup(videos: Video[], items: Map<string, VideoItem>) {
    if (videos.length < 2 || !videos.some((v, i) => i > 0 && v.chatId !== videos[0].chatId)) return
    const group = { videos, items: new Map(videos.flatMap((v) => (items.has(v.id) ? [[v.id, items.get(v.id)!] as const] : []))) }
    for (const v of videos) {
        groups.delete(v.id)
        groups.set(v.id, group)
    }
    while (groups.size > GROUPS_MAX) groups.delete(groups.keys().next().value!)
}

/** The group of copies across chats a card counted for `id`, if one did; for tests too. */
export function groupOf(id: string): { videos: Video[]; items: Map<string, VideoItem> } | undefined {
    return groups.get(id)
}

function arrange(s: ChatState, view: ChatView): ChatPage {
    const key = `${copyEpoch}|${JSON.stringify(view)}`
    if (s.kept && s.kept.version === s.version && s.kept.view === key) return s.kept.page
    const r = rulesNow!
    const query = view.query.trim()
    // The Last played order's times, by the version the view names (held once, not posted with every view).
    const lastPlayed = touchesFor(view.touches)
    // Under words, what Telegram's search found beyond the loaded pages is listed with them.
    const videos = [...s.videos.values()]
    if (query) for (const [id, v] of s.hits) if (!s.videos.has(id)) videos.push(v)
    // Words list what Telegram found, whatever the chat's filters say: a filter would hide the very thing looked for.
    const shelf = r.shelfView(
        videos,
        view.sort,
        query ? 'All' : (view.filter ?? 'All'),
        query ? null : (view.facets ?? '').slice(0, 200),
        view.minBytes,
        view.maxBytes,
        view.watched,
        view.started,
        lastPlayed.ids,
        lastPlayed.times,
        Math.floor(Date.now() / 1000),
    )
    let tiles = tilesOf(shelf, s.items, view, { r, videoOf: (id) => s.videos.get(id) ?? s.hits.get(id) })
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
        total: shelf.total,
        hiddenByFilter: shelf.hiddenByFilter,
        unknown: shelf.unknown,
        languages: shelf.languages.slice(0, 40),
        loaded: s.videos.size,
        done: query ? searchDone(s, query) : done(s),
        archives: [...s.archives.entries()].sort((a, b) => b[0] - a[0]).map(([, name]) => name),
        selfDestructing: s.selfDestructing,
        protected: s.protected,
        sponsored: s.sponsored,
        sponsoredBetween: s.sponsoredBetween,
        ...(s.topic ? { topic: s.topic } : {}),
    }
    s.kept = { version: s.version, view: key, page }
    return page
}

const opening = new Map<number | string, Promise<ChatState>>()

/** A chat read again from its newest message; a second open or Refresh while one runs waits for it. */
function fresh(tg: TelegramClient, chatId: number, topic = 0): Promise<ChatState> {
    const key = topic ? `${chatId}:${topic}` : chatId
    let p = opening.get(key)
    if (!p) {
        p = (async () => {
            const s = await openState(tg, chatId, topic)
            const [, ads] = await Promise.all([loadPage(tg, s), s.channel && !topic ? loadSponsored(tg, chatId) : null])
            if (ads) {
                s.sponsored = ads.items
                s.sponsoredBetween = ads.between
            }
            return s
        })().finally(() => opening.delete(key))
        opening.set(key, p)
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
    // A topic of a forum chat (the apps' topic tab): its own state, read through the topic's thread.
    const topic = Number.isSafeInteger(view.topic) && view.topic! > 0 ? view.topic! : 0
    const topicKey = `${chatId}:${topic}`
    let s = mode === 'refresh' ? undefined : topic ? topicChats.get(topicKey) : chats.get(chatId)
    const opened = !s
    if (!s) s = await fresh(tg, chatId, topic)
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
    if (topic) {
        topicChats.delete(topicKey)
        topicChats.set(topicKey, s)
        while (topicChats.size > KEEP_TOPIC_CHATS) topicChats.delete(topicChats.keys().next().value!)
    } else {
        chats.delete(chatId)
        chats.set(chatId, s)
        while (chats.size > KEEP_CHATS) chats.delete(chats.keys().next().value!)
    }
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
            out.push({ at, tile: { key: `show-${item.chatId}-${line.show}`, kind: 'show', title: item.title, item, seasons: 0, episodes: 0, copies: 0, versions: 1, parts: 0, pieces: 0, extra: null } })
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
    const tiles = tilesOf(shelf, items, view, { r, videoOf: lookup(videos) })
    return { tiles, hiddenBySize: shelf.hiddenBySize, ...tally(tiles), addedOnly: !!added }
}

// The chats the viewer added (HX27): a rough video count, without reading or screening any of
// them, three chats at a time so the picker never floods Telegram (tg/counts.ts holds the budget: kept for a
// while, never for a failure, a flood wait waited out once and then refused), as the apps' ChatVideoCount does.

/** A chat's rough video count: the two filters' totals Telegram's search already carries, summed. */
async function oneVideoCount(tg: TelegramClient, chatId: number): Promise<number> {
    const [videos, docs] = await Promise.all([
        tg.searchMessages({ chatId, filter: VIDEO_FILTER, limit: 1 }),
        tg.searchMessages({ chatId, filter: DOC_FILTER, limit: 1 }),
    ])
    return videos.total + docs.total
}

let counterClient: TelegramClient | null = null
const videoCounter = createVideoCounter((id) => oneVideoCount(counterClient!, id))

/** `chatIds`' rough video counts, by id; -1 for one Telegram could not answer, -2 once it refused (a flood wait). */
export function videoCounts(tg: TelegramClient, chatIds: readonly number[], signal?: AbortSignal): Promise<Record<number, number>> {
    counterClient = tg
    return videoCounter.counts(chatIds, signal)
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
    // The group a card across chats counted is part of the answer: a card that counts another one asks again.
    const viewKey = `${copyEpoch}|${groupOf(id)?.videos.map((v) => v.id).join(',') ?? ''}|${JSON.stringify(view)}`
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
    // One read of the picture for the badge, the copies list and the order (QualityRead, HX38).
    const read = r.qualityRead(current)
    // Every copy of it in the chat, found by the shelf's own grouping (the size limits left open, as the apps' copy list ignores them).
    const wholeShelf = r.shelf([...s.videos.values()], 'Newest', 0, r.sizeLimits().ceiling, [], [])
    const copyIds = new Set<string>()
    for (const t of wholeShelf.tiles) {
        if (t.kind === 'show') {
            for (const season of t.seasons) for (const e of season.episodes) if (e.video.id === id || e.copies.includes(id)) e.copies.forEach((c) => copyIds.add(c))
        } else if (t.video?.id === id || t.copies.includes(id)) t.copies.forEach((c) => copyIds.add(c))
    }
    // In the order the list shows them, recommended first, the same file posted twice once (copyPick.ts): its length is the count.
    // A card that folded copies from several chats (Search, Recently added) handed its group on: the page lists the same one, so the count and the list agree.
    const across = groupOf(id)
    const copyVideos = across ? across.videos : [...copyIds].flatMap((cid) => s.videos.get(cid) ?? [])
    const copies: CopyRef[] = copyRows(r, copyVideos, id, copyCtx, view.started).flatMap((row) => {
        const it = s.items.get(row.video.id) ?? across?.items.get(row.video.id)
        const chat = row.video.chatId === s.id ? s.title : (it?.chat ?? '')
        return it ? [{ item: it, facts: r.copyFacts(row.video), fileName: row.video.fileName, date: row.video.date, chat, best: row.best, fit: row.fit, started: row.started, mismatch: row.mismatch }] : []
    })
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
        quality: read.label ?? null,
        qualityHeight: read.height ?? null,
        copies: copies.length > 1 ? copies : [],
        group: copyVideos.length > 1 ? groupRefs(copyVideos) : [],
        overview: r.overviewOf(current) ?? null,
    }
    watchKept.set(id, { version: s.version, view: viewKey, info })
    while (watchKept.size > 20) watchKept.delete(watchKept.keys().next().value!)
    return info
}
