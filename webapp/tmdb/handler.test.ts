// The poster worker over a mocked TMDB: the app's lookup order, what is cached and what is not, a
// throttle, a refused key, a missing key, and that the key is only ever sent to TMDB.
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { createHandler, FOUND_TTL, MISS_TTL, type Deps } from './handler.ts'

const KEY = 'test-key-123'
const ORIGIN = 'https://web.example'

interface Mock {
    calls: URL[]
    cache: Map<string, Response>
    deps: Deps
    clock: { t: number }
    /** The next answers by path prefix: a function of the request URL. */
    answer: (url: URL) => Response
}

function mock(answer: (url: URL) => Response): Mock {
    const m: Mock = { calls: [], cache: new Map(), clock: { t: 1_000_000 }, answer } as unknown as Mock
    m.deps = {
        fetch: async (request) => {
            const url = new URL(request.url)
            m.calls.push(url)
            return m.answer(url)
        },
        cache: {
            match: async (r) => m.cache.get(r.url)?.clone(),
            put: async (r, res) => void m.cache.set(r.url, res),
        },
        now: () => m.clock.t,
        sleep: async (ms) => void (m.clock.t += ms),
    }
    return m
}

const j = (body: unknown, status = 200, headers: Record<string, string> = {}) => new Response(JSON.stringify(body), { status, headers })
const get = (handle: ReturnType<typeof createHandler>, path: string, env: { TMDB_API_KEY?: string } = { TMDB_API_KEY: KEY }, headers: Record<string, string> = {}) =>
    handle(new Request(ORIGIN + path, { headers }), env)

const film = (id: number, title: string, year: number, poster: string | null = `/p${id}abcdefgh.jpg`, language = 'en') => ({
    id,
    title,
    original_title: title,
    release_date: `${year}-05-01`,
    poster_path: poster,
    original_language: language,
})
const show = (id: number, name: string, year: number) => ({ id, name, original_name: name, first_air_date: `${year}-01-10`, poster_path: `/s${id}abcdefgh.jpg`, original_language: 'en' })

test('a film is found by its title and year and answered with the poster path', async () => {
    const m = mock((u) => (u.pathname.endsWith('/search/movie') ? j({ results: [film(1, 'Clap', 2021), film(2, 'Clap', 2022)] }) : j({}, 500)))
    const handle = createHandler(m.deps)
    const r = await get(handle, '/tmdb/poster?k=film&t=Clap&y=2022')
    assert.equal(r.status, 200)
    assert.deepEqual(await r.json(), { match: true, id: '2', kind: 'film', title: 'Clap', year: 2022, poster: '/p2abcdefgh.jpg' })
    assert.equal(m.calls.length, 1)
    assert.equal(m.calls[0].searchParams.get('query'), 'Clap')
    assert.equal(m.calls[0].searchParams.get('year'), '2022')
    assert.equal(m.calls[0].searchParams.get('language'), 'en-US')
    assert.match(r.headers.get('cache-control')!, new RegExp(`s-maxage=${FOUND_TTL}`))
})

test('the key goes to TMDB alone, never back to the page', async () => {
    const m = mock(() => j({ results: [film(2, 'Clap', 2022)] }))
    const handle = createHandler(m.deps)
    const r = await get(handle, '/tmdb/poster?k=film&t=Clap&y=2022')
    assert.ok(!(await r.text()).includes(KEY))
    assert.ok([...r.headers.values()].every((v) => !v.includes(KEY)))
    assert.ok(m.calls.every((u) => u.hostname === 'api.themoviedb.org'))
    assert.equal(m.calls[0].searchParams.get('api_key'), KEY)
})

test('a v4 read token goes as a bearer header', async () => {
    const token = 'x'.repeat(60)
    const seen: string[] = []
    const m = mock(() => j({ results: [film(2, 'Clap', 2022)] }))
    const inner = m.deps.fetch
    m.deps.fetch = (request) => (seen.push(request.headers.get('authorization') ?? ''), inner(request))
    const r = await get(createHandler(m.deps), '/tmdb/poster?k=film&t=Clap&y=2022', { TMDB_API_KEY: token })
    assert.equal(r.status, 200)
    assert.deepEqual(seen, [`Bearer ${token}`])
    assert.equal(m.calls[0].searchParams.get('api_key'), null)
})

test('a year one off is asked again without the year', async () => {
    const m = mock((u) => j({ results: u.searchParams.has('year') ? [] : [film(3, 'Festival Piece', 2021)] }))
    const r = await get(createHandler(m.deps), '/tmdb/poster?k=film&t=Festival+Piece&y=2022')
    const body = await r.json()
    assert.equal(body.match, true)
    assert.equal(m.calls.length, 2)
    assert.equal(m.calls[1].searchParams.has('year'), false)
})

test('a name with no episode is asked as a film and then as a show', async () => {
    const m = mock((u) => j({ results: u.pathname.endsWith('/search/tv') ? [show(9, 'Scam 1992', 2020)] : [] }))
    const r = await get(createHandler(m.deps), '/tmdb/poster?k=film&t=Scam+1992&y=2020')
    const body = await r.json()
    assert.deepEqual([body.match, body.kind], [true, 'show'])
    assert.deepEqual(m.calls.map((u) => u.pathname.split('/').pop()), ['movie', 'movie', 'tv'], 'the film with its year, without it, then the show')
    assert.equal(m.calls[2].searchParams.get('first_air_date_year'), '2020')
})

test('a show for a file that names its episode is trusted without a year', async () => {
    const m = mock(() => j({ results: [show(5, 'Harbour Notes', 2019)] }))
    const r = await get(createHandler(m.deps), '/tmdb/poster?k=show&t=Harbour+Notes&s=1')
    assert.equal((await r.json()).match, true)
    assert.equal(m.calls.length, 1)
    assert.ok(m.calls[0].pathname.endsWith('/search/tv'))
})

test('a film with no year to back it keeps the video\'s own picture', async () => {
    const m = mock(() => j({ results: [film(4, 'Night Train', 2019)] }))
    const r = await get(createHandler(m.deps), '/tmdb/poster?k=film&t=Night+Train')
    assert.deepEqual(await r.json(), { match: false })
    assert.match(r.headers.get('cache-control')!, new RegExp(`s-maxage=${MISS_TTL}`))
})

test('{tmdb-123} in the name fetches that title by id', async () => {
    const m = mock((u) => (u.pathname.endsWith('/movie/603') ? j(film(603, 'The Matrix', 1999)) : j({}, 404)))
    const r = await get(createHandler(m.deps), '/tmdb/poster?k=film&t=Matrix&y=1999&id=603')
    assert.equal((await r.json()).id, '603')
    const gone = await get(createHandler(mock(() => j({}, 404)).deps), '/tmdb/poster?k=film&t=Matrix&y=1999&id=1')
    assert.deepEqual(await gone.json(), { match: false })
})

test('an answer is kept and asked for once; the cache key is the cleaned title', async () => {
    const m = mock(() => j({ results: [film(2, 'Clap', 2022)] }))
    const handle = createHandler(m.deps)
    await get(handle, '/tmdb/poster?k=film&t=Clap&y=2022')
    const again = await get(handle, '/tmdb/poster?k=film&t=%20the+CLAP!&y=2022')
    assert.equal(again.headers.get('x-poster-cache'), 'hit')
    assert.equal((await again.json()).id, '2')
    assert.equal(m.calls.length, 1)
})

test('a server error is tried once more after a second, then not cached', async () => {
    let n = 0
    const m = mock(() => (n++ < 1 ? j({}, 500) : j({ results: [film(2, 'Clap', 2022)] })))
    const handle = createHandler(m.deps)
    const t0 = m.clock.t
    const r = await get(handle, '/tmdb/poster?k=film&t=Clap&y=2022')
    assert.equal(r.status, 200)
    assert.equal(m.clock.t - t0, 1000)
    const m2 = mock(() => j({}, 500))
    const bad = await get(createHandler(m2.deps), '/tmdb/poster?k=film&t=Clap&y=2022')
    assert.equal(bad.status, 503)
    assert.equal(bad.headers.get('cache-control'), 'no-store')
    assert.equal(m2.cache.size, 0)
})

test('a throttle pauses asking for the time TMDB names, and is not cached', async () => {
    const m = mock(() => j({}, 429, { 'Retry-After': '20' }))
    const handle = createHandler(m.deps)
    const first = await get(handle, '/tmdb/poster?k=film&t=Clap&y=2022')
    assert.equal(first.status, 503)
    assert.equal(first.headers.get('retry-after'), '20')
    const calls = m.calls.length
    const paused = await get(handle, '/tmdb/poster?k=film&t=Other&y=2020')
    assert.equal(paused.status, 503)
    assert.equal(m.calls.length, calls, 'nothing is asked while paused')
    m.clock.t += 21_000
    m.answer = () => j({ results: [film(2, 'Clap', 2022)] })
    assert.equal((await get(handle, '/tmdb/poster?k=film&t=Clap&y=2022')).status, 200)
    assert.equal(m.cache.size, 1)
})

test('a refused key is not asked with again for a day', async () => {
    const m = mock(() => j({}, 401))
    const handle = createHandler(m.deps)
    assert.equal((await get(handle, '/tmdb/poster?k=film&t=Clap&y=2022')).status, 503)
    const calls = m.calls.length
    assert.equal((await get(handle, '/tmdb/poster?k=film&t=Other&y=2020')).status, 503)
    assert.equal(m.calls.length, calls)
    m.clock.t += 24 * 3600 * 1000 + 1
    m.answer = () => j({ results: [film(2, 'Clap', 2022)] })
    assert.equal((await get(handle, '/tmdb/poster?k=film&t=Clap&y=2022')).status, 200)
})

test('without the secret every lookup is a 503 and TMDB is never asked', async () => {
    const m = mock(() => j({}))
    const r = await get(createHandler(m.deps), '/tmdb/poster?k=film&t=Clap&y=2022', {})
    assert.equal(r.status, 503)
    assert.equal(m.calls.length, 0)
})

test('bad questions, other sites and other methods are turned away', async () => {
    const m = mock(() => j({}))
    const handle = createHandler(m.deps)
    for (const q of ['', '?k=film', '?k=film&t=', '?k=movie&t=x', '?k=film&t=x&y=abc', '?k=film&t=x&id=-4', `?k=film&t=${'x'.repeat(201)}`]) {
        assert.equal((await get(handle, '/tmdb/poster' + q)).status, 400, q)
    }
    assert.equal((await get(handle, '/tmdb/poster?k=film&t=x', { TMDB_API_KEY: KEY }, { 'Sec-Fetch-Site': 'cross-site' })).status, 403)
    assert.equal((await handle(new Request(ORIGIN + '/tmdb/poster?k=film&t=x', { method: 'POST' }), { TMDB_API_KEY: KEY })).status, 405)
    assert.equal((await get(handle, '/tmdb/other')).status, 404)
    assert.equal(m.calls.length, 0)
})

test('posters come through the image route at the sizes the page uses, and nothing else does', async () => {
    const m = mock((u) => (u.pathname === '/t/p/w342/abcdefgh12.jpg' ? new Response('jpeg', { headers: { 'content-type': 'image/jpeg' } }) : new Response('', { status: 404 })))
    const handle = createHandler(m.deps)
    const ok = await get(handle, '/tmdb/img/w342/abcdefgh12.jpg')
    assert.equal(await ok.text(), 'jpeg')
    assert.equal(ok.headers.get('content-type'), 'image/jpeg')
    assert.match(ok.headers.get('cache-control')!, /immutable/)
    assert.equal((await get(handle, '/tmdb/img/original/abcdefgh12.jpg')).status, 404)
    assert.equal((await get(handle, '/tmdb/img/w342/..%2Fx.jpg')).status, 404)
    assert.equal((await get(handle, '/tmdb/img/w342/short.jpg')).status, 404)
    assert.equal((await get(handle, '/tmdb/img/w342/missingfile1.jpg')).status, 404)
    assert.ok(m.calls.every((u) => u.hostname === 'image.tmdb.org'))
    // No key is needed, or sent, for a picture.
    assert.equal((await get(handle, '/tmdb/img/w342/abcdefgh12.jpg', {})).status, 200)
    assert.ok(m.calls.every((u) => !u.search))
})
