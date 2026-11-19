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

test('a failed chat is asked once more only when the viewer asks for more', () => {
    const plan = createCountPlan()
    plan.setScanOrder(ids(400))
    plan.reserve(ids(150))
    plan.retryLater([3])
    assert.deepEqual(plan.take(), [])
    plan.more()
    assert.equal(plan.take()[0], 3)
})

test('chats that fail every time are asked twice at most, and taking until nothing is left ends within the budget', () => {
    const plan = createCountPlan()
    const bad = new Set([5, 6, 7, 160, 161])
    plan.setScanOrder(ids(400))
    plan.reserve(ids(60))
    for (const id of ids(100, 60)) plan.offer(id)
    plan.more()
    const asks = new Map<number, number>()
    let rounds = 0
    // The screen's pump: ask what is handed out, report the failures, until nothing is handed out.
    for (let ask = plan.take(); ask.length; ask = plan.take()) {
        assert.ok(++rounds < 500, 'the pump never ends')
        for (const id of ask) asks.set(id, (asks.get(id) ?? 0) + 1)
        plan.retryLater(ask.filter((id) => bad.has(id)))
    }
    assert.ok(rounds < 100)
    for (const id of bad) assert.ok((asks.get(id) ?? 0) <= 2, `chat ${id} asked ${asks.get(id)} times`)
    assert.ok([...asks.values()].reduce((a, b) => a + b, 0) <= VISIT_BUDGET + MORE_STEP + bad.size)
    // A row stayed on again does not ask a failed chat once more.
    plan.offer(160)
    assert.deepEqual(plan.take(), [])
})

test('three chats that always fail do not hold the scan back', () => {
    const plan = createCountPlan()
    const bad = new Set([200, 201, 202])
    plan.setScanOrder(ids(1000))
    plan.reserve(ids(150))
    plan.more()
    const done = new Set<number>()
    let rounds = 0
    for (let ask = plan.take(); ask.length; ask = plan.take()) {
        assert.ok(++rounds < 500, 'the pump never ends')
        plan.retryLater(ask.filter((id) => bad.has(id)))
        for (const id of ask) if (!bad.has(id)) done.add(id)
    }
    // The whole step was spent on chats, three of which only failed.
    assert.ok(done.size >= MORE_STEP - bad.size * 2)
    assert.equal(plan.take().length, 0)
})

test('offers made while the budget was spent are dropped, and offers waiting when more is asked for do not jump the scan order', () => {
    const plan = createCountPlan()
    plan.setScanOrder(ids(3000))
    plan.reserve(ids(150))
    plan.offer(2000)
    assert.deepEqual(plan.take(), [])
    plan.more()
    assert.deepEqual(plan.take(), [150, 151, 152])
    const roomy = createCountPlan()
    roomy.setScanOrder(ids(3000))
    roomy.reserve(ids(60))
    roomy.offer(2500)
    roomy.more()
    assert.ok(!roomy.take().includes(2500))
})
