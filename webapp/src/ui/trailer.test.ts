// The web's port of core's TrailerEmbed and TrailerRules: the same cases as core's TrailerEmbedTest
// and TrailerRulesTest, so a change on one side shows on the other.
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { embedUrl, failure, fallsBack, inApp, isValidKey, LOADING, next, parseMessage, parseWidgetMessage, READY_TIMEOUT_MS, timedOut, watchUrl, type Message, type State } from './trailer.ts'

const key = 'YE7VzlLtp-4'

test('only an eleven character YouTube id is a key', () => {
    for (const good of ['YE7VzlLtp-4', 'dQw4w9WgXcQ', '___________', '-----------', 'aZ09_-aZ09_']) assert.equal(isValidKey(good), true, good)
    const bad = [
        null, undefined, 7, {}, '', 'short', 'YE7VzlLtp-', 'YE7VzlLtp-44', 'YE7VzlLtp 4', 'YE7VzlLtp+4', 'YE7VzlLtp/4', 'YE7VzlLtp.4',
        'YE7Vz"><x>4', 'YE7VzlLtp\n4', '١٢٣٤٥٦٧٨٩٠١', 'ＡＢＣＤＥＦＧＨＩＪＫ', 'YE7VzlLtpé4', 'YE7VzlLtp\u00004', 'a'.repeat(100_000),
    ]
    for (const b of bad) assert.equal(isValidKey(b), false, String(b).slice(0, 20))
})

test('the embed is the no-cookie host with its player switches and the origin', () => {
    assert.equal(embedUrl(key), 'https://www.youtube-nocookie.com/embed/YE7VzlLtp-4?autoplay=1&playsinline=1&rel=0&enablejsapi=1&origin=https%3A%2F%2Fwww.youtube-nocookie.com')
    assert.equal(embedUrl(key, 'http://localhost:5173'), 'https://www.youtube-nocookie.com/embed/YE7VzlLtp-4?autoplay=1&playsinline=1&rel=0&enablejsapi=1&origin=http%3A%2F%2Flocalhost%3A5173')
    assert.equal(embedUrl('nope'), null)
    assert.equal(embedUrl('YE7Vz"><x>4'), null)
    for (const origin of ['', 'file:///', 'javascript:alert(1)', 'https://', 'https://a.b/path', 'https://a b', 'https://x:port', 'https://x:123456', 'https://ex"ample.com']) {
        assert.equal(embedUrl(key, origin), null, origin)
    }
})

test('the embed carries no tracking extras', () => {
    const url = embedUrl(key)!
    for (const extra of ['widget_referrer', 'modestbranding', 'iv_load_policy', 'cc_load_policy', 'list=', 'playlist', 'mute', 'controls=0']) assert.equal(url.includes(extra), false, extra)
})

test('the external link is the watch page, and only for a key', () => {
    assert.equal(watchUrl(key), 'https://www.youtube.com/watch?v=YE7VzlLtp-4')
    assert.equal(watchUrl('x"><y>'), null)
    assert.equal(watchUrl(null), null)
})

test("the player's messages are read and anything else is nothing", () => {
    assert.deepEqual(parseMessage('ready'), { kind: 'ready' })
    assert.deepEqual(parseMessage(' ready\n'), { kind: 'ready' })
    assert.deepEqual(parseMessage('state:1'), { kind: 'state', code: 1 })
    assert.deepEqual(parseMessage('state:-1'), { kind: 'state', code: -1 })
    assert.deepEqual(parseMessage('error:150'), { kind: 'error', code: 150 })
    assert.deepEqual(parseMessage('error:-1'), { kind: 'error', code: -1 })
    for (const raw of [null, undefined, 5, {}, '', 'Ready', 'state', 'state:', 'state:x', 'state:9', 'state:99999', 'error:1000', 'error:', 'error:1:2', 'play:1', 'state:１', 'state:١', 'x'.repeat(10_000)]) {
        assert.equal(parseMessage(raw), null, String(raw).slice(0, 20))
    }
})

test("YouTube's own posts are read through the same checks", () => {
    assert.deepEqual(parseWidgetMessage('{"event":"onReady"}'), { kind: 'ready' })
    assert.deepEqual(parseWidgetMessage('{"event":"onStateChange","info":1}'), { kind: 'state', code: 1 })
    assert.deepEqual(parseWidgetMessage('{"event":"onError","info":150}'), { kind: 'error', code: 150 })
    for (const raw of [
        null, undefined, 3, {}, [], '', 'ready', 'null', '[]', '"x"', '{', '{"event":"infoDelivery","info":{"currentTime":1}}', '{"event":"onStateChange"}',
        '{"event":"onStateChange","info":9}', '{"event":"onStateChange","info":"1"}', '{"event":"onStateChange","info":1.5}', '{"event":"onError","info":1000}',
        '{"event":"onError","info":null}', '{"event":"onReady' + 'x'.repeat(5_000) + '"}', '{"event":{"a":1},"info":2}',
    ]) {
        assert.equal(parseWidgetMessage(raw), null, String(raw).slice(0, 30))
    }
})

test('in the app only with the setting on and a real key', () => {
    assert.equal(inApp(true, key), true)
    assert.equal(inApp(false, key), false)
    for (const bad of [null, '', 'promoharbour', 'x"><y>', '١٢٣٤٥٦٧٨٩٠١']) assert.equal(inApp(true, bad), false, String(bad))
})

const ready = (): State => next(LOADING, { kind: 'ready' })
const playing = (): State => ({ phase: 'playing', failure: null })

test('every YouTube error falls back with its reason', () => {
    const expected = { 2: 'broken', 5: 'broken', 100: 'notFound', 101: 'notEmbeddable', 150: 'notEmbeddable', 153: 'notEmbeddable' } as const
    for (const [code, why] of Object.entries(expected)) {
        const after = next(playing(), { kind: 'error', code: Number(code) })
        assert.equal(fallsBack(after), true, code)
        assert.equal(after.failure, why, code)
    }
    assert.equal(next(LOADING, { kind: 'error', code: 999 }).failure, 'broken')
    assert.equal(failure(152), 'notEmbeddable')
    assert.equal(next(LOADING, { kind: 'error', code: -1 }).failure, 'unreachable')
})

test('no ready within twelve seconds falls back and ready in time does not', () => {
    assert.equal(READY_TIMEOUT_MS, 12_000)
    assert.equal(LOADING.phase, 'loading')
    const late = timedOut(LOADING)
    assert.equal(fallsBack(late), true)
    assert.equal(late.failure, 'timedOut')
    assert.equal(ready().phase, 'ready')
    assert.deepEqual(timedOut(ready()), ready())
    // Playing before the ready message arrived counts as ready.
    const early = next(LOADING, { kind: 'state', code: 1 })
    assert.equal(early.phase, 'playing')
    assert.deepEqual(timedOut(early), early)
})

test("the player's states are followed", () => {
    let state = ready()
    const seen = [3, 1, 2, 1, 0, 5, -1].map((code) => (state = next(state, { kind: 'state', code })).phase)
    assert.deepEqual(seen, ['buffering', 'playing', 'paused', 'playing', 'ended', 'ready', 'ready'])
    assert.equal(next(LOADING, { kind: 'state', code: 5 }).phase, 'loading')
    assert.equal(next(LOADING, { kind: 'state', code: -1 }).phase, 'loading')
})

test('a failed trailer stays failed and an unreadable message changes nothing', () => {
    const failed = next(playing(), { kind: 'error', code: 150 })
    for (const m of [{ kind: 'ready' }, { kind: 'state', code: 1 }, { kind: 'error', code: 2 }] as Message[]) assert.deepEqual(next(failed, m), failed)
    assert.deepEqual(timedOut(failed), failed)
    assert.deepEqual(next(playing(), parseMessage('rubbish')), playing())
    assert.equal(LOADING.failure, null)
})
