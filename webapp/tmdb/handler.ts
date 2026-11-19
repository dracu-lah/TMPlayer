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
//        { found: true, ... } or { found: false }; 503 as above. extras.ts holds the rules. A show TMDB lists
//        as one season that one of its episode groups splits (SeasonSplit) answers the split's seasons, with
//        TMDB's own beside (`seasonGroup`, `listedSeasons`, `listedNext`); a group that could not be read
//        answers TMDB's own seasons and is not kept.
//   GET /tmdb/season?id=<tmdb id>&s=<season>[&g=<episode group id>]
//        One season's episodes, each with its name, words, still, day and length: TMDB's own season, or
//        season s of the split the group g makes. { found: true, episodes } or { found: false }; 503 as above.
//   Anime (anilist.ts): a question with a=1 (the name reads like fansub anime, MetaQuery.anime) asks AniList
//        first for a show and TMDB first for a film, as OnlineMetadata.resolve does; AniList needs no key.
//        The answer then also says `source: "anilist"` when AniList matched; its pictures are "/al/<tail>",
//        and /tmdb/extras?...&a=1 asks AniList by that id. Each provider is paused on its own after a throttle.
//   GET /tmdb/anime?id=<anilist id>
//        An anime's episode guide (AnimeEpisodes, src/animeGuide.ts): the run's episodes numbered straight
//        through ("Show - 123"), each with a title, words, a TMDB still and a date where a source has
//        them, TMDB's seasons for the layout and the next episode to air. Sources in order: TMDB's
//        "Absolute order" group or its seasons, Kitsu, Jikan, AniList's streaming list; a later one only
//        while the earlier left titles missing. { found: true, complete, total, entries, ... } or
//        { found: false }; 503 as above. A long show is many calls, so one request makes at most
//        ANIME_CALLS of them and a few seconds' worth; what came is kept at the edge per source and the
//        answer says `complete: false` (and is not kept itself) until every source has been read: the
//        page asks again and the next request carries on from there.
//   GET /tmdb/skip?id=<anilist id>&e=<episode>&len=<seconds>
//        An anime episode's skip times (SkipTimesApi.aniSkip): the opening, ending and recap AniSkip holds for the
//        MyAnimeList id of the AniList title, matched to the file's length. { segments: [{ kind, start, end }] }
//        in seconds, empty when nobody timed it; 503 as above when a source was throttled or down. Kept a day.
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
import { GROUP_ID, applySplit, buildSplit, chooseGroup, episodeGroups, extras, splitEpisode, type Extras, type SeasonStop } from './extras.ts'
import { mergeChoices } from './choices.ts'
import { best, accepts, normalise, parseHints, trusted, type Ask, type Candidate } from './match.ts'
import * as guide from '../src/animeGuide.ts'
import * as src from './anime-sources.ts'
import * as skipSrc from './skiptimes.ts'
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
/** How long AniList is left alone after a server error (a 429 names its own time, up to 600 s). */
const ANILIST_BACKOFF = 30_000
/** One /tmdb/anime request makes at most this many calls (Pages allows 50 subrequests), over at most this long. */
export const ANIME_CALLS = 36
export const ANIME_TIME_MS = 15_000
/** KitsuApi.GAP_MS, JikanApi.GAP_MS and their DEFAULT_WAIT_MS: the spacing of calls and the pause after a throttle. */
const GAPS = { kitsu: 400, jikan: 1_100, aniskip: 500, idmap: 700 } as const
type Plain = keyof typeof GAPS
const PLAIN_WAIT = 30_000
/** OnlineMetadata.MISSING_TITLES_OK and TMDB_NAMES. */
const MISSING_TITLES_OK = 2
const TMDB_NAMES = 4
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
        if (!response.ok) {
            // An outage is not asked again at once: AniList is left alone for a short while (the retry below does not double it).
            aniPausedUntil = now() + ANILIST_BACKOFF
            return { kind: 'failed' }
        }
        try {
            return { kind: 'ok', json: await response.json() }
        } catch {
            return { kind: 'failed' }
        }
    }

    /** One more try after a second for a dropped connection or a server error, then give up. */
    async function aniRetrying(query: string, variables: Record<string, unknown>): Promise<Reply> {
        const first = await aniCall(query, variables)
        // A server error paused AniList (aniCall): no second ask into an outage. A dropped connection is tried once more.
        if (first.kind !== 'failed' || now() < aniPausedUntil) return first
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
        const hints = parseHints(p.get('h'))
        // A pick names its title by id; "picked" without one is no question.
        const picked = p.get('p') === '1'
        if (picked && id === null) return null
        const anime = p.get('a') === '1'
        const imdb = p.get('i')
        const imdbId = id === null && imdb && /^tt\d{7,9}$/.test(imdb) ? imdb : null
        return { title, year, kind, tmdbId: id, series: p.get('s') === '1', languages, ...(hints.length ? { hints } : {}), ...(imdbId ? { imdbId } : {}), ...(picked ? { picked } : {}), ...(anime ? { anime } : {}) }
    }

    /** What the cache files an answer under: the cleaned title, as MetaQuery.cacheKey does, never the raw one. */
    function cacheRequest(origin: string, ask: Ask): Request {
        const q = new URLSearchParams({ k: ask.kind, t: normalise(ask.title), y: String(ask.year ?? ''), id: String(ask.tmdbId ?? ''), s: ask.series ? '1' : '0', l: ask.languages.join(','), p: ask.picked ? '1' : '0', ...(ask.hints?.length && ask.tmdbId === null ? { h: [...ask.hints].sort().join(',') } : {}), ...(ask.imdbId ? { i: ask.imdbId } : {}), ...(ask.anime ? { a: '1' } : {}) })
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
        // v3: with the season split (v2: the title logo); answers kept before it are asked again rather than shown without.
        const p = new URLSearchParams({ v: '3', k: q.film ? 'film' : 'show', id: String(q.id), s: String(q.season ?? ''), e: String(q.episode ?? ''), r: q.region })
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

    /** One episode group's episodes, kept a day with the anime guide's (part "tmdbgroup"); "stopped" for a throttle, a refusal or a dropped call. */
    async function groupEpisodes(key: string, origin: string, id: string, rememberMissing = false): Promise<{ kind: 'ok'; episodes: guide.GroupEpisode[] } | { kind: 'missing' } | { kind: 'stopped' }> {
        const kept = await readPart<guide.GroupEpisode[]>(origin, 'tmdbgroup', id)
        if (Array.isArray(kept)) return { kind: 'ok', episodes: kept }
        const reply = await retrying(key, `tv/episode_group/${encodeURIComponent(id)}`, 'language=en-US')
        if (reply.kind === 'ok') {
            const episodes = src.groupOf(reply.json)
            await savePart(origin, 'tmdbgroup', id, episodes)
            return { kind: 'ok', episodes }
        }
        if (reply.kind === 'missing') {
            // A group TMDB lacks is kept as an empty one (for the season endpoint), so another season of it asks nothing.
            if (rememberMissing) await savePart(origin, 'tmdbgroup', id, [])
            return { kind: 'missing' }
        }
        return { kind: 'stopped' }
    }

    /** One season's episodes (TMDB's own, or season `s` of the split group `g` makes), as the series page lists them. */
    /** Uncached /tmdb/season asks that reach TMDB, per minute: one visitor's, and all together (this isolate's). */
    const SEASON_PER_IP = 30
    const SEASON_ALL = 120
    const seasonAsks = { all: [] as number[], byIp: new Map<string, number[]>() }

    /** Counts one uncached ask; the wait in seconds when the visitor or everyone is over the minute's budget, else 0. */
    function seasonBudget(ip: string): number {
        const t = now()
        const recent = (list: number[]) => list.filter((x) => t - x < 60_000)
        seasonAsks.all = recent(seasonAsks.all)
        if (seasonAsks.byIp.size > 2000) seasonAsks.byIp.clear()
        const mine = recent(seasonAsks.byIp.get(ip) ?? [])
        if (mine.length >= SEASON_PER_IP || seasonAsks.all.length >= SEASON_ALL) {
            seasonAsks.byIp.set(ip, mine)
            return Math.max(1, Math.ceil((60_000 - (t - ((mine.length >= SEASON_PER_IP ? mine : seasonAsks.all)[0] ?? t))) / 1000))
        }
        mine.push(t)
        seasonAsks.byIp.set(ip, mine)
        seasonAsks.all.push(t)
        return 0
    }

    async function seasonAnswer(request: Request, url: URL, env: Env): Promise<Response> {
        const p = url.searchParams
        const num = (name: string, lo: number, hi: number) => {
            const n = Number(p.get(name))
            return p.get(name) !== null && p.get(name) !== '' && Number.isInteger(n) && n >= lo && n <= hi ? n : NaN
        }
        const id = num('id', 1, 999_999_999)
        const g = p.get('g')
        // A group's seasons are as many as it holds; TMDB's own seasons of a show stay within 60.
        const s = num('s', 0, g !== null ? guide.MAX_SEASONS : 60)
        if (Number.isNaN(id) || Number.isNaN(s) || (g !== null && !GROUP_ID.test(g))) return json({ error: 'bad request' }, 400)
        const key = env.TMDB_API_KEY?.trim()
        if (!key) return unavailable('no key', 300)
        // A group's season does not depend on the show: its key leaves the id out, so a made up id adds nothing.
        const cacheKey = new Request(`${url.origin}/tmdb/season?${new URLSearchParams(g !== null ? { v: '2', s: String(s), g } : { v: '2', id: String(id), s: String(s) })}`)
        const kept = await deps.cache?.match(cacheKey).catch(() => undefined)
        if (kept) {
            const headers = new Headers(kept.headers)
            headers.set('X-Poster-Cache', 'hit')
            return new Response(kept.body, { status: kept.status, headers })
        }
        if (now() < pausedUntil) return unavailable('throttled', (pausedUntil - now()) / 1000)
        if (refusedAt && now() - refusedAt < REFUSED_RETRY) return unavailable('refused', (REFUSED_RETRY - (now() - refusedAt)) / 1000)
        // What is not kept reaches TMDB: a visitor and everyone together have a budget, so varying the question cannot spend the key.
        const wait = seasonBudget(request.headers.get('cf-connecting-ip') ?? request.headers.get('x-forwarded-for') ?? '')
        if (wait > 0) return json({ error: 'throttled' }, 429, { 'Cache-Control': 'no-store', 'Retry-After': String(wait) })
        const stop = (r: Reply) => {
            const out = stopped(r)
            return unavailable(out.kind === 'blocked' ? out.reason : 'unavailable', out.kind === 'blocked' ? out.waitS : 30)
        }
        let episodes: guide.ListedEpisode[] | null = null
        if (g !== null) {
            const read = await groupEpisodes(key, url.origin, g, true)
            if (read.kind === 'stopped') return unavailable('unavailable')
            // The split of this group alone: the page's extras answer already judged it against the show's own count.
            episodes = read.kind === 'ok' ? (buildSplit(g, read.episodes, [])?.episodes.get(s) ?? null) : null
        } else {
            const reply = await retrying(key, `tv/${id}/season/${s}`, 'language=en-US')
            if (reply.kind === 'ok') episodes = src.seasonOf(reply.json)
            else if (reply.kind !== 'missing') return stop(reply)
        }
        refusedAt = 0
        // A season airs on: its names and days come in, so it is kept a day, not the weeks of a title's page. "Not found" for an hour.
        const response = json(episodes ? { found: true, episodes } : { found: false }, 200, { 'Cache-Control': `public, max-age=3600, s-maxage=${episodes ? DAY : 3600}`, 'X-Poster-Cache': 'miss' })
        if (deps.cache) {
            const put = deps.cache.put(cacheKey, response.clone()).catch(() => {})
            deps.later?.(put)
        }
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
        const append = film ? 'credits,videos,release_dates,images' : 'aggregate_credits,videos,content_ratings,external_ids,images,episode_groups'
        const reply = await retrying(key, `${film ? 'movie' : 'tv'}/${id}`, `language=en-US&include_video_language=en,null&include_image_language=en,null&append_to_response=${append}`)
        const blocked = (r: Reply) => {
            const out = stopped(r)
            return unavailable(out.kind === 'blocked' ? out.reason : 'unavailable', out.kind === 'blocked' ? out.waitS : 30)
        }
        let body: { found: false } | Extras = { found: false }
        let ttl = MISS_TTL
        if (reply.kind === 'ok') {
            let found = extras(reply.json, film, region)
            body = found
            ttl = FOUND_TTL
            // A show TMDB lists as one season that an episode group splits (SeasonSplit): one more call, its answer kept a day.
            let split: ReturnType<typeof buildSplit> = null
            let cut = false
            const chosen = film ? null : chooseGroup(found.seasonList, episodeGroups(reply.json))
            if (chosen) {
                const read = await groupEpisodes(key, url.origin, chosen.id)
                if (read.kind === 'ok') split = buildSplit(chosen.id, read.episodes, found.seasonList)
                else if (read.kind === 'stopped') cut = true
            }
            if (split) found = body = applySplit(found, split)
            if (!film && season !== null && episode !== null) {
                // The episode's own name and words (MetaApis.episodes), only for the one the page is about; the split has its own.
                const own = split ? splitEpisode(split, season, episode) : undefined
                const one = own ? null : await retrying(key, `tv/${id}/season/${season}/episode/${episode}`, 'language=en-US')
                if (own) found.episode = own
                else if (one?.kind === 'ok') {
                    const still = typeof one.json.still_path === 'string' && FILE.test(one.json.still_path) ? one.json.still_path : null
                    found.episode = { name: String(one.json.name ?? '').trim(), overview: String(one.json.overview ?? '').trim(), still }
                } else if (one && one.kind !== 'missing') return blocked(one)
            }
            // The group could not be read (throttled, dropped): TMDB's own seasons stand for now, and nothing is kept.
            if (cut) {
                refusedAt = 0
                // Without `groupsRead`: this answer did not know the groups, so a page that kept it would ask again (and never keep it, no-store).
                const { groupsRead: _unread, ...unsettled } = body as Extras
                return json(unsettled, 200, { 'Cache-Control': 'no-store', 'X-Poster-Cache': 'miss' })
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

    // ---- an anime's episode guide (src/animeGuide.ts), kept per source so a long show carries on ----

    const plainPaused: Record<Plain, number> = { kitsu: 0, jikan: 0, aniskip: 0, idmap: 0 }
    const plainLast: Record<Plain, number> = { kitsu: 0, jikan: 0, aniskip: 0, idmap: 0 }

    /** A GET to Kitsu, Jikan, AniSkip or the id map (no key): spaced by their gap, paused on its own after a throttle, one more try after a dropped call. */
    async function plain(provider: Plain, url: string, accept: string): Promise<Reply> {
        if (now() < plainPaused[provider]) return { kind: 'throttled', waitMs: plainPaused[provider] - now() }
        const once = async (): Promise<Reply> => {
            const wait = plainLast[provider] + GAPS[provider] - now()
            if (wait > 0) await sleep(wait)
            plainLast[provider] = now()
            let response: Response
            try {
                response = await deps.fetch(new Request(url, { headers: { Accept: accept, 'User-Agent': 'TMPlayer-web-posters' } }))
            } catch {
                return { kind: 'failed' }
            }
            if (response.status === 429) {
                const seconds = Number(response.headers.get('retry-after'))
                const waitMs = seconds > 0 ? Math.min(600, Math.max(1, seconds)) * 1000 : PLAIN_WAIT
                plainPaused[provider] = now() + waitMs
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
        const first = await once()
        if (first.kind !== 'failed') return first
        await sleep(RETRY)
        return once()
    }

    /** What the cache files one source's progress under: kept a day, so a guide cut short resumes where it stopped. */
    const partRequest = (origin: string, part: string, id: string) => new Request(`${origin}/tmdb/anime-part?${new URLSearchParams({ v: '1', part, id })}`)

    async function readPart<T>(origin: string, part: string, id: string): Promise<T | null> {
        const hit = await deps.cache?.match(partRequest(origin, part, id)).catch(() => undefined)
        if (!hit) return null
        return ((await hit.json().catch(() => null)) as T | null) ?? null
    }

    async function savePart(origin: string, part: string, id: string, value: unknown): Promise<void> {
        if (!deps.cache) return
        await deps.cache.put(partRequest(origin, part, id), json(value, 200, { 'Cache-Control': `public, s-maxage=${DAY}` })).catch(() => {})
    }

    /** A source read page by page, kept as far as it got (a long show is dozens of calls, more than one request makes). */
    interface Progress {
        items: guide.ListedEpisode[]
        next: number
        done: boolean
    }

    /** AniList's facts for the run `id` (its length, next episode, MyAnimeList id, streaming list), kept a day; an error answer when AniList cannot be asked. */
    async function animeRun(origin: string, id: number): Promise<src.Run | { none: true } | Response> {
        const kept = await readPart<src.Run | { none: true }>(origin, 'run', String(id))
        if (kept && typeof kept === 'object') return kept
        if (now() < aniPausedUntil) return unavailable('throttled', (aniPausedUntil - now()) / 1000)
        const reply = await aniRetrying(src.EPISODES_QUERY, { id })
        if (reply.kind === 'throttled') return unavailable('throttled', reply.waitMs / 1000)
        if (reply.kind === 'failed' || reply.kind === 'refused') return unavailable('unavailable')
        const run = (reply.kind === 'ok' ? src.runOf(reply.json) : null) ?? { none: true as const }
        await savePart(origin, 'run', String(id), run)
        return run
    }

    /**
     * An anime episode's skip times (SkipTimesApi.aniSkip by MyAnimeList id, OnlineMetadata.animeSkips): the
     * title's MyAnimeList id from AniList, else the id map's (arm.haglund.dev), then AniSkip's opening, ending
     * and recap for the episode, matched to the file's length `len` (0 takes any). anime-skip.com is not read
     * (it wants a client id). The answer, an empty one too, is kept a day; a source that was throttled or down
     * makes it a 503 that is not kept, so "nobody timed this" is never remembered by mistake.
     */
    async function skip(url: URL): Promise<Response> {
        const p = url.searchParams
        const num = (name: string, lo: number, hi: number) => {
            const raw = p.get(name)
            return raw !== null && /^\d{1,9}$/.test(raw) && Number(raw) >= lo && Number(raw) <= hi ? Number(raw) : NaN
        }
        const id = num('id', 1, 999_999_999)
        const episode = num('e', 1, guide.MAX_NUMBER)
        const len = p.get('len') === null ? 0 : num('len', 0, 86_400)
        if (Number.isNaN(id) || Number.isNaN(episode) || Number.isNaN(len)) return json({ error: 'bad request' }, 400)
        const cacheKey = new Request(`${url.origin}/tmdb/skip?${new URLSearchParams({ v: '1', id: String(id), e: String(episode), len: String(len) })}`)
        const hit = await deps.cache?.match(cacheKey).catch(() => undefined)
        if (hit) {
            const headers = new Headers(hit.headers)
            headers.set('X-Poster-Cache', 'hit')
            return new Response(hit.body, { status: hit.status, headers })
        }
        const run = await animeRun(url.origin, id)
        if (run instanceof Response) return run
        let complete = true
        let mal = 'none' in run ? null : run.malId
        if (!mal && !('none' in run)) {
            const ids = await readPart<{ mal: string | null }>(url.origin, 'idmap', String(id))
            if (ids) {
                mal = typeof ids.mal === 'string' ? ids.mal : null
            } else {
                const reply = await plain('idmap', `${skipSrc.IDMAP}/ids?source=anilist&id=${id}`, 'application/json')
                if (reply.kind === 'ok') mal = skipSrc.malOfIdMap(reply.json)
                if (reply.kind === 'ok' || reply.kind === 'missing') await savePart(url.origin, 'idmap', String(id), { mal })
                else complete = false
            }
        }
        let segments: skipSrc.Segment[] = []
        if (mal && /^\d{1,9}$/.test(mal)) {
            const types = ['op', 'ed', 'mixed-op', 'mixed-ed', 'recap'].map((t) => `&types=${t}`).join('')
            const reply = await plain('aniskip', `${skipSrc.ANISKIP}/skip-times/${mal}/${episode}?episodeLength=${len}${types}`, 'application/json')
            if (reply.kind === 'ok') segments = skipSrc.aniSkipOf(reply.json)
            else if (reply.kind !== 'missing') complete = false
        }
        if (!complete && !segments.length) return unavailable('unavailable')
        // A source that failed beside one that answered: shown, not kept, so the rest is asked again.
        const response = json({ segments }, 200, complete ? { 'Cache-Control': `public, max-age=3600, s-maxage=${DAY}`, 'X-Poster-Cache': 'miss' } : { 'Cache-Control': 'no-store', 'X-Poster-Cache': 'miss' })
        if (deps.cache && complete) {
            const put = deps.cache.put(cacheKey, response.clone()).catch(() => {})
            deps.later?.(put)
        }
        return response
    }

    /** One show's guide, as the apps' OnlineMetadata.buildGuide builds it, within this request's calls. */
    async function anime(url: URL, env: Env): Promise<Response> {
        const raw = url.searchParams.get('id')
        const id = raw !== null && /^\d{1,9}$/.test(raw) ? Number(raw) : NaN
        if (!Number.isInteger(id) || id < 1) return json({ error: 'bad request' }, 400)
        const finalKey = new Request(`${url.origin}/tmdb/anime?${new URLSearchParams({ v: '1', id: String(id) })}`)
        const kept = await deps.cache?.match(finalKey).catch(() => undefined)
        if (kept) {
            const headers = new Headers(kept.headers)
            headers.set('X-Poster-Cache', 'hit')
            return new Response(kept.body, { status: kept.status, headers })
        }
        const budget = { calls: 0, until: now() + ANIME_TIME_MS }
        /** Whether one more call may be made now: false once this request has made its share, and the guide is cut short. */
        const spend = () => {
            if (budget.calls >= ANIME_CALLS || now() > budget.until) return false
            budget.calls++
            return true
        }
        let complete = true
        const origin = url.origin
        const sid = String(id)

        // AniList's own facts: the run's length, the next to air, the MyAnimeList id, the streaming list.
        spend()
        const run = await animeRun(origin, id)
        if (run instanceof Response) return run
        if ('none' in run) {
            const response = json({ found: false }, 200, { 'Cache-Control': `public, max-age=3600, s-maxage=${DAY}`, 'X-Poster-Cache': 'miss' })
            if (deps.cache) {
                const put = deps.cache.put(finalKey, response.clone()).catch(() => {})
                deps.later?.(put)
            }
            return response
        }
        const facts = run
        const expected = facts.episodes ?? (facts.next && facts.next.episode > 1 ? facts.next.episode - 1 : null)
        const lists: guide.SourceList[] = []
        let counts: SeasonStop[] = []

        // TMDB: the show found by the provider's names, its seasons with their counts, then its "Absolute order" group or its seasons' episodes.
        const key = env.TMDB_API_KEY?.trim() || null
        const tmdbBlocked = !key || now() < pausedUntil || (refusedAt !== 0 && now() - refusedAt < REFUSED_RETRY)
        if (key && tmdbBlocked) complete = false
        if (key && !tmdbBlocked) {
            const part = await tmdbSide(origin, sid, key, facts.names, expected, spend)
            complete = complete && part.complete
            lists.push(...part.lists)
            counts = part.counts
        }

        const missingTitles = () => {
            const sofar = guide.merge(lists, counts, facts.next)
            const total = expected ?? sofar.total
            if (total <= 0) return true
            const named = sofar.entries.filter((e) => e.absolute <= total && e.name.trim()).length
            return total - named > Math.max(MISSING_TITLES_OK, Math.floor(total / 50))
        }

        if (facts.malId && missingTitles()) {
            const part = await kitsuSide(origin, facts.malId, expected, spend)
            complete = complete && part.complete
            if (part.list) lists.push(part.list)
        }
        if (facts.malId && missingTitles()) {
            const part = await jikanSide(origin, facts.malId, expected, spend)
            complete = complete && part.complete
            if (part.list) lists.push(part.list)
        }
        if (facts.streaming.length) lists.push({ source: 'anilist', pieces: facts.streaming })

        const built = guide.merge(lists, counts, facts.next, facts.episodes ?? 0)
        const answer = built.isEmpty ? { found: false } : guide.toAnswer(built, complete)
        // A guide cut short is shown but not kept, so a later request carries on; a whole one is kept a day (the apps' GUIDE_TTL).
        const response = json(answer, 200, complete ? { 'Cache-Control': `public, max-age=300, s-maxage=${DAY}`, 'X-Poster-Cache': 'miss' } : { 'Cache-Control': 'no-store', 'X-Poster-Cache': 'miss' })
        if (deps.cache && complete) {
            const put = deps.cache.put(finalKey, response.clone()).catch(() => {})
            deps.later?.(put)
        }
        return response
    }

    type TmdbPart = { lists: guide.SourceList[]; counts: SeasonStop[]; complete: boolean }

    /** OnlineMetadata.tmdbAnime: TMDB's lists and season counts for the show, within the calls left. */
    async function tmdbSide(origin: string, sid: string, key: string, names: string[], expected: number | null, spend: () => boolean): Promise<TmdbPart> {
        const none: TmdbPart = { lists: [], counts: [], complete: true }
        const cut: TmdbPart = { lists: [], counts: [], complete: false }
        let stored = await readPart<{ id: string | null }>(origin, 'tmdbid', sid)
        if (!stored) {
            let found: string | null = null
            for (const name of names.filter(Boolean).slice(0, TMDB_NAMES)) {
                if (!spend()) return cut
                const reply = await retrying(key, 'search/tv', `query=${encodeURIComponent(name)}&include_adult=false&language=en-US`)
                if (reply.kind === 'missing') continue
                if (reply.kind !== 'ok') {
                    stopped(reply)
                    return cut
                }
                const results: unknown[] = Array.isArray(reply.json?.results) ? reply.json.results.slice(0, 40) : []
                const list = results.filter((o) => o && typeof o === 'object').map((o) => candidate(false, o))
                const pick = best({ title: name, year: null, kind: 'show', tmdbId: null, series: true, languages: [] }, list, true)
                if (pick) {
                    found = pick.id
                    break
                }
            }
            stored = { id: found }
            await savePart(origin, 'tmdbid', sid, stored)
        }
        const tmdbId = stored.id
        if (!tmdbId || !/^\d{1,9}$/.test(tmdbId)) return none

        let show = await readPart<src.ShowGuide>(origin, 'tmdbshow', tmdbId)
        if (!show) {
            if (!spend()) return cut
            const reply = await retrying(key, `tv/${tmdbId}`, 'language=en-US&append_to_response=episode_groups')
            if (reply.kind === 'missing') return none
            if (reply.kind !== 'ok') {
                stopped(reply)
                return cut
            }
            show = src.showGuideOf(reply.json)
            await savePart(origin, 'tmdbshow', tmdbId, show)
        }
        const layout = guide.layout(show.seasons)
        const total = layout.reduce((n, s) => n + s.episodes, 0)
        const wrong = !guide.plausible(expected, { source: 'seasons', pieces: [{ absolute: Math.max(total, 1) }] })
        if (!layout.length || wrong) return none

        // The absolute order group, the one with the most episodes where TMDB has several.
        const group = show.groups.filter((g) => g.type === 2).reduce<(typeof show.groups)[number] | null>((a, g) => (!a || g.episodes > a.episodes ? g : a), null)
        if (group) {
            let episodes = await readPart<guide.GroupEpisode[]>(origin, 'tmdbgroup', group.id)
            if (!episodes) {
                if (!spend()) return { lists: [], counts: layout, complete: false }
                const reply = await retrying(key, `tv/episode_group/${encodeURIComponent(group.id)}`, 'language=en-US')
                if (reply.kind === 'ok') {
                    episodes = src.groupOf(reply.json)
                    await savePart(origin, 'tmdbgroup', group.id, episodes)
                } else if (reply.kind !== 'missing') {
                    stopped(reply)
                    return { lists: [], counts: layout, complete: false }
                }
            }
            const list = episodes ? guide.fromGroup(episodes) : null
            if (list && guide.plausible(expected, list)) return { lists: [list], counts: layout, complete: true }
        }

        // The seasons counted in order: one call for each twenty not yet kept.
        const kept = await readPart<{ seasons: Record<string, guide.ListedEpisode[]> }>(origin, 'tmdbseasons', tmdbId)
        const bySeason = new Map<number, guide.ListedEpisode[]>(Object.entries(kept?.seasons ?? {}).map(([n, list]) => [Number(n), list]))
        const needed = layout.map((s) => s.number).filter((n) => !bySeason.has(n))
        let complete = true
        for (let i = 0; i < needed.length; i += src.APPEND_LIMIT) {
            const chunk = needed.slice(i, i + src.APPEND_LIMIT)
            if (!spend()) {
                complete = false
                break
            }
            const reply = await retrying(key, `tv/${tmdbId}`, `language=en-US&append_to_response=${chunk.map((n) => `season/${n}`).join(',')}`)
            if (reply.kind === 'ok') {
                for (const [n, list] of src.seasonsOf(reply.json, chunk)) bySeason.set(n, list)
            } else if (reply.kind !== 'missing') {
                stopped(reply)
                complete = false
                break
            }
        }
        // What came is kept, a chunk at a time, so a show of many seasons carries on where it stopped.
        if (needed.length) await savePart(origin, 'tmdbseasons', tmdbId, { seasons: Object.fromEntries(bySeason) })
        return { lists: [guide.fromSeasons(bySeason, layout)], counts: layout, complete }
    }

    type Listing = { list: guide.SourceList | null; complete: boolean }

    /** KitsuApi: the anime by its MyAnimeList id, then its episodes twenty a page, kept as far as they got. */
    async function kitsuSide(origin: string, mal: string, expected: number | null, spend: () => boolean): Promise<Listing> {
        let stored = await readPart<{ id: string | null }>(origin, 'kitsuid', mal)
        if (!stored) {
            if (!spend()) return { list: null, complete: false }
            const reply = await plain('kitsu', `${src.KITSU}/mappings?filter%5BexternalSite%5D=myanimelist%2Fanime&filter%5BexternalId%5D=${encodeURIComponent(mal)}&include=item`, 'application/vnd.api+json')
            if (reply.kind === 'ok') stored = { id: src.kitsuIdOf(reply.json) }
            else if (reply.kind === 'missing') stored = { id: null }
            else return { list: null, complete: false }
            await savePart(origin, 'kitsuid', mal, stored)
        }
        const kitsuId = stored.id
        if (!kitsuId || !/^\d{1,12}$/.test(kitsuId)) return { list: null, complete: true }
        return paged('kitsu', origin, kitsuId, expected, spend, (offset) => `${src.KITSU}/anime/${kitsuId}/episodes?page%5Blimit%5D=${src.KITSU_PAGE}&page%5Boffset%5D=${offset}&sort=number`, 'application/vnd.api+json', src.kitsuPageOf, src.KITSU_PAGE, src.KITSU_MAX_PAGES, 0)
    }

    /** JikanApi: the episodes of the MyAnimeList id, a hundred a page, kept as far as they got. */
    function jikanSide(origin: string, mal: string, expected: number | null, spend: () => boolean): Promise<Listing> {
        return paged('jikan', origin, mal, expected, spend, (page) => `${src.JIKAN}/anime/${encodeURIComponent(mal)}/episodes?page=${page}`, 'application/json', src.jikanPageOf, 1, src.JIKAN_MAX_PAGES, 1)
    }

    /** A list read a page at a time from `start` (a page number or an offset), `step` further each page, carrying on from what an earlier request kept. */
    async function paged(
        provider: 'kitsu' | 'jikan',
        origin: string,
        id: string,
        expected: number | null,
        spend: () => boolean,
        address: (cursor: number) => string,
        accept: string,
        read: (json: unknown) => src.Page,
        step: number,
        maxPages: number,
        start: number,
    ): Promise<Listing> {
        const kept = await readPart<Progress>(origin, provider, id)
        const sound = kept && Array.isArray(kept.items) && Number.isInteger(kept.next) && kept.next >= start && kept.next < 1_000_000
        const progress: Progress = sound ? { items: kept.items.slice(0, guide.MAX_EPISODES), next: kept.next, done: kept.done === true } : { items: [], next: start, done: false }
        const wasDone = progress.done
        let pages = 0
        let first = progress.items.length === 0
        let finished = progress.done
        let cut = false
        while (!finished && pages < maxPages) {
            if (!spend()) {
                cut = true
                break
            }
            const reply = await plain(provider, address(progress.next), accept)
            if (reply.kind === 'ok') {
                const page = read(reply.json)
                progress.items.push(...page.list)
                progress.next += step
                pages++
                if (!page.more || progress.items.length >= guide.MAX_EPISODES) finished = true
            } else if (reply.kind === 'missing' && first) {
                finished = true
            } else {
                cut = true
                break
            }
            first = false
        }
        // A list read to its end is kept whole; a cut one is kept as far as it got, to carry on from.
        progress.done = finished
        if (pages > 0 || finished !== wasDone) await savePart(origin, provider, id, progress)
        const list = progress.items.length ? guide.fromNumbered(provider, progress.items) : null
        // A whole list must fit the run (a wrong show of a similar name is left out); a cut one is used as it is, for now.
        const usable = list && (!finished || guide.plausible(expected, list)) ? list : null
        return { list: usable, complete: finished && !cut }
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
        if (url.pathname === '/tmdb/season') return seasonAnswer(request, url, env)
        if (url.pathname === '/tmdb/anime') return anime(url, env)
        if (url.pathname === '/tmdb/skip') return skip(url)
        if (url.pathname.startsWith('/tmdb/img/')) return image(url)
        return new Response('Not found', { status: 404 })
    }
}
