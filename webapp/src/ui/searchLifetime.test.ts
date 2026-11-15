// `node --test src/ui/searchLifetime.test.ts`
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { keepsSearch } from './searchLifetime.ts'

const t0 = 1_000_000

test('119 seconds hidden keeps the search, 121 clears it', () => {
    assert.equal(keepsSearch(t0, t0 + 119_000), true)
    assert.equal(keepsSearch(t0, t0 + 120_000), true)
    assert.equal(keepsSearch(t0, t0 + 121_000), false)
})

test('a page that was never hidden keeps it', () => {
    assert.equal(keepsSearch(null, t0 + 10_000_000), true)
})

test('a clock set back clears it', () => {
    assert.equal(keepsSearch(t0, t0 - 1), false)
})
