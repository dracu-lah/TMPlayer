import { test } from 'node:test'
import assert from 'node:assert/strict'
import { AUTO_PAGES, AUTO_TOTAL, cleanFacets, cleanFilter, cleanPref, ENOUGH, filterLabel, filterState, filtersOn, forgetPlayed, isDefault, languageName, lastOf, NO_FACETS, NO_PREF, randomScope, rememberPlayed, wantsMore } from './videoFilter.ts'

const words = (key: string) => key.replace('grid.filter_', '')

test('a stored Show filter is one the web lists; anything else, and the apps device filter, is All', () => {
    assert.equal(cleanFilter('Unwatched'), 'Unwatched')
    assert.equal(cleanFilter('InProgress'), 'InProgress')
    for (const bad of ['OnDevice', 'HighDefinition', 'unwatched', '', null, undefined, 4, {}, ['All'], '__proto__']) assert.equal(cleanFilter(bad), 'All')
})

test('a stored facets line keeps the parts the rules write, once each, and nothing else', () => {
    assert.equal(cleanFacets('len=short;pic=1080;hdr=1;sub=1;lang=ml;add=month'), 'len=short;pic=1080;hdr=1;sub=1;lang=ml;add=month')
    assert.equal(cleanFacets('len=short;len=long'), 'len=short', 'one of each kind')
    assert.equal(cleanFacets('hdr=0;lang=MALAYALAM;pic=<script>;x=1;;=;len'), '')
    assert.equal(cleanFacets('foo;lang=en;bar=baz'), 'lang=en')
    assert.equal(cleanFacets(42), '')
    assert.equal(cleanFacets(null), '')
    assert.equal(cleanFacets('len=short;'.repeat(1_000_000)), '', 'a megabyte is no line')
})

test('a stored preference that does not hold up is no preference', () => {
    assert.deepEqual(cleanPref({ filter: 'Watched', facets: 'hdr=1' }), { filter: 'Watched', facets: 'hdr=1' })
    for (const bad of [null, undefined, 'x', 7, [], { filter: 9, facets: {} }]) assert.deepEqual(cleanPref(bad), NO_PREF)
    assert.ok(isDefault(NO_PREF))
    assert.ok(!isDefault({ filter: 'Shows', facets: '' }))
    assert.ok(!isDefault({ filter: 'All', facets: 'hdr=1' }))
})

test('the Sort button counts the Show filter as one, then each filter by what a video is', () => {
    assert.equal(filtersOn(NO_PREF, 0), 0)
    assert.equal(filtersOn({ filter: 'Unwatched', facets: '' }, 0), 1)
    assert.equal(filtersOn({ filter: 'Unwatched', facets: 'hdr=1;sub=1' }, 2), 3)
})

test('the grid knows whether a filter hides some, hides all, is off for a visit or waits for words', () => {
    const o = { on: 2, query: false, showAll: false, shown: 10, total: 30, hidden: 20 }
    assert.equal(filterState({ ...o, on: 0 }), 'off')
    assert.equal(filterState(o), 'hides-some')
    assert.equal(filterState({ ...o, shown: 0, hidden: 30 }), 'hides-all')
    assert.equal(filterState({ ...o, showAll: true }), 'shown-all')
    assert.equal(filterState({ ...o, query: true }), 'paused', 'words list what Telegram found, whatever the filters say')
    assert.equal(filterState({ ...o, hidden: 0, shown: 30 }), 'on')
    assert.equal(filterState({ ...o, total: 0, shown: 0, hidden: 0 }), 'on', 'nothing loaded yet is not "hides all"')
})

test('a short filtered grid reads a few pages by itself, then stops; a full one never does', () => {
    const base = { state: 'hides-all' as const, shown: 0, done: false, busy: false, failed: false, auto: 0, total: 0 }
    assert.ok(wantsMore(base))
    // Each chip press starts the three again, but a visit has a total: 12 pages by itself, then only Keep looking.
    assert.ok(wantsMore({ ...base, total: AUTO_TOTAL - 1 }))
    assert.ok(!wantsMore({ ...base, total: AUTO_TOTAL }), 'the visit budget is spent however fresh the filter')
    let pages = 0
    for (let presses = 0; presses < 50; presses++)
        for (let auto = 0; wantsMore({ ...base, auto, total: pages }); auto++) pages++
    assert.equal(pages, AUTO_TOTAL, 'fifty chip presses on a chat that matches little read twelve pages, not a hundred and fifty')
    assert.ok(wantsMore({ ...base, state: 'hides-some', shown: ENOUGH - 1 }))
    assert.ok(!wantsMore({ ...base, shown: ENOUGH }), 'a screenful scrolls by itself')
    assert.ok(!wantsMore({ ...base, auto: AUTO_PAGES }), 'a filter matching nothing in 20,000 videos reads three pages, not the chat')
    assert.ok(!wantsMore({ ...base, done: true }))
    assert.ok(!wantsMore({ ...base, busy: true }))
    assert.ok(!wantsMore({ ...base, failed: true }))
    for (const state of ['off', 'paused', 'shown-all'] as const) assert.ok(!wantsMore({ ...base, state }))
})

test('the filters in force read as one phrase, as the apps say them', () => {
    assert.equal(filterLabel(NO_PREF, NO_FACETS, words), null)
    assert.equal(filterLabel({ filter: 'Unwatched', facets: '' }, NO_FACETS, words), 'unwatched')
    const f = { ...NO_FACETS, length: 'short', picture: '1080', hdr: true, subtitles: true, language: 'ml', added: 'week', count: 6 }
    assert.equal(filterLabel({ filter: 'InProgress', facets: 'x' }, f, words), 'in_progress, length_short, hd, hdr, subtitles, Malayalam, added_week')
    assert.equal(filterLabel(NO_PREF, { ...NO_FACETS, picture: 'bogus', length: '__proto__' }, words), null, 'a code the page does not know says nothing')
})

test('a language chip names the language, and a code that is not one names nothing', () => {
    assert.equal(languageName('en'), 'English')
    assert.equal(languageName('ml'), 'Malayalam')
    assert.equal(languageName('EN'), '')
    assert.equal(languageName('<script>'), '')
    assert.equal(languageName(''), '')
})

test('each list remembers what it played last, thirty-two lists at most, the oldest let go first', () => {
    forgetPlayed()
    assert.equal(lastOf(randomScope('chat', 1)), null)
    rememberPlayed(randomScope('chat', 1), '1:5')
    assert.equal(lastOf(randomScope('chat', 1)), '1:5')
    assert.equal(lastOf(randomScope('later', 'all')), null, 'another list has its own')
    for (let i = 0; i < 40; i++) rememberPlayed(randomScope('list', i), `9:${i}`)
    assert.equal(lastOf(randomScope('chat', 1)), null)
    assert.equal(lastOf(randomScope('list', 39)), '9:39')
    assert.equal(lastOf(randomScope('list', 7)), null)
    forgetPlayed()
})
