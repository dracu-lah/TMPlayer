// Bounds for the two Cache API stores that only grew: card thumbnails and TMDB answers and pictures.
// The Cache API keeps no order and no sizes, so a small index (idb-keyval, its own database) notes
// when each entry was last used and how big it is. Once a day, in idle time, one pass drops expired
// poster answers and the least recently used entries past the caps. The pass runs once and ends; any
// failure (private mode, quota, no Cache API) is swallowed, as the app works without these caches.
import { clear, createStore, delMany, entries, getMany, setMany, type UseStore } from 'idb-keyval'

export const THUMBS = 'tmplayer-thumbs'
export const POSTERS = 'tmplayer-posters'
export const THUMB_CAP = 3000
export const PICTURE_CAP = 1500

const DAY = 24 * 60 * 60 * 1000
/** Poster answers: MetaKeep's 60 days found, 7 days "nothing matched" (screens/posters.ts). */
const FOUND_TTL = 60 * DAY
const MISS_TTL = 7 * DAY
const TRIMMED = 'trimmed'

let db: UseStore | null = null
try {
    db = createStore('tmplayer-cache-index', 'kv')
} catch {
    db = null
}

/** [last used, bytes (-1 when unknown)] per `<cache>|<path and query>`. */
type Row = [number, number]
const pending = new Map<string, Row>()
let flushing: ReturnType<typeof setTimeout> | undefined

const idKey = (cache: string, url: string) => `${cache}|${url}`
const pathOf = (req: Request) => {
    const u = new URL(req.url)
    return u.pathname + u.search
}

/** Notes that `url` in `cache` was used (or stored, when `bytes` is known) just now. Written in batches. */
export function touch(cache: string, url: string, bytes?: number) {
    if (!db) return
    const key = idKey(cache, url)
    pending.set(key, [Date.now(), bytes ?? pending.get(key)?.[1] ?? -1])
    flushing ??= setTimeout(() => void flush(), 3000)
}

async function flush() {
    if (flushing) clearTimeout(flushing)
    flushing = undefined
    const rows = [...pending]
    pending.clear()
    if (!db || !rows.length) return
    try {
        // A touch without a size keeps the size already noted.
        const old = await getMany<Row>(rows.map(([k]) => k), db)
        await setMany(rows.map(([k, r], i) => [k, [r[0], r[1] >= 0 ? r[1] : (old[i]?.[1] ?? -1)] as Row]), db)
    } catch {}
}

/** Forgets every note (sign out). */
export async function clearIndex() {
    if (flushing) clearTimeout(flushing)
    flushing = undefined
    pending.clear()
    if (db) await clear(db).catch(() => {})
}

async function forgetCache(cache: string) {
    for (const k of [...pending.keys()]) if (k.startsWith(`${cache}|`)) pending.delete(k)
    if (!db) return
    try {
        const keys = (await entries<string, Row>(db)).map(([k]) => k).filter((k) => typeof k === 'string' && k.startsWith(`${cache}|`))
        await delMany(keys, db)
    } catch {}
}

/** Clears the thumbnail and poster caches only; the sign in, settings, places and watched stay. */
export async function clearCaches() {
    for (const n of [THUMBS, POSTERS]) {
        await globalThis.caches?.delete(n).catch(() => {})
        await forgetCache(n)
    }
}

/** What the thumbnails and the posters take, from the index (entries kept before it existed are counted by the next trim). */
export async function cacheSizes(): Promise<{ thumbs: number; posters: number }> {
    const out = { thumbs: 0, posters: 0 }
    if (!db) return out
    try {
        await flush()
        for (const [k, r] of await entries<string, Row>(db)) {
            if (typeof k !== 'string' || !Array.isArray(r)) continue
            const bytes = Math.max(0, Number(r[1]) || 0)
            if (k.startsWith(`${THUMBS}|`)) out.thumbs += bytes
            else if (k.startsWith(`${POSTERS}|`)) out.posters += bytes
        }
    } catch {}
    return out
}

const yieldNow = () => new Promise<void>((r) => setTimeout(r, 0))

/** Runs `work` when the browser is idle, or soon after where it has no idle callback. */
function idle(work: () => void) {
    const ric = (globalThis as { requestIdleCallback?: (cb: () => void, o?: { timeout: number }) => number }).requestIdleCallback
    if (ric) ric(work, { timeout: 60_000 })
    else setTimeout(work, 15_000)
}

let scheduled = false

/** Looks at the caches once a day, in idle time. Only the first call per page does anything. */
export function scheduleTrim() {
    if (scheduled || !db || !globalThis.caches) return
    scheduled = true
    // Past the first screens, so the trim never competes with the start.
    setTimeout(() => idle(() => void trim().catch(() => {})), 20_000)
}

async function trim() {
    if (!db) return
    const last = await getMany<number>([TRIMMED], db).then((r) => r[0]).catch(() => undefined)
    const now = Date.now()
    // A saved time in the future (the clock moved back) counts as due.
    if (typeof last === 'number' && last <= now && now - last < DAY) return
    await flush()
    await trimThumbs()
    await trimPosters()
    await flush()
    await setMany([[TRIMMED, now]], db).catch(() => {})
}

interface Item {
    req: Request
    time: number
}

/** Every request in `cache` with its last use; entries with no note are adopted with their size measured. */
async function survey(name: string): Promise<{ cache: Cache; items: Item[] } | null> {
    const cache = await globalThis.caches?.open(name).catch(() => undefined)
    if (!cache || !db) return null
    const reqs = await cache.keys().catch(() => [] as readonly Request[])
    const notes = await getMany<Row>(reqs.map((r) => idKey(name, pathOf(r))), db).catch(() => [] as (Row | undefined)[])
    const now = Date.now()
    const items: Item[] = []
    const live = new Set<string>()
    for (let i = 0; i < reqs.length; i++) {
        const req = reqs[i]
        const url = pathOf(req)
        live.add(idKey(name, url))
        let note = notes[i]
        if (!Array.isArray(note) || !(note[1] >= 0)) {
            const bytes = await cache.match(req).then((r) => r?.blob()).then((b) => b?.size ?? 0).catch(() => 0)
            note = [Array.isArray(note) ? note[0] : now, bytes]
            touchAt(name, url, note[0], bytes)
            if (i % 50 === 49) await yieldNow()
        }
        items.push({ req, time: note[0] })
    }
    // Notes of entries that are gone.
    try {
        const stale = (await entries<string, Row>(db)).map(([k]) => k).filter((k) => typeof k === 'string' && k.startsWith(`${name}|`) && !live.has(k))
        await delMany(stale, db)
    } catch {}
    return { cache, items }
}

/** Like `touch`, but keeping an earlier use time. */
function touchAt(cache: string, url: string, time: number, bytes: number) {
    pending.set(idKey(cache, url), [time, bytes])
    flushing ??= setTimeout(() => void flush(), 3000)
}

/** The least recently used entries beyond `cap` go. */
async function dropOldest(cache: Cache, name: string, items: Item[], cap: number) {
    if (items.length <= cap || !db) return
    const old = [...items].sort((a, b) => a.time - b.time).slice(0, items.length - cap)
    const keys: string[] = []
    for (const o of old) {
        await cache.delete(o.req).catch(() => {})
        pending.delete(idKey(name, pathOf(o.req)))
        keys.push(idKey(name, pathOf(o.req)))
    }
    await delMany(keys, db).catch(() => {})
}

async function trimThumbs() {
    const s = await survey(THUMBS)
    if (s) await dropOldest(s.cache, THUMBS, s.items, THUMB_CAP)
}

async function trimPosters() {
    const s = await survey(POSTERS)
    if (!s || !db) return
    const now = Date.now()
    const pictures: Item[] = []
    const gone: string[] = []
    let n = 0
    for (const item of s.items) {
        if (new URL(item.req.url).pathname.startsWith('/tmdb/img/')) {
            pictures.push(item)
            continue
        }
        // An answer: expired by its saved time, a time ahead of the clock counting as expired.
        const hit = await s.cache.match(item.req).catch(() => undefined)
        const saved = Number(hit?.headers.get('x-saved'))
        const body = (await hit?.json().catch(() => null)) as { match?: boolean; found?: boolean } | null
        const ttl = body && (body.match || body.found) ? FOUND_TTL : MISS_TTL
        const age = now - saved
        if (!hit || !body || !Number.isFinite(saved) || age < 0 || age >= ttl) {
            await s.cache.delete(item.req).catch(() => {})
            pending.delete(idKey(POSTERS, pathOf(item.req)))
            gone.push(idKey(POSTERS, pathOf(item.req)))
        }
        if (++n % 50 === 0) await yieldNow()
    }
    await delMany(gone, db).catch(() => {})
    await dropOldest(s.cache, POSTERS, pictures, PICTURE_CAP)
}
