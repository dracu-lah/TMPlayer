import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createAskedRefresh, dueOnReturn, onReturnAfterHidden, pullReleases, pullShown, HIDDEN_FOR_MS, TIME_BOX_MS, type ReturnDoc, type Timers } from './askedRefresh.ts'
import type { WorkStatus } from './workStatus.ts'

/** A clock and timers the test moves by hand. */
function fake() {
    let at = 1_000
    let next = 1
    const pending = new Map<number, { due: number; fn: () => void }>()
    const timers: Timers = {
        set: (fn, ms) => {
            const id = next++
            pending.set(id, { due: at + ms, fn })
            return id
        },
        clear: (id) => void pending.delete(id as number),
    }
    const advance = (ms: number) => {
        at += ms
        for (const [id, t] of [...pending]) {
            if (t.due <= at) {
                pending.delete(id)
                t.fn()
            }
        }
    }
    return { now: () => at, timers, advance }
}

const tick = () => new Promise<void>((r) => setImmediate(r))

test('a quick answer shows nothing, and a second press while it runs is ignored', async () => {
    const f = fake()
    const seen: WorkStatus[] = []
    let runs = 0
    let release!: () => void
    const r = createAskedRefresh({
        run: () => (runs++, new Promise<void>((res) => (release = res))),
        set: (s) => seen.push(s),
        now: f.now,
        timers: f.timers,
    })
    assert.equal(r.ask(), true)
    assert.equal(r.ask(), false)
    assert.equal(r.ask(), false)
    assert.equal(runs, 1)
    assert.equal(r.running(), true)
    f.advance(300)
    release()
    await tick()
    assert.equal(r.running(), false)
    assert.deepEqual(seen, [])
    assert.equal(r.ask(), true)
    assert.equal(runs, 2)
})

test('a slow answer says Updating after 400 ms, Up to date for 2 s, then goes', async () => {
    const f = fake()
    const seen: WorkStatus[] = []
    let release!: () => void
    const r = createAskedRefresh({ run: () => new Promise<void>((res) => (release = res)), set: (s) => seen.push(s), now: f.now, timers: f.timers })
    r.ask()
    f.advance(400)
    assert.deepEqual(seen.map((s) => s.kind), ['syncing'])
    release()
    await tick()
    assert.deepEqual(seen.map((s) => s.kind), ['syncing', 'updated'])
    f.advance(2000)
    assert.deepEqual(seen.map((s) => s.kind), ['syncing', 'updated', 'idle'])
})

test('a failure keeps the page and says when, and may be asked again', async () => {
    const f = fake()
    const seen: WorkStatus[] = []
    const r = createAskedRefresh({ run: () => Promise.reject(new Error('x')), set: (s) => seen.push(s), now: f.now, timers: f.timers })
    r.ask()
    await tick()
    assert.deepEqual(seen, [{ kind: 'failed', message: '', keptAt: 1000 }])
    assert.equal(r.running(), false)
    assert.equal(r.ask(), true)
})

test('a refresh that never answers ends at its time box', () => {
    const f = fake()
    const seen: WorkStatus[] = []
    const r = createAskedRefresh({ run: () => new Promise(() => {}), set: (s) => seen.push(s), now: f.now, timers: f.timers })
    r.ask()
    f.advance(TIME_BOX_MS)
    assert.equal(r.running(), false)
    assert.equal(seen.at(-1)?.kind, 'failed')
})

test('a quiet reload (the page coming back) says nothing unless it fails', async () => {
    const f = fake()
    const seen: WorkStatus[] = []
    let release!: () => void
    const r = createAskedRefresh({ run: () => new Promise<void>((res) => (release = res)), set: (s) => seen.push(s), now: f.now, timers: f.timers })
    r.ask(true)
    f.advance(5000)
    release()
    await tick()
    assert.deepEqual(seen, [])
})

test('coming back is due after 5 minutes hidden, not before', () => {
    assert.equal(dueOnReturn(1000, 1000 + HIDDEN_FOR_MS - 1), false)
    assert.equal(dueOnReturn(1000, 1000 + HIDDEN_FOR_MS), true)
    assert.equal(dueOnReturn(null, 1e12), false)
    assert.equal(dueOnReturn(5000, 1000), false)
})

test('a simulated 6 minute hide reloads once on return; a 1 minute hide does not', () => {
    let at = 0
    let state = 'visible'
    const listeners: Array<() => void> = []
    const doc: ReturnDoc = {
        get visibilityState() {
            return state
        },
        addEventListener: (_t, fn) => void listeners.push(fn),
        removeEventListener: (_t, fn) => void listeners.splice(listeners.indexOf(fn), 1),
    }
    let calls = 0
    const stop = onReturnAfterHidden(() => calls++, doc, () => at)
    const flip = (to: string) => {
        state = to
        listeners.forEach((l) => l())
    }
    flip('hidden')
    at += 60_000
    flip('visible')
    assert.equal(calls, 0)
    flip('hidden')
    at += 6 * 60_000
    flip('visible')
    assert.equal(calls, 1)
    flip('visible')
    assert.equal(calls, 1)
    stop()
    assert.equal(listeners.length, 0)
})

test('a pull follows at half, is capped, and refreshes only past the trigger', () => {
    assert.equal(pullShown(-10), 0)
    assert.equal(pullShown(60), 30)
    assert.equal(pullShown(1000), 96)
    assert.equal(pullReleases(71), false)
    assert.equal(pullReleases(72), true)
})
