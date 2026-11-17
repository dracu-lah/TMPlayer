// The anime episode guide's rules (src/animeGuide.ts) over the cases of the apps' AnimeEpisodesTest:
// the pure resolver, its numbering both ways, the order sources are taken in, and garbage.
import assert from 'node:assert/strict'
import { test } from 'node:test'
import * as g from '../src/animeGuide.ts'
import { counts, date, place, seasonSizes, stillOf, tmdbGroup, title } from './anime-fixtures.ts'
import { groupOf } from './anime-sources.ts'

const fromGroupOf = () => g.fromGroup(groupOf(tmdbGroup()))

const layout = counts()

function seasonsList() {
    const by = new Map<number, g.ListedEpisode[]>()
    seasonSizes.forEach((size, i) => {
        const start = seasonSizes.slice(0, i).reduce((a, b) => a + b, 0)
        by.set(i + 1, Array.from({ length: size }, (_, e) => ({ number: e + 1, name: title(start + e + 1), overview: '', still: stillOf(start + e + 1), airDate: date(start + e + 1), runtime: null })))
    })
    return g.fromSeasons(by, layout)
}

test('absolute and season numbers map both ways', () => {
    const guide = g.merge([seasonsList()], layout, null)
    assert.equal(guide.total, 500)
    assert.deepEqual(guide.locate(1), [1, 1])
    assert.deepEqual(guide.locate(32), [1, 32])
    assert.deepEqual(guide.locate(33), [2, 1])
    assert.deepEqual(guide.locate(500), [21, seasonSizes.at(-1)])
    assert.equal(guide.locate(501), null)
    for (let n = 1; n <= 500; n++) {
        const [s, e] = guide.locate(n)!
        assert.equal(guide.absoluteOf(s, e), n, `S${s}E${e}`)
    }
})

test('a number past its season is the absolute one', () => {
    const guide = g.merge([seasonsList()], layout, null)
    assert.equal(guide.absoluteOf(1, 123), 123)
    assert.equal(guide.absoluteOf(2, 57), 57)
    assert.equal(guide.absoluteOf(2, 5), 33 + 4)
    assert.equal(guide.absoluteOf(0, 5), null)
    assert.equal(guide.absoluteOf(1, 501), null)
    assert.equal(guide.absoluteOf(1, 0), null)
    assert.equal(guide.absoluteOf(1, -3), null)
})

test('with no season list only season one is taken straight', () => {
    const kitsu = g.fromNumbered('kitsu', Array.from({ length: 24 }, (_, i) => ({ number: i + 1, name: `T${i + 1} x`, overview: '', still: null, airDate: null, runtime: null })))
    const guide = g.merge([kitsu], [], null)
    assert.equal(guide.absoluteOf(1, 7), 7)
    assert.equal(guide.absoluteOf(2, 5), null)
    assert.equal(guide.locate(7), null)
})

test('the first source with a title gives it; the picture, words and date come from the first that has one', () => {
    const group: g.SourceList = { source: 'group', pieces: [{ absolute: 1, name: 'Real Title', season: 1, episode: 1 }, { absolute: 2, name: 'Episode 2', season: 1, episode: 2 }] }
    const kitsu: g.SourceList = { source: 'kitsu', pieces: [{ absolute: 1, name: 'Kitsu one', overview: 'Kitsu words', still: '/kitsustill01.jpg' }, { absolute: 2, name: 'Kitsu two' }] }
    const jikan: g.SourceList = { source: 'jikan', pieces: [{ absolute: 1, name: 'Jikan one', airDate: '2007-02-15' }, { absolute: 2, name: 'Jikan two', airDate: '2007-02-22' }] }
    const guide = g.merge([group, kitsu, jikan], [], null)
    const one = guide.entry(1)!
    assert.equal(one.name, 'Real Title')
    assert.equal(one.source, 'group')
    assert.equal(one.overview, 'Kitsu words')
    assert.equal(one.still, '/kitsustill01.jpg')
    assert.equal(one.stillSource, 'kitsu')
    assert.equal(one.airDate, '2007-02-15')
    const two = guide.entry(2)!
    assert.equal(two.name, 'Kitsu two', 'a placeholder is no title')
    assert.equal(two.source, 'kitsu')
    assert.deepEqual([one.season, one.episode], [1, 1])
})

test('an episode no source names stays unnamed, not numbered', () => {
    const guide = g.merge([{ source: 'jikan', pieces: [{ absolute: 3, name: 'Episode 3' }, { absolute: 4, name: '  ' }] }], [], null)
    assert.equal(guide.entry(3)!.name, '')
    assert.equal(guide.entry(4)!.name, '')
    assert.equal(guide.entry(3)!.source, null)
})

test('a picture that is not a TMDB still is never taken (the worker serves no other)', () => {
    const list: g.SourceList = { source: 'kitsu', pieces: [{ absolute: 1, name: 'A b', still: 'https://evil.example/x.jpg' }, { absolute: 2, name: 'C d', still: '../../x.jpg' }] }
    const guide = g.merge([list], [], null)
    assert.equal(guide.entry(1)!.still, null)
    assert.equal(guide.entry(2)!.still, null)
})

test('a list that is another show is not plausible', () => {
    const list = (total: number): g.SourceList => ({ source: 'group', pieces: [{ absolute: total, name: 'x y' }] })
    assert.equal(g.plausible(500, list(500)), true)
    assert.equal(g.plausible(500, list(496)), true)
    assert.equal(g.plausible(500, list(220)), false)
    assert.equal(g.plausible(12, list(24)), true)
    assert.equal(g.plausible(12, list(1000)), false)
    assert.equal(g.plausible(null, list(7)), true)
    assert.equal(g.plausible(12, { source: 'kitsu', pieces: [] }), false)
})

test('the season view is one season counted straight through', () => {
    const guide = g.merge([seasonsList()], layout, { season: 1, episode: 501, airDate: '2099-01-01' })
    const flat = g.seasonEpisodes(guide, 1)
    assert.equal(flat.length, 500)
    assert.equal(flat[122].name, title(123))
    assert.equal(g.seasonEpisodes(guide, 2).length, seasonSizes[1])
    assert.equal(g.seasonEpisodes(guide, 2)[0].name, title(33))
    assert.deepEqual(g.seasonList(guide), [{ number: 1, episodes: 500, airDate: date(1) }])
})

test('an aired episode with no date is never to air', () => {
    const list: g.SourceList = { source: 'jikan', pieces: [{ absolute: 1, name: 'A b', airDate: '2020-01-01' }, { absolute: 2, name: 'C d' }, { absolute: 3, name: 'E f' }] }
    const guide = g.merge([list], [], { season: 1, episode: 3, airDate: '2099-05-05' })
    const flat = g.flat(guide)
    assert.equal(flat[1].airDate, '2020-01-01')
    assert.equal(flat[2].airDate, null)
    assert.equal(guide.total, 3)
})

test('the overlay adds the run to a show AniList lists no seasons for, and leaves one with seasons alone', () => {
    const guide = g.merge([seasonsList()], layout, { season: 1, episode: 501, airDate: '2099-01-01' })
    const extras = g.overlay({ genres: ['Action'], seasonList: [] as { number: number; episodes: number; airDate: string | null }[], next: null as g.Next | null }, guide)
    assert.deepEqual(extras.genres, ['Action'])
    assert.equal(extras.seasonList.length, 1)
    assert.equal(extras.seasonList[0].episodes, 500)
    assert.equal(extras.next?.episode, 501)
    const tmdb = { seasonList: [{ number: 1, episodes: 3, airDate: null }], next: null }
    assert.equal(g.overlay(tmdb, guide), tmdb)
    assert.equal(g.overlay({ seasonList: [], next: null }, g.merge([], [], null)).seasonList.length, 0)
})

test('streaming titles carry their number', () => {
    assert.deepEqual(g.streamingPiece('Episode 12 - The Title'), { absolute: 12, name: 'The Title' })
    assert.equal(g.streamingPiece('E7 - Name x')?.absolute, 7)
    assert.equal(g.streamingPiece('Naruto Shippuden Opening 2'), null)
    assert.equal(g.streamingPiece('Episode 99999 - Huge'), null)
    assert.equal(g.streamingPiece(''), null)
})

test('a placeholder only numbers the episode (EpisodeNames.placeholder)', () => {
    assert.equal(g.placeholder('Episode 5', 5), true)
    assert.equal(g.placeholder('5', 5), true)
    assert.equal(g.placeholder('Chapter 3', 3), true)
    assert.equal(g.placeholder('第 5 集', 5), true)
    assert.equal(g.placeholder('Episode 6', 5), false)
    assert.equal(g.placeholder('The 4th Floor', 4), false)
    assert.equal(g.placeholder('Pilot', 1), false)
    assert.equal(g.placeholder('٥', 5), true, 'Arabic-Indic five')
})

test('dates are civil', () => {
    assert.equal(g.dateOf(0), '1970-01-01')
    assert.equal(g.dateOf(951_782_400), '2000-02-29')
    assert.equal(g.dateOf(1_794_000_000), '2026-11-06')
    assert.equal(g.dateOf(-1), null)
    assert.equal(g.dateOf(Number.MAX_SAFE_INTEGER), null)
    assert.equal(g.dateOf(NaN), null)
})

test('a file numbered per season finds its name through the group, and one counted straight through too', () => {
    const guide = g.merge([fromGroupOf()], layout, null)
    const [s, e] = place(150)
    assert.equal(g.episodeFor(guide, s, e)?.name, title(150))
    assert.equal(g.episodeFor(guide, 1, 150)?.name, title(150))
    assert.equal(g.episodeFor(guide, 1, 9999), null)
})

test('garbage never throws and is bounded', () => {
    let seed = 11
    const rnd = (n: number) => ((seed = (seed * 1103515245 + 12345) & 0x7fffffff) % n)
    const pieces: g.Piece[] = Array.from({ length: 20_000 }, () => ({
        absolute: rnd(20_000) - 5_000,
        name: rnd(2) ? 'x'.repeat(rnd(2_000)) : `Episode ${rnd(50)}`,
        overview: '<b>'.repeat(rnd(50)),
        still: [null, 'ftp://x', `/still${rnd(99999)}abcdef.jpg`][rnd(3)],
        airDate: [null, 'yesterday', '2020-01-01'][rnd(3)],
        runtime: rnd(5_000) - 100,
        season: rnd(400) - 100,
        episode: rnd(400) - 100,
    }))
    const guide = g.merge([{ source: 'kitsu', pieces }], [{ number: -4, episodes: 10, airDate: null }, { number: 1, episodes: -5, airDate: null }, { number: 1, episodes: 99_999, airDate: null }, { number: 3, episodes: 12, airDate: null }, { number: 3, episodes: 14, airDate: null }, { number: 9_999, episodes: 5, airDate: null }], { season: -1, episode: -1, airDate: 'x' })
    assert.ok(guide.entries.length <= g.MAX_EPISODES)
    assert.ok(guide.entries.every((e) => e.absolute >= 1 && e.absolute <= g.MAX_EPISODES && e.name.length <= 300 && e.overview.length <= 4_000))
    assert.deepEqual(guide.layout.map((s) => s.number), [3])
    assert.equal(g.flat(guide).length, Math.min(guide.total, g.MAX_EPISODES))
    assert.equal(guide.absoluteOf(Number.MAX_SAFE_INTEGER, Number.MAX_SAFE_INTEGER), null)
    assert.equal(g.fromSeasons(new Map(), []).pieces.length, 0)
    assert.equal(g.fromGroup([]).pieces.length, 0)
    assert.equal(g.layout([{ number: 1, episodes: 5, airDate: null }, { number: 1, episodes: 6, airDate: null }]).length, 1)
})

test('the wire round trip keeps the guide, and a garbage answer reads as none', () => {
    const guide = g.merge([fromGroupOf()], layout, { season: 1, episode: 501, airDate: '2099-01-01' }, 500)
    const back = g.readAnswer(JSON.parse(JSON.stringify(g.toAnswer(guide, true))))!
    assert.equal(back.complete, true)
    assert.equal(back.guide.total, 500)
    assert.equal(back.guide.entry(123)!.name, title(123))
    assert.deepEqual(back.guide.locate(123), place(123))
    assert.equal(back.guide.next?.episode, 501)
    assert.equal(back.guide.absoluteOf(...place(321)), 321)
    for (const junk of [null, 1, 'x', [], {}, { found: false }, { found: true }, { found: true, entries: 'x' }, { found: true, entries: [null, 1, 'x', {}, { absolute: 'a' }, { absolute: 1, season: 'x', still: 7, source: 'evil' }], layout: [1, null, { number: 'x' }], next: { season: 1 } }]) {
        const read = g.readAnswer(junk)
        if (read) {
            assert.ok(read.guide.entries.every((e) => e.absolute >= 1))
            assert.equal(read.guide.next, null)
        }
    }
    const huge = { found: true, entries: Array.from({ length: 100_000 }, (_, i) => ({ absolute: i + 1, name: 'x'.repeat(1_000), overview: 'y'.repeat(100_000) })) }
    const read = g.readAnswer(huge)!
    assert.ok(read.guide.entries.length <= g.MAX_EPISODES)
    assert.ok(read.guide.entries.every((e) => e.name.length <= 300 && e.overview.length <= 4_000))
})
