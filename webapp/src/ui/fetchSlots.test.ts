import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createSlots } from './fetchSlots.ts'

test('only `limit` run at once and the next starts when one is released', async () => {
    const s = createSlots(2)
    const a = await s.acquire()
    const b = await s.acquire()
    let third = false
    const c = s.acquire().then((r) => ((third = true), r))
    await Promise.resolve()
    assert.equal(third, false)
    assert.deepEqual(s.state, { active: 2, waiting: 1 })
    a()
    const rc = await c
    assert.equal(third, true)
    assert.deepEqual(s.state, { active: 2, waiting: 0 })
    b()
    rc()
    assert.deepEqual(s.state, { active: 0, waiting: 0 })
})

test('a release called twice frees one slot, not two', async () => {
    const s = createSlots(1)
    const a = await s.acquire()
    a()
    a()
    assert.deepEqual(s.state, { active: 0, waiting: 0 })
    const b = await s.acquire()
    let second = false
    void s.acquire().then(() => (second = true))
    await Promise.resolve()
    assert.equal(second, false)
    b()
})

test('an ask taken back while it waits never runs and never holds a slot', async () => {
    const s = createSlots(1)
    const held = await s.acquire()
    const ctl = new AbortController()
    const waiting = s.acquire(ctl.signal)
    const next = s.acquire()
    ctl.abort()
    await assert.rejects(waiting, { name: 'AbortError' })
    assert.deepEqual(s.state, { active: 1, waiting: 1 })
    held()
    const r = await next
    assert.deepEqual(s.state, { active: 1, waiting: 0 })
    r()
    assert.deepEqual(s.state, { active: 0, waiting: 0 })
})

test('an ask already aborted is refused at once; one aborted after it holds a slot keeps it until released', async () => {
    const s = createSlots(1)
    const dead = new AbortController()
    dead.abort()
    await assert.rejects(s.acquire(dead.signal), { name: 'AbortError' })
    assert.deepEqual(s.state, { active: 0, waiting: 0 })
    const ctl = new AbortController()
    const r = await s.acquire(ctl.signal)
    ctl.abort()
    assert.deepEqual(s.state, { active: 1, waiting: 0 })
    r()
    assert.deepEqual(s.state, { active: 0, waiting: 0 })
})

test('forty asks of a fling that all leave run none', async () => {
    const s = createSlots(4)
    const held = await Promise.all([s.acquire(), s.acquire(), s.acquire(), s.acquire()])
    const ctls = Array.from({ length: 40 }, () => new AbortController())
    let ran = 0
    const all = ctls.map((c) =>
        s.acquire(c.signal).then(
            () => ran++,
            () => {},
        ),
    )
    assert.equal(s.state.waiting, 40)
    for (const c of ctls) c.abort()
    held.forEach((h) => h())
    await Promise.all(all)
    assert.equal(ran, 0)
    assert.deepEqual(s.state, { active: 0, waiting: 0 })
})
