// History's select mode, the web twin of the apps' HistoryEdits selection. `node --test src/ui/selection.test.ts`
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { prune, range, toggleAll } from './selection.ts'

test('a ticked item that vanished is dropped from the selection', () => {
    assert.deepEqual([...prune(new Set(['1:1', '1:2', '2:9']), ['1:2', '3:3'])], ['1:2'])
    assert.deepEqual([...prune(new Set(), ['1:1'])], [])
})

test('shift click ticks the run between the anchor and the click, either way round', () => {
    const order = ['a', 'b', 'c', 'd', 'e']
    assert.deepEqual(range(order, 'b', 'd'), ['b', 'c', 'd'])
    assert.deepEqual(range(order, 'd', 'b'), ['b', 'c', 'd'])
    assert.deepEqual(range(order, null, 'c'), ['c'])
    assert.deepEqual(range(order, 'gone', 'c'), ['c'])
    assert.deepEqual(range(order, 'a', 'zzz'), [])
})

test('select all ticks everything, and again clears; nothing visible selects nothing', () => {
    assert.deepEqual([...toggleAll(new Set(['a']), ['a', 'b'])], ['a', 'b'])
    assert.deepEqual([...toggleAll(new Set(['a', 'b']), ['a', 'b'])], [])
    assert.deepEqual([...toggleAll(new Set(), [])], [])
})
