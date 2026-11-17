// What the player does with the databases' skip times (skipOnline.ts): the answer is read as outside
// data, then laid with the file's chapters as core's SkipMerge does (SkipPolicyTest's cases).
import assert from 'node:assert/strict'
import { test } from 'node:test'
import type { SkipSegment } from './chapters.ts'
import { mergeSkips, readSkips } from './skipOnline.ts'

const seg = (kind: SkipSegment['kind'], start: number, end: number): SkipSegment => ({ kind, start, end })

test('an answer is read for its opening, ending and recap in seconds; the rest is dropped', () => {
    const got = readSkips({ segments: [{ kind: 'opening', start: 90, end: 180 }, { kind: 'credits', start: 1, end: 5 }, { kind: 'ending', start: 5, end: 5 }, { kind: 'recap', start: -1, end: 20 }, { kind: 'recap', start: 0, end: 30 }, { kind: 'ending', start: '1', end: 9 }, null, 4] })
    assert.deepEqual(got, [seg('opening', 90, 180), seg('recap', 0, 30)])
})

test('garbage reads as no segments and never throws', () => {
    for (const junk of [null, undefined, 0, 'x', [], {}, { segments: 'x' }, { segments: [{}] }, { segments: [{ kind: 'opening', start: NaN, end: Infinity }] }, { segments: [{ kind: 'opening', start: 0, end: 1e12 }] }, { segments: Array.from({ length: 100_000 }, () => ({ kind: 'opening', start: 1, end: 2 })) }]) {
        assert.doesNotThrow(() => readSkips(junk))
    }
    assert.ok(readSkips({ segments: Array.from({ length: 100_000 }, (_, i) => ({ kind: 'opening', start: i, end: i + 1 })) }).length <= 20)
})

test('a database stretch that fits stands beside the chapters when it does not overlap or repeat a group', () => {
    const merged = mergeSkips([seg('recap', 0, 40)], [seg('opening', 90, 180), seg('ending', 1320, 1410)], 1420)
    assert.deepEqual(merged.map((s) => s.kind), ['recap', 'opening', 'ending'])
})

test('the file\'s own chapters win: a database opening that overlaps one, or a second of its group, is not wanted', () => {
    const chapters = [seg('intro', 60, 150)]
    assert.deepEqual(mergeSkips(chapters, [seg('opening', 100, 190)], 1420), chapters, 'overlap')
    assert.deepEqual(mergeSkips(chapters, [seg('opening', 300, 390)], 1420), chapters, 'the same group, another place')
    assert.deepEqual(mergeSkips([], [seg('opening', 300, 390)], 1420), [seg('opening', 300, 390)], 'alone it stands')
})

test('a stretch that cannot be right for the file is dropped, one past the end is cut to it', () => {
    assert.deepEqual(mergeSkips([], [seg('opening', 1419.5, 1425)], 1420), [], 'starts in the last second')
    assert.deepEqual(mergeSkips([], [seg('ending', 1300, 1500)], 1420), [], 'ends more than two seconds past the end')
    assert.deepEqual(mergeSkips([], [seg('ending', 1330, 1421)], 1420), [seg('ending', 1330, 1420)], 'a second past the end is cut')
    assert.deepEqual(mergeSkips([], [seg('recap', 0, 900)], 1420), [], 'longer than three fifths of the file')
    assert.deepEqual(mergeSkips([], [seg('recap', 0, 30)], 0), [seg('recap', 0, 30)], 'no known length takes it as it is')
})

test('two stretches of one source that do not overlap both stay, in order', () => {
    const merged = mergeSkips([], [seg('ending', 1300, 1350), seg('opening', 90, 180)], 1420)
    assert.deepEqual(merged, [seg('opening', 90, 180), seg('ending', 1300, 1350)])
})
