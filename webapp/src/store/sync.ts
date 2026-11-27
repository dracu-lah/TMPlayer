// Resume across devices (sync/local.ts): what a sync reads of this device's lists and what it writes
// into them.
import type { VideoItem } from '../tg/types'
import { encodePick, type Pick } from '../pick'
import { admit, cleanTitle } from '../rename'
import { cleanResume } from '../ui/stored'
import { all, allEntries, id, read, remove, removeRaw, VIDEO_KEY, write } from './kv'
import { setWatchedTick, watchedTick, type Resume } from './history'
import { laterIds, setLaterIds, type Later } from './later'
import { setStarredIds, starredIds } from './chats'
import { picks, setPicks } from './picks'
import { forgetTitle, setTitles, TITLES_MAX, titles } from './titles'

// Resume across devices (sync/local.ts): what a sync reads of this device's lists and what it writes
// into them. Reads are the lists as stored; writes are quiet (no removal noted: a removal that came
// in from the file is the file's own) and keep the times the file gave.

/** Removals the viewer made, by the apps' key, for the sync file (see `tombOf`); anything older than `since` is swept. */
export async function syncTombs(since: number): Promise<{ list: 'watched' | 'later' | 'correction'; key: string; at: number }[]> {
    const out: { list: 'watched' | 'later' | 'correction'; key: string; at: number }[] = []
    for (const [prefix, list] of [['xw:', 'watched'], ['xl:', 'later'], ['xfix:', 'correction']] as const) {
        for (const [k, at] of await allEntries<number>(prefix)) {
            if (typeof at !== 'number' || at < since) removeRaw(k)
            else out.push({ list, key: k.slice(prefix.length), at })
        }
    }
    return out
}

/** The picks as stored with when each was last changed (1 for one stored before the time was kept): the sync file's corrections. */
export async function syncPicks(): Promise<{ chatId: number; messageId: number; text: string; at: number }[]> {
    const [stored, times] = await Promise.all([allEntries<unknown>('fix:'), allEntries<number>('fixat:')])
    const out: { chatId: number; messageId: number; text: string; at: number }[] = []
    for (const [k, text] of stored) {
        const [chatId, messageId] = k.slice('fix:'.length).split(':').map(Number)
        if (!Number.isSafeInteger(chatId) || !Number.isSafeInteger(messageId) || typeof text !== 'string') continue
        const at = times.get(`fixat:${chatId}:${messageId}`)
        out.push({ chatId, messageId, text, at: typeof at === 'number' && at > 0 ? at : 1 })
    }
    return out
}

/** The renames as stored with when each was last changed (1 for one stored with no time): the sync file's `title=` lines. */
export async function syncTitles(): Promise<{ chatId: number; messageId: number; title: string; at: number }[]> {
    const [stored, times] = await Promise.all([allEntries<unknown>('title:'), allEntries<number>('titleat:')])
    const out: { chatId: number; messageId: number; title: string; at: number }[] = []
    for (const [k, value] of stored) {
        const key = k.slice('title:'.length)
        const title = cleanTitle(value)
        if (!title || !VIDEO_KEY.test(key) || out.length >= TITLES_MAX) continue
        const [chatId, messageId] = key.split(':').map(Number)
        const at = times.get(`titleat:${key}`)
        out.push({ chatId, messageId, title, at: typeof at === 'number' && at > 0 ? at : 1 })
    }
    return out
}

/** The renames the viewer reset, by the apps' key, newer than `since` (older ones are swept). */
export async function syncTitleTombs(since: number): Promise<{ key: string; at: number }[]> {
    const out: { key: string; at: number }[] = []
    for (const [k, at] of await allEntries<number>('xtitle:')) {
        if (typeof at !== 'number' || at < since) removeRaw(k)
        else out.push({ key: k.slice('xtitle:'.length), at })
    }
    return out
}

/** A rename from the file (null: none), with the time the file gave it. Quiet: no removal is noted for what the file took off. */
export function syncPutTitle(chatId: number, messageId: number, title: string | null, at: number) {
    const key = id(chatId, messageId)
    const clean = cleanTitle(title)
    let next: ReadonlyMap<string, string> = titles()
    if (clean) {
        const put = admit(next, key, clean, TITLES_MAX)
        next = put.next
        if (put.evicted) forgetTitle(put.evicted)
        write(`title:${key}`, clean)
        write(`titleat:${key}`, at)
    } else if (next.has(key)) {
        const left = new Map(next)
        left.delete(key)
        next = left
        remove(`title:${key}`, true)
        remove(`titleat:${key}`, true)
    } else return
    setTitles(next)
}

/** The starred chats with when each was starred. */
export async function syncStars(): Promise<{ chatId: number; time: number }[]> {
    return (await all<{ chatId: number; time: number }>('star:')).filter((s) => Number.isSafeInteger(s?.chatId))
}

/** Once a sync has written into the lists: what shows them (Home's rows, History) reads them again. */
export function syncWrote() {
    setWatchedTick(watchedTick() + 1)
}

/** A place that came in from the file, with the time the file gave it. */
export function syncPutResume(r: Resume) {
    write(`resume:${id(r.chatId, r.messageId)}`, r)
}

export function syncDropResume(chatId: number, messageId: number) {
    remove(`resume:${id(chatId, messageId)}`, true)
}

/** A watched mark from the file (a place it covers is forgotten, as finishing the video does). */
export async function syncPutWatched(chatId: number, messageId: number, time: number) {
    const key = id(chatId, messageId)
    write(`watched:${key}`, time)
    const place = cleanResume(await read<unknown>(`resume:${key}`))
    if (place && place.time <= time) remove(`resume:${key}`, true)
    setWatchedTick(watchedTick() + 1)
}

export function syncDropWatched(chatId: number, messageId: number) {
    remove(`watched:${id(chatId, messageId)}`, true)
    setWatchedTick(watchedTick() + 1)
}

/** A Watch later line from the file, once its video is read from Telegram. */
export function syncPutLater(item: VideoItem, time: number) {
    const key = id(item.chatId, item.messageId)
    write(`later:${key}`, { item, time } satisfies Later)
    setLaterIds(new Set([...laterIds(), key]))
}

export function syncDropLater(chatId: number, messageId: number) {
    const key = id(chatId, messageId)
    remove(`later:${key}`, true)
    const next = new Set(laterIds())
    next.delete(key)
    setLaterIds(next)
}

export function syncPutStar(chatId: number, time: number) {
    write(`star:${chatId}`, { chatId, time })
    setStarredIds(new Set([...starredIds(), chatId]))
}

export function syncDropStar(chatId: number) {
    remove(`star:${chatId}`, true)
    const next = new Set(starredIds())
    next.delete(chatId)
    setStarredIds(next)
}

/** A pick from the file (null: none), with the time the file gave it. */
export function syncPutPick(chatId: number, messageId: number, pick: Pick | null, at: number) {
    const key = id(chatId, messageId)
    const next = new Map(picks())
    if (pick) {
        next.set(key, pick)
        write(`fix:${key}`, encodePick(pick))
        write(`fixat:${key}`, at)
    } else if (next.delete(key)) {
        remove(`fix:${key}`, true)
        remove(`fixat:${key}`, true)
    }
    setPicks(next)
}
