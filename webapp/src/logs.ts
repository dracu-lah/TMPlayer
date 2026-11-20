// The web's log bundle (WB18, reliability plan L4 and L5): a small ring of what the page says about
// itself, scrubbed the way core's LogScrubber does it (and further: the web sees URLs), and the one
// text file Copy details and Report a problem hand over. The twin of core's LogScrubber, LogExport
// and LogBundle.
//
// Privacy, in four rules. Nothing here is sent: the ring lives in memory, goes with the tab, and
// leaves the device only when the viewer presses Copy details (or, once the inbox takes web uploads,
// a Send that lists what goes: [uploadLogs] is the hook, no screen calls it yet). Names the page has
// seen (chats, videos, the viewer's own) are taken out at export in every form a log line writes
// them (as typed, accented either way, URL encoded, dotted or underscored). Then URLs lose their
// query and fragment, file names, e-mail addresses, phone numbers, @usernames, long numbers and any
// long run of token characters go by pattern. What cannot be scrubbed reliably is not logged at all:
// the crash note holds an error's class and code, never its message, and callers write fixed words
// and bucketed counts.
//
// No imports and no DOM beyond try/catch'd `localStorage`, so node can test it and the page's first
// load pays for a few hundred bytes of ring. No look-behind in a pattern (Safari before 16.4 throws
// on one when the module loads), and every scan is linear in the line: a 2,000 character line with
// no space costs the same per character as any other.

/** The ring keeps this many lines; the oldest go first. */
export const MAX_LINES = 500
/** A line is never worth more than this: the rest is dropped before it is scrubbed. */
export const MAX_LINE = 2000
/** Under the inbox's 2 MB cap (workers/log-inbox) with room for its envelope; core's LogBundle.MAX_BYTES. */
export const MAX_BYTES = 1_500_000
/** A known name shorter than this is taken out only as a whole word ("Up" inside "Update" stays). */
export const MIN_KNOWN = 4
/** The shortest name that is taken out at all. */
export const MIN_NAME = 2
/** The most names kept to take out; the newest win. A large account's chats fit (3,000), and finding them costs one look-up per character, not one scan per name. */
export const MAX_KNOWN = 4000
export const CRASH_KEY = 'tmplayer-last-crash'
/** The longest a file name with spaces in it is taken out as one, counted back from its extension. */
export const FILE_REACH = 160
/** A console line is kept this long. */
export const MAX_CONSOLE = 300

const EXTENSIONS =
    'mkv|mp4|m4v|avi|webm|mov|wmv|flv|ts|m2ts|mpg|mpeg|3gp|mp3|m4a|aac|flac|ogg|opus|wav|srt|ass|ssa|vtt|sub|idx|sup|zip|rar|7z|pdf|jpg|jpeg|png|webp|gif|epub|apk'
/** An extension, then no more letters or digits: `.mkv` in `Show.mkv `, not in `.mkvtoolnix`. */
const EXTENSION = new RegExp(String.raw`\.(?:${EXTENSIONS})(?![\p{L}\p{N}_])`, 'giu')
/** Where a file name cannot start: a quote, an angle bracket, a pipe, an equals sign, a colon and a space (square brackets and parentheses are in release names). */
const NAME_STOPS = new Set(['"', "'", '<', '>', '|', '=', '\n'])
/** A web address up to its query or fragment, then the query or fragment: only the address stays. */
const URL_TAIL = /(https?:\/\/[^\s"'<>]*?)[?#][^\s"'<>]*/giu
/** A query string anywhere (a relative address, a request line): `?key=value&...`. */
const QUERY = /\?[\w%.~-]*=[^\s"'<>]*/gu
/** A stremio:// address: an addon's install link, whose path holds the viewer's secret. */
const STREMIO = /stremio:\/\/[^\s"'<>]*/giu
/** An addon's address (its list, a translation, its manifest), with the host and secret path before it when they are there. */
const ADDON = /(?:https?:\/\/[^\s"'<>]*)?\/(?:subtitles?\/|translate|sub-toolbox)[^\s"'<>]*|(?:https?:\/\/[^\s"'<>]*)?\/manifest\.json[^\s"'<>]*/giu
/** Any run this long of what tokens, keys and hashes are written in. */
const TOKEN = /[A-Za-z0-9_-]{16,}/gu
const DIGITS = /\d{6,}/gu
/** A phone number written with spaces, dashes or brackets between its digits, with or without a plus: seven digits or more. */
const PHONE = /[+(]?\d(?:[ ()-]{0,2}\d){6,}/gu
/** A Telegram username: an @ not glued to a word (so `Object@1a2b3c` stays), then five or more of what a username may hold. */
const USERNAME = /(^|[^\w.@])@[A-Za-z][A-Za-z0-9_]{4,}/gu
// eslint-disable-next-line no-control-regex
const CONTROL = /[\u0000-\u0008\u000b-\u001f\u007f-\u009f\u2028\u2029]/gu
const EMAIL_CHAR = /[\p{L}\p{N}._%+-]/u
const DOMAIN_CHAR = /[\p{L}\p{N}-]/u
const WORD_CHAR = /[\p{L}\p{N}_]/u

/**
 * Names to take out, as [knownPatterns] builds them: every form of each in lower case, found by look-up at each
 * character (the first four letters of the long ones, the whole of the two- and three-letter ones, which count only as whole words).
 */
export interface Known {
    long: Map<string, string[]>
    three: Set<string>
    two: Set<string>
}
const NOBODY: Known = { long: new Map(), three: new Set(), two: new Set() }
const isEmpty = (k: Known) => k.long.size === 0 && k.three.size === 0 && k.two.size === 0

/** The forms a name is written in inside a log line: as typed, accented either way, dotted, underscored, dashed, plus-joined and URL encoded. */
function variantsOf(name: string): string[] {
    const forms = new Set<string>()
    for (const base of new Set([name, name.normalize('NFC'), name.normalize('NFD')])) {
        forms.add(base)
        for (const joint of ['.', '_', '-', '+']) forms.add(base.replace(/\s+/gu, joint))
        try {
            const encoded = encodeURIComponent(base)
            forms.add(encoded)
            forms.add(encoded.replace(/%20/gu, '+'))
        } catch {
            // A lone surrogate does not encode: the other forms stand.
        }
    }
    return [...forms].map((f) => f.toLowerCase()).filter((f) => f.length >= MIN_NAME)
}

function addLong(known: Known, v: string) {
    const key = v.slice(0, 4)
    const bucket = known.long.get(key)
    if (bucket) bucket.push(v)
    else known.long.set(key, [v])
}

/** The names in [list] as something [scrubLine] takes out: whole names of [MIN_NAME] characters or more. Anything else is skipped. */
export function knownPatterns(list: Iterable<unknown>): Known {
    const known: Known = { long: new Map(), three: new Set(), two: new Set() }
    for (const n of list) {
        if (typeof n !== 'string') continue
        const t = n.trim().slice(0, 300)
        if (t.length < MIN_NAME) continue
        for (const v of variantsOf(t)) {
            // A name is short by what the viewer sees (its length), whatever form it is looked up in: short ones count as whole words only.
            if (t.length < MIN_KNOWN) {
                if (v.length === 2) known.two.add(v)
                else if (v.length === 3) known.three.add(v)
                else addLong(known, v)
            } else addLong(known, v)
        }
    }
    return known
}

type Span = [number, number]

function mergeSpans(spans: Span[]): Span[] {
    spans.sort((a, b) => a[0] - b[0] || b[1] - a[1])
    const out: Span[] = []
    for (const s of spans) {
        const last = out[out.length - 1]
        if (last && s[0] <= last[1]) last[1] = Math.max(last[1], s[1])
        else out.push([s[0], s[1]])
    }
    return out
}

function replaceSpans(s: string, spans: Span[], placeholder: string): string {
    if (spans.length === 0) return s
    let out = ''
    let at = 0
    for (const [a, b] of mergeSpans(spans)) {
        out += s.slice(at, a) + placeholder
        at = b
    }
    return out + s.slice(at)
}

/** Takes the known names out: every form of each, case ignored, the short ones only as whole words. One look-up per character. */
function takeOutNames(s: string, known: Known): string {
    if (isEmpty(known)) return s
    const lower = s.toLowerCase()
    // A letter whose lower case is longer (a dotted capital I) moves every later offset. Then the line is folded one
    // character at a time and each folded position keeps the original span it came from, so a name is still found
    // whatever its case and the whole original characters it touched go.
    const fold = lower.length === s.length ? null : foldWithMap(s)
    const hay = fold ? fold.hay : lower
    const n = hay.length
    const spans: Span[] = []
    const add = (from: number, to: number) => spans.push(fold ? [fold.from[from], fold.to[to - 1]] : [from, to])
    const whole = (i: number, len: number) => !(i > 0 && WORD_CHAR.test(hay[i - 1])) && !(i + len < n && WORD_CHAR.test(hay[i + len]))
    for (let i = 0; i < n; i++) {
        if (known.long.size) {
            const candidates = known.long.get(hay.substr(i, 4))
            if (candidates) for (const v of candidates) if (hay.startsWith(v, i)) add(i, i + v.length)
        }
        if (known.three.size && known.three.has(hay.substr(i, 3)) && whole(i, 3)) add(i, i + 3)
        if (known.two.size && known.two.has(hay.substr(i, 2)) && whole(i, 2)) add(i, i + 2)
    }
    return replaceSpans(s, spans, '[name]')
}

/** `s` lower-cased one code point at a time: the folded text, and for each folded unit where its original character starts and ends. */
function foldWithMap(s: string): { hay: string; from: number[]; to: number[] } {
    let hay = ''
    const from: number[] = []
    const to: number[] = []
    for (let i = 0; i < s.length; ) {
        const cp = s.codePointAt(i) as number
        const ch = String.fromCodePoint(cp)
        const lc = ch.toLowerCase()
        for (let k = 0; k < lc.length; k++) {
            from.push(i)
            to.push(i + ch.length)
        }
        hay += lc
        i += ch.length
    }
    return { hay, from, to }
}

/**
 * Takes out file names, spaces and all: from a media, subtitle or document extension back to where
 * a name cannot start (a quote, a bracket, `: `, the line's start) or [FILE_REACH] characters. A path
 * goes whole, but the words before it stay. Linear: one pass for the extensions, a bounded walk back from each.
 */
function takeOutFiles(s: string): string {
    const spans: Span[] = []
    let reach = 0
    EXTENSION.lastIndex = 0
    for (let m = EXTENSION.exec(s); m; m = EXTENSION.exec(s)) {
        const end = m.index + m[0].length
        let start = m.index
        const floor = Math.max(reach, m.index - FILE_REACH)
        while (start > floor && !NAME_STOPS.has(s[start - 1]) && !(s[start - 1] === ' ' && s[start - 2] === ':')) start--
        // A path starts where its first separator's word starts, so "read /a/b/Show.mkv" keeps "read".
        let sep = -1
        for (let i = start; i < m.index; i++) {
            if (s[i] === '/' || s[i] === '\\') {
                sep = i
                break
            }
        }
        if (sep >= 0) {
            const space = s.lastIndexOf(' ', sep)
            if (space >= start) start = space + 1
        }
        while (start < m.index && s[start] === ' ') start++
        spans.push([start, end])
        reach = end
    }
    return replaceSpans(s, spans, '[file]')
}

/** Takes out e-mail addresses, letters of any alphabet included: from each @ out over what an address is written in. */
function takeOutEmails(s: string): string {
    if (!s.includes('@')) return s
    const spans: Span[] = []
    for (let at = s.indexOf('@'); at >= 0; at = s.indexOf('@', at + 1)) {
        let a = at
        while (a > 0 && EMAIL_CHAR.test(s[a - 1])) a--
        let b = at + 1
        let dot = false
        while (b < s.length && (DOMAIN_CHAR.test(s[b]) || (s[b] === '.' && b + 1 < s.length && DOMAIN_CHAR.test(s[b + 1])))) {
            if (s[b] === '.') dot = true
            b++
        }
        if (a < at && b > at + 1 && dot) spans.push([a, b])
    }
    return replaceSpans(s, spans, '[email]')
}

// The subtitles addon the viewer installed (player/submaker.ts): its address is a secret, so the address, the
// token in its path and any address of the shapes an addon answers at never reach a log line, in any form.
let addonSecrets: string[] = []

/**
 * Tells the scrubber the addon's base address (or null when there is none now), so that it, and the long
 * segments of its path, are taken out of every line wherever they show up. Bounded to a handful of strings.
 */
export function hideAddon(base: string | null): void {
    const found = new Set<string>()
    if (typeof base === 'string' && base.length >= 8 && base.length <= 2000) {
        found.add(base)
        try {
            const u = new URL(base)
            for (const seg of u.pathname.split('/')) {
                if (seg.length < 6) continue
                found.add(seg)
                try {
                    found.add(decodeURIComponent(seg))
                } catch {}
            }
        } catch {}
    }
    for (const f of [...found]) {
        try {
            found.add(encodeURIComponent(f))
        } catch {}
    }
    addonSecrets = [...found].filter((f) => f.length >= 6).sort((a, b) => b.length - a.length).slice(0, 12)
    version++
}

function takeOutAddon(line: string): string {
    let out = line
    for (const secret of addonSecrets) if (out.includes(secret)) out = out.split(secret).join('[addon]')
    return out.replace(STREMIO, '[addon]').replace(ADDON, '[addon]')
}

/**
 * Takes what is personal out of one log line, keeping the tag, the words and the short numbers a bug
 * needs. [known] are the names the page has seen. In this order: names, a URL's query and fragment,
 * e-mail addresses, file names, tokens, long numbers, phone numbers, @usernames.
 */
export function scrubLine(line: unknown, known: Known = NOBODY): string {
    let out = typeof line === 'string' ? line : String(line ?? '')
    // The addon's address goes first, as core does: cut before it, a secret that straddles the cut
    // would be left half in the line.
    out = takeOutAddon(out)
    if (out.length > MAX_LINE) out = `${out.slice(0, MAX_LINE)} [cut]`
    out = out.replace(CONTROL, ' ')
    out = takeOutNames(out, known)
    out = out.replace(URL_TAIL, '$1[query]').replace(QUERY, '?[query]')
    out = takeOutEmails(out)
    out = takeOutFiles(out)
    out = out.replace(TOKEN, '[token]')
    // Seven digits or more with a plus, a bracket, a space or a dash among them is a phone number; a bare run is an id.
    out = out.replace(PHONE, (m) => (/^\d+$/u.test(m) ? '[number]' : '[phone]'))
    out = out.replace(DIGITS, '[number]')
    out = out.replace(USERNAME, '$1@[user]')
    return out
}

// ---- The ring ------------------------------------------------------------------------------------------

let ring: string[] = []
/** Bumped with every line and every name, so what was scrubbed for one version is not scrubbed again for the same. */
let version = 0
const names = new Set<string>()

const stamp = (at = new Date()) => at.toISOString().slice(11, 19)

/** Adds one line to the ring (`tag` is a fixed word such as `player`; `text` is cut to [MAX_LINE]). Never throws. */
export function log(level: 'I' | 'W' | 'E', tag: string, text: unknown): void {
    try {
        const body = typeof text === 'string' ? text : text instanceof Error ? `${text.name}: ${text.message}` : String(text ?? '')
        ring.push(`${stamp()} ${level} ${String(tag).slice(0, 24)}: ${body.slice(0, MAX_LINE)}`)
        if (ring.length > MAX_LINES) ring = ring.slice(ring.length - MAX_LINES)
        version++
    } catch {
        // A log line is never worth a failure.
    }
}

/** The ring's lines, oldest first, not scrubbed yet. */
export const ringLines = (): string[] => ring.slice()

/** Names the page has seen (a chat's or a video's title, the viewer's own) so the export can take them out. Bounded; the newest win. */
export function rememberNames(list: Iterable<unknown>): void {
    let added = false
    for (const n of list) {
        if (typeof n !== 'string') continue
        const t = n.trim().slice(0, 300)
        if (t.length < MIN_NAME) continue
        if (names.has(t)) names.delete(t)
        else added = true
        names.add(t)
    }
    if (names.size > MAX_KNOWN) {
        for (const old of [...names].slice(0, names.size - MAX_KNOWN)) names.delete(old)
        added = true
    }
    if (added) version++
}

const TITLE_KEYS = new Set(['title', 'name', 'chat', 'chatTitle', 'fileName', 'caption', 'show', 'topic', 'firstName', 'lastName', 'username'])
/** The most values one answer is read for names: a screen's page of videos, not the account. */
const NOTE_NODES = 3000

/**
 * Remembers the names in an answer the page got from Telegram (the one place every screen's chats, videos,
 * topics and the viewer's own name come through): the values of the title-like keys, a few levels down.
 * Bounded and never throws, so a huge or odd answer costs a little and no more.
 */
export function noteNames(value: unknown): void {
    try {
        const found: string[] = []
        let seen = 0
        const walk = (v: unknown, depth: number) => {
            if (seen++ > NOTE_NODES || typeof v !== 'object' || v === null) return
            if (Array.isArray(v)) {
                for (const item of v) walk(item, depth)
                return
            }
            // Only plain data: a file's bytes (a typed array of half a megabyte), a Blob or a Map has no names in it to read.
            const proto = Object.getPrototypeOf(v)
            if (depth > 4 || (proto !== Object.prototype && proto !== null)) return
            for (const key of Object.keys(v)) {
                const x = (v as Record<string, unknown>)[key]
                if (typeof x === 'string') {
                    if (TITLE_KEYS.has(key)) found.push(x)
                } else walk(x, depth + 1)
            }
        }
        walk(value, 0)
        if (found.length) rememberNames(found)
    } catch {
        // A name missed is a name the patterns still catch by shape.
    }
}

const clearers: (() => void)[] = []
/** Somebody else's in-memory trace (the stutter marks) goes when the logs do. */
export function onClear(fn: () => void): void {
    clearers.push(fn)
}

/** Forgets the ring, the names and the earlier run's crash (sign out, Delete your data). */
export function clearLogs(): void {
    ring = []
    names.clear()
    version++
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
        .slice(0, MAX_CONSOLE)

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

/** The error classes a crash note may name: the language's and the browser's own, never a word the page or a viewer chose. */
const ERROR_CLASSES = new Set([
    'Error', 'TypeError', 'RangeError', 'ReferenceError', 'SyntaxError', 'EvalError', 'URIError', 'AggregateError', 'DOMException',
    'AbortError', 'NotAllowedError', 'NotSupportedError', 'NotFoundError', 'NetworkError', 'SecurityError', 'InvalidStateError',
    'QuotaExceededError', 'DataError', 'EncodingError', 'OperationError', 'TimeoutError', 'UnknownError', 'InvalidAccessError',
    'DataCloneError', 'ConstraintError', 'VersionError', 'TransactionInactiveError', 'ReadOnlyError', 'MediaError',
])
/** A code as a number (Telegram's) or as capital words (`FLOOD_WAIT`). */
const CODE = /^[A-Z][A-Z0-9_]{2,30}$/u

/** The class and code a crash note keeps: nothing the error's message said. Exported for the tests. */
export function crashFacts(reason: unknown): { kind: string; code: string } {
    const name = (reason as { name?: unknown } | null)?.name
    const code = (reason as { code?: unknown } | null)?.code
    return {
        kind: typeof name === 'string' && ERROR_CLASSES.has(name) ? name : 'Error',
        code: typeof code === 'number' && Number.isFinite(code) && Math.abs(code) < 100000 ? String(Math.trunc(code)) : typeof code === 'string' && CODE.test(code) ? code : '',
    }
}

const crashWords = (kind: string, code: string) => `${kind}${code ? `, code ${code}` : ''}`

/**
 * What the crash card keeps for the next run's bundle: the error's class and code in fixed words,
 * never its message (a message can hold a title this page cannot recognise), scrubbed anyway with
 * the names known now. Never throws.
 */
export function noteCrash(reason: unknown, at = new Date()): void {
    try {
        const { kind, code } = crashFacts(reason)
        log('E', 'crash', scrubLine(crashWords(kind, code), knownPatterns(names)))
        localStorage.setItem(CRASH_KEY, JSON.stringify({ at: at.toISOString().slice(0, 16).replace('T', ' '), kind, code }))
    } catch {
        // Storage refused: the ring still has it.
    }
}

/** The crash kept by an earlier run as plain lines, or none. Trusts nothing in the stored value, and scrubs it again with the names known now. */
export function earlierCrash(storage: Pick<Storage, 'getItem'> | null = safeStorage(), known: Known = knownPatterns(names)): string[] {
    try {
        const raw = storage?.getItem(CRASH_KEY)
        if (typeof raw !== 'string' || raw.length > 500) return []
        const o: unknown = JSON.parse(raw)
        if (typeof o !== 'object' || o === null || Array.isArray(o)) return []
        const { at, kind, code } = o as Record<string, unknown>
        if (typeof kind !== 'string') return []
        const facts = crashFacts({ name: kind, code: typeof code === 'string' && /^-?\d{1,5}$/u.test(code) ? Number(code) : code })
        const when = typeof at === 'string' && /^\d{4}-\d\d-\d\d \d\d:\d\d$/u.test(at) ? at : 'an earlier time'
        return [`The page stopped on ${when}: ${scrubLine(crashWords(facts.kind, facts.code), known)}`]
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

let knownCache: { version: number; known: Known } | null = null
/** The names remembered, as patterns; built once for a version of the ring and the names. */
function currentKnown(): Known {
    if (knownCache?.version !== version) knownCache = { version, known: knownPatterns(names) }
    return knownCache.known
}

let ringCache: { version: number; lines: string[] } | null = null
/** The ring scrubbed, kept for the version it was scrubbed at: opening a sheet again, or Copy after the size line, scans nothing. */
function scrubbedRing(known: Known): string[] {
    if (ringCache?.version !== version) ringCache = { version, lines: ring.map((l) => scrubLine(l, known)) }
    return ringCache.lines
}

/**
 * The one text file: a header, the earlier run's crash, the device's facts, the moments marked as
 * stuttering and the log, every line scrubbed the same way and the whole under [MAX_BYTES]; when it
 * is over, the oldest log lines go first (what is newest is what a report is about).
 */
export function buildBundle(facts: BundleFacts, parts: BundleParts = {}, extraNames: Iterable<unknown> = [], saved = new Date(), maxBytes = MAX_BYTES): Bundle {
    const extra = [...extraNames]
    const known = extra.length ? knownPatterns([...names, ...extra]) : currentKnown()
    const clean = (lines: readonly string[] | undefined) => (lines ?? []).filter((l) => typeof l === 'string').map((l) => scrubLine(l, known))
    // The earlier run's crash comes scrubbed already (its date would read as a phone number a second time).
    const crash = parts.crash ? clean(parts.crash) : earlierCrash(undefined, known)
    const device = clean(parts.device)
    const marks = clean(parts.marks)
    let lines = extra.length ? ring.map((l) => scrubLine(l, known)) : scrubbedRing(known)
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

/** The log inbox's report route for the address the build carries (the apps' LOG_UPLOAD_URL, or its `/v1/feedback` or `/v1/report` form), or null. */
export function reportUrl(address: string | null): string | null {
    const u = (address ?? '').trim()
    if (!u.startsWith('https://') || u.length <= 'https://'.length || /\s/u.test(u)) return null
    const clean = u.split('?')[0].split('#')[0].replace(/\/+$/u, '')
    const base = clean.endsWith('/v1/report') ? clean.slice(0, -'/v1/report'.length) : clean.endsWith('/v1/feedback') ? clean.slice(0, -'/v1/feedback'.length) : clean
    return `${base}/v1/report`
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
