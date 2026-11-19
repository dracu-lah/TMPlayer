// U6 and U16 on the web over the real rules (core's WebRules, built by `pnpm rules`): the chat's
// filters, what they hide and cannot judge, and the video Play random picks.
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import * as core from '../rules/core/tmplayer-rules.mjs'
import type { Rules, Video } from '../rules'

core.provideCatalog('en', readFileSync(new URL('../../../core/src/commonMain/resources/i18n/en.json', import.meta.url), 'utf8'))
const r = core as unknown as Rules

const CHAT = -1007
const NOW = 1_800_000_000
const DAY = 86_400
function video(messageId: number, fileName: string, o: { duration?: number; date?: number; height?: number; width?: number; caption?: string } = {}): Video {
    const v = r.video({
        chatId: CHAT, messageId, kind: o.duration ? 'video' : 'document', fileName, mimeType: 'video/x-matroska', caption: o.caption ?? null,
        size: 600_000_000 + messageId, duration: o.duration ?? 0, width: o.width ?? 0, height: o.height ?? 0,
        date: o.date ?? NOW - messageId * DAY, topicId: 0, albumId: null, canBeSaved: true, selfDestructs: false, withheld: false,
    })
    assert.ok(v, fileName)
    return v
}
const view = (videos: Video[], o: { sort?: string; filter?: string; facets?: string | null; watched?: string[]; started?: string[] } = {}) =>
    r.shelfView(videos, o.sort ?? 'Newest', o.filter ?? 'All', o.facets ?? null, 0, r.sizeLimits().ceiling, o.watched ?? [], o.started ?? [], [], [], NOW)
const names = (v: ReturnType<typeof view>) => v.tiles.map((t) => t.video?.fileName ?? t.title)

const shelf = () => [
    video(1, 'Harbour.Notes.2021.1080p.WEB-DL.Hindi.mkv', { duration: 5_400, height: 1080, width: 1920 }),
    video(2, 'Lake.Walk.2019.720p.HDR.mkv', { duration: 900, height: 720, width: 1280 }),
    video(3, 'Night.Train.2160p.HDR.ESub.mkv', { duration: 7_200, height: 2160, width: 3840 }),
    video(4, 'untitled clip.mkv'),
    video(40, 'Old.Reel.1999.480p.mkv', { duration: 3_000, height: 480, width: 640, date: NOW - 400 * DAY }),
]

test('with no filter every video shows and nothing is counted as hidden', () => {
    const v = view(shelf())
    assert.equal(v.tiles.length, 5)
    assert.equal(v.total, 5)
    assert.equal(v.hiddenByFilter, 0)
    assert.equal(v.unknown, 0)
})

test('length, picture, HDR and subtitles combine; a video that states neither length nor picture is kept after the matches and counted', () => {
    const short = view(shelf(), { facets: 'len=short' })
    assert.deepEqual(names(short), ['Lake.Walk.2019.720p.HDR.mkv', 'untitled clip.mkv'])
    assert.equal(short.unknown, 1)
    assert.equal(short.hiddenByFilter, 3)
    const sharp = view(shelf(), { facets: 'pic=1080;hdr=1' })
    assert.deepEqual(names(sharp), ['Night.Train.2160p.HDR.ESub.mkv'])
    assert.deepEqual(names(view(shelf(), { facets: 'sub=1' })), ['Night.Train.2160p.HDR.ESub.mkv'])
})

test('added this week, month or year is by the video date', () => {
    assert.equal(view(shelf(), { facets: 'add=week' }).tiles.length, 4, 'four videos are 1 to 4 days old, the 400 day old reel goes')
    assert.equal(view(shelf(), { facets: 'add=year' }).tiles.length, 4)
})

test('Unwatched, In progress and Watched never overlap, and a started video is not unwatched', () => {
    const watched = ['-1007:1']
    const started = ['-1007:2']
    const ids = (f: string) => view(shelf(), { filter: f, watched, started }).tiles.map((t) => t.video?.id)
    assert.deepEqual(ids('Watched'), ['-1007:1'])
    assert.deepEqual(ids('InProgress'), ['-1007:2'])
    assert.ok(!ids('Unwatched').includes('-1007:1') && !ids('Unwatched').includes('-1007:2'))
    assert.equal(ids('Unwatched').length + ids('Watched').length + ids('InProgress').length, 5)
    assert.equal(view(shelf(), { filter: 'Nonsense' }).tiles.length, 5, 'an unknown filter is All')
})

test('a filter that matches nothing leaves total standing, so the page can say every loaded video is hidden', () => {
    const v = view(shelf(), { filter: 'Shows' })
    assert.equal(v.tiles.length, 0)
    assert.equal(v.total, 5)
    assert.equal(v.hiddenByFilter, 5)
})

test('the Language chips come from the names, and the filter line is written and read by the rules', () => {
    const v = view(shelf())
    assert.ok(v.languages.includes('hi'))
    assert.deepEqual(names(view(shelf(), { facets: 'lang=hi' })), ['Harbour.Notes.2021.1080p.WEB-DL.Hindi.mkv'])
    let line = r.facetsWith(null, 'length', 'short')
    line = r.facetsWith(line, 'hdr', '1')
    line = r.facetsWith(line, 'language', 'ML')
    const f = r.facetsRead(line)
    assert.deepEqual([f.length, f.hdr, f.language, f.count], ['short', true, 'ml', 3])
    assert.equal(r.facetsWith(line, 'clear', null), '')
    assert.equal(r.facetsRead('len=banana;;;lang=1;pic=720').picture, '720', 'each unreadable part is that filter off, not the line lost')
})

test('the Last played order lists what was watched most recently first', () => {
    const v = r.shelfView(shelf(), 'LastPlayed', 'All', null, 0, r.sizeLimits().ceiling, [], [], ['-1007:3', '-1007:1'], [2_000, 1_000], NOW)
    assert.deepEqual(v.tiles.slice(0, 2).map((t) => t.video?.id), ['-1007:3', '-1007:1'])
})

test('Play random picks an unwatched video while any is left and never the one it played last', () => {
    const videos = shelf()
    const watched = videos.map((v) => v.id).filter((id) => id !== '-1007:3')
    for (let i = 0; i < 30; i++) assert.equal(r.playRandom(videos, watched, null), '-1007:3')
    for (let i = 0; i < 60; i++) assert.notEqual(r.playRandom(videos, [], '-1007:2'), '-1007:2')
    // Only the last one is unwatched: another, watched, video beats a repeat.
    for (let i = 0; i < 30; i++) assert.notEqual(r.playRandom(videos, watched, '-1007:3'), '-1007:3')
    // A list of one has nothing else to offer.
    assert.equal(r.playRandom([videos[0]], [], videos[0].id), videos[0].id)
    assert.equal(r.playRandom([], [], null), null)
})

test('twenty thousand loaded videos are filtered and picked from in a blink', () => {
    const big: Video[] = []
    for (let i = 1; i <= 20_000; i++) big.push(video(i, `Show.Name.S01E${i % 99}.${i % 2 ? '1080p' : '720p'}.mkv`, { duration: 600 + (i % 5) * 600, date: NOW - i * 60 }))
    const start = Date.now()
    const v = view(big, { facets: 'len=short;pic=1080' })
    const pick = r.playRandom(big, [], null)
    assert.ok(v.tiles.length > 0 && pick)
    assert.ok(Date.now() - start < 8_000, `took ${Date.now() - start} ms`)
})
