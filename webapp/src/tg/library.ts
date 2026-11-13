// Worker only: the chat list, and each opened chat's videos paged in from Telegram and arranged
// by the app's own rules (WebRules' screen and shelf) once per change, the answer kept until the
// chat, the order, the size limits or the words change. The page asks again on every scroll and
// gets the kept answer.
import type { Message, Peer, TelegramClient } from '@mtcute/web'
import { rules, type Shelf, type ShelfTile, type Video } from '../rules'
import { chatProtects, itemOf, rawOf, thumbnails, videoId } from './media'
import { forgetSponsored, loadSponsored } from './sponsored'
import type { ChatItem, CopyRef, ChatPage, ChatView, Found, ListView, RecentPage, SponsoredItem, Tile, VideoItem, WatchInfo } from './types'

const PAGE = 100
const VIDEO_FILTER = { _: 'inputMessagesFilterVideo' } as const
const DOC_FILTER = { _: 'inputMessagesFilterDocument' } as const
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
    archives: Map<number, string>
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
        archives: new Map(),
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
    const byId = new Map<number, Message>()
    for (const msg of pages.flat()) byId.set(msg.id, msg)
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
    const screened = r.screen(raws)
    s.selfDestructing += screened.selfDestructing
    for (const v of screened.videos) {
        const msg = byId.get(v.messageId)
        const item = msg && itemOf(msg, v.title, r.posterAsk(v), r.continueTitle(v))
        if (!item) continue
        s.videos.set(v.id, v)
        s.items.set(v.id, item)
    }
    s.version++
}

/** Runs one load at a time per chat: a second Refresh or scroll while one runs waits for it. */
function load(s: ChatState, run: () => Promise<void>): Promise<void> {
    s.loading ??= run().finally(() => (s.loading = null))
    return s.loading
}

const done = (s: ChatState) => s.feeds.every((f) => f.done)

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
    const shelf = r.shelf([...s.videos.values()], view.sort, view.minBytes, view.maxBytes, view.watched, view.started)
    let tiles = tilesOf(shelf, s.items, view)
    const query = view.query.trim()
    if (query) {
        const texts = tiles.map((t) => (t.kind === 'show' ? t.title : `${t.title} ${s.videos.get(videoId(t.item))?.fileName ?? ''}`))
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
        done: done(s),
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
    if (!s) s = await fresh(tg, chatId)
    else if (mode === 'more' && !done(s)) {
        const st = s
        await load(st, () => loadPage(tg, st))
    } else if (s.loading) await s.loading
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

/**
 * The newest videos across every chat for [query] (all of them for a blank one), through the
 * rules, with their items. `added` leaves out chats not in the viewer's choice (HX27), null for
 * every chat.
 */
export async function across(tg: TelegramClient, query: string, added: Set<number> | null = null): Promise<{ videos: Video[]; items: Map<string, VideoItem> }> {
    const r = await rules()
    let hit = searches.get(query)
    if (!hit || Date.now() - hit.time > 120_000) {
        const limit = query ? 60 : 100
        hit = {
            time: Date.now(),
            messages: Promise.all([
                tg.searchGlobal({ query, filter: VIDEO_FILTER, limit }),
                tg.searchGlobal({ query, filter: DOC_FILTER, limit }),
            ]).then(([a, b]) => [...a, ...b] as Message[]),
        }
        hit.messages.catch(() => searches.delete(query))
        searches.set(query, hit)
        for (const [k, v] of searches) if (Date.now() - v.time > 120_000) searches.delete(k)
    }
    const messages = await hit.messages
    const byId = new Map<string, Message>()
    const raws = []
    for (const msg of messages) {
        if (added && !added.has(msg.chat?.id)) continue
        const raw = rawOf(msg, chatProtects(msg))
        if (!raw) continue
        byId.set(videoId(raw), msg)
        raws.push(raw)
    }
    const videos: Video[] = []
    const items = new Map<string, VideoItem>()
    for (const v of r.screen(raws).videos) {
        const item = itemOf(byId.get(v.id)!, v.title, r.posterAsk(v), r.continueTitle(v))
        if (!item || items.has(v.id)) continue
        items.set(v.id, item)
        videos.push(v)
    }
    return { videos, items }
}

/** Videos across every chat within the size limits, grouped into shows, copies and pieces as a chat's are, newest first. */
export async function searchAll(tg: TelegramClient, query: string, view: ListView): Promise<Found> {
    const r = await rules()
    const { videos, items } = await across(tg, query, addedSetOf(view))
    const shelf = r.shelf(videos, 'Newest', view.minBytes, view.maxBytes, view.watched, view.started)
    return { tiles: tilesOf(shelf, items, view), hiddenBySize: shelf.hiddenBySize }
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
            const item = msg && itemOf(msg, v.title, r.posterAsk(v), r.continueTitle(v))
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
    const shelf = r.shelf(shown, 'Newest', view.minBytes, view.maxBytes, view.watched, view.started)
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
    const item = v && itemOf(msg, v.title, r.posterAsk(v), r.continueTitle(v))
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
        episodes: season.episodes.flatMap((e) => {
            const it = s.items.get(e.video.id)
            return it ? [{ code: e.code, name: e.video.id === id ? (steps.current?.name ?? null) : null, item: it }] : []
        }),
    }))
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
    const info: WatchInfo = {
        title: steps.current?.show ?? r.tileTitle(current),
        episode: steps.current ? { code: steps.current.code, name: steps.current.name ?? null, show: steps.current.show } : null,
        previous: item(steps.previous),
        previousCode: steps.previousLabel?.code ?? null,
        next: item(steps.next),
        nextCode: steps.nextLabel?.code ?? null,
        seasons,
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
