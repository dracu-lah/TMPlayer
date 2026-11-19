// `node --test src/tg/counts.test.ts` (pnpm test:tg): the picker's video counts keep to their budget: three at a time,
// kept (bounded) and never kept for a failure, a flood wait waited out once, a refusal asked about no more.
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { COUNT_BATCH, COUNT_KEEP_MAX, COUNT_KEEP_MS, createVideoCounter, REFUSED, UNKNOWN } from './counts.ts'

const flood = (n: number) => Object.assign(new Error(`FLOOD_WAIT_${n}`), { text: `FLOOD_WAIT_${n}`, seconds: n })

function setup(answer: (id: number, n: number) => number | Error) {
    let time = 1_000
    const slept: number[] = []
    const asked = new Map<number, number>()
    let inFlight = 0
    let peak = 0
    const counter = createVideoCounter(
        async (id) => {
            const n = (asked.get(id) ?? 0) + 1
            asked.set(id, n)
            inFlight++
            peak = Math.max(peak, inFlight)
            try {
                await new Promise((r) => setTimeout(r, 1))
                const a = answer(id, n)
                if (a instanceof Error) throw a
                return a
            } finally {
                inFlight--
            }
        },
        {
            now: () => time,
            sleep: async (ms) => {
                slept.push(ms)
                time += ms
            },
        },
    )
    return { counter, asked, slept, peak: () => peak, advance: (ms: number) => void (time += ms) }
}

test('counts are asked three chats at a time and kept, so a second ask for the same chats asks nothing', async () => {
    const t = setup((id) => id * 2)
    const ids = Array.from({ length: 10 }, (_, i) => i + 1)
    const first = await t.counter.counts(ids)
    assert.equal(first[7], 14)
    assert.ok(t.peak() <= COUNT_BATCH)
    await t.counter.counts(ids)
    assert.ok([...t.asked.values()].every((n) => n === 1))
})

test('a count is asked again once it is old, and a failure is never kept', async () => {
    const t = setup((id, n) => (id === 2 && n === 1 ? new Error('boom') : 5))
    assert.deepEqual(await t.counter.counts([1, 2]), { 1: 5, 2: UNKNOWN })
    assert.deepEqual(await t.counter.counts([1, 2]), { 1: 5, 2: 5 })
    t.advance(COUNT_KEEP_MS + 1)
    await t.counter.counts([1])
    assert.equal(t.asked.get(1), 2)
})

test('garbage answers are not counts', async () => {
    const t = setup((id) => (id === 1 ? NaN : id === 2 ? -7 : 3))
    assert.deepEqual(await t.counter.counts([1, 2, 3]), { 1: UNKNOWN, 2: UNKNOWN, 3: 3 })
})

test('the kept counts are bounded', async () => {
    const t = setup(() => 1)
    for (let i = 0; i < COUNT_KEEP_MAX + 90; i += 90) await t.counter.counts(Array.from({ length: 90 }, (_, k) => i + k))
    assert.ok(t.counter.size() <= COUNT_KEEP_MAX)
})

test('a flood wait is waited out once by the call that got it, and the other chats go on', async () => {
    const t = setup((id, n) => (id === 2 && n === 1 ? flood(3) : 4))
    assert.deepEqual(await t.counter.counts([1, 2, 3]), { 1: 4, 2: 4, 3: 4 })
    assert.deepEqual(t.slept, [3250])
    assert.equal(t.asked.get(2), 2)
})

test('a flood wait past thirty seconds is a refusal: the rest is not asked and nothing is for a while', async () => {
    const t = setup((id) => (id === 4 ? flood(900) : 1))
    const out = await t.counter.counts([1, 2, 3, 4, 5, 6, 7])
    assert.equal(out[4], REFUSED)
    // The two asked beside it in the same three go on; the next three are not asked.
    assert.deepEqual([out[5], out[6], out[7]], [1, 1, REFUSED])
    const asked = t.asked.size
    assert.deepEqual(await t.counter.counts([8, 9]), { 8: REFUSED, 9: REFUSED })
    assert.equal(t.asked.size, asked)
    assert.deepEqual(t.slept, [])
    // After the pause it asks again.
    t.advance(5 * 60_000 + 1)
    assert.deepEqual(await t.counter.counts([8]), { 8: 1 })
})

test('a picker that closed during a flood wait does not ask again after it', async () => {
    const stop = new AbortController()
    let time = 1_000
    const asked: number[] = []
    const counter = createVideoCounter(
        async (id) => {
            asked.push(id)
            throw flood(20)
        },
        {
            now: () => time,
            sleep: async (ms) => {
                time += ms
                stop.abort()
            },
        },
    )
    assert.equal((await counter.counts([1, 2, 3, 4], stop.signal))[1], UNKNOWN)
    // The three asked together made their one ask each; none made a second, and the fourth was never asked.
    assert.deepEqual(asked, [1, 2, 3])
})

test('a closed picker asks nothing at all', async () => {
    const t = setup(() => 1)
    const stop = new AbortController()
    stop.abort()
    assert.deepEqual(await t.counter.counts([1, 2, 3], stop.signal), {})
    assert.equal(t.asked.size, 0)
})

test('an answer in flight when the account is forgotten is not kept, and answers after it are', async () => {
    const t = setup((id) => id)
    const pending = t.counter.counts([1, 2])
    t.counter.forget()
    await pending
    assert.equal(t.counter.size(), 0)
    await t.counter.counts([3])
    assert.equal(t.counter.size(), 1)
})

test('two flood waits in a row on one chat refuse it', async () => {
    const t = setup((id) => (id === 1 ? flood(2) : 1))
    assert.equal((await t.counter.counts([1]))[1], REFUSED)
    assert.deepEqual(t.slept, [2250])
})
