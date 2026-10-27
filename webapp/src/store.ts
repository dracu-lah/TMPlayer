// What the web player keeps on this device: settings, where each video was left, what was watched
// and the starred chats, in one IndexedDB store (idb-keyval); thumbnails in the Cache API. When the
// browser refuses storage (private mode, quota full) everything still works for the session from
// memory. Sign out clears all of it.
import { createSignal } from 'solid-js'
import { clear, createStore, del, entries, get, set, type UseStore } from 'idb-keyval'
import type { Sort, VideoItem } from './tg/types'

export type Theme = 'system' | 'dark' | 'light' | 'black'

export interface Settings {
    theme: Theme
    /**
     * The app's size limits (SizeFilter), in bytes: smaller videos are left out of every list,
     * and larger ones unless the top is at its ceiling, which means no limit.
     */
    sizeMin: number
    sizeMax: number
    sort: Sort
    /** Posters from TMDB on the cards (the apps' MetadataStore `enabled`, on by default); off, nothing is looked up online. */
    posters: boolean
    /** Taken off a saved position when a video resumes, in milliseconds; null for the app's default. */
    rewindMs: number | null
    /** The carousel at the top of Home in the TV layout (the apps' "Carousel on Home"), on by default. */
    carousel: boolean
}

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

const MB = 1024 * 1024
/** SizeFilter.DEFAULT_MIN and DEFAULT_MAX: 400 MB up, no top. */
export const SIZE_DEFAULTS = { sizeMin: 400 * MB, sizeMax: 8 * 1024 * MB }
const DEFAULTS: Settings = { theme: 'system', ...SIZE_DEFAULTS, sort: 'Newest', posters: true, rewindMs: null, carousel: true }
const THEME_KEY = 'tmplayer-theme'

let db: UseStore | null = null
try {
    db = createStore('tmplayer-app', 'kv')
} catch {
    db = null
}
/** The session's copy of everything, so a browser without storage still works until it closes. */
const memory = new Map<string, unknown>()

/** False once the browser refused to keep something (a private window, a full disk): Settings says so. */
const [storageWorks, setStorageWorks] = createSignal(db !== null)
export { storageWorks }

/**
 * Runs a storage call so that any failure arrives as a rejection: idb-keyval opens the database
 * on first use, and a browser that refuses it (Firefox's private windows) throws right there.
 */
const raw = <T>(op: (db: UseStore) => Promise<T>): Promise<T> => (db ? Promise.resolve(db).then(op) : Promise.reject(new Error('no storage')))
const stored = <T>(op: (db: UseStore) => Promise<T>): Promise<T> => replayed.then(() => raw(op))

// A write to IndexedDB finishes some milliseconds later, and a reload or a closing tab in that time
// loses it. So each write is also noted at once in localStorage (which is synchronous), struck off
// when IndexedDB has it, and anything still noted at the next start is written first.
const JOURNAL = 'tmplayer-pending'
type Note = { v: unknown } | { d: 1 }
const seqs = new Map<string, number>()
let seq = 0
function noted(change: (notes: Record<string, Note>) => void) {
    try {
        const notes: Record<string, Note> = JSON.parse(localStorage.getItem(JOURNAL) || '{}')
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
const replayed: Promise<void> = (async () => {
    let left: Record<string, Note> = {}
    try {
        left = JSON.parse(localStorage.getItem(JOURNAL) || '{}')
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

async function read<T>(key: string): Promise<T | undefined> {
    if (memory.has(key)) return memory.get(key) as T
    if (!db) return undefined
    try {
        const v = await stored((s) => get<T>(key, s))
        if (v !== undefined) memory.set(key, v)
        return v
    } catch {
        setStorageWorks(false)
        return undefined
    }
}

function write(key: string, value: unknown) {
    memory.set(key, value)
    const done = db ? note(key, { v: value }) : () => {}
    if (db)
        stored((s) => set(key, value, s)).then(done, (e) => {
            console.warn('[store] not saved', key, e)
            setStorageWorks(false)
        })
}

function remove(key: string) {
    memory.delete(key)
    const done = db ? note(key, { d: 1 }) : () => {}
    if (db) stored((s) => del(key, s)).then(done, () => {})
}

/** The player's own notes (bookmarks, its switches) go through the same storage, so signing out clears them too. */
export { read as kvRead, write as kvWrite, remove as kvRemove }

async function all<T>(prefix: string): Promise<T[]> {
    return [...(await allEntries<T>(prefix)).values()]
}

async function allEntries<T>(prefix: string): Promise<Map<string, T>> {
    const out = new Map<string, T>()
    if (db) {
        try {
            for (const [k, v] of await stored((s) => entries<string, T>(s))) if (String(k).startsWith(prefix)) out.set(String(k), v)
        } catch {}
    }
    for (const [k, v] of memory) if (k.startsWith(prefix)) out.set(k, v as T)
    return out
}

// Settings

const [settings, setSettings] = createSignal<Settings>({ ...DEFAULTS, theme: savedTheme() })
export { settings }

/** The theme is also kept in localStorage, so index.html paints the first frame in it. */
function savedTheme(): Theme {
    try {
        const t = localStorage.getItem(THEME_KEY)
        if (t === 'dark' || t === 'light' || t === 'black' || t === 'system') return t
    } catch {}
    return DEFAULTS.theme
}

function applyTheme(theme: Theme) {
    const root = document.documentElement
    if (theme === 'system') delete root.dataset.theme
    else root.dataset.theme = theme
    // The browser's own bars take the page's ground: index.html's pair follow the device, a chosen theme pins both.
    const ground = { dark: '#0c0e10', light: '#f6fafd', black: '#000000' }
    document.querySelectorAll<HTMLMetaElement>('meta[name=theme-color]').forEach((m) => {
        m.content = theme === 'system' ? (m.media.includes('light') ? ground.light : ground.dark) : ground[theme]
    })
    try {
        if (theme === 'system') localStorage.removeItem(THEME_KEY)
        else localStorage.setItem(THEME_KEY, theme)
    } catch {}
}

export async function loadSettings() {
    const [saved, stars, later] = await Promise.all([read<Partial<Settings>>('settings'), starred(), laterList()])
    // Keys of settings since removed (`rows`, the As rows layout) are dropped, so the next write leaves them behind.
    const known = Object.fromEntries(Object.entries(saved ?? {}).filter(([k]) => k in DEFAULTS)) as Partial<Settings>
    setSettings({ ...DEFAULTS, ...known })
    setStarredIds(new Set(stars))
    setLaterIds(new Set(later.map((l) => id(l.item.chatId, l.item.messageId))))
    applyTheme(settings().theme)
}

export function setSetting<K extends keyof Settings>(key: K, value: Settings[K]) {
    const next = { ...settings(), [key]: value }
    setSettings(next)
    write('settings', next)
    if (key === 'theme') applyTheme(value as Theme)
}

// Where each video was left, and what was watched

const id = (chatId: number, messageId: number) => `${chatId}:${messageId}`

export function getResume(chatId: number, messageId: number): Promise<Resume | undefined> {
    return read<Resume>(`resume:${id(chatId, messageId)}`)
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
const LAST_KEY = 'tmplayer-last-position'
type Last = { key: string; resume?: Resume; watched?: number }

function keepLast(last: Last) {
    try {
        localStorage.setItem(LAST_KEY, JSON.stringify(last))
    } catch {}
}

function foldLast() {
    let last: Last | null = null
    try {
        last = JSON.parse(localStorage.getItem(LAST_KEY) ?? 'null') as Last | null
    } catch {}
    if (!last?.key) return
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
    return (await all<Resume>('resume:')).sort((a, b) => b.time - a.time)
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

/** Marks a video watched (its place forgotten, as finishing it does) or not watched. */
export function setWatched(chatId: number, messageId: number, on: boolean) {
    const key = id(chatId, messageId)
    if (on) {
        remove(`resume:${key}`)
        write(`watched:${key}`, Date.now())
    } else remove(`watched:${key}`)
    setWatchedTick(watchedTick() + 1)
}
/** Bumped by every change to Watched, so what shows it reads it again. */
const [watchedTick, setWatchedTick] = createSignal(0)
export { watchedTick }

// Watch later: the video itself is kept, so the list draws without asking Telegram.

export interface Later {
    item: VideoItem
    time: number
}
const [laterIds, setLaterIds] = createSignal<ReadonlySet<string>>(new Set())
export { laterIds }

export function setLater(item: VideoItem, on: boolean) {
    const key = id(item.chatId, item.messageId)
    const next = new Set(laterIds())
    if (on) {
        next.add(key)
        write(`later:${key}`, { item, time: Date.now() } satisfies Later)
    } else {
        next.delete(key)
        remove(`later:${key}`)
    }
    setLaterIds(next)
}

/** Watch later, the latest first. */
export async function laterList(): Promise<Later[]> {
    return (await all<Later>('later:')).sort((a, b) => b.time - a.time)
}

// Starred chats

/** The starred chats, live, for every star drawn. */
const [starredIds, setStarredIds] = createSignal<ReadonlySet<number>>(new Set())
export { starredIds }

export function setStarred(chatId: number, on: boolean) {
    const next = new Set(starredIds())
    if (on) {
        next.add(chatId)
        write(`star:${chatId}`, { chatId, time: Date.now() })
    } else {
        next.delete(chatId)
        remove(`star:${chatId}`)
    }
    setStarredIds(next)
}

export async function starred(): Promise<number[]> {
    return (await all<{ chatId: number; time: number }>('star:')).sort((a, b) => a.time - b.time).map((s) => s.chatId)
}

// Thumbnails

const THUMBS = 'tmplayer-thumbs'
/** The Cache API's store for poster answers and pictures (screens/posters.ts). */
export const POSTERS = 'tmplayer-posters'

/** A thumbnail's JPEG from the Cache API, or from `fetch` (and then kept there). */
export async function cachedThumb(key: string, fetch: () => Promise<ArrayBuffer>): Promise<Blob> {
    const url = `/thumb/${encodeURIComponent(key)}`
    const cache = await globalThis.caches?.open(THUMBS).catch(() => undefined)
    const hit = await cache?.match(url).catch(() => undefined)
    if (hit) return hit.blob()
    const blob = new Blob([await fetch()], { type: 'image/jpeg' })
    cache?.put(url, new Response(blob)).catch(() => {})
    return blob
}

/** Sign out: settings, positions, watched, stars, thumbnails and posters go; the theme falls back to the system's. */
export async function clearAll() {
    memory.clear()
    seqs.clear()
    try {
        localStorage.removeItem(JOURNAL)
        localStorage.removeItem(LAST_KEY)
    } catch {}
    if (db) await stored(clear).catch(() => {})
    await globalThis.caches?.delete(THUMBS).catch(() => {})
    await globalThis.caches?.delete(POSTERS).catch(() => {})
    setSettings({ ...DEFAULTS })
    setStarredIds(new Set<number>())
    setLaterIds(new Set<string>())
    applyTheme(DEFAULTS.theme)
}
