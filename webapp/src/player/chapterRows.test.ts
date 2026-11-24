import assert from 'node:assert/strict'
import { test } from 'node:test'
import { chapterInside, chapterListable, chapterRows } from './chapters.ts'

// The same cases as core's ChapterRowsTest: the page and the apps list the same lines.
const untitled = (n: number) => `Chapter ${n}`
const three = [
    { start: 0, title: 'Opening' },
    { start: 95, title: 'Part A' },
    { start: 1310, title: 'Ending' },
]

test('the page is offered from two chapters', () => {
    assert.equal(chapterListable([]), false)
    assert.equal(chapterListable([{ start: 0, title: 'Only' }]), false)
    assert.equal(chapterListable(three.slice(0, 2)), true)
})

test('each row has its name and start, and the one playing is marked', () => {
    const rows = chapterRows(three, 100, untitled)
    assert.deepEqual(rows.map((r) => r.title), ['Opening', 'Part A', 'Ending'])
    assert.deepEqual(rows.map((r) => r.start), [0, 95, 1310])
    assert.deepEqual(rows.map((r) => r.current), [false, true, false])
})

test('a chapter with no name is numbered by its place', () => {
    const rows = chapterRows([{ start: 0, title: null }, { start: 60, title: '  ' }, { start: 120, title: 'Named' }], 0, untitled)
    assert.deepEqual(rows.map((r) => r.title), ['Chapter 1', 'Chapter 2', 'Named'])
})

test('unsorted, repeated, negative and broken starts come out sorted, once each', () => {
    const rows = chapterRows(
        [{ start: 120, title: null }, { start: 0, title: 'A' }, { start: 120, title: 'Dup' }, { start: -40, title: 'Below' }, { start: Number.NaN, title: 'Broken' }],
        0,
        untitled,
    )
    assert.deepEqual(rows.map((r) => r.start), [0, 120])
    assert.equal(rows[1].title, 'Chapter 2')
})

test('before the first chapter none is marked, and with none there are no rows', () => {
    const rows = chapterRows([{ start: 30, title: 'Late start' }, { start: 90, title: 'Next' }], 1, untitled)
    assert.ok(rows.every((r) => !r.current))
    assert.deepEqual(chapterRows([], 5, untitled), [])
})

test('three hundred chapters list at once and the last is found', () => {
    const many = Array.from({ length: 300 }, (_, i) => ({ start: i * 10, title: null }))
    const rows = chapterRows(many, 2995, untitled)
    assert.equal(rows.length, 300)
    assert.ok(rows[299].current)
    assert.equal(rows[299].title, 'Chapter 300')
})

// PB-02: a malformed file's marks at or past the end are no place to seek to.
test('with the length known, marks at or past the end are not listed, offered or stepped to', () => {
    const past = [
        { start: 0, title: 'Opening' },
        { start: 600, title: 'Part A' },
        { start: 1199.5, title: 'At the end' },
        { start: 5000, title: 'Way past' },
    ]
    assert.deepEqual(chapterRows(past, 700, untitled, 1200).map((r) => r.title), ['Opening', 'Part A'])
    assert.equal(chapterListable([{ start: 0, title: 'A' }, { start: 9000, title: 'B' }], 1200), false)
    assert.deepEqual(chapterInside(past, 1200).map((c) => c.start), [0, 600])
    assert.equal(chapterRows(past, 0, untitled).length, 4)
})
