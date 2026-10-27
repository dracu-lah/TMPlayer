// The carousel's rules, the web twin of core's HomeFeaturedTest. `node --test src/screens/featuredRules.test.ts`
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { candidatesOf, eligible, isComplete, isGenericTitle, MIN_BYTES, type Candidate } from './featuredRules.ts'

const film = (o: Partial<Candidate> = {}): Candidate => ({ key: 'a', kind: 'file', title: 'Harbour Notes', extra: null, size: MIN_BYTES, duration: 6000, asked: true, ...o })

test('a film of 400 MB with a real title passes, a smaller or unknown size does not', () => {
    assert.ok(eligible(film()))
    assert.ok(!eligible(film({ size: MIN_BYTES - 1 })))
    assert.ok(!eligible(film({ size: 0 })))
})

test('extras and unasked names are left out', () => {
    assert.ok(!eligible(film({ extra: 'Trailer' })))
    assert.ok(!eligible(film({ asked: false })))
})

test('a film needs 20 minutes when its length is known and a real title', () => {
    assert.ok(!eligible(film({ duration: 60 })))
    assert.ok(eligible(film({ duration: 0 })))
    assert.ok(!eligible(film({ title: 'Video' })))
    assert.ok(!eligible(film({ title: 'VID_20241013_120000' })))
    assert.ok(eligible(film({ title: '1917' })))
})

test('a show needs no length, and one title is one page', () => {
    assert.ok(eligible(film({ kind: 'show', duration: 60 })))
    const out = candidatesOf([film({ key: '1' }), film({ key: '2', title: 'harbour notes ' }), film({ key: '3', title: 'Other' })])
    assert.deepEqual(out.map((c) => c.key), ['1', '3'])
})

test('generic titles', () => {
    for (const t of ['', '  ', 'Untitled', 'IMG 1234', '2024-10-13', '20241013']) assert.ok(isGenericTitle(t), t)
    for (const t of ['300', 'Harbour Notes']) assert.ok(!isGenericTitle(t), t)
})

test('a complete page has a match, a wide picture and an overview', () => {
    assert.ok(isComplete({ match: true, backdrop: '/b.jpg', overview: 'Text' }))
    assert.ok(!isComplete({ match: true, backdrop: null, overview: 'Text' }))
    assert.ok(!isComplete({ match: true, backdrop: '/b.jpg', overview: ' ' }))
    assert.ok(!isComplete({ match: false, backdrop: '/b.jpg', overview: 'Text' }))
})
