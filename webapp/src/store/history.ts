// Where each video was left and what was watched: the saved places (Continue watching), the Watched
// marks, Clear history and Remove from history with their Undo.
import { createSignal } from 'solid-js'
import { cleanResume, parseLast } from '../ui/stored'
import { names, writesOpen } from '../accounts'
import { all, allEntries, id, read, remove, write } from './kv'

export interface Resume {
    chatId: number
    messageId: number
    /** Seconds into the video. */
    position: number
    /** Seconds, 0 when unknown. */
    length: number
    /** When it was left, milliseconds since 1970. */
    time: number
}

export function getResume(chatId: number, messageId: number): Promise<Resume | undefined> {
    return read<unknown>(`resume:${id(chatId, messageId)}`).then((r) => cleanResume(r) ?? undefined)
}

/** The position, or, near the end, the video as watched and its position dropped. */
export function saveResume(r: Omit<Resume, 'time'>) {
    const key = id(r.chatId, r.messageId)
    if (r.length > 0 && r.position > r.length - 30) {
        remove(`resume:${key}`)
        write(`watched:${key}`, Date.now())
        keepLast({ key, watched: Date.now() })
    } else if (r.position > 10) {
        const resume = { ...r, position: Math.floor(r.position), time: Date.now() }
        write(`resume:${key}`, resume)
        keepLast({ key, resume })
    }
}

// The latest save is also kept in localStorage, which writes at once: a save made as the page
// closes (Back off the site, a closed tab) may never reach IndexedDB. It is folded back in when
// the app next starts.
export const LAST_KEY = names.lastPosition
type Last = { key: string; resume?: Resume; watched?: number }

function keepLast(last: Last) {
    // A save as the page closes, after it began leaving its profile (a sign out wiped it), lands nowhere.
    if (!writesOpen()) return
    try {
        localStorage.setItem(LAST_KEY, JSON.stringify(last))
    } catch {}
}

function foldLast() {
    let last: Last | null = null
    try {
        last = parseLast(localStorage.getItem(LAST_KEY))
    } catch {}
    if (!last) return
    if (last.resume) write(`resume:${last.key}`, last.resume)
    else if (last.watched) {
        remove(`resume:${last.key}`)
        write(`watched:${last.key}`, last.watched)
    }
}
foldLast()

export function dropResume(chatId: number, messageId: number) {
    remove(`resume:${id(chatId, messageId)}`)
}

/** Videos left part way, the latest first: the Continue row. */
export async function resumes(): Promise<Resume[]> {
    return (await all<unknown>('resume:')).flatMap((r) => cleanResume(r) ?? []).sort((a, b) => b.time - a.time)
}

/** Video ids ("chatId:messageId") finished, for the Unwatched order and a show's next episode. */
export async function watchedIds(): Promise<string[]> {
    return [...(await allEntries('watched:')).keys()].map((k) => k.slice('watched:'.length))
}

/** What the lists' rules read of progress: video ids finished, and started. */
export async function progress(): Promise<{ watched: string[]; started: string[] }> {
    const [watched, left] = await Promise.all([watchedIds(), resumes()])
    return { watched, started: left.map((r) => id(r.chatId, r.messageId)) }
}

export async function isWatched(chatId: number, messageId: number): Promise<boolean> {
    return (await read(`watched:${id(chatId, messageId)}`)) !== undefined
}

/** Watched, as the detail page's round button and the History page read it: finished ids with when. */
export async function watchedList(): Promise<{ chatId: number; messageId: number; time: number }[]> {
    return [...(await allEntries<number>('watched:'))]
        .map(([k, time]) => {
            const [chatId, messageId] = k.slice('watched:'.length).split(':').map(Number)
            return { chatId, messageId, time: typeof time === 'number' ? time : 0 }
        })
        .sort((a, b) => b.time - a.time)
}

/**
 * When each video was last watched or left part way, for the Last played order: ids and times in
 * milliseconds, newest first, at most 20,000 (a chat's order reads what has loaded of it).
 */
export async function touches(): Promise<{ ids: string[]; times: number[] }> {
    const [left, done] = await Promise.all([resumes(), watchedList()])
    const at = new Map<string, number>()
    for (const r of left) at.set(id(r.chatId, r.messageId), r.time)
    for (const w of done) {
        const key = id(w.chatId, w.messageId)
        at.set(key, Math.max(at.get(key) ?? 0, w.time))
    }
    const newest = [...at].sort((a, b) => b[1] - a[1]).slice(0, 20_000)
    return { ids: newest.map(([k]) => k), times: newest.map(([, t]) => t) }
}

/** Marks a video watched (its place forgotten, as finishing it does) or not watched. */
export function setWatched(chatId: number, messageId: number, on: boolean) {
    const key = id(chatId, messageId)
    if (on) {
        remove(`resume:${key}`)
        write(`watched:${key}`, Date.now())
    } else remove(`watched:${key}`)
    setWatchedTick(watchedTick() + 1)
}

/**
 * A season or a whole show marked watched or not, as the apps' show page does it (HX20): every
 * place forgotten either way (watched leaves Continue watching, unwatched starts the show again),
 * the marks a moment apart in watching order, and one tick for the lot.
 */
export function setWatchedMany(videos: readonly { chatId: number; messageId: number }[], on: boolean) {
    const now = Date.now()
    videos.forEach((v, i) => {
        const key = id(v.chatId, v.messageId)
        remove(`resume:${key}`)
        if (on) write(`watched:${key}`, now - (videos.length - 1 - i))
        else remove(`watched:${key}`)
    })
    setWatchedTick(watchedTick() + 1)
}
/** Bumped by every change to Watched, so what shows it reads it again. */
const [watchedTick, setWatchedTick] = createSignal(0)
export { watchedTick, setWatchedTick }

/** What Clear history took out, to put back. */
export interface HistorySnapshot {
    resumes: [string, Resume][]
    watched: [string, number][]
}

/**
 * Clear history, as the apps do it: Continue watching and Watched go together, Watch later and the
 * starred chats stay. Returns what was cleared so an Undo can put it back, or null when there was
 * nothing to clear.
 */
export async function clearHistory(): Promise<HistorySnapshot | null> {
    const [left, done] = await Promise.all([allEntries<Resume>('resume:'), allEntries<number>('watched:')])
    if (!left.size && !done.size) return null
    const snap: HistorySnapshot = { resumes: [...left], watched: [...done] }
    for (const k of [...left.keys(), ...done.keys()]) remove(k)
    // The last position kept for a closing page would bring one back at the next start.
    try {
        localStorage.removeItem(LAST_KEY)
    } catch {}
    setWatchedTick(watchedTick() + 1)
    return snap
}

export function restoreHistory(snap: HistorySnapshot) {
    for (const [k, v] of snap.resumes) write(k, v)
    for (const [k, v] of snap.watched) write(k, v)
    setWatchedTick(watchedTick() + 1)
}

/**
 * Remove from history (HistoryEdits): each video in `ids` ("chatId:messageId") leaves Continue
 * watching and Watched together, and stays in its chat. Returns exactly what was taken out (the
 * saved positions and the watched times, so an Undo puts back the same order), or null when none
 * of them was in the history.
 */
export async function removeFromHistory(ids: string[]): Promise<{ snapshot: HistorySnapshot; count: number } | null> {
    const wanted = new Set(ids)
    const [left, done] = await Promise.all([allEntries<Resume>('resume:'), allEntries<number>('watched:')])
    const snapshot: HistorySnapshot = { resumes: [], watched: [] }
    const hit = new Set<string>()
    for (const e of left) {
        const id = e[0].slice('resume:'.length)
        if (wanted.has(id)) (snapshot.resumes.push(e), hit.add(id))
    }
    for (const e of done) {
        const id = e[0].slice('watched:'.length)
        if (wanted.has(id)) (snapshot.watched.push(e), hit.add(id))
    }
    if (!hit.size) return null
    for (const [k] of [...snapshot.resumes, ...snapshot.watched]) remove(k)
    // The last position kept for a closing page would bring one of them back at the next start.
    try {
        const last = parseLast(localStorage.getItem(LAST_KEY))
        if (last && wanted.has(last.key)) localStorage.removeItem(LAST_KEY)
    } catch {}
    setWatchedTick(watchedTick() + 1)
    return { snapshot, count: hit.size }
}
