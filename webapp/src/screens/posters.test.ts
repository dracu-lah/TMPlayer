// What the page keeps of the worker's answers (posters.ts): a no-store stopgap is never kept, and a
// one season show kept before the split is asked again.
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { fresh, storable } from './answerRules.ts'

test('an answer the worker marks no-store, no-cache or private is shown, not kept', () => {
    assert.equal(storable('no-store'), false)
    assert.equal(storable('public, no-store'), false)
    assert.equal(storable('No-Cache'), false)
    assert.equal(storable('private, max-age=0'), false)
    assert.equal(storable('public, max-age=3600, s-maxage=5184000'), true)
    assert.equal(storable(null), true)
})

test('a one season show kept before the split is asked again; one with its groups read, or with several seasons, stands', () => {
    const now = Date.now()
    const base = { found: true, logo: null }
    const one = [{ number: 1, episodes: 37, airDate: null }]
    assert.equal(fresh('/tmdb/extras?k=show&id=1', { ...base, seasonList: one }, now), false)
    assert.equal(fresh('/tmdb/extras?k=show&id=1', { ...base, seasonList: one, groupsRead: true }, now), true)
    assert.equal(fresh('/tmdb/extras?k=show&id=1', { ...base, seasonList: [...one, { number: 2, episodes: 3, airDate: null }] }, now), true)
    assert.equal(fresh('/tmdb/extras?k=show&id=1&a=1', { ...base, seasonList: one }, now), true)
})
