import assert from 'node:assert/strict'
import { test } from 'node:test'
import { entryId, fromFile, lineKeyOf, mergeInput, missingLines, toFile } from './listsFile.ts'
import { tdlibMessageId } from './format.ts'
import { merge, type ListsDoc } from '../ui/lists.ts'

const item = (chatId: number, messageId: number, title = 'T') => ({ chatId, messageId, title, name: title, chat: 'C', size: 9, duration: 30, fileName: 'f.mkv' }) as unknown as ListsDoc['lines'][number]['item']

const doc = (): ListsDoc => ({
    lists: [{ id: 'abcd12', name: 'Night', at: 5, home: true }],
    lines: [
        { list: 'abcd12', item: item(-100, 7), at: 10 },
        { list: 'abcd12', item: item(-100, 8, 'Harbour Notes'), at: 11, show: 'harbour notes' },
    ],
    removed: [
        { list: 'abcd12', entry: '-100:3', at: 12 },
        { list: 'zzzz99', entry: '', at: 13 },
    ],
})

test('entry names go to the apps form and back', () => {
    assert.equal(entryId('-100:7'), `-100_${tdlibMessageId(7)}`)
    assert.equal(entryId('show:-100:harbour notes'), '-100_s:harbour notes')
    assert.equal(entryId('garbage'), null)
    assert.equal(lineKeyOf(`-100_${tdlibMessageId(7)}`), '-100:7')
    assert.equal(lineKeyOf('-100_s:harbour notes'), 'show:-100:harbour notes')
    // an id that is not a server message's (not a multiple of 2^20) has no web line
    assert.equal(lineKeyOf('-100_12345'), null)
})

test('lists, lines and removals are written with the apps fields and TDLib ids', () => {
    const f = toFile(doc()) as Record<string, any>
    assert.deepEqual(f.lists, [{ id: 'abcd12', name: 'Night', at: 5, home: true }])
    assert.equal(f.entries.length, 2)
    const video = f.entries.find((e: any) => !e.series)
    assert.equal(video.message, tdlibMessageId(7))
    assert.equal(video.list, 'abcd12')
    assert.equal(video.addedAt, 10)
    assert.equal(video.durationSec, 30)
    const show = f.entries.find((e: any) => e.series)
    assert.equal(show.series, 'harbour notes')
    assert.equal(show.title, 'Harbour Notes')
    assert.deepEqual(f.removed, [{ list: 'abcd12', at: 12, entry: `-100_${tdlibMessageId(3)}` }, { list: 'zzzz99', at: 13 }])
})

test('a backup writes no removals', () => {
    assert.equal((toFile(doc(), false) as Record<string, unknown>).removed, undefined)
})

test('what toFile writes, fromFile reads', () => {
    const back = fromFile(JSON.parse(JSON.stringify(toFile(doc()))))
    assert.deepEqual(back.lists, doc().lists)
    assert.deepEqual(back.entries.map((e) => [e.list, e.chat, e.msg, e.at, e.show ?? '']), [['abcd12', -100, 7, 10, ''], ['abcd12', -100, 8, 11, 'harbour notes']])
    assert.deepEqual(back.removed, [{ list: 'abcd12', entry: '-100:3', at: 12 }, { list: 'zzzz99', entry: '', at: 13 }])
})

test('garbage and truncated input is skipped, never thrown', () => {
    for (const bad of [null, undefined, 5, 'x', [], { lists: 5, entries: 'a', removed: {} }, { lists: [null, 1, { id: 'X' }, { id: 'abcd12' }], entries: [{ list: 'abcd12' }, { list: 'abcd12', chat: 0, message: 1, addedAt: 1 }], removed: [{ list: 'abcd12' }, { list: 'abcd12', at: 1, entry: 'x'.repeat(400) }] }]) {
        const r = fromFile(bad)
        assert.deepEqual([r.lists.length, r.entries.length, r.removed.length], [0, 0, 0])
    }
})

test('huge input is capped', () => {
    const entries = Array.from({ length: 100_000 }, (_, i) => ({ list: 'abcd12', chat: 1, message: tdlibMessageId(i + 1), addedAt: i }))
    assert.ok(fromFile({ entries }).entries.length <= 40_000)
})

test('the lines this device lacks are the ones to read, newest first, minus what was removed after', () => {
    const file = fromFile({
        lists: [],
        entries: [
            { list: 'abcd12', chat: -100, message: tdlibMessageId(7), addedAt: 10 },
            { list: 'abcd12', chat: -100, message: tdlibMessageId(20), addedAt: 30 },
            { list: 'abcd12', chat: -100, message: tdlibMessageId(21), addedAt: 20 },
            { list: 'abcd12', chat: -100, message: tdlibMessageId(22), addedAt: 5 },
        ],
        removed: [{ list: 'abcd12', at: 8, entry: `-100_${tdlibMessageId(22)}` }, { list: 'abcd12', at: 40, entry: `-100_${tdlibMessageId(21)}` }],
    })
    const miss = missingLines(file, doc())
    assert.deepEqual(miss.map((e) => e.msg), [20])
})

test('a restore never takes a list or line away, whatever removals the file carries (SEC2-02)', () => {
    const now = 1_000_000
    const file = fromFile({ lists: [], entries: [], removed: [{ list: 'abcd12', at: now }, { list: 'abcd12', at: now, entry: `-100_${tdlibMessageId(7)}` }] })
    assert.equal(file.removed.length, 2)
    const restored = merge(doc(), mergeInput(file, [], false), now)
    assert.deepEqual(restored.lists.map((l) => l.id), ['abcd12'])
    assert.equal(restored.lines.length, 2)
    // A sync between this viewer's own devices still applies them.
    const synced = merge(doc(), mergeInput(file, [], true), now)
    assert.equal(synced.lists.some((l) => l.id === 'abcd12'), false)
})
