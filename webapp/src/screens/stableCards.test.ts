import { test } from 'node:test'
import assert from 'node:assert/strict'
import { stableCards, type StableCard } from './stableCards.ts'

const card = (key: string, facts = '1 GB', poster: unknown = { t: key }): StableCard => ({
    key,
    title: key,
    facts,
    item: { key, thumb: 't' + key, cover: null, size: 1, duration: 60, name: key, chat: 'c', poster },
})

test('the same card object comes back for a tile that did not change, whatever object the worker made', () => {
    const cards = stableCards((t: { key: string; facts?: string }) => card(t.key, t.facts))
    const first = cards([{ key: 'a' }, { key: 'b' }])
    const second = cards([{ key: 'a' }, { key: 'b' }, { key: 'c' }])
    assert.equal(second[0], first[0])
    assert.equal(second[1], first[1])
    assert.equal(second.length, 3)
})

test('a changed card is a new object, and only that one', () => {
    const cards = stableCards((t: { key: string; facts?: string }) => card(t.key, t.facts))
    const first = cards([{ key: 'a' }, { key: 'b' }])
    const second = cards([{ key: 'a' }, { key: 'b', facts: '2 GB' }])
    assert.equal(second[0], first[0])
    assert.notEqual(second[1], first[1])
    assert.equal(second[1].facts, '2 GB')
})

test('a card that left and came back is not kept from before the gap', () => {
    const cards = stableCards((t: { key: string }) => card(t.key))
    const first = cards([{ key: 'a' }])
    cards([{ key: 'b' }])
    assert.notEqual(cards([{ key: 'a' }])[0], first[0])
})

test('2,000 cards are compared in well under a frame budget', () => {
    const cards = stableCards((t: { key: string }) => card(t.key))
    const tiles = Array.from({ length: 2000 }, (_, i) => ({ key: 'k' + i }))
    cards(tiles)
    const at = performance.now()
    cards(tiles)
    assert.ok(performance.now() - at < 100)
})
