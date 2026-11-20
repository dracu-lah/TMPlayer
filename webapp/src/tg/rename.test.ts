// "Rename" in the Telegram worker over the real rules (core's WebRules, built by `pnpm rules`): the
// context's renames cleaned on the way in, written as the corrections the grouping reads, and the
// tile titled by them.
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import * as core from '../rules/core/tmplayer-rules.mjs'
import type { Rules, Video } from '../rules'
import { copyRows, groupRefs } from './copyPick.ts'
import { cleanContext, correctionLines, NO_COPY_CONTEXT, type CopyContext } from './copyFit.ts'

core.provideCatalog('en', readFileSync(new URL('../../../core/src/commonMain/resources/i18n/en.json', import.meta.url), 'utf8'))
const r = core as unknown as Rules

const CHAT = -1005
let date = 1_700_000_000
function video(messageId: number, fileName: string): Video {
    const v = r.video({
        chatId: CHAT, messageId, kind: 'document', fileName, mimeType: 'video/x-matroska', caption: null,
        size: 2_000_000_000 + messageId, duration: 5_400, width: 0, height: 0,
        date: date++, topicId: 0, albumId: null, canBeSaved: true, selfDestructs: false, withheld: false,
    })
    assert.ok(v, fileName)
    return v
}
const ctx = (o: Partial<CopyContext>): CopyContext => ({ ...NO_COPY_CONTEXT, ...o })

const film = () => [
    video(25, 'Big.Buck.Bunny.2008.2160p.HDR.HEVC.mkv'),
    video(26, 'Big.Buck.Bunny.2008.1080p.BluRay.x264.mkv'),
    video(27, 'Big.Buck.Bunny.2008.720p.WEB-DL.x264.mkv'),
]

test('a rename reaches the rules: the tile reads the viewer\'s title, every copy renamed stays one tile', () => {
    r.setCopyDevice('computer')
    const copies = film()
    const tiles = (c: CopyContext) => {
        const lines = correctionLines(c, r.correctionSet)
        r.setCorrections(lines.ids, lines.texts)
        return r.shelf(copies, 'Newest', 0, r.sizeLimits().ceiling, [], []).tiles
    }
    try {
        const all = ['-1005:25', '-1005:26', '-1005:27'].map((id) => ({ id, title: 'My Bunny (2009)' }))
        const renamed = tiles(ctx({ titles: all }))
        assert.deepEqual(renamed.map((t) => t.versions), [3])
        assert.equal(r.tileTitle(renamed[0].video!), 'My Bunny (2009)')
        // Only the copy that shows is renamed: it no longer reads as its siblings' title.
        const one = tiles(ctx({ titles: [{ id: '-1005:25', title: 'My Bunny (2009)' }] }))
        assert.ok(one.length > 1 || r.tileTitle(one[0].video!) !== 'My Bunny (2009)', 'a lone renamed copy splits from its group or is not the title')
        // Taken off again: the file name reads and the group is whole.
        const back = tiles(ctx({}))
        assert.deepEqual(back.map((t) => t.versions), [3])
        assert.notEqual(r.tileTitle(back[0].video!), 'My Bunny (2009)')
    } finally {
        r.setCorrections([], [])
    }
})

test('a rename and "Not the same video" on one file are two lines of one correction, the title cut by the rules', () => {
    const lines = correctionLines(cleanContext({ apart: ['-1005:27'], titles: [{ id: '-1005:27', title: 'T'.repeat(300) }, { id: '-1005:26', title: 'Other' }] }), r.correctionSet)
    assert.deepEqual(lines.ids, ['-1005:27', '-1005:26'])
    assert.deepEqual(lines.texts, [`separate\ntitle=${'T'.repeat(120)}`, 'title=Other'])
    assert.deepEqual(correctionLines(ctx({}), r.correctionSet), { ids: [], texts: [] })
    assert.deepEqual(correctionLines(ctx({ apart: ['-1005:27'] }), r.correctionSet), { ids: ['-1005:27'], texts: ['separate'] })
})

test('the context\'s renames are cleaned on the way into the worker: bad ids, empty or huge titles, repeats and non lists are dropped', () => {
    const c = cleanContext({
        titles: [
            { id: '-1005:25', title: '  A\nB  ' },
            { id: '-1005:25', title: 'Newest' },
            { id: 'nope', title: 'X' },
            { id: '-1005:26', title: '   ' },
            { id: '-1005:27', title: 'y'.repeat(400) },
            { id: 5, title: 'X' },
            null,
            'str',
        ],
    })
    assert.deepEqual(c.titles, [{ id: '-1005:25', title: 'Newest' }, { id: '-1005:27', title: 'y'.repeat(120) }])
    assert.deepEqual(cleanContext({ titles: 'x' }).titles, [])
    assert.deepEqual(cleanContext(null).titles, [])
    assert.equal(cleanContext({ titles: Array.from({ length: 20_000 }, (_, i) => ({ id: `-1:${i}`, title: 't' })) }).titles.length, 5_000)
})

test('a group holding the same file posted twice: the list shows it once, the rename is written to every post', () => {
    r.setCopyDevice('computer')
    const post = (messageId: number, name: string, size: number) => {
        const v = r.video({
            chatId: CHAT, messageId, kind: 'document', fileName: name, mimeType: 'video/x-matroska', caption: null,
            size, duration: 5_400, width: 0, height: 0, date: date++, topicId: 0, albumId: null, canBeSaved: true, selfDestructs: false, withheld: false,
        })
        assert.ok(v)
        return v
    }
    const group = [post(40, 'Big.Buck.Bunny.2008.1080p.BluRay.x264.mkv', 3_000_000_000), post(41, 'Big.Buck.Bunny.2008.1080p.BluRay.x264.mkv', 3_000_000_000), post(42, 'Big.Buck.Bunny.2008.720p.WEB-DL.x264.mkv', 1_000_000_000)]
    const shown = copyRows(r, group, '-1005:40', NO_COPY_CONTEXT, []).map((row) => row.video.messageId)
    assert.equal(shown.length, 2, 'the duplicate post is one row')
    const refs = groupRefs(group)
    assert.deepEqual(refs.map((x) => x.messageId), [40, 41, 42])
    // Renamed through the group, all three are one tile with the new title; through the shown rows only, the hidden post would stand apart.
    const named = (ids: number[]) => {
        const lines = correctionLines(ctx({ titles: ids.map((m) => ({ id: `${CHAT}:${m}`, title: 'My Bunny (2009)' })) }), r.correctionSet)
        r.setCorrections(lines.ids, lines.texts)
        return r.shelf(group, 'Newest', 0, r.sizeLimits().ceiling, [], []).tiles.map((t) => t.versions)
    }
    try {
        assert.deepEqual(named(refs.map((x) => x.messageId)), [2])
        assert.notDeepEqual(named(shown), [2], 'a hidden duplicate left with the old name no longer groups with the rest')
    } finally {
        r.setCorrections([], [])
    }
})

test('a title of 120 whole characters made of emoji is not cut through a pair on its way to the rules', () => {
    const title = '😀'.repeat(120)
    const lines = correctionLines(ctx({ titles: [{ id: '-1005:25', title }] }), r.correctionSet)
    assert.equal(lines.texts[0], `title=${title}`)
    assert.equal(correctionLines(ctx({ titles: [{ id: '-1005:25', title: 'x'.repeat(120) }] }), r.correctionSet).texts[0], `title=${'x'.repeat(120)}`)
})
