// The season split through the worker (SeasonSplit) and /tmdb/season: Blue Box, one season of 37 that
// a group splits 25 + 12, over a mocked TMDB: what is asked, what is kept and what is not, and that
// garbage and a failing group never break the page.
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { createHandler, FOUND_TTL, type Deps } from './handler.ts'
import { OWN_SEASON, SEASONS_GROUP, page } from './split-fixtures.ts'

const KEY = 'test-key-123'
const ORIGIN = 'https://web.example'

function mock(answer: (url: URL) => Response) {
    const m = { calls: [] as URL[], cache: new Map<string, Response>(), clock: { t: 1_000_000 }, deps: null as unknown as Deps }
    m.deps = {
        fetch: async (request) => {
            const url = new URL(request.url)
            m.calls.push(url)
            return answer(url)
        },
        cache: { match: async (r) => m.cache.get(r.url)?.clone(), put: async (r, res) => void m.cache.set(r.url, res) },
        now: () => m.clock.t,
        sleep: async (ms) => void (m.clock.t += ms),
    }
    return m
}

const j = (body: unknown, status = 200, headers: Record<string, string> = {}) => new Response(JSON.stringify(body), { status, headers })
const get = (handle: ReturnType<typeof createHandler>, path: string, env: { TMDB_API_KEY?: string } = { TMDB_API_KEY: KEY }, headers: Record<string, string> = {}) => handle(new Request(ORIGIN + path, { headers }), env)

const blueBox = (group: () => Response = () => j(SEASONS_GROUP)) => (u: URL) => {
    if (u.pathname.includes('/tv/episode_group/')) return group()
    if (u.pathname.endsWith('/tv/207347/season/1')) return j(OWN_SEASON)
    if (u.pathname.endsWith('/tv/207347')) return j(page())
    return j({}, 404)
}
const numbers = (list: { number: number; episodes: number }[]) => list.map((s) => [s.number, s.episodes])

test('a show TMDB lists as one season comes with its split: the group is asked once, kept, and the answer says so', async () => {
    const m = mock(blueBox())
    const handle = createHandler(m.deps)
    const r = await get(handle, '/tmdb/extras?k=show&id=207347&s=2&e=1')
    const body = await r.json()
    assert.deepEqual(numbers(body.seasonList), [[1, 25], [2, 12]])
    assert.deepEqual(numbers(body.listedSeasons), [[1, 37]])
    assert.equal(body.seasonGroup, 'seasons001')
    assert.equal(body.next.season, 2)
    assert.equal(body.next.episode, 8)
    assert.equal(body.groupsRead, true)
    // The episode the page is about is the split's own: no per-episode call.
    assert.equal(body.episode.name, 'The 26th Tide')
    assert.match(m.calls[0].searchParams.get('append_to_response')!, /episode_groups/)
    assert.equal(m.calls.filter((u) => u.pathname.includes('/tv/episode_group/')).length, 1)
    assert.equal(m.calls.filter((u) => u.pathname.includes('/season/')).length, 0)
    assert.match(r.headers.get('cache-control')!, new RegExp(`s-maxage=${FOUND_TTL}`))
    // Asked again: from the edge, no TMDB call at all.
    const calls = m.calls.length
    assert.equal((await get(handle, '/tmdb/extras?k=show&id=207347&s=2&e=1')).headers.get('x-poster-cache'), 'hit')
    assert.equal(m.calls.length, calls)
})

test('an episode the split lacks (the chat counts straight through, S01E26) is read from TMDB as before', async () => {
    const m = mock((u) => (u.pathname.endsWith('/season/1/episode/26') ? j({ name: 'The 26th Tide', overview: 'Words', still_path: '/still0026abc.jpg' }) : blueBox()(u)))
    const body = await (await get(createHandler(m.deps), '/tmdb/extras?k=show&id=207347&s=1&e=26')).json()
    assert.equal(body.episode.name, 'The 26th Tide')
    assert.equal(m.calls.filter((u) => u.pathname.endsWith('/season/1/episode/26')).length, 1)
})

test("a group that cannot be read leaves TMDB's own seasons, and nothing is kept so the next ask tries again", async () => {
    for (const bad of [() => j({}, 429, { 'retry-after': '5' }), () => j({}, 500)]) {
        const m = mock(blueBox(bad))
        const r = await get(createHandler(m.deps), '/tmdb/extras?k=show&id=207347')
        assert.equal(r.status, 200)
        const body = await r.json()
        assert.deepEqual(numbers(body.seasonList), [[1, 37]])
        assert.equal(body.seasonGroup, null)
        assert.equal(r.headers.get('cache-control'), 'no-store')
        // It did not know the groups: no `groupsRead`, so no page takes it for a settled answer.
        assert.equal('groupsRead' in body, false)
        assert.equal(m.cache.size, 0)
    }
})

test("a group TMDB does not have (404) is kept: TMDB's own seasons stand", async () => {
    const r = await get(createHandler(mock(blueBox(() => j({}, 404))).deps), '/tmdb/extras?k=show&id=207347')
    assert.equal((await r.json()).seasonGroup, null)
    assert.match(r.headers.get('cache-control')!, new RegExp(`s-maxage=${FOUND_TTL}`))
})

test('a show with several seasons never asks for a group, and a film never has one', async () => {
    const several = { ...page(), seasons: [{ season_number: 1, episode_count: 10 }, { season_number: 2, episode_count: 10 }] }
    const m = mock((u) => (u.pathname.endsWith('/tv/9') ? j(several) : u.pathname.endsWith('/movie/9') ? j({ ...page(), title: 'x' }) : j({}, 404)))
    const handle = createHandler(m.deps)
    assert.equal((await (await get(handle, '/tmdb/extras?k=show&id=9')).json()).seasonGroup, null)
    assert.equal((await (await get(handle, '/tmdb/extras?k=film&id=9')).json()).seasonGroup, null)
    assert.equal(m.calls.filter((u) => u.pathname.includes('episode_group')).length, 0)
    assert.doesNotMatch(m.calls.find((u) => u.pathname.endsWith('/movie/9'))!.searchParams.get('append_to_response')!, /episode_groups/)
})

test('garbage group answers split nothing and never break the page', async () => {
    for (const results of ['nope', [null, 7, { id: 5 }, { id: '../x' }], { x: 1 }]) {
        const r = await get(createHandler(mock((u) => (u.pathname.endsWith('/tv/207347') ? j(page({ results })) : j({}, 404))).deps), '/tmdb/extras?k=show&id=207347')
        assert.equal(r.status, 200)
        assert.equal((await r.json()).seasonGroup, null)
    }
    const m = mock(blueBox(() => j({ groups: 'nope' })))
    assert.equal((await (await get(createHandler(m.deps), '/tmdb/extras?k=show&id=207347')).json()).seasonGroup, null)
})

test("/tmdb/season answers the split's season, or TMDB's own, with names, words, stills, days and lengths", async () => {
    const m = mock(blueBox())
    const handle = createHandler(m.deps)
    const two = await (await get(handle, '/tmdb/season?id=207347&s=2&g=seasons001')).json()
    assert.equal(two.found, true)
    assert.equal(two.episodes.length, 12)
    assert.deepEqual(two.episodes[0], { number: 1, name: 'The 26th Tide', overview: 'About 26', still: '/still0026abc.jpg', airDate: '2026-10-02', runtime: 24 })
    assert.equal(two.episodes[6].name, '', 'an untitled one is no "Episode 32"')
    const own = await (await get(handle, '/tmdb/season?id=207347&s=1')).json()
    assert.equal(own.episodes.length, 37)
    assert.equal(own.episodes[32].number, 33)
    // A season the split lacks is "not found", kept; the group's episodes are kept a day and not asked twice.
    assert.deepEqual(await (await get(handle, '/tmdb/season?id=207347&s=3&g=seasons001')).json(), { found: false })
    assert.equal(m.calls.filter((u) => u.pathname.includes('/tv/episode_group/')).length, 1)
    assert.equal((await get(handle, '/tmdb/season?id=207347&s=2&g=seasons001')).headers.get('x-poster-cache'), 'hit')
})

test('/tmdb/season refuses nonsense, says so when TMDB is down and keeps nothing then', async () => {
    const handle = createHandler(mock(blueBox()).deps)
    for (const q of ['', 'id=1', 'id=x&s=1', 'id=1&s=999', 'id=1&s=1&g=../../x', 'id=1&s=-1', 'id=0&s=1']) assert.equal((await get(handle, `/tmdb/season?${q}`)).status, 400, q)
    assert.equal((await get(handle, '/tmdb/season?id=1&s=1', {})).status, 503)
    const down = mock(() => j({}, 500))
    assert.equal((await get(createHandler(down.deps), '/tmdb/season?id=207347&s=1')).status, 503)
    assert.equal(down.cache.size, 0)
    const gone = mock((u) => (u.pathname.includes('/tv/episode_group/') ? j({}, 500) : j({}, 404)))
    assert.equal((await get(createHandler(gone.deps), '/tmdb/season?id=207347&s=2&g=seasons001')).status, 503)
    assert.equal(gone.cache.size, 0)
})

test("a group's season is one cache entry whatever show id is passed with it, and TMDB's own seasons stay within 60", async () => {
    const m = mock(blueBox())
    const handle = createHandler(m.deps)
    await get(handle, '/tmdb/season?id=1&s=2&g=seasons001')
    const calls = m.calls.length
    for (const id of [2, 3, 999]) assert.equal((await get(handle, `/tmdb/season?id=${id}&s=2&g=seasons001`)).headers.get('x-poster-cache'), 'hit')
    assert.equal(m.calls.length, calls)
    assert.equal(m.cache.size, 2, 'the season and the group part')
    assert.equal((await get(handle, '/tmdb/season?id=207347&s=61')).status, 400)
    assert.equal((await get(handle, '/tmdb/season?id=207347&s=60')).status, 200)
})

test('a group TMDB lacks is remembered: its other seasons ask nothing, and "not found" is kept for an hour', async () => {
    const m = mock((u) => (u.pathname.includes('/tv/episode_group/') ? j({}, 404) : j({}, 404)))
    const handle = createHandler(m.deps)
    const first = await get(handle, '/tmdb/season?id=1&s=1&g=randomgroup1')
    assert.deepEqual(await first.json(), { found: false })
    assert.match(first.headers.get('cache-control')!, /s-maxage=3600$/)
    const calls = m.calls.length
    for (const s of [2, 3, 4]) assert.deepEqual(await (await get(handle, `/tmdb/season?id=1&s=${s}&g=randomgroup1`)).json(), { found: false })
    assert.equal(m.calls.length, calls, 'the missing group was asked once')
    const own = await get(handle, '/tmdb/season?id=1&s=1')
    assert.deepEqual(await own.json(), { found: false })
    assert.match(own.headers.get('cache-control')!, /s-maxage=3600$/)
})

test('a visitor has a minute budget of uncached asks; kept answers are free, another visitor is not held back', async () => {
    const m = mock(blueBox())
    const handle = createHandler(m.deps)
    const ask = (n: number, ip: string) => get(handle, `/tmdb/season?id=${n}&s=1`, { TMDB_API_KEY: KEY }, { 'cf-connecting-ip': ip })
    for (let n = 1; n <= 30; n++) assert.equal((await ask(n, '1.1.1.1')).status, 200)
    const over = await ask(31, '1.1.1.1')
    assert.equal(over.status, 429)
    assert.ok(Number(over.headers.get('retry-after')) >= 1)
    assert.equal(over.headers.get('cache-control'), 'no-store')
    assert.equal((await ask(1, '1.1.1.1')).status, 200, 'a kept answer costs nothing')
    assert.equal((await ask(32, '2.2.2.2')).status, 200)
    // The minute passes.
    m.clock.t += 61_000
    assert.equal((await ask(33, '1.1.1.1')).status, 200)
})

test('everyone together has a budget too: many visitors cannot spend the key', async () => {
    const handle = createHandler(mock(blueBox()).deps)
    let refused = 0
    for (let n = 1; n <= 150; n++) if ((await get(handle, `/tmdb/season?id=${n}&s=1`, { TMDB_API_KEY: KEY }, { 'cf-connecting-ip': `9.9.${n}.1` })).status === 429) refused++
    assert.equal(refused, 30)
})
