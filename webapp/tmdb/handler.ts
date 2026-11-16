// The web player's posters: a small proxy for TMDB that runs next to the page as a Cloudflare
// Pages Function (functions/tmdb/[[path]].ts), so the TMDB key lives in the Pages project's secrets
// (TMDB_API_KEY, the same name the apps' builds read) and never in the page or the repo.
//
//   GET /tmdb/poster?k=film|show&t=<title>&y=<year>&id=<tmdb id>&s=1&l=ta,hi
//        the app's lookup for one video, answered as JSON: { match: true, id, kind, title, year,
//        poster: "/abc.jpg" | null } or { match: false }. 503 when TMDB cannot be asked right now.
//        The answer also carries the title's overview (English) and its wide picture's file, which
//        MetaInfo has beside the poster: `overview`, `backdrop`.
//   GET /tmdb/search?k=film|show&t=<title>&y=<year>&l=ta,hi
//        "Choose the right poster": the titles TMDB has that the name could mean, films and shows
//        each searched with the year and without it, merged and ranked (choices.ts), at most ten:
//        { choices: [{ id, kind, title, year, language, overview, poster }] }. 503 as above when
//        nothing could be learned. Kept an hour at the edge, a failure never.
//   GET /tmdb/poster also takes p=1 with id: the viewer picked that title, so no year backs it.
//   GET /tmdb/extras?k=film|show&id=<tmdb id>[&s=1&e=3][&r=GB]
//        the detail page's second helping (MetaExtras): genres, length, rating, age rating, directors
//        or creators, cast, a trailer's YouTube id, and for a show's episode its own name and words.
//        { found: true, ... } or { found: false }; 503 as above. extras.ts holds the rules.
//   Anime (anilist.ts): a question with a=1 (the name reads like fansub anime, MetaQuery.anime) asks AniList
//        first for a show and TMDB first for a film, as OnlineMetadata.resolve does; AniList needs no key.
//        The answer then also says `source: "anilist"` when AniList matched; its pictures are "/al/<tail>",
//        and /tmdb/extras?...&a=1 asks AniList by that id. Each provider is paused on its own after a throttle.
//   GET /tmdb/img/<w185|w300|w342|w500|w780>/<file>.jpg   (and .../al/<cover|banner|character>/... for AniList's)
//        a picture's bytes from TMDB's image host, same origin so the page can keep them in the
//        Cache API as it keeps thumbnails.
//
// The rules are the apps' (OnlineMetadata, MetadataCache): TMDB only, English, the search asked
// as a film and then as a show for a name with no episode, with the year and then without it,
// MetaPick for the best of the answers (match.ts), a match shown only when its year or an episode
// code backs it (MetaConfidence). A found answer is kept 60 days, "nothing matched" 7 (MetaKeep),
// nothing longer than TMDB's six months; a throttle (429) pauses asking for the time TMDB says, a
// refused key is not asked with again for a day, a dropped call or a server error is tried once
// more after a second, and none of these failures is ever cached. The title is the only thing
// that leaves for TMDB: no chat, file or account.
import { extras, type Extras } from './extras.ts'
import { mergeChoices } from './choices.ts'
import { best, accepts, normalise, trusted, type Ask, type Candidate } from './match.ts'
import { BASE as ANILIST, DEFAULT_WAIT_MS as ANILIST_WAIT, EXTRAS_QUERY, SEARCH_QUERY, candidates as aniCandidates, extrasOf as aniExtras, imageAddress } from './anilist.ts'

export interface Env {
    /** A TMDB v3 key or v4 read token. Absent: every lookup answers 503 and the page keeps its thumbnails. */
    TMDB_API_KEY?: string
}

/** What the handler needs from the platform, so a test can stand in for all of it. */
export interface Deps {
    fetch: (request: Request) => Promise<Response>
    /** Cloudflare's `caches.default`. */
    cache?: { match(request: Request): Promise<Response | undefined>; put(request: Request, response: Response): Promise<void> }
    now?: () => number
    sleep?: (ms: number) => Promise<void>
    /** Run after the response is sent (Pages' `waitUntil`). */
    later?: (work: Promise<unknown>) => void
}

const API = 'https://api.themoviedb.org/3'
const IMAGES = 'https://image.tmdb.org/t/p'
const DAY = 24 * 60 * 60
/** MetaKeep.FOUND_TTL_MS and MISS_TTL_MS, in seconds. */
export const FOUND_TTL = 60 * DAY
export const MISS_TTL = 7 * DAY
/** OnlineMetadata.REFUSED_RETRY_MS, TmdbApi.DEFAULT_WAIT_MS and OnlineMetadata.RETRY_MS. */
const REFUSED_RETRY = DAY * 1000
const DEFAULT_WAIT = 10_000
const RETRY = 1_000
const SIZES = new Set(['w185', 'w300', 'w342', 'w500', 'w780'])
const FILE = /^\/[A-Za-z0-9_-]{8,64}\.(jpg|png|webp)$/

const json = (body: unknown, status = 200, headers: Record<string, string> = {}) =>
    new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json', ...headers } })

const unavailable = (why: string, retryAfterS = 30) =>
    json({ error: why }, 503, { 'Cache-Control': 'no-store', 'Retry-After': String(Math.max(1, Math.ceil(retryAfterS))) })

type Reply = { kind: 'ok'; json: any } | { kind: 'refused' } | { kind: 'throttled'; waitMs: number } | { kind: 'missing' } | { kind: 'failed' }

export function createHandler(deps: Deps) {
    const now = deps.now ?? Date.now
    const sleep = deps.sleep ?? ((ms: number) => new Promise<void>((r) => setTimeout(r, ms)))
    // Per isolate, like OnlineMetadata's: asking stops until these pass.
    let pausedUntil = 0
    let refusedAt = 0

    async function call(key: string, path: string, params: string): Promise<Reply> {
        const bearer = key.length > 40
        const url = `${API}/${path}?${params}${bearer ? '' : `&api_key=${encodeURIComponent(key)}`}`
        const headers: Record<string, string> = { Accept: 'application/json', 'User-Agent': 'TMPlayer-web-posters' }
        if (bearer) headers.Authorization = `Bearer ${key}`
        let response: Response
        try {
            response = await deps.fetch(new Request(url, { headers }))
        } catch {
            return { kind: 'failed' }
        }
        if (response.status === 401 || response.status === 403) return { kind: 'refused' }
        if (response.status === 429) {
            const seconds = Number(response.headers.get('retry-after'))
            return { kind: 'throttled', waitMs: seconds > 0 ? Math.min(600, Math.max(1, seconds)) * 1000 : DEFAULT_WAIT }
        }
        if (response.status === 404) return { kind: 'missing' }
        if (!response.ok) return { kind: 'failed' }
        try {
            return { kind: 'ok', json: await response.json() }
        } catch {
            return { kind: 'failed' }
        }
    }

    /** One more try after a second for a dropped connection or a server error, then give up. */
    async function retrying(key: string, path: string, params: string): Promise<Reply> {
        const first = await call(key, path, params)
        if (first.kind !== 'failed') return first
        await sleep(RETRY)
        return call(key, path, params)
    }

    // AniList (anilist.ts): no key, and a pause of its own after a throttle.
    let aniPausedUntil = 0

    async function aniCall(query: string, variables: Record<string, unknown>): Promise<Reply> {
        let response: Response
        try {
            response = await deps.fetch(
                new Request(ANILIST, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json', Accept: 'application/json', 'User-Agent': 'TMPlayer-web-posters' },
                    body: JSON.stringify({ query, variables }),
                }),
            )
        } catch {
            return { kind: 'failed' }
        }
        if (response.status === 429) {
            const seconds = Number(response.headers.get('retry-after'))
            const waitMs = seconds > 0 ? Math.min(600, Math.max(1, seconds)) * 1000 : ANILIST_WAIT
            aniPausedUntil = now() + waitMs
            return { kind: 'throttled', waitMs }
        }
        if (response.status === 404) return { kind: 'missing' }
        if (!response.ok) return { kind: 'failed' }
        try {
            return { kind: 'ok', json: await response.json() }
        } catch {
            return { kind: 'failed' }
        }
    }

    /** One more try after a second for a dropped connection or a server error, then give up. */
    async function aniRetrying(query: string, variables: Record<string, unknown>): Promise<Reply> {
        const first = await aniCall(query, variables)
        if (first.kind !== 'failed') return first
        await sleep(RETRY)
        return aniCall(query, variables)
    }

    const yearOf = (date: unknown) => {
        const y = Number(String(date ?? '').slice(0, 4))
        return y > 1800 ? y : null
    }

    function candidate(film: boolean, o: any): Candidate {
        return {
            id: String(o.id),
            names: film ? [o.title ?? '', o.original_title ?? ''] : [o.name ?? '', o.original_name ?? ''],
            year: yearOf(film ? o.release_date : o.first_air_date),
            poster: typeof o.poster_path === 'string' && o.poster_path.startsWith('/') ? o.poster_path : null,
            originalLanguage: o.original_language ?? '',
            overview: typeof o.overview === 'string' ? o.overview.trim() : '',
            backdrop: typeof o.backdrop_path === 'string' && FILE.test(o.backdrop_path) ? o.backdrop_path : null,
        }
    }

    /** [linked]: found by an IMDb id in the post's words, which backs the match on its own (MetaInfo.linked). */
    type Found = { film: boolean; candidate: Candidate; linked?: boolean; source?: 'anilist' }
    type Outcome = { kind: 'found'; found: Found } | { kind: 'none' } | { kind: 'blocked'; reason: string; waitS: number }

    function stopped(reply: Reply): Outcome {
        if (reply.kind === 'throttled') {
            pausedUntil = now() + reply.waitMs
            return { kind: 'blocked', reason: 'throttled', waitS: reply.waitMs / 1000 }
        }
        if (reply.kind === 'refused') {
            refusedAt = now()
            return { kind: 'blocked', reason: 'refused', waitS: REFUSED_RETRY / 1000 }
        }
        return { kind: 'blocked', reason: 'unavailable', waitS: 30 }
    }

    /**
     * The title an IMDb id names (OnlineMetadata.linkedTitle): one `find`, and null for an id TMDB
     * does not know or a call that failed, which leaves the title guess to run.
     */
    async function linked(key: string, imdbId: string): Promise<{ film: boolean; id: number } | null> {
        const reply = await retrying(key, `find/${imdbId}`, 'external_source=imdb_id')
        if (reply.kind !== 'ok') return null
        const json = (reply as { json: any }).json
        const id = (list: unknown) => (Array.isArray(list) && Number.isInteger(list[0]?.id) && list[0].id > 0 ? (list[0].id as number) : null)
        const film = id(json?.movie_results)
        if (film !== null) return { film: true, id: film }
        const show = id(json?.tv_results)
        return show !== null ? { film: false, id: show } : null
    }

    /** OnlineMetadata.tmdbLookup, in English. */
    async function lookup(key: string | null, ask: Ask): Promise<Outcome> {
        const noKey: Outcome = { kind: 'blocked', reason: 'no key', waitS: 300 }
        const byImdb = key && ask.tmdbId === null && ask.imdbId ? await linked(key, ask.imdbId) : null
        if (byImdb && key) {
            const reply = await retrying(key, `${byImdb.film ? 'movie' : 'tv'}/${byImdb.id}`, 'language=en-US')
            if (reply.kind !== 'ok' && reply.kind !== 'missing') return stopped(reply)
            if (reply.kind === 'ok') {
                const c = candidate(byImdb.film, reply.json)
                // A link the file's own year or episode contradicts is another title's: the guess runs.
                const contradicted = (byImdb.film && ask.series) || (ask.year !== null && c.year !== null && Math.abs(ask.year - c.year) > 1 && !(!byImdb.film && ask.year > c.year))
                if (!contradicted) return { kind: 'found', found: { film: byImdb.film, candidate: c, linked: true } }
            }
        }
        if (ask.tmdbId !== null) {
            if (!key) return noKey
            const film = ask.kind === 'film'
            const reply = await retrying(key, `${film ? 'movie' : 'tv'}/${ask.tmdbId}`, 'language=en-US')
            if (reply.kind === 'missing') return { kind: 'none' }
            if (reply.kind !== 'ok') return stopped(reply)
            return { kind: 'found', found: { film, candidate: candidate(film, reply.json) } }
        }
        // OnlineMetadata.resolve's order for a name that reads like fansub anime: a show asks AniList
        // first and TMDB after it, a film TMDB first. The web has no TVmaze to ask third.
        if (ask.anime) {
            let blocked: Outcome | null = null
            for (const step of ask.kind === 'film' ? [tmdbSearch, aniLookup] : [aniLookup, tmdbSearch]) {
                const outcome = await step(key, ask)
                if (outcome.kind === 'found') return outcome
                if (outcome.kind === 'blocked') blocked = outcome
            }
            return blocked ?? { kind: 'none' }
        }
        return tmdbSearch(key, ask)
    }

    /** OnlineMetadata.anilistLookup: AniList's titles of the kind asked, the first the name accepts. */
    async function aniLookup(_key: string | null, ask: Ask): Promise<Outcome> {
        if (now() < aniPausedUntil) return { kind: 'blocked', reason: 'throttled', waitS: (aniPausedUntil - now()) / 1000 }
        const reply = await aniRetrying(SEARCH_QUERY, { search: ask.title })
        if (reply.kind === 'throttled') return { kind: 'blocked', reason: 'throttled', waitS: reply.waitMs / 1000 }
        if (reply.kind === 'failed' || reply.kind === 'refused') return { kind: 'blocked', reason: 'unavailable', waitS: 30 }
        if (reply.kind === 'missing') return { kind: 'none' }
        const film = ask.kind === 'film'
        const found = aniCandidates((reply as { json: unknown }).json)
            .filter((c) => (c.format === 'MOVIE') === film)
            .find((c) => accepts(ask.title, ask.year, c.names, c.year, !film))
        return found ? { kind: 'found', found: { film, candidate: found, source: 'anilist' } } : { kind: 'none' }
    }

    /** The TMDB half of OnlineMetadata.tmdbLookup: the name searched, and the pause and refusal that stop asking. */
    async function tmdbSearch(key: string | null, ask: Ask): Promise<Outcome> {
        if (!key) return { kind: 'blocked', reason: 'no key', waitS: 300 }
        if (now() < pausedUntil) return { kind: 'blocked', reason: 'throttled', waitS: (pausedUntil - now()) / 1000 }
        if (refusedAt && now() - refusedAt < REFUSED_RETRY) return { kind: 'blocked', reason: 'refused', waitS: (REFUSED_RETRY - (now() - refusedAt)) / 1000 }
        // A name with no episode code is asked as a film first and, once no film matches, as a
        // show (a season posted whole as "Scam 1992 Complete"); trusted() still has to back it.
        for (const film of ask.kind === 'film' ? [true, false] : [false]) {
            const search = async (year: number | null) => {
                const params = `query=${encodeURIComponent(ask.title)}&include_adult=false&language=en-US` + (year !== null ? `&${film ? 'year' : 'first_air_date_year'}=${year}` : '')
                return retrying(key, `search/${film ? 'movie' : 'tv'}`, params)
            }
            const list = (reply: Reply): Candidate[] => ((reply as { json: any }).json.results ?? []).map((o: any) => candidate(film, o))
            const first = await search(ask.year)
            if (first.kind !== 'ok') return stopped(first)
            let pick = best(ask, list(first), !film)
            // A year one off (a festival date, a late release) is filtered out by TMDB's own year,
            // so ask once more without it and let the match allow the difference.
            if (!pick && ask.year !== null) {
                const again = await search(null)
                if (again.kind !== 'ok') return stopped(again)
                pick = best(ask, list(again), !film)
            }
            if (pick) return { kind: 'found', found: { film, candidate: pick } }
        }
        return { kind: 'none' }
    }

    function parse(url: URL): Ask | null {
        const p = url.searchParams
        const title = (p.get('t') ?? '').trim()
        const kind = p.get('k')
        if (!title || title.length > 200 || (kind !== 'film' && kind !== 'show')) return null
        const num = (name: string, lo: number, hi: number) => {
            const raw = p.get(name)
            if (raw === null || raw === '') return null
            const n = Number(raw)
            return Number.isInteger(n) && n >= lo && n <= hi ? n : NaN
        }
        const year = num('y', 1800, 2100)
        const id = num('id', 1, 999_999_999)
        if (Number.isNaN(year) || Number.isNaN(id)) return null
        const languages = (p.get('l') ?? '').split(',').filter((l) => /^[a-z]{2,3}$/.test(l)).slice(0, 8)
        // A pick names its title by id; "picked" without one is no question.
        const picked = p.get('p') === '1'
        if (picked && id === null) return null
        const anime = p.get('a') === '1'
        const imdb = p.get('i')
        const imdbId = id === null && imdb && /^tt\d{7,9}$/.test(imdb) ? imdb : null
        return { title, year, kind, tmdbId: id, series: p.get('s') === '1', languages, ...(imdbId ? { imdbId } : {}), ...(picked ? { picked } : {}), ...(anime ? { anime } : {}) }
    }

    /** What the cache files an answer under: the cleaned title, as MetaQuery.cacheKey does, never the raw one. */
    function cacheRequest(origin: string, ask: Ask): Request {
        const q = new URLSearchParams({ k: ask.kind, t: normalise(ask.title), y: String(ask.year ?? ''), id: String(ask.tmdbId ?? ''), s: ask.series ? '1' : '0', l: ask.languages.join(','), p: ask.picked ? '1' : '0', ...(ask.imdbId ? { i: ask.imdbId } : {}), ...(ask.anime ? { a: '1' } : {}) })
        return new Request(`${origin}/tmdb/poster?${q}`)
    }

    async function poster(request: Request, url: URL, env: Env): Promise<Response> {
        const ask = parse(url)
        if (!ask) return json({ error: 'bad request' }, 400)
        const key = env.TMDB_API_KEY?.trim() || null
        if (!key && !ask.anime) return unavailable('no key', 300)
        const cacheKey = cacheRequest(url.origin, ask)
        const kept = await deps.cache?.match(cacheKey).catch(() => undefined)
        if (kept) {
            const headers = new Headers(kept.headers)
            headers.set('X-Poster-Cache', 'hit')
            return new Response(kept.body, { status: kept.status, headers })
        }
        if (!ask.anime && now() < pausedUntil) return unavailable('throttled', (pausedUntil - now()) / 1000)
        if (!ask.anime && refusedAt && now() - refusedAt < REFUSED_RETRY) return unavailable('refused', (REFUSED_RETRY - (now() - refusedAt)) / 1000)
        const outcome = await lookup(key, ask)
        if (outcome.kind === 'blocked') return unavailable(outcome.reason, outcome.waitS)
        if (!(outcome.kind === 'found' && outcome.found.source)) refusedAt = 0
        let body: object = { match: false }
        let ttl = MISS_TTL
        if (outcome.kind === 'found') {
            const { film, candidate: c, linked: byLink, source } = outcome.found
            const kind = film ? 'film' : 'show'
            if (byLink || trusted(ask, { kind, year: c.year })) {
                body = { match: true, id: c.id, kind, title: c.names.find((n) => n.trim()) ?? ask.title, year: c.year, poster: c.poster, backdrop: c.backdrop ?? null, overview: c.overview ?? '', ...(source ? { source } : {}) }
                ttl = FOUND_TTL
            }
        }
        const response = json(body, 200, { 'Cache-Control': `public, max-age=3600, s-maxage=${ttl}`, 'X-Poster-Cache': 'miss' })
        if (deps.cache) {
            const put = deps.cache.put(cacheKey, response.clone()).catch(() => {})
            deps.later?.(put)
        }
        return response
    }

    /**
     * The titles a name could mean (OnlineMetadata.candidates): films and shows, each with the year
     * and without it, the kind the file looks like first so a throttle part way still leaves its titles.
     */
    async function search(url: URL, env: Env): Promise<Response> {
        const ask = parse(url)
        if (!ask) return json({ error: 'bad request' }, 400)
        const key = env.TMDB_API_KEY?.trim()
        if (!key) return unavailable('no key', 300)
        // The search is for the name: a forced id in the question is not part of it.
        const question: Ask = { ...ask, tmdbId: null }
        const q = new URLSearchParams({ v: '1', k: question.kind, t: normalise(question.title), y: String(question.year ?? ''), l: question.languages.join(',') })
        const cacheKey = new Request(`${url.origin}/tmdb/search?${q}`)
        const kept = await deps.cache?.match(cacheKey).catch(() => undefined)
        if (kept) {
            const headers = new Headers(kept.headers)
            headers.set('X-Poster-Cache', 'hit')
            return new Response(kept.body, { status: kept.status, headers })
        }
        if (now() < pausedUntil) return unavailable('throttled', (pausedUntil - now()) / 1000)
        if (refusedAt && now() - refusedAt < REFUSED_RETRY) return unavailable('refused', (REFUSED_RETRY - (now() - refusedAt)) / 1000)
        const lists: { film: boolean; candidates: Candidate[] }[] = []
        let blocked: Outcome | null = null
        const years = question.year === null ? [null] : [question.year, null]
        for (const film of question.kind === 'film' ? [true, false] : [false, true]) {
            for (const year of years) {
                // Once TMDB says to wait or turns the key down, nothing more is asked this time; nor
                // after an outage that has answered nothing yet (not four tries into a dead line).
                if (blocked && blocked.kind === 'blocked' && (blocked.reason !== 'unavailable' || !lists.length)) continue
                const params = `query=${encodeURIComponent(question.title)}&include_adult=false&language=en-US` + (year !== null ? `&${film ? 'year' : 'first_air_date_year'}=${year}` : '')
                const reply = await retrying(key, `search/${film ? 'movie' : 'tv'}`, params)
                if (reply.kind === 'ok') lists.push({ film, candidates: ((reply.json.results ?? []) as any[]).map((o) => candidate(film, o)) })
                else blocked = stopped(reply)
            }
        }
        // Nothing learned and something in the way: say so, so the page offers Try again; never "no titles".
        if (!lists.length && blocked && blocked.kind === 'blocked') return unavailable(blocked.reason, blocked.waitS)
        if (!blocked) refusedAt = 0
        const response = json({ choices: mergeChoices(question, lists) }, 200, { 'Cache-Control': 'public, max-age=60, s-maxage=3600', 'X-Poster-Cache': 'miss' })
        // A partial answer (one search was throttled) is shown but not kept, so a later try fills it in.
        if (deps.cache && !blocked) {
            const put = deps.cache.put(cacheKey, response.clone()).catch(() => {})
            deps.later?.(put)
        }
        return response
    }

    /** What the cache files an extras answer under. */
    function extrasRequest(origin: string, q: { film: boolean; id: number; season: number | null; episode: number | null; region: string }): Request {
        // v2: with the title logo; answers kept before it are asked again rather than shown without.
        const p = new URLSearchParams({ v: '2', k: q.film ? 'film' : 'show', id: String(q.id), s: String(q.season ?? ''), e: String(q.episode ?? ''), r: q.region })
        return new Request(`${origin}/tmdb/extras?${p}`)
    }

    /**
     * AniListApi.extras: genres, episode length, score, main characters with their voice, trailer.
     * AniList lists no episodes, so a show's episode asks nothing more (the apps' guide merges TMDB,
     * Kitsu and Jikan, which the web does not yet).
     */
    async function aniDetails(url: URL, id: number): Promise<Response> {
        const cacheKey = new Request(`${url.origin}/tmdb/extras?${new URLSearchParams({ v: '2', a: '1', id: String(id) })}`)
        const kept = await deps.cache?.match(cacheKey).catch(() => undefined)
        if (kept) {
            const headers = new Headers(kept.headers)
            headers.set('X-Poster-Cache', 'hit')
            return new Response(kept.body, { status: kept.status, headers })
        }
        if (now() < aniPausedUntil) return unavailable('throttled', (aniPausedUntil - now()) / 1000)
        const reply = await aniRetrying(EXTRAS_QUERY, { id })
        if (reply.kind === 'throttled') return unavailable('throttled', reply.waitMs / 1000)
        if (reply.kind === 'failed' || reply.kind === 'refused') return unavailable('unavailable')
        const found = reply.kind === 'ok' ? aniExtras(reply.json) : null
        const response = json(found ?? { found: false }, 200, { 'Cache-Control': `public, max-age=3600, s-maxage=${found ? FOUND_TTL : MISS_TTL}`, 'X-Poster-Cache': 'miss' })
        if (deps.cache) deps.later?.(deps.cache.put(cacheKey, response.clone()).catch(() => {}))
        return response
    }

    async function details(url: URL, env: Env): Promise<Response> {
        const p = url.searchParams
        const kind = p.get('k')
        const num = (name: string, lo: number, hi: number) => {
            const raw = p.get(name)
            if (raw === null || raw === '') return null
            const n = Number(raw)
            return Number.isInteger(n) && n >= lo && n <= hi ? n : NaN
        }
        const id = num('id', 1, 999_999_999)
        const season = num('s', 0, 9999)
        const episode = num('e', 1, 99_999)
        const region = (p.get('r') ?? 'US').toUpperCase()
        if ((kind !== 'film' && kind !== 'show') || id === null || Number.isNaN(id) || Number.isNaN(season) || Number.isNaN(episode) || !/^[A-Z]{2}$/.test(region)) return json({ error: 'bad request' }, 400)
        // A title AniList matched (the poster answer's `source`): its own page, no key.
        if (p.get('a') === '1') return aniDetails(url, id)
        const key = env.TMDB_API_KEY?.trim()
        if (!key) return unavailable('no key', 300)
        const film = kind === 'film'
        const cacheKey = extrasRequest(url.origin, { film, id, season: film ? null : season, episode: film ? null : episode, region })
        const kept = await deps.cache?.match(cacheKey).catch(() => undefined)
        if (kept) {
            const headers = new Headers(kept.headers)
            headers.set('X-Poster-Cache', 'hit')
            return new Response(kept.body, { status: kept.status, headers })
        }
        if (now() < pausedUntil) return unavailable('throttled', (pausedUntil - now()) / 1000)
        if (refusedAt && now() - refusedAt < REFUSED_RETRY) return unavailable('refused', (REFUSED_RETRY - (now() - refusedAt)) / 1000)
        // MetaApis.extras: the title's own page with its credits, videos and age ratings appended; videos in English and with no language.
        // The logos come with them (MetaApis.logos), English or wordless, so the page needs no second call.
        const append = film ? 'credits,videos,release_dates,images' : 'aggregate_credits,videos,content_ratings,external_ids,images'
        const reply = await retrying(key, `${film ? 'movie' : 'tv'}/${id}`, `language=en-US&include_video_language=en,null&include_image_language=en,null&append_to_response=${append}`)
        const blocked = (r: Reply) => {
            const out = stopped(r)
            return unavailable(out.kind === 'blocked' ? out.reason : 'unavailable', out.kind === 'blocked' ? out.waitS : 30)
        }
        let body: { found: false } | Extras = { found: false }
        let ttl = MISS_TTL
        if (reply.kind === 'ok') {
            const found = extras(reply.json, film, region)
            body = found
            ttl = FOUND_TTL
            if (!film && season !== null && episode !== null) {
                // The episode's own name and words (MetaApis.episodes), only for the one the page is about.
                const one = await retrying(key, `tv/${id}/season/${season}/episode/${episode}`, 'language=en-US')
                if (one.kind === 'ok') {
                    const still = typeof one.json.still_path === 'string' && FILE.test(one.json.still_path) ? one.json.still_path : null
                    found.episode = { name: String(one.json.name ?? '').trim(), overview: String(one.json.overview ?? '').trim(), still }
                } else if (one.kind !== 'missing') return blocked(one)
            }
        } else if (reply.kind !== 'missing') {
            return blocked(reply)
        }
        refusedAt = 0
        const response = json(body, 200, { 'Cache-Control': `public, max-age=3600, s-maxage=${ttl}`, 'X-Poster-Cache': 'miss' })
        if (deps.cache) {
            const put = deps.cache.put(cacheKey, response.clone()).catch(() => {})
            deps.later?.(put)
        }
        return response
    }

    async function image(url: URL): Promise<Response> {
        const [, , , size, ...rest] = url.pathname.split('/')
        const file = '/' + rest.join('/')
        // "/al/<tail>" is an AniList picture (anilist.ts): one size only, from AniList's image host.
        const aniAddress = rest[0] === 'al' ? imageAddress(rest.slice(1).join('/')) : null
        if (!SIZES.has(size) || (rest[0] === 'al' ? !aniAddress : !FILE.test(file))) return new Response('Not found', { status: 404 })
        let upstream: Response
        try {
            upstream = await deps.fetch(new Request(aniAddress ?? `${IMAGES}/${size}${file}`))
        } catch {
            return unavailable('unavailable')
        }
        if (upstream.status === 404) return new Response('Not found', { status: 404, headers: { 'Cache-Control': 'public, max-age=3600' } })
        if (!upstream.ok) return unavailable('unavailable')
        // A poster file never changes under its name: the browser and the edge keep it for TMDB's six months.
        return new Response(upstream.body, {
            headers: {
                'Content-Type': upstream.headers.get('content-type') ?? 'image/jpeg',
                'Cache-Control': 'public, max-age=15552000, immutable',
                'X-Content-Type-Options': 'nosniff',
            },
        })
    }

    return async function handle(request: Request, env: Env): Promise<Response> {
        const url = new URL(request.url)
        if (request.method !== 'GET' && request.method !== 'HEAD') return new Response('Method not allowed', { status: 405, headers: { Allow: 'GET, HEAD' } })
        // The key's quota is the apps' to spend: another site's page may not use this proxy.
        if (request.headers.get('sec-fetch-site') === 'cross-site') return new Response('Forbidden', { status: 403 })
        if (url.pathname === '/tmdb/poster') return poster(request, url, env)
        if (url.pathname === '/tmdb/search') return search(url, env)
        if (url.pathname === '/tmdb/extras') return details(url, env)
        if (url.pathname.startsWith('/tmdb/img/')) return image(url)
        return new Response('Not found', { status: 404 })
    }
}
