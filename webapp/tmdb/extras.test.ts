// The title logo pick (MetaRules.logo) over TMDB's `images.logos`, and that a page with none, or
// with only stacked, tiny, SVG or other-language ones, gets the name instead.
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { extras, logo } from './extras.ts'

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
