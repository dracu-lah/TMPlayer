// What the web player keeps on this device: settings, where each video was left, what was watched
// and the starred chats, in one IndexedDB store (idb-keyval); thumbnails in the Cache API. When the
// browser refuses storage (private mode, quota full) everything still works for the session from
// memory. Sign out clears all of it.
import { createSignal } from 'solid-js'
import { clear, createStore, del, entries, get, set, type UseStore } from 'idb-keyval'
import type { Sort, VideoItem } from './tg/types'
import { clearIndex, POSTERS as POSTER_CACHE, THUMBS, touch } from './cacheBounds'
import { cleanHome } from './ui/homeSettings'

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
    /** The carousel at the top of Home (the apps' "Carousel on Home"), on by default. */
    carousel: boolean
    /** Settings > Home's other rows (the apps' HomeLayout), each on by default. */
    homeContinue: boolean
    homeRecent: boolean
    homeStarred: boolean
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
const DEFAULTS: Settings = {
    theme: 'system',
    ...SIZE_DEFAULTS,
    sort: 'Newest',
    posters: true,
    rewindMs: null,
    carousel: true,
    homeContinue: true,
    homeRecent: true,
    homeStarred: true,
}

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
        m.content = theme === 'system' ? (m.getAttribute('media')?.includes('light') ? ground.light : ground.dark) : ground[theme]
    })
    try {
        if (theme === 'system') localStorage.removeItem(THEME_KEY)
        else localStorage.setItem(THEME_KEY, theme)
    } catch {}
}

export async function loadSettings() {
    const [saved, stars, later, added, mode] = await Promise.all([
        read<Partial<Settings>>('settings'),
        starred(),
        laterList(),
        addedChats(),
        read<unknown>(ADDED_MODE),
    ])
    // Keys of settings since removed (`rows`, the As rows layout) are dropped, so the next write leaves them behind.
    const known = Object.fromEntries(Object.entries(saved ?? {}).filter(([k]) => k in DEFAULTS)) as Partial<Settings>
    setSettings({ ...DEFAULTS, ...cleanHome(known) })
    setAddedIdsSignal(new Set(added.map((a) => a.chatId)))
    setAddedModeSignal(readMode(mode))
    setStarredIds(new Set(stars))
    setLaterIds(new Set(later.map(laterId)))
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
export { watchedTick }

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
        const last = JSON.parse(localStorage.getItem(LAST_KEY) ?? 'null') as Last | null
        if (last?.key && wanted.has(last.key)) localStorage.removeItem(LAST_KEY)
    } catch {}
    setWatchedTick(watchedTick() + 1)
    return { snapshot, count: hit.size }
}

// Watch later: the video itself is kept, so the list draws without asking Telegram.

export interface Later {
    item: VideoItem
    time: number
    /**
     * Set for a whole show saved (HX20), as the apps keep it: the show's name, lower-cased, which
     * with the chat is the line's key whatever episode comes next. [item] is one of its episodes
     * under the show's name, whose page lists the show's seasons.
     */
    show?: string
}
const [laterIds, setLaterIds] = createSignal<ReadonlySet<string>>(new Set())
export { laterIds }

/** A show's Watch later key: its chat and its name, lower-cased. */
export const showLaterId = (chatId: number, show: string) => `show:${chatId}:${show.trim().toLowerCase()}`

/** The key a Watch later line is kept and looked up by: the video's, or the show's. */
const laterId = (l: Later) => (l.show ? showLaterId(l.item.chatId, l.show) : id(l.item.chatId, l.item.messageId))

/**
 * Puts a whole show on Watch later, or takes it off (HX20). [episode] is the episode the page is
 * about, kept under the show's name: its card opens that page, which lists the show's seasons.
 */
export function setShowLater(episode: VideoItem, show: string, on: boolean) {
    const key = showLaterId(episode.chatId, show)
    const next = new Set(laterIds())
    if (on) {
        next.add(key)
        const item: VideoItem = { ...episode, title: show, name: show }
        write(`later:${key}`, { item, time: Date.now(), show: show.trim().toLowerCase() } satisfies Later)
    } else {
        next.delete(key)
        remove(`later:${key}`)
    }
    setLaterIds(next)
}

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

/** Takes one video off Watch later by its ids alone (a video gone from its chat, HX21); true when it was there. */
export function dropLater(chatId: number, messageId: number): boolean {
    const key = id(chatId, messageId)
    if (!laterIds().has(key)) return false
    setLater({ chatId, messageId } as VideoItem, false)
    return true
}

/** Watch later, the latest first. */
export async function laterList(): Promise<Later[]> {
    return (await all<Later>('later:')).sort((a, b) => b.time - a.time)
}

/** The key a Watch later line goes by, for History's ticks and `removeLater`. */
export const laterKey = laterId

/**
 * Takes the Watch later lines with these keys off, or every line when `keys` is null (Clear Watch
 * later), and returns exactly what went, times included, so an Undo puts back the same order.
 */
export async function removeLater(keys: readonly string[] | null): Promise<Later[]> {
    const wanted = keys ? new Set(keys) : null
    const gone = (await laterList()).filter((l) => !wanted || wanted.has(laterId(l)))
    if (!gone.length) return gone
    const next = new Set(laterIds())
    for (const l of gone) {
        const key = laterId(l)
        next.delete(key)
        remove(`later:${key}`)
    }
    setLaterIds(next)
    return gone
}

/** Undo for `removeLater`: the same lines back, with the times they had. */
export function restoreLater(lines: readonly Later[]) {
    const next = new Set(laterIds())
    for (const l of lines) {
        const key = laterId(l)
        next.add(key)
        write(`later:${key}`, l)
    }
    setLaterIds(next)
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

// The chats the viewer added (HX27, the apps' AddedChats)

/**
 * How much of the account this device draws from. 'unanswered' is every visitor from before the
 * picker existed, who keeps seeing everything; 'all' is the same view chosen on purpose, which is
 * what stops the one time prompt asking again; 'picked' honours the stored chats, and picking none
 * is a real answer rather than a reason to show them all.
 */
export type AddedMode = 'unanswered' | 'all' | 'picked'

export interface AddedChat {
    chatId: number
    /** The name it had when it was added, so a chat since left still has a row to remove. */
    title: string
    time: number
}

const ADDED_MODE = 'addedMode'

const [addedMode, setAddedModeSignal] = createSignal<AddedMode>('unanswered')
/** Live, for every list that browses and for the picker's own ticks. */
const [addedIds, setAddedIdsSignal] = createSignal<ReadonlySet<number> | null>(null)
export { addedIds, addedMode }

/**
 * Whether the chats gate (App.tsx) stands aside for this visit, with nothing written: the account
 * has too few chats to be worth choosing from, or its list would not load (offline). Kept in
 * memory only, so the gate asks again on the next visit, or once the connection comes back.
 */
const [chatsGateWaived, setChatsGateWaived] = createSignal(false)
export { chatsGateWaived, setChatsGateWaived }

/**
 * The chats a list may draw from, or null for every chat: the same shape the apps' gate has, so
 * one question is asked wherever videos are listed. Null while nobody has answered and under
 * 'all'; a set, possibly empty, once chats have been picked.
 */
export function addedGate(): ReadonlySet<number> | null {
    return addedMode() === 'picked' ? (addedIds() ?? new Set()) : null
}

/** Whether a chat's videos may be listed. */
export function addedShows(chatId: number): boolean {
    const gate = addedGate()
    return gate === null || gate.has(chatId)
}

/** `addedGate()`, as a plain array for a `ListView`/`HomeView` sent to the worker (it cannot read this store itself). */
export function addedView(): number[] | null {
    const gate = addedGate()
    return gate ? [...gate] : null
}

/** The whole choice, as the picker's Done writes it: the ids with the names they had. */
export function setAddedChats(chats: readonly { chatId: number; title: string }[]) {
    const now = Date.now()
    const next = new Set<number>()
    for (const c of chats) {
        next.add(c.chatId)
        write(`added:${c.chatId}`, { chatId: c.chatId, title: c.title, time: now } satisfies AddedChat)
    }
    for (const id of addedIds() ?? []) if (!next.has(id)) remove(`added:${id}`)
    setAddedIdsSignal(next)
    setAddedMode('picked')
}

function setAddedMode(mode: AddedMode) {
    setAddedModeSignal(mode)
    if (mode === 'unanswered') remove(ADDED_MODE)
    else write(ADDED_MODE, mode)
}

/** The added chats with their names, newest first, for the picker and the list of gone chats. */
export async function addedChats(): Promise<AddedChat[]> {
    return (await all<AddedChat>('added:')).sort((a, b) => a.time - b.time)
}

/** Anything but the two known words, and nothing stored, reads as never asked. */
function readMode(raw: unknown): AddedMode {
    return raw === 'all' || raw === 'picked' ? raw : 'unanswered'
}

// Thumbnails

/** The Cache API's store for poster answers and pictures (screens/posters.ts). */
export const POSTERS = POSTER_CACHE

/** A thumbnail's JPEG from the Cache API, or from `fetch` (and then kept there). */
export async function cachedThumb(key: string, fetch: () => Promise<ArrayBuffer>): Promise<Blob> {
    const url = `/thumb/${encodeURIComponent(key)}`
    const cache = await globalThis.caches?.open(THUMBS).catch(() => undefined)
    const hit = await cache?.match(url).catch(() => undefined)
    if (hit) {
        touch(THUMBS, url)
        return hit.blob()
    }
    const blob = new Blob([await fetch()], { type: 'image/jpeg' })
    cache?.put(url, new Response(blob)).then(() => touch(THUMBS, url, blob.size), () => {})
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
    await clearIndex()
    setSettings({ ...DEFAULTS })
    setStarredIds(new Set<number>())
    setLaterIds(new Set<string>())
    // The added chats are the account's, not the device's, so signing out leaves none of them
    // behind and the next account is asked for itself (as the apps' clearEverything does).
    setAddedIdsSignal(new Set<number>())
    setAddedModeSignal('unanswered')
    applyTheme(DEFAULTS.theme)
}
