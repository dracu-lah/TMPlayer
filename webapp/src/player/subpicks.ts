// The online subtitle picked for a video, and "Off", remembered (HX25, core's SubtitlePicks): reopen
// the video and the same one lays itself over it with no picker. A pick is filed under every key the
// video has (core's SubtitlePickKeys, asked of the Telegram worker: this very post, and the video's own
// identity, so a re-post or another copy finds it) and holds the hit (enough to fetch it again) and
// a copy of the file itself in its own database, so the MSone cache's eviction (200 files or 20 MB)
// cannot lose it. At most 400 picks and 64 MB of copies, the oldest used first out; a record that is
// missing, cut short or not what it should be is read as no picks at all, never as an error; a store
// that refuses (a private window, a full disk) means the pick is asked for again, nothing more. Every
// change runs one at a time under subpicks-db.ts's lock and stops before a write once a wipe has come.
// No page code (no strings, no Solid), so `node --test` reads it as it is.
import { COPY, exclusive, pickStore, setPickStore, STATE, wipeAllPicks } from './subpicks-db.ts'
import { FILE_MAX, parseHit, type Hit } from './msone.ts'

export { setPickStore, wipeAllPicks as wipePicks }

/** As core's SubtitlePicks.MAX_ENTRIES. */
export const MAX_PICKS = 400
/** All the copies together: the least recently used go first, their picks stay and download again when asked. */
export const MAX_COPY_BYTES = 64 * 1024 * 1024
/** As core's SubtitlePickKeys.MAX_KEY. */
const MAX_KEY = 400
/** A video has two keys (the post, the video itself); a caller passing more is not trusted. */
const MAX_KEYS = 8
/** The state record is a few tens of kilobytes; far more is not it. */
const MAX_STATE_ENTRIES = MAX_PICKS * 2

interface Entry {
    key: string
    /** When it was last used, ms since 1970. */
    at: number
    /** Null: the viewer turned subtitles off for the video. */
    hit: Hit | null
    /** The id of the copy kept for the hit (its file is under `copy|<id>`), when one is. */
    copy: string | null
    /** The copy's bytes, for the bound on all of them; 0 without a copy. */
    size: number
}

/** What is remembered for a video: `off`, or the hit with its own copy when that is still kept. */
export type Pick = { off: true } | { off: false; hit: Hit; file: File | null }

const isRecord = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v)

let lastAt = 0
let clock: () => number = Date.now

/** For the tests: a clock. */
export function setPickClock(now: () => number) {
    clock = now
    lastAt = 0
}

/** Strictly increasing, so two picks in one millisecond keep their order. */
function stamp(): number {
    const now = clock()
    lastAt = Number.isFinite(now) && now > lastAt ? now : lastAt + 1
    return lastAt
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
            byKey.set(key, { key, at, hit: null, copy: null, size: 0 })
            continue
        }
        const hit = parseHit(raw.hit)
        if (!hit) continue
        const copy = typeof raw.copy === 'string' && raw.copy === hit.id ? raw.copy : null
        const size = copy && typeof raw.size === 'number' && Number.isInteger(raw.size) && raw.size > 0 && raw.size <= FILE_MAX ? raw.size : 0
        byKey.set(key, copy && size ? { key, at, hit, copy, size } : { key, at, hit, copy: null, size: 0 })
    }
    return [...byKey.values()].sort((a, b) => a.at - b.at).slice(-MAX_PICKS)
}

function writeState(entries: Entry[]): unknown {
    return {
        v: 1,
        picks: entries.map((e) =>
            e.hit ? { key: e.key, at: e.at, hit: e.hit, ...(e.copy ? { copy: e.copy, size: e.size } : {}) } : { key: e.key, at: e.at, off: true },
        ),
    }
}

async function load(): Promise<Entry[]> {
    return readState(await pickStore().get(STATE))
}

/**
 * Holds all the copies to [MAX_COPY_BYTES]: the copy used longest ago goes first (every pick that
 * shares it loses it, and keeps its hit). A copy counts once however many picks share it.
 */
export function boundCopies(entries: Entry[], cap = MAX_COPY_BYTES): Entry[] {
    const copies = new Map<string, { size: number; used: number }>()
    for (const e of entries) {
        if (!e.copy) continue
        const known = copies.get(e.copy)
        copies.set(e.copy, { size: Math.max(e.size, known?.size ?? 0), used: Math.max(e.at, known?.used ?? 0) })
    }
    let total = 0
    for (const c of copies.values()) total += c.size
    if (total <= cap) return entries
    const gone = new Set<string>()
    for (const [id, c] of [...copies].sort((a, b) => a[1].used - b[1].used)) {
        if (total <= cap) break
        gone.add(id)
        total -= c.size
    }
    return entries.map((e) => (e.copy && gone.has(e.copy) ? { ...e, copy: null, size: 0 } : e))
}

/** Saves the picks (copies bounded), and drops the copies nothing points to any more; stops once `live()` says so. */
async function save(entries: Entry[], live: () => boolean): Promise<void> {
    const kept = boundCopies(entries)
    if (!live()) return
    await pickStore().set(STATE, writeState(kept))
    try {
        if (!live()) return
        const used = new Set(kept.flatMap((e) => (e.copy ? [COPY + e.copy] : [])))
        const orphans = (await pickStore().keys()).filter((k) => k.startsWith(COPY) && !used.has(k))
        if (orphans.length && live()) await pickStore().del(orphans)
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
    const v = await pickStore().get(COPY + id)
    const name = isRecord(v) && typeof v.name === 'string' && v.name.trim() ? v.name.trim().slice(0, 200) : null
    const bytes = isRecord(v) && v.bytes instanceof Uint8Array && v.bytes.length > 0 && v.bytes.length <= FILE_MAX ? v.bytes : null
    return name && bytes ? new File([bytes as BlobPart], name) : null
}

/**
 * The pick for the first of `keys` that has one (the most exact key first), or null. Used now: the
 * last to go when the list is full, as core's recall. A recency that cannot be saved (a full disk, a
 * read-only database) never costs the pick itself.
 */
export function recallPick(keys: string[]): Promise<Pick | null> {
    const wanted = cleanKeys(keys)
    if (!wanted.length) return Promise.resolve(null)
    return exclusive(async (live) => {
        const entries = await load()
        for (const key of wanted) {
            const entry = entries.find((e) => e.key === key)
            if (!entry) continue
            try {
                await save(put(entries, { ...entry, at: stamp() }), live)
            } catch {}
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
    return exclusive(async (live) => {
        let copy: string | null = null
        let size = 0
        if (file && file.size > 0 && file.size <= FILE_MAX) {
            try {
                const bytes = new Uint8Array(await file.arrayBuffer())
                if (!live()) return
                await pickStore().set(COPY + clean.id, { name: file.name, bytes })
                copy = clean.id
                size = bytes.length
            } catch {
                // Not kept (a full disk): the pick still stands and is fetched again when needed.
            }
        }
        if (!live()) return
        let entries = await load()
        for (const key of wanted) entries = put(entries, { key, at: stamp(), hit: clean, copy, size })
        await save(entries, live)
    }, undefined)
}

/** Remembers that the viewer turned subtitles off for the video, replacing any pick. */
export function rememberOff(keys: string[]): Promise<void> {
    const wanted = cleanKeys(keys)
    if (!wanted.length) return Promise.resolve()
    return exclusive(async (live) => {
        let entries = await load()
        for (const key of wanted) entries = put(entries, { key, at: stamp(), hit: null, copy: null, size: 0 })
        await save(entries, live)
    }, undefined)
}

/** Drops an "Off" the viewer has since turned back on by choosing a track; a real pick stays. */
export function clearOff(keys: string[]): Promise<void> {
    const wanted = cleanKeys(keys)
    if (!wanted.length) return Promise.resolve()
    return exclusive(async (live) => {
        const entries = await load()
        const rest = entries.filter((e) => !(wanted.includes(e.key) && e.hit === null))
        if (rest.length !== entries.length) await save(rest, live)
    }, undefined)
}

/** Forgets whatever is remembered for `keys`: another subtitle was chosen on purpose (an embedded track, a file of their own, one from the chat). */
export function forgetPick(keys: string[]): Promise<void> {
    const wanted = cleanKeys(keys)
    if (!wanted.length) return Promise.resolve()
    return exclusive(async (live) => {
        const entries = await load()
        const rest = entries.filter((e) => !wanted.includes(e.key))
        if (rest.length !== entries.length) await save(rest, live)
    }, undefined)
}

/** How many picks are kept (the tests, Settings' storage breakdown). */
export async function pickCount(): Promise<number> {
    return exclusive(async () => (await load()).length, 0)
}

// ---- the start: what opening a video finds ---------------------------------------------------

export type Restored =
    | { kind: 'none' }
    | { kind: 'off' }
    /**
     * The subtitle, ready to lay over the video; `fromDevice` when no download was needed. A file
     * downloaded again is not yet kept: the caller keeps it (rememberPick) once it has laid it, and
     * only if the viewer has chosen nothing meanwhile.
     */
    | { kind: 'ready'; file: File; hit: Hit; fromDevice: boolean }
    /** Remembered, but not to be had now (gone and not to be downloaded, or the download failed): the viewer is simply asked again, with no error. */
    | { kind: 'missing'; hit: Hit }

export interface RestoreDeps {
    /** MSone stopped for good: nothing of its is laid, kept copy or not. */
    stopped: () => boolean
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
        // MSone stopped at its request: nothing of its is laid, even from a copy this device still holds.
        if (deps.stopped()) return { kind: 'none' }
        const cached = await deps.cached(hit).catch(() => null)
        if (cached) return { kind: 'ready', file: cached, hit, fromDevice: true }
        if (pick.file) return { kind: 'ready', file: pick.file, hit, fromDevice: true }
        if (!deps.mayDownload()) return { kind: 'missing', hit }
        const file = await deps.download(hit).catch(() => null)
        return file ? { kind: 'ready', file, hit, fromDevice: false } : { kind: 'missing', hit }
    } catch {
        return { kind: 'none' }
    }
}
