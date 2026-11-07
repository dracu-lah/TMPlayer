import assert from 'node:assert/strict'
import { test } from 'node:test'
import { featuredFactsLine, type FactsWords } from './featuredFacts.ts'

const w: FactsWords = { film: 'Film', series: 'Series', length: (s) => `${Math.round(s / 60)}m`, seasons: (n) => `${n} seasons` }

test('a film: kind, year, two genres and the provider length', () => {
    assert.equal(featuredFactsLine({ isShow: false, year: 2026, genres: ['Drama', 'Thriller', 'Crime'], runtimeMin: 122, durationSec: 100 }, w), 'Film  ·  2026  ·  Drama, Thriller  ·  122m')
})
test('a film with no answer still has its length from Telegram', () => {
    assert.equal(featuredFactsLine({ isShow: false, durationSec: 7200 }, w), 'Film  ·  120m')
})
test('a show: its seasons from the provider, else from the chat when there is more than one', () => {
    assert.equal(featuredFactsLine({ isShow: true, year: 2024, genres: ['Crime'], seasons: 3 }, w), 'Series  ·  2024  ·  Crime  ·  3 seasons')
    assert.equal(featuredFactsLine({ isShow: true, chatSeasons: 2 }, w), 'Series  ·  2 seasons')
    assert.equal(featuredFactsLine({ isShow: true, chatSeasons: 1 }, w), 'Series')
})
test('blank genres, a zero year and a negative length are left out', () => {
    assert.equal(featuredFactsLine({ isShow: false, year: 0, genres: ['', ' '], runtimeMin: -3, durationSec: 0 }, w), 'Film')
})
