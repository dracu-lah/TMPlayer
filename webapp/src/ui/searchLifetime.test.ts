// `node --test src/ui/searchLifetime.test.ts`
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { dropsAddressSearch, keepsSearch } from './searchLifetime.ts'

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

test('the words in the address are old on a page load, but kept when the app itself opened Search with them', () => {
    const base = { shownThisLoad: false, words: true, entry: false, loadType: 'navigate' as string | null }
    // "Find in other chats" from a video's page: not the entry page, a plain load: kept.
    assert.equal(dropsAddressSearch(base), false)
    // A pasted address or a launched tab: the page opened on this entry.
    assert.equal(dropsAddressSearch({ ...base, entry: true }), true)
    // A reload, or Back into the page from another load, of an entry the app reached itself.
    assert.equal(dropsAddressSearch({ ...base, loadType: 'reload' }), true)
    assert.equal(dropsAddressSearch({ ...base, loadType: 'back_forward' }), true)
    assert.equal(dropsAddressSearch({ ...base, loadType: 'prerender' }), true)
    // The browser says nothing: only the entry mark decides.
    assert.equal(dropsAddressSearch({ ...base, loadType: null }), false)
    assert.equal(dropsAddressSearch({ ...base, loadType: null, entry: true }), true)
    // A later visit in the same load, or an address with no words, is never dropped.
    assert.equal(dropsAddressSearch({ ...base, entry: true, shownThisLoad: true }), false)
    assert.equal(dropsAddressSearch({ ...base, entry: true, words: false }), false)
})
