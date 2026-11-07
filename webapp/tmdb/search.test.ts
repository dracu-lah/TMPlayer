// "Choose the right poster" in the worker: /tmdb/search over a mocked TMDB (what it asks, what it
// lists, what is kept, a throttle, a refused or missing key) and a pick asking for its id.
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { createHandler, type Deps } from './handler.ts'

const KEY = 'test-key-123'
const ORIGIN = 'https://web.example'

interface Mock {
    calls: URL[]
    cache: Map<string, Response>
    deps: Deps
    clock: { t: number }
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

const film = (id: number, title: string, year: number, poster: string | null = null, language = 'en') => ({
    id,
    title,
    overview: '',
    original_title: title,
    release_date: `${year}-05-01`,
    poster_path: poster,
    original_language: language,
})
const show = (id: number, name: string, year: number) => ({ id, name, original_name: name, first_air_date: `${year}-01-10`, poster_path: `/s${id}abcdefgh.jpg`, original_language: 'en' })

interface Choice {
    id: number
    kind: string
    title: string
    year: number | null
    language: string
    overview: string
    poster: string | null
}
const choicesOf = async (r: Response) => ((await r.json()) as { choices: Choice[] }).choices

test('search lists films and shows, with the year and without it, once each, closest first', async () => {
    const m = mock((u) => {
        if (u.pathname.endsWith('/search/movie')) {
            return j({ results: u.searchParams.has('year') ? [film(1, 'Idiot', 2022, null, 'cs')] : [film(1, 'Idiot', 2022, null, 'cs'), film(2, 'Idiot', 2021, '/p2abcdefgh.jpg', 'ta')] })
        }
        return j({ results: [show(9, 'Idiot', 2019)] })
    })
    const r = await get(createHandler(m.deps), '/tmdb/search?k=film&t=Idiot&y=2022&l=ta')
    assert.equal(r.status, 200)
    const choices = await choicesOf(r)
    assert.deepEqual(choices.map((c) => `${c.kind}${c.id}`), ['film1', 'film2', 'show9'])
    assert.equal(choices[1].language, 'ta')
    assert.equal(choices[1].poster, '/p2abcdefgh.jpg')
    assert.equal(choices[0].poster, null)
    // Films with the year and without it, shows the same: four searches.
    assert.deepEqual(
        m.calls.map((u) => `${u.pathname.split('/').pop()}:${u.searchParams.get('year') ?? u.searchParams.get('first_air_date_year') ?? '-'}`),
        ['movie:2022', 'movie:-', 'tv:2022', 'tv:-'],
    )
    assert.match(r.headers.get('cache-control')!, /s-maxage=3600/)
})

test('a name with no year is searched once per kind', async () => {
    const m = mock(() => j({ results: [] }))
    const r = await get(createHandler(m.deps), '/tmdb/search?k=show&t=Harbour+Notes')
    assert.deepEqual(await choicesOf(r), [])
    // The kind the file looks like first.
    assert.deepEqual(m.calls.map((u) => u.pathname.split('/').pop()), ['tv', 'movie'])
})

test('search finds the right title even when its year differs, and never leaks the key', async () => {
    const m = mock((u) => (u.pathname.endsWith('/search/movie') ? j({ results: u.searchParams.has('year') ? [] : [film(2, 'Idiot', 2021, null, 'ta')] }) : j({ results: [] })))
    const r = await get(createHandler(m.deps), '/tmdb/search?k=film&t=Idiot&y=2022')
    const text = await r.text()
    assert.ok(text.includes('"id":2'))
    assert.ok(!text.includes(KEY))
    assert.ok([...r.headers.values()].every((v) => !v.includes(KEY)))
})

test('search is kept an hour at the edge by the cleaned title; a throttle is not kept and pauses asking', async () => {
    const m = mock(() => j({ results: [film(2, 'Clap', 2022)] }))
    const handle = createHandler(m.deps)
    await get(handle, '/tmdb/search?k=film&t=Clap&y=2022')
    const calls = m.calls.length
    const again = await get(handle, '/tmdb/search?k=film&t=%20the+CLAP!&y=2022')
    assert.equal(again.headers.get('x-poster-cache'), 'hit')
    assert.equal(m.calls.length, calls)
    const throttled = mock(() => j({}, 429, { 'Retry-After': '20' }))
    const h2 = createHandler(throttled.deps)
    const first = await get(h2, '/tmdb/search?k=film&t=Clap&y=2022')
    assert.equal(first.status, 503)
    assert.equal(first.headers.get('retry-after'), '20')
    assert.equal(first.headers.get('cache-control'), 'no-store')
    assert.equal(throttled.cache.size, 0)
    // Not four searches into a throttle: the first one stopped the rest.
    assert.equal(throttled.calls.length, 1)
    const asked = throttled.calls.length
    assert.equal((await get(h2, '/tmdb/search?k=film&t=Other&y=2020')).status, 503)
    assert.equal(throttled.calls.length, asked)
    throttled.clock.t += 21_000
    throttled.answer = () => j({ results: [film(2, 'Clap', 2022)] })
    assert.equal((await get(h2, '/tmdb/search?k=film&t=Clap&y=2022')).status, 200)
})

test('a server error is a 503 that is not kept; a refused key waits a day; a missing key never asks', async () => {
    const bad = mock(() => j({}, 500))
    assert.equal((await get(createHandler(bad.deps), '/tmdb/search?k=film&t=Clap&y=2022')).status, 503)
    assert.equal(bad.cache.size, 0)
    const refused = mock(() => j({}, 401))
    const handle = createHandler(refused.deps)
    assert.equal((await get(handle, '/tmdb/search?k=film&t=Clap')).status, 503)
    const asked = refused.calls.length
    assert.equal((await get(handle, '/tmdb/search?k=film&t=Other')).status, 503)
    assert.equal(refused.calls.length, asked)
    const none = mock(() => j({}))
    assert.equal((await get(createHandler(none.deps), '/tmdb/search?k=film&t=Clap', {})).status, 503)
    assert.equal(none.calls.length, 0)
})

test('one search throttled part way still lists what the others found, unkept', async () => {
    let n = 0
    const m = mock(() => (n++ === 0 ? j({ results: [film(2, 'Clap', 2022)] }) : j({}, 429, { 'Retry-After': '20' })))
    const r = await get(createHandler(m.deps), '/tmdb/search?k=film&t=Clap&y=2022')
    assert.equal(r.status, 200)
    assert.deepEqual((await choicesOf(r)).map((c) => c.id), [2])
    assert.equal(m.cache.size, 0)
})

test('bad questions, other sites and other methods are turned away', async () => {
    const m = mock(() => j({}))
    const handle = createHandler(m.deps)
    for (const q of ['', '?k=film', '?k=film&t=', '?k=movie&t=x', '?k=film&t=x&y=abc', `?k=film&t=${'x'.repeat(201)}`, '?k=film&t=x&p=1']) {
        assert.equal((await get(handle, '/tmdb/search' + q)).status, 400, q)
    }
    assert.equal((await get(handle, '/tmdb/search?k=film&t=x', { TMDB_API_KEY: KEY }, { 'Sec-Fetch-Site': 'cross-site' })).status, 403)
    assert.equal(m.calls.length, 0)
})

test('a pick asks for its id and needs no year to back it; the same question unpicked is not trusted', async () => {
    // The Tamil film is 2021; the file says 2025: the year would refuse it.
    const m = mock((u) => (u.pathname.endsWith('/movie/2') ? j(film(2, 'Idiot', 2021, '/p2abcdefgh.jpg', 'ta')) : j({}, 404)))
    const handle = createHandler(m.deps)
    const picked = await get(handle, '/tmdb/poster?k=film&t=Idiot&y=2025&id=2&p=1')
    assert.equal(((await picked.json()) as { id: string }).id, '2')
    const forced = await get(handle, '/tmdb/poster?k=film&t=Idiot&y=2025&id=2')
    assert.deepEqual(await forced.json(), { match: false })
    // A title TMDB has since removed is no match, not an error.
    const gone = await get(handle, '/tmdb/poster?k=film&t=Idiot&y=2025&id=99&p=1')
    assert.equal(gone.status, 200)
    assert.deepEqual(await gone.json(), { match: false })
    // A pick says its id.
    assert.equal((await get(handle, '/tmdb/poster?k=film&t=Idiot&p=1')).status, 400)
})
