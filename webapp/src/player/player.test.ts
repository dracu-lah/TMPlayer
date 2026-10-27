// The player's pure rules and the chapter reader, against real files from e2e/media.
// `node --test "src/player/*.test.ts"` (pnpm test:player).
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import { chapterAt, chapterMarks, chapterNext, chapterPrevious, readChapters, skipSegments } from './chapters.ts'
import { fineRate, pressLoop, SeekUndo, wrapLoop } from './extras.ts'
import { cardLeft, cardSize, frameSlot, parseStoryboardMap } from './seekpreview.ts'
import type { TgFile } from '../stream/reader'

const media = (name: string) => new Uint8Array(readFileSync(new URL(`../../e2e/media/${name}`, import.meta.url)))
const fake = (bytes: Uint8Array) => ({ chunk: 512 * 1024, size: bytes.length, read: async (start: number, end: number) => bytes.subarray(start, Math.min(end, bytes.length)) }) as unknown as TgFile

test('a Matroska file gives its chapters', async () => {
    const list = await readChapters(fake(media('chapters.mkv')))
    assert.deepEqual(
        list.map((c) => [c.start, c.title]),
        [[0, 'Intro'], [5, 'The Harbour'], [15, 'Credits']],
    )
    assert.deepEqual(chapterMarks(list, 20), [5, 15])
})

test('a file without chapters gives none', async () => {
    assert.deepEqual(await readChapters(fake(media('h264-aac.mkv'))), [])
    assert.deepEqual(await readChapters(fake(media('h264-aac.mp4'))), [])
    assert.deepEqual(await readChapters(fake(media('h264-aac.ts'))), [])
})

test('an MP4 with a chapter list gives its chapters', async () => {
    const list = await readChapters(fake(media('chapters.mp4')))
    assert.deepEqual(list.map((c) => c.title), ['Intro', 'The Harbour', 'Credits'])
})

test('chapter steps follow the apps', async () => {
    const list = await readChapters(fake(media('chapters.mkv')))
    assert.equal(chapterAt(list, 7), 1)
    assert.equal(chapterNext(list, 7), 2)
    assert.equal(chapterNext(list, 16), null)
    // More than three seconds in: back to the start of this one; near its start: the one before.
    assert.equal(chapterPrevious(list, 9), 1)
    assert.equal(chapterPrevious(list, 6), 0)
    assert.equal(chapterPrevious(list, 0.5), 0)
})

test('the intro and the credits are offered to skip, a plain chapter is not', async () => {
    const list = await readChapters(fake(media('chapters.mkv')))
    assert.deepEqual(skipSegments(list, 20), [
        { kind: 'intro', start: 0, end: 5 },
        { kind: 'credits', start: 15, end: 20 },
    ])
    // Too short, or most of the video: not an intro.
    assert.deepEqual(skipSegments([{ start: 0, title: 'Intro' }, { start: 3, title: null }], 100), [])
    assert.deepEqual(skipSegments([{ start: 0, title: 'Introduction' }, { start: 60, title: null }], 100), [])
})

test('A-B repeat: start, end, off', () => {
    const a = pressLoop(null, 10)
    assert.deepEqual(a, { start: 10, end: null })
    // Too close to the start: nothing changes.
    assert.equal(pressLoop(a, 10.2), a)
    const ab = pressLoop(a, 4)
    assert.deepEqual(ab, { start: 4, end: 10 })
    assert.equal(pressLoop(ab, 12), null)
    assert.equal(wrapLoop(ab, 10.1), 4)
    // A seek far past the loop is left alone; a start alone does not loop.
    assert.equal(wrapLoop(ab, 30), null)
    assert.equal(wrapLoop(a, 99), null)
})

test('the way back: a long jump offers it, a short one does not, a burst keeps its origin', () => {
    const u = new SeekUndo()
    assert.equal(u.seeked(100, 105, 0), null)
    const far = u.seeked(100, 400, 10_000)
    assert.equal(far?.backTo, 100)
    assert.equal(u.take(10_500), 100)
    // The seek back is not a journey of its own.
    assert.equal(u.seeked(400, 100, 10_600), null)
    // Presses close together are one journey from where it began.
    const v = new SeekUndo()
    for (let i = 0; i < 8; i++) v.seeked(100 + i * 10, 110 + i * 10, 20_000 + i * 300)
    assert.equal(v.current(22_500)?.backTo, 100)
    // The offer goes after its few seconds.
    assert.equal(v.current(40_000), null)
})

test('a finger further from the bar scrubs finer', () => {
    assert.equal(fineRate(10), 1)
    assert.equal(fineRate(-70), 0.5)
    assert.equal(fineRate(200), 0.25)
})

test('the seek card is 16:9 at its standard size and stays inside the bar', () => {
    assert.deepEqual(cardSize(false, 1200), { width: 240, height: 135 })
    assert.deepEqual(cardSize(true, 360), { width: 160, height: 90 })
    // A bar narrower than the card narrows it, never under 96 px.
    assert.equal(cardSize(false, 180).width, 180)
    assert.equal(cardSize(false, 10).width, 96)
    assert.equal(cardLeft(0, 240, 1000), 0)
    assert.equal(cardLeft(1000, 240, 1000), 760)
    assert.equal(cardLeft(500, 240, 1000), 380)
    assert.equal(cardLeft(0, 160, 300, 16), -16)
    // A card wider than the bar sits centred over it.
    assert.equal(cardLeft(50, 240, 100), -70)
})

test('frames are grabbed at the middle of a slot, a second apart on a short video', () => {
    assert.equal(frameSlot(3600, 0), 5)
    assert.equal(frameSlot(3600, 1234), 1235)
    assert.equal(frameSlot(30, 7.4), 7.5)
    // Unknown or zero length: ten second slots; never past the end.
    assert.equal(frameSlot(0, 12), 15)
    assert.equal(frameSlot(NaN, -4), 5)
    assert.ok(frameSlot(2, 1.9) <= 2)
})

test('a storyboard map is read in either shape, garbage skipped', () => {
    const plain = parseStoryboardMap('file=sb.jpg\nframe_width=160\nframe_height=90\n10,160,0\n0,0,0\nnot,a,line\n')
    assert.deepEqual(plain, { width: 160, height: 90, frames: [{ t: 0, x: 0, y: 0 }, { t: 10, x: 160, y: 0 }] })
    const vtt = parseStoryboardMap('WEBVTT\n\n00:00:00.000 --> 00:00:05.000\nsb.jpg#xywh=0,0,128,72\n\n00:01:05.000 --> 00:01:10.000\nsb.jpg#xywh=128,0,128,72\n')
    assert.deepEqual(vtt, { width: 128, height: 72, frames: [{ t: 0, x: 0, y: 0 }, { t: 65, x: 128, y: 0 }] })
    assert.equal(parseStoryboardMap(''), null)
    assert.equal(parseStoryboardMap('frame_width=160\nframe_height=90\n'), null)
    assert.equal(parseStoryboardMap('\u0000￿ garbage'.repeat(1000)), null)
    assert.equal(parseStoryboardMap('frame_width=99999\nframe_height=90\n0,0,0'), null)
})
