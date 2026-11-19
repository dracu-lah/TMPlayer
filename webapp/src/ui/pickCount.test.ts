// `node --test src/ui/pickCount.test.ts` (pnpm test:ui): the Add chats picker's count budget (WB22).
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { AT_ONCE, createCountPlan, MORE_STEP, VISIT_BUDGET } from './pickCount.ts'

const ids = (n: number, from = 0) => Array.from({ length: n }, (_, i) => from + i)
const drain = (plan: ReturnType<typeof createCountPlan>) => {
    const out: number[] = []
    for (let b = plan.take(); b.length; b = plan.take()) {
        assert.ok(b.length <= AT_ONCE)
        out.push(...b)
    }
    return out
}

test('nothing is counted behind the head start until a row is stayed on or more is asked for', () => {
    const plan = createCountPlan()
    plan.setScanOrder(ids(3000))
    plan.reserve(ids(60))
    assert.deepEqual(plan.take(), [])
    assert.equal(plan.spent, 60)
})

test('rows the viewer stayed on are counted, once each, up to the budget', () => {
    const plan = createCountPlan()
    plan.reserve(ids(60))
    for (const id of ids(500, 60)) plan.offer(id)
    plan.offer(61)
    plan.offer(5)
    const got = drain(plan)
    assert.equal(got.length, VISIT_BUDGET - 60)
    assert.equal(new Set(got).size, got.length)
    assert.ok(!got.includes(5))
    assert.equal(plan.spent, VISIT_BUDGET)
    assert.equal(plan.wantsMore(3000), true)
})

test('asking for more counts the next chats in the scan order, and offers more again when that is spent', () => {
    const plan = createCountPlan()
    plan.setScanOrder(ids(3000))
    plan.reserve(ids(150))
    assert.deepEqual(drain(plan), [])
    plan.more()
    const got = drain(plan)
    assert.equal(got.length, MORE_STEP)
    assert.deepEqual(got.slice(0, 3), [150, 151, 152])
    assert.equal(plan.wantsMore(3000), true)
})

test('once the whole account is counted nothing more is offered', () => {
    const plan = createCountPlan()
    plan.setScanOrder(ids(160))
    plan.reserve(ids(150))
    assert.equal(plan.wantsMore(160), true)
    plan.more()
    assert.equal(drain(plan).length, 10)
    assert.equal(plan.wantsMore(160), false)
})

test('a refusal, or closing the picker, stops everything', () => {
    for (const stop of ['refuse', 'close'] as const) {
        const plan = createCountPlan()
        plan.setScanOrder(ids(500))
        plan.reserve(ids(60))
        plan.offer(70)
        plan[stop]()
        plan.offer(71)
        plan.more()
        assert.deepEqual(plan.take(), [])
        assert.equal(plan.wantsMore(500), false)
    }
})

test('a failed chat is asked again only when the viewer asks for more, and a lost call gives its chats back', () => {
    const plan = createCountPlan()
    plan.setScanOrder(ids(400))
    plan.reserve(ids(150))
    plan.retryLater([3])
    assert.deepEqual(plan.take(), [])
    plan.more()
    assert.equal(plan.take()[0], 3)
    plan.offer(200)
    const [one] = plan.take()
    plan.giveBack([one])
    plan.offer(one)
    assert.ok(plan.take().includes(one))
})
