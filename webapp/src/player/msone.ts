// MSone's Malayalam subtitles through TMPlayer's gateway (subs.tmplayer.org, the API.md of the
// msone-unofficial-api repo): whether the provider is on, a search, and a release's file brought
// in as a File for readSubtitle(). Everything the gateway answers is outside data: a bad hit is
// skipped, a bad answer counts as offline, and nothing here throws for it. No page code (no
// strings, no Solid), so `node --test` reads it as it is.
//
// Files taken and the last answer of each search are kept on the device, as the apps' SubtitleCache
// does: a kept file loads again with no download (one MSone count per file per device), and while
// MSone is paused the sheet can still list what is kept. `stopped` deletes all of it.
import { createStore, delMany, get, keys as storeKeys, set, type UseStore } from 'idb-keyval'

export const GATEWAY = 'https://subs.tmplayer.org'

/** This build's gateway key, sent on every /msone call (not /health). Empty: MSone is not offered. */
export const GATEWAY_KEY: string = typeof __MSONE_GATEWAY_KEY__ === 'string' ? __MSONE_GATEWAY_KEY__ : ''
const keyHeaders = (): Record<string, string> => (GATEWAY_KEY ? { 'X-Api-Key': GATEWAY_KEY } : {})

/** `on`, `off` (no key set), `paused` (for now), `stopped` (for good, at MSone's request); `unknown` when never checked or not understood. */
export type State = 'on' | 'off' | 'paused' | 'stopped' | 'unknown'

export interface Health {
    state: State
    /** The operator's own words (quoting MSone, say), shown as they are; null when none. */
    message: string | null
    /** When the gateway last answered, ms since 1970; 0 for never. */
    checkedAt: number
}

export interface Hit {
    id: string
    title: string
    year: number | null
    season: number | null
    episode: number | null
    translator: { name: string; url: string | null } | null
    pageUrl: string | null
    downloadUrl: string
}

/** What the search asks: an IMDb id, or a title with its year; the season and episode for a show. */
export interface Ask {
    imdb?: string | null
    q?: string | null
    year?: number | null
    season?: number | null
    episode?: number | null
}

export type Failure =
    | { kind: 'too_short' }
    | { kind: 'no_results' }
    | { kind: 'busy'; retryAfter: number | null }
    | { kind: 'state'; state: 'paused' | 'stopped'; message: string | null }
    | { kind: 'offline' }
    | { kind: 'unreadable' }
    /** The sheet closed: nothing to say. */
    | { kind: 'aborted' }

export type SearchResult = { ok: true; hits: Hit[] } | { ok: false; failure: Failure }
export type DownloadResult = { ok: true; file: File } | { ok: false; failure: Failure }

type Fetch = typeof fetch

const HEALTH_KEY = 'tmplayer-msone-health'
/** The gateway asks clients to look at most every few hours. */
const CHECK_EVERY_MS = 6 * 3600_000
/** Check again, pressed over and over, still asks at most once a minute. */
const TRY_GAP_MS = 60_000
/** The gateway lets its operator write up to 500 characters. */
const MAX_MESSAGE = 500
const MAX_QUERY = 120
const MAX_TITLE = 300
const MAX_NAME = 120
const MAX_URL = 2048
const MAX_HITS = 100
/** Answers are JSON of a few KB; far more is not an answer. */
const MAX_JSON = 1024 * 1024
/** As subtitles.ts: a feature's subtitles are tens of kilobytes. */
export const FILE_MAX = 4 * 1024 * 1024
/** subtitles.ts's SUBTITLE_EXTENSIONS (not imported: that file is not plain enough for `node --test`). */
const SUBTITLE_EXT = ['srt', 'ass', 'ssa', 'vtt', 'sub']
const HEALTH_MS = 10_000
const SEARCH_MS = 15_000
const DOWNLOAD_MS = 60_000

const isRecord = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v)

/** A string trimmed and cut to `max`, else null. */
function text(v: unknown, max: number): string | null {
    if (typeof v !== 'string') return null
    const s = v.trim().slice(0, max).trim()
    return s || null
}

/** A whole number in [min, max], else null. */
function whole(v: unknown, min: number, max: number): number | null {
    return typeof v === 'number' && Number.isInteger(v) && v >= min && v <= max ? v : null
}

/** An https address, else null: nothing else is linked or fetched. */
export function httpsUrl(v: unknown): string | null {
    if (typeof v !== 'string' || v.length > MAX_URL) return null
    try {
        const u = new URL(v)
        return u.protocol === 'https:' ? u.href : null
    } catch {
        return null
    }
}

// ---- health ------------------------------------------------------------------------------

const STATES: State[] = ['on', 'off', 'paused', 'stopped']

/** `/health`'s answer: MSone's state and message, or null when the answer is not one. */
export function parseHealth(body: unknown): { state: State; message: string | null } | null {
    if (!isRecord(body) || !isRecord(body.providers)) return null
    const msone = body.providers.msone
    if (!isRecord(msone)) return null
    const state = STATES.find((s) => s === msone.state) ?? 'unknown'
    return { state, message: text(msone.message, MAX_MESSAGE) }
}

/** The kept answer, read back: it was written by this code, but storage can hold anything. */
function readKept(v: unknown): Health | null {
    if (!isRecord(v)) return null
    const parsed = parseHealth({ providers: { msone: v } })
    const at = typeof v.checkedAt === 'number' && Number.isFinite(v.checkedAt) ? v.checkedAt : null
    return parsed && at !== null ? { ...parsed, checkedAt: at } : null
}

let memory: Health | null = null
let lastTry = 0
let asking: Promise<Health> | null = null
/** No search before this, after a 429 (its Retry-After, else a minute). */
let busyUntil = 0

function kept(): Health | null {
    try {
        const raw = globalThis.localStorage?.getItem(HEALTH_KEY)
        if (raw) return readKept(JSON.parse(raw)) ?? memory
    } catch {}
    return memory
}

function keep(h: Health) {
    memory = h
    // Stopped for good, at MSone's request: nothing of theirs stays on the device.
    if (h.state === 'stopped') void forgetKept()
    try {
        globalThis.localStorage?.setItem(HEALTH_KEY, JSON.stringify(h))
    } catch {}
}

const unknown = (): Health => ({ state: 'unknown', message: null, checkedAt: 0 })

/** The last state known, without asking: `unknown` when never checked. */
export function knownHealth(): Health {
    return kept() ?? unknown()
}

/**
 * MSone's state: the kept answer while it is under six hours old, else the gateway's. `force`
 * (Check again) asks even so, but never more than once a minute. A failed check keeps the last
 * answer, so `stopped` stays stopped; never checked and failed is `unknown`.
 */
export function health(force = false, fetcher: Fetch = fetch): Promise<Health> {
    const now = Date.now()
    const last = kept()
    const fresh = last !== null && last.checkedAt <= now && now - last.checkedAt < CHECK_EVERY_MS
    if (fresh && !force) return Promise.resolve(last)
    if (asking) return asking
    if (now - lastTry < TRY_GAP_MS) return Promise.resolve(last ?? unknown())
    lastTry = now
    const run = (async (): Promise<Health> => {
        try {
            const answer = await deadline(HEALTH_MS, undefined, async (signal) => {
                const res = await fetcher(`${GATEWAY}/health`, { signal, credentials: 'omit' })
                return res.ok ? parseHealth(await readJson(res)) : null
            })
            if (!answer) return last ?? unknown()
            const h = { ...answer, checkedAt: Date.now() }
            keep(h)
            return h
        } catch {
            return last ?? unknown()
        }
    })()
    asking = run
    run.finally(() => asking === run && (asking = null))
    return run
}

/** A search or a download said MSone is paused or stopped: kept as if /health had said it. */
function noteState(f: Failure) {
    if (f.kind === 'state') keep({ state: f.state, message: f.message, checkedAt: Date.now() })
}

/** For the tests: forget what this module keeps in memory. */
export function resetMemory() {
    memory = null
    lastTry = 0
    asking = null
    busyUntil = 0
    index = null
    queue = Promise.resolve()
}

// ---- errors ------------------------------------------------------------------------------

/** Retry-After in seconds (a number or an HTTP date), held to an hour; null when absent or not one. */
export function retryAfterSeconds(header: string | null, now = Date.now()): number | null {
    if (!header) return null
    const h = header.trim()
    if (/^\d{1,9}$/.test(h)) return Math.min(Number(h), 3600)
    const at = Date.parse(h)
    if (!Number.isFinite(at)) return null
    return Math.max(0, Math.min(Math.ceil((at - now) / 1000), 3600))
}

/**
 * A gateway error in the sheet's terms (API.md's table); anything not in it is offline. Only 410
 * `stopped` and 503 `paused` say MSone's state: the gateway answers 503 `unavailable` for any
 * failure of its own too, so that one is offline (Try again), and only /health says `off`.
 */
export function failureOf(status: number, body: unknown, retryAfter: string | null = null): Failure {
    const code = isRecord(body) ? body.error : null
    const message = isRecord(body) ? text(body.message, MAX_MESSAGE) : null
    if (status === 400) return { kind: 'too_short' }
    if (status === 404) return { kind: 'no_results' }
    if (status === 429) return { kind: 'busy', retryAfter: retryAfterSeconds(retryAfter) }
    if (status === 410) return { kind: 'state', state: 'stopped', message }
    if (status === 503 && code === 'paused') return { kind: 'state', state: 'paused', message }
    return { kind: 'offline' }
}

// ---- search ------------------------------------------------------------------------------

/** One hit of the draft shape, or null when it lacks an id, a title or an https download. */
export function parseHit(v: unknown): Hit | null {
    if (!isRecord(v)) return null
    const rawId = typeof v.id === 'number' && Number.isInteger(v.id) && v.id >= 0 ? String(v.id) : v.id
    // The gateway's ids are the download link base64url coded, a few hundred characters long.
    const id = typeof rawId === 'string' && /^[\w.-]{1,4096}$/.test(rawId) ? rawId : null
    const title = text(v.title, MAX_TITLE)
    const downloadUrl = httpsUrl(v.downloadUrl)
    if (!id || !title || !downloadUrl) return null
    const tr = v.translator
    const trName = isRecord(tr) ? text(tr.name, MAX_NAME) : text(tr, MAX_NAME)
    return {
        id,
        title,
        year: whole(v.year, 1870, 2100),
        season: whole(v.season, 0, 999),
        episode: whole(v.episode, 0, 9999),
        translator: trName ? { name: trName, url: isRecord(tr) ? httpsUrl(tr.url) : null } : null,
        pageUrl: httpsUrl(v.pageUrl),
        downloadUrl,
    }
}

/** `/msone/search`'s hits, the bad ones left out and the list held to 100; null when the answer has no list. */
export function parseHits(body: unknown): Hit[] | null {
    if (!isRecord(body) || !Array.isArray(body.hits)) return null
    const out: Hit[] = []
    for (const raw of body.hits.slice(0, MAX_HITS * 10)) {
        const hit = parseHit(raw)
        if (hit) out.push(hit)
        if (out.length >= MAX_HITS) break
    }
    return out
}

/** The search's address, or null when there is nothing to search with (an empty title, no IMDb id). */
export function searchUrl(ask: Ask): string | null {
    const params = new URLSearchParams()
    const imdb = typeof ask.imdb === 'string' && /^tt\d{5,10}$/.test(ask.imdb.trim()) ? ask.imdb.trim() : null
    const q = typeof ask.q === 'string' ? ask.q.replace(/\s+/g, ' ').trim().slice(0, MAX_QUERY).trim() : ''
    if (!imdb && !q) return null
    if (imdb) params.set('imdb', imdb)
    if (q) params.set('q', q)
    const year = whole(ask.year, 1870, 2100)
    const season = whole(ask.season, 0, 999)
    const episode = whole(ask.episode, 0, 9999)
    if (year !== null && q) params.set('year', String(year))
    if (season !== null) params.set('season', String(season))
    if (episode !== null) params.set('episode', String(episode))
    return `${GATEWAY}/msone/search?${params}`
}

/** Searches MSone. Never throws: an answer that is not the draft shape is `offline`. No retries of its own. */
export async function search(ask: Ask, signal?: AbortSignal, fetcher: Fetch = fetch): Promise<SearchResult> {
    const url = searchUrl(ask)
    if (!url) return { ok: false, failure: { kind: 'too_short' } }
    const now = Date.now()
    if (now < busyUntil) return { ok: false, failure: { kind: 'busy', retryAfter: Math.ceil((busyUntil - now) / 1000) } }
    try {
        return await deadline(SEARCH_MS, signal, async (inner) => {
            const res = await fetcher(url, { signal: inner, credentials: 'omit', headers: keyHeaders() })
            const body = await readJson(res)
            if (!res.ok) {
                const failure = failureOf(res.status, body, res.headers.get('Retry-After'))
                noteFailure(failure)
                return { ok: false, failure }
            }
            const hits = parseHits(body)
            if (!hits) return { ok: false, failure: { kind: 'offline' } }
            if (!hits.length) return { ok: false, failure: { kind: 'no_results' } }
            await keepSearch(url, hits)
            return { ok: true, hits }
        })
    } catch {
        return { ok: false, failure: signal?.aborted ? { kind: 'aborted' } : { kind: 'offline' } }
    }
}

function noteFailure(f: Failure) {
    noteState(f)
    if (f.kind === 'busy') busyUntil = Date.now() + (f.retryAfter ?? 60) * 1000
}

// ---- download ----------------------------------------------------------------------------

/**
 * A hit's file through the gateway (the browser gets CORS there; MSone's own address is never
 * fetched from the page), as a File readSubtitle() takes: MSone serves one subtitle file per video
 * or episode. `signal` is the sheet's: closing it cancels the download.
 */
export async function download(hit: Hit, where: { season: number | null; episode: number | null }, signal?: AbortSignal, fetcher: Fetch = fetch): Promise<DownloadResult> {
    // Kept on the device: no download, whatever MSone's state.
    const key = fileKey(hit, where)
    const kept = await keptFile(key)
    if (kept) return { ok: true, file: kept }
    try {
        return await deadline(DOWNLOAD_MS, signal, async (inner): Promise<DownloadResult> => {
            const res = await fetcher(`${GATEWAY}/msone/download/${encodeURIComponent(hit.id)}`, { signal: inner, credentials: 'omit', headers: keyHeaders() })
            if (!res.ok) {
                const f = failureOf(res.status, await readJson(res), res.headers.get('Retry-After'))
                noteFailure(f)
                // No such release (404) or a refused id (400): this hit cannot be had.
                return { ok: false, failure: f.kind === 'no_results' || f.kind === 'too_short' ? { kind: 'unreadable' } : f }
            }
            const bytes = await readCapped(res, FILE_MAX)
            if (!bytes) return { ok: false, failure: { kind: 'unreadable' } }
            const out = await fileOf(bytes, hit, where, dispositionName(res.headers.get('Content-Disposition')))
            if (out.ok) await keepFile(key, out.file)
            return out
        })
    } catch {
        return { ok: false, failure: signal?.aborted ? { kind: 'aborted' } : { kind: 'offline' } }
    }
}

/** The bytes as a subtitle File, named as MSone sent it; an archive is not a subtitle. */
export async function fileOf(bytes: Uint8Array, hit: Hit, where: { season: number | null; episode: number | null }, sent: string | null): Promise<DownloadResult> {
    if (bytes.length > FILE_MAX || isZip(bytes) || isOtherArchive(bytes)) return { ok: false, failure: { kind: 'unreadable' } }
    const ext = sent ? extensionOf(sent) : ''
    const name = sent && SUBTITLE_EXT.includes(ext) ? sent : `${hit.title.replace(/[\\/:*?"<>|]+/g, ' ').trim() || 'MSone'}.srt`
    return { ok: true, file: new File([bytes as BlobPart], name) }
}

/** The file name Content-Disposition gives (RFC 6266's `filename*` first), without any folder; null when none. */
export function dispositionName(header: string | null): string | null {
    if (!header) return null
    let name: string | null = null
    const star = /filename\*\s*=\s*[\w-]*'[^']*'([^;]+)/i.exec(header)
    if (star) {
        try {
            name = decodeURIComponent(star[1].trim())
        } catch {}
    }
    name ??= /filename\s*=\s*"([^"]*)"/i.exec(header)?.[1] ?? /filename\s*=\s*([^;\s]+)/i.exec(header)?.[1] ?? null
    if (!name) return null
    return text(baseName(name).replace(/[\u0000-\u001f\u007f]/g, ''), 200)
}

const baseName = (path: string) => path.slice(Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\')) + 1)
const extensionOf = (name: string) => (name.includes('.') ? name.slice(name.lastIndexOf('.') + 1).toLowerCase() : '')

/** RAR and 7z: archives, which readSubtitle would only call unreadable later. */
const isOtherArchive = (b: Uint8Array) => (b[0] === 0x52 && b[1] === 0x61 && b[2] === 0x72 && b[3] === 0x21) || (b[0] === 0x37 && b[1] === 0x7a && b[2] === 0xbc && b[3] === 0xaf)
export const isZip = (b: Uint8Array) => b.length >= 4 && b[0] === 0x50 && b[1] === 0x4b && b[2] === 0x03 && b[3] === 0x04

/** The season and episode a name gives (S01E02, 1x02, E02), or null; the sheet reads the video's with it. */
export function episodeOf(name: string): { season: number | null; episode: number } | null {
    const se = /S(\d{1,2})[\s._-]*E[Pp]?(\d{1,4})/i.exec(name)
    if (se) return { season: Number(se[1]), episode: Number(se[2]) }
    const x = /(?:^|[^\dA-Za-z])(\d{1,2})x(\d{2,3})(?![\dA-Za-z])/i.exec(name)
    if (x) return { season: Number(x[1]), episode: Number(x[2]) }
    const e = /(?:^|[^A-Za-z])(?:E|EP|Episode)[\s._-]*(\d{1,4})(?!\d)/i.exec(name)
    if (e) return { season: null, episode: Number(e[1]) }
    return null
}

// ---- reading answers ---------------------------------------------------------------------

/** A stream's bytes, or null once they pass `cap` (the rest is not read). */
async function readStream(stream: ReadableStream<Uint8Array>, cap: number): Promise<Uint8Array | null> {
    const reader = stream.getReader()
    const parts: Uint8Array[] = []
    let total = 0
    for (;;) {
        const { done, value } = await reader.read()
        if (done) break
        total += value.length
        if (total > cap) {
            reader.cancel().catch(() => {})
            return null
        }
        parts.push(value)
    }
    const out = new Uint8Array(total)
    let at = 0
    for (const p of parts) (out.set(p, at), (at += p.length))
    return out
}

/** The body, or null when it says or turns out to be larger than `cap`. */
async function readCapped(res: Response, cap: number): Promise<Uint8Array | null> {
    const declared = Number(res.headers.get('Content-Length'))
    if (declared > cap) {
        res.body?.cancel().catch(() => {})
        return null
    }
    if (res.body && typeof res.body.getReader === 'function') return readStream(res.body, cap)
    const all = new Uint8Array(await res.arrayBuffer())
    return all.length <= cap ? all : null
}

/** The body as JSON, or null when it is not JSON or too large to be an answer. */
async function readJson(res: Response): Promise<unknown> {
    try {
        const bytes = await readCapped(res, MAX_JSON)
        return bytes ? JSON.parse(new TextDecoder().decode(bytes)) : null
    } catch (e) {
        if ((e as { name?: string })?.name === 'AbortError') throw e
        return null
    }
}

/** Runs `fn` with a signal that aborts after `ms` or with `outer`, whichever comes first. */
async function deadline<T>(ms: number, outer: AbortSignal | undefined, fn: (signal: AbortSignal) => Promise<T>): Promise<T> {
    const ctl = new AbortController()
    const stop = () => ctl.abort()
    if (outer?.aborted) ctl.abort()
    outer?.addEventListener('abort', stop)
    const timer = setTimeout(stop, ms)
    try {
        return await fn(ctl.signal)
    } finally {
        clearTimeout(timer)
        outer?.removeEventListener('abort', stop)
    }
}

// ---- kept on the device --------------------------------------------------------------------

/** The few calls the keeping needs: idb-keyval's own database in the page, a Map in the tests. */
export interface KeepStore {
    get(key: string): Promise<unknown>
    set(key: string, value: unknown): Promise<void>
    del(keys: string[]): Promise<void>
    keys(): Promise<string[]>
}

/** About the apps' cache: 200 files or 20 MB, whichever comes first, the least recently used going. */
export const KEEP_FILES = 200
export const KEEP_BYTES = 20 * 1024 * 1024
const KEEP_SEARCHES = 100
/** A store that does not answer (some private windows) is given up on rather than waited for. */
const STORE_MS = 2000
const INDEX = 'index'

function idbStore(): KeepStore {
    let db: UseStore | null = null
    const store = () => (db ??= createStore('tmplayer-msone', 'kv'))
    return {
        get: (key) => get(key, store()),
        set: (key, value) => set(key, value, store()),
        del: (keys) => delMany(keys, store()),
        keys: async () => (await storeKeys(store())).filter((k): k is string => typeof k === 'string'),
    }
}

let store: KeepStore = idbStore()

/** For the tests: the store to keep in (a Map, or one that fails). */
export function setKeepStore(next: KeepStore) {
    store = next
    index = null
}

/** What is kept, by key: [last used, bytes] for files, [saved] for searches. One record, so the bounds need no walk. */
interface Index {
    files: Record<string, [number, number]>
    searches: Record<string, number>
}

let index: Index | null = null
/** Writes one after another, so two at once never lose each other's entries. */
let queue: Promise<unknown> = Promise.resolve()

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

function readIndex(v: unknown): Index {
    const out: Index = { files: {}, searches: {} }
    if (!isRecord(v)) return out
    if (isRecord(v.files))
        for (const [k, r] of Object.entries(v.files)) if (Array.isArray(r) && typeof r[0] === 'number' && typeof r[1] === 'number') out.files[k] = [r[0], r[1]]
    if (isRecord(v.searches)) for (const [k, at] of Object.entries(v.searches)) if (typeof at === 'number') out.searches[k] = at
    return out
}

async function loadIndex(): Promise<Index> {
    index ??= readIndex(await guarded(() => store.get(INDEX), null))
    return index
}

/** Runs `work` after the writes before it; a failure is swallowed (the sheet works without keeping). */
function write(work: () => Promise<void>): Promise<void> {
    const next = queue.then(() => guarded(work, undefined))
    queue = next
    return next
}

/** The kept file's key: the hit, which is one video's or episode's own file. */
export function fileKey(hit: Hit, _where?: { season: number | null; episode: number | null }): string {
    return `msone:${hit.id}`
}

/** A kept subtitle as a File, or null when none is kept (or the store cannot say). */
export async function keptFile(key: string): Promise<File | null> {
    const ix = await loadIndex()
    if (!ix.files[key]) return null
    const v = await guarded(() => store.get(`file|${key}`), null)
    const name = isRecord(v) ? text(v.name, 200) : null
    const bytes = isRecord(v) && v.bytes instanceof Uint8Array && v.bytes.length <= FILE_MAX ? v.bytes : null
    if (!name || !bytes) return null
    ix.files[key] = [Date.now(), bytes.length]
    void write(() => store.set(INDEX, ix))
    return new File([bytes as BlobPart], name)
}

/** Keeps a file taken, then drops the least recently used past 200 files or 20 MB. Not while MSone is stopped. */
export async function keepFile(key: string, file: File): Promise<void> {
    if (knownHealth().state === 'stopped') return
    let bytes: Uint8Array
    try {
        bytes = new Uint8Array(await file.arrayBuffer())
    } catch {
        return
    }
    await write(async () => {
        const ix = await loadIndex()
        await store.set(`file|${key}`, { name: file.name, bytes })
        ix.files[key] = [Date.now(), bytes.length]
        const order = Object.entries(ix.files).sort((a, b) => b[1][0] - a[1][0])
        const gone: string[] = []
        let total = 0
        order.forEach(([k, [, size]], i) => {
            total += size
            if (i >= KEEP_FILES || total > KEEP_BYTES) gone.push(k)
        })
        for (const k of gone) delete ix.files[k]
        await store.set(INDEX, ix)
        if (gone.length) await store.del(gone.map((k) => `file|${k}`))
    })
}

/** Keeps a search's answer (a non-empty one) under its address, the newest 100. */
async function keepSearch(url: string, hits: Hit[]): Promise<void> {
    if (knownHealth().state === 'stopped') return
    await write(async () => {
        const ix = await loadIndex()
        await store.set(`search|${url}`, hits)
        ix.searches[url] = Date.now()
        const gone = Object.entries(ix.searches)
            .sort((a, b) => b[1] - a[1])
            .slice(KEEP_SEARCHES)
            .map(([k]) => k)
        for (const k of gone) delete ix.searches[k]
        await store.set(INDEX, ix)
        if (gone.length) await store.del(gone.map((k) => `search|${k}`))
    })
}

/**
 * The hits of this video's last kept search whose file is kept too (the apps' `cache.search(key)`
 * filtered the same way): what can still be laid over the video while MSone is paused.
 */
export async function keptHits(ask: Ask, where: { season: number | null; episode: number | null }): Promise<Hit[]> {
    const url = searchUrl(ask)
    if (!url) return []
    const ix = await loadIndex()
    if (!ix.searches[url]) return []
    const v = await guarded(() => store.get(`search|${url}`), null)
    const hits = parseHits({ hits: v }) ?? []
    return hits.filter((h) => ix.files[fileKey(h, where)])
}

/** Deletes every kept MSone file and search (MSone stopped). */
export function forgetKept(): Promise<void> {
    return write(async () => {
        index = { files: {}, searches: {} }
        const all = await store.keys()
        await store.del(all.filter((k) => k === INDEX || k.startsWith('file|') || k.startsWith('search|')))
    })
}

/** What the kept files take, for Settings' storage breakdown. */
export async function keptBytes(): Promise<number> {
    const ix = await loadIndex()
    return Object.values(ix.files).reduce((sum, [, size]) => sum + size, 0)
}
