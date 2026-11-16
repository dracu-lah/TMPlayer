// The sync file (core's SyncDocument.kt and SyncDocuments), in TypeScript: what resume across
// devices keeps in the viewer's own Saved Messages, read and written by the apps and the web the
// same way. Pure: no Telegram, no stores (local.ts reads and writes those; run.ts does the work).
//
// The format is the apps' (FORMAT "tmplayer-sync", versions 1 to 3, gzipped JSON, the same record
// fields, the same caps and removals): a file this module writes is read by the apps and the other
// way round (format.test.ts holds a file made by core's SyncDocuments to read, and the file this
// module writes for core's test to read). The merge is core's: the newest wins for each entry, a
// removal stands for what was taken off, an entry survives a removal of the same age or older, so
// it is commutative, idempotent and, below the caps, associative.
//
// What the web has no use for travels untouched: the named lists (`lists`, kept as the file has
// them) and the bookmarks are only ever merged and written back, never dropped, because a merge
// never removes an entry for being absent.
//
// Message ids: the apps (TDLib) write a server message's id shifted left by 20 bits; the web
// (MTProto) has the plain id. `tdlibMessageId` and `webMessageId` are the one place that is told.

export const FORMAT = 'tmplayer-sync'
/** Raised whenever a file this build writes could not be read by an older one (SyncDocuments.VERSION). */
export const VERSION = 3
export const LISTLESS_VERSION = 1
export const LISTS_VERSION = 2
export const MAX_RESUME = 200
export const MAX_WATCHED = 5_000
export const MAX_WATCH_LATER = 1_000
export const MAX_FAVOURITES = 1_000
export const MAX_REMOVALS = 5_000
export const MAX_BOOKMARKS = 2_000
export const MAX_CORRECTIONS = 1_000
export const TITLE_MAX = 60
/** How long a removal is remembered. */
export const REMOVAL_DAYS = 90
/** A time further ahead than this is a clock that runs fast: it counts as `now`. */
export const MAX_SKEW_MS = 24 * 60 * 60 * 1000
/** The compressed file is cut to fit this, oldest places first. */
export const MAX_BYTES = 1024 * 1024
/** A file larger than this, packed or unpacked, is not read at all. */
export const MAX_READ_BYTES = 2 * 1024 * 1024
export const MAX_TEXT_BYTES = 16 * 1024 * 1024
const DAY_MS = 24 * 60 * 60 * 1000
const SHIFT = 2 ** 20

/** A server message's id as TDLib (the apps) writes it, and back; null for an id that is not a server message's. */
export const tdlibMessageId = (webId: number): number => webId * SHIFT
export function webMessageId(tdlibId: number): number | null {
    if (!Number.isSafeInteger(tdlibId) || tdlibId <= 0 || tdlibId % SHIFT !== 0) return null
    return tdlibId / SHIFT
}

/** `<chat>_<message>`: how resume points, watched marks and Watch later lines are keyed (ProgressKeys). */
export const keyOf = (chat: number, msg: number) => `${chat}_${msg}`

export type SyncList = 'resume' | 'watched' | 'later' | 'favourite' | 'bookmark' | 'correction'
const LISTS: readonly SyncList[] = ['resume', 'watched', 'later', 'favourite', 'bookmark', 'correction']

export interface ResumeRec {
    chat: number
    msg: number
    file: number
    title: string
    chatTitle: string
    size: number
    durationSec: number
    positionMs: number
    durationMs: number
    updatedAt: number
    /** `audio,audioLang,subtitle,subtitleLang,speed`, as ResumeState.encode writes it; kept as it came. */
    state?: string
}

export interface WatchedRec {
    chat: number
    msg: number
    file: number
    title: string
    chatTitle: string
    size: number
    durationSec: number
    watchedAt: number
    manual: boolean
}

export interface LaterRec {
    chat: number
    msg: number
    file: number
    title: string
    fileName: string
    chatTitle: string
    size: number
    durationSec: number
    mime: string
    addedAt: number
    /** The show's grouping key for a show saved whole; empty for one video. */
    series: string
}

export interface BookmarkRec {
    chat: number
    msg: number
    id: number
    positionMs: number
    note: string
    at: number
}

export interface CorrectionRec {
    chat: number
    msg: number
    /** VideoCorrection.encode's lines: `separate`, `wrong`, `tmdb=film:123`, `title=...`. */
    text: string
    at: number
}

export interface Removal {
    list: SyncList
    key: string
    at: number
}

export interface SyncDoc {
    account: number
    updatedAt: number
    appVersion: string
    resume: ResumeRec[]
    watched: WatchedRec[]
    later: LaterRec[]
    /** A favourite chat's id to when it was starred. */
    favourites: Map<number, number>
    removed: Removal[]
    /** The named lists as the file holds them: not read, only kept and written back. */
    lists?: unknown
    bookmarks: BookmarkRec[]
    corrections: CorrectionRec[]
}

export const emptyDoc = (account = 0): SyncDoc => ({ account, updatedAt: 0, appVersion: '', resume: [], watched: [], later: [], favourites: new Map(), removed: [], bookmarks: [], corrections: [] })

export const resumeKey = (r: ResumeRec) => keyOf(r.chat, r.msg)
export const watchedKey = (w: WatchedRec) => keyOf(w.chat, w.msg)
/** A Watch later line's own name: the video's key, or the chat and the show's key. */
export const laterId = (l: LaterRec) => (l.series ? `${l.chat}_s:${l.series}` : keyOf(l.chat, l.msg))
export const bookmarkKey = (b: BookmarkRec) => `${b.chat}_${b.msg}_${b.id}`
export const correctionKey = (c: CorrectionRec) => keyOf(c.chat, c.msg)

// ---- reading ----------------------------------------------------------------------------------

type Json = Record<string, unknown>
const obj = (v: unknown): Json | null => (v && typeof v === 'object' && !Array.isArray(v) ? (v as Json) : null)
const objs = (v: unknown, cap: number): Json[] => (Array.isArray(v) ? v.slice(0, cap).map(obj).filter((o): o is Json => o !== null) : [])
/** A JSON number, as org.json's `opt(name) as? Number`: a string that looks like one is not. */
const num = (v: unknown): number | null => (typeof v === 'number' && Number.isFinite(v) ? v : null)
const int = (v: unknown): number | null => {
    const n = num(v)
    return n === null ? null : Math.trunc(n)
}
const str = (v: unknown, max = 2_000): string => (typeof v === 'string' ? v.slice(0, max) : '')

const cleanTitle = (t: string) => t.trim().slice(0, TITLE_MAX).trim()
const STATE = /^[^,]*,[^,]*,[^,]*,[^,]*,[^,]*$/

/** SyncDocuments.validKey: a name a removal may carry, so garbage cannot sit in the file for ever. */
export function validKey(list: SyncList, key: string): boolean {
    const whole = (s: string) => /^-?\d{1,18}$/.test(s)
    switch (list) {
        case 'favourite':
            return whole(key)
        case 'later':
            return key.length >= 3 && key.length <= 300 && key.includes('_')
        case 'bookmark': {
            const p = key.split('_')
            return p.length === 3 && p.every(whole)
        }
        default: {
            const p = key.split('_')
            return p.length === 2 && p.every(whole)
        }
    }
}

export function decodeJson(root: Json): { doc: SyncDoc } | { tooNew: number } | { notSync: true } {
    if (root.format !== FORMAT) return { notSync: true }
    const version = num(root.version)
    if (version === null || version < 1) return { notSync: true }
    if (version > VERSION) return { tooNew: Math.min(Math.trunc(version), 2 ** 31 - 1) }
    const doc = emptyDoc(int(root.account) ?? 0)
    doc.updatedAt = int(root.updatedAt) ?? 0
    doc.appVersion = str(root.app, 40)
    for (const j of objs(root.resume, MAX_RESUME * 2)) {
        const chat = int(j.chat)
        const msg = int(j.message)
        const file = int(j.file)
        const positionMs = int(j.positionMs)
        if (chat === null || msg === null || file === null || file <= 0 || positionMs === null) continue
        const title = cleanTitle(str(j.title, 400))
        if (!title) continue
        const state = str(j.state, 200)
        doc.resume.push({ chat, msg, file, title, chatTitle: '', size: int(j.size) ?? 0, durationSec: int(j.durationSec) ?? 0, positionMs, durationMs: int(j.durationMs) ?? 0, updatedAt: int(j.updatedAt) ?? 0, ...(STATE.test(state) ? { state } : {}) })
    }
    for (const j of objs(root.watched, MAX_WATCHED * 2)) {
        const chat = int(j.chat)
        const msg = int(j.message)
        const watchedAt = int(j.watchedAt)
        if (chat === null || msg === null || watchedAt === null) continue
        doc.watched.push({ chat, msg, file: 0, title: cleanTitle(str(j.title, 400)), chatTitle: '', size: int(j.size) ?? 0, durationSec: int(j.durationSec) ?? 0, watchedAt, manual: j.manual === true })
    }
    for (const j of objs(root.watchLater, MAX_WATCH_LATER * 2)) {
        const chat = int(j.chat)
        const msg = int(j.message)
        const addedAt = int(j.addedAt)
        if (chat === null || msg === null || addedAt === null) continue
        doc.later.push({ chat, msg, file: 0, title: cleanTitle(str(j.title, 400)), fileName: '', chatTitle: '', size: int(j.size) ?? 0, durationSec: int(j.durationSec) ?? 0, mime: str(j.mime, 100), addedAt, series: str(j.series, 400).trim().slice(0, TITLE_MAX * 2) })
    }
    const favourites = new Map<number, number>()
    for (const j of objs(root.favourites, MAX_FAVOURITES * 2)) {
        const chat = int(j.chat)
        if (chat === null) continue
        favourites.set(chat, Math.max(favourites.get(chat) ?? Number.MIN_SAFE_INTEGER, int(j.at) ?? 0))
    }
    doc.favourites = favourites
    for (const j of objs(root.removed, MAX_REMOVALS * 2)) {
        const list = LISTS.find((l) => l === j.list)
        const key = typeof j.key === 'string' ? j.key : null
        const at = int(j.at)
        if (list && key !== null && at !== null && validKey(list, key)) doc.removed.push({ list, key, at })
    }
    // Version 2's named lists: kept as they came (a plain object), never read here.
    if (obj(root.lists)) doc.lists = root.lists
    for (const j of objs(root.bookmarks, MAX_BOOKMARKS * 2)) {
        const chat = int(j.chat)
        const msg = int(j.msg)
        const id = int(j.id)
        const positionMs = int(j.pos)
        if (chat === null || msg === null || id === null || id <= 0 || positionMs === null || positionMs < 0) continue
        doc.bookmarks.push({ chat, msg, id, positionMs, note: str(j.note, 400).trim(), at: int(j.at) ?? id })
    }
    for (const j of objs(root.corrections, MAX_CORRECTIONS * 2)) {
        const chat = int(j.chat)
        const msg = int(j.msg)
        const text = str(j.fix, 1_000)
        if (chat === null || msg === null || !text.trim()) continue
        doc.corrections.push({ chat, msg, text, at: int(j.at) ?? 0 })
    }
    return { doc }
}

export type SyncRead = { kind: 'ok'; doc: SyncDoc } | { kind: 'tooNew'; version: number } | { kind: 'otherAccount'; account: number } | { kind: 'notSync' }

/** Reads a file's bytes, gzipped or plain. `account` is this session's; a file naming another account is refused. */
export async function decode(bytes: Uint8Array, account: number): Promise<SyncRead> {
    if (bytes.length > MAX_READ_BYTES) return { kind: 'notSync' }
    const unpacked = isGzip(bytes) ? await gunzip(bytes) : bytes
    if (!unpacked) return { kind: 'notSync' }
    let root: unknown
    try {
        root = JSON.parse(new TextDecoder().decode(unpacked))
    } catch {
        return { kind: 'notSync' }
    }
    const top = obj(root)
    if (!top) return { kind: 'notSync' }
    const read = decodeJson(top)
    if ('tooNew' in read) return { kind: 'tooNew', version: read.tooNew }
    if ('notSync' in read) return { kind: 'notSync' }
    const owner = read.doc.account
    if (owner !== 0 && account !== 0 && owner !== account) return { kind: 'otherAccount', account: owner }
    return { kind: 'ok', doc: read.doc }
}

const isGzip = (b: Uint8Array) => b.length >= 2 && b[0] === 0x1f && b[1] === 0x8b

async function pipe(bytes: Uint8Array, stream: CompressionStream | DecompressionStream, limit: number): Promise<Uint8Array | null> {
    const writer = stream.writable.getWriter()
    const writing = writer.write(bytes as unknown as BufferSource).then(() => writer.close())
    writing.catch(() => {})
    const reader = stream.readable.getReader()
    const parts: Uint8Array[] = []
    let total = 0
    try {
        for (;;) {
            const { done, value } = await reader.read()
            if (done) break
            total += value.length
            if (total > limit) {
                await reader.cancel().catch(() => {})
                return null
            }
            parts.push(value)
        }
        await writing
    } catch {
        return null
    }
    const out = new Uint8Array(total)
    let at = 0
    for (const p of parts) (out.set(p, at), (at += p.length))
    return out
}

export const gzip = async (bytes: Uint8Array): Promise<Uint8Array> => (await pipe(bytes, new CompressionStream('gzip'), Number.MAX_SAFE_INTEGER)) ?? bytes
const gunzip = (bytes: Uint8Array) => pipe(bytes, new DecompressionStream('gzip'), MAX_TEXT_BYTES)

// ---- merging ----------------------------------------------------------------------------------

/** Every time past `now` plus MAX_SKEW_MS read as `now` (SyncDocuments.clamped). */
export function clamped(doc: SyncDoc, now: number): SyncDoc {
    const limit = now + MAX_SKEW_MS
    const t = (at: number) => (at > limit ? now : at)
    return {
        ...doc,
        resume: doc.resume.map((r) => (r.updatedAt > limit ? { ...r, updatedAt: now } : r)),
        watched: doc.watched.map((w) => (w.watchedAt > limit ? { ...w, watchedAt: now } : w)),
        later: doc.later.map((l) => (l.addedAt > limit ? { ...l, addedAt: now } : l)),
        favourites: new Map([...doc.favourites].map(([k, v]) => [k, t(v)])),
        removed: doc.removed.map((r) => (r.at > limit ? { ...r, at: now } : r)),
        bookmarks: doc.bookmarks.map((b) => (b.at > limit ? { ...b, at: now } : b)),
        corrections: doc.corrections.map((c) => (c.at > limit ? { ...c, at: now } : c)),
    }
}

/** A stable text for breaking a tie, so the choice never depends on which file is read first. */
const tie = (v: unknown) => JSON.stringify(v, (_k, x) => (typeof x === 'object' && x && !Array.isArray(x) ? Object.fromEntries(Object.entries(x).sort(([a], [b]) => (a < b ? -1 : 1))) : x))

function newest<T>(all: T[], ...by: ((t: T) => number | string)[]): T {
    return all.reduce((a, b) => {
        for (const f of by) {
            const x = f(a)
            const y = f(b)
            if (x !== y) return x > y ? a : b
        }
        return tie(a) >= tie(b) ? a : b
    })
}

function groupBy<T>(items: T[], key: (t: T) => string): Map<string, T[]> {
    const out = new Map<string, T[]>()
    for (const i of items) {
        const k = key(i)
        const list = out.get(k)
        if (list) list.push(i)
        else out.set(k, [i])
    }
    return out
}

export interface Dropped {
    resume: number
    watched: number
    later: number
    removals: number
    bookmarks: number
    corrections: number
}
const noneDropped = (): Dropped => ({ resume: 0, watched: 0, later: 0, removals: 0, bookmarks: 0, corrections: 0 })
export const droppedTotal = (d: Dropped) => d.resume + d.watched + d.later + d.removals + d.bookmarks + d.corrections

/** [a] and [b] as one file, with how many entries the caps left out (SyncDocuments.mergeCounting). */
export function mergeCounting(left: SyncDoc, right: SyncDoc, now: number): { doc: SyncDoc; dropped: Dropped } {
    const a = clamped(left, now)
    const b = clamped(right, now)
    const oldest = now - REMOVAL_DAYS * DAY_MS
    const removalAt = new Map<string, number>()
    for (const r of [...a.removed, ...b.removed]) {
        if (r.at < oldest || !validKey(r.list, r.key)) continue
        const k = `${r.list}|${r.key}`
        removalAt.set(k, Math.max(removalAt.get(k) ?? Number.MIN_SAFE_INTEGER, r.at))
    }
    const removedAt = (list: SyncList, key: string) => removalAt.get(`${list}|${key}`) ?? Number.MIN_SAFE_INTEGER

    const resumeAll = [...groupBy([...a.resume, ...b.resume], resumeKey)]
        .flatMap(([key, all]) => {
            const kept = all.filter((r) => r.updatedAt >= removedAt('resume', key))
            return kept.length ? [newest(kept, (r) => r.updatedAt, (r) => r.positionMs)] : []
        })
        .sort((x, y) => y.updatedAt - x.updatedAt || (resumeKey(x) < resumeKey(y) ? -1 : 1))
    const resume = resumeAll.slice(0, MAX_RESUME)

    const watchedAll = [...groupBy([...a.watched, ...b.watched], watchedKey)]
        .flatMap(([key, all]) => {
            const kept = all.filter((w) => w.watchedAt >= removedAt('watched', key))
            return kept.length ? [newest(kept, (w) => w.watchedAt, (w) => (w.manual ? 1 : 0))] : []
        })
        .sort((x, y) => y.watchedAt - x.watchedAt || (watchedKey(x) < watchedKey(y) ? -1 : 1))
    const watched = watchedAll.slice(0, MAX_WATCHED)

    const laterAll = [...groupBy([...a.later, ...b.later], laterId)]
        .flatMap(([id, all]) => {
            const kept = all.filter((l) => l.addedAt >= removedAt('later', id))
            return kept.length ? [newest(kept, (l) => l.addedAt)] : []
        })
        .sort((x, y) => y.addedAt - x.addedAt || (laterId(x) < laterId(y) ? -1 : 1))
    const later = laterAll.slice(0, MAX_WATCH_LATER)

    const favourites = new Map<number, number>()
    const favAll = new Map<number, number>()
    for (const [id, at] of [...a.favourites, ...b.favourites]) favAll.set(id, Math.max(favAll.get(id) ?? Number.MIN_SAFE_INTEGER, at))
    ;[...favAll]
        .filter(([id, at]) => at >= removedAt('favourite', String(id)))
        .sort((x, y) => y[1] - x[1] || x[0] - y[0])
        .slice(0, MAX_FAVOURITES)
        .forEach(([id, at]) => favourites.set(id, at))

    // Of two copies of one bookmark the one changed last; of two with the same time, the longer note.
    const bookmarksAll = [...groupBy([...a.bookmarks, ...b.bookmarks], bookmarkKey)]
        .flatMap(([key, all]) => {
            const kept = all.filter((m) => m.at >= removedAt('bookmark', key))
            return kept.length ? [newest(kept, (m) => m.at, (m) => m.note.length)] : []
        })
        .sort((x, y) => y.at - x.at || (bookmarkKey(x) < bookmarkKey(y) ? -1 : 1))
    const bookmarks = bookmarksAll.slice(0, MAX_BOOKMARKS)

    const correctionsAll = [...groupBy([...a.corrections, ...b.corrections], correctionKey)]
        .flatMap(([key, all]) => {
            const kept = all.filter((c) => c.at >= removedAt('correction', key))
            return kept.length ? [newest(kept, (c) => c.at, (c) => c.text)] : []
        })
        .sort((x, y) => y.at - x.at || (correctionKey(x) < correctionKey(y) ? -1 : 1))
    const corrections = correctionsAll.slice(0, MAX_CORRECTIONS)

    // A removal with a newer entry kept over it has done its job; one whose entry a cap left out is kept.
    const live = new Map<string, number>()
    bookmarks.forEach((m) => live.set(`bookmark|${bookmarkKey(m)}`, m.at))
    corrections.forEach((c) => live.set(`correction|${correctionKey(c)}`, c.at))
    resume.forEach((r) => live.set(`resume|${resumeKey(r)}`, r.updatedAt))
    watched.forEach((w) => live.set(`watched|${watchedKey(w)}`, w.watchedAt))
    later.forEach((l) => live.set(`later|${laterId(l)}`, l.addedAt))
    favourites.forEach((at, id) => live.set(`favourite|${id}`, at))
    const removalsAll: Removal[] = [...removalAt]
        .filter(([id, at]) => (live.get(id) ?? Number.MIN_SAFE_INTEGER) < at)
        .map(([id, at]) => {
            const cut = id.indexOf('|')
            return { list: id.slice(0, cut) as SyncList, key: id.slice(cut + 1), at }
        })
        .sort((x, y) => y.at - x.at || LISTS.indexOf(x.list) - LISTS.indexOf(y.list) || (x.key < y.key ? -1 : 1))
    const removed = removalsAll.slice(0, MAX_REMOVALS)

    // Named lists are the apps' to merge (NamedListRules); here the two copies' are only joined, by id, the newest winning for each.
    const newerFile = a.updatedAt > b.updatedAt || (a.updatedAt === b.updatedAt && a.appVersion >= b.appVersion) ? a : b
    const lists = a.lists !== undefined && b.lists !== undefined ? unionLists(a.lists, b.lists) : (a.lists ?? b.lists)
    const doc: SyncDoc = {
        account: Math.max(a.account, b.account),
        updatedAt: newerFile.updatedAt,
        appVersion: newerFile.appVersion,
        resume,
        watched,
        later,
        favourites,
        removed,
        ...(lists !== undefined ? { lists } : {}),
        bookmarks,
        corrections,
    }
    return {
        doc,
        dropped: {
            resume: resumeAll.length - resume.length,
            watched: watchedAll.length - watched.length,
            later: laterAll.length - later.length,
            removals: removalsAll.length - removed.length,
            bookmarks: bookmarksAll.length - bookmarks.length,
            corrections: correctionsAll.length - corrections.length,
        },
    }
}

export const merge = (a: SyncDoc, b: SyncDoc, now: number): SyncDoc => mergeCounting(a, b, now).doc

/**
 * Two copies of the named lists (kept raw) joined: a list by its id, a line by its list and its own
 * name, a removal by its list and entry, each the newest by its time. Nothing is dropped for being
 * absent from one copy; the apps apply the removals when they merge (NamedListRules).
 */
export function unionLists(a: unknown, b: unknown): unknown {
    const pick = (x: unknown, name: string): Json[] => objs(obj(x)?.[name], 20_000)
    const join = (name: string, key: (o: Json) => string, at: (o: Json) => number) => {
        const out = new Map<string, Json>()
        for (const o of [...pick(a, name), ...pick(b, name)]) {
            const k = key(o)
            const prev = out.get(k)
            if (!prev || at(o) > at(prev) || (at(o) === at(prev) && tie(o) > tie(prev))) out.set(k, o)
        }
        return [...out.entries()].sort((x, y) => (x[0] < y[0] ? -1 : 1)).map(([, o]) => o)
    }
    const s = (v: unknown) => String(v ?? '')
    const n = (v: unknown) => num(v) ?? 0
    const entryName = (o: Json) => (s(o.series) ? `${s(o.chat)}_s:${s(o.series)}` : `${s(o.chat)}_${s(o.message)}`)
    return {
        lists: join('lists', (o) => s(o.id), (o) => n(o.at)),
        entries: join('entries', (o) => `${s(o.list)}|${entryName(o)}`, (o) => n(o.addedAt)),
        removed: join('removed', (o) => `${s(o.list)}|${s(o.entry)}`, (o) => n(o.at)),
    }
}

// ---- what a device changes to match a merge ---------------------------------------------------

export interface SyncApply {
    resumeIn: ResumeRec[]
    /** Places another device removed after this one last saved them: key to removal time. */
    resumeOut: Map<string, number>
    watchedIn: WatchedRec[]
    watchedOut: Map<string, number>
    laterIn: LaterRec[]
    laterOut: Map<string, number>
    favouritesIn: Set<number>
    favouritesOut: Set<number>
    bookmarksIn: BookmarkRec[]
    bookmarksOut: Map<string, number>
    correctionsIn: CorrectionRec[]
    correctionsOut: Map<string, number>
}

export const applyCount = (a: SyncApply) =>
    a.resumeIn.length + a.watchedIn.length + a.laterIn.length + a.favouritesIn.size + a.bookmarksIn.length + a.correctionsIn.length + a.resumeOut.size + a.watchedOut.size + a.laterOut.size + a.favouritesOut.size + a.bookmarksOut.size + a.correctionsOut.size

/**
 * What this device writes to its own lists to match `merged`, given its `local` file (SyncDocuments.apply).
 * Never removes an entry only because `merged` lacks it: a removal newer than this device's copy has to say so.
 */
export function apply(local: SyncDoc, merged: SyncDoc): SyncApply {
    const removalAt = new Map(merged.removed.map((r) => [`${r.list}|${r.key}`, r.at] as const))
    const removedAt = (list: SyncList, key: string) => removalAt.get(`${list}|${key}`)

    const localResume = new Map(local.resume.map((r) => [resumeKey(r), r]))
    const mergedResume = new Map(merged.resume.map((r) => [resumeKey(r), r]))
    const resumeIn = merged.resume.filter((m) => {
        const mine = localResume.get(resumeKey(m))
        return !mine || mine.updatedAt < m.updatedAt
    })
    const resumeOut = new Map<string, number>()
    for (const [key, mine] of localResume) {
        if (mergedResume.has(key)) continue
        const at = removedAt('resume', key)
        if (at !== undefined && at > mine.updatedAt) resumeOut.set(key, at)
    }

    const localWatched = new Map(local.watched.map((w) => [watchedKey(w), w]))
    const mergedWatched = new Map(merged.watched.map((w) => [watchedKey(w), w]))
    const watchedIn: WatchedRec[] = []
    for (const m of merged.watched) {
        const mine = localWatched.get(watchedKey(m))
        if (!mine) watchedIn.push({ ...m, file: 0 })
        else if (m.watchedAt > mine.watchedAt) watchedIn.push({ ...mine, watchedAt: m.watchedAt, manual: mine.manual || m.manual })
        else if (m.manual && !mine.manual) watchedIn.push({ ...mine, manual: true })
    }
    const watchedOut = new Map<string, number>()
    for (const [key, mine] of localWatched) {
        if (mergedWatched.has(key)) continue
        const at = removedAt('watched', key)
        if (at !== undefined && at > mine.watchedAt) watchedOut.set(key, at)
    }

    // The newest mark for a video once the merge is in, here or in the file.
    const watchedAt = (key: string): number | null => {
        if (watchedOut.has(key)) return null
        const times = [localWatched.get(key)?.watchedAt, mergedWatched.get(key)?.watchedAt].filter((t): t is number => t !== undefined)
        return times.length ? Math.max(...times) : null
    }
    const covered = (line: LaterRec) => !line.series && (watchedAt(keyOf(line.chat, line.msg)) ?? Number.MIN_SAFE_INTEGER) >= line.addedAt

    const localLater = new Map(local.later.map((l) => [laterId(l), l]))
    const mergedLater = new Map(merged.later.map((l) => [laterId(l), l]))
    const laterIn: LaterRec[] = []
    for (const m of merged.later) {
        if (covered(m)) continue
        const mine = localLater.get(laterId(m))
        if (!mine) laterIn.push({ ...m, file: 0 })
        else if (m.addedAt > mine.addedAt) laterIn.push({ ...mine, addedAt: m.addedAt })
    }
    const laterOut = new Map<string, number>()
    for (const [id, mine] of localLater) {
        const removed = removedAt('later', id)
        const gone = removed !== undefined && !mergedLater.has(id) && removed > mine.addedAt ? removed : null
        // Only a mark that came in from the file: one made here already took the line off.
        const mark = mergedWatched.get(keyOf(mine.chat, mine.msg))?.watchedAt
        const coveredBy = mark !== undefined && !mine.series && mark >= mine.addedAt && !watchedOut.has(keyOf(mine.chat, mine.msg)) ? mark : null
        const at = gone ?? coveredBy
        if (at !== null) laterOut.set(id, at)
    }

    const favouritesIn = new Set([...merged.favourites.keys()].filter((id) => !local.favourites.has(id)))
    const favouritesOut = new Set<number>()
    for (const [id, at] of local.favourites) {
        if (merged.favourites.has(id)) continue
        const removed = removedAt('favourite', String(id))
        if (removed !== undefined && removed > at) favouritesOut.add(id)
    }

    const localBookmarks = new Map(local.bookmarks.map((m) => [bookmarkKey(m), m]))
    const mergedBookmarks = new Map(merged.bookmarks.map((m) => [bookmarkKey(m), m]))
    const bookmarksIn = merged.bookmarks.filter((m) => {
        const mine = localBookmarks.get(bookmarkKey(m))
        return !mine || mine.at < m.at
    })
    const bookmarksOut = new Map<string, number>()
    for (const [key, mine] of localBookmarks) {
        if (mergedBookmarks.has(key)) continue
        const at = removedAt('bookmark', key)
        if (at !== undefined && at > mine.at) bookmarksOut.set(key, at)
    }

    const localCorrections = new Map(local.corrections.map((c) => [correctionKey(c), c]))
    const mergedCorrections = new Map(merged.corrections.map((c) => [correctionKey(c), c]))
    const correctionsIn = merged.corrections.filter((m) => {
        const mine = localCorrections.get(correctionKey(m))
        return !mine || mine.at < m.at
    })
    const correctionsOut = new Map<string, number>()
    for (const [key, mine] of localCorrections) {
        if (mergedCorrections.has(key)) continue
        const at = removedAt('correction', key)
        if (at !== undefined && at > mine.at) correctionsOut.set(key, at)
    }
    return { resumeIn, resumeOut, watchedIn, watchedOut, laterIn, laterOut, favouritesIn, favouritesOut, bookmarksIn, bookmarksOut, correctionsIn, correctionsOut }
}

// ---- writing ----------------------------------------------------------------------------------

/** The file's JSON as core writes it (SyncDocuments.json). */
export function toJson(doc: SyncDoc): Json {
    const extras = doc.bookmarks.length > 0 || doc.corrections.length > 0 || doc.removed.some((r) => r.list === 'bookmark' || r.list === 'correction')
    const hasLists = obj(doc.lists) !== null && Object.keys(doc.lists as Json).length > 0
    return {
        format: FORMAT,
        version: extras ? VERSION : hasLists ? LISTS_VERSION : LISTLESS_VERSION,
        account: doc.account,
        updatedAt: doc.updatedAt,
        app: doc.appVersion,
        resume: doc.resume.map((r) => ({ chat: r.chat, message: r.msg, file: r.file, title: r.title, chatTitle: r.chatTitle, size: r.size, durationSec: r.durationSec, positionMs: r.positionMs, durationMs: r.durationMs, updatedAt: r.updatedAt, ...(r.state ? { state: r.state } : {}) })),
        watched: doc.watched.map((w) => ({ chat: w.chat, message: w.msg, file: w.file, title: w.title, chatTitle: w.chatTitle, size: w.size, durationSec: w.durationSec, watchedAt: w.watchedAt, manual: w.manual })),
        watchLater: doc.later.map((l) => ({ chat: l.chat, message: l.msg, file: l.file, title: l.title, fileName: l.fileName, chatTitle: l.chatTitle, size: l.size, durationSec: l.durationSec, mime: l.mime, addedAt: l.addedAt, ...(l.series ? { series: l.series } : {}) })),
        favourites: [...doc.favourites].sort((x, y) => x[0] - y[0]).map(([chat, at]) => ({ chat, at })),
        removed: doc.removed.map((r) => ({ list: r.list, key: r.key, at: r.at })),
        ...(hasLists ? { lists: doc.lists } : {}),
        ...(doc.bookmarks.length ? { bookmarks: doc.bookmarks.map((m) => ({ chat: m.chat, msg: m.msg, id: m.id, pos: m.positionMs, note: m.note, at: m.at })) } : {}),
        ...(doc.corrections.length ? { corrections: doc.corrections.map((c) => ({ chat: c.chat, msg: c.msg, fix: c.text, at: c.at })) } : {}),
    }
}

const MAX_CUTS = 64

/** The first list that still has anything loses a quarter of its oldest (SyncDocuments.cut); null when all are empty. */
function cut(doc: SyncDoc): { doc: SyncDoc; dropped: Partial<Dropped> } | null {
    const quarter = (n: number) => Math.max(1, Math.floor(n / 4))
    if (doc.resume.length) {
        const n = quarter(doc.resume.length)
        return { doc: { ...doc, resume: [...doc.resume].sort((x, y) => y.updatedAt - x.updatedAt).slice(0, -n) }, dropped: { resume: n } }
    }
    if (doc.removed.length) {
        const n = quarter(doc.removed.length)
        return { doc: { ...doc, removed: [...doc.removed].sort((x, y) => y.at - x.at).slice(0, -n) }, dropped: { removals: n } }
    }
    if (doc.later.length) {
        const n = quarter(doc.later.length)
        return { doc: { ...doc, later: [...doc.later].sort((x, y) => y.addedAt - x.addedAt).slice(0, -n) }, dropped: { later: n } }
    }
    if (doc.watched.length) {
        const n = quarter(doc.watched.length)
        return { doc: { ...doc, watched: [...doc.watched].sort((x, y) => y.watchedAt - x.watchedAt).slice(0, -n) }, dropped: { watched: n } }
    }
    if (doc.corrections.length) {
        const n = quarter(doc.corrections.length)
        return { doc: { ...doc, corrections: [...doc.corrections].sort((x, y) => y.at - x.at).slice(0, -n) }, dropped: { corrections: n } }
    }
    if (doc.bookmarks.length) {
        const n = quarter(doc.bookmarks.length)
        return { doc: { ...doc, bookmarks: [...doc.bookmarks].sort((x, y) => y.at - x.at).slice(0, -n) }, dropped: { bookmarks: n } }
    }
    return null
}

export interface Encoded {
    bytes: Uint8Array
    doc: SyncDoc
    dropped: Dropped
}

/** The file's bytes: the JSON, gzipped; over `maxBytes` it is cut until it fits, the oldest first, and the cut is counted. */
export async function encode(doc: SyncDoc, maxBytes = MAX_BYTES): Promise<Encoded> {
    let current = doc
    const dropped = noneDropped()
    const pack = (d: SyncDoc) => gzip(new TextEncoder().encode(JSON.stringify(toJson(d))))
    for (let i = 0; i < MAX_CUTS; i++) {
        const bytes = await pack(current)
        if (bytes.length <= maxBytes) return { bytes, doc: current, dropped }
        const next = cut(current)
        if (!next) return { bytes, doc: current, dropped }
        current = next.doc
        for (const [k, v] of Object.entries(next.dropped)) dropped[k as keyof Dropped] += v ?? 0
    }
    return { bytes: await pack(current), doc: current, dropped }
}

/** The lists of two files alike, whoever wrote them and when. */
export function sameLists(a: SyncDoc, b: SyncDoc): boolean {
    const plain = (d: SyncDoc) => JSON.stringify({ ...toJson(d), account: 0, updatedAt: 0, app: '' })
    return plain(a) === plain(b)
}
