// The volume boost's decisions that need no real audio: the makeup the limiter's compressor adds
// by itself, which sources may be routed, and when a context may be asked to run. The levels
// through the real nodes are measured in Chromium (e2e/boost.spec.ts, an OfflineAudioContext).
// `node --test "src/player/*.test.ts"` (pnpm test:player).
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { limiterMakeup, mayStart, routableSource } from './boost.ts'

test('the compressor\'s own makeup gain is the spec\'s: about 1.2175 for a threshold of -3 dB and a ratio of 20', () => {
    // Out at 0 dBFS in is -3 + 3/20 = -2.85 dB, 0.7197 linear; its inverse to the power 0.6 is the makeup.
    assert.ok(Math.abs(limiterMakeup(-3, 20) - 1.2175) < 0.002)
    assert.ok(Math.abs(20 * Math.log10(limiterMakeup(-3, 20)) - 1.71) < 0.05)
    // No compression, no makeup; a higher threshold needs less.
    assert.equal(limiterMakeup(0, 20), 1)
    assert.ok(limiterMakeup(-1, 20) < limiterMakeup(-3, 20))
    assert.ok(limiterMakeup(-3, 2) < limiterMakeup(-3, 20))
})

test('only a same origin or blob source is routed, never one a browser would play silent', () => {
    const origin = 'https://web.tmplayer.org'
    assert.equal(routableSource('blob:https://web.tmplayer.org/abc', origin), true)
    assert.equal(routableSource('/stream/abc', origin), true)
    assert.equal(routableSource('https://web.tmplayer.org/stream/abc', origin), true)
    assert.equal(routableSource('https://cdn.example.com/a.mp4', origin), false)
    assert.equal(routableSource('http://web.tmplayer.org/a.mp4', origin), false)
    assert.equal(routableSource('', origin), true)
    assert.equal(routableSource('https://[bad', origin), false)
})

test('a context is made only once the page has had a tap or a key, where the browser says', () => {
    assert.equal(mayStart({ hasBeenActive: false }), false)
    assert.equal(mayStart({ hasBeenActive: true }), true)
    // A browser that does not track it is asked.
    assert.equal(mayStart(undefined), true)
})
