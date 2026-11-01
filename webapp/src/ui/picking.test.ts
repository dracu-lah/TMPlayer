// The Add chats picker's rules, the web twin of core's ChatPickingTest. `node --test src/ui/picking.test.ts`
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { adding, allTicked, changed, clearVisible, needsConfirm, pickRows, selectAll, visibleIds, type PickChat } from './picking.ts'

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
