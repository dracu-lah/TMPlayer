// What the web player keeps on this device: settings, where each video was left, what was watched
// and the starred chats, in one IndexedDB store (idb-keyval); thumbnails in the Cache API. When the
// browser refuses storage (private mode, quota full) everything still works for the session from
// memory. Sign out clears all of it.
import { createSignal } from 'solid-js'
import { clear, createStore, del, delMany, entries, get, set, type UseStore } from 'idb-keyval'
import type { Sort, VideoItem } from './tg/types'
import { clearIndex, POSTERS as POSTER_CACHE, THUMBS, touch } from './cacheBounds'
import { cleanHomeChats, cleanResume, cleanSettings, parseJournal, parseLast, readAddedMode, type HomeChatRow, type Note } from './ui/stored'
import { decodePick, encodePick, type Pick } from './pick'
import { admit, cleanTitle } from './rename'
import * as Lists from './ui/lists'
import * as Recents from './ui/recents'
import { cleanPref, isDefault, type ChatPref } from './ui/filterPref'
import { resolveSpeed, sanitiseBoost, speedTarget, speedToKeep, type SpeedScopes } from './player/extras'
import { tdlibMessageId } from './sync/format'
import { fromFile, mergeInput, missingLines } from './sync/listsFile'
import { forgetLinks } from './ui/tgLinks'
import { clearLogs } from './logs'
import { names, writesOpen } from './accounts'
import { ownsKey, scopedKey, viewKey } from './ui/accountBook'

export type Theme = 'system' | 'dark' | 'light' | 'black'

export interface Settings {
    theme: Theme
    /**
     * The app's size limits (SizeFilter), in bytes: 0 lists every video; 50 MB is the chat's
     * "Hide small clips" switch; anything else is a floor chosen before the switch, kept.
     * Larger videos are left out unless the top is at its ceiling, which means no limit.
     */
    sizeMin: number
    sizeMax: number
    sort: Sort
    /** Posters from TMDB on the cards (the apps' MetadataStore `enabled`, on by default); off, nothing is looked up online. */
    posters: boolean
    /** A detail page's trailer in YouTube's embedded player on the page (the apps' MetadataStore `trailers_in_app`), on by default; off, it opens on YouTube. */
    trailersInApp: boolean
    /** Taken off a saved position when a video resumes, in milliseconds; null for the app's default. */
    rewindMs: number | null
    /** The carousel at the top of Home (the apps' "Carousel on Home"), on by default. */
    carousel: boolean
    /** Settings > Home's other rows (the apps' HomeLayout), each on by default. */
    homeContinue: boolean
    homeRecent: boolean
    homeStarred: boolean
    /** Recent searches are kept to pick again (the apps' `remember_searches`), on by default. */
    rememberSearches: boolean
    reduceMotion: 'auto' | 'on' | 'off'
    contrast: 'auto' | 'on' | 'off'
    /** The order of Home's rows (the apps' HomeOrder): row keys, comma separated; empty is Home's own order. */
    homeOrder: string
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
/** SizeFilter.DEFAULT_MIN and DEFAULT_MAX: every video, no top (R1, design/redesign/show-every-video.md). */
export const SIZE_DEFAULTS = { sizeMin: 0, sizeMax: 8 * 1024 * MB }
/** SizeFilter.OLD_DEFAULT_MIN: the default until 2026-11. It was never a choice, so a saved 400 MB reads as "not set". */
const OLD_DEFAULT_MIN = 400 * MB
const DEFAULTS: Settings = {
    theme: 'system',
    ...SIZE_DEFAULTS,
    sort: 'Newest',
    posters: true,
    trailersInApp: true,
    rewindMs: null,
    carousel: true,
    homeContinue: true,
    homeRecent: true,
    homeStarred: true,
    rememberSearches: true,
    reduceMotion: 'auto',
    contrast: 'auto',
    homeOrder: '',
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

// Profiles (HX88): one database for all of them. The adopted profile's keys keep their names, another
// profile's own keys are `@<folder>:<key>`, and the device's keys (ui/accountBook.ts) are shared. The
// page runs one profile, so `memory` and every caller use the plain names; only the database sees the
// stored ones. A write made after the page began leaving its profile lands nowhere.
const PREFIX = names.keyPrefix
const stor = (key: string) => scopedKey(PREFIX, key)

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
const seqs = new Map<string, number>()
let seq = 0
function noted(change: (notes: Record<string, Note>) => void) {
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
const replayed: Promise<void> = (async () => {
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

async function read<T>(key: string): Promise<T | undefined> {
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

function write(key: string, value: unknown) {
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

function remove(key: string, quiet = false) {
    const tomb = quiet ? null : tombOf(key)
    if (tomb) write(tomb, Date.now())
    if (!quiet && listsChanged && SYNCED.test(key)) queueMicrotask(listsChanged)
    removeRaw(key)
}

function removeRaw(key: string) {
    if (!writesOpen()) return
    memory.delete(key)
    const at = stor(key)
    const done = db ? note(at, { d: 1 }) : () => {}
    if (db) stored((s) => del(at, s)).then(done, () => {})
}

/** The player's own notes (bookmarks, its switches) go through the same storage, so signing out clears them too. */
export { read as kvRead, write as kvWrite, remove as kvRemove }

// Speed per series, chat and topic (SettingsStore.openingSpeed and rememberSpeed): a speed belongs to
// what the video is part of. Read and written only as numbers; anything else stored is as if none was.
const speedKey = (scope: string) => `speed:${scope}`

/** The speed a video opens at: the one kept for its series, else for its chat, else 1x. */
export async function openingSpeed(scopes: SpeedScopes): Promise<number> {
    const [series, chat] = await Promise.all([scopes.series ? read<unknown>(speedKey(scopes.series)) : undefined, scopes.chat ? read<unknown>(speedKey(scopes.chat)) : undefined])
    return resolveSpeed(series, chat)
}

/** Remembers a speed the viewer picked for the narrowest place the video belongs to; 1x forgets it there and in the chat under it. */
export function rememberSpeed(scopes: SpeedScopes, value: number) {
    const keep = speedToKeep(value)
    if (keep === null) {
        for (const scope of [scopes.series, scopes.chat]) if (scope) remove(speedKey(scope), true)
        return
    }
    const target = speedTarget(scopes)
    if (target) write(speedKey(target), keep)
}

/** Volume boost, kept for every video until it is turned off (the apps' volume_boost); 1 is off. */
const BOOST_KEY = 'player:boost'
export async function readBoost(): Promise<number> {
    return sanitiseBoost(await read<unknown>(BOOST_KEY))
}
export function writeBoost(level: number) {
    if (level > 1) write(BOOST_KEY, level)
    else remove(BOOST_KEY, true)
}

/** Night mode and Even out volume on the web, kept for every video until turned off (the apps' night_mode and even_volume). Off unless asked: routing every video through Web Audio is a cost a viewer chooses. */
const NIGHT_KEY = 'player:night'
const EVEN_KEY = 'player:even'
export async function readNight(): Promise<boolean> {
    return (await read<unknown>(NIGHT_KEY)) === true
}
export function writeNight(on: boolean) {
    if (on) write(NIGHT_KEY, true)
    else remove(NIGHT_KEY, true)
}
export async function readEven(): Promise<boolean> {
    return (await read<unknown>(EVEN_KEY)) === true
}
export function writeEven(on: boolean) {
    if (on) write(EVEN_KEY, true)
    else remove(EVEN_KEY, true)
}

async function all<T>(prefix: string): Promise<T[]> {
    return [...(await allEntries<T>(prefix)).values()]
}

async function allEntries<T>(prefix: string): Promise<Map<string, T>> {
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

/** Reduce motion and High contrast on the page: `html[data-motion]` is reduce or full, `html[data-contrast]` high or off; Automatic leaves both off the element and the system's own query decides (styles.css). */
function applyLook(s: { reduceMotion: Settings['reduceMotion']; contrast: Settings['contrast'] }) {
    const root = document.documentElement
    if (s.reduceMotion === 'on') root.dataset.motion = 'reduce'
    else if (s.reduceMotion === 'off') root.dataset.motion = 'full'
    else delete root.dataset.motion
    if (s.contrast === 'on') root.dataset.contrast = 'high'
    else if (s.contrast === 'off') root.dataset.contrast = 'off'
    else delete root.dataset.contrast
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
    const [saved, stars, later, added, mode, chatRows] = await Promise.all([
        read<unknown>('settings'),
        starred(),
        laterList(),
        addedChats(),
        read<unknown>(ADDED_MODE),
        read<unknown>(HOME_CHATS),
    ])
    setHomeChatRows(cleanHomeChats(chatRows))
    // Keys of settings since removed (`rows`, the As rows layout) and values that do not hold up are dropped (ui/stored.ts): the defaults stand, and the next write leaves them behind.
    const known = cleanSettings(saved)
    if (known.sizeMin === OLD_DEFAULT_MIN) delete known.sizeMin
    setSettings({ ...DEFAULTS, ...known })
    setAddedIdsSignal(new Set(added.map((a) => a.chatId)))
    setAddedModeSignal(readAddedMode(mode))
    setStarredIds(new Set(stars))
    setLaterIds(new Set(later.map(laterId)))
    await loadLists()
    await loadRecents()
    await loadPicks()
    await loadTitles()
    await loadCopies()
    applyTheme(settings().theme)
    applyLook(settings())
}

export function setSetting<K extends keyof Settings>(key: K, value: Settings[K]) {
    const next = { ...settings(), [key]: value }
    setSettings(next)
    write('settings', next)
    if (key === 'theme') applyTheme(value as Theme)
    if (key === 'reduceMotion' || key === 'contrast') applyLook(next)
}

// Where each video was left, and what was watched

const id = (chatId: number, messageId: number) => `${chatId}:${messageId}`

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
const LAST_KEY = names.lastPosition
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
        const last = parseLast(localStorage.getItem(LAST_KEY))
        if (last && wanted.has(last.key)) localStorage.removeItem(LAST_KEY)
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

export type { HomeChatRow }

const HOME_CHATS = 'home-chats'
const [homeChatRows, setHomeChatRows] = createSignal<readonly HomeChatRow[]>([])
export { homeChatRows }

/** Home's chat rows as just drawn, kept for Settings > Home's order list. */
export function rememberHomeChats(rows: readonly HomeChatRow[]) {
    const next = cleanHomeChats(rows)
    if (JSON.stringify(next) === JSON.stringify(homeChatRows())) return
    setHomeChatRows(next)
    write(HOME_CHATS, next)
}

export async function starred(): Promise<number[]> {
    return (await all<{ chatId: number; time: number }>('star:')).sort((a, b) => a.time - b.time).map((s) => s.chatId)
}

// A chat's filters (U6): the Show filter and the line of the others, kept for each chat on this device.

/** One chat's filters; nothing stored is the default. A stored value that does not hold up is the default (ui/videoFilter.ts). */
export async function chatPref(chatId: number): Promise<ChatPref> {
    return cleanPref(await read<unknown>(`view:${chatId}`))
}

/** Remembers a chat's filters; the default is no entry at all, so the store holds only chats that are filtered. */
export function setChatPref(chatId: number, pref: ChatPref) {
    const next = cleanPref(pref)
    if (isDefault(next)) remove(`view:${chatId}`, true)
    else write(`view:${chatId}`, next)
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
/** A signed in visitor who has never chosen which chats to watch meets the chooser first (the gate). */
export const chatsGated = () => addedMode() === 'unanswered' && !chatsGateWaived()

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
export function setAddedChats(chats: readonly { chatId: number; title: string }[], all = false) {
    const now = Date.now()
    const next = new Set<number>()
    for (const c of chats) {
        next.add(c.chatId)
        write(`added:${c.chatId}`, { chatId: c.chatId, title: c.title, time: now } satisfies AddedChat)
    }
    for (const id of addedIds() ?? []) if (!next.has(id)) remove(`added:${id}`)
    setAddedIdsSignal(next)
    // "All chats" (HX86) is the mode 'all' with the ticks kept beside it, so switching it off brings them back.
    setAddedMode(all ? 'all' : 'picked')
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

// "Choose the right poster": the TMDB title picked for a video, one stored line per video as the
// apps keep a correction (`fix:<chat>:<message>` = `tmdb=film:123`), on this device only.

const [picks, setPicks] = createSignal<ReadonlyMap<string, Pick>>(new Map())
export { picks }

/** The title picked for a video, or undefined for none. Reactive: a pick redraws what read it. */
export const pickOf = (chatId: number, messageId: number): Pick | undefined => picks().get(id(chatId, messageId))

/**
 * Picks `pick` for each of `videos` (the video, its copies, and the others of its title when the
 * viewer asked for them), or takes the picks off with null: back to the name's own match.
 */
export function setPick(videos: readonly { chatId: number; messageId: number }[], pick: Pick | null) {
    const next = new Map(picks())
    for (const v of videos) {
        const key = id(v.chatId, v.messageId)
        if (pick) {
            next.set(key, pick)
            write(`fix:${key}`, encodePick(pick))
            write(`fixat:${key}`, Date.now())
        } else if (next.delete(key)) {
            remove(`fixat:${key}`)
            remove(`fix:${key}`)
        }
    }
    setPicks(next)
}

async function loadPicks() {
    const stored = await allEntries<unknown>('fix:')
    const found = new Map<string, Pick>()
    for (const [key, text] of stored) {
        const pick = decodePick(text)
        if (pick) found.set(key.slice('fix:'.length), pick)
    }
    setPicks(found)
}

// "Rename": the title a viewer gave a video (the apps' VideoCorrection.title), one stored line per
// file (`title:<chat>:<message>` = the cleaned title, `titleat:<chat>:<message>` = when). The sync
// file carries it as the `title=` line of that video's correction. What is stored is outside data:
// read back through `cleanTitle`, the keys checked, the count bounded.

const [titles, setTitles] = createSignal<ReadonlyMap<string, string>>(new Map())
export { titles }

/** The most renames kept (a viewer renames a handful; anything past this is not theirs). */
export const TITLES_MAX = 5_000

/** The title the viewer gave a video, or undefined for none. Reactive: a rename redraws what read it. */
export const titleOf = (chatId: number, messageId: number): string | undefined => titles().get(id(chatId, messageId))

/**
 * Gives each of `videos` (the video and its copies) the title `title`, or takes the rename off with
 * null (or a title with nothing in it): the file name reads again. The same title again changes
 * nothing, not even the time the sync file sees.
 */
export function setTitle(videos: readonly { chatId: number; messageId: number }[], title: string | null) {
    const clean = cleanTitle(title)
    let next: ReadonlyMap<string, string> = titles()
    const now = Date.now()
    let changed = false
    for (const v of videos) {
        const key = id(v.chatId, v.messageId)
        if (clean) {
            if (next.get(key) === clean) continue
            const put = admit(next, key, clean, TITLES_MAX)
            next = put.next
            // Full: the oldest rename goes, here only (the sync file keeps its own newest 1,000).
            if (put.evicted) forgetTitle(put.evicted)
            write(`title:${key}`, clean)
            write(`titleat:${key}`, now)
        } else if (next.has(key)) {
            const left = new Map(next)
            left.delete(key)
            next = left
            remove(`titleat:${key}`, true)
            remove(`title:${key}`)
        } else continue
        changed = true
    }
    if (changed) setTitles(next)
}

/** A rename dropped to make room: no removal is noted, it is not the viewer's act. */
function forgetTitle(key: string) {
    remove(`title:${key}`, true)
    remove(`titleat:${key}`, true)
}

async function loadTitles() {
    const [stored, times] = await Promise.all([allEntries<unknown>('title:'), allEntries<number>('titleat:')])
    const found: [string, string, number][] = []
    for (const [k, v] of stored) {
        const key = k.slice('title:'.length)
        const title = cleanTitle(v)
        const at = times.get(`titleat:${key}`)
        if (title && VIDEO_KEY.test(key)) found.push([key, title, typeof at === 'number' && Number.isFinite(at) ? at : 0])
    }
    // Oldest first, so a full store drops the oldest; a stored pile past the cap keeps the newest.
    found.sort((x, y) => x[2] - y[2])
    setTitles(new Map(found.slice(-TITLES_MAX).map(([key, title]) => [key, title])))
}

// The copies of a video (HX38), on this device only: the files kept apart from the copies they were
// grouped with ("Not the same video", `apart:<chat>:<message>`), the copy the viewer chose for a
// video in its list of copies (`chose:<chat>:<message>`, the time), and the quality they usually
// play (`copyhabit`, QualityHabit's line). The worker groups and picks by them (copies.ts).

const [apartIds, setApartIds] = createSignal<ReadonlySet<string>>(new Set())
const [chosenCopies, setChosenCopies] = createSignal<readonly string[]>([])
const [copyHabit, setCopyHabitSignal] = createSignal<string | null>(null)
export { apartIds, chosenCopies, copyHabit }

/** The most choices kept; the oldest goes first. */
export const CHOSEN_MAX = 2_000
/** The most files kept apart (a viewer splits a handful; anything past this is not theirs). */
export const APART_MAX = 5_000
const VIDEO_KEY = /^-?\d{1,20}:\d{1,12}$/
const HABIT_LINE = /^[\d:,]{1,400}$/

/** "Not the same video" (on) for one file, or "Group with its copies again" (off). */
export function setApart(chatId: number, messageId: number, on: boolean) {
    const key = id(chatId, messageId)
    const next = new Set(apartIds())
    if (on) {
        if (next.size >= APART_MAX && !next.has(key)) return
        next.add(key)
        write(`apart:${key}`, Date.now())
    } else {
        if (!next.delete(key)) return
        remove(`apart:${key}`)
    }
    setApartIds(next)
}

/**
 * The viewer chose `chosen` in a list of `among` (its copies): it is the one their cards of the
 * video play from now on, whatever is recommended. A choice made before among them is forgotten.
 */
export function chooseCopy(chosen: { chatId: number; messageId: number }, among: readonly { chatId: number; messageId: number }[]) {
    const key = id(chosen.chatId, chosen.messageId)
    const others = new Set(among.map((c) => id(c.chatId, c.messageId)))
    others.add(key)
    const kept = chosenCopies().filter((k) => !others.has(k))
    for (const k of chosenCopies()) if (others.has(k)) remove(`chose:${k}`)
    const next = [key, ...kept]
    for (const k of next.splice(CHOSEN_MAX)) remove(`chose:${k}`)
    write(`chose:${key}`, Date.now())
    setChosenCopies(next)
}

/** The quality line (QualityHabit) once a play is noted in it by the worker. */
export function setCopyHabit(line: string) {
    if (!HABIT_LINE.test(line)) return
    setCopyHabitSignal(line)
    write('copyhabit', line)
}

async function loadCopies() {
    const [apart, chose, habit] = await Promise.all([allEntries<unknown>('apart:'), allEntries<unknown>('chose:'), read<unknown>('copyhabit')])
    const keyOf = (k: string, prefix: string) => {
        const key = k.slice(prefix.length)
        return VIDEO_KEY.test(key) ? key : null
    }
    const ids = new Set<string>()
    for (const [k, at] of apart) {
        const key = keyOf(k, 'apart:')
        if (key && typeof at === 'number' && ids.size < APART_MAX) ids.add(key)
    }
    const chosen: [string, number][] = []
    for (const [k, at] of chose) {
        const key = keyOf(k, 'chose:')
        if (key && typeof at === 'number' && Number.isFinite(at)) chosen.push([key, at])
    }
    chosen.sort((a, b) => b[1] - a[1])
    setApartIds(ids)
    setChosenCopies(chosen.slice(0, CHOSEN_MAX).map(([k]) => k))
    setCopyHabitSignal(typeof habit === 'string' && HABIT_LINE.test(habit) ? habit : null)
}

// Thumbnails

/** The Cache API's store for poster answers and pictures (screens/posters.ts). */
export const POSTERS = POSTER_CACHE

/** A thumbnail's JPEG from the Cache API, or from `fetch` (and then kept there). */
export async function cachedThumb(key: string, fetch: () => Promise<ArrayBuffer>): Promise<Blob> {
    // Another profile's thumbnails are its own (a private chat's message ids are per account), in the same capped cache.
    const url = `/thumb/${encodeURIComponent(PREFIX + key)}`
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

// The viewer's named lists (HX43): one document under `lists`, the apps' rules (ui/lists.ts).

const LISTS = 'lists'
const [listsDoc, setListsDoc] = createSignal<Lists.ListsDoc>(Lists.emptyLists())
/** The lists as the page draws them: in the order made, each line in the order added. */
export const namedLists = () => Lists.visible(listsDoc())

export async function loadLists() {
    setListsDoc(Lists.readLists(await read<unknown>(LISTS), Date.now()))
}

/**
 * One edit, made over what is stored now and merged with it, so another tab's edit made meanwhile
 * is kept (the newest write wins per list and per line, as the apps merge them).
 */
function editLists(change: (doc: Lists.ListsDoc, now: number) => Lists.ListsDoc) {
    const now = Date.now()
    const next = change(listsDoc(), now)
    if (next === listsDoc()) return
    setListsDoc(next)
    write(LISTS, next)
    if (!db) return
    void raw((st) => get<unknown>(LISTS, st)).then((there) => {
        const merged = Lists.merge(Lists.readLists(there, now), listsDoc(), now)
        if (JSON.stringify(merged) === JSON.stringify(listsDoc())) return
        setListsDoc(merged)
        write(LISTS, merged)
    }, () => {})
}

/** A new list; null for an empty name or once the most lists are made. */
export function createList(name: string): Lists.ListInfo | null {
    let made: Lists.ListInfo | null = null
    editLists((doc, now) => {
        const r = Lists.create(doc, name, now)
        if (!r) return doc
        made = r[1]
        return r[0]
    })
    return made
}

export const renameList = (id: string, name: string) => editLists((doc, now) => Lists.rename(doc, id, name, now))
export const setListHome = (id: string, home: boolean) => editLists((doc, now) => Lists.setHome(doc, id, home, now))
export const deleteList = (id: string) => editLists((doc, now) => Lists.deleteList(doc, id, now))

/** Puts a video (or, with `show`, the whole show it is an episode of) in list `id`. */
export function addToList(id: string, item: VideoItem, show?: string): Lists.AddResult {
    let result: Lists.AddResult = 'no-list'
    const line = show ? { item: { ...item, title: show, name: show }, show: show.trim().toLowerCase() } : { item }
    editLists((doc, now) => {
        const [next, r] = Lists.add(doc, id, line, now)
        result = r
        return next
    })
    return result
}

export const removeFromList = (id: string, keys: readonly string[]) => editLists((doc, now) => Lists.removeLines(doc, id, new Set(keys), now))

/** Videos gone from their chats (HX21) out of every list: the ones of `keys` that were in one. */
export function forgetListVideos(keys: readonly string[]): string[] {
    const wanted = new Set(keys)
    const listed = new Set(listsDoc().lines.filter((l) => !l.show && wanted.has(Lists.lineKey(l))).map(Lists.lineKey))
    if (listed.size) editLists((doc, now) => Lists.forgetVideos(doc, listed, now)[0])
    return [...listed]
}

// Recent searches (WP-59): up to ten, newest first, under `recents`; kept only while "Remember
// searches" is on, and gone with Clear and with sign out.

const RECENTS = 'recents'
const [recentSearches, setRecentSearches] = createSignal<string[]>([])
export { recentSearches }

async function loadRecents() {
    setRecentSearches(Recents.parse(await read<unknown>(RECENTS)))
}

/** A search the viewer settled on, remembered unless they turned remembering off. */
export function noteSearch(query: string) {
    if (!settings().rememberSearches) return
    const next = Recents.add(recentSearches(), query)
    if (next.length === recentSearches().length && next.every((s, i) => s === recentSearches()[i])) return
    setRecentSearches(next)
    write(RECENTS, next)
}

export function forgetSearch(query: string) {
    const next = Recents.remove(recentSearches(), query)
    setRecentSearches(next)
    write(RECENTS, next)
}

export function clearSearches() {
    setRecentSearches([])
    remove(RECENTS, true)
}

/** A backup's searches joined behind this device's own; nothing is added while remembering is off. Returns how many came in. */
export function restoreSearches(theirs: readonly string[]): number {
    if (!settings().rememberSearches) return 0
    const next = Recents.join(recentSearches(), theirs)
    const added = next.length - recentSearches().length
    if (added <= 0) return 0
    setRecentSearches(next)
    write(RECENTS, next)
    return added
}

/** The lists as stored, for the sync and the backup. */
export const namedListsDoc = () => listsDoc()

/** How many lines of other devices' lists one sync reads from Telegram, the newest first (the rest wait for the next). */
export const LISTS_BUDGET = 20

/**
 * The lists a file holds (the apps' or another tab's) joined into this device's: the newest write
 * wins per list and per line, a removal beats an older write. A line this device lacks is read
 * from Telegram first (its card draws from the video), within a budget; one that is gone from its
 * chat is left out. Returns how many lines came in. A restore passes `removals` false: the file's
 * removals still keep the lines they name out, but never take a list or line away here.
 */
/** Lines whose video could not be read this page life (gone from its chat): not asked again, so they never starve the rest of the budget. */
const deadLines = new Set<string>()

export async function syncMergeLists(raw: unknown, video: (chatId: number, messageId: number) => Promise<VideoItem | null>, budget = LISTS_BUDGET, removals = true): Promise<number> {
    const file = fromFile(raw)
    if (!file.lists.length && !file.entries.length && (!removals || !file.removed.length)) return 0
    const lines: Lists.ListLine[] = []
    for (const e of missingLines(file, listsDoc())) {
        const dead = `${e.chat}:${e.msg}`
        if (deadLines.has(dead)) continue
        if (budget-- <= 0) break
        const item = await video(e.chat, e.msg).catch(() => null)
        if (!item) {
            deadLines.add(dead)
            continue
        }
        lines.push({ list: e.list, item: e.show ? { ...item, title: e.title || item.title, name: e.title || item.name } : item, at: e.at, ...(e.show ? { show: e.show } : {}) })
    }
    const before = listsDoc()
    editLists((doc, now) => Lists.merge(doc, mergeInput(file, lines, removals), now))
    return Math.max(0, listsDoc().lines.length - before.lines.length)
}

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

/** Sign out: settings, positions, watched, stars, thumbnails and posters go; the theme falls back to the system's. */
export async function clearAll() {
    memory.clear()
    // The message links asked for are the account's (U9): none stays for the next one.
    forgetLinks()
    seqs.clear()
    // The page's log, the names it remembered to take out of it and the crash note are the account's too (WB18).
    clearLogs()
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
    setListsDoc(Lists.emptyLists())
    setRecentSearches([])
    setPicks(new Map())
    setTitles(new Map())
    setApartIds(new Set<string>())
    setChosenCopies([])
    setCopyHabitSignal(null)
    // The added chats are the account's, not the device's, so signing out leaves none of them
    // behind and the next account is asked for itself (as the apps' clearEverything does).
    setAddedIdsSignal(new Set<number>())
    setAddedModeSignal('unanswered')
    applyTheme(DEFAULTS.theme)
    applyLook(DEFAULTS)
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
