// Play all on the web (WP-32): one press starts a run, and the end of each video goes on to the next
// without the Autoplay setting (a press is the request to play on, as on the phone, TV and desktop:
// PlayAll.playsOn). "Still watching?" and the sleep timer still stop it.
//
// A chat's run needs no queue here: the first listed video starts and what follows is the chat's own
// order a page at a time (core's rule, `chatOrder` forced on for the run, never the whole chat read
// first). A list's run is the list's cards in the order the page shows them, kept as keys only (at most
// `QUEUE_MAX`), so a reload of the tab rebuilds the queue from the address and the keys: the position is
// the video that is open. The run lives in the tab (sessionStorage, 12 hours), ends when the watch page
// closes or opens a video that is not part of it, and is never synced or backed up.
import type { VideoItem } from '../tg/types'

export const QUEUE_MAX = 2000
const LIFE_MS = 12 * 3600_000
const STORE = 'tmplayer.playall'

export type Run = { kind: 'chat'; chatId: number } | { kind: 'list'; keys: string[] }

/** A video's id in a queue. */
export const keyOf = (v: Pick<VideoItem, 'chatId' | 'messageId'>) => `${v.chatId}:${v.messageId}`

/** The keys of a list's cards, no repeat, the page's order, at most `QUEUE_MAX`. */
export function queueOf(items: readonly Pick<VideoItem, 'chatId' | 'messageId'>[]): string[] {
    const seen = new Set<string>()
    for (const v of items) {
        if (seen.size >= QUEUE_MAX) break
        seen.add(keyOf(v))
    }
    return [...seen]
}

/** The keys before (nearest first) and after `key` in a queue; both empty when `key` is not in it. */
export function around(keys: readonly string[], key: string): { previous: string[]; next: string[] } {
    const i = keys.indexOf(key)
    if (i < 0) return { previous: [], next: [] }
    return { previous: keys.slice(0, i).reverse(), next: keys.slice(i + 1) }
}

/** Whether a video belongs to the run: any video of the chat for a chat's run, a queued one for a list's. */
export function belongs(run: Run, v: Pick<VideoItem, 'chatId' | 'messageId'>): boolean {
    return run.kind === 'chat' ? run.chatId === v.chatId : run.keys.includes(keyOf(v))
}

/** A stored run, read as what it is: anything that is not a run of ours, or is stale, is nothing (outside data). */
export function readRun(raw: unknown, now: number): Run | null {
    if (typeof raw !== 'string' || raw.length > 400_000) return null
    let o: unknown
    try {
        o = JSON.parse(raw)
    } catch {
        return null
    }
    if (!o || typeof o !== 'object') return null
    const r = o as Record<string, unknown>
    if (typeof r.at !== 'number' || !(now - r.at < LIFE_MS) || r.at > now + 60_000) return null
    if (r.kind === 'chat' && typeof r.chatId === 'number' && Number.isFinite(r.chatId)) return { kind: 'chat', chatId: r.chatId }
    if (r.kind === 'list' && Array.isArray(r.keys)) {
        const keys = r.keys.filter((k): k is string => typeof k === 'string' && /^-?\d{1,20}:\d{1,20}$/.test(k)).slice(0, QUEUE_MAX)
        return keys.length ? { kind: 'list', keys } : null
    }
    return null
}

let current: Run | null | undefined
const store = () => {
    try {
        return typeof sessionStorage === 'undefined' ? null : sessionStorage
    } catch {
        return null
    }
}

function save(run: Run | null) {
    current = run
    try {
        if (run) store()?.setItem(STORE, JSON.stringify({ ...run, at: Date.now() }))
        else store()?.removeItem(STORE)
    } catch {
        // A browser that refuses storage keeps the run in the page; a reload then ends it.
    }
}

/** The run in force, or null. */
export function activeRun(): Run | null {
    if (current !== undefined) return current
    let raw: string | null = null
    try {
        raw = store()?.getItem(STORE) ?? null
    } catch {
        raw = null
    }
    current = readRun(raw, Date.now())
    return current
}

/** Play all of a chat: from the video the caller opens, the chat's order follows. */
export function startChatRun(chatId: number) {
    save({ kind: 'chat', chatId })
}

/** Play all of a list: its cards in the page's order, kept as keys (the next video is asked of its chat when it is due). */
export function startListRun(items: readonly Pick<VideoItem, 'chatId' | 'messageId'>[]) {
    const keys = queueOf(items)
    save(keys.length ? { kind: 'list', keys } : null)
}

/** The run is over: the watch page closed, or another video opened. */
export function endRun() {
    if (activeRun() !== null) save(null)
}

/**
 * The first video of `keys` that still exists: a video gone from its chat (`get` says null) is stepped over,
 * a few at most; a failed ask (offline, a flood wait) stops there, since that is no reason to skip.
 */
export async function nearest(keys: readonly string[], get: (key: string) => Promise<VideoItem | null>, tries = 4): Promise<VideoItem | null> {
    for (const key of keys.slice(0, tries)) {
        try {
            const found = await get(key)
            if (found) return found
        } catch {
            return null
        }
    }
    return null
}
