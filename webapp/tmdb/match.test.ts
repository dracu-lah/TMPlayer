// The cases of core's MetaPickTest, MetaConfidenceTest and MetaMatch, for the port in match.ts.
// `pnpm test:tmdb` (node's own runner).
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { accepts, best, normalise, overlap, trusted, type Ask, type Candidate } from './match.ts'

const ask = (title: string, year: number | null, over: Partial<Ask> = {}): Ask => ({ title, year, kind: 'film', tmdbId: null, series: false, languages: [], ...over })
const cand = (id: string, title: string, year: number | null, language = ''): Candidate => ({ id, names: [title, title], year, poster: `/${id}.jpg`, originalLanguage: language })

test('normalise drops accents, punctuation, a leading article and says "and" for "&"', () => {
    assert.equal(normalise('The Fabuleux Destin d’Amélie Poulain!'), 'fabuleux destin d amelie poulain')
    assert.equal(normalise('Fast & Furious'), 'fast and furious')
    assert.equal(normalise('A  Quiet   Place'), 'quiet place')
    assert.equal(normalise('1917'), '1917')
    assert.equal(normalise('Amélie'), 'amelie')
})

test('overlap is the share of words the two titles have in common', () => {
    assert.equal(overlap('Harbour Notes', 'Harbour Notes'), 1)
    assert.equal(overlap('Harbour Notes Extended', 'Harbour Notes'), 2 / 3)
    assert.equal(overlap('', 'Harbour'), 0)
})

test('accepts: the same words, or most of them with the year agreeing', () => {
    assert.ok(accepts('Clap', 2022, ['Clap', ''], 2022, false))
    assert.ok(accepts('Clap', 2022, ['Clap'], 2021, false))
    assert.ok(!accepts('Clap', 2022, ['Clap'], 2019, false))
    // With no year on either side only the same words will do.
    assert.ok(accepts('Clap', null, ['Clap'], 2019, false))
    assert.ok(!accepts('Coast walk day 2', 2023, ['Coastal Walks'], 2023, false))
    assert.ok(accepts('Quiet Valley Extended Cut', 2019, ['Quiet Valley Extended'], 2019, false))
    assert.ok(!accepts('Quiet Valley Extended Cut', null, ['Quiet Valley Extended'], 2019, false))
})

test('accepts: a show named with a later season year is still that show', () => {
    assert.ok(accepts('Loki', 2023, ['Loki'], 2021, true))
    assert.ok(!accepts('Loki', 2023, ['Loki'], 2021, false))
    assert.ok(!accepts('Loki', 2019, ['Loki'], 2021, true))
})

test('the same words come before the same year and the year before the language', () => {
    const q = ask('Clap', 2022, { languages: ['ta'] })
    assert.equal(best(q, [cand('near', 'Clap', 2021, 'ta'), cand('exact', 'Clap', 2022, 'en')], false)?.id, 'exact')
    assert.equal(best(q, [cand('first', 'Clap', 2022, 'en'), cand('tamil', 'Clap', 2022, 'ta')], false)?.id, 'tamil')
    assert.equal(best(q, [cand('first', 'Clap', 2022), cand('second', 'Clap', 2022)], false)?.id, 'first', "the provider's order breaks a tie")
})

test('nothing that fails the title check is picked', () => {
    assert.equal(best(ask('Coast walk day 2', 2023), [cand('x', 'Coastal Walks', 2023)], false), null)
})

test('trusted: the year within one, a later season, or a show for a file with an episode', () => {
    assert.ok(trusted(ask('Clap', 2022), { kind: 'film', year: 2022 }))
    assert.ok(trusted(ask('Clap', 2022), { kind: 'film', year: 2021 }))
    assert.ok(!trusted(ask('Night Train', null), { kind: 'film', year: 2019 }), 'a title alone is not enough')
    assert.ok(!trusted(ask('Night Train', 2015), { kind: 'film', year: 2019 }))
    assert.ok(trusted(ask('Loki', 2023, { kind: 'show', series: true }), { kind: 'show', year: 2021 }))
    assert.ok(trusted(ask('Panchayat', null, { kind: 'show', series: true }), { kind: 'show', year: 2020 }))
    assert.ok(!trusted(ask('Scam 1992', null, { kind: 'film' }), { kind: 'show', year: 2020 }), 'a show found for a name with no episode needs its year')
})
