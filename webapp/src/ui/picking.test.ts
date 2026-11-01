// The Add chats picker's rules, the web twin of core's ChatPickingTest. `node --test src/ui/picking.test.ts`
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { adding, allTicked, changed, clearVisible, counted, FALLBACK, headStart, MAX_PRETICKED, MIN_VIDEOS, needsConfirm, pickRows, recommend, selectAll, visibleIds, type PickChat } from './picking.ts'

type Chat = PickChat & { title: string }
const chat = (id: number, title: string, more: Partial<Chat> = {}): Chat => ({ id, title, kind: 'channel', self: false, ...more })
const saved = chat(1, 'Saved Messages', { kind: 'user', self: true })
const films = chat(2, 'Reels Club')
const friends = chat(3, 'Friends', { kind: 'group' })
const anna = chat(4, 'Anna', { kind: 'user' })
const old = chat(5, 'Old Reels', { archived: true })
const all = [saved, films, friends, anna, old]
const name = (c: Chat) => c.title

test('the whole list: Saved Messages, then starred, the rest, the archive, and chats since left', () => {
    const rows = pickRows({ chats: all, name, added: new Set([2, 99]), addedNames: new Map([[99, 'Left Channel']]), starred: new Set([3, 5]), listComplete: true })
    assert.deepEqual(
        rows.map((r) => [r.id, r.band]),
        [[1, 'saved'], [3, 'starred'], [2, 'chats'], [4, 'chats'], [5, 'archived'], [99, 'gone']],
    )
    assert.equal(rows.at(-1)!.title, 'Left Channel')
    // A starred chat in the archive stays in the archive's band.
    assert.equal(rows.find((r) => r.id === 5)!.band, 'archived')
})

test('a list still loading calls nothing gone', () => {
    const rows = pickRows({ chats: all, name, added: new Set([99]), listComplete: false })
    assert.equal(rows.some((r) => r.band === 'gone'), false)
})

test('a search or a filter drops the starred band and the gone rows', () => {
    const searched = pickRows({ chats: all, name, added: new Set([99]), starred: new Set([2]), query: ' reels ', listComplete: true })
    assert.deepEqual(searched.map((r) => [r.id, r.band]), [[2, 'chats'], [5, 'archived']])
    const archived = pickRows({ chats: all, name, added: new Set(), scope: 'archived' })
    assert.deepEqual(archived.map((r) => [r.id, r.band]), [[5, 'chats']])
    const people = pickRows({ chats: all, name, added: new Set(), scope: 'people' })
    assert.deepEqual(people.map((r) => r.id), [1, 4])
    assert.deepEqual(pickRows({ chats: all, name, added: new Set(), scope: 'groups' }).map((r) => r.id), [3])
    assert.deepEqual(pickRows({ chats: all, name, added: new Set(), scope: 'channels' }).map((r) => r.id), [2, 5])
})

test('select all and clear act on what is shown, keeping ticks made elsewhere', () => {
    const shown = visibleIds(pickRows({ chats: all, name, added: new Set([99]), query: 'reels', listComplete: true }))
    assert.deepEqual(shown, [2, 5])
    const ticked = selectAll(new Set([3]), shown)
    assert.deepEqual([...ticked].sort(), [2, 3, 5])
    assert.equal(allTicked(ticked, shown), true)
    assert.deepEqual([...clearVisible(ticked, shown)], [3])
    assert.equal(allTicked(new Set(), []), false)
    assert.equal(adding(new Set([2]), shown), 1)
})

test('fifty at once goes through, fifty one asks first', () => {
    assert.equal(needsConfirm(50), false)
    assert.equal(needsConfirm(51), true)
})

test('changed compares the ticks, not their order', () => {
    assert.equal(changed(new Set([1, 2]), new Set([2, 1])), false)
    assert.equal(changed(new Set([1, 2]), new Set([1, 3])), true)
    assert.equal(changed(new Set([1]), new Set([1, 2])), true)
})

test('the Recommended band sits under Saved Messages, in its own order, only over the whole list', () => {
    const rows = pickRows({ chats: all, name, added: new Set(), starred: new Set([3]), recommended: [4, 2, 1] })
    assert.deepEqual(
        rows.map((r) => [r.id, r.band]),
        [[1, 'saved'], [4, 'recommended'], [2, 'recommended'], [3, 'starred'], [5, 'archived']],
    )
    const searched = pickRows({ chats: all, name, added: new Set(), recommended: [2], query: 'reels' })
    assert.equal(searched.some((r) => r.band === 'recommended'), false)
    const filtered = pickRows({ chats: all, name, added: new Set(), recommended: [2], scope: 'channels' })
    assert.equal(filtered.some((r) => r.band === 'recommended'), false)
})

const c = (id: number, order: number, videos: number | null, savedMessages = false) => ({ id, order, videos, savedMessages })

test('recommend: fullest first over the bar, ties to the chat listed first, uncounted and Saved Messages left out', () => {
    const s = recommend([c(1, 0, 500, true), c(2, 1, 40), c(3, 2, null), c(4, 3, 90), c(5, 4, 40), c(6, 5, 3)])
    assert.deepEqual(s.ids, [4, 2, 5])
    assert.deepEqual([...s.ticked], [4, 2, 5])
    assert.equal(s.belowBar, false)
})

test('recommend ticks at most MAX_PRETICKED, and lists every chat over the bar', () => {
    const many = Array.from({ length: MAX_PRETICKED + 10 }, (_, i) => c(i + 10, i, MIN_VIDEOS + i))
    const s = recommend(many)
    assert.equal(s.ids.length, MAX_PRETICKED + 10)
    assert.equal(s.ticked.size, MAX_PRETICKED)
    for (const id of s.ticked) assert.ok(s.ids.slice(0, MAX_PRETICKED).includes(id))
})

test('recommend: nothing over the bar offers the fullest few, unticked; nothing counted offers nothing', () => {
    const s = recommend(Array.from({ length: 9 }, (_, i) => c(i + 1, i, i + 1)))
    assert.equal(s.ids.length, FALLBACK)
    assert.deepEqual(s.ids, [9, 8, 7, 6, 5])
    assert.equal(s.ticked.size, 0)
    assert.equal(s.belowBar, true)
    assert.deepEqual(recommend([c(1, 0, 0), c(2, 1, null), c(3, 2, -1)]), { ids: [], ticked: new Set(), belowBar: false })
})

test('headStart counts Saved Messages and the head of the list, no more than asked', () => {
    const list: PickChat[] = Array.from({ length: 100 }, (_, i) => ({ id: i + 1, kind: 'channel', self: false }))
    list[80] = { id: 81, kind: 'user', self: true }
    const ids = headStart(list)
    assert.equal(ids.length, 60)
    assert.equal(ids[0], 81)
    assert.deepEqual(ids.slice(1, 4), [1, 2, 3])
    assert.deepEqual(headStart(list, 3), [81, 1, 2])
})

test('counted reads unknown and failed counts as not known', () => {
    const got = counted(all, new Map([[2, 30], [3, -1]]))
    assert.deepEqual(got.map((x) => [x.id, x.order, x.videos, x.savedMessages]), [[1, 0, null, true], [2, 1, 30, false], [3, 2, null, false], [4, 3, null, false], [5, 4, null, false]])
})
