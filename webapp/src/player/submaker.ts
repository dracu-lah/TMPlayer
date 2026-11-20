// SubMaker, a Stremio subtitles addon the viewer installs themselves: its install address (the
// "base", which holds the viewer's secret token in its path), the list it answers for a video, and
// the translation it makes on request. Pure and plain, no DOM and no Solid, so `node --test` reads it
// as it is; the fetching, the clock, the sleeping and the tab's visibility are all passed in.
//
// Protocol: GET {base}/subtitles/{type}/{id}.json answers {subtitles: [{id, lang, label?, url}]}.
// A normal entry is a SubRip file at its url. A "Make <Language>" entry (lang "Make Malayalam") has a
// url that answers AT ONCE with a placeholder whose first cue says TRANSLATION IN PROGRESS, and the
// SAME url fetched again gives the translation so far, growing over one to three minutes, with no
// signal that it is complete. So the poller below watches the cue count and calls it done when the
// count stops growing for a while.
//
// The base is a secret. Nothing here puts it, or an address made from it, in a message, an error or
// a log line: failures are fixed words (`Why`), never the text of an exception.

/** The longest address taken. */
export const MAX_ADDRESS = 2000
/** Entries kept from one answer. */
export const MAX_ENTRIES = 300
/** Raw entries looked at, so a flood of unusable ones costs a bounded scan. */
const MAX_SCAN = 2000
const MAX_ID = 300
const MAX_LANG = 80
const MAX_LABEL = 160
/** A subtitle file or a list is tens of kilobytes; more is not one. */
export const MAX_BODY = 4 * 1024 * 1024
const LOOPBACK = new Set(['127.0.0.1', 'localhost', '[::1]'])

const isRecord = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v)

function text(v: unknown, max: number): string | null {
    if (typeof v !== 'string') return null
    const s = v.trim()
    return s && s.length <= max ? s : null
}

/** https, or http on this machine (for tests); nothing else is fetched. */
function allowed(u: URL): boolean {
    return u.protocol === 'https:' || (u.protocol === 'http:' && LOOPBACK.has(u.hostname))
}

// ---- the base address -----------------------------------------------------------------------

/**
 * The base from what the viewer pasted: the https install address, its stremio:// twin, with or
 * without the trailing manifest.json, slash or spaces. Returns the address without a trailing slash
 * or manifest.json (a host alone is a base), or null when it is not one: another scheme, plain http
 * off this machine, a login in it, control characters, more than 2000 characters. A query and a
 * fragment are dropped.
 */
export function normalizeBase(input: unknown): string | null {
    if (typeof input !== 'string') return null
    const s = input.trim()
    if (!s || s.length > MAX_ADDRESS) return null
    // eslint-disable-next-line no-control-regex
    if (/[\u0000-\u0020\u007f-\u009f\u2028\u2029]/u.test(s)) return null
    let u: URL
    try {
        u = new URL(s.replace(/^stremio:\/\//i, 'https://'))
    } catch {
        return null
    }
    if (!allowed(u) || u.username || u.password || !u.hostname) return null
    const path = u.pathname.replace(/\/+$/, '').replace(/\/manifest\.json$/i, '').replace(/\/+$/, '')
    return `${u.protocol}//${u.host}${path}`
}

/** The id forms the addon is asked about: tt1234567, tt1234567:1:2, kitsu:1234 or kitsu:1234:5. */
const ID = /^(?:tt\d{5,10}(?::\d{1,4}:\d{1,5})?|kitsu:\d{1,9}(?::\d{1,5})?)$/

export type Kind = 'movie' | 'series' | 'anime'

/** The list's address for a video, or null when the id is not one the addon is asked about. */
export function listUrl(base: string, type: Kind, id: string): string | null {
    if (!ID.test(id) || (type !== 'movie' && type !== 'series' && type !== 'anime')) return null
    return `${base}/subtitles/${type}/${id}.json`
}

/** The id for a video: IMDb's for a movie, with season and episode for a show; null without an IMDb id. */
export function idFor(imdb: string | null | undefined, season: number | null, episode: number | null): string | null {
    const tt = typeof imdb === 'string' && /^tt\d{5,10}$/.test(imdb.trim()) ? imdb.trim() : null
    if (!tt) return null
    if (season !== null && episode !== null && Number.isInteger(season) && Number.isInteger(episode) && season >= 0 && episode >= 0 && season < 10000 && episode < 100000) return `${tt}:${season}:${episode}`
    return tt
}

// ---- the list ---------------------------------------------------------------------------------

export interface Entry {
    id: string
    /** The addon's own language word: an ISO 639-2 code ("eng"), or "Make Malayalam" for a translation. */
    lang: string
    label: string
    url: string
    /** For a "Make <Language>" entry, the language's name ("Malayalam"); absent for a file. */
    make?: string
}

/** Every language name a translation may be asked for is a short plain word or two. */
const MAKE = /^make\s+(.{1,60})$/i

/**
 * The entries of an answer, the unusable ones left out. Never throws; at most 300; the toolbox page is not a subtitle.
 * An entry's address is fetched only from the host of [base] (the viewer's own install): an answer cannot
 * send the page to another server, so a foreign host drops the entry. No usable [base] drops them all.
 */
export function parseList(json: unknown, base: string): Entry[] {
    if (!isRecord(json) || !Array.isArray(json.subtitles)) return []
    let host: string
    try {
        host = new URL(base).hostname.toLowerCase()
    } catch {
        return []
    }
    if (!host) return []
    const out: Entry[] = []
    const list = json.subtitles
    for (let i = 0; i < list.length && i < MAX_SCAN && out.length < MAX_ENTRIES; i++) {
        const raw: unknown = list[i]
        if (!isRecord(raw)) continue
        const id = text(raw.id, MAX_ID)
        const lang = text(raw.lang, MAX_LANG)
        const address = text(raw.url, MAX_ADDRESS)
        if (!id || !lang || !address || id === 'sub_toolbox') continue
        let url: string
        try {
            const u = new URL(address)
            if (!allowed(u) || u.username || u.password || u.hostname.toLowerCase() !== host) continue
            url = u.href
        } catch {
            continue
        }
        const label = text(raw.label, MAX_LABEL) ?? lang
        const named = MAKE.exec(lang)?.[1]?.trim()
        const translate = /^translate_/i.test(id) || /\/translate\//i.test(url)
        const make = named ?? (translate ? lang : undefined)
        out.push(make ? { id, lang, label, url, make } : { id, lang, label, url })
    }
    return out
}

// ---- reading a SubRip answer -------------------------------------------------------------------

const TIMING = /(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})\s*-->\s*(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})/g

const ms = (h: string, m: string, s: string, f: string) => ((Number(h) * 60 + Number(m)) * 60 + Number(s)) * 1000 + Number(f.padEnd(3, '0'))

/** How many cues the text holds (timing lines). */
export function cueCount(srt: unknown): number {
    if (typeof srt !== 'string' || srt.length > MAX_BODY) return 0
    return srt.match(TIMING)?.length ?? 0
}

/** Whether the first cue is the placeholder the addon answers at once: "TRANSLATION IN PROGRESS". */
export function isPlaceholder(srt: unknown): boolean {
    if (typeof srt !== 'string' || srt.length > MAX_BODY) return false
    const re = new RegExp(TIMING.source)
    const first = re.exec(srt)
    if (!first) return false
    const rest = srt.slice(first.index + first[0].length)
    const end = rest.search(/\r?\n[ \t]*\r?\n/)
    const body = end < 0 ? rest : rest.slice(0, end)
    return /translation\s+in\s+progress/i.test(body)
}

/** Where the last cue ends, in milliseconds; 0 when there is none. */
export function lastCueEndMs(srt: unknown): number {
    if (typeof srt !== 'string' || srt.length > MAX_BODY) return 0
    let last = 0
    for (const m of srt.matchAll(TIMING)) last = ms(m[5], m[6], m[7], m[8])
    return last
}

// ---- consent for a translation ---------------------------------------------------------------

const CONSENT_KEY = 'tmplayer-submaker-consent'

/** The few calls of storage the consent needs. */
export type Memory = Pick<Storage, 'getItem' | 'setItem'>

function memoryOf(given?: Memory | null): Memory | null {
    if (given !== undefined) return given
    try {
        return globalThis.localStorage ?? null
    } catch {
        return null
    }
}

function consented(m: Memory | null): string[] {
    try {
        const v: unknown = JSON.parse(m?.getItem(CONSENT_KEY) ?? '[]')
        return Array.isArray(v) ? v.filter((x): x is string => typeof x === 'string').slice(0, 100) : []
    } catch {
        return []
    }
}

/** The viewer said yes to a translation into this language before: asked once per language. */
export function hasConsent(language: string, memory?: Memory | null): boolean {
    return consented(memoryOf(memory)).includes(language.trim().toLowerCase())
}

export function giveConsent(language: string, memory?: Memory | null): void {
    const m = memoryOf(memory)
    const key = language.trim().toLowerCase()
    if (!m || !key || key.length > MAX_LANG) return
    try {
        const list = consented(m)
        if (!list.includes(key)) m.setItem(CONSENT_KEY, JSON.stringify([...list, key].slice(-100)))
    } catch {}
}

// ---- fetching ----------------------------------------------------------------------------------

/** One answer: the status, the text (null when it was refused as too large or unreadable) and Retry-After as written. */
export interface Answer {
    status: number
    text: string | null
    retryAfter?: string | null
}

export type FetchText = (url: string, signal: AbortSignal) => Promise<Answer>

/** Reads a body up to MAX_BODY; null when it is larger (the rest is not read). */
async function readBody(res: Response): Promise<string | null> {
    const declared = Number(res.headers.get('Content-Length'))
    if (declared > MAX_BODY) {
        res.body?.cancel().catch(() => {})
        return null
    }
    if (!res.body || typeof res.body.getReader !== 'function') {
        const all = new Uint8Array(await res.arrayBuffer())
        return all.length <= MAX_BODY ? new TextDecoder().decode(all) : null
    }
    const reader = res.body.getReader()
    const parts: Uint8Array[] = []
    let total = 0
    for (;;) {
        const { done, value } = await reader.read()
        if (done) break
        total += value.length
        if (total > MAX_BODY) {
            reader.cancel().catch(() => {})
            return null
        }
        parts.push(value)
    }
    const all = new Uint8Array(total)
    let at = 0
    for (const p of parts) (all.set(p, at), (at += p.length))
    return new TextDecoder().decode(all)
}

/** One request, answer and body, takes no longer than this: a stuck connection is an error, not a hang. */
export const FETCH_TIMEOUT_MS = 20_000

/**
 * [signal] together with a timeout: `AbortSignal.any` and `AbortSignal.timeout` where the browser has them,
 * else a timer of our own. `done()` clears the timer and the listener when the request is over.
 */
function withTimeout(signal: AbortSignal, ms: number): { signal: AbortSignal; done: () => void } {
    const any = (AbortSignal as { any?: (signals: AbortSignal[]) => AbortSignal }).any
    const timeout = (AbortSignal as { timeout?: (ms: number) => AbortSignal }).timeout
    if (typeof any === 'function' && typeof timeout === 'function') {
        try {
            return { signal: any.call(AbortSignal, [signal, timeout.call(AbortSignal, ms)]), done: () => {} }
        } catch {}
    }
    const ctl = new AbortController()
    const stop = () => ctl.abort()
    if (signal.aborted) ctl.abort()
    signal.addEventListener('abort', stop)
    const timer = setTimeout(stop, ms)
    return {
        signal: ctl.signal,
        done: () => {
            clearTimeout(timer)
            signal.removeEventListener('abort', stop)
        },
    }
}

/** The page's fetch as a FetchText: no cookies, no referrer (the address is a secret), no cache, [FETCH_TIMEOUT_MS] at most. */
export function fetchTextWith(fetcher: typeof fetch = (...a) => fetch(...a), timeoutMs: number = FETCH_TIMEOUT_MS): FetchText {
    return async (url, signal) => {
        const guard = withTimeout(signal, timeoutMs)
        try {
            const res = await fetcher(url, { signal: guard.signal, credentials: 'omit', referrerPolicy: 'no-referrer', cache: 'no-store' })
            const retryAfter = res.headers.get('Retry-After')
            if (!res.ok) {
                res.body?.cancel().catch(() => {})
                return { status: res.status, text: null, retryAfter }
            }
            return { status: res.status, text: await readBody(res), retryAfter }
        } finally {
            guard.done()
        }
    }
}

/** The list for a video, or why not. A bad answer is a failure, never an exception. */
export type ListResult = { ok: true; entries: Entry[] } | { ok: false; why: 'offline' | 'unreadable' | 'aborted' }

export async function fetchList(url: string, fetchText: FetchText, signal: AbortSignal): Promise<ListResult> {
    try {
        const a = await fetchText(url, signal)
        if (signal.aborted) return { ok: false, why: 'aborted' }
        if (a.status < 200 || a.status >= 300) return { ok: false, why: a.status >= 500 || a.status === 429 ? 'offline' : 'unreadable' }
        if (typeof a.text !== 'string') return { ok: false, why: 'unreadable' }
        const entries = parseList(JSON.parse(a.text), url)
        return { ok: true, entries }
    } catch {
        return { ok: false, why: signal.aborted ? 'aborted' : 'unreadable' }
    }
}

/** A file's text, or why not (the toolbox and garbage are not subtitles). */
export type FileResult = { ok: true; srt: string } | { ok: false; why: 'offline' | 'unreadable' | 'aborted' }

export async function fetchSubtitle(url: string, fetchText: FetchText, signal: AbortSignal): Promise<FileResult> {
    try {
        const a = await fetchText(url, signal)
        if (signal.aborted) return { ok: false, why: 'aborted' }
        if (a.status < 200 || a.status >= 300) return { ok: false, why: a.status >= 500 || a.status === 429 ? 'offline' : 'unreadable' }
        if (typeof a.text !== 'string' || cueCount(a.text) === 0 || isPlaceholder(a.text)) return { ok: false, why: 'unreadable' }
        return { ok: true, srt: a.text }
    } catch {
        return { ok: false, why: signal.aborted ? 'aborted' : 'offline' }
    }
}

// ---- the translation ---------------------------------------------------------------------------

/** Why a translation did not finish: fixed words, never an exception's own. */
export type Why = 'offline' | 'unreadable' | 'too_big' | 'empty' | 'refused'

export interface Update {
    lines: number
    srt: string
    final: boolean
}

export type Outcome =
    | { state: 'done'; lines: number; srt: string }
    | { state: 'failed'; why: Why; lines: number; srt: string | null }
    | { state: 'cancelled'; lines: number; srt: string | null }

export interface TranslateOptions {
    /** The "Make <Language>" entry's address: fetched again and again, it is the same job. */
    url: string
    fetchText: FetchText
    sleep: (ms: number, signal: AbortSignal) => Promise<void>
    now: () => number
    /** The tab is visible: nothing is asked and no time counts while it is not. */
    foreground: () => boolean
    onUpdate: (u: Update) => void
    signal?: AbortSignal
    pollMs?: number
    /** Done when the cue count has not grown for this long (foreground time). */
    stableMs?: number
    /** The most foreground time one job takes. */
    capMs?: number
    /** Done as soon as the last cue ends at or after this (the video's length, minus what the caller allows). */
    sourceEndMs?: number
}

export interface Job {
    cancel: () => void
    done: Promise<Outcome>
    /** Cues of the best answer so far. */
    lines: () => number
}

/** Failures in a row that end a job: a flaky answer or two does not. */
const TOLERATED = 2
const MAX_BACKOFF_MS = 60_000
/** The most foreground time one job spends waiting out 429 answers. */
const MAX_BUSY_MS = 10 * 60_000

/** Retry-After in seconds (a number or an HTTP date), as milliseconds; null when absent or not one. */
function retryAfterMs(header: string | null | undefined, now: number): number | null {
    if (!header) return null
    const h = header.trim()
    if (/^\d{1,6}$/.test(h)) return Number(h) * 1000
    const at = Date.parse(h)
    return Number.isFinite(at) ? Math.max(0, at - now) : null
}

/** Starts watching a translation grow. Never throws; `done` always settles. */
export function translate(o: TranslateOptions): Job {
    const pollMs = o.pollMs ?? 10_000
    const stableMs = o.stableMs ?? 180_000
    const capMs = o.capMs ?? 360_000
    const ctl = new AbortController()
    const stop = () => ctl.abort()
    if (o.signal?.aborted) ctl.abort()
    o.signal?.addEventListener('abort', stop)
    let best = 0
    let bestText: string | null = null

    const run = async (): Promise<Outcome> => {
        let fg = 0
        // Foreground time spent waiting out 429 answers: it counts toward neither "not grown" nor the cap.
        let busyFg = 0
        let busyNow = false
        let grewAt = 0
        let last = o.now()
        let wasFg = o.foreground()
        let failures = 0
        let backoff = 0
        const failed = (why: Why): Outcome => ({ state: 'failed', why, lines: best, srt: bestText })
        for (;;) {
            if (ctl.signal.aborted) return { state: 'cancelled', lines: best, srt: bestText }
            // Time counts only when the tab was visible at both ends of it.
            const t = o.now()
            const isFg = o.foreground()
            if (wasFg && isFg) {
                const spent = Math.max(0, t - last)
                fg += spent
                if (busyNow) busyFg += spent
            }
            busyNow = false
            last = t
            wasFg = isFg
            if (!isFg) {
                await o.sleep(pollMs, ctl.signal).catch(() => {})
                continue
            }
            // A job held up by 429s for this long stops with what it has (shown, not final).
            if (busyFg >= MAX_BUSY_MS) return best > 0 && bestText !== null ? partial('offline') : failed('offline')
            // The cap is calm time: the waits the addon asked for are not counted. At the cap the text is partial.
            if (fg - busyFg >= capMs) return best > 0 && bestText !== null ? finish(false) : failed('empty')

            let wait = pollMs
            try {
                const a = await o.fetchText(o.url, ctl.signal)
                if (ctl.signal.aborted) return { state: 'cancelled', lines: best, srt: bestText }
                if (a && a.status === 429) {
                    const asked = retryAfterMs(a.retryAfter, o.now())
                    backoff = asked !== null ? Math.min(asked, MAX_BACKOFF_MS) : Math.min(backoff ? backoff * 2 : pollMs, MAX_BACKOFF_MS)
                    wait = Math.max(backoff, 1000)
                    busyNow = true
                } else if (!a || typeof a.status !== 'number' || a.status < 200 || a.status >= 300) {
                    // A refusal that will not change (404, 401, 403...) ends it; a server error may pass.
                    if (a && typeof a.status === 'number' && a.status >= 400 && a.status < 500) return failed('refused')
                    if (++failures > TOLERATED) return failed('offline')
                } else if (a.text === null || (typeof a.text === 'string' && a.text.length > MAX_BODY)) {
                    return failed('too_big')
                } else if (typeof a.text !== 'string') {
                    if (++failures > TOLERATED) return failed('unreadable')
                } else {
                    backoff = 0
                    const n = cueCount(a.text)
                    if (n === 0 && !isPlaceholder(a.text)) {
                        if (++failures > TOLERATED) return failed('unreadable')
                    } else {
                        failures = 0
                        if (!isPlaceholder(a.text) && n > best) {
                            best = n
                            bestText = a.text
                            grewAt = fg - busyFg
                            o.onUpdate({ lines: n, srt: a.text, final: false })
                        }
                        if (best > 0 && bestText !== null) {
                            if (o.sourceEndMs !== undefined && lastCueEndMs(bestText) >= o.sourceEndMs) return finish(true)
                            if (fg - busyFg - grewAt >= stableMs) return finish(true)
                        }
                    }
                }
            } catch {
                if (ctl.signal.aborted) return { state: 'cancelled', lines: best, srt: bestText }
                if (++failures > TOLERATED) return failed('offline')
            }
            await o.sleep(wait, ctl.signal).catch(() => {})
        }
    }

    /** [final] only at the video's end or after the calm stretch; at the cap what is shown is partial. */
    const finish = (final: boolean): Outcome => {
        const srt = bestText ?? ''
        o.onUpdate({ lines: best, srt, final })
        return { state: 'done', lines: best, srt }
    }

    /** Stopped by the addon's waits: what came is shown (not final) and the line says why. */
    const partial = (why: Why): Outcome => {
        o.onUpdate({ lines: best, srt: bestText ?? '', final: false })
        return { state: 'failed', why, lines: best, srt: bestText }
    }

    // Started on the next tick, so a cancel made right away asks nobody.
    const done = Promise.resolve()
        .then(run)
        .catch((): Outcome => ({ state: 'failed', why: 'unreadable', lines: best, srt: bestText }))
        .finally(() => o.signal?.removeEventListener('abort', stop))
    return { cancel: stop, done, lines: () => best }
}

// ---- one job per video and language --------------------------------------------------------------

const jobs = new Map<string, Job>()

/**
 * The job for this video and language: the one already running if there is one (a second press of
 * the same entry joins it), else a new one. A job leaves the map when it settles, so Try again starts afresh.
 */
export function startJob(videoKey: string, language: string, o: TranslateOptions): Job {
    const key = `${videoKey}|${language.trim().toLowerCase()}`
    const running = jobs.get(key)
    if (running) return running
    const made = translate(o)
    // Cancelling frees the name at once, so a Try again right after never joins a job that is stopping.
    const job: Job = { ...made, cancel: () => (made.cancel(), jobs.get(key) === job && jobs.delete(key), undefined) }
    jobs.set(key, job)
    void made.done.finally(() => jobs.get(key) === job && jobs.delete(key))
    return job
}

/** The job running for this video and language, or undefined. */
export const jobFor = (videoKey: string, language: string): Job | undefined => jobs.get(`${videoKey}|${language.trim().toLowerCase()}`)

/** For the tests: forget every job (cancelling them). */
export function clearJobs() {
    for (const j of jobs.values()) j.cancel()
    jobs.clear()
}

/** A sleep that ends early when the signal aborts, as the page uses it. */
export const pageSleep = (waitMs: number, signal: AbortSignal): Promise<void> =>
    new Promise((resolve) => {
        if (signal.aborted) return resolve()
        const done = () => {
            clearTimeout(timer)
            signal.removeEventListener('abort', done)
            resolve()
        }
        const timer = setTimeout(done, waitMs)
        signal.addEventListener('abort', done)
    })
