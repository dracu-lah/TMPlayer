import { POSTERS } from '../store'
import { touch } from '../cacheBounds'
import type { PosterAsk } from '../rules'

// Posters, as the apps show them: TMDB's poster for a video's cleaned title (the question the app's
// rules ask, tg/media.ts `poster`), looked up by the /tmdb worker (tmdb/handler.ts), which holds
// the key. Only for cards on screen, a few at a time like thumbnails, and kept in the Cache API
// for as long as the apps keep an answer (MetaKeep): 60 days found, 7 days "nothing matched".
// A failure of any kind, a throttle or a TMDB outage included, leaves the card its thumbnail and
// is never kept; after one the page stops asking for a while.
//
// The detail page asks for more of the same title (overview, genres, cast, trailer: MetaInfo and
// MetaExtras), by the id the poster answer gave, and under the same rules.

const DAY = 24 * 60 * 60 * 1000
const FOUND_TTL = 60 * DAY
const MISS_TTL = 7 * DAY
/** MetaApis.POSTER_SIZE: the size a card draws; a small card takes the lighter w185. */
export type PosterSize = 'w185' | 'w342'
/** The wide picture: w300 to show at once on the carousel and a video's page, w780 to replace it. */
export type BackdropSize = 'w300' | 'w780'
const PARALLEL = 4
/** Extra lanes for what the viewer is looking at (the carousel, a video's page), past the cards' queue. */
const PRIORITY_LANES = 2

/** Asks the carousel and a video's page make: past the cards' queue, ahead of it when the lanes are full. */
export interface Want {
    priority?: boolean
}

/** The question as the worker takes it. */
function query(a: PosterAsk): string {
    const q = new URLSearchParams({ k: a.kind, t: a.title })
    if (a.year) q.set('y', String(a.year))
    if (a.tmdbId) q.set('id', String(a.tmdbId))
    if (a.series) q.set('s', '1')
    if (a.languages.length) q.set('l', a.languages.join(','))
    return q.toString()
}

/** The worker's poster answer: the title it matched, and what MetaInfo keeps of it. */
export interface Answer {
    match: boolean
    id?: string
    kind?: 'film' | 'show'
    title?: string
    year?: number | null
    poster?: string | null
    /** The wide picture's file; answers kept before it was added have none. */
    backdrop?: string | null
    /** The overview, in English. */
    overview?: string
}

/** The detail page's second helping (MetaExtras), as the worker's /tmdb/extras answers. */
export interface Extras {
    found: boolean
    genres?: string[]
    runtime?: number | null
    seasons?: number | null
    rating?: number | null
    certification?: string | null
    directors?: string[]
    creators?: string[]
    cast?: { name: string; role: string; photo: string | null }[]
    trailer?: string | null
    ended?: boolean
    imdb?: string | null
    episode?: { name: string; overview: string; still: string | null }
}

let pausedUntil = 0
const pause = (ms: number) => (pausedUntil = Date.now() + ms)

async function open(): Promise<Cache | undefined> {
    return globalThis.caches?.open(POSTERS).catch(() => undefined)
}

const inflight = new Map<string, Promise<unknown>>()

/** The worker's answer at `url`: the same lookup made twice at once is made once. */
function ask<T extends { match?: boolean; found?: boolean }>(url: string): Promise<T> {
    let p = inflight.get(url) as Promise<T> | undefined
    if (!p) {
        p = fetchAnswer<T>(url).finally(() => inflight.delete(url))
        inflight.set(url, p)
    }
    return p
}

/** The worker's answer at `url`, from the Cache API while fresh, else asked and kept. Failures are not kept. */
async function fetchAnswer<T extends { match?: boolean; found?: boolean }>(url: string): Promise<T> {
    const cache = await open()
    const hit = await cache?.match(url).catch(() => undefined)
    if (hit) {
        touch(POSTERS, url)
        const saved = Number(hit.headers.get('x-saved'))
        const body = (await hit.clone().json().catch(() => null)) as T | null
        // A poster answer kept before the overview and the wide picture were added is asked again.
        const old = !!body?.match && url.startsWith('/tmdb/poster') && !('overview' in body)
        if (body && !old && Date.now() - saved < (body.match || body.found ? FOUND_TTL : MISS_TTL)) return body
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
    const body = JSON.parse(text) as T
    cache?.put(url, new Response(text, { headers: { 'Content-Type': 'application/json', 'x-saved': String(Date.now()) } })).then(() => touch(POSTERS, url, text.length), () => {})
    return body
}

/** The poster answer for `a`. */
export const answer = (a: PosterAsk) => ask<Answer>(`/tmdb/poster?${query(a)}`)

/** A picture's bytes, from the Cache API or the worker (and then kept there). */
async function picture(path: string, size: string): Promise<Blob> {
    const url = `/tmdb/img/${size}${path}`
    const cache = await open()
    const hit = await cache?.match(url).catch(() => undefined)
    if (hit) {
        touch(POSTERS, url)
        return hit.blob()
    }
    const response = await fetch(url)
    if (!response.ok) throw new Error(`poster picture ${response.status}`)
    const copy = response.clone()
    const blob = await response.blob()
    cache?.put(url, copy).then(() => touch(POSTERS, url, blob.size), () => {})
    return blob
}

const known = new Map<string, Promise<string | null>>()
let active = 0
const waiting: (() => void)[] = []

/**
 * Runs `work` once the page is within its few lookups at a time; a lookup already made is shared.
 * On a first sign in a Home of cards fills the queue at once, so a `priority` one (the carousel's
 * picture, a video's page) takes one of its own lanes, or goes to the front of the queue.
 */
function once<T>(key: string, store: Map<string, Promise<T>>, work: () => Promise<T>, priority = false): Promise<T> {
    let p = store.get(key)
    if (!p) {
        const limit = priority ? PARALLEL + PRIORITY_LANES : PARALLEL
        p = new Promise<void>((go) => (active < limit ? (active++, go()) : priority ? waiting.unshift(go) : waiting.push(go)))
            .then(work)
            .catch((e) => {
                store.delete(key)
                throw e
            })
            .finally(() => {
                const next = waiting.shift()
                if (next) next()
                else active--
            })
        store.set(key, p)
    }
    return p
}

/**
 * An address for `a`'s poster to draw, or null when TMDB has no title the video's name backs.
 * Rejects when the lookup could not be made; the card then keeps its thumbnail.
 */
export function loadPoster(a: PosterAsk, size: PosterSize = 'w342', want: Want = {}): Promise<string | null> {
    return once(`${size}:${query(a)}`, known, async () => {
        const found = await answer(a)
        if (!found.match || !found.poster) return null
        return URL.createObjectURL(await picture(found.poster, size))
    }, want.priority)
}

/** The poster size for a card `width` CSS pixels wide: w185 unless the screen's pixels need more (2x at most). */
export function posterSizeFor(width: number): PosterSize {
    return width * Math.min(2, globalThis.devicePixelRatio || 1) <= 260 ? 'w185' : 'w342'
}

const wide = new Map<string, Promise<string | null>>()

/** An address for `a`'s wide picture (MetaInfo.backdropUrl) at `size`, or null when it has none. */
export function loadBackdrop(a: PosterAsk, size: BackdropSize = 'w780', want: Want = {}): Promise<string | null> {
    return once(`${size}:${query(a)}`, wide, async () => {
        const found = await answer(a)
        if (!found.match || !found.backdrop) return null
        return URL.createObjectURL(await picture(found.backdrop, size))
    }, want.priority)
}

/**
 * The wide picture light first: `show` gets the w300 copy as soon as it is in, then the w780 one,
 * which never gives way to the light one arriving late. For the carousel and a video's page, past
 * the cards' queue. Returns a stop for a page that leaves before either arrives.
 */
export function loadBackdropLightFirst(a: PosterAsk, show: (url: string) => void): () => void {
    let live = true
    let full = false
    loadBackdrop(a, 'w300', { priority: true }).then((u) => live && !full && u && show(u), () => {})
    loadBackdrop(a, 'w780', { priority: true }).then((u) => {
        if (!live || !u) return
        full = true
        show(u)
    }, () => {})
    return () => (live = false)
}

const faces = new Map<string, Promise<string | null>>()

/** An address for one file of TMDB's (a face in the cast row, an episode's still) at `size`, or null when it is gone. */
export function loadFile(path: string, size: 'w185' | 'w780'): Promise<string | null> {
    return once(`${size}${path}`, faces, async () => URL.createObjectURL(await picture(path, size)).toString())
}

/** The detail page's extras for the title `found` the poster answer matched, with the episode's own words for a show's episode. */
export function loadExtras(found: Answer, episode: { season: number; episode: number } | null): Promise<Extras> {
    const q = new URLSearchParams({ k: found.kind!, id: found.id!, r: region() })
    if (found.kind === 'show' && episode) {
        q.set('s', String(episode.season))
        q.set('e', String(episode.episode))
    }
    return ask<Extras>(`/tmdb/extras?${q}`)
}

/** The viewer's region for the age rating: the browser's, else the US (MetaRules.region). */
function region(): string {
    const tag = (navigator.language || 'en-US').replace('_', '-').split('-').slice(1).find((p) => /^[A-Za-z]{2}$/.test(p))
    return (tag ?? 'US').toUpperCase()
}
