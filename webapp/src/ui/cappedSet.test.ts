import { test } from 'node:test'
import assert from 'node:assert/strict'
import { addCapped } from './cappedSet.ts'

test('past the cap the oldest goes and the newest is kept, not refused', () => {
    const s = new Set<number>()
    for (let i = 1; i <= 121; i++) addCapped(s, i, 120)
    assert.equal(s.size, 120)
    assert.equal(s.has(121), true)
    assert.equal(s.has(1), false)
})

test('adding one already there makes it the newest', () => {
    const s = new Set<number>()
    addCapped(s, 1, 2)
    addCapped(s, 2, 2)
    addCapped(s, 1, 2)
    addCapped(s, 3, 2)
    assert.deepEqual([...s], [1, 3])
})
