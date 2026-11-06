// Machine test of the webOS loopback bridge (tv/webos/service/org.tmplayer.tv.service): a fake page
// answers reads through the long poll, a fake TV player asks for ranges. Run: pnpm test:tv
'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const http = require('node:http')
const crypto = require('node:crypto')
const fs = require('node:fs')
const path = require('node:path')
const Module = require('node:module')

const DIR = path.join(__dirname, '..', 'webos', 'service', 'org.tmplayer.tv.service')
const { createBridge, createLease, MAX_ANSWER } = require(path.join(DIR, 'bridge.js'))

const ORIGIN = 'https://web.tmplayer.org'
const MiB = 1024 * 1024
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

/** Plain request to the bridge; resolves with { status, headers, body }. */
function request(port, { method = 'GET', pathname, headers = {}, body, host }) {
    return new Promise((resolve, reject) => {
        const req = http.request(
            { host: '127.0.0.1', port, method, path: pathname, headers: { Host: host || `127.0.0.1:${port}`, ...headers }, agent: false },
            (res) => {
                const chunks = []
                res.on('data', (c) => chunks.push(c))
                res.on('end', () => resolve({ status: res.statusCode, headers: res.headers, body: Buffer.concat(chunks) }))
                res.on('error', reject)
            },
        )
        req.on('error', reject)
        req.end(body)
    })
}

/** The TV's player: a request with a Range and no Origin, as a media element sends. */
function tvGet(bridge, key, size, range, { method = 'GET', token = bridge.token(), mime = 'video/mp4' } = {}) {
    const q = `token=${token}&size=${size}&mime=${encodeURIComponent(mime)}`
    return request(bridge.port(), { method, pathname: `/stream/${encodeURIComponent(key)}?${q}`, headers: range ? { Range: range } : {} })
}

/** The page: long-polls, answers each read from `files` (key -> Buffer). `slow` holds answers back. */
class FakePage {
    constructor(bridge, files, { session = 's1', slow = null, onRequest = null } = {}) {
        this.bridge = bridge
        this.files = files
        this.session = session
        this.slow = slow
        this.onRequest = onRequest
        this.running = false
        this.seen = []
        this.cancelled = []
        this.replyStatus = []
    }
    start() {
        this.running = true
        this.loop = this.run()
        return this
    }
    async stop() {
        this.running = false
        this.ctl?.destroy()
        await this.loop?.catch(() => {})
    }
    async run() {
        const port = this.bridge.port()
        while (this.running) {
            let res
            try {
                res = await new Promise((resolve, reject) => {
                    this.ctl = http.request(
                        { host: '127.0.0.1', port, path: `/poll?token=${this.bridge.token()}&session=${this.session}`, headers: { Origin: ORIGIN, Host: `127.0.0.1:${port}` }, agent: false },
                        (r) => {
                            const chunks = []
                            r.on('data', (c) => chunks.push(c))
                            r.on('end', () => resolve(JSON.parse(Buffer.concat(chunks).toString() || '{}')))
                        },
                    )
                    this.ctl.on('error', reject)
                    this.ctl.end()
                })
            } catch {
                return
            }
            for (const id of res.cancel || []) this.cancelled.push(id)
            for (const r of res.requests || []) {
                this.seen.push(r)
                this.answer(r).catch(() => {})
            }
        }
    }
    async answer(r) {
        if (this.onRequest) this.onRequest(r)
        if (this.slow) await this.slow(r)
        const file = this.files[r.key]
        const port = this.bridge.port()
        const token = this.bridge.token()
        const headers = { Origin: ORIGIN }
        const out = file
            ? await request(port, { method: 'POST', pathname: `/reply?token=${token}&id=${r.id}`, headers: { ...headers, 'Content-Length': r.end - r.start + 1 }, body: file.subarray(r.start, r.end + 1) })
            : await request(port, { method: 'POST', pathname: `/reply?token=${token}&id=${r.id}&error=gone`, headers })
        this.replyStatus.push(out.status)
    }
}

async function boot(opts = {}) {
    const bridge = createBridge({ origin: ORIGIN, port: 0, ...opts })
    await new Promise((res, rej) => bridge.start((e) => (e ? rej(e) : res())))
    return bridge
}

const stop = (bridge) => new Promise((res) => bridge.stop(res))

test('plays a whole file in 2 MiB ranges, byte for byte', async () => {
    const bridge = await boot()
    const file = crypto.randomBytes(5 * MiB + 123)
    const page = new FakePage(bridge, { k1: file }).start()
    try {
        const got = []
        let pos = 0
        while (pos < file.length) {
            const r = await tvGet(bridge, 'k1', file.length, `bytes=${pos}-`)
            assert.equal(r.status, 206)
            assert.equal(r.headers['content-type'], 'video/mp4')
            assert.equal(r.headers['accept-ranges'], 'bytes')
            const end = Math.min(file.length - 1, pos + MAX_ANSWER - 1)
            assert.equal(r.headers['content-range'], `bytes ${pos}-${end}/${file.length}`)
            assert.equal(r.body.length, end - pos + 1)
            got.push(r.body)
            pos = end + 1
        }
        assert.equal(crypto.createHash('md5').update(Buffer.concat(got)).digest('hex'), crypto.createHash('md5').update(file).digest('hex'))
        assert.equal(bridge.stats().pending, 0)
    } finally {
        await page.stop()
        await stop(bridge)
    }
})

test('range forms: none, closed, suffix, past the end, HEAD, a mime with codecs', async () => {
    const bridge = await boot()
    const file = crypto.randomBytes(300_000)
    const page = new FakePage(bridge, { k: file }).start()
    try {
        let r = await tvGet(bridge, 'k', file.length, null)
        assert.equal(r.status, 206)
        assert.deepEqual(r.body, file)
        r = await tvGet(bridge, 'k', file.length, 'bytes=10-19')
        assert.deepEqual(r.body, file.subarray(10, 20))
        r = await tvGet(bridge, 'k', file.length, 'bytes=-100')
        assert.equal(r.headers['content-range'], `bytes ${file.length - 100}-${file.length - 1}/${file.length}`)
        assert.deepEqual(r.body, file.subarray(file.length - 100))
        r = await tvGet(bridge, 'k', file.length, `bytes=${file.length}-`)
        assert.equal(r.status, 416)
        assert.equal(r.headers['content-range'], `bytes */${file.length}`)
        r = await tvGet(bridge, 'k', file.length, null, { method: 'HEAD' })
        assert.equal(r.status, 200)
        assert.equal(r.headers['content-length'], String(file.length))
        assert.equal(r.body.length, 0)
        r = await tvGet(bridge, 'k', file.length, 'bytes=0-9', { mime: 'video/mp4; codecs="hvc1.1.6.L153.90, mp4a.40.2"' })
        assert.equal(r.headers['content-type'], 'video/mp4; codecs="hvc1.1.6.L153.90, mp4a.40.2"')
        r = await tvGet(bridge, 'k', file.length, 'bytes=0-9', { mime: 'text/html\r\nX-Evil: 1' })
        assert.equal(r.headers['content-type'], 'video/mp4')
        r = await tvGet(bridge, 'k', 'abc', null)
        assert.equal(r.status, 400)
        r = await tvGet(bridge, 'k', 0, null)
        assert.equal(r.status, 416)
    } finally {
        await page.stop()
        await stop(bridge)
    }
})

test('token, Host and Origin are checked', async () => {
    const bridge = await boot()
    const port = bridge.port()
    const t = bridge.token()
    try {
        assert.equal(bridge.stats().connected, false)
        const probe = await request(port, { pathname: `/ping?token=${t}`, headers: { Origin: ORIGIN } })
        assert.equal(probe.status, 200)
        assert.deepEqual(JSON.parse(probe.body), { ok: true, v: 1 })
        assert.equal(probe.headers['access-control-allow-origin'], ORIGIN)

        assert.equal((await request(port, { pathname: `/ping?token=wrong`, headers: { Origin: ORIGIN } })).status, 403)
        assert.equal((await request(port, { pathname: `/ping`, headers: { Origin: ORIGIN } })).status, 403)
        assert.equal((await request(port, { pathname: `/ping?token=${t}&token=${t}`, headers: { Origin: ORIGIN } })).status, 403)
        assert.equal((await request(port, { pathname: `/ping?token=${t}`, headers: { Origin: 'https://evil.example' } })).status, 403)
        assert.equal((await request(port, { pathname: `/ping?token=${t}` })).status, 403, 'control endpoints need the page Origin')
        assert.equal((await request(port, { pathname: `/poll?token=${t}` })).status, 403)
        assert.equal((await request(port, { method: 'POST', pathname: `/reply?token=${t}&id=1` })).status, 403)
        assert.equal((await request(port, { pathname: `/ping?token=${t}`, headers: { Origin: ORIGIN }, host: 'evil.example' })).status, 421)
        assert.equal((await request(port, { pathname: `/ping?token=${t}`, headers: { Origin: ORIGIN }, host: `127.0.0.1:${port + 1}` })).status, 421)
        assert.equal((await request(port, { pathname: `/ping?token=${t}`, headers: { Origin: ORIGIN }, host: `localhost:${port}` })).status, 200)
        assert.equal((await tvGet(bridge, 'k', 10, null, { token: 'nope' })).status, 403)
        assert.equal((await request(port, { pathname: `/stream/k?token=${t}&size=10`, headers: { Origin: 'https://evil.example' } })).status, 403)
        assert.equal((await request(port, { pathname: `/nothing?token=${t}`, headers: { Origin: ORIGIN } })).status, 404)
        const pre = await request(port, { method: 'OPTIONS', pathname: `/reply?token=${t}&id=1`, headers: { Origin: ORIGIN, 'Access-Control-Request-Method': 'POST' } })
        assert.equal(pre.status, 204)
        assert.equal(pre.headers['access-control-allow-origin'], ORIGIN)
        assert.match(pre.headers['access-control-allow-methods'], /POST/)
        const pre2 = await request(port, { method: 'OPTIONS', pathname: `/reply?token=${t}&id=1`, headers: { Origin: 'https://evil.example' } })
        assert.equal(pre2.status, 403)
        assert.equal(pre2.headers['access-control-allow-origin'], undefined)
    } finally {
        await stop(bridge)
    }
})

test('takes the next port when one is taken, and is not reachable on the LAN address', async () => {
    const blocker = await new Promise((res) => {
        const s = http.createServer().listen(0, '127.0.0.1', () => res(s))
    })
    const taken = blocker.address().port
    const bridge = createBridge({ origin: ORIGIN, port: taken, portTries: 5 })
    try {
        await new Promise((res, rej) => bridge.start((e) => (e ? rej(e) : res())))
        assert.notEqual(bridge.port(), taken)
        assert.ok(bridge.port() > taken && bridge.port() <= taken + 4)
        const lanAddress = Object.values((await import('node:os')).networkInterfaces())
            .flat()
            .find((i) => i.family === 'IPv4' && !i.internal)
        if (lanAddress) {
            const refused = await new Promise((res) => {
                const r = http.get({ host: lanAddress.address, port: bridge.port(), path: '/ping' }, () => res(false))
                r.on('error', () => res(true))
            })
            assert.equal(refused, true, 'not reachable on the LAN address')
        }
    } finally {
        await stop(bridge)
        blocker.close()
    }
})

test('port range used up: start reports the error', async () => {
    const blocker = await new Promise((res) => {
        const s = http.createServer().listen(0, '127.0.0.1', () => res(s))
    })
    const bridge = createBridge({ origin: ORIGIN, port: blocker.address().port, portTries: 1 })
    try {
        const err = await new Promise((res) => bridge.start((e) => res(e)))
        assert.equal(err.code, 'EADDRINUSE')
    } finally {
        blocker.close()
    }
})

test('a seek: the TV closes a waiting request, the page is told to cancel, the slot frees', async () => {
    const bridge = await boot()
    const file = crypto.randomBytes(100_000)
    let release
    const gate = new Promise((r) => (release = r))
    const page = new FakePage(bridge, { k: file }, { slow: () => gate }).start()
    try {
        const first = await new Promise((resolve) => {
            const req = http.get(
                { host: '127.0.0.1', port: bridge.port(), path: `/stream/k?token=${bridge.token()}&size=${file.length}`, headers: { Range: 'bytes=0-', Host: `127.0.0.1:${bridge.port()}` }, agent: false },
                () => {},
            )
            req.on('error', () => {})
            setTimeout(() => {
                req.destroy()
                resolve()
            }, 200)
        })
        await first
        await sleep(100)
        assert.equal(page.seen.length, 1)
        assert.deepEqual(page.cancelled, [page.seen[0].id], 'the held poll is woken with the cancel')
        assert.equal(bridge.stats().pending, 0)
        assert.equal(bridge.stats().inflight, 0)
        // The page, still holding the answer, replies late: told it is gone.
        release()
        await sleep(100)
        assert.deepEqual(page.replyStatus, [410])
    } finally {
        await page.stop()
        await stop(bridge)
    }
})

test('several ranges at once are bounded: the fifth gets a 503 and the rest finish', async () => {
    const bridge = await boot({ maxInflight: 4 })
    const file = crypto.randomBytes(200_000)
    let release
    const gate = new Promise((r) => (release = r))
    const page = new FakePage(bridge, { k: file }, { slow: () => gate }).start()
    try {
        const calls = []
        for (let i = 0; i < 4; i++) calls.push(tvGet(bridge, 'k', file.length, `bytes=${i * 1000}-${i * 1000 + 999}`))
        await sleep(150)
        assert.equal(bridge.stats().inflight, 4)
        const over = await tvGet(bridge, 'k', file.length, 'bytes=0-9')
        assert.equal(over.status, 503)
        assert.equal(over.headers['retry-after'], '1')
        release()
        const done = await Promise.all(calls)
        done.forEach((r, i) => {
            assert.equal(r.status, 206)
            assert.deepEqual(r.body, file.subarray(i * 1000, i * 1000 + 1000))
        })
        assert.equal(bridge.stats().inflight, 0)
        assert.equal((await tvGet(bridge, 'k', file.length, 'bytes=0-9')).status, 206, 'slots come back')
    } finally {
        await page.stop()
        await stop(bridge)
    }
})

test('no page: a quick 503, not a wait', async () => {
    const bridge = await boot()
    try {
        const t0 = Date.now()
        const r = await tvGet(bridge, 'k', 1000, 'bytes=0-9')
        assert.equal(r.status, 503)
        assert.ok(Date.now() - t0 < 500)
        assert.equal(bridge.stats().pending, 0)
    } finally {
        await stop(bridge)
    }
})

test('the page reloads while a read waits: the new session is asked again, one answer reaches the TV', async () => {
    const bridge = await boot({ grace: 2000 })
    const file = crypto.randomBytes(50_000)
    const never = new Promise(() => {})
    const old = new FakePage(bridge, { k: file }, { session: 'before', slow: () => never }).start()
    try {
        const tv = tvGet(bridge, 'k', file.length, 'bytes=0-999')
        await sleep(150)
        assert.equal(old.seen.length, 1)
        await old.stop() // the page goes away
        await sleep(100)
        assert.equal(bridge.stats().connected, true, 'still within the grace')
        const fresh = new FakePage(bridge, { k: file }, { session: 'after' }).start()
        const r = await tv
        assert.equal(r.status, 206)
        assert.deepEqual(r.body, file.subarray(0, 1000))
        assert.equal(fresh.seen.length, 1, 'asked again by the new session')
        await fresh.stop()
    } finally {
        await stop(bridge)
    }
})

test('a page that is gone for good: waiting reads fail with 503 after the grace, and presence drops', async () => {
    const events = []
    const bridge = await boot({ grace: 150, onPage: (c) => events.push(c) })
    const page = new FakePage(bridge, {}, { slow: () => new Promise(() => {}) }).start()
    try {
        const tv = tvGet(bridge, 'k', 1000, 'bytes=0-9')
        await sleep(100)
        await page.stop()
        const r = await tv
        assert.equal(r.status, 503)
        assert.deepEqual(events, [true, false])
        assert.equal(bridge.stats().pending, 0)
    } finally {
        await stop(bridge)
    }
})

test('the page says the file is gone or failed: 410 and 502', async () => {
    const bridge = await boot()
    const page = new FakePage(bridge, {}).start()
    try {
        assert.equal((await tvGet(bridge, 'missing', 1000, 'bytes=0-9')).status, 410)
        await page.stop()
        const page2 = new FakePage(bridge, {}, { session: 'x' })
        page2.answer = async (r) => {
            const out = await request(bridge.port(), { method: 'POST', pathname: `/reply?token=${bridge.token()}&id=${r.id}&error=fail`, headers: { Origin: ORIGIN } })
            page2.replyStatus.push(out.status)
        }
        page2.start()
        assert.equal((await tvGet(bridge, 'k', 1000, 'bytes=0-9')).status, 502)
        await sleep(50)
        assert.deepEqual(page2.replyStatus, [204])
        await page2.stop()
    } finally {
        await stop(bridge)
    }
})

test('a reply of the wrong length is refused and the TV gets a 502', async () => {
    const bridge = await boot()
    const page = new FakePage(bridge, {})
    page.answer = async (r) => {
        const out = await request(bridge.port(), {
            method: 'POST', pathname: `/reply?token=${bridge.token()}&id=${r.id}`,
            headers: { Origin: ORIGIN, 'Content-Length': 5 }, body: Buffer.alloc(5),
        })
        page.replyStatus.push(out.status)
    }
    page.start()
    try {
        assert.equal((await tvGet(bridge, 'k', 1000, 'bytes=0-9')).status, 502)
        await sleep(50)
        assert.deepEqual(page.replyStatus, [400])
    } finally {
        await page.stop()
        await stop(bridge)
    }
})

test('a read the page never answers ends with a 504 and the page is told to cancel it', async () => {
    const bridge = await boot({ readTimeout: 200 })
    const page = new FakePage(bridge, {}, { slow: () => new Promise(() => {}) }).start()
    try {
        const r = await tvGet(bridge, 'k', 1000, 'bytes=0-9')
        assert.equal(r.status, 504)
        await sleep(100)
        assert.equal(page.cancelled.length, 1)
        assert.equal(bridge.stats().pending, 0)
    } finally {
        await page.stop()
        await stop(bridge)
    }
})

test('a reply for an unknown id is a 410, and a second reply to the same id too', async () => {
    const bridge = await boot()
    const file = crypto.randomBytes(1000)
    const page = new FakePage(bridge, { k: file }).start()
    try {
        const r = await tvGet(bridge, 'k', file.length, 'bytes=0-99')
        assert.equal(r.status, 206)
        const id = page.seen[0].id
        const again = await request(bridge.port(), { method: 'POST', pathname: `/reply?token=${bridge.token()}&id=${id}`, headers: { Origin: ORIGIN, 'Content-Length': 100 }, body: file.subarray(0, 100) })
        assert.equal(again.status, 410)
        const bogus = await request(bridge.port(), { method: 'POST', pathname: `/reply?token=${bridge.token()}&id=99999`, headers: { Origin: ORIGIN } })
        assert.equal(bogus.status, 410)
    } finally {
        await page.stop()
        await stop(bridge)
    }
})

test('idle: with no page there is no timer, and a long poll ends on its own and is asked again', async () => {
    const events = []
    const bridge = await boot({ pollHold: 100, grace: 100, onPage: (c) => events.push(c) })
    try {
        assert.deepEqual(process.getActiveResourcesInfo().filter((n) => n === 'Timeout'), [], 'no timer before a page connects')
        const page = new FakePage(bridge, {}).start()
        await sleep(350)
        assert.ok(page.running && bridge.stats().connected, 'still connected across several empty polls')
        assert.deepEqual(events, [true])
        await page.stop()
        await sleep(300)
        assert.deepEqual(events, [true, false])
        assert.deepEqual(process.getActiveResourcesInfo().filter((n) => n === 'Timeout'), [], 'no timer once the page is gone')
    } finally {
        await stop(bridge)
    }
})

test('lease: held while wanted, released only holdMs after the last down, up() in between keeps it', async () => {
    let acquired = 0
    let released = 0
    const lease = createLease(() => acquired++, () => released++, 80)
    lease.up()
    lease.up()
    assert.equal(acquired, 1)
    lease.down()
    await sleep(40)
    lease.up()
    await sleep(100)
    assert.equal(released, 0)
    lease.down()
    lease.down()
    await sleep(120)
    assert.equal(released, 1)
    assert.equal(lease.held(), false)
    lease.up()
    assert.equal(acquired, 2)
    lease.down()
    await sleep(120)
    assert.equal(released, 2)
})

test('service.js: start answers port and token, keeps the service alive while the page polls, and plays', async () => {
    const calls = { handlers: {}, created: [], completed: [] }
    class FakeService {
        constructor(id) {
            calls.id = id
            this.activityManager = {
                create: (name, cb) => {
                    calls.created.push(name)
                    setImmediate(() => cb({ name }))
                },
                complete: (a, cb) => {
                    calls.completed.push(a.name)
                    cb && cb()
                },
            }
        }
        register(name, fn) {
            calls.handlers[name] = fn
        }
    }
    const { build, ID } = require(path.join(DIR, 'service.js'))
    assert.equal(ID, 'org.tmplayer.tv.service')
    const built = build(FakeService, { hold: 150, grace: 200 })
    assert.equal(calls.id, ID)
    const call = (payload) =>
        new Promise((resolve) => calls.handlers.start({ payload, respond: resolve }))

    assert.equal((await call({})).returnValue, false)
    assert.equal((await call({ origin: 'javascript:alert(1)' })).returnValue, false)
    assert.equal((await call({ origin: 'https://web.tmplayer.org/x' })).returnValue, false)

    const r = await call({ origin: ORIGIN })
    assert.equal(r.returnValue, true)
    assert.match(r.token, /^[0-9a-f]{36}$/)
    assert.ok(r.port > 0)
    const again = await call({ origin: ORIGIN })
    assert.equal(again.port, r.port, 'the same bridge answers a second start')
    assert.equal(again.token, r.token)
    assert.deepEqual(calls.created, ['keepAlive'])

    // The page connects and plays; the activity is held the whole time.
    const bridge = built.bridge()
    const file = crypto.randomBytes(40_000)
    const fake = { port: () => r.port, token: () => r.token }
    const page = new FakePage(fake, { k: file }).start()
    await sleep(400)
    assert.deepEqual(calls.completed, [], 'held past the start hold while the page polls')
    const tv = await tvGet(fake, 'k', file.length, 'bytes=0-999')
    assert.equal(tv.status, 206)
    assert.deepEqual(tv.body, file.subarray(0, 1000))
    await page.stop()
    await sleep(600) // the bridge's grace, then the hold
    assert.deepEqual(calls.completed, ['keepAlive'])
    await stop(bridge)
})

test('ES5 only in the service files (Node 8 on webOS 5 and 6)', () => {
    for (const file of ['bridge.js', 'service.js']) {
        const code = fs
            .readFileSync(path.join(DIR, file), 'utf8')
            .replace(/\/\*[\s\S]*?\*\//g, '')
            .replace(/^\s*\/\/.*$/gm, '')
        assert.doesNotMatch(code, /=>/, `${file}: arrow function`)
        assert.doesNotMatch(code, /\b(let|const|class|async|await|yield)\b/, `${file}: ES2015+ keyword`)
        assert.doesNotMatch(code, /`/, `${file}: template string`)
        assert.doesNotMatch(code, /\.\.\.|\?\.|\?\?/, `${file}: spread or optional chaining`)
        assert.doesNotMatch(code, /\b(Object\.(values|entries)|padStart|padEnd|includes\()/, `${file}: newer than Node 8 / ES5 habits`)
    }
    const pkg = JSON.parse(fs.readFileSync(path.join(DIR, 'package.json'), 'utf8'))
    const services = JSON.parse(fs.readFileSync(path.join(DIR, 'services.json'), 'utf8'))
    assert.equal(pkg.main, 'service.js')
    assert.equal(services.id, 'org.tmplayer.tv.service')
    assert.equal(services.services[0].name, services.id)
})

/** Runs the wrapper page's script (tv/shell/index.html) against stubs; resolves with the address it opens. */
function runWrapper({ address, luna }) {
    const vm = require('node:vm')
    const html = fs.readFileSync(path.join(__dirname, '..', 'shell', 'index.html'), 'utf8').replace('{{ADDRESS_JS}}', JSON.stringify(address))
    const script = /<script>([\s\S]*)<\/script>/.exec(html)[1]
    const el = () => ({ className: '', focus() {}, addEventListener() {} })
    return new Promise((resolve) => {
        const window = {
            fetch: () => Promise.resolve({}),
            location: { replace: resolve },
            PalmServiceBridge: luna
                ? function () {
                      this.call = (uri, params) => {
                          this.uri = uri
                          this.params = JSON.parse(params)
                          setImmediate(() => this.onservicecallback(JSON.stringify(luna(this.uri, this.params))))
                      }
                  }
                : undefined,
        }
        const ctx = { window, document: { getElementById: el, addEventListener() {} }, location: window.location, fetch: window.fetch, setTimeout, clearTimeout, Date, JSON, Number, XMLHttpRequest: function () {} }
        ctx.window.window = window
        vm.runInNewContext(script, { ...ctx, PalmServiceBridge: window.PalmServiceBridge })
    })
}

test('wrapper: starts the service and opens the page with port and token in the fragment', async () => {
    let seen
    const opened = await runWrapper({
        address: 'https://web.tmplayer.org/',
        luna: (uri, params) => {
            seen = { uri, params }
            return { returnValue: true, port: 38491, token: 'ab12'.repeat(9) }
        },
    })
    assert.equal(seen.uri, 'luna://org.tmplayer.tv.service/start')
    assert.deepEqual(seen.params, { origin: 'https://web.tmplayer.org' })
    assert.equal(opened, `https://web.tmplayer.org/#tvsvc=38491.${'ab12'.repeat(9)}`)
})

test('wrapper: a service that fails, answers garbage, or is not there opens the plain address', async () => {
    const plain = 'http://10.0.0.5:8092/?x=1'
    assert.equal(await runWrapper({ address: plain, luna: () => ({ returnValue: false, errorText: 'origin' }) }), plain)
    assert.equal(await runWrapper({ address: plain, luna: () => ({ returnValue: true, port: 99999, token: 'ab12'.repeat(9) }) }), plain)
    assert.equal(await runWrapper({ address: plain, luna: () => ({ returnValue: true, port: 4000, token: '"><script>' }) }), plain)
    assert.equal(await runWrapper({ address: plain, luna: null }), plain, 'Samsung: no Luna at all')
    assert.equal(await runWrapper({ address: 'https://web.tmplayer.org/#old', luna: () => ({ returnValue: true, port: 4000, token: 'ab12'.repeat(9) }) }), `https://web.tmplayer.org/#tvsvc=4000.${'ab12'.repeat(9)}`)
})
