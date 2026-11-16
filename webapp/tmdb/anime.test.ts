// The poster worker's anime provider: a name that reads like fansub anime asks AniList first (a
// show) or TMDB first (a film), AniList needs no key, a throttle of one provider does not stop the
// other, and the title's pictures and extras come by short paths from AniList's image host.
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { createHandler, type Deps } from './handler.ts'

const KEY = 'test-key-123'
const ORIGIN = 'https://web.example'
const COVER = 'https://s4.anilist.co/file/anilistcdn/media/anime/cover/large/bx20-abcDEF123456.jpg'

interface Seen {
    url: URL
    body: { query: string; variables: Record<string, unknown> } | null
}

function world(answer: (seen: Seen) => Response) {
    const calls: Seen[] = []
    const clock = { t: 1_000_000 }
    const deps: Deps = {
        fetch: async (request) => {
            const seen: Seen = { url: new URL(request.url), body: request.method === 'POST' ? JSON.parse(await request.text()) : null }
            calls.push(seen)
            return answer(seen)
        },
        now: () => clock.t,
        sleep: async (ms) => void (clock.t += ms),
    }
    return { calls, deps, clock }
}

const j = (body: unknown, status = 200, headers: Record<string, string> = {}) => new Response(JSON.stringify(body), { status, headers })
const get = (handle: ReturnType<typeof createHandler>, path: string, env: { TMDB_API_KEY?: string } = { TMDB_API_KEY: KEY }) => handle(new Request(ORIGIN + path), env)

const naruto = { id: 20, format: 'TV', seasonYear: 2002, startDate: { year: 2002 }, title: { romaji: 'Naruto', english: 'Naruto' }, synonyms: [], description: 'A ninja.', coverImage: { large: COVER }, bannerImage: null }
const aniSearch = (media: unknown[]) => j({ data: { Page: { media } } })
const tmdbShow = { id: 9, name: 'Naruto', original_name: 'Naruto', first_air_date: '2002-10-03', poster_path: '/tmdbposter01.jpg', original_language: 'ja' }

test('an anime show asks AniList first and answers with its short picture path and its source', async () => {
    const w = world((s) => (s.url.hostname === 'graphql.anilist.co' ? aniSearch([naruto]) : j({ results: [tmdbShow] })))
    const r = await get(createHandler(w.deps), '/tmdb/poster?k=show&t=Naruto&s=1&a=1')
    assert.equal(r.status, 200)
    assert.deepEqual(await r.json(), { match: true, id: '20', kind: 'show', title: 'Naruto', year: 2002, poster: '/al/cover/large/bx20-abcDEF123456.jpg', backdrop: null, overview: 'A ninja.', source: 'anilist' })
    assert.equal(w.calls.length, 1)
    assert.equal(w.calls[0].url.hostname, 'graphql.anilist.co')
    assert.equal(w.calls[0].body!.variables.search, 'Naruto')
})

test('a name that is not anime never asks AniList', async () => {
    const w = world((s) => (s.url.hostname === 'graphql.anilist.co' ? aniSearch([naruto]) : j({ results: [tmdbShow] })))
    const r = await get(createHandler(w.deps), '/tmdb/poster?k=show&t=Naruto&s=1')
    assert.equal((await r.json()).poster, '/tmdbposter01.jpg')
    assert.ok(w.calls.every((c) => c.url.hostname === 'api.themoviedb.org'))
})

test('an anime AniList does not know falls through to TMDB', async () => {
    const w = world((s) => (s.url.hostname === 'graphql.anilist.co' ? aniSearch([]) : j({ results: [tmdbShow] })))
    const body = await (await get(createHandler(w.deps), '/tmdb/poster?k=show&t=Naruto&s=1&a=1')).json()
    assert.equal(body.poster, '/tmdbposter01.jpg')
    assert.equal(body.source, undefined)
    assert.deepEqual(w.calls.map((c) => c.url.hostname), ['graphql.anilist.co', 'api.themoviedb.org'])
})

test('an anime film asks TMDB first, then AniList for a MOVIE', async () => {
    const movie = { ...naruto, id: 61, format: 'MOVIE', seasonYear: 2004, startDate: { year: 2004 }, title: { romaji: 'Naruto Movie', english: 'Naruto Movie' } }
    const w = world((s) => (s.url.hostname === 'graphql.anilist.co' ? aniSearch([naruto, movie]) : j({ results: [] })))
    const body = await (await get(createHandler(w.deps), '/tmdb/poster?k=film&t=Naruto+Movie&y=2004&a=1')).json()
    assert.equal(body.id, '61')
    assert.equal(body.kind, 'film')
    assert.equal(body.source, 'anilist')
    assert.equal(w.calls[0].url.hostname, 'api.themoviedb.org')
})

test('AniList needs no key: an anime found there is answered without the TMDB key, one it lacks is unavailable', async () => {
    const found = world(() => aniSearch([naruto]))
    const ok = await get(createHandler(found.deps), '/tmdb/poster?k=show&t=Naruto&s=1&a=1', {})
    assert.equal((await ok.json()).match, true)
    const none = world(() => aniSearch([]))
    const no = await get(createHandler(none.deps), '/tmdb/poster?k=show&t=Naruto&s=1&a=1', {})
    assert.equal(no.status, 503)
    assert.equal(no.headers.get('cache-control'), 'no-store')
    // A name that is not anime still needs the key, as before.
    assert.equal((await get(createHandler(none.deps), '/tmdb/poster?k=show&t=Naruto&s=1', {})).status, 503)
})

test('a throttled AniList is waited out alone: TMDB is still asked, and AniList is not asked again until its time', async () => {
    const w = world((s) => (s.url.hostname === 'graphql.anilist.co' ? j({}, 429, { 'retry-after': '30' }) : j({ results: [tmdbShow] })))
    const handle = createHandler(w.deps)
    const first = await (await get(handle, '/tmdb/poster?k=show&t=Naruto&s=1&a=1')).json()
    assert.equal(first.poster, '/tmdbposter01.jpg')
    const asked = w.calls.filter((c) => c.url.hostname === 'graphql.anilist.co').length
    await get(handle, '/tmdb/poster?k=show&t=Other+Show&s=1&a=1')
    assert.equal(w.calls.filter((c) => c.url.hostname === 'graphql.anilist.co').length, asked)
    w.clock.t += 31_000
    await get(handle, '/tmdb/poster?k=show&t=Third+Show&s=1&a=1')
    assert.equal(w.calls.filter((c) => c.url.hostname === 'graphql.anilist.co').length, asked + 1)
})

test('AniList down and nothing from TMDB: unavailable and never kept, not "nothing matched"', async () => {
    const w = world((s) => (s.url.hostname === 'graphql.anilist.co' ? j({}, 500) : j({ results: [] })))
    const m = { cache: new Map<string, Response>() }
    w.deps.cache = { match: async (r) => m.cache.get(r.url)?.clone(), put: async (r, res) => void m.cache.set(r.url, res) }
    const r = await get(createHandler(w.deps), '/tmdb/poster?k=show&t=Naruto&s=1&a=1')
    assert.equal(r.status, 503)
    assert.equal(m.cache.size, 0)
})

test('a server error pauses AniList for a while: no second ask into an outage, TMDB still answers, and it is asked again after', async () => {
    const w = world((s) => (s.url.hostname === 'graphql.anilist.co' ? j({}, 503) : j({ results: [tmdbShow] })))
    const handle = createHandler(w.deps)
    const first = await (await get(handle, '/tmdb/poster?k=show&t=Naruto&s=1&a=1')).json()
    assert.equal(first.poster, '/tmdbposter01.jpg')
    assert.equal(w.calls.filter((c) => c.url.hostname === 'graphql.anilist.co').length, 1, 'not retried at once')
    await get(handle, '/tmdb/poster?k=show&t=Other+Show&s=1&a=1')
    assert.equal(w.calls.filter((c) => c.url.hostname === 'graphql.anilist.co').length, 1, 'not asked while paused')
    w.clock.t += 31_000
    await get(handle, '/tmdb/poster?k=show&t=Third+Show&s=1&a=1')
    assert.equal(w.calls.filter((c) => c.url.hostname === 'graphql.anilist.co').length, 2)
})

test('garbage from AniList is no match, never a crash', async () => {
    for (const body of ['not json', '{"data":', 'null', '[]', '{"data":{"Page":{"media":"x"}}}', '{"data":{"Page":{"media":[null,1,{"id":"a"}]}}}']) {
        const w = world((s) => (s.url.hostname === 'graphql.anilist.co' ? new Response(body) : j({ results: [] })))
        const r = await get(createHandler(w.deps), '/tmdb/poster?k=show&t=Naruto&s=1&a=1')
        assert.ok(r.status === 200 || r.status === 503, body)
        if (r.status === 200) assert.equal((await r.json()).match, false)
    }
})

test('an anime title\'s extras come from AniList by its id, with no key', async () => {
    const w = world(() => j({ data: { Media: { genres: ['Action'], averageScore: 79, duration: 23, trailer: { id: 'j2hiC9BmJlQ', site: 'youtube' }, characters: { edges: [] } } } }))
    const r = await get(createHandler(w.deps), '/tmdb/extras?k=show&id=20&a=1&r=US', {})
    const body = await r.json()
    assert.equal(body.found, true)
    assert.equal(body.rating, 7.9)
    assert.equal(body.trailer, 'j2hiC9BmJlQ')
    assert.equal(w.calls[0].body!.variables.id, 20)
    assert.ok(w.calls.every((c) => c.url.hostname === 'graphql.anilist.co'))
})

test('an AniList picture is served from AniList\'s image host by its short path, and nothing else is', async () => {
    const w = world(() => new Response('jpeg', { headers: { 'content-type': 'image/jpeg' } }))
    const handle = createHandler(w.deps)
    const ok = await get(handle, '/tmdb/img/w342/al/cover/large/bx20-abcDEF123456.jpg')
    assert.equal(ok.status, 200)
    assert.equal(w.calls[0].url.href, COVER)
    for (const bad of ['/tmdb/img/w342/al/../../etc/passwd', '/tmdb/img/w342/al/cover/large/x.svg', '/tmdb/img/w342/al/media/anime/cover/large/a1.jpg', '/tmdb/img/w999/al/cover/large/bx20-abcDEF123456.jpg']) {
        assert.equal((await get(handle, bad)).status, 404, bad)
    }
    assert.equal(w.calls.length, 1)
})
