import assert from 'node:assert/strict'
import { test } from 'node:test'
import { FRAME_FALLBACK, FrameClock, frameFrom } from './frameStep.ts'

test('no frames yet, or too few, is 1/30', () => {
    assert.equal(frameFrom([]), FRAME_FALLBACK)
    assert.equal(frameFrom([1 / 24, 1 / 24]), FRAME_FALLBACK)
})

test('the usual gap is the frame, from 24, 25, 30 and 60 frames a second', () => {
    for (const fps of [24, 25, 30, 60, 23.976]) {
        const c = new FrameClock()
        for (let i = 0; i < 20; i++) c.frame(i / fps, 1)
        assert.ok(Math.abs(c.duration - 1 / fps) < 1e-9, `${fps}`)
    }
})

test('one dropped frame and a seek in the middle do not move it', () => {
    const c = new FrameClock()
    let t = 0
    for (let i = 0; i < 12; i++) c.frame((t += 1 / 25), 1)
    c.frame((t += 3 / 25), 1)
    c.frame(500, 1)
    for (let i = 0; i < 6; i++) c.frame(500 + (i + 1) / 25, 1)
    assert.ok(Math.abs(c.duration - 1 / 25) < 1e-9)
})

test('garbage gaps (NaN, zero, a stall) count for nothing', () => {
    assert.equal(frameFrom([NaN, Infinity, 0, -1, 5, 5, 5, 5, 5, 5]), FRAME_FALLBACK)
})

test('a reset starts over', () => {
    const c = new FrameClock()
    for (let i = 0; i < 20; i++) c.frame(i / 60, 1)
    c.reset()
    assert.equal(c.duration, FRAME_FALLBACK)
})
