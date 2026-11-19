// The web's log bundle (WB18, reliability plan L4 and L5): a small ring of what the page says about
// itself, scrubbed the way core's LogScrubber does it, and the one text file Copy details and Report a
// problem hand over. The twin of core's LogScrubber, LogExport and LogBundle.
//
// Privacy, in three rules. Nothing here is sent: the ring lives in memory, goes with the tab, and
// leaves the device only when the viewer presses Copy details (or, once the inbox takes web uploads,
// a Send that lists what goes: [uploadLogs] is the hook, no screen calls it yet). Names the page
// has seen (a chat's or a video's title) are taken out word for word at export, and then names, file
// names, e-mail addresses, phone numbers, @usernames and long numbers by pattern. What a viewer
// plays never reaches a line: the callers write fixed words and bucketed counts.
//
// No imports and no DOM beyond try/catch'd `localStorage`, so node can test it and the page's first
// load pays for a few hundred bytes of ring. No look-behind in a pattern: Safari before 16.4 throws
// on one when the module loads.

/** The ring keeps this many lines; the oldest go first. */
export const MAX_LINES = 500
/** A line is never worth more than this: the rest is dropped before it is scrubbed (the patterns below are not linear on a long unbroken run). */
export const MAX_LINE = 2000
/** Under the inbox's 2 MB cap (workers/log-inbox) with room for its envelope; core's LogBundle.MAX_BYTES. */
export const MAX_BYTES = 1_500_000
/** A known name shorter than this is not taken out ("TV" taken out everywhere would leave nothing to read). */
export const MIN_KNOWN = 4
/** The most names kept to take out; the newest win. */
export const MAX_KNOWN = 200
/** The earlier run's crash is kept this short. */
export const MAX_CRASH = 600
export const CRASH_KEY = 'tmplayer-last-crash'

const EXTENSIONS =
    'mkv|mp4|m4v|avi|webm|mov|wmv|flv|ts|m2ts|mpg|mpeg|3gp|mp3|m4a|aac|flac|ogg|opus|wav|srt|ass|ssa|vtt|sub|idx|sup|zip|rar|7z|pdf|jpg|jpeg|png|webp|gif|epub|apk'
const EMAIL = /[\w.+-]+@[\w-]+(?:\.[\w-]+)+/gu
const PATH_FILE = new RegExp(String.raw`([/\\])[^/\\\r\n]+?\.(?:${EXTENSIONS})\b`, 'giu')
const BARE_FILE = new RegExp(String.raw`[^\s/\\:'"()\[\]]+\.(?:${EXTENSIONS})\b`, 'giu')
const PHONE = /\+\d[\d \-()]{5,}\d/gu
/** A Telegram username: an @ not glued to a word (so `Object@1a2b3c` stays), then five or more of what a username may hold. */
const USERNAME = /(^|[^\w.@])@[A-Za-z][A-Za-z0-9_]{4,}/gu
const DIGITS = /\d{6,}/gu
// eslint-disable-next-line no-control-regex
const CONTROL = /[\u0000-\u0008\u000b-\u001f\u007f-\u009f\u2028\u2029]/gu

const escapeRegex = (s: string) => s.replace(/[.*+?^${}()|[\]\\]/gu, '\\$&')

/** Takes what is personal out of one log line, keeping the tag, the words and the numbers a bug needs. [known] are names to take out as written. */
export function scrubLine(line: unknown, known: readonly RegExp[] = []): string {
    let out = typeof line === 'string' ? line : String(line ?? '')
    if (out.length > MAX_LINE) out = `${out.slice(0, MAX_LINE)} [cut]`
    out = out.replace(CONTROL, ' ')
    for (const name of known) out = out.replace(name, '[name]')
    out = out.replace(EMAIL, '[email]')
    out = out.replace(PATH_FILE, (_m, slash: string) => `${slash}[file]`)
    out = out.replace(BARE_FILE, '[file]')
    out = out.replace(PHONE, '[phone]')
    out = out.replace(USERNAME, '$1@[user]')
    out = out.replace(DIGITS, '[number]')
    return out
}

/** The patterns for [list]: whole names of [MIN_KNOWN] characters or more, longest first so one that holds another goes whole. Anything else is skipped. */
export function knownPatterns(list: Iterable<unknown>): RegExp[] {
    const seen = new Set<string>()
    for (const n of list) {
        if (typeof n !== 'string') continue
        const t = n.trim().slice(0, 300)
        if (t.length >= MIN_KNOWN) seen.add(t)
    }
    return [...seen].sort((a, b) => b.length - a.length).map((n) => new RegExp(escapeRegex(n), 'giu'))
}

// ---- The ring ------------------------------------------------------------------------------------------

let ring: string[] = []
const names: string[] = []

const stamp = (at = new Date()) => at.toISOString().slice(11, 19)

/** Adds one line to the ring (`tag` is a fixed word such as `player`; `text` is cut to [MAX_LINE]). Never throws. */
export function log(level: 'I' | 'W' | 'E', tag: string, text: unknown): void {
    try {
        const body = typeof text === 'string' ? text : text instanceof Error ? `${text.name}: ${text.message}` : String(text ?? '')
        ring.push(`${stamp()} ${level} ${String(tag).slice(0, 24)}: ${body.slice(0, MAX_LINE)}`)
        if (ring.length > MAX_LINES) ring = ring.slice(ring.length - MAX_LINES)
    } catch {
        // A log line is never worth a failure.
    }
}

/** The ring's lines, oldest first, not scrubbed yet. */
export const ringLines = (): string[] => ring.slice()

/** Names the page has seen (a chat's or a video's title) so the export can take them out as written. Bounded; the newest win. */
export function rememberNames(list: Iterable<unknown>): void {
    for (const n of list) {
        if (typeof n !== 'string' || n.trim().length < MIN_KNOWN) continue
        const t = n.trim().slice(0, 300)
        const at = names.indexOf(t)
        if (at >= 0) names.splice(at, 1)
        names.push(t)
    }
    if (names.length > MAX_KNOWN) names.splice(0, names.length - MAX_KNOWN)
}

const clearers: (() => void)[] = []
/** Somebody else's in-memory trace (the stutter marks) goes when the logs do. */
export function onClear(fn: () => void): void {
    clearers.push(fn)
}

/** Forgets the ring, the names and the earlier run's crash (sign out, Delete your data). */
export function clearLogs(): void {
    ring = []
    names.length = 0
    for (const fn of clearers) {
        try {
            fn()
        } catch {
            // One that cannot clear does not stop the rest.
        }
    }
    try {
        localStorage.removeItem(CRASH_KEY)
    } catch {
        // No storage: nothing was kept there.
    }
}

let hooked = false
const asText = (args: unknown[]) =>
    args
        .map((a) => (a instanceof Error ? `${a.name}: ${a.message}` : typeof a === 'string' ? a : typeof a === 'object' && a !== null ? Object.prototype.toString.call(a) : String(a)))
        .join(' ')

/** Copies console.warn and console.error into the ring (the page's own tags such as `[player]` come with them); the console still prints. Once. */
export function hookConsole(c: Pick<Console, 'warn' | 'error'> = console): void {
    if (hooked) return
    hooked = true
    for (const level of ['warn', 'error'] as const) {
        const original = c[level].bind(c)
        c[level] = (...args: unknown[]) => {
            log(level === 'error' ? 'E' : 'W', 'console', asText(args))
            original(...args)
        }
    }
}

// ---- The earlier run's crash -----------------------------------------------------------------------------

/** What the crash card keeps for the next run's bundle: the error's kind and message, scrubbed and cut. Never throws. */
export function noteCrash(reason: unknown, at = new Date()): void {
    try {
        const name = (reason as { name?: unknown } | null)?.name
        const message = (reason as { message?: unknown } | null)?.message ?? reason
        const text = scrubLine(`${typeof name === 'string' ? name : 'Error'}: ${typeof message === 'string' ? message : Object.prototype.toString.call(message)}`).slice(0, MAX_CRASH)
        log('E', 'crash', text)
        localStorage.setItem(CRASH_KEY, JSON.stringify({ at: at.toISOString().slice(0, 16).replace('T', ' '), text }))
    } catch {
        // Storage refused: the ring still has it.
    }
}

/** The crash kept by an earlier run as plain lines, or none. Trusts nothing in the stored value. */
export function earlierCrash(storage: Pick<Storage, 'getItem'> | null = safeStorage()): string[] {
    try {
        const raw = storage?.getItem(CRASH_KEY)
        if (typeof raw !== 'string' || raw.length > MAX_CRASH * 3) return []
        const o: unknown = JSON.parse(raw)
        if (typeof o !== 'object' || o === null || Array.isArray(o)) return []
        const { at, text } = o as Record<string, unknown>
        if (typeof text !== 'string' || text === '') return []
        const when = typeof at === 'string' && /^\d{4}-\d\d-\d\d \d\d:\d\d$/u.test(at) ? at : 'an earlier time'
        return [`The page stopped on ${when}: ${text.slice(0, MAX_CRASH)}`]
    } catch {
        return []
    }
}

function safeStorage(): Storage | null {
    try {
        return typeof localStorage === 'undefined' ? null : localStorage
    } catch {
        return null
    }
}

// ---- The bundle ----------------------------------------------------------------------------------------------

export interface BundleFacts {
    version: string
    platform: string
    device: string
    language: string
}

export interface BundleParts {
    /** The device's facts in fixed words and buckets (engines, decoder support), one line each. */
    device?: string[]
    /** The moments marked as stuttering, as [StutterMarks.reportLines] writes them. */
    marks?: string[]
    /** The earlier run's crash; [earlierCrash] when left out. */
    crash?: string[]
}

const suppliers: Partial<Record<'device' | 'marks', () => string[]>> = {}
/** The player says where the device's facts and the marks come from, so Copy details on any screen can attach them. */
export function supply(kind: 'device' | 'marks', fn: () => string[]): void {
    suppliers[kind] = fn
}

/** What is supplied now: the parts [buildBundle] takes, each from its supplier, none that fails. */
export function suppliedParts(): BundleParts {
    const out: BundleParts = {}
    for (const kind of ['device', 'marks'] as const) {
        try {
            const lines = suppliers[kind]?.()
            if (Array.isArray(lines)) out[kind] = lines.filter((l) => typeof l === 'string')
        } catch {
            // A supplier that throws gives no part.
        }
    }
    return out
}

export type PartKind = 'crash' | 'device' | 'marks' | 'log'

export interface Bundle {
    text: string
    bytes: number
    parts: { kind: PartKind; count: number }[]
    droppedLines: number
}

/**
 * The one text file: a header, the earlier run's crash, the device's facts, the moments marked as
 * stuttering and the log, every line scrubbed the same way and the whole under [MAX_BYTES]; when it
 * is over, the oldest log lines go first (what is newest is what a report is about).
 */
export function buildBundle(facts: BundleFacts, parts: BundleParts = {}, extraNames: Iterable<unknown> = [], saved = new Date(), maxBytes = MAX_BYTES): Bundle {
    const known = knownPatterns([...names, ...extraNames])
    const clean = (lines: readonly string[] | undefined) => (lines ?? []).filter((l) => typeof l === 'string').map((l) => scrubLine(l, known))
    const crash = clean(parts.crash ?? earlierCrash())
    const device = clean(parts.device)
    const marks = clean(parts.marks)
    let lines = ring.slice(-MAX_LINES).map((l) => scrubLine(l, known))
    const header = [
        'TMPlayer logs',
        `App version: ${scrubLine(facts.version)}`,
        `Platform: ${scrubLine(facts.platform) || 'other'}`,
        `Device: ${scrubLine(facts.device)}`,
        `App language: ${scrubLine(facts.language)}`,
        `Saved: ${saved.toISOString().slice(0, 16).replace('T', ' ')}`,
        'Names, long numbers, phone numbers, @usernames and file names are replaced with placeholders such as [name] and [number].',
    ]
    const encoder = new TextEncoder()
    const size = (list: readonly string[]) => list.reduce((n, l) => n + encoder.encode(l).length + 1, 0)
    const fixed = size(header) + size(crash) + size(device) + size(marks) + 1000
    let logBytes = size(lines)
    let dropped = 0
    while (fixed + logBytes > maxBytes && lines.length > 0) {
        const cut = Math.max(1, Math.floor(lines.length / 10))
        logBytes -= size(lines.slice(0, cut))
        lines = lines.slice(cut)
        dropped += cut
    }
    const out: string[] = [...header]
    if (dropped > 0) out.push(`To fit the size limit, ${dropped} older log line(s) were left out.`)
    const section = (title: string, list: readonly string[]) => {
        if (list.length === 0) return
        out.push('', `== ${title} ==`, ...list)
    }
    section('Earlier run', crash)
    section('Device', device)
    section('Moments marked as stuttering', marks)
    section('Page log, kept in this tab only', lines)
    const text = `${out.join('\n')}\n`
    const included: Bundle['parts'] = []
    if (crash.length) included.push({ kind: 'crash', count: crash.length })
    if (device.length) included.push({ kind: 'device', count: device.length })
    if (marks.length) included.push({ kind: 'marks', count: marks.length })
    if (lines.length) included.push({ kind: 'log', count: lines.length })
    return { text, bytes: encoder.encode(text).length, parts: included, droppedLines: dropped }
}

/** "4 KB" or "1.2 MB" for the line that says what the logs weigh. */
export function sizeText(bytes: number): string {
    const n = Number.isFinite(bytes) && bytes > 0 ? bytes : 0
    if (n < 1024 * 1024) return `${Math.max(1, Math.ceil(n / 1024))} KB`
    return `${Math.round((n / 1024 / 1024) * 10) / 10} MB`
}

// ---- The upload hook (WB28, not wired to any screen) ---------------------------------------------------------------

const REPORT_CODE = /"code"\s*:\s*"(TMP-[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{4})"/u

/** The code a `/v1/report` answer holds (`{"code":"TMP-7K3Q","keepDays":30}`), or null for anything else. Reads text from outside, so it never throws. */
export function reportCode(status: number, body: unknown): string | null {
    if (status !== 201 || typeof body !== 'string') return null
    return REPORT_CODE.exec(body.slice(0, 4096))?.[1] ?? null
}

/** The log inbox's report route for the feedback address the build carries (`https://host/v1/feedback` gives `https://host/v1/report`), or null. */
export function reportUrl(feedbackAddress: string | null): string | null {
    if (!feedbackAddress) return null
    try {
        const u = new URL(feedbackAddress)
        if (u.protocol !== 'https:') return null
        u.pathname = u.pathname.replace(/\/v1\/feedback\/?$/u, '/v1/report')
        return u.pathname.endsWith('/v1/report') ? u.toString() : null
    } catch {
        return null
    }
}

/**
 * Sends one bundle to the inbox once the viewer has seen what is in it and pressed Send: the code to
 * say in the group or an issue, or null (offline, refused, too big, an answer that is not one). Not
 * called by any screen until the inbox allows this origin on `/v1/report` and the user turns the Add
 * my logs line on (WB28).
 */
export async function uploadLogs(text: string, url: string, fetcher: typeof fetch = (...a) => fetch(...a)): Promise<string | null> {
    if (typeof text !== 'string' || !text.startsWith('TMPlayer logs') || new TextEncoder().encode(text).length > 2_000_000) return null
    const controller = new AbortController()
    const timer = setTimeout(() => controller.abort(), 20_000)
    try {
        const res = await fetcher(url, {
            method: 'POST',
            headers: { 'Content-Type': 'text/plain; charset=utf-8' },
            body: text,
            cache: 'no-store',
            credentials: 'omit',
            referrerPolicy: 'no-referrer',
            redirect: 'error',
            signal: controller.signal,
        })
        return reportCode(res.status, await res.text().catch(() => ''))
    } catch {
        return null
    } finally {
        clearTimeout(timer)
    }
}
