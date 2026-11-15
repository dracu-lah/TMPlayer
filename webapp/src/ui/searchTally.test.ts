// The count line's figures, the web twin of core's GlobalSearchTest tally. `node --test src/ui/searchTally.test.ts`
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { tally, type Countable } from './searchTally.ts'

const file = (chatId: number): Countable => ({ kind: 'file', episodes: 0, item: { chatId } })
const show = (chatId: number, episodes: number): Countable => ({ kind: 'show', episodes, item: { chatId } })

test('a video is one, a show is its episodes, chats are counted once', () => {
    assert.deepEqual(tally([file(1), file(2), show(1, 8)]), { videos: 10, chats: 2 })
})

test('nothing found counts nothing', () => {
    assert.deepEqual(tally([]), { videos: 0, chats: 0 })
})

test('a show with no episode count still counts as one, and garbage counts do not throw', () => {
    assert.deepEqual(tally([show(3, 0), show(3, Number.NaN), show(3, -4)]), { videos: 3, chats: 1 })
})
