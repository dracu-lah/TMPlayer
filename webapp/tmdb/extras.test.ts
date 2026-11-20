// The title logo pick (MetaRules.logo) over TMDB's `images.logos`, and that a page with none, or
// with only stacked, tiny, SVG or other-language ones, gets the name instead.
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { applySplit, buildSplit, chooseGroup, episodeGroups, extras, logo, nextEpisode, seasonList, splitEpisode, splitNext } from './extras.ts'
import { groupOf } from './anime-sources.ts'
import { SEASONS_GROUP, air, page } from './split-fixtures.ts'

const wide = (path: string, lang: string | null, votes = 5, width = 800) => ({ file_path: path, iso_639_1: lang, aspect_ratio: 3, width, height: width / 3, vote_average: votes, vote_count: 2 })

test('English comes before a logo with no words, then the most voted', () => {
    assert.equal(logo([wide('/nowords000.png', null, 9), wide('/english00a.png', 'en', 4), wide('/english00b.png', 'en', 6)]), '/english00b.png')
    assert.equal(logo([wide('/nowords000.png', null), wide('/french0000.png', 'fr')]), '/nowords000.png')
})

test('stacked, small, SVG and other-language logos are not drawn', () => {
    assert.equal(logo([{ ...wide('/stacked000.png', 'en'), aspect_ratio: 1 }]), null)
    assert.equal(logo([wide('/smalllogo0.png', 'en', 5, 120)]), null)
    assert.equal(logo([wide('/vectorlogo.svg', 'en')]), null)
    assert.equal(logo([wide('/german0000.png', 'de')]), null)
    assert.equal(logo([]), null)
})

test('garbage in the logos list is skipped, never thrown on', () => {
    const odd = [null, 7, 'x', { file_path: 42 }, { file_path: '/../../etc.png', iso_639_1: 'en', aspect_ratio: 3, width: 900 }, wide('/goodlogo00.png', 'en')]
    assert.equal(logo(odd as never), '/goodlogo00.png')
    assert.equal(extras({ images: { logos: 'nope' } }, true, 'US').logo, null)
    assert.equal(extras({ images: { logos: [wide('/goodlogo00.png', 'en')] } }, true, 'US').logo, '/goodlogo00.png')
})

test('a show lists its numbered seasons in order, the specials left out, garbage skipped', () => {
    const seasons = [{ season_number: 2, episode_count: 8, air_date: '2022-01-01' }, { season_number: 0, episode_count: 30 }, { season_number: 1, episode_count: 10, air_date: '' }, null, 'x', { season_number: 'a' }, { season_number: 9999, episode_count: 1 }]
    assert.deepEqual(seasonList(seasons), [{ number: 1, episodes: 10, airDate: null }, { number: 2, episodes: 8, airDate: '2022-01-01' }])
    assert.deepEqual(seasonList('nope'), [])
    assert.equal(extras({ seasons }, true, 'US').seasonList.length, 0)
    assert.equal(extras({ seasons }, false, 'US').seasonList.length, 2)
})

test('the next episode needs a day and a numbered season', () => {
    assert.deepEqual(nextEpisode({ season_number: 3, episode_number: 5, air_date: '2027-01-02' }), { season: 3, episode: 5, airDate: '2027-01-02' })
    assert.equal(nextEpisode({ season_number: 0, episode_number: 1, air_date: '2027-01-02' }), null)
    assert.equal(nextEpisode({ season_number: 1, episode_number: 1 }), null)
    assert.equal(nextEpisode(null), null)
    assert.equal(nextEpisode(7), null)
})

// ---- the season split (the apps' SeasonSplitTest), Blue Box ----

const own = () => seasonList(page().seasons)
const split = () => buildSplit('seasons001', groupOf(SEASONS_GROUP), own())!

test('the Seasons group is chosen over Cours and a short Blu-ray', () => {
    const groups = episodeGroups(page())
    assert.equal(chooseGroup(own(), groups)?.id, 'seasons001')
    assert.equal(chooseGroup(own(), groups.filter((g) => g.id !== 'seasons001'))?.id, 'cours00001')
    assert.equal(chooseGroup(own(), groups.filter((g) => g.id === 'bluray0001')), null)
})

test('a name starting with Season breaks a tie; several seasons, absolute order and one group never split', () => {
    const tied = [{ id: 'aaaa', type: 7, episodes: 37, name: 'Cours', groups: 2 }, { id: 'bbbb', type: 6, episodes: 36, name: 'Season order', groups: 2 }]
    assert.equal(chooseGroup(own(), tied)?.id, 'bbbb')
    const two = [{ number: 1, episodes: 20, airDate: null }, { number: 2, episodes: 17, airDate: null }]
    assert.equal(chooseGroup(two, [{ id: 'xxxx', type: 6, episodes: 37, name: 'Seasons', groups: 3 }]), null)
    assert.equal(chooseGroup(own(), [{ id: 'absolute', type: 2, episodes: 37, name: 'Absolute', groups: 2 }]), null)
    assert.equal(chooseGroup(own(), [{ id: 'oneonly1', type: 6, episodes: 37, name: 'Seasons', groups: 1 }]), null)
    assert.equal(chooseGroup([], episodeGroups(page())), null)
})

test('the split makes season 1 of 25 and season 2 of 12, each numbered from one, in the group order', () => {
    const s = split()
    assert.deepEqual(s.seasons.map((x) => [x.number, x.episodes, x.airDate]), [[1, 25, air(1)], [2, 12, air(26)]])
    assert.deepEqual(s.episodes.get(2)!.map((e) => e.number), Array.from({ length: 12 }, (_, i) => i + 1))
    assert.equal(s.episodes.get(2)![0].runtime, 24)
    assert.equal(s.episodes.get(2)![0].still, '/still0026abc.jpg')
})

test('an untitled "Episode 32" is no name in season 2 where it is episode 7', () => {
    const e = split().episodes.get(2)!
    assert.equal(e[6].name, '')
    assert.equal(e[5].name, 'The 31th Tide')
})

test('the next episode is placed in the split; one the group lacks follows the last, one unplaceable is dropped', () => {
    const s = split()
    assert.deepEqual(splitNext(s, { season: 1, episode: 33, airDate: air(33) }), { season: 2, episode: 8, airDate: air(33) })
    assert.deepEqual(splitNext(s, { season: 1, episode: 39, airDate: '2027-01-01' }), { season: 2, episode: 14, airDate: '2027-01-01' })
    assert.equal(splitNext(s, { season: 3, episode: 1, airDate: '2027-01-01' }), null)
    assert.equal(splitNext(s, null), null)
})

test("applying the split keeps TMDB's own seasons and next beside it", () => {
    const found = extras(page(), false, 'US')
    assert.equal(found.seasonGroup, null)
    assert.deepEqual(found.seasonList.map((x) => [x.number, x.episodes]), [[1, 37]])
    const done = applySplit(found, split())
    assert.equal(done.seasons, 2)
    assert.deepEqual(done.seasonList.map((x) => [x.number, x.episodes]), [[1, 25], [2, 12]])
    assert.equal(done.seasonGroup, 'seasons001')
    assert.deepEqual(done.listedSeasons!.map((x) => [x.number, x.episodes]), [[1, 37]])
    assert.deepEqual(done.listedNext, { season: 1, episode: 33, airDate: air(33) })
    assert.deepEqual(done.next, { season: 2, episode: 8, airDate: air(33) })
    assert.equal(splitEpisode(split(), 2, 1)?.name, 'The 26th Tide')
    assert.equal(splitEpisode(split(), 2, 99), undefined)
})

test('a group holding under 90% of the show, one group alone, or nonsense numbers split nothing', () => {
    const half = { ...SEASONS_GROUP, groups: SEASONS_GROUP.groups.map((g) => ({ ...g, episodes: g.episodes.slice(0, 5) })) }
    assert.equal(buildSplit('g', groupOf(half), own()), null)
    assert.equal(buildSplit('g', groupOf({ groups: [SEASONS_GROUP.groups[1]] }), own()), null)
    const nonsense = Array.from({ length: 37 }, (_, i) => ({ groupOrder: i % 2, order: i, season: -1, episode: 2 ** 31, piece: { absolute: 0 } }))
    assert.equal(buildSplit('g', nonsense, own()), null)
    const same = Array.from({ length: 37 }, (_, i) => ({ groupOrder: i % 2, order: i, season: 1, episode: 1, piece: { absolute: 0 } }))
    assert.equal(buildSplit('g', same, own()), null)
    assert.equal(buildSplit('g', [null, 7, 'x', {}] as never, own()), null)
})

test('garbage and huge group answers never throw and keep only the sane ones', () => {
    assert.deepEqual(episodeGroups({}), [])
    assert.deepEqual(episodeGroups({ episode_groups: 'nope' }), [])
    const odd = { episode_groups: { results: [null, 7, 'x', { id: '../../etc' }, { id: 'x' }, { id: 'goodid01', type: 'a', episode_count: -4, group_count: 1e99, name: 'N'.repeat(1000) }] } }
    assert.deepEqual(episodeGroups(odd).map((g) => [g.id, g.type, g.episodes, g.groups, g.name.length]), [['goodid01', 0, 0, 0, 200]])
    const many = { episode_groups: { results: Array.from({ length: 5000 }, (_, i) => ({ id: `group${i}x`, type: 6, episode_count: 37, group_count: 2, name: 'Seasons' })) } }
    assert.equal(episodeGroups(many).length, 100)
    assert.equal(chooseGroup([{ number: 1, episodes: 2 ** 40, airDate: null }], [{ id: 'hugegroup', type: 6, episodes: 2 ** 40, name: 'Seasons', groups: 2 ** 40 }]), null)
    assert.equal(chooseGroup([{ number: 1, episodes: -3, airDate: null }], episodeGroups(page())), null)
})
