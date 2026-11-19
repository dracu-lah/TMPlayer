// The Last played times the worker holds (WB13): the grid and the watch page's Next name them by
// version, so both arrange in one order and neither posts the list.
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import * as core from '../rules/core/tmplayer-rules.mjs'
import type { Rules, Video } from '../rules'
import { forgetTouches, holdTouches, touchesFor, TOUCHES_MAX } from './touchCache.ts'

core.provideCatalog('en', readFileSync(new URL('../../../core/src/commonMain/resources/i18n/en.json', import.meta.url), 'utf8'))
const r = core as unknown as Rules

test('a view names a version and gets the lists held under it, no other version gets any', () => {
    forgetTouches()
    assert.deepEqual(touchesFor(1), { ids: [], times: [] })
    assert.ok(holdTouches(3, ['1:1', '1:2'], [10, 20]))
    assert.deepEqual(touchesFor(3).ids, ['1:1', '1:2'])
    assert.deepEqual(touchesFor(2), { ids: [], times: [] }, 'an older version is not this one')
    assert.deepEqual(touchesFor(undefined), { ids: [], times: [] })
    forgetTouches()
    assert.deepEqual(touchesFor(3), { ids: [], times: [] }, 'a replaced worker holds nothing')
})

test('lists that are not well formed are refused and the held ones stay', () => {
    forgetTouches()
    holdTouches(1, ['1:1'], [5])
    for (const bad of [
        [0, ['a'], [1]],
        [-4, ['a'], [1]],
        [1.5, ['a'], [1]],
        ['2', ['a'], [1]],
        [2, 'a', [1]],
        [2, ['a', 'b'], [1]],
        [2, null, null],
    ] as [unknown, unknown, unknown][]) assert.equal(holdTouches(bad[0], bad[1], bad[2]), false)
    assert.deepEqual(touchesFor(1).ids, ['1:1'])
    assert.ok(holdTouches(2, ['1:1', 7, '1:3'] as unknown[], [1, 2, Number.NaN]))
    assert.deepEqual(touchesFor(2).ids, ['1:1'], 'an entry of the wrong type or a time that is not a number is skipped')
    assert.ok(holdTouches(3, Array.from({ length: TOUCHES_MAX + 5000 }, (_, i) => `1:${i}`), Array.from({ length: TOUCHES_MAX + 5000 }, (_, i) => i)))
    assert.equal(touchesFor(3).ids.length, TOUCHES_MAX)
    forgetTouches()
})

test('the held times put the video watched last first: the order the grid and Next both run', () => {
    forgetTouches()
    const mk = (n: number) =>
        r.video({ chatId: -1, messageId: n, kind: 'video', fileName: `Clip ${n}.mkv`, mimeType: 'video/x-matroska', caption: null, size: 9e8, duration: 600, width: 0, height: 0, date: 1_800_000_000 - n, topicId: 0, albumId: null, canBeSaved: true, selfDestructs: false, withheld: false })!
    const videos: Video[] = [1, 2, 3].map(mk)
    const order = (version: number | undefined) => {
        const t = touchesFor(version)
        return r.shelfView(videos, 'LastPlayed', 'All', null, 0, r.sizeLimits().ceiling, [], [], t.ids, t.times, 1_800_000_000).tiles.map((x) => x.video?.id)
    }
    holdTouches(1, ['-1:3', '-1:2'], [9_000, 1_000])
    assert.deepEqual(order(1).slice(0, 2), ['-1:3', '-1:2'])
    assert.deepEqual(order(99), ['-1:1', '-1:2', '-1:3'], 'with no times held the order is the posted one, which is what a view without the version gets')
    forgetTouches()
})
