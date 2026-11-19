// "Report a problem" and "Request a feature" on the web (WB11, HX64). The web twin of core's
// Feedback, FeedbackReport and FeedbackSender, and of the inbox's feedback.ts (workers/log-inbox):
// the same fields, caps and wording of the wire (`{"v":1,"id","kind","message","contact","app":{...}}`),
// so what the page sends is exactly what the Worker accepts, and the same draft id means the same
// filed report however often it arrives (a double press, a retry, the tab killed part way).
//
// Two routes. With an inbox address built in (`__LOG_UPLOAD_URL__`, the same one the apps carry) the
// page writes the report itself: a form, a confirm step that lists word for word what goes, then the
// code. Without one, or when a send fails, GitHub's prefilled issue and an e-mail are the ways round.
// The page sends no logs and no account: only what the viewer typed, the optional way to reply, and
// four facts about the browser. Nothing here touches the DOM beyond `localStorage` (try/catch, so a
// browser that refuses it still sends); the screen is ui/FeedbackForm.tsx.

import { buildBundle, suppliedParts, type Bundle } from '../logs.ts'

export const MAX_MESSAGE = 4000
export const MAX_CONTACT = 120
/** The inbox refuses a body over this; a stored or received report text over it is not ours. */
export const MAX_JSON = 16 * 1024
/** How long typing rests before it is kept on the device. */
export const SAVE_DELAY_MS = 1500
export const ISSUES = 'https://github.com/dracu-lah/TMPlayer/issues/new'
export const EMAIL = 'hello@tmplayer.org'
/** How long the inbox keeps feedback (the Worker's KEEP_DAYS). */
export const KEEP_DAYS = 30
/** A GitHub address longer than this is refused by some proxies; the typed words are cut to fit. */
export const MAX_URL = 7500
/** A mailto longer than this is refused by some mail programs. */
export const MAX_MAILTO = 1900

export type Kind = 'problem' | 'feature'
const KINDS: readonly Kind[] = ['problem', 'feature']
export const isKind = (v: unknown): v is Kind => typeof v === 'string' && (KINDS as readonly string[]).includes(v)

export interface Facts {
    version: string
    platform: string
    device: string
    language: string
}

// ---- Text ----------------------------------------------------------------------------------

// Control characters, but not the line break and the tab a message may hold; and the invisible marks that turn text around.
// eslint-disable-next-line no-control-regex
const CONTROL = /[\u0000-\u0008\u000b-\u001f\u007f-\u009f\u200b-\u200f\u2028-\u202e\u2066-\u2069\ufeff]/gu
const SPACES = /\s+/gu

/** The message as the inbox keeps it: line breaks kept (two blank lines at most), no control characters, trimmed. */
export function cleanMessage(text: string): string {
    return stripLone(text)
        .replace(/\r\n?/gu, '\n')
        .replace(CONTROL, '')
        .replace(/[ \t]+$/gmu, '')
        .replace(/\n{4,}/gu, '\n\n\n')
        .trim()
}

/** A one line field: no control characters, spaces tidied, trimmed. Not capped: see [problem]. */
export function cleanLine(text: string): string {
    return stripLone(cut(text, MAX_JSON)).replace(CONTROL, '').replace(SPACES, ' ').trim()
}

/**
 * [text] without a lone UTF-16 surrogate (half of an emoji, from a cut or a paste): encodeURIComponent
 * throws on one, and JSON would carry a character that is not text. Whole pairs stay.
 */
export function stripLone(text: string): string {
    let out = ''
    for (let i = 0; i < text.length; i++) {
        const c = text.charCodeAt(i)
        if (c >= 0xd800 && c <= 0xdbff) {
            const d = text.charCodeAt(i + 1)
            if (d >= 0xdc00 && d <= 0xdfff) {
                out += text[i] + text[i + 1]
                i++
            }
        } else if (c < 0xdc00 || c > 0xdfff) {
            out += text[i]
        }
    }
    return out
}

/** The first [max] UTF-16 units of [text], never through a surrogate pair (the half pair left at the end is dropped). */
export function cut(text: string, max: number): string {
    if (text.length <= max) return text
    let end = max
    const last = text.charCodeAt(end - 1)
    if (last >= 0xd800 && last <= 0xdbff) end--
    return text.slice(0, end)
}

export type Problem = 'empty' | 'too_long' | 'contact_too_long'

/** What stops [message] and [contact] from being sent, or null when they can go. */
export function problem(message: string, contact: string): Problem | null {
    const m = cleanMessage(message)
    if (m === '') return 'empty'
    if (m.length > MAX_MESSAGE) return 'too_long'
    if (cleanLine(contact).length > MAX_CONTACT) return 'contact_too_long'
    return null
}

/** The figure the counter shows: the cleaned length, as the inbox counts it. */
export const messageLength = (message: string) => cleanMessage(message).length

const ID = /^[0-9a-f]{32}$/u

/** A fresh draft id: 32 hex digits, random, nothing about the device in it. */
export function newId(random: (n: number) => Uint8Array = defaultRandom): string {
    return [...random(16)].map((b) => b.toString(16).padStart(2, '0')).join('')
}

function defaultRandom(n: number): Uint8Array {
    const out = new Uint8Array(n)
    try {
        crypto.getRandomValues(out)
    } catch {
        // No crypto (a very old engine): still unique enough for a dedupe key, never a secret.
        for (let i = 0; i < n; i++) out[i] = Math.floor(Math.random() * 256)
    }
    return out
}

// ---- The report ----------------------------------------------------------------------------

export interface Report {
    id: string
    kind: Kind
    message: string
    contact: string | null
    facts: Facts
    /** The code of a log report the viewer sent to the inbox just before (`TMP-7K3Q`), or null. Nothing sets it until the web uploads logs (WB28). */
    logs: string | null
}

/** The inbox's log report code (workers/log-inbox LOG_CODE); anything else is dropped, as the Worker drops it. */
const LOG_CODE = /^TMP-[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{4}$/u

/**
 * The report, or null when [problem] says it cannot go or [id] is not one. Nothing typed is ever
 * cut: too long is null, never half.
 */
export function makeReport(id: string, kind: Kind, message: string, contact: string | null, facts: Facts, logs: string | null = null): Report | null {
    if (!ID.test(id)) return null
    if (problem(message, contact ?? '') !== null) return null
    return {
        id,
        kind,
        message: cleanMessage(message),
        contact: cleanLine(contact ?? '') || null,
        logs: typeof logs === 'string' && LOG_CODE.test(logs) ? logs : null,
        facts: {
            version: cut(cleanLine(facts.version), 40),
            platform: cut(cleanLine(facts.platform), 60),
            device: cut(cleanLine(facts.device), 160),
            language: cut(cleanLine(facts.language), 20),
        },
    }
}

/** The one JSON object sent, the whole of it (FeedbackReport.toJson). */
export function toJson(r: Report): string {
    const o: Record<string, unknown> = { v: 1, id: r.id, kind: r.kind, message: r.message }
    if (r.contact !== null) o.contact = r.contact
    if (r.logs !== null) o.logs = r.logs
    o.app = { version: r.facts.version, platform: r.facts.platform || 'other', device: r.facts.device, language: r.facts.language }
    return JSON.stringify(o)
}

/** The report [text] holds (as [toJson] writes it), or null when it is not one. Trusts nothing in it. */
export function parseReport(text: unknown): Report | null {
    if (typeof text !== 'string' || text.length > MAX_JSON) return null
    let json: unknown
    try {
        json = JSON.parse(text)
    } catch {
        return null
    }
    if (typeof json !== 'object' || json === null || Array.isArray(json)) return null
    const o = json as Record<string, unknown>
    if (o.v !== 1 || typeof o.id !== 'string' || !isKind(o.kind) || typeof o.message !== 'string') return null
    const app = typeof o.app === 'object' && o.app !== null && !Array.isArray(o.app) ? (o.app as Record<string, unknown>) : {}
    const fact = (key: string) => (typeof app[key] === 'string' ? (app[key] as string) : '')
    return makeReport(o.id, o.kind, o.message, typeof o.contact === 'string' ? o.contact : null, {
        version: fact('version'),
        platform: fact('platform'),
        device: fact('device'),
        language: fact('language'),
    }, typeof o.logs === 'string' ? o.logs : null)
}

/** The lines the confirm step shows, word for word what [toJson] holds, in the order the apps show them. */
export interface ReviewLines {
    kind: Kind
    message: string
    /** Null when the viewer gave no way to reply. */
    contact: string | null
    /** "Web, Safari 17 on iPhone, TMPlayer web, en" (the apps' aboutLine). */
    about: string
}

export function aboutLine(f: Facts): string {
    return [f.platform, f.device, f.version.trim() ? `TMPlayer ${f.version}` : '', f.language].filter((s) => s.trim() !== '').join(', ')
}

export function reviewLines(r: Report): ReviewLines {
    return { kind: r.kind, message: r.message, contact: r.contact, about: aboutLine(r.facts) }
}

// ---- What the browser says about itself -------------------------------------------------------

/**
 * "Safari 17 on iPhone": the browser's family and major version, and the system's name, from the
 * user agent. Nothing else of the agent goes (no build numbers, no model codes): it is a hint for
 * the developer, which the viewer sees in the confirm step.
 */
export function browserLine(ua: string, installed = false): string {
    const s = typeof ua === 'string' ? ua.slice(0, 500) : ''
    const major = (re: RegExp) => re.exec(s)?.[1] ?? ''
    let browser: string
    if (/Edg(?:e|A|iOS)?\//u.test(s)) browser = `Edge ${major(/Edg(?:e|A|iOS)?\/(\d+)/u)}`
    else if (/OPR\//u.test(s)) browser = `Opera ${major(/OPR\/(\d+)/u)}`
    else if (/SamsungBrowser\//u.test(s)) browser = `Samsung Internet ${major(/SamsungBrowser\/(\d+)/u)}`
    else if (/Firefox\/|FxiOS\//u.test(s)) browser = `Firefox ${major(/(?:Firefox|FxiOS)\/(\d+)/u)}`
    else if (/Chrome\/|CriOS\//u.test(s)) browser = `Chrome ${major(/(?:Chrome|CriOS)\/(\d+)/u)}`
    else if (/Safari\//u.test(s)) browser = `Safari ${major(/Version\/(\d+)/u)}`
    else browser = 'a browser'
    let system: string
    if (/iPhone/u.test(s)) system = 'iPhone'
    else if (/iPad/u.test(s)) system = 'iPad'
    else if (/Web0S|webOS/u.test(s)) system = 'webOS TV'
    else if (/Tizen/u.test(s)) system = 'Tizen TV'
    else if (/Android/u.test(s)) system = /Android (\d+)/u.test(s) ? `Android ${major(/Android (\d+)/u)}` : 'Android'
    else if (/CrOS/u.test(s)) system = 'ChromeOS'
    else if (/Windows/u.test(s)) system = 'Windows'
    else if (/Macintosh|Mac OS X/u.test(s)) system = 'macOS'
    else if (/Linux|X11/u.test(s)) system = 'Linux'
    else system = ''
    const line = system ? `${browser.trim()} on ${system}` : browser.trim()
    return installed ? `${line}, installed app` : line
}

export function webFacts(ua: string, language: string, installed = false): Facts {
    return { version: 'web', platform: 'Web', device: browserLine(ua, installed), language: cut(cleanLine(language), 20) || 'en' }
}

// ---- GitHub and e-mail, the ways round ----------------------------------------------------------

const encode = (v: string) => encodeURIComponent(stripLone(v))

/** The longest prefix of [text] (whole characters, never half a surrogate pair) whose address stays within [room] characters when encoded. */
function fit(text: string, room: number): string {
    try {
        return fitUnsafe(text, room)
    } catch {
        // Nothing here may throw inside a reactive address: no typed words rather than a broken screen.
        return ''
    }
}

function fitUnsafe(text: string, room: number): string {
    if (room <= 0) return ''
    if (encode(text).length <= room) return text
    const chars = Array.from(text)
    let lo = 0
    let hi = chars.length
    while (lo < hi) {
        const mid = Math.ceil((lo + hi) / 2)
        if (encode(chars.slice(0, mid).join('')).length <= room) lo = mid
        else hi = mid - 1
    }
    return chars.slice(0, lo).join('')
}

function params(pairs: [string, string][]): string {
    return pairs.map(([k, v]) => `${k}=${encode(v)}`).join('&')
}

/**
 * The new bug report page with the template's fields filled in (`bug_report.yml`: version, device,
 * and what happened when the viewer already wrote it). The platform dropdown has no web option, so
 * it is left to the reporter. Kept under [MAX_URL]: long words are cut at the end.
 */
export function issueUrl(facts: Facts, message = ''): string {
    const head: [string, string][] = [
        ['template', 'bug_report.yml'],
        ['version', facts.version],
        ['device', deviceLine(facts)],
    ]
    return withBody(`${ISSUES}?`, head, 'what', message)
}

/** The new feature request page; the typed words go in "What would you like to do?". */
export function featureUrl(message = ''): string {
    return withBody(`${ISSUES}?`, [['template', 'feature_request.yml']], 'problem', message)
}

function withBody(start: string, head: [string, string][], field: string, message: string): string {
    const base = start + params(head)
    const words = cleanMessage(message)
    if (words === '') return base
    const room = MAX_URL - base.length - field.length - 2
    const kept = fit(words, room)
    return kept === '' ? base : `${base}&${field}=${encode(kept)}`
}

/** The "Device or computer" field: the browser, then the language when it is not English. */
export function deviceLine(f: Facts): string {
    return !f.language.trim() || f.language.toLowerCase().startsWith('en') ? f.device : `${f.device}, app language ${f.language}`
}

/** The same facts as an e-mail, for somebody without a GitHub account; the typed words follow when there are some. */
export function mailto(facts: Facts, kind: Kind, message = ''): string {
    const subject = kind === 'feature' ? `TMPlayer ${facts.version}: a feature idea` : `TMPlayer ${facts.version}: a problem`
    const facing = [
        `Platform: ${facts.platform || 'other'}`,
        `App version: ${facts.version}`,
        `Device: ${facts.device}`,
        `App language: ${facts.language}`,
        '',
        kind === 'feature' ? 'What I would like TMPlayer to do:' : 'What happened:',
        '',
        '',
    ].join('\n')
    const base = `mailto:${EMAIL}?subject=${encode(subject)}&body=`
    const words = cleanMessage(message)
    const room = MAX_MAILTO - base.length - encode(facing).length
    const kept = words === '' ? '' : fit(words, room)
    return base + encode(kept === '' ? facing : `${facing}${kept}`)
}

/**
 * The page's own log for a report (WB18): scrubbed, with the moments the viewer marked as stuttering and
 * what stopped the page last time, or null when there is nothing to say. Built only when Copy details is
 * pressed (or its size is shown); nothing is sent from here.
 */
export function logsFor(facts: Facts): Bundle | null {
    try {
        const b = buildBundle(facts, suppliedParts())
        return b.parts.length > 0 ? b : null
    } catch {
        return null
    }
}

/**
 * What Copy details puts on the clipboard for a viewer with neither GitHub nor mail: the facts, and
 * below them the scrubbed logs when [attach] and the page has any (names, file names, numbers and
 * addresses taken out). Nothing else.
 */
export function copyText(facts: Facts, attach = true): string {
    const base = [
        'TMPlayer web',
        `Platform: ${facts.platform || 'other'}`,
        `App version: ${facts.version}`,
        `Device: ${facts.device}`,
        `App language: ${facts.language}`,
    ].join('\n')
    const logs = attach ? logsFor(facts) : null
    return logs ? `${base}\n\n${logs.text}` : base
}

// ---- The inbox -----------------------------------------------------------------------------

/**
 * The feedback address from the build's one inbox address (`https://host/v1/report` gives
 * `https://host/v1/feedback`), or null where there is none (a fork, CI, a build before the Worker is
 * deployed): the page then keeps only its GitHub and e-mail routes. Only a secure address counts.
 */
export function feedbackUrl(address: string | null | undefined): string | null {
    const u = (address ?? '').trim()
    if (!u.startsWith('https://') || u.length <= 'https://'.length || /\s/u.test(u)) return null
    const clean = u.split('?')[0].split('#')[0].replace(/\/+$/u, '')
    const base = clean.endsWith('/v1/report') ? clean.slice(0, -'/v1/report'.length) : clean.endsWith('/v1/feedback') ? clean.slice(0, -'/v1/feedback'.length) : clean
    return `${base}/v1/feedback`
}

/** The build's address: set by vite.config.ts from LOG_UPLOAD_URL, the same one the apps read. */
export const BUILT_IN_ADDRESS: string = typeof __LOG_UPLOAD_URL__ === 'string' ? __LOG_UPLOAD_URL__ : ''

/** The key that turns the form off on this browser, so Report a problem goes straight to GitHub (also what the tests use). */
export const OFF_KEY = 'tmplayer-feedback-off'

/** The address the form sends to now, or null: the GitHub and e-mail routes are then all there is. */
export function inboxUrl(address = BUILT_IN_ADDRESS, storage: Pick<Storage, 'getItem'> | null = safeStorage()): string | null {
    try {
        if (storage?.getItem(OFF_KEY) === '1') return null
    } catch {}
    return feedbackUrl(address)
}

export type Outcome =
    | { kind: 'sent'; code: string; duplicate: boolean }
    | { kind: 'slow'; seconds: number }
    | { kind: 'offline' }
    | { kind: 'failed'; status: number }

const CODE_FIELD = /"code"\s*:\s*"(FB-[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{4}-[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{4})"/u
const DUPLICATE = /"duplicate"\s*:\s*true/u
const MAX_REPLY_CHARS = 4096

/** How the inbox's answer is read: `{"code":"FB-7K3Q-M4XP","duplicate":false}`. Reads text from outside, so it never throws. */
export function outcome(status: number, body: string | null | undefined, retryAfter?: string | null): Outcome {
    if (status === 201 || status === 200) {
        const text = typeof body === 'string' ? body.slice(0, MAX_REPLY_CHARS) : ''
        const code = CODE_FIELD.exec(text)?.[1]
        if (code) return { kind: 'sent', code, duplicate: DUPLICATE.test(text) }
    }
    if (status === 429) {
        const n = Number.parseInt((retryAfter ?? '').trim(), 10)
        return { kind: 'slow', seconds: Number.isFinite(n) ? Math.min(3600, Math.max(1, n)) : 60 }
    }
    return { kind: 'failed', status: Number.isFinite(status) ? status : 0 }
}

/** A slow down, no connection, the inbox failing, or an answer with no status at all: kept for later. A refusal of the report itself never changes. */
export function worthRetrying(o: Outcome): boolean {
    return o.kind === 'slow' || o.kind === 'offline' || (o.kind === 'failed' && (o.status === 0 || (o.status >= 500 && o.status <= 599)))
}

export type Poster = (json: string) => Promise<Outcome>

/**
 * Sends one report text to [url]: plain text, no cookies, no referrer, no redirects followed, at
 * most [timeoutMs]. Never throws: no connection, a timeout, a blocked answer (CORS) and a cut
 * answer are all `offline`, which keeps the draft for later.
 */
export function poster(url: string, fetcher: typeof fetch = (...a) => fetch(...a), timeoutMs = 20_000, online: () => boolean = () => navigator.onLine !== false): Poster {
    return async (json) => {
        if (!online()) return { kind: 'offline' }
        const controller = new AbortController()
        const timer = setTimeout(() => controller.abort(), timeoutMs)
        try {
            const res = await fetcher(url, {
                method: 'POST',
                headers: { 'Content-Type': 'text/plain; charset=utf-8' },
                body: json,
                cache: 'no-store',
                credentials: 'omit',
                referrerPolicy: 'no-referrer',
                redirect: 'error',
                signal: controller.signal,
            })
            let body = ''
            try {
                body = (await res.text()).slice(0, MAX_REPLY_CHARS)
            } catch {}
            return outcome(res.status, body, res.headers.get('Retry-After'))
        } catch {
            return { kind: 'offline' }
        } finally {
            clearTimeout(timer)
        }
    }
}

// ---- The draft ---------------------------------------------------------------------------------

/**
 * A report being written, kept on the device so nothing typed is lost: closed half way, no
 * connection, the tab killed. [pending] is the exact JSON the viewer confirmed and pressed Send on,
 * waiting to go; null while it is still being written. Never holds logs, an account or a name: only
 * what the viewer typed.
 */
export interface Draft {
    kind: Kind
    message: string
    contact: string
    id: string
    pending: string | null
}

export const DRAFT_KEY = 'tmplayer-feedback'
/** A stored draft bigger than this is not ours. */
const MAX_STORED = 64 * 1024

export const blankDraft = (kind: Kind = 'problem', random?: (n: number) => Uint8Array): Draft => ({ kind, message: '', contact: '', id: newId(random), pending: null })

/** Nothing worth keeping. */
export const isBlank = (d: Draft) => d.message.trim() === '' && d.contact.trim() === '' && d.pending === null

export function encodeDraft(d: Draft): string {
    const o: Record<string, unknown> = { kind: d.kind, message: d.message, contact: d.contact, id: d.id }
    if (d.pending !== null) o.pending = d.pending
    return JSON.stringify(o)
}

/** The draft [text] holds, or null when there is none or it is not one. Fields that are wrong fall back. */
export function decodeDraft(text: unknown): Draft | null {
    if (typeof text !== 'string' || text.trim() === '' || text.length > MAX_STORED) return null
    let json: unknown
    try {
        json = JSON.parse(text)
    } catch {
        return null
    }
    if (typeof json !== 'object' || json === null || Array.isArray(json)) return null
    const o = json as Record<string, unknown>
    const id = typeof o.id === 'string' && ID.test(o.id) ? o.id : newId()
    const pending = typeof o.pending === 'string' && parseReport(o.pending)?.id === id ? o.pending : null
    return {
        kind: isKind(o.kind) ? o.kind : 'problem',
        message: typeof o.message === 'string' ? stripLone(cut(o.message, MAX_MESSAGE * 2)) : '',
        contact: typeof o.contact === 'string' ? stripLone(cut(o.contact, MAX_CONTACT * 2)) : '',
        id,
        pending,
    }
}

/** Where the one draft lives: `localStorage` on a page, a variable in a test. A refused store loses nothing already sent. */
export interface DraftStore {
    read(): Draft | null
    write(d: Draft | null): void
}

function safeStorage(): Storage | null {
    try {
        return typeof localStorage === 'undefined' ? null : localStorage
    } catch {
        return null
    }
}

export function localDraftStore(storage: Storage | null = safeStorage()): DraftStore {
    return {
        read() {
            try {
                return decodeDraft(storage?.getItem(DRAFT_KEY))
            } catch {
                return null
            }
        },
        write(d) {
            try {
                if (!storage) return
                if (d === null || isBlank(d)) storage.removeItem(DRAFT_KEY)
                else storage.setItem(DRAFT_KEY, encodeDraft(d))
            } catch {
                // A full or refused store: what is typed stays on screen, only the safety copy is missing.
            }
        },
    }
}

export function memoryDraftStore(start: Draft | null = null): DraftStore & { draft: Draft | null } {
    const store = {
        draft: start,
        read: () => store.draft,
        write: (d: Draft | null) => {
            store.draft = d === null || isBlank(d) ? null : d
        },
    }
    return store
}

export interface Timers {
    set(fn: () => void, ms: number): unknown
    clear(handle: unknown): void
}
const realTimers: Timers = { set: (fn, ms) => setTimeout(fn, ms), clear: (h) => clearTimeout(h as ReturnType<typeof setTimeout>) }

/**
 * Keeps typing on the device [SAVE_DELAY_MS] after the last change: one wait, replaced by each
 * change, nothing left running when idle. [now] writes at once (closing, hiding the tab).
 */
export function saver(store: DraftStore, delay = SAVE_DELAY_MS, timers: Timers = realTimers) {
    let handle: unknown = null
    let latest: Draft | null = null
    const write = () => {
        if (handle !== null) timers.clear(handle)
        handle = null
        if (latest !== null) store.write(latest)
        latest = null
    }
    return {
        change(d: Draft) {
            latest = d
            if (handle !== null) timers.clear(handle)
            handle = timers.set(write, delay)
        },
        now: write,
        cancel() {
            if (handle !== null) timers.clear(handle)
            handle = null
            latest = null
        },
        get waiting() {
            return handle !== null
        },
    }
}

// ---- Sending ---------------------------------------------------------------------------------

export type Step =
    | { step: 'sent'; code: string; duplicate: boolean }
    /** Kept on this device for later; [wait] above 0 when it was the inbox's limit. */
    | { step: 'queued'; draft: Draft; wait: number }
    /** The inbox will never take it ([status]); the draft stays to edit. */
    | { step: 'refused'; draft: Draft; status: number }
    | { step: 'editing'; problem: Problem }

const inflight = new Map<string, Promise<Step>>()

/** True while a send of this draft is going on (a second press does nothing). */
export const sending = (id: string) => inflight.has(id)

/**
 * Sends [draft] once. A second call for the same draft id while the first is going returns the
 * first's answer, so a double press (or Enter and a click) files one report. The report is kept on
 * the device as `pending` before it goes, so a tab closed during the send sends the same one under
 * the same id on the next try. Sent clears the draft; a slow down, no connection or an inbox failing
 * keeps it queued; any other refusal keeps the words for editing.
 */
export function send(draft: Draft, facts: Facts, post: Poster, store: DraftStore): Promise<Step> {
    const running = inflight.get(draft.id)
    if (running) return running
    const run = (async (): Promise<Step> => {
        const report = makeReport(draft.id, draft.kind, draft.message, draft.contact, facts)
        if (report === null) return { step: 'editing', problem: problem(draft.message, draft.contact) ?? 'empty' }
        const json = toJson(report)
        const kept: Draft = { ...draft, pending: json }
        store.write(kept)
        const out = await post(json)
        if (out.kind === 'sent') {
            store.write(null)
            return { step: 'sent', code: out.code, duplicate: out.duplicate }
        }
        if (worthRetrying(out)) return { step: 'queued', draft: kept, wait: out.kind === 'slow' ? out.seconds : 0 }
        const words: Draft = { ...draft, pending: null }
        store.write(words)
        return { step: 'refused', draft: words, status: out.kind === 'failed' ? out.status : 0 }
    })().finally(() => inflight.delete(draft.id))
    inflight.set(draft.id, run)
    return run
}

/** The draft after the viewer changed [change]: a changed draft that was tried before is a new one, with a new id. */
export function edited(was: Draft, next: Draft): Draft {
    const changed = next.kind !== was.kind || next.message !== was.message || next.contact !== was.contact
    return changed && was.pending !== null ? { ...next, id: newId(), pending: null } : next
}

let formOpen = false
/** The form sets this while it is on screen, so a background retry never races it. */
export const setFormOpen = (open: boolean) => {
    formOpen = open
}

/**
 * Tries a report that was confirmed and is waiting, once, with no window: the connection was down
 * when the viewer pressed Send. Never while the form is open; the same id means a report that
 * did reach the inbox is not filed twice.
 */
export async function flush(post: Poster, store: DraftStore): Promise<boolean> {
    if (formOpen) return false
    const draft = store.read()
    if (!draft?.pending || sending(draft.id)) return false
    const out = await post(draft.pending)
    if (out.kind === 'sent' && !formOpen) {
        // Only if the stored draft is still this one: typing since then is kept.
        if (store.read()?.id === draft.id) store.write(null)
        return true
    }
    return false
}

/** One retry, a minute after the page starts, and never on its own again (main.tsx). */
export const FLUSH_DELAY_MS = 60_000
export function flushSoon(): void {
    const url = inboxUrl()
    if (!url) return
    void flush(poster(url), localDraftStore()).catch(() => {})
}
