import { test } from 'node:test'
import assert from 'node:assert/strict'
import { signature, touchSender } from './touchSend.ts'

function ends(lists: { ids: string[]; times: number[] }[]) {
    let at = 0
    const sent: { version: number; n: number }[] = []
    return {
        sent,
        move: (i: number) => (at = i),
        ends: {
            read: async () => lists[at],
            send: async (version: number, ids: string[]) => void sent.push({ version, n: ids.length }),
        },
    }
}

test('the times are sent once, however often a chat or the watch page asks, and again only when they change', async () => {
    const a = { ids: ['1:1', '1:2'], times: [10, 20] }
    const b = { ids: ['1:1', '1:2', '1:3'], times: [10, 20, 30] }
    const e = ends([a, b])
    const s = touchSender(e.ends)
    assert.equal(await s.ensure(), 1)
    assert.equal(await s.ensure(), 1)
    assert.equal(await s.ensure(), 1)
    assert.deepEqual(e.sent, [{ version: 1, n: 2 }])
    e.move(1)
    assert.equal(await s.ensure(), 2)
    assert.deepEqual(e.sent.map((x) => x.version), [1, 2])
})

test('a chat and the watch page asking at once share one send', async () => {
    const e = ends([{ ids: ['1:1'], times: [5] }])
    const s = touchSender(e.ends)
    const got = await Promise.all([s.ensure(), s.ensure(), s.ensure()])
    assert.deepEqual(got, [1, 1, 1])
    assert.equal(e.sent.length, 1)
})

test('a replaced worker holds nothing, so the next ask sends again under a newer version', async () => {
    const e = ends([{ ids: ['1:1'], times: [5] }])
    const s = touchSender(e.ends)
    await s.ensure()
    s.reset()
    assert.equal(await s.ensure(), 2)
    assert.equal(e.sent.length, 2)
})

test('a send that fails is told, and the next ask tries again', async () => {
    let fail = true
    const sent: number[] = []
    const s = touchSender({
        read: async () => ({ ids: ['1:1'], times: [5] }),
        send: async (v) => {
            if (fail) throw new Error('worker gone')
            sent.push(v)
        },
    })
    await assert.rejects(s.ensure())
    fail = false
    assert.equal(await s.ensure(), 1)
    assert.deepEqual(sent, [1])
})

test('twenty thousand entries are told apart in a blink', () => {
    const ids = Array.from({ length: 20_000 }, (_, i) => `1:${i}`)
    const times = ids.map((_, i) => i)
    const start = Date.now()
    const one = signature({ ids, times })
    assert.notEqual(one, signature({ ids, times: times.map((t, i) => (i === 5 ? t + 1 : t)) }))
    assert.ok(Date.now() - start < 200)
})
