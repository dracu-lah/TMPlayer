import { test } from 'node:test'
import assert from 'node:assert/strict'
import { subtitleFromDrop } from './subtitles.ts'

const file = (name: string) => ({ name }) as File

test('a dropped subtitle is found among other files, by extension and in any case', () => {
    assert.equal(subtitleFromDrop([file('movie.mkv'), file('Movie.EN.SRT'), file('b.vtt')])?.name, 'Movie.EN.SRT')
})

test('a drop with no subtitle, no files or nothing at all does nothing', () => {
    assert.equal(subtitleFromDrop([file('movie.mkv'), file('notes.txt'), file('srt')]), null)
    assert.equal(subtitleFromDrop([]), null)
    assert.equal(subtitleFromDrop(null), null)
    assert.equal(subtitleFromDrop(undefined), null)
})
