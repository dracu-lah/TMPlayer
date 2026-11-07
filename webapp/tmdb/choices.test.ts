// core's PosterChoiceRules cases, for the port in choices.ts.
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { LIMIT, mergeChoices, OVERVIEW_MAX } from './choices.ts'
import type { Ask, Candidate } from './match.ts'

const ask = (over: Partial<Ask> = {}): Ask => ({ title: 'Idiot', year: 2022, kind: 'film', tmdbId: null, series: false, languages: [], ...over })
const c = (id: string, title: string, year: number | null, language = 'en', overview = ''): Candidate => ({ id, names: [title], year, poster: null, originalLanguage: language, overview })

test('a title found by two searches is listed once, the closest first', () => {
    const merged = mergeChoices(ask(), [
        { film: true, candidates: [c('2', 'Idiot', 2021, 'ta'), c('1', 'Idiot', 2022, 'cs')] },
        { film: true, candidates: [c('1', 'Idiot', 2022, 'cs'), c('3', 'The Idiot', 1951, 'ja')] },
        { film: false, candidates: [c('9', 'Idiot', 2019)] },
    ])
    assert.deepEqual(merged.map((m) => `${m.kind}${m.id}`), ['film1', 'film2', 'film3', 'show9'])
})

test('the same id as a film and as a show are two titles', () => {
    const merged = mergeChoices(ask(), [
        { film: true, candidates: [c('5', 'Idiot', 2022)] },
        { film: false, candidates: [c('5', 'Idiot', 2022)] },
    ])
    assert.deepEqual(merged.map((m) => m.kind), ['film', 'show'])
})

test('a language the name lists breaks a tie, and a show is looked for first by a name that is a show', () => {
    const tie = mergeChoices(ask({ title: 'Governor', year: 2026, languages: ['hi'] }), [{ film: true, candidates: [c('10', 'Governor', 2026, 'en'), c('11', 'Governor', 2026, 'hi')] }])
    assert.deepEqual(tie.map((m) => m.id), [11, 10])
    const shows = mergeChoices(ask({ kind: 'show', year: null }), [{ film: true, candidates: [c('1', 'Idiot', null)] }, { film: false, candidates: [c('2', 'Idiot', null)] }])
    assert.deepEqual(shows.map((m) => m.kind), ['show', 'film'])
})

test('at most ten, bad ids and nameless titles dropped, the overview trimmed to a line', () => {
    const many = Array.from({ length: 30 }, (_, i) => c(String(i + 1), `Thing ${i + 1}`, 2020, 'en', 'x '.repeat(400)))
    const bad = [c('abc', 'Nope', 2020), c('0', 'Zero', 2020), c('-3', 'Neg', 2020), { ...c('77', '', 2020), names: ['', ' '] }]
    const merged = mergeChoices(ask({ title: 'Thing' }), [{ film: true, candidates: [...bad, ...many] }])
    assert.equal(merged.length, LIMIT)
    assert.ok(merged.every((m) => m.id > 0 && m.title && m.overview.length <= OVERVIEW_MAX && !m.overview.includes('  ')))
})

test('garbage in the lists never throws', () => {
    const weird = [
        { id: '1', names: [], year: null, poster: null, originalLanguage: '' },
        { id: '2', names: ['A'], year: NaN, poster: null, originalLanguage: 'ENGLISH-US' },
    ] as Candidate[]
    assert.doesNotThrow(() => mergeChoices(ask(), [{ film: true, candidates: weird }]))
    assert.deepEqual(mergeChoices(ask(), []), [])
})
