// SubMaker's address, list and translation poller against a FAKE addon that follows the protocol (no real
// addon, no real address). `node --test "src/player/*.test.ts"` (pnpm test:player).
import assert from 'node:assert/strict'
import { beforeEach, test } from 'node:test'
import {
    clearJobs, cueCount, FETCH_TIMEOUT_MS, fetchList, fetchSubtitle, fetchTextWith, giveConsent, hasConsent, idFor, isPlaceholder, jobFor, lastCueEndMs, listUrl, normalizeBase, parseList, startJob, translate,
    MAX_BODY, MAX_ENTRIES, type Answer, type FetchText, type Outcome, type TranslateOptions, type Update,
} from './submaker.ts'

// ---- the fake addon ---------------------------------------------------------------------------

const SECRET = 'tok3nSECRETvalue9f8e7d6c'
const BASE = `https://addon.example/${SECRET}`
const BASE_HOST = 'https://addon.example/tok'

const stamp = (ms: number) => {
    const h = Math.floor(ms / 3_600_000)
    const m = Math.floor((ms % 3_600_000) / 60_000)
    const s = Math.floor((ms % 60_000) / 1000)
    return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')},${String(ms % 1000).padStart(3, '0')}`
}
/** n cues, two seconds apart, one second long. */
const srt = (n: number, from = 0) =>
    Array.from({ length: n }, (_, i) => `${i + 1}\n${stamp(from + i * 2000)} --> ${stamp(from + i * 2000 + 1000)}\nLine ${i + 1}\n`).join('\n')
const PLACEHOLDER = `1\n00:00:00,000 --> 04:00:00,000\nTRANSLATION IN PROGRESS\n\n2\n04:00:00,000 --> 04:00:05,000\nThis can take a few minutes. Fetch this subtitle again later.\n`

/** The fake clock: sleeping moves it, fetching costs nothing. */
function world() {
    const w = {
        t: 1_000_000,
        visible: true,
        slept: [] as number[],
        calls: 0,
        updates: [] as Update[],
        sleep: async (ms: number) => {
            w.slept.push(ms)
            w.t += ms
        },
        now: () => w.t,
        foreground: () => w.visible,
    }
    return w
}

/** A fetch that gives each call its answer from `script` (the last one repeats); a function gets the call number. */
function addon(w: ReturnType<typeof world>, script: (Answer | Error | ((n: number) => Answer | Error))[]): FetchText {
    return async () => {
        const n = w.calls++
        const step = script[Math.min(n, script.length - 1)]
        const a = typeof step === 'function' ? step(n) : step
        if (a instanceof Error) throw a
        return a
    }
}
const ok = (text: string | null): Answer => ({ status: 200, text })

function options(w: ReturnType<typeof world>, fetchText: FetchText, more: Partial<TranslateOptions> = {}): TranslateOptions {
    return { url: `${BASE}/translate/abc.srt`, fetchText, sleep: w.sleep, now: w.now, foreground: w.foreground, onUpdate: (u) => w.updates.push(u), ...more }
}

const run = (w: ReturnType<typeof world>, script: Parameters<typeof addon>[1], more: Partial<TranslateOptions> = {}): Promise<Outcome> => translate(options(w, addon(w, script), more)).done

beforeEach(() => clearJobs())

// ---- normalizeBase -------------------------------------------------------------------------------

test('normalizeBase takes the install link in every form and gives the base', () => {
    assert.equal(normalizeBase(`https://addon.example/${SECRET}/manifest.json`), BASE)
    assert.equal(normalizeBase(`stremio://addon.example/${SECRET}/manifest.json`), BASE)
    assert.equal(normalizeBase(`STREMIO://addon.example/${SECRET}/manifest.json`), BASE)
    assert.equal(normalizeBase(`  https://addon.example/${SECRET}/manifest.json \n`), BASE)
    assert.equal(normalizeBase(`https://addon.example/${SECRET}/`), BASE)
    assert.equal(normalizeBase(`https://addon.example/${SECRET}`), BASE)
    assert.equal(normalizeBase(`https://addon.example/${SECRET}//manifest.json`), BASE)
    assert.equal(normalizeBase(`https://addon.example/${SECRET}/Manifest.JSON`), BASE)
    assert.equal(normalizeBase(`https://addon.example/${SECRET}/manifest.json?x=1#frag`), BASE)
    assert.equal(normalizeBase('https://addon.example:8443/a/b/manifest.json'), 'https://addon.example:8443/a/b')
    assert.equal(normalizeBase('https://addon.example/%7B%22k%22%3A1%7D/manifest.json'), 'https://addon.example/%7B%22k%22%3A1%7D')
})

test('normalizeBase accepts a host alone (an empty path is a base)', () => {
    assert.equal(normalizeBase('https://addon.example/manifest.json'), 'https://addon.example')
    assert.equal(normalizeBase('stremio://addon.example/manifest.json'), 'https://addon.example')
    assert.equal(normalizeBase('https://addon.example'), 'https://addon.example')
    assert.equal(normalizeBase('https://addon.example/'), 'https://addon.example')
})

test('normalizeBase rejects what is not an install address', () => {
    for (const bad of [
        '',
        '   ',
        'addon.example/x/manifest.json',
        `http://addon.example/${SECRET}/manifest.json`,
        'ftp://addon.example/x/manifest.json',
        'javascript:alert(1)',
        'file:///etc/passwd',
        'https://user:pass@addon.example/x/manifest.json',
        'https://user@addon.example/x/manifest.json',
        'https://',
        'https://addon.example/x y/manifest.json',
        'https://addon.example/x\u0000/manifest.json',
        'https://addon.example/x\u0007/manifest.json',
        'https://addon.example/x /manifest.json',
        `https://addon.example/${'a'.repeat(2000)}`,
    ]) assert.equal(normalizeBase(bad), null, JSON.stringify(bad.slice(0, 40)))
    for (const wrong of [null, undefined, 5, {}, [], true]) assert.equal(normalizeBase(wrong), null)
})

test('normalizeBase lets plain http through only on this machine', () => {
    assert.equal(normalizeBase('http://127.0.0.1:7000/t/manifest.json'), 'http://127.0.0.1:7000/t')
    assert.equal(normalizeBase('http://localhost:7000/t/manifest.json'), 'http://localhost:7000/t')
    assert.equal(normalizeBase('http://[::1]:7000/t/manifest.json'), 'http://[::1]:7000/t')
    assert.equal(normalizeBase('http://192.168.1.5/t/manifest.json'), null)
    assert.equal(normalizeBase('http://localhost.evil.example/t/manifest.json'), null)
})

test('listUrl and idFor build the list address only for ids the addon knows', () => {
    assert.equal(listUrl(BASE, 'movie', 'tt1234567'), `${BASE}/subtitles/movie/tt1234567.json`)
    assert.equal(listUrl(BASE, 'series', 'tt1234567:2:5'), `${BASE}/subtitles/series/tt1234567:2:5.json`)
    assert.equal(listUrl(BASE, 'anime', 'kitsu:1:1'), `${BASE}/subtitles/anime/kitsu:1:1.json`)
    assert.equal(listUrl(BASE, 'movie', '../../x'), null)
    assert.equal(listUrl(BASE, 'movie', 'tt1?x=1'), null)
    assert.equal(listUrl(BASE, 'music' as never, 'tt1234567'), null)
    assert.equal(idFor('tt1234567', 2, 5), 'tt1234567:2:5')
    assert.equal(idFor('tt1234567', null, null), 'tt1234567')
    assert.equal(idFor('tt1234567', 2, null), 'tt1234567')
    assert.equal(idFor(null, 1, 1), null)
    assert.equal(idFor('nm123', 1, 1), null)
    assert.equal(idFor('tt1234567', 1e9, 1), 'tt1234567')
})

// ---- parseList -----------------------------------------------------------------------------------

test('parseList reads files and Make entries and leaves the toolbox out', () => {
    const list = parseList({
        subtitles: [
            { id: 'sub_toolbox', lang: 'eng', label: 'Toolbox', url: 'https://addon.example/toolbox' },
            { id: 's1', lang: 'eng', label: 'English', url: 'https://addon.example/f/1.srt' },
            { id: 'translate_mal', lang: 'Make Malayalam', url: 'https://addon.example/translate/mal' },
            { id: 'x', lang: 'Make Brazilian Portuguese', url: 'https://addon.example/translate/pt' },
            { id: 'translate_fra', lang: 'fra', url: 'https://addon.example/f/translate/2' },
            { id: 's2', lang: 'mal' , url: 'https://addon.example/f/2.srt' },
        ],
    }, BASE_HOST)
    assert.deepEqual(
        list.map((e) => [e.id, e.make]),
        [['s1', undefined], ['translate_mal', 'Malayalam'], ['x', 'Brazilian Portuguese'], ['translate_fra', 'fra'], ['s2', undefined]],
    )
    assert.equal(list[0].label, 'English')
    assert.equal(list[4].label, 'mal')
    assert.ok(!('make' in list[0]))
})

test('parseList survives garbage, wrong types and a flood', () => {
    for (const bad of [null, undefined, 5, 'x', [], {}, { subtitles: 'x' }, { subtitles: {} }, { subtitles: [null, 1, 'a', [], {}] }]) assert.deepEqual(parseList(bad, BASE_HOST), [])
    const mixed = parseList({
        subtitles: [
            { id: 5, lang: 'eng', url: 'https://addon.example/a' },
            { id: 'a', lang: 5, url: 'https://addon.example/a' },
            { id: 'a', lang: 'eng', url: 5 },
            { id: 'a', lang: 'eng', url: 'http://addon.example/a' },
            { id: 'a', lang: 'eng', url: 'javascript:alert(1)' },
            { id: 'a', lang: 'eng', url: 'not a url' },
            { id: 'a'.repeat(400), lang: 'eng', url: 'https://addon.example/a' },
            { id: 'a', lang: 'e'.repeat(200), url: 'https://addon.example/a' },
            { id: 'a', lang: 'eng', label: { x: 1 }, url: 'https://addon.example/a' },
            { id: 'loop', lang: 'eng', url: 'http://127.0.0.1:7000/a.srt' },
        ],
    }, BASE_HOST)
    assert.deepEqual(mixed.map((e) => e.id), ['a'])
    assert.equal(mixed[0].label, 'eng')
    // A loopback address is fine for a loopback install, and for no other base.
    const loop = { subtitles: [{ id: 'loop', lang: 'eng', url: 'http://127.0.0.1:7000/a.srt' }] }
    assert.deepEqual(parseList(loop, 'http://127.0.0.1:7000/tok').map((e) => e.id), ['loop'])
    const many = { subtitles: Array.from({ length: 50_000 }, (_, i) => ({ id: `s${i}`, lang: 'eng', url: `https://addon.example/${i}` })) }
    assert.equal(parseList(many, BASE_HOST).length, MAX_ENTRIES)
    const junk = { subtitles: Array.from({ length: 50_000 }, () => null) }
    assert.deepEqual(parseList(junk, BASE_HOST), [])
})

test('parseList drops an entry on another host than the base, and everything without a usable base', () => {
    const json = {
        subtitles: [
            { id: 'a', lang: 'eng', url: 'https://addon.example/x/a.srt' },
            { id: 'b', lang: 'eng', url: 'https://evil.example/x/b.srt' },
            { id: 'c', lang: 'eng', url: 'https://ADDON.example/x/c.srt' },
            { id: 'd', lang: 'eng', url: 'https://addon.example.evil.example/d.srt' },
            { id: 'e', lang: 'eng', url: 'https://user:pw@addon.example/e.srt' },
            { id: 'f', lang: 'Make French', url: 'https://cdn.example/translate/f' },
        ],
    }
    assert.deepEqual(parseList(json, BASE).map((e) => e.id), ['a', 'c'])
    assert.deepEqual(parseList(json, 'not a base'), [])
    assert.deepEqual(parseList(json, ''), [])
})

test('fetchTextWith ends a stuck request after its timeout, and a caller abort still ends it at once', async () => {
    const stuck = ((_url: string, init: RequestInit) =>
        new Promise((_resolve, reject) => init.signal?.addEventListener('abort', () => reject(new Error('aborted'))))) as unknown as typeof fetch
    const started = Date.now()
    await assert.rejects(fetchTextWith(stuck, 30)('https://addon.example/x', new AbortController().signal))
    assert.ok(Date.now() - started < 2000)
    const ctl = new AbortController()
    const pending = fetchTextWith(stuck, 60_000)('https://addon.example/x', ctl.signal)
    ctl.abort()
    await assert.rejects(pending)
    assert.equal(FETCH_TIMEOUT_MS, 20_000)
})

test('fetchTextWith works where AbortSignal.any is missing (a timer of its own)', async () => {
    const real = (AbortSignal as { any?: unknown }).any
    ;(AbortSignal as { any?: unknown }).any = undefined
    try {
        const stuck = ((_url: string, init: RequestInit) =>
            new Promise((_resolve, reject) => init.signal?.addEventListener('abort', () => reject(new Error('aborted'))))) as unknown as typeof fetch
        await assert.rejects(fetchTextWith(stuck, 30)('https://addon.example/x', new AbortController().signal))
        const fine = (async () => new Response('hi', { status: 200 })) as unknown as typeof fetch
        assert.deepEqual(await fetchTextWith(fine, 30)('https://addon.example/x', new AbortController().signal), { status: 200, text: 'hi', retryAfter: null })
    } finally {
        ;(AbortSignal as { any?: unknown }).any = real
    }
})

test('429 answers are no stall: three minutes of them do not make the job final', async () => {
    const w = world()
    const busy: Answer = { status: 429, text: null, retryAfter: '60' }
    const out = await run(w, [ok(srt(20)), busy, busy, busy, ok(srt(20))])
    assert.equal(out.state, 'done')
    // 1 growth, 3 waits, then 18 calm polls without growth: 22 calls, and one final update at the end.
    assert.equal(w.calls, 22)
    assert.deepEqual(w.updates.map((u) => u.final), [false, true])
})

test('an addon that stays busy ends the job partial, shown and not final', async () => {
    const w = world()
    const busy: Answer = { status: 429, text: null, retryAfter: '60' }
    const out = await run(w, [ok(srt(20)), busy])
    assert.equal(out.state, 'failed')
    assert.equal(out.state === 'failed' && out.why, 'offline')
    assert.ok(w.updates.every((u) => !u.final))
    assert.equal(w.updates.at(-1)!.lines, 20)
    assert.equal(w.calls, 1 + 10)
    // No cues at all and always busy: a failure with nothing shown.
    const w2 = world()
    const none = await run(w2, [busy])
    assert.equal(none.state, 'failed')
    assert.equal(w2.updates.length, 0)
})

// ---- reading SubRip ------------------------------------------------------------------------------

test('cueCount, isPlaceholder and lastCueEndMs read SubRip and shrug at anything else', () => {
    assert.equal(cueCount(srt(5)), 5)
    assert.equal(cueCount(srt(3).replace(/\n/g, '\r\n')), 3)
    assert.equal(cueCount(''), 0)
    assert.equal(cueCount('<html>not found</html>'), 0)
    assert.equal(cueCount(42), 0)
    assert.equal(cueCount(null), 0)
    assert.equal(isPlaceholder(PLACEHOLDER), true)
    assert.equal(isPlaceholder(PLACEHOLDER.replace('TRANSLATION IN PROGRESS', 'translation in  progress')), true)
    assert.equal(isPlaceholder(srt(4)), false)
    assert.equal(isPlaceholder('nothing'), false)
    // A later cue that says it does not make a real subtitle a placeholder.
    assert.equal(isPlaceholder(srt(2) + '\n3\n00:00:09,000 --> 00:00:10,000\nTRANSLATION IN PROGRESS\n'), false)
    assert.equal(lastCueEndMs(srt(3)), 5000)
    assert.equal(lastCueEndMs(''), 0)
    assert.equal(lastCueEndMs(undefined), 0)
    assert.equal(lastCueEndMs('1\n01:02:03,004 --> 01:02:04,500\nHi\n'), 3_724_500)
    assert.equal(cueCount('x'.repeat(MAX_BODY + 1)), 0)
})

// ---- the poller ----------------------------------------------------------------------------------

test('a translation: placeholder first, then growing cues, then a flat tail ends it', async () => {
    const w = world()
    const out = await run(w, [ok(PLACEHOLDER), ok(srt(10)), ok(srt(30)), ok(srt(60)), ok(srt(60))])
    assert.equal(out.state, 'done')
    assert.equal(out.state === 'done' && out.lines, 60)
    // The placeholder is not an update; each growth is; the last, final, says the same count.
    assert.deepEqual(w.updates.map((u) => [u.lines, u.final]), [[10, false], [30, false], [60, false], [60, true]])
    assert.equal(w.updates.at(-1)!.srt, srt(60))
    // Stable for 180 s of polling every 10 s: the 4th answer grew, then 18 flat ones.
    assert.ok(w.calls >= 19 && w.calls <= 23, `calls ${w.calls}`)
    assert.ok(w.slept.every((ms) => ms === 10_000))
})

test('the last cue reaching the video end finishes it at once', async () => {
    const w = world()
    const out = await run(w, [ok(PLACEHOLDER), ok(srt(10)), ok(srt(100))], { sourceEndMs: 190_000 })
    assert.equal(out.state, 'done')
    assert.equal(w.calls, 3)
    assert.equal(w.updates.at(-1)!.final, true)
    // Without a source end the same answers keep it waiting.
    const w2 = world()
    await run(w2, [ok(PLACEHOLDER), ok(srt(10)), ok(srt(100))])
    assert.ok(w2.calls > 10)
})

test('a placeholder that never gives way fails as empty at the cap', async () => {
    const w = world()
    const out = await run(w, [ok(PLACEHOLDER)])
    assert.equal(out.state, 'failed')
    assert.equal(out.state === 'failed' && out.why, 'empty')
    assert.equal(w.updates.length, 0)
    assert.ok(w.t - 1_000_000 <= 380_000)
})

test('the cap ends a translation that keeps growing, with what it has', async () => {
    const w = world()
    const out = await run(w, [(n) => ok(srt(n * 5 + 5))], { capMs: 60_000 })
    assert.equal(out.state, 'done')
    // At the cap the text is shown but is not the final one: only the end or a calm stretch is.
    assert.ok(!w.updates.at(-1)!.final)
    assert.ok(w.calls <= 8)
})

test('429 honours Retry-After, else doubles up to a minute', async () => {
    const w = world()
    const out = await run(w, [{ status: 429, text: null, retryAfter: '7' }, ok(PLACEHOLDER), ok(srt(4))], { stableMs: 20_000 })
    assert.equal(out.state, 'done')
    assert.equal(w.slept[0], 7000)
    const w2 = world()
    await run(w2, [{ status: 429, text: null }, { status: 429, text: null }, { status: 429, text: null }, { status: 429, text: null }, { status: 429, text: null }, ok(srt(3))], { stableMs: 20_000 })
    assert.deepEqual(w2.slept.slice(0, 5), [10_000, 20_000, 40_000, 60_000, 60_000])
    // A huge Retry-After is held to a minute.
    const w3 = world()
    await run(w3, [{ status: 429, text: null, retryAfter: '86400' }, ok(srt(3))], { stableMs: 20_000 })
    assert.equal(w3.slept[0], 60_000)
})

test('failures: network error, 5xx, 4xx, garbage, huge, wrong types', async () => {
    const net = await run(world(), [new Error('boom https://addon.example/' + SECRET)])
    assert.deepEqual([net.state, net.state === 'failed' && net.why], ['failed', 'offline'])
    const server = await run(world(), [{ status: 503, text: null }])
    assert.deepEqual([server.state, server.state === 'failed' && server.why], ['failed', 'offline'])
    const gone = await run(world(), [{ status: 404, text: null }])
    assert.deepEqual([gone.state, gone.state === 'failed' && gone.why], ['failed', 'refused'])
    const w = world()
    const garbage = await run(w, [ok('<html>Welcome</html>')])
    assert.deepEqual([garbage.state, garbage.state === 'failed' && garbage.why], ['failed', 'unreadable'])
    assert.equal(w.updates.length, 0)
    const big = await run(world(), [ok(null)])
    assert.deepEqual([big.state, big.state === 'failed' && big.why], ['failed', 'too_big'])
    const huge = await run(world(), [ok('x'.repeat(MAX_BODY + 1))])
    assert.deepEqual([huge.state, huge.state === 'failed' && huge.why], ['failed', 'too_big'])
    const wrong = await run(world(), [{ status: 200, text: 42 as unknown as string }])
    assert.deepEqual([wrong.state, wrong.state === 'failed' && wrong.why], ['failed', 'unreadable'])
    const nothing = await run(world(), [undefined as unknown as Answer])
    assert.equal(nothing.state, 'failed')
})

test('a hiccup or two does not end a translation that has begun', async () => {
    const w = world()
    const out = await run(w, [ok(PLACEHOLDER), ok(srt(5)), new Error('x'), { status: 502, text: null }, ok(srt(9))], { stableMs: 30_000 })
    assert.equal(out.state, 'done')
    assert.equal(out.state === 'done' && out.lines, 9)
})

test('a failure after cues keeps what arrived, for a Try again to say so', async () => {
    const w = world()
    const out = await run(w, [ok(srt(5)), { status: 500, text: null }])
    assert.equal(out.state, 'failed')
    assert.equal(out.state === 'failed' && out.lines, 5)
})

test('a shorter answer later does not shrink what is shown', async () => {
    const w = world()
    await run(w, [ok(srt(20)), ok(srt(7)), ok(srt(20))], { stableMs: 20_000 })
    assert.deepEqual(w.updates.map((u) => u.lines), [20, 20])
})

test('cancel by signal and by the job stops the asking', async () => {
    const w = world()
    const ctl = new AbortController()
    const seen: number[] = []
    const out = await translate(
        options(w, addon(w, [ok(PLACEHOLDER), ok(srt(3)), ok(srt(6))]), {
            signal: ctl.signal,
            onUpdate: (u) => (seen.push(u.lines), ctl.abort()),
        }),
    ).done
    assert.equal(out.state, 'cancelled')
    assert.deepEqual(seen, [3])
    assert.equal(w.calls, 2)
    const w2 = world()
    const job = translate(options(w2, addon(w2, [ok(PLACEHOLDER)])))
    job.cancel()
    assert.equal((await job.done).state, 'cancelled')
    assert.equal(w2.calls, 0)
    const w3 = world()
    const early = new AbortController()
    early.abort()
    assert.equal((await translate(options(w3, addon(w3, [ok(srt(3))]), { signal: early.signal })).done).state, 'cancelled')
    assert.equal(w3.calls, 0)
})

test('nothing is asked while the tab is hidden, and that time does not count', async () => {
    const w = world()
    let hiddenCalls = -1
    let hiddenFor = 0
    const sleep = async (ms: number) => {
        w.slept.push(ms)
        w.t += ms
        // After the second answer the tab goes away for an hour, then comes back.
        if (w.calls === 2 && hiddenCalls < 0) {
            w.visible = false
            hiddenCalls = w.calls
        } else if (!w.visible && ++hiddenFor === 360) w.visible = true
    }
    const out = await translate({ ...options(w, addon(w, [ok(PLACEHOLDER), ok(srt(5)), ok(srt(8))])), sleep }).done
    assert.equal(hiddenCalls, 2)
    assert.equal(hiddenFor, 360)
    assert.equal(out.state, 'done')
    // The hour hidden never reached the fetch, and the cap (6 minutes of foreground) was not spent by it.
    assert.ok(w.calls >= 20 && w.calls < 30, `calls ${w.calls}`)
    assert.equal(out.state === 'done' && out.lines, 8)
})

test('a tab hidden from the start waits, then goes', async () => {
    const w = world()
    w.visible = false
    let polls = 0
    const sleep = async (ms: number) => {
        w.t += ms
        if (++polls === 5) w.visible = true
    }
    const out = await translate({ ...options(w, addon(w, [ok(srt(3))]), { stableMs: 20_000 }), sleep }).done
    assert.equal(out.state, 'done')
    assert.ok(polls >= 5)
})

test('one job per video and language: a second start joins the first', async () => {
    const w = world()
    const a = startJob('1:2', 'Malayalam', options(w, addon(w, [ok(PLACEHOLDER)])))
    const b = startJob('1:2', ' malayalam ', options(w, addon(w, [ok(PLACEHOLDER)])))
    assert.equal(a, b)
    assert.equal(jobFor('1:2', 'Malayalam'), a)
    assert.notEqual(startJob('1:2', 'Hindi', options(w, addon(w, [ok(PLACEHOLDER)]))), a)
    assert.notEqual(startJob('1:3', 'Malayalam', options(w, addon(w, [ok(PLACEHOLDER)]))), a)
    a.cancel()
    assert.equal(jobFor('1:2', 'Malayalam'), undefined)
    const c = startJob('1:2', 'Malayalam', options(w, addon(w, [ok(PLACEHOLDER)])))
    assert.notEqual(c, a)
    await a.done
    assert.equal(jobFor('1:2', 'Malayalam'), c)
    clearJobs()
    assert.equal(jobFor('1:2', 'Malayalam'), undefined)
})

test('a finished job leaves the map, so Try again starts afresh', async () => {
    const w = world()
    const a = startJob('9:9', 'Hindi', options(w, addon(w, [{ status: 404, text: null }])))
    assert.equal((await a.done).state, 'failed')
    await Promise.resolve()
    assert.equal(jobFor('9:9', 'Hindi'), undefined)
})

test('what a failed job reports never holds the address', async () => {
    const w = world()
    const out = await run(w, [new Error(`fetch ${BASE}/translate/abc.srt failed`)])
    const said = JSON.stringify(out)
    assert.ok(!said.includes(SECRET))
    assert.ok(!said.includes('addon.example'))
})

// ---- fetching with the page's fetch ----------------------------------------------------------------

test('fetchTextWith sends no cookies and no referrer, refuses a body over 4 MB, and reports Retry-After', async () => {
    const seen: RequestInit[] = []
    const f = (async (_url: string, init: RequestInit) => {
        seen.push(init)
        return new Response('hello', { status: 200 })
    }) as unknown as typeof fetch
    const ctl = new AbortController()
    assert.deepEqual(await fetchTextWith(f)('https://addon.example/x', ctl.signal), { status: 200, text: 'hello', retryAfter: null })
    assert.equal(seen[0].credentials, 'omit')
    assert.equal(seen[0].referrerPolicy, 'no-referrer')
    const bigStream = (async () => new Response(new Uint8Array(MAX_BODY + 10), { status: 200 })) as unknown as typeof fetch
    assert.equal((await fetchTextWith(bigStream)('https://addon.example/x', ctl.signal)).text, null)
    const declared = (async () => new Response('x', { status: 200, headers: { 'Content-Length': String(MAX_BODY + 1) } })) as unknown as typeof fetch
    assert.equal((await fetchTextWith(declared)('https://addon.example/x', ctl.signal)).text, null)
    const limited = (async () => new Response('', { status: 429, headers: { 'Retry-After': '12' } })) as unknown as typeof fetch
    assert.deepEqual(await fetchTextWith(limited)('https://addon.example/x', ctl.signal), { status: 429, text: null, retryAfter: '12' })
})

test('fetchList and fetchSubtitle turn every bad answer into a reason, not an exception', async () => {
    const ctl = new AbortController()
    const list = (a: Answer | Error): FetchText => async () => {
        if (a instanceof Error) throw a
        return a
    }
    const good = await fetchList(`${BASE_HOST}/subtitles/movie/tt1234567.json`, list(ok(JSON.stringify({ subtitles: [{ id: 'a', lang: 'eng', url: 'https://addon.example/a' }] }))), ctl.signal)
    assert.equal(good.ok && good.entries.length, 1)
    for (const bad of [ok('not json'), ok(null), ok('{"subtitles":5}'), { status: 404, text: null }, { status: 503, text: null }, new Error('x')]) {
        const r = await fetchList('u', list(bad), ctl.signal)
        if (r.ok) assert.deepEqual(r.entries, [])
        else assert.ok(['offline', 'unreadable'].includes(r.why))
    }
    assert.equal((await fetchSubtitle('u', list(ok(srt(3))), ctl.signal)).ok, true)
    assert.equal((await fetchSubtitle('u', list(ok(PLACEHOLDER)), ctl.signal)).ok, false)
    assert.equal((await fetchSubtitle('u', list(ok('<html></html>')), ctl.signal)).ok, false)
    assert.equal((await fetchSubtitle('u', list(new Error('x')), ctl.signal)).ok, false)
    ctl.abort()
    const stopped = await fetchSubtitle('u', list(ok(srt(3))), ctl.signal)
    assert.deepEqual(stopped, { ok: false, why: 'aborted' })
})

// ---- consent ---------------------------------------------------------------------------------------

test('consent is remembered per language', () => {
    const data = new Map<string, string>()
    const memory = { getItem: (k: string) => data.get(k) ?? null, setItem: (k: string, v: string) => void data.set(k, v) }
    assert.equal(hasConsent('Malayalam', memory), false)
    giveConsent('Malayalam', memory)
    assert.equal(hasConsent(' malayalam ', memory), true)
    assert.equal(hasConsent('Hindi', memory), false)
    giveConsent('Malayalam', memory)
    assert.equal(JSON.parse(data.get('tmplayer-submaker-consent')!).length, 1)
    // Storage that holds rubbish, or none at all, asks again and never throws.
    data.set('tmplayer-submaker-consent', '{"x":1}')
    assert.equal(hasConsent('Malayalam', memory), false)
    data.set('tmplayer-submaker-consent', 'not json')
    assert.equal(hasConsent('Malayalam', memory), false)
    assert.equal(hasConsent('Malayalam', null), false)
    giveConsent('Malayalam', null)
    const refusing = { getItem: () => { throw new Error('no') }, setItem: () => { throw new Error('no') } }
    assert.equal(hasConsent('Malayalam', refusing), false)
    giveConsent('Malayalam', refusing)
})
