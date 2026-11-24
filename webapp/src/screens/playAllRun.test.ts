import assert from 'node:assert/strict'
import { test } from 'node:test'
import { activeRun, around, belongs, endRun, keyOf, nearest, QUEUE_MAX, queueOf, readRun, startChatRun, startListRun } from './playAllRun.ts'

const v = (chatId: number, messageId: number) => ({ chatId, messageId })

test('a list queue keeps the page order, drops repeats and stops at the cap', () => {
    assert.deepEqual(queueOf([v(1, 5), v(2, 6), v(1, 5), v(1, 7)]), ['1:5', '2:6', '1:7'])
    assert.deepEqual(queueOf([]), [])
    const big = Array.from({ length: 20_000 }, (_, i) => v(1, i + 1))
    assert.equal(queueOf(big).length, QUEUE_MAX)
    assert.equal(queueOf(big)[0], '1:1')
})

test('the videos after and before the one playing, nothing for one that is not queued', () => {
    const keys = ['1:1', '1:2', '2:3', '2:4']
    assert.deepEqual(around(keys, '1:2'), { previous: ['1:1'], next: ['2:3', '2:4'] })
    assert.deepEqual(around(keys, '1:1'), { previous: [], next: ['1:2', '2:3', '2:4'] })
    assert.deepEqual(around(keys, '2:4'), { previous: ['2:3', '1:2', '1:1'], next: [] })
    assert.deepEqual(around(keys, '9:9'), { previous: [], next: [] })
})

test('a chat run covers its chat, a list run its queue, and a video outside either is not part of the run', () => {
    assert.equal(belongs({ kind: 'chat', chatId: 7 }, v(7, 100)), true)
    assert.equal(belongs({ kind: 'chat', chatId: 7 }, v(8, 100)), false)
    assert.equal(belongs({ kind: 'list', keys: ['1:2', '3:4'] }, v(3, 4)), true)
    assert.equal(belongs({ kind: 'list', keys: ['1:2', '3:4'] }, v(3, 5)), false)
    assert.equal(keyOf(v(-1001, 5)), '-1001:5')
})

test('a stored run is read as what it is: garbage, stale, wrong types and huge input are nothing', () => {
    const now = 1_000_000_000_000
    const ok = JSON.stringify({ kind: 'chat', chatId: -100123, at: now - 1000 })
    assert.deepEqual(readRun(ok, now), { kind: 'chat', chatId: -100123 })
    assert.deepEqual(readRun(JSON.stringify({ kind: 'list', keys: ['1:2', 5, 'x', '3:4'], at: now }), now), { kind: 'list', keys: ['1:2', '3:4'] })
    assert.equal(readRun(JSON.stringify({ kind: 'list', keys: [], at: now }), now), null)
    assert.equal(readRun(JSON.stringify({ kind: 'chat', chatId: -1, at: now - 13 * 3600_000 }), now), null)
    assert.equal(readRun(JSON.stringify({ kind: 'chat', chatId: -1, at: now + 3600_000 }), now), null)
    for (const bad of [null, undefined, 5, '', '{', '[]', 'null', '"x"', JSON.stringify({ kind: 'chat', chatId: 'a', at: now }), JSON.stringify({ kind: 'other', at: now }), 'x'.repeat(500_000)]) assert.equal(readRun(bad, now), null)
    const many = JSON.stringify({ kind: 'list', keys: Array.from({ length: 9000 }, (_, i) => `1:${i}`), at: now })
    assert.equal((readRun(many, now) as { keys: string[] }).keys.length, QUEUE_MAX)
})

test('a press starts a run, a second press replaces it, ending it leaves none, and a list with nothing queued starts none', () => {
    startChatRun(7)
    assert.deepEqual(activeRun(), { kind: 'chat', chatId: 7 })
    startListRun([v(1, 1), v(2, 2)])
    assert.deepEqual(activeRun(), { kind: 'list', keys: ['1:1', '2:2'] })
    endRun()
    assert.equal(activeRun(), null)
    startListRun([])
    assert.equal(activeRun(), null)
})

test('the next video steps over ones gone from their chat, a few at most, and stops at a failed ask', async () => {
    const item = (m: number) => ({ chatId: 1, messageId: m }) as never
    const alive = new Set(['1:3', '1:9'])
    const asked: string[] = []
    const get = async (k: string) => (asked.push(k), alive.has(k) ? item(Number(k.split(':')[1])) : null)
    assert.deepEqual(await nearest(['1:1', '1:2', '1:3', '1:4'], get), item(3))
    assert.deepEqual(asked, ['1:1', '1:2', '1:3'])
    assert.equal(await nearest(['1:1', '1:2', '1:5', '1:6', '1:9'], get), null, 'four tries, then the player goes on without a next')
    assert.equal(await nearest(['1:1'], async () => Promise.reject(new Error('offline'))), null)
    assert.equal(await nearest([], get), null)
})
