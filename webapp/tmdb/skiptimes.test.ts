// The anime skip-time readers (skiptimes.ts) over AniSkip's and the id map's shapes, and over garbage;
// then /tmdb/skip over mocked providers: the MyAnimeList id from AniList or the id map, AniSkip asked with
// the file's length, an empty answer kept, a throttled or failed source never remembered as "nobody timed this".
import assert from 'node:assert/strict'
import { test } from 'node:test'
import * as f from './anime-fixtures.ts'
import { createHandler, type Deps } from './handler.ts'
import { aniSkipOf, malOfIdMap } from './skiptimes.ts'

const ORIGIN = 'https://web.example'
const op = { interval: { startTime: 94.5, endTime: 184.25 }, skipType: 'op', episodeLength: 1420 }
const ed = { interval: { startTime: 1320, endTime: 1410 }, skipType: 'mixed-ed' }

test('AniSkip: an opening, an ending and a recap, in seconds; a type it does not know and a backwards stretch are dropped', () => {
    const got = aniSkipOf({ found: true, results: [op, ed, { interval: { startTime: 0, endTime: 30 }, skipType: 'recap' }, { interval: { startTime: 1, endTime: 5 }, skipType: 'mixed-op' }, { interval: { startTime: 1, endTime: 5 }, skipType: 'banner' }, { interval: { startTime: 50, endTime: 40 }, skipType: 'op' }] })
    assert.deepEqual(got, [
        { kind: 'opening', start: 94.5, end: 184.25 },
        { kind: 'ending', start: 1320, end: 1410 },
        { kind: 'recap', start: 0, end: 30 },
        { kind: 'opening', start: 1, end: 5 },
    ])
})

test('the id map names the MyAnimeList id as a number or as digits, and nothing else', () => {
    assert.equal(malOfIdMap({ myanimelist: 1735 }), '1735')
    assert.equal(malOfIdMap({ myanimelist: ' 1735 ' }), '1735')
    for (const bad of [{ myanimelist: 0 }, { myanimelist: '0' }, { myanimelist: -4 }, { myanimelist: 1.5 }, { myanimelist: '12ab' }, { myanimelist: 1e12 }, { myanimelist: null }, {}, null, 'x', []]) assert.equal(malOfIdMap(bad), null)
})

test('the readers survive truncated, wrong-typed, huge and random input', () => {
    const inputs: unknown[] = [null, undefined, 0, NaN, '', 'x', [], {}, { results: 'x' }, { results: [null, 1, 'x', [], {}, { interval: 5 }, { interval: {}, skipType: 'op' }, { interval: { startTime: 'a', endTime: 'b' }, skipType: 'op' }, { interval: { startTime: -5, endTime: 1e30 }, skipType: 'op' }, { interval: { startTime: NaN, endTime: Infinity }, skipType: 'ed' }] }, { results: Array.from({ length: 100_000 }, () => op) }]
    let seed = 3
    const rnd = (n: number) => ((seed = (seed * 1103515245 + 12345) & 0x7fffffff) % n)
    for (let i = 0; i < 300; i++) inputs.push({ results: [{ interval: { startTime: rnd(3) ? rnd(100_000) - 500 : 'x', endTime: rnd(100_000) }, skipType: ['op', 'ed', 'recap', 7, null][rnd(5)] }], myanimelist: [rnd(2e9), String(rnd(1e5)), {}, [], true][rnd(5)] })
    for (const input of inputs) {
        assert.doesNotThrow(() => aniSkipOf(input))
        assert.doesNotThrow(() => malOfIdMap(input))
    }
    assert.ok(aniSkipOf(inputs.at(-301)).length <= 50)
    assert.ok(aniSkipOf(inputs.at(-301)).length > 0)
    assert.deepEqual(aniSkipOf({ results: [{ interval: { startTime: 5, endTime: 1e9 }, skipType: 'op' }] }), [])
})

function world(over: { mal?: number | null; aniskip?: 'ok' | 'none' | 'down' | 'throttled'; idmap?: 'ok' | 'down' | 'none' } = {}) {
    const w = { mal: 1735 as number | null, aniskip: 'ok', idmap: 'ok', ...over }
    const urls: URL[] = []
    const clock = { t: 1_000_000 }
    const cache = new Map<string, Response>()
    const j = (body: unknown, status = 200, headers: Record<string, string> = {}) => new Response(JSON.stringify(body), { status, headers })
    const deps: Deps = {
        fetch: async (request) => {
            const url = new URL(request.url)
            urls.push(url)
            if (url.hostname === 'graphql.anilist.co') return j(f.aniRun({ idMal: w.mal }))
            if (url.hostname === 'arm.haglund.dev') return w.idmap === 'ok' ? j({ myanimelist: 777 }) : w.idmap === 'none' ? j({}, 404) : j({}, 500)
            if (url.hostname === 'api.aniskip.com') {
                if (w.aniskip === 'down') return j({}, 500)
                if (w.aniskip === 'throttled') return j({}, 429, { 'retry-after': '20' })
                return w.aniskip === 'none' ? j({ found: false, results: [] }, 404) : j({ found: true, results: [op, ed] })
            }
            return j({}, 404)
        },
        cache: { match: async (r) => cache.get(r.url)?.clone(), put: async (r, res) => void cache.set(r.url, res) },
        now: () => clock.t,
        sleep: async (ms) => void (clock.t += ms),
    }
    return { urls, handle: createHandler(deps), clock, cache }
}
const ask = (h: ReturnType<typeof world>, query = 'id=1735&e=123&len=1420') => h.handle(new Request(`${ORIGIN}/tmdb/skip?${query}`), {})
const skipCalls = (h: ReturnType<typeof world>) => h.urls.filter((u) => u.hostname === 'api.aniskip.com')

test('/tmdb/skip: AniList names the MyAnimeList id, AniSkip is asked with the episode, the length and the kinds', async () => {
    const h = world()
    const r = await ask(h)
    assert.equal(r.status, 200)
    assert.deepEqual(await r.json(), { segments: [{ kind: 'opening', start: 94.5, end: 184.25 }, { kind: 'ending', start: 1320, end: 1410 }] })
    const [call] = skipCalls(h)
    assert.equal(call.pathname, '/v2/skip-times/1735/123')
    assert.equal(call.searchParams.get('episodeLength'), '1420')
    assert.deepEqual(call.searchParams.getAll('types'), ['op', 'ed', 'mixed-op', 'mixed-ed', 'recap'])
    assert.equal(h.urls.filter((u) => u.hostname === 'arm.haglund.dev').length, 0)
    assert.match(r.headers.get('cache-control')!, /s-maxage=86400/)
})

test('/tmdb/skip: where AniList has no MyAnimeList id the id map is asked once, and its answer is kept', async () => {
    const h = world({ mal: null })
    await ask(h)
    assert.equal(skipCalls(h)[0].pathname, '/v2/skip-times/777/123')
    await ask(h, 'id=1735&e=124&len=1420')
    assert.equal(h.urls.filter((u) => u.hostname === 'arm.haglund.dev').length, 1)
    assert.equal(skipCalls(h)[1].pathname, '/v2/skip-times/777/124')
})

test('/tmdb/skip: nobody timed it is an empty answer, kept; the same question is not asked twice', async () => {
    const h = world({ aniskip: 'none' })
    assert.deepEqual(await (await ask(h)).json(), { segments: [] })
    const again = await ask(h)
    assert.equal(again.headers.get('x-poster-cache'), 'hit')
    assert.equal(skipCalls(h).length, 1)
})

test('/tmdb/skip: AniSkip down or throttled is a 503 that is not kept, and is asked again, not remembered as "nobody timed it"', async () => {
    const down = world({ aniskip: 'down' })
    const r = await ask(down)
    assert.equal(r.status, 503)
    assert.equal(r.headers.get('cache-control'), 'no-store')
    assert.ok(![...down.cache.keys()].some((k) => k.includes('/tmdb/skip')))
    const hot = world({ aniskip: 'throttled' })
    assert.equal((await ask(hot)).status, 503)
    const calls = skipCalls(hot).length
    assert.equal((await ask(hot)).status, 503)
    assert.equal(skipCalls(hot).length, calls, 'paused for the time AniSkip named')
    const noIds = world({ mal: null, idmap: 'down' })
    assert.equal((await ask(noIds)).status, 503)
})

test('/tmdb/skip: no id anywhere is an empty answer, and a bad question is a 400', async () => {
    const none = world({ mal: null, idmap: 'none' })
    assert.deepEqual(await (await ask(none)).json(), { segments: [] })
    assert.equal(skipCalls(none).length, 0)
    const h = world()
    for (const query of ['', 'id=x&e=1', 'id=0&e=1', 'id=1&e=0', 'id=1&e=99999', 'id=1&e=1&len=-5', 'id=1&e=1&len=999999', 'id=1&e=1.5', 'id=1;2&e=1']) assert.equal((await ask(h, query)).status, 400, query)
    assert.equal(h.urls.length, 0)
})
