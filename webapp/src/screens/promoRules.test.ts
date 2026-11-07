// The web twin of core's PromoCoversTest. `node --test src/screens/promoRules.test.ts`
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { promoCovers, type CoverItem } from './promoRules.ts'

const video = (chatId: number, title: string | null, cover: string | null): CoverItem => ({ chatId, cover, poster: title ? { title } : null })

test('an advert on three different films is flagged', () => {
    const found = promoCovers([video(1, 'Harbour Notes', 'ad'), video(1, 'Lantern Road', 'ad'), video(1, 'Quiet River', 'ad'), video(1, 'Other Film', 'own')])
    assert.deepEqual([...found], ['ad'])
})

test('the episodes of one show with one image are not flagged', () => {
    const items = Array.from({ length: 6 }, () => video(1, 'Harbour Notes', 'ad'))
    assert.equal(promoCovers(items).size, 0)
})

test('titles differing only in case and punctuation are one title', () => {
    const items = [video(1, 'Harbour Notes', 'ad'), video(1, 'harbour  notes!', 'ad'), video(1, 'HARBOUR-NOTES', 'ad')]
    assert.equal(promoCovers(items).size, 0)
})

test('two files are below the threshold', () => {
    assert.equal(promoCovers([video(1, 'Harbour Notes', 'ad'), video(1, 'Lantern Road', 'ad')]).size, 0)
})

test('no cover and no title count for nothing', () => {
    const items = [video(1, 'A', null), video(1, 'B', null), video(1, 'C', null), video(1, null, 'ad'), video(1, null, 'ad'), video(1, null, 'ad')]
    assert.equal(promoCovers(items).size, 0)
})

test('the count is per chat, except across three chats', () => {
    const two = [video(1, 'A', 'ad'), video(1, 'B', 'ad'), video(2, 'C', 'ad'), video(2, 'D', 'ad')]
    assert.equal(promoCovers(two).size, 0)
    const three = [video(1, 'A', 'ad'), video(2, 'B', 'ad'), video(3, 'C', 'ad')]
    assert.equal(promoCovers(three).size, 1)
    const oneFilm = [video(1, 'A', 'ad'), video(2, 'A', 'ad'), video(3, 'A', 'ad')]
    assert.equal(promoCovers(oneFilm).size, 0)
})

test('three thousand videos are worked out at once', () => {
    const items = Array.from({ length: 3000 }, (_, n) => video(1, `Film ${n}`, n % 5 === 0 ? 'ad' : `own${n}`))
    const started = performance.now()
    const found = promoCovers(items)
    assert.ok(performance.now() - started < 500)
    assert.deepEqual([...found], ['ad'])
})
