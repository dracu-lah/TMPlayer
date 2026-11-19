import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createDwell, type DwellTimers } from './dwell.ts'

/** A clock the test turns by hand. */
function clock() {
    let now = 0
    let next = 1
    const pending = new Map<number, { at: number; fn: () => void }>()
    const timers: DwellTimers = {
        set: (fn, ms) => (pending.set(next, { at: now + ms, fn }), next++),
        clear: (h) => void pending.delete(h as number),
    }
    const tick = (ms: number) => {
        const end = now + ms
        for (;;) {
            const due = [...pending.entries()].filter(([, t]) => t.at <= end).sort((a, b) => a[1].at - b[1].at)[0]
            if (!due) break
            pending.delete(due[0])
            now = due[1].at
            due[1].fn()
        }
        now = end
    }
    return { timers, tick, pending: () => pending.size }
}

function rig() {
    const c = clock()
    const log: string[] = []
    const d = createDwell({ ms: 250, grace: 600, confirm: (k) => log.push(`ask ${k}`), release: (k) => log.push(`drop ${k}`), timers: c.timers })
    return { c, log, d }
}

test('a row asks only after it has stayed 250 ms', () => {
    const { c, log, d } = rig()
    let told = 0
    d.enter('a', () => told++)
    c.tick(249)
    assert.deepEqual(log, [])
    assert.equal(told, 0)
    c.tick(1)
    assert.deepEqual(log, ['ask a'])
    assert.equal(told, 1)
})

test('a fast fling over forty rows asks for none', () => {
    const { c, log, d } = rig()
    for (let i = 0; i < 40; i++) {
        d.enter(`r${i}`, () => {})
        c.tick(60)
        d.leave(`r${i}`)
    }
    c.tick(5000)
    assert.deepEqual(log, [])
    assert.equal(c.pending(), 0)
})

test('a row that returns within the grace keeps its place: no second ask, no drop', () => {
    const { c, log, d } = rig()
    d.enter('a', () => {})
    c.tick(250)
    d.leave('a')
    c.tick(400)
    let told = 0
    d.enter('a', () => told++)
    assert.equal(told, 1, 'told at once')
    c.tick(5000)
    assert.deepEqual(log, ['ask a'])
})

test('a row that stays away is dropped after the grace, and asks again when it comes back', () => {
    const { c, log, d } = rig()
    d.enter('a', () => {})
    c.tick(250)
    d.leave('a')
    c.tick(600)
    assert.deepEqual(log, ['ask a', 'drop a'])
    assert.equal(d.confirmed('a'), false)
    d.enter('a', () => {})
    c.tick(250)
    assert.deepEqual(log, ['ask a', 'drop a', 'ask a'])
})

test('two drawings of one row count down once, the newest is told', () => {
    const { c, log, d } = rig()
    const told: string[] = []
    d.enter('a', () => told.push('old'))
    c.tick(100)
    d.enter('a', () => told.push('new'))
    c.tick(150)
    assert.deepEqual(log, ['ask a'])
    assert.deepEqual(told, ['new'])
})

test('leaving twice, or a row never entered, does nothing', () => {
    const { c, log, d } = rig()
    d.leave('x')
    d.enter('a', () => {})
    c.tick(250)
    d.leave('a')
    d.leave('a')
    c.tick(5000)
    assert.deepEqual(log, ['ask a', 'drop a'])
})
