// The providers' readers (anime-sources.ts) over the shapes their answers have, and over garbage:
// every reader of outside data gets truncated, wrong-typed, huge and random input and must never throw.
import assert from 'node:assert/strict'
import { test } from 'node:test'
import * as f from './anime-fixtures.ts'
import { groupOf, jikanPageOf, kitsuIdOf, kitsuPageOf, runOf, seasonOf, seasonsOf, showGuideOf } from './anime-sources.ts'

test('AniList: the count, the MyAnimeList id, the next episode with its day, the streaming titles and the names', () => {
    const run = runOf(f.aniRun({ nextAiringEpisode: { episode: 501, airingAt: 1_794_000_000 }, streamingEpisodes: [{ title: 'Episode 12 - The Title', thumbnail: 'https://x.test/a.jpg' }, { title: 'Opening 2' }, null, 5] }))!
    assert.equal(run.episodes, 500)
    assert.equal(run.malId, '1735')
    assert.deepEqual(run.next, { season: 1, episode: 501, airDate: '2026-11-06' })
    assert.deepEqual(run.streaming, [{ absolute: 12, name: 'The Title' }])
    assert.deepEqual(run.names, ['Naruto: Shippuuden', 'ナルト 疾風伝', 'Naruto Shippuden'])
})

test('AniList: no count, no id, a next episode without a day all read as nothing', () => {
    const run = runOf(f.aniRun({ idMal: null, episodes: null, nextAiringEpisode: { episode: 3 } }))!
    assert.equal(run.episodes, null)
    assert.equal(run.malId, null)
    assert.equal(run.next, null)
    assert.equal(runOf({ data: { Media: null } }), null)
    assert.equal(runOf(null), null)
})

test('TMDB: the show page gives its seasons (specials kept) and its groups; only a sane group id stands', () => {
    const show = showGuideOf(f.tmdbShow(f.seasonSizes, true))
    assert.equal(show.seasons.length, 22)
    assert.deepEqual(show.seasons[0], { number: 0, episodes: 5, airDate: null })
    assert.deepEqual(show.groups[0], { id: f.GROUP, type: 2, episodes: 500 })
    assert.equal(showGuideOf({ seasons: [], episode_groups: { results: [{ id: '../../x', type: 2 }] } }).groups.length, 0)
})

test('TMDB: a season, appended seasons and the absolute group keep the place and the still file', () => {
    const season = seasonOf(f.tmdbSeason(2))
    assert.equal(season.length, 21)
    assert.equal(season[0].name, f.title(33))
    assert.equal(season[0].still, f.stillOf(33))
    assert.equal(season[0].runtime, 24)
    const appended = seasonsOf({ 'season/1': f.tmdbSeason(1), 'season/2': f.tmdbSeason(2) }, [1, 2, 3])
    assert.equal(appended.get(1)!.length, 32)
    assert.equal(appended.get(3)!.length, 0)
    const group = groupOf(f.tmdbGroup())
    assert.equal(group.length, 500)
    assert.deepEqual([group[122].season, group[122].episode], f.place(123))
    assert.equal(seasonOf({ episodes: [{ episode_number: 1, name: 'A', still_path: 'https://evil.example/x.jpg' }] })[0].still, null)
})

test('Kitsu: the id the mapping names, its episodes and whether another page follows; a picture is never kept', () => {
    assert.equal(kitsuIdOf(f.kitsuMapping()), '1555')
    assert.equal(kitsuIdOf({ data: [{ relationships: { item: { data: { type: 'manga', id: '9' } } } }] }), null)
    assert.equal(kitsuIdOf({ data: [{ relationships: { item: { data: { type: 'anime', id: '../x' } } } }] }), null)
    const page = kitsuPageOf(f.kitsuPage(0))
    assert.equal(page.list.length, 20)
    assert.equal(page.more, true)
    assert.equal(page.list[0].name, f.kitsuTitle(1))
    assert.equal(page.list[0].still, null)
    assert.equal(page.list[0].airDate, f.date(1))
    assert.equal(kitsuPageOf(f.kitsuPage(480)).more, false)
})

test('Jikan: the number, the title and the day of a page, and whether another follows', () => {
    const page = jikanPageOf(f.jikanPage(1))
    assert.equal(page.list.length, 100)
    assert.equal(page.more, true)
    assert.equal(page.list[0].airDate, f.date(1))
    assert.equal(jikanPageOf(f.jikanPage(5)).more, false)
})

test('every reader survives truncated, wrong-typed, huge and random input', () => {
    const readers: ((json: unknown) => unknown)[] = [runOf, showGuideOf, seasonOf, groupOf, kitsuIdOf, kitsuPageOf, jikanPageOf, (j) => seasonsOf(j, [1, 2])]
    const samples = [f.aniRun(), f.tmdbShow(f.seasonSizes, true), f.tmdbSeason(1), f.tmdbGroup(), f.kitsuMapping(), f.kitsuPage(0), f.jikanPage(1)]
    const inputs: unknown[] = [null, undefined, 0, -1, 1e30, NaN, '', 'x', [], {}, [[]], { data: 'x' }, { data: [null, 1, 'x', [], {}] }, { episodes: 'x' }, { groups: [{ episodes: [{}] }] }, { data: { Media: { episodes: 'many', nextAiringEpisode: 'x', streamingEpisodes: 'x', title: 'x', synonyms: 5 } } }]
    for (const s of samples) {
        const text = JSON.stringify(s)
        for (const cut of [0, 1, text.length >> 3, text.length >> 1, text.length - 1]) {
            try {
                inputs.push(JSON.parse(text.slice(0, cut)))
            } catch {
                // A cut answer is not JSON: the readers never see it.
            }
        }
        inputs.push(JSON.parse(text.replace(/\d+/g, '99999999999999999999')), JSON.parse(text.replace(/:"[^"]*"/g, ":123")))
    }
    inputs.push({ data: Array.from({ length: 200_000 }, (_, i) => ({ mal_id: i + 1, title: 'x'.repeat(5_000), attributes: { number: i + 1 } })) })
    let seed = 7
    const rnd = (n: number) => ((seed = (seed * 1103515245 + 12345) & 0x7fffffff) % n)
    const junk = (depth: number): unknown => {
        const pick = rnd(depth > 3 ? 4 : 6)
        if (pick === 0) return rnd(2_000_000) - 1_000_000
        if (pick === 1) return 'x'.repeat(rnd(40))
        if (pick === 2) return rnd(2) === 0
        if (pick === 3) return null
        if (pick === 4) return Array.from({ length: rnd(6) }, () => junk(depth + 1))
        return Object.fromEntries(['data', 'episodes', 'groups', 'seasons', 'attributes', 'number', 'mal_id', 'title', 'Media', 'results', 'order', 'episode_number', 'season_number'].filter(() => rnd(2) === 0).map((k) => [k, junk(depth + 1)]))
    }
    for (let i = 0; i < 300; i++) inputs.push(junk(0))
    for (const read of readers) {
        for (const input of inputs) assert.doesNotThrow(() => read(input))
    }
    // Bounded: a huge answer never yields more than the limits.
    assert.ok(jikanPageOf(inputs.at(-301)).list.length <= 9999)
    assert.ok(groupOf({ groups: [{ episodes: Array.from({ length: 100_000 }, () => ({ season_number: 1, episode_number: 1 })) }] }).length <= 6000)
})
