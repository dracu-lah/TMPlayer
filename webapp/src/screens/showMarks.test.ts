// The web twin of core's ShowMarksTest. `node --test src/screens/showMarks.test.ts`
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { showChoices, type MarkSeason } from './showMarks.ts'

const ep = (messageId: number) => ({ chatId: -100, messageId })
const season = (number: number, ids: number[]): MarkSeason<{ chatId: number; messageId: number }> => ({ number, episodes: ids.map(ep) })
const seen = (...ids: number[]) => new Set(ids.map((id) => `-100:${id}`))

test('a show not started offers its season, then the whole show', () => {
    const seasons = [season(1, [1, 2, 3]), season(2, [4, 5])]
    const lines = showChoices(seasons, seasons[0], seen())
    assert.deepEqual(lines.map((l) => [l.scope, l.watched, l.episodes.length]), [['season', true, 3], ['show', true, 5]])
})

test('each line counts only what it changes, and unwatched comes once something is watched', () => {
    const seasons = [season(1, [1, 2, 3]), season(2, [4, 5])]
    const lines = showChoices(seasons, seasons[0], seen(1, 4))
    assert.deepEqual(lines.map((l) => [l.scope, l.watched, l.episodes.length]), [
        ['season', true, 2],
        ['show', true, 3],
        ['season', false, 1],
        ['show', false, 2],
    ])
})

test('one season alone is not offered twice as the whole show', () => {
    const seasons = [season(1, [1, 2])]
    const lines = showChoices(seasons, seasons[0], seen(1))
    assert.deepEqual(lines.map((l) => [l.scope, l.watched]), [['season', true], ['season', false]])
})

test('a show watched whole offers only unwatched, and an empty one nothing', () => {
    const seasons = [season(1, [1]), season(2, [2])]
    assert.deepEqual(showChoices(seasons, seasons[1], seen(1, 2)).map((l) => [l.scope, l.watched]), [['season', false], ['show', false]])
    assert.deepEqual(showChoices([], undefined, seen()), [])
})

test('five hundred episodes in one season are one line of five hundred', () => {
    const ids = Array.from({ length: 500 }, (_, i) => i + 1)
    const seasons = [season(1, ids)]
    const lines = showChoices(seasons, seasons[0], seen())
    assert.equal(lines.length, 1)
    assert.equal(lines[0].episodes.length, 500)
})
