// The title logo pick (MetaRules.logo) over TMDB's `images.logos`, and that a page with none, or
// with only stacked, tiny, SVG or other-language ones, gets the name instead.
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { extras, logo, nextEpisode, seasonList } from './extras.ts'

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
