// MSone's gateway answers and what is kept on the device, good and garbage (AGENTS.md: parsers of outside
// data get garbage tests). `node --test "src/player/*.test.ts"` (pnpm test:player).
import assert from 'node:assert/strict'
import { randomBytes } from 'node:crypto'
import { test, beforeEach } from 'node:test'
import {
    download, episodeOf, failureOf, FILE_MAX, health, KEEP_FILES, keptBytes, keptHits, knownHealth, parseHealth, parseHit, parseHits, resetMemory,
    retryAfterSeconds, search, searchUrl, setKeepStore, type Hit, type KeepStore,
} from './msone.ts'

const json = (body: unknown, status = 200, headers: Record<string, string> = {}) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json', ...headers } })

/** A fetch that answers from a list in turn and counts its calls. */
function fake(...answers: (Response | Error)[]) {
    const calls: string[] = []
    const f = (async (url: string) => {
        calls.push(String(url))
        const a = answers.length > 1 ? answers.shift()! : answers[0]
        if (a instanceof Error) throw a
        return a.clone()
    }) as unknown as typeof fetch
    return { f, calls }
}

const GOOD_HIT = {
    id: '21763',
    title: 'Harbour Notes',
    year: 2020,
    kind: 'single',
    season: null,
    episode: null,
    translator: { name: 'Translator name', url: 'https://example.org/profile' },
    downloadUrl: 'https://malayalamsubtitles.org/download/x',
    pageUrl: 'https://malayalamsubtitles.org/x',
    credit: 'Subtitles by MSone',
}

let now = 1_800_000_000_000
const realNow = Date.now
beforeEach(() => {
    resetMemory()
    now += 24 * 3600_000
    Date.now = () => now
})
process.on('exit', () => (Date.now = realNow))

// ---- health ----

test('health reads each state and its message', () => {
    for (const state of ['on', 'off', 'paused', 'stopped'] as const) assert.deepEqual(parseHealth({ ok: true, providers: { msone: { state, message: null } } }), { state, message: null })
    assert.deepEqual(parseHealth({ providers: { msone: { state: 'paused', message: '  Back soon.  ' } } }), { state: 'paused', message: 'Back soon.' })
    assert.equal(parseHealth({ providers: { msone: { state: 'on', message: 'x'.repeat(5000) } } })!.message!.length, 500)
})

test('health garbage: an unknown state is unknown, a broken answer is none', () => {
    assert.equal(parseHealth({ providers: { msone: { state: 'ON' } } })!.state, 'unknown')
    assert.equal(parseHealth({ providers: { msone: { state: 7, message: 42 } } })!.message, null)
    for (const bad of [null, undefined, 1, 'on', [], {}, { providers: [] }, { providers: { msone: 'on' } }, { providers: { other: {} } }]) assert.equal(parseHealth(bad), null)
})

test('health asks once in six hours, and Check again at most once a minute', async () => {
    const { f, calls } = fake(json({ providers: { msone: { state: 'off', message: null } } }))
    assert.equal((await health(false, f)).state, 'off')
    assert.equal((await health(false, f)).state, 'off')
    assert.equal(calls.length, 1)
    await health(true, f)
    assert.equal(calls.length, 1, 'forced within the minute: the kept answer')
    now += 61_000
    await health(true, f)
    assert.equal(calls.length, 2)
    now += 6 * 3600_000
    await health(false, f)
    assert.equal(calls.length, 3)
})

test('a failed check keeps stopped; never checked and failed is unknown', async () => {
    assert.equal((await health(false, fake(new TypeError('Failed to fetch')).f)).state, 'unknown')
    now += 61_000
    await health(true, fake(json({ providers: { msone: { state: 'stopped', message: 'Asked to stop.' } } })).f)
    now += 7 * 3600_000
    for (const bad of [new TypeError('Failed to fetch'), new Response('<html>', { status: 502 }), new Response('{"providers":', { status: 200 })]) {
        const h = await health(false, fake(bad).f)
        assert.deepEqual([h.state, h.message], ['stopped', 'Asked to stop.'])
        now += 61_000
    }
})

// ---- search ----

test('a good answer of the draft shape', () => {
    const hits = parseHits({ hits: [GOOD_HIT, { ...GOOD_HIT, id: 9, season: 1, episode: 3, translator: { name: 'A', url: null } }] })!
    assert.deepEqual(hits[0], {
        id: '21763',
        title: 'Harbour Notes',
        year: 2020,
        season: null,
        episode: null,
        translator: { name: 'Translator name', url: 'https://example.org/profile' },
        pageUrl: 'https://malayalamsubtitles.org/x',
        downloadUrl: 'https://malayalamsubtitles.org/download/x',
    } satisfies Hit)
    assert.deepEqual([hits[1].id, hits[1].season, hits[1].episode, hits[1].translator], ['9', 1, 3, { name: 'A', url: null }])
})

test('search garbage: bad hits are skipped, wrong types become null, addresses must be https', () => {
    assert.equal(parseHit({ ...GOOD_HIT, id: undefined }), null)
    assert.equal(parseHit({ ...GOOD_HIT, id: '../../etc' }), null)
    assert.equal(parseHit({ ...GOOD_HIT, title: '   ' }), null)
    assert.equal(parseHit({ ...GOOD_HIT, downloadUrl: 'http://malayalamsubtitles.org/x' }), null)
    assert.equal(parseHit({ ...GOOD_HIT, downloadUrl: 42 }), null)
    const odd = parseHit({ ...GOOD_HIT, year: '2020', kind: 'zip', season: '1', episode: 1.5, translator: { name: 'B', url: 'javascript:alert(1)' }, pageUrl: 'ftp://x' })!
    assert.deepEqual([odd.year, odd.season, odd.episode, odd.translator, odd.pageUrl], [null, null, null, { name: 'B', url: null }, null])
    assert.equal(parseHit({ ...GOOD_HIT, translator: 5 })!.translator, null)
    const huge = parseHit({ ...GOOD_HIT, title: 'x'.repeat(1_000_000), translator: { name: 'y'.repeat(100_000) } })!
    assert.equal(huge.title.length, 300)
    assert.equal(huge.translator!.name.length, 120)
    for (const bad of [null, 'hits', [], {}, { hits: {} }, { hits: 'a' }, { hits: null }]) assert.equal(parseHits(bad), null)
    assert.equal(parseHits({ hits: Array.from({ length: 5000 }, (_, i) => ({ ...GOOD_HIT, id: String(i) })) })!.length, 100)
    assert.deepEqual(parseHits({ hits: [null, 1, 'x', [], {}] }), [])
})

test('the search address: the title trimmed and capped, an empty one asks nothing', () => {
    assert.equal(searchUrl({ q: '   ' }), null)
    assert.equal(searchUrl({ imdb: 'nm123' }), null)
    const url = new URL(searchUrl({ q: `  ${'a'.repeat(500)}  `, year: 2020, season: 1, episode: 2 })!)
    assert.equal(url.searchParams.get('q')!.length, 120)
    assert.deepEqual([url.searchParams.get('year'), url.searchParams.get('season'), url.searchParams.get('episode')], ['2020', '1', '2'])
    assert.equal(new URL(searchUrl({ imdb: 'tt6723592', year: NaN })!).search, '?imdb=tt6723592')
})

test('errors map to the sheet lines', () => {
    assert.deepEqual(failureOf(400, { error: 'bad_query' }), { kind: 'too_short' })
    assert.deepEqual(failureOf(404, null), { kind: 'no_results' })
    assert.deepEqual(failureOf(429, { error: 'rate_limited' }, '30'), { kind: 'busy', retryAfter: 30 })
    assert.deepEqual(failureOf(410, { error: 'stopped', message: 'Stopped.' }), { kind: 'state', state: 'stopped', message: 'Stopped.' })
    assert.deepEqual(failureOf(503, { error: 'paused', message: 7 }), { kind: 'state', state: 'paused', message: null })
    // The gateway's own failures answer 503 unavailable too: offline, never a state.
    assert.deepEqual(failureOf(503, { error: 'unavailable', message: 'Something went wrong.' }), { kind: 'offline' })
    for (const [status, body] of [[503, null], [503, { error: 'x' }], [500, {}], [502, 'html'], [418, []]] as const) assert.deepEqual(failureOf(status, body), { kind: 'offline' })
    assert.equal(retryAfterSeconds('99999'), 3600)
    assert.equal(retryAfterSeconds('soon'), null)
    assert.equal(retryAfterSeconds(new Date(now + 90_000).toUTCString(), now), 90)
})

test('search answers: results, none, garbage, offline, and a state that is kept', async () => {
    assert.equal((await search({ q: 'Harbour' }, undefined, fake(json({ hits: [GOOD_HIT] })).f)).ok, true)
    assert.deepEqual(await search({ q: 'Harbour' }, undefined, fake(json({ hits: [] })).f), { ok: false, failure: { kind: 'no_results' } })
    for (const bad of [new Response('{"hits":[{"id"', { status: 200 }), new Response(randomBytes(4096), { status: 200 }), json({ hits: 'x' }), new TypeError('Failed to fetch')])
        assert.deepEqual(await search({ q: 'Harbour' }, undefined, fake(bad).f), { ok: false, failure: { kind: 'offline' } })
    const none = fake(json({}))
    assert.deepEqual(await search({ q: '' }, undefined, none.f), { ok: false, failure: { kind: 'too_short' } })
    assert.equal(none.calls.length, 0)
    await search({ q: 'Harbour' }, undefined, fake(json({ error: 'paused', message: 'Paused for now.' }, 503)).f)
    assert.deepEqual([knownHealth().state, knownHealth().message], ['paused', 'Paused for now.'])
    assert.deepEqual(await search({ q: 'Harbour' }, undefined, fake(json({ error: 'unavailable', message: 'Something went wrong.' }, 503)).f), { ok: false, failure: { kind: 'offline' } })
    assert.deepEqual([knownHealth().state, knownHealth().message], ['paused', 'Paused for now.'], 'a 503 unavailable leaves the state alone')
})

test('a 429 holds the next search back for its Retry-After, without asking', async () => {
    const busy = fake(json({ error: 'rate_limited' }, 429, { 'Retry-After': '20' }))
    assert.deepEqual(await search({ q: 'Harbour' }, undefined, busy.f), { ok: false, failure: { kind: 'busy', retryAfter: 20 } })
    now += 5_000
    const r = await search({ q: 'Harbour' }, undefined, busy.f)
    assert.deepEqual(r, { ok: false, failure: { kind: 'busy', retryAfter: 15 } })
    assert.equal(busy.calls.length, 1)
    now += 16_000
    await search({ q: 'Harbour' }, undefined, busy.f)
    assert.equal(busy.calls.length, 2)
})

test('a closed sheet aborts quietly', async () => {
    const ctl = new AbortController()
    ctl.abort()
    const f = (async (_: string, init?: RequestInit) => {
        if (init?.signal?.aborted) throw new DOMException('aborted', 'AbortError')
        return json({ hits: [GOOD_HIT] })
    }) as unknown as typeof fetch
    assert.deepEqual(await search({ q: 'Harbour' }, ctl.signal, f), { ok: false, failure: { kind: 'aborted' } })
})

// ---- the zip reader and downloads ----

const SRT = '1\n00:00:01,000 --> 00:00:02,000\nHello\n'

test('episode numbers from names', () => {
    assert.deepEqual(episodeOf('Show.S01E02.srt'), { season: 1, episode: 2 })
    assert.deepEqual(episodeOf('S1 E3'), { season: 1, episode: 3 })
    assert.deepEqual(episodeOf('show_2x05.ass'), { season: 2, episode: 5 })
    assert.deepEqual(episodeOf('Show Episode 7.srt'), { season: null, episode: 7 })
    assert.equal(episodeOf('Harbour.Notes.2020.1080p.x265.srt'), null)
})

const hit = (o: Partial<Hit> = {}): Hit => ({ ...parseHit(GOOD_HIT)!, ...o })

test('a download comes through the gateway as a File for readSubtitle', async () => {
    const single = fake(new Response(SRT, { headers: { 'Content-Disposition': 'attachment; filename="../Harbour.Notes.ml.srt"' } }))
    const r = await download(hit(), { season: null, episode: null }, undefined, single.f)
    assert.ok(r.ok)
    assert.equal(r.file.name, 'Harbour.Notes.ml.srt')
    assert.equal(await r.file.text(), SRT)
    assert.equal(single.calls[0], 'https://subs.tmplayer.org/msone/download/21763')
    const named = await download(hit(), { season: null, episode: null }, undefined, fake(new Response(SRT, { headers: { 'Content-Disposition': 'attachment; filename=x.exe' } })).f)
    assert.ok(named.ok && named.file.name === 'Harbour Notes.srt')
    assert.equal(hit({ id: 'aHR0cHM6Ly9hZGRvbi5' + 'x'.repeat(300) }).id.length, 319, 'the gateway long ids are kept')
    assert.ok(parseHit({ ...GOOD_HIT, id: 'aHR0cHM6Ly9hZGRvbi5' + 'x'.repeat(300) }), 'and parsed')
})

test('download garbage: too large, another archive, a missing release, a state', async () => {
    const where = { season: null, episode: null }
    const big = await download(hit(), where, undefined, fake(new Response('x', { headers: { 'Content-Length': String(FILE_MAX + 1) } })).f)
    assert.deepEqual(big, { ok: false, failure: { kind: 'unreadable' } })
    const huge = await download(hit(), where, undefined, fake(new Response(new Uint8Array(FILE_MAX + 1) as BodyInit)).f)
    assert.deepEqual(huge, { ok: false, failure: { kind: 'unreadable' } })
    const rar = await download(hit(), where, undefined, fake(new Response(new Uint8Array([0x52, 0x61, 0x72, 0x21, 0x1a, 0x07]) as BodyInit)).f)
    assert.deepEqual(rar, { ok: false, failure: { kind: 'unreadable' } })
    const gone = await download(hit(), where, undefined, fake(json({ error: 'not_found' }, 404)).f)
    assert.deepEqual(gone, { ok: false, failure: { kind: 'unreadable' } })
    const random = await download(hit(), { season: 1, episode: 1 }, undefined, fake(new Response(new Uint8Array([0x50, 0x4b, 3, 4, ...randomBytes(2000)]) as BodyInit)).f)
    assert.deepEqual(random, { ok: false, failure: { kind: 'unreadable' } })
    const stopped = await download(hit(), where, undefined, fake(json({ error: 'stopped', message: 'Done.' }, 410)).f)
    assert.deepEqual(stopped, { ok: false, failure: { kind: 'state', state: 'stopped', message: 'Done.' } })
    assert.equal(knownHealth().state, 'stopped')
})

// ---- kept on the device ----

/** A store in memory, as idb-keyval's would be in the page. */
function mapStore() {
    const map = new Map<string, unknown>()
    const store: KeepStore = {
        get: async (k) => map.get(k),
        set: async (k, v) => void map.set(k, v),
        del: async (ks) => ks.forEach((k) => map.delete(k)),
        keys: async () => [...map.keys()],
    }
    return { map, store }
}

const offline = () => fake(new TypeError('Failed to fetch'))
const ASK = { q: 'Show', season: 1, episode: 2 }
const EP = { season: 1, episode: 2 }

test('a kept file loads again with no network, whatever the state', async () => {
    setKeepStore(mapStore().store)
    const first = await download(hit(), { season: null, episode: null }, undefined, fake(new Response(SRT)).f)
    assert.ok(first.ok)
    await search({ q: 'Harbour' }, undefined, fake(json({ error: 'paused', message: null }, 503)).f)
    const net = offline()
    const again = await download(hit(), { season: null, episode: null }, undefined, net.f)
    assert.ok(again.ok)
    assert.equal(await again.file.text(), SRT)
    assert.equal(net.calls.length, 0)
    assert.ok((await keptBytes()) > 0)
})

test('paused: the last search lists its hits whose file is kept', async () => {
    setKeepStore(mapStore().store)
    const pack = { ...GOOD_HIT, id: '7', season: 1 }
    const other = { ...GOOD_HIT, id: '8' }
    assert.ok((await search(ASK, undefined, fake(json({ hits: [pack, other] })).f)).ok)
    await download(parseHit(pack)!, EP, undefined, fake(new Response(SRT)).f)
    await search(ASK, undefined, fake(json({ error: 'paused', message: 'Paused.' }, 503)).f)
    assert.equal(knownHealth().state, 'paused')
    assert.deepEqual((await keptHits(ASK, EP)).map((h) => h.id), ['7'])
    assert.deepEqual(await keptHits({ q: 'Another' }, EP), [])
})

test('stopped deletes every kept file and search; paused and off keep them', async () => {
    const { map, store } = mapStore()
    setKeepStore(store)
    await search(ASK, undefined, fake(json({ hits: [{ ...GOOD_HIT, id: '7' }] })).f)
    await download(hit({ id: '7' }), EP, undefined, fake(new Response(SRT)).f)
    for (const [status, error] of [[503, 'paused'], [503, 'unavailable']] as const) {
        await search({ q: 'x' }, undefined, fake(json({ error }, status)).f)
        assert.equal((await keptHits(ASK, EP)).length, 1)
    }
    await search({ q: 'x' }, undefined, fake(json({ error: 'stopped', message: 'Asked to stop.' }, 410)).f)
    await keptBytes()
    assert.equal([...map.keys()].filter((k) => k !== 'index').length, 0)
    assert.deepEqual(await keptHits(ASK, EP), [])
    const net = fake(new Response(SRT))
    await download(hit({ id: '7' }), EP, undefined, net.f)
    assert.equal(net.calls.length, 1, 'nothing kept to load from')
    assert.equal(map.size <= 1, true, 'and nothing new is kept while stopped')
})

test('a check that says stopped deletes them too', async () => {
    const { map, store } = mapStore()
    setKeepStore(store)
    await download(hit(), EP, undefined, fake(new Response(SRT)).f)
    await health(true, fake(json({ providers: { msone: { state: 'stopped', message: null } } })).f)
    await keptBytes()
    assert.equal([...map.keys()].filter((k) => k.startsWith('file|')).length, 0)
})

test('the files are held to 200, the least recently used going', async () => {
    const { map, store } = mapStore()
    setKeepStore(store)
    for (let i = 0; i < KEEP_FILES + 5; i++) {
        now += 1000
        await download(hit({ id: `n${i}` }), EP, undefined, fake(new Response(SRT)).f)
    }
    assert.equal([...map.keys()].filter((k) => k.startsWith('file|')).length, KEEP_FILES)
    assert.equal(map.has('file|msone:n0'), false)
    assert.equal(map.has(`file|msone:n${KEEP_FILES + 4}`), true)
})

test('a broken store is tolerated: failing, hanging, or holding garbage', async () => {
    const failing: KeepStore = {
        get: async () => {
            throw new DOMException('quota', 'QuotaExceededError')
        },
        set: async () => {
            throw new DOMException('quota', 'QuotaExceededError')
        },
        del: () => Promise.reject(new Error('gone')),
        keys: () => {
            throw new Error('no store')
        },
    }
    const hanging: KeepStore = { get: () => new Promise(() => {}), set: () => new Promise(() => {}), del: () => new Promise(() => {}), keys: () => new Promise(() => {}) }
    const junk = mapStore()
    for (const v of ['index', 'file|msone:21763', 'search|x']) junk.map.set(v, 'garbage')
    junk.map.set('index', { files: { 'msone:21763': [1, 2] }, searches: 'x' })
    junk.map.set('file|msone:21763', { name: 5, bytes: 'not bytes' })
    for (const store of [failing, hanging, junk.store]) {
        resetMemory()
        setKeepStore(store)
        assert.ok((await search({ q: 'Harbour' }, undefined, fake(json({ hits: [GOOD_HIT] })).f)).ok)
        const r = await download(hit(), { season: null, episode: null }, undefined, fake(new Response(SRT)).f)
        assert.ok(r.ok && (await r.file.text()) === SRT)
        assert.ok(Array.isArray(await keptHits({ q: 'Harbour' }, EP)))
        await search({ q: 'x' }, undefined, fake(json({ error: 'stopped' }, 410)).f)
    }
})
