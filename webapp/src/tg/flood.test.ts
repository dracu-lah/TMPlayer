// `node --test src/tg/flood.test.ts` (pnpm test:tg): a flood wait read from an error, and garbage that is not one.
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { createFloodClock, floodSeconds } from './flood.ts'

test('a flood wait is read from an error and nothing else is', () => {
    assert.equal(floodSeconds({ message: 'FLOOD_WAIT_12 (a wait of 12 seconds is required)' }), 12)
    assert.equal(floodSeconds({ text: 'FLOOD_PREMIUM_WAIT_30', message: 'x' }), 30)
    assert.equal(floodSeconds({ text: 'FLOOD_WAIT_%d', message: 'x', seconds: 7 }), 7)
    assert.equal(floodSeconds({ message: 'timeout' }), 0)
    assert.equal(floodSeconds(null), 0)
    assert.equal(floodSeconds(undefined), 0)
})

test('garbage in a flood error gives 0 or a bounded number', () => {
    const bad = [NaN, -5, 0, Infinity, '9', {}, [], null, 1e12, 0.2] as unknown[]
    for (const seconds of bad) {
        const n = floodSeconds({ message: 'FLOOD_WAIT_x', seconds: seconds as number })
        assert.ok(Number.isInteger(n) && n >= 0 && n <= 86_400, String(seconds))
    }
    assert.equal(floodSeconds({ message: 'FLOOD_WAIT_99999999999' }), 86_400)
    assert.equal(floodSeconds({ message: 'FLOOD_WAIT_', text: 5 as unknown as string }), 0)
})

test('the flood clock counts down, keeps the longest wait, and a new video clears it', () => {
    let now = 1000
    const c = createFloodClock(() => now)
    assert.equal(c.left(), 0)
    c.note(30)
    assert.equal(c.left(), 30)
    now += 12_400
    assert.equal(c.left(), 18)
    c.note(5)
    assert.equal(c.left(), 18)
    c.note(0)
    c.note(-3)
    c.note(NaN)
    assert.equal(c.left(), 18)
    c.clear()
    assert.equal(c.left(), 0)
    now += 60_000
    assert.equal(c.left(), 0)
})

test('a short flood wait is named too: 5 and 30 seconds', () => {
    for (const s of [5, 30]) {
        const c = createFloodClock(() => 0)
        c.note(floodSeconds({ message: `FLOOD_WAIT_${s}` }))
        assert.equal(c.left(), s)
    }
})
