import { test } from 'node:test'
import assert from 'node:assert/strict'
import type { VideoItem } from '../tg/types'
import { add, cleanName, create, deleteList, emptyLists, forgetVideos, homeKey, isId, lineKey, MAX_LISTS, MAX_REMOVALS, merge, NAME_MAX, prune, readLists, removeLines, rename, setHome, visible, type ListsDoc } from './lists.ts'

const NOW = 1_800_000_000_000
const DAY = 24 * 60 * 60 * 1000
const item = (chatId: number, messageId: number) => ({ chatId, messageId, title: `Clip ${messageId}`, name: `Clip ${messageId}` }) as VideoItem

function made(...names: string[]): [ListsDoc, string[]] {
    let doc = emptyLists()
    const ids: string[] = []
    names.forEach((n, i) => {
        const [next, info] = create(doc, n, NOW + i)!
        doc = next
        ids.push(info.id)
    })
    return [doc, ids]
}

test('a name is trimmed and cut to sixty, an empty one refused, ten million characters cost nothing', () => {
    assert.equal(cleanName('  For \t the\u0000 weekend \n'), 'For the weekend')
    assert.equal(cleanName('   '), null)
    assert.equal(cleanName(42), null)
    assert.equal(cleanName('a'.repeat(500))!.length, NAME_MAX)
    assert.equal(cleanName('a'.repeat(59) + '😀'), 'a'.repeat(59))
    const start = Date.now()
    assert.equal(cleanName('x'.repeat(10_000_000))!.length, NAME_MAX)
    assert.ok(Date.now() - start < 500)
})

test('two lists of one name are two lists, each a Home row key, and a hundred is the most', () => {
    const [doc, [a, b]] = made('Kids', 'Kids')
    assert.notEqual(a, b)
    assert.ok(isId(a) && isId(b))
    assert.match(homeKey(a), /^list-[a-z0-9]+$/)
    let many = doc
    for (let i = 0; many.lists.length < MAX_LISTS; i++) many = create(many, `L${i}`, NOW + 10 + i)![0]
    assert.equal(create(many, 'one more', NOW + 999), null)
})

test('the same video twice is one line that keeps its first place', () => {
    const [doc, [id]] = made('Mine')
    let [d, r] = add(doc, id, { item: item(1, 1) }, NOW + 1)
    assert.equal(r, 'added')
    ;[d] = add(d, id, { item: item(1, 2) }, NOW + 2)
    ;[d, r] = add(d, id, { item: item(1, 1) }, NOW + 3)
    assert.equal(r, 'already')
    assert.deepEqual(visible(d)[0].lines.map((l) => l.item.messageId), [1, 2])
    assert.equal(add(d, 'nolist00', { item: item(1, 1) }, NOW)[1], 'no-list')
})

test('the web app and the phone writing at once both land, the newer name wins', () => {
    const [base, [id]] = made('Shared')
    const phone = add(rename(base, id, 'Phone', NOW + 10), id, { item: item(1, 1) }, NOW + 10)[0]
    const web = add(rename(base, id, 'Web', NOW + 20), id, { item: item(2, 2) }, NOW + 20)[0]
    const one = merge(phone, web, NOW)
    assert.deepEqual(one, merge(web, phone, NOW))
    assert.equal(one.lists[0].name, 'Web')
    assert.equal(one.lines.length, 2)
    assert.deepEqual(merge(one, one, NOW), one)
})

test('a removal is remembered against an older copy, and a later add wins over it', () => {
    const [base, [id]] = made('L')
    const both = add(base, id, { item: item(1, 1) }, NOW + 1)[0]
    const gone = removeLines(both, id, new Set([lineKey({ item: item(1, 1) })]), NOW + 2)
    assert.equal(merge(gone, both, NOW).lines.length, 0)
    assert.equal(merge(both, gone, NOW).lines.length, 0)
    const again = add(gone, id, { item: item(1, 1) }, NOW + 3)[0]
    assert.equal(merge(again, both, NOW).lines.length, 1)
})

test('a deleted list goes, unless a line added after brings it back with that line alone', () => {
    const [base, [id]] = made('L')
    const withLine = add(base, id, { item: item(1, 1) }, NOW + 1)[0]
    const deleted = deleteList(withLine, id, NOW + 2)
    assert.equal(merge(withLine, deleted, NOW).lists.length, 0)
    const offline = add(withLine, id, { item: item(2, 2) }, NOW + 3)[0]
    const back = merge(deleted, offline, NOW)
    assert.equal(back.lists.length, 1)
    assert.deepEqual(back.lines.map((l) => l.item.messageId), [2])
})

test('removals expire after ninety days and are bounded', () => {
    const [base, [id]] = made('L')
    const old: ListsDoc = { ...base, removed: [{ list: id, entry: '1:1', at: NOW - 91 * DAY }, { list: id, entry: '1:2', at: NOW - DAY }] }
    assert.deepEqual(prune(old, NOW).removed.map((r) => r.entry), ['1:2'])
    const many: ListsDoc = { ...base, removed: Array.from({ length: 5_000 }, (_, i) => ({ list: id, entry: `1:${i}`, at: NOW - i })) }
    assert.equal(merge(many, emptyLists(), NOW).removed.length, MAX_REMOVALS)
})

test('a show keeps its line when one episode goes, a video leaves every list', () => {
    const [base, [a, b]] = made('A', 'B')
    let d = add(base, a, { item: item(1, 5) }, NOW)[0]
    d = add(d, b, { item: item(1, 5) }, NOW)[0]
    d = add(d, b, { item: item(1, 5), show: 'the show' }, NOW)[0]
    const [after, count] = forgetVideos(d, new Set(['1:5']), NOW + 1)
    assert.equal(count, 2)
    assert.deepEqual(after.lines.map((l) => l.show), ['the show'])
})

test('the Home switch and garbage from the store', () => {
    const [base, [id]] = made('L')
    assert.equal(setHome(base, id, false, NOW + 1).lists[0].home, false)
    for (const junk of [null, 5, 'x', [], { lists: 'x' }, { lists: [{ id: 'BAD', name: 'n', at: 1 }, { id: 'good1234', name: 7, at: 1 }, null] }, { lines: [{ list: 'good1234' }, { list: 'good1234', item: {}, at: 'x' }] }]) {
        const read = readLists(junk, NOW)
        assert.deepEqual(read.lists, [])
        assert.deepEqual(read.lines, [])
    }
    const huge = { lists: [{ id: 'good1234', name: 'n', at: 1 }], lines: Array.from({ length: 50_000 }, (_, i) => ({ list: 'good1234', item: item(1, i + 1), at: i })) }
    assert.equal(readLists(huge, NOW).lines.length, 5_000)
})
