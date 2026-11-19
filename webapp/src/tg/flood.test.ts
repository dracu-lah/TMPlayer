// `node --test src/tg/flood.test.ts` (pnpm test:tg): a flood wait read from an error, and garbage that is not one.
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { floodSeconds } from './flood.ts'

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
