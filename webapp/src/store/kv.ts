// The storage under everything the page keeps (../store.ts): one IndexedDB store (idb-keyval) with the
// session's copy in memory, the journal that saves a write the page closed on, the removal notes the
// sync file reads, and the profile keys. The only module that holds the database and `memory`.
import { createSignal } from 'solid-js'
import { clear, createStore, del, delMany, entries, get, set, type UseStore } from 'idb-keyval'
import { parseJournal, type Note } from '../ui/stored'
import { tdlibMessageId } from '../sync/format'
import { names, writesOpen } from '../accounts'
import { ownsKey, scopedKey, viewKey } from '../ui/accountBook'

export const db: UseStore | null = (() => {
    try {
        return createStore('tmplayer-app', 'kv')
    } catch {
        return null
    }
})()
/** The session's copy of everything, so a browser without storage still works until it closes. */
export const memory = new Map<string, unknown>()

// Profiles (HX88): one database for all of them. The adopted profile's keys keep their names, another
// profile's own keys are `@<folder>:<key>`, and the device's keys (ui/accountBook.ts) are shared. The
// page runs one profile, so `memory` and every caller use the plain names; only the database sees the
// stored ones. A write made after the page began leaving its profile lands nowhere.
export const PREFIX = names.keyPrefix
export const stor = (key: string) => scopedKey(PREFIX, key)

/** False once the browser refused to keep something (a private window, a full disk): Settings says so. */
const [storageWorks, setStorageWorks] = createSignal(db !== null)
export { storageWorks, setStorageWorks }

/**
 * Runs a storage call so that any failure arrives as a rejection: idb-keyval opens the database
 * on first use, and a browser that refuses it (Firefox's private windows) throws right there.
 */
export const raw = <T>(op: (db: UseStore) => Promise<T>): Promise<T> => (db ? Promise.resolve(db).then(op) : Promise.reject(new Error('no storage')))
export const stored = <T>(op: (db: UseStore) => Promise<T>): Promise<T> => replayed.then(() => raw(op))

// A write to IndexedDB finishes some milliseconds later, and a reload or a closing tab in that time
// loses it. So each write is also noted at once in localStorage (which is synchronous), struck off
// when IndexedDB has it, and anything still noted at the next start is written first.
export const JOURNAL = 'tmplayer-pending'
export const seqs = new Map<string, number>()
let seq = 0
export function noted(change: (notes: Record<string, Note>) => void) {
    try {
        const notes = parseJournal(localStorage.getItem(JOURNAL))
        change(notes)
        if (Object.keys(notes).length) localStorage.setItem(JOURNAL, JSON.stringify(notes))
        else localStorage.removeItem(JOURNAL)
    } catch {}
}
function note(key: string, n: Note) {
    const mine = ++seq
    seqs.set(key, mine)
    noted((notes) => (notes[key] = n))
    return () => {
        if (seqs.get(key) !== mine) return
        seqs.delete(key)
        noted((notes) => delete notes[key])
    }
}
/** Writes what an earlier page left noted; every storage call waits for it. */
export const replayed: Promise<void> = (async () => {
    let left: Record<string, Note> = {}
    try {
        left = parseJournal(localStorage.getItem(JOURNAL))
    } catch {}
    const keys = Object.keys(left)
    if (!keys.length || !db) return
    try {
        for (const key of keys) {
            const n = left[key]
            await raw((s) => ('d' in n ? del(key, s) : set(key, n.v, s)))
        }
        // Only what was replayed is struck off: a write made meanwhile is noted afresh.
        noted((notes) => keys.forEach((k) => !seqs.has(k) && delete notes[k]))
    } catch {}
})()

export async function read<T>(key: string): Promise<T | undefined> {
    if (memory.has(key)) return memory.get(key) as T
    if (!db) return undefined
    try {
        const v = await stored((s) => get<T>(stor(key), s))
        if (v !== undefined) memory.set(key, v)
        return v
    } catch {
        setStorageWorks(false)
        return undefined
    }
}

/**
 * Resume across devices (sync/): what is taken off Watched, Watch later and the picks is noted with
 * the time (`xw:`, `xl:`, `xfix:`, and `xtitle:` for a rename reset, named by the apps' `<chat>_<message>`
 * with the message's id as TDLib has it), so the sync file can say it was taken off and another
 * device does not bring it back. Written for the viewer's own removals only: `quiet` is for what a
 * sync itself applies.
 */
const TOMBS = { watched: 'xw', later: 'xl', fix: 'xfix', title: 'xtitle' } as const
const TOMB = /^(watched|later|fix|title):(-?\d+):(\d+)$/
function tombOf(key: string): string | null {
    const m = TOMB.exec(key)
    return m ? `${TOMBS[m[1] as keyof typeof TOMBS]}:${m[2]}_${tdlibMessageId(Number(m[3]))}` : null
}

/** Called when a list the sync file holds changed (a place, a mark, Watch later, a star, a pick); sync/index.ts pushes soon after. */
let listsChanged: (() => void) | null = null
export function onListsChanged(fn: () => void) {
    listsChanged = fn
}
const SYNCED = /^(resume|watched|later|star|fix|title):/

export function write(key: string, value: unknown) {
    if (!writesOpen()) return
    // A line put (back) is newer than any removal of it: an Undo must not be taken off again by a sync.
    const tomb = tombOf(key)
    if (tomb) removeRaw(tomb)
    if (listsChanged && SYNCED.test(key)) queueMicrotask(listsChanged)
    memory.set(key, value)
    const at = stor(key)
    const done = db ? note(at, { v: value }) : () => {}
    if (db)
        stored((s) => set(at, value, s)).then(done, (e) => {
            console.warn('[store] not saved', key, e)
            setStorageWorks(false)
        })
}

export function remove(key: string, quiet = false) {
    const tomb = quiet ? null : tombOf(key)
    if (tomb) write(tomb, Date.now())
    if (!quiet && listsChanged && SYNCED.test(key)) queueMicrotask(listsChanged)
    removeRaw(key)
}

export function removeRaw(key: string) {
    if (!writesOpen()) return
    memory.delete(key)
    const at = stor(key)
    const done = db ? note(at, { d: 1 }) : () => {}
    if (db) stored((s) => del(at, s)).then(done, () => {})
}

/** The player's own notes (bookmarks, its switches) go through the same storage, so signing out clears them too. */

export async function all<T>(prefix: string): Promise<T[]> {
    return [...(await allEntries<T>(prefix)).values()]
}

export async function allEntries<T>(prefix: string): Promise<Map<string, T>> {
    const out = new Map<string, T>()
    if (db) {
        try {
            for (const [k, v] of await stored((s) => entries<string, T>(s))) {
                const mine = viewKey(PREFIX, String(k))
                if (mine?.startsWith(prefix)) out.set(mine, v)
            }
        } catch {}
    }
    for (const [k, v] of memory) if (k.startsWith(prefix)) out.set(k, v as T)
    return out
}

/** A video's key: "chatId:messageId". */
export const id = (chatId: number, messageId: number) => `${chatId}:${messageId}`

/** The shape of a stored video key; anything else read back is not one. */
export const VIDEO_KEY = /^-?\d{1,20}:\d{1,12}$/

/** Sign out, the part that needs no waiting: the session's copy, the journal and the writes still noted go. */
export function forgetMemory() {
    memory.clear()
    seqs.clear()
    try {
        localStorage.removeItem(JOURNAL)
    } catch {}
}

/** Sign out: everything in the database. */
export async function clearDatabase() {
    if (db) await stored(clear).catch(() => {})
}

/**
 * Signing one profile out while others stay (HX88): its own keys go from the database and from the
 * journal, the device's settings and every other profile's keys stay. `prefix` is that profile's
 * (names.keyPrefix); the page reloads after, so what it holds in memory goes with it.
 */
export async function clearProfileData(prefix: string) {
    if (prefix === PREFIX) {
        for (const k of [...memory.keys()]) if (ownsKey(PREFIX, stor(k))) memory.delete(k)
        seqs.clear()
    }
    noted((notes) => {
        for (const k of Object.keys(notes)) if (ownsKey(prefix, k)) delete notes[k]
    })
    if (!db) return
    try {
        const keys = (await raw((s) => entries<string, unknown>(s))).map(([k]) => String(k)).filter((k) => ownsKey(prefix, k))
        for (let i = 0; i < keys.length; i += 500) {
            const part = keys.slice(i, i + 500)
            await raw((s) => delMany(part, s))
        }
    } catch (e) {
        console.warn('[store] profile data not cleared', e)
    }
}

/** Waits for the writes in flight, so a switch right after a save keeps it. */
export async function flushWrites() {
    await replayed
    if (!db) return
    // idb-keyval queues every call on one transaction at a time: a read now ends after the writes before it.
    await raw((s) => get('settings', s)).catch(() => {})
}
