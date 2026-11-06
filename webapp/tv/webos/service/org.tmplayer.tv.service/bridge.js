'use strict'
// The loopback stream bridge of the LG webOS app (private/TV-DIRECT-PLAN.md R4). The TV's own
// <video> asks http://127.0.0.1:<port>/stream/<key> for a range; the hosted page, which holds the
// Telegram connection, answers it through a long poll. Plain ES5 with no dependencies: it runs on
// Node 8 (webOS 5 and 6), and tv/test/bridge.test.js runs it on a desktop Node.
//
//   TV player   GET|HEAD /stream/<key>?token=&size=&mime=   Range: bytes=a-b      (no Origin)
//   page        GET  /ping?token=                           is the service there
//   page        GET  /poll?token=&session=                  held up to POLL_HOLD, then {requests, cancel}
//   page        POST /reply?token=&id=[&error=gone|fail]    the bytes of one request as the body
//
// The same answer the Service Worker gives (public/sw.js stream()): a 206 of at most 2 MiB.
// Nothing outside is trusted: token (constant time), Host (127.0.0.1 or localhost with our port,
// against DNS rebinding) and Origin (the page's own origin; a media element sends none, so /stream/
// accepts none and relies on the token) are checked, and the socket is bound to 127.0.0.1 only.

var http = require('http')
var url = require('url')
var crypto = require('crypto')

var MAX_ANSWER = 2 * 1024 * 1024
var DEFAULTS = {
    maxAnswer: MAX_ANSWER,
    readTimeout: 30000, // a read the page never answers
    pollHold: 20000, // one long poll, then the page asks again
    grace: 8000, // the page counts as there this long after a poll ended (a reload fits)
    maxInflight: 4, // ranges asked of the page at once; more get a 503 and the player retries
    firstPort: 38491,
    portTries: 20,
}

function noop() {}

function same(a, b) {
    if (typeof a !== 'string' || typeof b !== 'string') return false
    var x = Buffer.from(a)
    var y = Buffer.from(b)
    return x.length === y.length && crypto.timingSafeEqual(x, y)
}

function safeMime(m) {
    return typeof m === 'string' && /^[\w.+\-]+\/[\w.+\-]+(;\s*codecs="[\w.,+\- ]{0,200}")?$/.test(m) ? m : 'video/mp4'
}

function createBridge(options) {
    var opt = options || {}
    var cfg = {}
    Object.keys(DEFAULTS).forEach(function (k) {
        cfg[k] = typeof opt[k] === 'number' ? opt[k] : DEFAULTS[k]
    })
    var token = typeof opt.token === 'string' && opt.token ? opt.token : crypto.randomBytes(18).toString('hex')
    var origin = opt.origin || ''
    var onPage = typeof opt.onPage === 'function' ? opt.onPage : noop
    var log = typeof opt.log === 'function' ? opt.log : noop

    var server = null
    var port = 0
    var nextId = 1
    var pending = {} // id -> job
    var inflight = 0
    var queue = [] // jobs the page has not been told about
    var cancels = [] // ids the page was told about and the player then dropped
    var poll = null // the page's held request: { res, timer }
    var session = ''
    var connected = false
    var graceTimer = null

    // ---- replies ----

    function send(res, code, headers) {
        if (res.headersSent || res.finished) return
        try {
            res.writeHead(code, headers || {})
            res.end()
        } catch (e) {
            log('send', e && e.message)
        }
    }

    function sendJson(res, code, obj) {
        if (res.headersSent || res.finished) return
        var body = Buffer.from(JSON.stringify(obj))
        res.writeHead(code, { 'Content-Type': 'application/json', 'Content-Length': body.length, 'Cache-Control': 'no-store' })
        res.end(body)
    }

    function cors(req, res) {
        var o = req.headers.origin
        if (o && o === origin) {
            res.setHeader('Access-Control-Allow-Origin', o)
            res.setHeader('Access-Control-Allow-Private-Network', 'true')
            res.setHeader('Vary', 'Origin')
        }
    }

    // ---- the page's presence ----

    function setConnected(v) {
        if (v === connected) return
        connected = v
        if (!v) failAll(503)
        try {
            onPage(v)
        } catch (e) {
            log('onPage', e && e.message)
        }
    }

    function pollEnded() {
        clearTimeout(graceTimer)
        graceTimer = setTimeout(function () {
            graceTimer = null
            if (!poll) setConnected(false)
        }, cfg.grace)
    }

    function endPoll(body) {
        var p = poll
        if (!p) return
        poll = null
        clearTimeout(p.timer)
        try {
            sendJson(p.res, 200, body)
        } catch (e) {
            log('poll', e && e.message)
        }
        pollEnded()
    }

    function wake() {
        if (!poll || (!queue.length && !cancels.length)) return
        var reqs = queue.map(function (j) {
            j.told = true
            return { id: j.id, key: j.key, start: j.start, end: j.end }
        })
        var c = cancels
        queue = []
        cancels = []
        endPoll({ requests: reqs, cancel: c })
    }

    // ---- jobs: one range the TV asked for ----

    function release(job) {
        if (pending[job.id] !== job) return
        delete pending[job.id]
        inflight--
        clearTimeout(job.timer)
        var at = queue.indexOf(job)
        if (at >= 0) queue.splice(at, 1)
    }

    // Ends a job that has not started its answer with a status, and tells the page when it knew of it.
    function fail(job, code) {
        if (job.done) return
        job.done = true
        release(job)
        if (job.told) {
            cancels.push(job.id)
            wake()
        }
        send(job.res, code, { 'Retry-After': '1', 'Cache-Control': 'no-store' })
    }

    function failAll(code) {
        Object.keys(pending).forEach(function (id) {
            var j = pending[id]
            if (j && !j.replying) fail(j, code)
        })
    }

    // ---- /stream/ ----

    function stream(req, res, u) {
        var key
        try {
            key = decodeURIComponent(u.pathname.slice('/stream/'.length))
        } catch (e) {
            return send(res, 400)
        }
        if (!key || key.length > 512 || /[\u0000-\u001f]/.test(key)) return send(res, 400)
        var size = Number(u.query.size)
        if (!isFinite(size) || size % 1 !== 0) return send(res, 400)
        var mime = safeMime(u.query.mime)
        var m = /bytes=(\d*)-(\d*)/.exec(req.headers.range || '')
        var start = m && m[1] ? Number(m[1]) : 0
        var end = m && m[2] ? Number(m[2]) : size - 1
        if (m && !m[1] && m[2]) {
            start = Math.max(0, size - Number(m[2]))
            end = size - 1
        }
        if (!(size > 0) || start >= size || end < start) return send(res, 416, { 'Content-Range': 'bytes */' + size })
        end = Math.min(end, size - 1, start + cfg.maxAnswer - 1)

        if (req.method === 'HEAD') {
            return send(res, 200, { 'Content-Type': mime, 'Content-Length': size, 'Accept-Ranges': 'bytes', 'Cache-Control': 'no-store' })
        }
        if (!connected) return send(res, 503, { 'Retry-After': '1' })
        if (inflight >= cfg.maxInflight) return send(res, 503, { 'Retry-After': '1' })

        var job = {
            id: nextId++, key: key, start: start, end: end, size: size, mime: mime, res: res,
            told: false, replying: false, done: false, body: null, timer: null,
        }
        pending[job.id] = job
        inflight++
        queue.push(job)
        job.timer = setTimeout(function () {
            fail(job, 504)
        }, cfg.readTimeout)
        // The TV closed the request (a seek, Back): the read is cancelled, no bytes are kept.
        res.on('close', function () {
            if (job.done) return
            if (job.replying) {
                job.tvGone = true
                if (job.body) {
                    try {
                        job.body.unpipe(res)
                    } catch (e) {}
                    job.body.resume()
                }
                return
            }
            fail(job, 499)
        })
        wake()
    }

    // ---- /poll ----

    function handlePoll(req, res, u) {
        if (poll) endPoll({ replaced: true })
        clearTimeout(graceTimer)
        graceTimer = null
        var s = typeof u.query.session === 'string' ? u.query.session.slice(0, 64) : ''
        if (s !== session) {
            // A page that reloaded forgot what it was asked: ask again for every read still waiting.
            session = s
            queue = Object.keys(pending)
                .map(function (id) {
                    return pending[id]
                })
                .filter(function (j) {
                    return !j.replying
                })
            queue.forEach(function (j) {
                j.told = false
            })
            cancels = []
        }
        poll = { res: res, timer: null }
        poll.timer = setTimeout(function () {
            endPoll({})
        }, cfg.pollHold)
        res.on('close', function () {
            if (poll && poll.res === res) {
                var p = poll
                poll = null
                clearTimeout(p.timer)
                pollEnded()
            }
        })
        setConnected(true)
        wake()
    }

    // ---- /reply ----

    function handleReply(req, res, u) {
        var job = pending[Number(u.query.id)]
        if (req.method !== 'POST') return send(res, 405)
        if (!job || job.replying || job.done) {
            req.resume()
            return sendJson(res, 410, { gone: true })
        }
        if (u.query.error) {
            req.resume()
            fail(job, u.query.error === 'gone' ? 410 : 502)
            return send(res, 204)
        }
        var expected = job.end - job.start + 1
        if (Number(req.headers['content-length']) !== expected) {
            req.resume()
            fail(job, 502)
            return sendJson(res, 400, { expected: expected })
        }
        job.replying = true
        job.body = req
        clearTimeout(job.timer)
        var tv = job.res
        if (job.tvGone) {
            req.resume()
        } else {
            tv.writeHead(206, {
                'Content-Type': job.mime,
                'Content-Length': expected,
                'Content-Range': 'bytes ' + job.start + '-' + job.end + '/' + job.size,
                'Accept-Ranges': 'bytes',
                'Cache-Control': 'no-store',
            })
            req.pipe(tv, { end: false })
        }
        req.on('end', function () {
            job.done = true
            release(job)
            if (!job.tvGone) tv.end()
            send(res, 204)
        })
        req.on('aborted', function () {
            job.done = true
            release(job)
            tv.destroy()
        })
    }

    // ---- routing ----

    function handle(req, res) {
        try {
            var host = req.headers.host
            if (host !== '127.0.0.1:' + port && host !== 'localhost:' + port) return send(res, 421)
            var u = url.parse(req.url, true)
            var path = u.pathname || ''
            var sent = req.headers.origin
            var isStream = path.indexOf('/stream/') === 0
            if (sent !== undefined && (!origin || sent !== origin)) return send(res, 403)
            if (!isStream && sent === undefined) return send(res, 403)
            cors(req, res)
            if (req.method === 'OPTIONS') {
                return send(res, 204, {
                    'Access-Control-Allow-Methods': 'GET, POST',
                    'Access-Control-Allow-Headers': 'Content-Type',
                    'Access-Control-Max-Age': '600',
                })
            }
            if (!same(u.query.token, token)) return send(res, 403)
            if (isStream && (req.method === 'GET' || req.method === 'HEAD')) return stream(req, res, u)
            if (path === '/ping' && req.method === 'GET') return sendJson(res, 200, { ok: true, v: 1 })
            if (path === '/poll' && req.method === 'GET') return handlePoll(req, res, u)
            if (path === '/reply') return handleReply(req, res, u)
            send(res, 404)
        } catch (e) {
            log('handle', e && e.message)
            send(res, 500)
        }
    }

    // ---- lifecycle ----

    function listen(p, tries, done) {
        var s = http.createServer(handle)
        s.timeout = 120000
        s.maxConnections = 32
        s.on('clientError', function (err, socket) {
            try {
                socket.end('HTTP/1.1 400 Bad Request\r\n\r\n')
            } catch (e) {}
        })
        function onError(e) {
            if (e && e.code === 'EADDRINUSE' && tries > 1 && p !== 0) return listen(p + 1, tries - 1, done)
            done(e)
        }
        s.once('error', onError)
        s.listen(p, '127.0.0.1', function () {
            s.removeListener('error', onError)
            s.on('error', function (e) {
                log('server', e && e.message)
            })
            server = s
            port = s.address().port
            done(null, port)
        })
    }

    function start(done) {
        if (server) return done(null, port)
        listen(typeof opt.port === 'number' ? opt.port : cfg.firstPort, cfg.portTries, done)
    }

    function stop(done) {
        clearTimeout(graceTimer)
        graceTimer = null
        failAll(503)
        if (poll) endPoll({})
        clearTimeout(graceTimer)
        graceTimer = null
        connected = false
        var s = server
        server = null
        if (!s) return (done || noop)()
        s.close(function () {
            ;(done || noop)()
        })
        if (typeof s.closeAllConnections === 'function') s.closeAllConnections()
    }

    return {
        start: start,
        stop: stop,
        token: function () {
            return token
        },
        port: function () {
            return port
        },
        setOrigin: function (o) {
            origin = o || ''
        },
        origin: function () {
            return origin
        },
        isConnected: function () {
            return connected
        },
        stats: function () {
            return { pending: Object.keys(pending).length, inflight: inflight, queued: queue.length, polling: !!poll, connected: connected }
        },
    }
}

/**
 * Holds something (the webOS keepAlive activity) while it is wanted: `up()` takes it at once, `down()`
 * gives it back `holdMs` later unless `up()` comes first. Only one timer, and only while waiting.
 */
function createLease(acquire, releaseIt, holdMs) {
    var held = false
    var timer = null
    return {
        up: function () {
            clearTimeout(timer)
            timer = null
            if (held) return
            held = true
            acquire()
        },
        down: function () {
            if (!held || timer) return
            timer = setTimeout(function () {
                timer = null
                held = false
                releaseIt()
            }, holdMs)
        },
        held: function () {
            return held
        },
    }
}

module.exports = { createBridge: createBridge, createLease: createLease, MAX_ANSWER: MAX_ANSWER }
