// The volume boost's decisions that need no real audio: the makeup the limiter's compressor adds
// by itself, which sources may be routed, and when a context may be asked to run. The levels
// through the real nodes are measured in Chromium (e2e/boost.spec.ts, an OfflineAudioContext).
// `node --test "src/player/*.test.ts"` (pnpm test:player).
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { followLoudness, levelConstant, levelFor, LEVEL_FALL_SECONDS, LEVEL_MAX, LEVEL_RISE_SECONDS, LEVEL_START, limiterMakeup, mayStart, meanSquareOf, NIGHT, routableSource } from './boost.ts'

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

test('Night mode squeezes from -15 dBFS at 6 to 1, as the apps do, and its makeup is taken back out', () => {
    assert.equal(NIGHT.threshold, -15)
    assert.equal(NIGHT.ratio, 6)
    // Out at 0 dBFS in is -15 + 15/6 = -12.5 dB: a makeup near 7.5 dB * 0.6 of that range, and a gain of 1 below the threshold.
    assert.ok(limiterMakeup(NIGHT.threshold, NIGHT.ratio) > 1.5)
    assert.ok(limiterMakeup(NIGHT.threshold, NIGHT.ratio) > limiterMakeup(-3, 20))
})

test('Even out volume lifts a quiet track towards -18 dBFS, never above 4 and never below 1', () => {
    const quiet = 0.06 * 0.06
    assert.ok(Math.abs(levelFor(quiet) - 0.126 / 0.06) < 1e-9)
    // A track at the target or louder is left alone.
    assert.equal(levelFor(LEVEL_START), 1)
    assert.equal(levelFor(0.5), 1)
    // A very quiet one is held to +12 dB; silence and garbage ask for the top, not for NaN.
    assert.equal(levelFor(1e-8), LEVEL_MAX)
    assert.equal(levelFor(0), LEVEL_MAX)
    assert.equal(levelFor(Number.NaN), LEVEL_MAX)
    assert.equal(levelFor(-1), LEVEL_MAX)
})

test('the level follows over about two seconds and leaves silence and garbage out', () => {
    const loud = 0.04
    // Half a second of the same quiet block moves the average a fifth of the way, not all of it.
    const once = followLoudness(LEVEL_START, loud, 0.5)
    assert.ok(once > LEVEL_START && once < loud)
    assert.ok(Math.abs(once - (LEVEL_START + (loud - LEVEL_START) * (1 - Math.exp(-0.25)))) < 1e-12)
    // Long enough, it arrives.
    let avg = LEVEL_START
    for (let i = 0; i < 160; i++) avg = followLoudness(avg, loud, 0.25)
    assert.ok(Math.abs(avg - loud) < 1e-6)
    // A silent block (a pause between scenes) is not measured, so it does not read as a quiet track.
    assert.equal(followLoudness(0.01, 0, 0.25), 0.01)
    assert.equal(followLoudness(0.01, 1e-7, 0.25), 0.01)
    // Garbage and a clock that went back leave it alone.
    assert.equal(followLoudness(0.01, Number.NaN, 0.25), 0.01)
    assert.equal(followLoudness(0.01, 0.5, Number.NaN), 0.01)
    assert.equal(followLoudness(0.01, 0.5, -1), 0.01)
    assert.equal(followLoudness(0.01, 0.5, 0), 0.01)
})

test('the lift rises slowly and falls quickly, so it never pumps', () => {
    assert.equal(levelConstant(1, 3), LEVEL_RISE_SECONDS)
    assert.equal(levelConstant(3, 1), LEVEL_FALL_SECONDS)
    assert.ok(LEVEL_RISE_SECONDS > LEVEL_FALL_SECONDS)
})

test('the mean square of a block', () => {
    assert.equal(meanSquareOf([]), 0)
    assert.equal(meanSquareOf([1, -1, 1, -1]), 1)
    assert.ok(Math.abs(meanSquareOf(Float32Array.from([0.5, 0.5])) - 0.25) < 1e-9)
})
