// /tmdb/anime: the poster worker's anime episode guide over mocked providers (the apps' AnimeEpisodesTest
// "over a fake network"): which source is read in which order, how many calls a long show costs, a
// guide cut short by the request's budget carrying on in the next, a wrong match left out, a throttle
// paused on its own, and nothing kept that was not whole.
import assert from 'node:assert/strict'
import { test } from 'node:test'
import * as f from './anime-fixtures.ts'
import { ANIME_CALLS, createHandler, type Deps } from './handler.ts'
import { readAnswer } from '../src/animeGuide.ts'

const KEY = 'test-key-123'
const ORIGIN = 'https://web.example'

interface World {
    tmdb: boolean
    kitsu: boolean
    jikan: boolean
    absoluteGroup: boolean
    sizes: number[]
    run: Record<string, unknown>
    /** Calls to Kitsu's episode pages that fail from the n-th on (a dropped connection). */
    kitsuDownAfter: number
}

function world(over: Partial<World> = {}) {
    const w: World = { tmdb: true, kitsu: true, jikan: true, absoluteGroup: false, sizes: f.seasonSizes, run: {}, kitsuDownAfter: Infinity, ...over }
    const urls: URL[] = []
    const clock = { t: 1_000_000 }
    const cache = new Map<string, Response>()
    let kitsuPages = 0
    const j = (body: unknown, status = 200, headers: Record<string, string> = {}) => new Response(JSON.stringify(body), { status, headers })
    const deps: Deps = {
        fetch: async (request) => {
            const url = new URL(request.url)
            urls.push(url)
            if (url.hostname === 'graphql.anilist.co') return j(f.aniRun(w.run))
            if (url.hostname === 'api.themoviedb.org') {
                if (!w.tmdb) return j({}, 404)
                if (url.pathname === '/3/search/tv') return j({ results: [{ id: f.TMDB, name: 'Naruto Shippuden', original_name: 'ナルト 疾風伝', first_air_date: '2007-02-15', original_language: 'ja' }] })
                if (url.pathname.startsWith('/3/tv/episode_group/')) return j(f.tmdbGroup(w.sizes))
                if (url.pathname === `/3/tv/${f.TMDB}`) {
                    const append = url.searchParams.get('append_to_response') ?? ''
                    if (append.includes('season/')) return j(Object.fromEntries(append.split(',').map((a) => [a, f.tmdbSeason(Number(a.slice(7)), w.sizes)])))
                    return j(f.tmdbShow(w.sizes, w.absoluteGroup))
                }
            }
            if (url.hostname === 'kitsu.io') {
                if (!w.kitsu) return j({}, 404)
                if (url.pathname.endsWith('/mappings')) return j(f.kitsuMapping())
                if (kitsuPages++ >= w.kitsuDownAfter) throw new Error('dropped')
                return j(f.kitsuPage(Number(url.searchParams.get('page[offset]'))))
            }
            if (url.hostname === 'api.jikan.moe') return w.jikan ? j(f.jikanPage(Number(url.searchParams.get('page')))) : j({}, 404)
            return j({}, 404)
        },
        cache: { match: async (r) => cache.get(r.url)?.clone(), put: async (r, res) => void cache.set(r.url, res) },
        now: () => clock.t,
        sleep: async (ms) => void (clock.t += ms),
    }
    return { w, urls, deps, clock, cache, handle: createHandler(deps) }
}

const ask = (h: ReturnType<typeof world>, id = f.ANILIST, env: { TMDB_API_KEY?: string } = { TMDB_API_KEY: KEY }) => h.handle(new Request(`${ORIGIN}/tmdb/anime?id=${id}`), env)
const count = (h: ReturnType<typeof world>, host: string, part = '') => h.urls.filter((u) => u.hostname === host && (u.pathname + u.search).includes(part)).length
const read = async (r: Response) => readAnswer(await r.json())!

test('the absolute group is taken first and names every episode; Kitsu and Jikan are not asked', async () => {
    const h = world({ absoluteGroup: true })
    const r = await ask(h)
    assert.equal(r.status, 200)
    const { guide, complete } = await read(r)
    assert.equal(complete, true)
    assert.equal(guide.entries.length, 500)
    assert.ok(guide.entries.every((e) => e.name === f.title(e.absolute) && e.source === 'group'))
    assert.deepEqual(guide.locate(123), f.place(123))
    assert.equal(guide.entry(123)!.still, f.stillOf(123))
    assert.equal(count(h, 'kitsu.io') + count(h, 'api.jikan.moe'), 0)
    assert.match(r.headers.get('cache-control')!, /s-maxage=86400/)
})

test('without a group TMDB\'s seasons are counted in order in two appended calls, not 21', async () => {
    const h = world()
    const { guide } = await read(await ask(h))
    assert.equal(guide.entries.length, 500)
    assert.ok(guide.entries.every((e) => e.source === 'seasons'))
    assert.equal(count(h, 'api.themoviedb.org', 'append_to_response=language'), 0)
    assert.equal(h.urls.filter((u) => u.searchParams.get('append_to_response')?.startsWith('season/')).length, 2)
    assert.equal(guide.entry(500)!.name, f.title(500))
})

test('the show is found by the names AniList holds, the way the apps do', async () => {
    const h = world()
    await ask(h)
    const searched = h.urls.filter((u) => u.pathname === '/3/search/tv').map((u) => u.searchParams.get('query'))
    // The first name TMDB's listing does not carry; the second is its original name, so no more are tried.
    assert.deepEqual(searched, ['Naruto: Shippuuden', 'ナルト 疾風伝'])
})

test('with no TMDB key Kitsu answers by the MyAnimeList id: one mapping call, 25 pages of twenty', async () => {
    const h = world({ tmdb: false })
    const { guide, complete } = await read(await ask(h, f.ANILIST, {}))
    assert.equal(complete, true)
    assert.equal(guide.entries.length, 500)
    assert.ok(guide.entries.every((e) => e.source === 'kitsu'))
    assert.equal(guide.entry(123)!.name, f.kitsuTitle(123))
    assert.equal(guide.entry(123)!.still, null, 'Kitsu\'s picture is never one the page draws')
    assert.equal(count(h, 'kitsu.io', 'mappings'), 1)
    assert.equal(count(h, 'kitsu.io', 'episodes'), 25)
    assert.equal(count(h, 'api.jikan.moe'), 0)
    assert.equal(guide.locate(123), null)
})

test('without TMDB or Kitsu, Jikan gives titles and dates in five pages of a hundred', async () => {
    const h = world({ tmdb: false, kitsu: false })
    const { guide } = await read(await ask(h))
    assert.ok(guide.entries.every((e) => e.source === 'jikan'))
    assert.equal(guide.entry(300)!.name, f.jikanTitle(300))
    assert.equal(guide.entry(300)!.airDate, f.date(300))
    assert.equal(count(h, 'api.jikan.moe'), 5)
})

test('AniList\'s streaming list is the last resort', async () => {
    const h = world({ tmdb: false, kitsu: false, jikan: false, run: { episodes: 40, streamingEpisodes: Array.from({ length: 40 }, (_, i) => ({ title: `Episode ${i + 1} - ${f.title(i + 1)}` })) } })
    const { guide } = await read(await ask(h))
    assert.equal(guide.entry(12)!.name, f.title(12))
    assert.equal(guide.entry(12)!.source, 'anilist')
    assert.equal(guide.entries.length, 40)
})

test('a later source fills only the titles an earlier one lacks', async () => {
    const short = f.seasonSizes.slice(0, -1).concat(f.seasonSizes.at(-1)! - 20)
    const h = world({ sizes: short })
    const { guide } = await read(await ask(h))
    assert.equal(guide.entry(100)!.source, 'seasons')
    assert.equal(guide.entry(490)!.name, f.kitsuTitle(490))
    assert.equal(guide.entry(490)!.source, 'kitsu')
})

test('a wrong match (another show of a similar name, 220 episodes against 500) is left out', async () => {
    const h = world({ sizes: [26, 26, 26, 26, 26, 26, 26, 26, 12], kitsu: false, jikan: false })
    const answer = await (await ask(h)).json()
    // The run is still known (500 declared), but none of the other show's titles or seasons are laid over it.
    assert.equal(answer.entries.length, 0)
    assert.equal(answer.layout.length, 0)
    assert.equal(answer.total, 500)
})

test('the whole answer is kept a day and asked for once; a guide that is not whole is never kept', async () => {
    const h = world({ absoluteGroup: true })
    await ask(h)
    const calls = h.urls.length
    const again = await ask(h)
    assert.equal(again.headers.get('x-poster-cache'), 'hit')
    assert.equal(h.urls.length, calls)
    const cut = world({ tmdb: false, jikan: false, kitsuDownAfter: 3 })
    const r = await ask(cut)
    assert.equal(r.headers.get('cache-control'), 'no-store')
    assert.ok(![...cut.cache.keys()].some((k) => k.includes('/tmdb/anime?')), 'only the parts were kept')
})

test('a long show is read across requests: the budget cuts one, the next carries on from what was kept', async () => {
    // Kitsu has a thousand episodes here: fifty pages, more than one request makes.
    const long = world({ tmdb: false, jikan: false, run: { episodes: 1000 } })
    const original = long.deps.fetch
    const pages: number[] = []
    long.deps.fetch = async (request) => {
        const url = new URL(request.url)
        if (url.hostname === 'kitsu.io' && url.pathname.endsWith('/episodes')) pages.push(Number(url.searchParams.get('page[offset]')))
        if (url.hostname === 'kitsu.io' && url.pathname.endsWith('/episodes')) return new Response(JSON.stringify(f.kitsuPage(Number(url.searchParams.get('page[offset]')), 1000)))
        return original(request)
    }
    const first = await read(await ask(long))
    assert.equal(first.complete, false)
    assert.ok(first.guide.entries.length > 0 && first.guide.entries.length < 1000)
    const callsFirst = pages.length
    assert.ok(callsFirst <= ANIME_CALLS && callsFirst > 20, `one request made ${callsFirst} calls`)
    const second = await read(await ask(long))
    assert.equal(second.complete, true)
    assert.equal(second.guide.entries.length, 1000)
    assert.equal(second.guide.entry(1000)!.name, f.kitsuTitle(1000))
    // Nothing was read twice: 1 mapping + 50 pages in all.
    assert.deepEqual(pages, Array.from({ length: 50 }, (_, i) => i * 20))
})

test('the pace is Kitsu\'s: 400 ms between calls, by the clock the worker keeps', async () => {
    const h = world({ tmdb: false })
    const before = h.clock.t
    await ask(h)
    assert.ok(h.clock.t - before >= 25 * 400)
})

test('a throttled Kitsu pauses on its own: the guide is cut short, Jikan still answers', async () => {
    const h = world({ tmdb: false })
    const original = h.deps.fetch
    h.deps.fetch = async (request) => (new URL(request.url).hostname === 'kitsu.io' && new URL(request.url).pathname.endsWith('/episodes') ? new Response('{}', { status: 429, headers: { 'retry-after': '30' } }) : original(request))
    const r = await read(await ask(h))
    assert.equal(r.complete, false)
    assert.ok(r.guide.entries.some((e) => e.source === 'jikan'))
})

test('AniList throttled or down is unavailable and never kept; a title it does not know is found: false', async () => {
    const down = world()
    const original = down.deps.fetch
    down.deps.fetch = async (request) => (new URL(request.url).hostname === 'graphql.anilist.co' ? new Response('{}', { status: 500 }) : original(request))
    const r = await ask(down)
    assert.equal(r.status, 503)
    assert.equal(r.headers.get('cache-control'), 'no-store')
    assert.equal(down.cache.size, 0)
    const unknown = world()
    unknown.deps.fetch = async () => new Response(JSON.stringify({ data: { Media: null } }))
    const none = await ask(unknown)
    assert.equal((await none.json()).found, false)
})

test('a TMDB throttle leaves the guide to the other sources, whole of them or cut short, and is not asked again until its time', async () => {
    const h = world()
    const original = h.deps.fetch
    h.deps.fetch = async (request) => (new URL(request.url).hostname === 'api.themoviedb.org' ? new Response('{}', { status: 429, headers: { 'retry-after': '20' } }) : original(request))
    const r = await read(await ask(h))
    assert.equal(r.complete, false)
    assert.equal(r.guide.entries.length, 500, 'Kitsu names every episode')
    const tmdbCalls = count(h, 'api.themoviedb.org')
    await ask(h)
    assert.equal(count(h, 'api.themoviedb.org'), tmdbCalls)
})

test('a bad question is a 400, and another site may not use the proxy', async () => {
    const h = world()
    for (const id of ['', 'x', '0', '-1', '1e9', '99999999999', '1;2']) assert.equal((await h.handle(new Request(`${ORIGIN}/tmdb/anime?id=${encodeURIComponent(id)}`), { TMDB_API_KEY: KEY })).status, 400, id)
    assert.equal((await h.handle(new Request(`${ORIGIN}/tmdb/anime`), {})).status, 400)
    const cross = await h.handle(new Request(`${ORIGIN}/tmdb/anime?id=1`, { headers: { 'sec-fetch-site': 'cross-site' } }), {})
    assert.equal(cross.status, 403)
    assert.equal(h.urls.length, 0)
})
