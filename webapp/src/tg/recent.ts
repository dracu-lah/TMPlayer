// Recently added's See all: Telegram's search across chats, read on a page at a time into one kept state.
// Worker only.
import type { Message, TelegramClient } from '@mtcute/web'
import { addedSetOf, DOC_FILTER, PAGE, tilesOf, VIDEO_FILTER } from './library'
import { rules, type Video } from '../rules'
import { chatProtects, itemOf, rawOf, videoId } from './media'
import type { ListView, RecentPage, VideoItem } from './types'

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

/** A sign out: the kept state was the account's. */
export function forgetRecent() {
    recentState = null
    homeChatsKnown = null
}

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
        tiles: tilesOf(shelf, st.items, view, { r, videoOf: (id) => st.videos.get(id) }),
        hiddenBySize: shelf.hiddenBySize,
        done: recentDone(st),
        starredOnly: st.starredOnly,
        capped: st.videos.size >= RECENT_MAX && !st.feeds.every((f) => f.done),
    }
}
