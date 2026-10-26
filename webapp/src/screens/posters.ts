import { POSTERS } from '../store'
import type { PosterAsk } from '../rules'

// Posters, as the apps show them: TMDB's poster for a video's cleaned title (the question the app's
// rules ask, tg/media.ts `poster`), looked up by the /tmdb worker (tmdb/handler.ts), which holds
// the key. Only for cards on screen, a few at a time like thumbnails, and kept in the Cache API
// for as long as the apps keep an answer (MetaKeep): 60 days found, 7 days "nothing matched".
// A failure of any kind, a throttle or a TMDB outage included, leaves the card its thumbnail and
// is never kept; after one the page stops asking for a while.

const DAY = 24 * 60 * 60 * 1000
const FOUND_TTL = 60 * DAY
const MISS_TTL = 7 * DAY
/** MetaApis.POSTER_SIZE: the size a card draws. */
const SIZE = 'w342'
const PARALLEL = 4

/** The question as the worker takes it. */
function query(a: PosterAsk): string {
    const q = new URLSearchParams({ k: a.kind, t: a.title })
    if (a.year) q.set('y', String(a.year))
    if (a.tmdbId) q.set('id', String(a.tmdbId))
    if (a.series) q.set('s', '1')
    if (a.languages.length) q.set('l', a.languages.join(','))
    return q.toString()
}

interface Answer {
    match: boolean
    poster?: string | null
}

let pausedUntil = 0
const pause = (ms: number) => (pausedUntil = Date.now() + ms)

async function open(): Promise<Cache | undefined> {
    return globalThis.caches?.open(POSTERS).catch(() => undefined)
}

/** The worker's answer for `a`, from the Cache API while fresh, else asked and kept. */
async function answer(a: PosterAsk): Promise<Answer> {
    const url = `/tmdb/poster?${query(a)}`
    const cache = await open()
    const hit = await cache?.match(url).catch(() => undefined)
    if (hit) {
        const saved = Number(hit.headers.get('x-saved'))
        const body = (await hit.clone().json().catch(() => null)) as Answer | null
        if (body && Date.now() - saved < (body.match ? FOUND_TTL : MISS_TTL)) return body
    }
    if (Date.now() < pausedUntil) throw new Error('paused')
    let response: Response
    try {
        response = await fetch(url)
    } catch (e) {
        pause(15_000)
        throw e
    }
    if (response.status === 503 || response.status === 429) {
        pause(Math.max(30, Number(response.headers.get('retry-after')) || 0) * 1000)
        throw new Error(`posters unavailable (${response.status})`)
    }
    if (!response.ok) throw new Error(`posters ${response.status}`)
    const text = await response.text()
    const body = JSON.parse(text) as Answer
    cache?.put(url, new Response(text, { headers: { 'Content-Type': 'application/json', 'x-saved': String(Date.now()) } })).catch(() => {})
    return body
}

/** The poster file's bytes, from the Cache API or the worker (and then kept there). */
async function picture(path: string): Promise<Blob> {
    const url = `/tmdb/img/${SIZE}${path}`
    const cache = await open()
    const hit = await cache?.match(url).catch(() => undefined)
    if (hit) return hit.blob()
    const response = await fetch(url)
    if (!response.ok) throw new Error(`poster picture ${response.status}`)
    cache?.put(url, response.clone()).catch(() => {})
    return response.blob()
}

const known = new Map<string, Promise<string | null>>()
let active = 0
const waiting: (() => void)[] = []

/**
 * An address for `a`'s poster to draw, or null when TMDB has no title the video's name backs.
 * Rejects when the lookup could not be made; the card then keeps its thumbnail.
 */
export function loadPoster(a: PosterAsk): Promise<string | null> {
    const key = query(a)
    let p = known.get(key)
    if (!p) {
        p = new Promise<void>((go) => (active < PARALLEL ? (active++, go()) : waiting.push(go)))
            .then(async () => {
                const found = await answer(a)
                if (!found.match || !found.poster) return null
                return URL.createObjectURL(await picture(found.poster))
            })
            .catch((e) => {
                known.delete(key)
                throw e
            })
            .finally(() => {
                const next = waiting.shift()
                if (next) next()
                else active--
            })
        known.set(key, p)
    }
    return p
}
