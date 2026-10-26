// Worker only: the chat list, and each opened chat's videos paged in from Telegram and arranged
// by the app's own rules (WebRules' screen and shelf) once per change, the answer kept until the
// chat, the order, the size limits or the words change. The page asks again on every scroll and
// gets the kept answer.
import type { Message, Peer, TelegramClient } from '@mtcute/web'
import { rules, type Shelf, type ShelfTile, type Video } from '../rules'
import { chatProtects, itemOf, rawOf, thumbnails, videoId } from './media'
import { forgetSponsored, loadSponsored } from './sponsored'
import type { ChatItem, CopyRef, ChatPage, ChatView, Found, ListView, SponsoredItem, Tile, VideoItem, WatchInfo } from './types'

const PAGE = 100
const VIDEO_FILTER = { _: 'inputMessagesFilterVideo' } as const
const DOC_FILTER = { _: 'inputMessagesFilterDocument' } as const
/** Chats kept arranged at once; the oldest opened goes first. */
const KEEP_CHATS = 6

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
    homeStates.clear()
    searches.clear()
    watchKept.clear()
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
            const archive = r.archive(raw.fileName, raw.mimeType)
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

/** The page's cards for the rules' tiles, each playing an item `items` holds; a show plays its episode up next. */
export function tilesOf(shelf: Pick<Shelf, 'tiles'>, items: Map<string, VideoItem>, view: Pick<ListView, 'watched' | 'started'>): Tile[] {
    const watched = new Set(view.watched)
    const started = new Set(view.started)
    const tiles: Tile[] = []
    for (const t of shelf.tiles) {
        const video = t.kind === 'show' ? upNext(t, watched, started) : t.video
        const item = video && items.get(video.id)
        if (!item) continue
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

/**
 * Every chat outside the archive, pinned first, in Telegram's order. The list arrives in pages
 * of a hundred, each sent on to `onBatch` as it comes; it is kept for the session, and
 * `refresh` asks again.
 */
export async function chatList(tg: TelegramClient, refresh: boolean, onBatch: (chats: ChatItem[]) => void): Promise<ChatItem[]> {
    if (dialogs && !refresh) return dialogs
    dialogListeners.add(onBatch)
    try {
        dialogsLoading ??= (async () => {
            const out: ChatItem[] = []
            const seen = new Set<number>()
            for await (const d of tg.iterDialogs({ archived: 'exclude', pinned: 'include' })) {
                if (seen.has(d.peer.id)) continue
                seen.add(d.peer.id)
                out.push(chatItem(d.peer, d.isPinned))
                if (out.length % PAGE === 0) {
                    dialogsSoFar = out.slice()
                    for (const fn of dialogListeners) fn(dialogsSoFar)
                }
            }
            dialogs = out
            return out
        })().finally(() => (dialogsLoading = null))
        return await dialogsLoading
    } finally {
        dialogListeners.delete(onBatch)
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

/** The newest videos across every chat for [query] (all of them for a blank one), through the rules, with their items. */
export async function across(tg: TelegramClient, query: string): Promise<{ videos: Video[]; items: Map<string, VideoItem> }> {
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
    const { videos, items } = await across(tg, query)
    const shelf = r.shelf(videos, 'Newest', view.minBytes, view.maxBytes, view.watched, view.started)
    return { tiles: tilesOf(shelf, items, view), hiddenBySize: shelf.hiddenBySize }
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
    }
    watchKept.set(id, { version: s.version, view: viewKey, info })
    while (watchKept.size > 20) watchKept.delete(watchKept.keys().next().value!)
    return info
}
