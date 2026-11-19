// The online subtitle picked for a video, and "Off", remembered (HX25, core's SubtitlePicks): reopen
// the video and the same one lays itself over it with no picker. A pick is filed under every key the
// video has (core's SubtitlePickKeys, asked of the Telegram worker: this very post, and the video's own
// identity, so a re-post or another copy finds it) and holds the hit (enough to fetch it again) and
// a copy of the file itself in its own database, so the MSone cache's eviction (200 files or 20 MB)
// cannot lose it. At most 400 picks, the oldest used first out; a record that is missing, cut short or
// not what it should be is read as no picks at all, never as an error; a store that refuses (a private
// window, a full disk) means the pick is asked for again, nothing more. No page code (no strings, no
// Solid), so `node --test` reads it as it is.
import { createStore, delMany, get, keys as storeKeys, set, type UseStore } from 'idb-keyval'
import { FILE_MAX, onForgotten, parseHit, type Hit, type KeepStore } from './msone.ts'

/** As core's SubtitlePicks.MAX_ENTRIES. */
export const MAX_PICKS = 400
/** As core's SubtitlePickKeys.MAX_KEY. */
const MAX_KEY = 400
/** A video has two keys (the post, the video itself); a caller passing more is not trusted. */
const MAX_KEYS = 8
/** The state record is a few tens of kilobytes; far more is not it. */
const MAX_STATE_ENTRIES = MAX_PICKS * 2
/** A store that does not answer (some private windows) is given up on rather than waited for. */
const STORE_MS = 2000
const STATE = 'state'
const COPY = 'copy|'

interface Entry {
    key: string
    /** When it was last used, ms since 1970. */
    at: number
    /** Null: the viewer turned subtitles off for the video. */
    hit: Hit | null
    /** The id of the copy kept for the hit (its file is under `copy|<id>`), when one is. */
    copy: string | null
}

/** What is remembered for a video: `off`, or the hit with its own copy when that is still kept. */
export type Pick = { off: true } | { off: false; hit: Hit; file: File | null }

const isRecord = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v)

function idbStore(): KeepStore {
    let db: UseStore | null = null
    const store = () => (db ??= createStore('tmplayer-subpicks', 'kv'))
    return {
        get: (key) => get(key, store()),
        set: (key, value) => set(key, value, store()),
        del: (keys) => delMany(keys, store()),
        keys: async () => (await storeKeys(store())).filter((k): k is string => typeof k === 'string'),
    }
}

let store: KeepStore = idbStore()
let lastAt = 0
let clock: () => number = Date.now
let queue: Promise<unknown> = Promise.resolve()

/** For the tests: the store to keep in (a Map, or one that fails) and a clock. */
export function setPickStore(next: KeepStore, now: () => number = Date.now) {
    store = next
    clock = now
    lastAt = 0
    queue = Promise.resolve()
}

/** Strictly increasing, so two picks in one millisecond keep their order. */
function stamp(): number {
    const now = clock()
    lastAt = Number.isFinite(now) && now > lastAt ? now : lastAt + 1
    return lastAt
}

/** `p`, or `fallback` when it fails or takes longer than the store is given. */
async function guarded<T>(p: () => Promise<T>, fallback: T): Promise<T> {
    let timer: ReturnType<typeof setTimeout> | undefined
    try {
        return await Promise.race([p(), new Promise<T>((resolve) => (timer = setTimeout(() => resolve(fallback), STORE_MS)))])
    } catch {
        return fallback
    } finally {
        clearTimeout(timer)
    }
}

/**
 * One change at a time: after the ones before it in this tab, and (where the browser has Web Locks)
 * before another tab's, so a read, change and write is never torn. The newest write wins; the state
 * is read fresh each time, so no tab works from a stale copy.
 */
function exclusive<T>(work: () => Promise<T>, fallback: T): Promise<T> {
    const run = () => guarded(work, fallback)
    const locks = (globalThis as { navigator?: { locks?: { request: <R>(name: string, fn: () => Promise<R>) => Promise<R> } } }).navigator?.locks
    const next = queue.then(() => (locks ? locks.request('tmplayer-subpicks', run).catch(() => fallback) : run()))
    queue = next.catch(() => {})
    return next
}

function cleanKeys(keys: unknown): string[] {
    if (!Array.isArray(keys)) return []
    const out: string[] = []
    for (const k of keys.slice(0, MAX_KEYS)) if (typeof k === 'string' && k.trim() && k.length <= MAX_KEY && !out.includes(k)) out.push(k)
    return out
}

/** The state record read back: written by this code, but storage can hold anything. The oldest first. */
export function readState(v: unknown): Entry[] {
    const list = isRecord(v) && Array.isArray(v.picks) ? v.picks : []
    const byKey = new Map<string, Entry>()
    for (const raw of list.slice(0, MAX_STATE_ENTRIES)) {
        if (!isRecord(raw)) continue
        const key = typeof raw.key === 'string' && raw.key.trim() && raw.key.length <= MAX_KEY ? raw.key : null
        if (!key) continue
        const at = typeof raw.at === 'number' && Number.isFinite(raw.at) && raw.at >= 0 ? raw.at : 0
        if (raw.off === true) {
            byKey.set(key, { key, at, hit: null, copy: null })
            continue
        }
        const hit = parseHit(raw.hit)
        if (!hit) continue
        const copy = typeof raw.copy === 'string' && raw.copy === hit.id ? raw.copy : null
        byKey.set(key, { key, at, hit, copy })
    }
    return [...byKey.values()].sort((a, b) => a.at - b.at).slice(-MAX_PICKS)
}

function writeState(entries: Entry[]): unknown {
    return {
        v: 1,
        picks: entries.map((e) => (e.hit ? { key: e.key, at: e.at, hit: e.hit, ...(e.copy ? { copy: e.copy } : {}) } : { key: e.key, at: e.at, off: true })),
    }
}

async function load(): Promise<Entry[]> {
    return readState(await store.get(STATE))
}

/** Saves the picks, and drops the copies nothing points to any more. */
async function save(entries: Entry[]): Promise<void> {
    await store.set(STATE, writeState(entries))
    try {
        const used = new Set(entries.flatMap((e) => (e.copy ? [COPY + e.copy] : [])))
        const orphans = (await store.keys()).filter((k) => k.startsWith(COPY) && !used.has(k))
        if (orphans.length) await store.del(orphans)
    } catch {}
}

/** Puts `entry` last (the newest used) and holds the list to the bound. */
function put(entries: Entry[], entry: Entry): Entry[] {
    const rest = entries.filter((e) => e.key !== entry.key)
    rest.push(entry)
    return rest.slice(-MAX_PICKS)
}

/** The kept copy of a hit as a File, or null when it is gone, cut short or not a subtitle's size. */
async function readCopy(id: string): Promise<File | null> {
    const v = await store.get(COPY + id)
    const name = isRecord(v) && typeof v.name === 'string' && v.name.trim() ? v.name.trim().slice(0, 200) : null
    const bytes = isRecord(v) && v.bytes instanceof Uint8Array && v.bytes.length > 0 && v.bytes.length <= FILE_MAX ? v.bytes : null
    return name && bytes ? new File([bytes as BlobPart], name) : null
}

/**
 * The pick for the first of `keys` that has one (the most exact key first), or null. Used now: the
 * last to go when the list is full, as core's recall.
 */
export function recallPick(keys: string[]): Promise<Pick | null> {
    const wanted = cleanKeys(keys)
    if (!wanted.length) return Promise.resolve(null)
    return exclusive(async () => {
        const entries = await load()
        for (const key of wanted) {
            const entry = entries.find((e) => e.key === key)
            if (!entry) continue
            const fresh = { ...entry, at: stamp() }
            await save(put(entries, fresh))
            if (!entry.hit) return { off: true } as Pick
            const file = entry.copy ? await readCopy(entry.copy).catch(() => null) : null
            return { off: false, hit: entry.hit, file } as Pick
        }
        return null
    }, null)
}

/** Remembers `hit` for the video under `keys`, with a copy of `file` when it is a subtitle's size. */
export function rememberPick(keys: string[], hit: Hit, file: File | null): Promise<void> {
    const wanted = cleanKeys(keys)
    const clean = parseHit(hit)
    if (!wanted.length || !clean) return Promise.resolve()
    return exclusive(async () => {
        let copy: string | null = null
        if (file && file.size > 0 && file.size <= FILE_MAX) {
            try {
                const bytes = new Uint8Array(await file.arrayBuffer())
                await store.set(COPY + clean.id, { name: file.name, bytes })
                copy = clean.id
            } catch {
                // Not kept (a full disk): the pick still stands and is fetched again when needed.
            }
        }
        let entries = await load()
        for (const key of wanted) entries = put(entries, { key, at: stamp(), hit: clean, copy })
        await save(entries)
    }, undefined)
}

/** Remembers that the viewer turned subtitles off for the video, replacing any pick. */
export function rememberOff(keys: string[]): Promise<void> {
    const wanted = cleanKeys(keys)
    if (!wanted.length) return Promise.resolve()
    return exclusive(async () => {
        let entries = await load()
        for (const key of wanted) entries = put(entries, { key, at: stamp(), hit: null, copy: null })
        await save(entries)
    }, undefined)
}

/** Drops an "Off" the viewer has since turned back on by choosing a track; a real pick stays. */
export function clearOff(keys: string[]): Promise<void> {
    const wanted = cleanKeys(keys)
    if (!wanted.length) return Promise.resolve()
    return exclusive(async () => {
        const entries = await load()
        const rest = entries.filter((e) => !(wanted.includes(e.key) && e.hit === null))
        if (rest.length !== entries.length) await save(rest)
    }, undefined)
}

/** Forgets whatever is remembered for `keys`: another subtitle was chosen on purpose (an embedded track, a file of their own, one from the chat). */
export function forgetPick(keys: string[]): Promise<void> {
    const wanted = cleanKeys(keys)
    if (!wanted.length) return Promise.resolve()
    return exclusive(async () => {
        const entries = await load()
        const rest = entries.filter((e) => !wanted.includes(e.key))
        if (rest.length !== entries.length) await save(rest)
    }, undefined)
}

/** Every pick and every copy kept with them: a sign out, or MSone stopping for good. */
export function wipePicks(): Promise<void> {
    return exclusive(async () => {
        const all = await store.keys()
        await store.del(all.filter((k) => k === STATE || k.startsWith(COPY)))
    }, undefined)
}

// MSone stopped for good, at its request: nothing of its stays on the device, the picks' copies too.
onForgotten(() => void wipePicks())

/** How many picks are kept (the tests, Settings' storage breakdown). */
export async function pickCount(): Promise<number> {
    return exclusive(async () => (await load()).length, 0)
}

// ---- the start: what opening a video finds ---------------------------------------------------

export type Restored =
    | { kind: 'none' }
    | { kind: 'off' }
    /** The subtitle, ready to lay over the video; `fromDevice` when no download was needed. */
    | { kind: 'ready'; file: File; hit: Hit; fromDevice: boolean }
    /** Remembered, but not to be had now (gone and not to be downloaded, or the download failed): the viewer is simply asked again, with no error. */
    | { kind: 'missing'; hit: Hit }

export interface RestoreDeps {
    /** The MSone cache's file for the hit (free), or null. */
    cached: (hit: Hit) => Promise<File | null>
    /** Whether a download may be started now: no metered line, no lock, the provider on. */
    mayDownload: () => boolean
    /** Fetches the hit again; null when it cannot be had. */
    download: (hit: Hit) => Promise<File | null>
}

/**
 * The subtitle remembered for the video, in the order that spends least (core's restorePick): the
 * MSone cache's file, else the pick's own copy, else, when allowed, the download again once. Never throws.
 */
export async function restorePick(keys: string[], deps: RestoreDeps): Promise<Restored> {
    try {
        const pick = await recallPick(keys)
        if (!pick) return { kind: 'none' }
        if (pick.off) return { kind: 'off' }
        const { hit } = pick
        const cached = await deps.cached(hit).catch(() => null)
        if (cached) return { kind: 'ready', file: cached, hit, fromDevice: true }
        if (pick.file) return { kind: 'ready', file: pick.file, hit, fromDevice: true }
        if (!deps.mayDownload()) return { kind: 'missing', hit }
        const file = await deps.download(hit).catch(() => null)
        if (!file) return { kind: 'missing', hit }
        // Kept again, so the next opening finds its own copy.
        void rememberPick(keys, hit, file)
        return { kind: 'ready', file, hit, fromDevice: false }
    } catch {
        return { kind: 'none' }
    }
}
