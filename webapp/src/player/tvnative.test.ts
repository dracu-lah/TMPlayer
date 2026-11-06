// node --test: when the TV's own player is given a file, and when a TV stops trying (tvnative.ts).
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { MAX_MISSES, noteTvNative, TV_BROWSER, tvNativeOff, tvPlaysNatively } from './tvnative.ts'

function memory() {
    const m = new Map<string, string>()
    return {
        getItem: (k: string) => m.get(k) ?? null,
        setItem: (k: string, v: string) => void m.set(k, v),
        removeItem: (k: string) => void m.delete(k),
    }
}

test('the TV agents the site serves the TV build to', () => {
    assert.ok(TV_BROWSER.test('Mozilla/5.0 (Web0S; Linux/SmartTV) AppleWebKit/537.36 Chrome/79.0.3945.79 Safari/537.36 WebAppManager'))
    assert.ok(TV_BROWSER.test('Mozilla/5.0 (SMART-TV; LINUX; Tizen 5.5) AppleWebKit/537.36 69.0.3497.106/5.5 TV Safari/537.36'))
    assert.ok(!TV_BROWSER.test('Mozilla/5.0 (X11; Linux x86_64) Chrome/130.0.0.0 Safari/537.36'))
})

test('4K HEVC with AAC or E-AC-3 in MP4 goes to the TV player', () => {
    assert.ok(tvPlaysNatively('video/mp4; codecs="hvc1.2.4.L153.B0, mp4a.40.2"', true))
    assert.ok(tvPlaysNatively('video/mp4; codecs="hev1.1.6.L153.90, ec-3"', true))
    assert.ok(tvPlaysNatively('video/mp4; codecs="avc1.640028,ac-3"', true))
})

test('a codec off the TV list, no codecs, or a container the TV refuses stays on the repack', () => {
    assert.ok(!tvPlaysNatively('video/mp4; codecs="hvc1.2.4.L153.B0, dtsc"', true))
    assert.ok(!tvPlaysNatively('video/mp4; codecs="mp4v.20.9"', true))
    assert.ok(!tvPlaysNatively('video/mp4', true))
    assert.ok(!tvPlaysNatively('video/mp4; codecs=""', true))
    assert.ok(!tvPlaysNatively('video/mp4; codecs="hvc1.1.6.L93.B0"', false))
})

test('two misses turn the TV player off, one read through the worker turns it back on', () => {
    const s = memory()
    assert.ok(!tvNativeOff(s))
    noteTvNative(false, s)
    assert.ok(!tvNativeOff(s))
    for (let i = 1; i < MAX_MISSES; i++) noteTvNative(false, s)
    assert.ok(tvNativeOff(s))
    noteTvNative(true, s)
    assert.ok(!tvNativeOff(s))
})

test('broken or missing storage never throws and never turns it off', () => {
    assert.ok(!tvNativeOff(null))
    noteTvNative(false, null)
    const bad = { getItem: () => 'garbage', setItem: () => { throw new Error('quota') }, removeItem: () => { throw new Error('denied') } }
    assert.ok(!tvNativeOff(bad))
    noteTvNative(false, bad)
    noteTvNative(true, bad)
})
