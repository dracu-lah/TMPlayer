// `node --test src/ui/reach.test.ts` (pnpm test:ui): the banner's wait and its hysteresis, on a clock the test drives.
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { CLEAR_MS, createReachWatch, singleFlight, UNREACHABLE_MS, type Timers } from './reach.ts'

function rig() {
    let now = 0
    let next = 1
    const timers = new Map<number, { at: number; fn: () => void }>()
    const clock: Timers = {
        set: (fn, ms) => (timers.set(next, { at: now + ms, fn }), next++),
        clear: (h) => void timers.delete(h),
    }
    const log: boolean[] = []
    const watch = createReachWatch((on) => log.push(on), clock)
    const advance = (ms: number) => {
        const end = now + ms
        for (;;) {
            const due = [...timers.entries()].filter(([, t]) => t.at <= end).sort((a, b) => a[1].at - b[1].at)[0]
            if (!due) break
            now = due[1].at
            timers.delete(due[0])
            due[1].fn()
        }
        now = end
    }
    return { watch, advance, log, pending: () => timers.size }
}

test('the banner waits 20 s of a held line, and not a second less', () => {
    const r = rig()
    r.watch.update(true)
    r.advance(UNREACHABLE_MS - 1)
    assert.deepEqual(r.log, [])
    r.advance(1)
    assert.deepEqual(r.log, [true])
})

test('a line that comes back before 20 s never shows it', () => {
    const r = rig()
    r.watch.update(true)
    r.advance(15_000)
    r.watch.update(false)
    r.advance(60_000)
    assert.deepEqual(r.log, [])
    assert.equal(r.pending(), 0)
})

test('once shown it goes only after the line held for 3 s', () => {
    const r = rig()
    r.watch.update(true)
    r.advance(UNREACHABLE_MS)
    r.watch.update(false)
    r.advance(CLEAR_MS - 1)
    assert.deepEqual(r.log, [true])
    r.advance(1)
    assert.deepEqual(r.log, [true, false])
})

test('a blink of line while shown leaves it on, with no second 20 s wait', () => {
    const r = rig()
    r.watch.update(true)
    r.advance(UNREACHABLE_MS)
    r.watch.update(false)
    r.advance(1_000)
    r.watch.update(true)
    r.advance(120_000)
    assert.deepEqual(r.log, [true])
})

test('ten flaps before the banner never show it, and the count starts afresh each time', () => {
    const r = rig()
    for (let i = 0; i < 10; i++) {
        r.watch.update(true)
        r.advance(19_000)
        r.watch.update(false)
        r.advance(500)
    }
    assert.deepEqual(r.log, [])
})

test('repeating the same answer changes nothing', () => {
    const r = rig()
    r.watch.update(true)
    r.advance(10_000)
    r.watch.update(true)
    r.advance(10_000)
    assert.deepEqual(r.log, [true])
})

test('Retry hides it and counts 20 s again while the line is still held', () => {
    const r = rig()
    r.watch.update(true)
    r.advance(UNREACHABLE_MS)
    r.watch.restart()
    assert.deepEqual(r.log, [true, false])
    r.advance(UNREACHABLE_MS - 1)
    assert.deepEqual(r.log, [true, false])
    r.advance(1)
    assert.deepEqual(r.log, [true, false, true])
})

test('Retry with the line up leaves nothing running', () => {
    const r = rig()
    r.watch.update(true)
    r.advance(UNREACHABLE_MS)
    r.watch.update(false)
    r.watch.restart()
    assert.equal(r.pending(), 0)
    assert.deepEqual(r.log, [true, false])
})

test('a Retry pressed ten times reconnects once while it runs, and again after it ends', async () => {
    let calls = 0
    let finish!: () => void
    const go = singleFlight(() => new Promise<void>((resolve) => ((calls++, (finish = resolve)))))
    for (let i = 0; i < 10; i++) go()
    assert.equal(calls, 1)
    finish()
    await new Promise((r) => setTimeout(r, 0))
    go()
    assert.equal(calls, 2)
})

test('a reconnect that fails does not block the next', async () => {
    let calls = 0
    const go = singleFlight(() => (calls++, Promise.reject(new Error('x'))))
    go()
    await new Promise((r) => setTimeout(r, 0))
    go()
    assert.equal(calls, 2)
})
