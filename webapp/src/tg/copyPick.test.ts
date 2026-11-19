// HX38 on the web over the real rules (core's WebRules, built by `pnpm rules`): the copy a card
// plays, the copies list's order, its tags and its count, with what only the browser knows handed in.
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import * as core from '../rules/core/tmplayer-rules.mjs'
import type { Rules, Video } from '../rules'
import { chosenAmong, copyRows, playCopy, slowIds } from './copyPick.ts'
import { cleanContext, copyFit, deviceMoved, NO_COPY_CONTEXT, pictureKind, slowHere, slowKinds, type CopyContext } from './copyFit.ts'

core.provideCatalog('en', readFileSync(new URL('../../../core/src/commonMain/resources/i18n/en.json', import.meta.url), 'utf8'))
const r = core as unknown as Rules

const CHAT = -1005
let date = 1_700_000_000
function video(messageId: number, fileName: string, o: { width?: number; height?: number; size?: number; mimeType?: string; date?: number } = {}): Video {
    const v = r.video({
        chatId: CHAT, messageId, kind: 'document', fileName, mimeType: o.mimeType ?? 'video/x-matroska', caption: null,
        size: o.size ?? 2_000_000_000 + messageId, duration: 5_400, width: o.width ?? 0, height: o.height ?? 0,
        date: o.date ?? date++, topicId: 0, albumId: null, canBeSaved: true, selfDestructs: false, withheld: false,
    })
    assert.ok(v, fileName)
    return v
}
const ctx = (o: Partial<CopyContext>): CopyContext => ({ ...NO_COPY_CONTEXT, ...o })
const CHROMIUM = slowKinds((m) => /avc1\.(42|4D|64)|vp09|vp9|av01/.test(m))

const film = () => [
    video(25, 'Big.Buck.Bunny.2008.2160p.HDR.HEVC.mkv'),
    video(26, 'Big.Buck.Bunny.2008.1080p.BluRay.x264.mkv'),
    video(27, 'Big.Buck.Bunny.2008.720p.WEB-DL.x264.mkv'),
]

test('the name and type say the picture kind; H.264 and nothing said play everywhere', () => {
    assert.equal(pictureKind('Film.2160p.x265.mkv', null), 'hevc')
    assert.equal(pictureKind('Film.1080p.HEVC.10bit.mkv', null), 'hevc10')
    assert.equal(pictureKind('Film H.265 Hi10P.mkv', null), 'hevc10')
    assert.equal(pictureKind('Film.2023.AV1.mkv', null), 'av1')
    assert.equal(pictureKind('Film.VP9.webm', null), 'vp9')
    assert.equal(pictureKind('Film.DVDRip.XviD.avi', null), 'legacy')
    assert.equal(pictureKind('clip', 'video/x-msvideo'), 'legacy')
    assert.equal(pictureKind('Film.1080p.x264.mkv', 'video/x-matroska'), null)
    assert.equal(pictureKind('Heavy.Rain.mkv', null), null, 'a word with hevc letters inside is not a codec')
    assert.equal(pictureKind(undefined, 42), null)
    assert.equal(pictureKind('x'.repeat(100_000) + '.avi', null), null, 'a huge name is read only so far')
})

test('a browser without HEVC finds both HEVC kinds slow; a TV that decodes HEVC finds neither', () => {
    assert.deepEqual(CHROMIUM, ['hevc', 'hevc10', 'avc10', 'legacy'])
    assert.equal(slowHere('Film.HEVC.10bit.mkv', null, ['hevc']), true)
    assert.equal(slowHere('Film.x264.mkv', null, CHROMIUM), false)
    const tv = slowKinds(() => false, true)
    assert.deepEqual(tv, ['avc10', 'av1', 'vp9', 'legacy'])
    const thrower = slowKinds(() => {
        throw new Error('old engine')
    })
    assert.deepEqual(thrower, ['hevc', 'hevc10', 'avc10', 'av1', 'vp9', 'legacy'])
})

test('Smooth is said only beside a copy that may stutter', () => {
    assert.equal(copyFit(true, true), 'stutter')
    assert.equal(copyFit(false, true), 'smooth')
    assert.equal(copyFit(false, false), null)
})

test('the context from the page is cleaned: garbage becomes nothing, never an error', () => {
    for (const bad of [null, undefined, 5, 'x', [], { device: 'fridge', slow: 'hevc', apart: [1, '1:2:3', '-5:9'], chosen: {}, habit: 'DROP TABLE' }]) {
        const c = cleanContext(bad)
        assert.equal(c.device === 'phone' || c.device === 'tv' || c.device === 'computer', true)
        assert.ok(Array.isArray(c.slow) && Array.isArray(c.apart) && Array.isArray(c.chosen))
    }
    const c = cleanContext({ device: 'tv', slow: ['hevc', 'nope'], apart: ['-5:9', '-5:9', 'bad'], chosen: Array(9_000).fill('-5:1'), habit: '1080:3,720:1' })
    assert.deepEqual(c, { device: 'tv', slow: ['hevc'], apart: ['-5:9'], chosen: ['-5:1'], habit: '1080:3,720:1', titles: [] })
    assert.equal(cleanContext({ habit: 'x'.repeat(1_000) }).habit, null)
})

test('a computer whose browser plays HEVC leads with 4K; one without HEVC leads with the 1080p it plays', () => {
    r.setCopyDevice('computer')
    const copies = film()
    assert.deepEqual(copyRows(r, copies, '-1005:26', ctx({ device: 'computer' }), []).map((x) => x.video.messageId), [25, 26, 27])
    assert.equal(playCopy(r, copies, ctx({ device: 'computer' }), new Set()), '-1005:25')
    const rows = copyRows(r, copies, '-1005:26', ctx({ device: 'computer', slow: CHROMIUM }), [])
    assert.deepEqual(rows.map((x) => x.video.messageId), [26, 27, 25])
    assert.deepEqual(rows.map((x) => x.best), [true, false, false])
    assert.deepEqual(rows.map((x) => x.fit), ['smooth', 'smooth', 'stutter'])
    assert.equal(playCopy(r, copies, ctx({ device: 'computer', slow: CHROMIUM }), new Set()), '-1005:26')
    assert.deepEqual(slowIds(copies, ctx({ slow: CHROMIUM })), ['-1005:25'])
})

test('a phone is capped at 1080p: 4K stays in the list, never first, even where it plays', () => {
    r.setCopyDevice('phone')
    const copies = film()
    const rows = copyRows(r, copies, '-1005:25', ctx({ device: 'phone' }), [])
    assert.deepEqual(rows.map((x) => x.video.messageId), [26, 27, 25])
    assert.equal(rows[0].best, true)
    assert.deepEqual(rows.map((x) => x.fit), [null, null, null], 'nothing may stutter: nothing is said')
    assert.equal(playCopy(r, copies, ctx({ device: 'phone' }), new Set()), '-1005:26')
    r.setCopyDevice('computer')
})

test("the viewer's own choice and the copy they started win over the recommendation, whatever it is", () => {
    r.setCopyDevice('computer')
    const copies = film()
    const c = ctx({ device: 'computer', slow: CHROMIUM, chosen: ['-1005:25', '-1005:27'] })
    assert.equal(chosenAmong(copies, c), '-1005:25', 'the newest choice first')
    assert.equal(playCopy(r, copies, c, new Set()), '-1005:25', 'chosen, even a copy that may stutter')
    assert.equal(playCopy(r, copies, ctx({ device: 'computer', slow: CHROMIUM }), new Set(['-1005:27'])), '-1005:27')
    assert.equal(playCopy(r, copies, ctx({ device: 'computer', slow: CHROMIUM, habit: '720:9' }), new Set()), '-1005:27', 'the quality usually played')
    const rows = copyRows(r, copies, '-1005:26', ctx({ device: 'computer', slow: CHROMIUM }), ['-1005:27'])
    assert.deepEqual(rows.map((x) => x.started), [false, true, false])
})

test('one copy is no list; the same file posted twice is one row, the page’s own post standing for it', () => {
    r.setCopyDevice('computer')
    assert.deepEqual(copyRows(r, [video(30, 'Solo.2020.1080p.mkv')], '-1005:30', NO_COPY_CONTEXT, []), [])
    const twice = [video(31, 'Twice.2020.1080p.mkv', { size: 900 }), video(32, 'Twice.2020.1080p.mkv', { size: 900 })]
    assert.deepEqual(copyRows(r, twice, '-1005:31', NO_COPY_CONTEXT, []), [], 'one file posted twice is not two versions')
    const three = [...twice, video(33, 'Twice.2020.720p.mkv', { size: 500 })]
    const rows = copyRows(r, three, '-1005:32', NO_COPY_CONTEXT, [])
    assert.deepEqual(rows.map((x) => x.video.messageId), [32, 33])
    assert.equal(rows.length, r.versionCount(three), 'the count is the list')
})

test('sizes unknown and no quality said: no tag rather than a guess; a name the size contradicts keeps its flag', () => {
    r.setCopyDevice('computer')
    const plain = [video(40, 'Plain.Film.mkv', { size: 0 }), video(41, 'Plain.Film.mkv', { size: 0 })]
    const rows = copyRows(r, plain, '-1005:40', NO_COPY_CONTEXT, [])
    assert.equal(rows.length, 2)
    assert.deepEqual(rows.map((x) => x.best), [false, false])
    const lying = [video(42, 'Liar.2021.720p.mkv', { width: 1920, height: 1080 }), video(43, 'Liar.2021.480p.mkv')]
    const read = copyRows(r, lying, '-1005:42', NO_COPY_CONTEXT, [])
    assert.equal(read[0].video.messageId, 42)
    assert.equal(read[0].mismatch, true)
    assert.equal(r.qualityRead(lying[0]).label, '1080p', 'the stated size wins')
})

test('a cam is never recommended where a better copy exists', () => {
    r.setCopyDevice('computer')
    const copies = [video(50, 'Fresh.2024.2160p.CAMRip.mkv'), video(51, 'Fresh.2024.720p.WEB-DL.mkv')]
    assert.equal(copyRows(r, copies, '-1005:50', NO_COPY_CONTEXT, [])[0].video.messageId, 51)
    assert.equal(playCopy(r, copies, NO_COPY_CONTEXT, new Set()), '-1005:51')
})

test('600 copies: one pass, the count is the list, the recommended first', () => {
    r.setCopyDevice('computer')
    const many = Array.from({ length: 600 }, (_, i) => video(1_000 + i, `Many.2022.${[2160, 1080, 720, 480][i % 4]}p.${i % 3 ? 'x264' : 'x265'}.part${i}.mkv`))
    const t0 = performance.now()
    const rows = copyRows(r, many, '-1005:1000', ctx({ device: 'computer', slow: CHROMIUM }), [])
    const took = performance.now() - t0
    assert.equal(rows.length, 600)
    assert.equal(rows.length, r.versionCount(many))
    assert.equal(rows.filter((x) => x.best).length, 1)
    assert.equal(rows[0].best, true)
    assert.equal(rows[0].fit, 'smooth')
    assert.ok(took < 2_000, `took ${took} ms`)
})

test('"Not the same video" splits the file out of its group, and Group again puts it back', () => {
    r.setCopyDevice('computer')
    const copies = film()
    r.setCorrections(['-1005:27'], ['separate'])
    const apart = r.shelf(copies, 'Newest', 0, r.sizeLimits().ceiling, [], [])
    assert.deepEqual(apart.tiles.map((t) => t.versions).sort(), [1, 2])
    r.setCorrections([], [])
    const together = r.shelf(copies, 'Newest', 0, r.sizeLimits().ceiling, [], [])
    assert.deepEqual(together.tiles.map((t) => t.versions), [3])
})

test('review fix: a started cam copy resumes in its own file; with nothing started the cam is still passed over', () => {
    r.setCopyDevice('computer')
    const copies = [video(60, 'Early.2024.2160p.CAMRip.mkv'), video(61, 'Early.2024.720p.WEB-DL.mkv')]
    assert.equal(playCopy(r, copies, NO_COPY_CONTEXT, new Set(['-1005:60'])), '-1005:60')
    assert.equal(playCopy(r, copies, NO_COPY_CONTEXT, new Set()), '-1005:61')
    const rows = copyRows(r, copies, '-1005:60', NO_COPY_CONTEXT, ['-1005:60'])
    assert.equal(rows[0].video.messageId, 61, 'the list still puts the cam last')
    assert.equal(rows.find((x) => x.best)?.video.messageId, 61)
})

test('review fix: 10 bit H.264 (Hi10P) is a kind of its own, slow where High 10 is not decoded, never leading there', () => {
    assert.equal(pictureKind('[Group] Show - 01 (1080p x264 Hi10P).mkv', null), 'avc10')
    assert.equal(pictureKind('Film.2019.1080p.10bit.x264.mkv', null), 'avc10')
    assert.equal(pictureKind('Film.2019.1080p.H.264.10-bit.mkv', null), 'avc10')
    assert.equal(pictureKind('Film.2019.1080p.x264.mkv', null), null)
    assert.equal(slowHere('Show (1080p x264 Hi10P).mkv', null, CHROMIUM), true)
    r.setCopyDevice('computer')
    const copies = [video(70, 'Anime.2019.1080p.x264.Hi10P.mkv'), video(71, 'Anime.2019.720p.x264.mkv')]
    const c = ctx({ device: 'computer', slow: CHROMIUM })
    assert.equal(copyRows(r, copies, '-1005:70', c, [])[0].video.messageId, 71)
    assert.equal(playCopy(r, copies, c, new Set()), '-1005:71')
    assert.equal(copyRows(r, copies, '-1005:70', c, [])[1].fit, 'stutter')
})

test('review fix: copies of one video in two chats: the card counts them and the page lists the same group, one row a file', () => {
    r.setCopyDevice('computer')
    const raw = (chatId: number, messageId: number, fileName: string) =>
        r.video({ chatId, messageId, kind: 'document', fileName, mimeType: 'video/x-matroska', caption: null, size: 1_000_000 + messageId, duration: 5_400, width: 0, height: 0, date: date++, topicId: 0, albumId: null, canBeSaved: true, selfDestructs: false, withheld: false })!
    const both = [raw(-1005, 80, 'Twin.Town.2018.1080p.x264.mkv'), raw(-1005, 81, 'Twin.Town.2018.720p.x264.mkv'), raw(-1004, 82, 'Twin.Town.2018.480p.x264.mkv')]
    const tiles = r.shelf(both, 'Newest', 0, r.sizeLimits().ceiling, [], []).tiles
    assert.equal(tiles.length, 1)
    const byId = new Map(both.map((v) => [v.id, v]))
    const group = tiles[0].copies.map((id) => byId.get(id)!)
    const rows = copyRows(r, group, '-1004:82', NO_COPY_CONTEXT, [])
    assert.equal(tiles[0].versions, 3)
    assert.equal(rows.length, tiles[0].versions, 'the page lists what the card counted')
    assert.ok(rows.some((x) => x.video.chatId === -1004) && rows.some((x) => x.video.chatId === -1005))
})

test('review fix: a change of device kind is a reason to arrange the cards again, the first context is not', () => {
    assert.equal(deviceMoved(null, 'computer'), false)
    assert.equal(deviceMoved('computer', 'computer'), false)
    assert.equal(deviceMoved('computer', 'phone'), true)
    assert.equal(deviceMoved('phone', 'tv'), true)
})
