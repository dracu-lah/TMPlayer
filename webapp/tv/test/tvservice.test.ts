// node --test: the page side of the webOS bridge (src/stream/tvservice.ts) against the real bridge.js,
// with a fake TV player asking for ranges. Run: pnpm test:tv
import assert from 'node:assert/strict'
import crypto from 'node:crypto'
import { createRequire } from 'node:module'
import path from 'node:path'
import { test } from 'node:test'
import { MAX_FAILS, bridgeAsked, parseFragment, startTvService, stopTvService, tvServiceUp, tvServiceUrl } from '../../src/stream/tvservice.ts'

const require = createRequire(import.meta.url)
const { createBridge } = require(path.join(import.meta.dirname, '..', 'webos', 'service', 'org.tmplayer.tv.service', 'bridge.js'))
const ORIGIN = 'https://web.tmplayer.org'
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))

/** fetch as a browser sends it from the page: with the page's Origin. */
const pageFetch: typeof fetch = (input, init) => fetch(input, { ...init, headers: { ...(init?.headers as object), Origin: ORIGIN } })

async function boot() {
    const bridge = createBridge({ origin: ORIGIN, port: 0 })
    await new Promise<void>((res, rej) => bridge.start((e: Error | null) => (e ? rej(e) : res())))
    return bridge
}
const stopBridge = (b: { stop: (cb: () => void) => void }) => new Promise<void>((res) => b.stop(res))
const hashOf = (b: { port: () => number; token: () => string }) => `#tvsvc=${b.port()}.${b.token()}`

function files(map: Record<string, Buffer>, opts: { slowMs?: number; fail?: boolean } = {}) {
    return (key: string) => {
        const f = map[key]
        if (!f) return undefined
        return {
            async read(s: number, e: number) {
                if (opts.slowMs) await sleep(opts.slowMs)
                if (opts.fail) throw new Error('boom')
                return new Uint8Array(f.subarray(s, Math.min(e, f.length)))
            },
        }
    }
}

/** The TV's player: a plain request with a Range and no Origin. */
async function tvGet(url: string, range?: string) {
    const r = await fetch(url, { headers: range ? { Range: range } : {} })
    return { status: r.status, body: Buffer.from(await r.arrayBuffer()), headers: r.headers }
}

test('fragment parsing', () => {
    assert.deepEqual(parseFragment('#tvsvc=3190.abcdef0123456789'), { port: 3190, token: 'abcdef0123456789' })
    assert.equal(parseFragment(''), null)
    assert.equal(parseFragment('#tvsvc=0.abcdef0123456789'), null)
    assert.equal(parseFragment('#tvsvc=70000.abcdef0123456789'), null)
    assert.equal(parseFragment('#tvsvc=80.short'), null)
    assert.equal(parseFragment('#tvsvc=80.abc def0123456789'), null)
})

test('plays a file through the bridge, strips the fragment, remembers the key was asked', async () => {
    const bridge = await boot()
    const file = crypto.randomBytes(3 * 1024 * 1024 + 7)
    let stripped = 0
    try {
        assert.equal(tvServiceUrl('k', 1, 'video/mp4'), null)
        assert.equal(await startTvService(files({ k: file }), { hash: hashOf(bridge), fetch: pageFetch, strip: () => void stripped++ }), true)
        assert.equal(stripped, 1)
        assert.ok(tvServiceUp())
        assert.equal(bridgeAsked('k'), false)
        const url = tvServiceUrl('k', file.length, 'video/mp4; codecs="hvc1.1.6.L153.90"')!
        assert.match(url, /^http:\/\/127\.0\.0\.1:\d+\/stream\/k\?token=[0-9a-f]+&size=\d+&mime=video%2Fmp4$/)
        const parts: Buffer[] = []
        let pos = 0
        while (pos < file.length) {
            const r = await tvGet(url, `bytes=${pos}-`)
            assert.equal(r.status, 206)
            parts.push(r.body)
            pos += r.body.length
        }
        assert.deepEqual(Buffer.concat(parts), file)
        assert.ok(bridgeAsked('k'))
        const tail = await tvGet(url, 'bytes=-100')
        assert.deepEqual(tail.body, file.subarray(file.length - 100))
    } finally {
        stopTvService()
        await stopBridge(bridge)
    }
})

test('no fragment, a wrong token or a dead port leaves the page on its other routes', async () => {
    assert.equal(await startTvService(files({}), { hash: '', fetch: pageFetch }), false)
    const bridge = await boot()
    try {
        assert.equal(await startTvService(files({}), { hash: `#tvsvc=${bridge.port()}.${'0'.repeat(36)}`, fetch: pageFetch }), false)
        assert.equal(tvServiceUp(), false)
        const dead = bridge.port()
        await stopBridge(bridge)
        assert.equal(await startTvService(files({}), { hash: `#tvsvc=${dead}.${'a'.repeat(36)}`, fetch: pageFetch }), false)
    } finally {
        stopTvService()
    }
})

test('a file the player closed answers gone (410), a failing read answers 502', async () => {
    const bridge = await boot()
    try {
        await startTvService(files({ bad: Buffer.alloc(100) }, { fail: true }), { hash: hashOf(bridge), fetch: pageFetch })
        assert.equal((await tvGet(tvServiceUrl('missing', 100, 'video/mp4')!)).status, 410)
        assert.equal((await tvGet(tvServiceUrl('bad', 100, 'video/mp4')!)).status, 502)
    } finally {
        stopTvService()
        await stopBridge(bridge)
    }
})

test('a seek that closes a request does not send its bytes, the next range still plays', async () => {
    const bridge = await boot()
    const file = crypto.randomBytes(1_000_000)
    try {
        await startTvService(files({ k: file }, { slowMs: 300 }), { hash: hashOf(bridge), fetch: pageFetch })
        const url = tvServiceUrl('k', file.length, 'video/mp4')!
        const ctl = new AbortController()
        const first = fetch(url, { headers: { Range: 'bytes=0-' }, signal: ctl.signal }).catch(() => null)
        await sleep(100)
        ctl.abort()
        await first
        const r = await tvGet(url, 'bytes=500000-500099')
        assert.deepEqual(r.body, file.subarray(500000, 500100))
        assert.equal(bridge.stats().pending, 0)
    } finally {
        stopTvService()
        await stopBridge(bridge)
    }
})

test('the bridge going away stops the loop after MAX_FAILS and the URL is no longer offered', async () => {
    const bridge = await boot()
    await startTvService(files({}), { hash: hashOf(bridge), fetch: pageFetch, retryMs: 5 })
    assert.ok(tvServiceUp())
    await stopBridge(bridge)
    for (let i = 0; i < 100 && tvServiceUp(); i++) await sleep(50)
    assert.equal(tvServiceUp(), false, `down after ${MAX_FAILS} failed polls`)
    assert.equal(tvServiceUrl('k', 1, 'video/mp4'), null)
})

test('garbage from the bridge is skipped, not thrown', async () => {
    let calls = 0
    const fake: typeof fetch = async (input) => {
        const u = String(input)
        if (u.includes('/ping')) return new Response('{}')
        calls++
        if (calls === 1) return new Response(JSON.stringify({ requests: [null, 5, { id: 'x' }, { id: 1, key: 'k', start: -1, end: 4 }, { id: 2, key: 'k', start: 5, end: 1 }], cancel: ['a', null] }))
        return new Promise(() => {})
    }
    try {
        assert.equal(await startTvService(files({}), { hash: '#tvsvc=9.' + 'a'.repeat(20), fetch: fake, strip: () => {} }), true)
        await sleep(50)
        assert.ok(tvServiceUp())
    } finally {
        stopTvService()
    }
})
